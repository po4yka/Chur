//! The portable backup package, `docs/format/BACKUP_FORMAT_V1.md`.
//!
//! The package is a stream of records over one vault identity: a 32-byte public
//! preamble, a sealed manifest, the portable vault descriptor, the encrypted
//! catalog, one entry per object container, and a sealed final commit. §1 fixes
//! what it is for — portable across Android, iOS, and CLI, with no device-bound
//! slot, authenticating its own inventory and completeness, and preserving
//! immutable object containers without decrypting them.
//!
//! Two properties shape every type here.
//!
//! Ciphertext is copied, never opened. A container entry carries the container's
//! bytes exactly as the object store holds them, so a backup of a 40 GB vault
//! decrypts nothing and holds no plaintext at any moment.
//!
//! Completeness is authenticated, not counted. §7.2 commits to the ordered
//! inventory, and the final commit seals that commitment together with the
//! entry counts, so a package with a record removed, added, or reordered fails
//! the final commit rather than restoring quietly.

use chur_core::{Error, Id, Result, bail, ensure, limits::backup as bounds, status::ChurStatus};
use chur_crypto::commit::{Commitment, Committer};
use chur_crypto::kdf::{self, Context, Label};
use chur_crypto::tuple::{Tuple, tag};
use chur_crypto::{Key, Nonce, aead};

use crate::codec::Reader;
use crate::constants::{
    BACKUP_VERSION_V1, ENCODING_PROFILE_V1, MAGIC_BACKUP, SUITE_V1, StreamKind,
};

/// The `PublicBackupPreamble` length, §2.1.
pub const PREAMBLE_LEN: usize = 32;

/// The package-record header length, §2.2.
pub const RECORD_HEADER_LEN: usize = 12;

/// The `record_version` every v1 package record carries, §2.2.
pub const RECORD_VERSION_V1: u8 = 0x01;

/// One `StreamInventoryEntryV1`, §7.1.
pub const STREAM_ENTRY_LEN: usize = 16 + 16 + 1 + 4 + 8 + 32 + 32;

/// One `SlotInventoryEntryV1`, §7.1.
pub const SLOT_ENTRY_LEN: usize = 16 + 1 + 8;

/// The sealed `CanonicalBackupManifest` plaintext length.
pub const MANIFEST_PLAINTEXT_LEN: usize = 16 + 2 + 16 + 8 + 1 + 16 + 8 + 2 + 4 + 4 + 32 + 8;

/// The sealed final-commit plaintext length.
pub const FINAL_COMMIT_PLAINTEXT_LEN: usize = 16 + 8 + 4 + 4 + 32;

/// A package record's type, §2.2, allocated in `CANONICAL_ENCODING_V1.md` §15.3.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum RecordType {
    /// The encrypted backup manifest. Always the first record.
    Manifest = 0x01,
    /// The portable vault descriptor of §3.
    Descriptor = 0x02,
    /// The encrypted canonical catalog export.
    CatalogExport = 0x03,
    /// One object container, carried as its own ciphertext.
    Container = 0x04,
    /// One object-key or collection-key envelope.
    Envelope = 0x05,
    /// The incremental operation segment of §6, which v1 never writes.
    IncrementalSegment = 0x06,
    /// The authenticated final backup commit. Always the last record.
    FinalCommit = 0x07,
}

impl RecordType {
    /// The type an allocated byte names.
    ///
    /// §2.2: an unallocated `record_type` is a parse failure and is never an
    /// ignorable record, so this returns an error rather than an option.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::VaultCorrupt`] for an unallocated value.
    pub fn from_byte(value: u8) -> Result<Self> {
        match value {
            0x01 => Ok(Self::Manifest),
            0x02 => Ok(Self::Descriptor),
            0x03 => Ok(Self::CatalogExport),
            0x04 => Ok(Self::Container),
            0x05 => Ok(Self::Envelope),
            0x06 => Ok(Self::IncrementalSegment),
            0x07 => Ok(Self::FinalCommit),
            _ => bail!(
                VaultCorrupt,
                "the package carries an unallocated record type"
            ),
        }
    }
}

// ---------------------------------------------------------------------------
// §2.3 Outer framing
// ---------------------------------------------------------------------------

/// What the first eight bytes of a file say it is, §2.3.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Framing {
    /// An unwrapped native package, parsed by §2.1.
    Native,
    /// The start of an `age` v1 binary header line.
    AgeBinary,
    /// The start of an `age` ASCII-armored header.
    AgeArmored,
}

/// Classifies a file by its first eight bytes, §2.3.
///
/// The wrapper is transport only. This build removes no `age` layer, so a
/// wrapped package is recognized and named rather than misparsed: telling the
/// user the file is `age`-wrapped is a different thing from telling them it is
/// not a Chur backup, and §2.3 makes both outcomes reachable.
///
/// # Errors
///
/// Returns [`ChurStatus::VaultCorrupt`] when the bytes are none of the three,
/// which §2.3 requires to be rejected before any further parsing.
pub fn framing_of(head: &[u8]) -> Result<Framing> {
    ensure!(
        head.len() >= 8,
        VaultCorrupt,
        "the file is shorter than an eight-byte magic"
    );
    match &head[..8] {
        b if b == MAGIC_BACKUP => Ok(Framing::Native),
        b"age-encr" => Ok(Framing::AgeBinary),
        b"-----BEG" => Ok(Framing::AgeArmored),
        _ => bail!(VaultCorrupt, "the file is not a Chur backup package"),
    }
}

// ---------------------------------------------------------------------------
// §2.1 Public preamble
// ---------------------------------------------------------------------------

/// The 32-byte `PublicBackupPreamble` at file offset 0, §2.1.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PublicPreamble {
    record_count: u64,
}

impl PublicPreamble {
    /// Names the preamble of a package holding `record_count` records.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::ResourceLimitExceeded`] outside the §13 bound of
    /// 2 to 1048576: a package holds at least the manifest and the final
    /// commit.
    pub fn new(record_count: u64) -> Result<Self> {
        ensure!(
            (bounds::RECORD_COUNT_MIN..=bounds::RECORD_COUNT_MAX).contains(&record_count),
            ResourceLimitExceeded,
            "the record count is outside the §13 bound"
        );
        Ok(Self { record_count })
    }

    /// The records the package declares.
    ///
    /// §2.1: this is the only variable preamble field. It bounds allocation
    /// before any credential exists, and the final backup commit authenticates
    /// it, so a modified value surfaces as a commit authentication failure
    /// rather than as a successful parse.
    #[must_use]
    pub const fn record_count(&self) -> u64 {
        self.record_count
    }

    /// The canonical 32 bytes.
    #[must_use]
    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(PREAMBLE_LEN);
        out.extend_from_slice(&MAGIC_BACKUP);
        out.extend_from_slice(&BACKUP_VERSION_V1.to_be_bytes());
        out.extend_from_slice(&ENCODING_PROFILE_V1.to_be_bytes());
        out.extend_from_slice(&SUITE_V1.to_be_bytes());
        out.extend_from_slice(&0u16.to_be_bytes());
        #[expect(
            clippy::cast_possible_truncation,
            reason = "PREAMBLE_LEN is 32 and the field is the constant 32"
        )]
        out.extend_from_slice(&(PREAMBLE_LEN as u32).to_be_bytes());
        out.extend_from_slice(&0u32.to_be_bytes());
        out.extend_from_slice(&self.record_count.to_be_bytes());
        debug_assert_eq!(out.len(), PREAMBLE_LEN);
        out
    }

    /// Parses the preamble, §2.1.
    ///
    /// Every fixed field must hold its listed v1 value. An unknown version,
    /// profile, or suite fails as `UNSUPPORTED_*`; a fixed field holding any
    /// other value fails as `VAULT_CORRUPT` and is never ignored.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::VaultCorrupt`], [`ChurStatus::UnsupportedVersion`],
    /// [`ChurStatus::UnsupportedSuite`], or
    /// [`ChurStatus::ResourceLimitExceeded`] as §2.1 and §13 require.
    pub fn decode(bytes: &[u8]) -> Result<Self> {
        ensure!(
            bytes.len() >= PREAMBLE_LEN,
            VaultCorrupt,
            "the package is shorter than its preamble"
        );
        ensure!(
            bytes[..8] == MAGIC_BACKUP,
            VaultCorrupt,
            "the package magic is not CHURBAK1"
        );
        let mut reader = Reader::new(&bytes[8..PREAMBLE_LEN], ChurStatus::VaultCorrupt);
        let backup_version = reader.u16()?;
        let profile = reader.u16()?;
        let suite = reader.u16()?;
        let flags = reader.u16()?;
        let header_length = reader.u32()?;
        let reserved = reader.u32()?;
        let record_count = reader.u64()?;

        ensure!(
            backup_version == BACKUP_VERSION_V1,
            UnsupportedVersion,
            "the package declares an unsupported backup version"
        );
        ensure!(
            profile == ENCODING_PROFILE_V1,
            UnsupportedVersion,
            "the package declares an unsupported encoding profile"
        );
        ensure!(
            suite == SUITE_V1,
            UnsupportedSuite,
            "the package declares an unsupported suite"
        );
        ensure!(flags == 0, VaultCorrupt, "the package flags are not zero");
        #[expect(
            clippy::cast_possible_truncation,
            reason = "PREAMBLE_LEN is the constant 32"
        )]
        let expected_header = PREAMBLE_LEN as u32;
        ensure!(
            header_length == expected_header,
            VaultCorrupt,
            "the package header length is not the v1 value"
        );
        ensure!(
            reserved == 0,
            VaultCorrupt,
            "the package reserved field is not zero"
        );
        Self::new(record_count)
    }
}

// ---------------------------------------------------------------------------
// §2.2 Package records
// ---------------------------------------------------------------------------

/// One record header, §2.2.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RecordHeader {
    /// The record's type.
    pub record_type: RecordType,
    /// The payload length that follows the header.
    pub payload_length: u64,
}

impl RecordHeader {
    /// The canonical 12 bytes.
    #[must_use]
    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(RECORD_HEADER_LEN);
        out.push(self.record_type as u8);
        out.push(RECORD_VERSION_V1);
        out.extend_from_slice(&0u16.to_be_bytes());
        out.extend_from_slice(&self.payload_length.to_be_bytes());
        out
    }

    /// Parses a record header.
    ///
    /// §2.2: a reader dispatches on `record_type` before it reads any other
    /// field, and an unallocated type, a `record_version` other than `0x01`, or
    /// a non-zero `reserved` fails as `VAULT_CORRUPT`.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::VaultCorrupt`] for a short header or a fixed field
    /// holding another value.
    pub fn decode(bytes: &[u8]) -> Result<Self> {
        ensure!(
            bytes.len() >= RECORD_HEADER_LEN,
            VaultCorrupt,
            "the record header is short"
        );
        let record_type = RecordType::from_byte(bytes[0])?;
        ensure!(
            bytes[1] == RECORD_VERSION_V1,
            VaultCorrupt,
            "the record declares an unsupported record version"
        );
        ensure!(
            u16::from_be_bytes([bytes[2], bytes[3]]) == 0,
            VaultCorrupt,
            "the record reserved field is not zero"
        );
        let payload_length = u64::from_be_bytes([
            bytes[4], bytes[5], bytes[6], bytes[7], bytes[8], bytes[9], bytes[10], bytes[11],
        ]);
        Ok(Self {
            record_type,
            payload_length,
        })
    }
}

// ---------------------------------------------------------------------------
// §7.1 Inventory entries
// ---------------------------------------------------------------------------

/// One backed-up stream, §7.1.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct StreamInventoryEntry {
    /// The object the stream belongs to.
    pub object_id: Id,
    /// The stream.
    pub stream_id: Id,
    /// The stream's kind.
    pub stream_kind: StreamKind,
    /// The stream's revision.
    pub stream_revision: u32,
    /// The container's ciphertext length.
    pub ciphertext_length: u64,
    /// The container's manifest commitment.
    pub manifest_commitment: [u8; 32],
    /// The container's ordered chunk commitment.
    pub ordered_chunk_commitment: [u8; 32],
}

impl StreamInventoryEntry {
    /// The canonical bytes, in the field order of §7.1.
    #[must_use]
    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(STREAM_ENTRY_LEN);
        out.extend_from_slice(self.object_id.as_bytes());
        out.extend_from_slice(self.stream_id.as_bytes());
        out.push(self.stream_kind.value());
        out.extend_from_slice(&self.stream_revision.to_be_bytes());
        out.extend_from_slice(&self.ciphertext_length.to_be_bytes());
        out.extend_from_slice(&self.manifest_commitment);
        out.extend_from_slice(&self.ordered_chunk_commitment);
        debug_assert_eq!(out.len(), STREAM_ENTRY_LEN);
        out
    }

    /// Parses one entry.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::VaultCorrupt`] for a short entry or an unallocated
    /// `stream_kind`.
    pub fn decode(bytes: &[u8]) -> Result<Self> {
        ensure!(
            bytes.len() == STREAM_ENTRY_LEN,
            VaultCorrupt,
            "the stream inventory entry is not its canonical length"
        );
        let mut reader = Reader::new(bytes, ChurStatus::VaultCorrupt);
        let object_id = reader.id()?;
        let stream_id = reader.id()?;
        let stream_kind = StreamKind::from_value(reader.u8()?)
            .ok_or_else(|| Error::new(ChurStatus::VaultCorrupt, "an unallocated stream kind"))?;
        let stream_revision = reader.u32()?;
        let ciphertext_length = reader.u64()?;
        let manifest_commitment = reader.fixed::<32>()?;
        let ordered_chunk_commitment = reader.fixed::<32>()?;
        Ok(Self {
            object_id,
            stream_id,
            stream_kind,
            stream_revision,
            ciphertext_length,
            manifest_commitment,
            ordered_chunk_commitment,
        })
    }

    /// The total order of §7.1: object, then stream, then revision.
    ///
    /// The three keys are unique together, so the order is total and two
    /// conforming writers backing up the same content emit the same sequence.
    #[must_use]
    pub fn sort_key(&self) -> ([u8; 16], [u8; 16], u32) {
        (
            *self.object_id.as_bytes(),
            *self.stream_id.as_bytes(),
            self.stream_revision,
        )
    }
}

/// One portable key slot, §7.1.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SlotInventoryEntry {
    /// The slot.
    pub slot_id: Id,
    /// Its family, from `CANONICAL_ENCODING_V1.md` §15.4.
    pub slot_type: u8,
    /// Its generation.
    pub slot_generation: u64,
}

impl SlotInventoryEntry {
    /// The canonical bytes.
    #[must_use]
    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(SLOT_ENTRY_LEN);
        out.extend_from_slice(self.slot_id.as_bytes());
        out.push(self.slot_type);
        out.extend_from_slice(&self.slot_generation.to_be_bytes());
        debug_assert_eq!(out.len(), SLOT_ENTRY_LEN);
        out
    }
}

/// The ordered inventory commitment of §7.2.
///
/// Entries are fed in the §7.1 order as their canonical bytes, with no count
/// prefix and no separator; the counts are authenticated by the final backup
/// commit instead. For an empty inventory the value is BLAKE3-256 of the domain
/// tag alone, which this produces without a special case.
pub struct InventoryCommitter {
    inner: Committer,
    streams: u32,
    slots: u32,
}

impl Default for InventoryCommitter {
    fn default() -> Self {
        Self::new()
    }
}

impl InventoryCommitter {
    /// Starts an empty commitment.
    #[must_use]
    pub fn new() -> Self {
        Self {
            inner: Committer::new(tag::BACKUP_INVENTORY_COMMITMENT),
            streams: 0,
            slots: 0,
        }
    }

    /// Adds one stream entry.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::ResourceLimitExceeded`] above the §13 entry bound,
    /// and [`ChurStatus::Conflict`] when a slot entry has already been added:
    /// §7.1 puts every stream entry before every slot entry, and a committer
    /// that accepted them interleaved would produce a value no conforming
    /// reader reaches.
    pub fn add_stream(&mut self, entry: &StreamInventoryEntry) -> Result<()> {
        ensure!(
            self.slots == 0,
            Conflict,
            "§7.1 orders every stream entry before every slot entry"
        );
        ensure!(
            (self.streams as usize) < bounds::STREAM_ENTRIES_MAX,
            ResourceLimitExceeded,
            "the inventory exceeds the §13 stream-entry bound"
        );
        self.inner.update(&entry.encode());
        self.streams += 1;
        Ok(())
    }

    /// Adds one slot entry.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::ResourceLimitExceeded`] above the §13 slot bound.
    pub fn add_slot(&mut self, entry: &SlotInventoryEntry) -> Result<()> {
        ensure!(
            (self.slots as usize) < bounds::SLOT_ENTRIES_MAX,
            ResourceLimitExceeded,
            "the inventory exceeds the §13 slot-entry bound"
        );
        self.inner.update(&entry.encode());
        self.slots += 1;
        Ok(())
    }

    /// The stream entries added.
    #[must_use]
    pub const fn stream_count(&self) -> u32 {
        self.streams
    }

    /// The slot entries added.
    #[must_use]
    pub const fn slot_count(&self) -> u32 {
        self.slots
    }

    /// The 32-byte commitment.
    #[must_use]
    pub fn finish(&self) -> Commitment {
        self.inner.finish()
    }
}

// ---------------------------------------------------------------------------
// §4 Backup manifest
// ---------------------------------------------------------------------------

/// The `BackupManifestKey` of §4.
///
/// `KEY_HIERARCHY.md` §3 registers `chur/v1/root/backup-manifest` with
/// `vault_id` as its one context element, and ADR-0034 froze that list. The
/// backup's own identity is bound in the AAD below rather than in the context,
/// so one vault's backups share a key and no two of them share an AAD.
///
/// # Errors
///
/// Returns the derivation errors of [`kdf::derive_from`].
pub fn manifest_key(root: &Key, vault_id: &Id) -> Result<Key> {
    kdf::derive_from(root, Label::RootBackupManifest, &Context::vault(vault_id))
}

/// The AAD the manifest record is sealed under.
///
/// It binds the vault and not the backup. A restore has to open the manifest
/// before it knows which backup it is reading — the identifier is inside the
/// sealed plaintext, and §2.1 leaves no room for it in the public preamble — so
/// binding it here would make the AAD depend on a value only the record itself
/// carries. The identifier is checked instead: the manifest and the final commit
/// must name the same backup, and both are sealed under a key only the root
/// holder can derive.
fn manifest_aad(vault_id: &Id) -> Vec<u8> {
    Tuple::new(tag::BACKUP_MANIFEST_AAD)
        .u16(BACKUP_VERSION_V1)
        .u16(SUITE_V1)
        .id(vault_id)
        .finish()
}

/// The AAD the final backup commit is sealed under.
///
/// It shares the manifest's key and differs from it only in its domain tag,
/// which is exactly what a domain tag is for: two records of one package are
/// sealed under one key and neither opens as the other.
fn final_commit_aad(vault_id: &Id) -> Vec<u8> {
    Tuple::new(tag::BACKUP_FINAL_COMMIT_AAD)
        .u16(BACKUP_VERSION_V1)
        .u16(SUITE_V1)
        .id(vault_id)
        .finish()
}

/// Opens one sealed package record.
///
/// The AEAD reports a tag failure as `OBJECT_CORRUPT`, whose subject is an
/// object container. A package record's subject is a vault, so the status
/// becomes `VAULT_CORRUPT` here. The two causes it covers — a package damaged
/// in transit and a package sealed for another identity — are deliberately one
/// status: a MAC failure cannot tell them apart, and a reader that claimed to
/// would be guessing.
fn open_sealed(payload: &[u8], key: &Key, aad: &[u8], what: &'static str) -> Result<Vec<u8>> {
    let nonce_len = chur_core::limits::NONCE_LEN;
    ensure!(
        payload.len() > nonce_len,
        VaultCorrupt,
        "the package record is shorter than its nonce"
    );
    let nonce = Nonce::from_slice(&payload[..nonce_len])?;
    let plaintext = aead::open(key, &nonce, &payload[nonce_len..], aad)
        .map_err(|_| Error::new(ChurStatus::VaultCorrupt, what))?;
    Ok(plaintext.to_vec())
}

/// The manifest of §4, before it is sealed.
///
/// §4 lists the inventory among the manifest's contents. It is not carried here
/// as a list: at the §13 bound of 1048576 stream entries a list would be about
/// 109 MB, which exceeds the §13 manifest payload cap of 16 MiB and would make
/// a restore allocate the whole inventory before it reads a byte of content.
/// The manifest carries the ordered inventory *commitment* of §7.2 and the two
/// counts instead, and each entry travels in the head of the record it
/// describes. A restore recomputes the commitment as it walks the package, so
/// completeness is authenticated with one entry in memory at a time.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BackupManifest {
    /// This backup's identity.
    pub backup_id: Id,
    /// The vault identity the package holds. §11: one package, one identity.
    pub vault_id: Id,
    /// When the package was created, in Unix milliseconds.
    pub created_time_ms: u64,
    /// The base an incremental package builds on, §6. `None` for a full backup.
    pub base_backup_id: Option<Id>,
    /// The catalog generation the snapshot was taken at.
    pub catalog_generation: u64,
    /// The catalog schema version the export carries.
    pub catalog_format_version: u16,
    /// Stream inventory entries the package carries.
    pub stream_entry_count: u32,
    /// Slot inventory entries the package carries.
    pub slot_entry_count: u32,
    /// The §7.2 ordered inventory commitment.
    pub inventory_commitment: [u8; 32],
    /// The free space a restore requires, §13.
    pub free_space_required: u64,
}

impl BackupManifest {
    /// The canonical plaintext.
    #[must_use]
    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(MANIFEST_PLAINTEXT_LEN);
        out.extend_from_slice(self.backup_id.as_bytes());
        out.extend_from_slice(&BACKUP_VERSION_V1.to_be_bytes());
        out.extend_from_slice(self.vault_id.as_bytes());
        out.extend_from_slice(&self.created_time_ms.to_be_bytes());
        match self.base_backup_id {
            Some(base) => {
                out.push(1);
                out.extend_from_slice(base.as_bytes());
            }
            None => {
                out.push(0);
                out.extend_from_slice(&[0u8; 16]);
            }
        }
        out.extend_from_slice(&self.catalog_generation.to_be_bytes());
        out.extend_from_slice(&self.catalog_format_version.to_be_bytes());
        out.extend_from_slice(&self.stream_entry_count.to_be_bytes());
        out.extend_from_slice(&self.slot_entry_count.to_be_bytes());
        out.extend_from_slice(&self.inventory_commitment);
        out.extend_from_slice(&self.free_space_required.to_be_bytes());
        debug_assert_eq!(out.len(), MANIFEST_PLAINTEXT_LEN);
        out
    }

    /// Parses the canonical plaintext.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::VaultCorrupt`] for a wrong length, a
    /// `backup_version` differing from the public preamble's, an absent-base
    /// discriminant that is neither 0 nor 1, or a base identifier present under
    /// a zero discriminant. Returns [`ChurStatus::ResourceLimitExceeded`] above
    /// the §13 entry bounds.
    pub fn decode(bytes: &[u8]) -> Result<Self> {
        ensure!(
            bytes.len() == MANIFEST_PLAINTEXT_LEN,
            VaultCorrupt,
            "the backup manifest is not its canonical length"
        );
        let mut reader = Reader::new(bytes, ChurStatus::VaultCorrupt);
        let backup_id = reader.id()?;
        let backup_version = reader.u16()?;
        let vault_id = reader.id()?;
        let created_time_ms = reader.u64()?;
        let base_present = reader.u8()?;
        let base_bytes = reader.fixed::<16>()?;
        let catalog_generation = reader.u64()?;
        let catalog_format_version = reader.u16()?;
        let stream_entry_count = reader.u32()?;
        let slot_entry_count = reader.u32()?;
        let inventory_commitment = reader.fixed::<32>()?;
        let free_space_required = reader.u64()?;

        // §4: `backup_version` here repeats the public preamble field, and a
        // restore rejects the package as VAULT_CORRUPT when the two differ.
        ensure!(
            backup_version == BACKUP_VERSION_V1,
            VaultCorrupt,
            "the manifest contradicts the preamble's backup version"
        );
        let base_backup_id = match base_present {
            0 => {
                ensure!(
                    base_bytes == [0u8; 16],
                    VaultCorrupt,
                    "the manifest carries a base identifier it declares absent"
                );
                None
            }
            1 => Some(Id::new(base_bytes)?),
            _ => bail!(
                VaultCorrupt,
                "the manifest's base discriminant is neither zero nor one"
            ),
        };
        ensure!(
            (stream_entry_count as usize) <= bounds::STREAM_ENTRIES_MAX,
            ResourceLimitExceeded,
            "the manifest declares more stream entries than §13 admits"
        );
        ensure!(
            (slot_entry_count as usize) <= bounds::SLOT_ENTRIES_MAX,
            ResourceLimitExceeded,
            "the manifest declares more slot entries than §13 admits"
        );
        Ok(Self {
            backup_id,
            vault_id,
            created_time_ms,
            base_backup_id,
            catalog_generation,
            catalog_format_version,
            stream_entry_count,
            slot_entry_count,
            inventory_commitment,
            free_space_required,
        })
    }

    /// Seals the manifest into the payload of record `0x01`.
    ///
    /// # Errors
    ///
    /// Returns the AEAD errors of [`aead::seal`].
    pub fn seal(&self, key: &Key, nonce: &Nonce) -> Result<Vec<u8>> {
        let aad = manifest_aad(&self.vault_id);
        let sealed = aead::seal(key, nonce, &self.encode(), &aad)?;
        let mut out = Vec::with_capacity(nonce.as_bytes().len() + sealed.len());
        out.extend_from_slice(nonce.as_bytes());
        out.extend_from_slice(&sealed);
        Ok(out)
    }

    /// Opens the payload of record `0x01`.
    ///
    /// The identity is supplied by the caller rather than read from the record,
    /// because it is the AAD: a package resealed under another vault's manifest
    /// key, or renamed to another `backup_id`, fails the AEAD instead of parsing
    /// under its own claim. It is the same rule
    /// `OBJECT_CONTAINER_V1.md` §4 applies to a stream identity.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::VaultCorrupt`] for a payload shorter than a nonce,
    /// for a package the AEAD rejects, and for the parse failures of
    /// [`BackupManifest::decode`].
    pub fn open(payload: &[u8], key: &Key, vault_id: &Id) -> Result<Self> {
        let aad = manifest_aad(vault_id);
        let plaintext = open_sealed(
            payload,
            key,
            &aad,
            "the backup manifest did not authenticate for this vault and backup",
        )?;
        let manifest = Self::decode(&plaintext)?;
        ensure!(
            manifest.vault_id == *vault_id,
            VaultCorrupt,
            "the manifest names an identity other than the one it authenticated under"
        );
        Ok(manifest)
    }
}

// ---------------------------------------------------------------------------
// §7 Final backup commit
// ---------------------------------------------------------------------------

/// The authenticated final backup commit, the last record of every package.
///
/// It is what makes the package's completeness a cryptographic fact. It seals
/// the record count the public preamble declared, the two entry counts, and the
/// ordered inventory commitment, so a package with a container removed, a
/// record added, or two records swapped fails here rather than restoring a
/// vault that is missing something.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FinalBackupCommit {
    /// The backup this commit closes.
    pub backup_id: Id,
    /// The record count the preamble declared.
    pub record_count: u64,
    /// Stream entries the package carried.
    pub stream_entry_count: u32,
    /// Slot entries the package carried.
    pub slot_entry_count: u32,
    /// The §7.2 ordered inventory commitment.
    pub inventory_commitment: [u8; 32],
}

impl FinalBackupCommit {
    /// The canonical plaintext.
    #[must_use]
    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(FINAL_COMMIT_PLAINTEXT_LEN);
        out.extend_from_slice(self.backup_id.as_bytes());
        out.extend_from_slice(&self.record_count.to_be_bytes());
        out.extend_from_slice(&self.stream_entry_count.to_be_bytes());
        out.extend_from_slice(&self.slot_entry_count.to_be_bytes());
        out.extend_from_slice(&self.inventory_commitment);
        debug_assert_eq!(out.len(), FINAL_COMMIT_PLAINTEXT_LEN);
        out
    }

    /// Parses the canonical plaintext.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::VaultCorrupt`] for a wrong length.
    pub fn decode(bytes: &[u8]) -> Result<Self> {
        ensure!(
            bytes.len() == FINAL_COMMIT_PLAINTEXT_LEN,
            VaultCorrupt,
            "the final backup commit is not its canonical length"
        );
        let mut reader = Reader::new(bytes, ChurStatus::VaultCorrupt);
        Ok(Self {
            backup_id: reader.id()?,
            record_count: reader.u64()?,
            stream_entry_count: reader.u32()?,
            slot_entry_count: reader.u32()?,
            inventory_commitment: reader.fixed::<32>()?,
        })
    }

    /// Seals the commit into the payload of record `0x07`.
    ///
    /// # Errors
    ///
    /// Returns the AEAD errors of [`aead::seal`].
    pub fn seal(&self, key: &Key, vault_id: &Id, nonce: &Nonce) -> Result<Vec<u8>> {
        let aad = final_commit_aad(vault_id);
        let sealed = aead::seal(key, nonce, &self.encode(), &aad)?;
        let mut out = Vec::with_capacity(nonce.as_bytes().len() + sealed.len());
        out.extend_from_slice(nonce.as_bytes());
        out.extend_from_slice(&sealed);
        Ok(out)
    }

    /// Opens the payload of record `0x07`.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::VaultCorrupt`] for a short payload, for a package
    /// the AEAD rejects, and for the parse failures of
    /// [`FinalBackupCommit::decode`].
    pub fn open(payload: &[u8], key: &Key, vault_id: &Id, backup_id: &Id) -> Result<Self> {
        let aad = final_commit_aad(vault_id);
        let plaintext = open_sealed(
            payload,
            key,
            &aad,
            "the final backup commit did not authenticate for this vault and backup",
        )?;
        let commit = Self::decode(&plaintext)?;
        ensure!(
            commit.backup_id == *backup_id,
            VaultCorrupt,
            "the final commit names another backup"
        );
        Ok(commit)
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

    use super::*;

    const ROOT: Key = Key::new([0x61; 32]);

    fn ids() -> (Id, Id) {
        (
            Id::new([0x11; 16]).expect("vault"),
            Id::new([0x22; 16]).expect("backup"),
        )
    }

    fn manifest(vault_id: Id, backup_id: Id) -> BackupManifest {
        BackupManifest {
            backup_id,
            vault_id,
            created_time_ms: 1_700_000_000_000,
            base_backup_id: None,
            catalog_generation: 7,
            catalog_format_version: 1,
            stream_entry_count: 3,
            slot_entry_count: 2,
            inventory_commitment: [0x5a; 32],
            free_space_required: 4_096,
        }
    }

    fn status<T>(outcome: Result<T>) -> ChurStatus {
        let Err(error) = outcome else {
            panic!("the parser accepted something the specification forbids");
        };
        error.status()
    }

    #[test]
    fn the_preamble_round_trips_and_every_fixed_field_is_checked() {
        let bytes = PublicPreamble::new(4).expect("build").encode();
        assert_eq!(bytes.len(), PREAMBLE_LEN);
        assert_eq!(
            PublicPreamble::decode(&bytes)
                .expect("decode")
                .record_count(),
            4
        );

        let mut wrong_magic = bytes.clone();
        wrong_magic[7] = b'2';
        assert_eq!(
            status(PublicPreamble::decode(&wrong_magic)),
            ChurStatus::VaultCorrupt
        );

        let mut version = bytes.clone();
        version[9] = 0x02;
        assert_eq!(
            status(PublicPreamble::decode(&version)),
            ChurStatus::UnsupportedVersion
        );

        let mut suite = bytes.clone();
        suite[13] = 0x02;
        assert_eq!(
            status(PublicPreamble::decode(&suite)),
            ChurStatus::UnsupportedSuite
        );

        let mut flags = bytes.clone();
        flags[15] = 0x01;
        assert_eq!(
            status(PublicPreamble::decode(&flags)),
            ChurStatus::VaultCorrupt
        );

        let mut reserved = bytes.clone();
        reserved[23] = 0x01;
        assert_eq!(
            status(PublicPreamble::decode(&reserved)),
            ChurStatus::VaultCorrupt
        );
    }

    #[test]
    fn a_package_holds_at_least_the_manifest_and_the_final_commit() {
        assert_eq!(
            status(PublicPreamble::new(1)),
            ChurStatus::ResourceLimitExceeded
        );
        assert!(PublicPreamble::new(2).is_ok());
        assert!(PublicPreamble::new(bounds::RECORD_COUNT_MAX).is_ok());
        assert_eq!(
            status(PublicPreamble::new(bounds::RECORD_COUNT_MAX + 1)),
            ChurStatus::ResourceLimitExceeded
        );
    }

    #[test]
    fn an_unallocated_record_type_is_a_parse_failure_rather_than_an_ignorable_record() {
        for value in [0x00u8, 0x08, 0x7f, 0xff] {
            assert_eq!(
                status(RecordType::from_byte(value)),
                ChurStatus::VaultCorrupt
            );
        }
        for value in 0x01u8..=0x07 {
            assert!(RecordType::from_byte(value).is_ok());
        }
    }

    #[test]
    fn a_record_header_round_trips_and_refuses_a_changed_fixed_field() {
        let header = RecordHeader {
            record_type: RecordType::Container,
            payload_length: 262_144,
        };
        let bytes = header.encode();
        assert_eq!(bytes.len(), RECORD_HEADER_LEN);
        assert_eq!(RecordHeader::decode(&bytes).expect("decode"), header);

        let mut version = bytes.clone();
        version[1] = 0x02;
        assert_eq!(
            status(RecordHeader::decode(&version)),
            ChurStatus::VaultCorrupt
        );

        let mut reserved = bytes.clone();
        reserved[3] = 0x01;
        assert_eq!(
            status(RecordHeader::decode(&reserved)),
            ChurStatus::VaultCorrupt
        );
    }

    #[test]
    fn the_outer_framing_names_an_age_wrapper_rather_than_calling_it_not_a_backup() {
        assert_eq!(framing_of(&MAGIC_BACKUP).expect("native"), Framing::Native);
        assert_eq!(
            framing_of(b"age-encryption.org/v1\n").expect("binary"),
            Framing::AgeBinary
        );
        assert_eq!(
            framing_of(b"-----BEGIN AGE ENCRYPTED FILE-----").expect("armored"),
            Framing::AgeArmored
        );
        assert_eq!(
            status(framing_of(b"PK\x03\x04zzzz")),
            ChurStatus::VaultCorrupt
        );
        assert_eq!(status(framing_of(b"CHUR")), ChurStatus::VaultCorrupt);
    }

    #[test]
    fn a_manifest_round_trips_through_its_sealed_record() {
        let (vault_id, backup_id) = ids();
        let key = manifest_key(&ROOT, &vault_id).expect("derive");
        let record = manifest(vault_id, backup_id);
        let payload = record.seal(&key, &Nonce::new([0x31; 24])).expect("seal");
        let opened = BackupManifest::open(&payload, &key, &vault_id).expect("open");
        assert_eq!(opened, record);
    }

    /// §4 makes the manifest key per vault. A package sealed for one vault does
    /// not open under another's manifest key, so a restore into the wrong
    /// identity fails the AEAD rather than proceeding.
    #[test]
    fn a_manifest_does_not_open_under_another_identity() {
        let (vault_id, backup_id) = ids();
        let key = manifest_key(&ROOT, &vault_id).expect("derive");
        let payload = manifest(vault_id, backup_id)
            .seal(&key, &Nonce::new([0x31; 24]))
            .expect("seal");

        let other_vault = Id::new([0x12; 16]).expect("id");
        let other_key = manifest_key(&ROOT, &other_vault).expect("derive");
        assert_eq!(
            status(BackupManifest::open(&payload, &other_key, &other_vault)),
            ChurStatus::VaultCorrupt
        );
    }

    /// The manifest and the final commit share one key and differ only in their
    /// domain tag, so neither record opens as the other.
    #[test]
    fn a_final_commit_does_not_open_as_a_manifest() {
        let (vault_id, backup_id) = ids();
        let key = manifest_key(&ROOT, &vault_id).expect("derive");
        let commit = FinalBackupCommit {
            backup_id,
            record_count: 5,
            stream_entry_count: 3,
            slot_entry_count: 2,
            inventory_commitment: [0x5a; 32],
        };
        let sealed = commit
            .seal(&key, &vault_id, &Nonce::new([0x32; 24]))
            .expect("seal");
        assert_eq!(
            FinalBackupCommit::open(&sealed, &key, &vault_id, &backup_id).expect("open"),
            commit
        );
        assert_eq!(
            status(BackupManifest::open(&sealed, &key, &vault_id)),
            ChurStatus::VaultCorrupt
        );

        let manifest_payload = manifest(vault_id, backup_id)
            .seal(&key, &Nonce::new([0x33; 24]))
            .expect("seal");
        assert_eq!(
            status(FinalBackupCommit::open(
                &manifest_payload,
                &key,
                &vault_id,
                &backup_id
            )),
            ChurStatus::VaultCorrupt
        );
    }

    #[test]
    fn an_empty_inventory_commits_to_the_domain_tag_alone() {
        let empty = InventoryCommitter::new();
        assert_eq!(empty.stream_count(), 0);
        assert_eq!(
            empty.finish(),
            chur_crypto::commit::commit(tag::BACKUP_INVENTORY_COMMITMENT, &[])
        );
    }

    /// §7.1 orders every stream entry before every slot entry. A committer that
    /// accepted them interleaved would produce a value no conforming reader
    /// reaches, so the order is enforced rather than assumed.
    #[test]
    fn the_inventory_order_is_enforced_and_reordering_changes_the_commitment() {
        let first = StreamInventoryEntry {
            object_id: Id::new([0x01; 16]).expect("id"),
            stream_id: Id::new([0x02; 16]).expect("id"),
            stream_kind: StreamKind::Original,
            stream_revision: 1,
            ciphertext_length: 1_024,
            manifest_commitment: [0x03; 32],
            ordered_chunk_commitment: [0x04; 32],
        };
        let second = StreamInventoryEntry {
            object_id: Id::new([0x05; 16]).expect("id"),
            ..first
        };
        let slot = SlotInventoryEntry {
            slot_id: Id::new([0x06; 16]).expect("id"),
            slot_type: 0x01,
            slot_generation: 1,
        };

        let mut forward = InventoryCommitter::new();
        forward.add_stream(&first).expect("first");
        forward.add_stream(&second).expect("second");
        forward.add_slot(&slot).expect("slot");

        let mut reversed = InventoryCommitter::new();
        reversed.add_stream(&second).expect("second");
        reversed.add_stream(&first).expect("first");
        reversed.add_slot(&slot).expect("slot");

        assert_ne!(
            forward.finish(),
            reversed.finish(),
            "reordering two entries left the commitment unchanged"
        );
        assert_eq!(forward.stream_count(), 2);
        assert_eq!(forward.slot_count(), 1);

        let mut interleaved = InventoryCommitter::new();
        interleaved.add_slot(&slot).expect("slot");
        assert_eq!(status(interleaved.add_stream(&first)), ChurStatus::Conflict);
    }

    #[test]
    fn a_stream_entry_round_trips_at_its_canonical_length() {
        let entry = StreamInventoryEntry {
            object_id: Id::new([0x41; 16]).expect("id"),
            stream_id: Id::new([0x42; 16]).expect("id"),
            stream_kind: StreamKind::AudioWaveform,
            stream_revision: 9,
            ciphertext_length: 262_144,
            manifest_commitment: [0x43; 32],
            ordered_chunk_commitment: [0x44; 32],
        };
        let bytes = entry.encode();
        assert_eq!(bytes.len(), STREAM_ENTRY_LEN);
        assert_eq!(StreamInventoryEntry::decode(&bytes).expect("decode"), entry);
        assert_eq!(
            status(StreamInventoryEntry::decode(&bytes[..STREAM_ENTRY_LEN - 1])),
            ChurStatus::VaultCorrupt
        );

        let mut unallocated = bytes.clone();
        unallocated[32] = 0x7f;
        assert_eq!(
            status(StreamInventoryEntry::decode(&unallocated)),
            ChurStatus::VaultCorrupt
        );
    }

    #[test]
    fn a_manifest_that_contradicts_itself_is_refused() {
        let (vault_id, backup_id) = ids();
        let bytes = manifest(vault_id, backup_id).encode();

        // A base identifier present under a zero discriminant.
        let mut ghost_base = bytes.clone();
        ghost_base[43] = 0x01;
        assert_eq!(
            status(BackupManifest::decode(&ghost_base)),
            ChurStatus::VaultCorrupt
        );

        // A discriminant that is neither zero nor one.
        let mut discriminant = bytes.clone();
        discriminant[42] = 0x02;
        assert_eq!(
            status(BackupManifest::decode(&discriminant)),
            ChurStatus::VaultCorrupt
        );

        // A `backup_version` differing from the preamble's, §4.
        let mut version = bytes.clone();
        version[17] = 0x02;
        assert_eq!(
            status(BackupManifest::decode(&version)),
            ChurStatus::VaultCorrupt
        );

        assert_eq!(
            status(BackupManifest::decode(&bytes[..bytes.len() - 1])),
            ChurStatus::VaultCorrupt
        );
    }

    #[test]
    fn an_incremental_manifest_keeps_its_base() {
        let (vault_id, backup_id) = ids();
        let base = Id::new([0x77; 16]).expect("id");
        let record = BackupManifest {
            base_backup_id: Some(base),
            ..manifest(vault_id, backup_id)
        };
        let decoded = BackupManifest::decode(&record.encode()).expect("decode");
        assert_eq!(decoded.base_backup_id, Some(base));
    }
}
