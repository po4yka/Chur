//! Sequential export and the plaintext scratch policy.
//!
//! `docs/security/PLAINTEXT_LIFECYCLE.md` §6 makes export the moment the user
//! deliberately leaves the vault boundary, and §5 bounds the one case where a
//! platform API accepts nothing but a file URL.

use std::io::Write;

use chur_catalog::vault::Session;
use chur_core::{Id, Result, ensure, limits::scratch as scratch_bounds};
use chur_crypto::random;
use chur_format::constants::StreamKind;

use crate::reader;

/// The plaintext a single export call moves per iteration.
///
/// It is one chunk, so an export of a 1 TiB object holds one chunk of
/// plaintext, which is what `MEDIA_PIPELINE.md` §12 bounds.
const EXPORT_STEP: u64 = 262_144;

/// Writes one stream's plaintext into `destination`, authenticating as it goes.
///
/// Nothing is written before it authenticates, so a truncated destination is a
/// partial export of verified bytes rather than a whole export of unverified
/// ones.
pub fn export_stream(
    session: &Session,
    object_id: &Id,
    stream_kind: StreamKind,
    destination: &mut impl Write,
) -> Result<u64> {
    let mut source = reader::open(session, object_id, stream_kind)?;
    let size = source.size();
    let mut offset = 0u64;
    while offset < size {
        let take = EXPORT_STEP.min(size - offset);
        let plaintext = source.read_range(offset, take)?;
        destination
            .write_all(&plaintext)
            .map_err(|_| chur_core::err!(IoFailure, "the export destination rejected a write"))?;
        offset += take;
    }
    destination
        .flush()
        .map_err(|_| chur_core::err!(IoFailure, "the export destination could not be flushed"))?;
    Ok(size)
}

/// A plaintext scratch entry, `PLAINTEXT_LIFECYCLE.md` §5.
///
/// It exists only where a platform API accepts nothing but a file URL. The
/// range reader is the preferred path at every size, which is why the
/// single-entry cap sits far below the 1 TiB object bound: an object larger
/// than the cap has no scratch path at all.
pub struct ScratchEntry {
    path: std::path::PathBuf,
    entry_id: Id,
}

impl ScratchEntry {
    /// The file a platform API is handed.
    #[must_use]
    pub fn path(&self) -> &std::path::Path {
        &self.path
    }

    /// The opaque identifier the scratch journal records.
    #[must_use]
    pub const fn entry_id(&self) -> Id {
        self.entry_id
    }

    /// Deletes the entry, which is part of the completion path rather than a
    /// later cleanup.
    pub fn release(self) -> Result<()> {
        match std::fs::remove_file(&self.path) {
            Ok(()) => Ok(()),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(_) => Err(chur_core::err!(
                IoFailure,
                "the scratch entry could not be deleted"
            )),
        }
    }
}

/// Materializes one stream as a plaintext scratch file, §5.
///
/// Every cap is checked before the first plaintext byte is written. Exceeding
/// one fails with `RESOURCE_LIMIT_EXCEEDED`; nothing is truncated, and no
/// existing entry is evicted to make room.
pub fn materialize(
    session: &Session,
    object_id: &Id,
    stream_kind: StreamKind,
) -> Result<ScratchEntry> {
    let mut source = reader::open(session, object_id, stream_kind)?;
    let size = source.size();
    ensure!(
        size <= scratch_bounds::ENTRY_MAX,
        ResourceLimitExceeded,
        "the object exceeds the single-entry scratch cap, so it has no scratch path"
    );

    let directory = session.root_dir().scratch(&session.object_store_id());
    std::fs::create_dir_all(&directory)
        .map_err(|_| chur_core::err!(IoFailure, "the scratch directory could not be created"))?;
    let (entries, used) = scratch_usage(&directory)?;
    ensure!(
        entries < scratch_bounds::ENTRIES_MAX,
        ResourceLimitExceeded,
        "the vault holds the maximum number of scratch entries"
    );
    ensure!(
        used.saturating_add(size) <= scratch_bounds::DIRECTORY_MAX,
        ResourceLimitExceeded,
        "the scratch directory would exceed its total cap"
    );

    // A random opaque filename and no extension: §5 permits an extension only
    // where a consumer requires one, and none of v1's consumers does.
    let entry_id = random::id()?;
    let path = directory.join(entry_id.to_hex());
    let mut file = std::fs::File::create(&path)
        .map_err(|_| chur_core::err!(IoFailure, "the scratch entry could not be created"))?;
    let mut offset = 0u64;
    while offset < size {
        let take = EXPORT_STEP.min(size - offset);
        let plaintext = source.read_range(offset, take)?;
        file.write_all(&plaintext)
            .map_err(|_| chur_core::err!(IoFailure, "the scratch entry could not be written"))?;
        offset += take;
    }
    file.sync_all()
        .map_err(|_| chur_core::err!(IoFailure, "the scratch entry could not be made durable"))?;
    Ok(ScratchEntry { path, entry_id })
}

/// Deletes every scratch entry, §5 and §8 step 8.
///
/// It runs at startup for entries a previous process abandoned, and at lock for
/// every entry whatever its consumer's state.
pub fn clear_scratch(session: &Session) -> Result<usize> {
    let directory = session.root_dir().scratch(&session.object_store_id());
    let listing = match std::fs::read_dir(&directory) {
        Ok(listing) => listing,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(0),
        Err(_) => {
            return Err(chur_core::err!(
                IoFailure,
                "the scratch directory could not be read"
            ));
        }
    };
    let mut removed = 0;
    for entry in listing {
        let entry =
            entry.map_err(|_| chur_core::err!(IoFailure, "a scratch entry could not be read"))?;
        std::fs::remove_file(entry.path())
            .map_err(|_| chur_core::err!(IoFailure, "a scratch entry could not be deleted"))?;
        removed += 1;
    }
    Ok(removed)
}

fn scratch_usage(directory: &std::path::Path) -> Result<(u32, u64)> {
    let listing = match std::fs::read_dir(directory) {
        Ok(listing) => listing,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok((0, 0)),
        Err(_) => {
            return Err(chur_core::err!(
                IoFailure,
                "the scratch directory could not be read"
            ));
        }
    };
    let mut entries = 0u32;
    let mut used = 0u64;
    for entry in listing {
        let entry =
            entry.map_err(|_| chur_core::err!(IoFailure, "a scratch entry could not be read"))?;
        let metadata = entry
            .metadata()
            .map_err(|_| chur_core::err!(IoFailure, "a scratch entry could not be measured"))?;
        entries += 1;
        used = used.saturating_add(metadata.len());
    }
    Ok((entries, used))
}
