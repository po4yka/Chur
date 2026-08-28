//! `ChurObjectV1`, the immutable encrypted object container.
//!
//! `docs/format/OBJECT_CONTAINER_V1.md` freezes the public byte layout, the v1
//! constants, the commitment constructions, and the sealed-record plaintext
//! schemas. This module is that format: a 28-byte preamble, one sealed
//! manifest record, a sequence of chunk records, and one sealed final commit.
//!
//! Two properties shape the code. A reader dispatches on `record_type` before
//! it reads any other field, so a final commit is never parsed with the chunk
//! header. And every non-final chunk carries exactly `chunk_size` plaintext,
//! which gives one plaintext under one chunk size exactly one valid chunking
//! and makes the §12 seek a computation rather than a scan.

use std::io::Write;

use zeroize::Zeroizing;

use chur_core::limits::{COMMITMENT_LEN, ID_LEN, NONCE_LEN, TAG_LEN, container as bounds};
use chur_core::status::ChurStatus;
use chur_core::{Error, Id, Result, ensure};
use chur_crypto::aead::{self, Nonce};
use chur_crypto::commit::{self, Commitment, Committer};
use chur_crypto::kdf::{self, Context, Label};
use chur_crypto::secret::Key;
use chur_crypto::tuple::{Tuple, tag};

use crate::codec::{Reader, Writer};
use crate::constants::{
    CHUNK_RECORD_PROFILE_V1, COMMITMENT_PROFILE_V1, CONTAINER_VERSION_V1, ContainerRecordType,
    ENCODING_PROFILE_V1, FLAGS_V1, MAGIC_OBJECT, MediaClass, RECORD_VERSION_V1, RESERVED_V1,
    SUITE_V1, StreamKind,
};

/// Length of the per-stream-revision chunk nonce prefix, §7.
pub const NONCE_PREFIX_LEN: usize = 16;

/// Offset of the first byte after the preamble.
const AFTER_PREAMBLE: u64 = bounds::PREAMBLE_LEN as u64;

fn corrupt(context: &'static str) -> Error {
    Error::new(ChurStatus::ObjectCorrupt, context)
}

// ---------------------------------------------------------------------------
// Public preamble
// ---------------------------------------------------------------------------

/// `PublicPreambleV1`, the 28 bytes at file offset 0.
///
/// Every field except `manifest_record_length` is a constant compared byte for
/// byte, so the struct carries only the one variable value.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PublicPreamble {
    manifest_record_length: u32,
}

impl PublicPreamble {
    /// Exact encoded length.
    pub const LEN: usize = bounds::PREAMBLE_LEN;

    /// Builds a preamble.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::ObjectCorrupt`] when the length is outside the 40
    /// to 65536 bound of §3.
    pub fn new(manifest_record_length: u32) -> Result<Self> {
        ensure!(
            (bounds::MANIFEST_RECORD_MIN..=bounds::MANIFEST_RECORD_MAX)
                .contains(&manifest_record_length),
            ObjectCorrupt,
            "manifest record length is outside the v1 bound"
        );
        Ok(Self {
            manifest_record_length,
        })
    }

    /// The declared manifest record length.
    #[must_use]
    pub const fn manifest_record_length(&self) -> u32 {
        self.manifest_record_length
    }

    /// Encodes the 28 bytes.
    #[must_use]
    pub fn encode(&self) -> Vec<u8> {
        let mut writer = Writer::with_capacity(Self::LEN);
        writer
            .fixed(&MAGIC_OBJECT)
            .u16(CONTAINER_VERSION_V1)
            .u16(ENCODING_PROFILE_V1)
            .u16(SUITE_V1)
            .u16(FLAGS_V1)
            .u32(Self::LEN as u32)
            .u32(self.manifest_record_length)
            .u16(CHUNK_RECORD_PROFILE_V1)
            .u16(RESERVED_V1);
        debug_assert_eq!(writer.len(), Self::LEN);
        writer.finish()
    }

    /// Decodes the 28 bytes.
    ///
    /// An unknown version, profile, or suite fails as `UNSUPPORTED_*`. A fixed
    /// field holding any other value fails as `OBJECT_CORRUPT` and is never
    /// ignored.
    ///
    /// # Errors
    ///
    /// As described above.
    pub fn decode(bytes: &[u8]) -> Result<Self> {
        let mut reader = Reader::new(bytes, ChurStatus::ObjectCorrupt);
        reader.constant(
            &MAGIC_OBJECT,
            ChurStatus::ObjectCorrupt,
            "wrong container magic",
        )?;
        ensure!(
            reader.u16()? == CONTAINER_VERSION_V1,
            UnsupportedVersion,
            "container version is not supported"
        );
        ensure!(
            reader.u16()? == ENCODING_PROFILE_V1,
            UnsupportedVersion,
            "canonical encoding profile is not supported"
        );
        ensure!(
            reader.u16()? == SUITE_V1,
            UnsupportedSuite,
            "container suite is not supported"
        );
        ensure!(
            reader.u16()? == FLAGS_V1,
            ObjectCorrupt,
            "container flags are not the v1 value"
        );
        ensure!(
            reader.u32()? == Self::LEN as u32,
            ObjectCorrupt,
            "public header length is not 28"
        );
        let manifest_record_length = reader.u32()?;
        ensure!(
            reader.u16()? == CHUNK_RECORD_PROFILE_V1,
            UnsupportedVersion,
            "chunk record profile is not supported"
        );
        ensure!(
            reader.u16()? == RESERVED_V1,
            ObjectCorrupt,
            "container reserved field is not zero"
        );
        Self::new(manifest_record_length)
    }
}

// ---------------------------------------------------------------------------
// Manifest
// ---------------------------------------------------------------------------

/// `MediaPropertiesV1`, the closed 17-byte field list of §5.1.
///
/// These are the only properties the encoded bytes of a stream fix. Filename,
/// MIME type, codec name, capture time, EXIF, GPS, rating, caption, and album
/// membership are mutable private metadata and belong to the catalog.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MediaProperties {
    media_class: MediaClass,
    pixel_width: u32,
    pixel_height: u32,
    duration_ms: u64,
}

impl MediaProperties {
    /// Exact encoded length.
    pub const LEN: usize = 17;

    /// Builds media properties and checks the zero rules of §5.1.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::ObjectCorrupt`] when a dimension is non-zero for a
    /// class that has none, or a duration is non-zero for a class that has none.
    pub fn new(
        media_class: MediaClass,
        pixel_width: u32,
        pixel_height: u32,
        duration_ms: u64,
    ) -> Result<Self> {
        if !media_class.has_pixels() {
            ensure!(
                pixel_width == 0 && pixel_height == 0,
                ObjectCorrupt,
                "media class carries no pixel dimensions but they are non-zero"
            );
        }
        if !media_class.has_duration() {
            ensure!(
                duration_ms == 0,
                ObjectCorrupt,
                "media class carries no duration but it is non-zero"
            );
        }
        Ok(Self {
            media_class,
            pixel_width,
            pixel_height,
            duration_ms,
        })
    }

    /// An opaque stream with no decodable media dimensions.
    #[must_use]
    pub const fn opaque() -> Self {
        Self {
            media_class: MediaClass::Opaque,
            pixel_width: 0,
            pixel_height: 0,
            duration_ms: 0,
        }
    }

    /// The media class.
    #[must_use]
    pub const fn media_class(&self) -> MediaClass {
        self.media_class
    }

    /// The pixel width, zero when the class carries none.
    #[must_use]
    pub const fn pixel_width(&self) -> u32 {
        self.pixel_width
    }

    /// The pixel height, zero when the class carries none.
    #[must_use]
    pub const fn pixel_height(&self) -> u32 {
        self.pixel_height
    }

    /// The duration in milliseconds, zero when the class carries none.
    #[must_use]
    pub const fn duration_ms(&self) -> u64 {
        self.duration_ms
    }

    fn write(&self, writer: &mut Writer) {
        writer
            .u8(self.media_class.value())
            .u32(self.pixel_width)
            .u32(self.pixel_height)
            .u64(self.duration_ms);
    }

    fn read(reader: &mut Reader<'_>) -> Result<Self> {
        let class = MediaClass::from_value(reader.u8()?)
            .ok_or_else(|| corrupt("manifest media class is unallocated"))?;
        let pixel_width = reader.u32()?;
        let pixel_height = reader.u32()?;
        let duration_ms = reader.u64()?;
        Self::new(class, pixel_width, pixel_height, duration_ms)
    }
}

/// `CanonicalManifest`, the sealed plaintext of the manifest record.
///
/// It is exactly 85 bytes for an original stream and 89 for a derived one,
/// because `source_content_revision` is present exactly when `stream_kind` is
/// not [`StreamKind::Original`]. One logical stream therefore has exactly one
/// manifest encoding.
///
/// The manifest never contains the wrapped `ObjectKey`, which is what avoids a
/// circular dependency between the container and its envelope.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CanonicalManifest {
    object_id: Id,
    stream_id: Id,
    stream_kind: StreamKind,
    stream_revision: u32,
    source_content_revision: Option<u32>,
    chunk_size: u32,
    nonce_prefix: [u8; NONCE_PREFIX_LEN],
    manifest_generation: u64,
    media_properties: MediaProperties,
}

impl CanonicalManifest {
    /// Encoded length of a manifest for an original stream.
    pub const LEN_ORIGINAL: usize = bounds::CANONICAL_MANIFEST_ORIGINAL_LEN;
    /// Encoded length of a manifest for a derived stream.
    pub const LEN_DERIVED: usize = bounds::CANONICAL_MANIFEST_DERIVED_LEN;

    /// The length of the manifest record a manifest of this shape produces.
    ///
    /// It is the 24-byte nonce, the sealed plaintext, and the 16-byte tag. The
    /// import journal records this value when the transaction opens, and
    /// §14.1's journaled-length formula reads it back, so it must be derivable
    /// before the record is written.
    #[must_use]
    pub const fn record_length(&self) -> u32 {
        (NONCE_LEN + self.len() + TAG_LEN) as u32
    }

    /// Builds a manifest.
    ///
    /// The four identity fields arrive as one [`StreamIdentity`], which is the
    /// same value a reader must supply to open the container, so a writer and a
    /// reader name the stream the same way.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::ObjectCorrupt`] when `source_content_revision` is
    /// not present exactly for a derived kind, when `stream_revision` is zero,
    /// or when `chunk_size` is outside the §16 range or is not a whole multiple
    /// of 4096.
    pub fn new(
        identity: StreamIdentity,
        source_content_revision: Option<u32>,
        chunk_size: u32,
        nonce_prefix: [u8; NONCE_PREFIX_LEN],
        manifest_generation: u64,
        media_properties: MediaProperties,
    ) -> Result<Self> {
        ensure!(
            source_content_revision.is_some() == identity.stream_kind.is_derived(),
            ObjectCorrupt,
            "source content revision is not present exactly for a derived kind"
        );
        ensure!(
            identity.stream_revision >= 1,
            ObjectCorrupt,
            "stream revision starts at 1"
        );
        check_chunk_size(chunk_size)?;
        Ok(Self {
            object_id: identity.object_id,
            stream_id: identity.stream_id,
            stream_kind: identity.stream_kind,
            stream_revision: identity.stream_revision,
            source_content_revision,
            chunk_size,
            nonce_prefix,
            manifest_generation,
            media_properties,
        })
    }

    /// The encoded length of this manifest: 85 or 89 bytes.
    #[must_use]
    pub const fn len(&self) -> usize {
        if self.stream_kind.is_derived() {
            Self::LEN_DERIVED
        } else {
            Self::LEN_ORIGINAL
        }
    }

    /// Always false: a manifest is never empty.
    #[must_use]
    pub const fn is_empty(&self) -> bool {
        false
    }

    /// Encodes the sealed plaintext.
    #[must_use]
    pub fn encode(&self) -> Vec<u8> {
        let mut writer = Writer::with_capacity(self.len());
        writer
            .id(&self.object_id)
            .id(&self.stream_id)
            .u8(self.stream_kind.value())
            .u32(self.stream_revision);
        match self.source_content_revision {
            Some(revision) => {
                writer.presence(true).u32(revision);
            }
            None => {
                writer.presence(false);
            }
        }
        writer
            .u32(self.chunk_size)
            .fixed(&self.nonce_prefix)
            .u64(self.manifest_generation);
        self.media_properties.write(&mut writer);
        writer.u16(COMMITMENT_PROFILE_V1);
        debug_assert_eq!(writer.len(), self.len());
        writer.finish()
    }

    /// Decodes the sealed plaintext.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::ObjectCorrupt`] for any field that violates §5, and
    /// [`ChurStatus::NonCanonicalEncoding`] for a bad presence byte or trailing
    /// bytes.
    pub fn decode(bytes: &[u8]) -> Result<Self> {
        let mut reader = Reader::new(bytes, ChurStatus::ObjectCorrupt);
        let object_id = reader.id()?;
        let stream_id = reader.id()?;
        let stream_kind = StreamKind::from_value(reader.u8()?)
            .ok_or_else(|| corrupt("manifest stream kind is unallocated"))?;
        let stream_revision = reader.u32()?;
        let source_content_revision = if reader.presence()? {
            Some(reader.u32()?)
        } else {
            None
        };
        let chunk_size = reader.u32()?;
        let nonce_prefix = reader.fixed::<NONCE_PREFIX_LEN>()?;
        let manifest_generation = reader.u64()?;
        let media_properties = MediaProperties::read(&mut reader)?;
        ensure!(
            reader.u16()? == COMMITMENT_PROFILE_V1,
            UnsupportedVersion,
            "manifest commitment profile is not supported"
        );
        reader.finish()?;
        Self::new(
            StreamIdentity {
                object_id,
                stream_id,
                stream_kind,
                stream_revision,
            },
            source_content_revision,
            chunk_size,
            nonce_prefix,
            manifest_generation,
            media_properties,
        )
    }

    /// The object this stream belongs to.
    #[must_use]
    pub const fn object_id(&self) -> &Id {
        &self.object_id
    }

    /// The stream identifier.
    #[must_use]
    pub const fn stream_id(&self) -> &Id {
        &self.stream_id
    }

    /// The stream kind.
    #[must_use]
    pub const fn stream_kind(&self) -> StreamKind {
        self.stream_kind
    }

    /// The stream revision.
    #[must_use]
    pub const fn stream_revision(&self) -> u32 {
        self.stream_revision
    }

    /// The source content revision, present exactly for a derived kind.
    #[must_use]
    pub const fn source_content_revision(&self) -> Option<u32> {
        self.source_content_revision
    }

    /// The chunk size of this stream revision.
    #[must_use]
    pub const fn chunk_size(&self) -> u32 {
        self.chunk_size
    }

    /// The nonce prefix of this stream revision.
    #[must_use]
    pub const fn nonce_prefix(&self) -> &[u8; NONCE_PREFIX_LEN] {
        &self.nonce_prefix
    }

    /// The manifest generation.
    #[must_use]
    pub const fn manifest_generation(&self) -> u64 {
        self.manifest_generation
    }

    /// The immutable media properties.
    #[must_use]
    pub const fn media_properties(&self) -> &MediaProperties {
        &self.media_properties
    }

    /// The §32 manifest AAD, exactly 66 bytes.
    #[must_use]
    pub fn aad(&self) -> Vec<u8> {
        Tuple::new(tag::OBJECT_MANIFEST_AAD)
            .id(&self.object_id)
            .id(&self.stream_id)
            .u8(self.stream_kind.value())
            .u32(self.stream_revision)
            .u16(SUITE_V1)
            .finish()
    }

    /// The HKDF context every object-domain key of this stream takes.
    #[must_use]
    pub fn key_context(&self) -> Context {
        Context::container_stream(
            &self.object_id,
            &self.stream_id,
            self.stream_kind.value(),
            self.stream_revision,
        )
    }
}

fn check_chunk_size(chunk_size: u32) -> Result<()> {
    ensure!(
        (bounds::CHUNK_SIZE_MIN..=bounds::CHUNK_SIZE_MAX).contains(&chunk_size),
        ObjectCorrupt,
        "chunk size is outside the v1 range"
    );
    ensure!(
        chunk_size.is_multiple_of(bounds::CHUNK_SIZE_MULTIPLE),
        ObjectCorrupt,
        "chunk size is not a whole multiple of 4096"
    );
    Ok(())
}

/// The BLAKE3-256 commitment over a sealed manifest record, §5.
///
/// It covers the sealed record, so it is computable before any key is
/// available, which is what lets a locked object be structurally verified. It
/// is public and is not evidence of authenticity: a substituted manifest record
/// carries its own matching commitment.
#[must_use]
pub fn manifest_commitment(manifest_nonce: &Nonce, ciphertext_and_tag: &[u8]) -> Commitment {
    commit::commit(
        tag::OBJECT_MANIFEST_COMMITMENT,
        &[manifest_nonce.as_bytes(), ciphertext_and_tag],
    )
}

// ---------------------------------------------------------------------------
// Chunk records
// ---------------------------------------------------------------------------

/// The 20-byte `ChunkRecordV1` header of §8.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ChunkHeader {
    chunk_index: u64,
    plaintext_length: u32,
    ciphertext_length: u32,
}

impl ChunkHeader {
    /// Exact encoded length.
    pub const LEN: usize = bounds::CHUNK_HEADER_LEN;

    /// Builds a header for one chunk.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::ObjectCorrupt`] when the ciphertext length is not
    /// the plaintext length plus one tag.
    pub fn new(chunk_index: u64, plaintext_length: u32) -> Result<Self> {
        let ciphertext_length = plaintext_length
            .checked_add(TAG_LEN as u32)
            .ok_or_else(|| corrupt("chunk ciphertext length overflows u32"))?;
        Ok(Self {
            chunk_index,
            plaintext_length,
            ciphertext_length,
        })
    }

    /// The chunk index.
    #[must_use]
    pub const fn chunk_index(&self) -> u64 {
        self.chunk_index
    }

    /// The plaintext length.
    #[must_use]
    pub const fn plaintext_length(&self) -> u32 {
        self.plaintext_length
    }

    /// The ciphertext length, including the tag.
    #[must_use]
    pub const fn ciphertext_length(&self) -> u32 {
        self.ciphertext_length
    }

    /// The whole record length: header plus ciphertext.
    #[must_use]
    pub const fn record_length(&self) -> u64 {
        Self::LEN as u64 + self.ciphertext_length as u64
    }

    /// Encodes the header.
    #[must_use]
    pub fn encode(&self) -> Vec<u8> {
        let mut writer = Writer::with_capacity(Self::LEN);
        writer
            .u8(ContainerRecordType::Chunk.value())
            .u8(RECORD_VERSION_V1)
            .u16(RESERVED_V1)
            .u64(self.chunk_index)
            .u32(self.plaintext_length)
            .u32(self.ciphertext_length);
        debug_assert_eq!(writer.len(), Self::LEN);
        writer.finish()
    }
}

/// The 32-byte `FinalCommitRecordV1` header of §11.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct FinalCommitHeader {
    commit_ciphertext_length: u32,
    commit_nonce: Nonce,
}

impl FinalCommitHeader {
    const LEN: usize = bounds::COMMIT_HEADER_LEN;

    fn encode(&self) -> Vec<u8> {
        let mut writer = Writer::with_capacity(Self::LEN);
        writer
            .u8(ContainerRecordType::FinalCommit.value())
            .u8(RECORD_VERSION_V1)
            .u16(RESERVED_V1)
            .u32(self.commit_ciphertext_length)
            .fixed(self.commit_nonce.as_bytes());
        debug_assert_eq!(writer.len(), Self::LEN);
        writer.finish()
    }
}

/// The ordered chunk commitment of §10, over exact wire bytes.
///
/// The `FinalCommitRecordV1` is never fed to this hasher. For a zero-chunk
/// object the value is BLAKE3-256 of the domain tag alone.
#[must_use]
pub fn ordered_commitment_of(chunk_records: &[&[u8]]) -> Commitment {
    commit::commit(tag::OBJECT_ORDERED_COMMITMENT, chunk_records)
}

// ---------------------------------------------------------------------------
// Final commit
// ---------------------------------------------------------------------------

/// `CanonicalFinalCommit`, the sealed plaintext of the final commit record.
///
/// It has no optional field, so it is exactly 128 bytes and
/// `commit_ciphertext_length` is 144 for suite `0x0001`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CanonicalFinalCommit {
    object_id: Id,
    stream_id: Id,
    stream_revision: u32,
    manifest_commitment: Commitment,
    chunk_count: u64,
    total_plaintext_length: u64,
    last_chunk_plaintext_length: u32,
    ordered_chunk_commitment: Commitment,
    commit_generation: u64,
}

impl CanonicalFinalCommit {
    /// Exact encoded length.
    pub const LEN: usize = bounds::CANONICAL_FINAL_COMMIT_LEN;

    /// The encoded length, always [`CanonicalFinalCommit::LEN`].
    #[must_use]
    pub const fn len(&self) -> usize {
        Self::LEN
    }

    /// Always false: a final commit is never empty.
    #[must_use]
    pub const fn is_empty(&self) -> bool {
        false
    }

    /// Encodes the sealed plaintext.
    #[must_use]
    pub fn encode(&self) -> Vec<u8> {
        let mut writer = Writer::with_capacity(Self::LEN);
        writer
            .id(&self.object_id)
            .id(&self.stream_id)
            .u32(self.stream_revision)
            .fixed(&self.manifest_commitment)
            .u64(self.chunk_count)
            .u64(self.total_plaintext_length)
            .u32(self.last_chunk_plaintext_length)
            .fixed(&self.ordered_chunk_commitment)
            .u64(self.commit_generation);
        debug_assert_eq!(writer.len(), Self::LEN);
        writer.finish()
    }

    /// Decodes the sealed plaintext.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::ObjectCorrupt`] for a bound violation and
    /// [`ChurStatus::NonCanonicalEncoding`] for trailing bytes.
    pub fn decode(bytes: &[u8]) -> Result<Self> {
        let mut reader = Reader::new(bytes, ChurStatus::ObjectCorrupt);
        let object_id = reader.id()?;
        let stream_id = reader.id()?;
        let stream_revision = reader.u32()?;
        let manifest_commitment = reader.fixed::<COMMITMENT_LEN>()?;
        let chunk_count = reader.u64()?;
        let total_plaintext_length = reader.u64()?;
        let last_chunk_plaintext_length = reader.u32()?;
        let ordered_chunk_commitment = reader.fixed::<COMMITMENT_LEN>()?;
        let commit_generation = reader.u64()?;
        reader.finish()?;
        ensure!(
            chunk_count <= bounds::CHUNK_COUNT_MAX,
            ObjectCorrupt,
            "chunk count exceeds the v1 maximum"
        );
        ensure!(
            total_plaintext_length <= bounds::TOTAL_PLAINTEXT_MAX,
            ObjectCorrupt,
            "total plaintext length exceeds the v1 maximum"
        );
        Ok(Self {
            object_id,
            stream_id,
            stream_revision,
            manifest_commitment,
            chunk_count,
            total_plaintext_length,
            last_chunk_plaintext_length,
            ordered_chunk_commitment,
            commit_generation,
        })
    }

    /// The number of chunk records.
    #[must_use]
    pub const fn chunk_count(&self) -> u64 {
        self.chunk_count
    }

    /// The total plaintext length of the stream.
    #[must_use]
    pub const fn total_plaintext_length(&self) -> u64 {
        self.total_plaintext_length
    }

    /// The plaintext length of the last chunk, zero for an empty object.
    #[must_use]
    pub const fn last_chunk_plaintext_length(&self) -> u32 {
        self.last_chunk_plaintext_length
    }

    /// The manifest commitment this commit is bound to.
    #[must_use]
    pub const fn manifest_commitment(&self) -> &Commitment {
        &self.manifest_commitment
    }

    /// The ordered chunk commitment.
    #[must_use]
    pub const fn ordered_chunk_commitment(&self) -> &Commitment {
        &self.ordered_chunk_commitment
    }

    /// The commit generation.
    #[must_use]
    pub const fn commit_generation(&self) -> u64 {
        self.commit_generation
    }
}

fn final_commit_aad(
    object_id: &Id,
    stream_id: &Id,
    stream_kind: StreamKind,
    stream_revision: u32,
    manifest_commitment: &Commitment,
) -> Vec<u8> {
    Tuple::new(tag::OBJECT_FINAL_COMMIT_AAD)
        .id(object_id)
        .id(stream_id)
        .u8(stream_kind.value())
        .u32(stream_revision)
        .fixed(manifest_commitment)
        .u16(SUITE_V1)
        .finish()
}

fn chunk_aad(
    manifest: &CanonicalManifest,
    manifest_commitment: &Commitment,
    chunk_index: u64,
    plaintext_length: u32,
) -> Vec<u8> {
    Tuple::new(tag::OBJECT_CHUNK_AAD)
        .u16(CONTAINER_VERSION_V1)
        .u16(SUITE_V1)
        .id(manifest.object_id())
        .id(manifest.stream_id())
        .u8(manifest.stream_kind().value())
        .u32(manifest.stream_revision())
        .fixed(manifest_commitment)
        .u64(chunk_index)
        .u32(plaintext_length)
        .finish()
}

/// The three record keys of one container stream.
///
/// They derive from the object key under the three container labels, each
/// bound to the object, the stream, its kind, and its revision.
pub struct StreamKeys {
    manifest: Key,
    content: Key,
    final_commit: Key,
}

impl StreamKeys {
    /// Derives the three keys.
    ///
    /// # Errors
    ///
    /// Returns an error only if a derivation itself fails.
    pub fn derive(object_key: &Key, manifest: &CanonicalManifest) -> Result<Self> {
        let context = manifest.key_context();
        Ok(Self {
            manifest: kdf::derive_from(object_key, Label::ObjectManifest, &context)?,
            content: kdf::derive_from(object_key, Label::ObjectContent, &context)?,
            final_commit: kdf::derive_from(object_key, Label::ObjectFinalCommit, &context)?,
        })
    }
}

// ---------------------------------------------------------------------------
// Writer
// ---------------------------------------------------------------------------

/// Streams one container to a sink.
///
/// The writer holds one chunk of plaintext at a time and never the whole
/// container, so a multi-gigabyte object stays bounded in memory. It computes
/// the ordered chunk commitment incrementally, over the exact wire bytes it
/// emits.
pub struct ContainerWriter<W: Write> {
    sink: W,
    manifest: CanonicalManifest,
    manifest_commitment: Commitment,
    keys: StreamKeys,
    committer: Committer,
    chunk_count: u64,
    total_plaintext_length: u64,
    last_chunk_plaintext_length: u32,
    finished: bool,
}

impl<W: Write> ContainerWriter<W> {
    /// Writes the preamble and the sealed manifest record, and returns a writer
    /// positioned at chunk index 0.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::IoFailure`] when the sink rejects a write, and an
    /// AEAD or derivation error otherwise.
    pub fn start(
        mut sink: W,
        object_key: &Key,
        manifest: CanonicalManifest,
        manifest_nonce: Nonce,
    ) -> Result<Self> {
        let keys = StreamKeys::derive(object_key, &manifest)?;
        let sealed = aead::seal(
            &keys.manifest,
            &manifest_nonce,
            &manifest.encode(),
            &manifest.aad(),
        )?;
        let manifest_record_length = u32::try_from(NONCE_LEN + sealed.len())
            .map_err(|_| corrupt("manifest record length overflows u32"))?;
        let preamble = PublicPreamble::new(manifest_record_length)?;
        write_all(&mut sink, &preamble.encode())?;
        write_all(&mut sink, manifest_nonce.as_bytes())?;
        write_all(&mut sink, &sealed)?;
        let manifest_commitment = manifest_commitment(&manifest_nonce, &sealed);
        Ok(Self {
            sink,
            manifest,
            manifest_commitment,
            keys,
            committer: Committer::new(tag::OBJECT_ORDERED_COMMITMENT),
            chunk_count: 0,
            total_plaintext_length: 0,
            last_chunk_plaintext_length: 0,
            finished: false,
        })
    }

    /// Takes the sink back without finishing the container.
    ///
    /// It is the abandonment path of §14.4: the transaction is dead, so the
    /// bytes written so far are discarded rather than committed, and the caller
    /// needs the handle to delete them.
    pub fn into_sink(self) -> W {
        self.sink
    }

    /// The sink, so a caller that owns durability can act between records.
    ///
    /// `docs/format/OBJECT_CONTAINER_V1.md` §14.2 requires an fsync of the
    /// container between chunk records, and the writer cannot perform it: a
    /// `Write` sink has no durability operation. The importer therefore reaches
    /// the sink here rather than reimplementing the record encoders.
    pub fn sink_mut(&mut self) -> &mut W {
        &mut self.sink
    }

    /// The number of chunk records written so far.
    #[must_use]
    pub const fn chunk_count(&self) -> u64 {
        self.chunk_count
    }

    /// The plaintext length written so far.
    #[must_use]
    pub const fn total_plaintext_length(&self) -> u64 {
        self.total_plaintext_length
    }

    /// The length of the last chunk written, zero before the first.
    #[must_use]
    pub const fn last_chunk_plaintext_length(&self) -> u32 {
        self.last_chunk_plaintext_length
    }

    /// Seals and writes one chunk.
    ///
    /// Every chunk except the last carries exactly `chunk_size` plaintext, so
    /// the caller feeds full chunks and one shorter final chunk. A short chunk
    /// followed by another chunk is rejected here rather than producing a
    /// container no reader accepts.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::InvalidInput`] for an empty chunk, a chunk longer
    /// than `chunk_size`, or a chunk after a short one; and
    /// [`ChurStatus::ResourceLimitExceeded`] when a bound of §16 would be
    /// exceeded.
    pub fn write_chunk(&mut self, plaintext: &[u8]) -> Result<()> {
        ensure!(
            !self.finished,
            InvalidInput,
            "container is already committed"
        );
        ensure!(
            !plaintext.is_empty(),
            InvalidInput,
            "a chunk carries at least one byte"
        );
        let chunk_size = self.manifest.chunk_size();
        let plaintext_length =
            u32::try_from(plaintext.len()).map_err(|_| corrupt("chunk exceeds u32"))?;
        ensure!(
            plaintext_length <= chunk_size,
            InvalidInput,
            "chunk plaintext exceeds the manifest chunk size"
        );
        ensure!(
            self.last_chunk_plaintext_length == 0 || self.last_chunk_plaintext_length == chunk_size,
            InvalidInput,
            "a short chunk must be the last chunk"
        );
        ensure!(
            self.chunk_count < bounds::CHUNK_COUNT_MAX,
            ResourceLimitExceeded,
            "chunk count would exceed the v1 maximum"
        );
        let total = self
            .total_plaintext_length
            .checked_add(u64::from(plaintext_length))
            .ok_or_else(|| corrupt("total plaintext length overflows u64"))?;
        ensure!(
            total <= bounds::TOTAL_PLAINTEXT_MAX,
            ResourceLimitExceeded,
            "total plaintext length would exceed the v1 maximum"
        );

        let index = self.chunk_count;
        let aad = chunk_aad(
            &self.manifest,
            &self.manifest_commitment,
            index,
            plaintext_length,
        );
        let nonce = Nonce::chunk(self.manifest.nonce_prefix(), index);
        let sealed = aead::seal(&self.keys.content, &nonce, plaintext, &aad)?;
        let header = ChunkHeader::new(index, plaintext_length)?;
        let encoded_header = header.encode();

        self.committer.update(&encoded_header);
        self.committer.update(&sealed);
        write_all(&mut self.sink, &encoded_header)?;
        write_all(&mut self.sink, &sealed)?;

        self.chunk_count = index + 1;
        self.total_plaintext_length = total;
        self.last_chunk_plaintext_length = plaintext_length;
        Ok(())
    }

    /// Seals and writes the final commit, closing the container.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::IoFailure`] when the sink rejects a write, and an
    /// AEAD error otherwise.
    pub fn finish(mut self, commit_nonce: Nonce, commit_generation: u64) -> Result<W> {
        ensure!(
            !self.finished,
            InvalidInput,
            "container is already committed"
        );
        let commit = CanonicalFinalCommit {
            object_id: *self.manifest.object_id(),
            stream_id: *self.manifest.stream_id(),
            stream_revision: self.manifest.stream_revision(),
            manifest_commitment: self.manifest_commitment,
            chunk_count: self.chunk_count,
            total_plaintext_length: self.total_plaintext_length,
            last_chunk_plaintext_length: self.last_chunk_plaintext_length,
            ordered_chunk_commitment: self.committer.finish(),
            commit_generation,
        };
        let aad = final_commit_aad(
            self.manifest.object_id(),
            self.manifest.stream_id(),
            self.manifest.stream_kind(),
            self.manifest.stream_revision(),
            &self.manifest_commitment,
        );
        let sealed = aead::seal(
            &self.keys.final_commit,
            &commit_nonce,
            &commit.encode(),
            &aad,
        )?;
        let header = FinalCommitHeader {
            commit_ciphertext_length: u32::try_from(sealed.len())
                .map_err(|_| corrupt("commit ciphertext length overflows u32"))?,
            commit_nonce,
        };
        write_all(&mut self.sink, &header.encode())?;
        write_all(&mut self.sink, &sealed)?;
        self.finished = true;
        Ok(self.sink)
    }

    /// The commitment over the manifest record this container carries.
    #[must_use]
    pub const fn manifest_commitment(&self) -> &Commitment {
        &self.manifest_commitment
    }
}

fn write_all<W: Write>(sink: &mut W, bytes: &[u8]) -> Result<()> {
    sink.write_all(bytes)
        .map_err(|_| Error::new(ChurStatus::IoFailure, "container sink rejected a write"))
}

/// Encodes a whole container into a byte vector.
///
/// It exists for vectors and tests. Production import streams to a file
/// through [`ContainerWriter`] under the journal ordering of §14.2.
///
/// # Errors
///
/// As [`ContainerWriter::start`], [`ContainerWriter::write_chunk`], and
/// [`ContainerWriter::finish`].
pub fn encode_container(
    object_key: &Key,
    manifest: CanonicalManifest,
    manifest_nonce: Nonce,
    plaintext: &[u8],
    commit_nonce: Nonce,
    commit_generation: u64,
) -> Result<Vec<u8>> {
    let chunk_size = manifest.chunk_size() as usize;
    let mut writer = ContainerWriter::start(Vec::new(), object_key, manifest, manifest_nonce)?;
    for chunk in plaintext.chunks(chunk_size) {
        writer.write_chunk(chunk)?;
    }
    writer.finish(commit_nonce, commit_generation)
}

// ---------------------------------------------------------------------------
// Reader
// ---------------------------------------------------------------------------

/// The identity of the stream a reader expects to find.
///
/// `ManifestKey` binds the object, the stream, its kind, and its revision
/// through the HKDF context of [ADR-0034], and the manifest AAD binds the same
/// four values. Those fields are inside the sealed manifest, so they are not
/// recoverable from container bytes: a reader supplies them from the catalog
/// row and the object-key envelope that sent it to this file.
///
/// That is a property rather than a limitation. A container substituted for
/// another stream authenticates under neither the key nor the AAD of the stream
/// the caller asked for, so a swap in the object store is caught by the first
/// AEAD rather than by a later consistency check.
///
/// [ADR-0034]: https://github.com/po4yka/Chur/blob/main/docs/adr/0034-freeze-the-hkdf-context-element-lists.md
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct StreamIdentity {
    /// The object the stream belongs to.
    pub object_id: Id,
    /// The stream identifier.
    pub stream_id: Id,
    /// The stream kind.
    pub stream_kind: StreamKind,
    /// The stream revision.
    pub stream_revision: u32,
}

impl StreamIdentity {
    /// The identity a manifest declares.
    #[must_use]
    pub fn of(manifest: &CanonicalManifest) -> Self {
        Self {
            object_id: *manifest.object_id(),
            stream_id: *manifest.stream_id(),
            stream_kind: manifest.stream_kind(),
            stream_revision: manifest.stream_revision(),
        }
    }

    fn key_context(&self) -> Context {
        Context::container_stream(
            &self.object_id,
            &self.stream_id,
            self.stream_kind.value(),
            self.stream_revision,
        )
    }

    fn manifest_aad(&self) -> Vec<u8> {
        Tuple::new(tag::OBJECT_MANIFEST_AAD)
            .id(&self.object_id)
            .id(&self.stream_id)
            .u8(self.stream_kind.value())
            .u32(self.stream_revision)
            .u16(SUITE_V1)
            .finish()
    }
}

/// Where each record of a container begins.
///
/// Building it validates every header without decrypting anything and without
/// hashing any body, which is what §3 and §8 mean by structural checks that
/// need no key.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Layout {
    manifest_record_length: u32,
    first_chunk_offset: u64,
    chunk_count: u64,
    declared_plaintext_length: u64,
    last_chunk_plaintext_length: u32,
    final_commit_offset: Option<u64>,
    final_commit_ciphertext_length: u32,
}

impl Layout {
    /// Walks the record sequence and validates every header.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::ObjectCorrupt`] for a malformed preamble or
    /// record, and `UNSUPPORTED_*` for an unknown version, profile, or suite.
    pub fn parse(bytes: &[u8]) -> Result<Self> {
        let preamble = PublicPreamble::decode(
            bytes
                .get(..PublicPreamble::LEN)
                .ok_or_else(|| corrupt("container is shorter than its preamble"))?,
        )?;
        let manifest_record_length = preamble.manifest_record_length();
        let first_chunk_offset = AFTER_PREAMBLE + u64::from(manifest_record_length);
        ensure!(
            first_chunk_offset <= bytes.len() as u64,
            ObjectCorrupt,
            "container ends inside its manifest record"
        );

        let mut position = first_chunk_offset;
        let mut chunk_count = 0u64;
        let mut declared_plaintext_length = 0u64;
        let mut last_chunk_plaintext_length = 0u32;
        let mut final_commit_offset = None;
        let mut final_commit_ciphertext_length = 0u32;

        while position < bytes.len() as u64 {
            match ContainerRecordType::from_value(byte_at(bytes, position)?)
                .ok_or_else(|| corrupt("container record type is unallocated"))?
            {
                ContainerRecordType::Chunk => {
                    ensure!(
                        final_commit_offset.is_none(),
                        ObjectCorrupt,
                        "a chunk record follows the final commit record"
                    );
                    let header = read_chunk_header(bytes, position, chunk_count)?;
                    ensure!(
                        chunk_count < bounds::CHUNK_COUNT_MAX,
                        ObjectCorrupt,
                        "chunk count exceeds the v1 maximum"
                    );
                    declared_plaintext_length = declared_plaintext_length
                        .checked_add(u64::from(header.plaintext_length()))
                        .ok_or_else(|| corrupt("declared plaintext length overflows u64"))?;
                    last_chunk_plaintext_length = header.plaintext_length();
                    chunk_count += 1;
                    position += header.record_length();
                }
                ContainerRecordType::FinalCommit => {
                    let length = read_final_commit_length(bytes, position)?;
                    let end = position
                        .checked_add(FinalCommitHeader::LEN as u64 + u64::from(length))
                        .ok_or_else(|| corrupt("final commit end overflows u64"))?;
                    ensure!(
                        end == bytes.len() as u64,
                        ObjectCorrupt,
                        "bytes follow the final commit record"
                    );
                    final_commit_offset = Some(position);
                    final_commit_ciphertext_length = length;
                    position = end;
                }
            }
        }

        Ok(Self {
            manifest_record_length,
            first_chunk_offset,
            chunk_count,
            declared_plaintext_length,
            last_chunk_plaintext_length,
            final_commit_offset,
            final_commit_ciphertext_length,
        })
    }

    /// The declared manifest record length.
    #[must_use]
    pub const fn manifest_record_length(&self) -> u32 {
        self.manifest_record_length
    }

    /// The offset of chunk record 0.
    #[must_use]
    pub const fn first_chunk_offset(&self) -> u64 {
        self.first_chunk_offset
    }

    /// The number of chunk records found.
    #[must_use]
    pub const fn chunk_count(&self) -> u64 {
        self.chunk_count
    }

    /// The sum of the plaintext lengths the chunk headers declare.
    #[must_use]
    pub const fn declared_plaintext_length(&self) -> u64 {
        self.declared_plaintext_length
    }

    /// The plaintext length of the last chunk record, zero when there is none.
    #[must_use]
    pub const fn last_chunk_plaintext_length(&self) -> u32 {
        self.last_chunk_plaintext_length
    }

    /// Whether a well-formed final commit record closes the file.
    #[must_use]
    pub const fn has_final_commit(&self) -> bool {
        self.final_commit_offset.is_some()
    }

    /// The ordered chunk commitment over the records as written, §10.
    ///
    /// The final commit record is never fed to the hasher. For a zero-chunk
    /// object the value is BLAKE3-256 of the domain tag alone.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::ObjectCorrupt`] when a record does not fit.
    pub fn ordered_chunk_commitment(&self, bytes: &[u8]) -> Result<Commitment> {
        let mut committer = Committer::new(tag::OBJECT_ORDERED_COMMITMENT);
        let mut position = self.first_chunk_offset;
        for index in 0..self.chunk_count {
            let header = read_chunk_header(bytes, position, index)?;
            let end = position + header.record_length();
            committer.update(slice_at(bytes, position, end)?);
            position = end;
        }
        Ok(committer.finish())
    }
}

/// Reads one container from a byte slice.
///
/// Opening authenticates the manifest, so every later operation works from
/// trusted structure. Nothing here returns plaintext whose tag has not verified.
pub struct ContainerReader<'a> {
    bytes: &'a [u8],
    manifest: CanonicalManifest,
    manifest_commitment: Commitment,
    keys: StreamKeys,
    layout: Layout,
}

impl<'a> ContainerReader<'a> {
    /// Opens a container and authenticates its manifest.
    ///
    /// `identity` is what the catalog says this file holds. The manifest key
    /// and the manifest AAD both derive from it, so a container written for
    /// another stream fails here rather than later.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::ObjectCorrupt`] when the manifest does not
    /// authenticate, when the file is malformed, or when the sealed manifest
    /// contradicts the identity it was opened under.
    pub fn open(bytes: &'a [u8], object_key: &Key, identity: &StreamIdentity) -> Result<Self> {
        let layout = Layout::parse(bytes)?;
        let manifest_record = slice_at(bytes, AFTER_PREAMBLE, layout.first_chunk_offset)?;
        let manifest_nonce = Nonce::from_slice(
            manifest_record
                .get(..NONCE_LEN)
                .ok_or_else(|| corrupt("manifest record is shorter than its nonce"))?,
        )?;
        let sealed = &manifest_record[NONCE_LEN..];
        let manifest_commitment = manifest_commitment(&manifest_nonce, sealed);

        let manifest_key =
            kdf::derive_from(object_key, Label::ObjectManifest, &identity.key_context())?;
        let plaintext = aead::open(
            &manifest_key,
            &manifest_nonce,
            sealed,
            &identity.manifest_aad(),
        )?;
        let manifest = CanonicalManifest::decode(&plaintext)?;
        ensure!(
            StreamIdentity::of(&manifest) == *identity,
            ObjectCorrupt,
            "sealed manifest contradicts the identity it was opened under"
        );
        let keys = StreamKeys::derive(object_key, &manifest)?;
        Ok(Self {
            bytes,
            manifest,
            manifest_commitment,
            keys,
            layout,
        })
    }

    /// The authenticated manifest.
    #[must_use]
    pub const fn manifest(&self) -> &CanonicalManifest {
        &self.manifest
    }

    /// The commitment over this container's manifest record.
    #[must_use]
    pub const fn manifest_commitment(&self) -> &Commitment {
        &self.manifest_commitment
    }

    /// The validated record layout.
    #[must_use]
    pub const fn layout(&self) -> &Layout {
        &self.layout
    }

    /// The offset of chunk record `chunk_index`, by the §12 formula.
    ///
    /// It is a computation rather than a scan because §8 fixes every non-final
    /// chunk at `chunk_size`. It is defined for an index below the chunk count;
    /// the final commit record does not sit at the offset this returns for the
    /// count itself, because the last chunk may be short.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::ObjectCorrupt`] when the arithmetic overflows.
    pub fn record_offset(&self, chunk_index: u64) -> Result<u64> {
        let stride =
            u64::from(self.manifest.chunk_size()) + ChunkHeader::LEN as u64 + TAG_LEN as u64;
        chunk_index
            .checked_mul(stride)
            .and_then(|skip| self.layout.first_chunk_offset.checked_add(skip))
            .ok_or_else(|| corrupt("chunk record offset overflows u64"))
    }

    /// Reads and authenticates one chunk.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::ObjectIncomplete`] when the record is absent and
    /// [`ChurStatus::ObjectCorrupt`] when it is malformed or does not verify.
    pub fn read_chunk(&self, chunk_index: u64) -> Result<Zeroizing<Vec<u8>>> {
        ensure!(
            chunk_index < self.layout.chunk_count,
            ObjectIncomplete,
            "chunk record is past the last record in the container"
        );
        let offset = self.record_offset(chunk_index)?;
        let header = read_chunk_header(self.bytes, offset, chunk_index)?;
        let body_start = offset + ChunkHeader::LEN as u64;
        let body = slice_at(
            self.bytes,
            body_start,
            body_start + u64::from(header.ciphertext_length()),
        )?;
        let aad = chunk_aad(
            &self.manifest,
            &self.manifest_commitment,
            chunk_index,
            header.plaintext_length(),
        );
        let nonce = Nonce::chunk(self.manifest.nonce_prefix(), chunk_index);
        aead::open(&self.keys.content, &nonce, body, &aad)
    }

    /// Reads and authenticates the final commit.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::ObjectIncomplete`] when no final commit record is
    /// present, and [`ChurStatus::ObjectCorrupt`] when it is malformed, does
    /// not authenticate, or disagrees with the manifest.
    pub fn read_final_commit(&self) -> Result<CanonicalFinalCommit> {
        let offset = self.layout.final_commit_offset.ok_or_else(|| {
            Error::new(
                ChurStatus::ObjectIncomplete,
                "container carries no final commit record",
            )
        })?;
        let nonce_start = offset + 8;
        let nonce = Nonce::from_slice(slice_at(
            self.bytes,
            nonce_start,
            nonce_start + NONCE_LEN as u64,
        )?)?;
        let body_start = offset + FinalCommitHeader::LEN as u64;
        let body = slice_at(
            self.bytes,
            body_start,
            body_start + u64::from(self.layout.final_commit_ciphertext_length),
        )?;
        let aad = final_commit_aad(
            self.manifest.object_id(),
            self.manifest.stream_id(),
            self.manifest.stream_kind(),
            self.manifest.stream_revision(),
            &self.manifest_commitment,
        );
        let plaintext = aead::open(&self.keys.final_commit, &nonce, body, &aad)?;
        let commit = CanonicalFinalCommit::decode(&plaintext)?;
        check_final_commit(&commit, &self.manifest, &self.manifest_commitment)?;
        Ok(commit)
    }

    /// Authenticates the manifest, every chunk, both commitments, and the final
    /// commit, and returns the authenticated plaintext length.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::ObjectIncomplete`] for a missing final commit and
    /// [`ChurStatus::ObjectCorrupt`] for any authentication, commitment, or
    /// canonical-chunking failure.
    pub fn verify_complete(&self) -> Result<u64> {
        let commit = self.read_final_commit()?;
        ensure!(
            self.layout.chunk_count == commit.chunk_count,
            ObjectCorrupt,
            "chunk record count disagrees with the final commit"
        );
        ensure!(
            self.layout.declared_plaintext_length == commit.total_plaintext_length,
            ObjectCorrupt,
            "declared plaintext lengths disagree with the final commit"
        );
        ensure!(
            self.layout.ordered_chunk_commitment(self.bytes)? == commit.ordered_chunk_commitment,
            ObjectCorrupt,
            "ordered chunk commitment disagrees with the final commit"
        );
        let chunk_size = u64::from(self.manifest.chunk_size());
        for index in 0..commit.chunk_count {
            let header = read_chunk_header(self.bytes, self.record_offset(index)?, index)?;
            let expected = if index + 1 == commit.chunk_count {
                u64::from(commit.last_chunk_plaintext_length)
            } else {
                chunk_size
            };
            ensure!(
                u64::from(header.plaintext_length()) == expected,
                ObjectCorrupt,
                "a chunk record breaks the canonical chunking rule"
            );
            let plaintext = self.read_chunk(index)?;
            ensure!(
                plaintext.len() as u64 == expected,
                ObjectCorrupt,
                "an authenticated chunk is not the declared length"
            );
        }
        Ok(commit.total_plaintext_length)
    }

    /// Reads an authenticated plaintext range.
    ///
    /// Every chunk the range touches is authenticated in full before any byte
    /// is copied, so no unverified plaintext is ever returned.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::InvalidInput`] when the range extends past the
    /// authenticated object length, and [`ChurStatus::ObjectCorrupt`] for an
    /// authentication failure.
    pub fn read_range(&self, offset: u64, length: u64) -> Result<Zeroizing<Vec<u8>>> {
        let commit = self.read_final_commit()?;
        let end = offset
            .checked_add(length)
            .ok_or_else(|| Error::new(ChurStatus::InvalidInput, "range end overflows u64"))?;
        ensure!(
            end <= commit.total_plaintext_length,
            InvalidInput,
            "range extends past the authenticated object length"
        );
        let mut out = Zeroizing::new(Vec::new());
        if length == 0 {
            return Ok(out);
        }
        let chunk_size = u64::from(self.manifest.chunk_size());
        let first = offset / chunk_size;
        let last = (end - 1) / chunk_size;
        for index in first..=last {
            let plaintext = self.read_chunk(index)?;
            let chunk_start = index * chunk_size;
            let from = usize::try_from(offset.saturating_sub(chunk_start))
                .map_err(|_| corrupt("range offset overflows the address space"))?;
            let to = usize::try_from((end - chunk_start).min(plaintext.len() as u64))
                .map_err(|_| corrupt("range end overflows the address space"))?;
            ensure!(
                from <= to && to <= plaintext.len(),
                ObjectCorrupt,
                "range slice is outside an authenticated chunk"
            );
            out.extend_from_slice(&plaintext[from..to]);
        }
        Ok(out)
    }
}

fn byte_at(bytes: &[u8], offset: u64) -> Result<u8> {
    let index =
        usize::try_from(offset).map_err(|_| corrupt("offset overflows the address space"))?;
    bytes
        .get(index)
        .copied()
        .ok_or_else(|| corrupt("container ended inside a record"))
}

fn slice_at(bytes: &[u8], start: u64, end: u64) -> Result<&[u8]> {
    let start =
        usize::try_from(start).map_err(|_| corrupt("offset overflows the address space"))?;
    let end = usize::try_from(end).map_err(|_| corrupt("offset overflows the address space"))?;
    ensure!(start <= end, ObjectCorrupt, "record end precedes its start");
    bytes
        .get(start..end)
        .ok_or_else(|| corrupt("container ended inside a record"))
}

/// The §11 rules a final commit must satisfy against its own manifest.
///
/// Both readers apply it, so a container that one accepts and the other refuses
/// is impossible by construction rather than by review.
fn check_final_commit(
    commit: &CanonicalFinalCommit,
    manifest: &CanonicalManifest,
    manifest_commitment: &Commitment,
) -> Result<()> {
    ensure!(
        commit.object_id == *manifest.object_id()
            && commit.stream_id == *manifest.stream_id()
            && commit.stream_revision == manifest.stream_revision(),
        ObjectCorrupt,
        "final commit identity fields disagree with the manifest"
    );
    ensure!(
        commit.manifest_commitment == *manifest_commitment,
        ObjectCorrupt,
        "final commit names another manifest record"
    );
    if commit.chunk_count == 0 {
        ensure!(
            commit.total_plaintext_length == 0 && commit.last_chunk_plaintext_length == 0,
            ObjectCorrupt,
            "an empty object declares a non-zero length"
        );
    } else {
        ensure!(
            commit.last_chunk_plaintext_length >= 1
                && commit.last_chunk_plaintext_length <= manifest.chunk_size(),
            ObjectCorrupt,
            "last chunk plaintext length is outside the canonical chunking"
        );
        let expected = (commit.chunk_count - 1)
            .checked_mul(u64::from(manifest.chunk_size()))
            .and_then(|full| full.checked_add(u64::from(commit.last_chunk_plaintext_length)))
            .ok_or_else(|| corrupt("total plaintext length overflows u64"))?;
        ensure!(
            expected == commit.total_plaintext_length,
            ObjectCorrupt,
            "total plaintext length disagrees with the canonical chunking"
        );
    }
    Ok(())
}

/// Decodes a chunk record header from exactly its 20 bytes.
fn decode_chunk_header(bytes: &[u8], expected_index: u64) -> Result<ChunkHeader> {
    let mut reader = Reader::new(bytes, ChurStatus::ObjectCorrupt);
    ensure!(
        reader.u8()? == ContainerRecordType::Chunk.value(),
        ObjectCorrupt,
        "record type is not a chunk record"
    );
    ensure!(
        reader.u8()? == RECORD_VERSION_V1,
        ObjectCorrupt,
        "chunk record version is not the v1 value"
    );
    ensure!(
        reader.u16()? == RESERVED_V1,
        ObjectCorrupt,
        "chunk record reserved field is not zero"
    );
    let chunk_index = reader.u64()?;
    let plaintext_length = reader.u32()?;
    let ciphertext_length = reader.u32()?;
    ensure!(
        chunk_index == expected_index,
        ObjectCorrupt,
        "the chunk record carries another index"
    );
    ensure!(
        plaintext_length >= 1,
        ObjectCorrupt,
        "a chunk record carries at least one plaintext byte"
    );
    ensure!(
        u64::from(ciphertext_length) == u64::from(plaintext_length) + TAG_LEN as u64,
        ObjectCorrupt,
        "chunk ciphertext length is not the plaintext length plus one tag"
    );
    Ok(ChunkHeader {
        chunk_index,
        plaintext_length,
        ciphertext_length,
    })
}

/// Decodes a final commit record header from exactly its 32 bytes.
fn decode_final_commit_header(bytes: &[u8]) -> Result<FinalCommitHeader> {
    let mut reader = Reader::new(bytes, ChurStatus::ObjectCorrupt);
    ensure!(
        reader.u8()? == ContainerRecordType::FinalCommit.value(),
        ObjectCorrupt,
        "record type is not a final commit record"
    );
    ensure!(
        reader.u8()? == RECORD_VERSION_V1,
        ObjectCorrupt,
        "final commit record version is not the v1 value"
    );
    ensure!(
        reader.u16()? == RESERVED_V1,
        ObjectCorrupt,
        "final commit reserved field is not zero"
    );
    let commit_ciphertext_length = reader.u32()?;
    ensure!(
        (bounds::COMMIT_CIPHERTEXT_MIN..=bounds::COMMIT_CIPHERTEXT_MAX)
            .contains(&commit_ciphertext_length),
        ObjectCorrupt,
        "final commit ciphertext length is outside the v1 bounds"
    );
    let commit_nonce = Nonce::new(reader.fixed::<NONCE_LEN>()?);
    Ok(FinalCommitHeader {
        commit_ciphertext_length,
        commit_nonce,
    })
}

fn read_chunk_header(bytes: &[u8], offset: u64, expected_index: u64) -> Result<ChunkHeader> {
    let header = slice_at(bytes, offset, offset + ChunkHeader::LEN as u64)?;
    let mut reader = Reader::new(header, ChurStatus::ObjectCorrupt);
    ensure!(
        reader.u8()? == ContainerRecordType::Chunk.value(),
        ObjectCorrupt,
        "record type is not a chunk record"
    );
    ensure!(
        reader.u8()? == RECORD_VERSION_V1,
        ObjectCorrupt,
        "chunk record version is not the v1 value"
    );
    ensure!(
        reader.u16()? == RESERVED_V1,
        ObjectCorrupt,
        "chunk record reserved field is not zero"
    );
    let chunk_index = reader.u64()?;
    let plaintext_length = reader.u32()?;
    let ciphertext_length = reader.u32()?;
    ensure!(
        chunk_index == expected_index,
        ObjectCorrupt,
        "chunk index does not equal the number of chunk records already read"
    );
    ensure!(
        plaintext_length >= 1,
        ObjectCorrupt,
        "a chunk record carries at least one plaintext byte"
    );
    ensure!(
        u64::from(ciphertext_length) == u64::from(plaintext_length) + TAG_LEN as u64,
        ObjectCorrupt,
        "chunk ciphertext length is not the plaintext length plus one tag"
    );
    let end = offset
        .checked_add(ChunkHeader::LEN as u64 + u64::from(ciphertext_length))
        .ok_or_else(|| corrupt("chunk record end overflows u64"))?;
    ensure!(
        end <= bytes.len() as u64,
        ObjectCorrupt,
        "chunk record does not fit in the remaining bytes"
    );
    Ok(ChunkHeader {
        chunk_index,
        plaintext_length,
        ciphertext_length,
    })
}

fn read_final_commit_length(bytes: &[u8], offset: u64) -> Result<u32> {
    let header = slice_at(bytes, offset, offset + FinalCommitHeader::LEN as u64)?;
    let mut reader = Reader::new(header, ChurStatus::ObjectCorrupt);
    ensure!(
        reader.u8()? == ContainerRecordType::FinalCommit.value(),
        ObjectCorrupt,
        "record type is not a final commit record"
    );
    ensure!(
        reader.u8()? == RECORD_VERSION_V1,
        ObjectCorrupt,
        "final commit record version is not the v1 value"
    );
    ensure!(
        reader.u16()? == RESERVED_V1,
        ObjectCorrupt,
        "final commit reserved field is not zero"
    );
    let length = reader.u32()?;
    ensure!(
        (bounds::COMMIT_CIPHERTEXT_MIN..=bounds::COMMIT_CIPHERTEXT_MAX).contains(&length),
        ObjectCorrupt,
        "final commit ciphertext length is outside the v1 bound"
    );
    Ok(length)
}

const _: () = assert!(PublicPreamble::LEN == 28);
const _: () = assert!(MediaProperties::LEN == 17);
const _: () = assert!(CanonicalManifest::LEN_ORIGINAL == 16 + 16 + 1 + 4 + 1 + 4 + 16 + 8 + 17 + 2);
const _: () = assert!(CanonicalManifest::LEN_DERIVED == CanonicalManifest::LEN_ORIGINAL + 4);
const _: () = assert!(ChunkHeader::LEN == 1 + 1 + 2 + 8 + 4 + 4);
const _: () = assert!(FinalCommitHeader::LEN == 1 + 1 + 2 + 4 + NONCE_LEN);
const _: () = assert!(CanonicalFinalCommit::LEN == 16 + 16 + 4 + 32 + 8 + 8 + 4 + 32 + 8);
const _: () = assert!(ID_LEN == 16);

// ---------------------------------------------------------------------------
// Random-access reader over a source that is not in memory
// ---------------------------------------------------------------------------

/// A source that can read a byte range without loading the whole file.
pub trait ReadAt {
    /// The byte length of the source.
    fn length(&self) -> u64;

    /// Reads exactly `buffer.len()` bytes at `offset`.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::ObjectIncomplete`] when the source ends before the
    /// range, and [`ChurStatus::IoFailure`] otherwise.
    fn read_at(&mut self, offset: u64, buffer: &mut [u8]) -> Result<()>;
}

/// A borrowed source reads exactly as the source does.
///
/// [`StreamReader::open`] takes its source by value, so a caller that must keep
/// the source afterwards, as an import does when it renames the file it just
/// verified, lends it instead of surrendering it.
impl<R: ReadAt + ?Sized> ReadAt for &mut R {
    fn length(&self) -> u64 {
        (**self).length()
    }

    fn read_at(&mut self, offset: u64, buffer: &mut [u8]) -> Result<()> {
        (**self).read_at(offset, buffer)
    }
}

/// The record length of a v1 final commit.
///
/// §11 fixes `CanonicalFinalCommit` at exactly 128 bytes, so its sealed length
/// is exactly 144 and the record is exactly 176 bytes in every v1 container.
/// Locating the record from the file length depends on that, and the assertions
/// below are what make the dependence checked rather than assumed.
pub const FINAL_COMMIT_RECORD_LEN: u64 =
    bounds::COMMIT_HEADER_LEN as u64 + bounds::CANONICAL_FINAL_COMMIT_LEN as u64 + TAG_LEN as u64;

const _: () = assert!(FINAL_COMMIT_RECORD_LEN == 176);
const _: () =
    assert!((bounds::CANONICAL_FINAL_COMMIT_LEN + TAG_LEN) as u32 >= bounds::COMMIT_CIPHERTEXT_MIN);
const _: () =
    assert!((bounds::CANONICAL_FINAL_COMMIT_LEN + TAG_LEN) as u32 <= bounds::COMMIT_CIPHERTEXT_MAX);

/// The record layout of one container, computed rather than walked.
///
/// §12 makes the computation possible: every non-final chunk carries exactly
/// `chunk_size` plaintext, so the whole layout follows from the file length,
/// the manifest record length, and the chunk size. [`Layout`] walks instead,
/// which suits a container already in memory; this suits one that is not.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Geometry {
    first_chunk_offset: u64,
    chunk_size: u32,
    chunk_count: u64,
    last_chunk_plaintext_length: u32,
    total_plaintext_length: u64,
    final_commit_offset: u64,
}

impl Geometry {
    /// Derives the layout from the file length and the manifest.
    ///
    /// With `H` the first chunk offset, `C` the full chunk record stride, `F`
    /// the final commit record length, `n` the chunk count, and `last` the last
    /// chunk's plaintext length in `1..=chunk_size`:
    ///
    /// ```text
    /// file_length = H + (n - 1) * C + (36 + last) + F
    /// ```
    ///
    /// so with `body = file_length - H - F`, `body - 37 = (n - 1) * C + (last - 1)`
    /// and `last - 1` lies in `0..C`, which makes `n - 1` exactly `(body - 37) / C`.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::ObjectIncomplete`] when the file is shorter than
    /// one record sequence and [`ChurStatus::ObjectCorrupt`] when its length is
    /// not a whole one.
    pub fn derive(file_length: u64, manifest_record_length: u32, chunk_size: u32) -> Result<Self> {
        check_chunk_size(chunk_size)?;
        let first_chunk_offset = AFTER_PREAMBLE + u64::from(manifest_record_length);
        let overhead = ChunkHeader::LEN as u64 + TAG_LEN as u64;
        let stride = u64::from(chunk_size) + overhead;
        let body = file_length
            .checked_sub(first_chunk_offset)
            .and_then(|value| value.checked_sub(FINAL_COMMIT_RECORD_LEN))
            .ok_or_else(|| {
                Error::new(
                    ChurStatus::ObjectIncomplete,
                    "the container is shorter than one record sequence",
                )
            })?;
        ensure!(
            body > overhead,
            ObjectIncomplete,
            "the container holds no chunk record"
        );
        let chunk_count = (body - overhead - 1) / stride + 1;
        let last = body - (chunk_count - 1) * stride - overhead;
        ensure!(
            last >= 1 && last <= u64::from(chunk_size),
            ObjectCorrupt,
            "the container length is not a whole record sequence"
        );
        let last_chunk_plaintext_length =
            u32::try_from(last).map_err(|_| corrupt("the last chunk exceeds a u32"))?;
        let total_plaintext_length = (chunk_count - 1)
            .checked_mul(u64::from(chunk_size))
            .and_then(|value| value.checked_add(last))
            .ok_or_else(|| corrupt("the plaintext length overflows u64"))?;
        ensure!(
            chunk_count <= bounds::CHUNK_COUNT_MAX,
            ResourceLimitExceeded,
            "the chunk count exceeds the §16 bound"
        );
        ensure!(
            total_plaintext_length <= bounds::TOTAL_PLAINTEXT_MAX,
            ResourceLimitExceeded,
            "the plaintext length exceeds the §16 bound"
        );
        Ok(Self {
            first_chunk_offset,
            chunk_size,
            chunk_count,
            last_chunk_plaintext_length,
            total_plaintext_length,
            final_commit_offset: file_length - FINAL_COMMIT_RECORD_LEN,
        })
    }

    /// The number of chunk records.
    #[must_use]
    pub const fn chunk_count(&self) -> u64 {
        self.chunk_count
    }

    /// The plaintext length the layout implies.
    ///
    /// It is a computation over the file length, so it stays a claim until the
    /// final commit record authenticates the same value.
    #[must_use]
    pub const fn total_plaintext_length(&self) -> u64 {
        self.total_plaintext_length
    }

    /// The plaintext length of chunk `index`.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::InvalidInput`] for an index past the container.
    pub fn chunk_plaintext_length(&self, index: u64) -> Result<u32> {
        ensure!(
            index < self.chunk_count,
            InvalidInput,
            "the chunk index is past the container"
        );
        Ok(if index + 1 == self.chunk_count {
            self.last_chunk_plaintext_length
        } else {
            self.chunk_size
        })
    }

    /// The offset of chunk record `index`, by the §12 formula.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::InvalidInput`] for an index past the container.
    pub fn chunk_record_offset(&self, index: u64) -> Result<u64> {
        ensure!(
            index < self.chunk_count,
            InvalidInput,
            "the chunk index is past the container"
        );
        index
            .checked_mul(u64::from(self.chunk_size) + ChunkHeader::LEN as u64 + TAG_LEN as u64)
            .and_then(|skip| self.first_chunk_offset.checked_add(skip))
            .ok_or_else(|| corrupt("the record offset overflows u64"))
    }

    /// The byte length of chunk record `index`.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::InvalidInput`] for an index past the container.
    pub fn chunk_record_length(&self, index: u64) -> Result<usize> {
        Ok(ChunkHeader::LEN + self.chunk_plaintext_length(index)? as usize + TAG_LEN)
    }
}

/// A reader over a container held in a source rather than in memory.
///
/// It holds one chunk at a time, so complete verification of a 1 TiB object
/// costs one chunk of memory rather than a terabyte.
pub struct StreamReader<R: ReadAt> {
    source: R,
    manifest: CanonicalManifest,
    manifest_commitment: Commitment,
    keys: StreamKeys,
    identity: StreamIdentity,
    geometry: Geometry,
    size: u64,
}

impl<R: ReadAt> StreamReader<R> {
    /// Opens a container, authenticating its manifest and its final commit.
    ///
    /// `identity` is what the catalog says the file holds, which §4 requires.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::ObjectCorrupt`] when a record does not
    /// authenticate or the final commit disagrees with the layout, and
    /// [`ChurStatus::ObjectIncomplete`] when a record is absent.
    pub fn open(mut source: R, object_key: &Key, identity: &StreamIdentity) -> Result<Self> {
        let mut preamble_bytes = [0u8; PublicPreamble::LEN];
        source.read_at(0, &mut preamble_bytes)?;
        let preamble = PublicPreamble::decode(&preamble_bytes)?;
        let manifest_record_length = preamble.manifest_record_length();

        let mut manifest_record = vec![0u8; manifest_record_length as usize];
        source.read_at(AFTER_PREAMBLE, &mut manifest_record)?;
        let manifest_nonce = Nonce::from_slice(
            manifest_record
                .get(..NONCE_LEN)
                .ok_or_else(|| corrupt("manifest record is shorter than its nonce"))?,
        )?;
        let sealed = &manifest_record[NONCE_LEN..];
        let manifest_commitment = manifest_commitment(&manifest_nonce, sealed);
        let manifest_key =
            kdf::derive_from(object_key, Label::ObjectManifest, &identity.key_context())?;
        let plaintext = aead::open(
            &manifest_key,
            &manifest_nonce,
            sealed,
            &identity.manifest_aad(),
        )?;
        let manifest = CanonicalManifest::decode(&plaintext)?;
        ensure!(
            StreamIdentity::of(&manifest) == *identity,
            ObjectCorrupt,
            "the sealed manifest contradicts the identity it was opened under"
        );
        let keys = StreamKeys::derive(object_key, &manifest)?;
        let geometry = Geometry::derive(
            source.length(),
            manifest_record_length,
            manifest.chunk_size(),
        )?;

        let mut reader = Self {
            source,
            manifest,
            manifest_commitment,
            keys,
            identity: *identity,
            geometry,
            size: 0,
        };
        // The final commit is authenticated here, so a reader that exists has a
        // size that came from a verified record rather than from the file
        // length that suggested it.
        let commit = reader.read_final_commit()?;
        ensure!(
            commit.total_plaintext_length == geometry.total_plaintext_length
                && commit.chunk_count == geometry.chunk_count,
            ObjectCorrupt,
            "the final commit disagrees with the container layout"
        );
        reader.size = commit.total_plaintext_length;
        Ok(reader)
    }

    /// The authenticated plaintext size.
    #[must_use]
    pub const fn size(&self) -> u64 {
        self.size
    }

    /// The computed layout.
    #[must_use]
    pub const fn geometry(&self) -> &Geometry {
        &self.geometry
    }

    /// The identity this container was opened under.
    #[must_use]
    pub const fn identity(&self) -> &StreamIdentity {
        &self.identity
    }

    /// The authenticated manifest.
    #[must_use]
    pub const fn manifest(&self) -> &CanonicalManifest {
        &self.manifest
    }

    /// Reads and authenticates one chunk.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::ObjectCorrupt`] when the record does not verify
    /// and [`ChurStatus::ObjectIncomplete`] when it is absent or short.
    pub fn read_chunk(&mut self, index: u64) -> Result<Zeroizing<Vec<u8>>> {
        let offset = self.geometry.chunk_record_offset(index)?;
        let length = self.geometry.chunk_record_length(index)?;
        let mut record = vec![0u8; length];
        self.source.read_at(offset, &mut record)?;
        self.open_record(&record, index)
    }

    /// Reads and authenticates the final commit record.
    ///
    /// # Errors
    ///
    /// As [`ContainerReader::read_final_commit`].
    pub fn read_final_commit(&mut self) -> Result<CanonicalFinalCommit> {
        let mut record = vec![0u8; FINAL_COMMIT_RECORD_LEN as usize];
        self.source
            .read_at(self.geometry.final_commit_offset, &mut record)?;
        let header = decode_final_commit_header(&record[..FinalCommitHeader::LEN])?;
        ensure!(
            u64::from(header.commit_ciphertext_length)
                == FINAL_COMMIT_RECORD_LEN - FinalCommitHeader::LEN as u64,
            ObjectCorrupt,
            "the final commit record declares another ciphertext length"
        );
        let aad = final_commit_aad(
            self.manifest.object_id(),
            self.manifest.stream_id(),
            self.manifest.stream_kind(),
            self.manifest.stream_revision(),
            &self.manifest_commitment,
        );
        let plaintext = aead::open(
            &self.keys.final_commit,
            &header.commit_nonce,
            &record[FinalCommitHeader::LEN..],
            &aad,
        )?;
        let commit = CanonicalFinalCommit::decode(&plaintext)?;
        check_final_commit(&commit, &self.manifest, &self.manifest_commitment)?;
        Ok(commit)
    }

    /// Reads a plaintext range, loading one chunk at a time.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::InvalidInput`] when the range is past the
    /// authenticated plaintext, and the record errors otherwise.
    pub fn read_range(&mut self, offset: u64, length: u64) -> Result<Zeroizing<Vec<u8>>> {
        ensure!(
            offset
                .checked_add(length)
                .is_some_and(|end| end <= self.size),
            InvalidInput,
            "the range is past the authenticated plaintext"
        );
        let capacity = usize::try_from(length)
            .map_err(|_| Error::new(ChurStatus::ResourceLimitExceeded, "range exceeds a usize"))?;
        let mut out = Zeroizing::new(Vec::with_capacity(capacity));
        let chunk_size = u64::from(self.manifest.chunk_size());
        let mut position = offset;
        while out.len() < capacity {
            let index = position / chunk_size;
            let within = usize::try_from(position % chunk_size)
                .map_err(|_| corrupt("a chunk offset exceeds a usize"))?;
            let chunk = self.read_chunk(index)?;
            let take = (chunk.len() - within).min(capacity - out.len());
            out.extend_from_slice(&chunk[within..within + take]);
            position += take as u64;
        }
        Ok(out)
    }

    /// Authenticates every record and the ordered commitment, §13.
    ///
    /// # Errors
    ///
    /// As [`ContainerReader::verify_complete`].
    pub fn verify_complete(&mut self) -> Result<u64> {
        let mut committer = Committer::new(tag::OBJECT_ORDERED_COMMITMENT);
        for index in 0..self.geometry.chunk_count {
            let offset = self.geometry.chunk_record_offset(index)?;
            let length = self.geometry.chunk_record_length(index)?;
            let mut record = vec![0u8; length];
            self.source.read_at(offset, &mut record)?;
            // Authenticating before committing is the point: the commitment
            // must cover records that verify, not whatever bytes are at the
            // offsets.
            let _ = self.open_record(&record, index)?;
            committer.update(&record);
        }
        let commit = self.read_final_commit()?;
        ensure!(
            chur_crypto::secret::constant_time_eq(
                &committer.finish(),
                &commit.ordered_chunk_commitment
            ),
            ObjectCorrupt,
            "the ordered chunk commitment does not match the final commit"
        );
        Ok(commit.total_plaintext_length)
    }

    fn open_record(&self, record: &[u8], index: u64) -> Result<Zeroizing<Vec<u8>>> {
        let header = decode_chunk_header(
            record
                .get(..ChunkHeader::LEN)
                .ok_or_else(|| corrupt("chunk record is shorter than its header"))?,
            index,
        )?;
        let expected = self.geometry.chunk_plaintext_length(index)?;
        ensure!(
            header.plaintext_length() == expected,
            ObjectCorrupt,
            "the chunk record contradicts the canonical chunking"
        );
        let aad = chunk_aad(
            &self.manifest,
            &self.manifest_commitment,
            index,
            header.plaintext_length(),
        );
        let nonce = Nonce::chunk(self.manifest.nonce_prefix(), index);
        aead::open(
            &self.keys.content,
            &nonce,
            &record[ChunkHeader::LEN..],
            &aad,
        )
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::panic, clippy::expect_used)]

    use super::*;

    const CHUNK: u32 = bounds::CHUNK_SIZE_MIN;

    fn id(byte: u8) -> Id {
        Id::new([byte; ID_LEN]).unwrap()
    }

    fn identity() -> StreamIdentity {
        StreamIdentity {
            object_id: id(0x11),
            stream_id: id(0x22),
            stream_kind: StreamKind::Original,
            stream_revision: 1,
        }
    }

    fn manifest() -> CanonicalManifest {
        CanonicalManifest::new(
            identity(),
            None,
            CHUNK,
            [0x33; NONCE_PREFIX_LEN],
            1,
            MediaProperties::new(MediaClass::Image, 4032, 3024, 0).unwrap(),
        )
        .unwrap()
    }

    fn object_key() -> Key {
        Key::new([0x44; 32])
    }

    fn build(plaintext: &[u8]) -> Vec<u8> {
        encode_container(
            &object_key(),
            manifest(),
            Nonce::new([0x55; NONCE_LEN]),
            plaintext,
            Nonce::new([0x66; NONCE_LEN]),
            1,
        )
        .unwrap()
    }

    fn open(bytes: &[u8]) -> ContainerReader<'_> {
        ContainerReader::open(bytes, &object_key(), &identity()).unwrap()
    }

    fn pattern(length: usize) -> Vec<u8> {
        (0..length).map(|index| (index % 251) as u8).collect()
    }

    // -- frozen lengths ----------------------------------------------------

    #[test]
    fn the_manifest_record_length_is_125_or_129() {
        assert_eq!(manifest().encode().len(), 85);
        let bytes = build(b"");
        assert_eq!(
            PublicPreamble::decode(&bytes[..28])
                .unwrap()
                .manifest_record_length(),
            125
        );

        let derived = CanonicalManifest::new(
            StreamIdentity {
                stream_kind: StreamKind::ThumbnailSmall,
                ..identity()
            },
            Some(1),
            CHUNK,
            [0x33; NONCE_PREFIX_LEN],
            1,
            MediaProperties::new(MediaClass::Image, 256, 256, 0).unwrap(),
        )
        .unwrap();
        assert_eq!(derived.encode().len(), 89);
        assert_eq!(NONCE_LEN + derived.encode().len() + TAG_LEN, 129);
    }

    #[test]
    fn the_final_commit_ciphertext_length_is_144() {
        let bytes = build(b"");
        let layout = Layout::parse(&bytes).unwrap();
        assert_eq!(layout.final_commit_ciphertext_length, 144);
        assert_eq!(CanonicalFinalCommit::LEN, 128);
    }

    #[test]
    fn the_three_aad_tuples_are_the_frozen_lengths() {
        let manifest = manifest();
        assert_eq!(manifest.aad().len(), 66);
        assert_eq!(
            chunk_aad(&manifest, &[0u8; COMMITMENT_LEN], 0, CHUNK).len(),
            109
        );
        assert_eq!(
            final_commit_aad(
                manifest.object_id(),
                manifest.stream_id(),
                manifest.stream_kind(),
                manifest.stream_revision(),
                &[0u8; COMMITMENT_LEN],
            )
            .len(),
            102
        );
    }

    #[test]
    fn the_preamble_holds_the_documented_bytes() {
        let bytes = build(b"");
        assert_eq!(&bytes[0x00..0x08], b"CHUROBJ1");
        assert_eq!(&bytes[0x08..0x0a], &[0, 1]);
        assert_eq!(&bytes[0x0a..0x0c], &[0, 1]);
        assert_eq!(&bytes[0x0c..0x0e], &[0, 1]);
        assert_eq!(&bytes[0x0e..0x10], &[0, 0]);
        assert_eq!(&bytes[0x10..0x14], &28u32.to_be_bytes());
        assert_eq!(&bytes[0x14..0x18], &125u32.to_be_bytes());
        assert_eq!(&bytes[0x18..0x1a], &[0, 1]);
        assert_eq!(&bytes[0x1a..0x1c], &[0, 0]);
    }

    // -- the three §13 shapes ---------------------------------------------

    #[test]
    fn a_zero_byte_stream_has_no_chunk_records() {
        let bytes = build(b"");
        let layout = Layout::parse(&bytes).unwrap();
        assert_eq!(layout.chunk_count(), 0);
        assert!(layout.has_final_commit());
        assert_eq!(layout.first_chunk_offset(), 28 + 125);
        assert_eq!(bytes.len() as u64, 28 + 125 + 32 + 144);

        let reader = open(&bytes);
        let commit = reader.read_final_commit().unwrap();
        assert_eq!(commit.chunk_count(), 0);
        assert_eq!(commit.total_plaintext_length(), 0);
        assert_eq!(commit.last_chunk_plaintext_length(), 0);
        assert_eq!(
            commit.ordered_chunk_commitment(),
            &commit::commit(tag::OBJECT_ORDERED_COMMITMENT, &[])
        );
        assert_eq!(reader.verify_complete().unwrap(), 0);
        assert!(reader.read_range(0, 0).unwrap().is_empty());
    }

    #[test]
    fn one_partial_chunk_is_also_the_last_chunk() {
        let plaintext = pattern(1000);
        let bytes = build(&plaintext);
        let reader = open(&bytes);
        let commit = reader.read_final_commit().unwrap();
        assert_eq!(commit.chunk_count(), 1);
        assert_eq!(commit.total_plaintext_length(), 1000);
        assert_eq!(commit.last_chunk_plaintext_length(), 1000);
        assert_eq!(reader.verify_complete().unwrap(), 1000);
        assert_eq!(
            reader.read_range(0, 1000).unwrap().as_slice(),
            &plaintext[..]
        );
    }

    #[test]
    fn an_exact_multiple_writes_no_zero_length_trailing_record() {
        let plaintext = pattern(2 * CHUNK as usize);
        let bytes = build(&plaintext);
        let reader = open(&bytes);
        let commit = reader.read_final_commit().unwrap();
        assert_eq!(commit.chunk_count(), 2);
        assert_eq!(commit.last_chunk_plaintext_length(), CHUNK);
        assert_eq!(commit.total_plaintext_length(), u64::from(2 * CHUNK));
        assert_eq!(reader.verify_complete().unwrap(), u64::from(2 * CHUNK));
    }

    #[test]
    fn many_chunks_with_a_partial_final_chunk_round_trip() {
        let plaintext = pattern(3 * CHUNK as usize + 17);
        let bytes = build(&plaintext);
        let reader = open(&bytes);
        let commit = reader.read_final_commit().unwrap();
        assert_eq!(commit.chunk_count(), 4);
        assert_eq!(commit.last_chunk_plaintext_length(), 17);
        assert_eq!(reader.verify_complete().unwrap(), plaintext.len() as u64);
        assert_eq!(
            reader
                .read_range(0, plaintext.len() as u64)
                .unwrap()
                .as_slice(),
            &plaintext[..]
        );
    }

    // -- random access -----------------------------------------------------

    #[test]
    fn the_seek_formula_matches_the_walked_record_offsets() {
        let bytes = build(&pattern(3 * CHUNK as usize + 5));
        let reader = open(&bytes);
        let mut walked = reader.layout().first_chunk_offset();
        for index in 0..reader.layout().chunk_count() {
            assert_eq!(
                reader.record_offset(index).unwrap(),
                walked,
                "index {index}"
            );
            let header = read_chunk_header(&bytes, walked, index).unwrap();
            walked += header.record_length();
        }
    }

    #[test]
    fn a_range_across_chunk_boundaries_returns_the_source_bytes() {
        let plaintext = pattern(2 * CHUNK as usize + 100);
        let bytes = build(&plaintext);
        let reader = open(&bytes);
        for (offset, length) in [
            (0u64, 1u64),
            (u64::from(CHUNK) - 1, 2),
            (u64::from(CHUNK), 10),
            (u64::from(CHUNK) - 5, 10),
            (u64::from(2 * CHUNK) - 3, 6),
            (0, plaintext.len() as u64),
            (plaintext.len() as u64, 0),
        ] {
            let read = reader.read_range(offset, length).unwrap();
            let from = usize::try_from(offset).unwrap();
            let to = from + usize::try_from(length).unwrap();
            assert_eq!(
                read.as_slice(),
                &plaintext[from..to],
                "range {offset}+{length}"
            );
        }
    }

    #[test]
    fn a_range_past_the_authenticated_length_is_refused() {
        let bytes = build(&pattern(100));
        let reader = open(&bytes);
        assert_eq!(
            reader.read_range(0, 101).unwrap_err().status(),
            ChurStatus::InvalidInput
        );
        assert_eq!(
            reader.read_range(u64::MAX, 1).unwrap_err().status(),
            ChurStatus::InvalidInput
        );
    }

    // -- preamble rejection ------------------------------------------------

    #[test]
    fn a_wrong_magic_is_object_corrupt() {
        let mut bytes = build(b"");
        bytes[4] = b'X';
        assert_eq!(
            Layout::parse(&bytes).unwrap_err().status(),
            ChurStatus::ObjectCorrupt
        );
    }

    #[test]
    fn non_zero_flags_or_reserved_and_a_wrong_header_length_are_object_corrupt() {
        for offset in [0x0e, 0x1a] {
            let mut bytes = build(b"");
            bytes[offset] = 0x01;
            assert_eq!(
                Layout::parse(&bytes).unwrap_err().status(),
                ChurStatus::ObjectCorrupt,
                "offset {offset:#x}"
            );
        }
        let mut bytes = build(b"");
        bytes[0x13] = 0x1d;
        assert_eq!(
            Layout::parse(&bytes).unwrap_err().status(),
            ChurStatus::ObjectCorrupt
        );
    }

    #[test]
    fn an_unknown_version_profile_or_suite_is_unsupported() {
        for (offset, expected) in [
            (0x09, ChurStatus::UnsupportedVersion),
            (0x0b, ChurStatus::UnsupportedVersion),
            (0x0d, ChurStatus::UnsupportedSuite),
            (0x19, ChurStatus::UnsupportedVersion),
        ] {
            let mut bytes = build(b"");
            bytes[offset] = 0x02;
            assert_eq!(
                Layout::parse(&bytes).unwrap_err().status(),
                expected,
                "offset {offset:#x}"
            );
        }
    }

    #[test]
    fn a_manifest_record_length_outside_the_bound_is_rejected() {
        for value in [39u32, 65_537] {
            let mut bytes = build(b"");
            bytes[0x14..0x18].copy_from_slice(&value.to_be_bytes());
            assert_eq!(
                Layout::parse(&bytes).unwrap_err().status(),
                ChurStatus::ObjectCorrupt,
                "length {value}"
            );
        }
    }

    // -- record rejection --------------------------------------------------

    #[test]
    fn a_missing_final_commit_is_object_incomplete() {
        let bytes = build(&pattern(100));
        let truncated = &bytes[..bytes.len() - (32 + 144)];
        let layout = Layout::parse(truncated).unwrap();
        assert!(!layout.has_final_commit());
        let reader = ContainerReader::open(truncated, &object_key(), &identity()).unwrap();
        assert_eq!(
            reader.read_final_commit().unwrap_err().status(),
            ChurStatus::ObjectIncomplete
        );
        assert_eq!(
            reader.verify_complete().unwrap_err().status(),
            ChurStatus::ObjectIncomplete
        );
    }

    #[test]
    fn bytes_after_the_final_commit_are_rejected() {
        let mut bytes = build(&pattern(100));
        bytes.push(0x00);
        assert_eq!(
            Layout::parse(&bytes).unwrap_err().status(),
            ChurStatus::ObjectCorrupt
        );
    }

    #[test]
    fn an_unallocated_record_type_is_object_corrupt() {
        let bytes = build(&pattern(100));
        let offset = usize::try_from(Layout::parse(&bytes).unwrap().first_chunk_offset()).unwrap();
        for value in [0x00u8, 0x03, 0xff] {
            let mut damaged = bytes.clone();
            damaged[offset] = value;
            assert_eq!(
                Layout::parse(&damaged).unwrap_err().status(),
                ChurStatus::ObjectCorrupt,
                "record type {value:#x}"
            );
        }
    }

    #[test]
    fn a_forged_chunk_header_is_rejected_without_a_key() {
        let bytes = build(&pattern(100));
        let offset = usize::try_from(Layout::parse(&bytes).unwrap().first_chunk_offset()).unwrap();
        // record_version, reserved, chunk_index, and the length relationship.
        let cases: [(usize, &[u8]); 4] = [
            (offset + 1, &[0x02]),
            (offset + 2, &[0x00, 0x01]),
            (offset + 4, &[0, 0, 0, 0, 0, 0, 0, 1]),
            (offset + 16, &[0, 0, 0, 99]),
        ];
        for (at, replacement) in cases {
            let mut damaged = bytes.clone();
            damaged[at..at + replacement.len()].copy_from_slice(replacement);
            assert_eq!(
                Layout::parse(&damaged).unwrap_err().status(),
                ChurStatus::ObjectCorrupt,
                "forged bytes at {at}"
            );
        }
    }

    #[test]
    fn a_final_commit_ciphertext_length_outside_the_bound_is_rejected() {
        let bytes = build(b"");
        let offset = usize::try_from(Layout::parse(&bytes).unwrap().first_chunk_offset()).unwrap();
        for value in [15u32, 4097] {
            let mut damaged = bytes.clone();
            damaged[offset + 4..offset + 8].copy_from_slice(&value.to_be_bytes());
            assert_eq!(
                Layout::parse(&damaged).unwrap_err().status(),
                ChurStatus::ObjectCorrupt,
                "length {value}"
            );
        }
    }

    // -- authentication ----------------------------------------------------

    #[test]
    fn opening_under_another_identity_fails() {
        let bytes = build(&pattern(100));
        for other in [
            StreamIdentity {
                object_id: id(0x12),
                ..identity()
            },
            StreamIdentity {
                stream_id: id(0x23),
                ..identity()
            },
            StreamIdentity {
                stream_kind: StreamKind::ThumbnailSmall,
                ..identity()
            },
            StreamIdentity {
                stream_revision: 2,
                ..identity()
            },
        ] {
            assert!(
                ContainerReader::open(&bytes, &object_key(), &other).is_err(),
                "a container opened under a foreign identity"
            );
        }
    }

    #[test]
    fn opening_under_another_object_key_fails() {
        let bytes = build(&pattern(100));
        assert!(ContainerReader::open(&bytes, &Key::new([0x45; 32]), &identity()).is_err());
    }

    #[test]
    fn a_substituted_chunk_from_another_index_fails_authentication() {
        let plaintext = pattern(2 * CHUNK as usize);
        let bytes = build(&plaintext);
        let stride = (CHUNK + 20 + 16) as usize;
        let first = usize::try_from(Layout::parse(&bytes).unwrap().first_chunk_offset()).unwrap();
        let mut damaged = bytes.clone();
        // Copy record 1's ciphertext over record 0's, keeping record 0's header.
        let (body0, body1) = (first + 20, first + stride + 20);
        let borrowed = bytes[body1..body1 + CHUNK as usize + 16].to_vec();
        damaged[body0..body0 + CHUNK as usize + 16].copy_from_slice(&borrowed);
        let reader = open(&damaged);
        assert_eq!(
            reader.read_chunk(0).unwrap_err().status(),
            ChurStatus::ObjectCorrupt
        );
    }

    #[test]
    fn a_manifest_record_from_another_container_fails_to_open() {
        let mine = build(&pattern(100));
        let other = encode_container(
            &object_key(),
            manifest(),
            Nonce::new([0x77; NONCE_LEN]),
            &pattern(200),
            Nonce::new([0x88; NONCE_LEN]),
            1,
        )
        .unwrap();
        // The two manifests differ only in nonce, so the spliced record opens,
        // but its commitment differs and every chunk AAD then fails.
        let mut spliced = mine.clone();
        spliced[28..28 + 125].copy_from_slice(&other[28..28 + 125]);
        let reader = open(&spliced);
        assert_ne!(
            reader.manifest_commitment(),
            open(&mine).manifest_commitment()
        );
        assert_eq!(
            reader.read_chunk(0).unwrap_err().status(),
            ChurStatus::ObjectCorrupt
        );
        assert_eq!(
            reader.read_final_commit().unwrap_err().status(),
            ChurStatus::ObjectCorrupt
        );
    }

    #[test]
    fn every_single_byte_change_in_a_small_container_is_caught() {
        let bytes = build(&pattern(64));
        for index in 0..bytes.len() {
            let mut damaged = bytes.clone();
            damaged[index] ^= 0x01;
            let caught = match ContainerReader::open(&damaged, &object_key(), &identity()) {
                Err(_) => true,
                Ok(reader) => reader.verify_complete().is_err(),
            };
            assert!(caught, "a flipped bit at byte {index} was not caught");
        }
    }

    // -- writer rules ------------------------------------------------------

    #[test]
    fn a_short_chunk_must_be_the_last_chunk() {
        let mut writer = ContainerWriter::start(
            Vec::new(),
            &object_key(),
            manifest(),
            Nonce::new([1; NONCE_LEN]),
        )
        .unwrap();
        writer.write_chunk(&pattern(10)).unwrap();
        assert_eq!(
            writer.write_chunk(&pattern(10)).unwrap_err().status(),
            ChurStatus::InvalidInput
        );
    }

    #[test]
    fn an_empty_or_oversized_chunk_is_refused() {
        let mut writer = ContainerWriter::start(
            Vec::new(),
            &object_key(),
            manifest(),
            Nonce::new([1; NONCE_LEN]),
        )
        .unwrap();
        assert_eq!(
            writer.write_chunk(b"").unwrap_err().status(),
            ChurStatus::InvalidInput
        );
        assert_eq!(
            writer
                .write_chunk(&pattern(CHUNK as usize + 1))
                .unwrap_err()
                .status(),
            ChurStatus::InvalidInput
        );
    }

    // -- manifest rules ----------------------------------------------------

    #[test]
    fn a_chunk_size_outside_the_range_or_off_the_multiple_is_refused() {
        for size in [65_535u32, 8_388_609, 65_536 + 1] {
            assert!(
                CanonicalManifest::new(
                    identity(),
                    None,
                    size,
                    [0; NONCE_PREFIX_LEN],
                    1,
                    MediaProperties::opaque(),
                )
                .is_err(),
                "chunk size {size}"
            );
        }
        assert!(
            CanonicalManifest::new(
                identity(),
                None,
                8_388_608,
                [0; NONCE_PREFIX_LEN],
                1,
                MediaProperties::opaque(),
            )
            .is_ok()
        );
    }

    #[test]
    fn the_source_content_revision_is_present_exactly_for_a_derived_kind() {
        assert!(
            CanonicalManifest::new(
                identity(),
                Some(1),
                CHUNK,
                [0; NONCE_PREFIX_LEN],
                1,
                MediaProperties::opaque(),
            )
            .is_err()
        );
        assert!(
            CanonicalManifest::new(
                StreamIdentity {
                    stream_kind: StreamKind::GridPreview,
                    ..identity()
                },
                None,
                CHUNK,
                [0; NONCE_PREFIX_LEN],
                1,
                MediaProperties::opaque(),
            )
            .is_err()
        );
    }

    #[test]
    fn media_properties_reject_a_dimension_the_class_does_not_carry() {
        assert!(MediaProperties::new(MediaClass::Audio, 1, 0, 0).is_err());
        assert!(MediaProperties::new(MediaClass::Image, 1, 1, 1).is_err());
        assert!(MediaProperties::new(MediaClass::Opaque, 0, 0, 0).is_ok());
        assert!(MediaProperties::new(MediaClass::Video, 1920, 1080, 5000).is_ok());
    }

    #[test]
    fn a_manifest_round_trips_through_encode_and_decode() {
        let manifest = manifest();
        assert_eq!(
            CanonicalManifest::decode(&manifest.encode()).unwrap(),
            manifest
        );
        let commit = CanonicalFinalCommit {
            object_id: id(1),
            stream_id: id(2),
            stream_revision: 3,
            manifest_commitment: [4; COMMITMENT_LEN],
            chunk_count: 5,
            total_plaintext_length: 6,
            last_chunk_plaintext_length: 7,
            ordered_chunk_commitment: [8; COMMITMENT_LEN],
            commit_generation: 9,
        };
        assert_eq!(
            CanonicalFinalCommit::decode(&commit.encode()).unwrap(),
            commit
        );
    }

    #[test]
    fn a_manifest_with_trailing_bytes_is_non_canonical() {
        let mut encoded = manifest().encode();
        encoded.push(0);
        assert_eq!(
            CanonicalManifest::decode(&encoded).unwrap_err().status(),
            ChurStatus::NonCanonicalEncoding
        );
    }

    #[test]
    fn a_presence_byte_other_than_zero_or_one_is_non_canonical() {
        let mut encoded = manifest().encode();
        encoded[37] = 0x02;
        assert_eq!(
            CanonicalManifest::decode(&encoded).unwrap_err().status(),
            ChurStatus::NonCanonicalEncoding
        );
    }
}
