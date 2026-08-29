//! Aggregate convergence behavior for decrypted operation payloads.

#![allow(clippy::expect_used)]

use chur_core::Id;
use chur_crypto::{Key, Nonce};
use chur_format::envelope::ObjectKeyEnvelope;
use chur_sync_protocol::convergence::MergeOutcome;
use chur_sync_protocol::materialization::MaterializedState;
use chur_sync_protocol::operation::{DeviceSigningKey, ObservedHead, Operation};
use chur_sync_protocol::payload::{MetadataField, MetadataFieldId, OperationPayload, PayloadBody};

fn id(byte: u8) -> Id {
    Id::new([byte; 16]).expect("id")
}

fn operation(
    key: &DeviceSigningKey,
    marker: u8,
    device_id: Id,
    sequence: u64,
    previous: [u8; 32],
    observed: Vec<ObservedHead>,
    payload: &OperationPayload,
) -> Operation {
    Operation::seal(
        id(marker),
        id(1),
        device_id,
        sequence,
        previous,
        observed,
        id(9),
        &Key::new([8; 32]),
        Nonce::new([marker; 24]),
        &payload.encode(),
    )
    .expect("operation")
    .sign(key)
}

#[test]
fn object_metadata_albums_and_favorites_share_one_state() {
    let key = DeviceSigningKey::from_seed([2; 32]);
    let create = OperationPayload::new(
        id(10),
        1,
        PayloadBody::CreateObject {
            object_id: id(11),
            object_generation: 1,
            store_id: id(12),
            stream_id: id(18),
            metadata_fields: vec![
                MetadataField::new(MetadataFieldId::Caption, b"First".to_vec()).expect("field"),
            ],
        },
    )
    .expect("create");
    let create_operation = operation(&key, 20, id(2), 1, [0; 32], Vec::new(), &create);
    let album = OperationPayload::new(
        id(10),
        1,
        PayloadBody::CreateAlbum {
            album_id: id(13),
            name: "Trips".to_owned(),
        },
    )
    .expect("album");
    let album_operation = operation(
        &key,
        21,
        id(2),
        2,
        create_operation.digest(),
        Vec::new(),
        &album,
    );
    let membership = OperationPayload::new(
        id(10),
        1,
        PayloadBody::AddAlbumMembership {
            album_id: id(13),
            object_id: id(11),
        },
    )
    .expect("membership");
    let membership_operation = operation(
        &key,
        22,
        id(2),
        3,
        album_operation.digest(),
        Vec::new(),
        &membership,
    );
    let favorite = OperationPayload::new(
        id(10),
        1,
        PayloadBody::SetFavorite {
            object_id: id(11),
            favorite: true,
            removed_tokens: Vec::new(),
        },
    )
    .expect("favorite");
    let favorite_operation = operation(
        &key,
        23,
        id(2),
        4,
        membership_operation.digest(),
        Vec::new(),
        &favorite,
    );
    let mut state = MaterializedState::new();

    state.apply(&create_operation, &create).expect("create");
    state.apply(&album_operation, &album).expect("album");
    state
        .apply(&membership_operation, &membership)
        .expect("membership");
    state
        .apply(&favorite_operation, &favorite)
        .expect("favorite");

    assert_eq!(
        state.metadata(&id(11), MetadataFieldId::Caption),
        Some(b"First".as_slice())
    );
    assert_eq!(state.album_name(&id(13)), Some("Trips"));
    assert!(state.album_contains(&id(13), &id(11)));
    assert!(state.is_favorite(&id(11)));
    assert!(!state.is_presentable(&id(11)));
}

#[test]
fn remove_waits_for_its_observed_add() {
    let key_a = DeviceSigningKey::from_seed([3; 32]);
    let key_b = DeviceSigningKey::from_seed([4; 32]);
    let create = OperationPayload::new(
        id(10),
        1,
        PayloadBody::CreateObject {
            object_id: id(30),
            object_generation: 1,
            store_id: id(31),
            stream_id: id(29),
            metadata_fields: Vec::new(),
        },
    )
    .expect("create");
    let create_operation = operation(&key_a, 32, id(2), 1, [0; 32], Vec::new(), &create);
    let add = OperationPayload::new(
        id(10),
        1,
        PayloadBody::SetFavorite {
            object_id: id(30),
            favorite: true,
            removed_tokens: Vec::new(),
        },
    )
    .expect("add");
    let add_operation = operation(
        &key_a,
        33,
        id(2),
        2,
        create_operation.digest(),
        Vec::new(),
        &add,
    );
    let remove = OperationPayload::new(
        id(10),
        1,
        PayloadBody::SetFavorite {
            object_id: id(30),
            favorite: false,
            removed_tokens: vec![*add_operation.operation_id()],
        },
    )
    .expect("remove");
    let remove_operation = operation(
        &key_b,
        34,
        id(3),
        1,
        [0; 32],
        vec![ObservedHead::new(id(2), 2)],
        &remove,
    );
    let mut state = MaterializedState::new();
    state.apply(&create_operation, &create).expect("create");

    assert_eq!(
        state.apply(&remove_operation, &remove).expect("pending"),
        MergeOutcome::PendingCause
    );
    state.apply(&add_operation, &add).expect("add");
    state.apply(&remove_operation, &remove).expect("remove");
    assert!(!state.is_favorite(&id(30)));
}

#[test]
fn committed_object_keeps_the_stream_identity_needed_for_download() {
    let key = DeviceSigningKey::from_seed([5; 32]);
    let create = OperationPayload::new(
        id(10),
        1,
        PayloadBody::CreateObject {
            object_id: id(40),
            object_generation: 1,
            store_id: id(41),
            stream_id: id(42),
            metadata_fields: Vec::new(),
        },
    )
    .expect("create");
    let create_operation = operation(&key, 43, id(2), 1, [0; 32], Vec::new(), &create);
    let envelope = ObjectKeyEnvelope::seal(
        &Key::new([44; 32]),
        id(1),
        id(10),
        1,
        id(40),
        1,
        Nonce::new([45; 24]),
        &Key::new([46; 32]),
    )
    .expect("envelope");
    let commit = OperationPayload::new(
        id(10),
        1,
        PayloadBody::CommitObject {
            object_id: id(40),
            object_generation: 1,
            store_id: id(41),
            container_length: 100,
            container_commitment: [47; 32],
            object_key_envelope: envelope,
        },
    )
    .expect("commit");
    let commit_operation = operation(
        &key,
        48,
        id(2),
        2,
        create_operation.digest(),
        Vec::new(),
        &commit,
    );
    let mut state = MaterializedState::new();
    state.apply(&create_operation, &create).expect("create");
    state.apply(&commit_operation, &commit).expect("commit");

    assert_eq!(
        state
            .committed_object(&id(40))
            .expect("committed")
            .stream_id(),
        &id(42)
    );
}
