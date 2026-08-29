//! HKDF-SHA-256 derivation under the v1 label and context registry.
//!
//! `docs/CRYPTOGRAPHY.md` §13 fixes the construction: extract with a salt of 32
//! zero bytes, then expand under the `info` tuple
//! `CanonicalTuple("CHUR\0KDF\0INFO\0V1", purpose_label, context_fields)` to 32
//! bytes. `docs/security/KEY_HIERARCHY.md` §3 is the only registry of the label
//! strings and of the context element list each one takes.
//!
//! The extract salt never varies. All domain separation is carried by `info`,
//! so a derivation that changed the salt would silently produce a second key
//! for one label.

use hkdf::Hkdf;
use sha2::Sha256;

use chur_core::limits::KEY_LEN;
use chur_core::status::ChurStatus;
use chur_core::{Error, Id, Result};

use crate::secret::Key;
use crate::tuple::{Tuple, tag};

/// The RFC 5869 default extract salt for HKDF-SHA-256: 32 zero bytes.
///
/// It is the same value for every vault, platform, profile, and derivation.
const EXTRACT_SALT: [u8; 32] = [0u8; 32];

/// The element list a label's context carries.
///
/// Every label maps to exactly one shape, and [`derive`] refuses a context of
/// another shape, so a call cannot pass a collection context to a root label.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ContextShape {
    /// `vault_id`.
    Vault,
    /// `vault_id`, `collection_id`, `collection_epoch`.
    CollectionEnvelope,
    /// `collection_id`, `collection_epoch`, `object_id`.
    ObjectEnvelope,
    /// `collection_id`, `collection_epoch`.
    CollectionMetadata,
    /// `object_id`, `stream_id`, `stream_kind`, `stream_revision`.
    ContainerStream,
    /// `object_id`, `stream_kind`, `source_content_revision`, `stream_revision`.
    DerivedAsset,
    /// `object_id`, `metadata_revision`.
    ObjectMetadata,
    /// `vault_id`, `slot_id`, `slot_generation`.
    Slot,
}

macro_rules! labels {
    ($( $(#[$meta:meta])* $variant:ident = $text:literal, $shape:ident; )+) => {
        /// A registered HKDF domain label.
        ///
        /// A label selects key bytes, so it is never redefined. A different
        /// purpose, tier, or spelling is a new label plus a migration, per
        /// `docs/security/KEY_HIERARCHY.md` §3.
        #[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
        #[non_exhaustive]
        pub enum Label {
            $( $(#[$meta])* $variant, )+
        }

        impl Label {
            /// Every registered label, in registry order.
            pub const ALL: &'static [Label] = &[ $( Label::$variant, )+ ];

            /// The exact label string.
            #[must_use]
            pub const fn as_str(self) -> &'static str {
                match self {
                    $( Label::$variant => $text, )+
                }
            }

            /// The context element list this label takes.
            #[must_use]
            pub const fn shape(self) -> ContextShape {
                match self {
                    $( Label::$variant => ContextShape::$shape, )+
                }
            }
        }
    };
}

labels! {
    /// Wraps random security-collection keys. Input: `VaultRootSecret`.
    RootCollectionEnvelope = "chur/v1/root/collection-envelope", CollectionEnvelope;
    /// Opens the Rust-owned private catalog. Input: `VaultRootSecret`.
    RootCatalogDatabase = "chur/v1/root/catalog-database", Vault;
    /// Protects catalog records. Input: `VaultRootSecret`.
    RootCatalogRecords = "chur/v1/root/catalog-records", Vault;
    /// Protects private search structures. Input: `VaultRootSecret`.
    RootSearch = "chur/v1/root/search", Vault;
    /// Keyed opaque-identifier derivations. Input: `VaultRootSecret`.
    RootIdentifiers = "chur/v1/root/identifiers", Vault;
    /// Keyed local duplicate-detection fingerprints. Input: `VaultRootSecret`.
    RootLocalFingerprint = "chur/v1/root/local-fingerprint", Vault;
    /// Protects private settings outside the catalog. Input: `VaultRootSecret`.
    RootPrivateSettings = "chur/v1/root/private-settings", Vault;
    /// Wraps device and user identity private keys. Input: `VaultRootSecret`.
    RootDeviceIdentityWrap = "chur/v1/root/device-identity-wrap", Vault;
    /// Authenticates a native backup package manifest. Input: `VaultRootSecret`.
    RootBackupManifest = "chur/v1/root/backup-manifest", Vault;
    /// Authenticates the vault descriptor body. Input: `VaultRootSecret`.
    RootDescriptorAuth = "chur/v1/root/descriptor-auth", Vault;
    /// Encrypts root-domain sync operations. Input: `VaultRootSecret`.
    RootSyncOperations = "chur/v1/root/sync-operations", Vault;
    /// Derives the root-domain opaque selector. Input: `VaultRootSecret`.
    RootSyncSelector = "chur/v1/root/sync-selector", Vault;

    /// Wraps one object key. Input: `SecurityCollectionKey[epoch]`.
    CollectionObjectEnvelope = "chur/v1/collection/object-envelope", ObjectEnvelope;
    /// Protects collection metadata. Input: `SecurityCollectionKey[epoch]`.
    CollectionMetadata = "chur/v1/collection/metadata", CollectionMetadata;
    /// Encrypts collection-domain sync operations. Input: `SecurityCollectionKey[epoch]`.
    CollectionSyncOperations = "chur/v1/collection/sync-operations", CollectionMetadata;
    /// Derives a collection-domain opaque selector. Input: `SecurityCollectionKey[epoch]`.
    CollectionSyncSelector = "chur/v1/collection/sync-selector", CollectionMetadata;

    /// Seals the container manifest record. Input: `ObjectKey`.
    ObjectManifest = "chur/v1/object/manifest", ContainerStream;
    /// Seals container chunk records. Input: `ObjectKey`.
    ObjectContent = "chur/v1/object/content", ContainerStream;
    /// Protects one metadata revision. Input: `ObjectKey`.
    ObjectMetadata = "chur/v1/object/metadata", ObjectMetadata;
    /// Protects a single-record thumbnail. Input: `ObjectKey`.
    ObjectThumbnail = "chur/v1/object/thumbnail", DerivedAsset;
    /// Protects a single-record preview. Input: `ObjectKey`.
    ObjectPreview = "chur/v1/object/preview", DerivedAsset;
    /// Protects a single-record video poster frame. Input: `ObjectKey`.
    ObjectPosterFrame = "chur/v1/object/poster-frame", DerivedAsset;
    /// Protects a single-record audio waveform. Input: `ObjectKey`.
    ObjectWaveform = "chur/v1/object/waveform", DerivedAsset;
    /// Protects a single-record OCR text layer. Input: `ObjectKey`.
    ObjectOcr = "chur/v1/object/ocr", DerivedAsset;
    /// Protects a single-record face record. Input: `ObjectKey`.
    ObjectFace = "chur/v1/object/face", DerivedAsset;
    /// Protects a single-record embedding. Input: `ObjectKey`.
    ObjectEmbedding = "chur/v1/object/embedding", DerivedAsset;
    /// Seals the container final commit. Input: `ObjectKey`.
    ObjectFinalCommit = "chur/v1/object/final-commit", ContainerStream;

    /// Wraps the root under a recovery secret. Input: `RecoverySecret`.
    RecoveryRootEnvelope = "chur/v1/recovery/root-envelope", Slot;
    /// Wraps the root under a Keychain-held secret. Input: `DeviceUnlockSecret`.
    SlotAppleDeviceKek = "chur/v1/slot/apple-device-kek", Slot;
}

/// The encoded context elements of one derivation.
///
/// The type is built only by the constructors below, one per shape, so an
/// element list is written in one place and cannot be assembled field by field
/// at a call site in the wrong order.
#[derive(Clone)]
pub struct Context {
    shape: ContextShape,
    elements: Vec<u8>,
}

impl Context {
    /// `vault_id`: every root-tier label.
    #[must_use]
    pub fn vault(vault_id: &Id) -> Self {
        Self {
            shape: ContextShape::Vault,
            elements: vault_id.as_bytes().to_vec(),
        }
    }

    /// `vault_id`, `collection_id`, `collection_epoch`.
    #[must_use]
    pub fn collection_envelope(vault_id: &Id, collection_id: &Id, collection_epoch: u64) -> Self {
        let mut elements = Vec::with_capacity(40);
        elements.extend_from_slice(vault_id.as_bytes());
        elements.extend_from_slice(collection_id.as_bytes());
        elements.extend_from_slice(&collection_epoch.to_be_bytes());
        Self {
            shape: ContextShape::CollectionEnvelope,
            elements,
        }
    }

    /// `collection_id`, `collection_epoch`, `object_id`.
    #[must_use]
    pub fn object_envelope(collection_id: &Id, collection_epoch: u64, object_id: &Id) -> Self {
        let mut elements = Vec::with_capacity(40);
        elements.extend_from_slice(collection_id.as_bytes());
        elements.extend_from_slice(&collection_epoch.to_be_bytes());
        elements.extend_from_slice(object_id.as_bytes());
        Self {
            shape: ContextShape::ObjectEnvelope,
            elements,
        }
    }

    /// `collection_id`, `collection_epoch`.
    #[must_use]
    pub fn collection_metadata(collection_id: &Id, collection_epoch: u64) -> Self {
        let mut elements = Vec::with_capacity(24);
        elements.extend_from_slice(collection_id.as_bytes());
        elements.extend_from_slice(&collection_epoch.to_be_bytes());
        Self {
            shape: ContextShape::CollectionMetadata,
            elements,
        }
    }

    /// `object_id`, `stream_id`, `stream_kind`, `stream_revision`.
    #[must_use]
    pub fn container_stream(
        object_id: &Id,
        stream_id: &Id,
        stream_kind: u8,
        stream_revision: u32,
    ) -> Self {
        let mut elements = Vec::with_capacity(37);
        elements.extend_from_slice(object_id.as_bytes());
        elements.extend_from_slice(stream_id.as_bytes());
        elements.push(stream_kind);
        elements.extend_from_slice(&stream_revision.to_be_bytes());
        Self {
            shape: ContextShape::ContainerStream,
            elements,
        }
    }

    /// `object_id`, `stream_kind`, `source_content_revision`, `stream_revision`.
    #[must_use]
    pub fn derived_asset(
        object_id: &Id,
        stream_kind: u8,
        source_content_revision: u32,
        stream_revision: u32,
    ) -> Self {
        let mut elements = Vec::with_capacity(25);
        elements.extend_from_slice(object_id.as_bytes());
        elements.push(stream_kind);
        elements.extend_from_slice(&source_content_revision.to_be_bytes());
        elements.extend_from_slice(&stream_revision.to_be_bytes());
        Self {
            shape: ContextShape::DerivedAsset,
            elements,
        }
    }

    /// `object_id`, `metadata_revision`.
    #[must_use]
    pub fn object_metadata(object_id: &Id, metadata_revision: u32) -> Self {
        let mut elements = Vec::with_capacity(20);
        elements.extend_from_slice(object_id.as_bytes());
        elements.extend_from_slice(&metadata_revision.to_be_bytes());
        Self {
            shape: ContextShape::ObjectMetadata,
            elements,
        }
    }

    /// `vault_id`, `slot_id`, `slot_generation`.
    #[must_use]
    pub fn slot(vault_id: &Id, slot_id: &Id, slot_generation: u64) -> Self {
        let mut elements = Vec::with_capacity(40);
        elements.extend_from_slice(vault_id.as_bytes());
        elements.extend_from_slice(slot_id.as_bytes());
        elements.extend_from_slice(&slot_generation.to_be_bytes());
        Self {
            shape: ContextShape::Slot,
            elements,
        }
    }

    /// The element list this context carries.
    #[must_use]
    pub const fn shape(&self) -> ContextShape {
        self.shape
    }

    /// The encoded elements, without the tag and without the label.
    #[must_use]
    pub fn elements(&self) -> &[u8] {
        &self.elements
    }
}

/// Builds the HKDF `info` tuple of one derivation.
///
/// # Errors
///
/// Returns [`ChurStatus::InvalidInput`] when the context shape is not the one
/// the label takes.
pub fn info(label: Label, context: &Context) -> Result<Vec<u8>> {
    if label.shape() != context.shape() {
        return Err(Error::new(
            ChurStatus::InvalidInput,
            "context element list does not match the registered label",
        ));
    }
    Ok(Tuple::new(tag::KDF_INFO)
        .string(label.as_str())?
        .fixed(context.elements())
        .finish())
}

/// Derives a 32-byte key.
///
/// # Errors
///
/// Returns [`ChurStatus::InvalidInput`] when the context shape does not match
/// the label, and [`ChurStatus::InternalFailure`] if HKDF-Expand rejects the
/// output length, which 32 bytes never does for SHA-256.
pub fn derive(input_key: &[u8], label: Label, context: &Context) -> Result<Key> {
    let info = info(label, context)?;
    let hkdf = Hkdf::<Sha256>::new(Some(&EXTRACT_SALT), input_key);
    let mut derived = Key::zeroed();
    hkdf.expand(&info, derived.expose_mut()).map_err(|_| {
        Error::new(
            ChurStatus::InternalFailure,
            "HKDF-Expand rejected the v1 output length",
        )
    })?;
    Ok(derived)
}

/// Derives a 32-byte key from another key.
///
/// # Errors
///
/// As [`derive`].
pub fn derive_from(parent: &Key, label: Label, context: &Context) -> Result<Key> {
    derive(parent.expose(), label, context)
}

const _: () = assert!(KEY_LEN == 32);

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::panic, clippy::expect_used)]

    use super::*;

    fn id(byte: u8) -> Id {
        Id::new([byte; 16]).unwrap()
    }

    #[test]
    fn the_registry_holds_twenty_nine_labels() {
        assert_eq!(Label::ALL.len(), 29);
        assert!(Label::ALL.contains(&Label::RootSyncOperations));
        assert!(Label::ALL.contains(&Label::RootSyncSelector));
        assert!(Label::ALL.contains(&Label::CollectionSyncOperations));
        assert!(Label::ALL.contains(&Label::CollectionSyncSelector));
    }

    #[test]
    fn every_label_string_is_unique_and_follows_the_naming_rules() {
        let mut seen = std::collections::BTreeSet::new();
        for label in Label::ALL {
            let text = label.as_str();
            assert!(seen.insert(text), "duplicate label {text}");
            let segments: Vec<&str> = text.split('/').collect();
            assert_eq!(segments.len(), 4, "{text} is not four segments");
            assert_eq!(segments[0], "chur");
            assert_eq!(segments[1], "v1");
            assert!(
                matches!(
                    segments[2],
                    "root" | "collection" | "object" | "recovery" | "slot"
                ),
                "{text} names an unregistered tier"
            );
            assert!(
                text.chars()
                    .all(|c| c.is_ascii_lowercase() || c == '/' || c == '-' || c == '1'),
                "{text} is not lowercase ASCII"
            );
        }
    }

    #[test]
    fn a_context_of_the_wrong_shape_is_refused() {
        let outcome = derive(
            &[1u8; 32],
            Label::RootCatalogDatabase,
            &Context::collection_metadata(&id(2), 1),
        );
        let Err(error) = outcome else {
            panic!("a mismatched context shape was accepted")
        };
        assert_eq!(error.status(), ChurStatus::InvalidInput);
    }

    #[test]
    fn the_info_tuple_is_the_tag_the_prefixed_label_and_the_elements() {
        let context = Context::vault(&id(0x11));
        let encoded = info(Label::RootSearch, &context).unwrap();
        let label = Label::RootSearch.as_str().as_bytes();
        let mut expected = tag::KDF_INFO.to_vec();
        expected.extend_from_slice(&u32::try_from(label.len()).unwrap().to_be_bytes());
        expected.extend_from_slice(label);
        expected.extend_from_slice(&[0x11; 16]);
        assert_eq!(encoded, expected);
    }

    #[test]
    fn every_label_derives_a_distinct_key_from_one_input() {
        let input = [9u8; 32];
        let mut keys = std::collections::BTreeSet::new();
        for label in Label::ALL {
            let context = match label.shape() {
                ContextShape::Vault => Context::vault(&id(1)),
                ContextShape::CollectionEnvelope => Context::collection_envelope(&id(1), &id(2), 1),
                ContextShape::ObjectEnvelope => Context::object_envelope(&id(2), 1, &id(3)),
                ContextShape::CollectionMetadata => Context::collection_metadata(&id(2), 1),
                ContextShape::ContainerStream => Context::container_stream(&id(3), &id(4), 1, 1),
                ContextShape::DerivedAsset => Context::derived_asset(&id(3), 2, 1, 1),
                ContextShape::ObjectMetadata => Context::object_metadata(&id(3), 1),
                ContextShape::Slot => Context::slot(&id(1), &id(5), 1),
            };
            let key = derive(&input, *label, &context).unwrap();
            assert!(keys.insert(*key.expose()), "{} collides", label.as_str());
        }
        assert_eq!(keys.len(), 29);
    }

    #[test]
    fn one_label_with_two_contexts_derives_two_keys() {
        let input = [5u8; 32];
        let first = derive(&input, Label::RootCatalogDatabase, &Context::vault(&id(1))).unwrap();
        let second = derive(&input, Label::RootCatalogDatabase, &Context::vault(&id(2))).unwrap();
        assert_ne!(first.expose(), second.expose());
    }

    #[test]
    fn the_derivation_is_deterministic() {
        let context = Context::container_stream(&id(7), &id(8), 0x01, 1);
        let first = derive(&[2u8; 32], Label::ObjectContent, &context).unwrap();
        let second = derive(&[2u8; 32], Label::ObjectContent, &context).unwrap();
        assert_eq!(first.expose(), second.expose());
    }

    #[test]
    fn context_lengths_match_the_registered_element_lists() {
        assert_eq!(Context::vault(&id(1)).elements().len(), 16);
        assert_eq!(
            Context::collection_envelope(&id(1), &id(2), 1)
                .elements()
                .len(),
            40
        );
        assert_eq!(
            Context::object_envelope(&id(1), 1, &id(2)).elements().len(),
            40
        );
        assert_eq!(Context::collection_metadata(&id(1), 1).elements().len(), 24);
        assert_eq!(
            Context::container_stream(&id(1), &id(2), 1, 1)
                .elements()
                .len(),
            37
        );
        assert_eq!(Context::derived_asset(&id(1), 1, 1, 1).elements().len(), 25);
        assert_eq!(Context::object_metadata(&id(1), 1).elements().len(), 20);
        assert_eq!(Context::slot(&id(1), &id(2), 1).elements().len(), 40);
    }
}
