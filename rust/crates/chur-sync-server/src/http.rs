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
use chur_sync_protocol::membership::{EnrollmentRecord, RevocationRecord};
use chur_sync_protocol::operation::Operation;

use crate::{ReferenceServer, RelayOutcome};

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
            "/v1/vaults/{vault}/checkpoints",
            get(checkpoints).post(checkpoint),
        )
        .route(
            "/v1/vaults/{vault}/checkpoints/{commitment}",
            get(checkpoint_by_commitment),
        )
        .route("/v1/vaults/{vault}/token", post(rotate_token))
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
    query
        .and_then(|query| query.strip_prefix(name))
        .and_then(|query| query.strip_prefix('='))
        .and_then(|value| value.parse().ok())
        .ok_or_else(|| Error::new(ChurStatus::InvalidInput, "numeric query is invalid"))
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
