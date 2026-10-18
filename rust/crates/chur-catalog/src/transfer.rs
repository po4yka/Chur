//! Logical selection for independent encrypted archives. No key or sync rows are copied.
use crate::{
    CatalogDb,
    db::{as_sqlite_integer, map_sqlite},
    model::Album,
    notes, schema, store,
};
use chur_core::{Id, Result, ensure};
use rusqlite::params;
use std::collections::{BTreeMap, BTreeSet};
/// One selected album and its directly selected live members in manual order.
pub struct SelectedAlbum {
    /// Display metadata and original tree position.
    pub listing: store::AlbumListing,
    /// Live original identities, never copied into the destination catalog.
    pub members: Vec<Id>,
}
/// Bounded logical snapshot of a selection.
pub struct Plan {
    /// Source generation; any concurrent edit invalidates the selection.
    pub generation: u64,
    /// Deduplicated live originals.
    pub objects: Vec<Id>,
    /// Selected album subtree metadata only.
    pub albums: Vec<SelectedAlbum>,
    /// Expected note revisions.
    pub notes: Vec<(Id, u64)>,
}
/// Resolves selected roots and deduplicates overlapping membership.
pub fn select(
    db: &CatalogDb,
    objects: &[Id],
    album_ids: &[Id],
    note_ids: &[(Id, u64)],
) -> Result<Plan> {
    let all = store::albums(db)?;
    let mut selected: BTreeSet<Id> = album_ids.iter().copied().collect();
    ensure!(
        selected
            .iter()
            .all(|id| all.iter().any(|a| a.album.album_id == *id)),
        NotFound,
        "a selected album does not exist"
    );
    loop {
        let before = selected.len();
        for a in &all {
            if a.parent_id.is_some_and(|id| selected.contains(&id)) {
                selected.insert(a.album.album_id);
            }
        }
        ensure!(
            selected.len() <= 1000,
            ResourceLimitExceeded,
            "the selected album tree exceeds its bound"
        );
        if selected.len() == before {
            break;
        }
    }
    let mut ids: BTreeSet<Id> = objects.iter().copied().collect();
    let mut albums = Vec::new();
    for listing in all
        .into_iter()
        .filter(|a| selected.contains(&a.album.album_id))
    {
        let mut st=db.connection().prepare("SELECT m.object_id FROM album_memberships m JOIN objects o ON o.object_id=m.object_id WHERE m.album_id=?1 AND o.state=1 ORDER BY m.sort_position,m.object_id")
          .map_err(|e|map_sqlite(e,"the selected album could not be read"))?;
        let rows = st
            .query_map([listing.album.album_id.as_bytes().as_slice()], |r| {
                r.get::<_, Vec<u8>>(0)
            })
            .map_err(|e| map_sqlite(e, "the selected album could not be read"))?;
        let mut members = Vec::new();
        for row in rows {
            let id = Id::from_slice(
                &row.map_err(|e| map_sqlite(e, "the selected membership could not be read"))?,
            )?;
            ids.insert(id);
            members.push(id);
            ensure!(
                ids.len() <= 10000,
                ResourceLimitExceeded,
                "the selected originals exceed their bound"
            );
        }
        albums.push(SelectedAlbum { listing, members });
    }
    ensure!(
        ids.len() <= 10000 && note_ids.len() <= 10000,
        ResourceLimitExceeded,
        "the selected records exceed their bound"
    );
    for id in &ids {
        ensure!(
            store::object(db, id)?.state == chur_format::constants::ObjectState::Active,
            NotFound,
            "a selected original is not active"
        );
    }
    let mut unique = BTreeMap::new();
    for (id, revision) in note_ids {
        let n = notes::get(db, id)?;
        ensure!(
            n.revision == *revision && n.deleted_ms.is_none(),
            Conflict,
            "a selected note changed"
        );
        if let Some(old) = unique.insert(*id, *revision) {
            ensure!(
                old == *revision,
                Conflict,
                "a note has conflicting selected revisions"
            );
        }
    }
    ensure!(
        !ids.is_empty() || !albums.is_empty() || !unique.is_empty(),
        InvalidInput,
        "the archive selection is empty"
    );
    Ok(Plan {
        generation: schema::generation(db)?,
        objects: ids.into_iter().collect(),
        albums,
        notes: unique.into_iter().collect(),
    })
}
/// Selects all active logical content from a fully authenticated private staging vault.
pub fn all(db: &CatalogDb) -> Result<Plan> {
    let mut objects = Vec::new();
    let mut st = db
        .connection()
        .prepare("SELECT object_id FROM objects WHERE state=1 ORDER BY object_id")
        .map_err(|e| map_sqlite(e, "the archive originals could not be read"))?;
    let rows = st
        .query_map([], |r| r.get::<_, Vec<u8>>(0))
        .map_err(|e| map_sqlite(e, "the archive originals could not be read"))?;
    for row in rows {
        objects.push(Id::from_slice(&row.map_err(|e| {
            map_sqlite(e, "the archive original could not be read")
        })?)?);
        ensure!(
            objects.len() <= 10000,
            ResourceLimitExceeded,
            "the archive has too many originals"
        );
    }
    let mut note_ids = Vec::new();
    let mut st = db
        .connection()
        .prepare(
            "SELECT note_id,revision FROM private_notes WHERE deleted_ms IS NULL ORDER BY note_id",
        )
        .map_err(|e| map_sqlite(e, "the archive notes could not be read"))?;
    let rows = st
        .query_map([], |r| Ok((r.get::<_, Vec<u8>>(0)?, r.get::<_, i64>(1)?)))
        .map_err(|e| map_sqlite(e, "the archive notes could not be read"))?;
    for row in rows {
        let (id, revision) =
            row.map_err(|e| map_sqlite(e, "the archive note could not be read"))?;
        note_ids.push((
            Id::from_slice(&id)?,
            u64::try_from(revision)
                .map_err(|_| chur_core::err!(CatalogCorrupt, "the note revision is invalid"))?,
        ));
        ensure!(
            note_ids.len() <= 10000,
            ResourceLimitExceeded,
            "the archive has too many notes"
        );
    }
    let albums = store::albums(db)?
        .into_iter()
        .map(|a| a.album.album_id)
        .collect::<Vec<_>>();
    if objects.is_empty() && albums.is_empty() && note_ids.is_empty() {
        return Ok(Plan {
            generation: schema::generation(db)?,
            objects,
            albums: Vec::new(),
            notes: note_ids,
        });
    }
    select(db, &objects, &albums, &note_ids)
}
/// Adds a note under a fresh id and revision, atomically with its display times and search row.
pub fn copy_note(db: &mut CatalogDb, source: &notes::Note) -> Result<Id> {
    ensure!(
        source.title.len() <= 256
            && source.body.len() <= 262144
            && !source.title.contains('\0')
            && !source.body.contains('\0'),
        InvalidInput,
        "the archive note exceeds its text bounds"
    );
    let created = as_sqlite_integer(source.created_ms, "the archive note time is invalid")?;
    let updated = as_sqlite_integer(source.updated_ms, "the archive note time is invalid")?;
    let id = chur_crypto::random::id()?;
    db.transaction(|tx|{
        let count:i64=tx.query_row("SELECT count(*) FROM private_notes",[],|r|r.get(0)).map_err(|e|map_sqlite(e,"the note count could not be read"))?;ensure!(count<10000,ResourceLimitExceeded,"the private note count limit was reached");
        tx.execute("INSERT INTO private_notes(note_id,revision,title,body,created_ms,updated_ms,pinned,format) VALUES(?1,1,?2,?3,?4,?5,?6,?7)",params![id.as_bytes().as_slice(),&source.title,&source.body,created,updated,source.pinned,source.format as u8]).map_err(|e|map_sqlite(e,"the archive note could not be imported"))?;
        tx.execute("INSERT INTO private_note_search(rowid,title,body) SELECT rowid,title,body FROM private_notes WHERE note_id=?1",[id.as_bytes().as_slice()]).map_err(|e|map_sqlite(e,"the note search row could not be imported"))?;schema::bump_generation(tx)?;Ok(id)
    })
}
/// Creates an album under a fresh identity. Names never overwrite an existing album.
pub fn copy_album(db: &mut CatalogDb, source: &Album) -> Result<Id> {
    let id = chur_crypto::random::id()?;
    store::put_album(
        db,
        &Album {
            album_id: id,
            name: source.name.clone(),
            created_ms: source.created_ms,
            revision: 1,
        },
    )?;
    Ok(id)
}

/// Reuses an exact-name destination tag, or creates a fresh identity without rewriting existing tags.
pub fn copy_tag(db: &mut CatalogDb, source: &crate::model::Tag) -> Result<Id> {
    use rusqlite::OptionalExtension;
    source.check()?;
    let created = as_sqlite_integer(source.created_ms, "the archive tag time is invalid")?;
    db.transaction(|tx| {
        let existing: Option<Vec<u8>> = tx
            .query_row(
                "SELECT tag_id FROM tags WHERE name=?1",
                [source.name.as_str()],
                |r| r.get(0),
            )
            .optional()
            .map_err(|e| map_sqlite(e, "the destination tag could not be read"))?;
        if let Some(bytes) = existing {
            return Id::from_slice(&bytes);
        }
        let count: i64 = tx
            .query_row("SELECT count(*) FROM tags", [], |r| r.get(0))
            .map_err(|e| map_sqlite(e, "the destination tag count could not be read"))?;
        ensure!(
            u64::try_from(count).is_ok_and(|count| count < chur_core::limits::catalog::TAGS_MAX),
            ResourceLimitExceeded,
            "the destination tag count limit was reached"
        );
        let id = chur_crypto::random::id()?;
        tx.execute(
            "INSERT INTO tags(tag_id,name,created_ms) VALUES(?1,?2,?3)",
            params![id.as_bytes().as_slice(), source.name, created],
        )
        .map_err(|e| map_sqlite(e, "the archive tag could not be imported"))?;
        schema::bump_generation(tx)?;
        Ok(id)
    })
}
