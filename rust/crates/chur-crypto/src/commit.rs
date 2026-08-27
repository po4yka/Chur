//! BLAKE3-256 commitments.
//!
//! Suite `0x0001` uses BLAKE3-256 for every commitment. Two constructions
//! appear in v1 and this module offers exactly those two:
//!
//! - an unkeyed hash over a domain tag followed by declared record bytes, used
//!   by the manifest commitment of `docs/format/OBJECT_CONTAINER_V1.md` §5 and
//!   the ordered chunk commitment of §10;
//! - a keyed hash over a domain tag followed by a record body, used by the
//!   descriptor authenticator of `docs/format/VAULT_DESCRIPTOR_V1.md` §8.
//!
//! Neither input is a canonical tuple. `docs/format/CANONICAL_ENCODING_V1.md`
//! §7.1 states that a hash input a byte-exact specification defines directly is
//! a domain tag followed by record bytes, and that the tuple rules do not apply
//! to it.

use chur_core::limits::COMMITMENT_LEN;

use crate::secret::{Key, constant_time_eq};

/// A 32-byte commitment.
pub type Commitment = [u8; COMMITMENT_LEN];

/// Hashes a domain tag followed by the given byte runs, in order.
#[must_use]
pub fn commit(tag: &[u8], parts: &[&[u8]]) -> Commitment {
    let mut hasher = Committer::new(tag);
    for part in parts {
        hasher.update(part);
    }
    hasher.finish()
}

/// Hashes a domain tag and a body under a key.
///
/// The key is a derived authentication key, never a parent secret.
#[must_use]
pub fn keyed_commit(key: &Key, tag: &[u8], body: &[u8]) -> Commitment {
    let mut hasher = blake3::Hasher::new_keyed(key.expose());
    hasher.update(tag);
    hasher.update(body);
    *hasher.finalize().as_bytes()
}

/// Verifies a keyed commitment in constant time.
///
/// The comparison never returns early, so its duration does not reveal how many
/// leading bytes of a forged tag were correct.
#[must_use]
pub fn verify_keyed(key: &Key, tag: &[u8], body: &[u8], expected: &[u8]) -> bool {
    constant_time_eq(&keyed_commit(key, tag, body), expected)
}

/// An incremental commitment over a domain tag and a stream of record bytes.
///
/// The ordered chunk commitment of `OBJECT_CONTAINER_V1.md` §10 is computed
/// while an import writes, so it must not require the whole container in
/// memory. For a zero-chunk object the result is the hash of the tag alone,
/// which is what a [`Committer`] with no update produces.
pub struct Committer {
    hasher: blake3::Hasher,
}

impl Committer {
    /// Starts a commitment with its domain tag.
    #[must_use]
    pub fn new(tag: &[u8]) -> Self {
        let mut hasher = blake3::Hasher::new();
        hasher.update(tag);
        Self { hasher }
    }

    /// Feeds the exact wire bytes of one record.
    pub fn update(&mut self, bytes: &[u8]) {
        self.hasher.update(bytes);
    }

    /// Finishes the commitment.
    #[must_use]
    pub fn finish(&self) -> Commitment {
        *self.hasher.finalize().as_bytes()
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::panic, clippy::expect_used)]

    use super::*;
    use crate::tuple::tag;

    #[test]
    fn a_commitment_is_thirty_two_bytes() {
        assert_eq!(
            commit(tag::OBJECT_MANIFEST_COMMITMENT, &[b"body"]).len(),
            32
        );
    }

    #[test]
    fn the_domain_tag_separates_two_identical_bodies() {
        let first = commit(tag::OBJECT_MANIFEST_COMMITMENT, &[b"same"]);
        let second = commit(tag::OBJECT_ORDERED_COMMITMENT, &[b"same"]);
        assert_ne!(first, second);
    }

    #[test]
    fn streaming_and_one_shot_agree() {
        let mut streamed = Committer::new(tag::OBJECT_ORDERED_COMMITMENT);
        streamed.update(b"record zero");
        streamed.update(b"record one");
        let one_shot = commit(
            tag::OBJECT_ORDERED_COMMITMENT,
            &[b"record zero", b"record one"],
        );
        assert_eq!(streamed.finish(), one_shot);
    }

    #[test]
    fn a_zero_chunk_object_commits_to_the_tag_alone() {
        let empty = Committer::new(tag::OBJECT_ORDERED_COMMITMENT).finish();
        assert_eq!(empty, commit(tag::OBJECT_ORDERED_COMMITMENT, &[]));
        assert_eq!(
            empty,
            *blake3::hash(tag::OBJECT_ORDERED_COMMITMENT).as_bytes()
        );
    }

    #[test]
    fn a_keyed_commitment_depends_on_the_key() {
        let body = b"descriptor body";
        let first = keyed_commit(&Key::new([1; 32]), tag::VAULT_DESCRIPTOR_AUTH, body);
        let second = keyed_commit(&Key::new([2; 32]), tag::VAULT_DESCRIPTOR_AUTH, body);
        assert_ne!(first, second);
        assert!(verify_keyed(
            &Key::new([1; 32]),
            tag::VAULT_DESCRIPTOR_AUTH,
            body,
            &first
        ));
        assert!(!verify_keyed(
            &Key::new([1; 32]),
            tag::VAULT_DESCRIPTOR_AUTH,
            body,
            &second
        ));
    }

    #[test]
    fn verification_rejects_a_tag_of_the_wrong_length() {
        let key = Key::new([3; 32]);
        let commitment = keyed_commit(&key, tag::VAULT_DESCRIPTOR_AUTH, b"body");
        assert!(!verify_keyed(
            &key,
            tag::VAULT_DESCRIPTOR_AUTH,
            b"body",
            &commitment[..31]
        ));
    }
}
