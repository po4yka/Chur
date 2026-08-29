//! Bounded ciphertext download and complete local container verification.

use chur_catalog::paths::VaultRoot;
use chur_core::{Id, Result, ensure};
use chur_crypto::Key;
use chur_format::constants::StreamKind;
use chur_format::container::{
    CanonicalFinalCommit, CanonicalManifest, ReadAt, StreamIdentity, StreamReader,
};

use crate::store::TemporaryContainer;

const RANGE_BYTES: usize = 1024 * 1024;

/// Authenticated values carried by a committed object operation.
pub struct Expectation {
    identity: StreamIdentity,
    container_length: u64,
    ordered_chunk_commitment: [u8; 32],
}

impl Expectation {
    /// Creates the immutable original-stream expectation.
    pub fn new(
        object_id: Id,
        stream_id: Id,
        container_length: u64,
        ordered_chunk_commitment: [u8; 32],
    ) -> Result<Self> {
        ensure!(
            container_length != 0 && ordered_chunk_commitment != [0; 32],
            InvalidInput,
            "sync download expectation is empty"
        );
        Ok(Self {
            identity: StreamIdentity {
                object_id,
                stream_id,
                stream_kind: StreamKind::Original,
                stream_revision: 1,
            },
            container_length,
            ordered_chunk_commitment,
        })
    }
}

/// A complete authenticated temporary container that is safe to publish locally.
pub struct VerifiedDownload {
    container: TemporaryContainer,
    manifest: CanonicalManifest,
    final_commit: CanonicalFinalCommit,
    ciphertext_size: u64,
}

impl VerifiedDownload {
    /// Authenticated sealed manifest.
    #[must_use]
    pub const fn manifest(&self) -> &CanonicalManifest {
        &self.manifest
    }

    /// Authenticated final commit.
    #[must_use]
    pub const fn final_commit(&self) -> &CanonicalFinalCommit {
        &self.final_commit
    }

    /// Complete encoded ciphertext length.
    #[must_use]
    pub const fn ciphertext_size(&self) -> u64 {
        self.ciphertext_size
    }

    /// Authenticated original plaintext length.
    #[must_use]
    pub const fn plaintext_size(&self) -> u64 {
        self.final_commit.total_plaintext_length()
    }

    /// Atomically moves the verified bytes into the committed object namespace.
    pub fn commit(
        self,
        root_dir: &VaultRoot,
        local_store_id: &Id,
        container_path_id: &Id,
    ) -> Result<()> {
        self.container
            .commit(root_dir, local_store_id, container_path_id)
    }

    /// Deletes the temporary ciphertext without publishing it.
    pub fn discard(self) -> Result<()> {
        self.container.discard()
    }
}

/// Downloads opaque bytes into the temporary namespace and authenticates every record.
pub fn stage(
    root_dir: &VaultRoot,
    local_store_id: &Id,
    temp_path_id: &Id,
    source: &mut impl ReadAt,
    object_key: &Key,
    expected: &Expectation,
) -> Result<VerifiedDownload> {
    ensure!(
        source.length() >= expected.container_length,
        ObjectIncomplete,
        "sync download is shorter than its committed length"
    );
    ensure!(
        source.length() == expected.container_length,
        ObjectCorrupt,
        "sync download has bytes after its committed length"
    );
    let mut container = TemporaryContainer::open(root_dir, local_store_id, temp_path_id)?;
    let mut offset = container.length()?;
    ensure!(
        offset <= expected.container_length,
        ObjectCorrupt,
        "resumable sync download exceeds its committed length"
    );
    container.truncate_to(offset)?;
    let mut buffer = vec![0; RANGE_BYTES];
    while offset < expected.container_length {
        let remaining = expected.container_length - offset;
        let length = usize::try_from(remaining.min(RANGE_BYTES as u64))
            .map_err(|_| chur_core::err!(ResourceLimitExceeded, "download range is too large"))?;
        source.read_at(offset, &mut buffer[..length])?;
        container.write(&buffer[..length])?;
        container.sync()?;
        offset += length as u64;
    }

    let mut view = Downloaded {
        container: &mut container,
        length: expected.container_length,
    };
    let mut reader = StreamReader::open(&mut view, object_key, &expected.identity)?;
    let manifest = reader.manifest().clone();
    let final_commit = reader.read_final_commit()?;
    reader.verify_complete()?;
    ensure!(
        final_commit.ordered_chunk_commitment() == &expected.ordered_chunk_commitment,
        ObjectCorrupt,
        "downloaded container commitment differs from the signed operation"
    );
    drop(reader);
    Ok(VerifiedDownload {
        container,
        manifest,
        final_commit,
        ciphertext_size: expected.container_length,
    })
}

struct Downloaded<'a> {
    container: &'a mut TemporaryContainer,
    length: u64,
}

impl ReadAt for Downloaded<'_> {
    fn length(&self) -> u64 {
        self.length
    }

    fn read_at(&mut self, offset: u64, buffer: &mut [u8]) -> Result<()> {
        let bytes = self.container.read_at(offset, buffer.len())?;
        buffer.copy_from_slice(&bytes);
        Ok(())
    }
}
