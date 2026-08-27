//! Parser limits, gathered from the specification that owns each one.
//!
//! `docs/adr/0020-set-the-v1-parser-limits.md` requires every v1 parser to
//! reject an out-of-range declared value before it allocates or derives. The
//! constants live in one module so a bound cannot be written twice with two
//! values, and each carries the section that owns it.
//!
//! A constant here never invents a bound. If a value disagrees with its cited
//! section, the section is right and this module is a defect.

/// Length of every Chur identifier, `docs/format/CANONICAL_ENCODING_V1.md` §8.
pub const ID_LEN: usize = 16;

/// Length of every symmetric key in the v1 hierarchy.
pub const KEY_LEN: usize = 32;

/// Length of a BLAKE3-256 output.
pub const COMMITMENT_LEN: usize = 32;

/// Length of an XChaCha20-Poly1305 nonce.
pub const NONCE_LEN: usize = 24;

/// Length of an AES-GCM nonce, used only by the Android Keystore slot.
pub const GCM_NONCE_LEN: usize = 12;

/// Length of a Poly1305 or GCM authentication tag.
pub const TAG_LEN: usize = 16;

/// Length of a wrapped 32-byte key: the key plus its tag.
pub const WRAPPED_KEY_LEN: usize = KEY_LEN + TAG_LEN;

/// Eight-byte magic shared prefix, `docs/format/CANONICAL_ENCODING_V1.md` §15.1.
pub const MAGIC_LEN: usize = 8;

/// Limits of `docs/format/OBJECT_CONTAINER_V1.md` §16.
pub mod container {
    /// Exact length of `PublicPreambleV1`, §3.
    pub const PREAMBLE_LEN: usize = 28;
    /// Smallest accepted `manifest_record_length`, §3.
    pub const MANIFEST_RECORD_MIN: u32 = 40;
    /// Largest accepted `manifest_record_length`, §3.
    pub const MANIFEST_RECORD_MAX: u32 = 65_536;
    /// Exact length of `CanonicalManifest` for an original stream, §5.
    pub const CANONICAL_MANIFEST_ORIGINAL_LEN: usize = 85;
    /// Exact length of `CanonicalManifest` for a derived stream, §5.
    pub const CANONICAL_MANIFEST_DERIVED_LEN: usize = 89;
    /// Exact length of the `ChunkRecordV1` header, §8.
    pub const CHUNK_HEADER_LEN: usize = 20;
    /// Exact length of the `FinalCommitRecordV1` header, §11.
    pub const COMMIT_HEADER_LEN: usize = 32;
    /// Exact length of `CanonicalFinalCommit`, §11.
    pub const CANONICAL_FINAL_COMMIT_LEN: usize = 128;
    /// Smallest accepted `commit_ciphertext_length`, §11.
    pub const COMMIT_CIPHERTEXT_MIN: u32 = 16;
    /// Largest accepted `commit_ciphertext_length`, §11.
    pub const COMMIT_CIPHERTEXT_MAX: u32 = 4_096;
    /// Smallest accepted `chunk_size`, 64 KiB, §16.
    pub const CHUNK_SIZE_MIN: u32 = 65_536;
    /// Largest accepted `chunk_size`, 8 MiB, §16.
    pub const CHUNK_SIZE_MAX: u32 = 8_388_608;
    /// Required whole multiple of every `chunk_size`, §16.
    pub const CHUNK_SIZE_MULTIPLE: u32 = 4_096;
    /// Largest accepted `chunk_count`, §16.
    pub const CHUNK_COUNT_MAX: u64 = 1_048_576;
    /// Largest accepted `total_plaintext_length`, 1 TiB, §16.
    pub const TOTAL_PLAINTEXT_MAX: u64 = 1_099_511_627_776;
    /// Peak container buffer: one chunk plaintext plus one chunk ciphertext, §16.
    pub const MAX_BUFFER: usize = 16_777_232;
}

/// Limits of `docs/format/OBJECT_KEY_ENVELOPE_V1.md` §10 and
/// `docs/format/COLLECTION_KEY_ENVELOPE_V1.md` §9.
pub mod envelope {
    /// Exact length of `ObjectKeyEnvelopeV1`.
    pub const OBJECT_KEY_ENVELOPE_LEN: usize = 142;
    /// Exact length of `CollectionKeyEnvelopeV1`.
    pub const COLLECTION_KEY_ENVELOPE_LEN: usize = 126;
    /// Envelopes retained per object, `docs/format/CATALOG_SCHEMA_V1.md` §21.
    pub const MAX_PER_OBJECT: u32 = 64;
    /// Envelopes active at once per object.
    pub const MAX_ACTIVE_PER_OBJECT: u32 = 4;
    /// Envelopes per collection epoch.
    pub const MAX_PER_COLLECTION_EPOCH: u32 = 8;
    /// Collections per vault.
    pub const MAX_COLLECTIONS_PER_VAULT: u32 = 1_024;
}

/// Limits of `docs/format/VAULT_DESCRIPTOR_V1.md` §13.
pub mod descriptor {
    /// Exact length of the public head, §2.1.
    pub const HEAD_LEN: usize = 40;
    /// Smallest accepted `descriptor_length`.
    pub const LENGTH_MIN: u32 = 220;
    /// Largest accepted `descriptor_length`.
    pub const LENGTH_MAX: u32 = 65_536;
    /// Exact length of the catalog descriptor, §5.
    pub const CATALOG_DESCRIPTOR_LEN: usize = 60;
    /// Exact length of the object-store descriptor, §6.
    pub const OBJECT_STORE_DESCRIPTOR_LEN: usize = 24;
    /// Exact length of the key-slot descriptor header, §7.
    pub const SLOT_HEADER_LEN: usize = 34;
    /// Exact length of `MigrationDescriptorV1` when present, §2.2.
    pub const MIGRATION_DESCRIPTOR_LEN: usize = 32;
    /// Exact length of the trailing `descriptor_authentication` tag, §8.
    pub const AUTH_TAG_LEN: usize = 32;
    /// Smallest accepted key-slot count.
    pub const SLOT_COUNT_MIN: u32 = 1;
    /// Largest accepted key-slot count.
    pub const SLOT_COUNT_MAX: u32 = 16;
    /// Entries the vault registry holds, §11.
    pub const REGISTRY_MAX_ENTRIES: usize = 2;
}

/// Limits of `docs/format/KEY_SLOT_BODIES_V1.md` §8.
pub mod slot {
    /// Smallest accepted `slot_body_length`.
    pub const BODY_MIN: u32 = 16;
    /// Largest accepted `slot_body_length`.
    pub const BODY_MAX: u32 = 4_096;
    /// Largest accepted total of every slot body in one descriptor.
    pub const BODY_TOTAL_MAX: u32 = 16_384;
    /// Smallest accepted Argon2id salt length.
    pub const SALT_MIN: u32 = 16;
    /// Largest accepted Argon2id salt length.
    pub const SALT_MAX: u32 = 32;
    /// Salt length a v1 writer produces.
    pub const SALT_DEFAULT: u32 = 16;
    /// Smallest accepted Android Keystore alias length.
    pub const ALIAS_MIN: u32 = 16;
    /// Largest accepted Android Keystore alias length.
    pub const ALIAS_MAX: u32 = 64;
    /// Password slots one descriptor may declare,
    /// `docs/security/KEY_SLOTS.md` §11.
    pub const MAX_PASSWORD_SLOT_IDENTITIES: usize = 1;
    /// Argon2id derivations one password unlock attempt runs,
    /// `docs/security/KEY_SLOTS.md` §8.
    pub const PASSWORD_CANDIDATES_PER_ATTEMPT: usize = 2;
}

/// Password and Argon2id bounds, `docs/CRYPTOGRAPHY.md` §17 and §18.3 and
/// `docs/security/PASSWORD_PROFILE.md` §4.
pub mod password {
    /// Smallest accepted encoded password, `docs/CRYPTOGRAPHY.md` §17.
    pub const ENCODED_MIN: usize = 1;
    /// Largest accepted encoded password, `docs/CRYPTOGRAPHY.md` §17.
    pub const ENCODED_MAX: usize = 1_024;
    /// Frozen memory floor in KiB, also the v1 default, `PASSWORD_PROFILE.md` §4.
    pub const MEMORY_FLOOR_KIB: u32 = 65_536;
    /// Largest memory cost a v1 parser accepts, in KiB, `CRYPTOGRAPHY.md` §18.3.
    pub const MEMORY_MAX_KIB: u32 = 524_288;
    /// Frozen iteration floor, also the v1 default.
    pub const ITERATIONS_FLOOR: u32 = 3;
    /// Largest iteration count a v1 parser accepts.
    pub const ITERATIONS_MAX: u32 = 10;
    /// Smallest accepted parallelism, also the v1 default.
    pub const PARALLELISM_MIN: u32 = 1;
    /// Largest accepted parallelism.
    pub const PARALLELISM_MAX: u32 = 4;
    /// Required Argon2 output length.
    pub const OUTPUT_LEN: usize = 32;
}

// Compile-time consistency checks. A limit that contradicts another limit is a
// defect in this module, not a runtime condition, so it fails the build.

// §16: the two §6 chunk-size candidates are inside the accepted range and are
// whole multiples of 4096.
const _: () = assert!(262_144 >= container::CHUNK_SIZE_MIN);
const _: () = assert!(262_144 <= container::CHUNK_SIZE_MAX);
const _: () = assert!(262_144 % container::CHUNK_SIZE_MULTIPLE == 0);
const _: () = assert!(1_048_576 >= container::CHUNK_SIZE_MIN);
const _: () = assert!(1_048_576 <= container::CHUNK_SIZE_MAX);
const _: () = assert!(1_048_576 % container::CHUNK_SIZE_MULTIPLE == 0);

// §16: the peak container buffer is one chunk plaintext plus one chunk
// ciphertext at the largest accepted chunk size.
const _: () =
    assert!(2 * container::CHUNK_SIZE_MAX as u64 + TAG_LEN as u64 == container::MAX_BUFFER as u64);

// §16: at the 1 MiB candidate, the maximum chunk count covers the maximum
// plaintext length exactly.
const _: () = assert!(container::CHUNK_COUNT_MAX * 1_048_576 == container::TOTAL_PLAINTEXT_MAX);

// `docs/format/VAULT_DESCRIPTOR_V1.md` §13: the smallest descriptor is the
// 40-byte head, a 148-byte body holding one key-slot descriptor whose body is
// the 16-byte minimum and an absent migration descriptor, and the 32-byte tag.
const SMALLEST_DESCRIPTOR_BODY: usize = 8
    + 1
    + descriptor::CATALOG_DESCRIPTOR_LEN
    + descriptor::OBJECT_STORE_DESCRIPTOR_LEN
    + 4
    + descriptor::SLOT_HEADER_LEN
    + slot::BODY_MIN as usize
    + 1;
const _: () = assert!(SMALLEST_DESCRIPTOR_BODY == 148);
const _: () = assert!(
    (descriptor::HEAD_LEN + SMALLEST_DESCRIPTOR_BODY + descriptor::AUTH_TAG_LEN) as u32
        == descriptor::LENGTH_MIN
);

// `docs/CRYPTOGRAPHY.md` §18.3: the frozen Argon2id floor is inside the bounds a
// v1 parser accepts, and the output length is one symmetric key.
const _: () = assert!(password::MEMORY_FLOOR_KIB <= password::MEMORY_MAX_KIB);
const _: () = assert!(password::ITERATIONS_FLOOR <= password::ITERATIONS_MAX);
const _: () = assert!(password::PARALLELISM_MIN <= password::PARALLELISM_MAX);
const _: () = assert!(password::OUTPUT_LEN == KEY_LEN);

// `docs/format/KEY_SLOT_BODIES_V1.md` §8: every family body fits the descriptor
// bound, and the salt a v1 writer produces is inside the accepted range.
const _: () = assert!(slot::SALT_DEFAULT >= slot::SALT_MIN);
const _: () = assert!(slot::SALT_DEFAULT <= slot::SALT_MAX);
const _: () = assert!(92 + slot::SALT_MAX <= slot::BODY_MAX);
const _: () = assert!(66 + slot::ALIAS_MAX <= slot::BODY_MAX);
const _: () = assert!(74 >= slot::BODY_MIN);
const _: () = assert!(90 >= slot::BODY_MIN);
