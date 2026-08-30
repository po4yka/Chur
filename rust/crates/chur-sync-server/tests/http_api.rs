//! HTTP reference-server integration tests.

#![allow(clippy::expect_used)]

use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};

use axum::body::Body;
use axum::http::{Method, Request, StatusCode};
use chur_core::Id;
use chur_crypto::secret::Key;
use chur_sync_protocol::checkpoint::{Checkpoint, CheckpointHead};
use chur_sync_protocol::collection_membership::{
    CollectionMembershipAction, CollectionMembershipRecord,
};
use chur_sync_protocol::deletion::ServerDeletionAuthorization;
use chur_sync_protocol::grant::{CollectionGrant, PermissionProfile};
use chur_sync_protocol::identity::DeviceIdentity;
use chur_sync_protocol::membership::{EnrollmentRecord, RevocationRecord};
use chur_sync_protocol::operation::{DeviceSigningKey, Operation};
use chur_sync_server::ReferenceServer;
use sha2::{Digest, Sha256};
use tower::ServiceExt;

static NEXT: AtomicU64 = AtomicU64::new(0);
const BOOTSTRAP_TOKEN: [u8; 32] = [10; 32];

#[tokio::test]
async fn health_checks_the_open_server() {
    let response = chur_sync_server::http::router(server(), BOOTSTRAP_TOKEN)
        .oneshot(
            Request::builder()
                .uri("/healthz")
                .body(Body::empty())
                .expect("request"),
        )
        .await
        .expect("response");
    assert_eq!(response.status(), StatusCode::NO_CONTENT);
}

#[tokio::test]
async fn bootstrap_installs_transport_auth_and_relays_canonical_records() {
    let vault = id(1);
    let device = id(2);
    let key = DeviceSigningKey::from_seed([3; 32]);
    let enrollment = EnrollmentRecord::initial(vault, device, key.verifying_key(), [4; 32])
        .expect("enrollment")
        .sign(&key);
    let initial_operation = Operation::new(
        id(5),
        vault,
        device,
        1,
        [0; 32],
        Vec::new(),
        id(6),
        [vec![7; 24], vec![8; 16]].concat(),
        [0; 64],
    )
    .expect("operation")
    .sign(&key);
    let token = [9; 32];
    let app = chur_sync_server::http::router(server(), BOOTSTRAP_TOKEN);

    let response = app
        .clone()
        .oneshot(request(
            Method::POST,
            &format!("/v1/vaults/{}/bootstrap", hex::encode(vault.as_bytes())),
            paired_body(&token, &enrollment.encode(), &initial_operation.encode()),
            Some(("Bootstrap", &[99; 32])),
        ))
        .await
        .expect("rejected bootstrap response");
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);

    let response = app
        .clone()
        .oneshot(request(
            Method::POST,
            &format!("/v1/vaults/{}/bootstrap", hex::encode(vault.as_bytes())),
            paired_body(&token, &enrollment.encode(), &initial_operation.encode()),
            Some(("Bootstrap", &BOOTSTRAP_TOKEN)),
        ))
        .await
        .expect("bootstrap response");
    assert_eq!(response.status(), StatusCode::CREATED);

    let response = app
        .clone()
        .oneshot(request(
            Method::GET,
            &format!(
                "/v1/vaults/{}/memberships?after=0",
                hex::encode(vault.as_bytes())
            ),
            Vec::new(),
            Some(("Bearer", &token)),
        ))
        .await
        .expect("membership response");
    assert_eq!(response.status(), StatusCode::OK);
    let body = axum::body::to_bytes(response.into_body(), usize::MAX)
        .await
        .expect("response body");
    assert_eq!(body.as_ref(), framed(&[enrollment.encode()]));

    let second = operation(vault, device, id(11), 2, initial_operation.digest(), &key);
    let response = app
        .clone()
        .oneshot(request(
            Method::POST,
            &format!("/v1/vaults/{}/operations", hex::encode(vault.as_bytes())),
            second.encode(),
            Some(("Bearer", &token)),
        ))
        .await
        .expect("operation response");
    assert_eq!(response.status(), StatusCode::CREATED);

    let checkpoint = Checkpoint::new(
        vault,
        device,
        2,
        1,
        enrollment.commitment(),
        vec![CheckpointHead::new(device, 2, second.digest())],
        [12; 32],
        [0; 32],
    )
    .expect("checkpoint")
    .sign(&key);
    let response = app
        .clone()
        .oneshot(request(
            Method::POST,
            &format!("/v1/vaults/{}/checkpoints", hex::encode(vault.as_bytes())),
            checkpoint.encode(),
            Some(("Bearer", &token)),
        ))
        .await
        .expect("checkpoint response");
    assert_eq!(response.status(), StatusCode::CREATED);

    let response = app
        .clone()
        .oneshot(request(
            Method::GET,
            &format!(
                "/v1/vaults/{}/operations/{}?after=0",
                hex::encode(vault.as_bytes()),
                hex::encode(device.as_bytes())
            ),
            Vec::new(),
            Some(("Bearer", &token)),
        ))
        .await
        .expect("operation page response");
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(
        axum::body::to_bytes(response.into_body(), usize::MAX)
            .await
            .expect("operation page")
            .as_ref(),
        framed(&[initial_operation.encode(), second.encode()])
    );

    let response = app
        .clone()
        .oneshot(request(
            Method::GET,
            &format!(
                "/v1/vaults/{}/checkpoints/{}",
                hex::encode(vault.as_bytes()),
                hex::encode(checkpoint.commitment())
            ),
            Vec::new(),
            Some(("Bearer", &token)),
        ))
        .await
        .expect("checkpoint fetch response");
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(
        axum::body::to_bytes(response.into_body(), usize::MAX)
            .await
            .expect("checkpoint body")
            .as_ref(),
        checkpoint.encode()
    );

    let second_device = id(13);
    let second_key = DeviceSigningKey::from_seed([14; 32]);
    let third = operation(vault, device, id(15), 3, second.digest(), &key);
    let second_enrollment = EnrollmentRecord::new(
        vault,
        second_device,
        second_key.verifying_key(),
        [16; 32],
        3,
        device,
        2,
        enrollment.commitment(),
        checkpoint.commitment(),
    )
    .expect("second enrollment")
    .sign(&key);
    let second_token = [17; 32];
    let response = app
        .clone()
        .oneshot(request(
            Method::POST,
            &format!(
                "/v1/vaults/{}/memberships/enroll",
                hex::encode(vault.as_bytes())
            ),
            paired_body(&second_token, &second_enrollment.encode(), &third.encode()),
            Some(("Bearer", &token)),
        ))
        .await
        .expect("enrollment response");
    assert_eq!(response.status(), StatusCode::CREATED);

    let second_device_operation = operation(vault, second_device, id(18), 1, [0; 32], &second_key);
    let response = app
        .clone()
        .oneshot(request(
            Method::POST,
            &format!("/v1/vaults/{}/operations", hex::encode(vault.as_bytes())),
            second_device_operation.encode(),
            Some(("Bearer", &second_token)),
        ))
        .await
        .expect("second-device operation response");
    assert_eq!(response.status(), StatusCode::CREATED);

    let fourth = operation(vault, device, id(19), 4, third.digest(), &key);
    let revocation = RevocationRecord::new(
        vault,
        second_device,
        1,
        second_device_operation.digest(),
        3,
        device,
        second_enrollment.commitment(),
    )
    .expect("revocation")
    .sign(&key);
    let response = app
        .clone()
        .oneshot(request(
            Method::POST,
            &format!(
                "/v1/vaults/{}/memberships/revoke",
                hex::encode(vault.as_bytes())
            ),
            pair_body(&revocation.encode(), &fourth.encode()),
            Some(("Bearer", &token)),
        ))
        .await
        .expect("revocation response");
    assert_eq!(response.status(), StatusCode::CREATED);

    let response = app
        .clone()
        .oneshot(request(
            Method::GET,
            &format!(
                "/v1/vaults/{}/operations/{}?after=0",
                hex::encode(vault.as_bytes()),
                hex::encode(second_device.as_bytes())
            ),
            Vec::new(),
            Some(("Bearer", &second_token)),
        ))
        .await
        .expect("revoked response");
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);

    let replacement_token = [20; 32];
    let response = app
        .clone()
        .oneshot(request(
            Method::POST,
            &format!("/v1/vaults/{}/token", hex::encode(vault.as_bytes())),
            replacement_token.to_vec(),
            Some(("Bearer", &token)),
        ))
        .await
        .expect("token rotation response");
    assert_eq!(response.status(), StatusCode::NO_CONTENT);
    let response = app
        .clone()
        .oneshot(request(
            Method::GET,
            &format!("/v1/vaults/{}/checkpoints", hex::encode(vault.as_bytes())),
            Vec::new(),
            Some(("Bearer", &replacement_token)),
        ))
        .await
        .expect("rotated token response");
    assert_eq!(response.status(), StatusCode::OK);

    let transfer = id(21);
    let store = id(22);
    let object = b"opaque object";
    let checksum: [u8; 32] = Sha256::digest(object).into();
    let response = app
        .clone()
        .oneshot(request(
            Method::POST,
            &format!(
                "/v1/vaults/{}/objects/{}/uploads/{}?length={}",
                hex::encode(vault.as_bytes()),
                hex::encode(store.as_bytes()),
                hex::encode(transfer.as_bytes()),
                object.len()
            ),
            Vec::new(),
            Some(("Bearer", &replacement_token)),
        ))
        .await
        .expect("begin upload response");
    assert_eq!(response.status(), StatusCode::CREATED);
    let response = app
        .clone()
        .oneshot(request(
            Method::PATCH,
            &format!(
                "/v1/vaults/{}/uploads/{}?offset=0&sha256={}",
                hex::encode(vault.as_bytes()),
                hex::encode(transfer.as_bytes()),
                hex::encode(checksum)
            ),
            object.to_vec(),
            Some(("Bearer", &replacement_token)),
        ))
        .await
        .expect("append upload response");
    assert_eq!(response.status(), StatusCode::OK);
    let response = app
        .clone()
        .oneshot(request(
            Method::POST,
            &format!(
                "/v1/vaults/{}/uploads/{}/finish?sha256={}",
                hex::encode(vault.as_bytes()),
                hex::encode(transfer.as_bytes()),
                hex::encode(checksum)
            ),
            Vec::new(),
            Some(("Bearer", &replacement_token)),
        ))
        .await
        .expect("finish upload response");
    assert_eq!(response.status(), StatusCode::OK);
    let response = app
        .clone()
        .oneshot(request(
            Method::GET,
            &format!(
                "/v1/vaults/{}/objects/{}?offset=0&length=64",
                hex::encode(vault.as_bytes()),
                hex::encode(store.as_bytes())
            ),
            Vec::new(),
            Some(("Bearer", &replacement_token)),
        ))
        .await
        .expect("download response");
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(
        axum::body::to_bytes(response.into_body(), usize::MAX)
            .await
            .expect("download body")
            .as_ref(),
        object
    );

    let deletion =
        ServerDeletionAuthorization::object(id(23), vault, device, store, fourth.digest())
            .expect("deletion")
            .sign(&key);
    let response = app
        .clone()
        .oneshot(request(
            Method::POST,
            &format!("/v1/vaults/{}/deletions", hex::encode(vault.as_bytes())),
            deletion.encode(),
            None,
        ))
        .await
        .expect("deletion response");
    assert_eq!(response.status(), StatusCode::NO_CONTENT);
    let response = app
        .oneshot(request(
            Method::GET,
            &format!(
                "/v1/vaults/{}/objects/{}?offset=0&length=64",
                hex::encode(vault.as_bytes()),
                hex::encode(store.as_bytes())
            ),
            Vec::new(),
            Some(("Bearer", &replacement_token)),
        ))
        .await
        .expect("deleted object response");
    assert_eq!(response.status(), StatusCode::UNPROCESSABLE_ENTITY);
}

#[tokio::test]
async fn sharing_endpoints_authenticate_issuers_and_recipient_inboxes() {
    let source_vault = id(30);
    let source_device = id(31);
    let source_key = DeviceSigningKey::from_seed([32; 32]);
    let source_enrollment = EnrollmentRecord::initial(
        source_vault,
        source_device,
        source_key.verifying_key(),
        [33; 32],
    )
    .expect("source enrollment")
    .sign(&source_key);
    let source_initial = operation(source_vault, source_device, id(34), 1, [0; 32], &source_key);
    let source_token = [35; 32];
    let recipient_vault = id(36);
    let recipient_device = id(37);
    let recipient = DeviceIdentity::from_seeds([38; 32], [39; 32]);
    let recipient_enrollment = EnrollmentRecord::initial(
        recipient_vault,
        recipient_device,
        recipient.signing_public_key(),
        recipient.hpke_public_key(),
    )
    .expect("recipient enrollment")
    .sign(recipient.signing_key());
    let recipient_initial = operation(
        recipient_vault,
        recipient_device,
        id(40),
        1,
        [0; 32],
        recipient.signing_key(),
    );
    let recipient_token = [41; 32];
    let app = chur_sync_server::http::router(server(), BOOTSTRAP_TOKEN);
    bootstrap_vault(
        &app,
        source_vault,
        &source_token,
        &source_enrollment,
        &source_initial,
    )
    .await;
    bootstrap_vault(
        &app,
        recipient_vault,
        &recipient_token,
        &recipient_enrollment,
        &recipient_initial,
    )
    .await;

    let collection_id = id(42);
    let membership = CollectionMembershipRecord::new(
        source_vault,
        collection_id,
        1,
        [0; 32],
        CollectionMembershipAction::Upsert(PermissionProfile::Read),
        recipient_vault,
        recipient_device,
        recipient.signing_public_key(),
        recipient.hpke_public_key(),
        1,
        source_vault,
        source_device,
        1,
        2,
    )
    .expect("membership")
    .sign(&source_key);
    let membership_outer = operation(
        source_vault,
        source_device,
        id(43),
        2,
        source_initial.digest(),
        &source_key,
    );
    let membership_uri = format!(
        "/v1/vaults/{}/sharing/memberships",
        hex::encode(source_vault.as_bytes())
    );
    let response = app
        .clone()
        .oneshot(request(
            Method::POST,
            &membership_uri,
            pair_body(&membership.encode(), &membership_outer.encode()),
            Some(("Bearer", &recipient_token)),
        ))
        .await
        .expect("wrong issuer response");
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    let response = app
        .clone()
        .oneshot(request(
            Method::POST,
            &membership_uri,
            pair_body(&membership.encode(), &membership_outer.encode()),
            Some(("Bearer", &source_token)),
        ))
        .await
        .expect("membership response");
    assert_eq!(response.status(), StatusCode::CREATED);

    let grant = CollectionGrant::seal(
        id(44),
        source_vault,
        collection_id,
        1,
        1,
        recipient_vault,
        recipient_device,
        &recipient.hpke_public_key(),
        source_device,
        PermissionProfile::Read,
        1,
        3,
        &Key::new([45; 32]),
        &source_key,
    )
    .expect("grant");
    let grant_outer = operation(
        source_vault,
        source_device,
        id(44),
        3,
        membership_outer.digest(),
        &source_key,
    );
    let response = app
        .clone()
        .oneshot(request(
            Method::POST,
            &format!(
                "/v1/vaults/{}/sharing/grants",
                hex::encode(source_vault.as_bytes())
            ),
            pair_body(&grant.encode(), &grant_outer.encode()),
            Some(("Bearer", &source_token)),
        ))
        .await
        .expect("grant response");
    assert_eq!(response.status(), StatusCode::CREATED);

    for (path, expected) in [
        ("memberships", membership.encode()),
        ("grants", grant.encode()),
    ] {
        let response = app
            .clone()
            .oneshot(request(
                Method::GET,
                &format!(
                    "/v1/vaults/{}/sharing/{path}",
                    hex::encode(recipient_vault.as_bytes())
                ),
                Vec::new(),
                Some(("Bearer", &recipient_token)),
            ))
            .await
            .expect("inbox response");
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(
            axum::body::to_bytes(response.into_body(), usize::MAX)
                .await
                .expect("inbox body")
                .as_ref(),
            framed(&[expected])
        );
    }
}

async fn bootstrap_vault(
    app: &axum::Router,
    vault: Id,
    token: &[u8; 32],
    enrollment: &EnrollmentRecord,
    operation: &Operation,
) {
    let response = app
        .clone()
        .oneshot(request(
            Method::POST,
            &format!("/v1/vaults/{}/bootstrap", hex::encode(vault.as_bytes())),
            paired_body(token, &enrollment.encode(), &operation.encode()),
            Some(("Bootstrap", &BOOTSTRAP_TOKEN)),
        ))
        .await
        .expect("bootstrap response");
    assert_eq!(response.status(), StatusCode::CREATED);
}

fn request(
    method: Method,
    uri: &str,
    body: Vec<u8>,
    authorization: Option<(&str, &[u8; 32])>,
) -> Request<Body> {
    let mut request = Request::builder().method(method).uri(uri);
    if let Some((scheme, token)) = authorization {
        request = request.header("authorization", format!("{scheme} {}", hex::encode(token)));
    }
    request.body(Body::from(body)).expect("request")
}

fn paired_body(token: &[u8; 32], first: &[u8], second: &[u8]) -> Vec<u8> {
    let mut body = Vec::with_capacity(36 + first.len() + second.len());
    body.extend_from_slice(token);
    body.extend_from_slice(&u32::try_from(first.len()).expect("length").to_be_bytes());
    body.extend_from_slice(first);
    body.extend_from_slice(second);
    body
}

fn pair_body(first: &[u8], second: &[u8]) -> Vec<u8> {
    let mut body = Vec::with_capacity(4 + first.len() + second.len());
    body.extend_from_slice(&u32::try_from(first.len()).expect("length").to_be_bytes());
    body.extend_from_slice(first);
    body.extend_from_slice(second);
    body
}

fn operation(
    vault: Id,
    device: Id,
    operation_id: Id,
    sequence: u64,
    previous: [u8; 32],
    key: &DeviceSigningKey,
) -> Operation {
    Operation::new(
        operation_id,
        vault,
        device,
        sequence,
        previous,
        Vec::new(),
        id(6),
        [vec![7; 24], vec![8; 16]].concat(),
        [0; 64],
    )
    .expect("operation")
    .sign(key)
}

fn framed(records: &[Vec<u8>]) -> Vec<u8> {
    let mut body = Vec::new();
    body.extend_from_slice(&u32::try_from(records.len()).expect("count").to_be_bytes());
    for record in records {
        body.extend_from_slice(&u32::try_from(record.len()).expect("length").to_be_bytes());
        body.extend_from_slice(record);
    }
    body
}

fn id(byte: u8) -> Id {
    Id::new([byte; 16]).expect("id")
}

fn server() -> ReferenceServer {
    ReferenceServer::open(scratch(), 1024, 4096).expect("server")
}

fn scratch() -> PathBuf {
    let path = std::env::temp_dir().join(format!(
        "chur-sync-http-{}-{}",
        std::process::id(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    ));
    let _ = std::fs::remove_dir_all(&path);
    path
}
