//! The import, read, export, and integrity path end to end.
//!
//! Every test here creates a real vault on disk, imports real bytes through the
//! real journal, and reads them back through the real reader. Nothing is
//! stubbed, because the properties being checked are the ordering ones: what is
//! durable when, what survives an interruption, and what a substituted or
//! damaged container does.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

use chur_catalog::journal::{ImportTransaction, Stage};
use chur_catalog::paths::VaultRoot;
use chur_catalog::query::{ObjectQuery, Scope, page};
use chur_catalog::vault::{self, Session};
use chur_catalog::{deletion, journal, store};
use chur_core::{ChurStatus, Id, Result};
use chur_crypto::random;
use chur_format::constants::{IntegritySummary, MediaClass, ObjectState, StreamKind};
use chur_media::import::{CanonicalMedia, SourceCapability};
use chur_media::progress::Uninterrupted;
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
        requested_object_id: None,
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
fn imports_write_padded_containers_that_report_the_true_size() {
    // ADR-0066: both objects fall in the 512 KiB bucket, so their containers
    // have one length, while every size the catalog and the reader report is
    // the object's own.
    let (root, mut session) = new_vault();
    let mut lengths = Vec::new();
    for (length, name) in [(300_000usize, "a.jpg"), (500_000, "b.jpg")] {
        let (object_id, expected) = import_photo(&mut session, length, name);
        let stream = &store::streams(session.catalog_ref().unwrap(), &object_id).unwrap()[0];
        assert_eq!(stream.container_version, 2);
        assert_eq!(stream.plaintext_size, length as u64);
        let path = root.container(&session.object_store_id(), &stream.container_path_id);
        let on_disk = std::fs::metadata(&path).unwrap().len();
        assert_eq!(stream.ciphertext_size, on_disk);
        lengths.push(on_disk);

        let mut handle = reader::open(&session, &object_id, StreamKind::Original).expect("open");
        assert_eq!(handle.size(), length as u64);
        let read = handle.read_range(0, length as u64).expect("read");
        assert_eq!(read.as_slice(), expected.as_slice());
        assert_eq!(
            rejection(handle.read_range(length as u64, 1)),
            ChurStatus::InvalidInput
        );
    }
    assert_eq!(lengths[0], lengths[1]);
    assert_eq!(
        session.descriptor().object_store.container_version_ceiling,
        2
    );
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
fn reconciliation_removes_a_container_the_rename_committed_but_nothing_activated() {
    let (root, mut session) = new_vault();
    let mut running =
        import::begin(&mut session, source("a.jpg", 5_000), photo(), NOW).expect("begin");
    running
        .write(&mut session, &plaintext(5_000))
        .expect("write");
    let transaction_id = running.transaction_id();
    let record = journal::read(session.catalog_ref().unwrap(), &transaction_id).unwrap();
    // The rename of §14 is the last step a process can survive with nothing
    // to show for it. The file is in the store now, no catalog row names it,
    // and the journal record still carries the one identifier that names both
    // the temporary and the committed location.
    journal::mark_committing(session.catalog().unwrap(), &transaction_id).unwrap();
    let temporary = root.temporary_container(&session.object_store_id(), &record.temp_path_id);
    let committed = root.container(&session.object_store_id(), &record.temp_path_id);
    // The §14 rename lands in a store whose shard directory the first
    // activation of this object creates; the crash window being simulated is
    // after that creation, so the test creates it the way the rename's
    // implementation does.
    std::fs::create_dir_all(committed.parent().unwrap()).unwrap();
    std::fs::rename(&temporary, &committed).unwrap();
    drop(running);
    assert!(committed.exists());

    assert_eq!(import::reconcile(&mut session, NOW).expect("reconcile"), 1);
    assert!(
        !committed.exists(),
        "the committed container of an abandoned import survived reconciliation"
    );
    assert!(
        page(session.catalog_ref().unwrap(), &ObjectQuery::timeline())
            .unwrap()
            .objects
            .is_empty()
    );
    assert!(
        journal::live(session.catalog_ref().unwrap())
            .unwrap()
            .is_empty()
    );
}

#[test]
fn reconciliation_completes_a_record_the_activation_outlived_instead_of_destroying_the_object() {
    let (root, mut session) = new_vault();
    let (object_id, _) = import_photo(&mut session, 5_000, "a.jpg");
    let stream = &store::streams(session.catalog_ref().unwrap(), &object_id).unwrap()[0];
    let committed = root.container(&session.object_store_id(), &stream.container_path_id);
    assert!(committed.exists());
    // The journal state a death between the activation and its close leaves:
    // an open record for an object the catalog already activated, over a
    // container the activation row names. Abandoning that record would
    // strand a live object.
    let record = ImportTransaction {
        transaction_id: random::id().unwrap(),
        temp_path_id: stream.container_path_id,
        object_id,
        stream_id: stream.stream_id,
        stream_kind: stream.stream_kind,
        stream_revision: stream.stream_revision,
        envelope_generation: 1,
        envelope_body: Some(Vec::new()),
        nonce_prefix: [0; 16],
        chunk_size: stream.chunk_size,
        manifest_length: chur_core::limits::container::MANIFEST_RECORD_MIN,
        reserved_index: None,
        expected_length: None,
        source_seekable: true,
        stage: Stage::Opening,
        opened_ms: NOW,
    };
    journal::open(session.catalog().unwrap(), &record).unwrap();

    assert_eq!(import::reconcile(&mut session, NOW).expect("reconcile"), 0);
    assert!(
        committed.exists(),
        "reconciliation destroyed the container of an activated object"
    );
    assert!(
        journal::live(session.catalog_ref().unwrap())
            .unwrap()
            .is_empty()
    );
    assert_eq!(
        page(session.catalog_ref().unwrap(), &ObjectQuery::timeline())
            .unwrap()
            .objects
            .len(),
        1
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
fn a_resume_never_counts_bucket_padding_as_object_bytes() {
    // 300000 bytes are one full 262144-byte chunk and a short one; the short
    // one is padded to the 512 KiB bucket, so after it the next source offset
    // would lie past the object.
    let (_root, mut session) = new_vault();
    let mut running =
        import::begin(&mut session, source("a.jpg", 300_000), photo(), NOW).expect("begin");
    let transaction_id = running.transaction_id();
    running
        .write(&mut session, &plaintext(262_144))
        .expect("write");
    assert_eq!(
        import::resume_offset(&session, &transaction_id, true).unwrap(),
        Some(262_144)
    );
    running
        .write(&mut session, &plaintext(300_000 - 262_144))
        .expect("write");
    assert_eq!(
        import::resume_offset(&session, &transaction_id, true).unwrap(),
        None,
        "a reserved record that holds padding must not resume past the object"
    );
    running.abandon(&mut session).expect("abandon");

    // Without a known length, no reserved record is provably object bytes.
    let mut unknown = source("b.jpg", 0);
    unknown.known_length = None;
    let mut running = import::begin(&mut session, unknown, photo(), NOW).expect("begin");
    let transaction_id = running.transaction_id();
    running
        .write(&mut session, &plaintext(262_144))
        .expect("write");
    assert_eq!(
        import::resume_offset(&session, &transaction_id, true).unwrap(),
        None
    );
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
            scope: Scope::Search,
            terms: Some(String::from("backerei")),
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
    let written = export::export_stream(
        &session,
        &object_id,
        StreamKind::Original,
        &mut out,
        &mut Uninterrupted,
    )
    .expect("export");
    assert_eq!(written, length as u64);
    assert_eq!(out.as_slice(), expected.as_slice());
}

#[test]
fn a_scratch_entry_is_created_and_released() {
    let (root, mut session) = new_vault();
    let (object_id, expected) = import_photo(&mut session, 4_096, "a.jpg");
    let entry = export::materialize(
        &session,
        &object_id,
        StreamKind::Original,
        &mut Uninterrupted,
    )
    .expect("scratch");
    assert_eq!(std::fs::read(entry.path()).unwrap(), expected.as_slice());
    let directory = root.scratch(&session.object_store_id());
    assert_eq!(scratch_entries(&directory), 1);
    entry.release().expect("release");
    assert_eq!(scratch_entries(&directory), 0);
}

/// Counts scratch entries, which excludes the journal the lifecycle keeps.
fn scratch_entries(directory: &std::path::Path) -> usize {
    std::fs::read_dir(directory)
        .unwrap()
        .filter_map(std::result::Result::ok)
        .filter(|entry| entry.file_name().to_string_lossy() != "scratch-journal")
        .count()
}

#[test]
fn a_materialization_journals_its_entry() {
    let (root, mut session) = new_vault();
    let (object_id, _) = import_photo(&mut session, 4_096, "a.jpg");
    let entry = export::materialize(
        &session,
        &object_id,
        StreamKind::Original,
        &mut Uninterrupted,
    )
    .expect("scratch");
    let directory = root.scratch(&session.object_store_id());
    let entry_id = entry.entry_id().to_hex();
    let journal = std::fs::read_to_string(directory.join("scratch-journal")).unwrap();
    assert!(
        journal.contains(&entry_id),
        "the journal names the entry it was created for"
    );
    entry.release().expect("release");
    let journal = std::fs::read_to_string(directory.join("scratch-journal"));
    assert!(
        journal.map_or(true, |text| !text.contains(&entry_id)),
        "the released entry is no longer in the journal"
    );
}

#[test]
fn an_entry_expires_after_its_hold_bound() {
    let (root, mut session) = new_vault();
    let (object_id, _) = import_photo(&mut session, 4_096, "a.jpg");
    let entry = export::materialize(
        &session,
        &object_id,
        StreamKind::Original,
        &mut Uninterrupted,
    )
    .expect("scratch");
    let directory = root.scratch(&session.object_store_id());
    let journal = std::fs::read_to_string(directory.join("scratch-journal")).unwrap();
    let (_, created_at_ms) = journal.trim_end().rsplit_once(' ').unwrap();
    let created_at_ms: u64 = created_at_ms.parse().unwrap();
    // Inside the hold bound the sweep keeps the entry and its row.
    export::sweep_expired(&directory, created_at_ms).expect("sweep");
    assert!(entry.path().exists());
    // At the bound it deletes the entry and drops the row.
    let removed = export::sweep_expired(
        &directory,
        created_at_ms + chur_core::limits::scratch::HOLD_MS_MAX,
    )
    .expect("sweep");
    assert_eq!(removed, 1);
    assert!(!entry.path().exists());
    let journal = std::fs::read_to_string(directory.join("scratch-journal")).unwrap();
    assert!(!journal.contains(&entry.entry_id().to_hex()));
}

#[test]
fn the_scratch_entry_cap_is_enforced_before_the_first_byte() {
    let (_root, mut session) = new_vault();
    let (object_id, _) = import_photo(&mut session, 1_024, "a.jpg");
    let mut held = Vec::new();
    for _ in 0..4 {
        held.push(
            export::materialize(
                &session,
                &object_id,
                StreamKind::Original,
                &mut Uninterrupted,
            )
            .expect("scratch"),
        );
    }
    assert_eq!(
        rejection(export::materialize(
            &session,
            &object_id,
            StreamKind::Original,
            &mut Uninterrupted
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
    let _entry = export::materialize(
        &session,
        &object_id,
        StreamKind::Original,
        &mut Uninterrupted,
    )
    .expect("scratch");
    session.lock().expect("lock");
    let directory = root.scratch(&session.object_store_id());
    assert_eq!(std::fs::read_dir(&directory).unwrap().count(), 0);
}

#[test]
fn a_failed_derived_catalog_write_is_reconciled_without_a_ciphertext_orphan() {
    let (root, mut session) = new_vault();
    let (object_id, original_bytes) = import_photo(&mut session, 5_000, "original.jpg");
    session
        .catalog()
        .unwrap()
        .transaction(|transaction| {
            transaction
                .execute_batch(
                    "CREATE TEMP TRIGGER reject_derivative BEFORE INSERT ON derived_assets
             BEGIN SELECT RAISE(FAIL, 'injected derivative catalog failure'); END;",
                )
                .unwrap();
            Ok(())
        })
        .unwrap();
    let result = derived::put(
        &mut session,
        &object_id,
        StreamKind::ThumbnailSmall,
        100,
        100,
        &plaintext(2_000),
        NOW,
    );
    assert!(result.is_err(), "catalog fault must fire");
    import::reconcile(&mut session, NOW).unwrap();
    let mut containers = 0;
    for shard in std::fs::read_dir(root.objects(&session.object_store_id())).unwrap() {
        containers += std::fs::read_dir(shard.unwrap().path()).unwrap().count();
    }
    assert_eq!(
        containers, 1,
        "the failed derivative must not retain a committed orphan"
    );
    assert!(
        journal::live(session.catalog_ref().unwrap())
            .unwrap()
            .is_empty()
    );
    assert_eq!(
        derived::read(&session, &object_id, StreamKind::ThumbnailSmall)
            .unwrap_err()
            .status(),
        ChurStatus::NotFound
    );
    let mut original = Vec::new();
    export::export_stream(
        &session,
        &object_id,
        StreamKind::Original,
        &mut original,
        &mut Uninterrupted,
    )
    .unwrap();
    assert_eq!(original, *original_bytes);
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
fn a_compatible_video_is_encrypted_and_never_replaces_the_original() {
    let (_root, mut session) = new_vault();
    let original = Zeroizing::new(
        include_bytes!("../../../../tests/media/hevc-main10/sdr10-hvc1.mp4").to_vec(),
    );
    let object_id = import::import_bytes(
        &mut session,
        source("source.mp4", original.len() as u64),
        CanonicalMedia {
            media_class: MediaClass::Video,
            width: 192,
            height: 96,
            duration_ms: 1250,
        },
        "video/mp4",
        &original,
        NOW,
    )
    .unwrap();
    let copy = Zeroizing::new(
        include_bytes!("../../../../tests/media/video-compatibility/reference-sdr.mp4").to_vec(),
    );
    derived::put(
        &mut session,
        &object_id,
        StreamKind::CompatibleVideo,
        192,
        96,
        &copy,
        NOW,
    )
    .unwrap();
    let mut reader = reader::open(&session, &object_id, StreamKind::CompatibleVideo).unwrap();
    let info = reader.content_info().unwrap();
    assert_eq!(info.content_type, "video/mp4");
    assert_eq!(info.media_kind, u16::from(MediaClass::Video.value()));
    assert_eq!(reader.dimensions(), (192, 96));
    assert_eq!(
        reader
            .read_range(0, info.plaintext_size)
            .unwrap()
            .as_slice(),
        copy.as_slice()
    );
    let mut exported = Vec::new();
    export::export_stream(
        &session,
        &object_id,
        StreamKind::Original,
        &mut exported,
        &mut Uninterrupted,
    )
    .unwrap();
    assert_eq!(exported.as_slice(), original.as_slice());
    assert_eq!(
        derived::put(
            &mut session,
            &object_id,
            StreamKind::CompatibleVideo,
            1920,
            1080,
            &copy,
            NOW
        )
        .unwrap_err()
        .status(),
        ChurStatus::ResourceLimitExceeded
    );
}

#[test]
fn a_real_seek_atlas_is_encrypted_and_bound_to_its_original() {
    let (_root, mut session) = new_vault();
    let original = Zeroizing::new(
        include_bytes!("../../../../tests/media/video-acceptance/main10-long-gop-vfr.mp4").to_vec(),
    );
    let object_id = import::import_bytes(
        &mut session,
        source("timeline.mp4", original.len() as u64),
        CanonicalMedia {
            media_class: MediaClass::Video,
            width: 320,
            height: 180,
            duration_ms: 64_000,
        },
        "video/mp4",
        &original,
        NOW,
    )
    .unwrap();
    let atlas = Zeroizing::new(
        include_bytes!("../../../../tests/media/video-timeline/reference-storyboard.jpg").to_vec(),
    );
    derived::put(
        &mut session,
        &object_id,
        StreamKind::VideoStoryboard,
        640,
        270,
        &atlas,
        NOW,
    )
    .unwrap();
    let mut reader = reader::open(&session, &object_id, StreamKind::VideoStoryboard).unwrap();
    assert_eq!(reader.dimensions(), (640, 270));
    assert_eq!(
        reader.read_range(0, atlas.len() as u64).unwrap().as_slice(),
        atlas.as_slice()
    );
    assert_eq!(
        derived::put(
            &mut session,
            &object_id,
            StreamKind::VideoStoryboard,
            641,
            270,
            &atlas,
            NOW
        )
        .unwrap_err()
        .status(),
        ChurStatus::ResourceLimitExceeded
    );
    let mut exported = Vec::new();
    export::export_stream(
        &session,
        &object_id,
        StreamKind::Original,
        &mut exported,
        &mut Uninterrupted,
    )
    .unwrap();
    assert_eq!(exported.as_slice(), original.as_slice());
}

#[test]
fn a_viewing_copy_cannot_be_attached_to_a_nonvideo_object() {
    let (_root, mut session) = new_vault();
    let (object_id, _) = import_photo(&mut session, 512, "source.jpg");
    let copy = Zeroizing::new(
        include_bytes!("../../../../tests/media/video-compatibility/reference-sdr.mp4").to_vec(),
    );
    assert_eq!(
        derived::put(
            &mut session,
            &object_id,
            StreamKind::CompatibleVideo,
            192,
            96,
            &copy,
            NOW
        )
        .unwrap_err()
        .status(),
        ChurStatus::InvalidInput
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
fn empty_opaque_files_authenticate_and_export_without_chunks() {
    let (_root, mut session) = new_vault();
    for streamed in [false, true] {
        let capability = SourceCapability {
            requested_object_id: None,
            seekable: !streamed,
            known_length: if streamed { None } else { Some(0) },
            content_type_hint: String::from("application/octet-stream"),
            original_filename: Some(String::from("empty.bin")),
            capture_time_ms: None,
        };
        let object_id = if streamed {
            let pending = import::begin(&mut session, capability, CanonicalMedia::opaque(), NOW)
                .expect("begin empty file");
            import::stream_into(
                pending,
                &mut session,
                &mut std::io::empty(),
                "application/octet-stream",
                NOW,
                &mut Uninterrupted,
            )
        } else {
            import::import_bytes(
                &mut session,
                capability,
                CanonicalMedia::opaque(),
                "application/octet-stream",
                &Zeroizing::new(Vec::new()),
                NOW,
            )
        }
        .expect("import empty file");
        let mut handle = reader::open(&session, &object_id, StreamKind::Original).expect("open");
        assert_eq!(handle.size(), 0);
        assert!(handle.read_range(0, 0).expect("read empty file").is_empty());
        let mut destination = Vec::new();
        assert_eq!(
            export::export_stream(
                &session,
                &object_id,
                StreamKind::Original,
                &mut destination,
                &mut Uninterrupted,
            )
            .expect("export empty file"),
            0
        );
        assert!(destination.is_empty());
        assert!(
            journal::live(session.catalog_ref().unwrap())
                .unwrap()
                .is_empty()
        );
    }
}

#[test]
fn opaque_chunking_reaches_the_object_bound_with_unknown_or_large_sources() {
    let (_root, mut session) = new_vault();
    let small_max =
        u64::from(import::DEFAULT_CHUNK_SIZE) * chur_core::limits::container::CHUNK_COUNT_MAX;
    for (length, expected_chunk) in [
        (Some(0), import::VIDEO_CHUNK_SIZE),
        (Some(small_max), import::VIDEO_CHUNK_SIZE),
        (Some(small_max + 1), import::VIDEO_CHUNK_SIZE),
        (
            Some(chur_core::limits::container::TOTAL_PLAINTEXT_MAX),
            import::VIDEO_CHUNK_SIZE,
        ),
        (None, import::VIDEO_CHUNK_SIZE),
    ] {
        let mut capability = source("file.bin", 0);
        capability.known_length = length;
        let pending = import::begin(&mut session, capability, CanonicalMedia::opaque(), NOW)
            .expect("begin file within the object bound");
        assert_eq!(pending.chunk_size(), expected_chunk);
        pending.abandon(&mut session).expect("abandon");
    }
    let mut capability = source("oversized.bin", 0);
    capability.known_length = Some(chur_core::limits::container::TOTAL_PLAINTEXT_MAX + 1);
    assert_eq!(
        rejection(import::begin(
            &mut session,
            capability,
            CanonicalMedia::opaque(),
            NOW
        )),
        ChurStatus::ResourceLimitExceeded
    );
    assert!(
        journal::live(session.catalog_ref().unwrap())
            .unwrap()
            .is_empty()
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

/// A caller that cancels once it has seen `after` bytes.
///
/// It records what it saw, so a test can assert that the operation stopped
/// where the flag was raised rather than at the end of the object.
struct CancelAfter {
    after: u64,
    seen: u64,
    cancelled: bool,
}

impl CancelAfter {
    const fn new(after: u64) -> Self {
        Self {
            after,
            seen: 0,
            cancelled: false,
        }
    }
}

impl chur_media::progress::Progress for CancelAfter {
    fn cancelled(&self) -> bool {
        self.cancelled
    }

    fn advance(&mut self, processed: u64) {
        self.seen = processed;
        if processed >= self.after {
            self.cancelled = true;
        }
    }
}

/// `FFI_CONTRACT.md` §9: an operation observes cancellation without waiting for
/// its own completion. Before this, an export checked the flag once before it
/// started and then ran to the end, so a large export could not be stopped.
#[test]
fn an_export_stops_where_it_is_cancelled_rather_than_at_the_end() {
    let (_root, mut session) = new_vault();
    let bytes = plaintext(262_144 * 6);
    let object_id = import::import_bytes(
        &mut session,
        source("long.bin", bytes.len() as u64),
        photo(),
        "image/jpeg",
        &bytes,
        NOW,
    )
    .expect("import");

    let mut out: Vec<u8> = Vec::new();
    let mut progress = CancelAfter::new(262_144 * 2);
    let status = rejection(export::export_stream(
        &session,
        &object_id,
        StreamKind::Original,
        &mut out,
        &mut progress,
    ));

    assert_eq!(status, ChurStatus::Cancelled);
    // It wrote what it had authenticated and no more, and it stopped well
    // before the end rather than after the last chunk.
    assert_eq!(out.len() as u64, progress.seen);
    assert!(out.len() < bytes.len());
    assert_eq!(&out[..], &bytes[..out.len()]);
}

/// The same guarantee for the import half, and the §14.4 ordering with it: a
/// cancelled import leaves no object and no live journal record.
#[test]
fn a_cancelled_import_activates_nothing_and_leaves_no_live_transaction() {
    let (_root, mut session) = new_vault();
    let bytes = plaintext(262_144 * 5);
    let running = import::begin(
        &mut session,
        source("long.bin", bytes.len() as u64),
        photo(),
        NOW,
    )
    .expect("begin");
    let mut cursor = std::io::Cursor::new(bytes.to_vec());
    let mut progress = CancelAfter::new(262_144 * 2);
    let status = rejection(import::stream_into(
        running,
        &mut session,
        &mut cursor,
        "image/jpeg",
        NOW,
        &mut progress,
    ));

    assert_eq!(status, ChurStatus::Cancelled);
    assert!(
        journal::live(session.catalog_ref().unwrap())
            .unwrap()
            .is_empty(),
        "a cancelled import left a live journal record"
    );
    let listed = page(session.catalog_ref().unwrap(), &ObjectQuery::timeline()).expect("page");
    assert_eq!(listed.total_count, 0);
}

#[test]
fn a_streamed_source_cannot_commit_a_prefix_but_an_underreported_hint_keeps_all_bytes() {
    use std::io::Write;
    use std::os::unix::net::UnixStream;

    let (_root, mut session) = new_vault();
    for (actual, reported, accepted) in [
        (128, 256, false),
        (0, 256, false),
        (513, 0, true),
        (0, 0, true),
    ] {
        let before = page(session.catalog_ref().unwrap(), &ObjectQuery::timeline())
            .unwrap()
            .total_count;
        let mut capability = source("shared.bin", reported);
        capability.seekable = false;
        let pending =
            import::begin(&mut session, capability, CanonicalMedia::opaque(), NOW).unwrap();
        let bytes = vec![0x42; actual];
        let (mut input, mut output) = UnixStream::pair().unwrap();
        output.write_all(&bytes).unwrap();
        drop(output);
        let result = import::stream_into(
            pending,
            &mut session,
            &mut input,
            "application/octet-stream",
            NOW,
            &mut Uninterrupted,
        );
        if accepted {
            let object = result.expect("complete source");
            let mut exported = Vec::new();
            export::export_stream(
                &session,
                &object,
                StreamKind::Original,
                &mut exported,
                &mut Uninterrupted,
            )
            .unwrap();
            assert_eq!(exported, bytes);
        } else {
            assert_eq!(rejection(result), ChurStatus::IoFailure);
        }
        assert!(
            journal::live(session.catalog_ref().unwrap())
                .unwrap()
                .is_empty()
        );
        assert_eq!(
            page(session.catalog_ref().unwrap(), &ObjectQuery::timeline())
                .unwrap()
                .total_count,
            before + u64::from(accepted)
        );
    }
}

#[test]
fn cancellation_on_the_last_short_chunk_does_not_activate_an_original() {
    let (_root, mut session) = new_vault();
    let pending = import::begin(
        &mut session,
        source("shared.bin", 513),
        CanonicalMedia::opaque(),
        NOW,
    )
    .unwrap();
    let mut input = std::io::Cursor::new(vec![0x42; 513]);
    let status = rejection(import::stream_into(
        pending,
        &mut session,
        &mut input,
        "application/octet-stream",
        NOW,
        &mut CancelAfter::new(1),
    ));
    assert_eq!(status, ChurStatus::Cancelled);
    assert!(
        journal::live(session.catalog_ref().unwrap())
            .unwrap()
            .is_empty()
    );
    assert_eq!(
        page(session.catalog_ref().unwrap(), &ObjectQuery::timeline())
            .unwrap()
            .total_count,
        0
    );
}

/// An integrity scan of one long object must observe cancellation inside the
/// object, and a cancelled scan records no verdict: it proved nothing.
#[test]
fn a_cancelled_scan_records_no_verdict() {
    let (_root, mut session) = new_vault();
    let bytes = plaintext(262_144 * 4);
    let object_id = import::import_bytes(
        &mut session,
        source("long.bin", bytes.len() as u64),
        photo(),
        "image/jpeg",
        &bytes,
        NOW,
    )
    .expect("import");
    // The import already verified the container, so reset the summary to the
    // state a scan would start from.
    store::set_integrity_summary(
        session.catalog().unwrap(),
        &object_id,
        IntegritySummary::Unverified,
        NOW,
    )
    .expect("reset");

    struct Always;
    impl chur_media::progress::Progress for Always {
        fn cancelled(&self) -> bool {
            true
        }
        fn advance(&mut self, _processed: u64) {}
    }

    let status = rejection(integrity::scan_object_with(
        &mut session,
        &object_id,
        NOW,
        &mut Always,
    ));
    assert_eq!(status, ChurStatus::Cancelled);

    let row = store::object(session.catalog_ref().unwrap(), &object_id).expect("object");
    assert_eq!(row.state, ObjectState::Active);
    assert_eq!(row.integrity_summary, IntegritySummary::Unverified);
}

/// `MEDIA_PIPELINE.md` §6 lists a video poster frame and an audio waveform as
/// derived kinds, and `CANONICAL_ENCODING_V1.md` §15.4 allocates both. Before
/// Phase 2 neither could be produced: `needs` had no media class, so it decided
/// a poster by pixel size and said no to every 1080p video, and returned false
/// for every waveform because the kind has no long edge.
#[test]
fn a_video_needs_a_poster_at_every_resolution_and_audio_needs_a_waveform() {
    use chur_format::constants::MediaClass;

    // The size a 1080p video reports is inside the 2048 px poster target, which
    // is exactly the case the old rule refused.
    assert!(derived::needs(
        StreamKind::VideoPoster,
        MediaClass::Video,
        1_920,
        1_080
    ));
    assert!(derived::needs(
        StreamKind::VideoPoster,
        MediaClass::Video,
        3_840,
        2_160
    ));
    assert!(derived::needs(
        StreamKind::AudioWaveform,
        MediaClass::Audio,
        0,
        0
    ));

    // And neither kind is generated for a class that has no use for it.
    assert!(!derived::needs(
        StreamKind::VideoPoster,
        MediaClass::Image,
        4_000,
        3_000
    ));
    assert!(!derived::needs(
        StreamKind::AudioWaveform,
        MediaClass::Video,
        1_920,
        1_080
    ));
    // An image inside the grid target is its own preview.
    assert!(!derived::needs(
        StreamKind::GridPreview,
        MediaClass::Image,
        500,
        400
    ));
    assert!(derived::needs(
        StreamKind::GridPreview,
        MediaClass::Image,
        4_000,
        3_000
    ));
}

#[test]
fn audio_artwork_uses_picture_assets_without_audio_pixel_dimensions() {
    for kind in [
        StreamKind::ThumbnailSmall,
        StreamKind::GridPreview,
        StreamKind::ScreenPreview,
    ] {
        assert!(derived::needs(kind, MediaClass::Audio, 0, 0));
        assert!(!derived::needs(kind, MediaClass::Opaque, 0, 0));
    }
    assert!(!derived::needs(
        StreamKind::Original,
        MediaClass::Audio,
        0,
        0
    ));
    assert!(!derived::needs(
        StreamKind::VideoPoster,
        MediaClass::Audio,
        0,
        0
    ));
}

#[test]
fn audio_artwork_round_trips_encrypted_without_changing_the_audio_original() {
    let (_root, mut session) = new_vault();
    let original = include_bytes!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../../../apps/androidApp/src/androidTest/assets/audio-art/with-art.mp3"
    ));
    let cover = include_bytes!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../../../apps/androidApp/src/androidTest/assets/audio-art/cover.jpg"
    ));
    let object_id = import::import_bytes(
        &mut session,
        SourceCapability {
            requested_object_id: None,
            seekable: true,
            known_length: Some(original.len() as u64),
            content_type_hint: String::from("audio/mpeg"),
            original_filename: Some(String::from("fixture.mp3")),
            capture_time_ms: None,
        },
        CanonicalMedia {
            media_class: MediaClass::Audio,
            width: 0,
            height: 0,
            duration_ms: 150,
        },
        "audio/mpeg",
        &Zeroizing::new(original.to_vec()),
        NOW,
    )
    .expect("import audio");
    for kind in [
        StreamKind::ThumbnailSmall,
        StreamKind::GridPreview,
        StreamKind::ScreenPreview,
    ] {
        derived::put(
            &mut session,
            &object_id,
            kind,
            96,
            48,
            &Zeroizing::new(cover.to_vec()),
            NOW,
        )
        .expect("encrypt audio artwork");
        let read = derived::read(&session, &object_id, kind).expect("read artwork");
        assert_eq!(read.as_slice(), cover);
        assert_eq!(
            reader::open(&session, &object_id, kind)
                .unwrap()
                .dimensions(),
            (96, 48)
        );
    }
    assert!(
        store::object(session.catalog_ref().unwrap(), &object_id)
            .unwrap()
            .thumbnail_ready
    );
    let mut read_original = reader::open(&session, &object_id, StreamKind::Original).unwrap();
    assert_eq!(read_original.dimensions(), (0, 0));
    assert_eq!(
        read_original
            .read_range(0, original.len() as u64)
            .unwrap()
            .as_slice(),
        original
    );
}

/// A waveform is stored, read back, and parsed by the same record the shared
/// renderer reads, so a container that holds one holds a waveform rather than
/// arbitrary bytes.
#[test]
fn a_waveform_round_trips_through_a_container() {
    use chur_format::waveform::Waveform;

    let (_root, mut session) = new_vault();
    let (object_id, _bytes) = import_photo(&mut session, 4_096, "voice.m4a");

    let peaks: Vec<u8> = (0..512).map(|index| ((index * 7) % 256) as u8).collect();
    let record = Waveform::new(183_000, peaks.clone()).expect("build the waveform");
    let stored = Zeroizing::new(record.encode());
    derived::put(
        &mut session,
        &object_id,
        StreamKind::AudioWaveform,
        0,
        0,
        &stored,
        NOW,
    )
    .expect("store the waveform");

    let read = derived::read(&session, &object_id, StreamKind::AudioWaveform).expect("read");
    let decoded = Waveform::decode(&read).expect("decode");
    assert_eq!(decoded.duration_ms(), 183_000);
    assert_eq!(decoded.peaks(), &peaks[..]);
}

/// The waveform kind has no pixel edge, so its record is what bounds it. Bytes
/// that do not parse are refused before they are sealed, rather than sealed and
/// found unreadable by whichever host tries to draw them.
#[test]
fn a_waveform_that_does_not_parse_is_refused_before_it_is_stored() {
    let (_root, mut session) = new_vault();
    let (object_id, _bytes) = import_photo(&mut session, 4_096, "voice.m4a");

    let junk = Zeroizing::new(vec![0xff_u8; 64]);
    assert_eq!(
        rejection(derived::put(
            &mut session,
            &object_id,
            StreamKind::AudioWaveform,
            0,
            0,
            &junk,
            NOW,
        )),
        ChurStatus::UnsupportedVersion
    );

    let oversize = Zeroizing::new(
        chur_format::waveform::Waveform::new(1_000, vec![1; 64])
            .expect("build")
            .encode()
            .into_iter()
            .chain(std::iter::once(0))
            .collect::<Vec<u8>>(),
    );
    assert_eq!(
        rejection(derived::put(
            &mut session,
            &object_id,
            StreamKind::AudioWaveform,
            0,
            0,
            &oversize,
            NOW,
        )),
        ChurStatus::NonCanonicalEncoding
    );
}

/// A poster frame is an image derivative and is bound to the object, the kind,
/// and the source content revision like every other one.
#[test]
fn a_poster_frame_is_stored_and_read_like_any_image_derivative() {
    let (_root, mut session) = new_vault();
    let (object_id, _bytes) = import_photo(&mut session, 8_192, "clip.mp4");

    let poster = plaintext(20_000);
    derived::put(
        &mut session,
        &object_id,
        StreamKind::VideoPoster,
        1_920,
        1_080,
        &poster,
        NOW,
    )
    .expect("store the poster");

    let read = derived::read(&session, &object_id, StreamKind::VideoPoster).expect("read");
    assert_eq!(&read[..], &poster[..]);

    // §12's long edge still bounds it: a poster larger than the target is a
    // generator that ignored its own profile.
    assert_eq!(
        rejection(derived::put(
            &mut session,
            &object_id,
            StreamKind::VideoPoster,
            4_096,
            2_160,
            &poster,
            NOW,
        )),
        ChurStatus::ResourceLimitExceeded
    );
}

#[test]
fn a_persisted_transfer_identity_commits_once_and_cannot_overwrite() {
    let (root, mut session) = new_vault();
    let id = random::id().unwrap();
    let mut capability = source("source.bin", 8);
    capability.requested_object_id = Some(id);
    let imported = import::import_bytes(
        &mut session,
        capability.clone(),
        CanonicalMedia::opaque(),
        "application/octet-stream",
        &Zeroizing::new(b"original".to_vec()),
        NOW,
    )
    .unwrap();
    assert_eq!(imported, id);
    assert_eq!(
        rejection(import::begin(
            &mut session,
            capability,
            CanonicalMedia::opaque(),
            NOW
        )),
        ChurStatus::InvalidInput
    );
    let object = store::object(session.catalog_ref().unwrap(), &id).unwrap();
    assert_eq!(object.plaintext_size, 8);
    assert!(store::object_id_reserved(session.catalog_ref().unwrap(), &id).unwrap());
    drop(session);
    std::fs::remove_dir_all(root.base()).unwrap();
}

#[test]
fn a_transfer_identity_is_exclusive_until_abandonment_and_reusable_afterwards() {
    let (root, mut session) = new_vault();
    let mut capability = source("retry.bin", 8);
    let id = random::id().unwrap();
    capability.requested_object_id = Some(id);
    let pending = import::begin(
        &mut session,
        capability.clone(),
        CanonicalMedia::opaque(),
        NOW,
    )
    .unwrap();
    assert!(store::object_id_reserved(session.catalog_ref().unwrap(), &id).unwrap());
    assert_eq!(
        rejection(import::begin(
            &mut session,
            capability.clone(),
            CanonicalMedia::opaque(),
            NOW
        )),
        ChurStatus::InvalidInput
    );
    pending.abandon(&mut session).unwrap();
    assert!(!store::object_id_reserved(session.catalog_ref().unwrap(), &id).unwrap());
    assert_eq!(
        import::import_bytes(
            &mut session,
            capability,
            CanonicalMedia::opaque(),
            "application/octet-stream",
            &Zeroizing::new(b"original".to_vec()),
            NOW
        )
        .unwrap(),
        id
    );
    drop(session);
    std::fs::remove_dir_all(root.base()).unwrap();
}
