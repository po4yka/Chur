//! Runnable self-hosted Chur sync service.

use std::error::Error;
use std::net::SocketAddr;
use std::path::PathBuf;

use chur_sync_server::ReferenceServer;

const DEFAULT_BIND: &str = "127.0.0.1:7780";
const DEFAULT_DATA: &str = "chur-sync-data";
const DEFAULT_MAX_OBJECT_BYTES: u64 = 1_099_511_627_776;
const DEFAULT_MAX_ACCOUNT_BYTES: u64 = 2_199_023_255_552;

#[tokio::main]
async fn main() -> Result<(), Box<dyn Error>> {
    let bind: SocketAddr = value("CHUR_SYNC_BIND", DEFAULT_BIND).parse()?;
    let data = PathBuf::from(value("CHUR_SYNC_DATA", DEFAULT_DATA));
    let max_object = number("CHUR_SYNC_MAX_OBJECT_BYTES", DEFAULT_MAX_OBJECT_BYTES)?;
    let max_account = number("CHUR_SYNC_MAX_ACCOUNT_BYTES", DEFAULT_MAX_ACCOUNT_BYTES)?;
    let server = ReferenceServer::open(data, max_object, max_account)?;
    let listener = tokio::net::TcpListener::bind(bind).await?;
    axum::serve(listener, chur_sync_server::http::router(server)).await?;
    Ok(())
}

fn value(name: &str, default: &str) -> String {
    std::env::var(name).unwrap_or_else(|_| default.to_owned())
}

fn number(name: &str, default: u64) -> Result<u64, Box<dyn Error>> {
    Ok(std::env::var(name).map_or_else(|_| Ok(default), |value| value.parse())?)
}
