//! The two key envelopes of the v1 wrapping chain.
//!
//! `docs/format/COLLECTION_KEY_ENVELOPE_V1.md` and
//! `docs/format/OBJECT_KEY_ENVELOPE_V1.md` freeze a 126-byte and a 142-byte
//! record. Together they are the chain root to `CollectionEnvelopeKey` to
//! `SecurityCollectionKey` to `ObjectEnvelopeKey` to `ObjectKey`, so every
//! object key in a vault is reachable only through one of these records.
//!
//! Both readers compare `format_version`, `encoding_profile`, and `suite_id`
//! against the supported values before the AEAD runs, so a modified identifier
//! fails as `UNSUPPORTED_VERSION` or `UNSUPPORTED_SUITE` and can never select a
//! different construction.

use chur_core::limits::{NONCE_LEN, WRAPPED_KEY_LEN, envelope as bounds};
use chur_core::status::ChurStatus;
use chur_core::{Error, Id, Result, ensure};
use chur_crypto::aead::{self, Nonce};
use chur_crypto::kdf::{self, Context, Label};
use chur_crypto::secret::Key;
use chur_crypto::tuple::{Tuple, tag};

use crate::codec::{Reader, Writer};
use crate::constants::{ENCODING_PROFILE_V1, ENVELOPE_FORMAT_VERSION_V1, SUITE_V1};

/// Rejects a counter that is zero or has no successor.
///
/// Both specifications start `collection_epoch` and `envelope_generation` at 1
/// and require `0xFFFFFFFFFFFFFFFF` to be rejected so an increment always
/// exists.
fn check_counter(value: u64, context: &'static str) -> Result<()> {
    if value == 0 || value == u64::MAX {
        return Err(Error::new(ChurStatus::InvalidInput, context));
    }
    Ok(())
}

fn check_identifiers(format_version: u16, encoding_profile: u16, suite_id: u16) -> Result<()> {
    ensure!(
        format_version == ENVELOPE_FORMAT_VERSION_V1,
        UnsupportedVersion,
        "envelope format version is not supported"
    );
    ensure!(
        encoding_profile == ENCODING_PROFILE_V1,
        UnsupportedVersion,
        "envelope encoding profile is not supported"
    );
    ensure!(
        suite_id == SUITE_V1,
        UnsupportedSuite,
        "envelope suite is not supported"
    );
    Ok(())
}

/// `CollectionKeyEnvelopeV1`: one `SecurityCollectionKey[epoch]` wrapped under a
/// root-derived envelope key.
///
/// The record is a private catalog row rather than a file, so it carries no
/// magic; the catalog table selects it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CollectionKeyEnvelope {
    vault_id: Id,
    collection_id: Id,
    collection_epoch: u64,
    envelope_generation: u64,
    nonce: Nonce,
    wrapped_collection_key: [u8; WRAPPED_KEY_LEN],
}

impl CollectionKeyEnvelope {
    /// Exact encoded length.
    pub const LEN: usize = bounds::COLLECTION_KEY_ENVELOPE_LEN;

    /// Derives `CollectionEnvelopeKey` for one vault, collection, and epoch.
    ///
    /// # Errors
    ///
    /// Returns an error only if the derivation itself fails.
    pub fn wrapping_key(
        root: &Key,
        vault_id: &Id,
        collection_id: &Id,
        collection_epoch: u64,
    ) -> Result<Key> {
        kdf::derive_from(
            root,
            Label::RootCollectionEnvelope,
            &Context::collection_envelope(vault_id, collection_id, collection_epoch),
        )
    }

    /// Seals a collection key.
    ///
    /// The nonce is a parameter rather than a draw so a vector is reproducible.
    /// Production callers pass a fresh 24-byte value from the CSPRNG.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::InvalidInput`] for a counter of zero or
    /// `u64::MAX`, and an AEAD failure otherwise.
    pub fn seal(
        root: &Key,
        vault_id: Id,
        collection_id: Id,
        collection_epoch: u64,
        envelope_generation: u64,
        nonce: Nonce,
        collection_key: &Key,
    ) -> Result<Self> {
        check_counter(
            collection_epoch,
            "collection epoch is zero or has no successor",
        )?;
        check_counter(
            envelope_generation,
            "envelope generation is zero or has no successor",
        )?;
        let aad = collection_aad(
            &vault_id,
            &collection_id,
            collection_epoch,
            envelope_generation,
        );
        let wrapping = Self::wrapping_key(root, &vault_id, &collection_id, collection_epoch)?;
        let sealed = aead::seal(&wrapping, &nonce, collection_key.expose(), &aad)?;
        let wrapped_collection_key: [u8; WRAPPED_KEY_LEN] = sealed
            .as_slice()
            .try_into()
            .map_err(|_| Error::new(ChurStatus::InternalFailure, "wrapped key is not 48 bytes"))?;
        Ok(Self {
            vault_id,
            collection_id,
            collection_epoch,
            envelope_generation,
            nonce,
            wrapped_collection_key,
        })
    }

    /// Opens the envelope and returns the collection key.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::ObjectCorrupt`] when the tag does not verify. The
    /// caller redacts it so a damaged record is not distinguishable from a
    /// wrong credential.
    pub fn open(&self, root: &Key) -> Result<Key> {
        let aad = self.aad();
        let wrapping = Self::wrapping_key(
            root,
            &self.vault_id,
            &self.collection_id,
            self.collection_epoch,
        )?;
        let plaintext = aead::open(&wrapping, &self.nonce, &self.wrapped_collection_key, &aad)?;
        let bytes: [u8; 32] = plaintext.as_slice().try_into().map_err(|_| {
            Error::new(
                ChurStatus::ObjectCorrupt,
                "unwrapped collection key is not 32 bytes",
            )
        })?;
        Ok(Key::new(bytes))
    }

    /// The §3 AAD of this envelope.
    #[must_use]
    pub fn aad(&self) -> Vec<u8> {
        collection_aad(
            &self.vault_id,
            &self.collection_id,
            self.collection_epoch,
            self.envelope_generation,
        )
    }

    /// Encodes the 126-byte record.
    #[must_use]
    pub fn encode(&self) -> Vec<u8> {
        let mut writer = Writer::with_capacity(Self::LEN);
        writer
            .u16(ENVELOPE_FORMAT_VERSION_V1)
            .u16(ENCODING_PROFILE_V1)
            .u16(SUITE_V1)
            .id(&self.vault_id)
            .id(&self.collection_id)
            .u64(self.collection_epoch)
            .u64(self.envelope_generation)
            .fixed(self.nonce.as_bytes())
            .fixed(&self.wrapped_collection_key);
        debug_assert_eq!(writer.len(), Self::LEN);
        writer.finish()
    }

    /// Decodes a 126-byte record.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::UnsupportedVersion`] or
    /// [`ChurStatus::UnsupportedSuite`] for an unsupported identifier,
    /// [`ChurStatus::NonCanonicalEncoding`] for a wrong length or trailing
    /// bytes, and [`ChurStatus::InvalidInput`] for a reserved identifier or an
    /// out-of-range counter.
    pub fn decode(bytes: &[u8]) -> Result<Self> {
        ensure!(
            bytes.len() == Self::LEN,
            NonCanonicalEncoding,
            "collection key envelope is not 126 bytes"
        );
        let mut reader = Reader::new(bytes, ChurStatus::NonCanonicalEncoding);
        check_identifiers(reader.u16()?, reader.u16()?, reader.u16()?)?;
        let vault_id = reader.id()?;
        let collection_id = reader.id()?;
        let collection_epoch = reader.u64()?;
        let envelope_generation = reader.u64()?;
        check_counter(
            collection_epoch,
            "collection epoch is zero or has no successor",
        )?;
        check_counter(
            envelope_generation,
            "envelope generation is zero or has no successor",
        )?;
        let nonce = Nonce::new(reader.fixed::<NONCE_LEN>()?);
        let wrapped_collection_key = reader.fixed::<WRAPPED_KEY_LEN>()?;
        reader.finish()?;
        Ok(Self {
            vault_id,
            collection_id,
            collection_epoch,
            envelope_generation,
            nonce,
            wrapped_collection_key,
        })
    }

    /// The vault this envelope belongs to.
    #[must_use]
    pub const fn vault_id(&self) -> &Id {
        &self.vault_id
    }

    /// The collection this envelope belongs to.
    #[must_use]
    pub const fn collection_id(&self) -> &Id {
        &self.collection_id
    }

    /// The epoch of the wrapped key.
    #[must_use]
    pub const fn collection_epoch(&self) -> u64 {
        self.collection_epoch
    }

    /// The generation of this envelope.
    #[must_use]
    pub const fn envelope_generation(&self) -> u64 {
        self.envelope_generation
    }
}

fn collection_aad(
    vault_id: &Id,
    collection_id: &Id,
    collection_epoch: u64,
    envelope_generation: u64,
) -> Vec<u8> {
    Tuple::new(tag::COLLECTION_KEY_ENVELOPE)
        .id(vault_id)
        .id(collection_id)
        .u64(collection_epoch)
        .u16(SUITE_V1)
        .u64(envelope_generation)
        .finish()
}

/// `ObjectKeyEnvelopeV1`: one random `ObjectKey` wrapped under a Security
/// Collection key.
///
/// It is mutable and stored separately from the immutable media container, so a
/// collection change or a share rewraps the key without re-encrypting media.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ObjectKeyEnvelope {
    vault_id: Id,
    collection_id: Id,
    collection_epoch: u64,
    object_id: Id,
    envelope_generation: u64,
    nonce: Nonce,
    wrapped_object_key: [u8; WRAPPED_KEY_LEN],
}

impl ObjectKeyEnvelope {
    /// Exact encoded length.
    pub const LEN: usize = bounds::OBJECT_KEY_ENVELOPE_LEN;

    /// Derives `ObjectEnvelopeKey` for one collection, epoch, and object.
    ///
    /// # Errors
    ///
    /// Returns an error only if the derivation itself fails.
    pub fn wrapping_key(
        collection_key: &Key,
        collection_id: &Id,
        collection_epoch: u64,
        object_id: &Id,
    ) -> Result<Key> {
        kdf::derive_from(
            collection_key,
            Label::CollectionObjectEnvelope,
            &Context::object_envelope(collection_id, collection_epoch, object_id),
        )
    }

    /// Seals an object key.
    ///
    /// # Errors
    ///
    /// As [`CollectionKeyEnvelope::seal`].
    #[allow(clippy::too_many_arguments)]
    pub fn seal(
        collection_key: &Key,
        vault_id: Id,
        collection_id: Id,
        collection_epoch: u64,
        object_id: Id,
        envelope_generation: u64,
        nonce: Nonce,
        object_key: &Key,
    ) -> Result<Self> {
        check_counter(
            collection_epoch,
            "collection epoch is zero or has no successor",
        )?;
        check_counter(
            envelope_generation,
            "envelope generation is zero or has no successor",
        )?;
        let aad = object_aad(
            &vault_id,
            &collection_id,
            collection_epoch,
            &object_id,
            envelope_generation,
        );
        let wrapping =
            Self::wrapping_key(collection_key, &collection_id, collection_epoch, &object_id)?;
        let sealed = aead::seal(&wrapping, &nonce, object_key.expose(), &aad)?;
        let wrapped_object_key: [u8; WRAPPED_KEY_LEN] = sealed
            .as_slice()
            .try_into()
            .map_err(|_| Error::new(ChurStatus::InternalFailure, "wrapped key is not 48 bytes"))?;
        Ok(Self {
            vault_id,
            collection_id,
            collection_epoch,
            object_id,
            envelope_generation,
            nonce,
            wrapped_object_key,
        })
    }

    /// Opens the envelope and returns the object key.
    ///
    /// # Errors
    ///
    /// As [`CollectionKeyEnvelope::open`].
    pub fn open(&self, collection_key: &Key) -> Result<Key> {
        let aad = self.aad();
        let wrapping = Self::wrapping_key(
            collection_key,
            &self.collection_id,
            self.collection_epoch,
            &self.object_id,
        )?;
        let plaintext = aead::open(&wrapping, &self.nonce, &self.wrapped_object_key, &aad)?;
        let bytes: [u8; 32] = plaintext.as_slice().try_into().map_err(|_| {
            Error::new(
                ChurStatus::ObjectCorrupt,
                "unwrapped object key is not 32 bytes",
            )
        })?;
        Ok(Key::new(bytes))
    }

    /// The §3 AAD of this envelope.
    #[must_use]
    pub fn aad(&self) -> Vec<u8> {
        object_aad(
            &self.vault_id,
            &self.collection_id,
            self.collection_epoch,
            &self.object_id,
            self.envelope_generation,
        )
    }

    /// Encodes the 142-byte record.
    #[must_use]
    pub fn encode(&self) -> Vec<u8> {
        let mut writer = Writer::with_capacity(Self::LEN);
        writer
            .u16(ENVELOPE_FORMAT_VERSION_V1)
            .u16(ENCODING_PROFILE_V1)
            .u16(SUITE_V1)
            .id(&self.vault_id)
            .id(&self.collection_id)
            .u64(self.collection_epoch)
            .id(&self.object_id)
            .u64(self.envelope_generation)
            .fixed(self.nonce.as_bytes())
            .fixed(&self.wrapped_object_key);
        debug_assert_eq!(writer.len(), Self::LEN);
        writer.finish()
    }

    /// Decodes a 142-byte record.
    ///
    /// # Errors
    ///
    /// As [`CollectionKeyEnvelope::decode`].
    pub fn decode(bytes: &[u8]) -> Result<Self> {
        ensure!(
            bytes.len() == Self::LEN,
            NonCanonicalEncoding,
            "object key envelope is not 142 bytes"
        );
        let mut reader = Reader::new(bytes, ChurStatus::NonCanonicalEncoding);
        check_identifiers(reader.u16()?, reader.u16()?, reader.u16()?)?;
        let vault_id = reader.id()?;
        let collection_id = reader.id()?;
        let collection_epoch = reader.u64()?;
        let object_id = reader.id()?;
        let envelope_generation = reader.u64()?;
        check_counter(
            collection_epoch,
            "collection epoch is zero or has no successor",
        )?;
        check_counter(
            envelope_generation,
            "envelope generation is zero or has no successor",
        )?;
        let nonce = Nonce::new(reader.fixed::<NONCE_LEN>()?);
        let wrapped_object_key = reader.fixed::<WRAPPED_KEY_LEN>()?;
        reader.finish()?;
        Ok(Self {
            vault_id,
            collection_id,
            collection_epoch,
            object_id,
            envelope_generation,
            nonce,
            wrapped_object_key,
        })
    }

    /// The vault this envelope belongs to.
    #[must_use]
    pub const fn vault_id(&self) -> &Id {
        &self.vault_id
    }

    /// The collection this envelope belongs to.
    #[must_use]
    pub const fn collection_id(&self) -> &Id {
        &self.collection_id
    }

    /// The epoch of the wrapping collection key.
    #[must_use]
    pub const fn collection_epoch(&self) -> u64 {
        self.collection_epoch
    }

    /// The object whose key this envelope wraps.
    #[must_use]
    pub const fn object_id(&self) -> &Id {
        &self.object_id
    }

    /// The generation of this envelope.
    #[must_use]
    pub const fn envelope_generation(&self) -> u64 {
        self.envelope_generation
    }

    /// Rewraps the same object key under a new generation.
    ///
    /// §5 makes a replacement an `envelope_generation` increase; a new object
    /// key is a new object, not a new generation.
    ///
    /// # Errors
    ///
    /// As [`ObjectKeyEnvelope::seal`], plus an opening failure of the current
    /// envelope.
    pub fn rewrap(
        &self,
        current_collection_key: &Key,
        destination_collection_key: &Key,
        destination_collection_id: Id,
        destination_epoch: u64,
        envelope_generation: u64,
        nonce: Nonce,
    ) -> Result<Self> {
        let object_key = self.open(current_collection_key)?;
        Self::seal(
            destination_collection_key,
            self.vault_id,
            destination_collection_id,
            destination_epoch,
            self.object_id,
            envelope_generation,
            nonce,
            &object_key,
        )
    }
}

fn object_aad(
    vault_id: &Id,
    collection_id: &Id,
    collection_epoch: u64,
    object_id: &Id,
    envelope_generation: u64,
) -> Vec<u8> {
    Tuple::new(tag::OBJECT_KEY_ENVELOPE)
        .id(vault_id)
        .id(collection_id)
        .u64(collection_epoch)
        .id(object_id)
        .u16(SUITE_V1)
        .u64(envelope_generation)
        .finish()
}

const _: () = assert!(CollectionKeyEnvelope::LEN == 6 + 16 + 16 + 8 + 8 + 24 + 48);
const _: () = assert!(ObjectKeyEnvelope::LEN == 6 + 16 + 16 + 8 + 16 + 8 + 24 + 48);

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::panic, clippy::expect_used)]

    use super::*;

    fn id(byte: u8) -> Id {
        Id::new([byte; 16]).unwrap()
    }

    fn root() -> Key {
        Key::new([0x01; 32])
    }

    fn collection_envelope() -> CollectionKeyEnvelope {
        CollectionKeyEnvelope::seal(
            &root(),
            id(0x11),
            id(0x22),
            1,
            1,
            Nonce::new([0x33; NONCE_LEN]),
            &Key::new([0x44; 32]),
        )
        .unwrap()
    }

    fn object_envelope() -> ObjectKeyEnvelope {
        ObjectKeyEnvelope::seal(
            &Key::new([0x44; 32]),
            id(0x11),
            id(0x22),
            1,
            id(0x55),
            1,
            Nonce::new([0x66; NONCE_LEN]),
            &Key::new([0x77; 32]),
        )
        .unwrap()
    }

    #[test]
    fn the_records_are_the_frozen_lengths() {
        assert_eq!(collection_envelope().encode().len(), 126);
        assert_eq!(object_envelope().encode().len(), 142);
    }

    #[test]
    fn the_aad_tuples_are_the_documented_lengths() {
        assert_eq!(collection_envelope().aad().len(), 81);
        assert_eq!(object_envelope().aad().len(), 93);
    }

    #[test]
    fn the_offset_table_places_every_field_where_section_one_says() {
        let encoded = object_envelope().encode();
        assert_eq!(&encoded[0x00..0x06], &[0, 1, 0, 1, 0, 1]);
        assert_eq!(&encoded[0x06..0x16], &[0x11; 16]);
        assert_eq!(&encoded[0x16..0x26], &[0x22; 16]);
        assert_eq!(&encoded[0x26..0x2e], &1u64.to_be_bytes());
        assert_eq!(&encoded[0x2e..0x3e], &[0x55; 16]);
        assert_eq!(&encoded[0x3e..0x46], &1u64.to_be_bytes());
        assert_eq!(&encoded[0x46..0x5e], &[0x66; NONCE_LEN]);
        assert_eq!(encoded[0x5e..].len(), WRAPPED_KEY_LEN);

        let encoded = collection_envelope().encode();
        assert_eq!(&encoded[0x06..0x16], &[0x11; 16]);
        assert_eq!(&encoded[0x16..0x26], &[0x22; 16]);
        assert_eq!(&encoded[0x2e..0x36], &1u64.to_be_bytes());
        assert_eq!(&encoded[0x36..0x4e], &[0x33; NONCE_LEN]);
        assert_eq!(encoded[0x4e..].len(), WRAPPED_KEY_LEN);
    }

    #[test]
    fn a_record_round_trips_through_encode_and_decode() {
        let envelope = collection_envelope();
        assert_eq!(
            CollectionKeyEnvelope::decode(&envelope.encode()).unwrap(),
            envelope
        );
        let envelope = object_envelope();
        assert_eq!(
            ObjectKeyEnvelope::decode(&envelope.encode()).unwrap(),
            envelope
        );
    }

    #[test]
    fn the_whole_chain_recovers_the_object_key() {
        let collection_key = Key::new([0x44; 32]);
        let recovered = collection_envelope().open(&root()).unwrap();
        assert_eq!(recovered.expose(), collection_key.expose());
        let object_key = object_envelope().open(&recovered).unwrap();
        assert_eq!(object_key.expose(), &[0x77; 32]);
    }

    #[test]
    fn a_wrong_root_or_collection_key_fails_to_open() {
        let Err(error) = collection_envelope().open(&Key::new([0x02; 32])) else {
            panic!("a wrong root opened the envelope")
        };
        assert_eq!(error.status(), ChurStatus::ObjectCorrupt);
        assert!(object_envelope().open(&Key::new([0x45; 32])).is_err());
    }

    #[test]
    fn substitution_across_any_bound_field_fails_authentication() {
        let base = object_envelope();
        let variants = [
            ObjectKeyEnvelope::seal(
                &Key::new([0x44; 32]),
                id(0x12),
                id(0x22),
                1,
                id(0x55),
                1,
                Nonce::new([0x66; NONCE_LEN]),
                &Key::new([0x77; 32]),
            ),
            ObjectKeyEnvelope::seal(
                &Key::new([0x44; 32]),
                id(0x11),
                id(0x22),
                2,
                id(0x55),
                1,
                Nonce::new([0x66; NONCE_LEN]),
                &Key::new([0x77; 32]),
            ),
            ObjectKeyEnvelope::seal(
                &Key::new([0x44; 32]),
                id(0x11),
                id(0x22),
                1,
                id(0x55),
                2,
                Nonce::new([0x66; NONCE_LEN]),
                &Key::new([0x77; 32]),
            ),
        ];
        for variant in variants {
            let variant = variant.unwrap();
            // Splice the other envelope's wrapped bytes into the base record and
            // assert the substitution does not authenticate.
            let mut spliced = base.clone();
            spliced.wrapped_object_key = variant.wrapped_object_key;
            assert!(spliced.open(&Key::new([0x44; 32])).is_err());
        }
    }

    #[test]
    fn an_unsupported_identifier_is_rejected_before_the_aead() {
        let mut encoded = object_envelope().encode();
        encoded[1] = 0x02;
        assert_eq!(
            ObjectKeyEnvelope::decode(&encoded).unwrap_err().status(),
            ChurStatus::UnsupportedVersion
        );
        let mut encoded = object_envelope().encode();
        encoded[5] = 0x02;
        assert_eq!(
            ObjectKeyEnvelope::decode(&encoded).unwrap_err().status(),
            ChurStatus::UnsupportedSuite
        );
    }

    #[test]
    fn a_counter_of_zero_or_the_maximum_is_rejected() {
        let mut encoded = object_envelope().encode();
        encoded[0x26..0x2e].copy_from_slice(&0u64.to_be_bytes());
        assert_eq!(
            ObjectKeyEnvelope::decode(&encoded).unwrap_err().status(),
            ChurStatus::InvalidInput
        );
        let mut encoded = object_envelope().encode();
        encoded[0x3e..0x46].copy_from_slice(&u64::MAX.to_be_bytes());
        assert_eq!(
            ObjectKeyEnvelope::decode(&encoded).unwrap_err().status(),
            ChurStatus::InvalidInput
        );
    }

    #[test]
    fn truncation_at_every_boundary_is_rejected() {
        let encoded = object_envelope().encode();
        for cut in 0..encoded.len() {
            assert!(
                ObjectKeyEnvelope::decode(&encoded[..cut]).is_err(),
                "cut {cut}"
            );
        }
        let mut extended = encoded.clone();
        extended.push(0);
        assert_eq!(
            ObjectKeyEnvelope::decode(&extended).unwrap_err().status(),
            ChurStatus::NonCanonicalEncoding
        );
    }

    #[test]
    fn every_single_bit_flip_of_the_wrapped_bytes_fails_to_open() {
        let envelope = collection_envelope();
        for index in 0..WRAPPED_KEY_LEN {
            for bit in 0..8 {
                let mut damaged = envelope.clone();
                damaged.wrapped_collection_key[index] ^= 1 << bit;
                assert!(damaged.open(&root()).is_err(), "bit {bit} of byte {index}");
            }
        }
    }

    #[test]
    fn a_rewrap_keeps_the_object_key_and_advances_the_generation() {
        let first_collection = Key::new([0x44; 32]);
        let second_collection = Key::new([0x88; 32]);
        let envelope = object_envelope();
        let rewrapped = envelope
            .rewrap(
                &first_collection,
                &second_collection,
                id(0x99),
                2,
                2,
                Nonce::new([0xaa; NONCE_LEN]),
            )
            .unwrap();
        assert_eq!(rewrapped.envelope_generation(), 2);
        assert_eq!(rewrapped.collection_epoch(), 2);
        assert_eq!(rewrapped.object_id(), envelope.object_id());
        assert_eq!(
            rewrapped.open(&second_collection).unwrap().expose(),
            envelope.open(&first_collection).unwrap().expose()
        );
        assert_ne!(rewrapped.encode(), envelope.encode());
    }
}
