//! The deterministic v1 vector set.
//!
//! Every value here is a fixed test-only constant. `docs/format/TEST_VECTORS.md`
//! §3 permits deterministic keys, salts, nonces, passwords, and recovery
//! secrets inside `test-vectors/` and requires them to be clearly marked:
//!
//! ```text
//! TEST-ONLY — NEVER USE FOR REAL VAULTS
//! ```
//!
//! No production API selects deterministic randomness. This module builds its
//! bytes explicitly instead of replacing `chur_crypto::random`, so the
//! generator and a release build share no code path that could substitute one
//! for the other.

use serde_json::json;

use chur_core::limits::{ID_LEN, KEY_LEN, NONCE_LEN};
use chur_core::{Id, Result};
use chur_crypto::aead::Nonce;
use chur_crypto::commit;
use chur_crypto::kdf::{self, Context, ContextShape, Label};
use chur_crypto::password::{self, Argon2Params};
use chur_crypto::recovery;
use chur_crypto::secret::Key;
use chur_crypto::tuple::{Tuple, tag};
use chur_format::codec::Writer;
use chur_format::constants::{MediaClass, SlotType, StreamKind, VaultState};
use chur_format::container::{
    CanonicalManifest, MediaProperties, NONCE_PREFIX_LEN, PublicPreamble, StreamIdentity,
    encode_container,
};
use chur_format::descriptor::{
    CatalogDescriptor, KeySlotDescriptor, MigrationDescriptor, ObjectStoreDescriptor,
    VaultDescriptor,
};
use chur_format::envelope::{CollectionKeyEnvelope, ObjectKeyEnvelope};
use chur_format::slot::{
    AndroidKeystoreSlotBody, AppleKeychainSlotBody, PasswordSlotBody, RecoverySlotBody, SlotBinding,
};

use crate::manifest::{Vector, VectorBuilder, hex_of, number};

/// The marker every generated `README` and manifest note carries.
pub const TEST_ONLY: &str = "TEST-ONLY — NEVER USE FOR REAL VAULTS";

/// A fixed identifier built from one repeated byte.
fn id(byte: u8) -> Result<Id> {
    Id::new([byte; ID_LEN])
}

/// A fixed key built from one repeated byte.
fn key(byte: u8) -> Key {
    Key::new([byte; KEY_LEN])
}

/// A fixed nonce built from one repeated byte.
fn nonce(byte: u8) -> Nonce {
    Nonce::new([byte; NONCE_LEN])
}

/// A repeatable plaintext pattern of the given length.
fn pattern(length: usize) -> Vec<u8> {
    (0..length).map(|index| (index % 251) as u8).collect()
}

/// The chunk size every container vector uses: the v1 minimum, 64 KiB.
const VECTOR_CHUNK_SIZE: u32 = 65_536;

/// Builds the whole vector set, sorted by `vector_id`.
///
/// # Errors
///
/// Returns an error when a construction the vectors exercise fails, which for
/// fixed inputs means the library is broken rather than the input.
pub fn build_all() -> Result<Vec<Vector>> {
    let mut vectors = Vec::new();
    canonical_encoding(&mut vectors)?;
    key_derivations(&mut vectors)?;
    password_slots(&mut vectors)?;
    recovery_slots(&mut vectors)?;
    platform_slots(&mut vectors)?;
    vault_descriptors(&mut vectors)?;
    collection_envelopes(&mut vectors)?;
    object_key_envelopes(&mut vectors)?;
    object_containers(&mut vectors)?;
    vectors.sort_by(|left, right| left.entry.vector_id.cmp(&right.entry.vector_id));
    Ok(vectors)
}

const ENCODING_SPEC: &str = "docs/format/CANONICAL_ENCODING_V1.md";
const CONTAINER_SPEC: &str = "docs/format/OBJECT_CONTAINER_V1.md";
const DESCRIPTOR_SPEC: &str = "docs/format/VAULT_DESCRIPTOR_V1.md";
const SLOT_SPEC: &str = "docs/format/KEY_SLOT_BODIES_V1.md";
const HIERARCHY_SPEC: &str = "docs/security/KEY_HIERARCHY.md";
const COLLECTION_ENVELOPE_SPEC: &str = "docs/format/COLLECTION_KEY_ENVELOPE_V1.md";
const OBJECT_ENVELOPE_SPEC: &str = "docs/format/OBJECT_KEY_ENVELOPE_V1.md";
const RECOVERY_SPEC: &str = "docs/security/RECOVERY.md";

// ---------------------------------------------------------------------------
// Canonical encoding
// ---------------------------------------------------------------------------

fn canonical_encoding(out: &mut Vec<Vector>) -> Result<()> {
    let mut writer = Writer::new();
    writer
        .u8(0)
        .u8(u8::MAX)
        .u16(0)
        .u16(u16::MAX)
        .u32(0)
        .u32(u32::MAX)
        .u64(0)
        .u64(u64::MAX)
        .bool(false)
        .bool(true)
        .presence(false)
        .presence(true);
    let boundaries = writer.finish();
    out.push(
        VectorBuilder::accept(
            "canonical-encoding",
            "canonical-encoding-v1-primitive-boundaries",
            ENCODING_SPEC,
            "2",
            "Every fixed-width primitive at both bounds, then a boolean and an optional presence byte in both states.",
        )
        .input("field_order", json!([
            "u8 minimum", "u8 maximum", "u16 minimum", "u16 maximum",
            "u32 minimum", "u32 maximum", "u64 minimum", "u64 maximum",
            "boolean false", "boolean true", "optional absent", "optional present"
        ]))
        .expect_bytes("encoded", &boundaries)
        .expect("encoded_length", json!(boundaries.len()))
        .build(),
    );

    let mut writer = Writer::new();
    writer.variable(b"")?.variable(b"chur")?;
    let variable = writer.finish();
    out.push(
        VectorBuilder::accept(
            "canonical-encoding",
            "canonical-encoding-v1-empty-and-short-variable-elements",
            ENCODING_SPEC,
            "2",
            "A zero-length variable element carries its u32 length and no bytes; a short one follows it.",
        )
        .input("elements", json!(["", "chur"]))
        .expect_bytes("encoded", &variable)
        .build(),
    );

    out.push(
        VectorBuilder::accept(
            "canonical-encoding",
            "canonical-encoding-v1-maximum-identifier",
            ENCODING_SPEC,
            "8",
            "The all-ones identifier is a valid 16-byte value; only the all-zero value is reserved.",
        )
        .input_bytes("identifier", &[0xff; ID_LEN])
        .expect("accepted", json!(true))
        .build(),
    );

    let example = Tuple::new(b"CHUR\0EXAMPLE\0TUPLE\0V1")
        .u16(0x0001)
        .id(&id(0xaa)?)
        .string("label")?
        .finish();
    out.push(
        VectorBuilder::accept(
            "canonical-encoding",
            "canonical-encoding-v1-illustrative-tuple",
            ENCODING_SPEC,
            "7.1",
            "The worked tuple example: a bare tag, a u16, a 16-byte fixed element, then a u32-prefixed string.",
        )
        .input("domain_tag", json!(hex_of(b"CHUR\0EXAMPLE\0TUPLE\0V1")))
        .input("suite_id", json!(1))
        .input_bytes("object_id", &[0xaa; ID_LEN])
        .input("label", json!("label"))
        .expect_bytes("encoded", &example)
        .expect("encoded_length", json!(example.len()))
        .note("The tag is unregistered and illustrative; no record uses it.")
        .build(),
    );

    out.push(
        VectorBuilder::reject(
            "canonical-encoding",
            "canonical-encoding-v1-all-zero-identifier",
            ENCODING_SPEC,
            "8",
            "The all-zero identifier is reserved as invalid.",
            "INVALID_INPUT",
        )
        .input_bytes("identifier", &[0x00; ID_LEN])
        .build(),
    );
    out.push(
        VectorBuilder::reject(
            "canonical-encoding",
            "canonical-encoding-v1-boolean-byte-two",
            ENCODING_SPEC,
            "11",
            "A boolean byte other than 0x00 or 0x01 is not canonical.",
            "NON_CANONICAL_ENCODING",
        )
        .input_bytes("encoded", &[0x02])
        .build(),
    );
    out.push(
        VectorBuilder::reject(
            "canonical-encoding",
            "canonical-encoding-v1-invalid-utf8-string",
            ENCODING_SPEC,
            "3",
            "A string element that is not valid UTF-8 is rejected.",
            "NON_CANONICAL_ENCODING",
        )
        .input_bytes("encoded", &[0x00, 0x00, 0x00, 0x02, 0xff, 0xfe])
        .build(),
    );
    out.push(
        VectorBuilder::reject(
            "canonical-encoding",
            "canonical-encoding-v1-declared-length-over-the-limit",
            ENCODING_SPEC,
            "10",
            "A declared element length above the parser limit allocates nothing.",
            "RESOURCE_LIMIT_EXCEEDED",
        )
        .input_bytes("encoded", &[0xee, 0x6b, 0x28, 0x00, 0x00, 0x00, 0x00, 0x00])
        .input("parser_limit", json!(64))
        .build(),
    );
    Ok(())
}

// ---------------------------------------------------------------------------
// Key derivations
// ---------------------------------------------------------------------------

fn label_slug(label: Label) -> String {
    label.as_str().replace('/', "-").replace("chur-v1-", "")
}

fn sample_context(shape: ContextShape) -> Result<Context> {
    Ok(match shape {
        ContextShape::Vault => Context::vault(&id(0x11)?),
        ContextShape::CollectionEnvelope => Context::collection_envelope(&id(0x11)?, &id(0x22)?, 1),
        ContextShape::ObjectEnvelope => Context::object_envelope(&id(0x22)?, 1, &id(0x33)?),
        ContextShape::CollectionMetadata => Context::collection_metadata(&id(0x22)?, 1),
        ContextShape::ContainerStream => {
            Context::container_stream(&id(0x33)?, &id(0x44)?, StreamKind::Original.value(), 1)
        }
        ContextShape::DerivedAsset => {
            Context::derived_asset(&id(0x33)?, StreamKind::ThumbnailSmall.value(), 1, 1)
        }
        ContextShape::ObjectMetadata => Context::object_metadata(&id(0x33)?, 1),
        ContextShape::Slot => Context::slot(&id(0x11)?, &id(0x55)?, 1),
    })
}

fn key_derivations(out: &mut Vec<Vector>) -> Result<()> {
    let input_key = [0x99u8; KEY_LEN];
    for label in Label::ALL {
        let context = sample_context(label.shape())?;
        let info = kdf::info(*label, &context)?;
        let derived = kdf::derive(&input_key, *label, &context)?;
        out.push(
            VectorBuilder::accept(
                "key-derivation",
                &format!("key-derivation-v1-{}", label_slug(*label)),
                HIERARCHY_SPEC,
                "3",
                &format!(
                    "HKDF-SHA-256 under `{}` with its registered context.",
                    label.as_str()
                ),
            )
            .input_bytes("input_key", &input_key)
            .input("label", json!(label.as_str()))
            .input_bytes("context_elements", context.elements())
            .expect_bytes("info", &info)
            .expect("info_length", json!(info.len()))
            .expect_bytes("derived_key", derived.expose())
            .note(TEST_ONLY)
            .build(),
        );
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// Key slots
// ---------------------------------------------------------------------------

fn password_binding() -> Result<SlotBinding> {
    Ok(SlotBinding::v1(id(0x11)?, id(0x55)?, SlotType::Password, 1))
}

fn password_slots(out: &mut Vec<Vector>) -> Result<()> {
    let binding = password_binding()?;
    let params = Argon2Params::v1_default();
    let salt = vec![0x77u8; 16];
    let password = "correct horse battery staple";
    let body = PasswordSlotBody::seal(
        &binding,
        password.as_bytes(),
        salt.clone(),
        params,
        nonce(0x88),
        &key(0x5a),
    )?;
    let aad = PasswordSlotBody::aad(&binding, params, &salt)?;
    out.push(
        VectorBuilder::accept(
            "password-slot",
            "password-slot-v1-frozen-floor-profile",
            SLOT_SPEC,
            "3",
            "A password slot at the frozen Argon2id floor: 65536 KiB, 3 iterations, parallelism 1, 16-byte salt.",
        )
        .input("password", json!(password))
        .input_bytes("password_bytes", password.as_bytes())
        .input_bytes("salt", &salt)
        .input_bytes("slot_nonce", nonce(0x88).as_bytes())
        .input_bytes("vault_root_secret", key(0x5a).expose())
        .input("memory_kib", json!(params.memory_kib()))
        .input("iterations", json!(params.iterations()))
        .input("parallelism", json!(params.parallelism()))
        .expect_bytes("slot_aad", &aad)
        .expect("slot_aad_length", json!(aad.len()))
        .expect_single_fixture("slot_body", &body.encode())
        .expect("slot_body_length", json!(body.encode().len()))
        .note(TEST_ONLY)
        .build(),
    );

    // The composed and decomposed spellings of one accented character are
    // different passwords, because profile 0x0001 applies no normalization.
    let composed = "é";
    let decomposed = "e\u{0301}";
    let composed_kek = password::derive_kek(composed.as_bytes(), &salt, params)?;
    let decomposed_kek = password::derive_kek(decomposed.as_bytes(), &salt, params)?;
    out.push(
        VectorBuilder::accept(
            "password-slot",
            "password-slot-v1-unicode-combining-no-normalization",
            "docs/security/PASSWORD_PROFILE.md",
            "3",
            "Profile 0x0001 applies no normalization, so U+00E9 and U+0065 U+0301 are different passwords.",
        )
        .input_bytes("composed_password_bytes", composed.as_bytes())
        .input_bytes("decomposed_password_bytes", decomposed.as_bytes())
        .input_bytes("salt", &salt)
        .expect_bytes("composed_password_kek", composed_kek.expose())
        .expect_bytes("decomposed_password_kek", decomposed_kek.expose())
        .expect("keys_differ", json!(true))
        .note(TEST_ONLY)
        .build(),
    );

    let mut lowered = body.encode();
    lowered[4..8].copy_from_slice(&1024u32.to_be_bytes());
    out.push(
        VectorBuilder::reject(
            "password-slot",
            "password-slot-v1-memory-below-the-floor",
            SLOT_SPEC,
            "8",
            "A memory cost under the frozen floor is refused before Argon2 allocates anything.",
            "RESOURCE_LIMIT_EXCEEDED",
        )
        .input_bytes("slot_body", &lowered)
        .build(),
    );

    let mut trailing = body.encode();
    trailing.push(0x00);
    out.push(
        VectorBuilder::reject(
            "password-slot",
            "password-slot-v1-trailing-byte",
            SLOT_SPEC,
            "8",
            "A body length that exceeds what its own fields imply is rejected.",
            "NON_CANONICAL_ENCODING",
        )
        .input_bytes("slot_body", &trailing)
        .build(),
    );
    Ok(())
}

fn recovery_slots(out: &mut Vec<Vector>) -> Result<()> {
    let binding = SlotBinding::v1(id(0x11)?, id(0x56)?, SlotType::Recovery, 1);
    let recovery_secret = key(0x99);
    let body = RecoverySlotBody::seal(&binding, &recovery_secret, nonce(0xaa), &key(0x5a))?;
    let aad = RecoverySlotBody::aad(&binding);
    out.push(
        VectorBuilder::accept(
            "recovery-slot",
            "recovery-slot-v1-generation-one",
            SLOT_SPEC,
            "4",
            "A recovery slot: no salt, no password KDF, RecoveryKEK derived from the 32-byte secret.",
        )
        .input_bytes("recovery_secret", recovery_secret.expose())
        .input_bytes("slot_nonce", nonce(0xaa).as_bytes())
        .input_bytes("vault_root_secret", key(0x5a).expose())
        .expect_bytes("slot_aad", &aad)
        .expect("slot_aad_length", json!(aad.len()))
        .expect_single_fixture("slot_body", &body.encode())
        .expect("slot_body_length", json!(body.encode().len()))
        .note(TEST_ONLY)
        .build(),
    );

    let phrase = recovery::to_phrase(&recovery_secret);
    let denormalized = format!("  {}  ", phrase.to_uppercase().replace(' ', "\u{00a0} "));
    out.push(
        VectorBuilder::accept(
            "recovery-slot",
            "recovery-slot-v1-phrase-round-trip",
            RECOVERY_SPEC,
            "2",
            "32 bytes to 24 BIP-39 English words and back, and one denormalized re-entry that normalizes to the same words.",
        )
        .input_bytes("recovery_secret", recovery_secret.expose())
        .input("denormalized_re_entry", json!(denormalized))
        .expect("phrase", json!(phrase.as_str()))
        .expect("word_count", json!(recovery::WORD_COUNT))
        .expect("qr_payload", json!(recovery::to_qr_payload(&recovery_secret).as_str()))
        .expect("normalized_re_entry", json!(recovery::normalize(&denormalized)))
        .note(TEST_ONLY)
        .build(),
    );

    let words: Vec<&str> = recovery::encode(&recovery_secret);
    let mut broken = words.clone();
    broken[23] = if words[23] == "abandon" {
        "ability"
    } else {
        "abandon"
    };
    out.push(
        VectorBuilder::reject(
            "recovery-slot",
            "recovery-slot-v1-checksum-mismatch",
            RECOVERY_SPEC,
            "2",
            "A phrase whose last word changes fails the BIP-39 checksum and no slot unwrap is attempted.",
            "INVALID_INPUT",
        )
        .input("phrase", json!(broken.join(" ")))
        .build(),
    );

    let mut short = words.clone();
    short.truncate(23);
    out.push(
        VectorBuilder::reject(
            "recovery-slot",
            "recovery-slot-v1-twenty-three-words",
            RECOVERY_SPEC,
            "2",
            "A v1 recovery phrase is exactly 24 words.",
            "INVALID_INPUT",
        )
        .input("phrase", json!(short.join(" ")))
        .build(),
    );
    Ok(())
}

fn platform_slots(out: &mut Vec<Vector>) -> Result<()> {
    let keychain_binding = SlotBinding::v1(id(0x11)?, id(0x57)?, SlotType::AppleKeychain, 1);
    let device_unlock_secret = key(0xbb);
    let body = AppleKeychainSlotBody::seal(
        &keychain_binding,
        &device_unlock_secret,
        id(0xcc)?,
        nonce(0xdd),
        &key(0x5a),
    )?;
    let aad = AppleKeychainSlotBody::aad(&keychain_binding);
    let kek = AppleKeychainSlotBody::kek(&device_unlock_secret, &keychain_binding)?;
    out.push(
        VectorBuilder::accept(
            "keychain-slot",
            "keychain-slot-v1-device-unlock-secret",
            SLOT_SPEC,
            "6",
            "The Keychain family: a device-held secret, AppleDeviceKEK derived in Rust, and the root wrapped there.",
        )
        .input_bytes("device_unlock_secret", device_unlock_secret.expose())
        .input_bytes("keychain_item_id", id(0xcc)?.as_bytes())
        .input_bytes("slot_nonce", nonce(0xdd).as_bytes())
        .input_bytes("vault_root_secret", key(0x5a).expose())
        .expect_bytes("apple_device_kek", kek.expose())
        .expect_bytes("slot_aad", &aad)
        .expect("slot_aad_length", json!(aad.len()))
        .expect_single_fixture("slot_body", &body.encode())
        .note(TEST_ONLY)
        .build(),
    );

    let keystore_binding = SlotBinding::v1(id(0x11)?, id(0x58)?, SlotType::AndroidKeystore, 1);
    let alias = vec![0xeeu8; 16];
    let keystore_aad = AndroidKeystoreSlotBody::aad(&keystore_binding, &alias)?;
    let keystore_body =
        AndroidKeystoreSlotBody::new(&keystore_binding, alias.clone(), [0x0f; 12], [0x01; 48])?;
    out.push(
        VectorBuilder::accept(
            "keystore-slot",
            "keystore-slot-v1-sixteen-byte-alias",
            SLOT_SPEC,
            "5",
            "The Android family's body framing and the AAD the platform cipher receives.",
        )
        .input_bytes("alias", &alias)
        .input_bytes("gcm_nonce", &[0x0f; 12])
        .input_bytes("wrapped_root_secret", &[0x01; 48])
        .expect_bytes("slot_aad", &keystore_aad)
        .expect("slot_aad_length", json!(keystore_aad.len()))
        .expect_single_fixture("slot_body", &keystore_body.encode())
        .note(
            "The wrapped bytes are a fixed placeholder: this family's AEAD runs in the platform \
             Keystore, so no Rust implementation reproduces them. TEST-ONLY — NEVER USE FOR REAL VAULTS",
        )
        .build(),
    );
    Ok(())
}

// ---------------------------------------------------------------------------
// Vault descriptors
// ---------------------------------------------------------------------------

fn minimal_descriptor() -> Result<VaultDescriptor> {
    Ok(VaultDescriptor {
        vault_id: id(0x11)?,
        descriptor_generation: 1,
        state: VaultState::Active,
        catalog: CatalogDescriptor {
            opaque_catalog_path_id: id(0x12)?,
            catalog_generation: 1,
            catalog_header_commitment: [0x13; 32],
        },
        object_store: ObjectStoreDescriptor::v1(id(0x14)?),
        key_slots: vec![KeySlotDescriptor::v1(
            id(0x55)?,
            SlotType::Password,
            1,
            vec![0xaa; 16],
        )?],
        migration: None,
    })
}

fn vault_descriptors(out: &mut Vec<Vector>) -> Result<()> {
    let root = key(0x5a);
    let descriptor = minimal_descriptor()?;
    let encoded = descriptor.encode(&root)?;
    out.push(
        VectorBuilder::accept(
            "vault-descriptor",
            "vault-descriptor-v1-minimal-password-slot",
            DESCRIPTOR_SPEC,
            "13",
            "The smallest v1 descriptor: the head, one password slot with a 16-byte body, no migration, and the tag.",
        )
        .input_bytes("vault_root_secret", root.expose())
        .input_bytes("vault_id", id(0x11)?.as_bytes())
        .input("descriptor_generation", json!(1))
        .input("state", json!("ACTIVE"))
        .expect_single_fixture("descriptor", &encoded)
        .expect("descriptor_length", json!(encoded.len()))
        .expect_bytes("descriptor_auth_tag", &encoded[encoded.len() - 32..])
        .decoded("key_slot_count", json!(1))
        .note(TEST_ONLY)
        .build(),
    );

    let mut migrating = minimal_descriptor()?;
    migrating.state = VaultState::Migrating;
    migrating.descriptor_generation = 2;
    migrating.migration = Some(MigrationDescriptor {
        from_descriptor_version: 1,
        to_descriptor_version: 1,
        from_catalog_format_version: 1,
        to_catalog_format_version: 1,
        migration_generation: 1,
        checkpoint_id: id(0x15)?,
    });
    let migrating_bytes = migrating.encode(&root)?;
    out.push(
        VectorBuilder::accept(
            "vault-descriptor",
            "vault-descriptor-v1-migrating-state",
            DESCRIPTOR_SPEC,
            "2.2",
            "MIGRATING carries the 32-byte migration descriptor, so the encoding is 33 bytes longer than the minimum.",
        )
        .input_bytes("vault_root_secret", root.expose())
        .input("state", json!("MIGRATING"))
        .expect_single_fixture("descriptor", &migrating_bytes)
        .expect("descriptor_length", json!(migrating_bytes.len()))
        .note(TEST_ONLY)
        .build(),
    );

    let mut wrong_magic = encoded.clone();
    wrong_magic[4] = b'X';
    out.push(
        VectorBuilder::reject(
            "vault-descriptor",
            "vault-descriptor-v1-wrong-magic",
            DESCRIPTOR_SPEC,
            "2.1",
            "A magic that differs in one byte is rejected before any credential is used.",
            "VAULT_CORRUPT",
        )
        .input_bytes("descriptor", &wrong_magic)
        .build(),
    );

    let mut short_tag = encoded.clone();
    short_tag.truncate(encoded.len() - 1);
    out.push(
        VectorBuilder::reject(
            "vault-descriptor",
            "vault-descriptor-v1-truncated-tag",
            DESCRIPTOR_SPEC,
            "8",
            "A descriptor whose declared length does not match its bytes fails step 1.",
            "VAULT_CORRUPT",
        )
        .input_bytes("descriptor", &short_tag)
        .build(),
    );

    let mut wrong_root = minimal_descriptor()?;
    wrong_root.descriptor_generation = 1;
    out.push(
        VectorBuilder::reject(
            "vault-descriptor",
            "vault-descriptor-v1-wrong-candidate-root",
            DESCRIPTOR_SPEC,
            "8",
            "A structurally valid descriptor under a wrong root is AUTHENTICATION_FAILED, never VAULT_CORRUPT.",
            "AUTHENTICATION_FAILED",
        )
        .input_bytes("descriptor", &encoded)
        .input_bytes("candidate_root", key(0x5b).expose())
        .note(TEST_ONLY)
        .build(),
    );
    Ok(())
}

// ---------------------------------------------------------------------------
// Key envelopes
// ---------------------------------------------------------------------------

fn collection_envelopes(out: &mut Vec<Vector>) -> Result<()> {
    let root = key(0x5a);
    let collection_key = key(0x44);
    let envelope = CollectionKeyEnvelope::seal(
        &root,
        id(0x11)?,
        id(0x22)?,
        1,
        1,
        nonce(0x33),
        &collection_key,
    )?;
    let encoded = envelope.encode();
    let wrapping = CollectionKeyEnvelope::wrapping_key(&root, &id(0x11)?, &id(0x22)?, 1)?;
    out.push(
        VectorBuilder::accept(
            "collection-envelope",
            "collection-envelope-v1-epoch-one-generation-one",
            COLLECTION_ENVELOPE_SPEC,
            "1",
            "A collection key wrapped under the root-derived envelope key for epoch 1, generation 1.",
        )
        .input_bytes("vault_root_secret", root.expose())
        .input_bytes("security_collection_key", collection_key.expose())
        .input_bytes("vault_id", id(0x11)?.as_bytes())
        .input_bytes("collection_id", id(0x22)?.as_bytes())
        .input("collection_epoch", number(1))
        .input("envelope_generation", number(1))
        .input_bytes("nonce", nonce(0x33).as_bytes())
        .expect_bytes("collection_envelope_key", wrapping.expose())
        .expect_bytes("aad", &envelope.aad())
        .expect("aad_length", json!(envelope.aad().len()))
        .expect_single_fixture("envelope", &encoded)
        .expect("envelope_length", json!(encoded.len()))
        .note(TEST_ONLY)
        .build(),
    );

    let mut foreign = encoded.clone();
    foreign[0x06] = 0x12;
    out.push(
        VectorBuilder::reject(
            "collection-envelope",
            "collection-envelope-v1-foreign-vault-identity",
            COLLECTION_ENVELOPE_SPEC,
            "3",
            "Changing the vault identity changes both the wrapping key and the AAD, so the envelope does not open.",
            "OBJECT_CORRUPT",
        )
        .input_bytes("envelope", &foreign)
        .input_bytes("vault_root_secret", root.expose())
        .note(TEST_ONLY)
        .build(),
    );
    Ok(())
}

fn object_key_envelopes(out: &mut Vec<Vector>) -> Result<()> {
    let collection_key = key(0x44);
    let object_key = key(0x77);
    let envelope = ObjectKeyEnvelope::seal(
        &collection_key,
        id(0x11)?,
        id(0x22)?,
        1,
        id(0x33)?,
        1,
        nonce(0x66),
        &object_key,
    )?;
    let encoded = envelope.encode();
    let wrapping = ObjectKeyEnvelope::wrapping_key(&collection_key, &id(0x22)?, 1, &id(0x33)?)?;
    out.push(
        VectorBuilder::accept(
            "object-key-envelope",
            "object-key-envelope-v1-epoch-one-generation-one",
            OBJECT_ENVELOPE_SPEC,
            "1",
            "An object key wrapped under the collection-derived envelope key for epoch 1, generation 1.",
        )
        .input_bytes("security_collection_key", collection_key.expose())
        .input_bytes("object_key", object_key.expose())
        .input_bytes("vault_id", id(0x11)?.as_bytes())
        .input_bytes("collection_id", id(0x22)?.as_bytes())
        .input("collection_epoch", number(1))
        .input_bytes("object_id", id(0x33)?.as_bytes())
        .input("envelope_generation", number(1))
        .input_bytes("nonce", nonce(0x66).as_bytes())
        .expect_bytes("object_envelope_key", wrapping.expose())
        .expect_bytes("aad", &envelope.aad())
        .expect("aad_length", json!(envelope.aad().len()))
        .expect_single_fixture("envelope", &encoded)
        .expect("envelope_length", json!(encoded.len()))
        .note(TEST_ONLY)
        .build(),
    );

    let mut unsupported = encoded.clone();
    unsupported[5] = 0x02;
    out.push(
        VectorBuilder::reject(
            "object-key-envelope",
            "object-key-envelope-v1-unsupported-suite",
            OBJECT_ENVELOPE_SPEC,
            "1",
            "An unsupported suite identifier is refused before the AEAD runs.",
            "UNSUPPORTED_SUITE",
        )
        .input_bytes("envelope", &unsupported)
        .build(),
    );
    Ok(())
}

// ---------------------------------------------------------------------------
// Object containers
// ---------------------------------------------------------------------------

fn container_identity() -> Result<StreamIdentity> {
    Ok(StreamIdentity {
        object_id: id(0x33)?,
        stream_id: id(0x34)?,
        stream_kind: StreamKind::Original,
        stream_revision: 1,
    })
}

fn container_manifest() -> Result<CanonicalManifest> {
    CanonicalManifest::new(
        container_identity()?,
        None,
        VECTOR_CHUNK_SIZE,
        [0x35; NONCE_PREFIX_LEN],
        1,
        MediaProperties::new(MediaClass::Image, 4032, 3024, 0)?,
    )
}

fn container_case(
    out: &mut Vec<Vector>,
    vector_id: &str,
    section: &str,
    purpose: &str,
    plaintext: &[u8],
) -> Result<Vec<u8>> {
    let object_key = key(0x77);
    let encoded = encode_container(
        &object_key,
        container_manifest()?,
        nonce(0x36),
        plaintext,
        nonce(0x37),
        1,
    )?;
    let preamble = PublicPreamble::decode(&encoded[..PublicPreamble::LEN])?;
    let chunk_count = plaintext.len().div_ceil(VECTOR_CHUNK_SIZE as usize);
    out.push(
        VectorBuilder::accept("object", vector_id, CONTAINER_SPEC, section, purpose)
            .input_bytes("object_key", object_key.expose())
            .input_bytes("object_id", id(0x33)?.as_bytes())
            .input_bytes("stream_id", id(0x34)?.as_bytes())
            .input("stream_kind", json!(StreamKind::Original.value()))
            .input("stream_revision", json!(1))
            .input("chunk_size", json!(VECTOR_CHUNK_SIZE))
            .input_bytes("nonce_prefix", &[0x35; NONCE_PREFIX_LEN])
            .input_bytes("manifest_nonce", nonce(0x36).as_bytes())
            .input_bytes("commit_nonce", nonce(0x37).as_bytes())
            .input("plaintext_length", number(plaintext.len() as u64))
            .input("plaintext_pattern", json!("byte i equals i modulo 251"))
            .expect_single_fixture("container", &encoded)
            .expect("container_length", number(encoded.len() as u64))
            .expect(
                "manifest_record_length",
                json!(preamble.manifest_record_length()),
            )
            .decoded("chunk_count", number(chunk_count as u64))
            .decoded("total_plaintext_length", number(plaintext.len() as u64))
            .note(TEST_ONLY)
            .build(),
    );
    Ok(encoded)
}

fn object_containers(out: &mut Vec<Vector>) -> Result<()> {
    container_case(
        out,
        "object-v1-zero-byte",
        "13",
        "A zero-byte stream: no chunk records, and the ordered commitment is the hash of its domain tag alone.",
        b"",
    )?;
    let one_chunk = container_case(
        out,
        "object-v1-one-partial-chunk",
        "13",
        "One chunk shorter than the chunk size, which is therefore also the last chunk.",
        &pattern(1000),
    )?;
    container_case(
        out,
        "object-v1-exact-multiple-of-chunk-size",
        "13",
        "An exact multiple of the chunk size writes no zero-length trailing record.",
        &pattern(2 * VECTOR_CHUNK_SIZE as usize),
    )?;
    container_case(
        out,
        "object-v1-two-chunks-partial-final",
        "8",
        "Two chunks and a short third, the canonical chunking every reader recomputes.",
        &pattern(2 * VECTOR_CHUNK_SIZE as usize + 17),
    )?;

    out.push(
        VectorBuilder::accept(
            "object",
            "object-v1-zero-chunk-ordered-commitment",
            CONTAINER_SPEC,
            "10",
            "The ordered chunk commitment of a zero-chunk object is BLAKE3-256 of the domain tag alone.",
        )
        .input("domain_tag", json!(hex_of(tag::OBJECT_ORDERED_COMMITMENT)))
        .expect_bytes(
            "ordered_chunk_commitment",
            &commit::commit(tag::OBJECT_ORDERED_COMMITMENT, &[]),
        )
        .build(),
    );

    let truncated = &one_chunk[..one_chunk.len() - (32 + 144)];
    out.push(
        VectorBuilder::reject(
            "object",
            "object-v1-missing-final-commit",
            CONTAINER_SPEC,
            "15",
            "A container whose chunk records authenticate but whose final commit is absent is incomplete, not corrupt.",
            "OBJECT_INCOMPLETE",
        )
        .input_bytes("container", truncated)
        .input_bytes("object_key", key(0x77).expose())
        .note(TEST_ONLY)
        .build(),
    );

    let mut trailing = one_chunk.clone();
    trailing.push(0x00);
    out.push(
        VectorBuilder::reject(
            "object",
            "object-v1-trailing-byte-after-final-commit",
            CONTAINER_SPEC,
            "11",
            "No bytes may follow the final commit record.",
            "OBJECT_CORRUPT",
        )
        .input_bytes("container", &trailing)
        .build(),
    );

    let mut reserved = one_chunk.clone();
    reserved[0x1a] = 0x01;
    out.push(
        VectorBuilder::reject(
            "object",
            "object-v1-non-zero-preamble-reserved",
            CONTAINER_SPEC,
            "3",
            "A fixed preamble field holding any other value is OBJECT_CORRUPT and is never ignored.",
            "OBJECT_CORRUPT",
        )
        .input_bytes("container", &reserved)
        .build(),
    );

    let mut forged = one_chunk.clone();
    let first_chunk = PublicPreamble::LEN
        + one_chunk[0x14..0x18]
            .iter()
            .fold(0usize, |acc, byte| (acc << 8) | *byte as usize);
    forged[first_chunk + 12..first_chunk + 16].copy_from_slice(&99u32.to_be_bytes());
    out.push(
        VectorBuilder::reject(
            "object",
            "object-v1-forged-chunk-plaintext-length",
            CONTAINER_SPEC,
            "8",
            "A chunk header whose ciphertext length is not the plaintext length plus one tag is rejected without a key.",
            "OBJECT_CORRUPT",
        )
        .input_bytes("container", &forged)
        .build(),
    );

    let mut wrong_suite = one_chunk;
    wrong_suite[0x0d] = 0x02;
    out.push(
        VectorBuilder::reject(
            "object",
            "object-v1-unsupported-suite",
            CONTAINER_SPEC,
            "3",
            "An unknown suite fails as UNSUPPORTED_SUITE rather than as corruption.",
            "UNSUPPORTED_SUITE",
        )
        .input_bytes("container", &wrong_suite)
        .build(),
    );
    Ok(())
}
