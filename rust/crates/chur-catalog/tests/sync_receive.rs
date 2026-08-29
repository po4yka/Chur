//! Atomic acceptance of encrypted membership operations.

#![allow(clippy::expect_used)]

use chur_catalog::db::{CatalogKey, CatalogLocation};
use chur_catalog::model::{COLLECTION_POLICY_VAULT_DEFAULT, COLLECTION_STATUS_ACTIVE, Collection};
use chur_catalog::{CatalogDb, schema, store, sync_keys, sync_log, sync_membership, sync_receive};
use chur_core::Id;
use chur_crypto::{Key, Nonce};
use chur_sync_protocol::materialization::MaterializedState;
use chur_sync_protocol::membership::EnrollmentRecord;
use chur_sync_protocol::operation::{DeviceSigningKey, Operation};
use chur_sync_protocol::operation_log::ApplyOutcome;
use chur_sync_protocol::payload::{OperationPayload, PayloadBody};
use chur_sync_protocol::{KeyDirectory, KeyDomain};

fn id(byte: u8) -> Id {
    Id::new([byte; 16]).expect("id")
}

struct Fixture {
    db: CatalogDb,
    root: Key,
    issuer: DeviceSigningKey,
    membership: chur_sync_protocol::state::MembershipState,
}

fn setup() -> Fixture {
    let root = Key::new([1; 32]);
    let catalog_key = CatalogKey::derive(&root, &id(2)).expect("catalog key");
    let mut db = CatalogDb::open(&CatalogLocation::Memory, &catalog_key).expect("catalog");
    schema::open_at_current_version(&mut db, 1).expect("schema");
    let issuer = DeviceSigningKey::from_seed([3; 32]);
    let initial = EnrollmentRecord::initial(id(2), id(4), issuer.verifying_key(), [5; 32])
        .expect("initial")
        .sign(&issuer);
    let membership = sync_membership::provision(&mut db, &initial).expect("membership");
    Fixture {
        db,
        root,
        issuer,
        membership,
    }
}

#[test]
fn initial_membership_and_outer_operation_provision_together() {
    let root = Key::new([50; 32]);
    let catalog_key = CatalogKey::derive(&root, &id(51)).expect("catalog key");
    let mut db = CatalogDb::open(&CatalogLocation::Memory, &catalog_key).expect("catalog");
    schema::open_at_current_version(&mut db, 1).expect("schema");
    let signing_key = DeviceSigningKey::from_seed([52; 32]);
    let enrollment =
        EnrollmentRecord::initial(id(51), id(53), signing_key.verifying_key(), [54; 32])
            .expect("initial")
            .sign(&signing_key);

    let (membership, log, operation) =
        sync_receive::provision_initial_membership(&mut db, &root, &signing_key, &enrollment)
            .expect("provision");
    assert_eq!(operation.device_sequence(), 1);
    assert_eq!(log.head(&id(53)), Some((1, operation.digest())));
    assert_eq!(membership.generation(), 1);
    assert_eq!(
        sync_membership::load(&db)
            .expect("membership")
            .expect("present")
            .generation(),
        1
    );
    assert_eq!(
        sync_log::load(&db, &membership).expect("log").head(&id(53)),
        Some((1, operation.digest()))
    );
}

#[test]
fn failed_initial_provision_writes_no_outer_operation() {
    let root = Key::new([55; 32]);
    let catalog_key = CatalogKey::derive(&root, &id(56)).expect("catalog key");
    let mut db = CatalogDb::open(&CatalogLocation::Memory, &catalog_key).expect("catalog");
    schema::open_at_current_version(&mut db, 1).expect("schema");
    let signing_key = DeviceSigningKey::from_seed([57; 32]);
    let enrollment =
        EnrollmentRecord::initial(id(56), id(58), signing_key.verifying_key(), [59; 32])
            .expect("initial")
            .sign(&signing_key);
    sync_membership::provision(&mut db, &enrollment).expect("membership only");

    assert!(
        sync_receive::provision_initial_membership(&mut db, &root, &signing_key, &enrollment,)
            .is_err()
    );
    let membership = sync_membership::load(&db)
        .expect("membership")
        .expect("present");
    assert!(
        sync_log::load(&db, &membership)
            .expect("log")
            .head(&id(58))
            .is_none()
    );
}

fn enrollment_operation(fixture: &Fixture, enrollment: EnrollmentRecord) -> Operation {
    let domain = KeyDomain::root(&fixture.root, &id(2)).expect("root domain");
    let payload =
        OperationPayload::new(id(2), 0, PayloadBody::AddDevice(enrollment)).expect("payload");
    Operation::seal(
        id(6),
        id(2),
        id(4),
        1,
        [0; 32],
        Vec::new(),
        *domain.selector(),
        domain.operation_key(),
        Nonce::new([7; 24]),
        &payload.encode(),
    )
    .expect("operation")
    .sign(&fixture.issuer)
}

#[test]
fn membership_and_log_head_commit_together() {
    let mut fixture = setup();
    let peer = DeviceSigningKey::from_seed([8; 32]);
    let enrollment = EnrollmentRecord::new(
        id(2),
        id(9),
        peer.verifying_key(),
        [10; 32],
        1,
        id(4),
        2,
        *fixture.membership.commitment(),
        [11; 32],
    )
    .expect("enrollment")
    .sign(&fixture.issuer);
    let operation = enrollment_operation(&fixture, enrollment);
    let keys = KeyDirectory::new(&fixture.root, &id(2)).expect("keys");
    let mut log = sync_log::load(&fixture.db, &fixture.membership).expect("log");

    assert_eq!(
        sync_receive::accept_membership_operation(
            &mut fixture.db,
            &mut log,
            &mut fixture.membership,
            &keys,
            &operation.encode(),
        )
        .expect("accept"),
        ApplyOutcome::Applied
    );
    assert_eq!(fixture.membership.generation(), 2);
    assert_eq!(log.head(&id(4)), Some((1, operation.digest())));
    assert_eq!(
        sync_membership::load(&fixture.db)
            .expect("reload")
            .expect("membership")
            .generation(),
        2
    );
}

#[test]
fn invalid_nested_membership_rolls_back_the_log_head() {
    let mut fixture = setup();
    let peer = DeviceSigningKey::from_seed([12; 32]);
    let wrong_issuer = DeviceSigningKey::from_seed([13; 32]);
    let enrollment = EnrollmentRecord::new(
        id(2),
        id(14),
        peer.verifying_key(),
        [15; 32],
        1,
        id(4),
        2,
        *fixture.membership.commitment(),
        [16; 32],
    )
    .expect("enrollment")
    .sign(&wrong_issuer);
    let operation = enrollment_operation(&fixture, enrollment);
    let keys = KeyDirectory::new(&fixture.root, &id(2)).expect("keys");
    let mut log = sync_log::load(&fixture.db, &fixture.membership).expect("log");

    assert!(
        sync_receive::accept_membership_operation(
            &mut fixture.db,
            &mut log,
            &mut fixture.membership,
            &keys,
            &operation.encode(),
        )
        .is_err()
    );
    assert_eq!(fixture.membership.generation(), 1);
    assert!(log.head(&id(4)).is_none());
    assert!(
        sync_log::load(&fixture.db, &fixture.membership)
            .expect("reload")
            .head(&id(4))
            .is_none()
    );
}

#[test]
fn collection_epoch_and_log_head_commit_together() {
    let mut fixture = setup();
    let collection_id = id(17);
    let old_key = Key::new([18; 32]);
    let new_key = Key::new([19; 32]);
    let old_envelope = chur_format::envelope::CollectionKeyEnvelope::seal(
        &fixture.root,
        id(2),
        collection_id,
        1,
        1,
        Nonce::new([20; 24]),
        &old_key,
    )
    .expect("old envelope");
    store::put_collection_with_envelope(
        &mut fixture.db,
        &Collection {
            collection_id,
            current_epoch: 1,
            policy_type: COLLECTION_POLICY_VAULT_DEFAULT,
            created_revision: 1,
            status: COLLECTION_STATUS_ACTIVE,
        },
        1,
        &old_envelope.encode(),
    )
    .expect("collection");
    let new_envelope = chur_format::envelope::CollectionKeyEnvelope::seal(
        &fixture.root,
        id(2),
        collection_id,
        2,
        2,
        Nonce::new([21; 24]),
        &new_key,
    )
    .expect("new envelope");
    let payload = OperationPayload::new(
        collection_id,
        1,
        PayloadBody::CreateCollectionEpoch {
            previous_collection_epoch: 1,
            membership_generation: 1,
            collection_key_envelope: new_envelope,
        },
    )
    .expect("payload");
    let old_domain = KeyDomain::collection(&old_key, &collection_id, 1).expect("old domain");
    let operation = Operation::seal(
        id(22),
        id(2),
        id(4),
        1,
        [0; 32],
        Vec::new(),
        *old_domain.selector(),
        old_domain.operation_key(),
        Nonce::new([23; 24]),
        &payload.encode(),
    )
    .expect("operation")
    .sign(&fixture.issuer);
    let mut keys = sync_keys::key_directory(&fixture.db, &fixture.root, id(2)).expect("keys");
    let mut log = sync_log::load(&fixture.db, &fixture.membership).expect("log");

    assert_eq!(
        sync_receive::accept_rotation_operation(
            &mut fixture.db,
            &mut log,
            &fixture.membership,
            &mut keys,
            &fixture.root,
            1_000,
            &operation.encode(),
        )
        .expect("accept"),
        ApplyOutcome::Applied
    );
    assert_eq!(
        store::collection(&fixture.db, &collection_id)
            .expect("collection")
            .current_epoch,
        2
    );
    let new_domain = KeyDomain::collection(&new_key, &collection_id, 2).expect("new domain");
    assert!(keys.operation_key(new_domain.selector()).is_ok());
    assert_eq!(log.head(&id(4)), Some((1, operation.digest())));
}

#[test]
fn accepted_content_state_rebuilds_after_restart() {
    let mut fixture = setup();
    let collection_id = id(24);
    let collection_key = Key::new([25; 32]);
    let envelope = chur_format::envelope::CollectionKeyEnvelope::seal(
        &fixture.root,
        id(2),
        collection_id,
        1,
        1,
        Nonce::new([26; 24]),
        &collection_key,
    )
    .expect("envelope");
    store::put_collection_with_envelope(
        &mut fixture.db,
        &Collection {
            collection_id,
            current_epoch: 1,
            policy_type: COLLECTION_POLICY_VAULT_DEFAULT,
            created_revision: 1,
            status: COLLECTION_STATUS_ACTIVE,
        },
        1,
        &envelope.encode(),
    )
    .expect("collection");
    let domain = KeyDomain::collection(&collection_key, &collection_id, 1).expect("domain");
    let create = OperationPayload::new(
        collection_id,
        1,
        PayloadBody::CreateObject {
            object_id: id(27),
            object_generation: 1,
            store_id: id(28),
            stream_id: id(33),
            metadata_fields: Vec::new(),
        },
    )
    .expect("create");
    let create_operation = Operation::seal(
        id(29),
        id(2),
        id(4),
        1,
        [0; 32],
        Vec::new(),
        *domain.selector(),
        domain.operation_key(),
        Nonce::new([30; 24]),
        &create.encode(),
    )
    .expect("create operation")
    .sign(&fixture.issuer);
    let favorite = OperationPayload::new(
        collection_id,
        1,
        PayloadBody::SetFavorite {
            object_id: id(27),
            favorite: true,
            removed_tokens: Vec::new(),
        },
    )
    .expect("favorite");
    let favorite_operation = Operation::seal(
        id(31),
        id(2),
        id(4),
        2,
        create_operation.digest(),
        Vec::new(),
        *domain.selector(),
        domain.operation_key(),
        Nonce::new([32; 24]),
        &favorite.encode(),
    )
    .expect("favorite operation")
    .sign(&fixture.issuer);
    let mut log = sync_log::load(&fixture.db, &fixture.membership).expect("log");
    log.accept_with(
        &mut fixture.db,
        &create_operation,
        &fixture.membership,
        |_| Ok(()),
    )
    .expect("create");
    log.accept_with(
        &mut fixture.db,
        &favorite_operation,
        &fixture.membership,
        |_| Ok(()),
    )
    .expect("favorite");
    let keys = sync_keys::key_directory(&fixture.db, &fixture.root, id(2)).expect("keys");

    let restored = sync_receive::load_materialized_state(&fixture.db, &keys).expect("state");
    assert!(restored.is_favorite(&id(27)));
    assert!(!restored.is_presentable(&id(27)));
}

#[test]
fn missing_content_cause_does_not_advance_the_log() {
    let mut fixture = setup();
    let collection_id = id(33);
    let collection_key = Key::new([34; 32]);
    let domain = KeyDomain::collection(&collection_key, &collection_id, 1).expect("domain");
    let create_payload = OperationPayload::new(
        collection_id,
        1,
        PayloadBody::CreateObject {
            object_id: id(35),
            object_generation: 1,
            store_id: id(36),
            stream_id: id(43),
            metadata_fields: Vec::new(),
        },
    )
    .expect("create payload");
    let create = Operation::seal(
        id(37),
        id(2),
        id(4),
        1,
        [0; 32],
        Vec::new(),
        *domain.selector(),
        domain.operation_key(),
        Nonce::new([38; 24]),
        &create_payload.encode(),
    )
    .expect("create")
    .sign(&fixture.issuer);
    let favorite_payload = OperationPayload::new(
        collection_id,
        1,
        PayloadBody::SetFavorite {
            object_id: id(35),
            favorite: true,
            removed_tokens: Vec::new(),
        },
    )
    .expect("favorite payload");
    let pending = Operation::seal(
        id(39),
        id(2),
        id(4),
        1,
        [0; 32],
        Vec::new(),
        *domain.selector(),
        domain.operation_key(),
        Nonce::new([40; 24]),
        &favorite_payload.encode(),
    )
    .expect("pending")
    .sign(&fixture.issuer);
    let favorite = Operation::seal(
        id(41),
        id(2),
        id(4),
        2,
        create.digest(),
        Vec::new(),
        *domain.selector(),
        domain.operation_key(),
        Nonce::new([42; 24]),
        &favorite_payload.encode(),
    )
    .expect("favorite")
    .sign(&fixture.issuer);
    let mut keys = KeyDirectory::new(&fixture.root, &id(2)).expect("keys");
    keys.insert(domain).expect("domain");
    let mut log = sync_log::load(&fixture.db, &fixture.membership).expect("log");
    let mut state = MaterializedState::new();

    assert_eq!(
        sync_receive::accept_content_operation(
            &mut fixture.db,
            &mut log,
            &fixture.membership,
            &mut state,
            &keys,
            &pending.encode(),
        )
        .expect("pending cause"),
        ApplyOutcome::PendingCause
    );
    assert!(log.head(&id(4)).is_none());
    assert_eq!(
        sync_receive::accept_content_operation(
            &mut fixture.db,
            &mut log,
            &fixture.membership,
            &mut state,
            &keys,
            &create.encode(),
        )
        .expect("create"),
        ApplyOutcome::Applied
    );
    assert_eq!(
        sync_receive::accept_content_operation(
            &mut fixture.db,
            &mut log,
            &fixture.membership,
            &mut state,
            &keys,
            &favorite.encode(),
        )
        .expect("favorite"),
        ApplyOutcome::Applied
    );
    assert!(state.is_favorite(&id(35)));
    assert_eq!(log.head(&id(4)), Some((2, favorite.digest())));
}

#[test]
fn locally_authored_content_and_head_commit_together() {
    let mut fixture = setup();
    let collection_id = id(44);
    let collection_key = Key::new([45; 32]);
    let domain = KeyDomain::collection(&collection_key, &collection_id, 1).expect("domain");
    let create = OperationPayload::new(
        collection_id,
        1,
        PayloadBody::CreateObject {
            object_id: id(46),
            object_generation: 1,
            store_id: id(47),
            stream_id: id(48),
            metadata_fields: Vec::new(),
        },
    )
    .expect("create");
    let mut log = sync_log::load(&fixture.db, &fixture.membership).expect("log");
    let mut state = MaterializedState::new();

    let operation = sync_receive::author_content_operation(
        &mut fixture.db,
        &mut log,
        &fixture.membership,
        &mut state,
        &domain,
        id(4),
        &fixture.issuer,
        &create,
    )
    .expect("author create");
    assert_eq!(operation.device_sequence(), 1);
    assert_eq!(log.head(&id(4)), Some((1, operation.digest())));
    assert_eq!(
        sync_log::load(&fixture.db, &fixture.membership)
            .expect("reload")
            .head(&id(4)),
        Some((1, operation.digest()))
    );

    let invalid = OperationPayload::new(
        collection_id,
        1,
        PayloadBody::SetFavorite {
            object_id: id(49),
            favorite: true,
            removed_tokens: Vec::new(),
        },
    )
    .expect("invalid favorite");
    assert!(
        sync_receive::author_content_operation(
            &mut fixture.db,
            &mut log,
            &fixture.membership,
            &mut state,
            &domain,
            id(4),
            &fixture.issuer,
            &invalid,
        )
        .is_err()
    );
    assert_eq!(log.head(&id(4)), Some((1, operation.digest())));
}
