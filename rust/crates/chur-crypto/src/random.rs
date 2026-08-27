//! Random bytes from the operating-system CSPRNG.
//!
//! `docs/CRYPTOGRAPHY.md` §9 requires every production random value to come
//! from the OS CSPRNG through Rust, forbids a fallback PRNG, and requires an
//! RNG failure to abort the operation. There is no seedable variant here and no
//! feature that swaps one in: a deterministic generator exists only in the
//! vector tooling, which builds its bytes explicitly rather than replacing this
//! module.

use chur_core::status::ChurStatus;
use chur_core::{Error, Id, Result};

use crate::secret::Secret;

/// Fills a buffer from the OS CSPRNG.
///
/// # Errors
///
/// Returns [`ChurStatus::InternalFailure`] when the OS CSPRNG fails. There is
/// no fallback: the caller aborts the operation rather than proceeding with
/// weaker material.
pub fn fill(buffer: &mut [u8]) -> Result<()> {
    getrandom::fill(buffer).map_err(|_| {
        Error::new(
            ChurStatus::InternalFailure,
            "the operating-system CSPRNG failed and there is no fallback",
        )
    })
}

/// Generates a fresh secret of `N` random bytes.
///
/// # Errors
///
/// Returns [`ChurStatus::InternalFailure`] when the OS CSPRNG fails.
pub fn secret<const N: usize>() -> Result<Secret<N>> {
    let mut value = Secret::<N>::zeroed();
    fill(value.expose_mut())?;
    Ok(value)
}

/// Generates a fresh array of `N` random bytes.
///
/// Use it for public random values: nonces, salts, prefixes, and opaque
/// identifiers. Key material uses [`secret`], which zeroizes.
///
/// # Errors
///
/// Returns [`ChurStatus::InternalFailure`] when the OS CSPRNG fails.
pub fn array<const N: usize>() -> Result<[u8; N]> {
    let mut value = [0u8; N];
    fill(&mut value)?;
    Ok(value)
}

/// Generates a fresh opaque identifier.
///
/// The all-zero value is reserved as invalid by
/// `docs/format/CANONICAL_ENCODING_V1.md` §8. Drawing it has probability
/// 2^-128, and this function draws again rather than returning it, so the
/// reserved value never reaches a record.
///
/// # Errors
///
/// Returns [`ChurStatus::InternalFailure`] when the OS CSPRNG fails.
pub fn id() -> Result<Id> {
    loop {
        let bytes = array::<{ chur_core::limits::ID_LEN }>()?;
        if let Ok(value) = Id::new(bytes) {
            return Ok(value);
        }
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::panic, clippy::expect_used)]

    use super::*;

    #[test]
    fn two_draws_differ() {
        let first = array::<32>().unwrap();
        let second = array::<32>().unwrap();
        assert_ne!(first, second);
    }

    #[test]
    fn a_generated_identifier_is_never_the_reserved_value() {
        for _ in 0..64 {
            assert_ne!(id().unwrap().as_bytes(), &[0u8; 16]);
        }
    }

    #[test]
    fn a_generated_secret_is_not_all_zero() {
        assert!(secret::<32>().unwrap() != Secret::<32>::zeroed());
    }
}
