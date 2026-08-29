//! Cross-device sync key-domain behavior.

#![allow(clippy::unwrap_used)]

use chur_core::Id;
use chur_crypto::{Key, Nonce};
use chur_sync_protocol::operation::{DeviceSigningKey, Operation};
use chur_sync_protocol::payload::{OperationPayload, PayloadBody};
use chur_sync_protocol::{KeyDirectory, KeyDomain};

fn id(byte: u8) -> Id {
    Id::new([byte; 16]).unwrap()
}

#[test]
fn peers_rebuild_the_same_root_domain() {
    let first = KeyDomain::root(&Key::new([1; 32]), &id(2)).unwrap();
    let second = KeyDomain::root(&Key::new([1; 32]), &id(2)).unwrap();

    assert_eq!(first.selector(), second.selector());
    assert!(first.operation_key() == second.operation_key());
}

#[test]
fn purpose_collection_and_epoch_are_separate_domains() {
    let parent = Key::new([3; 32]);
    let root = KeyDomain::root(&parent, &id(4)).unwrap();
    let collection = KeyDomain::collection(&parent, &id(4), 1).unwrap();
    let other_collection = KeyDomain::collection(&parent, &id(5), 1).unwrap();
    let other_epoch = KeyDomain::collection(&parent, &id(4), 2).unwrap();

    assert_ne!(root.selector(), collection.selector());
    assert_ne!(collection.selector(), other_collection.selector());
    assert_ne!(collection.selector(), other_epoch.selector());
    assert_ne!(
        root.selector().as_bytes(),
        &root.operation_key().expose()[..16]
    );
}

#[test]
fn directory_routes_known_selectors_and_refuses_unknown_ones() {
    let root_key = Key::new([6; 32]);
    let collection_key = Key::new([7; 32]);
    let mut directory = KeyDirectory::new(&root_key, &id(8)).unwrap();
    let collection = KeyDomain::collection(&collection_key, &id(9), 1).unwrap();
    let collection_selector = *collection.selector();
    directory.insert(collection).unwrap();

    assert!(
        directory.operation_key(&collection_selector).unwrap()
            == KeyDomain::collection(&collection_key, &id(9), 1)
                .unwrap()
                .operation_key()
    );
    assert!(directory.operation_key(&id(10)).is_err());
}

#[test]
fn selected_domain_authenticates_the_opened_payload_header() {
    let vault_id = id(11);
    let collection_id = id(12);
    let collection_key = Key::new([13; 32]);
    let domain = KeyDomain::collection(&collection_key, &collection_id, 2).unwrap();
    let payload = OperationPayload::new(
        collection_id,
        2,
        PayloadBody::CreateAlbum {
            album_id: id(14),
            name: "Trips".to_owned(),
        },
    )
    .unwrap();
    let operation = Operation::seal(
        id(15),
        vault_id,
        id(16),
        1,
        [0; 32],
        Vec::new(),
        *domain.selector(),
        domain.operation_key(),
        Nonce::new([17; 24]),
        &payload.encode(),
    )
    .unwrap()
    .sign(&DeviceSigningKey::from_seed([18; 32]));
    let mut directory = KeyDirectory::new(&Key::new([19; 32]), &vault_id).unwrap();
    directory.insert(domain).unwrap();

    let opened = OperationPayload::open_for_operation(&operation, &directory).unwrap();
    assert_eq!(opened.collection_id(), &collection_id);
    assert_eq!(opened.collection_epoch(), 2);

    let mismatched = OperationPayload::new(
        id(20),
        2,
        PayloadBody::CreateAlbum {
            album_id: id(21),
            name: "Hidden".to_owned(),
        },
    )
    .unwrap();
    let operation = Operation::seal(
        id(22),
        vault_id,
        id(16),
        2,
        operation.digest(),
        Vec::new(),
        *directory
            .domain(operation.key_selector())
            .unwrap()
            .selector(),
        directory.operation_key(operation.key_selector()).unwrap(),
        Nonce::new([23; 24]),
        &mismatched.encode(),
    )
    .unwrap();
    assert!(OperationPayload::open_for_operation(&operation, &directory).is_err());
}
