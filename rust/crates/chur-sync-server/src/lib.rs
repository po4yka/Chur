//! Self-hosted ciphertext-only Chur sync service.

mod auth;
mod checkpoint;
mod deletion;
pub mod http;
mod relay;
mod sharing;

pub use deletion::DeletionOutcome;
pub use relay::RelayOutcome;

use std::fs::{self, File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};

use chur_core::{ChurStatus, Error, Id, Result, ensure};
use rusqlite::{Connection, OptionalExtension, params};
use sha2::{Digest, Sha256};

const DOWNLOAD_RANGE_MAX: u64 = 16 * 1024 * 1024;

/// Durable progress for one opaque upload.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct UploadProgress {
    /// Bytes durably accepted in order.
    pub received: u64,
    /// Exact ciphertext length declared at creation.
    pub expected: u64,
    /// Whether the immutable object is available for download.
    pub complete: bool,
}

/// Persistent core of the self-hosted reference sync service.
pub struct ReferenceServer {
    root: PathBuf,
    db: Connection,
    max_object_bytes: u64,
    max_account_bytes: u64,
}

impl ReferenceServer {
    /// Opens or creates one service data directory.
    pub fn open(
        root: impl AsRef<Path>,
        max_object_bytes: u64,
        max_account_bytes: u64,
    ) -> Result<Self> {
        ensure!(
            max_object_bytes != 0
                && max_object_bytes <= max_account_bytes
                && max_account_bytes <= i64::MAX as u64,
            InvalidInput,
            "server storage limits are invalid"
        );
        let root = root.as_ref().to_path_buf();
        fs::create_dir_all(&root).map_err(|_| {
            Error::new(
                ChurStatus::StorageUnavailable,
                "server root creation failed",
            )
        })?;
        let mut db = Connection::open(root.join("server.sqlite"))
            .map_err(|error| map_sqlite(error, "server database open failed"))?;
        db.execute_batch(
            "PRAGMA journal_mode = WAL;
             PRAGMA synchronous = FULL;
             PRAGMA secure_delete = ON;
             CREATE TABLE IF NOT EXISTS object_transfers (
                 vault_id BLOB NOT NULL CHECK(length(vault_id) = 16),
                 transfer_id BLOB NOT NULL CHECK(length(transfer_id) = 16),
                 store_id BLOB NOT NULL CHECK(length(store_id) = 16),
                 expected_length INTEGER NOT NULL CHECK(expected_length > 0),
                 received_length INTEGER NOT NULL CHECK(
                     received_length >= 0 AND received_length <= expected_length
                 ),
                 complete INTEGER NOT NULL CHECK(complete IN (0, 1)),
                 object_sha256 BLOB CHECK(object_sha256 IS NULL OR length(object_sha256) = 32),
                 PRIMARY KEY(vault_id, transfer_id),
                 UNIQUE(vault_id, store_id)
             );
             CREATE TABLE IF NOT EXISTS operations (
                 vault_id BLOB NOT NULL CHECK(length(vault_id) = 16),
                 device_id BLOB NOT NULL CHECK(length(device_id) = 16),
                 device_sequence INTEGER NOT NULL CHECK(device_sequence > 0),
                 operation_id BLOB NOT NULL CHECK(length(operation_id) = 16),
                 digest BLOB NOT NULL CHECK(length(digest) = 32),
                 record BLOB NOT NULL,
                 PRIMARY KEY(vault_id, device_id, device_sequence),
                 UNIQUE(vault_id, operation_id)
             );
             CREATE TABLE IF NOT EXISTS membership_records (
                 vault_id BLOB NOT NULL CHECK(length(vault_id) = 16),
                 membership_generation INTEGER NOT NULL CHECK(membership_generation > 0),
                 record_kind INTEGER NOT NULL CHECK(record_kind IN (1, 2)),
                 outer_device_id BLOB NOT NULL CHECK(length(outer_device_id) = 16),
                 outer_device_sequence INTEGER NOT NULL CHECK(outer_device_sequence > 0),
                 record BLOB NOT NULL,
                 PRIMARY KEY(vault_id, membership_generation)
             );
             CREATE TABLE IF NOT EXISTS collection_membership_records (
                 collection_id BLOB NOT NULL CHECK(length(collection_id) = 16),
                 membership_generation INTEGER NOT NULL CHECK(membership_generation > 0),
                 issuer_vault_id BLOB NOT NULL CHECK(length(issuer_vault_id) = 16),
                 issuer_signing_public_key BLOB NOT NULL CHECK(length(issuer_signing_public_key) = 32),
                 recipient_vault_id BLOB NOT NULL CHECK(length(recipient_vault_id) = 16),
                 recipient_device_id BLOB NOT NULL CHECK(length(recipient_device_id) = 16),
                 outer_device_id BLOB NOT NULL CHECK(length(outer_device_id) = 16),
                 outer_device_sequence INTEGER NOT NULL CHECK(outer_device_sequence > 0),
                 record BLOB NOT NULL,
                 PRIMARY KEY(collection_id, membership_generation)
             );
             CREATE INDEX IF NOT EXISTS collection_membership_recipient
                 ON collection_membership_records(recipient_vault_id, recipient_device_id);
             CREATE TABLE IF NOT EXISTS collection_grants (
                 grant_id BLOB PRIMARY KEY CHECK(length(grant_id) = 16),
                 collection_id BLOB NOT NULL CHECK(length(collection_id) = 16),
                 collection_epoch INTEGER NOT NULL CHECK(collection_epoch > 0),
                 issuer_vault_id BLOB NOT NULL CHECK(length(issuer_vault_id) = 16),
                 recipient_vault_id BLOB NOT NULL CHECK(length(recipient_vault_id) = 16),
                 recipient_device_id BLOB NOT NULL CHECK(length(recipient_device_id) = 16),
                 outer_device_id BLOB NOT NULL CHECK(length(outer_device_id) = 16),
                 outer_device_sequence INTEGER NOT NULL CHECK(outer_device_sequence > 0),
                 key_selector BLOB NOT NULL CHECK(length(key_selector) = 16),
                 record BLOB NOT NULL
             );
             CREATE INDEX IF NOT EXISTS collection_grant_recipient
                 ON collection_grants(recipient_vault_id, recipient_device_id, collection_id);
             CREATE TABLE IF NOT EXISTS collection_operations (
                 key_selector BLOB NOT NULL CHECK(length(key_selector) = 16),
                 issuer_vault_id BLOB NOT NULL CHECK(length(issuer_vault_id) = 16),
                 issuer_device_id BLOB NOT NULL CHECK(length(issuer_device_id) = 16),
                 device_sequence INTEGER NOT NULL CHECK(device_sequence > 0),
                 operation_id BLOB NOT NULL CHECK(length(operation_id) = 16),
                 digest BLOB NOT NULL CHECK(length(digest) = 32),
                 record BLOB NOT NULL,
                 PRIMARY KEY(key_selector, issuer_vault_id, issuer_device_id, device_sequence),
                 UNIQUE(key_selector, operation_id)
             );
             CREATE TABLE IF NOT EXISTS deletion_requests (
                 vault_id BLOB NOT NULL CHECK(length(vault_id) = 16),
                 request_id BLOB NOT NULL CHECK(length(request_id) = 16),
                 target_kind INTEGER NOT NULL CHECK(target_kind IN (1, 2)),
                 target_id BLOB NOT NULL CHECK(length(target_id) = 16),
                 record BLOB NOT NULL,
                 PRIMARY KEY(vault_id, request_id)
             );
             CREATE TABLE IF NOT EXISTS checkpoints (
                 vault_id BLOB NOT NULL CHECK(length(vault_id) = 16),
                 issuer_device_id BLOB NOT NULL CHECK(length(issuer_device_id) = 16),
                 issuer_sequence INTEGER NOT NULL CHECK(issuer_sequence > 0),
                 commitment BLOB NOT NULL CHECK(length(commitment) = 32),
                 record BLOB NOT NULL,
                 PRIMARY KEY(vault_id, commitment),
                 UNIQUE(vault_id, issuer_device_id, issuer_sequence)
             );
             CREATE TABLE IF NOT EXISTS transport_tokens (
                 vault_id BLOB NOT NULL CHECK(length(vault_id) = 16),
                 device_id BLOB NOT NULL CHECK(length(device_id) = 16),
                 token_sha256 BLOB NOT NULL CHECK(length(token_sha256) = 32),
                 PRIMARY KEY(vault_id, device_id),
                 UNIQUE(vault_id, token_sha256)
             );",
        )
        .map_err(|error| map_sqlite(error, "server schema creation failed"))?;
        sharing::migrate_outer_associations(&mut db)?;
        Ok(Self {
            root,
            db,
            max_object_bytes,
            max_account_bytes,
        })
    }

    /// Creates an idempotent sequential-range upload.
    pub fn begin_upload(
        &mut self,
        vault_id: Id,
        transfer_id: Id,
        store_id: Id,
        expected_length: u64,
    ) -> Result<UploadProgress> {
        ensure!(
            expected_length != 0 && expected_length <= self.max_object_bytes,
            ResourceLimitExceeded,
            "object upload length exceeds the configured limit"
        );
        let existing = self
            .db
            .query_row(
                "SELECT store_id, expected_length, received_length, complete
                 FROM object_transfers WHERE vault_id = ?1 AND transfer_id = ?2",
                params![
                    vault_id.as_bytes().as_slice(),
                    transfer_id.as_bytes().as_slice()
                ],
                |row| {
                    Ok((
                        row.get::<_, Vec<u8>>(0)?,
                        row.get::<_, i64>(1)?,
                        row.get::<_, i64>(2)?,
                        row.get::<_, bool>(3)?,
                    ))
                },
            )
            .optional()
            .map_err(|error| map_sqlite(error, "upload lookup failed"))?;
        if let Some((stored_id, expected, received, complete)) = existing {
            ensure!(
                stored_id == store_id.as_bytes()
                    && expected == to_sqlite(expected_length, "upload length does not fit")?,
                Conflict,
                "upload identifier was reused with different fields"
            );
            if !complete && received == 0 && !self.partial_path(vault_id, transfer_id).exists() {
                self.create_empty_partial(vault_id, transfer_id)?;
            }
            return progress(received, expected, complete);
        }

        let reserved: i64 = self
            .db
            .query_row(
                "SELECT
                    COALESCE((SELECT SUM(expected_length) FROM object_transfers WHERE vault_id = ?1), 0)
                  + COALESCE((SELECT SUM(length(record)) FROM operations WHERE vault_id = ?1), 0)
                  + COALESCE((SELECT SUM(length(record)) FROM membership_records WHERE vault_id = ?1), 0)
                  + COALESCE((SELECT SUM(length(record)) FROM collection_membership_records WHERE issuer_vault_id = ?1), 0)
                  + COALESCE((SELECT SUM(length(record)) FROM collection_grants WHERE issuer_vault_id = ?1), 0)
                  + COALESCE((SELECT SUM(length(record)) FROM checkpoints WHERE vault_id = ?1), 0)",
                params![vault_id.as_bytes().as_slice()],
                |row| row.get(0),
            )
            .map_err(|error| map_sqlite(error, "account storage usage lookup failed"))?;
        let expected = to_sqlite(expected_length, "upload length does not fit")?;
        ensure!(
            reserved
                .checked_add(expected)
                .is_some_and(|total| total <= self.max_account_bytes as i64),
            ResourceLimitExceeded,
            "account ciphertext quota is exceeded"
        );
        self.db
            .execute(
                "INSERT INTO object_transfers (
                    vault_id, transfer_id, store_id, expected_length,
                    received_length, complete, object_sha256
                 ) VALUES (?1, ?2, ?3, ?4, 0, 0, NULL)",
                params![
                    vault_id.as_bytes().as_slice(),
                    transfer_id.as_bytes().as_slice(),
                    store_id.as_bytes().as_slice(),
                    expected,
                ],
            )
            .map_err(|error| map_sqlite(error, "upload creation failed"))?;
        if let Err(error) = self.create_empty_partial(vault_id, transfer_id) {
            let _ = self.db.execute(
                "DELETE FROM object_transfers WHERE vault_id = ?1 AND transfer_id = ?2",
                params![
                    vault_id.as_bytes().as_slice(),
                    transfer_id.as_bytes().as_slice()
                ],
            );
            return Err(error);
        }
        Ok(UploadProgress {
            received: 0,
            expected: expected_length,
            complete: false,
        })
    }

    /// Appends one checksum-verified range, or accepts an exact range replay.
    pub fn append_upload(
        &mut self,
        vault_id: Id,
        transfer_id: Id,
        offset: u64,
        bytes: &[u8],
        expected_sha256: [u8; 32],
    ) -> Result<UploadProgress> {
        ensure!(!bytes.is_empty(), InvalidInput, "upload range is empty");
        ensure!(
            <Sha256 as Digest>::digest(bytes).as_slice() == expected_sha256,
            AuthenticationFailed,
            "upload transport checksum did not verify"
        );
        let (expected, received, complete) = self.transfer_state(vault_id, transfer_id)?;
        ensure!(
            !complete,
            Conflict,
            "completed upload cannot accept a range"
        );
        let length = u64::try_from(bytes.len()).map_err(|_| {
            Error::new(
                ChurStatus::ResourceLimitExceeded,
                "upload range is too large",
            )
        })?;
        let end = offset.checked_add(length).ok_or_else(|| {
            Error::new(ChurStatus::ResourceLimitExceeded, "upload range overflows")
        })?;
        ensure!(
            end <= expected,
            ResourceLimitExceeded,
            "upload range exceeds object length"
        );
        ensure!(offset <= received, Conflict, "upload range has a gap");

        let path = self.partial_path(vault_id, transfer_id);
        if offset < received {
            ensure!(
                end <= received,
                Conflict,
                "upload replay overlaps new bytes"
            );
            let mut file = File::open(path).map_err(|_| {
                Error::new(
                    ChurStatus::StorageUnavailable,
                    "upload replay source is absent",
                )
            })?;
            file.seek(SeekFrom::Start(offset))
                .map_err(|_| Error::new(ChurStatus::IoFailure, "upload replay seek failed"))?;
            let mut stored = vec![0; bytes.len()];
            file.read_exact(&mut stored).map_err(|_| {
                Error::new(
                    ChurStatus::StorageUnavailable,
                    "upload replay bytes are absent",
                )
            })?;
            ensure!(stored == bytes, Conflict, "upload replay bytes differ");
            return Ok(UploadProgress {
                received,
                expected,
                complete: false,
            });
        }

        let mut file = OpenOptions::new()
            .read(true)
            .write(true)
            .open(path)
            .map_err(|_| Error::new(ChurStatus::StorageUnavailable, "upload file is absent"))?;
        file.set_len(received)
            .and_then(|()| file.seek(SeekFrom::Start(received)).map(|_| ()))
            .and_then(|()| file.write_all(bytes))
            .and_then(|()| file.sync_data())
            .map_err(|_| {
                Error::new(
                    ChurStatus::StorageUnavailable,
                    "upload range was not stored",
                )
            })?;
        self.db
            .execute(
                "UPDATE object_transfers SET received_length = ?3
                 WHERE vault_id = ?1 AND transfer_id = ?2 AND received_length = ?4",
                params![
                    vault_id.as_bytes().as_slice(),
                    transfer_id.as_bytes().as_slice(),
                    to_sqlite(end, "received length does not fit")?,
                    to_sqlite(received, "stored received length does not fit")?,
                ],
            )
            .map_err(|error| map_sqlite(error, "upload progress update failed"))?;
        Ok(UploadProgress {
            received: end,
            expected,
            complete: false,
        })
    }

    /// Verifies and atomically publishes one complete immutable ciphertext.
    pub fn finish_upload(
        &mut self,
        vault_id: Id,
        transfer_id: Id,
        expected_sha256: [u8; 32],
    ) -> Result<UploadProgress> {
        let (store_id, expected, received, complete, stored_sha256) = self
            .db
            .query_row(
                "SELECT store_id, expected_length, received_length, complete, object_sha256
                 FROM object_transfers WHERE vault_id = ?1 AND transfer_id = ?2",
                params![
                    vault_id.as_bytes().as_slice(),
                    transfer_id.as_bytes().as_slice()
                ],
                |row| {
                    Ok((
                        row.get::<_, Vec<u8>>(0)?,
                        row.get::<_, i64>(1)?,
                        row.get::<_, i64>(2)?,
                        row.get::<_, bool>(3)?,
                        row.get::<_, Option<Vec<u8>>>(4)?,
                    ))
                },
            )
            .optional()
            .map_err(|error| map_sqlite(error, "upload finalization lookup failed"))?
            .ok_or_else(|| Error::new(ChurStatus::NotFound, "upload is absent"))?;
        let expected = from_sqlite(expected, "stored expected length is invalid")?;
        let received = from_sqlite(received, "stored received length is invalid")?;
        if complete {
            ensure!(
                stored_sha256.as_deref() == Some(expected_sha256.as_slice()),
                Conflict,
                "completed object checksum differs"
            );
            return Ok(UploadProgress {
                received,
                expected,
                complete: true,
            });
        }
        ensure!(
            received == expected,
            ObjectIncomplete,
            "upload is incomplete"
        );
        let store_id = Id::from_slice(&store_id)?;
        let partial = self.partial_path(vault_id, transfer_id);
        let object = self.object_path(vault_id, store_id);
        let source = if partial.exists() { &partial } else { &object };
        ensure!(
            source.exists(),
            StorageUnavailable,
            "completed upload bytes are absent"
        );
        ensure!(
            file_sha256(source)? == expected_sha256,
            AuthenticationFailed,
            "complete object transport checksum did not verify"
        );
        if partial.exists() {
            let parent = object.parent().ok_or_else(|| {
                Error::new(ChurStatus::InternalFailure, "object path has no parent")
            })?;
            fs::create_dir_all(parent).map_err(|_| {
                Error::new(
                    ChurStatus::StorageUnavailable,
                    "object directory creation failed",
                )
            })?;
            ensure!(
                !object.exists(),
                Conflict,
                "immutable object already exists"
            );
            fs::rename(&partial, &object).map_err(|_| {
                Error::new(ChurStatus::StorageUnavailable, "object publication failed")
            })?;
        }
        self.db
            .execute(
                "UPDATE object_transfers SET complete = 1, object_sha256 = ?3
                 WHERE vault_id = ?1 AND transfer_id = ?2",
                params![
                    vault_id.as_bytes().as_slice(),
                    transfer_id.as_bytes().as_slice(),
                    expected_sha256.as_slice(),
                ],
            )
            .map_err(|error| map_sqlite(error, "object publication record failed"))?;
        Ok(UploadProgress {
            received,
            expected,
            complete: true,
        })
    }

    /// Reads one bounded range from a complete opaque object.
    pub fn read_object(
        &self,
        vault_id: Id,
        store_id: Id,
        offset: u64,
        max_length: u64,
    ) -> Result<Vec<u8>> {
        ensure!(
            max_length != 0 && max_length <= DOWNLOAD_RANGE_MAX,
            ResourceLimitExceeded,
            "download range limit is invalid"
        );
        let expected: Option<i64> = self
            .db
            .query_row(
                "SELECT expected_length FROM object_transfers
                 WHERE vault_id = ?1 AND store_id = ?2 AND complete = 1",
                params![
                    vault_id.as_bytes().as_slice(),
                    store_id.as_bytes().as_slice()
                ],
                |row| row.get(0),
            )
            .optional()
            .map_err(|error| map_sqlite(error, "object lookup failed"))?;
        let expected = from_sqlite(
            expected.ok_or_else(|| {
                Error::new(ChurStatus::ObjectIncomplete, "object is not complete")
            })?,
            "stored object length is invalid",
        )?;
        ensure!(
            offset <= expected,
            InvalidInput,
            "download offset exceeds object length"
        );
        let length = max_length.min(expected - offset);
        let mut file = File::open(self.object_path(vault_id, store_id))
            .map_err(|_| Error::new(ChurStatus::StorageUnavailable, "object bytes are absent"))?;
        file.seek(SeekFrom::Start(offset))
            .map_err(|_| Error::new(ChurStatus::IoFailure, "object range seek failed"))?;
        let mut bytes = vec![
            0;
            usize::try_from(length).map_err(|_| {
                Error::new(
                    ChurStatus::ResourceLimitExceeded,
                    "download range is too large",
                )
            })?
        ];
        file.read_exact(&mut bytes)
            .map_err(|_| Error::new(ChurStatus::StorageUnavailable, "object range is truncated"))?;
        Ok(bytes)
    }

    fn transfer_state(&self, vault_id: Id, transfer_id: Id) -> Result<(u64, u64, bool)> {
        let state = self
            .db
            .query_row(
                "SELECT expected_length, received_length, complete FROM object_transfers
                 WHERE vault_id = ?1 AND transfer_id = ?2",
                params![
                    vault_id.as_bytes().as_slice(),
                    transfer_id.as_bytes().as_slice()
                ],
                |row| {
                    Ok((
                        row.get::<_, i64>(0)?,
                        row.get::<_, i64>(1)?,
                        row.get::<_, bool>(2)?,
                    ))
                },
            )
            .optional()
            .map_err(|error| map_sqlite(error, "upload state lookup failed"))?
            .ok_or_else(|| Error::new(ChurStatus::NotFound, "upload is absent"))?;
        Ok((
            from_sqlite(state.0, "stored expected length is invalid")?,
            from_sqlite(state.1, "stored received length is invalid")?,
            state.2,
        ))
    }

    fn create_empty_partial(&self, vault_id: Id, transfer_id: Id) -> Result<()> {
        let path = self.partial_path(vault_id, transfer_id);
        let parent = path
            .parent()
            .ok_or_else(|| Error::new(ChurStatus::InternalFailure, "upload path has no parent"))?;
        fs::create_dir_all(parent)
            .and_then(|()| {
                OpenOptions::new()
                    .create(true)
                    .truncate(true)
                    .write(true)
                    .open(path)
            })
            .and_then(|file| file.sync_all())
            .map_err(|_| {
                Error::new(
                    ChurStatus::StorageUnavailable,
                    "upload file creation failed",
                )
            })
    }

    fn partial_path(&self, vault_id: Id, transfer_id: Id) -> PathBuf {
        self.root
            .join("uploads")
            .join(vault_id.to_hex())
            .join(format!("{}.partial", transfer_id.to_hex()))
    }

    fn object_path(&self, vault_id: Id, store_id: Id) -> PathBuf {
        self.root
            .join("objects")
            .join(vault_id.to_hex())
            .join(format!("{}.object", store_id.to_hex()))
    }
}

fn progress(received: i64, expected: i64, complete: bool) -> Result<UploadProgress> {
    Ok(UploadProgress {
        received: from_sqlite(received, "stored received length is invalid")?,
        expected: from_sqlite(expected, "stored expected length is invalid")?,
        complete,
    })
}

fn to_sqlite(value: u64, context: &'static str) -> Result<i64> {
    i64::try_from(value).map_err(|_| Error::new(ChurStatus::ResourceLimitExceeded, context))
}

fn from_sqlite(value: i64, context: &'static str) -> Result<u64> {
    u64::try_from(value).map_err(|_| Error::new(ChurStatus::InternalFailure, context))
}

fn file_sha256(path: &Path) -> Result<[u8; 32]> {
    let mut file = File::open(path).map_err(|_| {
        Error::new(
            ChurStatus::StorageUnavailable,
            "object checksum source is absent",
        )
    })?;
    let mut hasher = Sha256::new();
    let mut buffer = [0; 64 * 1024];
    loop {
        let read = file
            .read(&mut buffer)
            .map_err(|_| Error::new(ChurStatus::IoFailure, "object checksum read failed"))?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
    }
    Ok(hasher.finalize().into())
}

fn map_sqlite(error: rusqlite::Error, context: &'static str) -> Error {
    let status = match &error {
        rusqlite::Error::SqliteFailure(failure, _) => match failure.code {
            rusqlite::ErrorCode::ConstraintViolation => ChurStatus::Conflict,
            rusqlite::ErrorCode::DatabaseBusy | rusqlite::ErrorCode::DatabaseLocked => {
                ChurStatus::Conflict
            }
            rusqlite::ErrorCode::DiskFull
            | rusqlite::ErrorCode::ReadOnly
            | rusqlite::ErrorCode::CannotOpen => ChurStatus::StorageUnavailable,
            rusqlite::ErrorCode::DatabaseCorrupt | rusqlite::ErrorCode::NotADatabase => {
                ChurStatus::CatalogCorrupt
            }
            _ => ChurStatus::InternalFailure,
        },
        _ => ChurStatus::InternalFailure,
    };
    Error::new(status, context)
}

#[cfg(test)]
mod tests {
    #![allow(clippy::expect_used)]

    use std::sync::atomic::{AtomicU64, Ordering};

    use super::*;

    pub(crate) struct TestRoot(pub(crate) PathBuf);

    impl TestRoot {
        pub(crate) fn new() -> Self {
            static NEXT: AtomicU64 = AtomicU64::new(0);
            let path = std::env::temp_dir().join(format!(
                "chur-sync-server-{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
            fs::create_dir(&path).expect("test root");
            Self(path)
        }
    }

    impl Drop for TestRoot {
        fn drop(&mut self) {
            fs::remove_dir_all(&self.0).expect("remove test root");
        }
    }

    fn id(byte: u8) -> Id {
        Id::new([byte; 16]).expect("id")
    }

    fn sha256(bytes: &[u8]) -> [u8; 32] {
        Sha256::digest(bytes).into()
    }

    #[test]
    fn interrupted_upload_resumes_and_only_complete_ciphertext_is_readable() {
        let root = TestRoot::new();
        let mut server = ReferenceServer::open(&root.0, 8, 12).expect("open");
        let vault = id(1);
        let transfer = id(2);
        let object = id(3);
        let bytes = b"cipher";

        server
            .begin_upload(vault, transfer, object, bytes.len() as u64)
            .expect("begin");
        fs::remove_file(server.partial_path(vault, transfer)).expect("simulate interrupted begin");
        server
            .begin_upload(vault, transfer, object, bytes.len() as u64)
            .expect("repair interrupted begin");
        assert_eq!(
            server
                .append_upload(vault, transfer, 0, &bytes[..3], sha256(&bytes[..3]))
                .expect("first range")
                .received,
            3
        );
        assert_eq!(
            server
                .append_upload(vault, transfer, 0, &bytes[..3], sha256(&bytes[..3]))
                .expect("idempotent replay")
                .received,
            3
        );
        assert_eq!(
            server
                .append_upload(vault, transfer, 3, &bytes[3..], [0; 32])
                .expect_err("wrong checksum")
                .status(),
            ChurStatus::AuthenticationFailed
        );
        assert_eq!(
            server
                .begin_upload(vault, id(4), id(5), 7)
                .expect_err("account quota")
                .status(),
            ChurStatus::ResourceLimitExceeded
        );
        assert_eq!(
            server
                .read_object(vault, object, 0, 8)
                .expect_err("incomplete object")
                .status(),
            ChurStatus::ObjectIncomplete
        );
        drop(server);

        let mut server = ReferenceServer::open(&root.0, 8, 12).expect("reopen");
        server
            .append_upload(vault, transfer, 3, &bytes[3..], sha256(&bytes[3..]))
            .expect("resume");
        assert_eq!(
            server
                .finish_upload(vault, transfer, [9; 32])
                .expect_err("wrong object checksum")
                .status(),
            ChurStatus::AuthenticationFailed
        );
        assert!(
            server
                .finish_upload(vault, transfer, sha256(bytes))
                .expect("finish")
                .complete
        );
        assert_eq!(
            server.read_object(vault, object, 0, 8).expect("read"),
            bytes
        );
        assert!(
            server
                .finish_upload(vault, transfer, sha256(bytes))
                .expect("idempotent finish")
                .complete
        );
    }
}
