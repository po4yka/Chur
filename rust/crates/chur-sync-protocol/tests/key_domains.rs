//! Cross-device sync key-domain behavior.

#![allow(clippy::unwrap_used)]

use chur_core::Id;
use chur_crypto::Key;
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
