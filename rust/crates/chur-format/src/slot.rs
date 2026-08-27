//! The four v1 key-slot bodies.
//!
//! `docs/format/KEY_SLOT_BODIES_V1.md` owns the `slot_body` of every family and
//! its AAD tuple. A slot protects one `VaultRootSecret`; it never encrypts
//! media and carries no private user metadata.
//!
//! Every family AAD repeats the same six binding elements before its
//! family-specific elements, so a slot is bound to one vault, one slot
//! identity, one family, one suite, and one generation whatever its shape.

use chur_core::limits::{GCM_NONCE_LEN, ID_LEN, NONCE_LEN, WRAPPED_KEY_LEN, slot as bounds};
use chur_core::status::ChurStatus;
use chur_core::{Error, Id, Result, ensure};
use chur_crypto::aead::{self, Nonce};
use chur_crypto::kdf::{self, Context, Label};
use chur_crypto::password::{self, ARGON2_TYPE, ARGON2_VERSION, Argon2Params};
use chur_crypto::secret::Key;
use chur_crypto::tuple::{Tuple, tag};

use crate::codec::{Reader, Writer};
use crate::constants::{
    KEYCHAIN_PROFILE_V1, KEYSTORE_PROFILE_V1, PASSWORD_PROFILE_V1, RECOVERY_PROFILE_V1,
    SLOT_VERSION_V1, SUITE_V1, SlotType,
};

/// The `wrap_suite_id` of every family whose AEAD runs in Rust.
pub const WRAP_SUITE_RUST: u16 = SUITE_V1;

/// The `wrap_suite_id` of the Android Keystore family: AES-256-GCM performed by
/// the platform keystore, allocated in `CANONICAL_ENCODING_V1.md` §15.2.
pub const WRAP_SUITE_ANDROID_KEYSTORE: u16 = 0x0002;

fn bad(context: &'static str) -> Error {
    Error::new(ChurStatus::NonCanonicalEncoding, context)
}

/// The six binding elements every family AAD repeats, §1.
///
/// They are the key-slot descriptor header fields of
/// `docs/format/VAULT_DESCRIPTOR_V1.md` §7 plus the `vault_id` of §2.1 there.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SlotBinding {
    /// The vault this slot opens.
    pub vault_id: Id,
    /// The slot identity.
    pub slot_id: Id,
    /// The slot family.
    pub slot_type: SlotType,
    /// The slot format version.
    pub slot_version: u16,
    /// The wrapping suite of this family.
    pub wrap_suite_id: u16,
    /// The generation of this slot.
    pub slot_generation: u64,
}

impl SlotBinding {
    /// Encoded length of the six elements: 45 bytes.
    pub const LEN: usize = ID_LEN + ID_LEN + 1 + 2 + 2 + 8;

    /// A v1 binding with the version and wrap suite its family requires.
    #[must_use]
    pub const fn v1(vault_id: Id, slot_id: Id, slot_type: SlotType, slot_generation: u64) -> Self {
        Self {
            vault_id,
            slot_id,
            slot_type,
            slot_version: SLOT_VERSION_V1,
            wrap_suite_id: match slot_type {
                SlotType::AndroidKeystore => WRAP_SUITE_ANDROID_KEYSTORE,
                _ => WRAP_SUITE_RUST,
            },
            slot_generation,
        }
    }

    /// Checks the version, the family-to-suite pairing, and the generation.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::UnsupportedVersion`] for an unsupported
    /// `slot_version`, [`ChurStatus::UnsupportedSuite`] for a pairing the
    /// descriptor limits reject, and [`ChurStatus::InvalidInput`] for a
    /// generation with no successor.
    pub fn check(&self) -> Result<()> {
        ensure!(
            self.slot_version == SLOT_VERSION_V1,
            UnsupportedVersion,
            "slot version is not supported"
        );
        let expected = match self.slot_type {
            SlotType::AndroidKeystore => WRAP_SUITE_ANDROID_KEYSTORE,
            _ => WRAP_SUITE_RUST,
        };
        ensure!(
            self.wrap_suite_id == expected,
            UnsupportedSuite,
            "slot family and wrap suite are not a permitted pairing"
        );
        ensure!(
            self.slot_generation != u64::MAX,
            InvalidInput,
            "slot generation has no successor"
        );
        Ok(())
    }

    fn open_tuple(&self, domain: &'static [u8]) -> Tuple {
        Tuple::new(domain)
            .id(&self.vault_id)
            .id(&self.slot_id)
            .u8(self.slot_type.value())
            .u16(self.slot_version)
            .u16(self.wrap_suite_id)
            .u64(self.slot_generation)
    }
}

fn check_wrapped(bytes: &[u8]) -> Result<[u8; WRAPPED_KEY_LEN]> {
    bytes
        .try_into()
        .map_err(|_| Error::new(ChurStatus::InternalFailure, "wrapped root is not 48 bytes"))
}

fn unwrap_root(plaintext: &[u8]) -> Result<Key> {
    let bytes: [u8; 32] = plaintext.try_into().map_err(|_| {
        Error::new(
            ChurStatus::AuthenticationFailed,
            "unwrapped root secret is not 32 bytes",
        )
    })?;
    Ok(Key::new(bytes))
}

// ---------------------------------------------------------------------------
// Password slot
// ---------------------------------------------------------------------------

/// `PasswordSlotBodyV1`, §3.
///
/// Every Argon2 parameter is an AAD element, so lowering `memory_kib` in a
/// stored slot changes the AAD and the unwrap fails even if the parser bound
/// were removed.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PasswordSlotBody {
    params: Argon2Params,
    salt: Vec<u8>,
    slot_nonce: Nonce,
    wrapped_root_secret: [u8; WRAPPED_KEY_LEN],
}

impl PasswordSlotBody {
    /// Encoded length for a given salt length.
    #[must_use]
    pub const fn len_for(salt_length: usize) -> usize {
        2 + 1 + 1 + 4 + 4 + 4 + 4 + salt_length + NONCE_LEN + WRAPPED_KEY_LEN
    }

    /// Wraps a root secret under a password.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::ResourceLimitExceeded`] for an out-of-range salt,
    /// [`ChurStatus::KdfMemoryUnavailable`] when the device cannot run the
    /// profile, and the binding errors of [`SlotBinding::check`].
    pub fn seal(
        binding: &SlotBinding,
        password_bytes: &[u8],
        salt: Vec<u8>,
        params: Argon2Params,
        slot_nonce: Nonce,
        root: &Key,
    ) -> Result<Self> {
        binding.check()?;
        password::check_salt(&salt)?;
        let aad = Self::aad(binding, params, &salt)?;
        let kek = password::derive_kek(password_bytes, &salt, params)?;
        let sealed = aead::seal(&kek, &slot_nonce, root.expose(), &aad)?;
        Ok(Self {
            params,
            salt,
            slot_nonce,
            wrapped_root_secret: check_wrapped(&sealed)?,
        })
    }

    /// Unwraps the root secret.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::AuthenticationFailed`] when the password is wrong
    /// or the record is damaged. The two are one external result.
    pub fn open(&self, binding: &SlotBinding, password_bytes: &[u8]) -> Result<Key> {
        binding.check()?;
        let aad = Self::aad(binding, self.params, &self.salt)?;
        let kek = password::derive_kek(password_bytes, &self.salt, self.params)?;
        let plaintext = aead::open(&kek, &self.slot_nonce, &self.wrapped_root_secret, &aad)
            .map_err(|_| {
                Error::new(
                    ChurStatus::AuthenticationFailed,
                    "password slot did not authenticate",
                )
            })?;
        unwrap_root(&plaintext)
    }

    /// The §3 AAD.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::ResourceLimitExceeded`] when the salt exceeds the
    /// `u32` length prefix, which the parser bound already prevents.
    pub fn aad(binding: &SlotBinding, params: Argon2Params, salt: &[u8]) -> Result<Vec<u8>> {
        Ok(binding
            .open_tuple(tag::SLOT_PASSWORD)
            .u16(PASSWORD_PROFILE_V1)
            .u8(ARGON2_TYPE)
            .u8(ARGON2_VERSION)
            .u32(params.memory_kib())
            .u32(params.iterations())
            .u32(params.parallelism())
            .variable(salt)?
            .finish())
    }

    /// Encodes the body.
    #[must_use]
    pub fn encode(&self) -> Vec<u8> {
        let mut writer = Writer::with_capacity(Self::len_for(self.salt.len()));
        writer
            .u16(PASSWORD_PROFILE_V1)
            .u8(ARGON2_TYPE)
            .u8(ARGON2_VERSION)
            .u32(self.params.memory_kib())
            .u32(self.params.iterations())
            .u32(self.params.parallelism())
            .u32(self.salt.len() as u32)
            .fixed(&self.salt)
            .fixed(self.slot_nonce.as_bytes())
            .fixed(&self.wrapped_root_secret);
        debug_assert_eq!(writer.len(), Self::len_for(self.salt.len()));
        writer.finish()
    }

    /// Decodes a body.
    ///
    /// Every bound of §8 is checked before Argon2 could run, so a rejected body
    /// costs no derivation.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::UnsupportedVersion`] for an unknown profile,
    /// [`ChurStatus::ResourceLimitExceeded`] for a parameter outside §18.3, and
    /// [`ChurStatus::NonCanonicalEncoding`] for a wrong length or trailing
    /// bytes.
    pub fn decode(bytes: &[u8]) -> Result<Self> {
        let mut reader = Reader::new(bytes, ChurStatus::NonCanonicalEncoding);
        ensure!(
            reader.u16()? == PASSWORD_PROFILE_V1,
            UnsupportedVersion,
            "password profile is not supported"
        );
        ensure!(
            reader.u8()? == ARGON2_TYPE,
            UnsupportedSuite,
            "Argon2 variant is not Argon2id"
        );
        ensure!(
            reader.u8()? == ARGON2_VERSION,
            UnsupportedVersion,
            "Argon2 version is not 0x13"
        );
        let params = Argon2Params::validated(reader.u32()?, reader.u32()?, reader.u32()?)?;
        let salt = reader.variable(bounds::SALT_MAX)?.to_vec();
        password::check_salt(&salt)?;
        let slot_nonce = Nonce::new(reader.fixed::<NONCE_LEN>()?);
        let wrapped_root_secret = reader.fixed::<WRAPPED_KEY_LEN>()?;
        reader
            .finish()
            .map_err(|_| bad("password slot body carries trailing bytes"))?;
        Ok(Self {
            params,
            salt,
            slot_nonce,
            wrapped_root_secret,
        })
    }

    /// The validated Argon2id parameters.
    #[must_use]
    pub const fn params(&self) -> Argon2Params {
        self.params
    }

    /// The salt.
    #[must_use]
    pub fn salt(&self) -> &[u8] {
        &self.salt
    }
}

// ---------------------------------------------------------------------------
// Recovery slot
// ---------------------------------------------------------------------------

/// `RecoverySlotBodyV1`, §4: exactly 74 bytes.
///
/// The recovery secret is high-entropy, so no password KDF runs and no salt is
/// stored.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RecoverySlotBody {
    slot_nonce: Nonce,
    wrapped_root_secret: [u8; WRAPPED_KEY_LEN],
}

impl RecoverySlotBody {
    /// Exact encoded length.
    pub const LEN: usize = 2 + NONCE_LEN + WRAPPED_KEY_LEN;

    /// Derives `RecoveryKEK`.
    ///
    /// # Errors
    ///
    /// Returns an error only if the derivation itself fails.
    pub fn kek(recovery_secret: &Key, binding: &SlotBinding) -> Result<Key> {
        kdf::derive_from(
            recovery_secret,
            Label::RecoveryRootEnvelope,
            &Context::slot(&binding.vault_id, &binding.slot_id, binding.slot_generation),
        )
    }

    /// Wraps a root secret under a recovery secret.
    ///
    /// # Errors
    ///
    /// As [`SlotBinding::check`], plus an AEAD failure.
    pub fn seal(
        binding: &SlotBinding,
        recovery_secret: &Key,
        slot_nonce: Nonce,
        root: &Key,
    ) -> Result<Self> {
        binding.check()?;
        let sealed = aead::seal(
            &Self::kek(recovery_secret, binding)?,
            &slot_nonce,
            root.expose(),
            &Self::aad(binding),
        )?;
        Ok(Self {
            slot_nonce,
            wrapped_root_secret: check_wrapped(&sealed)?,
        })
    }

    /// Unwraps the root secret.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::AuthenticationFailed`] for a wrong secret or a
    /// damaged record.
    pub fn open(&self, binding: &SlotBinding, recovery_secret: &Key) -> Result<Key> {
        binding.check()?;
        let plaintext = aead::open(
            &Self::kek(recovery_secret, binding)?,
            &self.slot_nonce,
            &self.wrapped_root_secret,
            &Self::aad(binding),
        )
        .map_err(|_| {
            Error::new(
                ChurStatus::AuthenticationFailed,
                "recovery slot did not authenticate",
            )
        })?;
        unwrap_root(&plaintext)
    }

    /// The §4 AAD, exactly 68 bytes.
    #[must_use]
    pub fn aad(binding: &SlotBinding) -> Vec<u8> {
        binding
            .open_tuple(tag::SLOT_RECOVERY)
            .u16(RECOVERY_PROFILE_V1)
            .finish()
    }

    /// Encodes the body.
    #[must_use]
    pub fn encode(&self) -> Vec<u8> {
        let mut writer = Writer::with_capacity(Self::LEN);
        writer
            .u16(RECOVERY_PROFILE_V1)
            .fixed(self.slot_nonce.as_bytes())
            .fixed(&self.wrapped_root_secret);
        debug_assert_eq!(writer.len(), Self::LEN);
        writer.finish()
    }

    /// Decodes a body.
    ///
    /// # Errors
    ///
    /// As [`PasswordSlotBody::decode`], minus the Argon2 checks.
    pub fn decode(bytes: &[u8]) -> Result<Self> {
        ensure!(
            bytes.len() == Self::LEN,
            NonCanonicalEncoding,
            "recovery slot body is not 74 bytes"
        );
        let mut reader = Reader::new(bytes, ChurStatus::NonCanonicalEncoding);
        ensure!(
            reader.u16()? == RECOVERY_PROFILE_V1,
            UnsupportedVersion,
            "recovery profile is not supported"
        );
        let slot_nonce = Nonce::new(reader.fixed::<NONCE_LEN>()?);
        let wrapped_root_secret = reader.fixed::<WRAPPED_KEY_LEN>()?;
        reader.finish()?;
        Ok(Self {
            slot_nonce,
            wrapped_root_secret,
        })
    }
}

// ---------------------------------------------------------------------------
// Apple Keychain slot
// ---------------------------------------------------------------------------

/// `AppleKeychainSlotBodyV1`, §6: exactly 90 bytes.
///
/// The Keychain holds a random `DeviceUnlockSecret` as a `ThisDeviceOnly` item
/// and Rust performs the AEAD, which is what keeps the family test-vectorable
/// at the Rust envelope layer.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AppleKeychainSlotBody {
    keychain_item_id: Id,
    slot_nonce: Nonce,
    wrapped_root_secret: [u8; WRAPPED_KEY_LEN],
}

impl AppleKeychainSlotBody {
    /// Exact encoded length.
    pub const LEN: usize = 2 + ID_LEN + NONCE_LEN + WRAPPED_KEY_LEN;

    /// Derives `AppleDeviceKEK` from the Keychain-held secret.
    ///
    /// # Errors
    ///
    /// Returns an error only if the derivation itself fails.
    pub fn kek(device_unlock_secret: &Key, binding: &SlotBinding) -> Result<Key> {
        kdf::derive_from(
            device_unlock_secret,
            Label::SlotAppleDeviceKek,
            &Context::slot(&binding.vault_id, &binding.slot_id, binding.slot_generation),
        )
    }

    /// Wraps a root secret under a Keychain-held secret.
    ///
    /// # Errors
    ///
    /// As [`RecoverySlotBody::seal`].
    pub fn seal(
        binding: &SlotBinding,
        device_unlock_secret: &Key,
        keychain_item_id: Id,
        slot_nonce: Nonce,
        root: &Key,
    ) -> Result<Self> {
        binding.check()?;
        let sealed = aead::seal(
            &Self::kek(device_unlock_secret, binding)?,
            &slot_nonce,
            root.expose(),
            &Self::aad(binding),
        )?;
        Ok(Self {
            keychain_item_id,
            slot_nonce,
            wrapped_root_secret: check_wrapped(&sealed)?,
        })
    }

    /// Unwraps the root secret.
    ///
    /// # Errors
    ///
    /// As [`RecoverySlotBody::open`].
    pub fn open(&self, binding: &SlotBinding, device_unlock_secret: &Key) -> Result<Key> {
        binding.check()?;
        let plaintext = aead::open(
            &Self::kek(device_unlock_secret, binding)?,
            &self.slot_nonce,
            &self.wrapped_root_secret,
            &Self::aad(binding),
        )
        .map_err(|_| {
            Error::new(
                ChurStatus::AuthenticationFailed,
                "Apple Keychain slot did not authenticate",
            )
        })?;
        unwrap_root(&plaintext)
    }

    /// The §6 AAD, exactly 74 bytes.
    ///
    /// `keychain_item_id` is not an element: it names where the secret is
    /// stored and never selects a construction.
    #[must_use]
    pub fn aad(binding: &SlotBinding) -> Vec<u8> {
        binding
            .open_tuple(tag::SLOT_APPLE_KEYCHAIN)
            .u16(KEYCHAIN_PROFILE_V1)
            .finish()
    }

    /// Encodes the body.
    #[must_use]
    pub fn encode(&self) -> Vec<u8> {
        let mut writer = Writer::with_capacity(Self::LEN);
        writer
            .u16(KEYCHAIN_PROFILE_V1)
            .id(&self.keychain_item_id)
            .fixed(self.slot_nonce.as_bytes())
            .fixed(&self.wrapped_root_secret);
        debug_assert_eq!(writer.len(), Self::LEN);
        writer.finish()
    }

    /// Decodes a body.
    ///
    /// # Errors
    ///
    /// As [`RecoverySlotBody::decode`].
    pub fn decode(bytes: &[u8]) -> Result<Self> {
        ensure!(
            bytes.len() == Self::LEN,
            NonCanonicalEncoding,
            "Apple Keychain slot body is not 90 bytes"
        );
        let mut reader = Reader::new(bytes, ChurStatus::NonCanonicalEncoding);
        ensure!(
            reader.u16()? == KEYCHAIN_PROFILE_V1,
            UnsupportedVersion,
            "Keychain profile is not supported"
        );
        let keychain_item_id = reader.id()?;
        let slot_nonce = Nonce::new(reader.fixed::<NONCE_LEN>()?);
        let wrapped_root_secret = reader.fixed::<WRAPPED_KEY_LEN>()?;
        reader.finish()?;
        Ok(Self {
            keychain_item_id,
            slot_nonce,
            wrapped_root_secret,
        })
    }

    /// The opaque Keychain item identifier.
    #[must_use]
    pub const fn keychain_item_id(&self) -> &Id {
        &self.keychain_item_id
    }
}

// ---------------------------------------------------------------------------
// Android Keystore slot
// ---------------------------------------------------------------------------

/// `AndroidKeystoreSlotBodyV1`, §5.
///
/// This is the one family whose AEAD runs outside Rust: the Keystore cipher
/// performs it, so the nonce is the 12-byte GCM nonce and Rust supplies the AAD
/// and stores the wrapped bytes the platform returns. There is therefore no
/// `seal` or `open` here; [`AndroidKeystoreSlotBody::aad`] is what the platform
/// call needs.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AndroidKeystoreSlotBody {
    alias: Vec<u8>,
    gcm_nonce: [u8; GCM_NONCE_LEN],
    wrapped_root_secret: [u8; WRAPPED_KEY_LEN],
}

impl AndroidKeystoreSlotBody {
    /// Encoded length for a given alias length.
    #[must_use]
    pub const fn len_for(alias_length: usize) -> usize {
        2 + 4 + alias_length + GCM_NONCE_LEN + WRAPPED_KEY_LEN
    }

    /// Builds a body from what a Keystore wrap returned.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::ResourceLimitExceeded`] for an alias outside the
    /// 16 to 64 range, and the binding errors of [`SlotBinding::check`].
    pub fn new(
        binding: &SlotBinding,
        alias: Vec<u8>,
        gcm_nonce: [u8; GCM_NONCE_LEN],
        wrapped_root_secret: [u8; WRAPPED_KEY_LEN],
    ) -> Result<Self> {
        binding.check()?;
        check_alias(&alias)?;
        Ok(Self {
            alias,
            gcm_nonce,
            wrapped_root_secret,
        })
    }

    /// The §5 AAD the platform cipher receives.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::ResourceLimitExceeded`] when the alias exceeds the
    /// `u32` length prefix, which the parser bound already prevents.
    pub fn aad(binding: &SlotBinding, alias: &[u8]) -> Result<Vec<u8>> {
        Ok(binding
            .open_tuple(tag::SLOT_ANDROID_KEYSTORE)
            .u16(KEYSTORE_PROFILE_V1)
            .variable(alias)?
            .finish())
    }

    /// Encodes the body.
    #[must_use]
    pub fn encode(&self) -> Vec<u8> {
        let mut writer = Writer::with_capacity(Self::len_for(self.alias.len()));
        writer
            .u16(KEYSTORE_PROFILE_V1)
            .u32(self.alias.len() as u32)
            .fixed(&self.alias)
            .fixed(&self.gcm_nonce)
            .fixed(&self.wrapped_root_secret);
        debug_assert_eq!(writer.len(), Self::len_for(self.alias.len()));
        writer.finish()
    }

    /// Decodes a body.
    ///
    /// # Errors
    ///
    /// As [`PasswordSlotBody::decode`], with the alias bound in place of the
    /// Argon2 bounds.
    pub fn decode(bytes: &[u8]) -> Result<Self> {
        let mut reader = Reader::new(bytes, ChurStatus::NonCanonicalEncoding);
        ensure!(
            reader.u16()? == KEYSTORE_PROFILE_V1,
            UnsupportedVersion,
            "Keystore profile is not supported"
        );
        let alias = reader.variable(bounds::ALIAS_MAX)?.to_vec();
        check_alias(&alias)?;
        let gcm_nonce = reader.fixed::<GCM_NONCE_LEN>()?;
        let wrapped_root_secret = reader.fixed::<WRAPPED_KEY_LEN>()?;
        reader
            .finish()
            .map_err(|_| bad("Android Keystore slot body carries trailing bytes"))?;
        Ok(Self {
            alias,
            gcm_nonce,
            wrapped_root_secret,
        })
    }

    /// The opaque Keystore alias.
    #[must_use]
    pub fn alias(&self) -> &[u8] {
        &self.alias
    }

    /// The 96-bit GCM nonce.
    #[must_use]
    pub const fn gcm_nonce(&self) -> &[u8; GCM_NONCE_LEN] {
        &self.gcm_nonce
    }

    /// The wrapped root secret the platform returned.
    #[must_use]
    pub const fn wrapped_root_secret(&self) -> &[u8; WRAPPED_KEY_LEN] {
        &self.wrapped_root_secret
    }
}

fn check_alias(alias: &[u8]) -> Result<()> {
    let length = u32::try_from(alias.len()).unwrap_or(u32::MAX);
    ensure!(
        (bounds::ALIAS_MIN..=bounds::ALIAS_MAX).contains(&length),
        ResourceLimitExceeded,
        "Keystore alias length is outside the v1 bounds"
    );
    Ok(())
}

const _: () = assert!(SlotBinding::LEN == 45);
const _: () = assert!(RecoverySlotBody::LEN == 74);
const _: () = assert!(AppleKeychainSlotBody::LEN == 90);
const _: () = assert!(PasswordSlotBody::len_for(16) == 108);
const _: () = assert!(AndroidKeystoreSlotBody::len_for(16) == 82);
const _: () = assert!(PasswordSlotBody::len_for(32) <= bounds::BODY_MAX as usize);
const _: () = assert!(AndroidKeystoreSlotBody::len_for(64) <= bounds::BODY_MAX as usize);

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::panic, clippy::expect_used)]

    use super::*;

    fn id(byte: u8) -> Id {
        Id::new([byte; ID_LEN]).unwrap()
    }

    fn root() -> Key {
        Key::new([0x5a; 32])
    }

    fn cheap() -> Argon2Params {
        // Production never lowers the floor. These values keep a unit test from
        // allocating 64 MiB per case; the bound checks are exercised separately
        // by `Argon2Params::validated`.
        Argon2Params::validated(65_536, 3, 1).unwrap()
    }

    fn bind(slot_type: SlotType) -> SlotBinding {
        SlotBinding::v1(id(0x11), id(0x22), slot_type, 1)
    }

    #[test]
    fn every_family_binds_the_same_six_elements() {
        let password = PasswordSlotBody::aad(
            &bind(SlotType::Password),
            Argon2Params::v1_default(),
            &[0u8; 16],
        )
        .unwrap();
        let recovery = RecoverySlotBody::aad(&bind(SlotType::Recovery));
        let keychain = AppleKeychainSlotBody::aad(&bind(SlotType::AppleKeychain));
        let keystore =
            AndroidKeystoreSlotBody::aad(&bind(SlotType::AndroidKeystore), &[0u8; 16]).unwrap();

        assert_eq!(password.len(), 86 + 16);
        assert_eq!(recovery.len(), 68);
        assert_eq!(keychain.len(), 74);
        assert_eq!(keystore.len(), 80 + 16);

        for (aad, domain) in [
            (&password, tag::SLOT_PASSWORD),
            (&recovery, tag::SLOT_RECOVERY),
            (&keychain, tag::SLOT_APPLE_KEYCHAIN),
            (&keystore, tag::SLOT_ANDROID_KEYSTORE),
        ] {
            assert!(aad.starts_with(domain));
            let elements = &aad[domain.len()..];
            assert_eq!(&elements[..ID_LEN], &[0x11; ID_LEN]);
            assert_eq!(&elements[ID_LEN..2 * ID_LEN], &[0x22; ID_LEN]);
            assert_eq!(
                &elements[SlotBinding::LEN - 8..SlotBinding::LEN],
                &1u64.to_be_bytes()
            );
        }
    }

    #[test]
    fn the_family_to_suite_pairing_is_enforced() {
        assert_eq!(
            bind(SlotType::AndroidKeystore).wrap_suite_id,
            WRAP_SUITE_ANDROID_KEYSTORE
        );
        assert_eq!(bind(SlotType::Password).wrap_suite_id, WRAP_SUITE_RUST);
        let mut wrong = bind(SlotType::Password);
        wrong.wrap_suite_id = WRAP_SUITE_ANDROID_KEYSTORE;
        assert_eq!(
            wrong.check().unwrap_err().status(),
            ChurStatus::UnsupportedSuite
        );
        let mut wrong = bind(SlotType::AndroidKeystore);
        wrong.wrap_suite_id = WRAP_SUITE_RUST;
        assert_eq!(
            wrong.check().unwrap_err().status(),
            ChurStatus::UnsupportedSuite
        );
    }

    #[test]
    fn a_generation_with_no_successor_is_refused() {
        let mut binding = bind(SlotType::Password);
        binding.slot_generation = u64::MAX;
        assert_eq!(
            binding.check().unwrap_err().status(),
            ChurStatus::InvalidInput
        );
    }

    #[test]
    fn a_password_slot_round_trips_and_recovers_the_root() {
        let binding = bind(SlotType::Password);
        let body = PasswordSlotBody::seal(
            &binding,
            b"correct horse battery staple",
            vec![0x77; 16],
            cheap(),
            Nonce::new([0x88; NONCE_LEN]),
            &root(),
        )
        .unwrap();
        assert_eq!(body.encode().len(), 108);
        assert_eq!(PasswordSlotBody::decode(&body.encode()).unwrap(), body);
        assert_eq!(
            body.open(&binding, b"correct horse battery staple")
                .unwrap()
                .expose(),
            root().expose()
        );
    }

    #[test]
    fn a_wrong_password_and_a_damaged_slot_share_one_result() {
        let binding = bind(SlotType::Password);
        let body = PasswordSlotBody::seal(
            &binding,
            b"password",
            vec![0x77; 16],
            cheap(),
            Nonce::new([0x88; NONCE_LEN]),
            &root(),
        )
        .unwrap();
        let Err(wrong_password) = body.open(&binding, b"passworE") else {
            panic!("a wrong password opened the slot")
        };
        assert_eq!(wrong_password.status(), ChurStatus::AuthenticationFailed);
        let mut damaged = body.clone();
        damaged.wrapped_root_secret[0] ^= 1;
        let Err(damaged_slot) = damaged.open(&binding, b"password") else {
            panic!("a damaged slot opened")
        };
        assert_eq!(damaged_slot.status(), ChurStatus::AuthenticationFailed);
    }

    #[test]
    fn a_slot_from_another_vault_or_generation_does_not_authenticate() {
        let binding = bind(SlotType::Password);
        let body = PasswordSlotBody::seal(
            &binding,
            b"password",
            vec![0x77; 16],
            cheap(),
            Nonce::new([0x88; NONCE_LEN]),
            &root(),
        )
        .unwrap();
        for other in [
            SlotBinding::v1(id(0x12), id(0x22), SlotType::Password, 1),
            SlotBinding::v1(id(0x11), id(0x23), SlotType::Password, 1),
            SlotBinding::v1(id(0x11), id(0x22), SlotType::Password, 2),
        ] {
            assert!(body.open(&other, b"password").is_err());
        }
    }

    #[test]
    fn a_downgraded_memory_cost_is_refused_by_the_parser_and_by_the_aad() {
        let binding = bind(SlotType::Password);
        let body = PasswordSlotBody::seal(
            &binding,
            b"password",
            vec![0x77; 16],
            cheap(),
            Nonce::new([0x88; NONCE_LEN]),
            &root(),
        )
        .unwrap();
        let mut encoded = body.encode();
        encoded[4..8].copy_from_slice(&1024u32.to_be_bytes());
        assert_eq!(
            PasswordSlotBody::decode(&encoded).unwrap_err().status(),
            ChurStatus::ResourceLimitExceeded
        );

        // With the bound lifted, the AAD still binds the parameter.
        let mut lowered = body.clone();
        lowered.params = Argon2Params::validated(131_072, 3, 1).unwrap();
        assert!(lowered.open(&binding, b"password").is_err());
    }

    #[test]
    fn a_recovery_slot_and_a_password_slot_unwrap_the_same_root() {
        let password_binding = bind(SlotType::Password);
        let recovery_binding = bind(SlotType::Recovery);
        let password = PasswordSlotBody::seal(
            &password_binding,
            b"password",
            vec![0x77; 16],
            cheap(),
            Nonce::new([0x88; NONCE_LEN]),
            &root(),
        )
        .unwrap();
        let recovery_secret = Key::new([0x99; 32]);
        let recovery = RecoverySlotBody::seal(
            &recovery_binding,
            &recovery_secret,
            Nonce::new([0xaa; NONCE_LEN]),
            &root(),
        )
        .unwrap();
        assert_eq!(recovery.encode().len(), 74);
        assert_eq!(
            password
                .open(&password_binding, b"password")
                .unwrap()
                .expose(),
            recovery
                .open(&recovery_binding, &recovery_secret)
                .unwrap()
                .expose()
        );
    }

    #[test]
    fn an_apple_keychain_slot_round_trips() {
        let binding = bind(SlotType::AppleKeychain);
        let secret = Key::new([0xbb; 32]);
        let body = AppleKeychainSlotBody::seal(
            &binding,
            &secret,
            id(0xcc),
            Nonce::new([0xdd; NONCE_LEN]),
            &root(),
        )
        .unwrap();
        assert_eq!(body.encode().len(), 90);
        assert_eq!(AppleKeychainSlotBody::decode(&body.encode()).unwrap(), body);
        assert_eq!(
            body.open(&binding, &secret).unwrap().expose(),
            root().expose()
        );
        assert!(body.open(&binding, &Key::new([0xbc; 32])).is_err());
    }

    #[test]
    fn an_android_keystore_body_round_trips_without_a_rust_aead() {
        let binding = bind(SlotType::AndroidKeystore);
        let body = AndroidKeystoreSlotBody::new(
            &binding,
            vec![0xee; 16],
            [0xff; GCM_NONCE_LEN],
            [0x01; WRAPPED_KEY_LEN],
        )
        .unwrap();
        assert_eq!(body.encode().len(), 82);
        assert_eq!(
            AndroidKeystoreSlotBody::decode(&body.encode()).unwrap(),
            body
        );
    }

    #[test]
    fn salt_and_alias_lengths_are_bounded_at_both_ends() {
        let binding = bind(SlotType::Password);
        for length in [15usize, 33] {
            assert!(
                PasswordSlotBody::seal(
                    &binding,
                    b"password",
                    vec![0x77; length],
                    cheap(),
                    Nonce::new([0x88; NONCE_LEN]),
                    &root(),
                )
                .is_err(),
                "salt length {length}"
            );
        }
        let keystore = bind(SlotType::AndroidKeystore);
        for length in [15usize, 65] {
            assert!(
                AndroidKeystoreSlotBody::new(
                    &keystore,
                    vec![0xee; length],
                    [0; GCM_NONCE_LEN],
                    [0; WRAPPED_KEY_LEN],
                )
                .is_err(),
                "alias length {length}"
            );
        }
    }

    #[test]
    fn truncation_and_trailing_bytes_are_rejected_for_every_family() {
        let binding = bind(SlotType::Password);
        let password = PasswordSlotBody::seal(
            &binding,
            b"password",
            vec![0x77; 16],
            cheap(),
            Nonce::new([0x88; NONCE_LEN]),
            &root(),
        )
        .unwrap()
        .encode();
        let recovery = RecoverySlotBody::seal(
            &bind(SlotType::Recovery),
            &Key::new([0x99; 32]),
            Nonce::new([0xaa; NONCE_LEN]),
            &root(),
        )
        .unwrap()
        .encode();
        let keychain = AppleKeychainSlotBody::seal(
            &bind(SlotType::AppleKeychain),
            &Key::new([0xbb; 32]),
            id(0xcc),
            Nonce::new([0xdd; NONCE_LEN]),
            &root(),
        )
        .unwrap()
        .encode();
        let keystore = AndroidKeystoreSlotBody::new(
            &bind(SlotType::AndroidKeystore),
            vec![0xee; 16],
            [0xff; GCM_NONCE_LEN],
            [0x01; WRAPPED_KEY_LEN],
        )
        .unwrap()
        .encode();

        for cut in 0..password.len() {
            assert!(
                PasswordSlotBody::decode(&password[..cut]).is_err(),
                "password {cut}"
            );
        }
        for cut in 0..recovery.len() {
            assert!(
                RecoverySlotBody::decode(&recovery[..cut]).is_err(),
                "recovery {cut}"
            );
        }
        for cut in 0..keychain.len() {
            assert!(
                AppleKeychainSlotBody::decode(&keychain[..cut]).is_err(),
                "keychain {cut}"
            );
        }
        for cut in 0..keystore.len() {
            assert!(
                AndroidKeystoreSlotBody::decode(&keystore[..cut]).is_err(),
                "keystore {cut}"
            );
        }
        for mut encoded in [password, recovery, keychain, keystore] {
            let original = encoded.clone();
            encoded.push(0);
            let rejected = PasswordSlotBody::decode(&encoded).is_err()
                && RecoverySlotBody::decode(&encoded).is_err()
                && AppleKeychainSlotBody::decode(&encoded).is_err()
                && AndroidKeystoreSlotBody::decode(&encoded).is_err();
            assert!(
                rejected,
                "a trailing byte was accepted after {} bytes",
                original.len()
            );
        }
    }

    #[test]
    fn an_unknown_profile_identifier_fails_closed() {
        let binding = bind(SlotType::Recovery);
        let mut encoded = RecoverySlotBody::seal(
            &binding,
            &Key::new([0x99; 32]),
            Nonce::new([0xaa; NONCE_LEN]),
            &root(),
        )
        .unwrap()
        .encode();
        encoded[1] = 0x02;
        assert_eq!(
            RecoverySlotBody::decode(&encoded).unwrap_err().status(),
            ChurStatus::UnsupportedVersion
        );
    }
}
