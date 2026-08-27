//! The import, read, export, and integrity path end to end.
//!
//! Every test here creates a real vault on disk, imports real bytes through the
//! real journal, and reads them back through the real reader. Nothing is
//! stubbed, because the properties being checked are the ordering ones: what is
//! durable when, what survives an interruption, and what a substituted or
//! damaged container does.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

use chur_catalog::paths::VaultRoot;
use chur_catalog::query::{ObjectQuery, Scope, page};
use chur_catalog::vault::{self, Session};
use chur_catalog::{deletion, journal, store};
use chur_core::{ChurStatus, Id, Result};
use chur_crypto::random;
use chur_format::constants::{IntegritySummary, MediaClass, ObjectState, StreamKind};
use chur_media::import::{CanonicalMedia, SourceCapability};
use chur_media::{derived, export, import, integrity, reader};
use zeroize::Zeroizing;

const PASSWORD: &[u8] = b"correct horse battery staple";
const NOW: u64 = 1_700_000_000_000;

fn scratch_root() -> VaultRoot {
    let mut path = std::env::temp_dir();
    path.push(format!("chur-pipeline-{}", random::id().unwrap().to_hex()));
    std::fs::create_dir_all(&path).unwrap();
    VaultRoot::new(path)
}

fn new_vault() -> (VaultRoot, Session) {
    let root = scratch_root();
    let session = vault::create(&root, PASSWORD, NOW)
        .expect("create")
        .activate()
        .expect("activate");
    (root, session)
}

fn source(name: &str, length: u64) -> SourceCapability {
    SourceCapability {
        seekable: true,
        known_length: Some(length),
        content_type_hint: String::from("image/jpeg"),
        original_filename: Some(String::from(name)),
        capture_time_ms: Some(NOW - 86_400_000),
    }
}

fn photo() -> CanonicalMedia {
    CanonicalMedia {
        media_class: MediaClass::Image,
        width: 4_000,
        height: 3_000,
        duration_ms: 0,
    }
}

/// Deterministic bytes that are not compressible into a pattern a bug could
/// reproduce by accident.
fn plaintext(length: usize) -> Zeroizing<Vec<u8>> {
    let mut bytes = vec![0u8; length];
    let mut state = 0x2545_F491_4F6C_DD1Du64;
    for byte in &mut bytes {
        state ^= state << 13;
        state ^= state >> 7;
        state ^= state << 17;
        *byte = (state & 0xff) as u8;
    }
    Zeroizing::new(bytes)
}

fn import_photo(session: &mut Session, length: usize, name: &str) -> (Id, Zeroizing<Vec<u8>>) {
    let bytes = plaintext(length);
    let object_id = import::import_bytes(
        session,
        source(name, length as u64),
        photo(),
        "image/jpeg",
        &bytes,
        NOW,
    )
    .expect("import");
    (object_id, bytes)
}

fn rejection<T>(outcome: Result<T>) -> ChurStatus {
    let Err(error) = outcome else {
        panic!("the pipeline accepted something the specification forbids");
    };
    error.status()
}

#[test]
fn an_imported_object_reads_back_byte_for_byte() {
    let (_root, mut session) = new_vault();
    // Three chunks and a short fourth, so the canonical chunking rule and the
    // short-last-chunk case are both exercised.
    let length = 262_144 * 3 + 1_000;
    let (object_id, expected) = import_photo(&mut session, length, "holiday.jpg");

    let mut handle = reader::open(&session, &object_id, StreamKind::Original).expect("open");
    assert_eq!(handle.size(), length as u64);
    let read = handle.read_range(0, length as u64).expect("read");
    assert_eq!(read.as_slice(), expected.as_slice());
}

#[test]
fn a_range_read_returns_the_same_bytes_as_the_whole() {
    let (_root, mut session) = new_vault();
    let length = 262_144 * 2 + 500;
    let (object_id, expected) = import_photo(&mut session, length, "a.jpg");
    let mut handle = reader::open(&session, &object_id, StreamKind::Original).expect("open");
    for (offset, take) in [
        (0u64, 10u64),
        (262_143, 2),
        (262_144, 1),
        (262_100, 300),
        (length as u64 - 1, 1),
        (524_288, 500),
    ] {
        let read = handle.read_range(offset, take).expect("range");
        let start = usize::try_from(offset).unwrap();
        let end = start + usize::try_from(take).unwrap();
        assert_eq!(
            read.as_slice(),
            &expected[start..end],
            "range {offset}+{take}"
        );
    }
}

#[test]
fn read_at_follows_the_ffi_contract_end_of_stream_rules() {
    let (_root, mut session) = new_vault();
    let length = 1_000;
    let (object_id, expected) = import_photo(&mut session, length, "a.jpg");
    let mut handle = reader::open(&session, &object_id, StreamKind::Original).expect("open");
    let size = handle.size();

    // §6.3: capacity zero is a success with zero bytes and touches nothing.
    let mut empty: [u8; 0] = [];
    assert_eq!(handle.read_at(0, &mut empty).expect("empty"), 0);

    // §6.3: offset == size is a success with zero bytes.
    let mut one = [0u8; 1];
    assert_eq!(handle.read_at(size, &mut one).expect("end"), 0);

    // §6.3: offset > size is INVALID_INPUT, never a zero-length success.
    assert_eq!(
        rejection(handle.read_at(size + 1, &mut one)),
        ChurStatus::InvalidInput
    );

    // A caller loops until it has what it needs.
    let mut buffer = vec![0u8; length];
    let mut written = 0usize;
    while written < length {
        let taken = handle
            .read_at(written as u64, &mut buffer[written..])
            .expect("read");
        assert!(taken > 0, "a short read returned zero before the end");
        written += taken;
    }
    assert_eq!(buffer.as_slice(), expected.as_slice());
}

#[test]
fn a_committed_import_leaves_no_journal_record_and_no_temporary_container() {
    let (root, mut session) = new_vault();
    let (_object_id, _) = import_photo(&mut session, 5_000, "a.jpg");
    assert!(
        journal::live(session.catalog_ref().unwrap())
            .unwrap()
            .is_empty()
    );
    assert!(
        journal::dead(session.catalog_ref().unwrap())
            .unwrap()
            .is_empty()
    );
    let incoming = root.incoming(&session.object_store_id());
    assert_eq!(std::fs::read_dir(&incoming).unwrap().count(), 0);
}

#[test]
fn a_committed_container_carries_the_epoch_timestamp() {
    let (root, mut session) = new_vault();
    let (object_id, _) = import_photo(&mut session, 5_000, "a.jpg");
    let stream = &store::streams(session.catalog_ref().unwrap(), &object_id).unwrap()[0];
    let path = root.container(&session.object_store_id(), &stream.container_path_id);
    let modified = std::fs::metadata(&path).unwrap().modified().unwrap();
    assert_eq!(
        modified,
        std::time::UNIX_EPOCH,
        "§14 normalizes the container timestamp so a listing discloses no import order"
    );
}

#[test]
fn an_abandoned_import_destroys_its_key_and_its_container() {
    let (root, mut session) = new_vault();
    let mut running =
        import::begin(&mut session, source("a.jpg", 5_000), photo(), NOW).expect("begin");
    running
        .write(&mut session, &plaintext(5_000))
        .expect("write");
    let transaction_id = running.transaction_id();
    let record = journal::read(session.catalog_ref().unwrap(), &transaction_id).unwrap();
    assert!(record.envelope_body.is_some());
    let temporary = root.temporary_container(&session.object_store_id(), &record.temp_path_id);
    assert!(temporary.exists());

    running.abandon(&mut session).expect("abandon");
    assert!(!temporary.exists(), "the temporary container survived");
    assert_eq!(
        rejection(journal::read(
            session.catalog_ref().unwrap(),
            &transaction_id
        )),
        ChurStatus::NotFound
    );
    assert!(
        page(session.catalog_ref().unwrap(), &ObjectQuery::timeline())
            .unwrap()
            .objects
            .is_empty()
    );
}

#[test]
fn reconciliation_kills_an_import_a_crash_left_behind() {
    let (root, mut session) = new_vault();
    let mut running =
        import::begin(&mut session, source("a.jpg", 5_000), photo(), NOW).expect("begin");
    running
        .write(&mut session, &plaintext(5_000))
        .expect("write");
    let transaction_id = running.transaction_id();
    let record = journal::read(session.catalog_ref().unwrap(), &transaction_id).unwrap();
    let temporary = root.temporary_container(&session.object_store_id(), &record.temp_path_id);
    // Drop without committing or abandoning: the process died.
    drop(running);
    assert!(temporary.exists());
    assert_eq!(
        journal::live(session.catalog_ref().unwrap()).unwrap().len(),
        1
    );

    assert_eq!(import::reconcile(&mut session, NOW).expect("reconcile"), 1);
    assert!(
        !temporary.exists(),
        "a dead container survived reconciliation"
    );
    assert!(
        journal::live(session.catalog_ref().unwrap())
            .unwrap()
            .is_empty()
    );
    assert!(
        journal::dead(session.catalog_ref().unwrap())
            .unwrap()
            .is_empty()
    );
}

#[test]
fn a_journal_reservation_is_durable_before_the_record_is_written() {
    let (_root, mut session) = new_vault();
    let mut running =
        import::begin(&mut session, source("a.jpg", 800_000), photo(), NOW).expect("begin");
    let transaction_id = running.transaction_id();
    // §14.2: after the manifest is durable and before any chunk, no index is
    // reserved.
    assert_eq!(
        journal::read(session.catalog_ref().unwrap(), &transaction_id)
            .unwrap()
            .reserved_index,
        None
    );
    for index in 0..3u64 {
        running
            .write(&mut session, &plaintext(262_144))
            .expect("write");
        assert_eq!(
            journal::read(session.catalog_ref().unwrap(), &transaction_id)
                .unwrap()
                .reserved_index,
            Some(index),
            "the reservation did not advance with the record"
        );
    }
    running.abandon(&mut session).expect("abandon");
}

#[test]
fn the_journaled_length_is_where_the_next_record_begins() {
    let (root, mut session) = new_vault();
    let mut running =
        import::begin(&mut session, source("a.jpg", 800_000), photo(), NOW).expect("begin");
    let transaction_id = running.transaction_id();
    running
        .write(&mut session, &plaintext(262_144))
        .expect("write");
    let record = journal::read(session.catalog_ref().unwrap(), &transaction_id).unwrap();
    let temporary = root.temporary_container(&session.object_store_id(), &record.temp_path_id);
    let on_disk = std::fs::metadata(&temporary).unwrap().len();
    // The journal proves index 0 durable, so its offset plus one full record is
    // the whole file.
    let offset = record.journaled_ciphertext_length().expect("a reservation");
    assert_eq!(
        offset + 20 + u64::from(record.chunk_size) + 16,
        on_disk,
        "the §14.1 formula does not name the end of the durable prefix"
    );
    running.abandon(&mut session).expect("abandon");
}

#[test]
fn an_object_appears_in_the_timeline_and_in_search() {
    let (_root, mut session) = new_vault();
    let (object_id, _) = import_photo(&mut session, 5_000, "Bäckerei.jpg");
    let listed = page(session.catalog_ref().unwrap(), &ObjectQuery::timeline()).unwrap();
    assert_eq!(listed.objects.len(), 1);
    assert_eq!(listed.objects[0].object_id, object_id);
    assert_eq!(listed.objects[0].plaintext_size, 5_000);
    assert_eq!(
        listed.objects[0].integrity_summary,
        IntegritySummary::CompleteVerified.value()
    );

    let found = page(
        session.catalog_ref().unwrap(),
        &ObjectQuery {
            scope: Scope::Search(String::from("backerei")),
            ..ObjectQuery::timeline()
        },
    )
    .unwrap();
    assert_eq!(found.objects.len(), 1);
}

#[test]
fn an_export_writes_the_original_bytes() {
    let (_root, mut session) = new_vault();
    let length = 262_144 + 77;
    let (object_id, expected) = import_photo(&mut session, length, "a.jpg");
    let mut out = Vec::new();
    let written = export::export_stream(&session, &object_id, StreamKind::Original, &mut out)
        .expect("export");
    assert_eq!(written, length as u64);
    assert_eq!(out.as_slice(), expected.as_slice());
}

#[test]
fn a_scratch_entry_is_created_and_released() {
    let (root, mut session) = new_vault();
    let (object_id, expected) = import_photo(&mut session, 4_096, "a.jpg");
    let entry = export::materialize(&session, &object_id, StreamKind::Original).expect("scratch");
    assert_eq!(std::fs::read(entry.path()).unwrap(), expected.as_slice());
    let directory = root.scratch(&session.object_store_id());
    assert_eq!(std::fs::read_dir(&directory).unwrap().count(), 1);
    entry.release().expect("release");
    assert_eq!(std::fs::read_dir(&directory).unwrap().count(), 0);
}

#[test]
fn the_scratch_entry_cap_is_enforced_before_the_first_byte() {
    let (_root, mut session) = new_vault();
    let (object_id, _) = import_photo(&mut session, 1_024, "a.jpg");
    let mut held = Vec::new();
    for _ in 0..4 {
        held.push(
            export::materialize(&session, &object_id, StreamKind::Original).expect("scratch"),
        );
    }
    assert_eq!(
        rejection(export::materialize(
            &session,
            &object_id,
            StreamKind::Original
        )),
        ChurStatus::ResourceLimitExceeded
    );
    for entry in held {
        entry.release().expect("release");
    }
}

#[test]
fn lock_clears_every_scratch_entry() {
    let (root, mut session) = new_vault();
    let (object_id, _) = import_photo(&mut session, 1_024, "a.jpg");
    let _entry = export::materialize(&session, &object_id, StreamKind::Original).expect("scratch");
    session.lock().expect("lock");
    let directory = root.scratch(&session.object_store_id());
    assert_eq!(std::fs::read_dir(&directory).unwrap().count(), 0);
}

#[test]
fn a_derived_asset_round_trips_and_sets_the_thumbnail_flag() {
    let (_root, mut session) = new_vault();
    let (object_id, _) = import_photo(&mut session, 5_000, "a.jpg");
    let thumbnail = plaintext(2_048);
    derived::put(
        &mut session,
        &object_id,
        StreamKind::ThumbnailSmall,
        320,
        240,
        &thumbnail,
        NOW,
    )
    .expect("thumbnail");
    assert!(
        store::object(session.catalog_ref().unwrap(), &object_id)
            .unwrap()
            .thumbnail_ready
    );
    let read = derived::read(&session, &object_id, StreamKind::ThumbnailSmall).expect("read");
    assert_eq!(read.as_slice(), thumbnail.as_slice());
}

#[test]
fn a_derivative_above_its_long_edge_is_refused() {
    let (_root, mut session) = new_vault();
    let (object_id, _) = import_photo(&mut session, 5_000, "a.jpg");
    assert_eq!(
        rejection(derived::put(
            &mut session,
            &object_id,
            StreamKind::ThumbnailSmall,
            321,
            240,
            &plaintext(64),
            NOW,
        )),
        ChurStatus::ResourceLimitExceeded
    );
}

#[test]
fn an_integrity_scan_confirms_an_intact_object() {
    let (_root, mut session) = new_vault();
    let (object_id, _) = import_photo(&mut session, 300_000, "a.jpg");
    let outcome = integrity::scan_object(&mut session, &object_id, NOW).expect("scan");
    assert_eq!(outcome.state, ObjectState::Active);
    assert_eq!(
        outcome.integrity_summary,
        IntegritySummary::CompleteVerified
    );
}

#[test]
fn an_absent_container_is_quarantined_rather_than_corrupt() {
    let (root, mut session) = new_vault();
    let (object_id, _) = import_photo(&mut session, 5_000, "a.jpg");
    let stream = &store::streams(session.catalog_ref().unwrap(), &object_id).unwrap()[0];
    std::fs::remove_file(root.container(&session.object_store_id(), &stream.container_path_id))
        .unwrap();

    let outcome = integrity::scan_object(&mut session, &object_id, NOW).expect("scan");
    assert_eq!(outcome.state, ObjectState::Active);
    assert_eq!(outcome.integrity_summary, IntegritySummary::Quarantined);

    // §16.2: a quarantined row leaves the ordinary library and appears only in
    // the quarantine scope.
    assert!(
        page(session.catalog_ref().unwrap(), &ObjectQuery::timeline())
            .unwrap()
            .objects
            .is_empty()
    );
    assert_eq!(
        page(
            session.catalog_ref().unwrap(),
            &ObjectQuery {
                scope: Scope::Quarantine,
                ..ObjectQuery::timeline()
            }
        )
        .unwrap()
        .objects
        .len(),
        1
    );
}

#[test]
fn a_flipped_ciphertext_bit_is_proven_corruption() {
    let (root, mut session) = new_vault();
    let (object_id, _) = import_photo(&mut session, 300_000, "a.jpg");
    let stream = &store::streams(session.catalog_ref().unwrap(), &object_id).unwrap()[0];
    let path = root.container(&session.object_store_id(), &stream.container_path_id);
    let mut bytes = std::fs::read(&path).unwrap();
    // A byte inside the first chunk's ciphertext, past the preamble and the
    // manifest record.
    let at = bytes.len() / 2;
    bytes[at] ^= 0x01;
    std::fs::write(&path, &bytes).unwrap();

    let outcome = integrity::scan_object(&mut session, &object_id, NOW).expect("scan");
    assert_eq!(
        outcome.state,
        ObjectState::Corrupt,
        "a failed authentication is a lifecycle change, §5.1"
    );
    // §16.2: a corrupt row is in no scope.
    for scope in [Scope::Timeline, Scope::Quarantine] {
        assert!(
            page(
                session.catalog_ref().unwrap(),
                &ObjectQuery {
                    scope,
                    ..ObjectQuery::timeline()
                }
            )
            .unwrap()
            .objects
            .is_empty()
        );
    }
}

#[test]
fn a_container_from_another_object_does_not_open() {
    let (root, mut session) = new_vault();
    let (first, _) = import_photo(&mut session, 5_000, "a.jpg");
    let (second, _) = import_photo(&mut session, 5_000, "b.jpg");
    let store_id = session.object_store_id();
    let first_stream = store::streams(session.catalog_ref().unwrap(), &first).unwrap()[0].clone();
    let second_stream = store::streams(session.catalog_ref().unwrap(), &second).unwrap()[0].clone();

    // Substitute the second object's container under the first object's path.
    let donor = std::fs::read(root.container(&store_id, &second_stream.container_path_id)).unwrap();
    std::fs::write(
        root.container(&store_id, &first_stream.container_path_id),
        donor,
    )
    .unwrap();

    // §4: the reader supplies the identity from the catalog, so the manifest
    // key and AAD are the first object's and the substituted manifest does not
    // authenticate.
    assert_eq!(
        rejection(reader::open(&session, &first, StreamKind::Original)),
        ChurStatus::ObjectCorrupt
    );
}

#[test]
fn deleting_an_object_removes_its_container_and_its_key() {
    let (root, mut session) = new_vault();
    let (object_id, _) = import_photo(&mut session, 5_000, "a.jpg");
    let store_id = session.object_store_id();
    let stream = store::streams(session.catalog_ref().unwrap(), &object_id).unwrap()[0].clone();
    let path = root.container(&store_id, &stream.container_path_id);
    assert!(path.exists());

    deletion::begin(session.catalog().unwrap(), &object_id).expect("begin");
    let pending = deletion::sweep(session.catalog_ref().unwrap()).expect("sweep");
    assert_eq!(pending.len(), 1);
    deletion::erase(session.catalog().unwrap(), &object_id, NOW).expect("erase");
    for container in &pending[0].containers {
        chur_media::store::unlink_container(&root, &store_id, container).expect("unlink");
    }
    deletion::finish(session.catalog().unwrap(), &object_id).expect("finish");

    assert!(!path.exists(), "the container survived deletion");
    assert_eq!(
        rejection(reader::open(&session, &object_id, StreamKind::Original)),
        ChurStatus::NotFound
    );
    assert!(
        page(session.catalog_ref().unwrap(), &ObjectQuery::timeline())
            .unwrap()
            .objects
            .is_empty()
    );
}

#[test]
fn an_object_survives_a_lock_and_an_unlock() {
    let (root, mut session) = new_vault();
    let length = 300_000;
    let (object_id, expected) = import_photo(&mut session, length, "a.jpg");
    session.lock().expect("lock");
    drop(session);

    let mut reopened = vault::unlock_with_password(&root, PASSWORD, NOW + 1).expect("unlock");
    let listed = page(reopened.catalog().unwrap(), &ObjectQuery::timeline()).unwrap();
    assert_eq!(listed.objects.len(), 1);
    let mut handle = reader::open(&reopened, &object_id, StreamKind::Original).expect("open");
    let read = handle.read_range(0, length as u64).expect("read");
    assert_eq!(read.as_slice(), expected.as_slice());
}

#[test]
fn an_empty_source_is_refused_and_leaves_nothing_behind() {
    let (root, mut session) = new_vault();
    assert_eq!(
        rejection(import::import_bytes(
            &mut session,
            source("a.jpg", 0),
            photo(),
            "image/jpeg",
            &Zeroizing::new(Vec::new()),
            NOW,
        )),
        ChurStatus::InvalidInput
    );
    assert!(
        journal::live(session.catalog_ref().unwrap())
            .unwrap()
            .is_empty()
    );
    assert_eq!(
        std::fs::read_dir(root.incoming(&session.object_store_id()))
            .unwrap()
            .count(),
        0
    );
}

#[test]
fn a_source_above_the_object_bound_is_refused_before_any_work() {
    let (_root, mut session) = new_vault();
    let mut oversized = source("a.jpg", 0);
    oversized.known_length = Some(1_099_511_627_777);
    assert_eq!(
        rejection(import::begin(&mut session, oversized, photo(), NOW)),
        ChurStatus::ResourceLimitExceeded
    );
    assert!(
        journal::live(session.catalog_ref().unwrap())
            .unwrap()
            .is_empty()
    );
}
