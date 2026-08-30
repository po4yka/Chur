//! HTTP transport for the self-hosted reference server.

use std::sync::{Arc, Mutex};

use axum::Router;
use axum::body::Bytes;
use axum::extract::State;
use axum::extract::{DefaultBodyLimit, Path, RawQuery};
use axum::http::{HeaderMap, StatusCode, header};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use chur_core::limits::sync as bounds;
use chur_core::{ChurStatus, Error, Id, ensure};
use chur_sync_protocol::checkpoint::Checkpoint;
use chur_sync_protocol::collection_membership::CollectionMembershipRecord;
use chur_sync_protocol::collection_operation::CollectionOperation;
use chur_sync_protocol::deletion::ServerDeletionAuthorization;
use chur_sync_protocol::grant::CollectionGrant;
use chur_sync_protocol::membership::{EnrollmentRecord, RevocationRecord};
use chur_sync_protocol::operation::Operation;

use crate::{DeletionOutcome, ReferenceServer, RelayOutcome, UploadProgress};

#[derive(Clone)]
struct AppState {
    // ponytail: one serialized core keeps SQLite and file transactions ordered;
    // use a blocking worker pool if concurrent transfer throughput needs it.
    server: Arc<Mutex<ReferenceServer>>,
    bootstrap_token: [u8; 32],
}

/// Builds the reference HTTP service.
pub fn router(server: ReferenceServer, bootstrap_token: [u8; 32]) -> Router {
    let state = AppState {
        server: Arc::new(Mutex::new(server)),
        bootstrap_token,
    };
    Router::new()
        .route("/healthz", get(health))
        .route("/v1/vaults/{vault}/bootstrap", post(bootstrap))
        .route("/v1/vaults/{vault}/memberships", get(memberships))
        .route("/v1/vaults/{vault}/memberships/enroll", post(enroll))
        .route("/v1/vaults/{vault}/memberships/revoke", post(revoke))
        .route("/v1/vaults/{vault}/operations", post(operation))
        .route("/v1/vaults/{vault}/operations/{device}", get(operations))
        .route(
            "/v1/vaults/{vault}/sharing/memberships",
            get(sharing_memberships).post(sharing_membership),
        )
        .route(
            "/v1/vaults/{vault}/sharing/grants",
            get(sharing_grants).post(sharing_grant),
        )
        .route(
            "/v1/vaults/{vault}/sharing/operations",
            post(sharing_operation),
        )
        .route(
            "/v1/vaults/{vault}/sharing/operations/{selector}",
            get(sharing_operations),
        )
        .route(
            "/v1/vaults/{vault}/checkpoints",
            get(checkpoints).post(checkpoint),
        )
        .route(
            "/v1/vaults/{vault}/checkpoints/{commitment}",
            get(checkpoint_by_commitment),
        )
        .route("/v1/vaults/{vault}/token", post(rotate_token))
        .route(
            "/v1/vaults/{vault}/objects/{store}/uploads/{transfer}",
            post(begin_upload),
        )
        .route(
            "/v1/vaults/{vault}/uploads/{transfer}",
            axum::routing::patch(append_upload),
        )
        .route(
            "/v1/vaults/{vault}/uploads/{transfer}/finish",
            post(finish_upload),
        )
        .route("/v1/vaults/{vault}/objects/{store}", get(download_object))
        .route("/v1/vaults/{vault}/deletions", post(delete))
        .layer(DefaultBodyLimit::max(bounds::RESPONSE_BYTES_MAX))
        .with_state(state)
}

async fn health(State(state): State<AppState>) -> StatusCode {
    match state.server.lock() {
        Ok(_) => StatusCode::NO_CONTENT,
        Err(_) => StatusCode::SERVICE_UNAVAILABLE,
    }
}

async fn bootstrap(
    State(state): State<AppState>,
    Path(vault): Path<String>,
    headers: HeaderMap,
    body: Bytes,
) -> HttpResult<StatusCode> {
    authenticate_bootstrap(&state, &headers)?;
    let vault = id(&vault)?;
    let (token, enrollment_bytes, operation_bytes) = token_pair(&body)?;
    let enrollment = EnrollmentRecord::decode(enrollment_bytes)?;
    let operation = Operation::decode(operation_bytes)?;
    path_matches(
        enrollment.vault_id() == &vault && operation.vault_id() == &vault,
        "bootstrap path does not match its records",
    )?;
    let mut server = lock(&state)?;
    let outcome = server.accept_initial_membership(&enrollment, &operation)?;
    server.set_transport_token(vault, *enrollment.device_id(), &token)?;
    Ok(relay_status(outcome))
}

async fn enroll(
    State(state): State<AppState>,
    Path(vault): Path<String>,
    headers: HeaderMap,
    body: Bytes,
) -> HttpResult<StatusCode> {
    let vault = id(&vault)?;
    let (token, enrollment_bytes, operation_bytes) = token_pair(&body)?;
    let enrollment = EnrollmentRecord::decode(enrollment_bytes)?;
    let operation = Operation::decode(operation_bytes)?;
    path_matches(
        enrollment.vault_id() == &vault && operation.vault_id() == &vault,
        "enrollment path does not match its records",
    )?;
    let mut server = lock(&state)?;
    authenticate(&server, vault, &headers)?;
    let outcome = server.accept_enrollment(&enrollment, &operation)?;
    server.set_transport_token(vault, *enrollment.device_id(), &token)?;
    Ok(relay_status(outcome))
}

async fn revoke(
    State(state): State<AppState>,
    Path(vault): Path<String>,
    headers: HeaderMap,
    body: Bytes,
) -> HttpResult<StatusCode> {
    let vault = id(&vault)?;
    let (revocation_bytes, operation_bytes) = pair(&body)?;
    let revocation = RevocationRecord::decode(revocation_bytes)?;
    let operation = Operation::decode(operation_bytes)?;
    path_matches(
        revocation.vault_id() == &vault && operation.vault_id() == &vault,
        "revocation path does not match its records",
    )?;
    let mut server = lock(&state)?;
    authenticate(&server, vault, &headers)?;
    Ok(relay_status(
        server.accept_revocation(&revocation, &operation)?,
    ))
}

async fn memberships(
    State(state): State<AppState>,
    Path(vault): Path<String>,
    RawQuery(query): RawQuery,
    headers: HeaderMap,
) -> HttpResult<Response> {
    let vault = id(&vault)?;
    let after = query_u64(query.as_deref(), "after")?;
    let server = lock(&state)?;
    authenticate(&server, vault, &headers)?;
    binary(records(server.membership_records_after(vault, after)?))
}

async fn operation(
    State(state): State<AppState>,
    Path(vault): Path<String>,
    headers: HeaderMap,
    body: Bytes,
) -> HttpResult<StatusCode> {
    let vault = id(&vault)?;
    let operation = Operation::decode(&body)?;
    path_matches(
        operation.vault_id() == &vault,
        "operation path does not match its record",
    )?;
    let mut server = lock(&state)?;
    authenticate(&server, vault, &headers)?;
    Ok(relay_status(server.accept_operation(&operation)?))
}

async fn operations(
    State(state): State<AppState>,
    Path((vault, device)): Path<(String, String)>,
    RawQuery(query): RawQuery,
    headers: HeaderMap,
) -> HttpResult<Response> {
    let vault = id(&vault)?;
    let device = id(&device)?;
    let after = query_u64(query.as_deref(), "after")?;
    let server = lock(&state)?;
    authenticate(&server, vault, &headers)?;
    binary(records(server.operations_after(vault, device, after)?))
}

async fn sharing_membership(
    State(state): State<AppState>,
    Path(vault): Path<String>,
    headers: HeaderMap,
    body: Bytes,
) -> HttpResult<StatusCode> {
    let vault = id(&vault)?;
    let (membership_bytes, operation_bytes) = pair(&body)?;
    let membership = CollectionMembershipRecord::decode(membership_bytes)?;
    let operation = Operation::decode(operation_bytes)?;
    path_matches(
        membership.issuer_identity_vault_id() == &vault && operation.vault_id() == &vault,
        "sharing membership path does not match its records",
    )?;
    let mut server = lock(&state)?;
    authenticate(&server, vault, &headers)?;
    Ok(relay_status(
        server.accept_collection_membership(&membership, &operation)?,
    ))
}

async fn sharing_memberships(
    State(state): State<AppState>,
    Path(vault): Path<String>,
    headers: HeaderMap,
) -> HttpResult<Response> {
    let vault = id(&vault)?;
    let server = lock(&state)?;
    let device = authenticate(&server, vault, &headers)?;
    binary(records(
        server.collection_memberships_for_recipient(vault, device)?,
    ))
}

async fn sharing_grant(
    State(state): State<AppState>,
    Path(vault): Path<String>,
    headers: HeaderMap,
    body: Bytes,
) -> HttpResult<StatusCode> {
    let vault = id(&vault)?;
    let (grant_bytes, operation_bytes) = pair(&body)?;
    let grant = CollectionGrant::decode(grant_bytes)?;
    let operation = Operation::decode(operation_bytes)?;
    path_matches(
        operation.vault_id() == &vault,
        "sharing grant path does not match its operation",
    )?;
    let mut server = lock(&state)?;
    authenticate(&server, vault, &headers)?;
    Ok(relay_status(
        server.accept_collection_grant(&grant, &operation)?,
    ))
}

async fn sharing_operation(
    State(state): State<AppState>,
    Path(vault): Path<String>,
    headers: HeaderMap,
    body: Bytes,
) -> HttpResult<StatusCode> {
    let vault = id(&vault)?;
    let operation = CollectionOperation::decode(&body)?;
    path_matches(
        operation.issuer_identity_vault_id() == &vault,
        "collection operation path does not match its issuer",
    )?;
    let mut server = lock(&state)?;
    let device = authenticate(&server, vault, &headers)?;
    path_matches(
        operation.issuer_device_id() == &device,
        "collection operation transport device does not match its issuer",
    )?;
    Ok(relay_status(
        server.accept_collection_operation(&operation)?,
    ))
}

async fn sharing_operations(
    State(state): State<AppState>,
    Path((vault, selector)): Path<(String, String)>,
    headers: HeaderMap,
) -> HttpResult<Response> {
    let vault = id(&vault)?;
    let selector = id(&selector)?;
    let server = lock(&state)?;
    let device = authenticate(&server, vault, &headers)?;
    binary(records(server.collection_operations_for_recipient(
        vault, device, selector,
    )?))
}

async fn sharing_grants(
    State(state): State<AppState>,
    Path(vault): Path<String>,
    headers: HeaderMap,
) -> HttpResult<Response> {
    let vault = id(&vault)?;
    let server = lock(&state)?;
    let device = authenticate(&server, vault, &headers)?;
    binary(records(
        server.collection_grants_for_recipient(vault, device)?,
    ))
}

async fn checkpoint(
    State(state): State<AppState>,
    Path(vault): Path<String>,
    headers: HeaderMap,
    body: Bytes,
) -> HttpResult<StatusCode> {
    let vault = id(&vault)?;
    let checkpoint = Checkpoint::decode(&body)?;
    path_matches(
        checkpoint.vault_id() == &vault,
        "checkpoint path does not match its record",
    )?;
    let mut server = lock(&state)?;
    authenticate(&server, vault, &headers)?;
    Ok(relay_status(server.accept_checkpoint(&checkpoint)?))
}

async fn checkpoints(
    State(state): State<AppState>,
    Path(vault): Path<String>,
    headers: HeaderMap,
) -> HttpResult<Response> {
    let vault = id(&vault)?;
    let server = lock(&state)?;
    authenticate(&server, vault, &headers)?;
    binary(records(server.checkpoints(vault)?))
}

async fn checkpoint_by_commitment(
    State(state): State<AppState>,
    Path((vault, commitment)): Path<(String, String)>,
    headers: HeaderMap,
) -> HttpResult<Response> {
    let vault = id(&vault)?;
    let commitment = hex::<32>(&commitment)?;
    let server = lock(&state)?;
    authenticate(&server, vault, &headers)?;
    binary(server.checkpoint(vault, commitment)?)
}

async fn rotate_token(
    State(state): State<AppState>,
    Path(vault): Path<String>,
    headers: HeaderMap,
    body: Bytes,
) -> HttpResult<StatusCode> {
    let vault = id(&vault)?;
    let token: [u8; 32] = body
        .as_ref()
        .try_into()
        .map_err(|_| Error::new(ChurStatus::InvalidInput, "transport token is not 32 bytes"))?;
    let mut server = lock(&state)?;
    let device = authenticate(&server, vault, &headers)?;
    server.set_transport_token(vault, device, &token)?;
    Ok(StatusCode::NO_CONTENT)
}

async fn begin_upload(
    State(state): State<AppState>,
    Path((vault, store, transfer)): Path<(String, String, String)>,
    RawQuery(query): RawQuery,
    headers: HeaderMap,
) -> HttpResult<Response> {
    let vault = id(&vault)?;
    let store = id(&store)?;
    let transfer = id(&transfer)?;
    let expected = query_u64(query.as_deref(), "length")?;
    let mut server = lock(&state)?;
    authenticate(&server, vault, &headers)?;
    let progress = server.begin_upload(vault, transfer, store, expected)?;
    progress_response(StatusCode::CREATED, progress)
}

async fn append_upload(
    State(state): State<AppState>,
    Path((vault, transfer)): Path<(String, String)>,
    RawQuery(query): RawQuery,
    headers: HeaderMap,
    body: Bytes,
) -> HttpResult<Response> {
    let vault = id(&vault)?;
    let transfer = id(&transfer)?;
    let offset = query_u64(query.as_deref(), "offset")?;
    let checksum = query_hex(query.as_deref(), "sha256")?;
    let mut server = lock(&state)?;
    authenticate(&server, vault, &headers)?;
    let progress = server.append_upload(vault, transfer, offset, &body, checksum)?;
    progress_response(StatusCode::OK, progress)
}

async fn finish_upload(
    State(state): State<AppState>,
    Path((vault, transfer)): Path<(String, String)>,
    RawQuery(query): RawQuery,
    headers: HeaderMap,
) -> HttpResult<Response> {
    let vault = id(&vault)?;
    let transfer = id(&transfer)?;
    let checksum = query_hex(query.as_deref(), "sha256")?;
    let mut server = lock(&state)?;
    authenticate(&server, vault, &headers)?;
    let progress = server.finish_upload(vault, transfer, checksum)?;
    progress_response(StatusCode::OK, progress)
}

async fn download_object(
    State(state): State<AppState>,
    Path((vault, store)): Path<(String, String)>,
    RawQuery(query): RawQuery,
    headers: HeaderMap,
) -> HttpResult<Response> {
    let vault = id(&vault)?;
    let store = id(&store)?;
    let offset = query_u64(query.as_deref(), "offset")?;
    let length = query_u64(query.as_deref(), "length")?;
    let server = lock(&state)?;
    authenticate(&server, vault, &headers)?;
    binary(server.read_object(vault, store, offset, length)?)
}

async fn delete(
    State(state): State<AppState>,
    Path(vault): Path<String>,
    body: Bytes,
) -> HttpResult<StatusCode> {
    let vault = id(&vault)?;
    let authorization = ServerDeletionAuthorization::decode(&body)?;
    path_matches(
        authorization.vault_id() == &vault,
        "deletion path does not match its record",
    )?;
    let mut server = lock(&state)?;
    Ok(match server.apply_deletion(&authorization)? {
        DeletionOutcome::Deleted => StatusCode::NO_CONTENT,
        DeletionOutcome::Duplicate => StatusCode::OK,
    })
}

fn authenticate(server: &ReferenceServer, vault: Id, headers: &HeaderMap) -> chur_core::Result<Id> {
    let value = headers
        .get(header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.strip_prefix("Bearer "))
        .ok_or_else(|| {
            Error::new(
                ChurStatus::AuthenticationFailed,
                "transport token is absent",
            )
        })?;
    let token = hex(value).map_err(|_| {
        Error::new(
            ChurStatus::AuthenticationFailed,
            "transport token is not accepted",
        )
    })?;
    server.authenticate_transport(vault, &token)
}

fn authenticate_bootstrap(state: &AppState, headers: &HeaderMap) -> chur_core::Result<()> {
    let supplied = headers
        .get(header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.strip_prefix("Bootstrap "))
        .and_then(|value| hex::<32>(value).ok())
        .ok_or_else(|| {
            Error::new(
                ChurStatus::AuthenticationFailed,
                "bootstrap token is not accepted",
            )
        })?;
    ensure!(
        chur_crypto::secret::constant_time_eq(&supplied, &state.bootstrap_token),
        AuthenticationFailed,
        "bootstrap token is not accepted"
    );
    Ok(())
}

fn path_matches(matches: bool, context: &'static str) -> chur_core::Result<()> {
    if matches {
        Ok(())
    } else {
        Err(Error::new(ChurStatus::InvalidInput, context))
    }
}

fn lock(state: &AppState) -> chur_core::Result<std::sync::MutexGuard<'_, ReferenceServer>> {
    state
        .server
        .lock()
        .map_err(|_| Error::new(ChurStatus::InternalFailure, "server state lock failed"))
}

fn token_pair(body: &[u8]) -> chur_core::Result<([u8; 32], &[u8], &[u8])> {
    ensure!(
        body.len() > 36,
        InvalidInput,
        "token and paired records are incomplete"
    );
    let token = body[..32]
        .try_into()
        .map_err(|_| Error::new(ChurStatus::InvalidInput, "transport token is not 32 bytes"))?;
    let (first, second) = pair(&body[32..])?;
    Ok((token, first, second))
}

fn pair(body: &[u8]) -> chur_core::Result<(&[u8], &[u8])> {
    let length = body
        .get(..4)
        .and_then(|bytes| bytes.try_into().ok())
        .map(u32::from_be_bytes)
        .and_then(|value| usize::try_from(value).ok())
        .ok_or_else(|| Error::new(ChurStatus::InvalidInput, "record pair has no length"))?;
    let end = 4usize
        .checked_add(length)
        .filter(|end| *end < body.len())
        .ok_or_else(|| Error::new(ChurStatus::InvalidInput, "record pair length is invalid"))?;
    Ok((&body[4..end], &body[end..]))
}

fn query_u64(query: Option<&str>, name: &str) -> chur_core::Result<u64> {
    query_value(query, name)?
        .and_then(|value| value.parse().ok())
        .ok_or_else(|| Error::new(ChurStatus::InvalidInput, "numeric query is invalid"))
}

fn query_hex(query: Option<&str>, name: &str) -> chur_core::Result<[u8; 32]> {
    query_value(query, name)?
        .ok_or_else(|| Error::new(ChurStatus::InvalidInput, "hex query is absent"))
        .and_then(hex)
}

fn query_value<'a>(query: Option<&'a str>, name: &str) -> chur_core::Result<Option<&'a str>> {
    let mut values = query
        .into_iter()
        .flat_map(|query| query.split('&'))
        .filter_map(|part| {
            part.split_once('=')
                .filter(|(key, _)| *key == name)
                .map(|(_, value)| value)
        });
    let value = values.next();
    ensure!(
        values.next().is_none(),
        InvalidInput,
        "query parameter is repeated"
    );
    Ok(value)
}

fn id(value: &str) -> chur_core::Result<Id> {
    Id::new(hex(value)?)
}

fn hex<const N: usize>(value: &str) -> chur_core::Result<[u8; N]> {
    ensure!(
        value.len() == N * 2,
        InvalidInput,
        "hex value has the wrong length"
    );
    let mut bytes = [0; N];
    for (index, pair) in value.as_bytes().chunks_exact(2).enumerate() {
        bytes[index] = (nibble(pair[0])? << 4) | nibble(pair[1])?;
    }
    Ok(bytes)
}

fn nibble(byte: u8) -> chur_core::Result<u8> {
    match byte {
        b'0'..=b'9' => Ok(byte - b'0'),
        b'a'..=b'f' => Ok(byte - b'a' + 10),
        b'A'..=b'F' => Ok(byte - b'A' + 10),
        _ => Err(Error::new(
            ChurStatus::InvalidInput,
            "hex value has a non-hex byte",
        )),
    }
}

fn relay_status(outcome: RelayOutcome) -> StatusCode {
    match outcome {
        RelayOutcome::Stored => StatusCode::CREATED,
        RelayOutcome::Duplicate => StatusCode::OK,
    }
}

fn records(records: Vec<Vec<u8>>) -> Vec<u8> {
    let mut body = Vec::new();
    body.extend_from_slice(&(records.len() as u32).to_be_bytes());
    for record in records {
        body.extend_from_slice(&(record.len() as u32).to_be_bytes());
        body.extend_from_slice(&record);
    }
    body
}

fn binary(body: Vec<u8>) -> HttpResult<Response> {
    Ok(([(header::CONTENT_TYPE, "application/octet-stream")], body).into_response())
}

fn progress_response(status: StatusCode, progress: UploadProgress) -> HttpResult<Response> {
    let mut body = Vec::with_capacity(17);
    body.extend_from_slice(&progress.received.to_be_bytes());
    body.extend_from_slice(&progress.expected.to_be_bytes());
    body.push(u8::from(progress.complete));
    Ok((
        status,
        [(header::CONTENT_TYPE, "application/octet-stream")],
        body,
    )
        .into_response())
}

type HttpResult<T> = Result<T, HttpError>;

struct HttpError(Error);

impl From<Error> for HttpError {
    fn from(error: Error) -> Self {
        Self(error)
    }
}

impl IntoResponse for HttpError {
    fn into_response(self) -> Response {
        let status = match self.0.status() {
            ChurStatus::AuthenticationFailed => StatusCode::UNAUTHORIZED,
            ChurStatus::NotFound => StatusCode::NOT_FOUND,
            ChurStatus::Conflict | ChurStatus::SyncChainFork | ChurStatus::SyncHeadRollback => {
                StatusCode::CONFLICT
            }
            ChurStatus::ResourceLimitExceeded => StatusCode::PAYLOAD_TOO_LARGE,
            ChurStatus::InvalidInput
            | ChurStatus::UnsupportedVersion
            | ChurStatus::UnsupportedSuite
            | ChurStatus::NonCanonicalEncoding => StatusCode::BAD_REQUEST,
            ChurStatus::IoFailure
            | ChurStatus::StorageUnavailable
            | ChurStatus::NetworkFailure
            | ChurStatus::InternalFailure => StatusCode::SERVICE_UNAVAILABLE,
            _ => StatusCode::UNPROCESSABLE_ENTITY,
        };
        (
            status,
            [(header::CONTENT_TYPE, "application/octet-stream")],
            self.0.status().as_i32().to_be_bytes(),
        )
            .into_response()
    }
}
