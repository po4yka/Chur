//! The deletion transaction and garbage collection.
//!
//! `docs/format/CATALOG_SCHEMA_V1.md` §14.1 fixes the order and this module
//! runs it and no other:
//!
//! 1. one catalog transaction sets `state` to `DELETING`, [`begin`];
//! 2. one catalog transaction destroys every object-key envelope, writes the
//!    tombstone, and sets `state` to `TOMBSTONED`, [`erase`];
//! 3. unlink the derived-asset containers;
//! 4. unlink the original container;
//! 5. delete the object's scratch entries;
//! 6. delete the object row, leaving the tombstone.
//!
//! Steps 1 and 2 are the atomic boundary §17 requires. Steps 3 to 6 are
//! garbage collection: each is idempotent, none is required for the
//! crypto-erasure claim of SEC-026, and a crash inside them loses no security
//! property.
//!
//! Steps 3 and 4 unlink files, which the catalog does not own. [`sweep`]
//! therefore returns the opaque path identifiers and [`finish`] runs steps 5
//! and 6 once the caller reports the unlinks done. A caller that never reports
//! leaves the object at `TOMBSTONED`, which the next sweep picks up again.

use chur_core::{Id, Result, ensure};
use chur_format::constants::ObjectState;
use rusqlite::{Transaction, params};

use crate::db::{CatalogDb, as_sqlite_integer, map_sqlite};
use crate::schema::bump_generation;

/// What garbage collection must still do for one object, §14.1.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Pending {
    /// The object.
    pub object_id: Id,
    /// Whether step 2 has already committed.
    ///
    /// `docs/format/CATALOG_SCHEMA_V1.md` §14.1 makes this the whole recovery
    /// question: an object whose envelopes are gone is past the erasure moment
    /// and never returns to `ACTIVE`, whatever its `state` column says.
    pub erased: bool,
    /// The opaque store identifiers of the containers steps 3 and 4 unlink, the
    /// derived assets first.
    pub containers: Vec<Id>,
}

/// What recovery does with a half-deleted object, §14.1.
///
/// Recovery rolls forward and never back, because rolling back would return to
/// `ACTIVE` an object whose key may already be gone.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Rollforward {
    /// `DELETING` with envelopes present: step 2 never committed, so run it now.
    Erase,
    /// The envelopes are already destroyed: finish steps 3 to 6.
    Collect,
}

impl Pending {
    /// The step this object resumes at.
    #[must_use]
    pub const fn rollforward(&self) -> Rollforward {
        if self.erased {
            Rollforward::Collect
        } else {
            Rollforward::Erase
        }
    }
}

/// Step 1: the object stops being listable.
pub fn begin(db: &mut CatalogDb, object_id: &Id) -> Result<()> {
    let object_id = *object_id;
    db.transaction(|transaction| {
        let changed = transaction
            .execute(
                "UPDATE objects SET state = ?2 WHERE object_id = ?1 AND state = ?3",
                params![
                    object_id.as_bytes().as_slice(),
                    i64::from(ObjectState::Deleting.value()),
                    i64::from(ObjectState::Active.value()),
                ],
            )
            .map_err(|error| map_sqlite(error, "the object could not enter deletion"))?;
        ensure!(changed == 1, NotFound, "no active object carries that id");
        // The object is no longer listable, so it is no longer searchable.
        unindex_search(transaction, &object_id)?;
        bump_generation(transaction)
    })
}

/// Step 2: the erasure moment.
///
/// Once this transaction commits durably the `ContentKey` is unrecoverable from
/// this vault's live state, and every copy of the container that state reaches,
/// including WAL pages and queued sync operations, is ciphertext no reachable
/// key opens.
///
/// It is idempotent: re-running it on an object whose envelopes are already
/// gone commits the same end state, which is what lets recovery roll forward
/// without first deciding whether the earlier attempt got there.
pub fn erase(db: &mut CatalogDb, object_id: &Id, now_ms: u64) -> Result<()> {
    let object_id = *object_id;
    let authored = as_sqlite_integer(now_ms, "the time is out of range")?;
    db.transaction(|transaction| {
        let generation: i64 = transaction
            .query_row(
                "SELECT object_generation FROM objects WHERE object_id = ?1",
                [object_id.as_bytes().as_slice()],
                |row| row.get(0),
            )
            .map_err(|error| map_sqlite(error, "the object could not be read"))?;
        transaction
            .execute(
                "DELETE FROM object_key_envelopes WHERE object_id = ?1",
                [object_id.as_bytes().as_slice()],
            )
            .map_err(|error| map_sqlite(error, "the key envelopes could not be destroyed"))?;
        transaction
            .execute(
                "INSERT INTO tombstones (object_id, authored_ms, object_generation)
                 VALUES (?1, ?2, ?3)
                 ON CONFLICT(object_id) DO NOTHING",
                params![object_id.as_bytes().as_slice(), authored, generation],
            )
            .map_err(|error| map_sqlite(error, "the tombstone could not be written"))?;
        transaction
            .execute(
                "UPDATE objects SET state = ?2 WHERE object_id = ?1",
                params![
                    object_id.as_bytes().as_slice(),
                    i64::from(ObjectState::Tombstoned.value())
                ],
            )
            .map_err(|error| map_sqlite(error, "the object could not be tombstoned"))?;
        unindex_search(transaction, &object_id)?;
        bump_generation(transaction)
    })
}

/// Steps 5 and 6: the scratch entries and the object row.
///
/// The tombstone survives. `docs/format/CATALOG_SCHEMA_V1.md` §14 lets a vault
/// with no enrolled peer discard it afterwards, which is [`discard_tombstone`].
pub fn finish(db: &mut CatalogDb, object_id: &Id) -> Result<()> {
    let object_id = *object_id;
    db.transaction(|transaction| {
        let key = object_id.as_bytes().as_slice();
        // Step 5.
        transaction
            .execute("DELETE FROM scratch_entries WHERE object_id = ?1", [key])
            .map_err(|error| map_sqlite(error, "the scratch entries could not be deleted"))?;
        // Step 6. The child rows go first because the schema declares plain
        // references rather than cascades: a cascade would delete a child on
        // any future path that removed an object row, and §14.1 wants exactly
        // one such path.
        for statement in [
            "DELETE FROM derived_assets WHERE object_id = ?1",
            "DELETE FROM object_streams WHERE object_id = ?1",
            "DELETE FROM metadata_revisions WHERE object_id = ?1",
            "DELETE FROM album_memberships WHERE object_id = ?1",
            "DELETE FROM favorites WHERE object_id = ?1",
            "DELETE FROM object_tags WHERE object_id = ?1",
            "DELETE FROM integrity_records WHERE object_id = ?1",
            "DELETE FROM object_key_envelopes WHERE object_id = ?1",
            "DELETE FROM objects WHERE object_id = ?1",
        ] {
            transaction
                .execute(statement, [key])
                .map_err(|error| map_sqlite(error, "a deleted object row could not be removed"))?;
        }
        unindex_search(transaction, &object_id)?;
        bump_generation(transaction)
    })
}

/// Discards a tombstone under the peerless retention rule of §14.
///
/// It applies only in a vault with no enrolled peer device, because nothing
/// local can resurrect an object whose row, envelopes, and containers are all
/// gone. Every other vault follows the membership rule of
/// `docs/sync/OPERATION_LOG.md` §11, and v1 enrols no peer.
pub fn discard_tombstone(db: &mut CatalogDb, object_id: &Id) -> Result<()> {
    let object_id = *object_id;
    db.transaction(|transaction| {
        let live: i64 = transaction
            .query_row(
                "SELECT count(*) FROM objects WHERE object_id = ?1",
                [object_id.as_bytes().as_slice()],
                |row| row.get(0),
            )
            .map_err(|error| map_sqlite(error, "the object could not be read"))?;
        ensure!(
            live == 0,
            Conflict,
            "a tombstone is discarded only after its object row is gone"
        );
        transaction
            .execute(
                "DELETE FROM tombstones WHERE object_id = ?1",
                [object_id.as_bytes().as_slice()],
            )
            .map_err(|error| map_sqlite(error, "the tombstone could not be discarded"))?;
        Ok(())
    })
}

/// Every object garbage collection must still act on, §14.1.
///
/// A sweep runs at the first unlock of a session and again after each deletion
/// that session performs, so an interrupted deletion always completes rather
/// than being repaired.
pub fn sweep(db: &CatalogDb) -> Result<Vec<Pending>> {
    let connection = db.connection();
    let mut statement = connection
        .prepare(
            "SELECT o.object_id,
                    (SELECT count(*) FROM object_key_envelopes e WHERE e.object_id = o.object_id)
               FROM objects o
              WHERE o.state IN (?1, ?2)
              ORDER BY o.object_id",
        )
        .map_err(|error| map_sqlite(error, "the sweep query could not be prepared"))?;
    let rows = statement
        .query_map(
            params![
                i64::from(ObjectState::Deleting.value()),
                i64::from(ObjectState::Tombstoned.value()),
            ],
            |row| Ok((row.get::<_, Vec<u8>>(0)?, row.get::<_, i64>(1)?)),
        )
        .map_err(|error| map_sqlite(error, "the sweep could not be read"))?;
    let mut pending = Vec::new();
    for row in rows {
        let (object_id, envelopes) =
            row.map_err(|error| map_sqlite(error, "a sweep row could not be read"))?;
        let object_id = crate::row::id(&object_id, "the object id is malformed")?;
        pending.push(Pending {
            object_id,
            erased: envelopes == 0,
            containers: containers_of(db, &object_id)?,
        });
    }
    Ok(pending)
}

/// The opaque container identifiers of one object, derived assets first.
///
/// §14.1 unlinks the derived-asset containers at step 3 and the original at
/// step 4, so the order this returns is the order the caller unlinks in.
fn containers_of(db: &CatalogDb, object_id: &Id) -> Result<Vec<Id>> {
    let connection = db.connection();
    let mut statement = connection
        .prepare(
            "SELECT container_path_id FROM object_streams
              WHERE object_id = ?1
              ORDER BY (stream_kind = 1), stream_kind",
        )
        .map_err(|error| map_sqlite(error, "the container query could not be prepared"))?;
    let rows = statement
        .query_map([object_id.as_bytes().as_slice()], |row| {
            row.get::<_, Vec<u8>>(0)
        })
        .map_err(|error| map_sqlite(error, "the containers could not be read"))?;
    let mut containers = Vec::new();
    for row in rows {
        let bytes = row.map_err(|error| map_sqlite(error, "a container row could not be read"))?;
        containers.push(crate::row::id(
            &bytes,
            "the container path id is malformed",
        )?);
    }
    Ok(containers)
}

/// Whether a container found in the committed namespace should be deleted.
///
/// §14.1 last bullet: a container with no object row and no `ImportTransaction`
/// row is deleted. The import-temporary case belongs to
/// `docs/format/OBJECT_CONTAINER_V1.md` §14.4 and is not this decision.
pub fn is_orphan_container(db: &CatalogDb, container_path_id: &Id) -> Result<bool> {
    let key = container_path_id.as_bytes().as_slice();
    let streams: i64 = db
        .connection()
        .query_row(
            "SELECT count(*) FROM object_streams WHERE container_path_id = ?1",
            [key],
            |row| row.get(0),
        )
        .map_err(|error| map_sqlite(error, "the streams could not be read"))?;
    let imports: i64 = db
        .connection()
        .query_row(
            "SELECT count(*) FROM import_transactions WHERE temp_path_id = ?1",
            [key],
            |row| row.get(0),
        )
        .map_err(|error| map_sqlite(error, "the journal could not be read"))?;
    Ok(streams == 0 && imports == 0)
}

/// Removes one object from the search index.
fn unindex_search(transaction: &Transaction<'_>, object_id: &Id) -> Result<()> {
    transaction
        .execute(
            "DELETE FROM object_search WHERE rowid = ?1",
            [crate::store::row_key(object_id)],
        )
        .map_err(|error| map_sqlite(error, "the search row could not be removed"))?;
    Ok(())
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::panic, clippy::expect_used)]

    use super::*;
    use crate::db::{CatalogKey, CatalogLocation};
    use crate::model::{
        COLLECTION_POLICY_VAULT_DEFAULT, COLLECTION_STATUS_ACTIVE, Collection, MetadataRevision,
        Object, Stream,
    };
    use crate::query::{ObjectQuery, Scope, page};
    use crate::schema::open_at_current_version;
    use crate::store;
    use chur_core::ChurStatus;
    use chur_crypto::{Key, random};
    use chur_format::constants::{IntegritySummary, MediaClass, StreamKind};

    struct Vault {
        db: CatalogDb,
        collection: Id,
    }

    fn vault() -> Vault {
        let root: Key = random::secret::<32>().expect("root");
        let vault_id = random::id().expect("id");
        let key = CatalogKey::derive(&root, &vault_id).expect("key");
        let mut db = CatalogDb::open(&CatalogLocation::Memory, &key).expect("open");
        open_at_current_version(&mut db, 1).expect("schema");
        let collection = random::id().expect("id");
        store::put_collection(
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
        Vault { db, collection }
    }

    fn import(vault: &mut Vault) -> (Id, Id) {
        let object_id = random::id().expect("id");
        let stream_id = random::id().expect("id");
        let container = random::id().expect("id");
        store::activate_object(
            &mut vault.db,
            &store::ObjectActivation {
                object: Object {
                    object_id,
                    object_generation: 1,
                    collection_id: vault.collection,
                    primary_stream_id: stream_id,
                    media_kind: MediaClass::Image,
                    capture_time_ms: 1_000,
                    import_time_ms: 1_001,
                    capture_time_substituted: false,
                    plaintext_size: 4_096,
                    width: 100,
                    height: 100,
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
                    container_path_id: container,
                    container_version: 1,
                    suite_id: 1,
                    ciphertext_size: 4_200,
                    plaintext_size: 4_096,
                    chunk_size: 262_144,
                    complete_verified_ms: None,
                    final_commitment: [0u8; 32],
                },
                envelope: vec![1u8; 142],
                envelope_generation: 1,
                metadata: MetadataRevision {
                    object_id,
                    revision: 1,
                    active: true,
                    record: vec![0u8; 32],
                    original_filename: Some(String::from("holiday.jpg")),
                    caption: None,
                    content_type: String::from("image/jpeg"),
                    capture_time_ms: Some(1_000),
                    width: 100,
                    height: 100,
                    duration_ms: 0,
                },
            },
        )
        .expect("activate");
        (object_id, container)
    }

    fn listed(db: &CatalogDb) -> usize {
        page(db, &ObjectQuery::timeline())
            .expect("page")
            .objects
            .len()
    }

    fn searchable(db: &CatalogDb) -> usize {
        page(
            db,
            &ObjectQuery {
                scope: Scope::Search(String::from("holiday")),
                ..ObjectQuery::timeline()
            },
        )
        .expect("page")
        .objects
        .len()
    }

    fn rejection<T>(outcome: Result<T>) -> ChurStatus {
        let Err(error) = outcome else {
            panic!("deletion accepted something the specification forbids");
        };
        error.status()
    }

    #[test]
    fn step_one_stops_the_object_being_listable_and_searchable() {
        let mut vault = vault();
        let (object_id, _) = import(&mut vault);
        assert_eq!(listed(&vault.db), 1);
        assert_eq!(searchable(&vault.db), 1);
        begin(&mut vault.db, &object_id).expect("begin");
        assert_eq!(listed(&vault.db), 0);
        assert_eq!(searchable(&vault.db), 0);
        assert_eq!(
            store::object(&vault.db, &object_id).expect("object").state,
            ObjectState::Deleting
        );
    }

    #[test]
    fn step_two_destroys_every_envelope_and_writes_the_tombstone() {
        let mut vault = vault();
        let (object_id, _) = import(&mut vault);
        begin(&mut vault.db, &object_id).expect("begin");
        erase(&mut vault.db, &object_id, 4_242).expect("erase");
        assert_eq!(
            rejection(store::active_envelope(&vault.db, &object_id)),
            ChurStatus::NotFound,
            "an envelope survived the erasure moment"
        );
        let authored: i64 = vault
            .db
            .connection()
            .query_row(
                "SELECT authored_ms FROM tombstones WHERE object_id = ?1",
                [object_id.as_bytes().as_slice()],
                |row| row.get(0),
            )
            .expect("tombstone");
        assert_eq!(authored, 4_242);
        assert_eq!(
            store::object(&vault.db, &object_id).expect("object").state,
            ObjectState::Tombstoned
        );
    }

    #[test]
    fn the_whole_sequence_leaves_only_the_tombstone() {
        let mut vault = vault();
        let (object_id, container) = import(&mut vault);
        begin(&mut vault.db, &object_id).expect("begin");
        erase(&mut vault.db, &object_id, 1).expect("erase");
        let pending = sweep(&vault.db).expect("sweep");
        assert_eq!(pending.len(), 1);
        assert_eq!(pending[0].containers, vec![container]);
        assert_eq!(pending[0].rollforward(), Rollforward::Collect);
        finish(&mut vault.db, &object_id).expect("finish");

        assert_eq!(
            rejection(store::object(&vault.db, &object_id)),
            ChurStatus::NotFound
        );
        assert!(sweep(&vault.db).expect("sweep").is_empty());
        let tombstones: i64 = vault
            .db
            .connection()
            .query_row("SELECT count(*) FROM tombstones", [], |row| row.get(0))
            .expect("count");
        assert_eq!(tombstones, 1, "the tombstone was discarded too early");
        for table in [
            "object_streams",
            "metadata_revisions",
            "object_key_envelopes",
            "derived_assets",
        ] {
            let rows: i64 = vault
                .db
                .connection()
                .query_row(&format!("SELECT count(*) FROM {table}"), [], |row| {
                    row.get(0)
                })
                .expect("count");
            assert_eq!(rows, 0, "{table} kept a row of a purged object");
        }
    }

    #[test]
    fn recovery_from_step_one_rolls_forward_and_never_back() {
        let mut vault = vault();
        let (object_id, _) = import(&mut vault);
        begin(&mut vault.db, &object_id).expect("begin");
        let pending = sweep(&vault.db).expect("sweep");
        assert_eq!(
            pending[0].rollforward(),
            Rollforward::Erase,
            "envelopes are present, so step 2 never committed"
        );
        assert!(!pending[0].erased);
        erase(&mut vault.db, &object_id, 1).expect("run step 2 now");
        assert_eq!(
            sweep(&vault.db).expect("sweep")[0].rollforward(),
            Rollforward::Collect
        );
    }

    #[test]
    fn an_object_whose_envelopes_are_gone_never_returns_to_active() {
        let mut vault = vault();
        let (object_id, _) = import(&mut vault);
        begin(&mut vault.db, &object_id).expect("begin");
        erase(&mut vault.db, &object_id, 1).expect("erase");
        // Nothing offers a path back, and the one function that sets ACTIVE is
        // activation, which inserts rather than updates.
        assert_eq!(
            rejection(begin(&mut vault.db, &object_id)),
            ChurStatus::NotFound
        );
        assert_eq!(
            rejection(store::set_favorite(&mut vault.db, &object_id, true, 1)),
            ChurStatus::NotFound
        );
    }

    #[test]
    fn every_garbage_collection_step_is_idempotent() {
        let mut vault = vault();
        let (object_id, _) = import(&mut vault);
        begin(&mut vault.db, &object_id).expect("begin");
        erase(&mut vault.db, &object_id, 1).expect("erase");
        erase(&mut vault.db, &object_id, 2).expect("erase again");
        finish(&mut vault.db, &object_id).expect("finish");
        finish(&mut vault.db, &object_id).expect("finish again");
        discard_tombstone(&mut vault.db, &object_id).expect("discard");
        discard_tombstone(&mut vault.db, &object_id).expect("discard again");
        let tombstones: i64 = vault
            .db
            .connection()
            .query_row("SELECT count(*) FROM tombstones", [], |row| row.get(0))
            .expect("count");
        assert_eq!(tombstones, 0);
    }

    #[test]
    fn a_tombstone_is_not_discarded_while_its_object_row_exists() {
        let mut vault = vault();
        let (object_id, _) = import(&mut vault);
        begin(&mut vault.db, &object_id).expect("begin");
        erase(&mut vault.db, &object_id, 1).expect("erase");
        assert_eq!(
            rejection(discard_tombstone(&mut vault.db, &object_id)),
            ChurStatus::Conflict
        );
    }

    #[test]
    fn a_sweep_returns_the_derived_containers_before_the_original() {
        let mut vault = vault();
        let (object_id, original) = import(&mut vault);
        let mut thumbnail = store::streams(&vault.db, &object_id).expect("streams")[0].clone();
        thumbnail.stream_id = random::id().expect("id");
        thumbnail.stream_kind = StreamKind::ThumbnailSmall;
        thumbnail.source_content_revision = 1;
        thumbnail.container_path_id = random::id().expect("id");
        let derived = thumbnail.container_path_id;
        store::put_derived_asset(
            &mut vault.db,
            &crate::model::DerivedAsset {
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
        begin(&mut vault.db, &object_id).expect("begin");
        let pending = sweep(&vault.db).expect("sweep");
        assert_eq!(
            pending[0].containers,
            vec![derived, original],
            "§14.1 unlinks the derived containers at step 3 and the original at step 4"
        );
    }

    #[test]
    fn a_container_with_no_row_and_no_journal_record_is_an_orphan() {
        let mut vault = vault();
        let (_, container) = import(&mut vault);
        assert!(!is_orphan_container(&vault.db, &container).expect("check"));
        let stranger = random::id().expect("id");
        assert!(is_orphan_container(&vault.db, &stranger).expect("check"));

        let mut journal = crate::journal::ImportTransaction {
            transaction_id: random::id().expect("id"),
            temp_path_id: stranger,
            object_id: random::id().expect("id"),
            stream_id: random::id().expect("id"),
            stream_kind: StreamKind::Original,
            stream_revision: 1,
            envelope_generation: 1,
            envelope_body: Some(vec![0u8; 142]),
            nonce_prefix: [0u8; 16],
            chunk_size: 262_144,
            manifest_length: 117,
            reserved_index: None,
            expected_length: None,
            source_seekable: true,
            stage: crate::journal::Stage::Opening,
            opened_ms: 1,
        };
        crate::journal::open(&mut vault.db, &journal).expect("open");
        assert!(
            !is_orphan_container(&vault.db, &stranger).expect("check"),
            "a container an import owns is not an orphan"
        );
        journal.temp_path_id = stranger;
    }

    #[test]
    fn a_deleting_object_is_returned_by_no_query_scope() {
        let mut vault = vault();
        let (object_id, _) = import(&mut vault);
        store::set_favorite(&mut vault.db, &object_id, true, 1).expect("favourite");
        begin(&mut vault.db, &object_id).expect("begin");
        for scope in [Scope::Timeline, Scope::Favorites, Scope::Quarantine] {
            let result = page(
                &vault.db,
                &ObjectQuery {
                    scope,
                    ..ObjectQuery::timeline()
                },
            )
            .expect("page");
            assert!(result.objects.is_empty());
        }
    }
}
