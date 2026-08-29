//! HTTP transport for the self-hosted reference server.

use std::sync::{Arc, Mutex};

use axum::Router;
use axum::extract::State;
use axum::http::StatusCode;
use axum::routing::get;

use crate::ReferenceServer;

#[derive(Clone)]
struct AppState {
    server: Arc<Mutex<ReferenceServer>>,
}

/// Builds the reference HTTP service.
pub fn router(server: ReferenceServer) -> Router {
    let state = AppState {
        server: Arc::new(Mutex::new(server)),
    };
    Router::new()
        .route("/healthz", get(health))
        .with_state(state)
}

async fn health(State(state): State<AppState>) -> StatusCode {
    match state.server.lock() {
        Ok(_) => StatusCode::NO_CONTENT,
        Err(_) => StatusCode::SERVICE_UNAVAILABLE,
    }
}
