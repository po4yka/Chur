//! HTTP reference-server integration tests.

#![allow(clippy::expect_used)]

use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};

use axum::body::Body;
use axum::http::{Request, StatusCode};
use chur_sync_server::ReferenceServer;
use tower::ServiceExt;

static NEXT: AtomicU64 = AtomicU64::new(0);

#[tokio::test]
async fn health_checks_the_open_server() {
    let response = chur_sync_server::http::router(server())
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
