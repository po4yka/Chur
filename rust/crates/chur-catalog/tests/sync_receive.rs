//! Atomic acceptance of encrypted membership operations.

#![allow(clippy::expect_used)]

use chur_catalog::db::{CatalogKey, CatalogLocation};
use chur_catalog::{CatalogDb, schema, sync_log, sync_membership, sync_receive};
use chur_core::Id;
use chur_crypto::{Key, Nonce};
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
