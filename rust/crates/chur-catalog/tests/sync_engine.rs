//! Locked staging to unlocked catalog acceptance.

#![allow(clippy::expect_used)]

use chur_catalog::db::{CatalogKey, CatalogLocation};
use chur_catalog::paths::VaultRoot;
use chur_catalog::sync_engine::{self, StagedKind};
use chur_catalog::{CatalogDb, schema, sync_receive, sync_staging::LockedStaging};
use chur_core::Id;
use chur_crypto::{Key, random};
use chur_sync_protocol::membership::EnrollmentRecord;
use chur_sync_protocol::operation::DeviceSigningKey;

fn id(byte: u8) -> Id {
    Id::new([byte; 16]).expect("id")
}

#[test]
fn unlocked_processing_removes_validated_records_and_reports_rejections() {
    let vault_id = id(1);
    let root = Key::new([2; 32]);
    let catalog_key = CatalogKey::derive(&root, &vault_id).expect("catalog key");
    let mut db = CatalogDb::open(&CatalogLocation::Memory, &catalog_key).expect("catalog");
    schema::open_at_current_version(&mut db, 1).expect("schema");
    let signing_key = DeviceSigningKey::from_seed([3; 32]);
    let enrollment =
        EnrollmentRecord::initial(vault_id, id(4), signing_key.verifying_key(), [5; 32])
            .expect("enrollment")
            .sign(&signing_key);
    let (membership, mut log, operation) =
        sync_receive::provision_initial_membership(&mut db, &root, &signing_key, &enrollment)
            .expect("provision");
    let checkpoint = log
        .issue_own_checkpoint(&mut db, &membership, &id(4), &signing_key, 7)
        .expect("checkpoint");
    let path = std::env::temp_dir().join(format!(
        "chur-sync-engine-{}",
        random::id().expect("random id").to_hex()
    ));
    let vault_root = VaultRoot::new(&path);
    sync_engine::stage_inbound(
        &vault_root,
        vault_id,
        StagedKind::Operation,
        8,
        &operation.encode(),
    )
    .expect("stage operation");
    sync_engine::stage_inbound(
        &vault_root,
        vault_id,
        StagedKind::Checkpoint,
        9,
        &checkpoint.encode(),
    )
    .expect("stage checkpoint");
    sync_engine::stage_inbound(
        &vault_root,
        vault_id,
        StagedKind::Operation,
        10,
        b"malformed",
    )
    .expect("stage malformed");
    let mut staging = LockedStaging::open(vault_root.sync_inbox(&vault_id)).expect("staging");

    let report =
        sync_engine::process_staged(&mut db, &root, vault_id, &mut staging, 10).expect("process");

    assert_eq!(report.duplicates, 2);
    assert_eq!(report.rejected, 1);
    assert_eq!(
        report.first_rejection,
        Some(chur_core::ChurStatus::UnsupportedVersion)
    );
    assert_eq!(staging.len(10).expect("remaining"), 0);
    std::fs::remove_dir_all(path).expect("cleanup");
}
