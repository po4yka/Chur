//! Derived assets.
//!
//! `docs/interop/MEDIA_PIPELINE.md` §1 puts codec probing, decoding, and
//! resizing on the platform, and identity, encryption, persistence, and
//! integrity here. A derivative therefore arrives as bytes the platform
//! produced, and this module binds it, encrypts it, and records it.
//!
//! §6 requires every derived asset to bind its object, its source content
//! revision, its kind, its asset revision, and its generator profile, so a
//! stale derivative is never presented as current after the original is
//! replaced. The binding is not advisory: the derived-asset HKDF context of
//! `docs/security/KEY_HIERARCHY.md` §3 carries `source_content_revision`, so a
//! derivative of an older revision derives a different key and does not open.

use chur_catalog::journal::{self, ImportTransaction, Stage};
use chur_catalog::model::{DerivedAsset, Stream};
use chur_catalog::store;
use chur_catalog::vault::Session;
use chur_core::{Id, Result, ensure, limits::media as media_bounds};
use chur_crypto::{Key, Nonce, random};
use chur_format::constants::{MediaClass, ObjectState, SUITE_V1, StreamKind};
use chur_format::container::{
    CanonicalManifest, ContainerVersion, ContainerWriter, MediaProperties, StreamIdentity,
    StreamReader,
};
use chur_format::waveform::Waveform;
use zeroize::Zeroizing;

use crate::import::DEFAULT_CHUNK_SIZE;
use crate::keys;
use crate::store::{ContainerFile, TemporaryContainer};

/// The v1 generator profile, `MEDIA_PIPELINE.md` §11.
///
/// §12 fixes the codec as baseline JPEG with 4:2:0 chroma and the quality of
/// each kind. Pixel-identical output across platforms is impractical, so the
/// profile is declared rather than claimed: a change of codec, quality, or
/// resize rule takes the next profile value rather than a silent difference.
pub const GENERATOR_PROFILE_V1: u32 = 1;

/// A native H.264/AAC SDR viewing copy, distinct from the JPEG profile.
pub const COMPATIBLE_VIDEO_PROFILE_V1: u32 = 2;

/// Twelve center-time, upright SDR frames in a fixed JPEG grid.
pub const VIDEO_STORYBOARD_PROFILE_V1: u32 = 3;

/// The long-edge target of one derivative kind, §12.
///
/// An unlisted kind has no v1 target, so it returns `None` rather than a
/// plausible default that no specification states.
#[must_use]
pub const fn long_edge(kind: StreamKind) -> Option<u32> {
    match kind {
        StreamKind::ThumbnailSmall => Some(media_bounds::THUMBNAIL_SMALL_EDGE),
        StreamKind::GridPreview => Some(media_bounds::GRID_PREVIEW_EDGE),
        StreamKind::ScreenPreview => Some(media_bounds::SCREEN_PREVIEW_EDGE),
        StreamKind::VideoPoster => Some(media_bounds::VIDEO_POSTER_EDGE),
        StreamKind::VideoStoryboard => Some(640),
        // A waveform is a data record rather than a picture: §6 lists it beside
        // the OCR and embedding records, and §12 gives it no long edge. The arm
        // is written out so that the absence is a decision rather than a
        // fallthrough, and `put` bounds it by its record length instead.
        StreamKind::AudioWaveform => None,
        _ => None,
    }
}

/// Encrypts and records one derived asset.
///
/// The bytes are the platform's output and are consumed here: §8 of
/// `PLAINTEXT_LIFECYCLE.md` keeps a decoded derivative only long enough to
/// encrypt it, so the buffer is zeroized when it drops.
pub fn put(
    session: &mut Session,
    object_id: &Id,
    kind: StreamKind,
    width: u32,
    height: u32,
    bytes: &Zeroizing<Vec<u8>>,
    now_ms: u64,
) -> Result<Id> {
    check_asset(kind, width, height, bytes)?;
    let object = store::object(session.catalog_ref()?, object_id)?;
    ensure!(
        object.state == ObjectState::Active,
        NotFound,
        "the object is not listable"
    );
    check_video_asset(kind, object.media_kind, width, height, bytes)?;
    let source_content_revision = object.object_generation.try_into().map_err(|_| {
        chur_core::err!(
            ResourceLimitExceeded,
            "the object generation exceeds a content revision"
        )
    })?;
    let object_key = keys::object_key(session, object_id)?;

    let existing = store::streams(session.catalog_ref()?, object_id)?;
    let stream_revision = existing
        .iter()
        .filter(|stream| stream.stream_kind == kind)
        .map(|stream| stream.stream_revision)
        .max()
        .map_or(1, |current| current + 1);
    let sealed = seal(
        session,
        object_id,
        &object_key,
        source_content_revision,
        stream_revision,
        kind,
        width,
        height,
        object.duration_ms,
        bytes,
        now_ms,
    )?;
    store::put_derived_asset(session.catalog()?, &sealed.asset, &sealed.stream)?;
    journal::close(session.catalog()?, &sealed.transaction_id)?;
    Ok(sealed.stream.stream_id)
}

/// A derived asset whose container is written, committed, and verified, and
/// whose catalog rows are not yet inserted.
pub struct SealedAsset {
    pub(crate) asset: DerivedAsset,
    pub(crate) stream: Stream,
    pub(crate) transaction_id: Id,
}

impl SealedAsset {
    /// The journal record of the asset, so a caller can abandon it alone.
    #[must_use]
    pub const fn transaction_id(&self) -> Id {
        self.transaction_id
    }
}

/// Writes one display asset for an import that is not yet activated,
/// ADR-0069.
///
/// The asset is under the import's fresh object key, and its rows are
/// inserted by the activation of the import, in the same transaction.
pub fn seal_for_import(
    session: &mut Session,
    import: &crate::import::Import,
    kind: StreamKind,
    width: u32,
    height: u32,
    bytes: &Zeroizing<Vec<u8>>,
    now_ms: u64,
) -> Result<SealedAsset> {
    check_asset(kind, width, height, bytes)?;
    let media = import.media();
    check_video_asset(kind, media.media_class, width, height, bytes)?;
    seal(
        session,
        &import.object_id(),
        import.object_key(),
        1,
        1,
        kind,
        width,
        height,
        media.duration_ms,
        bytes,
        now_ms,
    )
}

/// The bounds of the two video display assets, which depend on the object.
fn check_video_asset(
    kind: StreamKind,
    media_class: MediaClass,
    width: u32,
    height: u32,
    bytes: &[u8],
) -> Result<()> {
    if kind == StreamKind::CompatibleVideo {
        ensure!(
            media_class == MediaClass::Video,
            InvalidInput,
            "a viewing copy requires a video original"
        );
        ensure!(
            width > 0
                && height > 0
                && width.max(height) <= 1280
                && width.min(height) <= 720
                && bytes.len() <= 32 * 1024 * 1024,
            ResourceLimitExceeded,
            "the viewing copy exceeds its dimensions or byte bound"
        );
    }
    if kind == StreamKind::VideoStoryboard {
        ensure!(
            media_class == MediaClass::Video,
            InvalidInput,
            "a timeline requires a video original"
        );
        ensure!(
            width == 640 && height == 270 && bytes.len() <= 2 * 1024 * 1024,
            ResourceLimitExceeded,
            "the timeline exceeds its fixed atlas bounds"
        );
    }
    Ok(())
}

fn check_asset(kind: StreamKind, width: u32, height: u32, bytes: &[u8]) -> Result<()> {
    ensure!(
        kind != StreamKind::Original,
        InvalidInput,
        "an original is a stream, not a derived asset"
    );
    ensure!(
        !bytes.is_empty(),
        InvalidInput,
        "a derived asset carries at least one byte"
    );
    if let Some(edge) = long_edge(kind) {
        ensure!(
            width.max(height) <= edge,
            ResourceLimitExceeded,
            "the derivative exceeds the §12 long-edge target for its kind"
        );
    }
    if kind == StreamKind::AudioWaveform {
        // A waveform has no pixel edge to bound it, so its own record is the
        // bound. Parsing rather than measuring is deliberate: a waveform that
        // the shared renderer cannot read is not a waveform, and the moment to
        // find that out is before it is sealed into a container.
        Waveform::decode(bytes)?;
    }
    Ok(())
}

#[expect(
    clippy::too_many_arguments,
    reason = "the stream identity, its key, and the asset fields are all inputs"
)]
fn seal(
    session: &mut Session,
    object_id: &Id,
    object_key: &Key,
    source_content_revision: u32,
    stream_revision: u32,
    kind: StreamKind,
    width: u32,
    height: u32,
    duration_ms: u64,
    bytes: &Zeroizing<Vec<u8>>,
    now_ms: u64,
) -> Result<SealedAsset> {
    let stream_id = random::id()?;
    let identity = StreamIdentity {
        object_id: *object_id,
        stream_id,
        stream_kind: kind,
        stream_revision,
    };
    // A derivative of an image is an image; of a video's poster frame, also an
    // image, as is embedded audio artwork. Waveforms and future records carry
    // no pixels.
    let properties = if kind == StreamKind::CompatibleVideo {
        MediaProperties::new(MediaClass::Video, width, height, duration_ms)?
    } else if width == 0 && height == 0 {
        MediaProperties::opaque()
    } else {
        MediaProperties::new(MediaClass::Image, width, height, 0)?
    };
    let nonce_prefix = random::array::<16>()?;
    let manifest = CanonicalManifest::new(
        identity,
        Some(source_content_revision),
        DEFAULT_CHUNK_SIZE,
        nonce_prefix,
        1,
        properties,
    )?;

    let temp_path_id = random::id()?;
    let container_path_id = temp_path_id;
    let transaction_id = random::id()?;
    journal::open(
        session.catalog()?,
        &ImportTransaction {
            transaction_id,
            temp_path_id,
            object_id: *object_id,
            stream_id,
            stream_kind: kind,
            stream_revision,
            envelope_generation: 1,
            envelope_body: None,
            nonce_prefix,
            chunk_size: DEFAULT_CHUNK_SIZE,
            manifest_length: manifest.record_length(),
            reserved_index: None,
            expected_length: Some(bytes.len() as u64),
            source_seekable: false,
            stage: Stage::Opening,
            opened_ms: now_ms,
        },
    )?;
    // A failure after the journal record opened kills this record alone, so
    // a caller that shares the session loses no other live transaction.
    let written = (|| {
        session.admit_container_version(ContainerVersion::CURRENT.value())?;
        let store_id = session.object_store_id();
        let mut temporary = TemporaryContainer::open(session.root_dir(), &store_id, &temp_path_id)?;
        temporary.truncate_to(0)?;
        let mut writer = ContainerWriter::start(temporary, object_key, manifest, Nonce::random()?)?;
        writer.sink_mut().sync()?;
        journal::mark_manifest_durable(session.catalog()?, &transaction_id)?;
        let chunk = usize::try_from(DEFAULT_CHUNK_SIZE)
            .map_err(|_| chur_core::err!(InternalFailure, "the chunk size exceeds a usize"))?;
        for piece in bytes.chunks(chunk) {
            journal::reserve_chunk(session.catalog()?, &transaction_id, writer.chunk_count())?;
            writer.write_chunk(piece)?;
            writer.sink_mut().sync()?;
        }
        // ADR-0066: each padding chunk takes a reserved index like an object chunk.
        while writer.padding_pending() {
            journal::reserve_chunk(session.catalog()?, &transaction_id, writer.chunk_count())?;
            writer.write_padding_chunk()?;
            writer.sink_mut().sync()?;
        }
        journal::mark_committing(session.catalog()?, &transaction_id)?;
        let plaintext_size = writer.total_plaintext_length();
        let container_version = writer.version().value();
        let mut container = writer.finish(Nonce::random()?, 1)?;
        container.sync()?;
        let ciphertext_size = container.length()?;
        container.commit(session.root_dir(), &store_id, &container_path_id)?;

        // Verify from the committed file rather than from the writer's accounting,
        // exactly as an import does.
        let file = ContainerFile::open(session.root_dir(), &store_id, &container_path_id)?;
        let mut reader = StreamReader::open(file, object_key, &identity)?;
        let verified = reader.verify_complete()?;
        ensure!(
            verified == plaintext_size,
            ObjectCorrupt,
            "the written derivative does not authenticate to the bytes that were fed"
        );
        let commitment = *reader.read_final_commit()?.ordered_chunk_commitment();
        drop(reader);

        Ok((
            commitment,
            plaintext_size,
            container_version,
            ciphertext_size,
        ))
    })();
    let (commitment, plaintext_size, container_version, ciphertext_size) = match written {
        Ok(written) => written,
        Err(error) => {
            let _ = crate::import::reconcile_one(session, &transaction_id);
            return Err(error);
        }
    };
    let chunk_size = DEFAULT_CHUNK_SIZE;

    Ok(SealedAsset {
        asset: DerivedAsset {
            object_id: *object_id,
            kind,
            source_content_revision,
            asset_revision: stream_revision,
            generator_profile: if kind == StreamKind::CompatibleVideo {
                COMPATIBLE_VIDEO_PROFILE_V1
            } else if kind == StreamKind::VideoStoryboard {
                VIDEO_STORYBOARD_PROFILE_V1
            } else {
                GENERATOR_PROFILE_V1
            },
            stream_id,
        },
        stream: Stream {
            stream_id,
            object_id: *object_id,
            stream_kind: kind,
            stream_revision,
            source_content_revision,
            container_path_id,
            container_version,
            suite_id: SUITE_V1,
            ciphertext_size,
            plaintext_size,
            chunk_size,
            complete_verified_ms: Some(now_ms),
            final_commitment: commitment,
        },
        transaction_id,
    })
}

/// Reads one derived asset whole.
///
/// A derivative is bounded by §12's long edges, so it fits a buffer; an
/// original does not, which is why this refuses the original kind rather than
/// silently loading a terabyte.
pub fn read(session: &Session, object_id: &Id, kind: StreamKind) -> Result<Zeroizing<Vec<u8>>> {
    ensure!(
        kind != StreamKind::Original,
        InvalidInput,
        "an original is read through the range reader"
    );
    let mut reader = crate::reader::open(session, object_id, kind)?;
    let size = reader.size();
    reader.read_range(0, size)
}

/// Whether a source of this class and size needs a derivative of this kind.
///
/// An image already inside the long edge is its own preview, so generating one
/// would spend a container and a key on a copy. The thumbnail is always
/// generated, because the timeline reads it for every row and reading the
/// original there would defeat the point.
///
/// The two kinds Phase 2 adds are decided by class rather than by size. A video
/// needs a poster frame at every resolution: the poster is the still the
/// viewer shows before playback starts, and a 1920 by 1080 video that is
/// already inside the 2048 px target has no still at all until one is
/// generated. Audio has no source pixel dimensions. Its waveform and optional
/// artwork are therefore selected by class; a missing embedded image produces
/// no picture asset.
#[must_use]
pub fn needs(kind: StreamKind, media_class: MediaClass, width: u32, height: u32) -> bool {
    match kind {
        StreamKind::VideoPoster => media_class == MediaClass::Video,
        StreamKind::AudioWaveform => media_class == MediaClass::Audio,
        StreamKind::ThumbnailSmall => matches!(
            media_class,
            MediaClass::Image | MediaClass::Video | MediaClass::Audio
        ),
        StreamKind::GridPreview | StreamKind::ScreenPreview if media_class == MediaClass::Audio => {
            true
        }
        _ => match long_edge(kind) {
            None => false,
            Some(edge) => media_class == MediaClass::Image && width.max(height) > edge,
        },
    }
}
