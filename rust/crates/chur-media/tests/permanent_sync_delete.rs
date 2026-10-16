//! Permanent source deletion destroys content keys on an enrolled sibling.
#![allow(clippy::expect_used, clippy::unwrap_used)]
use chur_catalog::{
    deletion, device_pairing, own_sync,
    paths::VaultRoot,
    sharing_publication, sync_engine, sync_keys, sync_log, sync_membership, sync_receive,
    sync_staging::LockedStaging,
    vault::{self, Session},
};
use chur_core::{ChurStatus, Id};
use chur_crypto::{Key, random};
use chur_format::constants::StreamKind;
use chur_media::{
    backup, export,
    import::{self, CanonicalMedia, SourceCapability},
    keys,
    progress::Uninterrupted,
    store, sync_download,
};
use std::io::Cursor;
use zeroize::Zeroizing;
const PASSWORD: &[u8] = b"permanent sibling deletion password";
const NOW: u64 = 1_700_000_000_000;
fn root() -> VaultRoot {
    let path = std::env::temp_dir().join(format!(
        "chur-permanent-sync-{}",
        random::id().unwrap().to_hex()
    ));
    std::fs::create_dir_all(&path).unwrap();
    VaultRoot::new(path)
}
#[track_caller]
fn relay(source: &Session, target: &mut Session, secret: &Key, device: &Id) {
    let vault = source.vault_id();
    for record in sync_log::records_after(source.catalog_ref().unwrap(), device, 0).unwrap() {
        sync_engine::stage_inbound(
            target.root_dir(),
            vault,
            sync_engine::StagedKind::Operation,
            NOW + 10,
            &record,
        )
        .unwrap();
    }
    let mut staging = LockedStaging::open(target.root_dir().sync_inbox(&vault)).unwrap();
    let result = sync_engine::process_staged(
        target.catalog().unwrap(),
        secret,
        vault,
        &mut staging,
        NOW + 10,
    )
    .unwrap();
    assert_eq!(result.rejected, 0);
    assert_eq!(result.pending, 0);
}
fn finish(session: &mut Session, secret: &Key) {
    let vault = session.vault_id();
    let store_id = session.object_store_id();
    for pending in deletion::sweep(session.catalog_ref().unwrap()).unwrap() {
        if !pending.erased {
            deletion::erase_with_sync(
                session.catalog().unwrap(),
                secret,
                vault,
                &pending.object_id,
                NOW + 20,
            )
            .unwrap();
        }
        for id in pending.containers {
            store::unlink_container(session.root_dir(), &store_id, &id).unwrap();
        }
        deletion::finish(session.catalog().unwrap(), &pending.object_id).unwrap();
        deletion::discard_tombstone(session.catalog().unwrap(), &pending.object_id).unwrap();
    }
    session.catalog().unwrap().checkpoint().unwrap();
}
fn import(session: &mut Session, value: &[u8]) -> Id {
    import::import_bytes(
        session,
        SourceCapability {
            seekable: true,
            known_length: Some(value.len() as u64),
            content_type_hint: "application/octet-stream".into(),
            original_filename: Some("private.bin".into()),
            capture_time_ms: None,
        },
        CanonicalMedia::opaque(),
        "application/octet-stream",
        &Zeroizing::new(value.to_vec()),
        NOW + 8,
    )
    .unwrap()
}
fn rotate(source: &mut Session, secret: &Key, collection: Id, objects: &[Id]) {
    use chur_sync_protocol::{
        KeyDomain,
        payload::{OperationPayload, PayloadBody},
    };
    let vault = source.vault_id();
    let membership = sync_membership::load(source.catalog_ref().unwrap())
        .unwrap()
        .unwrap();
    let (device, identity) =
        sync_keys::local_identity(source.catalog_ref().unwrap(), secret, &membership)
            .unwrap()
            .unwrap();
    let old = keys::collection_key(source, &collection, 1).unwrap();
    let next = random::secret::<32>().unwrap();
    let envelope = chur_format::envelope::CollectionKeyEnvelope::seal(
        secret,
        vault,
        collection,
        2,
        2,
        chur_crypto::Nonce::random().unwrap(),
        &next,
    )
    .unwrap();
    let domain = KeyDomain::collection(&old, &collection, 1).unwrap();
    let mut directory =
        sync_keys::key_directory(source.catalog_ref().unwrap(), secret, vault).unwrap();
    let mut log = sync_log::load(source.catalog_ref().unwrap(), &membership).unwrap();
    sync_receive::author_rotation_operation(
        source.catalog().unwrap(),
        &mut log,
        &membership,
        &mut directory,
        secret,
        &domain,
        device,
        identity.signing_key(),
        NOW + 12,
        &OperationPayload::new(
            collection,
            1,
            PayloadBody::CreateCollectionEpoch {
                previous_collection_epoch: 1,
                membership_generation: membership.generation(),
                collection_key_envelope: envelope,
            },
        )
        .unwrap(),
    )
    .unwrap();
    let domain = KeyDomain::collection(&next, &collection, 2).unwrap();
    for object in objects {
        let content = keys::object_key(source, object).unwrap();
        sync_receive::author_rotation_operation(
            source.catalog().unwrap(),
            &mut log,
            &membership,
            &mut directory,
            secret,
            &domain,
            device,
            identity.signing_key(),
            NOW + 13,
            &OperationPayload::new(
                collection,
                2,
                PayloadBody::RewrapObjectKey {
                    object_id: *object,
                    object_key_commitment: chur_sync_protocol::object_grant::object_key_commitment(
                        &collection,
                        object,
                        &content,
                    ),
                },
            )
            .unwrap(),
        )
        .unwrap();
    }
}
#[test]
fn source_purge_relay_erases_sibling_after_restart_and_backup_without_damaging_neighbor() {
    let source_root = root();
    let mut source = vault::create(&source_root, PASSWORD, NOW)
        .unwrap()
        .activate()
        .unwrap();
    let secret = source.root_secret().unwrap().duplicate();
    let vault = source.vault_id();
    let (source_enrollment, _) =
        sync_receive::provision_local_identity(source.catalog().unwrap(), &secret, vault).unwrap();
    let mut baseline = Cursor::new(Vec::new());
    backup::create(&mut source, &mut baseline, NOW, &mut Uninterrupted).unwrap();
    let target_root = root();
    backup::restore(&target_root, &mut baseline, PASSWORD, &mut Uninterrupted).unwrap();
    let mut target = vault::unlock_with_password(&target_root, PASSWORD, NOW + 1).unwrap();
    let request =
        device_pairing::prepare_request(target.catalog().unwrap(), &secret, vault).unwrap();
    let approval = device_pairing::approve(
        source.catalog().unwrap(),
        &secret,
        vault,
        &request,
        "https://sync.example",
        &[88; 32],
        NOW + 2,
    )
    .unwrap();
    device_pairing::accept_start(
        target.catalog().unwrap(),
        &secret,
        vault,
        &approval.approval,
    )
    .unwrap();
    relay(&source, &mut target, &secret, source_enrollment.device_id());
    device_pairing::finalize(target.catalog().unwrap(), &secret, vault, NOW + 4).unwrap();
    let removed = import(&mut source, b"removed private original");
    let neighbor = import(&mut source, b"neighbor remains readable");
    let pending_removed = import(&mut source, b"pending original never activated");
    let pending_sealed = import(&mut source, b"accepted pending key without container");
    let collection = chur_catalog::store::object(source.catalog_ref().unwrap(), &removed)
        .unwrap()
        .collection_id;
    for object in [removed, neighbor, pending_removed, pending_sealed] {
        let item =
            sharing_publication::candidate(source.catalog_ref().unwrap(), &collection, &object)
                .unwrap();
        own_sync::publish(
            source.catalog().unwrap(),
            &secret,
            vault,
            collection,
            &[item],
        )
        .unwrap();
    }
    relay(&source, &mut target, &secret, source_enrollment.device_id());
    let plan =
        own_sync::pending_for_collection(target.catalog().unwrap(), &secret, vault, collection)
            .unwrap();
    assert_eq!(plan.objects.len(), 4);
    let mut retained_grants = Vec::new();
    for object in plan.objects {
        let prekey = chur_catalog::object_grants::request(
            target.catalog().unwrap(),
            &secret,
            vault,
            collection,
            object.object_id,
        )
        .unwrap();
        let grant =
            chur_catalog::object_grants::issue(source.catalog().unwrap(), &secret, vault, &prekey)
                .unwrap();
        retained_grants.push(grant.clone());
        if object.object_id == pending_removed {
            continue;
        }
        chur_catalog::object_grants::accept(target.catalog().unwrap(), &secret, vault, &[], &grant)
            .unwrap();
        if object.object_id == pending_sealed {
            continue;
        }
        let bytes = store::read_container(
            source.root_dir(),
            &source.object_store_id(),
            &object.store_id,
        )
        .unwrap();
        let expected = sync_download::Expectation::new(
            object.object_id,
            object.stream_id,
            object.container_length,
            object.container_commitment,
        )
        .unwrap();
        sync_download::append_range(
            target.root_dir(),
            &target.object_store_id(),
            &object.object_id,
            0,
            &bytes,
            &expected,
        )
        .unwrap();
        sync_download::activate_shared(&mut target, &object, NOW + 11).unwrap();
    }
    assert!(keys::object_key(&target, &removed).is_ok());
    rotate(
        &mut source,
        &secret,
        collection,
        &[removed, neighbor, pending_removed, pending_sealed],
    );
    // Rotation must not block on the sibling's missing pending container/key.
    let later = import(&mut source, b"new epoch original");
    let item =
        sharing_publication::candidate(source.catalog_ref().unwrap(), &collection, &later).unwrap();
    own_sync::publish(
        source.catalog().unwrap(),
        &secret,
        vault,
        collection,
        &[item],
    )
    .unwrap();
    relay(&source, &mut target, &secret, source_enrollment.device_id());
    assert_eq!(
        chur_catalog::store::collection(target.catalog_ref().unwrap(), &collection)
            .unwrap()
            .current_epoch,
        2
    );
    let pending =
        own_sync::pending_for_collection(target.catalog().unwrap(), &secret, vault, collection)
            .unwrap();
    assert_eq!(pending.objects.len(), 3);
    // The new-epoch original remains pending until it has its own bound grant.
    deletion::begin(source.catalog().unwrap(), &removed).unwrap();
    deletion::begin(source.catalog().unwrap(), &pending_removed).unwrap();
    deletion::begin(source.catalog().unwrap(), &pending_sealed).unwrap();
    finish(&mut source, &secret);
    assert!(keys::object_key(&source, &removed).is_err());
    relay(&source, &mut target, &secret, source_enrollment.device_id());
    // The native acceptance transaction removes keys before it advances the head.
    assert!(keys::object_key(&target, &removed).is_err());
    assert!(
        chur_catalog::object_grants::pending_envelope(
            target.catalog_ref().unwrap(),
            &pending_removed
        )
        .is_err()
    );
    finish(&mut target, &secret);
    drop(target);
    let mut target = vault::unlock_with_password(&target_root, PASSWORD, NOW + 30).unwrap();
    assert!(keys::object_key(&target, &removed).is_err());
    assert_eq!(
        chur_catalog::object_grants::request(
            target.catalog().unwrap(),
            &secret,
            vault,
            collection,
            removed
        )
        .err()
        .unwrap()
        .status(),
        ChurStatus::NotFound
    );
    for grant in retained_grants.iter().filter(|grant| {
        grant.object_id() == &removed
            || grant.object_id() == &pending_removed
            || grant.object_id() == &pending_sealed
    }) {
        assert_eq!(
            chur_catalog::object_grants::accept(
                target.catalog().unwrap(),
                &secret,
                vault,
                &[],
                grant
            )
            .unwrap_err()
            .status(),
            ChurStatus::NotFound
        );
    }
    let mut plaintext = Vec::new();
    export::export_stream(
        &target,
        &neighbor,
        StreamKind::Original,
        &mut plaintext,
        &mut Uninterrupted,
    )
    .unwrap();
    assert_eq!(plaintext, b"neighbor remains readable");
    let membership = sync_membership::load(target.catalog_ref().unwrap())
        .unwrap()
        .unwrap();
    assert_eq!(
        sync_keys::local_identity(target.catalog_ref().unwrap(), &secret, &membership)
            .unwrap()
            .unwrap()
            .0,
        *request.device_id()
    );
    let mut package = Cursor::new(Vec::new());
    backup::create(&mut target, &mut package, NOW + 31, &mut Uninterrupted).unwrap();
    let restored_root = root();
    backup::restore(&restored_root, &mut package, PASSWORD, &mut Uninterrupted).unwrap();
    let restored = vault::unlock_with_password(&restored_root, PASSWORD, NOW + 32).unwrap();
    assert!(keys::object_key(&restored, &removed).is_err());
    let mut bytes = Vec::new();
    export::export_stream(
        &restored,
        &neighbor,
        StreamKind::Original,
        &mut bytes,
        &mut Uninterrupted,
    )
    .unwrap();
    assert_eq!(bytes, b"neighbor remains readable");
}
