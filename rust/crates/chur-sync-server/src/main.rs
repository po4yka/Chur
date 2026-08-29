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
    let bootstrap_token = token(&std::env::var("CHUR_SYNC_BOOTSTRAP_TOKEN")?)?;
    let server = ReferenceServer::open(data, max_object, max_account)?;
    let listener = tokio::net::TcpListener::bind(bind).await?;
    axum::serve(
        listener,
        chur_sync_server::http::router(server, bootstrap_token),
    )
    .await?;
    Ok(())
}

fn value(name: &str, default: &str) -> String {
    std::env::var(name).unwrap_or_else(|_| default.to_owned())
}

fn number(name: &str, default: u64) -> Result<u64, Box<dyn Error>> {
    Ok(std::env::var(name).map_or_else(|_| Ok(default), |value| value.parse())?)
}

fn token(value: &str) -> Result<[u8; 32], Box<dyn Error>> {
    if value.len() != 64 {
        return Err("CHUR_SYNC_BOOTSTRAP_TOKEN must be 64 hex characters".into());
    }
    let mut token = [0; 32];
    for (index, pair) in value.as_bytes().chunks_exact(2).enumerate() {
        token[index] = (nibble(pair[0])? << 4) | nibble(pair[1])?;
    }
    Ok(token)
}

fn nibble(byte: u8) -> Result<u8, Box<dyn Error>> {
    match byte {
        b'0'..=b'9' => Ok(byte - b'0'),
        b'a'..=b'f' => Ok(byte - b'a' + 10),
        b'A'..=b'F' => Ok(byte - b'A' + 10),
        _ => Err("CHUR_SYNC_BOOTSTRAP_TOKEN must contain only hex".into()),
    }
}
