//! Random-access authenticated reads.
//!
//! `docs/interop/MEDIA_PIPELINE.md` §9 fixes what a player asks for and what
//! Rust does with the request: validate the session and reader, resolve the
//! affected encrypted chunks, authenticate and decrypt whole chunks, copy the
//! requested range, report the verified range or end of stream, and never
//! equate range success with complete-object verification.
//!
//! `docs/interop/FFI_CONTRACT.md` §6.3 fixes the boundary behaviour of
//! [`ObjectReader::read_at`]: a short read is permitted at any offset, zero
//! bytes with a success status means end of plaintext and occurs only at
//! `offset == size`, and an offset past the end is `INVALID_INPUT` rather than
//! a zero-length success.

use chur_catalog::store;
use chur_catalog::vault::Session;
use chur_core::{Id, Result, bail, ensure, limits::media as media_bounds};
use chur_format::constants::{IntegritySummary, ObjectState, StreamKind};
use chur_format::container::{ReadAt, StreamIdentity, StreamReader};
use zeroize::Zeroizing;

use crate::keys;
use crate::store::ContainerFile;

impl ReadAt for ContainerFile {
    fn length(&self) -> u64 {
        ContainerFile::length(self)
    }

    fn read_at(&mut self, offset: u64, buffer: &mut [u8]) -> Result<()> {
        let bytes = ContainerFile::read_at(self, offset, buffer.len())?;
        buffer.copy_from_slice(&bytes);
        Ok(())
    }
}

/// The content information a range reader publishes, `FFI_CONTRACT.md` §6.1.
///
/// Every value comes from authenticated canonical metadata, never from the
/// provider hint `MEDIA_PIPELINE.md` §3 classifies as untrusted.
#[derive(Debug, Clone)]
pub struct ContentInfo {
    /// The authenticated size from the final commit record.
    pub plaintext_size: u64,
    /// The lowercase IANA media type, at most 63 bytes.
    pub content_type: String,
    /// The canonical media-kind value.
    pub media_kind: u16,
    /// True for a committed immutable object.
    pub byte_range_supported: bool,
    /// True only after final-commit validation.
    pub complete: bool,
}

/// An open reader on one committed stream.
///
/// One chunk of plaintext is in flight at a time, so a reader's memory is the
/// chunk size whatever the object's length.
pub struct ObjectReader {
    inner: StreamReader<ContainerFile>,
    content_type: String,
    media_kind: u16,
    cached_index: Option<u64>,
    cached_chunk: Zeroizing<Vec<u8>>,
}

/// Opens a reader on one stream of one object.
///
/// `docs/format/OBJECT_CONTAINER_V1.md` §4: the reader supplies the stream
/// identity from the catalog, because the manifest key and the manifest AAD
/// both bind fields sealed inside the manifest. A substituted container fails
/// the first AEAD rather than being opened under its own claim.
pub fn open(session: &Session, object_id: &Id, stream_kind: StreamKind) -> Result<ObjectReader> {
    let object = store::object(session.catalog_ref()?, object_id)?;
    ensure!(
        object.state == ObjectState::Active,
        NotFound,
        "the object is not listable"
    );
    let streams = store::streams(session.catalog_ref()?, object_id)?;
    let Some(stream) = streams
        .iter()
        .filter(|candidate| candidate.stream_kind == stream_kind)
        .max_by_key(|candidate| candidate.stream_revision)
    else {
        bail!(NotFound, "the object carries no stream of that kind");
    };
    let metadata = store::active_metadata(session.catalog_ref()?, object_id)?;
    let object_key = keys::object_key(session, object_id)?;
    let store_id = session.object_store_id();
    let file = ContainerFile::open(session.root_dir(), &store_id, &stream.container_path_id)?;
    let identity = StreamIdentity {
        object_id: *object_id,
        stream_id: stream.stream_id,
        stream_kind: stream.stream_kind,
        stream_revision: stream.stream_revision,
    };
    let inner = StreamReader::open(file, &object_key, &identity)?;
    Ok(ObjectReader {
        inner,
        content_type: metadata.content_type,
        media_kind: u16::from(object.media_kind.value()),
        cached_index: None,
        cached_chunk: Zeroizing::new(Vec::new()),
    })
}

impl ObjectReader {
    /// The authenticated plaintext size.
    #[must_use]
    pub const fn size(&self) -> u64 {
        self.inner.size()
    }

    /// The content information a player needs before its first range request.
    ///
    /// §6.1 makes this publishable only when the final commit validates, which
    /// [`open`] already required, so a reader that exists can always answer.
    pub fn content_info(&self) -> Result<ContentInfo> {
        ensure!(
            self.content_type.len() <= media_bounds::CONTENT_TYPE_MAX,
            ResourceLimitExceeded,
            "the content type exceeds the §6.1 bound"
        );
        Ok(ContentInfo {
            plaintext_size: self.inner.size(),
            content_type: self.content_type.clone(),
            media_kind: self.media_kind,
            byte_range_supported: true,
            complete: true,
        })
    }

    /// Reads plaintext at `offset` into `destination`, `FFI_CONTRACT.md` §6.3.
    ///
    /// The return value is the number of bytes written. A short read is
    /// permitted at any offset: the reader returns at most the authenticated
    /// bytes of the one chunk the offset falls in, so a caller loops until it
    /// has the range it needs or observes zero.
    pub fn read_at(&mut self, offset: u64, destination: &mut [u8]) -> Result<usize> {
        let size = self.inner.size();
        if offset > size {
            bail!(
                InvalidInput,
                "the offset is past the authenticated plaintext"
            );
        }
        if destination.is_empty() || offset == size {
            return Ok(0);
        }
        let chunk_size = u64::from(self.inner.manifest().chunk_size());
        let index = offset / chunk_size;
        let within = usize::try_from(offset % chunk_size)
            .map_err(|_| chur_core::err!(InternalFailure, "a chunk offset exceeds a usize"))?;
        self.load(index)?;
        let available = self.cached_chunk.len().saturating_sub(within);
        let copied = available.min(destination.len());
        destination[..copied].copy_from_slice(&self.cached_chunk[within..within + copied]);
        Ok(copied)
    }

    /// Reads a whole range, looping over chunks.
    pub fn read_range(&mut self, offset: u64, length: u64) -> Result<Zeroizing<Vec<u8>>> {
        self.inner.read_range(offset, length)
    }

    /// Runs the complete verification of `OBJECT_CONTAINER_V1.md` §13.
    ///
    /// It returns the `integrity_summary` the scan reached. Proven corruption
    /// is a lifecycle change rather than a verdict, so a container that fails a
    /// cryptographic check returns `OBJECT_CORRUPT` and no summary, which is
    /// what `FFI_CONTRACT.md` §6.2 requires of the same call.
    pub fn verify_complete(&mut self) -> Result<IntegritySummary> {
        let verified = self.inner.verify_complete()?;
        ensure!(
            verified == self.inner.size(),
            ObjectCorrupt,
            "the container authenticates to a different length than its final commit"
        );
        Ok(IntegritySummary::CompleteVerified)
    }

    /// Loads and authenticates one chunk into the cache.
    ///
    /// The cache is one chunk. `docs/security/PLAINTEXT_LIFECYCLE.md` §1 allows
    /// a bounded media buffer and requires it to be overwritten; `Zeroizing`
    /// does that when the buffer is replaced or dropped.
    fn load(&mut self, index: u64) -> Result<()> {
        if self.cached_index == Some(index) {
            return Ok(());
        }
        self.cached_chunk = self.inner.read_chunk(index)?;
        self.cached_index = Some(index);
        Ok(())
    }
}
