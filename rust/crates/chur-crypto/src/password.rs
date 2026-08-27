//! Canonical password bytes and the Argon2id password KEK.
//!
//! `docs/security/PASSWORD_PROFILE.md` §3 fixes profile `0x0001`: the exact
//! Unicode scalar sequence the user entered, no normalization, no trimming, no
//! case folding, strict UTF-8, and a bounded encoded length. §4 freezes the
//! Argon2id floor, which is also the v1 default, and `docs/CRYPTOGRAPHY.md`
//! §18.3 fixes the bounds a parser accepts before Argon2 runs.
//!
//! No lower-memory compatibility profile exists. A device that cannot allocate
//! the floor fails with [`ChurStatus::KdfMemoryUnavailable`] and must never
//! retry with reduced parameters, because one password must derive one key on
//! every supported device.

use argon2::{Algorithm, Argon2, ParamsBuilder, Version};
use zeroize::Zeroizing;

use chur_core::limits::password as bounds;
use chur_core::limits::slot;
use chur_core::status::ChurStatus;
use chur_core::{Error, Result};

use crate::secret::Key;

/// The v1 password encoding profile identifier.
pub const PROFILE_ID: u16 = 0x0001;

/// The Argon2id variant byte carried in a password slot body: RFC 9106 Argon2id.
pub const ARGON2_TYPE: u8 = 0x02;

/// The Argon2 version byte carried in a password slot body: version 1.3.
pub const ARGON2_VERSION: u8 = 0x13;

/// Canonical password bytes under profile `0x0001`.
///
/// The input is the raw bytes a platform text field produced. They are
/// validated as strict UTF-8 and bounded; nothing else is applied.
///
/// The result is zeroized when it is dropped.
///
/// # Errors
///
/// Returns [`ChurStatus::InvalidInput`] for invalid UTF-8 or an empty password,
/// and [`ChurStatus::ResourceLimitExceeded`] when the encoded length exceeds
/// the bound of `docs/CRYPTOGRAPHY.md` §17.
pub fn canonical_bytes(input: &[u8]) -> Result<Zeroizing<Vec<u8>>> {
    if input.len() > bounds::ENCODED_MAX {
        return Err(Error::new(
            ChurStatus::ResourceLimitExceeded,
            "encoded password exceeds the v1 maximum",
        ));
    }
    if input.len() < bounds::ENCODED_MIN {
        return Err(Error::new(
            ChurStatus::InvalidInput,
            "password is empty, which v1 rejects for a new vault",
        ));
    }
    core::str::from_utf8(input).map_err(|_| {
        Error::new(
            ChurStatus::InvalidInput,
            "password bytes are not valid UTF-8",
        )
    })?;
    Ok(Zeroizing::new(input.to_vec()))
}

/// Validated Argon2id parameters of one password slot.
///
/// The type cannot be constructed outside its bounds, so a value that reaches
/// [`derive_kek`] has already passed the §18.3 checks and no derivation runs on
/// an attacker-chosen cost.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Argon2Params {
    memory_kib: u32,
    iterations: u32,
    parallelism: u32,
}

impl Argon2Params {
    /// The frozen v1 floor, which is also the default for a new slot.
    #[must_use]
    pub const fn v1_default() -> Self {
        Self {
            memory_kib: bounds::MEMORY_FLOOR_KIB,
            iterations: bounds::ITERATIONS_FLOOR,
            parallelism: bounds::PARALLELISM_MIN,
        }
    }

    /// Validates parameters read from an untrusted slot body.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::ResourceLimitExceeded`] when any value is outside
    /// the bounds of `docs/CRYPTOGRAPHY.md` §18.3. No derivation runs and no
    /// buffer is allocated for a rejected value.
    pub const fn validated(memory_kib: u32, iterations: u32, parallelism: u32) -> Result<Self> {
        if memory_kib < bounds::MEMORY_FLOOR_KIB || memory_kib > bounds::MEMORY_MAX_KIB {
            return Err(Error::new(
                ChurStatus::ResourceLimitExceeded,
                "Argon2id memory cost is outside the v1 bounds",
            ));
        }
        if iterations < bounds::ITERATIONS_FLOOR || iterations > bounds::ITERATIONS_MAX {
            return Err(Error::new(
                ChurStatus::ResourceLimitExceeded,
                "Argon2id iteration count is outside the v1 bounds",
            ));
        }
        if parallelism < bounds::PARALLELISM_MIN || parallelism > bounds::PARALLELISM_MAX {
            return Err(Error::new(
                ChurStatus::ResourceLimitExceeded,
                "Argon2id parallelism is outside the v1 bounds",
            ));
        }
        Ok(Self {
            memory_kib,
            iterations,
            parallelism,
        })
    }

    /// The memory cost in KiB.
    #[must_use]
    pub const fn memory_kib(self) -> u32 {
        self.memory_kib
    }

    /// The iteration count.
    #[must_use]
    pub const fn iterations(self) -> u32 {
        self.iterations
    }

    /// The parallelism.
    #[must_use]
    pub const fn parallelism(self) -> u32 {
        self.parallelism
    }
}

/// Validates a salt length read from an untrusted slot body.
///
/// # Errors
///
/// Returns [`ChurStatus::ResourceLimitExceeded`] when the length is outside the
/// 16 to 32 range of `docs/format/KEY_SLOT_BODIES_V1.md` §8.
pub fn check_salt(salt: &[u8]) -> Result<()> {
    let length = u32::try_from(salt.len()).unwrap_or(u32::MAX);
    if !(slot::SALT_MIN..=slot::SALT_MAX).contains(&length) {
        return Err(Error::new(
            ChurStatus::ResourceLimitExceeded,
            "Argon2id salt length is outside the v1 bounds",
        ));
    }
    Ok(())
}

/// Derives `PasswordKEK` from canonical password bytes and a slot salt.
///
/// # Errors
///
/// Returns [`ChurStatus::ResourceLimitExceeded`] when the salt length is out of
/// bounds, and [`ChurStatus::KdfMemoryUnavailable`] when the device cannot run
/// the approved profile. The second is a device-resource state decided before
/// any credential is judged; the caller must not retry it with reduced
/// parameters.
pub fn derive_kek(password: &[u8], salt: &[u8], params: Argon2Params) -> Result<Key> {
    check_salt(salt)?;
    // `docs/interop/FFI_CONTRACT.md` §8.1: the Argon2id semaphore is 1 for the
    // whole process. One evaluation is the largest allocation the runtime
    // makes, and two at once on a low-memory device is the fastest way to be
    // killed by the platform. The guard is taken here rather than at each call
    // site so no caller can forget it.
    let _permit = semaphore();
    let built = ParamsBuilder::new()
        .m_cost(params.memory_kib)
        .t_cost(params.iterations)
        .p_cost(params.parallelism)
        .output_len(bounds::OUTPUT_LEN)
        .build()
        .map_err(|_| {
            Error::new(
                ChurStatus::ResourceLimitExceeded,
                "Argon2id parameters were rejected by the primitive",
            )
        })?;
    let argon2 = Argon2::new(Algorithm::Argon2id, Version::V0x13, built);
    let mut derived = Key::zeroed();
    argon2
        .hash_password_into(password, salt, derived.expose_mut())
        .map_err(|_| {
            Error::new(
                ChurStatus::KdfMemoryUnavailable,
                "the device could not run the approved Argon2id profile",
            )
        })?;
    Ok(derived)
}

/// The process-wide Argon2id permit.
///
/// A poisoned mutex is recovered rather than propagated: the lock protects no
/// data, only the right to allocate, and refusing every later unlock because an
/// earlier one panicked would turn one contained failure into a locked-out
/// vault.
fn semaphore() -> std::sync::MutexGuard<'static, ()> {
    static SEMAPHORE: std::sync::Mutex<()> = std::sync::Mutex::new(());
    SEMAPHORE
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}

/// Checks that the device can allocate the profile, once, before any candidate.
///
/// `docs/security/PASSWORD_PROFILE.md` §6 requires this decision to be made
/// before the first candidate of `docs/security/KEY_SLOTS.md` §8 runs, so no
/// partial candidate set is attempted, and requires it to be a device-resource
/// state that reveals nothing about which slots exist. It therefore allocates
/// the profile's memory and frees it again, judging no credential.
///
/// # Errors
///
/// Returns [`ChurStatus::KdfMemoryUnavailable`] when the allocation fails. The
/// caller may retry after freeing memory and must never retry with reduced
/// parameters.
pub fn check_memory_available(params: Argon2Params) -> Result<()> {
    let blocks = usize::try_from(params.memory_kib).map_err(|_| {
        Error::new(
            ChurStatus::KdfMemoryUnavailable,
            "the approved Argon2id profile does not fit this device's address space",
        )
    })?;
    let bytes = blocks.checked_mul(1024).ok_or_else(|| {
        Error::new(
            ChurStatus::KdfMemoryUnavailable,
            "the approved Argon2id profile does not fit this device's address space",
        )
    })?;
    let _permit = semaphore();
    let mut probe: Vec<u8> = Vec::new();
    probe.try_reserve_exact(bytes).map_err(|_| {
        Error::new(
            ChurStatus::KdfMemoryUnavailable,
            "the device could not allocate the approved Argon2id profile",
        )
    })?;
    drop(probe);
    Ok(())
}

const _: () = assert!(ARGON2_VERSION == 0x13);

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::panic, clippy::expect_used)]

    use super::*;

    /// A cheap profile for tests. Production never lowers the floor; these
    /// values exist so a unit test does not allocate 64 MiB per case.
    fn cheap() -> Argon2Params {
        Argon2Params {
            memory_kib: 8,
            iterations: 1,
            parallelism: 1,
        }
    }

    #[test]
    fn the_default_profile_is_the_frozen_floor() {
        let params = Argon2Params::v1_default();
        assert_eq!(params.memory_kib(), 65_536);
        assert_eq!(params.iterations(), 3);
        assert_eq!(params.parallelism(), 1);
    }

    #[test]
    fn parameters_below_the_floor_are_refused() {
        for (memory, iterations, parallelism) in [
            (65_535_u32, 3_u32, 1_u32),
            (524_289, 3, 1),
            (65_536, 2, 1),
            (65_536, 11, 1),
            (65_536, 3, 0),
            (65_536, 3, 5),
        ] {
            let error = Argon2Params::validated(memory, iterations, parallelism).unwrap_err();
            assert_eq!(error.status(), ChurStatus::ResourceLimitExceeded);
        }
    }

    #[test]
    fn parameters_at_every_bound_are_accepted() {
        assert!(Argon2Params::validated(65_536, 3, 1).is_ok());
        assert!(Argon2Params::validated(524_288, 10, 4).is_ok());
    }

    #[test]
    fn no_normalization_is_applied() {
        // U+00E9 and "e" plus U+0301 look alike and are different passwords.
        let composed = canonical_bytes("é".as_bytes()).unwrap();
        let decomposed = canonical_bytes("e\u{0301}".as_bytes()).unwrap();
        assert_ne!(composed.as_slice(), decomposed.as_slice());
        assert_eq!(composed.len(), 2);
        assert_eq!(decomposed.len(), 3);
    }

    #[test]
    fn whitespace_and_case_are_preserved() {
        assert_eq!(
            canonical_bytes(b"  Pass Word  ").unwrap().as_slice(),
            b"  Pass Word  "
        );
    }

    #[test]
    fn length_and_encoding_bounds_are_enforced() {
        assert_eq!(
            canonical_bytes(b"").unwrap_err().status(),
            ChurStatus::InvalidInput
        );
        assert!(canonical_bytes(&[b'a'; 1024]).is_ok());
        assert_eq!(
            canonical_bytes(&[b'a'; 1025]).unwrap_err().status(),
            ChurStatus::ResourceLimitExceeded
        );
        assert_eq!(
            canonical_bytes(&[0xff, 0xfe]).unwrap_err().status(),
            ChurStatus::InvalidInput
        );
    }

    #[test]
    fn a_salt_outside_the_bounds_is_refused() {
        assert!(check_salt(&[0u8; 15]).is_err());
        assert!(check_salt(&[0u8; 16]).is_ok());
        assert!(check_salt(&[0u8; 32]).is_ok());
        assert!(check_salt(&[0u8; 33]).is_err());
    }

    #[test]
    fn the_derivation_is_deterministic_and_salt_bound() {
        let salt = [0x11u8; 16];
        let first = derive_kek(b"password", &salt, cheap()).unwrap();
        let second = derive_kek(b"password", &salt, cheap()).unwrap();
        assert_eq!(first.expose(), second.expose());
        assert_ne!(
            first.expose(),
            derive_kek(b"password", &[0x12u8; 16], cheap())
                .unwrap()
                .expose()
        );
        assert_ne!(
            first.expose(),
            derive_kek(b"passworE", &salt, cheap()).unwrap().expose()
        );
    }

    #[test]
    fn a_one_bit_password_change_derives_a_different_key() {
        let salt = [0x22u8; 16];
        let base = derive_kek(b"correct horse", &salt, cheap()).unwrap();
        let mut changed = b"correct horse".to_vec();
        changed[0] ^= 1;
        assert_ne!(
            base.expose(),
            derive_kek(&changed, &salt, cheap()).unwrap().expose()
        );
    }
}
