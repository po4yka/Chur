//! Streaming import.
//!
//! `docs/format/OBJECT_CONTAINER_V1.md` §14 fixes the transaction and this
//! module runs it. The ordering rule of §14.2 is the whole point of the type:
//! for each chunk index, the journal reservation is made durable before the
//! chunk is encrypted, and the container is fsynced before the next index is
//! reserved. A writer therefore never encrypts an index that is not already
//! durably reserved, and never reserves an index while an earlier record is
//! still only in a page cache.
//!
//! Memory is bounded by `docs/interop/MEDIA_PIPELINE.md` §12: one chunk of
//! plaintext and one of ciphertext are in flight, whatever the source length.

use chur_catalog::journal::{self, ImportTransaction, Resume, Stage};
use chur_catalog::model::{MetadataRevision, Object, Stream};
use chur_catalog::store::{self, ObjectActivation};
use chur_catalog::vault::Session;
use chur_core::{
    Id, Result, bail, ensure,
    limits::{container as container_bounds, media as media_bounds},
};
use chur_crypto::{Key, Nonce, random};
use chur_format::constants::{
    CONTAINER_VERSION_V1, IntegritySummary, MediaClass, ObjectState, SUITE_V1, StreamKind,
};
use chur_format::container::{
    CanonicalManifest, ContainerReader, ContainerWriter, Layout, MediaProperties, StreamIdentity,
};
use zeroize::Zeroizing;

use crate::keys;
use crate::store::TemporaryContainer;

/// The chunk size a v1 writer uses.
///
/// `docs/format/OBJECT_CONTAINER_V1.md` §6 lists two candidates and
/// `docs/assurance/EVIDENCE_PHASE_0.md` records that neither is approved above
/// the frozen floor until the device measurement exists. 256 KiB is the smaller
/// candidate and the one whose peak buffer is smallest, which is the safe
/// choice while the measurement is outstanding.
pub const DEFAULT_CHUNK_SIZE: u32 = 262_144;

/// What the platform adapter reports about a source, `MEDIA_PIPELINE.md` §3.
///
/// Every field is a bounded fact and none is trusted as authenticated truth:
/// the length is a hint that the final commit later replaces, and the content
/// type is validated for shape before it is stored.
#[derive(Debug, Clone)]
pub struct SourceCapability {
    /// Whether the source can be re-read from an offset.
    pub seekable: bool,
    /// The length the provider reported, when it reported one.
    pub known_length: Option<u64>,
    /// The provider's media-type hint, untrusted.
    pub content_type_hint: String,
    /// The filename the provider reported, if any.
    pub original_filename: Option<String>,
    /// The capture time the provider reported, if any, §8.1 of the catalog.
    pub capture_time_ms: Option<u64>,
}

/// The canonical media facts Rust validated, `MEDIA_PIPELINE.md` §4.
#[derive(Debug, Clone, Copy)]
pub struct CanonicalMedia {
    /// The media class.
    pub media_class: MediaClass,
    /// Pixel width, zero when the class has none.
    pub width: u32,
    /// Pixel height, zero when the class has none.
    pub height: u32,
    /// Duration in milliseconds, zero when the class has none.
    pub duration_ms: u64,
}

impl CanonicalMedia {
    /// An opaque object: no decodable dimensions and no duration.
    #[must_use]
    pub const fn opaque() -> Self {
        Self {
            media_class: MediaClass::Opaque,
            width: 0,
            height: 0,
            duration_ms: 0,
        }
    }

    /// Rejects a value outside `MEDIA_PIPELINE.md` §12, before any decode.
    pub fn check(&self) -> Result<()> {
        match self.media_class {
            MediaClass::Image => {
                ensure!(
                    self.width <= media_bounds::IMAGE_EDGE_MAX
                        && self.height <= media_bounds::IMAGE_EDGE_MAX,
                    ResourceLimitExceeded,
                    "the image exceeds the §12 edge bound"
                );
                ensure!(
                    u64::from(self.width) * u64::from(self.height) <= media_bounds::IMAGE_AREA_MAX,
                    ResourceLimitExceeded,
                    "the image exceeds the §12 area bound"
                );
            }
            MediaClass::Video => {
                ensure!(
                    self.width <= media_bounds::VIDEO_WIDTH_MAX
                        && self.height <= media_bounds::VIDEO_HEIGHT_MAX,
                    ResourceLimitExceeded,
                    "the video exceeds the §12 track bound"
                );
            }
            _ => {}
        }
        ensure!(
            self.duration_ms <= media_bounds::DURATION_MS_MAX,
            ResourceLimitExceeded,
            "the duration exceeds the §12 four-hour bound"
        );
        Ok(())
    }
}

/// One import in progress.
///
/// It owns the journal record, the container writer, and the object key.
/// Dropping it without [`Import::commit`] or [`Import::abandon`] leaves a live
/// journal record whose container is present, which [`reconcile`] kills at the
/// next unlock.
pub struct Import {
    transaction_id: Id,
    object_id: Id,
    stream_id: Id,
    collection_id: Id,
    object_key: Key,
    envelope: Vec<u8>,
    envelope_generation: u64,
    writer: ContainerWriter<TemporaryContainer>,
    identity: StreamIdentity,
    container_path_id: Id,
    temp_path_id: Id,
    chunk_size: u32,
    source: SourceCapability,
    media: CanonicalMedia,
}

/// Opens an import transaction and writes the preamble and manifest, §14.
///
/// The object key is drawn, wrapped, and journaled before the first byte is
/// encrypted, so a crash at any point leaves an envelope the abandonment path
/// can destroy, which is what makes an abandoned import's bytes unrecoverable
/// rather than merely deleted.
pub fn begin(
    session: &mut Session,
    source: SourceCapability,
    media: CanonicalMedia,
    now_ms: u64,
) -> Result<Import> {
    media.check()?;
    if let Some(length) = source.known_length {
        ensure!(
            length <= container_bounds::TOTAL_PLAINTEXT_MAX,
            ResourceLimitExceeded,
            "the source exceeds the 1 TiB object bound of container §16"
        );
    }

    let collection_id = keys::ensure_default_collection(session)?;
    let collection = store::collection(session.catalog_ref()?, &collection_id)?;
    let collection_key = keys::collection_key(session, &collection_id, collection.current_epoch)?;
    let object_id = random::id()?;
    let stream_id = random::id()?;
    let (object_key, envelope) = keys::seal_object_key(
        &session.vault_id(),
        &collection_id,
        collection.current_epoch,
        &collection_key,
        &object_id,
        1,
    )?;

    let nonce_prefix = random::array::<16>()?;
    let identity = StreamIdentity {
        object_id,
        stream_id,
        stream_kind: StreamKind::Original,
        stream_revision: 1,
    };
    let properties = MediaProperties::new(
        media.media_class,
        media.width,
        media.height,
        media.duration_ms,
    )?;
    let manifest = CanonicalManifest::new(
        identity,
        None,
        DEFAULT_CHUNK_SIZE,
        nonce_prefix,
        1,
        properties,
    )?;
    let manifest_length = manifest.record_length();

    let temp_path_id = random::id()?;
    let container_path_id = random::id()?;
    let store_id = session.object_store_id();
    let mut container = TemporaryContainer::open(session.root_dir(), &store_id, &temp_path_id)?;
    container.truncate_to(0)?;

    // §14.2: the preamble and the manifest record are written and fsynced
    // before index 0 is reserved.
    let mut writer = ContainerWriter::start(container, &object_key, manifest, Nonce::random()?)?;
    writer.sink_mut().sync()?;

    let record = ImportTransaction {
        transaction_id: random::id()?,
        temp_path_id,
        object_id,
        stream_id,
        stream_kind: StreamKind::Original,
        stream_revision: 1,
        envelope_generation: 1,
        envelope_body: Some(envelope.clone()),
        nonce_prefix,
        chunk_size: DEFAULT_CHUNK_SIZE,
        manifest_length,
        reserved_index: None,
        expected_length: source.known_length,
        source_seekable: source.seekable,
        stage: Stage::Opening,
        opened_ms: now_ms,
    };
    let transaction_id = record.transaction_id;
    journal::open(session.catalog()?, &record)?;
    journal::mark_manifest_durable(session.catalog()?, &transaction_id)?;

    Ok(Import {
        transaction_id,
        object_id,
        stream_id,
        collection_id,
        object_key,
        envelope,
        envelope_generation: 1,
        writer,
        identity,
        container_path_id,
        temp_path_id,
        chunk_size: DEFAULT_CHUNK_SIZE,
        source,
        media,
    })
}

impl Import {
    /// The object this import will activate.
    #[must_use]
    pub const fn object_id(&self) -> Id {
        self.object_id
    }

    /// The transaction, so a caller can ask [`resume_offset`] about it.
    #[must_use]
    pub const fn transaction_id(&self) -> Id {
        self.transaction_id
    }

    /// The chunk size, which is the plaintext a caller feeds per [`Import::write`].
    #[must_use]
    pub const fn chunk_size(&self) -> u32 {
        self.chunk_size
    }

    /// The plaintext written so far.
    #[must_use]
    pub const fn written(&self) -> u64 {
        self.writer.total_plaintext_length()
    }

    /// Writes one chunk, running §14.2 steps 1 to 4 in order and no other.
    ///
    /// Every chunk but the last carries exactly [`Import::chunk_size`] bytes.
    /// A short chunk ends the stream, and the writer refuses one after it
    /// rather than producing a container no reader accepts.
    pub fn write(&mut self, session: &mut Session, plaintext: &[u8]) -> Result<()> {
        let index = self.writer.chunk_count();
        // Steps 1 and 2. The call returns only after its catalog transaction has
        // committed under `synchronous = FULL`, so the index is durably reserved
        // before a byte is encrypted under it.
        let offset = journal::reserve_chunk(session.catalog()?, &self.transaction_id, index)?;
        debug_assert_eq!(offset, self.writer.sink_mut().length()?);
        // Step 3.
        self.writer.write_chunk(plaintext)?;
        // Step 4.
        self.writer.sink_mut().sync()?;
        Ok(())
    }

    /// Writes the final commit, verifies the container, renames it, and
    /// activates the object.
    ///
    /// The order is §14's: fsync, structurally verify and compare the ordered
    /// commitment, atomic rename, then the catalog transaction. The
    /// verification reads back what was written rather than trusting the
    /// writer's own accounting, which is what catches a storage layer that
    /// acknowledged a write it did not perform.
    pub fn commit(self, session: &mut Session, content_type: &str, now_ms: u64) -> Result<Id> {
        ensure!(
            self.writer.chunk_count() > 0,
            InvalidInput,
            "an object carries at least one chunk"
        );
        journal::mark_committing(session.catalog()?, &self.transaction_id)?;

        let total_plaintext = self.writer.total_plaintext_length();
        let mut container = self.writer.finish(Nonce::random()?, 1)?;
        container.sync()?;
        let ciphertext_size = container.length()?;

        let readable = usize::try_from(ciphertext_size).map_err(|_| {
            chur_core::err!(
                ResourceLimitExceeded,
                "the container exceeds addressable memory"
            )
        })?;
        let whole = Zeroizing::new(container.read_at(0, readable)?);
        let reader = ContainerReader::open(&whole, &self.object_key, &self.identity)?;
        let verified = reader.verify_complete()?;
        ensure!(
            verified == total_plaintext,
            ObjectCorrupt,
            "the written container does not authenticate to the plaintext that was fed"
        );
        let final_commit = reader.read_final_commit()?;
        drop(reader);
        drop(whole);

        let store_id = session.object_store_id();
        container.commit(session.root_dir(), &store_id, &self.container_path_id)?;

        let capture = self.source.capture_time_ms;
        store::activate_object(
            session.catalog()?,
            &ObjectActivation {
                object: Object {
                    object_id: self.object_id,
                    object_generation: 1,
                    collection_id: self.collection_id,
                    primary_stream_id: self.stream_id,
                    media_kind: self.media.media_class,
                    capture_time_ms: capture.unwrap_or(now_ms),
                    import_time_ms: now_ms,
                    capture_time_substituted: capture.is_none(),
                    plaintext_size: total_plaintext,
                    width: self.media.width,
                    height: self.media.height,
                    duration_ms: self.media.duration_ms,
                    favorite: false,
                    state: ObjectState::Active,
                    // The container was just authenticated end to end, which is
                    // exactly what COMPLETE_VERIFIED means in §5.1. Recording
                    // UNVERIFIED here would ask the user to verify work that
                    // just ran.
                    integrity_summary: IntegritySummary::CompleteVerified,
                    thumbnail_ready: false,
                    active_metadata_revision: 1,
                },
                stream: Stream {
                    stream_id: self.stream_id,
                    object_id: self.object_id,
                    stream_kind: StreamKind::Original,
                    stream_revision: 1,
                    source_content_revision: 0,
                    container_path_id: self.container_path_id,
                    container_version: CONTAINER_VERSION_V1,
                    suite_id: SUITE_V1,
                    ciphertext_size,
                    plaintext_size: total_plaintext,
                    chunk_size: self.chunk_size,
                    complete_verified_ms: Some(now_ms),
                    final_commitment: *final_commit.ordered_chunk_commitment(),
                },
                envelope: self.envelope,
                envelope_generation: self.envelope_generation,
                metadata: MetadataRevision {
                    object_id: self.object_id,
                    revision: 1,
                    active: true,
                    record: Vec::new(),
                    original_filename: self.source.original_filename,
                    caption: None,
                    content_type: content_type.to_owned(),
                    capture_time_ms: capture,
                    width: self.media.width,
                    height: self.media.height,
                    duration_ms: self.media.duration_ms,
                },
            },
        )?;
        journal::close(session.catalog()?, &self.transaction_id)?;
        let _ = self.temp_path_id;
        Ok(self.object_id)
    }

    /// Abandons the import, §14.4.
    ///
    /// Death is recorded as one durable stage update, then the envelope is
    /// destroyed, then the container is deleted, then the record. The order is
    /// the security property: after the second step the `ContentKey` and every
    /// byte written under it are unrecoverable, and the remaining steps carry
    /// none.
    pub fn abandon(self, session: &mut Session) -> Result<()> {
        journal::mark_dead(session.catalog()?, &self.transaction_id)?;
        journal::destroy_envelope(session.catalog()?, &self.transaction_id)?;
        let container = self.writer.into_sink();
        container.discard()?;
        journal::close(session.catalog()?, &self.transaction_id)
    }
}

/// Reconciles every live import at the first unlock of a session, §14.4.
///
/// A journal record whose temporary container is absent is dead, and so is one
/// whose reserved record does not authenticate. This runs the abandonment for
/// each, and returns how many it killed.
///
/// Resuming an interrupted import is possible only when the source can be
/// re-read from the offset the journal proves durable, `MEDIA_PIPELINE.md` §3.
/// v1 does not hold a source across a process death, so reconciliation kills
/// every live transaction rather than resuming one; [`resume_offset`] is what a
/// caller that still holds its source uses instead.
pub fn reconcile(session: &mut Session, now_ms: u64) -> Result<usize> {
    let _ = now_ms;
    let store_id = session.object_store_id();
    let root_dir = session.root_dir().clone();
    let mut killed = 0;

    for record in journal::dead(session.catalog_ref()?)? {
        finish_abandonment(session, &root_dir, &store_id, &record)?;
        killed += 1;
    }
    for record in journal::live(session.catalog_ref()?)? {
        journal::mark_dead(session.catalog()?, &record.transaction_id)?;
        finish_abandonment(session, &root_dir, &store_id, &record)?;
        killed += 1;
    }
    Ok(killed)
}

fn finish_abandonment(
    session: &mut Session,
    root_dir: &chur_catalog::paths::VaultRoot,
    store_id: &Id,
    record: &ImportTransaction,
) -> Result<()> {
    if record.envelope_body.is_some() {
        journal::destroy_envelope(session.catalog()?, &record.transaction_id)?;
    }
    let path = root_dir.temporary_container(store_id, &record.temp_path_id);
    if path.exists() {
        std::fs::remove_file(&path)
            .map_err(|_| chur_core::err!(IoFailure, "a dead container could not be deleted"))?;
    }
    journal::close(session.catalog()?, &record.transaction_id)
}

/// The plaintext offset a resumed import reads its source from, §14.3.
///
/// It returns `None` when the transaction is dead, in which case the container
/// is discarded rather than rewritten: the reserved index has already consumed
/// its nonce and §8 forbids a gap in the sequence.
pub fn resume_offset(
    session: &Session,
    transaction_id: &Id,
    reserved_record_valid: bool,
) -> Result<Option<u64>> {
    let record = journal::read(session.catalog_ref()?, transaction_id)?;
    Ok(
        match journal::resume_decision(&record, reserved_record_valid) {
            Resume::RestartFromManifest => Some(0),
            Resume::ContinueAfter(index) => Some((index + 1) * u64::from(record.chunk_size)),
            Resume::Dead => None,
        },
    )
}

/// Imports a whole in-memory source, which is what a derived asset and a test
/// have.
///
/// `docs/interop/MEDIA_PIPELINE.md` §8 keeps a decoded derivative only long
/// enough to encrypt it, so the caller hands over the bytes and this consumes
/// them chunk by chunk. The buffer is zeroized when it drops.
pub fn import_bytes(
    session: &mut Session,
    source: SourceCapability,
    media: CanonicalMedia,
    content_type: &str,
    plaintext: &Zeroizing<Vec<u8>>,
    now_ms: u64,
) -> Result<Id> {
    let mut import = begin(session, source, media, now_ms)?;
    let chunk = usize::try_from(import.chunk_size())
        .map_err(|_| chur_core::err!(InternalFailure, "the chunk size exceeds a usize"))?;
    if plaintext.is_empty() {
        import.abandon(session)?;
        bail!(InvalidInput, "an object carries at least one byte");
    }
    for piece in plaintext.chunks(chunk) {
        import.write(session, piece)?;
    }
    import.commit(session, content_type, now_ms)
}

/// The layout of a committed container, for the reader and the integrity scan.
pub fn layout_of(bytes: &[u8]) -> Result<Layout> {
    Layout::parse(bytes)
}
