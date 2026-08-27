//! The physical catalog schema and its migrations.
//!
//! `docs/format/CATALOG_SCHEMA_V1.md` §1 lists the logical entities and §18
//! fixes how a schema version moves: versions are values of the
//! `catalog_format_version` namespace, they begin at `0x0001`, and they
//! increase by one with no gap and no branch. This module holds one migration
//! step per version and applies them in order.
//!
//! The DDL carries no user label, which §16.5 asks for: a table or index named
//! after an album would put a private word in a page an attacker can see the
//! size of.

use chur_core::{Result, bail, err, limits::catalog as limits};
use chur_format::constants::CATALOG_FORMAT_VERSION_V1;
use rusqlite::Connection;

use crate::db::{CatalogDb, as_sqlite_integer, from_sqlite_integer, map_sqlite};

/// One schema step: the version it produces and the statements that produce it.
struct Step {
    version: u16,
    ddl: &'static str,
}

/// Every schema step, in ascending version order with no gap.
const STEPS: &[Step] = &[Step {
    version: CATALOG_FORMAT_VERSION_V1,
    ddl: V1_DDL,
}];

/// The v1 schema.
///
/// Column widths follow the canonical model rather than SQLite's affinities: an
/// identifier is a 16-byte `BLOB` and never text, a time is the `u64`
/// milliseconds of `docs/format/CANONICAL_ENCODING_V1.md` §9 stored as a signed
/// integer, and every enumerated value is the byte allocated in §15.4.
const V1_DDL: &str = r#"
-- §2. Exactly one row. The CHECK is what makes that structural rather than a
-- convention a later statement can break.
CREATE TABLE vault_state (
    only_row                  INTEGER PRIMARY KEY CHECK (only_row = 1),
    catalog_format_version    INTEGER NOT NULL,
    catalog_generation        INTEGER NOT NULL,
    active_migration_target   INTEGER,
    object_store_checkpoint   INTEGER NOT NULL,
    integrity_checkpoint_ms   INTEGER NOT NULL,
    capability_flags          INTEGER NOT NULL
) STRICT;

-- §3.
CREATE TABLE collections (
    collection_id     BLOB PRIMARY KEY,
    current_epoch     INTEGER NOT NULL,
    policy_type       INTEGER NOT NULL,
    created_revision  INTEGER NOT NULL,
    status            INTEGER NOT NULL
) STRICT;

-- §4. `body` is the encoded CollectionKeyEnvelopeV1 of the format crate; the
-- catalog stores it whole rather than splitting it into columns, so no second
-- encoder exists for a record CANONICAL_ENCODING_V1.md §13 reserves for Rust.
CREATE TABLE collection_key_envelopes (
    collection_id     BLOB NOT NULL REFERENCES collections(collection_id),
    collection_epoch  INTEGER NOT NULL,
    generation        INTEGER NOT NULL,
    status            INTEGER NOT NULL,
    body              BLOB NOT NULL,
    PRIMARY KEY (collection_id, collection_epoch, generation)
) STRICT;

-- §5. `state` and `integrity_summary` are the two independent enums of §5.1.
-- The projection columns of §16.1 live here so a page is a range scan.
CREATE TABLE objects (
    object_id                 BLOB PRIMARY KEY,
    object_generation         INTEGER NOT NULL,
    collection_id             BLOB NOT NULL REFERENCES collections(collection_id),
    primary_stream_id         BLOB NOT NULL,
    media_kind                INTEGER NOT NULL,
    capture_time_ms           INTEGER NOT NULL,
    import_time_ms            INTEGER NOT NULL,
    capture_time_substituted  INTEGER NOT NULL,
    plaintext_size            INTEGER NOT NULL,
    width                     INTEGER NOT NULL,
    height                    INTEGER NOT NULL,
    duration_ms               INTEGER NOT NULL,
    favorite                  INTEGER NOT NULL,
    state                     INTEGER NOT NULL,
    integrity_summary         INTEGER NOT NULL,
    thumbnail_ready           INTEGER NOT NULL,
    active_metadata_revision  INTEGER NOT NULL
) STRICT;

-- §6. No active row points at a temporary uncommitted container, which the
-- import journal enforces by holding the temp path itself.
CREATE TABLE object_streams (
    stream_id                 BLOB PRIMARY KEY,
    object_id                 BLOB NOT NULL REFERENCES objects(object_id),
    stream_kind               INTEGER NOT NULL,
    stream_revision           INTEGER NOT NULL,
    source_content_revision   INTEGER NOT NULL,
    container_path_id         BLOB NOT NULL,
    container_version         INTEGER NOT NULL,
    suite_id                  INTEGER NOT NULL,
    ciphertext_size           INTEGER NOT NULL,
    plaintext_size            INTEGER NOT NULL,
    chunk_size                INTEGER NOT NULL,
    complete_verified_ms      INTEGER,
    final_commitment          BLOB NOT NULL
) STRICT;

-- §7. Uniqueness prevents an ambiguous active generation.
CREATE TABLE object_key_envelopes (
    object_id     BLOB NOT NULL REFERENCES objects(object_id),
    generation    INTEGER NOT NULL,
    status        INTEGER NOT NULL,
    body          BLOB NOT NULL,
    PRIMARY KEY (object_id, generation)
) STRICT;

-- §8. `record` is the sealed canonical revision; the remaining columns are the
-- queryable projection of the same bytes, rewritten in the transaction that
-- activates the revision so the two cannot drift.
CREATE TABLE metadata_revisions (
    object_id           BLOB NOT NULL REFERENCES objects(object_id),
    revision            INTEGER NOT NULL,
    active              INTEGER NOT NULL,
    record              BLOB NOT NULL,
    original_filename   TEXT,
    caption             TEXT,
    content_type        TEXT NOT NULL,
    capture_time_ms     INTEGER,
    width               INTEGER NOT NULL,
    height              INTEGER NOT NULL,
    duration_ms         INTEGER NOT NULL,
    PRIMARY KEY (object_id, revision)
) STRICT;

-- §10. The binding to source_content_revision is what stops a stale derivative
-- from being presented as current.
CREATE TABLE derived_assets (
    object_id                BLOB NOT NULL REFERENCES objects(object_id),
    kind                     INTEGER NOT NULL,
    source_content_revision  INTEGER NOT NULL,
    asset_revision           INTEGER NOT NULL,
    generator_profile        INTEGER NOT NULL,
    stream_id                BLOB NOT NULL,
    PRIMARY KEY (object_id, kind, source_content_revision)
) STRICT;

-- §9.
CREATE TABLE albums (
    album_id          BLOB PRIMARY KEY,
    name              TEXT NOT NULL,
    created_ms        INTEGER NOT NULL,
    revision          INTEGER NOT NULL
) STRICT;

-- §9 and §16.3: capture_time_ms is duplicated here so an album page never joins
-- before sorting.
CREATE TABLE album_memberships (
    album_id          BLOB NOT NULL REFERENCES albums(album_id),
    object_id         BLOB NOT NULL REFERENCES objects(object_id),
    capture_time_ms   INTEGER NOT NULL,
    added_ms          INTEGER NOT NULL,
    revision          INTEGER NOT NULL,
    PRIMARY KEY (album_id, object_id)
) STRICT;

-- §9 and §16.3: the same duplication, for the same reason.
CREATE TABLE favorites (
    object_id         BLOB PRIMARY KEY REFERENCES objects(object_id),
    capture_time_ms   INTEGER NOT NULL,
    added_ms          INTEGER NOT NULL
) STRICT;

CREATE TABLE tags (
    tag_id     BLOB PRIMARY KEY,
    name       TEXT NOT NULL UNIQUE,
    created_ms INTEGER NOT NULL
) STRICT;

CREATE TABLE object_tags (
    tag_id            BLOB NOT NULL REFERENCES tags(tag_id),
    object_id         BLOB NOT NULL REFERENCES objects(object_id),
    capture_time_ms   INTEGER NOT NULL,
    PRIMARY KEY (tag_id, object_id)
) STRICT;

-- §11. The journal record of OBJECT_CONTAINER_V1.md §14.1. It shares the
-- catalog transaction domain, so a chunk-index reservation and the state that
-- activates the object cannot disagree after a crash.
CREATE TABLE import_transactions (
    transaction_id       BLOB PRIMARY KEY,
    temp_path_id         BLOB NOT NULL,
    object_id            BLOB NOT NULL,
    stream_id            BLOB NOT NULL,
    stream_kind          INTEGER NOT NULL,
    stream_revision      INTEGER NOT NULL,
    envelope_generation  INTEGER NOT NULL,
    envelope_body        BLOB NOT NULL,
    nonce_prefix         BLOB NOT NULL,
    chunk_size           INTEGER NOT NULL,
    manifest_length      INTEGER NOT NULL,
    reserved_index       INTEGER,
    expected_length      INTEGER,
    source_seekable      INTEGER NOT NULL,
    stage                INTEGER NOT NULL,
    opened_ms            INTEGER NOT NULL
) STRICT;

-- §12. Opaque cleanup state with no private filename.
CREATE TABLE scratch_entries (
    entry_id     BLOB PRIMARY KEY,
    path_id      BLOB NOT NULL,
    object_id    BLOB,
    byte_length  INTEGER NOT NULL,
    created_ms   INTEGER NOT NULL
) STRICT;

-- §13. Stable codes, never a secret error string.
CREATE TABLE integrity_records (
    object_id     BLOB NOT NULL REFERENCES objects(object_id),
    stream_id     BLOB NOT NULL,
    checked_ms    INTEGER NOT NULL,
    scope         INTEGER NOT NULL,
    range_start   INTEGER NOT NULL,
    range_end     INTEGER NOT NULL,
    outcome       INTEGER NOT NULL,
    PRIMARY KEY (object_id, stream_id, checked_ms)
) STRICT;

-- §14. Written before physical garbage collection so recovery cannot resurrect
-- a removed object.
CREATE TABLE tombstones (
    object_id     BLOB PRIMARY KEY,
    authored_ms   INTEGER NOT NULL,
    object_generation INTEGER NOT NULL
) STRICT;

-- §16.3. The five indexes that cover a scope under a sort, then the four that
-- serve lookups which are not query scopes.
CREATE INDEX objects_by_capture ON objects (state, capture_time_ms, object_id);
CREATE INDEX objects_by_import ON objects (state, import_time_ms, object_id);
CREATE INDEX memberships_by_capture
    ON album_memberships (album_id, capture_time_ms, object_id);
CREATE INDEX favorites_by_capture ON favorites (capture_time_ms, object_id);
CREATE INDEX object_tags_by_capture ON object_tags (tag_id, capture_time_ms, object_id);
CREATE INDEX streams_by_object ON object_streams (object_id, stream_kind);
CREATE INDEX assets_by_object ON derived_assets (object_id, kind, source_content_revision);
CREATE INDEX envelopes_by_object ON object_key_envelopes (object_id, status);
CREATE INDEX imports_by_stage ON import_transactions (stage);
CREATE INDEX tombstones_by_authored ON tombstones (authored_ms);

-- §16.4. The FTS5 table lives in the same database, so its pages carry the same
-- at-rest encryption. It is contentless: the catalog already holds the
-- filename, the caption, and the tag names in ordinary columns, so a second
-- copy inside the index would be a second source of truth for private text.
-- `contentless_delete=1` is what makes a row replaceable by its rowid alone; a
-- plain contentless table can only be appended to, and reindexing a revised
-- object would leave the old terms matching for ever.
CREATE VIRTUAL TABLE object_search USING fts5(
    filename,
    caption,
    tag_names,
    content='',
    contentless_delete=1,
    tokenize='unicode61 remove_diacritics 2',
    prefix='2 3'
);
"#;

/// Creates or upgrades the schema, and returns the version it ends at.
///
/// `docs/format/CATALOG_SCHEMA_V1.md` §18 forbids skipping an untested step, so
/// each step runs in its own transaction and the recorded version advances one
/// value at a time. A database recording a version this build does not know is
/// `MigrationRequired`, never a silent downgrade.
pub fn open_at_current_version(db: &mut CatalogDb, now_ms: u64) -> Result<u16> {
    let present = recorded_version(db.connection())?;
    let Some(present) = present else {
        install(db, now_ms)?;
        return Ok(CATALOG_FORMAT_VERSION_V1);
    };
    if present > CATALOG_FORMAT_VERSION_V1 {
        bail!(
            MigrationRequired,
            "the catalog records a format version this build does not read"
        );
    }
    for step in STEPS.iter().filter(|step| step.version > present) {
        apply(db, step)?;
    }
    Ok(CATALOG_FORMAT_VERSION_V1)
}

/// The version the database records, or `None` when the schema is absent.
fn recorded_version(connection: &Connection) -> Result<Option<u16>> {
    let present: i64 = connection
        .query_row(
            "SELECT count(*) FROM sqlite_schema WHERE type = 'table' AND name = 'vault_state'",
            [],
            |row| row.get(0),
        )
        .map_err(|error| map_sqlite(error, "the catalog schema could not be read"))?;
    if present == 0 {
        return Ok(None);
    }
    let version: i64 = connection
        .query_row(
            "SELECT catalog_format_version FROM vault_state WHERE only_row = 1",
            [],
            |row| row.get(0),
        )
        .map_err(|error| map_sqlite(error, "the catalog format version could not be read"))?;
    u16::try_from(version)
        .map(Some)
        .map_err(|_| err!(CatalogCorrupt, "the catalog format version is out of range"))
}

/// Installs the first version and its single `vault_state` row.
fn install(db: &mut CatalogDb, now_ms: u64) -> Result<()> {
    let checkpoint = as_sqlite_integer(now_ms, "the install time is out of range")?;
    db.transaction(|transaction| {
        transaction
            .execute_batch(V1_DDL)
            .map_err(|error| map_sqlite(error, "the catalog schema could not be created"))?;
        transaction
            .execute(
                "INSERT INTO vault_state (
                     only_row, catalog_format_version, catalog_generation,
                     active_migration_target, object_store_checkpoint,
                     integrity_checkpoint_ms, capability_flags
                 ) VALUES (1, ?1, 1, NULL, 0, ?2, 0)",
                rusqlite::params![i64::from(CATALOG_FORMAT_VERSION_V1), checkpoint],
            )
            .map_err(|error| map_sqlite(error, "the catalog state row could not be written"))?;
        Ok(())
    })
}

/// Applies one migration step and records the version it produced.
///
/// The version is written in the same transaction as the step's statements, so
/// a crash leaves the database at the version whose schema is actually present.
fn apply(db: &mut CatalogDb, step: &Step) -> Result<()> {
    let target = i64::from(step.version);
    db.transaction(|transaction| {
        transaction
            .execute(
                "UPDATE vault_state SET active_migration_target = ?1 WHERE only_row = 1",
                [target],
            )
            .map_err(|error| map_sqlite(error, "the migration target could not be recorded"))?;
        transaction
            .execute_batch(step.ddl)
            .map_err(|error| map_sqlite(error, "a migration step failed"))?;
        transaction
            .execute(
                "UPDATE vault_state
                    SET catalog_format_version = ?1, active_migration_target = NULL
                  WHERE only_row = 1",
                [target],
            )
            .map_err(|error| map_sqlite(error, "the migrated version could not be recorded"))?;
        Ok(())
    })
}

/// The catalog generation, which every query page reports so a caller can tell
/// that its cursor crossed a write.
pub fn generation(db: &CatalogDb) -> Result<u64> {
    let value: i64 = db
        .connection()
        .query_row(
            "SELECT catalog_generation FROM vault_state WHERE only_row = 1",
            [],
            |row| row.get(0),
        )
        .map_err(|error| map_sqlite(error, "the catalog generation could not be read"))?;
    from_sqlite_integer(value, "the catalog generation is negative")
}

/// Advances the catalog generation inside a transaction that already changed a
/// row a query page can return.
pub fn bump_generation(transaction: &rusqlite::Transaction<'_>) -> Result<()> {
    transaction
        .execute(
            "UPDATE vault_state SET catalog_generation = catalog_generation + 1 WHERE only_row = 1",
            [],
        )
        .map_err(|error| map_sqlite(error, "the catalog generation could not be advanced"))?;
    Ok(())
}

/// Rejects a page `limit` outside the bounds of §16.2.
pub fn check_query_limit(limit: u32) -> Result<u32> {
    if limit == 0 {
        return Ok(limits::QUERY_LIMIT_DEFAULT);
    }
    if !(limits::QUERY_LIMIT_MIN..=limits::QUERY_LIMIT_MAX).contains(&limit) {
        bail!(ResourceLimitExceeded, "the page limit is outside §16.2");
    }
    Ok(limit)
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::panic, clippy::expect_used)]

    use super::*;
    use crate::db::{CatalogKey, CatalogLocation};
    use chur_crypto::{Key, random};

    fn open() -> CatalogDb {
        let root: Key = random::secret::<32>().expect("root");
        let vault = random::id().expect("id");
        let key = CatalogKey::derive(&root, &vault).expect("key");
        CatalogDb::open(&CatalogLocation::Memory, &key).expect("open")
    }

    #[test]
    fn a_new_catalog_installs_version_one() {
        let mut db = open();
        assert_eq!(
            open_at_current_version(&mut db, 1_700_000_000_000).expect("install"),
            CATALOG_FORMAT_VERSION_V1
        );
        assert_eq!(generation(&db).expect("generation"), 1);
    }

    #[test]
    fn opening_an_installed_catalog_is_idempotent() {
        let mut db = open();
        open_at_current_version(&mut db, 1).expect("install");
        open_at_current_version(&mut db, 2).expect("reopen");
        assert_eq!(generation(&db).expect("generation"), 1);
    }

    #[test]
    fn a_future_version_is_migration_required_and_not_a_downgrade() {
        let mut db = open();
        open_at_current_version(&mut db, 1).expect("install");
        db.connection()
            .execute(
                "UPDATE vault_state SET catalog_format_version = 2 WHERE only_row = 1",
                [],
            )
            .expect("write a future version");
        let Err(error) = open_at_current_version(&mut db, 1) else {
            panic!("a future catalog version opened");
        };
        assert_eq!(error.status(), chur_core::ChurStatus::MigrationRequired);
    }

    #[test]
    fn the_state_row_cannot_be_duplicated() {
        let mut db = open();
        open_at_current_version(&mut db, 1).expect("install");
        let outcome = db.connection().execute(
            "INSERT INTO vault_state (
                 only_row, catalog_format_version, catalog_generation,
                 active_migration_target, object_store_checkpoint,
                 integrity_checkpoint_ms, capability_flags
             ) VALUES (2, 1, 1, NULL, 0, 0, 0)",
            [],
        );
        assert!(outcome.is_err(), "a second vault_state row was accepted");
    }

    #[test]
    fn the_schema_steps_ascend_by_one_from_one() {
        for (index, step) in STEPS.iter().enumerate() {
            let expected = u16::try_from(index + 1).expect("a schema step index fits a u16");
            assert_eq!(step.version, expected, "§18 forbids a gap or a branch");
        }
        assert_eq!(
            STEPS.last().map(|step| step.version),
            Some(CATALOG_FORMAT_VERSION_V1)
        );
    }

    #[test]
    fn the_generation_advances_once_per_transaction_that_asks() {
        let mut db = open();
        open_at_current_version(&mut db, 1).expect("install");
        db.transaction(bump_generation).expect("bump");
        assert_eq!(generation(&db).expect("generation"), 2);
    }

    #[test]
    fn a_page_limit_is_bounded_and_zero_means_the_default() {
        assert_eq!(check_query_limit(0).expect("default"), 200);
        assert_eq!(check_query_limit(1).expect("minimum"), 1);
        assert_eq!(check_query_limit(500).expect("maximum"), 500);
        let Err(error) = check_query_limit(501) else {
            panic!("a page limit above §16.2 was accepted");
        };
        assert_eq!(error.status(), chur_core::ChurStatus::ResourceLimitExceeded);
    }

    #[test]
    fn the_search_index_tokenizes_with_the_registered_profile() {
        let mut db = open();
        open_at_current_version(&mut db, 1).expect("install");
        db.connection()
            .execute(
                "INSERT INTO object_search (rowid, filename, caption, tag_names)
                 VALUES (1, 'Ärger.jpg', 'a caption', 'holiday')",
                [],
            )
            .expect("index a row");
        // remove_diacritics 2 folds the umlaut, and the prefix index answers a
        // two-character as-you-type query.
        for query in ["arger", "ar*", "holiday", "caption"] {
            let hits: i64 = db
                .connection()
                .query_row(
                    "SELECT count(*) FROM object_search WHERE object_search MATCH ?1",
                    [query],
                    |row| row.get(0),
                )
                .expect("search");
            assert_eq!(hits, 1, "query {query} did not match");
        }
    }
}
