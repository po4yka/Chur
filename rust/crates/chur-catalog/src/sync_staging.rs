//! Bounded opaque sync staging while a vault is locked.

use std::fs::{File, OpenOptions};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};

use chur_core::limits::sync as bounds;
use chur_core::{ChurStatus, Error, Id, Result, ensure};

const MAGIC: &[u8; 8] = b"CHURSTG1";
const HEADER_LEN: usize = 8 + 8 + 8 + 16;
const SUFFIX: &str = ".stage";
const TEMP_SUFFIX: &str = ".stage.tmp";

struct Entry {
    id: Id,
    staged_at_ms: u64,
    payload_len: u64,
    path: PathBuf,
}

struct TemporaryFile {
    path: PathBuf,
    installed: bool,
}

impl Drop for TemporaryFile {
    fn drop(&mut self) {
        if !self.installed {
            let _ = std::fs::remove_file(&self.path);
        }
    }
}

/// One opaque staged record returned for full validation after unlock.
pub struct StagedRecord {
    id: Id,
    staged_at_ms: u64,
    bytes: Vec<u8>,
}

impl StagedRecord {
    /// Stable opaque record identifier.
    #[must_use]
    pub const fn id(&self) -> &Id {
        &self.id
    }

    /// Local arrival time used only for bounded retention.
    #[must_use]
    pub const fn staged_at_ms(&self) -> u64 {
        self.staged_at_ms
    }

    /// Untrusted bytes that must be validated from the start after unlock.
    #[must_use]
    pub fn bytes(&self) -> &[u8] {
        &self.bytes
    }
}

/// App-private disposable storage for ciphertext and signed opaque bytes.
pub struct LockedStaging {
    directory: PathBuf,
}

impl LockedStaging {
    /// Opens or creates one vault's staging directory and removes crash temps.
    pub fn open(directory: impl Into<PathBuf>) -> Result<Self> {
        let directory = directory.into();
        std::fs::create_dir_all(&directory)
            .map_err(|_| Error::new(ChurStatus::IoFailure, "sync staging could not be created"))?;
        let area = Self { directory };
        area.sweep_temporary()?;
        Ok(area)
    }

    /// Durably stages one unacknowledged record, evicting oldest records first.
    pub fn stage(&mut self, id: Id, staged_at_ms: u64, bytes: &[u8]) -> Result<()> {
        ensure!(
            staged_at_ms
                .checked_add(bounds::STAGED_RETENTION_MS)
                .is_some(),
            InvalidInput,
            "staging time has no retention successor"
        );
        let payload_len = u64::try_from(bytes.len()).map_err(|_| {
            Error::new(
                ChurStatus::ResourceLimitExceeded,
                "staged record length does not fit u64",
            )
        })?;
        ensure!(
            payload_len <= bounds::STAGED_BYTES_MAX,
            ResourceLimitExceeded,
            "one staged record exceeds the per-vault byte bound"
        );
        if let Some(existing) = self.record(&id, staged_at_ms)? {
            ensure!(
                existing.bytes() == bytes,
                Conflict,
                "staged record identifier names different bytes"
            );
            return Ok(());
        }

        let temporary = self.temporary_path(&id);
        let installed = self.record_path(&id);
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&temporary)
            .map_err(|_| {
                Error::new(
                    ChurStatus::IoFailure,
                    "staged record temporary file could not be created",
                )
            })?;
        let mut temporary_file = TemporaryFile {
            path: temporary.clone(),
            installed: false,
        };
        file.write_all(MAGIC)
            .and_then(|()| file.write_all(&staged_at_ms.to_be_bytes()))
            .and_then(|()| file.write_all(&payload_len.to_be_bytes()))
            .and_then(|()| file.write_all(id.as_bytes()))
            .and_then(|()| file.write_all(bytes))
            .and_then(|()| file.sync_all())
            .map_err(|_| {
                Error::new(
                    ChurStatus::IoFailure,
                    "staged record could not be made durable",
                )
            })?;

        let mut entries = self.scan(staged_at_ms)?;
        let mut total = entries.iter().try_fold(0u64, |sum, entry| {
            sum.checked_add(entry.payload_len).ok_or_else(|| {
                Error::new(
                    ChurStatus::ResourceLimitExceeded,
                    "staged byte total overflows",
                )
            })
        })?;
        while entries.len() >= bounds::STAGED_RECORDS_MAX
            || total
                .checked_add(payload_len)
                .is_none_or(|sum| sum > bounds::STAGED_BYTES_MAX)
        {
            let oldest = entries.remove(0);
            std::fs::remove_file(&oldest.path).map_err(|_| {
                Error::new(
                    ChurStatus::IoFailure,
                    "old staged record could not be evicted",
                )
            })?;
            total -= oldest.payload_len;
        }
        std::fs::rename(&temporary, &installed).map_err(|_| {
            Error::new(
                ChurStatus::IoFailure,
                "staged record could not be installed",
            )
        })?;
        temporary_file.installed = true;
        sync_directory(&self.directory);
        Ok(())
    }

    /// Returns the oldest retained record without acknowledging or deleting it.
    pub fn next(&mut self, now_ms: u64) -> Result<Option<StagedRecord>> {
        let Some(entry) = self.scan(now_ms)?.into_iter().next() else {
            return Ok(None);
        };
        self.read_record(&entry).map(Some)
    }

    /// Returns one retained record without acknowledging or deleting it.
    pub fn record(&mut self, id: &Id, now_ms: u64) -> Result<Option<StagedRecord>> {
        let Some(entry) = self.scan(now_ms)?.into_iter().find(|entry| &entry.id == id) else {
            return Ok(None);
        };
        self.read_record(&entry).map(Some)
    }

    /// Number of non-expired staged records.
    pub fn len(&mut self, now_ms: u64) -> Result<usize> {
        self.scan(now_ms).map(|entries| entries.len())
    }

    /// Removes a record only after its caller has fully validated or rejected it.
    pub fn remove(&mut self, id: &Id) -> Result<()> {
        match std::fs::remove_file(self.record_path(id)) {
            Ok(()) => {
                sync_directory(&self.directory);
                Ok(())
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(_) => Err(Error::new(
                ChurStatus::IoFailure,
                "staged record could not be removed",
            )),
        }
    }

    fn scan(&self, now_ms: u64) -> Result<Vec<Entry>> {
        let listing = std::fs::read_dir(&self.directory)
            .map_err(|_| Error::new(ChurStatus::IoFailure, "sync staging could not be listed"))?;
        let mut entries = Vec::new();
        for item in listing {
            let item = item.map_err(|_| {
                Error::new(ChurStatus::IoFailure, "staging entry could not be read")
            })?;
            let file_type = item.file_type().map_err(|_| {
                Error::new(
                    ChurStatus::IoFailure,
                    "staging entry type could not be read",
                )
            })?;
            let Some(name) = item.file_name().to_str().map(str::to_owned) else {
                continue;
            };
            if name.ends_with(TEMP_SUFFIX) {
                continue;
            }
            if !name.ends_with(SUFFIX) {
                continue;
            }
            if !file_type.is_file() {
                std::fs::remove_file(item.path()).map_err(|_| {
                    Error::new(
                        ChurStatus::IoFailure,
                        "non-file staging entry could not be removed",
                    )
                })?;
                continue;
            }
            match read_entry(&item.path(), &name) {
                Ok(entry)
                    if now_ms.saturating_sub(entry.staged_at_ms) < bounds::STAGED_RETENTION_MS =>
                {
                    entries.push(entry);
                }
                Ok(_) => {
                    std::fs::remove_file(item.path()).map_err(|_| {
                        Error::new(
                            ChurStatus::IoFailure,
                            "expired staged record could not be removed",
                        )
                    })?;
                }
                Err(error) if error.status() == ChurStatus::CatalogCorrupt => {
                    std::fs::remove_file(item.path()).map_err(|_| {
                        Error::new(
                            ChurStatus::IoFailure,
                            "malformed staged record could not be removed",
                        )
                    })?;
                }
                Err(error) => return Err(error),
            }
        }
        entries.sort_by_key(|entry| (entry.staged_at_ms, entry.id));
        Ok(entries)
    }

    fn read_record(&self, entry: &Entry) -> Result<StagedRecord> {
        let capacity = usize::try_from(entry.payload_len).map_err(|_| {
            Error::new(
                ChurStatus::ResourceLimitExceeded,
                "staged record is too large for this platform",
            )
        })?;
        let mut file = File::open(&entry.path)
            .map_err(|_| Error::new(ChurStatus::IoFailure, "staged record could not be opened"))?;
        let mut header = [0u8; HEADER_LEN];
        file.read_exact(&mut header)
            .map_err(|_| Error::new(ChurStatus::IoFailure, "staged header could not be read"))?;
        let mut bytes = Vec::with_capacity(capacity);
        (&mut file)
            .take(entry.payload_len + 1)
            .read_to_end(&mut bytes)
            .map_err(|_| Error::new(ChurStatus::IoFailure, "staged bytes could not be read"))?;
        ensure!(
            bytes.len() == capacity,
            CatalogCorrupt,
            "staged record changed after enumeration"
        );
        Ok(StagedRecord {
            id: entry.id,
            staged_at_ms: entry.staged_at_ms,
            bytes,
        })
    }

    fn record_path(&self, id: &Id) -> PathBuf {
        self.directory.join(format!("{}{SUFFIX}", id.to_hex()))
    }

    fn temporary_path(&self, id: &Id) -> PathBuf {
        self.directory.join(format!("{}{TEMP_SUFFIX}", id.to_hex()))
    }

    fn sweep_temporary(&self) -> Result<()> {
        let listing = std::fs::read_dir(&self.directory)
            .map_err(|_| Error::new(ChurStatus::IoFailure, "sync staging could not be listed"))?;
        for item in listing {
            let item = item.map_err(|_| {
                Error::new(ChurStatus::IoFailure, "staging entry could not be read")
            })?;
            if item
                .file_name()
                .to_str()
                .is_some_and(|name| name.ends_with(TEMP_SUFFIX))
            {
                std::fs::remove_file(item.path()).map_err(|_| {
                    Error::new(
                        ChurStatus::IoFailure,
                        "staging temporary file could not be removed",
                    )
                })?;
            }
        }
        Ok(())
    }
}

fn read_entry(path: &Path, name: &str) -> Result<Entry> {
    let metadata = path
        .metadata()
        .map_err(|_| Error::new(ChurStatus::IoFailure, "staged metadata could not be read"))?;
    ensure!(
        metadata.len() >= HEADER_LEN as u64,
        CatalogCorrupt,
        "staged record is shorter than its header"
    );
    let mut header = [0u8; HEADER_LEN];
    File::open(path)
        .and_then(|mut file| file.read_exact(&mut header))
        .map_err(|_| Error::new(ChurStatus::IoFailure, "staged header could not be read"))?;
    ensure!(
        &header[..8] == MAGIC,
        CatalogCorrupt,
        "staged record magic is invalid"
    );
    let staged_at_ms = u64::from_be_bytes(header[8..16].try_into().map_err(|_| {
        Error::new(
            ChurStatus::InternalFailure,
            "staging timestamp width changed",
        )
    })?);
    let payload_len =
        u64::from_be_bytes(header[16..24].try_into().map_err(|_| {
            Error::new(ChurStatus::InternalFailure, "staging length width changed")
        })?);
    ensure!(
        payload_len <= bounds::STAGED_BYTES_MAX
            && metadata.len() == HEADER_LEN as u64 + payload_len,
        CatalogCorrupt,
        "staged record length is invalid"
    );
    let id = Id::from_slice(&header[24..40]).map_err(|_| {
        Error::new(
            ChurStatus::CatalogCorrupt,
            "staged record identifier is invalid",
        )
    })?;
    ensure!(
        name == format!("{}{SUFFIX}", id.to_hex()),
        CatalogCorrupt,
        "staged record name does not match its identifier"
    );
    Ok(Entry {
        id,
        staged_at_ms,
        payload_len,
        path: path.to_owned(),
    })
}

fn sync_directory(path: &Path) {
    if let Ok(directory) = File::open(path) {
        let _ = directory.sync_all();
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::expect_used, clippy::panic)]

    use chur_core::limits::sync as bounds;
    use chur_crypto::random;

    use super::*;

    fn area() -> (std::path::PathBuf, LockedStaging) {
        let path = std::env::temp_dir().join(format!(
            "chur-sync-staging-{}",
            random::id().expect("id").to_hex()
        ));
        let area = LockedStaging::open(&path).expect("open");
        (path, area)
    }

    #[test]
    fn staging_is_idempotent_bounded_and_expires_without_acknowledging() {
        let (path, mut area) = area();
        let first = random::id().expect("id");
        area.stage(first, 1, b"first").expect("first");
        area.stage(first, 1, b"first").expect("duplicate");
        assert!(area.stage(first, 1, b"fork").is_err());

        let newest = random::id().expect("id");
        area.stage(newest, 10_000, b"newest").expect("newest");
        let record = area.next(10_000).expect("next").expect("record");
        assert_eq!(record.id(), &first);
        assert_eq!(record.bytes(), b"first");
        area.remove(&first).expect("remove");
        assert_eq!(area.len(10_000).expect("len"), 1);
        assert!(
            area.next(10_000 + bounds::STAGED_RETENTION_MS)
                .expect("expired")
                .is_none()
        );
        assert_eq!(bounds::STAGED_RECORDS_MAX, 4_096);
        assert_eq!(bounds::STAGED_BYTES_MAX, 64 * 1_024 * 1_024);
        std::fs::remove_dir_all(path).expect("cleanup");
    }
}
