//! Rebuilds the unlocked sync key directory from catalog key envelopes.

use crate::CatalogDb;
use crate::db::{from_sqlite_integer, map_sqlite};
use crate::model::ENVELOPE_STATUS_ACTIVE;
use chur_core::{ChurStatus, Error, Id, Result, ensure};
use chur_crypto::Key;
use chur_format::envelope::CollectionKeyEnvelope;
use chur_sync_protocol::{KeyDirectory, KeyDomain};

/// Derives root and retained collection-epoch routing for one unlocked vault.
pub fn key_directory(db: &CatalogDb, root: &Key, vault_id: Id) -> Result<KeyDirectory> {
    let mut directory = KeyDirectory::new(root, &vault_id)?;
    let mut statement = db
        .connection()
        .prepare(
            "SELECT collection_id, collection_epoch, generation, body
               FROM collection_key_envelopes WHERE status = ?1
               ORDER BY collection_id, collection_epoch, generation DESC",
        )
        .map_err(|error| map_sqlite(error, "collection envelopes could not be prepared"))?;
    let rows = statement
        .query_map([i64::from(ENVELOPE_STATUS_ACTIVE)], |row| {
            Ok((
                row.get::<_, Vec<u8>>(0)?,
                row.get::<_, i64>(1)?,
                row.get::<_, i64>(2)?,
                row.get::<_, Vec<u8>>(3)?,
            ))
        })
        .map_err(|error| map_sqlite(error, "collection envelopes could not be read"))?;
    let mut previous = None;
    for row in rows {
        let (collection, epoch, generation, body) =
            row.map_err(|error| map_sqlite(error, "a collection envelope could not be read"))?;
        let collection_id = crate::row::id(&collection, "a collection id is malformed")?;
        let epoch = from_sqlite_integer(epoch, "a collection epoch is negative")?;
        let generation = from_sqlite_integer(generation, "an envelope generation is negative")?;
        ensure!(
            previous != Some((collection_id, epoch)),
            CatalogCorrupt,
            "a collection epoch has multiple active envelopes"
        );
        previous = Some((collection_id, epoch));
        let envelope = CollectionKeyEnvelope::decode(&body).map_err(corrupt_envelope)?;
        ensure!(
            envelope.vault_id() == &vault_id
                && envelope.collection_id() == &collection_id
                && envelope.collection_epoch() == epoch
                && envelope.envelope_generation() == generation,
            CatalogCorrupt,
            "a collection envelope contradicts its catalog row"
        );
        let collection_key = envelope.open(root).map_err(corrupt_envelope)?;
        directory.insert(KeyDomain::collection(
            &collection_key,
            &collection_id,
            epoch,
        )?)?;
    }
    Ok(directory)
}

fn corrupt_envelope(_: Error) -> Error {
    Error::new(
        ChurStatus::CatalogCorrupt,
        "a collection key envelope is invalid",
    )
}
