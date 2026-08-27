//! Fixed-size secret buffers.
//!
//! `docs/CRYPTOGRAPHY.md` §11 requires secret types to use fixed-size buffers,
//! to be zeroized when they leave scope, to stay out of general serialization,
//! and to have no default `Debug`. `docs/security/SECURITY_INVARIANTS.md`
//! SEC-010 turns the last requirement into a compile-fail test: formatting a
//! secret-bearing type with `{:?}` must not compile.
//!
//! [`Secret`] therefore implements no `Debug`, no `Display`, no `Clone` that
//! could be taken implicitly, and no comparison other than the constant-time
//! one. A struct that holds a [`Secret`] cannot derive `Debug`, which is the
//! invariant SEC-010 asks for.

use subtle::{Choice, ConstantTimeEq};
use zeroize::Zeroize;

use chur_core::limits::KEY_LEN;

/// A fixed-size secret that is zeroized when it is dropped.
///
/// `N` is a compile-time length, so a secret never carries a capacity an
/// attacker chose and never reallocates a buffer that would leave a copy behind.
///
/// SEC-010 requires that formatting a secret-bearing type with `{:?}` does not
/// compile. This is that test:
///
/// ```compile_fail
/// let key = chur_crypto::Key::new([0u8; 32]);
/// println!("{key:?}");
/// ```
///
/// A type that holds a [`Secret`] inherits the property, because it cannot
/// derive `Debug` either:
///
/// ```compile_fail
/// #[derive(Debug)]
/// struct Session {
///     root: chur_crypto::Key,
/// }
/// ```
///
/// Exposing the bytes deliberately still works, which is what the vector
/// tooling does:
///
/// ```
/// let key = chur_crypto::Key::new([0xab; 32]);
/// assert_eq!(key.expose()[0], 0xab);
/// ```
pub struct Secret<const N: usize>([u8; N]);

impl<const N: usize> Zeroize for Secret<N> {
    fn zeroize(&mut self) {
        self.0.zeroize();
    }
}

impl<const N: usize> Drop for Secret<N> {
    /// Overwrites the buffer before it is released.
    ///
    /// It is written by hand rather than derived so the crate needs no
    /// procedural-macro dependency for one three-line implementation.
    fn drop(&mut self) {
        self.zeroize();
    }
}

impl<const N: usize> Secret<N> {
    /// Takes ownership of secret bytes.
    ///
    /// The caller's array is moved, not borrowed, so no second live copy
    /// remains for this type to be responsible for.
    #[must_use]
    pub const fn new(bytes: [u8; N]) -> Self {
        Self(bytes)
    }

    /// A secret of `N` zero bytes.
    ///
    /// It is a placeholder for a buffer about to be filled, never a key. A
    /// derivation that would return this value is a defect.
    #[must_use]
    pub const fn zeroed() -> Self {
        Self([0u8; N])
    }

    /// Borrows the bytes.
    ///
    /// The name says what the call does. Every use is a place a reviewer looks
    /// for a copy that outlives the secret.
    #[must_use]
    pub const fn expose(&self) -> &[u8; N] {
        &self.0
    }

    /// Borrows the bytes for writing, so a primitive can fill them in place.
    #[must_use]
    pub const fn expose_mut(&mut self) -> &mut [u8; N] {
        &mut self.0
    }

    /// The buffer length.
    #[must_use]
    pub const fn len(&self) -> usize {
        N
    }

    /// Whether the buffer is empty, which only a zero-length secret is.
    #[must_use]
    pub const fn is_empty(&self) -> bool {
        N == 0
    }

    /// An independent copy.
    ///
    /// It is a named method rather than a `Clone` implementation so a duplicate
    /// of a secret is always visible at the call site.
    #[must_use]
    pub fn duplicate(&self) -> Self {
        Self(self.0)
    }
}

impl<const N: usize> ConstantTimeEq for Secret<N> {
    fn ct_eq(&self, other: &Self) -> Choice {
        self.0.ct_eq(&other.0)
    }
}

impl<const N: usize> PartialEq for Secret<N> {
    /// Compares in constant time over the whole buffer.
    ///
    /// `docs/CRYPTOGRAPHY.md` §57 forbids a comparison whose duration depends
    /// on where two secrets first differ, so this never returns early.
    fn eq(&self, other: &Self) -> bool {
        self.ct_eq(other).into()
    }
}

impl<const N: usize> Eq for Secret<N> {}

/// A 32-byte symmetric key: the size of every key in the v1 hierarchy.
pub type Key = Secret<KEY_LEN>;

/// Compares two byte slices in constant time.
///
/// Returns `false` for slices of different lengths without inspecting their
/// contents, because the lengths are public.
#[must_use]
pub fn constant_time_eq(left: &[u8], right: &[u8]) -> bool {
    if left.len() != right.len() {
        return false;
    }
    left.ct_eq(right).into()
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::panic, clippy::expect_used)]

    use super::*;

    #[test]
    fn a_secret_exposes_the_bytes_it_was_given() {
        let secret = Key::new([7u8; KEY_LEN]);
        assert_eq!(secret.expose(), &[7u8; KEY_LEN]);
        assert_eq!(secret.len(), 32);
        assert!(!secret.is_empty());
    }

    #[test]
    fn equality_is_by_value_and_a_duplicate_matches() {
        let secret = Key::new([3u8; KEY_LEN]);
        assert!(secret == secret.duplicate());
        assert!(secret != Key::new([4u8; KEY_LEN]));
    }

    #[test]
    fn a_zeroed_secret_is_all_zero() {
        assert_eq!(Key::zeroed().expose(), &[0u8; KEY_LEN]);
    }

    #[test]
    fn writing_in_place_reaches_the_buffer() {
        let mut secret = Key::zeroed();
        secret.expose_mut()[0] = 0xff;
        assert_eq!(secret.expose()[0], 0xff);
    }

    #[test]
    fn constant_time_eq_rejects_a_length_mismatch() {
        assert!(constant_time_eq(b"abc", b"abc"));
        assert!(!constant_time_eq(b"abc", b"abd"));
        assert!(!constant_time_eq(b"abc", b"abcd"));
    }
}
