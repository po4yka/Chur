//! Source-side collection publication and bounded ciphertext reads.

use std::collections::BTreeMap;
use std::fs::{File, Metadata};
use std::io::Read;

use chur_catalog::sharing_publication::{self, Candidate};
use chur_core::{ChurStatus, Error, Id, Result, ensure};
use chur_format::codec::Writer;
use chur_format::constants::StreamKind;
use chur_format::container::{StreamIdentity, StreamReader};
use sha2::{Digest, Sha256};

use crate::api::{Status, borrow_bytes, borrow_bytes_mut, write_out};
use crate::panic::guard_status_for;
use crate::registry::{self, Entry, Handle, Kind};

const RECORD_VERSION: u16 = 1;
pub(crate) const RANGE_MAX: u32 = 1_048_576;
const SNAPSHOT_MAX: usize = 8 * 1_024;

const VERIFICATION_CACHE_MAX: usize = 1_024;

/// Public ciphertext verification results owned by one unlocked session.
#[doc(hidden)]
pub struct VerificationCache {
    limit: usize,
    entries: BTreeMap<Id, VerifiedContainer>,
}

impl Default for VerificationCache {
    fn default() -> Self {
        Self {
            limit: VERIFICATION_CACHE_MAX,
            entries: BTreeMap::new(),
        }
    }
}

impl VerificationCache {
    pub(crate) fn clear(&mut self) {
        self.entries.clear();
    }
}

/// Uses only this session handle's public verification cache. The caller holds
/// the session lock; this helper never acquires it a second time.
pub(crate) fn cached_sha256(
    owner: Handle,
    session: &chur_catalog::vault::Session,
    item: &Candidate,
) -> Result<[u8; 32]> {
    let entry = registry::get(owner, Kind::Session)?;
    let Entry::Session {
        publication_cache, ..
    } = entry.as_ref()
    else {
        return Err(Error::new(
            ChurStatus::InvalidInput,
            "handle is not a session",
        ));
    };
    hash_with_cache(&mut registry::lock(publication_cache), session, item)
}

#[derive(Clone, Copy, PartialEq, Eq)]
struct ContainerIdentity {
    object_store_id: Id,
    collection_id: Id,
    object_id: Id,
    object_generation: u64,
    stream_id: Id,
    stream_revision: u32,
    store_id: Id,
    container_length: u64,
    container_commitment: [u8; 32],
    key_commitment: [u8; 32],
}

impl ContainerIdentity {
    fn new(
        object_store_id: Id,
        collection_id: Id,
        item: &Candidate,
        key_commitment: [u8; 32],
    ) -> Self {
        Self {
            object_store_id,
            collection_id,
            object_id: item.object_id,
            object_generation: item.object_generation,
            stream_id: item.stream_id,
            stream_revision: item.stream_revision,
            store_id: item.store_id,
            container_length: item.container_length,
            container_commitment: item.container_commitment,
            key_commitment,
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
struct FileStamp {
    device: u64,
    inode: u64,
    length: u64,
    modified_and_changed: [i64; 4],
}

fn file_stamp(metadata: &Metadata) -> Option<FileStamp> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        Some(FileStamp {
            device: metadata.dev(),
            inode: metadata.ino(),
            length: metadata.len(),
            modified_and_changed: [
                metadata.mtime(),
                metadata.mtime_nsec(),
                metadata.ctime(),
                metadata.ctime_nsec(),
            ],
        })
    }
    #[cfg(not(unix))]
    {
        let _ = metadata;
        None
    }
}

struct VerifiedContainer {
    identity: ContainerIdentity,
    stamp: FileStamp,
    sha256: [u8; 32],
}

fn hash_with_cache(
    cache: &mut VerificationCache,
    session: &chur_catalog::vault::Session,
    item: &Candidate,
) -> Result<[u8; 32]> {
    let result = verified_hash(cache, session, item);
    if result.is_err() {
        cache.entries.remove(&item.object_id);
    }
    result
}

fn verified_hash(
    cache: &mut VerificationCache,
    session: &chur_catalog::vault::Session,
    item: &Candidate,
) -> Result<[u8; 32]> {
    // Recheck protected catalog state and key availability even on a cache hit.
    // A cached public digest must not bypass deletion or a changed envelope.
    let db = session.catalog_ref()?;
    let collection = chur_catalog::store::object(db, &item.object_id)?.collection_id;
    let current = sharing_publication::candidate(db, &collection, &item.object_id)?;
    let object_store = session.object_store_id();
    ensure!(
        ContainerIdentity::new(object_store, collection, item, [0; 32])
            == ContainerIdentity::new(object_store, collection, &current, [0; 32]),
        Conflict,
        "source catalog identity changed during publication"
    );
    let key = chur_media::keys::object_key(session, &item.object_id)?;
    let key_commitment =
        chur_sync_protocol::object_grant::object_key_commitment(&collection, &item.object_id, &key);
    let identity = ContainerIdentity::new(object_store, collection, item, key_commitment);
    let path = session.root_dir().container(&object_store, &item.store_id);
    let file = File::open(&path)
        .map_err(|_| Error::new(ChurStatus::NotFound, "committed container is absent"))?;
    let metadata = file.metadata().map_err(|_| {
        Error::new(
            ChurStatus::IoFailure,
            "container identity could not be read",
        )
    })?;
    ensure!(
        metadata.len() == item.container_length,
        ObjectCorrupt,
        "container length contradicts catalog"
    );
    let initial_stamp = file_stamp(&metadata);
    let hit = initial_stamp.and_then(|stamp| {
        cache
            .entries
            .get(&item.object_id)
            .filter(|entry| entry.identity == identity && entry.stamp == stamp)
            .map(|entry| entry.sha256)
    });
    let sha256 = if let Some(digest) = hit {
        digest
    } else {
        cache.entries.remove(&item.object_id);
        full_sha256(session, item)?
    };
    if let Some(stamp) = initial_stamp {
        let final_metadata = file.metadata().map_err(|_| {
            Error::new(
                ChurStatus::IoFailure,
                "container identity could not be read",
            )
        })?;
        let path_metadata = std::fs::metadata(&path)
            .map_err(|_| Error::new(ChurStatus::NotFound, "committed container is absent"))?;
        ensure!(
            file_stamp(&final_metadata) == Some(stamp) && file_stamp(&path_metadata) == Some(stamp),
            ObjectCorrupt,
            "container changed during publication"
        );
        // Stable admission avoids a sequential library scan evicting every
        // prior result when the library is larger than the bounded cache.
        if hit.is_none() && cache.entries.len() < cache.limit {
            cache.entries.insert(
                item.object_id,
                VerifiedContainer {
                    identity,
                    stamp,
                    sha256,
                },
            );
        }
    }
    Ok(sha256)
}

#[cfg(test)]
std::thread_local! {
    static PUBLICATION_IO: std::cell::Cell<(u64, u64)> = const { std::cell::Cell::new((0, 0)) };
}

/// Returns the next 64 active source objects after `after_object_id`.
///
/// The all-zero cursor starts from the first object. This only lists and
/// verifies ciphertext; no collection operation is authored before upload.
///
/// # Safety
///
/// Input IDs each cover 16 readable bytes. `destination` covers `capacity`
/// writable bytes and `bytes_written` points to a writable `size_t`.
#[unsafe(no_mangle)]
#[expect(unsafe_code, reason = "the C ABI requires an exported symbol")]
pub unsafe extern "C" fn chur_sharing_publication(
    session: Handle,
    collection_id: *const u8,
    after_object_id: *const u8,
    destination: *mut u8,
    capacity: usize,
    bytes_written: *mut usize,
) -> Status {
    guard_status_for(session, || {
        // SAFETY: the caller guarantees these ranges and out-parameter.
        unsafe { write_out(bytes_written, 0usize)? };
        let collection_id = Id::from_slice(unsafe { borrow_bytes(collection_id, 16)? })?;
        let after: [u8; 16] = unsafe { borrow_bytes(after_object_id, 16)? }
            .try_into()
            .map_err(|_| Error::new(ChurStatus::InvalidInput, "cursor is not 16 bytes"))?;
        let entry = registry::get(session, Kind::Session)?;
        let owner = session;
        let Entry::Session { session, .. } = entry.as_ref() else {
            return Err(Error::new(
                ChurStatus::InvalidInput,
                "handle is not a session",
            ));
        };
        let encoded = {
            let session = registry::lock(session);
            if chur_catalog::sharing::load(session.catalog_ref()?, &collection_id)?.is_some() {
                sharing_publication::ensure_ready(
                    session.catalog_ref()?,
                    session.root_secret()?,
                    session.vault_id(),
                    collection_id,
                )?;
            } else {
                ensure!(
                    chur_catalog::own_sync::is_local_collection(
                        session.catalog_ref()?,
                        &collection_id
                    )?,
                    AuthenticationFailed,
                    "source collection does not belong to this vault"
                );
            }
            let candidates =
                sharing_publication::candidates(session.catalog_ref()?, &collection_id, &after)?;
            let hashes = candidates
                .iter()
                .map(|item| cached_sha256(owner, &session, item))
                .collect::<Result<Vec<_>>>()?;
            let mut writer = Writer::new();
            writer
                .u16(RECORD_VERSION)
                .u32(u32::try_from(candidates.len()).map_err(|_| {
                    Error::new(
                        ChurStatus::ResourceLimitExceeded,
                        "publication count exceeds u32",
                    )
                })?);
            for (candidate, hash) in candidates.iter().zip(hashes) {
                writer.variable(&[])?;
                writer.variable(&[])?;
                writer
                    .id(&candidate.object_id)
                    .id(&candidate.store_id)
                    .u64(candidate.container_length)
                    .fixed(&hash);
                ensure!(
                    writer.len() <= SNAPSHOT_MAX,
                    ResourceLimitExceeded,
                    "publication page exceeds the output bound"
                );
            }
            writer.finish()
        };
        // SAFETY: the caller guarantees the writable output range.
        let buffer = unsafe { borrow_bytes_mut(destination, capacity)? };
        ensure!(
            encoded.len() <= buffer.len(),
            ResourceLimitExceeded,
            "destination is smaller than publication page"
        );
        buffer[..encoded.len()].copy_from_slice(&encoded);
        // SAFETY: the caller guarantees the writable out-parameter.
        unsafe { write_out(bytes_written, encoded.len()) }
    })
}

/// Authors one object's stable signed records after its ciphertext is uploaded.
///
/// The expected source identity and digest bind this call to the earlier
/// snapshot. A retry returns the same durable CreateObject/CommitObject bytes.
///
/// # Safety
///
/// Each ID covers 16 readable bytes, `expected_sha256` covers 32 readable
/// bytes, and the destination and output cover their declared lengths.
#[unsafe(no_mangle)]
#[expect(unsafe_code, reason = "the C ABI requires an exported symbol")]
pub unsafe extern "C" fn chur_sharing_author(
    session: Handle,
    collection_id: *const u8,
    object_id: *const u8,
    store_id: *const u8,
    expected_length: u64,
    expected_sha256: *const u8,
    destination: *mut u8,
    capacity: usize,
    bytes_written: *mut usize,
) -> Status {
    guard_status_for(session, || {
        // SAFETY: the caller guarantees fixed input ranges and output storage.
        unsafe { write_out(bytes_written, 0usize)? };
        let collection_id = Id::from_slice(unsafe { borrow_bytes(collection_id, 16)? })?;
        let object_id = Id::from_slice(unsafe { borrow_bytes(object_id, 16)? })?;
        let store_id = Id::from_slice(unsafe { borrow_bytes(store_id, 16)? })?;
        let expected_sha256: [u8; 32] = unsafe { borrow_bytes(expected_sha256, 32)? }
            .try_into()
            .map_err(|_| Error::new(ChurStatus::InvalidInput, "digest is not 32 bytes"))?;
        let entry = registry::get(session, Kind::Session)?;
        let owner = session;
        let Entry::Session { session, .. } = entry.as_ref() else {
            return Err(Error::new(
                ChurStatus::InvalidInput,
                "handle is not a session",
            ));
        };
        let encoded = {
            let mut session = registry::lock(session);
            let item =
                sharing_publication::candidate(session.catalog_ref()?, &collection_id, &object_id)?;
            ensure!(
                item.store_id == store_id && item.container_length == expected_length,
                Conflict,
                "source object changed after upload"
            );
            ensure!(
                cached_sha256(owner, &session, &item)? == expected_sha256,
                Conflict,
                "source ciphertext changed after upload"
            );
            let root = session.root_secret()?.duplicate();
            let source_vault_id = session.vault_id();
            let record = sharing_publication::publish_page(
                session.catalog()?,
                &root,
                source_vault_id,
                collection_id,
                &[item],
            )?
            .pop()
            .ok_or_else(|| Error::new(ChurStatus::InternalFailure, "publication was empty"))?;
            let mut writer = Writer::new();
            writer.u16(RECORD_VERSION).u32(1);
            writer.variable(&record.create_operation.encode())?;
            writer.variable(&record.commit_operation.encode())?;
            writer
                .id(&record.candidate.object_id)
                .id(&record.candidate.store_id)
                .u64(record.candidate.container_length)
                .fixed(&expected_sha256);
            writer.finish()
        };
        // SAFETY: the caller guarantees the writable output range.
        let buffer = unsafe { borrow_bytes_mut(destination, capacity)? };
        ensure!(
            encoded.len() <= buffer.len(),
            ResourceLimitExceeded,
            "destination is smaller than publication record"
        );
        buffer[..encoded.len()].copy_from_slice(&encoded);
        // SAFETY: the caller guarantees the writable out-parameter.
        unsafe { write_out(bytes_written, encoded.len()) }
    })
}

/// Reads at most 1 MiB from one active object's committed original ciphertext.
///
/// `range_sha256` is SHA-256 of exactly the returned bytes.
///
/// # Safety
///
/// `object_id` covers 16 readable bytes; `destination` covers `capacity`
/// writable bytes; `bytes_written` and `range_sha256` are writable outputs.
#[unsafe(no_mangle)]
#[expect(unsafe_code, reason = "the C ABI requires an exported symbol")]
pub unsafe extern "C" fn chur_sharing_object_read(
    session: Handle,
    object_id: *const u8,
    offset: u64,
    max_bytes: u32,
    destination: *mut u8,
    capacity: usize,
    bytes_written: *mut usize,
    range_sha256: *mut u8,
) -> Status {
    guard_status_for(session, || {
        // SAFETY: the caller guarantees these ranges and out-parameters.
        unsafe { write_out(bytes_written, 0usize)? };
        let object_id = Id::from_slice(unsafe { borrow_bytes(object_id, 16)? })?;
        let entry = registry::get(session, Kind::Session)?;
        let Entry::Session { session, .. } = entry.as_ref() else {
            return Err(Error::new(
                ChurStatus::InvalidInput,
                "handle is not a session",
            ));
        };
        let bytes = object_range(&registry::lock(session), &object_id, offset, max_bytes)?;
        // SAFETY: the caller guarantees the writable output ranges.
        let buffer = unsafe { borrow_bytes_mut(destination, capacity)? };
        ensure!(
            bytes.len() <= buffer.len(),
            ResourceLimitExceeded,
            "destination is smaller than ciphertext range"
        );
        let digest = Sha256::digest(&bytes);
        let hash = unsafe { borrow_bytes_mut(range_sha256, 32)? };
        buffer[..bytes.len()].copy_from_slice(&bytes);
        hash.copy_from_slice(&digest);
        // SAFETY: the caller guarantees the writable out-parameter.
        unsafe { write_out(bytes_written, bytes.len()) }
    })
}

pub(crate) fn full_sha256(
    session: &chur_catalog::vault::Session,
    item: &Candidate,
) -> Result<[u8; 32]> {
    #[cfg(test)]
    PUBLICATION_IO.with(|counter| {
        let (scans, bytes) = counter.get();
        counter.set((scans + 1, bytes));
    });
    let mut verifier = authenticated_container(session, item)?;
    verifier.verify_complete()?;
    let path = session
        .root_dir()
        .container(&session.object_store_id(), &item.store_id);
    let mut file = File::open(path)
        .map_err(|_| Error::new(ChurStatus::NotFound, "committed container is absent"))?;
    ensure!(
        file.metadata()
            .map_err(|_| Error::new(ChurStatus::IoFailure, "container length could not be read"))?
            .len()
            == item.container_length,
        ObjectCorrupt,
        "container length contradicts catalog"
    );
    let mut digest = Sha256::new();
    let mut buffer = [0u8; 262_144];
    let mut total = 0u64;
    loop {
        let n = file
            .read(&mut buffer)
            .map_err(|_| Error::new(ChurStatus::IoFailure, "container could not be read"))?;
        if n == 0 {
            break;
        }
        #[cfg(test)]
        PUBLICATION_IO.with(|counter| {
            let (scans, bytes) = counter.get();
            counter.set((scans, bytes + n as u64));
        });
        digest.update(&buffer[..n]);
        total += n as u64;
    }
    ensure!(
        total == item.container_length,
        ObjectCorrupt,
        "container changed during publication"
    );
    Ok(digest.finalize().into())
}

/// Reads at most 1 MiB from one active object's committed original ciphertext.
pub(crate) fn object_range(
    session: &chur_catalog::vault::Session,
    object_id: &Id,
    offset: u64,
    max_bytes: u32,
) -> Result<Vec<u8>> {
    ensure!(
        max_bytes != 0 && max_bytes <= RANGE_MAX,
        InvalidInput,
        "ciphertext range exceeds the 1 MiB bound"
    );
    let object = chur_catalog::store::object(session.catalog_ref()?, object_id)?;
    let item =
        sharing_publication::candidate(session.catalog_ref()?, &object.collection_id, object_id)?;
    ensure!(
        offset <= item.container_length,
        InvalidInput,
        "ciphertext offset is past the container"
    );
    // Opening authenticates the manifest and the final commit, which reads only the
    // container head and its fixed-size tail. The range is read from that same
    // handle. The recipient verifies every chunk of the final committed container.
    let mut file = authenticated_container(session, &item)?.into_source();
    let take = u64::from(max_bytes).min(item.container_length - offset);
    file.read_at(
        offset,
        usize::try_from(take)
            .map_err(|_| Error::new(ChurStatus::ResourceLimitExceeded, "range exceeds usize"))?,
    )
}

fn authenticated_container(
    session: &chur_catalog::vault::Session,
    item: &Candidate,
) -> Result<StreamReader<chur_media::store::ContainerFile>> {
    let key = chur_media::keys::object_key(session, &item.object_id)?;
    let file = chur_media::store::ContainerFile::open(
        session.root_dir(),
        &session.object_store_id(),
        &item.store_id,
    )?;
    ensure!(
        file.length() == item.container_length,
        ObjectCorrupt,
        "container length contradicts the committed catalog row"
    );
    let identity = StreamIdentity {
        object_id: item.object_id,
        stream_id: item.stream_id,
        stream_kind: StreamKind::Original,
        stream_revision: item.stream_revision,
    };
    let mut reader = StreamReader::open(file, &key, &identity)?;
    ensure!(
        reader.read_final_commit()?.ordered_chunk_commitment() == &item.container_commitment,
        ObjectCorrupt,
        "container final commitment contradicts the catalog"
    );
    Ok(reader)
}

#[cfg(all(test, unix))]
mod tests {
    #![allow(clippy::expect_used, clippy::unwrap_used)]
    use super::*;
    struct Directory(std::path::PathBuf);
    impl Drop for Directory {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }
    struct Fixture {
        session: chur_catalog::vault::Session,
        _directory: Directory,
        items: Vec<Candidate>,
    }
    fn fixture(count: usize) -> Fixture {
        let directory = Directory(std::env::temp_dir().join(format!(
            "chur-publication-cache-{}",
            chur_crypto::random::id().unwrap().to_hex()
        )));
        let root = chur_catalog::paths::VaultRoot::new(&directory.0);
        let mut session =
            chur_catalog::vault::create(&root, b"strong publication cache password", 1)
                .unwrap()
                .activate()
                .unwrap();
        let mut items = Vec::new();
        for _ in 0..count {
            let plaintext = zeroize::Zeroizing::new(vec![42u8; 65536]);
            let object = chur_media::import::import_bytes(
                &mut session,
                chur_media::import::SourceCapability {
                    requested_object_id: None,
                    seekable: true,
                    known_length: Some(plaintext.len() as u64),
                    content_type_hint: "image/jpeg".into(),
                    original_filename: Some("photo.jpg".into()),
                    capture_time_ms: None,
                },
                chur_media::import::CanonicalMedia {
                    media_class: chur_format::constants::MediaClass::Image,
                    width: 64,
                    height: 64,
                    duration_ms: 0,
                },
                "image/jpeg",
                &plaintext,
                1,
            )
            .unwrap();
            let collection = chur_catalog::store::object(session.catalog_ref().unwrap(), &object)
                .unwrap()
                .collection_id;
            items.push(
                sharing_publication::candidate(
                    session.catalog_ref().unwrap(),
                    &collection,
                    &object,
                )
                .unwrap(),
            );
        }
        Fixture {
            session,
            _directory: directory,
            items,
        }
    }
    #[test]
    fn an_unchanged_original_is_fully_verified_and_read_only_once_per_session() {
        let f = fixture(1);
        let mut cache = VerificationCache::default();
        PUBLICATION_IO.with(|counter| counter.set((0, 0)));
        let first = hash_with_cache(&mut cache, &f.session, &f.items[0]).unwrap();
        assert_eq!(
            PUBLICATION_IO.with(std::cell::Cell::get),
            (1, f.items[0].container_length)
        );
        assert_eq!(
            hash_with_cache(&mut cache, &f.session, &f.items[0]).unwrap(),
            first
        );
        assert_eq!(
            PUBLICATION_IO.with(std::cell::Cell::get),
            (1, f.items[0].container_length),
            "second publication reads no ciphertext and does not rerun full MAC verification"
        );
    }
    #[test]
    fn an_erased_objects_cached_digest_cannot_bypass_current_catalog_state() {
        let mut f = fixture(1);
        let item = f.items[0];
        let mut cache = VerificationCache::default();
        hash_with_cache(&mut cache, &f.session, &item).unwrap();
        assert!(cache.entries.contains_key(&item.object_id));
        chur_catalog::deletion::begin(f.session.catalog().unwrap(), &item.object_id).unwrap();
        chur_catalog::deletion::erase(f.session.catalog().unwrap(), &item.object_id, 2).unwrap();
        assert!(chur_media::keys::object_key(&f.session, &item.object_id).is_err());
        assert!(hash_with_cache(&mut cache, &f.session, &item).is_err());
        assert!(!cache.entries.contains_key(&item.object_id));
    }

    #[test]
    fn same_length_mutation_with_restored_mtime_invalidates_verification() {
        use std::io::{Seek, SeekFrom, Write};
        let f = fixture(1);
        let item = &f.items[0];
        let mut cache = VerificationCache::default();
        hash_with_cache(&mut cache, &f.session, item).unwrap();
        let path = f
            .session
            .root_dir()
            .container(&f.session.object_store_id(), &item.store_id);
        let mut file = File::options().read(true).write(true).open(path).unwrap();
        let before = file.metadata().unwrap();
        file.seek(SeekFrom::Start(item.container_length / 2))
            .unwrap();
        let mut byte = [0u8; 1];
        file.read_exact(&mut byte).unwrap();
        byte[0] ^= 1;
        file.seek(SeekFrom::Start(item.container_length / 2))
            .unwrap();
        file.write_all(&byte).unwrap();
        file.sync_all().unwrap();
        file.set_times(std::fs::FileTimes::new().set_modified(before.modified().unwrap()))
            .unwrap();
        assert_eq!(file.metadata().unwrap().len(), before.len());
        assert_eq!(
            file.metadata().unwrap().modified().unwrap(),
            before.modified().unwrap()
        );
        assert!(
            hash_with_cache(&mut cache, &f.session, item).is_err(),
            "modified ciphertext must fail full authentication despite restored length and mtime"
        );
    }

    #[test]
    fn a_scan_larger_than_the_cache_keeps_hits_instead_of_evicting_the_entire_previous_scan() {
        let f = fixture(3);
        let mut cache = VerificationCache {
            limit: 2,
            ..VerificationCache::default()
        };
        PUBLICATION_IO.with(|counter| counter.set((0, 0)));
        for item in &f.items {
            hash_with_cache(&mut cache, &f.session, item).unwrap();
        }
        let first_scan = PUBLICATION_IO.with(std::cell::Cell::get);
        assert_eq!(first_scan.0, 3);
        for item in &f.items {
            hash_with_cache(&mut cache, &f.session, item).unwrap();
        }
        assert_eq!(
            PUBLICATION_IO.with(std::cell::Cell::get),
            (4, first_scan.1 + f.items[2].container_length),
            "second sequential scan rereads only the original outside the admission bound"
        );
        cache.clear();
        hash_with_cache(&mut cache, &f.session, &f.items[0]).unwrap();
        assert_eq!(
            PUBLICATION_IO.with(std::cell::Cell::get).0,
            5,
            "session lock clears prior verification results"
        );
    }
}
