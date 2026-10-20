//! Direction-separated HKDF keys for ephemeral direct peer sessions.
use crate::Key;
use chur_core::{ChurStatus, Error, Result, ensure};
use hkdf::Hkdf;
use sha2::{Digest, Sha256};

/// Binds a high-entropy one-use invitation secret to both fresh handshake challenges.
pub fn session_key(secret: &Key, initiator: &[u8], responder: &[u8], direction: u8) -> Result<Key> {
    ensure!(
        initiator.len() == 40 && responder.len() == 40 && direction <= 1,
        InvalidInput,
        "peer key context is invalid"
    );
    let mut transcript = Sha256::new();
    transcript.update(b"CHUR\0P2P\0TRANSCRIPT\0V1");
    transcript.update(initiator);
    transcript.update(responder);
    let salt = transcript.finalize();
    let kdf = Hkdf::<Sha256>::new(Some(&salt), secret.expose());
    let mut key = Key::zeroed();
    let mut info = b"CHUR\0P2P\0DIRECTION\0V1".to_vec();
    info.push(direction);
    kdf.expand(&info, key.expose_mut())
        .map_err(|_| Error::new(ChurStatus::InternalFailure, "peer key derivation failed"))?;
    Ok(key)
}
