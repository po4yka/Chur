//! Source-side collection publication and bounded ciphertext reads.

use std::fs::File;
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
const RANGE_MAX: u32 = 1_048_576;
const SNAPSHOT_MAX: usize = 8 * 1_024;

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
        let Entry::Session { session, .. } = entry.as_ref() else {
            return Err(Error::new(
                ChurStatus::InvalidInput,
                "handle is not a session",
            ));
        };
        let encoded = {
            let session = registry::lock(session);
            sharing_publication::ensure_ready(
                session.catalog_ref()?,
                session.root_secret()?,
                session.vault_id(),
                collection_id,
            )?;
            let candidates =
                sharing_publication::candidates(session.catalog_ref()?, &collection_id, &after)?;
            let hashes = candidates
                .iter()
                .map(|item| full_sha256(&session, item))
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
                full_sha256(&session, &item)? == expected_sha256,
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
        ensure!(
            max_bytes != 0 && max_bytes <= RANGE_MAX,
            InvalidInput,
            "ciphertext range exceeds the 1 MiB bound"
        );
        let object_id = Id::from_slice(unsafe { borrow_bytes(object_id, 16)? })?;
        let entry = registry::get(session, Kind::Session)?;
        let Entry::Session { session, .. } = entry.as_ref() else {
            return Err(Error::new(
                ChurStatus::InvalidInput,
                "handle is not a session",
            ));
        };
        let bytes = {
            let session = registry::lock(session);
            let object = chur_catalog::store::object(session.catalog_ref()?, &object_id)?;
            let item = sharing_publication::candidate(
                session.catalog_ref()?,
                &object.collection_id,
                &object_id,
            )?;
            ensure!(
                offset <= item.container_length,
                InvalidInput,
                "ciphertext offset is past the container"
            );
            // The snapshot verifies all chunks. Upload checks its full SHA-256,
            // and the recipient verifies the final committed container.
            let _reader = authenticated_container(&session, &item)?;
            let mut file = chur_media::store::ContainerFile::open(
                session.root_dir(),
                &session.object_store_id(),
                &item.store_id,
            )?;
            ensure!(
                file.length() == item.container_length,
                ObjectCorrupt,
                "container length contradicts the committed catalog row"
            );
            let take = u64::from(max_bytes).min(item.container_length - offset);
            file.read_at(
                offset,
                usize::try_from(take).map_err(|_| {
                    Error::new(ChurStatus::ResourceLimitExceeded, "range exceeds usize")
                })?,
            )?
        };
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

fn full_sha256(session: &chur_catalog::vault::Session, item: &Candidate) -> Result<[u8; 32]> {
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
