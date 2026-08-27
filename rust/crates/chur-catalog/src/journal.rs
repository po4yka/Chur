//! The import journal.
//!
//! `docs/format/CATALOG_SCHEMA_V1.md` §11 puts the journal in the catalog
//! rather than in a file of its own, so a chunk-index reservation and the
//! catalog state that activates the object share one transaction domain and
//! cannot disagree after a crash.
//!
//! `docs/format/OBJECT_CONTAINER_V1.md` §14.2 fixes the ordering this module
//! exists to enforce. For each chunk index `i`, in this order and no other:
//!
//! 1. set `reserved_index` to `i` in the journal record;
//! 2. make that journal update durable;
//! 3. write chunk record `i` to the container;
//! 4. fsync the container.
//!
//! Steps 1 and 2 are [`reserve_chunk`], and it returns only after the catalog
//! transaction has committed under `synchronous = FULL`. Steps 3 and 4 belong
//! to the writer, which is why this module hands back the offset rather than
//! writing anything itself.

use chur_core::{Id, Result, bail, ensure, limits::catalog as limits, limits::container};
use chur_format::constants::StreamKind;
use rusqlite::params;

use crate::db::{CatalogDb, as_sqlite_integer, from_sqlite_integer, map_sqlite};

/// The stage of an import transaction, `docs/format/CATALOG_SCHEMA_V1.md` §11.
///
/// The stage only advances. A resume never moves it back, because a lower stage
/// would claim a durability the container has already exceeded.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
#[repr(u8)]
pub enum Stage {
    /// The row exists; the preamble and manifest are not yet durable.
    Opening = 0x01,
    /// The manifest is durable and chunk records are being written.
    Incoming = 0x02,
    /// Every record is written and fsynced; the rename and the activation have
    /// not both completed.
    Committing = 0x03,
    /// The transaction is abandoned under §14.4 and cleanup may run.
    Dead = 0x04,
}

impl Stage {
    /// The stored discriminant.
    #[must_use]
    pub const fn value(self) -> u8 {
        self as u8
    }

    /// The stage a discriminant denotes, or `None` when it is unallocated.
    #[must_use]
    pub const fn from_value(value: u8) -> Option<Self> {
        match value {
            0x01 => Some(Stage::Opening),
            0x02 => Some(Stage::Incoming),
            0x03 => Some(Stage::Committing),
            0x04 => Some(Stage::Dead),
            _ => None,
        }
    }
}

/// One import transaction, `docs/format/OBJECT_CONTAINER_V1.md` §14.1.
///
/// The envelope, the nonce prefix, and the chunk size are written when the
/// transaction opens and are never rewritten; a resume reads them and never
/// regenerates them. Only `reserved_index` and `stage` change while it runs.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ImportTransaction {
    /// The transaction identifier.
    pub transaction_id: Id,
    /// The opaque store identifier of the temporary container.
    pub temp_path_id: Id,
    /// The object being imported.
    pub object_id: Id,
    /// The stream being written.
    pub stream_id: Id,
    /// The stream kind.
    pub stream_kind: StreamKind,
    /// The stream revision.
    pub stream_revision: u32,
    /// The generation of the object-key envelope this transaction holds.
    pub envelope_generation: u64,
    /// The encoded envelope, absent once §14.4 step 1 has destroyed it.
    pub envelope_body: Option<Vec<u8>>,
    /// The 16-byte nonce prefix every record of this container is written under.
    pub nonce_prefix: [u8; 16],
    /// The chunk size.
    pub chunk_size: u32,
    /// The length of the written manifest record.
    pub manifest_length: u32,
    /// The highest chunk index ever reserved, absent when none has been.
    pub reserved_index: Option<u64>,
    /// The source length when the adapter reported one, §3 of the media pipeline.
    pub expected_length: Option<u64>,
    /// Whether the source can be re-read from an offset.
    pub source_seekable: bool,
    /// The stage.
    pub stage: Stage,
    /// When the transaction opened.
    pub opened_ms: u64,
}

impl ImportTransaction {
    /// The journaled ciphertext length of §14.1.
    ///
    /// It is both the offset at which the reserved record begins and the end of
    /// the last record the journal proves durable, which is why the
    /// specification stores `reserved_index` and derives this rather than
    /// storing the length twice.
    #[must_use]
    pub fn journaled_ciphertext_length(&self) -> Option<u64> {
        let index = self.reserved_index?;
        let record = u64::from(container::CHUNK_HEADER_LEN as u32)
            + u64::from(self.chunk_size)
            + chur_core::limits::TAG_LEN as u64;
        Some(
            container::PREAMBLE_LEN as u64
                + u64::from(self.manifest_length)
                + index.saturating_mul(record),
        )
    }

    fn check(&self) -> Result<()> {
        ensure!(
            self.chunk_size >= container::CHUNK_SIZE_MIN
                && self.chunk_size <= container::CHUNK_SIZE_MAX
                && self
                    .chunk_size
                    .is_multiple_of(container::CHUNK_SIZE_MULTIPLE),
            ResourceLimitExceeded,
            "the chunk size is outside container §16"
        );
        ensure!(
            self.manifest_length >= container::MANIFEST_RECORD_MIN
                && self.manifest_length <= container::MANIFEST_RECORD_MAX,
            ResourceLimitExceeded,
            "the manifest length is outside container §3"
        );
        ensure!(
            self.stream_revision >= 1,
            InvalidInput,
            "a stream revision is numbered from one"
        );
        Ok(())
    }
}

/// Opens an import transaction, §14.1.
///
/// The row is durable before the caller writes a byte, so a crash between this
/// call and the first record leaves a journal record whose temporary container
/// is absent, which §14.4 classifies as dead and reconciliation cleans up.
pub fn open(db: &mut CatalogDb, transaction: &ImportTransaction) -> Result<()> {
    transaction.check()?;
    ensure!(
        transaction.stage == Stage::Opening,
        InvalidInput,
        "an import transaction opens at the OPENING stage"
    );
    ensure!(
        transaction.reserved_index.is_none(),
        InvalidInput,
        "an import transaction opens with no reserved index"
    );
    let envelope = transaction.envelope_body.as_deref().ok_or_else(|| {
        chur_core::err!(
            InvalidInput,
            "an import transaction opens with its envelope"
        )
    })?;
    db.transaction(|tx| {
        let live: i64 = tx
            .query_row(
                "SELECT count(*) FROM import_transactions WHERE stage <> ?1",
                [i64::from(Stage::Dead.value())],
                |row| row.get(0),
            )
            .map_err(|error| map_sqlite(error, "the journal could not be counted"))?;
        ensure!(
            live < i64::from(limits::IMPORT_TRANSACTIONS_MAX),
            ResourceLimitExceeded,
            "the vault holds the §21 maximum of concurrent imports"
        );
        tx.execute(
            "INSERT INTO import_transactions (
                 transaction_id, temp_path_id, object_id, stream_id, stream_kind,
                 stream_revision, envelope_generation, envelope_body, nonce_prefix,
                 chunk_size, manifest_length, reserved_index, expected_length,
                 source_seekable, stage, opened_ms
             ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, NULL, ?12, ?13, ?14, ?15)",
            params![
                transaction.transaction_id.as_bytes().as_slice(),
                transaction.temp_path_id.as_bytes().as_slice(),
                transaction.object_id.as_bytes().as_slice(),
                transaction.stream_id.as_bytes().as_slice(),
                i64::from(transaction.stream_kind.value()),
                i64::from(transaction.stream_revision),
                as_sqlite_integer(
                    transaction.envelope_generation,
                    "the envelope generation is too large"
                )?,
                envelope,
                transaction.nonce_prefix.as_slice(),
                i64::from(transaction.chunk_size),
                i64::from(transaction.manifest_length),
                transaction
                    .expected_length
                    .map(|value| as_sqlite_integer(value, "the source length is out of range"))
                    .transpose()?,
                i64::from(transaction.source_seekable),
                i64::from(transaction.stage.value()),
                as_sqlite_integer(transaction.opened_ms, "the open time is out of range")?,
            ],
        )
        .map_err(|error| map_sqlite(error, "the journal record could not be written"))?;
        Ok(())
    })
}

/// Records that the preamble and the manifest record are durable, §14.2.
///
/// The specification requires this before index 0 is reserved, so
/// [`reserve_chunk`] refuses a transaction still at [`Stage::Opening`].
pub fn mark_manifest_durable(db: &mut CatalogDb, transaction_id: &Id) -> Result<()> {
    advance(db, transaction_id, Stage::Incoming)
}

/// Records that every record is written and fsynced, §14.
pub fn mark_committing(db: &mut CatalogDb, transaction_id: &Id) -> Result<()> {
    advance(db, transaction_id, Stage::Committing)
}

/// Records the one durable stage update that makes a transaction dead, §14.4.
pub fn mark_dead(db: &mut CatalogDb, transaction_id: &Id) -> Result<()> {
    advance(db, transaction_id, Stage::Dead)
}

fn advance(db: &mut CatalogDb, transaction_id: &Id, stage: Stage) -> Result<()> {
    db.transaction(|tx| {
        let changed = tx
            .execute(
                "UPDATE import_transactions SET stage = ?2
                  WHERE transaction_id = ?1 AND stage <= ?2",
                params![
                    transaction_id.as_bytes().as_slice(),
                    i64::from(stage.value())
                ],
            )
            .map_err(|error| map_sqlite(error, "the journal stage could not advance"))?;
        ensure!(
            changed == 1,
            Conflict,
            "the journal stage only advances, and no record is at a lower stage"
        );
        Ok(())
    })
}

/// Reserves chunk index `index`, §14.2 steps 1 and 2.
///
/// It returns the offset at which the reserved record begins, which is the
/// journaled ciphertext length the caller then writes at. The call returns only
/// after the catalog transaction has committed, and the connection runs under
/// `synchronous = FULL`, so the reservation is durable against power loss
/// before any byte is encrypted under that index.
///
/// A reserved index is never encrypted a second time and `reserved_index` never
/// decreases, so the only accepted values are `0` when none has been reserved
/// and `reserved_index + 1` afterwards.
pub fn reserve_chunk(db: &mut CatalogDb, transaction_id: &Id, index: u64) -> Result<u64> {
    let record = read(db, transaction_id)?;
    ensure!(
        record.stage == Stage::Incoming,
        Conflict,
        "a chunk index is reserved only while the manifest is durable"
    );
    let expected = record.reserved_index.map_or(0, |current| current + 1);
    ensure!(
        index == expected,
        InvalidInput,
        "container §8 forbids a gap and §14.2 forbids a repeat in the index sequence"
    );
    ensure!(
        index < container::CHUNK_COUNT_MAX,
        ResourceLimitExceeded,
        "the chunk index exceeds the container §16 bound"
    );
    db.transaction(|tx| {
        let changed = tx
            .execute(
                "UPDATE import_transactions SET reserved_index = ?2
                  WHERE transaction_id = ?1
                    AND (reserved_index IS NULL OR reserved_index = ?2 - 1)",
                params![
                    transaction_id.as_bytes().as_slice(),
                    as_sqlite_integer(index, "the chunk index is out of range")?
                ],
            )
            .map_err(|error| map_sqlite(error, "the chunk index could not be reserved"))?;
        ensure!(
            changed == 1,
            Conflict,
            "another writer reserved an index on this transaction"
        );
        Ok(())
    })?;
    let mut reserved = record;
    reserved.reserved_index = Some(index);
    reserved
        .journaled_ciphertext_length()
        .ok_or_else(|| chur_core::err!(InternalFailure, "a reserved index has no offset"))
}

/// Destroys the object-key envelope of a dead transaction, §14.4 step 1.
///
/// This is the erasure moment for an abandoned import: once it commits the
/// `ContentKey` is unrecoverable and every byte written into the temporary
/// container is ciphertext no reachable key opens. Steps 2 and 3 carry no
/// security property, which is why they may run later or after a crash.
pub fn destroy_envelope(db: &mut CatalogDb, transaction_id: &Id) -> Result<()> {
    db.transaction(|tx| {
        let changed = tx
            .execute(
                "UPDATE import_transactions SET envelope_body = NULL
                  WHERE transaction_id = ?1 AND stage = ?2",
                params![
                    transaction_id.as_bytes().as_slice(),
                    i64::from(Stage::Dead.value())
                ],
            )
            .map_err(|error| map_sqlite(error, "the import envelope could not be destroyed"))?;
        ensure!(
            changed == 1,
            Conflict,
            "only a dead transaction destroys its envelope"
        );
        Ok(())
    })
}

/// Deletes a journal record, §14.4 step 3, and the success path's last step.
pub fn close(db: &mut CatalogDb, transaction_id: &Id) -> Result<()> {
    db.transaction(|tx| {
        tx.execute(
            "DELETE FROM import_transactions WHERE transaction_id = ?1",
            [transaction_id.as_bytes().as_slice()],
        )
        .map_err(|error| map_sqlite(error, "the journal record could not be deleted"))?;
        Ok(())
    })
}

/// Reads one journal record.
pub fn read(db: &CatalogDb, transaction_id: &Id) -> Result<ImportTransaction> {
    db.connection()
        .query_row(SELECT, [transaction_id.as_bytes().as_slice()], decode)
        .map_err(|error| map_sqlite(error, "the journal record could not be read"))?
}

/// Every journal record that is not dead, oldest first.
///
/// Reconciliation walks this at the first unlock of a session: §14.4 makes a
/// record whose temporary container is absent, and a temporary container with
/// no record, both dead.
pub fn live(db: &CatalogDb) -> Result<Vec<ImportTransaction>> {
    collect(db, "AND stage <> 4 ORDER BY opened_ms, transaction_id")
}

/// Every dead journal record, so cleanup can finish an interrupted abandonment.
pub fn dead(db: &CatalogDb) -> Result<Vec<ImportTransaction>> {
    collect(db, "AND stage = 4 ORDER BY opened_ms, transaction_id")
}

fn collect(db: &CatalogDb, tail: &str) -> Result<Vec<ImportTransaction>> {
    let connection = db.connection();
    let sql = format!("{SELECT_ALL} {tail}");
    let mut statement = connection
        .prepare(&sql)
        .map_err(|error| map_sqlite(error, "the journal query could not be prepared"))?;
    let rows = statement
        .query_map([], decode)
        .map_err(|error| map_sqlite(error, "the journal could not be read"))?;
    let mut records = Vec::new();
    for row in rows {
        records.push(row.map_err(|error| map_sqlite(error, "a journal row could not be read"))??);
    }
    Ok(records)
}

/// The column list both statements below read, in the order [`decode`] expects.
macro_rules! journal_columns {
    () => {
        "SELECT transaction_id, temp_path_id, object_id, stream_id, stream_kind, \
         stream_revision, envelope_generation, envelope_body, nonce_prefix, chunk_size, \
         manifest_length, reserved_index, expected_length, source_seekable, stage, opened_ms \
         FROM import_transactions"
    };
}

const SELECT: &str = concat!(journal_columns!(), " WHERE transaction_id = ?1");

const SELECT_ALL: &str = concat!(journal_columns!(), " WHERE 1 = 1");

fn decode(row: &rusqlite::Row<'_>) -> rusqlite::Result<Result<ImportTransaction>> {
    let transaction_id: Vec<u8> = row.get(0)?;
    let temp_path_id: Vec<u8> = row.get(1)?;
    let object_id: Vec<u8> = row.get(2)?;
    let stream_id: Vec<u8> = row.get(3)?;
    let stream_kind: i64 = row.get(4)?;
    let stream_revision: i64 = row.get(5)?;
    let envelope_generation: i64 = row.get(6)?;
    let envelope_body: Option<Vec<u8>> = row.get(7)?;
    let nonce_prefix: Vec<u8> = row.get(8)?;
    let chunk_size: i64 = row.get(9)?;
    let manifest_length: i64 = row.get(10)?;
    let reserved_index: Option<i64> = row.get(11)?;
    let expected_length: Option<i64> = row.get(12)?;
    let source_seekable: i64 = row.get(13)?;
    let stage: i64 = row.get(14)?;
    let opened_ms: i64 = row.get(15)?;
    Ok((|| {
        let prefix: [u8; 16] = nonce_prefix.as_slice().try_into().map_err(|_| {
            chur_core::Error::new(
                chur_core::ChurStatus::CatalogCorrupt,
                "the nonce prefix is malformed",
            )
        })?;
        let kind = u8::try_from(stream_kind)
            .ok()
            .and_then(StreamKind::from_value)
            .ok_or_else(|| {
                chur_core::Error::new(
                    chur_core::ChurStatus::CatalogCorrupt,
                    "the stream kind is unallocated",
                )
            })?;
        let stage = u8::try_from(stage)
            .ok()
            .and_then(Stage::from_value)
            .ok_or_else(|| {
                chur_core::Error::new(
                    chur_core::ChurStatus::CatalogCorrupt,
                    "the journal stage is unallocated",
                )
            })?;
        Ok(ImportTransaction {
            transaction_id: crate::row::id(&transaction_id, "the transaction id is malformed")?,
            temp_path_id: crate::row::id(&temp_path_id, "the temporary path id is malformed")?,
            object_id: crate::row::id(&object_id, "the object id is malformed")?,
            stream_id: crate::row::id(&stream_id, "the stream id is malformed")?,
            stream_kind: kind,
            stream_revision: u32::try_from(stream_revision).map_err(|_| {
                chur_core::Error::new(
                    chur_core::ChurStatus::CatalogCorrupt,
                    "the stream revision is out of range",
                )
            })?,
            envelope_generation: from_sqlite_integer(
                envelope_generation,
                "the envelope generation is negative",
            )?,
            envelope_body,
            nonce_prefix: prefix,
            chunk_size: u32::try_from(chunk_size).map_err(|_| {
                chur_core::Error::new(
                    chur_core::ChurStatus::CatalogCorrupt,
                    "the chunk size is out of range",
                )
            })?,
            manifest_length: u32::try_from(manifest_length).map_err(|_| {
                chur_core::Error::new(
                    chur_core::ChurStatus::CatalogCorrupt,
                    "the manifest length is out of range",
                )
            })?,
            reserved_index: reserved_index
                .map(|value| from_sqlite_integer(value, "the reserved index is negative"))
                .transpose()?,
            expected_length: expected_length
                .map(|value| from_sqlite_integer(value, "the source length is negative"))
                .transpose()?,
            source_seekable: crate::row::flag(
                source_seekable,
                "the seekable flag is not a boolean",
            )?,
            stage,
            opened_ms: from_sqlite_integer(opened_ms, "the open time is negative")?,
        })
    })())
}

/// Rejects a resume the specification declares dead, §14.3.
///
/// The caller supplies what it found in the container at
/// [`ImportTransaction::journaled_ciphertext_length`]. If the record there is
/// absent, short, or unauthentic, its index has already consumed its nonce and
/// container §8 forbids a gap, so the transaction is dead and the container is
/// discarded rather than rewritten.
pub fn resume_decision(record: &ImportTransaction, reserved_record_valid: bool) -> Resume {
    if record.stage == Stage::Dead {
        return Resume::Dead;
    }
    match record.reserved_index {
        None => Resume::RestartFromManifest,
        Some(index) if reserved_record_valid => Resume::ContinueAfter(index),
        Some(_) => Resume::Dead,
    }
}

/// What a resumed transaction does next, §14.3.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Resume {
    /// No index was reserved, so the container is truncated to zero and the
    /// preamble and manifest are written again under a fresh manifest nonce.
    /// The object key and prefix are kept and the import starts at index 0.
    RestartFromManifest,
    /// The record at the journaled length parsed and authenticated, so the
    /// container is truncated to its end and index `n + 1` is reserved next.
    ContinueAfter(u64),
    /// The transaction is dead under §14.4 and the container is discarded.
    Dead,
}

/// A journal record whose temporary container is absent is dead, §14.4.
pub fn is_dead_without_container(record: &ImportTransaction, container_present: bool) -> bool {
    !container_present || record.stage == Stage::Dead
}

/// Rejects a stage value the catalog does not allocate.
pub fn stage_of(value: u8) -> Result<Stage> {
    let Some(stage) = Stage::from_value(value) else {
        bail!(CatalogCorrupt, "the journal stage is unallocated");
    };
    Ok(stage)
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::panic, clippy::expect_used)]

    use super::*;
    use crate::db::{CatalogDb, CatalogKey, CatalogLocation};
    use crate::schema::open_at_current_version;
    use chur_core::ChurStatus;
    use chur_crypto::{Key, random};

    fn catalog() -> CatalogDb {
        let root: Key = random::secret::<32>().expect("root");
        let vault_id = random::id().expect("id");
        let key = CatalogKey::derive(&root, &vault_id).expect("key");
        let mut db = CatalogDb::open(&CatalogLocation::Memory, &key).expect("open");
        open_at_current_version(&mut db, 1).expect("schema");
        db
    }

    fn record() -> ImportTransaction {
        ImportTransaction {
            transaction_id: random::id().expect("id"),
            temp_path_id: random::id().expect("id"),
            object_id: random::id().expect("id"),
            stream_id: random::id().expect("id"),
            stream_kind: StreamKind::Original,
            stream_revision: 1,
            envelope_generation: 1,
            envelope_body: Some(vec![3u8; 142]),
            nonce_prefix: [5u8; 16],
            chunk_size: 262_144,
            manifest_length: 117,
            reserved_index: None,
            expected_length: Some(1_000_000),
            source_seekable: true,
            stage: Stage::Opening,
            opened_ms: 1_700_000_000_000,
        }
    }

    fn rejection<T>(outcome: Result<T>) -> ChurStatus {
        let Err(error) = outcome else {
            panic!("the journal accepted something the specification forbids");
        };
        error.status()
    }

    #[test]
    fn a_record_round_trips() {
        let mut db = catalog();
        let written = record();
        open(&mut db, &written).expect("open");
        assert_eq!(read(&db, &written.transaction_id).expect("read"), written);
    }

    #[test]
    fn the_journaled_length_is_the_container_formula() {
        let mut written = record();
        assert_eq!(written.journaled_ciphertext_length(), None);
        written.reserved_index = Some(0);
        assert_eq!(
            written.journaled_ciphertext_length(),
            Some(0x1C + 117),
            "index 0 begins immediately after the manifest record"
        );
        written.reserved_index = Some(3);
        assert_eq!(
            written.journaled_ciphertext_length(),
            Some(0x1C + 117 + 3 * (20 + 262_144 + 16))
        );
    }

    #[test]
    fn an_index_is_reserved_only_after_the_manifest_is_durable() {
        let mut db = catalog();
        let written = record();
        open(&mut db, &written).expect("open");
        assert_eq!(
            rejection(reserve_chunk(&mut db, &written.transaction_id, 0)),
            ChurStatus::Conflict,
            "§14.2 writes and fsyncs the manifest before index 0 is reserved"
        );
        mark_manifest_durable(&mut db, &written.transaction_id).expect("durable");
        let offset = reserve_chunk(&mut db, &written.transaction_id, 0).expect("reserve");
        assert_eq!(offset, 0x1C + 117);
    }

    #[test]
    fn the_index_sequence_admits_no_gap_and_no_repeat() {
        let mut db = catalog();
        let written = record();
        open(&mut db, &written).expect("open");
        mark_manifest_durable(&mut db, &written.transaction_id).expect("durable");
        reserve_chunk(&mut db, &written.transaction_id, 0).expect("index 0");
        reserve_chunk(&mut db, &written.transaction_id, 1).expect("index 1");
        for wrong in [0u64, 1, 3, u64::MAX] {
            assert_eq!(
                rejection(reserve_chunk(&mut db, &written.transaction_id, wrong)),
                ChurStatus::InvalidInput,
                "index {wrong} was accepted after index 1"
            );
        }
        reserve_chunk(&mut db, &written.transaction_id, 2).expect("index 2");
        assert_eq!(
            read(&db, &written.transaction_id)
                .expect("read")
                .reserved_index,
            Some(2)
        );
    }

    #[test]
    fn the_reserved_index_never_decreases_across_a_reopen() {
        let mut db = catalog();
        let written = record();
        open(&mut db, &written).expect("open");
        mark_manifest_durable(&mut db, &written.transaction_id).expect("durable");
        for index in 0..5 {
            reserve_chunk(&mut db, &written.transaction_id, index).expect("reserve");
        }
        let resumed = read(&db, &written.transaction_id).expect("read");
        assert_eq!(resumed.reserved_index, Some(4));
        assert_eq!(
            resume_decision(&resumed, true),
            Resume::ContinueAfter(4),
            "a valid reserved record continues after it"
        );
    }

    #[test]
    fn a_stage_only_advances() {
        let mut db = catalog();
        let written = record();
        open(&mut db, &written).expect("open");
        mark_committing(&mut db, &written.transaction_id).expect("committing");
        assert_eq!(
            rejection(mark_manifest_durable(&mut db, &written.transaction_id)),
            ChurStatus::Conflict
        );
        assert_eq!(
            read(&db, &written.transaction_id).expect("read").stage,
            Stage::Committing
        );
    }

    #[test]
    fn a_resume_with_no_reserved_index_restarts_from_the_manifest() {
        let mut db = catalog();
        let written = record();
        open(&mut db, &written).expect("open");
        mark_manifest_durable(&mut db, &written.transaction_id).expect("durable");
        let resumed = read(&db, &written.transaction_id).expect("read");
        assert_eq!(
            resume_decision(&resumed, false),
            Resume::RestartFromManifest
        );
    }

    #[test]
    fn a_reserved_record_that_does_not_authenticate_kills_the_transaction() {
        let mut db = catalog();
        let written = record();
        open(&mut db, &written).expect("open");
        mark_manifest_durable(&mut db, &written.transaction_id).expect("durable");
        reserve_chunk(&mut db, &written.transaction_id, 0).expect("reserve");
        let resumed = read(&db, &written.transaction_id).expect("read");
        // §14.3: the index has already consumed its nonce and §8 forbids a gap,
        // so the container is discarded rather than rewritten.
        assert_eq!(resume_decision(&resumed, false), Resume::Dead);
    }

    #[test]
    fn abandonment_destroys_the_envelope_before_the_record_is_deleted() {
        let mut db = catalog();
        let written = record();
        open(&mut db, &written).expect("open");
        assert_eq!(
            rejection(destroy_envelope(&mut db, &written.transaction_id)),
            ChurStatus::Conflict,
            "§14.4 records death as one durable stage update first"
        );
        mark_dead(&mut db, &written.transaction_id).expect("dead");
        destroy_envelope(&mut db, &written.transaction_id).expect("destroy");
        assert!(
            read(&db, &written.transaction_id)
                .expect("read")
                .envelope_body
                .is_none(),
            "the envelope survived step 1"
        );
        close(&mut db, &written.transaction_id).expect("close");
        assert_eq!(
            rejection(read(&db, &written.transaction_id)),
            ChurStatus::NotFound
        );
    }

    #[test]
    fn closing_a_record_twice_is_not_a_failure() {
        let mut db = catalog();
        let written = record();
        open(&mut db, &written).expect("open");
        close(&mut db, &written.transaction_id).expect("close");
        close(&mut db, &written.transaction_id).expect("close again");
    }

    #[test]
    fn a_record_whose_container_is_absent_is_dead() {
        let record = record();
        assert!(is_dead_without_container(&record, false));
        assert!(!is_dead_without_container(&record, true));
        let mut dead = record;
        dead.stage = Stage::Dead;
        assert!(is_dead_without_container(&dead, true));
    }

    #[test]
    fn live_and_dead_records_are_listed_separately() {
        let mut db = catalog();
        let first = record();
        let mut second = record();
        second.opened_ms = first.opened_ms + 1;
        open(&mut db, &first).expect("open");
        open(&mut db, &second).expect("open");
        mark_dead(&mut db, &second.transaction_id).expect("dead");
        let live = live(&db).expect("live");
        assert_eq!(live.len(), 1);
        assert_eq!(live[0].transaction_id, first.transaction_id);
        let dead = dead(&db).expect("dead");
        assert_eq!(dead.len(), 1);
        assert_eq!(dead[0].transaction_id, second.transaction_id);
    }

    #[test]
    fn the_concurrent_import_bound_of_section_21_is_enforced() {
        let mut db = catalog();
        for index in 0..128u64 {
            let mut written = record();
            written.opened_ms = index;
            open(&mut db, &written).expect("an import inside the bound");
        }
        assert_eq!(
            rejection(open(&mut db, &record())),
            ChurStatus::ResourceLimitExceeded
        );
    }

    #[test]
    fn a_dead_record_does_not_count_toward_the_concurrent_bound() {
        let mut db = catalog();
        let mut ids = Vec::new();
        for index in 0..128u64 {
            let mut written = record();
            written.opened_ms = index;
            ids.push(written.transaction_id);
            open(&mut db, &written).expect("open");
        }
        mark_dead(&mut db, &ids[0]).expect("dead");
        open(&mut db, &record()).expect("a slot freed by an abandoned import");
    }

    #[test]
    fn an_out_of_range_chunk_size_or_manifest_length_is_refused() {
        let mut db = catalog();
        let mut small = record();
        small.chunk_size = 4_096;
        assert_eq!(
            rejection(open(&mut db, &small)),
            ChurStatus::ResourceLimitExceeded
        );
        let mut ragged = record();
        ragged.chunk_size = 262_145;
        assert_eq!(
            rejection(open(&mut db, &ragged)),
            ChurStatus::ResourceLimitExceeded
        );
        let mut short = record();
        short.manifest_length = 39;
        assert_eq!(
            rejection(open(&mut db, &short)),
            ChurStatus::ResourceLimitExceeded
        );
    }

    #[test]
    fn an_import_opens_only_at_the_opening_stage_with_its_envelope() {
        let mut db = catalog();
        let mut resumed = record();
        resumed.stage = Stage::Incoming;
        assert_eq!(rejection(open(&mut db, &resumed)), ChurStatus::InvalidInput);
        let mut keyless = record();
        keyless.envelope_body = None;
        assert_eq!(rejection(open(&mut db, &keyless)), ChurStatus::InvalidInput);
        let mut ahead = record();
        ahead.reserved_index = Some(0);
        assert_eq!(rejection(open(&mut db, &ahead)), ChurStatus::InvalidInput);
    }
}
