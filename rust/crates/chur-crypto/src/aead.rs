//! XChaCha20-Poly1305, the v1 AEAD of suite `0x0001`.
//!
//! `docs/CRYPTOGRAPHY.md` §36 requires tag verification to complete before
//! plaintext is released, a decryption failure to return a stable error, and a
//! failed record never to be retried under another key or suite. The API here
//! offers no way to obtain unverified plaintext: `open` either returns the whole
//! authenticated plaintext or an error.

use chacha20poly1305::XChaCha20Poly1305;
use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use zeroize::Zeroizing;

use chur_core::limits::{NONCE_LEN, TAG_LEN};
use chur_core::status::ChurStatus;
use chur_core::{Error, Result};

use crate::secret::Key;

/// A 24-byte XChaCha20-Poly1305 nonce.
///
/// The type is a public value, not a secret: a nonce is written in the clear in
/// every record that carries one.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Nonce([u8; NONCE_LEN]);

impl Nonce {
    /// Wraps 24 bytes.
    #[must_use]
    pub const fn new(bytes: [u8; NONCE_LEN]) -> Self {
        Self(bytes)
    }

    /// Builds the chunk nonce of one index: a 16-byte prefix then the index.
    ///
    /// `docs/format/OBJECT_CONTAINER_V1.md` §7 fixes the construction as
    /// `prefix || chunk_index_u64_be`. The prefix is fresh per stream revision,
    /// and the index never repeats under one prefix, which is what keeps the
    /// nonce unique without a random draw per chunk.
    #[must_use]
    pub const fn chunk(prefix: &[u8; 16], chunk_index: u64) -> Self {
        let index = chunk_index.to_be_bytes();
        let mut bytes = [0u8; NONCE_LEN];
        let mut position = 0;
        while position < 16 {
            bytes[position] = prefix[position];
            position += 1;
        }
        while position < NONCE_LEN {
            bytes[position] = index[position - 16];
            position += 1;
        }
        Self(bytes)
    }

    /// Draws a fresh nonce from the OS CSPRNG.
    ///
    /// It is the only construction for a record whose nonce is not derived from
    /// a prefix and an index: a slot, an envelope, a manifest, or a final
    /// commit. `docs/CRYPTOGRAPHY.md` §16 makes a random 192-bit nonce safe at
    /// these volumes, which is why XChaCha20 rather than ChaCha20 is the suite.
    ///
    /// # Errors
    ///
    /// Returns the error of the OS random source.
    pub fn random() -> Result<Self> {
        Ok(Self(crate::random::array::<NONCE_LEN>()?))
    }

    /// Reads a nonce from a slice.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::InvalidInput`] when the slice is not 24 bytes.
    pub fn from_slice(bytes: &[u8]) -> Result<Self> {
        let array: [u8; NONCE_LEN] = bytes
            .try_into()
            .map_err(|_| Error::new(ChurStatus::InvalidInput, "nonce is not 24 bytes"))?;
        Ok(Self(array))
    }

    /// The exact bytes, as they appear in a record.
    #[must_use]
    pub const fn as_bytes(&self) -> &[u8; NONCE_LEN] {
        &self.0
    }
}

/// Seals a plaintext, returning ciphertext followed by its 16-byte tag.
///
/// # Errors
///
/// Returns [`ChurStatus::InternalFailure`] when the cipher rejects the input,
/// which for a valid key and nonce means an allocation failure.
pub fn seal(key: &Key, nonce: &Nonce, plaintext: &[u8], aad: &[u8]) -> Result<Vec<u8>> {
    let cipher = XChaCha20Poly1305::new(key.expose().into());
    cipher
        .encrypt(
            nonce.as_bytes().into(),
            Payload {
                msg: plaintext,
                aad,
            },
        )
        .map_err(|_| Error::new(ChurStatus::InternalFailure, "AEAD seal failed"))
}

/// Opens a sealed record.
///
/// The returned plaintext is zeroized when it is dropped, so a caller that
/// abandons it does not leave a copy behind.
///
/// # Errors
///
/// Returns [`ChurStatus::ObjectCorrupt`] when the tag does not verify or the
/// input is shorter than one tag. The caller maps that to the code its own
/// record uses; nothing about which check failed reaches the value.
pub fn open(
    key: &Key,
    nonce: &Nonce,
    ciphertext_and_tag: &[u8],
    aad: &[u8],
) -> Result<Zeroizing<Vec<u8>>> {
    if ciphertext_and_tag.len() < TAG_LEN {
        return Err(Error::new(
            ChurStatus::ObjectCorrupt,
            "sealed record is shorter than one authentication tag",
        ));
    }
    let cipher = XChaCha20Poly1305::new(key.expose().into());
    let plaintext = cipher
        .decrypt(
            nonce.as_bytes().into(),
            Payload {
                msg: ciphertext_and_tag,
                aad,
            },
        )
        .map_err(|_| {
            Error::new(
                ChurStatus::ObjectCorrupt,
                "AEAD tag did not verify for the given key, nonce, and AAD",
            )
        })?;
    Ok(Zeroizing::new(plaintext))
}

/// The sealed length of a plaintext under suite `0x0001`.
///
/// # Errors
///
/// Returns [`ChurStatus::ResourceLimitExceeded`] when the sum overflows `usize`.
pub fn sealed_len(plaintext_len: usize) -> Result<usize> {
    plaintext_len.checked_add(TAG_LEN).ok_or_else(|| {
        Error::new(
            ChurStatus::ResourceLimitExceeded,
            "sealed length overflows the address space",
        )
    })
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::panic, clippy::expect_used)]

    use super::*;

    fn key() -> Key {
        Key::new([0x42; 32])
    }

    #[test]
    fn a_sealed_record_opens_under_the_same_inputs() {
        let nonce = Nonce::new([7; NONCE_LEN]);
        let sealed = seal(&key(), &nonce, b"plaintext", b"aad").unwrap();
        assert_eq!(sealed.len(), sealed_len(9).unwrap());
        let opened = open(&key(), &nonce, &sealed, b"aad").unwrap();
        assert_eq!(opened.as_slice(), b"plaintext");
    }

    #[test]
    fn a_changed_aad_fails_to_open() {
        let nonce = Nonce::new([7; NONCE_LEN]);
        let sealed = seal(&key(), &nonce, b"plaintext", b"aad").unwrap();
        let error = open(&key(), &nonce, &sealed, b"aae").unwrap_err();
        assert_eq!(error.status(), ChurStatus::ObjectCorrupt);
    }

    #[test]
    fn a_changed_key_or_nonce_fails_to_open() {
        let nonce = Nonce::new([7; NONCE_LEN]);
        let sealed = seal(&key(), &nonce, b"plaintext", b"aad").unwrap();
        assert!(open(&Key::new([0x43; 32]), &nonce, &sealed, b"aad").is_err());
        assert!(open(&key(), &Nonce::new([8; NONCE_LEN]), &sealed, b"aad").is_err());
    }

    #[test]
    fn every_single_bit_flip_fails_to_open() {
        let nonce = Nonce::new([1; NONCE_LEN]);
        let sealed = seal(&key(), &nonce, b"twenty four bytes here!!", b"").unwrap();
        for index in 0..sealed.len() {
            for bit in 0..8 {
                let mut damaged = sealed.clone();
                damaged[index] ^= 1 << bit;
                assert!(
                    open(&key(), &nonce, &damaged, b"").is_err(),
                    "bit {bit} of byte {index} opened"
                );
            }
        }
    }

    #[test]
    fn a_record_shorter_than_a_tag_is_rejected_without_a_cipher_call() {
        let nonce = Nonce::new([1; NONCE_LEN]);
        let error = open(&key(), &nonce, &[0u8; 15], b"").unwrap_err();
        assert_eq!(error.status(), ChurStatus::ObjectCorrupt);
    }

    #[test]
    fn an_empty_plaintext_seals_to_exactly_one_tag() {
        let nonce = Nonce::new([2; NONCE_LEN]);
        let sealed = seal(&key(), &nonce, b"", b"aad").unwrap();
        assert_eq!(sealed.len(), TAG_LEN);
        assert!(open(&key(), &nonce, &sealed, b"aad").unwrap().is_empty());
    }

    #[test]
    fn the_chunk_nonce_is_the_prefix_then_the_index() {
        let nonce = Nonce::chunk(&[0xab; 16], 0x0102_0304_0506_0708);
        assert_eq!(&nonce.as_bytes()[..16], &[0xab; 16]);
        assert_eq!(&nonce.as_bytes()[16..], &[1, 2, 3, 4, 5, 6, 7, 8]);
    }

    #[test]
    fn two_chunk_indexes_never_share_a_nonce() {
        let prefix = [0x5a; 16];
        assert_ne!(Nonce::chunk(&prefix, 0), Nonce::chunk(&prefix, 1));
        assert_ne!(Nonce::chunk(&prefix, 0), Nonce::chunk(&[0x5b; 16], 0));
    }
}
