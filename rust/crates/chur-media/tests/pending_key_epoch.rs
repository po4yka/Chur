//! Activation of a verified pending grant across a later metadata-key epoch.
#![allow(clippy::expect_used, clippy::unwrap_used)]
use chur_catalog::{paths::VaultRoot, sharing_publication, sharing_receive::SharedObject, vault};
use chur_crypto::{Key, Nonce, random};
use chur_format::{
    constants::StreamKind,
    envelope::{CollectionKeyEnvelope, ObjectKeyEnvelope},
};
use chur_media::{
    backup, export,
    import::{self, CanonicalMedia, SourceCapability},
    keys,
    progress::Uninterrupted,
    store, sync_download,
};
use chur_sync_protocol::{
    object_grant::{ObjectKeyGrant, ObjectPrekey, object_key_commitment},
    operation::DeviceSigningKey,
};
use std::io::Cursor;
use zeroize::Zeroizing;
const PASSWORD: &[u8] = b"pending epoch rotation password";
fn root() -> VaultRoot {
    let path = std::env::temp_dir().join(format!(
        "chur-pending-epoch-{}",
        random::id().unwrap().to_hex()
    ));
    std::fs::create_dir_all(&path).unwrap();
    VaultRoot::new(path)
}
#[test]
fn shared_pending_key_accepted_before_metadata_rotation_activates_under_current_epoch() {
    let source_root = root();
    let mut source = vault::create(&source_root, PASSWORD, 1)
        .unwrap()
        .activate()
        .unwrap();
    let collection = keys::ensure_default_collection(&mut source).unwrap();
    let root_key = source.root_secret().unwrap().duplicate();
    let vault_id = source.vault_id();
    let mut baseline = Cursor::new(Vec::new());
    backup::create(&mut source, &mut baseline, 2, &mut Uninterrupted).unwrap();
    let target_root = root();
    backup::restore(&target_root, &mut baseline, PASSWORD, &mut Uninterrupted).unwrap();
    let mut target = vault::unlock_with_password(&target_root, PASSWORD, 3).unwrap();
    let bytes = b"pending key survives a metadata epoch change";
    let object = import::import_bytes(
        &mut source,
        SourceCapability {
            seekable: true,
            known_length: Some(bytes.len() as u64),
            content_type_hint: "application/octet-stream".into(),
            original_filename: None,
            capture_time_ms: None,
        },
        CanonicalMedia::opaque(),
        "application/octet-stream",
        &Zeroizing::new(bytes.to_vec()),
        4,
    )
    .unwrap();
    let item = sharing_publication::candidate(source.catalog_ref().unwrap(), &collection, &object)
        .unwrap();
    let content = keys::object_key(&source, &object).unwrap();
    let old = keys::collection_key(&target, &collection, 1).unwrap();
    let next = Key::new([201; 32]);
    let signer = DeviceSigningKey::from_seed([202; 32]);
    let request = ObjectPrekey::sign(
        random::id().unwrap(),
        collection,
        object,
        1,
        item.container_commitment,
        vault_id,
        random::id().unwrap(),
        1,
        &Key::new([203; 32]),
        &signer,
    )
    .unwrap();
    let grant = ObjectKeyGrant::seal(
        random::id().unwrap(),
        vault_id,
        random::id().unwrap(),
        object,
        1,
        item.container_commitment,
        &request,
        &content,
        &signer,
    )
    .unwrap();
    let pending = ObjectKeyEnvelope::seal(
        &old,
        vault_id,
        collection,
        1,
        object,
        1,
        Nonce::new([204; 24]),
        &content,
    )
    .unwrap();
    // This is the trusted projection left by grant acceptance. Authority and
    // HPKE acceptance are covered by the separate sharing-surface tests.
    target
        .catalog()
        .unwrap()
        .transaction(|tx| {
            tx.execute(
                "INSERT INTO pending_object_keys VALUES (?1, ?2, ?3, ?4, ?5)",
                (
                    object.as_bytes().as_slice(),
                    collection.as_bytes().as_slice(),
                    request.encode(),
                    grant.encode(),
                    pending.encode(),
                ),
            )
            .unwrap();
            Ok(())
        })
        .unwrap();
    let mut updated =
        chur_catalog::store::collection(target.catalog_ref().unwrap(), &collection).unwrap();
    updated.current_epoch = 2;
    let metadata_envelope = CollectionKeyEnvelope::seal(
        &root_key,
        vault_id,
        collection,
        2,
        2,
        Nonce::new([205; 24]),
        &next,
    )
    .unwrap();
    chur_catalog::store::put_collection_with_envelope(
        target.catalog().unwrap(),
        &updated,
        2,
        &metadata_envelope.encode(),
    )
    .unwrap();
    let expected = sync_download::Expectation::new(
        object,
        item.stream_id,
        item.container_length,
        item.container_commitment,
    )
    .unwrap();
    let ciphertext =
        store::read_container(source.root_dir(), &source.object_store_id(), &item.store_id)
            .unwrap();
    sync_download::append_range(
        target.root_dir(),
        &target.object_store_id(),
        &object,
        0,
        &ciphertext,
        &expected,
    )
    .unwrap();
    sync_download::activate_shared(
        &mut target,
        &SharedObject {
            source_vault_id: vault_id,
            collection_id: collection,
            object_id: object,
            object_generation: 1,
            stream_id: item.stream_id,
            store_id: item.store_id,
            container_length: item.container_length,
            container_commitment: item.container_commitment,
            object_key_commitment: object_key_commitment(&collection, &object, &content),
            metadata: vec![],
        },
        5,
    )
    .unwrap();
    let envelope = ObjectKeyEnvelope::decode(
        &chur_catalog::store::active_envelope(target.catalog_ref().unwrap(), &object).unwrap(),
    )
    .unwrap();
    assert_eq!(envelope.collection_epoch(), 2);
    assert_eq!(envelope.open(&next).unwrap().expose(), content.expose());
    assert!(
        chur_catalog::object_grants::pending_envelope(
            target.catalog_ref().unwrap(),
            &collection,
            &object
        )
        .is_err()
    );
    drop(target);
    let target = vault::unlock_with_password(&target_root, PASSWORD, 6).unwrap();
    let mut plaintext = Vec::new();
    export::export_stream(
        &target,
        &object,
        StreamKind::Original,
        &mut plaintext,
        &mut Uninterrupted,
    )
    .unwrap();
    assert_eq!(plaintext, bytes);
}
