//! Reading and writing the catalog entities.
//!
//! Every function here is either one statement or one transaction. The
//! transactions are the atomic boundaries `docs/format/CATALOG_SCHEMA_V1.md`
//! §17 requires by name, and each one says which boundary it is.
//!
//! The policy bounds of §21 are enforced here rather than in the schema. SQLite
//! can express a per-row constraint but not "at most 1000000 objects", and a
//! bound checked in the same transaction as the insert cannot be raced by a
//! second writer, because §8.1 of `docs/interop/FFI_CONTRACT.md` serializes
//! catalog writes behind one mutex per session.

use chur_core::{Id, Result, bail, ensure, limits::catalog as limits};
use chur_format::constants::{IntegritySummary, ObjectState, StreamKind};
use rusqlite::{Transaction, params};

use crate::db::{CatalogDb, as_sqlite_integer, from_sqlite_integer, map_sqlite};
use crate::model::{
    Album, Collection, DerivedAsset, ENVELOPE_STATUS_ACTIVE, MetadataRevision, Object, Stream, Tag,
};
use crate::schema::bump_generation;

/// Everything one transaction needs to activate a newly imported object.
///
/// It is one type rather than eight arguments because §17 makes the object
/// commit, its envelope, its stream, and its metadata revision a single atomic
/// boundary: a caller that could pass them separately could also commit them
/// separately.
pub struct ObjectActivation {
    /// The object row.
    pub object: Object,
    /// The original stream.
    pub stream: Stream,
    /// The encoded `ObjectKeyEnvelopeV1` that wraps this object's key.
    pub envelope: Vec<u8>,
    /// The generation of that envelope.
    pub envelope_generation: u64,
    /// The first metadata revision.
    pub metadata: MetadataRevision,
}

/// Inserts the collection every object of a single-vault install belongs to.
///
/// It is idempotent on the collection identifier, so provisioning may run it
/// again after an interrupted first run.
pub fn put_collection(db: &mut CatalogDb, collection: &Collection) -> Result<()> {
    let epoch = as_sqlite_integer(
        collection.current_epoch,
        "the collection epoch is too large",
    )?;
    let revision = as_sqlite_integer(
        collection.created_revision,
        "the collection revision is too large",
    )?;
    db.transaction(|transaction| {
        let present = count(transaction, "SELECT count(*) FROM collections")?;
        let already: i64 = transaction
            .query_row(
                "SELECT count(*) FROM collections WHERE collection_id = ?1",
                [collection.collection_id.as_bytes().as_slice()],
                |row| row.get(0),
            )
            .map_err(|error| map_sqlite(error, "the collection could not be read"))?;
        if already == 0 {
            ensure!(
                present < limits::COLLECTIONS_MAX,
                ResourceLimitExceeded,
                "the vault holds the §21 maximum of collections"
            );
        }
        transaction
            .execute(
                "INSERT INTO collections
                     (collection_id, current_epoch, policy_type, created_revision, status)
                 VALUES (?1, ?2, ?3, ?4, ?5)
                 ON CONFLICT(collection_id) DO UPDATE SET
                     current_epoch = excluded.current_epoch,
                     status = excluded.status",
                params![
                    collection.collection_id.as_bytes().as_slice(),
                    epoch,
                    i64::from(collection.policy_type),
                    revision,
                    i64::from(collection.status),
                ],
            )
            .map_err(|error| map_sqlite(error, "the collection could not be written"))?;
        Ok(())
    })
}

/// Writes a collection and its first key envelope in one transaction, §4 and §17.
pub fn put_collection_with_envelope(
    db: &mut CatalogDb,
    collection: &Collection,
    envelope_generation: u64,
    envelope: &[u8],
) -> Result<()> {
    put_collection(db, collection)?;
    let epoch = as_sqlite_integer(
        collection.current_epoch,
        "the collection epoch is too large",
    )?;
    let generation =
        as_sqlite_integer(envelope_generation, "the envelope generation is too large")?;
    db.transaction(|transaction| {
        let active: i64 = transaction
            .query_row(
                "SELECT count(*) FROM collection_key_envelopes
                  WHERE collection_id = ?1 AND collection_epoch = ?2 AND status = 1",
                params![collection.collection_id.as_bytes().as_slice(), epoch],
                |row| row.get(0),
            )
            .map_err(|error| map_sqlite(error, "the collection envelopes could not be counted"))?;
        let active = from_sqlite_integer(active, "a catalog count is negative")?;
        ensure!(
            active < u64::from(limits::COLLECTION_ENVELOPES_ACTIVE_MAX),
            ResourceLimitExceeded,
            "the epoch holds the §21 maximum of active collection envelopes"
        );
        transaction
            .execute(
                "INSERT INTO collection_key_envelopes
                     (collection_id, collection_epoch, generation, status, body)
                 VALUES (?1, ?2, ?3, ?4, ?5)
                 ON CONFLICT(collection_id, collection_epoch, generation) DO NOTHING",
                params![
                    collection.collection_id.as_bytes().as_slice(),
                    epoch,
                    generation,
                    i64::from(ENVELOPE_STATUS_ACTIVE),
                    envelope,
                ],
            )
            .map_err(|error| map_sqlite(error, "the collection envelope could not be written"))?;
        Ok(())
    })
}

/// The active key envelope of one collection epoch, §4.
pub fn active_collection_envelope(
    db: &CatalogDb,
    collection_id: &Id,
    epoch: u64,
) -> Result<Vec<u8>> {
    db.connection()
        .query_row(
            "SELECT body FROM collection_key_envelopes
              WHERE collection_id = ?1 AND collection_epoch = ?2 AND status = ?3
              ORDER BY generation DESC LIMIT 1",
            params![
                collection_id.as_bytes().as_slice(),
                as_sqlite_integer(epoch, "the collection epoch is too large")?,
                i64::from(ENVELOPE_STATUS_ACTIVE),
            ],
            |row| row.get::<_, Vec<u8>>(0),
        )
        .map_err(|error| map_sqlite(error, "the collection envelope could not be read"))
}

/// The collection a single-vault install seals every object under, §3.
///
/// It returns `None` rather than failing when the vault has none yet, because
/// a vault created but never imported into is a legitimate state.
pub fn default_collection(db: &CatalogDb) -> Result<Option<Id>> {
    let bytes: Option<Vec<u8>> = db
        .connection()
        .query_row(
            "SELECT collection_id FROM collections
              WHERE policy_type = ?1 AND status = ?2
              ORDER BY created_revision, collection_id LIMIT 1",
            params![
                i64::from(crate::model::COLLECTION_POLICY_VAULT_DEFAULT),
                i64::from(crate::model::COLLECTION_STATUS_ACTIVE),
            ],
            |row| row.get(0),
        )
        .optional_row()?;
    bytes
        .map(|value| crate::row::id(&value, "the collection id is malformed"))
        .transpose()
}

/// Turns "no rows" into `None` rather than an error.
trait OptionalRow<T> {
    fn optional_row(self) -> Result<Option<T>>;
}

impl<T> OptionalRow<T> for rusqlite::Result<T> {
    fn optional_row(self) -> Result<Option<T>> {
        match self {
            Ok(value) => Ok(Some(value)),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
            Err(error) => Err(map_sqlite(error, "a catalog row could not be read")),
        }
    }
}

/// Reads one collection.
pub fn collection(db: &CatalogDb, collection_id: &Id) -> Result<Collection> {
    db.connection()
        .query_row(
            "SELECT current_epoch, policy_type, created_revision, status
               FROM collections WHERE collection_id = ?1",
            [collection_id.as_bytes().as_slice()],
            |row| {
                Ok((
                    row.get::<_, i64>(0)?,
                    row.get::<_, i64>(1)?,
                    row.get::<_, i64>(2)?,
                    row.get::<_, i64>(3)?,
                ))
            },
        )
        .map_err(|error| map_sqlite(error, "the collection could not be read"))
        .and_then(|(epoch, policy, revision, status)| {
            Ok(Collection {
                collection_id: *collection_id,
                current_epoch: from_sqlite_integer(epoch, "the collection epoch is negative")?,
                policy_type: byte(policy, "the collection policy is out of range")?,
                created_revision: from_sqlite_integer(
                    revision,
                    "the collection revision is negative",
                )?,
                status: byte(status, "the collection status is out of range")?,
            })
        })
}

/// Commits an imported object, its key envelope, its stream, and its first
/// metadata revision in one transaction.
///
/// This is the "object commit plus envelope/catalog activation" boundary of
/// §17. Nothing here touches the filesystem: the container is already renamed
/// into the committed namespace by `docs/format/OBJECT_CONTAINER_V1.md` §14
/// before this runs, so a crash leaves a container with no row, which §14.1 of
/// the catalog specification sweeps.
pub fn activate_object(db: &mut CatalogDb, activation: &ObjectActivation) -> Result<()> {
    activation.object.check()?;
    activation.stream.check()?;
    activation.metadata.check()?;
    ensure!(
        activation.object.object_id == activation.stream.object_id
            && activation.object.object_id == activation.metadata.object_id,
        InvalidInput,
        "the activation names more than one object"
    );
    ensure!(
        activation.object.primary_stream_id == activation.stream.stream_id,
        InvalidInput,
        "the primary stream is not the stream being activated"
    );
    ensure!(
        activation.stream.stream_kind == StreamKind::Original,
        InvalidInput,
        "an activation commits the original stream"
    );
    ensure!(
        activation.metadata.active,
        InvalidInput,
        "the first metadata revision is the active one"
    );

    db.transaction(|transaction| {
        let objects = count(transaction, "SELECT count(*) FROM objects")?;
        ensure!(
            objects < limits::OBJECTS_MAX,
            ResourceLimitExceeded,
            "the vault holds the §21 maximum of objects"
        );
        insert_object_row(transaction, &activation.object)?;
        insert_stream_row(transaction, &activation.stream)?;
        insert_envelope_row(
            transaction,
            &activation.object.object_id,
            activation.envelope_generation,
            ENVELOPE_STATUS_ACTIVE,
            &activation.envelope,
        )?;
        insert_metadata_row(transaction, &activation.metadata)?;
        reindex_search(transaction, &activation.object.object_id)?;
        bump_generation(transaction)
    })
}

fn insert_object_row(transaction: &Transaction<'_>, object: &Object) -> Result<()> {
    transaction
        .execute(
            "INSERT INTO objects (
                 object_id, object_generation, collection_id, primary_stream_id, media_kind,
                 capture_time_ms, import_time_ms, capture_time_substituted, plaintext_size,
                 width, height, duration_ms, favorite, state, integrity_summary,
                 thumbnail_ready, active_metadata_revision, search_key
             ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18)",
            params![
                object.object_id.as_bytes().as_slice(),
                as_sqlite_integer(
                    object.object_generation,
                    "the object generation is too large"
                )?,
                object.collection_id.as_bytes().as_slice(),
                object.primary_stream_id.as_bytes().as_slice(),
                i64::from(object.media_kind.value()),
                as_sqlite_integer(object.capture_time_ms, "the capture time is out of range")?,
                as_sqlite_integer(object.import_time_ms, "the import time is out of range")?,
                i64::from(object.capture_time_substituted),
                as_sqlite_integer(object.plaintext_size, "the plaintext size is out of range")?,
                i64::from(object.width),
                i64::from(object.height),
                as_sqlite_integer(object.duration_ms, "the duration is out of range")?,
                i64::from(object.favorite),
                i64::from(object.state.value()),
                i64::from(object.integrity_summary.value()),
                i64::from(object.thumbnail_ready),
                i64::from(object.active_metadata_revision),
                row_key(&object.object_id),
            ],
        )
        .map_err(|error| map_sqlite(error, "the object row could not be written"))?;
    Ok(())
}

fn insert_stream_row(transaction: &Transaction<'_>, stream: &Stream) -> Result<()> {
    let streams: u64 = count_with(
        transaction,
        "SELECT count(*) FROM object_streams WHERE object_id = ?1",
        [stream.object_id.as_bytes().as_slice()],
    )?;
    ensure!(
        streams < u64::from(limits::STREAMS_PER_OBJECT_MAX),
        ResourceLimitExceeded,
        "the object holds the §21 maximum of streams"
    );
    transaction
        .execute(
            "INSERT INTO object_streams (
                 stream_id, object_id, stream_kind, stream_revision, source_content_revision,
                 container_path_id, container_version, suite_id, ciphertext_size,
                 plaintext_size, chunk_size, complete_verified_ms, final_commitment
             ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13)",
            params![
                stream.stream_id.as_bytes().as_slice(),
                stream.object_id.as_bytes().as_slice(),
                i64::from(stream.stream_kind.value()),
                i64::from(stream.stream_revision),
                i64::from(stream.source_content_revision),
                stream.container_path_id.as_bytes().as_slice(),
                i64::from(stream.container_version),
                i64::from(stream.suite_id),
                as_sqlite_integer(
                    stream.ciphertext_size,
                    "the ciphertext size is out of range"
                )?,
                as_sqlite_integer(stream.plaintext_size, "the plaintext size is out of range")?,
                i64::from(stream.chunk_size),
                stream
                    .complete_verified_ms
                    .map(|value| as_sqlite_integer(value, "the verification time is out of range"))
                    .transpose()?,
                stream.final_commitment.as_slice(),
            ],
        )
        .map_err(|error| map_sqlite(error, "the stream row could not be written"))?;
    Ok(())
}

fn insert_envelope_row(
    transaction: &Transaction<'_>,
    object_id: &Id,
    generation: u64,
    status: u8,
    body: &[u8],
) -> Result<()> {
    let total: u64 = count_with(
        transaction,
        "SELECT count(*) FROM object_key_envelopes WHERE object_id = ?1",
        [object_id.as_bytes().as_slice()],
    )?;
    ensure!(
        total < u64::from(limits::OBJECT_ENVELOPES_MAX),
        ResourceLimitExceeded,
        "the object holds the §21 maximum of key envelopes"
    );
    if status == ENVELOPE_STATUS_ACTIVE {
        let active: u64 = count_with(
            transaction,
            "SELECT count(*) FROM object_key_envelopes WHERE object_id = ?1 AND status = 1",
            [object_id.as_bytes().as_slice()],
        )?;
        ensure!(
            active < u64::from(limits::OBJECT_ENVELOPES_ACTIVE_MAX),
            ResourceLimitExceeded,
            "the object holds the §21 maximum of active key envelopes"
        );
    }
    transaction
        .execute(
            "INSERT INTO object_key_envelopes (object_id, generation, status, body)
             VALUES (?1, ?2, ?3, ?4)",
            params![
                object_id.as_bytes().as_slice(),
                as_sqlite_integer(generation, "the envelope generation is too large")?,
                i64::from(status),
                body,
            ],
        )
        .map_err(|error| map_sqlite(error, "the key envelope could not be written"))?;
    Ok(())
}

fn insert_metadata_row(transaction: &Transaction<'_>, revision: &MetadataRevision) -> Result<()> {
    let present: u64 = count_with(
        transaction,
        "SELECT count(*) FROM metadata_revisions WHERE object_id = ?1",
        [revision.object_id.as_bytes().as_slice()],
    )?;
    ensure!(
        present < u64::from(limits::METADATA_REVISIONS_MAX),
        ResourceLimitExceeded,
        "the object holds the §21 maximum of metadata revisions"
    );
    if revision.active {
        transaction
            .execute(
                "UPDATE metadata_revisions SET active = 0 WHERE object_id = ?1",
                [revision.object_id.as_bytes().as_slice()],
            )
            .map_err(|error| map_sqlite(error, "the previous revision could not be retired"))?;
    }
    transaction
        .execute(
            "INSERT INTO metadata_revisions (
                 object_id, revision, active, record, original_filename, caption,
                 content_type, capture_time_ms, width, height, duration_ms
             ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)",
            params![
                revision.object_id.as_bytes().as_slice(),
                i64::from(revision.revision),
                i64::from(revision.active),
                revision.record.as_slice(),
                revision.original_filename.as_deref(),
                revision.caption.as_deref(),
                revision.content_type.as_str(),
                revision
                    .capture_time_ms
                    .map(|value| as_sqlite_integer(value, "the capture time is out of range"))
                    .transpose()?,
                i64::from(revision.width),
                i64::from(revision.height),
                as_sqlite_integer(revision.duration_ms, "the duration is out of range")?,
            ],
        )
        .map_err(|error| map_sqlite(error, "the metadata revision could not be written"))?;
    Ok(())
}

/// Activates a new metadata revision, §17.
///
/// The revision, the object's projection columns, and the search index are
/// rewritten together, which is what §16.3 means by the duplicated
/// `capture_time_ms` not being able to drift and what §16.4 means by an index
/// that never outlives the revision it describes.
pub fn activate_metadata_revision(db: &mut CatalogDb, revision: &MetadataRevision) -> Result<()> {
    revision.check()?;
    ensure!(
        revision.active,
        InvalidInput,
        "an activation writes the active revision"
    );
    let object_id = revision.object_id;
    db.transaction(|transaction| {
        insert_metadata_row(transaction, revision)?;
        let capture = revision
            .capture_time_ms
            .map(|value| as_sqlite_integer(value, "the capture time is out of range"))
            .transpose()?;
        transaction
            .execute(
                "UPDATE objects
                    SET active_metadata_revision = ?2,
                        width = ?3, height = ?4, duration_ms = ?5,
                        capture_time_ms = COALESCE(?6, capture_time_ms),
                        capture_time_substituted = CASE WHEN ?6 IS NULL THEN 1 ELSE 0 END
                  WHERE object_id = ?1",
                params![
                    object_id.as_bytes().as_slice(),
                    i64::from(revision.revision),
                    i64::from(revision.width),
                    i64::from(revision.height),
                    as_sqlite_integer(revision.duration_ms, "the duration is out of range")?,
                    capture,
                ],
            )
            .map_err(|error| map_sqlite(error, "the object projection could not be rewritten"))?;
        // §16.3: the duplicated sort key is rewritten in the same transaction.
        transaction
            .execute(
                "UPDATE album_memberships
                    SET capture_time_ms = (SELECT capture_time_ms FROM objects WHERE object_id = ?1)
                  WHERE object_id = ?1",
                [object_id.as_bytes().as_slice()],
            )
            .map_err(|error| map_sqlite(error, "an album sort key could not be rewritten"))?;
        transaction
            .execute(
                "UPDATE favorites
                    SET capture_time_ms = (SELECT capture_time_ms FROM objects WHERE object_id = ?1)
                  WHERE object_id = ?1",
                [object_id.as_bytes().as_slice()],
            )
            .map_err(|error| map_sqlite(error, "a favourite sort key could not be rewritten"))?;
        transaction
            .execute(
                "UPDATE object_tags
                    SET capture_time_ms = (SELECT capture_time_ms FROM objects WHERE object_id = ?1)
                  WHERE object_id = ?1",
                [object_id.as_bytes().as_slice()],
            )
            .map_err(|error| map_sqlite(error, "a tag sort key could not be rewritten"))?;
        reindex_search(transaction, &object_id)?;
        bump_generation(transaction)
    })
}

/// Records a derived asset and its stream, §10 and §17.
pub fn put_derived_asset(db: &mut CatalogDb, asset: &DerivedAsset, stream: &Stream) -> Result<()> {
    asset.check()?;
    stream.check()?;
    ensure!(
        asset.stream_id == stream.stream_id && asset.object_id == stream.object_id,
        InvalidInput,
        "the asset and its stream name different subjects"
    );
    let thumbnail = asset.kind == StreamKind::ThumbnailSmall;
    db.transaction(|transaction| {
        insert_stream_row(transaction, stream)?;
        transaction
            .execute(
                "INSERT INTO derived_assets (
                     object_id, kind, source_content_revision, asset_revision,
                     generator_profile, stream_id
                 ) VALUES (?1, ?2, ?3, ?4, ?5, ?6)
                 ON CONFLICT(object_id, kind, source_content_revision) DO UPDATE SET
                     asset_revision = excluded.asset_revision,
                     generator_profile = excluded.generator_profile,
                     stream_id = excluded.stream_id",
                params![
                    asset.object_id.as_bytes().as_slice(),
                    i64::from(asset.kind.value()),
                    i64::from(asset.source_content_revision),
                    i64::from(asset.asset_revision),
                    i64::from(asset.generator_profile),
                    asset.stream_id.as_bytes().as_slice(),
                ],
            )
            .map_err(|error| map_sqlite(error, "the derived asset could not be written"))?;
        if thumbnail {
            transaction
                .execute(
                    "UPDATE objects SET thumbnail_ready = 1 WHERE object_id = ?1",
                    [asset.object_id.as_bytes().as_slice()],
                )
                .map_err(|error| map_sqlite(error, "the thumbnail flag could not be set"))?;
        }
        bump_generation(transaction)
    })
}

/// Sets or clears the favourite flag, §9.
pub fn set_favorite(db: &mut CatalogDb, object_id: &Id, favorite: bool, now_ms: u64) -> Result<()> {
    let added = as_sqlite_integer(now_ms, "the time is out of range")?;
    db.transaction(|transaction| {
        let changed = transaction
            .execute(
                "UPDATE objects SET favorite = ?2 WHERE object_id = ?1 AND state = 1",
                params![object_id.as_bytes().as_slice(), i64::from(favorite)],
            )
            .map_err(|error| map_sqlite(error, "the favourite flag could not be set"))?;
        ensure!(changed == 1, NotFound, "no listable object carries that id");
        if favorite {
            transaction
                .execute(
                    "INSERT INTO favorites (object_id, capture_time_ms, added_ms)
                     SELECT object_id, capture_time_ms, ?2 FROM objects WHERE object_id = ?1
                     ON CONFLICT(object_id) DO NOTHING",
                    params![object_id.as_bytes().as_slice(), added],
                )
                .map_err(|error| map_sqlite(error, "the favourite row could not be written"))?;
        } else {
            transaction
                .execute(
                    "DELETE FROM favorites WHERE object_id = ?1",
                    [object_id.as_bytes().as_slice()],
                )
                .map_err(|error| map_sqlite(error, "the favourite row could not be removed"))?;
        }
        bump_generation(transaction)
    })
}

/// Records a verification verdict, §5.1 and §13.
///
/// Proven corruption is a lifecycle change rather than a verdict, so it is
/// [`mark_corrupt`] and not a value this function accepts.
pub fn set_integrity_summary(
    db: &mut CatalogDb,
    object_id: &Id,
    summary: IntegritySummary,
    now_ms: u64,
) -> Result<()> {
    let checked = as_sqlite_integer(now_ms, "the time is out of range")?;
    db.transaction(|transaction| {
        let changed = transaction
            .execute(
                "UPDATE objects SET integrity_summary = ?2 WHERE object_id = ?1 AND state = 1",
                params![object_id.as_bytes().as_slice(), i64::from(summary.value())],
            )
            .map_err(|error| map_sqlite(error, "the integrity summary could not be set"))?;
        ensure!(changed == 1, NotFound, "no active object carries that id");
        if summary == IntegritySummary::CompleteVerified {
            transaction
                .execute(
                    "UPDATE object_streams SET complete_verified_ms = ?2 WHERE object_id = ?1",
                    params![object_id.as_bytes().as_slice(), checked],
                )
                .map_err(|error| map_sqlite(error, "the verification time could not be set"))?;
        }
        bump_generation(transaction)
    })
}

/// Moves an object to the terminal `CORRUPT` lifecycle state, §5.1.
///
/// The transition is one-way and this is the only function that performs it.
pub fn mark_corrupt(db: &mut CatalogDb, object_id: &Id) -> Result<()> {
    db.transaction(|transaction| {
        let changed = transaction
            .execute(
                "UPDATE objects SET state = ?2, integrity_summary = ?3
                  WHERE object_id = ?1 AND state = ?4",
                params![
                    object_id.as_bytes().as_slice(),
                    i64::from(ObjectState::Corrupt.value()),
                    i64::from(IntegritySummary::Unverified.value()),
                    i64::from(ObjectState::Active.value()),
                ],
            )
            .map_err(|error| map_sqlite(error, "the object could not be marked corrupt"))?;
        ensure!(changed == 1, NotFound, "no active object carries that id");
        bump_generation(transaction)
    })
}

/// Reads one object row.
pub fn object(db: &CatalogDb, object_id: &Id) -> Result<Object> {
    db.connection()
        .query_row(
            "SELECT object_generation, collection_id, primary_stream_id, media_kind,
                    capture_time_ms, import_time_ms, capture_time_substituted, plaintext_size,
                    width, height, duration_ms, favorite, state, integrity_summary,
                    thumbnail_ready, active_metadata_revision
               FROM objects WHERE object_id = ?1",
            [object_id.as_bytes().as_slice()],
            |row| crate::row::object(object_id, row),
        )
        .map_err(|error| map_sqlite(error, "the object could not be read"))?
}

/// Reads the streams of one object, in kind order.
pub fn streams(db: &CatalogDb, object_id: &Id) -> Result<Vec<Stream>> {
    let connection = db.connection();
    let mut statement = connection
        .prepare(
            "SELECT stream_id, stream_kind, stream_revision, source_content_revision,
                    container_path_id, container_version, suite_id, ciphertext_size,
                    plaintext_size, chunk_size, complete_verified_ms, final_commitment
               FROM object_streams WHERE object_id = ?1 ORDER BY stream_kind",
        )
        .map_err(|error| map_sqlite(error, "the stream query could not be prepared"))?;
    let rows = statement
        .query_map([object_id.as_bytes().as_slice()], |row| {
            crate::row::stream(object_id, row)
        })
        .map_err(|error| map_sqlite(error, "the streams could not be read"))?;
    let mut streams = Vec::new();
    for row in rows {
        streams.push(row.map_err(|error| map_sqlite(error, "a stream row could not be read"))??);
    }
    Ok(streams)
}

/// Reads the active key envelope of one object, §7.
pub fn active_envelope(db: &CatalogDb, object_id: &Id) -> Result<Vec<u8>> {
    db.connection()
        .query_row(
            "SELECT body FROM object_key_envelopes
              WHERE object_id = ?1 AND status = ?2
              ORDER BY generation DESC LIMIT 1",
            params![
                object_id.as_bytes().as_slice(),
                i64::from(ENVELOPE_STATUS_ACTIVE)
            ],
            |row| row.get::<_, Vec<u8>>(0),
        )
        .map_err(|error| map_sqlite(error, "the object key envelope could not be read"))
}

/// Reads the active metadata revision of one object, §8.
pub fn active_metadata(db: &CatalogDb, object_id: &Id) -> Result<MetadataRevision> {
    db.connection()
        .query_row(
            "SELECT revision, record, original_filename, caption, content_type,
                    capture_time_ms, width, height, duration_ms
               FROM metadata_revisions WHERE object_id = ?1 AND active = 1",
            [object_id.as_bytes().as_slice()],
            |row| crate::row::metadata(object_id, row),
        )
        .map_err(|error| map_sqlite(error, "the metadata revision could not be read"))?
}

/// Creates an album, §9.
pub fn put_album(db: &mut CatalogDb, album: &Album) -> Result<()> {
    album.check()?;
    let created = as_sqlite_integer(album.created_ms, "the time is out of range")?;
    let revision = as_sqlite_integer(album.revision, "the revision is too large")?;
    db.transaction(|transaction| {
        let present = count(transaction, "SELECT count(*) FROM albums")?;
        let already: i64 = transaction
            .query_row(
                "SELECT count(*) FROM albums WHERE album_id = ?1",
                [album.album_id.as_bytes().as_slice()],
                |row| row.get(0),
            )
            .map_err(|error| map_sqlite(error, "the album could not be read"))?;
        if already == 0 {
            ensure!(
                present < limits::ALBUMS_MAX,
                ResourceLimitExceeded,
                "the vault holds the §21 maximum of albums"
            );
        }
        transaction
            .execute(
                "INSERT INTO albums (album_id, name, created_ms, revision)
                 VALUES (?1, ?2, ?3, ?4)
                 ON CONFLICT(album_id) DO UPDATE SET
                     name = excluded.name, revision = excluded.revision",
                params![
                    album.album_id.as_bytes().as_slice(),
                    album.name.as_str(),
                    created,
                    revision,
                ],
            )
            .map_err(|error| map_sqlite(error, "the album could not be written"))?;
        bump_generation(transaction)
    })
}

/// Every album, with its membership count, in name order, §9.
pub fn albums(db: &CatalogDb) -> Result<Vec<(Album, u64)>> {
    let connection = db.connection();
    let mut statement = connection
        .prepare(
            "SELECT a.album_id, a.name, a.created_ms, a.revision,
                    (SELECT count(*) FROM album_memberships m
                      JOIN objects o ON o.object_id = m.object_id
                     WHERE m.album_id = a.album_id AND o.state = 1)
               FROM albums a ORDER BY a.name, a.album_id",
        )
        .map_err(|error| map_sqlite(error, "the album query could not be prepared"))?;
    let rows = statement
        .query_map([], |row| {
            Ok((
                row.get::<_, Vec<u8>>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, i64>(2)?,
                row.get::<_, i64>(3)?,
                row.get::<_, i64>(4)?,
            ))
        })
        .map_err(|error| map_sqlite(error, "the albums could not be read"))?;
    let mut albums = Vec::new();
    for row in rows {
        let (id, name, created, revision, members) =
            row.map_err(|error| map_sqlite(error, "an album row could not be read"))?;
        albums.push((
            Album {
                album_id: crate::row::id(&id, "the album id is malformed")?,
                name,
                created_ms: from_sqlite_integer(created, "the album time is negative")?,
                revision: from_sqlite_integer(revision, "the album revision is negative")?,
            },
            from_sqlite_integer(members, "an album count is negative")?,
        ));
    }
    Ok(albums)
}

/// The tags on one object, in name order, §9.
pub fn object_tags(db: &CatalogDb, object_id: &Id) -> Result<Vec<Tag>> {
    let connection = db.connection();
    let mut statement = connection
        .prepare(
            "SELECT t.tag_id, t.name, t.created_ms
               FROM object_tags o JOIN tags t ON t.tag_id = o.tag_id
              WHERE o.object_id = ?1 ORDER BY t.name, t.tag_id",
        )
        .map_err(|error| map_sqlite(error, "the tag query could not be prepared"))?;
    let rows = statement
        .query_map([object_id.as_bytes().as_slice()], |row| {
            Ok((
                row.get::<_, Vec<u8>>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, i64>(2)?,
            ))
        })
        .map_err(|error| map_sqlite(error, "the tags could not be read"))?;
    let mut tags = Vec::new();
    for row in rows {
        let (id, name, created) =
            row.map_err(|error| map_sqlite(error, "a tag row could not be read"))?;
        tags.push(Tag {
            tag_id: crate::row::id(&id, "the tag id is malformed")?,
            name,
            created_ms: from_sqlite_integer(created, "the tag time is negative")?,
        });
    }
    Ok(tags)
}

/// Adds or removes an album membership, §9.
pub fn set_album_membership(
    db: &mut CatalogDb,
    album_id: &Id,
    object_id: &Id,
    member: bool,
    now_ms: u64,
) -> Result<()> {
    let added = as_sqlite_integer(now_ms, "the time is out of range")?;
    db.transaction(|transaction| {
        if member {
            let present: u64 = count_with(
                transaction,
                "SELECT count(*) FROM album_memberships WHERE album_id = ?1",
                [album_id.as_bytes().as_slice()],
            )?;
            ensure!(
                present < limits::ALBUM_MEMBERSHIPS_MAX,
                ResourceLimitExceeded,
                "the album holds the §21 maximum of memberships"
            );
            let changed = transaction
                .execute(
                    "INSERT INTO album_memberships
                         (album_id, object_id, capture_time_ms, added_ms, revision)
                     SELECT ?1, object_id, capture_time_ms, ?3, 1
                       FROM objects WHERE object_id = ?2 AND state = 1
                     ON CONFLICT(album_id, object_id) DO NOTHING",
                    params![
                        album_id.as_bytes().as_slice(),
                        object_id.as_bytes().as_slice(),
                        added,
                    ],
                )
                .map_err(|error| map_sqlite(error, "the membership could not be written"))?;
            ensure!(
                changed == 1
                    || count_with::<_, [&[u8]; 2]>(
                        transaction,
                        "SELECT count(*) FROM album_memberships WHERE album_id = ?1 AND object_id = ?2",
                        [album_id.as_bytes().as_slice(), object_id.as_bytes().as_slice()],
                    )? == 1,
                NotFound,
                "no listable object carries that id"
            );
        } else {
            transaction
                .execute(
                    "DELETE FROM album_memberships WHERE album_id = ?1 AND object_id = ?2",
                    params![
                        album_id.as_bytes().as_slice(),
                        object_id.as_bytes().as_slice()
                    ],
                )
                .map_err(|error| map_sqlite(error, "the membership could not be removed"))?;
        }
        transaction
            .execute(
                "UPDATE albums SET revision = revision + 1 WHERE album_id = ?1",
                [album_id.as_bytes().as_slice()],
            )
            .map_err(|error| map_sqlite(error, "the album revision could not advance"))?;
        bump_generation(transaction)
    })
}

/// Creates a tag, §9.
pub fn put_tag(db: &mut CatalogDb, tag: &Tag) -> Result<()> {
    tag.check()?;
    let created = as_sqlite_integer(tag.created_ms, "the time is out of range")?;
    db.transaction(|transaction| {
        let present = count(transaction, "SELECT count(*) FROM tags")?;
        let already: i64 = transaction
            .query_row(
                "SELECT count(*) FROM tags WHERE tag_id = ?1",
                [tag.tag_id.as_bytes().as_slice()],
                |row| row.get(0),
            )
            .map_err(|error| map_sqlite(error, "the tag could not be read"))?;
        if already == 0 {
            ensure!(
                present < limits::TAGS_MAX,
                ResourceLimitExceeded,
                "the vault holds the §21 maximum of tags"
            );
        }
        transaction
            .execute(
                "INSERT INTO tags (tag_id, name, created_ms) VALUES (?1, ?2, ?3)
                 ON CONFLICT(tag_id) DO UPDATE SET name = excluded.name",
                params![tag.tag_id.as_bytes().as_slice(), tag.name.as_str(), created],
            )
            .map_err(|error| map_sqlite(error, "the tag could not be written"))?;
        bump_generation(transaction)
    })
}

/// Adds or removes a tag on one object, §9.
///
/// The search index is rewritten in the same transaction, which is the second
/// half of the §16.4 rule that an index never outlives the row it describes.
pub fn set_object_tag(db: &mut CatalogDb, tag_id: &Id, object_id: &Id, tagged: bool) -> Result<()> {
    let object_id = *object_id;
    db.transaction(|transaction| {
        if tagged {
            let present: u64 = count_with(
                transaction,
                "SELECT count(*) FROM object_tags WHERE object_id = ?1",
                [object_id.as_bytes().as_slice()],
            )?;
            ensure!(
                present < u64::from(limits::TAGS_PER_OBJECT_MAX),
                ResourceLimitExceeded,
                "the object holds the §21 maximum of tags"
            );
            transaction
                .execute(
                    "INSERT INTO object_tags (tag_id, object_id, capture_time_ms)
                     SELECT ?1, object_id, capture_time_ms FROM objects
                      WHERE object_id = ?2 AND state = 1
                     ON CONFLICT(tag_id, object_id) DO NOTHING",
                    params![
                        tag_id.as_bytes().as_slice(),
                        object_id.as_bytes().as_slice()
                    ],
                )
                .map_err(|error| map_sqlite(error, "the tag could not be applied"))?;
        } else {
            transaction
                .execute(
                    "DELETE FROM object_tags WHERE tag_id = ?1 AND object_id = ?2",
                    params![
                        tag_id.as_bytes().as_slice(),
                        object_id.as_bytes().as_slice()
                    ],
                )
                .map_err(|error| map_sqlite(error, "the tag could not be removed"))?;
        }
        reindex_search(transaction, &object_id)?;
        bump_generation(transaction)
    })
}

/// Rewrites one object's row in the FTS5 index, §16.4.
///
/// The index is external-content, so the catalog writes it explicitly. The
/// delete-then-insert pair is how FTS5 replaces a row of such a table: an
/// `INSERT` with the `delete` command supplies the old column values, and
/// storing them would duplicate the source of truth, so the row is deleted by
/// its identifier through the `delete-all`-free path of a rowid delete.
pub(crate) fn reindex_search(transaction: &Transaction<'_>, object_id: &Id) -> Result<()> {
    let rowid = search_rowid(transaction, object_id)?;
    let Some(rowid) = rowid else {
        // The object row is gone, so nothing describes it any more.
        transaction
            .execute(
                "DELETE FROM object_search WHERE rowid = ?1",
                [row_key(object_id)],
            )
            .map_err(|error| map_sqlite(error, "the search row could not be removed"))?;
        return Ok(());
    };
    transaction
        .execute("DELETE FROM object_search WHERE rowid = ?1", [rowid])
        .map_err(|error| map_sqlite(error, "the search row could not be replaced"))?;
    transaction
        .execute(
            "INSERT INTO object_search (rowid, filename, caption, tag_names)
             SELECT ?1,
                    COALESCE(m.original_filename, ''),
                    COALESCE(m.caption, ''),
                    COALESCE((SELECT group_concat(t.name, ' ')
                                FROM object_tags o JOIN tags t ON t.tag_id = o.tag_id
                               WHERE o.object_id = ?2), '')
               FROM metadata_revisions m
              WHERE m.object_id = ?2 AND m.active = 1",
            params![rowid, object_id.as_bytes().as_slice()],
        )
        .map_err(|error| map_sqlite(error, "the search row could not be written"))?;
    Ok(())
}

/// The FTS5 rowid of one object, or `None` when the object row is gone.
fn search_rowid(transaction: &Transaction<'_>, object_id: &Id) -> Result<Option<i64>> {
    let present: i64 = transaction
        .query_row(
            "SELECT count(*) FROM objects WHERE object_id = ?1",
            [object_id.as_bytes().as_slice()],
            |row| row.get(0),
        )
        .map_err(|error| map_sqlite(error, "the object could not be read"))?;
    Ok((present == 1).then(|| row_key(object_id)))
}

/// The stable FTS5 rowid of an object.
///
/// FTS5 keys on an `INTEGER` rowid, and an object is keyed on 16 opaque bytes,
/// so a mapping is unavoidable. The first eight bytes of the identifier are
/// used, big-endian, with the sign bit cleared so the value is positive. The
/// identifier is CSPRNG output, so a collision needs about 2^31 objects in one
/// vault by the birthday bound and the vault holds at most 10^6 under §21. The
/// `objects.search_key` column carries the same value under a `UNIQUE`
/// constraint, so the case the bound makes improbable is still refused rather
/// than served as a wrong page.
pub(crate) fn row_key(object_id: &Id) -> i64 {
    let bytes = object_id.as_bytes();
    let mut head = [0u8; 8];
    head.copy_from_slice(&bytes[..8]);
    #[expect(
        clippy::cast_possible_wrap,
        reason = "the sign bit is cleared, so the value is a positive i64"
    )]
    let value = (u64::from_be_bytes(head) & 0x7fff_ffff_ffff_ffff) as i64;
    value
}

fn count(transaction: &Transaction<'_>, sql: &str) -> Result<u64> {
    let value: i64 = transaction
        .query_row(sql, [], |row| row.get(0))
        .map_err(|error| map_sqlite(error, "a catalog count could not be read"))?;
    from_sqlite_integer(value, "a catalog count is negative")
}

fn count_with<P, I>(transaction: &Transaction<'_>, sql: &str, params: I) -> Result<u64>
where
    I: IntoIterator<Item = P>,
    P: rusqlite::ToSql,
{
    let value: i64 = transaction
        .query_row(sql, rusqlite::params_from_iter(params), |row| row.get(0))
        .map_err(|error| map_sqlite(error, "a catalog count could not be read"))?;
    from_sqlite_integer(value, "a catalog count is negative")
}

fn byte(value: i64, context: &'static str) -> Result<u8> {
    u8::try_from(value)
        .map_err(|_| chur_core::Error::new(chur_core::ChurStatus::CatalogCorrupt, context))
}

/// Rejects an object identifier the catalog does not hold.
pub fn require_object(db: &CatalogDb, object_id: &Id) -> Result<()> {
    let present: i64 = db
        .connection()
        .query_row(
            "SELECT count(*) FROM objects WHERE object_id = ?1",
            [object_id.as_bytes().as_slice()],
            |row| row.get(0),
        )
        .map_err(|error| map_sqlite(error, "the object could not be read"))?;
    if present == 0 {
        bail!(NotFound, "no object carries that id");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::panic, clippy::expect_used)]

    use super::*;
    use crate::db::{CatalogKey, CatalogLocation};
    use crate::model::{COLLECTION_POLICY_VAULT_DEFAULT, COLLECTION_STATUS_ACTIVE};
    use crate::schema::open_at_current_version;
    use chur_core::ChurStatus;
    use chur_crypto::{Key, random};
    use chur_format::constants::MediaClass;

    struct Fixture {
        db: CatalogDb,
        collection: Id,
    }

    fn fixture() -> Fixture {
        let root: Key = random::secret::<32>().expect("root");
        let vault = random::id().expect("id");
        let key = CatalogKey::derive(&root, &vault).expect("key");
        let mut db = CatalogDb::open(&CatalogLocation::Memory, &key).expect("open");
        open_at_current_version(&mut db, 1_700_000_000_000).expect("schema");
        let collection = random::id().expect("id");
        put_collection(
            &mut db,
            &Collection {
                collection_id: collection,
                current_epoch: 1,
                policy_type: COLLECTION_POLICY_VAULT_DEFAULT,
                created_revision: 1,
                status: COLLECTION_STATUS_ACTIVE,
            },
        )
        .expect("collection");
        Fixture { db, collection }
    }

    fn activation(fixture: &Fixture, capture_ms: u64, filename: &str) -> ObjectActivation {
        let object_id = random::id().expect("id");
        let stream_id = random::id().expect("id");
        ObjectActivation {
            object: Object {
                object_id,
                object_generation: 1,
                collection_id: fixture.collection,
                primary_stream_id: stream_id,
                media_kind: MediaClass::Image,
                capture_time_ms: capture_ms,
                import_time_ms: capture_ms + 1,
                capture_time_substituted: false,
                plaintext_size: 4_096,
                width: 4_000,
                height: 3_000,
                duration_ms: 0,
                favorite: false,
                state: ObjectState::Active,
                integrity_summary: IntegritySummary::Unverified,
                thumbnail_ready: false,
                active_metadata_revision: 1,
            },
            stream: Stream {
                stream_id,
                object_id,
                stream_kind: StreamKind::Original,
                stream_revision: 1,
                source_content_revision: 0,
                container_path_id: random::id().expect("id"),
                container_version: 1,
                suite_id: 1,
                ciphertext_size: 4_200,
                plaintext_size: 4_096,
                chunk_size: 262_144,
                complete_verified_ms: None,
                final_commitment: [7u8; 32],
            },
            envelope: vec![9u8; 142],
            envelope_generation: 1,
            metadata: MetadataRevision {
                object_id,
                revision: 1,
                active: true,
                record: vec![1u8; 64],
                original_filename: Some(String::from(filename)),
                caption: None,
                content_type: String::from("image/jpeg"),
                capture_time_ms: Some(capture_ms),
                width: 4_000,
                height: 3_000,
                duration_ms: 0,
            },
        }
    }

    fn rejection<T>(outcome: Result<T>) -> ChurStatus {
        let Err(error) = outcome else {
            panic!("the catalog accepted something the specification forbids");
        };
        error.status()
    }

    #[test]
    fn an_activation_commits_the_object_stream_envelope_and_revision_together() {
        let mut fixture = fixture();
        let activation = activation(&fixture, 1_700_000_000_000, "holiday.jpg");
        let object_id = activation.object.object_id;
        activate_object(&mut fixture.db, &activation).expect("activate");

        let stored = object(&fixture.db, &object_id).expect("object");
        assert_eq!(stored, activation.object);
        assert_eq!(streams(&fixture.db, &object_id).expect("streams").len(), 1);
        assert_eq!(
            active_envelope(&fixture.db, &object_id).expect("envelope"),
            activation.envelope
        );
        assert_eq!(
            active_metadata(&fixture.db, &object_id)
                .expect("metadata")
                .original_filename
                .as_deref(),
            Some("holiday.jpg")
        );
    }

    #[test]
    fn an_activation_that_names_two_objects_is_refused() {
        let mut fixture = fixture();
        let mut activation = activation(&fixture, 1, "a.jpg");
        activation.metadata.object_id = random::id().expect("id");
        assert_eq!(
            rejection(activate_object(&mut fixture.db, &activation)),
            ChurStatus::InvalidInput
        );
    }

    #[test]
    fn an_activation_rolls_back_whole_when_one_row_fails() {
        let mut fixture = fixture();
        let mut activation = activation(&fixture, 1, "a.jpg");
        // A collection the vault does not hold fails the foreign key, which is
        // the last statement of the transaction to run.
        activation.object.collection_id = random::id().expect("id");
        assert!(activate_object(&mut fixture.db, &activation).is_err());
        assert_eq!(
            rejection(object(&fixture.db, &activation.object.object_id)),
            ChurStatus::NotFound
        );
        let orphans: i64 = fixture
            .db
            .connection()
            .query_row("SELECT count(*) FROM object_streams", [], |row| row.get(0))
            .expect("count");
        assert_eq!(orphans, 0, "a stream survived a failed activation");
    }

    #[test]
    fn the_favourite_scope_and_its_sort_key_follow_the_flag() {
        let mut fixture = fixture();
        let activation = activation(&fixture, 1_700_000_000_000, "a.jpg");
        let object_id = activation.object.object_id;
        activate_object(&mut fixture.db, &activation).expect("activate");
        set_favorite(&mut fixture.db, &object_id, true, 5).expect("favourite");
        let stored: i64 = fixture
            .db
            .connection()
            .query_row(
                "SELECT capture_time_ms FROM favorites WHERE object_id = ?1",
                [object_id.as_bytes().as_slice()],
                |row| row.get(0),
            )
            .expect("row");
        assert_eq!(stored, 1_700_000_000_000);
        assert!(object(&fixture.db, &object_id).expect("object").favorite);
        set_favorite(&mut fixture.db, &object_id, false, 6).expect("unfavourite");
        let rows: i64 = fixture
            .db
            .connection()
            .query_row("SELECT count(*) FROM favorites", [], |row| row.get(0))
            .expect("count");
        assert_eq!(rows, 0);
    }

    #[test]
    fn a_new_revision_rewrites_every_duplicated_sort_key() {
        let mut fixture = fixture();
        let activation = activation(&fixture, 1_000, "a.jpg");
        let object_id = activation.object.object_id;
        activate_object(&mut fixture.db, &activation).expect("activate");
        set_favorite(&mut fixture.db, &object_id, true, 1).expect("favourite");
        let album_id = random::id().expect("id");
        put_album(
            &mut fixture.db,
            &Album {
                album_id,
                name: String::from("Holiday"),
                created_ms: 1,
                revision: 1,
            },
        )
        .expect("album");
        set_album_membership(&mut fixture.db, &album_id, &object_id, true, 1).expect("member");

        let mut revision = activation.metadata.clone();
        revision.revision = 2;
        revision.capture_time_ms = Some(9_000);
        revision.caption = Some(String::from("a caption"));
        activate_metadata_revision(&mut fixture.db, &revision).expect("activate revision");

        assert_eq!(
            object(&fixture.db, &object_id)
                .expect("object")
                .capture_time_ms,
            9_000
        );
        for table in ["favorites", "album_memberships"] {
            let stored: i64 = fixture
                .db
                .connection()
                .query_row(
                    &format!("SELECT capture_time_ms FROM {table} WHERE object_id = ?1"),
                    [object_id.as_bytes().as_slice()],
                    |row| row.get(0),
                )
                .expect("row");
            assert_eq!(stored, 9_000, "{table} kept a stale sort key");
        }
    }

    #[test]
    fn an_absent_capture_time_marks_the_row_substituted() {
        let mut fixture = fixture();
        let activation = activation(&fixture, 1_000, "a.jpg");
        let object_id = activation.object.object_id;
        activate_object(&mut fixture.db, &activation).expect("activate");
        let mut revision = activation.metadata.clone();
        revision.revision = 2;
        revision.capture_time_ms = None;
        activate_metadata_revision(&mut fixture.db, &revision).expect("activate revision");
        let stored = object(&fixture.db, &object_id).expect("object");
        assert!(stored.capture_time_substituted);
        assert_eq!(stored.capture_time_ms, 1_000, "the earlier time is kept");
    }

    #[test]
    fn corruption_is_a_lifecycle_change_and_is_terminal() {
        let mut fixture = fixture();
        let activation = activation(&fixture, 1, "a.jpg");
        let object_id = activation.object.object_id;
        activate_object(&mut fixture.db, &activation).expect("activate");
        mark_corrupt(&mut fixture.db, &object_id).expect("mark");
        assert_eq!(
            object(&fixture.db, &object_id).expect("object").state,
            ObjectState::Corrupt
        );
        assert_eq!(
            rejection(mark_corrupt(&mut fixture.db, &object_id)),
            ChurStatus::NotFound
        );
        assert_eq!(
            rejection(set_integrity_summary(
                &mut fixture.db,
                &object_id,
                IntegritySummary::CompleteVerified,
                1
            )),
            ChurStatus::NotFound,
            "a corrupt object still accepted a verification verdict"
        );
    }

    #[test]
    fn complete_verification_stamps_every_stream() {
        let mut fixture = fixture();
        let activation = activation(&fixture, 1, "a.jpg");
        let object_id = activation.object.object_id;
        activate_object(&mut fixture.db, &activation).expect("activate");
        set_integrity_summary(
            &mut fixture.db,
            &object_id,
            IntegritySummary::CompleteVerified,
            4_242,
        )
        .expect("verify");
        let stream = &streams(&fixture.db, &object_id).expect("streams")[0];
        assert_eq!(stream.complete_verified_ms, Some(4_242));
    }

    #[test]
    fn a_derived_asset_sets_the_thumbnail_flag_only_for_the_small_thumbnail() {
        let mut fixture = fixture();
        let activation = activation(&fixture, 1, "a.jpg");
        let object_id = activation.object.object_id;
        activate_object(&mut fixture.db, &activation).expect("activate");
        let mut stream = activation.stream.clone();
        stream.stream_id = random::id().expect("id");
        stream.stream_kind = StreamKind::GridPreview;
        stream.source_content_revision = 1;
        put_derived_asset(
            &mut fixture.db,
            &DerivedAsset {
                object_id,
                kind: StreamKind::GridPreview,
                source_content_revision: 1,
                asset_revision: 1,
                generator_profile: 1,
                stream_id: stream.stream_id,
            },
            &stream,
        )
        .expect("preview");
        assert!(
            !object(&fixture.db, &object_id)
                .expect("object")
                .thumbnail_ready
        );

        let mut thumbnail = stream.clone();
        thumbnail.stream_id = random::id().expect("id");
        thumbnail.stream_kind = StreamKind::ThumbnailSmall;
        put_derived_asset(
            &mut fixture.db,
            &DerivedAsset {
                object_id,
                kind: StreamKind::ThumbnailSmall,
                source_content_revision: 1,
                asset_revision: 1,
                generator_profile: 1,
                stream_id: thumbnail.stream_id,
            },
            &thumbnail,
        )
        .expect("thumbnail");
        assert!(
            object(&fixture.db, &object_id)
                .expect("object")
                .thumbnail_ready
        );
    }

    #[test]
    fn the_active_envelope_bound_of_section_21_is_enforced() {
        let mut fixture = fixture();
        let activation = activation(&fixture, 1, "a.jpg");
        let object_id = activation.object.object_id;
        activate_object(&mut fixture.db, &activation).expect("activate");
        for generation in 2..=4 {
            fixture
                .db
                .transaction(|transaction| {
                    insert_envelope_row(
                        transaction,
                        &object_id,
                        generation,
                        ENVELOPE_STATUS_ACTIVE,
                        &[0u8; 142],
                    )
                })
                .expect("an envelope inside the bound");
        }
        let outcome = fixture.db.transaction(|transaction| {
            insert_envelope_row(
                transaction,
                &object_id,
                5,
                ENVELOPE_STATUS_ACTIVE,
                &[0u8; 142],
            )
        });
        assert_eq!(rejection(outcome), ChurStatus::ResourceLimitExceeded);
    }

    #[test]
    fn the_tags_per_object_bound_of_section_21_is_enforced() {
        let mut fixture = fixture();
        let activation = activation(&fixture, 1, "a.jpg");
        let object_id = activation.object.object_id;
        activate_object(&mut fixture.db, &activation).expect("activate");
        let mut ids = Vec::new();
        for index in 0..129 {
            let tag_id = random::id().expect("id");
            put_tag(
                &mut fixture.db,
                &Tag {
                    tag_id,
                    name: format!("tag-{index}"),
                    created_ms: 1,
                },
            )
            .expect("tag");
            ids.push(tag_id);
        }
        for tag_id in ids.iter().take(128) {
            set_object_tag(&mut fixture.db, tag_id, &object_id, true).expect("tag the object");
        }
        assert_eq!(
            rejection(set_object_tag(&mut fixture.db, &ids[128], &object_id, true)),
            ChurStatus::ResourceLimitExceeded
        );
    }

    #[test]
    fn the_search_index_follows_the_filename_the_caption_and_the_tags() {
        let mut fixture = fixture();
        let activation = activation(&fixture, 1, "Bäckerei.jpg");
        let object_id = activation.object.object_id;
        activate_object(&mut fixture.db, &activation).expect("activate");

        let hits = |query: &str, db: &CatalogDb| -> i64 {
            db.connection()
                .query_row(
                    "SELECT count(*) FROM object_search WHERE object_search MATCH ?1",
                    [query],
                    |row| row.get(0),
                )
                .expect("search")
        };
        assert_eq!(hits("backerei", &fixture.db), 1, "diacritics are folded");
        assert_eq!(hits("holiday", &fixture.db), 0);

        let tag_id = random::id().expect("id");
        put_tag(
            &mut fixture.db,
            &Tag {
                tag_id,
                name: String::from("holiday"),
                created_ms: 1,
            },
        )
        .expect("tag");
        set_object_tag(&mut fixture.db, &tag_id, &object_id, true).expect("apply");
        assert_eq!(hits("holiday", &fixture.db), 1, "a tag is indexed");

        let mut revision = activation.metadata.clone();
        revision.revision = 2;
        revision.caption = Some(String::from("Kaffee und Kuchen"));
        activate_metadata_revision(&mut fixture.db, &revision).expect("revision");
        assert_eq!(hits("kuchen", &fixture.db), 1, "a caption is indexed");
        assert_eq!(
            hits("holiday", &fixture.db),
            1,
            "the tag survived a revision"
        );

        set_object_tag(&mut fixture.db, &tag_id, &object_id, false).expect("untag");
        assert_eq!(
            hits("holiday", &fixture.db),
            0,
            "a removed tag is unindexed"
        );
    }

    #[test]
    fn every_write_that_changes_a_page_advances_the_generation() {
        let mut fixture = fixture();
        let start = crate::schema::generation(&fixture.db).expect("generation");
        let activation = activation(&fixture, 1, "a.jpg");
        activate_object(&mut fixture.db, &activation).expect("activate");
        assert_eq!(
            crate::schema::generation(&fixture.db).expect("generation"),
            start + 1
        );
        set_favorite(&mut fixture.db, &activation.object.object_id, true, 1).expect("favourite");
        assert_eq!(
            crate::schema::generation(&fixture.db).expect("generation"),
            start + 2
        );
    }

    #[test]
    fn a_membership_of_an_absent_object_is_not_found() {
        let mut fixture = fixture();
        let album_id = random::id().expect("id");
        put_album(
            &mut fixture.db,
            &Album {
                album_id,
                name: String::from("Holiday"),
                created_ms: 1,
                revision: 1,
            },
        )
        .expect("album");
        assert_eq!(
            rejection(set_album_membership(
                &mut fixture.db,
                &album_id,
                &random::id().expect("id"),
                true,
                1
            )),
            ChurStatus::NotFound
        );
    }
}
