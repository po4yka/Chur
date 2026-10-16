//! Bounded, rebuildable local content text. SQLCipher owns every durable byte.
use crate::{CatalogDb, db::map_sqlite, schema, store};
use chur_core::{Id, Result, ensure};
use chur_format::constants::{MediaClass, ObjectState};
use rusqlite::{OptionalExtension, params};

/// Maximum UTF-8 content per object.
pub const TEXT_MAX: usize = 262_144;
/// Version of the platform text extraction contract.
pub const ENGINE_VERSION: u32 = 1;
/// An original to read through the authenticated reader. It contains no text.
pub struct Candidate {
    /// Vault-local object identity.
    pub object_id: Id,
    /// Source binding checked again when text is committed.
    pub primary_stream_id: Id,
    /// Canonical media kind.
    pub media_class: MediaClass,
    /// Authenticated original size.
    pub plaintext_size: u64,
    /// Authenticated metadata type.
    pub content_type: String,
}
fn supported(kind: MediaClass, mime: &str) -> bool {
    kind == MediaClass::Image
        || (kind == MediaClass::Opaque
            && (mime.starts_with("image/")
                || mime == "application/pdf"
                || mime.starts_with("text/")
                || matches!(mime, "application/json" | "application/xml")))
}
/// Stable keyset enumeration, independent of changes to catalog generation.
/// Complete and partial records both count as indexed; a rebuild replaces either.
pub fn candidates(
    db: &CatalogDb,
    after: Option<&Id>,
    limit: u32,
    rebuild: bool,
) -> Result<Vec<Candidate>> {
    ensure!(
        (1..=100).contains(&limit),
        InvalidInput,
        "the content page size is out of range"
    );
    let mut statement=db.connection().prepare("SELECT o.object_id,o.primary_stream_id,o.media_kind,o.plaintext_size,m.content_type FROM objects o JOIN metadata_revisions m ON m.object_id=o.object_id AND m.active=1 LEFT JOIN content_index c ON c.object_id=o.object_id AND c.primary_stream_id=o.primary_stream_id AND c.engine_version=1 WHERE o.state=1 AND o.integrity_summary<>6 AND o.object_id>?1 AND (?2 OR c.object_id IS NULL) AND (o.media_kind=1 OR (o.media_kind=4 AND (m.content_type LIKE 'image/%' OR m.content_type='application/pdf' OR m.content_type LIKE 'text/%' OR m.content_type IN ('application/json','application/xml')))) ORDER BY o.object_id LIMIT ?3").map_err(|e|map_sqlite(e,"the content candidates could not be queried"))?;
    let rows = statement
        .query_map(
            params![
                after.map_or(&[0u8; 16], Id::as_bytes).as_slice(),
                rebuild,
                limit
            ],
            |r| {
                let object: Vec<u8> = r.get(0)?;
                let stream: Vec<u8> = r.get(1)?;
                let kind: u8 = r.get(2)?;
                Ok(Candidate {
                    object_id: Id::from_slice(&object)
                        .map_err(|_| rusqlite::Error::InvalidQuery)?,
                    primary_stream_id: Id::from_slice(&stream)
                        .map_err(|_| rusqlite::Error::InvalidQuery)?,
                    media_class: MediaClass::from_value(kind)
                        .ok_or(rusqlite::Error::InvalidQuery)?,
                    plaintext_size: u64::try_from(r.get::<_, i64>(3)?)
                        .map_err(|_| rusqlite::Error::InvalidQuery)?,
                    content_type: r.get(4)?,
                })
            },
        )
        .map_err(|e| map_sqlite(e, "the content candidates could not be read"))?;
    rows.map(|row| row.map_err(|e| map_sqlite(e, "a content candidate is invalid")))
        .collect()
}
/// Commits only bounded text from the current active original. A metadata edit
/// does not discard content; an original replacement cannot accept stale OCR.
pub fn put(
    db: &mut CatalogDb,
    object: &Id,
    expected_stream: &Id,
    status: u8,
    engine_version: u32,
    text: &str,
) -> Result<()> {
    ensure!(
        text.len() <= TEXT_MAX,
        ResourceLimitExceeded,
        "the content text exceeds its bound"
    );
    ensure!(
        matches!(status, 1 | 2) && engine_version == ENGINE_VERSION,
        InvalidInput,
        "the content extraction version or status is unsupported"
    );
    ensure!(
        !text.contains('\0'),
        InvalidInput,
        "the content text contains a null character"
    );
    db.transaction(|tx| {
        let source = tx
            .query_row(
                "SELECT o.primary_stream_id,o.state,o.media_kind,m.content_type,o.integrity_summary
             FROM objects o JOIN metadata_revisions m ON m.object_id=o.object_id AND m.active=1
             WHERE o.object_id=?1",
                [object.as_bytes().as_slice()],
                |row| {
                    Ok((
                        row.get::<_, Vec<u8>>(0)?,
                        row.get::<_, u8>(1)?,
                        row.get::<_, u8>(2)?,
                        row.get::<_, String>(3)?,
                        row.get::<_, u8>(4)?,
                    ))
                },
            )
            .optional()
            .map_err(|error| map_sqlite(error, "the content source could not be checked"))?;
        let Some((stream, state, kind, mime, integrity)) = source else {
            return Err(chur_core::err!(
                NotFound,
                "the content source does not exist"
            ));
        };
        ensure!(
            state == ObjectState::Active.value()
                && integrity != 6
                && stream == expected_stream.as_bytes(),
            Conflict,
            "the content source changed"
        );
        let kind = MediaClass::from_value(kind)
            .ok_or_else(|| chur_core::err!(CatalogCorrupt, "the content media kind is invalid"))?;
        ensure!(
            supported(kind, &mime),
            InvalidInput,
            "the content source type is unsupported"
        );
        tx.execute(
            "INSERT INTO content_index(object_id,primary_stream_id,engine_version,status,body)
             VALUES(?1,?2,?3,?4,?5) ON CONFLICT(object_id) DO UPDATE SET
             primary_stream_id=excluded.primary_stream_id,engine_version=excluded.engine_version,
             status=excluded.status,body=excluded.body",
            params![
                object.as_bytes().as_slice(),
                expected_stream.as_bytes().as_slice(),
                engine_version,
                status,
                text
            ],
        )
        .map_err(|error| map_sqlite(error, "the content text could not be stored"))?;
        store::reindex_search(tx, object)?;
        schema::bump_generation(tx)
    })
}

/// Clears the derived text and rebuilds metadata search in one transaction.
/// WAL truncation removes previous text pages before success is reported.
pub fn clear(db: &mut CatalogDb) -> Result<()> {
    db.transaction(|tx| {
        tx.execute("DELETE FROM content_index",[]).map_err(|e|map_sqlite(e,"the content index could not be cleared"))?;
        tx.execute("DELETE FROM object_search",[]).map_err(|e|map_sqlite(e,"the content search could not be cleared"))?;
        tx.execute("INSERT INTO object_search(rowid,filename,caption,tag_names,content_body) SELECT o.search_key,COALESCE(m.original_filename,''),COALESCE(m.caption,''),COALESCE((SELECT group_concat(t.name,' ') FROM object_tags ot JOIN tags t ON t.tag_id=ot.tag_id WHERE ot.object_id=o.object_id),''),'' FROM objects o JOIN metadata_revisions m ON m.object_id=o.object_id AND m.active=1 WHERE o.state=1",[]).map_err(|e|map_sqlite(e,"the metadata search could not be rebuilt"))?;
        schema::bump_generation(tx)
    })?;
    db.checkpoint()
}
