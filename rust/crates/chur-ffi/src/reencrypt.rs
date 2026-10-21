//! Re-encryption of objects whose key a revoked member may hold, ADR-0069.
//!
//! A rotation rewraps each object key under the new epoch and keeps the key.
//! A member removed by the rotation that already opened an object therefore
//! still opens every container of it. This module replaces each such object
//! with a copy under a fresh object key: the original is written again through
//! the import writer, so the journal of ADR-0012 and the padding of ADR-0066
//! apply, and the copy activates, with its display assets, in the catalog
//! transaction that starts the deletion of the old object. The deletion sweep
//! then destroys the old envelope, truncates the WAL, and unlinks the old
//! containers.
//!
//! The work is an operation that the host starts after unlock, in the
//! foreground, and that a lock cancels. It never runs inside an unlock. It
//! holds the session only for one chunk or one catalog step at a time, so host
//! calls proceed while a large object is copied, and it observes cancellation
//! between chunks. The candidates come from catalog state, not from a cursor,
//! so the next run resumes after a cancellation or a crash. A cancelled copy
//! restarts from its first chunk under new key material, because a dead
//! import is never resumed; the smallest objects come first, so a large
//! object that needs an uninterrupted session does not hold back the rest.
//!
//! An object that fails is not retried by this process until its backoff
//! ends, so it does not block the others. An original that does not
//! authenticate is marked corrupt, which is durable and which the host shows.

use std::collections::{BTreeMap, BTreeSet};
use std::sync::Mutex;

use chur_catalog::{store, sync_rotation, vault::Session};
use chur_core::{ChurStatus, Error, Id, Result, ensure};
use chur_format::constants::{ObjectState, StreamKind};
use chur_media::{derived, import, reader};

use crate::api::Status;
use crate::operation::{Operation, OperationKind, Shared, Stage};
use crate::panic::guard_status_for;
use crate::registry::{self, Entry, Handle, Kind};

/// The largest display asset that is copied. A larger one is not copied, and
/// the host shows the original in its place, as it does for any object
/// without a display asset.
const DERIVED_ASSET_MAX: u64 = 32 * 1024 * 1024;

const DERIVED_KINDS: [StreamKind; 7] = [
    StreamKind::ThumbnailSmall,
    StreamKind::GridPreview,
    StreamKind::ScreenPreview,
    StreamKind::VideoPoster,
    StreamKind::AudioWaveform,
    StreamKind::CompatibleVideo,
    StreamKind::VideoStoryboard,
];

/// The first backoff after a failure. Each further failure doubles it.
const BACKOFF_FIRST_MS: u64 = 60_000;

/// The longest backoff, one day.
const BACKOFF_MAX_MS: u64 = 86_400_000;

/// Failures of this process, per object: the count and the earliest time of
/// the next attempt. Object identifiers are random, so one map serves every
/// identity a process opens.
static FAILURES: Mutex<BTreeMap<Id, (u32, u64)>> = Mutex::new(BTreeMap::new());

/// The delay before an object that failed `count` times is tried again.
#[must_use]
const fn backoff_ms(count: u32) -> u64 {
    let shift = if count == 0 { 0 } else { count - 1 };
    if shift >= 11 {
        return BACKOFF_MAX_MS;
    }
    let delay = BACKOFF_FIRST_MS << shift;
    if delay > BACKOFF_MAX_MS {
        BACKOFF_MAX_MS
    } else {
        delay
    }
}

fn record_failure(object_id: &Id, now_ms: u64) {
    let mut failures = registry::lock(&FAILURES);
    let entry = failures.entry(*object_id).or_insert((0, 0));
    entry.0 = entry.0.saturating_add(1);
    entry.1 = now_ms.saturating_add(backoff_ms(entry.0));
}

fn clear_failure(object_id: &Id) {
    registry::lock(&FAILURES).remove(object_id);
}

fn backing_off(object_id: &Id, now_ms: u64) -> bool {
    registry::lock(&FAILURES)
        .get(object_id)
        .is_some_and(|(_, retry_at)| *retry_at > now_ms)
}

/// The objects still to re-encrypt, and the objects that cannot be
/// re-encrypted now: an original that does not authenticate, or an object
/// whose last attempt in this process failed.
pub(crate) fn counts(session: &Session) -> Result<(u64, u64)> {
    let root = session.root_secret()?.duplicate();
    let db = session.catalog_ref()?;
    let (pending, corrupt) = sync_rotation::reencryption_counts(db, &root)?;
    let candidates = sync_rotation::reencryption_candidates(db, &root)?;
    let failures = registry::lock(&FAILURES);
    let failing = candidates
        .iter()
        .filter(|id| failures.contains_key(id))
        .count();
    let failing = u64::try_from(failing)
        .map_err(|_| Error::new(ChurStatus::InternalFailure, "the count is too large"))?;
    Ok((pending, corrupt.saturating_add(failing)))
}

/// Runs one pass over the candidates.
///
/// It returns `CANCELLED` when the operation is cancelled, and success
/// otherwise, also when some objects failed: [`counts`] reports them.
pub(crate) fn run(
    guarded: &Mutex<Session>,
    now_ms: &dyn Fn() -> u64,
    shared: &Shared,
) -> Result<Option<Id>> {
    shared.advance(0, Stage::Running);
    run_with(
        guarded,
        now_ms,
        &|| shared.cancelled(),
        &mut |processed, total| {
            shared.set_total(total);
            shared.advance(processed, Stage::Running);
        },
    )?;
    Ok(None)
}

fn run_with(
    guarded: &Mutex<Session>,
    now_ms: &dyn Fn() -> u64,
    cancelled: &dyn Fn() -> bool,
    report: &mut dyn FnMut(u64, u64),
) -> Result<()> {
    let candidates: Vec<Id> = {
        let session = registry::lock(guarded);
        let root = session.root_secret()?.duplicate();
        sync_rotation::reencryption_candidates(session.catalog_ref()?, &root)?
    }
    .into_iter()
    .filter(|id| !backing_off(id, now_ms()))
    .collect();
    let total = candidates.len() as u64;
    report(0, total);
    for (index, retired) in candidates.iter().enumerate() {
        ensure!(!cancelled(), Cancelled, "re-encryption was cancelled");
        match replace(guarded, retired, now_ms(), cancelled) {
            Ok(_) => {
                clear_failure(retired);
                let mut session = registry::lock(guarded);
                crate::product::finish_pending_deletions(&mut session, now_ms())?;
            }
            // A lock cancels the operation before it closes the session; a
            // session that is already locked or expired ends the pass the
            // same way, and no object is charged with a failure.
            Err(error)
                if matches!(
                    error.status(),
                    ChurStatus::Cancelled | ChurStatus::VaultLocked | ChurStatus::SessionExpired
                ) =>
            {
                return Err(Error::new(
                    ChurStatus::Cancelled,
                    "re-encryption ended with the session",
                ));
            }
            Err(error) => {
                if matches!(
                    error.status(),
                    ChurStatus::ObjectCorrupt | ChurStatus::ObjectIncomplete
                ) {
                    // The original does not authenticate, so no copy can be
                    // made. The corrupt state is durable and the host shows it.
                    let mut session = registry::lock(guarded);
                    let _ = store::mark_corrupt(session.catalog()?, retired);
                }
                record_failure(retired, now_ms());
            }
        }
        report(index as u64 + 1, total);
    }
    Ok(())
}

/// Copies one object under a fresh key and commits the copy, with its
/// display assets, together with the start of the old object's deletion. It
/// does not run the deletion.
///
/// The session is locked for one chunk or one catalog step at a time, and
/// `cancelled` is observed between chunks. A cancellation or an error leaves
/// the old object as it was and no copy behind.
pub(crate) fn replace(
    guarded: &Mutex<Session>,
    retired: &Id,
    now_ms: u64,
    cancelled: &dyn Fn() -> bool,
) -> Result<Id> {
    replace_bounded(guarded, retired, now_ms, cancelled, DERIVED_ASSET_MAX)
}

fn replace_bounded(
    guarded: &Mutex<Session>,
    retired: &Id,
    now_ms: u64,
    cancelled: &dyn Fn() -> bool,
    asset_max: u64,
) -> Result<Id> {
    let (object, metadata, mut source, mut assets, mut writing) = {
        let mut session = registry::lock(guarded);
        let db = session.catalog_ref()?;
        let object = store::object(db, retired)?;
        ensure!(
            matches!(object.state, ObjectState::Active | ObjectState::Trashed),
            NotFound,
            "the object to re-encrypt is not active or trashed"
        );
        let metadata = store::active_metadata(db, retired)?;
        let streams = store::streams(db, retired)?;
        // Opening a reader authenticates the manifest and the final commit.
        let source = reader::open(&session, retired, StreamKind::Original)?;
        let mut assets = Vec::new();
        let mut kinds = BTreeSet::new();
        for stream in &streams {
            let kind = stream.stream_kind;
            if !DERIVED_KINDS.contains(&kind) || !kinds.insert(kind.value()) {
                continue;
            }
            // A display asset that cannot be read, or that is above the
            // bound, is not copied; it does not fail the object.
            if let Ok(asset) = reader::open(&session, retired, kind)
                && asset.size() <= asset_max
            {
                assets.push((kind, asset));
            }
        }
        let writing = import::begin(
            &mut session,
            import::SourceCapability {
                requested_object_id: None,
                seekable: true,
                known_length: Some(object.plaintext_size),
                content_type_hint: metadata.content_type.clone(),
                original_filename: metadata.original_filename.clone(),
                capture_time_ms: metadata.capture_time_ms,
            },
            import::CanonicalMedia {
                media_class: object.media_kind,
                width: object.width,
                height: object.height,
                duration_ms: object.duration_ms,
            },
            now_ms,
        )?;
        (object, metadata, source, assets, writing)
    };

    // The copy and the display assets. Each chunk is read without the
    // session and written under it.
    let mut sealed_assets = Vec::new();
    let copied = (|| {
        let mut offset = 0;
        while offset < object.plaintext_size {
            ensure!(!cancelled(), Cancelled, "re-encryption was cancelled");
            let take = u64::from(writing.chunk_size()).min(object.plaintext_size - offset);
            let bytes = source.read_range(offset, take)?;
            writing.write(&mut registry::lock(guarded), &bytes)?;
            offset += take;
        }
        writing.check_content(&metadata.content_type)?;
        loop {
            ensure!(!cancelled(), Cancelled, "re-encryption was cancelled");
            if !writing.pad_step(&mut registry::lock(guarded))? {
                break;
            }
        }
        // One display asset is in memory at a time.
        for (kind, asset) in &mut assets {
            ensure!(!cancelled(), Cancelled, "re-encryption was cancelled");
            let (width, height) = asset.dimensions();
            let Ok(bytes) = asset.read_range(0, asset.size()) else {
                continue;
            };
            let mut session = registry::lock(guarded);
            if let Ok(sealed) = derived::seal_for_import(
                &mut session,
                &writing,
                *kind,
                width,
                height,
                &bytes,
                now_ms,
            ) {
                sealed_assets.push(sealed);
            }
        }
        writing.mark_committing(&mut registry::lock(guarded))
    })();
    drop(source);
    drop(assets);
    let transaction = writing.transaction_id();
    // Only the journal records of this copy are abandoned. A host import or
    // display asset that runs on the same session between chunks keeps its
    // own live record.
    let mut own = vec![transaction];
    own.extend(
        sealed_assets
            .iter()
            .map(derived::SealedAsset::transaction_id),
    );
    let abandon = |session: &mut Session| {
        for id in &own {
            let _ = import::reconcile_one(session, id);
        }
    };
    if let Err(error) = copied {
        let mut session = registry::lock(guarded);
        let _ = writing.abandon(&mut session);
        abandon(&mut session);
        return Err(error);
    }
    // The verification of the written container needs no session.
    let sealed = match writing.seal(cancelled) {
        Ok(sealed) => sealed,
        Err(error) => {
            abandon(&mut registry::lock(guarded));
            return Err(error);
        }
    };
    let mut session = registry::lock(guarded);
    if cancelled() {
        drop(sealed);
        abandon(&mut session);
        return Err(Error::new(
            ChurStatus::Cancelled,
            "re-encryption was cancelled",
        ));
    }
    match sealed.activate(
        &mut session,
        &metadata.content_type,
        now_ms,
        import::CopiedDisplay {
            caption: metadata.caption.clone(),
            favorite: object.favorite,
            capture_time: Some((object.capture_time_ms, object.capture_time_substituted)),
        },
        Some((*retired, sealed_assets)),
    ) {
        Ok(id) => Ok(id),
        Err(error) => {
            abandon(&mut session);
            Err(error)
        }
    }
}

/// Starts one re-encryption pass, ADR-0069.
///
/// The host starts it after unlock while the application is in the
/// foreground, polls it with `chur_operation_poll`, and cancels it with
/// `chur_operation_cancel`; a lock cancels it. `processed` and `total` count
/// objects. A pass that finds no candidate ends at once with success.
///
/// # Safety
///
/// `out_operation` points to a writable handle.
#[unsafe(no_mangle)]
#[expect(
    unsafe_code,
    reason = "ADR-0016: the v1 C ABI requires an exported symbol"
)]
pub unsafe extern "C" fn chur_reencryption_begin(
    session: Handle,
    out_operation: *mut Handle,
) -> Status {
    guard_status_for(session, || {
        let entry = registry::get(session, Kind::Session)?;
        crate::api::session_of(&entry)?;
        let operation = Operation::spawn(OperationKind::Reencryption, 0, move |shared| {
            run(crate::api::session_of(&entry)?, &crate::api::now_ms, shared)
        })?;
        let handle = registry::insert(Entry::Operation {
            owner: session,
            operation,
        })?;
        // SAFETY: the caller guarantees `out_operation` is writable.
        unsafe { crate::api::write_out(out_operation, handle) }
    })
}

/// Reports the objects still to re-encrypt and the objects that cannot be
/// re-encrypted now, ADR-0069. Both are zero in an identity with no
/// completed rotation that it owns.
///
/// # Safety
///
/// `out_pending` and `out_failed` point to writable values.
#[unsafe(no_mangle)]
#[expect(
    unsafe_code,
    reason = "ADR-0016: the v1 C ABI requires an exported symbol"
)]
pub unsafe extern "C" fn chur_reencryption_counts(
    session: Handle,
    out_pending: *mut u64,
    out_failed: *mut u64,
) -> Status {
    guard_status_for(session, || {
        let entry = registry::get(session, Kind::Session)?;
        let (pending, failed) = counts(&registry::lock(crate::api::session_of(&entry)?))?;
        // SAFETY: the caller guarantees both pointers are writable.
        unsafe { crate::api::write_out(out_pending, pending)? };
        unsafe { crate::api::write_out(out_failed, failed) }
    })
}

#[cfg(test)]
mod tests {
    #![allow(clippy::expect_used, clippy::unwrap_used, clippy::panic)]

    use super::*;
    use chur_catalog::{
        own_sync, paths::VaultRoot, sharing_publication, sharing_service, sync_keys, sync_receive,
        vault,
    };
    use chur_crypto::{Key, random};
    use chur_format::constants::MediaClass;
    use chur_format::container::{StreamIdentity, StreamReader};
    use chur_sync_protocol::{
        grant::PermissionProfile, identity::DeviceIdentity, membership::EnrollmentRecord,
    };
    use std::cell::Cell;
    use zeroize::Zeroizing;

    const PASSWORD: &[u8] = b"re-encryption after revocation password";
    const NOW: u64 = 1_700_000_000_000;

    struct Directory(std::path::PathBuf);
    impl Drop for Directory {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    struct Fixture {
        session: Mutex<Session>,
        root: VaultRoot,
        _directory: Directory,
        album: Id,
        trashed: Id,
        published: Id,
        captioned: Id,
        video: Id,
    }

    fn import(session: &mut Session, bytes: &[u8]) -> Id {
        chur_media::import::import_bytes(
            session,
            import::SourceCapability {
                requested_object_id: None,
                seekable: true,
                known_length: Some(bytes.len() as u64),
                content_type_hint: "application/octet-stream".into(),
                original_filename: Some("item.bin".into()),
                capture_time_ms: Some(7),
            },
            import::CanonicalMedia {
                media_class: MediaClass::Opaque,
                width: 0,
                height: 0,
                duration_ms: 0,
            },
            "application/octet-stream",
            &Zeroizing::new(bytes.to_vec()),
            NOW,
        )
        .expect("import")
    }

    /// A shared vault with four objects, one published to the own-device log
    /// and the sharing log, one trashed, one with two metadata revisions and
    /// extracted text, whose recipient was revoked and whose rewrap completed.
    fn fixture() -> Fixture {
        let directory = Directory(
            std::env::temp_dir().join(format!("chur-reencrypt-{}", random::id().unwrap().to_hex())),
        );
        let root = VaultRoot::new(&directory.0);
        let mut session = vault::create(&root, PASSWORD, NOW)
            .unwrap()
            .activate()
            .unwrap();
        let secret = session.root_secret().unwrap().duplicate();
        let vault_id = session.vault_id();
        sync_receive::provision_local_identity(session.catalog().unwrap(), &secret, vault_id)
            .unwrap();
        let mut objects = Vec::new();
        for byte in [3u8, 1, 2, 4] {
            objects.push(import(
                &mut session,
                &vec![byte; 300_000 + usize::from(byte)],
            ));
        }
        derived::put(
            &mut session,
            &objects[0],
            StreamKind::ThumbnailSmall,
            128,
            32,
            &Zeroizing::new(vec![9, 8, 7, 6, 5]),
            NOW,
        )
        .unwrap();
        store::set_favorite(session.catalog().unwrap(), &objects[0], true, NOW).unwrap();
        let album = chur_catalog::model::Album {
            album_id: random::id().unwrap(),
            name: "Kept".into(),
            created_ms: NOW,
            revision: 1,
        };
        store::put_album(session.catalog().unwrap(), &album).unwrap();
        store::set_album_membership(
            session.catalog().unwrap(),
            &album.album_id,
            &objects[1],
            true,
            NOW,
        )
        .unwrap();
        // A second metadata revision and extracted text.
        let captioned = objects[2];
        let mut revision =
            store::active_metadata(session.catalog_ref().unwrap(), &captioned).unwrap();
        revision.revision = 2;
        revision.caption = Some("second revision".into());
        revision.content_type = "text/plain".into();
        store::activate_metadata_revision(session.catalog().unwrap(), &revision).unwrap();
        let primary = store::object(session.catalog_ref().unwrap(), &captioned)
            .unwrap()
            .primary_stream_id;
        chur_catalog::content_index::put(
            session.catalog().unwrap(),
            &captioned,
            &primary,
            1,
            chur_catalog::content_index::ENGINE_VERSION,
            "quuxextracted",
        )
        .unwrap();
        let collection = chur_media::keys::ensure_default_collection(&mut session).unwrap();
        let recipient = DeviceIdentity::from_seeds([8; 32], [9; 32]);
        let recipient_vault = random::id().unwrap();
        let recipient_device = random::id().unwrap();
        let enrollment = EnrollmentRecord::initial(
            recipient_vault,
            recipient_device,
            recipient.signing_public_key(),
            recipient.hpke_public_key(),
        )
        .unwrap()
        .sign(recipient.signing_key());
        sharing_service::prepare_share(
            session.catalog().unwrap(),
            &secret,
            vault_id,
            collection,
            &enrollment,
            PermissionProfile::Read,
            true,
        )
        .unwrap();
        // Peers learn of one object through both logs.
        let published = objects[1];
        let candidate =
            sharing_publication::candidate(session.catalog_ref().unwrap(), &collection, &published)
                .unwrap();
        own_sync::publish(
            session.catalog().unwrap(),
            &secret,
            vault_id,
            collection,
            std::slice::from_ref(&candidate),
        )
        .unwrap();
        sharing_publication::publish_page(
            session.catalog().unwrap(),
            &secret,
            vault_id,
            collection,
            &[candidate],
        )
        .unwrap();
        let trashed = objects[3];
        // A video with a saved resume position.
        let video = chur_media::import::import_bytes(
            &mut session,
            import::SourceCapability {
                requested_object_id: None,
                seekable: true,
                known_length: Some(200_000),
                content_type_hint: "video/mp4".into(),
                original_filename: Some("clip.mp4".into()),
                capture_time_ms: Some(9),
            },
            import::CanonicalMedia {
                media_class: MediaClass::Video,
                width: 640,
                height: 360,
                duration_ms: 10_000,
            },
            "video/mp4",
            &Zeroizing::new(vec![5; 200_000]),
            NOW,
        )
        .unwrap();
        chur_catalog::playback::put(session.catalog().unwrap(), &video, 4_000, 10_000).unwrap();
        chur_catalog::trash::set(session.catalog().unwrap(), &[trashed], false, NOW).unwrap();
        let revocation = sharing_service::prepare_share_revocation(
            session.catalog().unwrap(),
            &secret,
            vault_id,
            collection,
            recipient_vault,
            recipient_device,
            NOW + 1,
            64,
        )
        .unwrap();
        assert!(revocation.rotation_complete());
        Fixture {
            session: Mutex::new(session),
            root,
            _directory: directory,
            album: album.album_id,
            trashed,
            published,
            captioned,
            video,
        }
    }

    /// Locks, then runs what every unlock runs before the session becomes a handle.
    fn reopen(fixture: &mut Fixture) {
        fixture.session.get_mut().unwrap().lock().unwrap();
        let mut session = vault::unlock_with_password(&fixture.root, PASSWORD, NOW + 5).unwrap();
        import::reconcile(&mut session, NOW + 5).unwrap();
        import::sweep_orphan_temporaries(&session).unwrap();
        crate::product::sweep_expired_trash(&mut session, NOW + 5).unwrap();
        fixture.session = Mutex::new(session);
    }

    fn session(fixture: &Fixture) -> std::sync::MutexGuard<'_, Session> {
        fixture.session.lock().unwrap()
    }

    /// Every object id that is active or trashed.
    fn stored_ids(session: &Session) -> Vec<Id> {
        let mut ids = chur_catalog::transfer::all(session.catalog_ref().unwrap())
            .unwrap()
            .objects;
        ids.extend(chur_catalog::trash::pending(session.catalog_ref().unwrap(), None).unwrap());
        ids.sort();
        ids
    }

    fn contents(session: &Session) -> Vec<Vec<u8>> {
        let mut all: Vec<Vec<u8>> = stored_ids(session)
            .iter()
            .map(|id| {
                let mut reader = reader::open(session, id, StreamKind::Original).unwrap();
                reader.read_range(0, reader.size()).unwrap().to_vec()
            })
            .collect();
        all.sort();
        all
    }

    /// No stored container opens under any of `keys`, and no old container is left.
    fn old_keys_open_nothing(session: &Session, keys: &[(Id, Key, Vec<Id>)]) {
        let mut streams = 0;
        store::for_each_stream_ordered(session.catalog_ref().unwrap(), |id, stream| {
            streams += 1;
            let identity = StreamIdentity {
                object_id: *id,
                stream_id: stream.stream_id,
                stream_kind: stream.stream_kind,
                stream_revision: stream.stream_revision,
            };
            for (_, key, _) in keys {
                let file = chur_media::store::ContainerFile::open(
                    session.root_dir(),
                    &session.object_store_id(),
                    &stream.container_path_id,
                )
                .unwrap();
                assert!(StreamReader::open(file, key, &identity).is_err());
            }
            Ok(())
        })
        .unwrap();
        assert!(streams > 0);
        for (old, key, containers) in keys {
            assert!(store::object(session.catalog_ref().unwrap(), old).is_err());
            for container in containers {
                assert!(
                    chur_media::store::ContainerFile::open(
                        session.root_dir(),
                        &session.object_store_id(),
                        container,
                    )
                    .is_err()
                );
            }
            for id in stored_ids(session) {
                let fresh = chur_media::keys::object_key(session, &id).unwrap();
                assert_ne!(fresh.expose(), key.expose());
            }
        }
    }

    fn old_keys(session: &Session, ids: &[Id]) -> Vec<(Id, Key, Vec<Id>)> {
        ids.iter()
            .map(|id| {
                (
                    *id,
                    chur_media::keys::object_key(session, id).unwrap(),
                    store::streams(session.catalog_ref().unwrap(), id)
                        .unwrap()
                        .iter()
                        .map(|stream| stream.container_path_id)
                        .collect(),
                )
            })
            .collect()
    }

    fn run_to_end(fixture: &Fixture) {
        run_with(&fixture.session, &|| NOW + 10, &|| false, &mut |_, _| {}).unwrap();
    }

    #[test]
    fn a_completed_rotation_lists_every_object_held_under_an_old_key() {
        let fixture = fixture();
        let session = session(&fixture);
        let root = session.root_secret().unwrap().duplicate();
        let mut candidates =
            sync_rotation::reencryption_candidates(session.catalog_ref().unwrap(), &root).unwrap();
        candidates.sort();
        assert_eq!(candidates, stored_ids(&session));
        assert!(candidates.contains(&fixture.trashed));
        assert_eq!(counts(&session).unwrap(), (5, 0));
    }

    #[test]
    fn reencryption_replaces_every_object_carries_its_data_and_old_keys_open_nothing() {
        let fixture = fixture();
        let (before, keys, import_times) = {
            let session = session(&fixture);
            let ids = stored_ids(&session);
            let import_times: BTreeSet<u64> = ids
                .iter()
                .map(|id| {
                    store::object(session.catalog_ref().unwrap(), id)
                        .unwrap()
                        .import_time_ms
                })
                .collect();
            (contents(&session), old_keys(&session, &ids), import_times)
        };
        run_to_end(&fixture);
        let session = session(&fixture);
        let db = session.catalog_ref().unwrap();
        assert_eq!(contents(&session), before);
        old_keys_open_nothing(&session, &keys);
        assert_eq!(counts(&session).unwrap(), (0, 0));
        let ids = stored_ids(&session);
        // The import time is kept.
        let kept: BTreeSet<u64> = ids
            .iter()
            .map(|id| store::object(db, id).unwrap().import_time_ms)
            .collect();
        assert_eq!(kept, import_times);
        // Favourite, album, display asset, Trash state.
        assert_eq!(
            ids.iter()
                .filter(|id| store::object(db, id).unwrap().favorite)
                .count(),
            1
        );
        let mut album = chur_catalog::query::ObjectQuery::timeline();
        album.scope = chur_catalog::query::Scope::Album(fixture.album);
        assert_eq!(
            chur_catalog::query::page(db, &album).unwrap().objects.len(),
            1
        );
        let with_thumbnail: Vec<_> = ids
            .iter()
            .filter(|id| reader::open(&session, id, StreamKind::ThumbnailSmall).is_ok())
            .collect();
        assert_eq!(with_thumbnail.len(), 1);
        assert!(
            store::object(db, with_thumbnail[0])
                .unwrap()
                .thumbnail_ready
        );
        assert_eq!(
            ids.iter()
                .filter(|id| store::object(db, id).unwrap().state == ObjectState::Trashed)
                .count(),
            1
        );
        // The resume position of the video.
        let video = ids
            .iter()
            .find(|id| store::object(db, id).unwrap().media_kind == MediaClass::Video)
            .unwrap();
        assert_ne!(*video, fixture.video);
        assert_eq!(chur_catalog::playback::get(db, video).unwrap(), 4_000);
        // Both metadata revisions and the extracted text.
        let captioned = ids
            .iter()
            .find(|id| store::active_metadata(db, id).unwrap().caption.is_some())
            .unwrap();
        assert_eq!(
            store::object(db, captioned)
                .unwrap()
                .active_metadata_revision,
            2
        );
        let mut search = chur_catalog::query::ObjectQuery::timeline();
        search.scope = chur_catalog::query::Scope::Search;
        search.terms = Some("quuxextracted".into());
        let found = chur_catalog::query::page(db, &search).unwrap().objects;
        assert_eq!(found.len(), 1);
        assert_eq!(found[0].object_id, *captioned);
        assert_ne!(*captioned, fixture.captioned);
        // Every copy is under the current epoch at generation 1.
        for id in &ids {
            let collection = store::object(db, id).unwrap().collection_id;
            let current = store::collection(db, &collection).unwrap().current_epoch;
            let envelope = chur_format::envelope::ObjectKeyEnvelope::decode(
                &store::active_envelope(db, id).unwrap(),
            )
            .unwrap();
            assert_eq!(envelope.collection_epoch(), current);
            assert_eq!(envelope.envelope_generation(), 1);
        }
    }

    #[test]
    fn peers_receive_a_signed_purge_for_the_replaced_identifier() {
        let fixture = fixture();
        run_to_end(&fixture);
        let mut session = session(&fixture);
        let root = session.root_secret().unwrap().duplicate();
        let vault_id = session.vault_id();
        let keys =
            sync_keys::key_directory(session.catalog_ref().unwrap(), &root, vault_id).unwrap();
        let state =
            sync_receive::load_materialized_state(session.catalog_ref().unwrap(), &keys).unwrap();
        assert!(state.object_is_purged(&fixture.published));
        // The sharing log carries the purge of the published identifier.
        let collection = chur_media::keys::ensure_default_collection(&mut session).unwrap();
        let sharing = chur_catalog::sharing::load(session.catalog_ref().unwrap(), &collection)
            .unwrap()
            .unwrap();
        let key =
            chur_media::keys::collection_key(&session, &collection, sharing.collection_epoch())
                .unwrap();
        let domain = chur_sync_protocol::KeyDomain::collection(
            &key,
            &collection,
            sharing.collection_epoch(),
        )
        .unwrap();
        chur_catalog::sharing_log::ensure_object_index(
            session.catalog().unwrap(),
            domain.selector(),
            &keys,
        )
        .unwrap();
        let mut purged = false;
        chur_catalog::sharing_log::visit_object_records(
            session.catalog_ref().unwrap(),
            &keys,
            domain.selector(),
            &fixture.published,
            |_, payload| {
                purged |= matches!(
                    payload.body(),
                    chur_sync_protocol::payload::PayloadBody::PurgeObject { .. }
                );
                Ok(())
            },
        )
        .unwrap();
        assert!(purged);
        // The old identifier is gone and stays reserved, so no peer can fetch it.
        assert!(store::object(session.catalog_ref().unwrap(), &fixture.published).is_err());
        assert!(
            store::object_id_reserved(session.catalog_ref().unwrap(), &fixture.published).unwrap()
        );
    }

    #[test]
    fn a_cancellation_between_chunks_leaves_nothing_and_releases_the_session() {
        let fixture = fixture();
        let (before, ids) = {
            let session = session(&fixture);
            (contents(&session), stored_ids(&session))
        };
        let largest = *ids.last().unwrap();
        let calls = Cell::new(0);
        let cancelled = || {
            // Between chunks, the session is free for host calls.
            assert!(fixture.session.try_lock().is_ok());
            calls.set(calls.get() + 1);
            calls.get() > 1
        };
        let error = replace(&fixture.session, &largest, NOW + 2, &cancelled).unwrap_err();
        assert_eq!(error.status(), ChurStatus::Cancelled);
        {
            let session = session(&fixture);
            assert_eq!(stored_ids(&session), ids);
            assert_eq!(contents(&session), before);
            assert!(
                chur_catalog::journal::live(session.catalog_ref().unwrap())
                    .unwrap()
                    .is_empty()
            );
        }
        run_to_end(&fixture);
        assert_eq!(contents(&session(&fixture)), before);
        assert_eq!(counts(&session(&fixture)).unwrap(), (0, 0));
    }

    /// A host import that is still writing when re-encryption stops.
    fn host_import(fixture: &Fixture) -> import::Import {
        let mut session = session(fixture);
        let mut writing = import::begin(
            &mut session,
            import::SourceCapability {
                requested_object_id: None,
                seekable: true,
                known_length: Some(2_000),
                content_type_hint: "application/octet-stream".into(),
                original_filename: Some("host.bin".into()),
                capture_time_ms: None,
            },
            import::CanonicalMedia::opaque(),
            NOW + 2,
        )
        .unwrap();
        writing.write(&mut session, &[7; 2_000]).unwrap();
        writing
    }

    #[test]
    fn a_stopped_pass_leaves_a_host_import_on_the_same_session_intact() {
        let fixture = fixture();
        let ids = stored_ids(&session(&fixture));
        // Cancellation.
        let writing = host_import(&fixture);
        let calls = Cell::new(0);
        let cancelled = || {
            calls.set(calls.get() + 1);
            calls.get() > 1
        };
        let error = replace(&fixture.session, &ids[0], NOW + 2, &cancelled).unwrap_err();
        assert_eq!(error.status(), ChurStatus::Cancelled);
        let first = {
            let mut session = session(&fixture);
            assert_eq!(
                chur_catalog::journal::live(session.catalog_ref().unwrap())
                    .unwrap()
                    .len(),
                1
            );
            writing
                .commit(&mut session, "application/octet-stream", NOW + 3)
                .unwrap()
        };
        // An error: the original no longer authenticates.
        let writing = host_import(&fixture);
        {
            let session = session(&fixture);
            let stream =
                store::streams(session.catalog_ref().unwrap(), &ids[1]).unwrap()[0].clone();
            let path = session
                .root_dir()
                .container(&session.object_store_id(), &stream.container_path_id);
            let mut bytes = std::fs::read(&path).unwrap();
            let last = bytes.len() - 40;
            bytes[last] ^= 0xff;
            std::fs::write(&path, bytes).unwrap();
        }
        assert!(replace(&fixture.session, &ids[1], NOW + 2, &|| false).is_err());
        let second = {
            let mut session = session(&fixture);
            writing
                .commit(&mut session, "application/octet-stream", NOW + 3)
                .unwrap()
        };
        let session = session(&fixture);
        for id in [first, second] {
            let mut reader = reader::open(&session, &id, StreamKind::Original).unwrap();
            assert_eq!(reader.read_range(0, 2_000).unwrap().as_slice(), &[7; 2_000]);
        }
        assert!(
            chur_catalog::journal::live(session.catalog_ref().unwrap())
                .unwrap()
                .is_empty()
        );
    }

    #[test]
    fn a_locked_session_ends_the_pass_without_charging_a_failure() {
        let fixture = fixture();
        let candidates = stored_ids(&session(&fixture));
        session(&fixture).lock().unwrap();
        let error =
            run_with(&fixture.session, &|| NOW + 10, &|| false, &mut |_, _| {}).unwrap_err();
        assert!(matches!(
            error.status(),
            ChurStatus::Cancelled | ChurStatus::VaultLocked
        ));
        for id in &candidates {
            assert!(!backing_off(id, NOW + 10));
        }
    }

    #[test]
    fn failing_objects_back_off_and_do_not_block_the_others() {
        let fixture = fixture();
        let (corrupt, missing) = {
            let session = session(&fixture);
            let ids = sync_rotation::reencryption_candidates(
                session.catalog_ref().unwrap(),
                session.root_secret().unwrap(),
            )
            .unwrap();
            (ids[0], ids[1])
        };
        {
            let session = session(&fixture);
            let path = |id: &Id| {
                let stream = store::streams(session.catalog_ref().unwrap(), id).unwrap()[0].clone();
                session
                    .root_dir()
                    .container(&session.object_store_id(), &stream.container_path_id)
            };
            // One original no longer authenticates; another is unreadable.
            let corrupt_path = path(&corrupt);
            let mut bytes = std::fs::read(&corrupt_path).unwrap();
            let middle = bytes.len() / 2;
            bytes[middle] ^= 0xff;
            std::fs::write(&corrupt_path, bytes).unwrap();
            std::fs::remove_file(path(&missing)).unwrap();
        }
        run_to_end(&fixture);
        {
            let session = session(&fixture);
            assert_eq!(
                store::object(session.catalog_ref().unwrap(), &corrupt)
                    .unwrap()
                    .state,
                ObjectState::Corrupt
            );
            // The others were replaced; the two failures are reported.
            assert_eq!(counts(&session).unwrap(), (1, 2));
        }
        // The unreadable object is not retried before its backoff ends.
        assert!(backing_off(&missing, NOW + 10 + BACKOFF_FIRST_MS - 1));
        assert!(!backing_off(&missing, NOW + 10 + BACKOFF_FIRST_MS));
        assert_eq!(backoff_ms(1), 60_000);
        assert_eq!(backoff_ms(2), 120_000);
        assert_eq!(backoff_ms(40), BACKOFF_MAX_MS);
        clear_failure(&missing);
    }

    #[test]
    fn a_kill_during_the_copy_resumes_with_a_fresh_key() {
        let mut fixture = fixture();
        let (before, keys) = {
            let session = session(&fixture);
            let ids = stored_ids(&session);
            (contents(&session), old_keys(&session, &ids))
        };
        // The process dies after one chunk: the journal is live and nothing
        // is activated.
        {
            let mut session = session(&fixture);
            let mut writing = import::begin(
                &mut session,
                import::SourceCapability {
                    requested_object_id: None,
                    seekable: true,
                    known_length: Some(before[0].len() as u64),
                    content_type_hint: "application/octet-stream".into(),
                    original_filename: None,
                    capture_time_ms: None,
                },
                import::CanonicalMedia::opaque(),
                NOW + 2,
            )
            .unwrap();
            let chunk = writing.chunk_size() as usize;
            writing
                .write(&mut session, &before[0][..chunk.min(before[0].len())])
                .unwrap();
            std::mem::forget(writing);
        }
        reopen(&mut fixture);
        assert_eq!(contents(&session(&fixture)), before);
        run_to_end(&fixture);
        assert_eq!(contents(&session(&fixture)), before);
        old_keys_open_nothing(&session(&fixture), &keys);
    }

    #[test]
    fn a_kill_after_the_replacement_commit_rolls_the_deletion_forward() {
        let mut fixture = fixture();
        let (before, ids, keys) = {
            let session = session(&fixture);
            let ids = stored_ids(&session);
            (contents(&session), ids.clone(), old_keys(&session, &ids))
        };
        // Kill point 1: the copy is active and the old object is in step 1 of
        // its deletion. No listable duplicate exists.
        let first = replace(&fixture.session, &ids[0], NOW + 2, &|| false).unwrap();
        {
            let session = session(&fixture);
            assert_eq!(stored_ids(&session).len(), ids.len());
            assert_eq!(
                store::object(session.catalog_ref().unwrap(), &ids[0])
                    .unwrap()
                    .state,
                ObjectState::Deleting
            );
        }
        reopen(&mut fixture);
        {
            let session = session(&fixture);
            assert!(store::object(session.catalog_ref().unwrap(), &ids[0]).is_err());
            assert!(store::object(session.catalog_ref().unwrap(), &first).is_ok());
        }
        // Kill point 2: the old key envelope is erased, the containers and
        // rows are not yet removed.
        replace(&fixture.session, &ids[1], NOW + 3, &|| false).unwrap();
        {
            let mut session = session(&fixture);
            let secret = session.root_secret().unwrap().duplicate();
            let vault_id = session.vault_id();
            chur_catalog::deletion::erase_with_sync(
                session.catalog().unwrap(),
                &secret,
                vault_id,
                &ids[1],
                NOW + 3,
            )
            .unwrap();
        }
        reopen(&mut fixture);
        assert!(store::object(session(&fixture).catalog_ref().unwrap(), &ids[1]).is_err());
        run_to_end(&fixture);
        assert_eq!(contents(&session(&fixture)), before);
        old_keys_open_nothing(&session(&fixture), &keys);
    }

    #[test]
    fn an_unreadable_or_oversized_display_asset_does_not_fail_the_object() {
        let fixture = fixture();
        let targets: Vec<Id> = {
            let mut session = session(&fixture);
            let ids: Vec<Id> = stored_ids(&session)
                .into_iter()
                .filter(|id| {
                    store::object(session.catalog_ref().unwrap(), id)
                        .unwrap()
                        .state
                        == ObjectState::Active
                        && reader::open(&session, id, StreamKind::ThumbnailSmall).is_err()
                })
                .take(2)
                .collect();
            for id in &ids {
                derived::put(
                    &mut session,
                    id,
                    StreamKind::GridPreview,
                    64,
                    64,
                    &Zeroizing::new(vec![1; 1024]),
                    NOW,
                )
                .unwrap();
            }
            // The first asset can no longer be read.
            let stream = store::streams(session.catalog_ref().unwrap(), &ids[0])
                .unwrap()
                .into_iter()
                .find(|stream| stream.stream_kind == StreamKind::GridPreview)
                .unwrap();
            std::fs::remove_file(
                session
                    .root_dir()
                    .container(&session.object_store_id(), &stream.container_path_id),
            )
            .unwrap();
            ids
        };
        let unreadable = replace(&fixture.session, &targets[0], NOW + 2, &|| false).unwrap();
        // The second asset is above a lowered bound.
        let oversized =
            replace_bounded(&fixture.session, &targets[1], NOW + 2, &|| false, 512).unwrap();
        let session = session(&fixture);
        for copy in [unreadable, oversized] {
            assert!(reader::open(&session, &copy, StreamKind::Original).is_ok());
            assert!(reader::open(&session, &copy, StreamKind::GridPreview).is_err());
        }
    }

    #[test]
    fn unlock_does_not_reencrypt_and_the_c_abi_runs_the_pass() {
        let mut fixture = fixture();
        // The runtime unlocks at the real time, when the fixture's Trash
        // deadline has passed, so the trashed object is restored first.
        chur_catalog::trash::set(
            session(&fixture).catalog().unwrap(),
            &[fixture.trashed],
            true,
            NOW,
        )
        .unwrap();
        let before = contents(&session(&fixture));
        fixture.session.get_mut().unwrap().lock().unwrap();
        let path = fixture.root.base().to_str().unwrap().as_bytes().to_vec();
        let config = crate::records::ChurRuntimeConfigV1 {
            root_path: path.as_ptr(),
            root_path_length: path.len() as u32,
        };
        let mut runtime = 0;
        #[expect(unsafe_code, reason = "the test drives the real C ABI")]
        let opened = unsafe { crate::api::chur_runtime_open(&config, &mut runtime) };
        assert_eq!(opened, 0);
        let request = crate::records::ChurUnlockRequestV1 {
            factor: 1,
            reserved: [0; 3],
            secret: PASSWORD.as_ptr(),
            secret_length: PASSWORD.len() as u32,
        };
        let mut handle = 0;
        #[expect(unsafe_code, reason = "the test drives the real C ABI")]
        let unlocked = unsafe { crate::api::chur_vault_unlock(runtime, &request, &mut handle) };
        assert_eq!(unlocked, 0);
        let (mut pending, mut failed) = (0u64, 0u64);
        #[expect(unsafe_code, reason = "the test drives the real C ABI")]
        let counted = unsafe { chur_reencryption_counts(handle, &mut pending, &mut failed) };
        assert_eq!((counted, pending, failed), (0, 5, 0));
        let mut operation = 0;
        #[expect(unsafe_code, reason = "the test drives the real C ABI")]
        let started = unsafe { chur_reencryption_begin(handle, &mut operation) };
        assert_eq!(started, 0);
        let progress = loop {
            let mut progress = crate::records::ChurProgressV1 {
                kind: 0,
                stage: 0,
                processed: 0,
                total: 0,
                terminal: 0,
                reserved: [0; 3],
                status: 0,
                object_id: [0; 16],
            };
            #[expect(unsafe_code, reason = "the test drives the real C ABI")]
            let polled = unsafe { crate::api::chur_operation_poll(operation, &mut progress) };
            assert_eq!(polled, 0);
            if progress.terminal == 1 {
                break progress;
            }
            std::thread::yield_now();
        };
        assert_eq!(progress.status, 0);
        assert_eq!(progress.kind, OperationKind::Reencryption as u32);
        assert_eq!((progress.processed, progress.total), (5, 5));
        #[expect(unsafe_code, reason = "the test drives the real C ABI")]
        let counted = unsafe { chur_reencryption_counts(handle, &mut pending, &mut failed) };
        assert_eq!((counted, pending, failed), (0, 0, 0));
        {
            let entry = registry::get(handle, Kind::Session).unwrap();
            let session = registry::lock(crate::api::session_of(&entry).unwrap());
            assert_eq!(contents(&session), before);
        }
        #[expect(unsafe_code, reason = "the test drives the real C ABI")]
        let closed = unsafe { crate::api::chur_runtime_close(runtime) };
        assert_eq!(closed, 0);
    }
}
