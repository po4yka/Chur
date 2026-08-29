//! Rebuilding sync key routing from retained collection envelopes.

#![allow(clippy::expect_used)]

use chur_catalog::db::{CatalogKey, CatalogLocation};
use chur_catalog::model::{COLLECTION_POLICY_VAULT_DEFAULT, COLLECTION_STATUS_ACTIVE, Collection};
use chur_catalog::{CatalogDb, schema, store, sync_keys};
use chur_core::Id;
use chur_crypto::{Key, Nonce};
use chur_format::envelope::CollectionKeyEnvelope;
use chur_sync_protocol::KeyDomain;

fn id(byte: u8) -> Id {
    Id::new([byte; 16]).expect("id")
}

#[test]
fn all_retained_collection_epochs_are_routable() {
    let vault_id = id(1);
    let collection_id = id(2);
    let root = Key::new([3; 32]);
    let catalog_key = CatalogKey::derive(&root, &vault_id).expect("catalog key");
    let mut db = CatalogDb::open(&CatalogLocation::Memory, &catalog_key).expect("catalog");
    schema::open_at_current_version(&mut db, 1).expect("schema");

    for epoch in 1..=2 {
        let collection_key = Key::new([u8::try_from(epoch + 3).expect("epoch"); 32]);
        let envelope = CollectionKeyEnvelope::seal(
            &root,
            vault_id,
            collection_id,
            epoch,
            1,
            Nonce::new([u8::try_from(epoch).expect("epoch"); 24]),
            &collection_key,
        )
        .expect("envelope");
        store::put_collection_with_envelope(
            &mut db,
            &Collection {
                collection_id,
                current_epoch: epoch,
                policy_type: COLLECTION_POLICY_VAULT_DEFAULT,
                created_revision: 1,
                status: COLLECTION_STATUS_ACTIVE,
            },
            1,
            &envelope.encode(),
        )
        .expect("collection");
    }

    let directory = sync_keys::key_directory(&db, &root, vault_id).expect("directory");
    for epoch in 1..=2 {
        let collection_key = Key::new([u8::try_from(epoch + 3).expect("epoch"); 32]);
        let domain = KeyDomain::collection(&collection_key, &collection_id, epoch).expect("domain");
        assert!(directory.operation_key(domain.selector()).is_ok());
    }
}
