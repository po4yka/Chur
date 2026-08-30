//! The fixed collection grant of `docs/sync/COLLECTION_GRANTS.md`.

use chur_core::Id;
use chur_crypto::{commit::commit, tuple::tag};

const KEY_ID_LEN: usize = 16;
const HPKE_PROFILE_V1: u16 = 1;

/// Derives the v1 identifier of one Ed25519 public key.
#[must_use]
pub fn signing_key_id(
    identity_vault_id: &Id,
    device_id: &Id,
    public_key: &[u8; 32],
) -> [u8; KEY_ID_LEN] {
    key_id(
        tag::IDENTITY_SIGNING_KEY_ID,
        identity_vault_id,
        device_id,
        public_key,
    )
}

/// Derives the v1 identifier of one X25519 HPKE public key.
#[must_use]
pub fn hpke_key_id(
    identity_vault_id: &Id,
    device_id: &Id,
    public_key: &[u8; 32],
) -> [u8; KEY_ID_LEN] {
    key_id(
        tag::IDENTITY_HPKE_KEY_ID,
        identity_vault_id,
        device_id,
        public_key,
    )
}

fn key_id(
    domain: &[u8],
    identity_vault_id: &Id,
    device_id: &Id,
    public_key: &[u8; 32],
) -> [u8; KEY_ID_LEN] {
    let commitment = commit(
        domain,
        &[
            identity_vault_id.as_bytes(),
            device_id.as_bytes(),
            &HPKE_PROFILE_V1.to_be_bytes(),
            public_key,
        ],
    );
    let mut id = [0; KEY_ID_LEN];
    id.copy_from_slice(&commitment[..KEY_ID_LEN]);
    id
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::panic, clippy::expect_used)]

    use super::*;

    #[test]
    fn key_purpose_separates_identical_public_bytes() {
        let vault_id = Id::new([1; 16]).expect("vault");
        let device_id = Id::new([2; 16]).expect("device");
        let public_key = [3; 32];

        assert_ne!(
            signing_key_id(&vault_id, &device_id, &public_key),
            hpke_key_id(&vault_id, &device_id, &public_key)
        );
    }
}
