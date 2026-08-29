//! Device verification fingerprints from `docs/sync/DEVICE_IDENTITY.md` §5.

use chur_core::Id;
use chur_crypto::{commit, tuple::tag};

const HEX: &[u8; 16] = b"0123456789abcdef";

/// Computes the full device verification digest.
#[must_use]
pub fn fingerprint_digest(
    vault_id: &Id,
    device_id: &Id,
    signing_public_key: &[u8; 32],
    hpke_public_key: &[u8; 32],
) -> [u8; 32] {
    commit::commit(
        tag::IDENTITY_FINGERPRINT,
        &[
            vault_id.as_bytes(),
            device_id.as_bytes(),
            signing_public_key,
            hpke_public_key,
        ],
    )
}

/// Formats the portable 160-bit verification string.
#[must_use]
pub fn fingerprint(
    vault_id: &Id,
    device_id: &Id,
    signing_public_key: &[u8; 32],
    hpke_public_key: &[u8; 32],
) -> String {
    let digest = fingerprint_digest(vault_id, device_id, signing_public_key, hpke_public_key);
    let mut display = String::with_capacity(49);
    for (index, pair) in digest[..20].chunks_exact(2).enumerate() {
        if index != 0 {
            display.push(' ');
        }
        for byte in pair {
            display.push(char::from(HEX[usize::from(byte >> 4)]));
            display.push(char::from(HEX[usize::from(byte & 0x0f)]));
        }
    }
    display
}

/// The binary enrollment QR identity payload, before the checkpoint commitment.
#[must_use]
pub fn qr_identity_payload(
    vault_id: &Id,
    device_id: &Id,
    signing_public_key: &[u8; 32],
    hpke_public_key: &[u8; 32],
) -> [u8; 96] {
    let mut payload = [0; 96];
    payload[..16].copy_from_slice(vault_id.as_bytes());
    payload[16..32].copy_from_slice(device_id.as_bytes());
    payload[32..64].copy_from_slice(signing_public_key);
    payload[64..].copy_from_slice(hpke_public_key);
    payload
}

#[cfg(test)]
mod tests {
    #![allow(clippy::expect_used, clippy::panic)]

    use super::*;

    #[test]
    fn fingerprint_and_qr_use_the_same_identity_bytes() {
        let vault_id = Id::new([1; 16]).expect("vault");
        let device_id = Id::new([2; 16]).expect("device");
        let signing = [3; 32];
        let hpke = [4; 32];
        let qr = qr_identity_payload(&vault_id, &device_id, &signing, &hpke);

        assert_eq!(&qr[..16], vault_id.as_bytes());
        assert_eq!(&qr[16..32], device_id.as_bytes());
        assert_eq!(&qr[32..64], &signing);
        assert_eq!(&qr[64..], &hpke);
        let display = fingerprint(&vault_id, &device_id, &signing, &hpke);
        assert_eq!(display.len(), 49);
        assert_eq!(display.split(' ').count(), 10);
        assert_eq!(
            fingerprint_digest(&vault_id, &device_id, &signing, &hpke),
            commit::commit(tag::IDENTITY_FINGERPRINT, &[&qr])
        );
    }
}
