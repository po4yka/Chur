//! Real native own-vault media and HTTP handlers, without collection-sharing metadata.
#![allow(clippy::expect_used, clippy::unwrap_used)]

use axum::{
    Router,
    body::{Body, to_bytes},
    http::{Method, Request, StatusCode},
};
use chur_catalog::{
    device_pairing, object_grants, own_sync, paths::VaultRoot, sharing_publication, store,
    sync_engine, sync_keys, sync_log, sync_receive, vault,
};
use chur_core::{ChurStatus, Id};
use chur_crypto::{Key, random};
use chur_format::{
    codec::{Reader, Writer},
    constants::{MediaClass, StreamKind},
};
use chur_media::{backup, export, import, progress::Uninterrupted, sync_download};
use chur_sync_protocol::{
    checkpoint::Checkpoint,
    object_grant::{ObjectKeyGrant, ObjectPrekey},
    operation::Operation,
};
use chur_sync_server::{ReferenceServer, http};
use sha2::{Digest, Sha256};
use std::io::Cursor;
use tower::ServiceExt;

const NOW: u64 = 1_700_000_000_000;
const PASSWORD: &[u8] = b"real own HTTP backup pairing password";
const BOOTSTRAP: [u8; 32] = [17; 32];

struct Directory(std::path::PathBuf);
impl Directory {
    fn new() -> Self {
        let path =
            std::env::temp_dir().join(format!("chur-own-http-{}", random::id().unwrap().to_hex()));
        std::fs::create_dir_all(&path).unwrap();
        Self(path)
    }
}
impl Drop for Directory {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

fn pair(first: &[u8], second: &[u8]) -> Vec<u8> {
    let mut writer = Writer::new();
    writer.variable(first).unwrap();
    writer.fixed(second);
    writer.finish()
}
fn records(bytes: &[u8]) -> Vec<Vec<u8>> {
    let mut reader = Reader::new(bytes, ChurStatus::NonCanonicalEncoding);
    let count = reader.u32().unwrap();
    assert!(count <= 128);
    let result = (0..count)
        .map(|_| reader.variable(16 * 1024 * 1024).unwrap().to_vec())
        .collect();
    reader.finish().unwrap();
    result
}
async fn exchange(
    app: &Router,
    method: Method,
    path: &str,
    authorization: String,
    body: Vec<u8>,
) -> (StatusCode, Vec<u8>) {
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method(method)
                .uri(path)
                .header("authorization", authorization)
                .body(Body::from(body))
                .unwrap(),
        )
        .await
        .unwrap();
    let status = response.status();
    let bytes = to_bytes(response.into_body(), 16 * 1024 * 1024)
        .await
        .unwrap()
        .to_vec();
    (status, bytes)
}
async fn ok(app: &Router, method: Method, path: &str, token: &[u8; 32], body: Vec<u8>) -> Vec<u8> {
    let (status, bytes) = exchange(
        app,
        method,
        path,
        format!("Bearer {}", hex::encode(token)),
        body,
    )
    .await;
    assert!(
        status.is_success(),
        "HTTP {path}: {status}, error code {:?}",
        bytes.get(..4)
    );
    bytes
}
async fn bootstrap(app: &Router, session: &mut vault::Session, root: &Key, token: [u8; 32]) -> Id {
    let vault = session.vault_id();
    let vault_hex = vault.to_hex();
    let (enrollment, initial) =
        sync_receive::provision_local_identity(session.catalog().unwrap(), root, vault).unwrap();
    let body = [
        token.to_vec(),
        pair(&enrollment.encode(), &initial.encode()),
    ]
    .concat();
    let (status, response) = exchange(
        app,
        Method::POST,
        &format!("/v1/vaults/{vault_hex}/bootstrap"),
        format!("Bootstrap {}", hex::encode(BOOTSTRAP)),
        body,
    )
    .await;
    assert!(
        status.is_success(),
        "bootstrap: {status}, code {:?}",
        response.get(..4)
    );
    *enrollment.device_id()
}
async fn receive(
    app: &Router,
    target: &mut vault::Session,
    path: &VaultRoot,
    root: &Key,
    device: Id,
    token: &[u8; 32],
) {
    let vault = target.vault_id();
    let vault_hex = vault.to_hex();
    let device_hex = device.to_hex();
    let bytes = ok(
        app,
        Method::GET,
        &format!("/v1/vaults/{vault_hex}/operations/{device_hex}?after=0"),
        token,
        vec![],
    )
    .await;
    for record in records(&bytes) {
        sync_engine::stage_inbound(
            path,
            vault,
            sync_engine::StagedKind::Operation,
            NOW + 10,
            &record,
        )
        .unwrap();
    }
    let mut staging =
        chur_catalog::sync_staging::LockedStaging::open(path.sync_inbox(&vault)).unwrap();
    let report = sync_engine::process_staged(
        target.catalog().unwrap(),
        root,
        vault,
        &mut staging,
        NOW + 10,
    )
    .unwrap();
    assert_eq!(report.rejected, 0);
    assert_eq!(report.pending, 0);
}

#[tokio::test]
async fn private_original_uses_own_http_storage_and_keys_without_sharing_membership() {
    let directory = Directory::new();
    let source_path = VaultRoot::new(directory.0.join("source"));
    let peer_path = VaultRoot::new(directory.0.join("peer"));
    let mut source = vault::create(&source_path, PASSWORD, NOW)
        .unwrap()
        .activate()
        .unwrap();
    let root = source.root_secret().unwrap().duplicate();
    let vault_id = source.vault_id();
    let vault_hex = vault_id.to_hex();
    let server = ReferenceServer::open(directory.0.join("relay"), 1_048_576, 4_194_304).unwrap();
    let app = http::router(server, BOOTSTRAP);
    let source_token = [18; 32];
    let source_id = bootstrap(&app, &mut source, &root, source_token).await;
    let mut backup_bytes = Cursor::new(Vec::new());
    backup::create(&mut source, &mut backup_bytes, NOW, &mut Uninterrupted).unwrap();
    backup::restore(
        &peer_path,
        &mut Cursor::new(backup_bytes.into_inner()),
        PASSWORD,
        &mut Uninterrupted,
    )
    .unwrap();
    let mut peer = vault::unlock_with_password(&peer_path, PASSWORD, NOW + 1).unwrap();
    let request =
        device_pairing::prepare_request(peer.catalog().unwrap(), &root, vault_id).unwrap();
    let approved = device_pairing::approve(
        source.catalog().unwrap(),
        &root,
        vault_id,
        &request,
        "http://localhost",
        &[19; 32],
        NOW + 2,
    )
    .unwrap();
    let peer_token = approved.transport_token;
    ok(
        &app,
        Method::POST,
        &format!("/v1/vaults/{vault_hex}/checkpoints"),
        &source_token,
        approved.checkpoint.clone(),
    )
    .await;
    ok(
        &app,
        Method::POST,
        &format!("/v1/vaults/{vault_hex}/memberships/enroll"),
        &source_token,
        [
            peer_token.to_vec(),
            pair(&approved.enrollment, &approved.operation),
        ]
        .concat(),
    )
    .await;
    device_pairing::accept_start(peer.catalog().unwrap(), &root, vault_id, &approved.approval)
        .unwrap();
    receive(&app, &mut peer, &peer_path, &root, source_id, &peer_token).await;
    let peer_id =
        device_pairing::finalize(peer.catalog().unwrap(), &root, vault_id, NOW + 3).unwrap();
    for record in sync_log::records_after(peer.catalog_ref().unwrap(), &peer_id, 0).unwrap() {
        ok(
            &app,
            Method::POST,
            &format!("/v1/vaults/{vault_hex}/operations"),
            &peer_token,
            record,
        )
        .await;
    }
    receive(
        &app,
        &mut source,
        &source_path,
        &root,
        peer_id,
        &source_token,
    )
    .await;

    let original = (0..8192)
        .map(|index| ((index * 17) % 256) as u8)
        .collect::<Vec<_>>();
    let running = import::begin(
        &mut source,
        import::SourceCapability {
            seekable: true,
            known_length: Some(original.len() as u64),
            content_type_hint: "application/octet-stream".into(),
            original_filename: Some("private-original.bin".into()),
            capture_time_ms: None,
        },
        import::CanonicalMedia {
            media_class: MediaClass::Opaque,
            width: 0,
            height: 0,
            duration_ms: 0,
        },
        NOW + 4,
    )
    .unwrap();
    let object_id = import::stream_into(
        running,
        &mut source,
        &mut Cursor::new(&original),
        "application/octet-stream",
        NOW + 4,
        &mut Uninterrupted,
    )
    .unwrap();
    let collection = store::object(source.catalog_ref().unwrap(), &object_id)
        .unwrap()
        .collection_id;
    assert!(
        store::collection(peer.catalog_ref().unwrap(), &collection).is_err(),
        "the new metadata-key declaration must cross HTTP too"
    );
    let candidate =
        sharing_publication::candidate(source.catalog_ref().unwrap(), &collection, &object_id)
            .unwrap();
    let ciphertext = chur_media::store::read_container(
        &source_path,
        &source.object_store_id(),
        &candidate.store_id,
    )
    .unwrap();
    let checksum = hex::encode(Sha256::digest(&ciphertext));
    let store_id = candidate.store_id;
    let store_hex = store_id.to_hex();
    let collection_hex = collection.to_hex();
    ok(
        &app,
        Method::POST,
        &format!(
            "/v1/vaults/{vault_hex}/objects/{store_hex}/uploads/{store_hex}?length={}",
            ciphertext.len()
        ),
        &source_token,
        vec![],
    )
    .await;
    ok(
        &app,
        Method::PATCH,
        &format!("/v1/vaults/{vault_hex}/uploads/{store_hex}?offset=0&sha256={checksum}"),
        &source_token,
        ciphertext.clone(),
    )
    .await;
    ok(
        &app,
        Method::POST,
        &format!("/v1/vaults/{vault_hex}/uploads/{store_hex}/finish?sha256={checksum}"),
        &source_token,
        vec![],
    )
    .await;
    // A private declaration must not create sharing ACL state as a side effect.
    let (status, _) = exchange(
        &app,
        Method::POST,
        &format!("/v1/vaults/{vault_hex}/sharing/collections/{collection_hex}/objects/{store_hex}"),
        format!("Bearer {}", hex::encode(source_token)),
        vec![],
    )
    .await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    own_sync::publish(
        source.catalog().unwrap(),
        &root,
        vault_id,
        collection,
        &[candidate],
    )
    .unwrap();
    for record in sync_log::records_after(source.catalog_ref().unwrap(), &source_id, 0).unwrap() {
        ok(
            &app,
            Method::POST,
            &format!("/v1/vaults/{vault_hex}/operations"),
            &source_token,
            record,
        )
        .await;
    }
    receive(&app, &mut peer, &peer_path, &root, source_id, &peer_token).await;
    let object =
        own_sync::pending_for_collection(peer.catalog().unwrap(), &root, vault_id, collection)
            .unwrap()
            .objects
            .remove(0);
    let prekey = object_grants::request(
        peer.catalog().unwrap(),
        &root,
        vault_id,
        collection,
        object_id,
    )
    .unwrap();
    ok(
        &app,
        Method::POST,
        &format!("/v1/vaults/{vault_hex}/sharing/object-key-requests"),
        &peer_token,
        prekey.encode(),
    )
    .await;
    let pending = ok(
        &app,
        Method::GET,
        &format!("/v1/vaults/{vault_hex}/sharing/object-key-requests"),
        &source_token,
        vec![],
    )
    .await;
    assert_eq!(records(&pending), vec![prekey.encode()]);
    // An unavailable own replica can ACK its persisted canonical source binding.
    ok(
        &app,
        Method::POST,
        &format!("/v1/vaults/{vault_hex}/sharing/object-key-requests/ack"),
        &source_token,
        prekey.prekey_id().as_bytes().to_vec(),
    )
    .await;
    let remaining = records(
        &ok(
            &app,
            Method::GET,
            &format!("/v1/vaults/{vault_hex}/sharing/object-key-requests"),
            &peer_token,
            vec![],
        )
        .await,
    );
    assert_eq!(
        remaining,
        vec![prekey.encode()],
        "one replica cannot dismiss its sibling's own request"
    );
    ok(
        &app,
        Method::POST,
        &format!("/v1/vaults/{vault_hex}/sharing/object-key-requests"),
        &peer_token,
        prekey.encode(),
    )
    .await;
    let grant = object_grants::issue(source.catalog().unwrap(), &root, vault_id, &prekey).unwrap();
    ok(
        &app,
        Method::POST,
        &format!("/v1/vaults/{vault_hex}/sharing/object-key-grants"),
        &source_token,
        grant.encode(),
    )
    .await;
    let delivered = records(
        &ok(
            &app,
            Method::GET,
            &format!("/v1/vaults/{vault_hex}/sharing/object-key-grants"),
            &peer_token,
            vec![],
        )
        .await,
    );
    assert_eq!(delivered, vec![grant.encode()]);
    object_grants::accept(
        peer.catalog().unwrap(),
        &root,
        vault_id,
        &[],
        &ObjectKeyGrant::decode(&delivered[0]).unwrap(),
    )
    .unwrap();
    let unused: i64 = peer
        .catalog()
        .unwrap()
        .transaction(|tx| {
            Ok(tx
                .query_row("SELECT count(*) FROM object_key_requests", [], |row| {
                    row.get(0)
                })
                .unwrap())
        })
        .unwrap();
    assert_eq!(
        unused, 0,
        "acceptance must destroy the native disposable private key"
    );
    ok(
        &app,
        Method::POST,
        &format!("/v1/vaults/{vault_hex}/sharing/object-key-grants/ack"),
        &peer_token,
        grant.grant_id().as_bytes().to_vec(),
    )
    .await;
    let received = ok(
        &app,
        Method::GET,
        &format!(
            "/v1/vaults/{vault_hex}/objects/{store_hex}?offset=0&length={}",
            ciphertext.len()
        ),
        &peer_token,
        vec![],
    )
    .await;
    assert_eq!(received, ciphertext);
    let expected = sync_download::Expectation::new(
        object.object_id,
        object.stream_id,
        object.container_length,
        object.container_commitment,
    )
    .unwrap();
    sync_download::append_range(
        &peer_path,
        &peer.object_store_id(),
        &object.object_id,
        0,
        &received,
        &expected,
    )
    .unwrap();
    sync_download::activate_shared(&mut peer, &object, NOW + 5).unwrap();
    let mut exported = Vec::new();
    export::export_stream(
        &peer,
        &object_id,
        StreamKind::Original,
        &mut exported,
        &mut Uninterrupted,
    )
    .unwrap();
    assert_eq!(exported, original);
    drop(peer);
    let peer = vault::unlock_with_password(&peer_path, PASSWORD, NOW + 6).unwrap();
    let mut exported = Vec::new();
    export::export_stream(
        &peer,
        &object_id,
        StreamKind::Original,
        &mut exported,
        &mut Uninterrupted,
    )
    .unwrap();
    assert_eq!(exported, original);

    // No public sharing ACL may be inferred for an unrelated vault.
    let other_path = VaultRoot::new(directory.0.join("other"));
    let mut other = vault::create(&other_path, PASSWORD, NOW)
        .unwrap()
        .activate()
        .unwrap();
    let other_root = other.root_secret().unwrap().duplicate();
    let other_token = [20; 32];
    let other_id = bootstrap(&app, &mut other, &other_root, other_token).await;
    let other_vault = other.vault_id();
    let (_, other_identity) = sync_keys::local_identity(
        other.catalog_ref().unwrap(),
        &other_root,
        &chur_catalog::sync_membership::load(other.catalog_ref().unwrap())
            .unwrap()
            .unwrap(),
    )
    .unwrap()
    .unwrap();
    let foreign_request = ObjectPrekey::sign(
        random::id().unwrap(),
        collection,
        object_id,
        object.object_generation,
        object.container_commitment,
        other_vault,
        other_id,
        1,
        &Key::new([21; 32]),
        other_identity.signing_key(),
    )
    .unwrap();
    let (_, source_identity) = sync_keys::local_identity(
        source.catalog_ref().unwrap(),
        &root,
        &chur_catalog::sync_membership::load(source.catalog_ref().unwrap())
            .unwrap()
            .unwrap(),
    )
    .unwrap()
    .unwrap();
    let foreign_grant = ObjectKeyGrant::seal(
        random::id().unwrap(),
        vault_id,
        source_id,
        object_id,
        object.object_generation,
        object.container_commitment,
        &foreign_request,
        &Key::new([22; 32]),
        source_identity.signing_key(),
    )
    .unwrap();
    let (status, _) = exchange(
        &app,
        Method::POST,
        &format!("/v1/vaults/{vault_hex}/sharing/object-key-grants"),
        format!("Bearer {}", hex::encode(source_token)),
        foreign_grant.encode(),
    )
    .await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    let (status, _) = exchange(
        &app,
        Method::GET,
        &format!("/v1/vaults/{vault_hex}/objects/{store_hex}?offset=0&length=1"),
        format!("Bearer {}", hex::encode(other_token)),
        vec![],
    )
    .await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    let revoked = device_pairing::prepare_revoke(
        source.catalog().unwrap(),
        &root,
        vault_id,
        peer_id,
        NOW + 7,
    )
    .unwrap();
    ok(
        &app,
        Method::POST,
        &format!("/v1/vaults/{vault_hex}/checkpoints"),
        &source_token,
        revoked.checkpoint,
    )
    .await;
    ok(
        &app,
        Method::POST,
        &format!("/v1/vaults/{vault_hex}/memberships/revoke"),
        &source_token,
        pair(&revoked.revocation, &revoked.operation),
    )
    .await;
    let (status, _) = exchange(
        &app,
        Method::GET,
        &format!("/v1/vaults/{vault_hex}/objects/{store_hex}?offset=0&length=1"),
        format!("Bearer {}", hex::encode(peer_token)),
        vec![],
    )
    .await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    // Keep the wire's operation type checked by this integration target.
    assert!(Operation::decode(&approved.operation).is_ok());
}

fn append_offline_own_edit(session: &mut vault::Session, root: &Key, now: u64) {
    let plaintext = vec![42; 128];
    let running = import::begin(
        session,
        import::SourceCapability {
            seekable: true,
            known_length: Some(plaintext.len() as u64),
            content_type_hint: "application/octet-stream".into(),
            original_filename: Some("offline-edit.bin".into()),
            capture_time_ms: None,
        },
        import::CanonicalMedia {
            media_class: MediaClass::Opaque,
            width: 0,
            height: 0,
            duration_ms: 0,
        },
        now,
    )
    .unwrap();
    let object = import::stream_into(
        running,
        session,
        &mut Cursor::new(plaintext),
        "application/octet-stream",
        now,
        &mut Uninterrupted,
    )
    .unwrap();
    let vault = session.vault_id();
    let collection = store::object(session.catalog_ref().unwrap(), &object)
        .unwrap()
        .collection_id;
    let candidate =
        sharing_publication::candidate(session.catalog_ref().unwrap(), &collection, &object)
            .unwrap();
    own_sync::publish(
        session.catalog().unwrap(),
        root,
        vault,
        collection,
        &[candidate],
    )
    .unwrap();
}

#[tokio::test]
async fn cached_approval_and_revocation_checkpoints_recover_across_outbox_advance() {
    for advance_approval_outbox in [false, true] {
        let directory = Directory::new();
        let source_path = VaultRoot::new(directory.0.join("source"));
        let peer_path = VaultRoot::new(directory.0.join("peer"));
        let relay_path = directory.0.join("relay");
        let mut source = vault::create(&source_path, PASSWORD, NOW)
            .unwrap()
            .activate()
            .unwrap();
        let root = source.root_secret().unwrap().duplicate();
        let vault_id = source.vault_id();
        let vault_hex = vault_id.to_hex();
        let source_token = [18; 32];
        let mut app = http::router(
            ReferenceServer::open(&relay_path, 1_048_576, 4_194_304).unwrap(),
            BOOTSTRAP,
        );
        let source_id = bootstrap(&app, &mut source, &root, source_token).await;
        let mut backup_bytes = Cursor::new(Vec::new());
        backup::create(&mut source, &mut backup_bytes, NOW, &mut Uninterrupted).unwrap();
        backup::restore(
            &peer_path,
            &mut Cursor::new(backup_bytes.into_inner()),
            PASSWORD,
            &mut Uninterrupted,
        )
        .unwrap();
        let mut peer = vault::unlock_with_password(&peer_path, PASSWORD, NOW + 1).unwrap();
        let request =
            device_pairing::prepare_request(peer.catalog().unwrap(), &root, vault_id).unwrap();
        let approved = device_pairing::approve(
            source.catalog().unwrap(),
            &root,
            vault_id,
            &request,
            "http://localhost",
            &[19; 32],
            NOW + 2,
        )
        .unwrap();
        let enrollment_operation = Operation::decode(&approved.operation).unwrap();
        // Native approval is durable, but no checkpoint or /enroll HTTP happened.
        drop(source);
        source = vault::unlock_with_password(&source_path, PASSWORD, NOW + 3).unwrap();
        drop(peer);
        peer = vault::unlock_with_password(&peer_path, PASSWORD, NOW + 3).unwrap();
        let repeated_request =
            device_pairing::prepare_request(peer.catalog().unwrap(), &root, vault_id).unwrap();
        assert_eq!(
            repeated_request.encode(),
            request.encode(),
            "the human can re-export the same durable request after restart"
        );
        if advance_approval_outbox {
            append_offline_own_edit(&mut source, &root, NOW + 4);
        }
        // This is the exact native page and HTTP path used by ordinary Own outbox.
        let mut cursor = 0;
        for record in sync_log::records_after(source.catalog_ref().unwrap(), &source_id, 0).unwrap()
        {
            cursor = Operation::decode(&record).unwrap().device_sequence();
            ok(
                &app,
                Method::POST,
                &format!("/v1/vaults/{vault_hex}/operations"),
                &source_token,
                record,
            )
            .await;
        }
        if advance_approval_outbox {
            assert!(cursor > enrollment_operation.device_sequence());
        }
        let membership_before = ok(
            &app,
            Method::GET,
            &format!("/v1/vaults/{vault_hex}/memberships?after=0"),
            &source_token,
            vec![],
        )
        .await;
        assert_eq!(
            records(&membership_before).len(),
            1,
            "opaque AddDevice does not project relay membership"
        );
        receive(
            &app,
            &mut source,
            &source_path,
            &root,
            source_id,
            &source_token,
        )
        .await;
        drop(app);
        app = http::router(
            ReferenceServer::open(&relay_path, 1_048_576, 4_194_304).unwrap(),
            BOOTSTRAP,
        );
        let retry = device_pairing::approve(
            source.catalog().unwrap(),
            &root,
            vault_id,
            &request,
            "http://localhost",
            &[88; 32],
            NOW + 5,
        )
        .unwrap();
        assert_eq!(retry.checkpoint, approved.checkpoint);
        assert_eq!(retry.enrollment, approved.enrollment);
        assert_eq!(retry.operation, approved.operation);
        assert_eq!(retry.approval, approved.approval);
        assert_eq!(retry.transport_token, [19; 32]);
        ok(
            &app,
            Method::POST,
            &format!("/v1/vaults/{vault_hex}/checkpoints"),
            &source_token,
            retry.checkpoint.clone(),
        )
        .await;
        let enroll_body = [
            retry.transport_token.to_vec(),
            pair(&retry.enrollment, &retry.operation),
        ]
        .concat();
        for _ in 0..2 {
            ok(
                &app,
                Method::POST,
                &format!("/v1/vaults/{vault_hex}/memberships/enroll"),
                &source_token,
                enroll_body.clone(),
            )
            .await;
        }
        let current =
            device_pairing::current_checkpoint(source.catalog().unwrap(), &root, vault_id, NOW + 6)
                .unwrap();
        ok(
            &app,
            Method::POST,
            &format!("/v1/vaults/{vault_hex}/checkpoints"),
            &source_token,
            current.clone(),
        )
        .await;
        device_pairing::accept_start(peer.catalog().unwrap(), &root, vault_id, &retry.approval)
            .unwrap();
        receive(
            &app,
            &mut peer,
            &peer_path,
            &root,
            source_id,
            &retry.transport_token,
        )
        .await;
        let peer_id =
            device_pairing::finalize(peer.catalog().unwrap(), &root, vault_id, NOW + 7).unwrap();
        for record in sync_log::records_after(peer.catalog_ref().unwrap(), &peer_id, 0).unwrap() {
            ok(
                &app,
                Method::POST,
                &format!("/v1/vaults/{vault_hex}/operations"),
                &retry.transport_token,
                record,
            )
            .await;
        }
        receive(
            &app,
            &mut source,
            &source_path,
            &root,
            peer_id,
            &source_token,
        )
        .await;
        if advance_approval_outbox {
            // Isolate the ahead-of-relay error from the independent same-issuer checkpoint conflict.
            append_offline_own_edit(&mut source, &root, NOW + 8);
            for record in
                sync_log::records_after(source.catalog_ref().unwrap(), &source_id, cursor).unwrap()
            {
                cursor = Operation::decode(&record).unwrap().device_sequence();
                ok(
                    &app,
                    Method::POST,
                    &format!("/v1/vaults/{vault_hex}/operations"),
                    &source_token,
                    record,
                )
                .await;
            }
        }
        // Model the native revoke commit, process crash, then a later ordinary offline edit.
        let revoked = device_pairing::prepare_revoke(
            source.catalog().unwrap(),
            &root,
            vault_id,
            peer_id,
            NOW + 8,
        )
        .unwrap();
        drop(source);
        source = vault::unlock_with_password(&source_path, PASSWORD, NOW + 9).unwrap();
        append_offline_own_edit(&mut source, &root, NOW + 10);
        let cached = device_pairing::prepare_revoke(
            source.catalog().unwrap(),
            &root,
            vault_id,
            peer_id,
            NOW + 11,
        )
        .unwrap();
        assert_eq!(cached.operation, revoked.operation);
        let future = device_pairing::current_checkpoint(
            source.catalog().unwrap(),
            &root,
            vault_id,
            NOW + 12,
        )
        .unwrap();
        // A peer's Ready advances the frontier without advancing this issuer's sequence.
        for _ in 0..2 {
            ok(
                &app,
                Method::POST,
                &format!("/v1/vaults/{vault_hex}/checkpoints"),
                &source_token,
                cached.checkpoint.clone(),
            )
            .await;
        }
        // The latest page has one strongest frontier; old signed IDs remain exact receipts.
        let latest = ok(
            &app,
            Method::GET,
            &format!("/v1/vaults/{vault_hex}/checkpoints"),
            &source_token,
            vec![],
        )
        .await;
        assert_eq!(records(&latest), vec![cached.checkpoint.clone()]);
        drop(app);
        app = http::router(
            ReferenceServer::open(&relay_path, 1_048_576, 4_194_304).unwrap(),
            BOOTSTRAP,
        );
        ok(
            &app,
            Method::POST,
            &format!("/v1/vaults/{vault_hex}/checkpoints"),
            &source_token,
            current,
        )
        .await;
        // Replaying flushPending twice cannot reach ordinary outbox after this error.
        for _ in 0..2 {
            ok(
                &app,
                Method::POST,
                &format!("/v1/vaults/{vault_hex}/checkpoints"),
                &source_token,
                cached.checkpoint.clone(),
            )
            .await;
            ok(
                &app,
                Method::POST,
                &format!("/v1/vaults/{vault_hex}/memberships/revoke"),
                &source_token,
                pair(&cached.revocation, &cached.operation),
            )
            .await;
            let (status, error) = exchange(
                &app,
                Method::POST,
                &format!("/v1/vaults/{vault_hex}/checkpoints"),
                format!("Bearer {}", hex::encode(source_token)),
                future.clone(),
            )
            .await;
            assert_eq!(status, StatusCode::CONFLICT);
            assert_eq!(
                i32::from_be_bytes(error[..4].try_into().unwrap()),
                ChurStatus::SyncHeadRollback.as_i32(),
                "later ordinary local heads have not reached relay before current checkpoint"
            );
        }
        // Advancing the ordinary outbox before current checkpoint resolves the same bytes.
        for record in
            sync_log::records_after(source.catalog_ref().unwrap(), &source_id, cursor).unwrap()
        {
            ok(
                &app,
                Method::POST,
                &format!("/v1/vaults/{vault_hex}/operations"),
                &source_token,
                record,
            )
            .await;
        }
        // Even a correctly signed newer own head cannot drop the accepted peer head.
        let offered = Checkpoint::decode(&future).unwrap();
        let own_head = offered
            .heads()
            .iter()
            .find(|head| head.device_id() == &source_id)
            .unwrap()
            .clone();
        let membership = chur_catalog::sync_membership::load(source.catalog_ref().unwrap())
            .unwrap()
            .unwrap();
        let (_, identity) =
            sync_keys::local_identity(source.catalog_ref().unwrap(), &root, &membership)
                .unwrap()
                .unwrap();
        let incomparable = Checkpoint::new(
            vault_id,
            source_id,
            own_head.device_sequence(),
            offered.membership_generation(),
            *offered.membership_commitment(),
            vec![own_head],
            *offered.collection_epoch_commitment(),
            *offered.catalog_state_commitment(),
        )
        .unwrap()
        .sign(identity.signing_key());
        let (status, error) = exchange(
            &app,
            Method::POST,
            &format!("/v1/vaults/{vault_hex}/checkpoints"),
            format!("Bearer {}", hex::encode(source_token)),
            incomparable.encode(),
        )
        .await;
        assert_eq!(status, StatusCode::CONFLICT);
        assert_eq!(
            i32::from_be_bytes(error[..4].try_into().unwrap()),
            ChurStatus::SyncHeadRollback.as_i32()
        );
        ok(
            &app,
            Method::POST,
            &format!("/v1/vaults/{vault_hex}/checkpoints"),
            &source_token,
            future,
        )
        .await;
    }
}
