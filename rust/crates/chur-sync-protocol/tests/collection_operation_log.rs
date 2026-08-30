//! Cross-vault acceptance checks for the collection-scoped operation log.

#![allow(clippy::expect_used, clippy::panic)]

use chur_core::{ChurStatus, Id};
use chur_crypto::{Key, Nonce};
use chur_sync_protocol::collection_membership::{
    CollectionMembershipAction, CollectionMembershipRecord, CollectionMembershipState,
};
use chur_sync_protocol::collection_operation::CollectionOperation;
use chur_sync_protocol::collection_operation_log::CollectionOperationLog;
use chur_sync_protocol::grant::PermissionProfile;
use chur_sync_protocol::membership::EnrollmentRecord;
use chur_sync_protocol::operation::DeviceSigningKey;
use chur_sync_protocol::operation_log::ApplyOutcome;
use chur_sync_protocol::payload::{OperationPayload, PayloadBody};
use chur_sync_protocol::state::MembershipState;

fn id(byte: u8) -> Id {
    Id::new([byte; 16]).expect("non-zero identifier")
}

fn identity(vault: Id, device: Id, seed: u8) -> (DeviceSigningKey, MembershipState) {
    let key = DeviceSigningKey::from_seed([seed; 32]);
    let enrollment = EnrollmentRecord::initial(vault, device, key.verifying_key(), [seed + 1; 32])
        .expect("enrollment")
        .sign(&key);
    let membership = MembershipState::bootstrap(&enrollment).expect("membership");
    (key, membership)
}

struct Fixture {
    source_key: DeviceSigningKey,
    source_membership: MembershipState,
    recipient_key: DeviceSigningKey,
    recipient_membership: MembershipState,
    collection_membership: CollectionMembershipState,
    payload: OperationPayload,
    operation_key: Key,
    selector: Id,
}

fn fixture(permission: PermissionProfile) -> Fixture {
    let source_vault = id(1);
    let source_device = id(2);
    let recipient_vault = id(3);
    let recipient_device = id(4);
    let collection = id(5);
    let (source_key, source_membership) = identity(source_vault, source_device, 10);
    let (recipient_key, recipient_membership) = identity(recipient_vault, recipient_device, 20);
    let mut collection_membership =
        CollectionMembershipState::new(source_vault, collection, 1).expect("collection state");
    let record = CollectionMembershipRecord::new(
        source_vault,
        collection,
        1,
        [0; 32],
        CollectionMembershipAction::Upsert(permission),
        recipient_vault,
        recipient_device,
        recipient_key.verifying_key(),
        [21; 32],
        1,
        source_vault,
        source_device,
        source_membership.generation(),
        1,
    )
    .expect("collection member")
    .sign(&source_key);
    collection_membership
        .accept(&record, &source_membership)
        .expect("accept member");
    let payload = OperationPayload::new(
        collection,
        1,
        PayloadBody::CreateAlbum {
            album_id: id(6),
            name: "Shared".to_owned(),
        },
    )
    .expect("payload");
    Fixture {
        source_key,
        source_membership,
        recipient_key,
        recipient_membership,
        collection_membership,
        payload,
        operation_key: Key::new([30; 32]),
        selector: id(7),
    }
}

#[test]
fn cross_vault_cause_waits_then_applies_deterministically() {
    let fixture = fixture(PermissionProfile::Contribute);
    let mut sender = CollectionOperationLog::new(id(5), 1, fixture.selector);
    let source = sender
        .author(
            id(8),
            id(1),
            id(2),
            &fixture.operation_key,
            Nonce::new([31; 24]),
            &fixture.payload,
            &fixture.source_key,
            &fixture.source_membership,
            &fixture.source_membership,
            &fixture.collection_membership,
        )
        .expect("source operation");
    assert_eq!(
        sender
            .accept(
                &source,
                &fixture.payload,
                &fixture.source_membership,
                &fixture.source_membership,
                &fixture.collection_membership,
            )
            .expect("source accept"),
        ApplyOutcome::Applied
    );
    let recipient = sender
        .author(
            id(9),
            id(3),
            id(4),
            &fixture.operation_key,
            Nonce::new([32; 24]),
            &fixture.payload,
            &fixture.recipient_key,
            &fixture.recipient_membership,
            &fixture.source_membership,
            &fixture.collection_membership,
        )
        .expect("recipient operation");
    assert_eq!(recipient.observed_heads().len(), 1);

    let mut receiver = CollectionOperationLog::new(id(5), 1, fixture.selector);
    assert_eq!(
        receiver
            .accept(
                &recipient,
                &fixture.payload,
                &fixture.recipient_membership,
                &fixture.source_membership,
                &fixture.collection_membership,
            )
            .expect("pending cause"),
        ApplyOutcome::PendingCause
    );
    assert_eq!(
        receiver
            .accept(
                &source,
                &fixture.payload,
                &fixture.source_membership,
                &fixture.source_membership,
                &fixture.collection_membership,
            )
            .expect("source"),
        ApplyOutcome::Applied
    );
    assert_eq!(
        receiver
            .accept(
                &recipient,
                &fixture.payload,
                &fixture.recipient_membership,
                &fixture.source_membership,
                &fixture.collection_membership,
            )
            .expect("recipient"),
        ApplyOutcome::Applied
    );
}

#[test]
fn read_only_and_conflicting_participant_streams_fail_closed() {
    let read = fixture(PermissionProfile::Read);
    let log = CollectionOperationLog::new(id(5), 1, read.selector);
    assert_eq!(
        log.author(
            id(8),
            id(3),
            id(4),
            &read.operation_key,
            Nonce::new([31; 24]),
            &read.payload,
            &read.recipient_key,
            &read.recipient_membership,
            &read.source_membership,
            &read.collection_membership,
        )
        .err()
        .expect("read-only recipient")
        .status(),
        ChurStatus::AuthenticationFailed
    );

    let contribute = fixture(PermissionProfile::Contribute);
    let mut log = CollectionOperationLog::new(id(5), 1, contribute.selector);
    let first = log
        .author(
            id(8),
            id(3),
            id(4),
            &contribute.operation_key,
            Nonce::new([31; 24]),
            &contribute.payload,
            &contribute.recipient_key,
            &contribute.recipient_membership,
            &contribute.source_membership,
            &contribute.collection_membership,
        )
        .expect("first");
    log.accept(
        &first,
        &contribute.payload,
        &contribute.recipient_membership,
        &contribute.source_membership,
        &contribute.collection_membership,
    )
    .expect("accept first");
    let conflict = CollectionOperation::seal(
        id(9),
        id(3),
        id(4),
        1,
        [0; 32],
        Vec::new(),
        contribute.selector,
        &contribute.operation_key,
        Nonce::new([32; 24]),
        &contribute.payload.encode(),
    )
    .expect("conflict")
    .sign(&contribute.recipient_key);
    assert_eq!(
        log.accept(
            &conflict,
            &contribute.payload,
            &contribute.recipient_membership,
            &contribute.source_membership,
            &contribute.collection_membership,
        )
        .expect_err("fork")
        .status(),
        ChurStatus::SyncChainFork
    );
    assert!(log.fork(&id(3), &id(4)).is_some());
}
