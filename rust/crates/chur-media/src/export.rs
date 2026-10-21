//! Sequential export and the plaintext scratch policy.
//!
//! `docs/security/PLAINTEXT_LIFECYCLE.md` §6 makes export the moment the user
//! deliberately leaves the vault boundary, and §5 bounds the one case where a
//! platform API accepts nothing but a file URL.

use std::io::Write;

use chur_catalog::vault::Session;
use chur_core::{Id, Result};
use chur_format::constants::StreamKind;

use crate::progress::{self, Progress};
use crate::reader;

/// Writes one stream's plaintext into `destination`, authenticating as it goes.
///
/// Nothing is written before it authenticates, so a truncated destination is a
/// partial export of verified bytes rather than a whole export of unverified
/// ones.
///
/// The step is the container's own chunk size rather than a constant of this
/// module. A step smaller than the chunk would authenticate and decrypt the
/// same chunk once per step: at the 1 MiB video chunk of
/// `OBJECT_CONTAINER_V1.md` §6 a 256 KiB step costs four decryptions per chunk,
/// on exactly the objects that make an export long.
///
/// `progress` is read once per step, so a cancelled export of a 1 TiB object
/// stops within one chunk rather than at the end.
pub fn export_stream(
    session: &Session,
    object_id: &Id,
    stream_kind: StreamKind,
    destination: &mut impl Write,
    progress: &mut impl Progress,
) -> Result<u64> {
    let mut source = reader::open(session, object_id, stream_kind)?;
    let size = source.size();
    let step = u64::from(source.chunk_size());
    let mut offset = 0u64;
    while offset < size {
        if progress.cancelled() {
            return Err(progress::cancelled("the export was cancelled"));
        }
        let take = step.min(size - offset);
        let plaintext = source.read_range(offset, take)?;
        destination
            .write_all(&plaintext)
            .map_err(|_| chur_core::err!(IoFailure, "the export destination rejected a write"))?;
        offset += take;
        progress.advance(offset);
    }
    destination
        .flush()
        .map_err(|_| chur_core::err!(IoFailure, "the export destination could not be flushed"))?;
    Ok(size)
}

/// Deletes every scratch entry, §5 and §8 step 8.
///
/// It runs at startup, where `chur_vault_unlock` calls it before the new
/// session becomes a handle, so entries a previous process abandoned never
/// outlive its crash. The lock path of §8 step 8 lives in the catalog's
/// session lock, which removes the whole directory in one step.
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
