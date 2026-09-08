//! Sequential export and the plaintext scratch policy.
//!
//! `docs/security/PLAINTEXT_LIFECYCLE.md` §6 makes export the moment the user
//! deliberately leaves the vault boundary, and §5 bounds the one case where a
//! platform API accepts nothing but a file URL.

use std::io::Write;

use chur_catalog::vault::Session;
use chur_core::{ensure, limits::scratch as scratch_bounds, Id, Result};
use chur_crypto::random;
use chur_format::constants::StreamKind;

use crate::progress::{self, Progress};
use crate::reader;

/// The name of the scratch journal inside the scratch directory.
///
/// Entry names are random hex, so a fixed non-hex name cannot collide with
/// one, and §5 gives the journal opaque cleanup state only: an entry name and
/// the time it was created. No filename, path, or object identifier is in it.
const JOURNAL_NAME: &str = "scratch-journal";

/// The wall clock, which §5's hold bound is measured against.
fn now_ms() -> u64 {
    let Ok(since) = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH) else {
        return 0;
    };
    u64::try_from(since.as_millis()).unwrap_or(u64::MAX)
}

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

/// A plaintext scratch entry, `PLAINTEXT_LIFECYCLE.md` §5.
///
/// It exists only where a platform API accepts nothing but a file URL. The
/// range reader is the preferred path at every size, which is why the
/// single-entry cap sits far below the 1 TiB object bound: an object larger
/// than the cap has no scratch path at all.
pub struct ScratchEntry {
    path: std::path::PathBuf,
    directory: std::path::PathBuf,
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

    /// Deletes the entry and its journal row, which is part of the completion
    /// path rather than a later cleanup.
    pub fn release(self) -> Result<()> {
        let deleted = match std::fs::remove_file(&self.path) {
            Ok(()) => Ok(()),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(_) => Err(chur_core::err!(
                IoFailure,
                "the scratch entry could not be deleted"
            )),
        };
        deleted?;
        journal_remove(&self.directory, &self.entry_id.to_hex())
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
    progress: &mut impl Progress,
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
    // §5 bounds how long a consumer may hold an entry. The sweep runs before
    // the caps are counted, so an expired entry makes room rather than
    // triggering a refusal, and a row whose file is already gone is dropped.
    let created_at_ms = now_ms();
    sweep_expired(&directory, created_at_ms)?;
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
    // The row lands before the first plaintext byte: a crash mid-write then
    // leaves a journal row naming the partial file, which is what the startup
    // cleanup and the hold-bound sweep read.
    if let Err(error) = journal_append(&directory, &entry_id.to_hex(), created_at_ms) {
        drop(file);
        let _ = std::fs::remove_file(&path);
        return Err(error);
    }
    let step = u64::from(source.chunk_size());
    let mut offset = 0u64;
    while offset < size {
        if progress.cancelled() {
            // §5 caps what the scratch directory may hold, so a cancelled
            // materialization leaves nothing behind to count against the cap.
            drop(file);
            let _ = std::fs::remove_file(&path);
            let _ = journal_remove(&directory, &entry_id.to_hex());
            return Err(progress::cancelled("the materialization was cancelled"));
        }
        let take = step.min(size - offset);
        let plaintext = source.read_range(offset, take)?;
        file.write_all(&plaintext)
            .map_err(|_| chur_core::err!(IoFailure, "the scratch entry could not be written"))?;
        offset += take;
        progress.advance(offset);
    }
    file.sync_all()
        .map_err(|_| chur_core::err!(IoFailure, "the scratch entry could not be made durable"))?;
    Ok(ScratchEntry {
        path,
        directory,
        entry_id,
    })
}

/// Deletes every scratch entry and the journal, §5 and §8 step 8.
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

/// Deletes every entry the journal says has outlived §5's hold bound, and
/// drops every row whose file is already gone.
///
/// It returns the number of entries deleted. A consumer still holding an
/// expired entry sees the file disappear from underneath it, which is the
/// outcome §5 names; the range reader, not the scratch path, is how anything
/// long-running reads an object. It runs when the next entry is materialized,
/// which bounds an expired entry's life to the hold bound plus one call.
pub fn sweep_expired(directory: &std::path::Path, now: u64) -> Result<usize> {
    let rows = journal_rows(directory)?;
    if rows.is_empty() {
        return Ok(0);
    }
    let mut kept = String::new();
    let mut removed = 0;
    for (entry, created_at_ms) in rows {
        let path = directory.join(&entry);
        if now >= created_at_ms.saturating_add(scratch_bounds::HOLD_MS_MAX) {
            match std::fs::remove_file(&path) {
                Ok(()) => removed += 1,
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
                Err(_) => {
                    return Err(chur_core::err!(
                        IoFailure,
                        "an expired scratch entry could not be deleted"
                    ))
                }
            }
            continue;
        }
        if path.exists() {
            kept.push_str(&format!("{entry} {created_at_ms}\n"));
        }
    }
    std::fs::write(directory.join(JOURNAL_NAME), kept)
        .map_err(|_| chur_core::err!(IoFailure, "the scratch journal could not be rewritten"))?;
    Ok(removed)
}

/// Appends one row naming `entry` and the time it was created.
fn journal_append(directory: &std::path::Path, entry: &str, created_at_ms: u64) -> Result<()> {
    let mut journal = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(directory.join(JOURNAL_NAME))
        .map_err(|_| chur_core::err!(IoFailure, "the scratch journal could not be opened"))?;
    writeln!(journal, "{entry} {created_at_ms}")
        .map_err(|_| chur_core::err!(IoFailure, "the scratch journal could not be written"))
}

/// The journal's rows: an entry name and the time it was created.
///
/// A row this build cannot parse is skipped rather than fatal: the journal is
/// cleanup state, and the next rewrite drops what it cannot use.
fn journal_rows(directory: &std::path::Path) -> Result<Vec<(String, u64)>> {
    let text = match std::fs::read_to_string(directory.join(JOURNAL_NAME)) {
        Ok(text) => text,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(_) => {
            return Err(chur_core::err!(
                IoFailure,
                "the scratch journal could not be read"
            ))
        }
    };
    Ok(text
        .lines()
        .filter_map(|line| {
            let (entry, created_at_ms) = line.rsplit_once(' ')?;
            Some((entry.to_owned(), created_at_ms.parse::<u64>().ok()?))
        })
        .collect())
}

/// Rewrites the journal without the row `entry` names.
///
/// A journal left with no rows is removed, so an empty directory stays empty.
fn journal_remove(directory: &std::path::Path, entry: &str) -> Result<()> {
    let rows: Vec<(String, u64)> = journal_rows(directory)?
        .into_iter()
        .filter(|(name, _)| name != entry)
        .collect();
    if rows.is_empty() {
        let _ = std::fs::remove_file(directory.join(JOURNAL_NAME));
        return Ok(());
    }
    let text: String = rows
        .iter()
        .map(|(name, at)| format!("{name} {at}\n"))
        .collect();
    std::fs::write(directory.join(JOURNAL_NAME), text)
        .map_err(|_| chur_core::err!(IoFailure, "the scratch journal could not be rewritten"))
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
        // The journal is cleanup state, not an entry, so it counts against
        // neither the entry cap nor the directory cap.
        if entry.file_name().to_string_lossy() == JOURNAL_NAME {
            continue;
        }
        let metadata = entry
            .metadata()
            .map_err(|_| chur_core::err!(IoFailure, "a scratch entry could not be measured"))?;
        entries += 1;
        used = used.saturating_add(metadata.len());
    }
    Ok((entries, used))
}
