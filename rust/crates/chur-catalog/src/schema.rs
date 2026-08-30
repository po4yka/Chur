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

use chur_core::{Id, Result, bail, ensure, err, limits::catalog as limits};
use chur_format::{
    constants::{CATALOG_FORMAT_VERSION_V1, CATALOG_FORMAT_VERSION_V2, CATALOG_FORMAT_VERSION_V3},
    envelope::ObjectKeyEnvelope,
};
use rusqlite::Connection;

use crate::db::{CatalogDb, as_sqlite_integer, from_sqlite_integer, map_sqlite};

/// One schema step: the version it produces and the statements that produce it.
#[cfg(test)]
struct Step {
    version: u16,
}

/// Every schema step, in ascending version order with no gap.
#[cfg(test)]
const STEPS: &[Step] = &[
    Step {
        version: CATALOG_FORMAT_VERSION_V1,
    },
    Step {
        version: CATALOG_FORMAT_VERSION_V2,
    },
    Step {
        version: CATALOG_FORMAT_VERSION_V3,
    },
];

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
    active_metadata_revision  INTEGER NOT NULL,
    -- The FTS5 rowid of this object. FTS5 keys on an INTEGER rowid and an
    -- object is keyed on 16 opaque bytes, so a search hit could not be joined
    -- back to its row without a column holding the mapping. UNIQUE is what
    -- turns a collision into a refused insert rather than a wrong page.
    search_key                INTEGER NOT NULL UNIQUE
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
    -- NULL once §14.4 step 1 has destroyed it, which is the moment the
    -- ContentKey and every byte written under it become unrecoverable. The
    -- column is the envelope itself rather than a reference, because
    -- object_key_envelopes references an object row that does not exist until
    -- the transaction commits.
    envelope_body        BLOB,
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

/// Catalog v2 adds only encrypted synchronization state.
const V2_DDL: &str = r#"
CREATE TABLE sync_state (
    only_row                         INTEGER PRIMARY KEY CHECK (only_row = 1),
    membership_generation           INTEGER NOT NULL CHECK (membership_generation >= 0),
    membership_commitment           BLOB NOT NULL CHECK (length(membership_commitment) = 32),
    latest_own_checkpoint_commitment BLOB
        CHECK (latest_own_checkpoint_commitment IS NULL OR length(latest_own_checkpoint_commitment) = 32)
) STRICT;

CREATE TABLE sync_membership_records (
    membership_generation INTEGER PRIMARY KEY CHECK (membership_generation >= 0),
    record_kind            INTEGER NOT NULL CHECK (record_kind IN (1, 2)),
    device_id              BLOB NOT NULL CHECK (length(device_id) = 16),
    commitment             BLOB NOT NULL UNIQUE CHECK (length(commitment) = 32),
    record                 BLOB NOT NULL CHECK (length(record) BETWEEN 1 AND 16777216)
) STRICT;

CREATE TABLE sync_devices (
    device_id              BLOB PRIMARY KEY CHECK (length(device_id) = 16),
    signing_public_key     BLOB NOT NULL CHECK (length(signing_public_key) = 32),
    hpke_public_key        BLOB NOT NULL CHECK (length(hpke_public_key) = 32),
    status                 INTEGER NOT NULL CHECK (status IN (1, 2)),
    membership_generation  INTEGER NOT NULL CHECK (membership_generation >= 0),
    revoked_sequence       INTEGER CHECK (revoked_sequence IS NULL OR revoked_sequence >= 0),
    revoked_digest         BLOB CHECK (revoked_digest IS NULL OR length(revoked_digest) = 32),
    CHECK ((status = 1 AND revoked_sequence IS NULL AND revoked_digest IS NULL)
        OR (status = 2 AND revoked_sequence IS NOT NULL AND revoked_digest IS NOT NULL))
) STRICT;

CREATE TABLE sync_signing_keys (
    device_id              BLOB NOT NULL REFERENCES sync_devices(device_id),
    membership_generation  INTEGER NOT NULL CHECK (membership_generation >= 0),
    public_key             BLOB NOT NULL CHECK (length(public_key) = 32),
    PRIMARY KEY (device_id, membership_generation)
) STRICT;

CREATE TABLE sync_identity_envelopes (
    device_id          BLOB NOT NULL REFERENCES sync_devices(device_id),
    identity_generation INTEGER NOT NULL CHECK (identity_generation >= 0),
    active             INTEGER NOT NULL CHECK (active IN (0, 1)),
    recovery_only      INTEGER NOT NULL CHECK (recovery_only IN (0, 1)),
    body               BLOB NOT NULL CHECK (length(body) BETWEEN 1 AND 16777216),
    PRIMARY KEY (device_id, identity_generation)
) STRICT;

CREATE TABLE sync_operations (
    device_id       BLOB NOT NULL REFERENCES sync_devices(device_id),
    device_sequence INTEGER NOT NULL CHECK (device_sequence >= 0),
    operation_id    BLOB NOT NULL CHECK (length(operation_id) = 16),
    digest          BLOB NOT NULL CHECK (length(digest) = 32),
    record          BLOB NOT NULL CHECK (length(record) BETWEEN 1 AND 16777216),
    PRIMARY KEY (device_id, device_sequence)
) STRICT;

CREATE TABLE sync_heads (
    device_id         BLOB PRIMARY KEY REFERENCES sync_devices(device_id),
    accepted_sequence INTEGER CHECK (accepted_sequence IS NULL OR accepted_sequence >= 0),
    accepted_digest   BLOB CHECK (accepted_digest IS NULL OR length(accepted_digest) = 32),
    floor_sequence    INTEGER CHECK (floor_sequence IS NULL OR floor_sequence >= 0),
    floor_digest      BLOB CHECK (floor_digest IS NULL OR length(floor_digest) = 32),
    CHECK ((accepted_sequence IS NULL) = (accepted_digest IS NULL)),
    CHECK ((floor_sequence IS NULL) = (floor_digest IS NULL))
) STRICT;

CREATE TABLE sync_forks (
    device_id          BLOB PRIMARY KEY REFERENCES sync_devices(device_id),
    state              INTEGER NOT NULL CHECK (state IN (1, 2)),
    accepted_record    BLOB NOT NULL CHECK (length(accepted_record) BETWEEN 1 AND 16777216),
    conflicting_record BLOB NOT NULL CHECK (length(conflicting_record) BETWEEN 1 AND 16777216)
) STRICT;

CREATE TABLE sync_checkpoints (
    issuer_device_id BLOB PRIMARY KEY REFERENCES sync_devices(device_id),
    commitment       BLOB NOT NULL UNIQUE CHECK (length(commitment) = 32),
    record           BLOB NOT NULL CHECK (length(record) BETWEEN 1 AND 16777216),
    accepted_at_ms   INTEGER NOT NULL CHECK (accepted_at_ms >= 0),
    own              INTEGER NOT NULL CHECK (own IN (0, 1))
) STRICT;

CREATE TABLE sync_rotations (
    collection_id         BLOB PRIMARY KEY REFERENCES collections(collection_id),
    target_epoch          INTEGER NOT NULL CHECK (target_epoch >= 0),
    owner_device_id       BLOB NOT NULL REFERENCES sync_devices(device_id),
    membership_generation INTEGER NOT NULL CHECK (membership_generation >= 0),
    accepted_at_ms        INTEGER NOT NULL CHECK (accepted_at_ms >= 0),
    collection_envelope   BLOB NOT NULL CHECK (length(collection_envelope) = 126),
    completed             INTEGER NOT NULL CHECK (completed IN (0, 1))
) STRICT;

CREATE TABLE sync_object_envelope_epochs (
    object_id           BLOB PRIMARY KEY REFERENCES objects(object_id),
    collection_id       BLOB NOT NULL REFERENCES collections(collection_id),
    collection_epoch    INTEGER NOT NULL CHECK (collection_epoch >= 0),
    envelope_generation INTEGER NOT NULL CHECK (envelope_generation >= 0)
) STRICT;

CREATE UNIQUE INDEX sync_operations_by_id ON sync_operations (operation_id);
CREATE INDEX sync_operations_by_digest ON sync_operations (device_id, digest);
CREATE INDEX sync_membership_by_device
    ON sync_membership_records (device_id, membership_generation);
CREATE INDEX sync_old_epoch_objects
    ON sync_object_envelope_epochs (collection_id, collection_epoch, object_id);
CREATE UNIQUE INDEX sync_active_identity_envelope
    ON sync_identity_envelopes (device_id) WHERE active = 1;
"#;

/// Catalog v3 adds only durable collection-sharing state.
const V3_DDL: &str = r#"
CREATE TABLE sharing_collections (
    collection_id         BLOB PRIMARY KEY CHECK (length(collection_id) = 16),
    source_vault_id       BLOB NOT NULL CHECK (length(source_vault_id) = 16),
    initial_epoch         INTEGER NOT NULL CHECK (initial_epoch >= 1),
    membership_generation INTEGER NOT NULL CHECK (membership_generation >= 0),
    membership_commitment BLOB NOT NULL CHECK (length(membership_commitment) = 32),
    current_epoch         INTEGER NOT NULL CHECK (current_epoch >= initial_epoch)
) STRICT;

CREATE TABLE sharing_membership_records (
    collection_id              BLOB NOT NULL REFERENCES sharing_collections(collection_id),
    membership_generation      INTEGER NOT NULL CHECK (membership_generation >= 1),
    commitment                 BLOB NOT NULL CHECK (length(commitment) = 32),
    issuer_signing_public_key  BLOB NOT NULL CHECK (length(issuer_signing_public_key) = 32),
    recipient_identity_vault_id BLOB NOT NULL CHECK (length(recipient_identity_vault_id) = 16),
    recipient_device_id        BLOB NOT NULL CHECK (length(recipient_device_id) = 16),
    record                     BLOB NOT NULL CHECK (length(record) = 292),
    PRIMARY KEY (collection_id, membership_generation),
    UNIQUE (collection_id, commitment)
) STRICT;

CREATE TABLE sharing_recipient_pins (
    collection_id               BLOB NOT NULL REFERENCES sharing_collections(collection_id),
    recipient_identity_vault_id BLOB NOT NULL CHECK (length(recipient_identity_vault_id) = 16),
    recipient_device_id         BLOB NOT NULL CHECK (length(recipient_device_id) = 16),
    signing_public_key          BLOB NOT NULL CHECK (length(signing_public_key) = 32),
    hpke_public_key             BLOB NOT NULL CHECK (length(hpke_public_key) = 32),
    verification                INTEGER NOT NULL CHECK (verification IN (1, 2)),
    PRIMARY KEY (collection_id, recipient_identity_vault_id, recipient_device_id)
) STRICT;

CREATE TABLE sharing_grants (
    grant_id                    BLOB PRIMARY KEY CHECK (length(grant_id) = 16),
    collection_id               BLOB NOT NULL REFERENCES sharing_collections(collection_id),
    recipient_identity_vault_id BLOB NOT NULL CHECK (length(recipient_identity_vault_id) = 16),
    recipient_device_id         BLOB NOT NULL CHECK (length(recipient_device_id) = 16),
    membership_generation       INTEGER NOT NULL CHECK (membership_generation >= 1),
    collection_epoch            INTEGER NOT NULL CHECK (collection_epoch >= 1),
    record                      BLOB NOT NULL CHECK (length(record) = 309)
) STRICT;

CREATE INDEX sharing_membership_by_recipient
    ON sharing_membership_records (
        collection_id, recipient_identity_vault_id, recipient_device_id, membership_generation
    );
CREATE INDEX sharing_grants_by_collection
    ON sharing_grants (collection_id, membership_generation, collection_epoch, grant_id);
"#;

/// Creates the current schema or opens it without performing an implicit migration.
///
/// `docs/format/CATALOG_SCHEMA_V1.md` §18 forbids skipping an untested step, so
/// Existing v1 catalogs return `MigrationRequired`: the authenticated vault
/// descriptor must enter `MIGRATING` before their pages change.
pub fn open_at_current_version(db: &mut CatalogDb, now_ms: u64) -> Result<u16> {
    let present = recorded_version(db.connection())?;
    let Some(present) = present else {
        install(db, now_ms)?;
        return Ok(CATALOG_FORMAT_VERSION_V3);
    };
    if present != CATALOG_FORMAT_VERSION_V3 {
        bail!(
            MigrationRequired,
            "the catalog requires an authenticated schema migration"
        );
    }
    Ok(CATALOG_FORMAT_VERSION_V3)
}

/// The version the database records, or `None` when the schema is absent.
pub(crate) fn recorded_version(connection: &Connection) -> Result<Option<u16>> {
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

/// Installs the current schema and its single `vault_state` row.
fn install(db: &mut CatalogDb, now_ms: u64) -> Result<()> {
    let checkpoint = as_sqlite_integer(now_ms, "the install time is out of range")?;
    db.transaction(|transaction| {
        transaction
            .execute_batch(V1_DDL)
            .map_err(|error| map_sqlite(error, "the catalog schema could not be created"))?;
        transaction
            .execute_batch(V2_DDL)
            .map_err(|error| map_sqlite(error, "the sync catalog schema could not be created"))?;
        transaction.execute_batch(V3_DDL).map_err(|error| {
            map_sqlite(error, "the sharing catalog schema could not be created")
        })?;
        transaction
            .execute(
                "INSERT INTO vault_state (
                     only_row, catalog_format_version, catalog_generation,
                     active_migration_target, object_store_checkpoint,
                     integrity_checkpoint_ms, capability_flags
                 ) VALUES (1, ?1, 1, NULL, 0, ?2, 0)",
                rusqlite::params![i64::from(CATALOG_FORMAT_VERSION_V3), checkpoint],
            )
            .map_err(|error| map_sqlite(error, "the catalog state row could not be written"))?;
        Ok(())
    })
}

#[cfg(test)]
pub(crate) fn reset_to_v1(db: &mut CatalogDb) -> Result<()> {
    db.transaction(|transaction| {
        transaction
            .execute_batch(
                "DROP TABLE sharing_grants;
                 DROP TABLE sharing_recipient_pins;
                 DROP TABLE sharing_membership_records;
                 DROP TABLE sharing_collections;
                 DROP TABLE sync_rotations;
                 DROP TABLE sync_object_envelope_epochs;
                 DROP TABLE sync_checkpoints;
                 DROP TABLE sync_forks;
                 DROP TABLE sync_heads;
                 DROP TABLE sync_operations;
                 DROP TABLE sync_identity_envelopes;
                 DROP TABLE sync_signing_keys;
                 DROP TABLE sync_devices;
                 DROP TABLE sync_membership_records;
                 DROP TABLE sync_state;
                 UPDATE vault_state SET catalog_format_version = 1 WHERE only_row = 1;",
            )
            .map_err(|error| map_sqlite(error, "the test catalog could not be reset to v1"))?;
        Ok(())
    })
}

#[cfg(test)]
pub(crate) fn reset_to_v2(db: &mut CatalogDb) -> Result<()> {
    db.transaction(|transaction| {
        transaction
            .execute_batch(
                "DROP TABLE sharing_grants;
                 DROP TABLE sharing_recipient_pins;
                 DROP TABLE sharing_membership_records;
                 DROP TABLE sharing_collections;
                 UPDATE vault_state SET catalog_format_version = 2 WHERE only_row = 1;",
            )
            .map_err(|error| map_sqlite(error, "the test catalog could not be reset to v2"))?;
        Ok(())
    })
}

/// Applies the only supported migration after the descriptor entered `MIGRATING`.
///
/// The version is written in the same transaction as the step's statements, so
/// a crash leaves the database at the version whose schema is actually present.
pub(crate) fn migrate_v1_to_v2(db: &mut CatalogDb, vault_id: &Id) -> Result<()> {
    ensure!(
        recorded_version(db.connection())? == Some(CATALOG_FORMAT_VERSION_V1),
        MigrationRequired,
        "the catalog is not at the supported migration source"
    );
    db.transaction(|transaction| {
        transaction
            .execute(
                "UPDATE vault_state SET active_migration_target = ?1 WHERE only_row = 1",
                [i64::from(CATALOG_FORMAT_VERSION_V2)],
            )
            .map_err(|error| map_sqlite(error, "the migration target could not be recorded"))?;
        transaction
            .execute_batch(V2_DDL)
            .map_err(|error| map_sqlite(error, "a migration step failed"))?;
        backfill_object_envelope_epochs(transaction, vault_id)?;
        transaction
            .execute(
                "UPDATE vault_state
                    SET catalog_format_version = ?1, active_migration_target = NULL
                  WHERE only_row = 1",
                [i64::from(CATALOG_FORMAT_VERSION_V2)],
            )
            .map_err(|error| map_sqlite(error, "the migrated version could not be recorded"))?;
        Ok(())
    })
}

/// Applies the v2-to-v3 sharing-state migration after the descriptor entered
/// `MIGRATING`.
pub(crate) fn migrate_v2_to_v3(db: &mut CatalogDb) -> Result<()> {
    ensure!(
        recorded_version(db.connection())? == Some(CATALOG_FORMAT_VERSION_V2),
        MigrationRequired,
        "the catalog is not at the supported migration source"
    );
    db.transaction(|transaction| {
        transaction
            .execute(
                "UPDATE vault_state SET active_migration_target = ?1 WHERE only_row = 1",
                [i64::from(CATALOG_FORMAT_VERSION_V3)],
            )
            .map_err(|error| map_sqlite(error, "the migration target could not be recorded"))?;
        transaction
            .execute_batch(V3_DDL)
            .map_err(|error| map_sqlite(error, "a migration step failed"))?;
        transaction
            .execute(
                "UPDATE vault_state
                    SET catalog_format_version = ?1, active_migration_target = NULL
                  WHERE only_row = 1",
                [i64::from(CATALOG_FORMAT_VERSION_V3)],
            )
            .map_err(|error| map_sqlite(error, "the migrated version could not be recorded"))?;
        Ok(())
    })
}

fn backfill_object_envelope_epochs(
    transaction: &rusqlite::Transaction<'_>,
    vault_id: &Id,
) -> Result<()> {
    let mut select = transaction
        .prepare(
            "SELECT e.object_id, o.collection_id, e.generation, e.body
               FROM object_key_envelopes e
               JOIN objects o ON o.object_id = e.object_id
              WHERE e.status = 1
                AND e.generation = (
                    SELECT max(candidate.generation)
                      FROM object_key_envelopes candidate
                     WHERE candidate.object_id = e.object_id AND candidate.status = 1
                )",
        )
        .map_err(|error| map_sqlite(error, "active object envelopes could not be read"))?;
    let mut rows = select
        .query([])
        .map_err(|error| map_sqlite(error, "active object envelopes could not be read"))?;
    while let Some(row) = rows
        .next()
        .map_err(|error| map_sqlite(error, "an active object envelope could not be read"))?
    {
        let object_bytes: Vec<u8> = row
            .get(0)
            .map_err(|error| map_sqlite(error, "an object id could not be read"))?;
        let collection_bytes: Vec<u8> = row
            .get(1)
            .map_err(|error| map_sqlite(error, "a collection id could not be read"))?;
        let generation: i64 = row
            .get(2)
            .map_err(|error| map_sqlite(error, "an envelope generation could not be read"))?;
        let body: Vec<u8> = row
            .get(3)
            .map_err(|error| map_sqlite(error, "an object envelope could not be read"))?;
        let object_id = crate::row::id(&object_bytes, "an object id is malformed")?;
        let collection_id = crate::row::id(&collection_bytes, "a collection id is malformed")?;
        let generation = from_sqlite_integer(generation, "an envelope generation is negative")?;
        let envelope = ObjectKeyEnvelope::decode(&body)
            .map_err(|_| err!(CatalogCorrupt, "an active object envelope is malformed"))?;
        ensure!(
            envelope.vault_id() == vault_id
                && envelope.object_id() == &object_id
                && envelope.collection_id() == &collection_id
                && envelope.envelope_generation() == generation,
            CatalogCorrupt,
            "an active object envelope contradicts its catalog row"
        );
        transaction
            .execute(
                "INSERT INTO sync_object_envelope_epochs
                    (object_id, collection_id, collection_epoch, envelope_generation)
                 VALUES (?1, ?2, ?3, ?4)",
                rusqlite::params![
                    object_id.as_bytes().as_slice(),
                    collection_id.as_bytes().as_slice(),
                    as_sqlite_integer(
                        envelope.collection_epoch(),
                        "an envelope collection epoch is too large"
                    )?,
                    as_sqlite_integer(generation, "an envelope generation is too large")?,
                ],
            )
            .map_err(|error| map_sqlite(error, "an envelope epoch could not be projected"))?;
    }
    Ok(())
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
    use chur_crypto::{Key, Nonce, random};
    use chur_format::envelope::ObjectKeyEnvelope;

    fn open() -> CatalogDb {
        let root: Key = random::secret::<32>().expect("root");
        let vault = random::id().expect("id");
        let key = CatalogKey::derive(&root, &vault).expect("key");
        CatalogDb::open(&CatalogLocation::Memory, &key).expect("open")
    }

    fn install_v1(db: &mut CatalogDb) {
        db.transaction(|transaction| {
            transaction.execute_batch(V1_DDL).expect("v1 schema");
            transaction
                .execute(
                    "INSERT INTO vault_state (
                         only_row, catalog_format_version, catalog_generation,
                         active_migration_target, object_store_checkpoint,
                         integrity_checkpoint_ms, capability_flags
                     ) VALUES (1, 1, 1, NULL, 0, 1, 0)",
                    [],
                )
                .expect("v1 state");
            Ok(())
        })
        .expect("install v1");
    }

    #[test]
    fn a_new_catalog_installs_version_three() {
        let mut db = open();
        assert_eq!(
            open_at_current_version(&mut db, 1_700_000_000_000).expect("install"),
            CATALOG_FORMAT_VERSION_V3
        );
        let sharing_tables: i64 = db
            .connection()
            .query_row(
                "SELECT count(*) FROM sqlite_schema
                  WHERE type = 'table' AND name LIKE 'sharing_%'",
                [],
                |row| row.get(0),
            )
            .expect("sharing tables");
        assert_eq!(sharing_tables, 4);
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
    fn a_non_current_version_is_migration_required_and_not_changed() {
        let mut db = open();
        install_v1(&mut db);
        let Err(error) = open_at_current_version(&mut db, 1) else {
            panic!("a v1 catalog migrated without an authenticated descriptor");
        };
        assert_eq!(error.status(), chur_core::ChurStatus::MigrationRequired);
        assert_eq!(
            recorded_version(db.connection()).expect("version"),
            Some(CATALOG_FORMAT_VERSION_V1)
        );
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
            Some(CATALOG_FORMAT_VERSION_V3)
        );
    }

    #[test]
    fn v2_migration_installs_empty_sharing_state() {
        let mut db = open();
        open_at_current_version(&mut db, 1).expect("install");
        reset_to_v2(&mut db).expect("reset to v2");

        migrate_v2_to_v3(&mut db).expect("migrate");

        assert_eq!(
            recorded_version(db.connection()).expect("version"),
            Some(CATALOG_FORMAT_VERSION_V3)
        );
        let sharing_tables: i64 = db
            .connection()
            .query_row(
                "SELECT count(*) FROM sqlite_schema
                  WHERE type = 'table' AND name LIKE 'sharing_%'",
                [],
                |row| row.get(0),
            )
            .expect("sharing tables");
        assert_eq!(sharing_tables, 4);
    }

    #[test]
    fn v3_sharing_constraints_reject_malformed_rows() {
        let mut db = open();
        open_at_current_version(&mut db, 1).expect("schema");
        let collection = [1u8; 16];
        db.connection()
            .execute(
                "INSERT INTO sharing_collections VALUES (?1, ?2, 1, 0, ?3, 1)",
                rusqlite::params![collection, [2u8; 16], [0u8; 32]],
            )
            .expect("collection");

        assert!(
            db.connection()
                .execute(
                    "INSERT INTO sharing_recipient_pins VALUES (?1, ?2, ?3, ?4, ?5, 3)",
                    rusqlite::params![collection, [3u8; 16], [4u8; 16], [5u8; 32], [6u8; 32],],
                )
                .is_err(),
            "an invalid verification state was accepted"
        );
        assert!(
            db.connection()
                .execute(
                    "INSERT INTO sharing_grants VALUES (?1, ?2, ?3, ?4, 1, 1, ?5)",
                    rusqlite::params![[7u8; 16], collection, [3u8; 16], [4u8; 16], [8u8; 308],],
                )
                .is_err(),
            "a short grant record was accepted"
        );
    }

    #[test]
    fn v1_migration_projects_the_highest_active_object_envelope() {
        let mut db = open();
        install_v1(&mut db);
        let vault_id = random::id().expect("vault");
        let collection_id = random::id().expect("collection");
        let object_id = random::id().expect("object");
        let stream_id = random::id().expect("stream");
        let collection_key: Key = random::secret::<32>().expect("collection key");
        let object_key: Key = random::secret::<32>().expect("object key");
        let envelope = ObjectKeyEnvelope::seal(
            &collection_key,
            vault_id,
            collection_id,
            7,
            object_id,
            3,
            Nonce::new([4; 24]),
            &object_key,
        )
        .expect("envelope");
        db.transaction(|transaction| {
            transaction
                .execute(
                    "INSERT INTO collections VALUES (?1, 7, 1, 1, 1)",
                    [collection_id.as_bytes().as_slice()],
                )
                .expect("collection");
            transaction
                .execute(
                    "INSERT INTO objects VALUES (
                         ?1, 1, ?2, ?3, 1, 1, 1, 0, 1, 1, 1, 0, 0, 1, 1, 0, 1, 1
                     )",
                    rusqlite::params![
                        object_id.as_bytes().as_slice(),
                        collection_id.as_bytes().as_slice(),
                        stream_id.as_bytes().as_slice(),
                    ],
                )
                .expect("object");
            transaction
                .execute(
                    "INSERT INTO object_key_envelopes VALUES (?1, 3, 1, ?2)",
                    rusqlite::params![object_id.as_bytes().as_slice(), envelope.encode()],
                )
                .expect("envelope row");
            Ok(())
        })
        .expect("v1 rows");

        migrate_v1_to_v2(&mut db, &vault_id).expect("migrate");
        let projection: (i64, i64) = db
            .connection()
            .query_row(
                "SELECT collection_epoch, envelope_generation
                   FROM sync_object_envelope_epochs WHERE object_id = ?1",
                [object_id.as_bytes().as_slice()],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .expect("projection");
        assert_eq!(projection, (7, 3));
        assert_eq!(
            recorded_version(db.connection()).expect("version"),
            Some(CATALOG_FORMAT_VERSION_V2)
        );
    }

    #[test]
    fn v2_sync_constraints_reject_malformed_rows() {
        let mut db = open();
        open_at_current_version(&mut db, 1).expect("schema");
        assert!(
            db.connection()
                .execute(
                    "INSERT INTO sync_devices VALUES (?1, ?2, ?3, 1, 1, NULL, NULL)",
                    rusqlite::params![vec![1u8; 15], vec![2u8; 32], vec![3u8; 32]],
                )
                .is_err(),
            "a short device id was accepted"
        );
        let device = [1u8; 16];
        db.connection()
            .execute(
                "INSERT INTO sync_devices VALUES (?1, ?2, ?3, 1, 1, NULL, NULL)",
                rusqlite::params![device, [2u8; 32], [3u8; 32]],
            )
            .expect("device");
        assert!(
            db.connection()
                .execute(
                    "INSERT INTO sync_heads VALUES (?1, 1, NULL, NULL, NULL)",
                    [device],
                )
                .is_err(),
            "a partial accepted head was accepted"
        );
        db.connection()
            .execute(
                "INSERT INTO sync_identity_envelopes VALUES (?1, 1, 1, 0, ?2)",
                rusqlite::params![device, [4u8]],
            )
            .expect("identity envelope");
        assert!(
            db.connection()
                .execute(
                    "INSERT INTO sync_identity_envelopes VALUES (?1, 2, 1, 0, ?2)",
                    rusqlite::params![device, [5u8]],
                )
                .is_err(),
            "a second active identity envelope was accepted"
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
