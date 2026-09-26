//! Recoverable local deletion. Objects retain their keys and containers until
//! explicitly purged or their thirty-day retention period expires.

use chur_core::{Id, Result, ensure};
use chur_format::constants::ObjectState;
use rusqlite::params;

use crate::db::{CatalogDb, as_sqlite_integer, map_sqlite};
use crate::schema::bump_generation;
use crate::store;

/// Retention period for recoverable local deletion.
pub const RETENTION_MS: u64 = 30 * 24 * 60 * 60 * 1000;

/// Moves one selection to trash, or restores it, atomically.
pub fn set(db: &mut CatalogDb, ids: &[Id], restore: bool, now_ms: u64) -> Result<()> {
    ensure!(!ids.is_empty(), InvalidInput, "the selection is empty");
    let now = as_sqlite_integer(now_ms, "the time is out of range")?;
    let deadline = as_sqlite_integer(
        now_ms
            .checked_add(RETENTION_MS)
            .ok_or_else(|| chur_core::err!(InvalidInput, "the trash deadline is out of range"))?,
        "the trash deadline is out of range",
    )?;
    db.transaction(|tx| {
        for id in ids {
            let bytes = id.as_bytes().as_slice();
            if restore {
                let changed = tx.execute(
                    "UPDATE objects SET state = ?2 WHERE object_id = ?1 AND state = ?3
                       AND EXISTS (SELECT 1 FROM trash_entries WHERE object_id = ?1 AND expires_ms > ?4)",
                    params![bytes, i64::from(ObjectState::Active.value()),
                        i64::from(ObjectState::Trashed.value()), now],
                ).map_err(|error| map_sqlite(error, "the object could not be restored"))?;
                ensure!(changed == 1, NotFound, "no trashed object carries that id");
                tx.execute("DELETE FROM trash_entries WHERE object_id = ?1", [bytes])
                    .map_err(|error| map_sqlite(error, "the trash entry could not be removed"))?;
                store::reindex_search(tx, id)?;
            } else {
                let changed = tx.execute(
                    "UPDATE objects SET state = ?2 WHERE object_id = ?1 AND state = ?3",
                    params![bytes, i64::from(ObjectState::Trashed.value()),
                        i64::from(ObjectState::Active.value())],
                ).map_err(|error| map_sqlite(error, "the object could not enter trash"))?;
                ensure!(changed == 1, NotFound, "no active object carries that id");
                tx.execute(
                    "INSERT INTO trash_entries (object_id, deleted_ms, expires_ms) VALUES (?1, ?2, ?3)",
                    params![bytes, now, deadline],
                ).map_err(|error| map_sqlite(error, "the trash entry could not be written"))?;
                tx.execute("DELETE FROM object_search WHERE rowid = ?1", [store::row_key(id)])
                    .map_err(|error| map_sqlite(error, "the search row could not be removed"))?;
            }
        }
        bump_generation(tx)
    })
}

/// IDs awaiting final deletion, oldest first. `None` selects all entries.
pub fn pending(db: &CatalogDb, expired_at_ms: Option<u64>) -> Result<Vec<Id>> {
    let limit = expired_at_ms
        .map(|time| as_sqlite_integer(time, "the time is out of range"))
        .transpose()?;
    let mut statement = db
        .connection()
        .prepare(
            "SELECT object_id FROM trash_entries WHERE (?1 IS NULL OR expires_ms <= ?1)
         ORDER BY expires_ms, object_id",
        )
        .map_err(|error| map_sqlite(error, "the trash query could not be prepared"))?;
    let rows = statement
        .query_map([limit], |row| row.get::<_, Vec<u8>>(0))
        .map_err(|error| map_sqlite(error, "the trash query could not be read"))?;
    rows.map(|row| {
        let bytes = row.map_err(|error| map_sqlite(error, "the trash id could not be read"))?;
        crate::row::id(&bytes, "the trash id is malformed")
    })
    .collect()
}
