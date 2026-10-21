//! Local resume positions in the encrypted catalog; never a sync or preference record.

use chur_core::{Id, Result, ensure};
use chur_format::constants::{MediaClass, ObjectState, StreamKind};
use rusqlite::{Connection, OptionalExtension, params};

use crate::db::{CatalogDb, as_sqlite_integer, from_sqlite_integer, map_sqlite};
use crate::schema::bump_generation;

/// The existing four-hour video bound, in milliseconds.
pub const MAX_DURATION_MS: u64 = 14_400_000;

fn source(connection: &Connection, object: &Id) -> Result<(i64, i64, i64)> {
    let result = connection.query_row(
        "SELECT o.object_generation, s.stream_revision, o.duration_ms
         FROM objects o JOIN object_streams s ON s.stream_id = o.primary_stream_id AND s.object_id = o.object_id
         WHERE o.object_id = ?1 AND o.state = ?2 AND o.media_kind = ?3 AND s.stream_kind = ?4",
        params![object.as_bytes().as_slice(), ObjectState::Active.value(), MediaClass::Video.value(), StreamKind::Original.value()],
        |row| Ok((row.get::<_, i64>(0)?, row.get::<_, i64>(1)?, row.get::<_, i64>(2)?)),
    ).optional().map_err(|error| map_sqlite(error, "the playback source could not be read"))?
        .ok_or_else(|| chur_core::err!(NotFound, "the active video source is absent"))?;
    ensure!(
        result.0 > 0 && result.1 > 0 && result.2 > 0 && result.2 <= MAX_DURATION_MS as i64,
        InvalidInput,
        "the video has no bounded resume duration"
    );
    Ok(result)
}

/// Returns zero for an absent or stale position. The original's revision owns the position.
pub fn get(db: &CatalogDb, object: &Id) -> Result<u64> {
    let (generation, revision, duration) = source(db.connection(), object)?;
    let value = db
        .connection()
        .query_row(
            "SELECT position_ms FROM playback_positions
         WHERE object_id = ?1 AND object_generation = ?2 AND source_content_revision = ?3
           AND duration_ms = ?4 AND position_ms BETWEEN 0 AND duration_ms",
            params![object.as_bytes().as_slice(), generation, revision, duration],
            |row| row.get::<_, i64>(0),
        )
        .optional()
        .map_err(|error| map_sqlite(error, "the playback position could not be read"))?;
    value.map_or(Ok(0), |value| {
        from_sqlite_integer(value, "the playback position is invalid")
    })
}

/// Validates one observation and writes it atomically. Native duration rounding need not match exactly.
pub fn put(db: &mut CatalogDb, object: &Id, position_ms: u64, duration_ms: u64) -> Result<()> {
    ensure!(
        duration_ms > 0 && duration_ms <= MAX_DURATION_MS && position_ms <= duration_ms,
        InvalidInput,
        "the playback position exceeds its duration"
    );
    let position = as_sqlite_integer(position_ms, "the playback position is too large")?;
    db.transaction(|tx| {
        let (generation, revision, canonical_duration) = source(tx, object)?;
        ensure!(position <= canonical_duration, InvalidInput, "the position exceeds the original video duration");
        let changed = if position == 0 {
            tx.execute("DELETE FROM playback_positions WHERE object_id = ?1", [object.as_bytes().as_slice()])
        } else {
            tx.execute(
                "INSERT INTO playback_positions (object_id, object_generation, source_content_revision, position_ms, duration_ms)
                 VALUES (?1, ?2, ?3, ?4, ?5) ON CONFLICT(object_id) DO UPDATE SET
                   object_generation = excluded.object_generation, source_content_revision = excluded.source_content_revision,
                   position_ms = excluded.position_ms, duration_ms = excluded.duration_ms
                 WHERE playback_positions.object_generation != excluded.object_generation
                    OR playback_positions.source_content_revision != excluded.source_content_revision
                    OR playback_positions.position_ms != excluded.position_ms OR playback_positions.duration_ms != excluded.duration_ms",
                params![object.as_bytes().as_slice(), generation, revision, position, canonical_duration],
            )
        }.map_err(|error| map_sqlite(error, "the playback position could not be saved"))?;
        if changed > 0 { bump_generation(tx)?; }
        Ok(())
    })
}

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "These tests must fail immediately when fixture setup or an asserted failure is unexpected."
)]
mod tests {
    use super::*;
    use crate::{
        db::{CatalogKey, CatalogLocation},
        schema,
    };
    use chur_core::ChurStatus;
    use chur_crypto::{Key, random};

    fn fixture(db: &mut CatalogDb) -> Id {
        schema::open_at_current_version(db, 1).expect("schema");
        let object = random::id().expect("object");
        db.connection()
            .execute(
                "INSERT INTO collections VALUES (zeroblob(16), 1, 1, 1, 1)",
                [],
            )
            .expect("collection");
        db.connection().execute(
            "INSERT INTO objects VALUES (?1,1,zeroblob(16),?1,?2,1,1,0,100,192,96,60000,0,?3,0,0,1,1)",
            params![object.as_bytes().as_slice(), MediaClass::Video.value(), ObjectState::Active.value()],
        ).expect("video");
        db.connection().execute(
            "INSERT INTO object_streams VALUES (?1,?1,?2,1,0,zeroblob(16),1,1,100,100,65536,1,zeroblob(32))",
            params![object.as_bytes().as_slice(), StreamKind::Original.value()],
        ).expect("original");
        object
    }

    #[test]
    fn positions_survive_encrypted_reopen_and_delete_with_the_object() {
        let root: Key = random::secret::<32>().expect("root");
        let key = CatalogKey::derive(&root, &random::id().expect("vault")).expect("key");
        let path = std::env::temp_dir().join(format!(
            "chur-playback-{}.db",
            random::id().expect("path").to_hex()
        ));
        let mut db = CatalogDb::open(&CatalogLocation::File(&path), &key).expect("open");
        let object = fixture(&mut db);
        assert_eq!(get(&db, &object).expect("empty"), 0);
        put(&mut db, &object, 59_999, 60_001).expect("rounded native duration");
        let generation = schema::generation(&db).expect("generation");
        put(&mut db, &object, 59_999, 60_000).expect("no change");
        assert_eq!(schema::generation(&db).expect("generation"), generation);
        db.checkpoint().expect("checkpoint");
        drop(db);
        assert_ne!(
            &std::fs::read(&path).expect("encrypted file")[..16],
            b"SQLite format 3\0"
        );
        let mut db = CatalogDb::open(&CatalogLocation::File(&path), &key).expect("reopen");
        assert_eq!(get(&db, &object).expect("raw near-end position"), 59_999);
        put(&mut db, &object, 0, 60_000).expect("clear");
        assert_eq!(get(&db, &object).expect("cleared"), 0);
        put(&mut db, &object, 500, 60_000).expect("save");
        db.connection()
            .execute("DELETE FROM object_streams", [])
            .expect("remove source");
        db.connection()
            .execute("DELETE FROM objects", [])
            .expect("delete object");
        let rows: i64 = db
            .connection()
            .query_row("SELECT count(*) FROM playback_positions", [], |row| {
                row.get(0)
            })
            .expect("cascade");
        assert_eq!(rows, 0);
        drop(db);
        std::fs::remove_file(path).expect("remove encrypted fixture");
    }

    #[test]
    fn bounds_and_source_revisions_fail_closed() {
        let root: Key = random::secret::<32>().expect("root");
        let key = CatalogKey::derive(&root, &random::id().expect("vault")).expect("key");
        let mut db = CatalogDb::open(&CatalogLocation::Memory, &key).expect("open");
        let object = fixture(&mut db);
        for (position, duration) in [
            (1, 0),
            (2, 1),
            (1, MAX_DURATION_MS + 1),
            (60_001, 70_000),
            (u64::MAX, u64::MAX),
        ] {
            assert_eq!(
                put(&mut db, &object, position, duration)
                    .expect_err("invalid bounds")
                    .status(),
                ChurStatus::InvalidInput
            );
        }
        put(&mut db, &object, 500, 60_000).expect("save");
        db.connection()
            .execute("UPDATE objects SET object_generation = 2", [])
            .expect("new generation");
        assert_eq!(get(&db, &object).expect("stale generation"), 0);
        put(&mut db, &object, 600, 60_000).expect("new save");
        db.connection()
            .execute("UPDATE object_streams SET stream_revision = 2", [])
            .expect("new content");
        assert_eq!(get(&db, &object).expect("stale content"), 0);
        put(&mut db, &object, 700, 60_000).expect("new content save");
        db.connection()
            .execute("UPDATE objects SET duration_ms = 59000", [])
            .expect("corrected duration");
        assert_eq!(get(&db, &object).expect("stale duration"), 0);
        db.connection()
            .execute(
                "UPDATE objects SET media_kind = ?1",
                [MediaClass::Image.value()],
            )
            .expect("nonvideo");
        assert_eq!(
            get(&db, &object).expect_err("nonvideo").status(),
            ChurStatus::NotFound
        );
        assert_eq!(
            put(&mut db, &object, 500, 60_000)
                .expect_err("nonvideo")
                .status(),
            ChurStatus::NotFound
        );
        db.connection()
            .execute(
                "UPDATE objects SET media_kind = ?1, state = 0",
                [MediaClass::Video.value()],
            )
            .expect("inactive");
        assert_eq!(
            put(&mut db, &object, 500, 60_000)
                .expect_err("inactive")
                .status(),
            ChurStatus::NotFound
        );
    }
}
