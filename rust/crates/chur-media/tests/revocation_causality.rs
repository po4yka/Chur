//! A lost device's no-op readiness must not partition an active peer's chain.
#![allow(clippy::unwrap_used)]
use chur_catalog::{
    device_pairing, own_sync,
    paths::VaultRoot,
    sync_engine, sync_keys, sync_log, sync_membership, sync_receive,
    sync_staging::LockedStaging,
    vault::{self, Session},
};
use chur_core::Id;
use chur_crypto::{Key, random};
use chur_media::{backup, progress::Uninterrupted};
use chur_sync_protocol::{
    KeyDomain,
    membership::RevocationRecord,
    operation::Operation,
    payload::{OperationPayload, PayloadBody},
};
use std::io::Cursor;
const PASSWORD: &[u8] = b"causal revocation replica password";
const NOW: u64 = 1_700_000_000_000;
fn root() -> VaultRoot {
    let path = std::env::temp_dir().join(format!(
        "chur-causal-revocation-{}",
        random::id().unwrap().to_hex()
    ));
    std::fs::create_dir_all(&path).unwrap();
    VaultRoot::new(path)
}
fn deliver(target: &mut Session, secret: &Key, records: &[Vec<u8>]) -> sync_engine::ProcessReport {
    let vault = target.vault_id();
    for record in records {
        sync_engine::stage_inbound(
            target.root_dir(),
            vault,
            sync_engine::StagedKind::Operation,
            NOW + 10,
            record,
        )
        .unwrap();
    }
    let mut staging = LockedStaging::open(target.root_dir().sync_inbox(&vault)).unwrap();
    sync_engine::process_staged(
        target.catalog().unwrap(),
        secret,
        vault,
        &mut staging,
        NOW + 10,
    )
    .unwrap()
}
fn records(source: &Session, device: &Id) -> Vec<Vec<u8>> {
    sync_log::records_after(source.catalog_ref().unwrap(), device, 0).unwrap()
}
fn relay(source: &Session, target: &mut Session, secret: &Key, device: &Id) {
    let report = deliver(target, secret, &records(source, device));
    assert_eq!(report.rejected, 0);
    assert_eq!(report.pending, 0);
}
fn pair(source: &mut Session, secret: &Key, source_device: &Id) -> (Session, Id) {
    let vault = source.vault_id();
    let mut package = Cursor::new(Vec::new());
    backup::create(source, &mut package, NOW, &mut Uninterrupted).unwrap();
    let target_root = root();
    backup::restore(&target_root, &mut package, PASSWORD, &mut Uninterrupted).unwrap();
    let mut target = vault::unlock_with_password(&target_root, PASSWORD, NOW + 1).unwrap();
    let request =
        device_pairing::prepare_request(target.catalog().unwrap(), secret, vault).unwrap();
    let approval = device_pairing::approve(
        source.catalog().unwrap(),
        secret,
        vault,
        &request,
        "https://sync.example",
        &[88; 32],
        NOW + 2,
    )
    .unwrap();
    device_pairing::accept_start(target.catalog().unwrap(), secret, vault, &approval.approval)
        .unwrap();
    relay(source, &mut target, secret, source_device);
    device_pairing::finalize(target.catalog().unwrap(), secret, vault, NOW + 4).unwrap();
    (target, *request.device_id())
}
fn album(source: &mut Session, secret: &Key, name: &str) -> (Id, Operation) {
    let vault = source.vault_id();
    let membership = sync_membership::load(source.catalog_ref().unwrap())
        .unwrap()
        .unwrap();
    let (device, identity) =
        sync_keys::local_identity(source.catalog_ref().unwrap(), secret, &membership)
            .unwrap()
            .unwrap();
    let collection = own_sync::collection_ids(source.catalog_ref().unwrap()).unwrap()[0];
    let key = chur_media::keys::collection_key(source, &collection, 1).unwrap();
    let domain = KeyDomain::collection(&key, &collection, 1).unwrap();
    let directory = sync_keys::key_directory(source.catalog_ref().unwrap(), secret, vault).unwrap();
    let mut state =
        sync_receive::load_materialized_state(source.catalog_ref().unwrap(), &directory).unwrap();
    let mut log = sync_log::load(source.catalog_ref().unwrap(), &membership).unwrap();
    let id = random::id().unwrap();
    let op = sync_receive::author_content_operation(
        source.catalog().unwrap(),
        &mut log,
        &membership,
        &mut state,
        &domain,
        device,
        identity.signing_key(),
        &OperationPayload::new(
            collection,
            1,
            PayloadBody::CreateAlbum {
                album_id: id,
                name: name.into(),
            },
        )
        .unwrap(),
    )
    .unwrap();
    (id, op)
}
fn original(source: &mut Session, secret: &Key, bytes: &[u8]) -> (Id, Operation) {
    let object = chur_media::import::import_bytes(
        source,
        chur_media::import::SourceCapability {
            requested_object_id: None,
            seekable: true,
            known_length: Some(bytes.len() as u64),
            content_type_hint: "application/octet-stream".into(),
            original_filename: Some("private.bin".into()),
            capture_time_ms: None,
        },
        chur_media::import::CanonicalMedia::opaque(),
        "application/octet-stream",
        &zeroize::Zeroizing::new(bytes.to_vec()),
        NOW,
    )
    .unwrap();
    let vault = source.vault_id();
    let collection = chur_catalog::store::object(source.catalog_ref().unwrap(), &object)
        .unwrap()
        .collection_id;
    let item = chur_catalog::sharing_publication::candidate(
        source.catalog_ref().unwrap(),
        &collection,
        &object,
    )
    .unwrap();
    let mut published = own_sync::publish(
        source.catalog().unwrap(),
        secret,
        vault,
        collection,
        &[item],
    )
    .unwrap();
    (object, published.remove(0).commit_operation)
}
fn dependent_effect(source: &mut Session, secret: &Key, object: Id, terminal: bool) {
    let vault = source.vault_id();
    let membership = sync_membership::load(source.catalog_ref().unwrap())
        .unwrap()
        .unwrap();
    let (device, identity) =
        sync_keys::local_identity(source.catalog_ref().unwrap(), secret, &membership)
            .unwrap()
            .unwrap();
    let collection = own_sync::collection_ids(source.catalog_ref().unwrap()).unwrap()[0];
    let key = chur_media::keys::collection_key(source, &collection, 1).unwrap();
    let domain = KeyDomain::collection(&key, &collection, 1).unwrap();
    let directory = sync_keys::key_directory(source.catalog_ref().unwrap(), secret, vault).unwrap();
    let mut state =
        sync_receive::load_materialized_state(source.catalog_ref().unwrap(), &directory).unwrap();
    let mut log = sync_log::load(source.catalog_ref().unwrap(), &membership).unwrap();
    sync_receive::author_content_operation(
        source.catalog().unwrap(),
        &mut log,
        &membership,
        &mut state,
        &domain,
        device,
        identity.signing_key(),
        &OperationPayload::new(
            collection,
            1,
            if terminal {
                PayloadBody::PurgeObject {
                    object_id: object,
                    object_generation: 1,
                    authored_at_ms: NOW,
                }
            } else {
                PayloadBody::SetFavorite {
                    object_id: object,
                    favorite: true,
                    removed_tokens: vec![],
                }
            },
        )
        .unwrap(),
    )
    .unwrap();
}
#[test]
fn a_zero_revocation_cannot_partition_a_concurrent_active_peers_ready_dependency() {
    replicas(false, false);
}
#[test]
fn content_depending_on_a_revoked_branch_does_not_block_healthy_peer_descendants() {
    replicas(true, false);
}
#[test]
fn a_deferred_terminal_purge_has_no_effect_without_its_cut_content_cause() {
    replicas(true, true);
}
fn replicas(dependent: bool, terminal: bool) {
    let a_root = root();
    let mut a = vault::create(&a_root, PASSWORD, NOW)
        .unwrap()
        .activate()
        .unwrap();
    let secret = a.root_secret().unwrap().duplicate();
    let vault = a.vault_id();
    let (enrollment, _) =
        sync_receive::provision_local_identity(a.catalog().unwrap(), &secret, vault).unwrap();
    let a_device = *enrollment.device_id();
    original(&mut a, &secret, b"initial original");
    let (mut b, b_device) = pair(&mut a, &secret, &a_device);
    let (mut c, c_device) = pair(&mut a, &secret, &a_device);
    // A never receives B1. C does, and can use B's content before the revocation.
    relay(&a, &mut b, &secret, &a_device);
    relay(&b, &mut c, &secret, &b_device);
    let withheld = if dependent {
        let (object, _) = original(&mut b, &secret, b"withheld revoked original");
        relay(&b, &mut c, &secret, &b_device);
        dependent_effect(&mut c, &secret, object, terminal);
        Some(object)
    } else {
        None
    };
    let (healthy_object, before) = original(&mut c, &secret, b"healthy active peer original");
    let (first_album, _) = album(&mut c, &secret, "Healthy concurrent work");
    assert!(
        before
            .observed_heads()
            .iter()
            .any(|head| head.device_id() == &b_device && head.device_sequence() >= 1)
    );
    let (_, forbidden) = album(&mut b, &secret, "Revoked unpublished work");
    let mut membership = sync_membership::load(a.catalog_ref().unwrap())
        .unwrap()
        .unwrap();
    let (_, identity) = sync_keys::local_identity(a.catalog_ref().unwrap(), &secret, &membership)
        .unwrap()
        .unwrap();
    let mut log = sync_log::load(a.catalog_ref().unwrap(), &membership).unwrap();
    assert_eq!(log.head(&b_device), None);
    let revocation = RevocationRecord::new(
        vault,
        b_device,
        0,
        [0; 32],
        membership.generation() + 1,
        a_device,
        *membership.commitment(),
    )
    .unwrap()
    .sign(identity.signing_key());
    sync_receive::author_membership_operation(
        a.catalog().unwrap(),
        &mut log,
        &mut membership,
        &KeyDomain::root(&secret, &vault).unwrap(),
        a_device,
        identity.signing_key(),
        PayloadBody::RevokeDevice(revocation),
    )
    .unwrap();
    // D starts after the revocation. Ordinary transport withholds all B records.
    let (mut d, _) = pair(&mut a, &secret, &a_device);
    relay(&a, &mut c, &secret, &a_device);
    let (second_album, after) = album(&mut c, &secret, "Healthy work after revocation");
    // C's current checkpoint still records the B history C had already accepted.
    let membership = sync_membership::load(c.catalog_ref().unwrap())
        .unwrap()
        .unwrap();
    let (_, identity) = sync_keys::local_identity(c.catalog_ref().unwrap(), &secret, &membership)
        .unwrap()
        .unwrap();
    let mut log = sync_log::load(c.catalog_ref().unwrap(), &membership).unwrap();
    let checkpoint = log
        .issue_own_checkpoint(
            c.catalog().unwrap(),
            &membership,
            &c_device,
            identity.signing_key(),
            NOW + 8,
        )
        .unwrap();
    assert!(
        checkpoint
            .heads()
            .iter()
            .any(|head| head.device_id() == &b_device && head.device_sequence() > 0)
    );
    sync_engine::stage_inbound(
        d.root_dir(),
        vault,
        sync_engine::StagedKind::Checkpoint,
        NOW + 10,
        &checkpoint.encode(),
    )
    .unwrap();
    let report = deliver(&mut d, &secret, &records(&c, &c_device));
    assert_eq!(
        report.rejected, 0,
        "C cannot inherit B's authorization cutoff"
    );
    assert_eq!(
        report.pending,
        usize::from(dependent),
        "only unresolved content can remain pending, not the healthy chain"
    );
    let report = deliver(&mut d, &secret, &[forbidden.encode()]);
    assert_eq!(
        report.rejected, 1,
        "unvouched B operations remain forbidden"
    );
    let membership = sync_membership::load(d.catalog_ref().unwrap())
        .unwrap()
        .unwrap();
    let log = sync_log::load(d.catalog_ref().unwrap(), &membership).unwrap();
    assert_eq!(
        log.head(&c_device),
        Some((after.device_sequence(), after.digest()))
    );
    assert_eq!(log.head(&b_device), None);
    let directory = sync_keys::key_directory(d.catalog_ref().unwrap(), &secret, vault).unwrap();
    let state =
        sync_receive::load_materialized_state(d.catalog_ref().unwrap(), &directory).unwrap();
    assert_eq!(
        state.album_name(&first_album),
        Some("Healthy concurrent work")
    );
    assert_eq!(
        state.album_name(&second_album),
        Some("Healthy work after revocation")
    );
    if let Some(withheld) = withheld {
        assert!(state.committed_object(&withheld).is_none());
        assert!(
            !state.object_is_purged(&withheld),
            "a deferred purge is not a terminal effect"
        );
    }
    let collection = chur_catalog::store::object(c.catalog_ref().unwrap(), &healthy_object)
        .unwrap()
        .collection_id;
    let plan =
        own_sync::pending_for_collection(d.catalog().unwrap(), &secret, vault, collection).unwrap();
    assert_eq!(plan.pending_operations, usize::from(dependent));
    let object = plan
        .objects
        .iter()
        .find(|item| item.object_id == healthy_object)
        .unwrap();
    let prekey = chur_catalog::object_grants::request(
        d.catalog().unwrap(),
        &secret,
        vault,
        collection,
        healthy_object,
    )
    .unwrap();
    let grant =
        chur_catalog::object_grants::issue(c.catalog().unwrap(), &secret, vault, &prekey).unwrap();
    chur_catalog::object_grants::accept(d.catalog().unwrap(), &secret, vault, &[], &grant).unwrap();
    let bytes =
        chur_media::store::read_container(c.root_dir(), &c.object_store_id(), &object.store_id)
            .unwrap();
    let expected = chur_media::sync_download::Expectation::new(
        object.object_id,
        object.stream_id,
        object.container_length,
        object.container_commitment,
    )
    .unwrap();
    chur_media::sync_download::append_range(
        d.root_dir(),
        &d.object_store_id(),
        &healthy_object,
        0,
        &bytes,
        &expected,
    )
    .unwrap();
    chur_media::sync_download::activate_shared(&mut d, object, NOW + 11).unwrap();
    let d_root = d.root_dir().clone();
    drop(d);
    let mut d = vault::unlock_with_password(&d_root, PASSWORD, NOW + 12).unwrap();
    let mut plaintext = Vec::new();
    chur_media::export::export_stream(
        &d,
        &healthy_object,
        chur_format::constants::StreamKind::Original,
        &mut plaintext,
        &mut Uninterrupted,
    )
    .unwrap();
    assert_eq!(plaintext, b"healthy active peer original");
    let mut package = Cursor::new(Vec::new());
    backup::create(&mut d, &mut package, NOW + 13, &mut Uninterrupted).unwrap();
    let restored_root = root();
    backup::restore(&restored_root, &mut package, PASSWORD, &mut Uninterrupted).unwrap();
    let restored = vault::unlock_with_password(&restored_root, PASSWORD, NOW + 14).unwrap();
    let mut bytes = Vec::new();
    chur_media::export::export_stream(
        &restored,
        &healthy_object,
        chur_format::constants::StreamKind::Original,
        &mut bytes,
        &mut Uninterrupted,
    )
    .unwrap();
    assert_eq!(bytes, b"healthy active peer original");
}

fn content(source: &mut Session, secret: &Key, body: PayloadBody) -> Operation {
    let vault = source.vault_id();
    let membership = sync_membership::load(source.catalog_ref().unwrap())
        .unwrap()
        .unwrap();
    let (device, identity) =
        sync_keys::local_identity(source.catalog_ref().unwrap(), secret, &membership)
            .unwrap()
            .unwrap();
    let collection = own_sync::collection_ids(source.catalog_ref().unwrap()).unwrap()[0];
    let key = chur_media::keys::collection_key(source, &collection, 1).unwrap();
    let domain = KeyDomain::collection(&key, &collection, 1).unwrap();
    let directory = sync_keys::key_directory(source.catalog_ref().unwrap(), secret, vault).unwrap();
    let mut state =
        sync_receive::load_materialized_state(source.catalog_ref().unwrap(), &directory).unwrap();
    let mut log = sync_log::load(source.catalog_ref().unwrap(), &membership).unwrap();
    sync_receive::author_content_operation(
        source.catalog().unwrap(),
        &mut log,
        &membership,
        &mut state,
        &domain,
        device,
        identity.signing_key(),
        &OperationPayload::new(collection, 1, body).unwrap(),
    )
    .unwrap()
}
fn restore_generation(source: &mut Session, secret: &Key, object: Id) {
    let delete = content(
        source,
        secret,
        PayloadBody::DeleteObject {
            object_id: object,
            object_generation: 1,
            authored_at_ms: NOW,
        },
    );
    content(
        source,
        secret,
        PayloadBody::RestoreObject {
            object_id: object,
            tombstone_operation_id: *delete.operation_id(),
            new_object_generation: 2,
        },
    );
}
fn custody(source: &mut Session, object: Id) -> [i64; 4] {
    source
        .catalog()
        .unwrap()
        .transaction(|tx| {
            let mut result = [0; 4];
            for (index, table) in [
                "object_key_requests",
                "pending_object_keys",
                "object_key_envelopes",
                "tombstones",
            ]
            .iter()
            .enumerate()
            {
                result[index] = tx
                    .query_row(
                        &format!("SELECT count(*) FROM {table} WHERE object_id = ?1"),
                        [object.as_bytes().as_slice()],
                        |row| row.get(0),
                    )
                    .unwrap();
            }
            Ok(result)
        })
        .unwrap()
}
#[test]
fn a_deferred_generation_purge_erases_custody_when_an_active_restore_resolves_it() {
    let a_root = root();
    let mut a = vault::create(&a_root, PASSWORD, NOW)
        .unwrap()
        .activate()
        .unwrap();
    let secret = a.root_secret().unwrap().duplicate();
    let vault = a.vault_id();
    let (enrollment, _) =
        sync_receive::provision_local_identity(a.catalog().unwrap(), &secret, vault).unwrap();
    let a_device = *enrollment.device_id();
    let (neighbor, _) = original(&mut a, &secret, b"healthy neighbor");
    let (mut b, b_device) = pair(&mut a, &secret, &a_device);
    let (mut c, c_device) = pair(&mut a, &secret, &a_device);
    let (mut d, _) = pair(&mut a, &secret, &a_device);
    let objects = [
        original(&mut a, &secret, b"waiting disposable private key").0,
        original(&mut a, &secret, b"accepted pending envelope").0,
        original(&mut a, &secret, b"active original envelope").0,
    ];
    relay(&a, &mut b, &secret, &a_device);
    relay(&a, &mut c, &secret, &a_device);
    relay(&a, &mut d, &secret, &a_device);
    let collection = own_sync::collection_ids(a.catalog_ref().unwrap()).unwrap()[0];
    let plan =
        own_sync::pending_for_collection(d.catalog().unwrap(), &secret, vault, collection).unwrap();
    let mut grants = Vec::new();
    for (index, object) in objects.iter().enumerate() {
        let request = chur_catalog::object_grants::request(
            d.catalog().unwrap(),
            &secret,
            vault,
            collection,
            *object,
        )
        .unwrap();
        let grant =
            chur_catalog::object_grants::issue(a.catalog().unwrap(), &secret, vault, &request)
                .unwrap();
        if index > 0 {
            chur_catalog::object_grants::accept(d.catalog().unwrap(), &secret, vault, &[], &grant)
                .unwrap();
        }
        if index == 2 {
            let item = plan
                .objects
                .iter()
                .find(|item| item.object_id == *object)
                .unwrap();
            let bytes = chur_media::store::read_container(
                a.root_dir(),
                &a.object_store_id(),
                &item.store_id,
            )
            .unwrap();
            let expected = chur_media::sync_download::Expectation::new(
                item.object_id,
                item.stream_id,
                item.container_length,
                item.container_commitment,
            )
            .unwrap();
            chur_media::sync_download::append_range(
                d.root_dir(),
                &d.object_store_id(),
                object,
                0,
                &bytes,
                &expected,
            )
            .unwrap();
            chur_media::sync_download::activate_shared(&mut d, item, NOW + 11).unwrap();
        }
        grants.push(grant);
        restore_generation(&mut b, &secret, *object);
    }
    relay(&b, &mut c, &secret, &b_device);
    for object in objects {
        content(
            &mut c,
            &secret,
            PayloadBody::PurgeObject {
                object_id: object,
                object_generation: 2,
                authored_at_ms: NOW + 12,
            },
        );
    }
    let mut membership = sync_membership::load(a.catalog_ref().unwrap())
        .unwrap()
        .unwrap();
    let (_, identity) = sync_keys::local_identity(a.catalog_ref().unwrap(), &secret, &membership)
        .unwrap()
        .unwrap();
    let mut log = sync_log::load(a.catalog_ref().unwrap(), &membership).unwrap();
    assert_eq!(log.head(&b_device), None);
    let revocation = RevocationRecord::new(
        vault,
        b_device,
        0,
        [0; 32],
        membership.generation() + 1,
        a_device,
        *membership.commitment(),
    )
    .unwrap()
    .sign(identity.signing_key());
    sync_receive::author_membership_operation(
        a.catalog().unwrap(),
        &mut log,
        &mut membership,
        &KeyDomain::root(&secret, &vault).unwrap(),
        a_device,
        identity.signing_key(),
        PayloadBody::RevokeDevice(revocation),
    )
    .unwrap();
    relay(&a, &mut d, &secret, &a_device);
    let report = deliver(&mut d, &secret, &records(&c, &c_device));
    assert_eq!(report.rejected, 0);
    assert_eq!(report.pending, 3);
    assert_eq!(custody(&mut d, objects[0]), [1, 0, 0, 0]);
    assert_eq!(custody(&mut d, objects[1]), [0, 1, 0, 0]);
    assert_eq!(custody(&mut d, objects[2]), [0, 0, 1, 0]);
    chur_media::keys::object_key(&d, &objects[2]).unwrap();
    // These later active restores are legitimate and independent of B. The
    // earlier terminal purge dominates them after generation two exists.
    for object in objects {
        restore_generation(&mut a, &secret, object);
    }
    let report = deliver(&mut d, &secret, &records(&a, &a_device));
    assert_eq!(report.rejected, 0);
    assert_eq!(report.pending, 0);
    for (index, object) in objects.iter().enumerate() {
        assert_eq!(
            custody(&mut d, *object),
            [0, 0, 0, 1],
            "terminal state and key custody commit together"
        );
        assert!(
            chur_catalog::object_grants::request(
                d.catalog().unwrap(),
                &secret,
                vault,
                collection,
                *object
            )
            .is_err()
        );
        assert!(
            chur_catalog::object_grants::accept(
                d.catalog().unwrap(),
                &secret,
                vault,
                &[],
                &grants[index]
            )
            .is_err()
        );
    }
    assert!(chur_media::keys::object_key(&d, &objects[2]).is_err());
    for pending in chur_catalog::deletion::sweep(d.catalog_ref().unwrap()).unwrap() {
        chur_catalog::deletion::erase(d.catalog().unwrap(), &pending.object_id, NOW + 20).unwrap();
        for container in pending.containers {
            chur_media::store::unlink_container(d.root_dir(), &d.object_store_id(), &container)
                .unwrap();
        }
        chur_catalog::deletion::finish(d.catalog().unwrap(), &pending.object_id).unwrap();
    }
    d.catalog().unwrap().checkpoint().unwrap();
    let d_root = d.root_dir().clone();
    drop(d);
    let mut d = vault::unlock_with_password(&d_root, PASSWORD, NOW + 21).unwrap();
    for object in objects {
        assert_eq!(custody(&mut d, object), [0, 0, 0, 1]);
    }
    let mut bytes = Vec::new();
    chur_media::export::export_stream(
        &d,
        &neighbor,
        chur_format::constants::StreamKind::Original,
        &mut bytes,
        &mut Uninterrupted,
    )
    .unwrap();
    assert_eq!(bytes, b"healthy neighbor");
    let mut package = Cursor::new(Vec::new());
    backup::create(&mut d, &mut package, NOW + 22, &mut Uninterrupted).unwrap();
}
