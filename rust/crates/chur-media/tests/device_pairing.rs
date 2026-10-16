//! Restored backup to fresh ordinary identity through an authorized device.
#![allow(clippy::expect_used, clippy::unwrap_used)]
use chur_catalog::{device_pairing, paths::VaultRoot, sync_receive, vault};
use chur_crypto::random;
use chur_media::{backup, progress::Uninterrupted};
use std::io::Cursor;
const PASSWORD: &[u8] = b"pairing test strong password";
const NOW: u64 = 1_700_000_000_000;
fn root() -> VaultRoot {
    let path =
        std::env::temp_dir().join(format!("chur-pairing-{}", random::id().unwrap().to_hex()));
    std::fs::create_dir_all(&path).unwrap();
    VaultRoot::new(path)
}
fn receive_device(
    source: &chur_catalog::CatalogDb,
    device: &chur_core::Id,
    target: &mut vault::Session,
    target_root: &VaultRoot,
    secret: &chur_crypto::Key,
    vault_id: chur_core::Id,
) {
    for record in chur_catalog::sync_log::records_after(source, device, 0).unwrap() {
        chur_catalog::sync_engine::stage_inbound(
            target_root,
            vault_id,
            chur_catalog::sync_engine::StagedKind::Operation,
            NOW + 9,
            &record,
        )
        .unwrap();
    }
    let mut staging =
        chur_catalog::sync_staging::LockedStaging::open(target_root.sync_inbox(&vault_id)).unwrap();
    let report = chur_catalog::sync_engine::process_staged(
        target.catalog().unwrap(),
        secret,
        vault_id,
        &mut staging,
        NOW + 9,
    )
    .unwrap();
    assert_eq!(report.rejected, 0);
    assert_eq!(report.pending, 0);
}
#[test]
fn restored_backup_prepares_a_new_identity_without_reusing_the_recovery_signer() {
    let source_root = root();
    let mut source = vault::create(&source_root, PASSWORD, NOW)
        .unwrap()
        .activate()
        .unwrap();
    let secret = source.root_secret().unwrap().duplicate();
    let vault_id = source.vault_id();
    let (original, _) =
        sync_receive::provision_local_identity(source.catalog().unwrap(), &secret, vault_id)
            .unwrap();
    let mut package = Cursor::new(Vec::new());
    backup::create(&mut source, &mut package, NOW, &mut Uninterrupted).unwrap();
    let target_root = root();
    backup::restore(&target_root, &mut package, PASSWORD, &mut Uninterrupted).unwrap();
    let mut target = vault::unlock_with_password(&target_root, PASSWORD, NOW + 1).unwrap();
    let request = device_pairing::prepare_request(target.catalog().unwrap(), &secret, vault_id)
        .expect("restored device must prepare its own fresh identity");
    assert_ne!(request.device_id(), original.device_id());
    assert_eq!(request.vault_id(), &vault_id);
    request.verify_root(&secret).unwrap();
    assert_eq!(
        device_pairing::prepare_request(target.catalog().unwrap(), &secret, vault_id)
            .unwrap()
            .encode(),
        request.encode()
    );
    let prepared = device_pairing::approve(
        source.catalog().unwrap(),
        &secret,
        vault_id,
        &request,
        "https://sync.example",
        &[88; 32],
        NOW + 2,
    )
    .expect("authorized device approves");
    let replay = device_pairing::approve(
        source.catalog().unwrap(),
        &secret,
        vault_id,
        &request,
        "https://sync.example",
        &[99; 32],
        NOW + 3,
    )
    .expect("approval replay");
    assert_eq!(prepared.approval, replay.approval);
    assert_eq!(replay.transport_token, [88; 32]);
    let mut damaged = prepared.approval.clone();
    *damaged.last_mut().unwrap() ^= 1;
    assert!(
        device_pairing::accept_start(target.catalog().unwrap(), &secret, vault_id, &damaged)
            .is_err()
    );
    let credentials = device_pairing::accept_start(
        target.catalog().unwrap(),
        &secret,
        vault_id,
        &prepared.approval,
    )
    .expect("authority verified before credentials leave native code");
    assert_eq!(credentials.server_url, "https://sync.example");
    assert_eq!(credentials.token, [88; 32]);
    assert_eq!(
        device_pairing::finalize(target.catalog().unwrap(), &secret, vault_id, NOW + 4)
            .unwrap_err()
            .status(),
        chur_core::ChurStatus::RecoveryRequired
    );
    let records = chur_catalog::sync_log::records_after(
        source.catalog_ref().unwrap(),
        original.device_id(),
        0,
    )
    .unwrap();
    for record in records {
        chur_catalog::sync_engine::stage_inbound(
            &target_root,
            vault_id,
            chur_catalog::sync_engine::StagedKind::Operation,
            NOW + 4,
            &record,
        )
        .unwrap();
    }
    let mut staging =
        chur_catalog::sync_staging::LockedStaging::open(target_root.sync_inbox(&vault_id)).unwrap();
    let report = chur_catalog::sync_engine::process_staged(
        target.catalog().unwrap(),
        &secret,
        vault_id,
        &mut staging,
        NOW + 4,
    )
    .unwrap();
    assert_eq!(report.rejected, 0);
    assert_eq!(report.pending, 0);
    // Newer authenticated state may arrive while bootstrap prefixes download.
    let source_membership = chur_catalog::sync_membership::load(source.catalog_ref().unwrap())
        .unwrap()
        .unwrap();
    let (_, source_identity) = chur_catalog::sync_keys::local_identity(
        source.catalog_ref().unwrap(),
        &secret,
        &source_membership,
    )
    .unwrap()
    .unwrap();
    let mut source_log =
        chur_catalog::sync_log::load(source.catalog_ref().unwrap(), &source_membership).unwrap();
    let newer_checkpoint = source_log
        .issue_own_checkpoint(
            source.catalog().unwrap(),
            &source_membership,
            original.device_id(),
            source_identity.signing_key(),
            NOW + 4,
        )
        .unwrap();
    let target_membership = chur_catalog::sync_membership::load(target.catalog_ref().unwrap())
        .unwrap()
        .unwrap();
    let mut target_log =
        chur_catalog::sync_log::load(target.catalog_ref().unwrap(), &target_membership).unwrap();
    let approved_checkpoint =
        chur_sync_protocol::checkpoint::Checkpoint::decode(&prepared.checkpoint).unwrap();
    assert!(matches!(
        target_log
            .accept_checkpoint(
                target.catalog().unwrap(),
                &approved_checkpoint,
                &target_membership,
                false,
                NOW + 4
            )
            .expect("exact trusted checkpoint replay remains idempotent after enrollment"),
        chur_sync_protocol::operation_log::CheckpointOutcome::Unchanged
    ));
    target_log
        .accept_checkpoint(
            target.catalog().unwrap(),
            &newer_checkpoint,
            &target_membership,
            false,
            NOW + 4,
        )
        .expect("a pending device can accept newer source checkpoint evidence");
    assert_eq!(
        target_log.checkpoint_commitment(original.device_id()),
        Some(&newer_checkpoint.commitment()),
        "durable and in-memory checkpoint evidence must agree before finalization"
    );
    assert_eq!(
        target_log
            .accept_checkpoint(
                target.catalog().unwrap(),
                &approved_checkpoint,
                &target_membership,
                false,
                NOW + 4
            )
            .expect_err("unknown stale membership checkpoint is still rejected")
            .status(),
        chur_core::ChurStatus::SyncHeadRollback
    );
    assert_eq!(
        device_pairing::finalize(target.catalog().unwrap(), &secret, vault_id, NOW + 5).unwrap(),
        *request.device_id()
    );
    assert_eq!(
        device_pairing::finalize(target.catalog().unwrap(), &secret, vault_id, NOW + 6).unwrap(),
        *request.device_id()
    );
    let membership = chur_catalog::sync_membership::load(target.catalog_ref().unwrap())
        .unwrap()
        .unwrap();
    let (new_device, _) = chur_catalog::sync_keys::local_identity(
        target.catalog_ref().unwrap(),
        &secret,
        &membership,
    )
    .unwrap()
    .unwrap();
    assert_eq!(new_device, *request.device_id());
    let reloaded =
        chur_catalog::sync_log::load(target.catalog_ref().unwrap(), &membership).unwrap();
    assert_eq!(
        reloaded.checkpoint_commitment(original.device_id()),
        Some(&newer_checkpoint.commitment())
    );
    assert_eq!(
        reloaded.floor(original.device_id()).unwrap().0,
        newer_checkpoint
            .heads()
            .iter()
            .find(|head| head.device_id() == original.device_id())
            .unwrap()
            .device_sequence()
    );
    let first = chur_catalog::sync_log::record_at(target.catalog_ref().unwrap(), &new_device, 1)
        .unwrap()
        .unwrap();
    let ready = chur_sync_protocol::operation::Operation::decode(&first).unwrap();
    let keys = chur_sync_protocol::KeyDirectory::new(&secret, &vault_id).unwrap();
    assert!(matches!(
        chur_sync_protocol::payload::OperationPayload::open_for_operation(&ready, &keys)
            .unwrap()
            .body(),
        chur_sync_protocol::payload::PayloadBody::DeviceReady
    ));
    let retry = device_pairing::accept_start(
        target.catalog().unwrap(),
        &secret,
        vault_id,
        &prepared.approval,
    )
    .unwrap();
    assert_eq!(retry.token, [88; 32]);
    let mut replacement_backup = Cursor::new(Vec::new());
    backup::create(
        &mut target,
        &mut replacement_backup,
        NOW + 7,
        &mut Uninterrupted,
    )
    .expect("fresh paired identity supports backup immediately");
    let final_root = root();
    backup::restore(
        &final_root,
        &mut replacement_backup,
        PASSWORD,
        &mut Uninterrupted,
    )
    .expect("fresh paired backup restores");
    // An approval from a locally revoked issuer must not release credentials.
    receive_device(
        target.catalog_ref().unwrap(),
        request.device_id(),
        &mut source,
        &source_root,
        &secret,
        vault_id,
    );
    let mut pending = vault::unlock_with_password(&final_root, PASSWORD, NOW + 8).unwrap();
    let another =
        device_pairing::prepare_request(pending.catalog().unwrap(), &secret, vault_id).unwrap();
    assert!(
        device_pairing::accept_start(
            pending.catalog().unwrap(),
            &secret,
            vault_id,
            &prepared.approval
        )
        .is_err()
    );
    assert_eq!(
        device_pairing::accept_start(
            pending.catalog().unwrap(),
            &secret,
            random::id().unwrap(),
            &prepared.approval
        )
        .err()
        .unwrap()
        .status(),
        chur_core::ChurStatus::AuthenticationFailed
    );
    assert!(
        device_pairing::approve(
            source.catalog().unwrap(),
            &chur_crypto::Key::new([44; 32]),
            vault_id,
            &another,
            "https://sync.example",
            &[90; 32],
            NOW + 9
        )
        .is_err()
    );
    let next = device_pairing::approve(
        source.catalog().unwrap(),
        &secret,
        vault_id,
        &another,
        "https://sync.example",
        &[90; 32],
        NOW + 9,
    )
    .unwrap();
    receive_device(
        source.catalog_ref().unwrap(),
        original.device_id(),
        &mut target,
        &target_root,
        &secret,
        vault_id,
    );
    let current = chur_catalog::sync_membership::load(target.catalog_ref().unwrap())
        .unwrap()
        .unwrap();
    let (_, local) =
        chur_catalog::sync_keys::local_identity(target.catalog_ref().unwrap(), &secret, &current)
            .unwrap()
            .unwrap();
    let invalid_genesis = chur_sync_protocol::membership::RevocationRecord::new(
        vault_id,
        *original.device_id(),
        0,
        [0; 32],
        current.generation() + 1,
        *request.device_id(),
        *current.commitment(),
    )
    .unwrap()
    .sign(local.signing_key());
    assert_eq!(
        chur_catalog::sync_membership::accept_revocation(
            target.catalog().unwrap(),
            &invalid_genesis,
            request.device_id()
        )
        .err()
        .unwrap()
        .status(),
        chur_core::ChurStatus::AuthenticationFailed
    );
    let revocation = device_pairing::prepare_revoke(
        target.catalog().unwrap(),
        &secret,
        vault_id,
        *original.device_id(),
        NOW + 10,
    )
    .unwrap();
    assert_eq!(
        chur_sync_protocol::membership::RevocationRecord::decode(&revocation.revocation)
            .unwrap()
            .final_accepted_device_sequence(),
        3
    );
    receive_device(
        source.catalog_ref().unwrap(),
        original.device_id(),
        &mut pending,
        &final_root,
        &secret,
        vault_id,
    );
    receive_device(
        target.catalog_ref().unwrap(),
        request.device_id(),
        &mut pending,
        &final_root,
        &secret,
        vault_id,
    );
    assert_eq!(
        device_pairing::accept_start(
            pending.catalog().unwrap(),
            &secret,
            vault_id,
            &next.approval
        )
        .err()
        .unwrap()
        .status(),
        chur_core::ChurStatus::AuthenticationFailed
    );
}

#[test]
fn approval_can_be_revoked_before_the_new_device_ever_authors() {
    let source_root = root();
    let mut source = vault::create(&source_root, PASSWORD, NOW)
        .unwrap()
        .activate()
        .unwrap();
    let secret = source.root_secret().unwrap().duplicate();
    let vault_id = source.vault_id();
    let (original, _) =
        sync_receive::provision_local_identity(source.catalog().unwrap(), &secret, vault_id)
            .unwrap();
    let mut package = Cursor::new(Vec::new());
    backup::create(&mut source, &mut package, NOW, &mut Uninterrupted).unwrap();
    let target_root = root();
    backup::restore(&target_root, &mut package, PASSWORD, &mut Uninterrupted).unwrap();
    let mut target = vault::unlock_with_password(&target_root, PASSWORD, NOW + 1).unwrap();
    let request =
        device_pairing::prepare_request(target.catalog().unwrap(), &secret, vault_id).unwrap();
    let prepared = device_pairing::approve(
        source.catalog().unwrap(),
        &secret,
        vault_id,
        &request,
        "https://sync.example",
        &[88; 32],
        NOW + 2,
    )
    .unwrap();
    assert_eq!(
        device_pairing::overview(source.catalog_ref().unwrap(), &secret, vault_id)
            .unwrap()
            .len(),
        2
    );
    assert_eq!(
        device_pairing::prepare_revoke(
            source.catalog().unwrap(),
            &secret,
            vault_id,
            *original.device_id(),
            NOW + 3
        )
        .err()
        .unwrap()
        .status(),
        chur_core::ChurStatus::InvalidInput
    );
    let revoked = device_pairing::prepare_revoke(
        source.catalog().unwrap(),
        &secret,
        vault_id,
        *request.device_id(),
        NOW + 3,
    )
    .expect("lost approval can be revoked without a first operation");
    let record =
        chur_sync_protocol::membership::RevocationRecord::decode(&revoked.revocation).unwrap();
    assert_eq!(record.final_accepted_device_sequence(), 0);
    assert_eq!(record.final_accepted_operation_digest(), &[0; 32]);
    let retry = device_pairing::prepare_revoke(
        source.catalog().unwrap(),
        &secret,
        vault_id,
        *request.device_id(),
        NOW + 4,
    )
    .unwrap();
    assert_eq!(retry.operation, revoked.operation);
    device_pairing::accept_start(
        target.catalog().unwrap(),
        &secret,
        vault_id,
        &prepared.approval,
    )
    .unwrap();
    for record in chur_catalog::sync_log::records_after(
        source.catalog_ref().unwrap(),
        original.device_id(),
        0,
    )
    .unwrap()
    {
        chur_catalog::sync_engine::stage_inbound(
            &target_root,
            vault_id,
            chur_catalog::sync_engine::StagedKind::Operation,
            NOW + 4,
            &record,
        )
        .unwrap();
    }
    let mut staging =
        chur_catalog::sync_staging::LockedStaging::open(target_root.sync_inbox(&vault_id)).unwrap();
    let report = chur_catalog::sync_engine::process_staged(
        target.catalog().unwrap(),
        &secret,
        vault_id,
        &mut staging,
        NOW + 4,
    )
    .unwrap();
    assert_eq!(report.rejected, 0);
    assert_eq!(report.pending, 0);
    assert_eq!(
        device_pairing::finalize(target.catalog().unwrap(), &secret, vault_id, NOW + 5)
            .err()
            .unwrap()
            .status(),
        chur_core::ChurStatus::AuthenticationFailed
    );
    assert_eq!(
        device_pairing::approve(
            source.catalog().unwrap(),
            &secret,
            vault_id,
            &request,
            "https://sync.example",
            &[88; 32],
            NOW + 5
        )
        .err()
        .unwrap()
        .status(),
        chur_core::ChurStatus::AuthenticationFailed
    );
    let devices =
        device_pairing::overview(source.catalog_ref().unwrap(), &secret, vault_id).unwrap();
    assert!(
        devices
            .iter()
            .any(|device| device.device_id == *request.device_id()
                && !device.active
                && !device.current)
    );
}

#[test]
fn first_approval_cannot_move_an_already_advanced_local_history_backwards() {
    let source_root = root();
    let mut source = vault::create(&source_root, PASSWORD, NOW)
        .unwrap()
        .activate()
        .unwrap();
    let secret = source.root_secret().unwrap().duplicate();
    let vault_id = source.vault_id();
    let (original, _) =
        sync_receive::provision_local_identity(source.catalog().unwrap(), &secret, vault_id)
            .unwrap();
    let mut package = Cursor::new(Vec::new());
    backup::create(&mut source, &mut package, NOW, &mut Uninterrupted).unwrap();
    let target_root = root();
    backup::restore(&target_root, &mut package, PASSWORD, &mut Uninterrupted).unwrap();
    let mut target = vault::unlock_with_password(&target_root, PASSWORD, NOW + 1).unwrap();
    let request =
        device_pairing::prepare_request(target.catalog().unwrap(), &secret, vault_id).unwrap();
    let prepared = device_pairing::approve(
        source.catalog().unwrap(),
        &secret,
        vault_id,
        &request,
        "https://sync.example",
        &[88; 32],
        NOW + 2,
    )
    .unwrap();
    receive_device(
        source.catalog_ref().unwrap(),
        original.device_id(),
        &mut target,
        &target_root,
        &secret,
        vault_id,
    );
    assert_eq!(
        device_pairing::accept_start(
            target.catalog().unwrap(),
            &secret,
            vault_id,
            &prepared.approval
        )
        .err()
        .unwrap()
        .status(),
        chur_core::ChurStatus::SyncHeadRollback
    );
}

#[test]
fn a_concurrent_ready_before_genesis_revocation_preserves_history_and_other_devices() {
    let source_root = root();
    let mut source = vault::create(&source_root, PASSWORD, NOW)
        .unwrap()
        .activate()
        .unwrap();
    let secret = source.root_secret().unwrap().duplicate();
    let vault_id = source.vault_id();
    let (original, _) =
        sync_receive::provision_local_identity(source.catalog().unwrap(), &secret, vault_id)
            .unwrap();
    let mut backup_bytes = Cursor::new(Vec::new());
    backup::create(&mut source, &mut backup_bytes, NOW, &mut Uninterrupted).unwrap();
    let target_root = root();
    backup::restore(
        &target_root,
        &mut Cursor::new(backup_bytes.get_ref().clone()),
        PASSWORD,
        &mut Uninterrupted,
    )
    .unwrap();
    let mut target = vault::unlock_with_password(&target_root, PASSWORD, NOW + 1).unwrap();
    let target_request =
        device_pairing::prepare_request(target.catalog().unwrap(), &secret, vault_id).unwrap();
    let target_approval = device_pairing::approve(
        source.catalog().unwrap(),
        &secret,
        vault_id,
        &target_request,
        "https://sync.example",
        &[80; 32],
        NOW + 2,
    )
    .unwrap();
    let neighbor_root = root();
    backup::restore(
        &neighbor_root,
        &mut Cursor::new(backup_bytes.get_ref().clone()),
        PASSWORD,
        &mut Uninterrupted,
    )
    .unwrap();
    let mut neighbor = vault::unlock_with_password(&neighbor_root, PASSWORD, NOW + 1).unwrap();
    let neighbor_request =
        device_pairing::prepare_request(neighbor.catalog().unwrap(), &secret, vault_id).unwrap();
    let neighbor_approval = device_pairing::approve(
        source.catalog().unwrap(),
        &secret,
        vault_id,
        &neighbor_request,
        "https://sync.example",
        &[81; 32],
        NOW + 2,
    )
    .unwrap();
    for (session, path, approval) in [
        (&mut target, &target_root, &target_approval),
        (&mut neighbor, &neighbor_root, &neighbor_approval),
    ] {
        device_pairing::accept_start(
            session.catalog().unwrap(),
            &secret,
            vault_id,
            &approval.approval,
        )
        .unwrap();
        receive_device(
            source.catalog_ref().unwrap(),
            original.device_id(),
            session,
            path,
            &secret,
            vault_id,
        );
        device_pairing::finalize(session.catalog().unwrap(), &secret, vault_id, NOW + 3).unwrap();
    }
    let ready = chur_catalog::sync_log::record_at(
        target.catalog_ref().unwrap(),
        target_request.device_id(),
        1,
    )
    .unwrap()
    .unwrap();
    let target_checkpoint =
        device_pairing::current_checkpoint(target.catalog().unwrap(), &secret, vault_id, NOW + 4)
            .unwrap();
    receive_device(
        target.catalog_ref().unwrap(),
        target_request.device_id(),
        &mut neighbor,
        &neighbor_root,
        &secret,
        vault_id,
    );
    let neighbor_checkpoint =
        device_pairing::current_checkpoint(neighbor.catalog().unwrap(), &secret, vault_id, NOW + 4)
            .unwrap();
    // The issuer has not observed Ready. Its signed accepted prefix is zero,
    // even though the target and a third device have already accepted Ready.
    let revoked = device_pairing::prepare_revoke(
        source.catalog().unwrap(),
        &secret,
        vault_id,
        *target_request.device_id(),
        NOW + 5,
    )
    .unwrap();
    assert_eq!(
        chur_sync_protocol::membership::RevocationRecord::decode(&revoked.revocation)
            .unwrap()
            .final_accepted_device_sequence(),
        0
    );
    for (session, path) in [(&mut target, &target_root), (&mut neighbor, &neighbor_root)] {
        receive_device(
            source.catalog_ref().unwrap(),
            original.device_id(),
            session,
            path,
            &secret,
            vault_id,
        );
        let membership = chur_catalog::sync_membership::load(session.catalog_ref().unwrap())
            .unwrap()
            .unwrap();
        assert!(!membership.is_active(target_request.device_id()));
        chur_catalog::sync_log::load(session.catalog_ref().unwrap(), &membership)
            .expect("authenticated retained history survives a later cutoff");
        assert_eq!(
            chur_catalog::sync_log::record_at(
                session.catalog_ref().unwrap(),
                target_request.device_id(),
                1
            )
            .unwrap()
            .unwrap(),
            ready
        );
    }
    for (session, expected_retry) in [(&source, true), (&neighbor, false)] {
        let devices = device_pairing::overview(session.catalog_ref().unwrap(), &secret, vault_id)
            .expect("ordinary device membership overview");
        let revoked_peer = devices
            .iter()
            .find(|device| device.device_id == *target_request.device_id())
            .expect("revoked target remains visible");
        assert!(!revoked_peer.active);
        assert_eq!(
            revoked_peer.retry_available, expected_retry,
            "only the original revoker has a cached upload to retry"
        );
        assert!(
            devices
                .iter()
                .filter(|device| device.active)
                .all(|device| !device.retry_available)
        );
    }
    assert!(
        chur_catalog::sync_keys::local_identity(
            target.catalog_ref().unwrap(),
            &secret,
            &chur_catalog::sync_membership::load(target.catalog_ref().unwrap())
                .unwrap()
                .unwrap()
        )
        .is_err()
    );
    assert_eq!(
        device_pairing::current_checkpoint(target.catalog().unwrap(), &secret, vault_id, NOW + 6)
            .err()
            .unwrap()
            .status(),
        chur_core::ChurStatus::AuthenticationFailed
    );
    for (session, device, encoded) in [
        (&target, target_request.device_id(), &target_checkpoint),
        (
            &neighbor,
            neighbor_request.device_id(),
            &neighbor_checkpoint,
        ),
    ] {
        let membership = chur_catalog::sync_membership::load(session.catalog_ref().unwrap())
            .unwrap()
            .unwrap();
        let history =
            chur_catalog::sync_log::load(session.catalog_ref().unwrap(), &membership).unwrap();
        assert_eq!(
            history.checkpoint_commitment(device),
            Some(
                &chur_sync_protocol::checkpoint::Checkpoint::decode(encoded)
                    .unwrap()
                    .commitment()
            )
        );
    }
    drop(target);
    let mut target = vault::unlock_with_password(&target_root, PASSWORD, NOW + 7).unwrap();
    let membership = chur_catalog::sync_membership::load(target.catalog_ref().unwrap())
        .unwrap()
        .unwrap();
    let mut restored_log =
        chur_catalog::sync_log::load(target.catalog_ref().unwrap(), &membership).unwrap();
    let decoded_ready = chur_sync_protocol::operation::Operation::decode(&ready).unwrap();
    assert_eq!(
        restored_log
            .accept_with(
                target.catalog().unwrap(),
                &decoded_ready,
                &membership,
                |_| Ok(())
            )
            .err()
            .unwrap()
            .status(),
        chur_core::ChurStatus::AuthenticationFailed
    );
    assert!(
        device_pairing::current_checkpoint(neighbor.catalog().unwrap(), &secret, vault_id, NOW + 7)
            .is_ok()
    );
}
