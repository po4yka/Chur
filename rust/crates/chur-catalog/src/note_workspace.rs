//! Encrypted organization and bounded revision recovery for private notes.
use crate::{
    CatalogDb,
    db::{as_sqlite_integer, map_sqlite},
    note_attachments::{self as files, Attachment},
    notes::{self, Note, NoteFormat},
    schema,
};
use chur_core::{Id, Result, ensure};
use rusqlite::{Connection, OptionalExtension, params};
use zeroize::{Zeroize, Zeroizing};

/// Maximum retained previous revisions per note.
pub const HISTORY_MAX: usize = 20;
/// Maximum deduplicated historical attachment bytes per note.
pub const HISTORY_BYTES_MAX: usize = 64 * 1024 * 1024;
/// Explicit organization, scoped to a note in this unlocked vault.
#[derive(Default, PartialEq, Eq)]
pub struct Organization {
    /// Empty means no folder; otherwise a single trimmed label of at most 64 UTF-8 bytes.
    pub folder: String,
    /// At most sixteen sorted, distinct trimmed labels, each at most 64 UTF-8 bytes.
    pub tags: Vec<String>,
}
impl Drop for Organization {
    fn drop(&mut self) {
        self.folder.zeroize();
        self.tags.zeroize();
    }
}
fn valid_label(value: &str) -> bool {
    value.len() <= 64
        && !value.is_empty()
        && value.trim() == value
        && !value.chars().any(char::is_control)
}
/// Normalizes and validates user-entered labels without discarding distinct metadata.
pub fn organization(folder: &str, tags: &[&str]) -> Result<Organization> {
    let folder = folder.trim();
    ensure!(
        folder.is_empty() || valid_label(folder),
        InvalidInput,
        "the note folder is invalid"
    );
    ensure!(
        tags.len() <= 16,
        ResourceLimitExceeded,
        "the note has too many tags"
    );
    let mut normalized = Vec::with_capacity(tags.len());
    for tag in tags {
        let tag = tag.trim();
        if tag.is_empty() {
            continue;
        }
        ensure!(valid_label(tag), InvalidInput, "a note tag is invalid");
        normalized.push(tag.to_owned());
    }
    normalized.sort();
    normalized.dedup();
    Ok(Organization {
        folder: folder.to_owned(),
        tags: normalized,
    })
}
fn decode_organization(folder: String, tags: String) -> Result<Organization> {
    ensure!(
        folder.len() <= 64 && tags.len() <= 4096,
        CatalogCorrupt,
        "the stored note organization exceeds its bounds"
    );
    let decoded: Zeroizing<Vec<String>> = Zeroizing::new(
        serde_json::from_str(&tags)
            .map_err(|_| chur_core::err!(CatalogCorrupt, "the stored note tags are invalid"))?,
    );
    ensure!(
        decoded.len() <= 16
            && (folder.is_empty() || valid_label(&folder))
            && decoded.iter().all(|s| valid_label(s)),
        CatalogCorrupt,
        "the stored note labels are invalid"
    );
    let refs = decoded.iter().map(String::as_str).collect::<Vec<_>>();
    let result = organization(&folder, &refs)?;
    ensure!(
        result.tags == *decoded,
        CatalogCorrupt,
        "the stored note tags are not canonical"
    );
    Ok(result)
}
fn organization_conn(c: &Connection, id: &Id) -> Result<Organization> {
    let row:Option<(String,String)>=c.query_row("SELECT CASE WHEN length(CAST(folder AS BLOB))<=64 THEN folder END,CASE WHEN length(CAST(tags AS BLOB))<=4096 THEN tags END FROM private_note_organization WHERE note_id=?1",[id.as_bytes().as_slice()],|r|Ok((r.get(0)?,r.get(1)?))).optional().map_err(|e|map_sqlite(e,"the note organization could not be read"))?;
    row.map_or_else(
        || Ok(Organization::default()),
        |(f, t)| decode_organization(f, t),
    )
}
/// Reads organization, including for a note retained in Trash.
pub fn get_organization(db: &CatalogDb, id: &Id) -> Result<Organization> {
    notes::get(db, id)?;
    organization_conn(db.connection(), id)
}
fn put_organization(c: &Connection, id: &Id, value: &Organization) -> Result<()> {
    let tags = Zeroizing::new(
        serde_json::to_string(&value.tags)
            .map_err(|_| chur_core::err!(InternalFailure, "the note tags could not be encoded"))?,
    );
    c.execute("INSERT INTO private_note_organization(note_id,folder,tags) VALUES(?1,?2,?3) ON CONFLICT(note_id) DO UPDATE SET folder=excluded.folder,tags=excluded.tags",params![id.as_bytes().as_slice(),value.folder,tags.as_str()]).map_err(|e|map_sqlite(e,"the note organization could not be written"))?;
    Ok(())
}
/// Commits organization under the same optimistic revision as content and files.
pub fn set_organization(
    db: &mut CatalogDb,
    id: &Id,
    expected: u64,
    folder: &str,
    tags: &[&str],
    now: u64,
) -> Result<Note> {
    let value = organization(folder, tags)?;
    let time = as_sqlite_integer(now, "the note time is invalid")?;
    db.transaction(|tx|{
  let old=active(tx,id,expected)?;
  if organization_conn(tx,id)?==value {return Ok(old)}
  snapshot(tx,id)?; put_organization(tx,id,&value)?;
  tx.execute("UPDATE private_notes SET revision=revision+1,updated_ms=max(updated_ms,?2) WHERE note_id=?1",params![id.as_bytes().as_slice(),time]).map_err(|e|map_sqlite(e,"the note organization revision could not be written"))?;
  schema::bump_generation(tx)?; notes::get_conn(tx,id)
 })
}
fn active(c: &Connection, id: &Id, expected: u64) -> Result<Note> {
    let n = notes::get_conn(c, id)?;
    ensure!(
        n.revision == expected && n.deleted_ms.is_none(),
        Conflict,
        "the private note changed"
    );
    ensure!(
        expected < i64::MAX as u64,
        ResourceLimitExceeded,
        "the note revision is exhausted"
    );
    Ok(n)
}
fn clean_blobs(c: &Connection, id: &Id) -> Result<()> {
    c.execute("DELETE FROM private_note_history_blobs WHERE note_id=?1 AND blob_id NOT IN (SELECT blob_id FROM private_note_history_files WHERE note_id=?1)",[id.as_bytes().as_slice()]).map_err(|e|map_sqlite(e,"unused historical files could not be erased"))?;
    Ok(())
}
/// Captures current source and file identities inside the caller's mutation transaction.
/// Equal file bytes reuse one encrypted historical BLOB, including across text edits.
pub(crate) fn snapshot(c: &Connection, id: &Id) -> Result<()> {
    let n = notes::get_conn(c, id)?;
    let org = organization_conn(c, id)?;
    let tags = Zeroizing::new(
        serde_json::to_string(&org.tags)
            .map_err(|_| chur_core::err!(InternalFailure, "the note tags could not be encoded"))?,
    );
    c.execute("INSERT OR IGNORE INTO private_note_history(note_id,revision,title,body,format,updated_ms,pinned,folder,tags) VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9)",params![id.as_bytes().as_slice(),n.revision as i64,n.title,n.body,n.format as u8,n.updated_ms as i64,n.pinned,org.folder,tags.as_str()]).map_err(|e|map_sqlite(e,"the note revision could not be retained"))?;
    // Validate bounded metadata before allowing SQLite to copy its associated payloads.
    let all = files::list_conn(c, id)?;
    for a in all {
        let existing:Option<Vec<u8>>=c.query_row("SELECT b.blob_id FROM private_note_history_blobs b JOIN private_note_attachments a ON a.note_id=b.note_id AND a.data=b.data WHERE a.note_id=?1 AND a.attachment_id=?2 LIMIT 1",params![id.as_bytes().as_slice(),a.id.as_bytes().as_slice()],|r|r.get(0)).optional().map_err(|e|map_sqlite(e,"the historical file identity could not be read"))?;
        let blob = if let Some(bytes) = existing {
            Id::from_slice(&bytes)?
        } else {
            let blob = chur_crypto::random::id()?;
            c.execute("INSERT INTO private_note_history_blobs(note_id,blob_id,data) SELECT note_id,?3,data FROM private_note_attachments WHERE note_id=?1 AND attachment_id=?2",params![id.as_bytes().as_slice(),a.id.as_bytes().as_slice(),blob.as_bytes().as_slice()]).map_err(|e|map_sqlite(e,"the historical file could not be retained"))?;
            blob
        };
        c.execute("INSERT OR IGNORE INTO private_note_history_files(note_id,revision,attachment_id,name,mime_type,blob_id) VALUES(?1,?2,?3,?4,?5,?6)",params![id.as_bytes().as_slice(),n.revision as i64,a.id.as_bytes().as_slice(),a.name,a.mime_type,blob.as_bytes().as_slice()]).map_err(|e|map_sqlite(e,"the historical file reference could not be retained"))?;
    }
    loop {
        let (count,bytes):(i64,i64)=c.query_row("SELECT (SELECT count(*) FROM private_note_history WHERE note_id=?1),(SELECT coalesce(sum(length(data)),0) FROM private_note_history_blobs WHERE note_id=?1)",[id.as_bytes().as_slice()],|r|Ok((r.get(0)?,r.get(1)?))).map_err(|e|map_sqlite(e,"the history retention bounds could not be read"))?;
        if count <= HISTORY_MAX as i64 && bytes <= HISTORY_BYTES_MAX as i64 {
            break;
        }
        c.execute("DELETE FROM private_note_history WHERE note_id=?1 AND revision=(SELECT min(revision) FROM private_note_history WHERE note_id=?1)",[id.as_bytes().as_slice()]).map_err(|e|map_sqlite(e,"the oldest note revision could not be removed"))?;
        clean_blobs(c, id)?;
    }
    clean_blobs(c, id)
}
/// One retained source revision, with its own exact file metadata.
pub struct Revision {
    /// Prior committed source and format. Trash state belongs to the current note.
    pub note: Note,
    /// Organization saved with this version.
    pub organization: Organization,
    /// Metadata for files recoverable with this version.
    pub attachments: Vec<Attachment>,
}
fn history_files(c: &Connection, id: &Id, revision: u64) -> Result<Vec<Attachment>> {
    let mut s=c.prepare("SELECT f.attachment_id,CASE WHEN length(CAST(f.name AS BLOB))<=240 THEN f.name END,CASE WHEN length(CAST(f.mime_type AS BLOB))<=128 THEN f.mime_type END,length(b.data) FROM private_note_history_files f LEFT JOIN private_note_history_blobs b ON b.note_id=f.note_id AND b.blob_id=f.blob_id WHERE f.note_id=?1 AND f.revision=?2 ORDER BY f.attachment_id LIMIT 33").map_err(|e|map_sqlite(e,"historical files could not be listed"))?;
    let rows = s
        .query_map(
            params![
                id.as_bytes().as_slice(),
                as_sqlite_integer(revision, "the revision is invalid")?
            ],
            |r| {
                Ok((
                    r.get::<_, Vec<u8>>(0)?,
                    r.get::<_, String>(1)?,
                    r.get::<_, String>(2)?,
                    r.get::<_, i64>(3)?,
                ))
            },
        )
        .map_err(|e| map_sqlite(e, "historical files could not be read"))?;
    let mut result = Vec::new();
    for row in rows {
        let (aid, name, mime, size) =
            row.map_err(|e| map_sqlite(e, "a historical file is invalid"))?;
        ensure!(
            files::valid_name(&name)
                && files::supported_mime(&mime)
                && (0..=files::FILE_MAX as i64).contains(&size),
            CatalogCorrupt,
            "historical file metadata is invalid"
        );
        result.push(Attachment {
            id: Id::from_slice(&aid)?,
            name,
            mime_type: mime,
            size: size as u64,
        });
    }
    ensure!(
        result.len() <= files::COUNT_MAX
            && result.iter().map(|a| a.size).sum::<u64>() <= files::TOTAL_MAX as u64,
        CatalogCorrupt,
        "historical files exceed their bound"
    );
    Ok(result)
}
fn revision_conn(c: &Connection, id: &Id, revision: u64) -> Result<Revision> {
    let current = notes::get_conn(c, id)?;
    let value:Option<(String,String,u8,i64,bool,String,String)>=c.query_row("SELECT CASE WHEN length(CAST(title AS BLOB))<=256 THEN title END,CASE WHEN length(CAST(body AS BLOB))<=262144 THEN body END,format,updated_ms,pinned,CASE WHEN length(CAST(folder AS BLOB))<=64 THEN folder END,CASE WHEN length(CAST(tags AS BLOB))<=4096 THEN tags END FROM private_note_history WHERE note_id=?1 AND revision=?2",params![id.as_bytes().as_slice(),as_sqlite_integer(revision,"the revision is invalid")?],|r|Ok((r.get(0)?,r.get(1)?,r.get(2)?,r.get(3)?,r.get(4)?,r.get(5)?,r.get(6)?))).optional().map_err(|e|map_sqlite(e,"the historical note could not be read"))?;
    let (title, body, format, time, pinned, folder, tags) =
        value.ok_or_else(|| chur_core::err!(NotFound, "the note revision no longer exists"))?;
    ensure!(
        revision > 0
            && revision < current.revision
            && time >= 0
            && !title.contains('\0')
            && !body.contains('\0'),
        CatalogCorrupt,
        "the historical source is invalid"
    );
    let organization = decode_organization(folder, tags)?;
    let attachments = history_files(c, id, revision)?;
    Ok(Revision {
        note: Note {
            id: *id,
            revision,
            title,
            body,
            format: NoteFormat::try_from(format).map_err(|_| {
                chur_core::err!(CatalogCorrupt, "the historical note format is invalid")
            })?,
            attachment_count: attachments.len() as u32,
            created_ms: current.created_ms,
            updated_ms: time as u64,
            pinned,
            deleted_ms: None,
        },
        organization,
        attachments,
    })
}
/// Reads one version without changing current content or its revision.
pub fn get_revision(db: &CatalogDb, id: &Id, revision: u64) -> Result<Revision> {
    revision_conn(db.connection(), id, revision)
}
/// Lists at most twenty retained revisions, newest first.
pub fn history(db: &CatalogDb, id: &Id) -> Result<Vec<Revision>> {
    notes::get(db, id)?;
    let mut s=db.connection().prepare("SELECT revision FROM private_note_history WHERE note_id=?1 ORDER BY revision DESC LIMIT 21").map_err(|e|map_sqlite(e,"note history could not be listed"))?;
    let revisions = s
        .query_map([id.as_bytes().as_slice()], |r| r.get::<_, i64>(0))
        .map_err(|e| map_sqlite(e, "note history could not be read"))?
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(|e| map_sqlite(e, "note history is invalid"))?;
    ensure!(
        revisions.len() <= HISTORY_MAX,
        CatalogCorrupt,
        "the stored note history exceeds its bound"
    );
    revisions
        .into_iter()
        .map(|r| {
            ensure!(r > 0, CatalogCorrupt, "the historical revision is invalid");
            revision_conn(db.connection(), id, r as u64)
        })
        .collect()
}
/// Reads bounded original bytes from one retained version without exporting them.
pub fn read_revision_file(
    db: &CatalogDb,
    id: &Id,
    revision: u64,
    attachment: &Id,
) -> Result<Zeroizing<Vec<u8>>> {
    let rev = get_revision(db, id, revision)?;
    ensure!(
        rev.attachments.iter().any(|a| a.id == *attachment),
        NotFound,
        "the historical attachment does not exist"
    );
    let bytes:Vec<u8>=db.connection().query_row("SELECT CASE WHEN length(b.data)<=8388608 THEN b.data END FROM private_note_history_files f JOIN private_note_history_blobs b ON b.note_id=f.note_id AND b.blob_id=f.blob_id WHERE f.note_id=?1 AND f.revision=?2 AND f.attachment_id=?3",params![id.as_bytes().as_slice(),revision as i64,attachment.as_bytes().as_slice()],|r|r.get(0)).map_err(|e|map_sqlite(e,"the historical attachment could not be read"))?;
    Ok(Zeroizing::new(bytes))
}
/// Restores exact source, organization, and files as a fresh revision. The displaced state is retained.
pub fn restore(
    db: &mut CatalogDb,
    id: &Id,
    expected: u64,
    revision: u64,
    now: u64,
) -> Result<Note> {
    let time = as_sqlite_integer(now, "the note time is invalid")?;
    db.transaction(|tx|{
  active(tx,id,expected)?;let prior=revision_conn(tx,id,revision)?;
  // Keep the selected files alive even if retention evicts its metadata during this transaction.
  let staging:Vec<(Attachment,Zeroizing<Vec<u8>>)>=prior.attachments.iter().map(|a|{
   let bytes:Vec<u8>=tx.query_row("SELECT CASE WHEN length(b.data)<=8388608 THEN b.data END FROM private_note_history_files f JOIN private_note_history_blobs b ON b.note_id=f.note_id AND b.blob_id=f.blob_id WHERE f.note_id=?1 AND f.revision=?2 AND f.attachment_id=?3",params![id.as_bytes().as_slice(),revision as i64,a.id.as_bytes().as_slice()],|r|r.get(0)).map_err(|e|map_sqlite(e,"the historical attachment could not be restored"))?;
   Ok((Attachment{id:a.id,name:a.name.clone(),mime_type:a.mime_type.clone(),size:a.size},Zeroizing::new(bytes)))
  }).collect::<Result<_>>()?;
  snapshot(tx,id)?;
  tx.execute("DELETE FROM private_note_attachments WHERE note_id=?1",[id.as_bytes().as_slice()]).map_err(|e|map_sqlite(e,"the current files could not be replaced"))?;
  for (a,bytes) in staging {ensure!(a.mime_type==files::mime_type(&bytes),CatalogCorrupt,"historical attachment bytes disagree with metadata");tx.execute("INSERT INTO private_note_attachments(note_id,attachment_id,name,mime_type,data) VALUES(?1,?2,?3,?4,?5)",params![id.as_bytes().as_slice(),a.id.as_bytes().as_slice(),a.name,a.mime_type,bytes.as_slice()]).map_err(|e|map_sqlite(e,"the original file could not be restored"))?;}
  put_organization(tx,id,&prior.organization)?;
  tx.execute("UPDATE private_notes SET title=?2,body=?3,format=?4,pinned=?5,revision=revision+1,updated_ms=max(updated_ms,?6) WHERE note_id=?1",params![id.as_bytes().as_slice(),prior.note.title,prior.note.body,prior.note.format as u8,prior.note.pinned,time]).map_err(|e|map_sqlite(e,"the note version could not be restored"))?;
  notes::reindex(tx,id)?;schema::bump_generation(tx)?;notes::get_conn(tx,id)
 })
}
/// Bounded logical workspace carried by an independent encrypted archive.
/// Each unique historical file is owned once, regardless of snapshot count.
pub struct Archive {
    organization: Organization,
    revisions: Vec<(Revision, Vec<Id>)>,
    blobs: Vec<(Id, Zeroizing<Vec<u8>>)>,
}
/// Authenticates the logical history and copies at most 64 MiB of unique file bytes.
pub fn archive(db: &CatalogDb, id: &Id) -> Result<Archive> {
    let organization = get_organization(db, id)?;
    let mut revisions = Vec::new();
    let mut unique = std::collections::BTreeSet::new();
    for revision in history(db, id)? {
        let mut refs = Vec::with_capacity(revision.attachments.len());
        for a in &revision.attachments {
            let bytes:Vec<u8>=db.connection().query_row("SELECT blob_id FROM private_note_history_files WHERE note_id=?1 AND revision=?2 AND attachment_id=?3",params![id.as_bytes().as_slice(),revision.note.revision as i64,a.id.as_bytes().as_slice()],|r|r.get(0)).map_err(|e|map_sqlite(e,"the historical archive reference could not be read"))?;
            let bid = Id::from_slice(&bytes)?;
            unique.insert(bid);
            refs.push(bid);
        }
        revisions.push((revision, refs));
    }
    let mut total = 0usize;
    let mut blobs = Vec::with_capacity(unique.len());
    for blob in unique {
        let (size,data):(i64,Option<Vec<u8>>)=db.connection().query_row("SELECT length(data),CASE WHEN length(data)<=8388608 THEN data END FROM private_note_history_blobs WHERE note_id=?1 AND blob_id=?2",params![id.as_bytes().as_slice(),blob.as_bytes().as_slice()],|r|Ok((r.get(0)?,r.get(1)?))).map_err(|e|map_sqlite(e,"the historical archive file could not be read"))?;
        ensure!(
            (0..=files::FILE_MAX as i64).contains(&size),
            CatalogCorrupt,
            "the historical archive file exceeds its bound"
        );
        total = total.checked_add(size as usize).ok_or_else(|| {
            chur_core::err!(CatalogCorrupt, "the historical archive total is invalid")
        })?;
        ensure!(
            total <= HISTORY_BYTES_MAX,
            CatalogCorrupt,
            "historical archive files exceed their total bound"
        );
        blobs.push((
            blob,
            Zeroizing::new(data.ok_or_else(|| {
                chur_core::err!(CatalogCorrupt, "the historical archive file is absent")
            })?),
        ));
    }
    Ok(Archive {
        organization,
        revisions,
        blobs,
    })
}
/// Copies validated metadata and history in the same transaction as its new note.
/// Revisions receive a fresh monotonic sequence in the destination note namespace.
pub(crate) fn copy_archive(c: &Connection, id: &Id, workspace: &Archive) -> Result<()> {
    ensure!(
        workspace.revisions.len() <= HISTORY_MAX
            && workspace.blobs.iter().map(|(_, b)| b.len()).sum::<usize>() <= HISTORY_BYTES_MAX,
        ResourceLimitExceeded,
        "the archived note history exceeds its bound"
    );
    put_organization(c, id, &workspace.organization)?;
    for (blob, data) in &workspace.blobs {
        ensure!(
            data.len() <= files::FILE_MAX,
            ResourceLimitExceeded,
            "the archived historical file exceeds its bound"
        );
        c.execute(
            "INSERT INTO private_note_history_blobs(note_id,blob_id,data) VALUES(?1,?2,?3)",
            params![
                id.as_bytes().as_slice(),
                blob.as_bytes().as_slice(),
                data.as_slice()
            ],
        )
        .map_err(|e| map_sqlite(e, "the archived historical file could not be saved"))?;
    }
    for (index, (rev, refs)) in workspace.revisions.iter().rev().enumerate() {
        let revision = index as i64 + 1;
        let tags = Zeroizing::new(serde_json::to_string(&rev.organization.tags).map_err(|_| {
            chur_core::err!(InternalFailure, "the archived tags could not be encoded")
        })?);
        c.execute("INSERT INTO private_note_history(note_id,revision,title,body,format,updated_ms,pinned,folder,tags) VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9)",params![id.as_bytes().as_slice(),revision,rev.note.title,rev.note.body,rev.note.format as u8,as_sqlite_integer(rev.note.updated_ms,"the archived revision time is invalid")?,rev.note.pinned,rev.organization.folder,tags.as_str()]).map_err(|e|map_sqlite(e,"the archived source revision could not be saved"))?;
        for (a, blob) in rev.attachments.iter().zip(refs) {
            let (_, data) = workspace
                .blobs
                .iter()
                .find(|(bid, _)| bid == blob)
                .ok_or_else(|| {
                    chur_core::err!(CatalogCorrupt, "the archived historical file is missing")
                })?;
            ensure!(
                a.size == data.len() as u64 && a.mime_type == files::mime_type(data),
                CatalogCorrupt,
                "the archived historical file metadata disagrees with its bytes"
            );
            c.execute("INSERT INTO private_note_history_files(note_id,revision,attachment_id,name,mime_type,blob_id) VALUES(?1,?2,?3,?4,?5,?6)",params![id.as_bytes().as_slice(),revision,a.id.as_bytes().as_slice(),a.name,a.mime_type,blob.as_bytes().as_slice()]).map_err(|e|map_sqlite(e,"the archived historical file reference could not be saved"))?;
        }
    }
    c.execute(
        "UPDATE private_notes SET revision=?2 WHERE note_id=?1",
        params![
            id.as_bytes().as_slice(),
            workspace.revisions.len() as i64 + 1
        ],
    )
    .map_err(|e| map_sqlite(e, "the archived revision sequence could not be saved"))?;
    Ok(())
}
#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
    use super::*;
    use crate::{CatalogKey, CatalogLocation};
    fn db() -> CatalogDb {
        let root = chur_crypto::random::secret::<32>().unwrap();
        let id = chur_crypto::random::id().unwrap();
        let key = CatalogKey::derive(&root, &id).unwrap();
        let mut db = CatalogDb::open(&CatalogLocation::Memory, &key).unwrap();
        schema::open_at_current_version(&mut db, 1).unwrap();
        db
    }
    fn note(db: &mut CatalogDb) -> Note {
        notes::save(
            db,
            None,
            0,
            "first",
            "# original",
            false,
            NoteFormat::Markdown,
            10,
        )
        .unwrap()
    }
    fn file(data: &[u8]) -> files::AttachmentData {
        files::AttachmentData {
            attachment: Attachment {
                id: chur_crypto::random::id().unwrap(),
                name: "original.bin".into(),
                mime_type: files::mime_type(data).into(),
                size: data.len() as u64,
            },
            data: Zeroizing::new(data.to_vec()),
        }
    }
    fn status<T>(r: Result<T>) -> chur_core::ChurStatus {
        match r {
            Ok(_) => panic!("expected rejection"),
            Err(e) => e.status(),
        }
    }
    #[test]
    fn organization_is_normalized_atomic_and_filters_before_pagination() {
        let mut db = db();
        let original = note(&mut db);
        let n = set_organization(
            &mut db,
            &original.id,
            1,
            " Work ",
            &[" beta ", "alpha", "alpha"],
            11,
        )
        .unwrap();
        let org = get_organization(&db, &n.id).unwrap();
        assert_eq!(org.folder, "Work");
        assert_eq!(org.tags, vec!["alpha", "beta"]);
        assert_eq!(
            status(set_organization(&mut db, &n.id, 1, "Wrong", &[], 12)),
            chur_core::ChurStatus::Conflict
        );
        for i in 0..3 {
            notes::save(
                &mut db,
                None,
                0,
                &format!("unmatched {i}"),
                "",
                false,
                NoteFormat::PlainText,
                20 + i,
            )
            .unwrap();
        }
        let page = notes::page_filtered(&db, "", false, 1, None, "Work", "alpha").unwrap();
        assert_eq!(page.notes.len(), 1);
        assert_eq!(page.notes[0].note.id, n.id);
        assert!(page.next_cursor.is_none());
        assert_eq!(history(&db, &n.id).unwrap().len(), 1);
        assert!(organization(&"x".repeat(65), &[]).is_err());
        assert!(organization("", &["line\nfeed"]).is_err());
    }
    #[test]
    fn restore_recovers_original_bytes_and_source_and_retains_displaced_revision() {
        let mut db = db();
        let n = note(&mut db);
        let item = file(b"original secret");
        let aid = item.attachment.id;
        let n = files::put(&mut db, &n.id, n.revision, item, 11).unwrap();
        let historical = n.revision;
        let n = set_organization(&mut db, &n.id, n.revision, "Folder", &["tag"], 12).unwrap();
        let n = notes::save(
            &mut db,
            Some(n.id),
            n.revision,
            "changed",
            "new source",
            true,
            NoteFormat::PlainText,
            13,
        )
        .unwrap();
        let n = files::remove(&mut db, &n.id, n.revision, &aid, 14).unwrap();
        assert!(files::list(&db, &n.id).unwrap().is_empty());
        let restored = restore(&mut db, &n.id, n.revision, historical, 9).unwrap();
        assert_eq!(restored.revision, n.revision + 1);
        assert_eq!(restored.updated_ms, 14);
        assert_eq!(restored.body, "# original");
        assert_eq!(restored.format, NoteFormat::Markdown);
        assert_eq!(&*files::read(&db, &n.id, &aid).unwrap(), b"original secret");
        assert!(get_organization(&db, &n.id).unwrap().folder.is_empty());
        assert_eq!(
            get_revision(&db, &n.id, n.revision).unwrap().note.body,
            "new source"
        );
        assert_eq!(
            status(restore(&mut db, &n.id, n.revision, historical, 15)),
            chur_core::ChurStatus::Conflict
        );
    }
    #[test]
    fn pin_and_unpin_keep_recoverable_saved_states() {
        let mut db = db();
        let initial = note(&mut db);
        let pinned = notes::change(&mut db, &initial.id, initial.revision, 1, 11)
            .unwrap()
            .unwrap();
        let unpinned = notes::change(&mut db, &initial.id, pinned.revision, 2, 12)
            .unwrap()
            .unwrap();
        let versions = history(&db, &initial.id).unwrap();
        assert_eq!(versions.len(), 2);
        assert_eq!(versions[0].note.revision, pinned.revision);
        assert!(versions[0].note.pinned);
        assert!(!versions[1].note.pinned);
        let restored =
            restore(&mut db, &initial.id, unpinned.revision, pinned.revision, 13).unwrap();
        assert!(restored.pinned);
        assert!(restored.revision > unpinned.revision);
        assert!(
            !get_revision(&db, &initial.id, unpinned.revision)
                .unwrap()
                .note
                .pinned
        );
    }

    #[test]
    fn text_edits_deduplicate_files_and_retention_is_bounded() {
        let mut db = db();
        let n = note(&mut db);
        let item = file(b"one encrypted payload");
        let aid = item.attachment.id;
        let mut n = files::put(&mut db, &n.id, n.revision, item, 11).unwrap();
        for i in 0..30 {
            n = notes::save(
                &mut db,
                Some(n.id),
                n.revision,
                "title",
                &format!("body {i}"),
                false,
                NoteFormat::Markdown,
                20 + i,
            )
            .unwrap();
        }
        assert_eq!(history(&db, &n.id).unwrap().len(), HISTORY_MAX);
        let (count, bytes): (i64, i64) = db
            .connection()
            .query_row(
                "SELECT count(*),sum(length(data)) FROM private_note_history_blobs",
                [],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .unwrap();
        assert_eq!(count, 1);
        assert_eq!(bytes, 21);
        let oldest = history(&db, &n.id).unwrap().last().unwrap().note.revision;
        let restored = restore(&mut db, &n.id, n.revision, oldest, 100).unwrap();
        assert_eq!(
            &*files::read(&db, &n.id, &aid).unwrap(),
            b"one encrypted payload"
        );
        assert_eq!(restored.body, "body 9");
        assert_eq!(history(&db, &n.id).unwrap().len(), 20);
    }
    #[test]
    fn trash_retains_history_and_purge_erases_all_workspace_rows() {
        let mut db = db();
        let n = note(&mut db);
        let n = files::put(&mut db, &n.id, n.revision, file(b"secret"), 11).unwrap();
        let aid = files::list(&db, &n.id).unwrap()[0].id;
        let n = files::remove(&mut db, &n.id, n.revision, &aid, 12).unwrap();
        let n = notes::change(&mut db, &n.id, n.revision, 3, 13)
            .unwrap()
            .unwrap();
        assert!(!history(&db, &n.id).unwrap().is_empty());
        assert_eq!(
            status(restore(&mut db, &n.id, n.revision, 1, 14)),
            chur_core::ChurStatus::Conflict
        );
        notes::change(&mut db, &n.id, n.revision, 5, 14).unwrap();
        for table in [
            "private_note_history",
            "private_note_history_files",
            "private_note_history_blobs",
            "private_note_organization",
        ] {
            let count: i64 = db
                .connection()
                .query_row(&format!("SELECT count(*) FROM {table}"), [], |r| r.get(0))
                .unwrap();
            assert_eq!(count, 0);
        }
    }
    #[test]
    fn historical_byte_budget_evicts_oldest_versions_and_preserves_current_files() {
        let mut db = db();
        let mut n = note(&mut db);
        let mut payload = Zeroizing::new(vec![0u8; files::FILE_MAX]);
        for marker in 1..=9 {
            payload[0] = marker;
            let item = file(&payload);
            let aid = item.attachment.id;
            n = files::put(&mut db, &n.id, n.revision, item, marker as u64 + 10).unwrap();
            n = files::remove(&mut db, &n.id, n.revision, &aid, marker as u64 + 20).unwrap();
        }
        let bytes: i64 = db
            .connection()
            .query_row(
                "SELECT coalesce(sum(length(data)),0) FROM private_note_history_blobs",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(bytes, HISTORY_BYTES_MAX as i64);
        let h = history(&db, &n.id).unwrap();
        assert!(h.len() <= HISTORY_MAX);
        let last = h.iter().find(|v| !v.attachments.is_empty()).unwrap();
        let restored = restore(&mut db, &n.id, n.revision, last.note.revision, 100).unwrap();
        assert_eq!(
            files::read(&db, &n.id, &last.attachments[0].id).unwrap()[0],
            9
        );
        assert_eq!(restored.attachment_count, 1);
    }
    #[test]
    fn corrupt_persisted_organization_is_rejected_without_rewriting_it() {
        let mut db = db();
        let n = note(&mut db);
        db.connection().execute("INSERT INTO private_note_organization(note_id,folder,tags) VALUES(?1,'folder','[\"bad\\nlabel\"]')",[n.id.as_bytes().as_slice()]).unwrap();
        assert_eq!(
            status(get_organization(&db, &n.id)),
            chur_core::ChurStatus::CatalogCorrupt
        );
        assert_eq!(
            status(set_organization(&mut db, &n.id, n.revision, "new", &[], 20)),
            chur_core::ChurStatus::CatalogCorrupt
        );
        assert_eq!(notes::get(&db, &n.id).unwrap().revision, n.revision);
    }
    #[test]
    fn selective_archive_round_trip_copies_historical_bytes_and_organization() {
        let mut source = db();
        let n = note(&mut source);
        let item = file(b"history only");
        let aid = item.attachment.id;
        let n = files::put(&mut source, &n.id, n.revision, item, 11).unwrap();
        let n =
            set_organization(&mut source, &n.id, n.revision, "Folder", &["private"], 12).unwrap();
        let n = files::remove(&mut source, &n.id, n.revision, &aid, 13).unwrap();
        let mut target = db();
        let all = files::all(&source, &n.id).unwrap();
        let workspace = archive(&source, &n.id).unwrap();
        let id = crate::transfer::copy_note(&mut target, &n, &all, Some(&workspace)).unwrap();
        assert_ne!(id, n.id);
        assert_eq!(get_organization(&target, &id).unwrap().folder, "Folder");
        let h = history(&target, &id).unwrap();
        let version = h
            .iter()
            .find(|v| v.note.attachment_count == 1)
            .unwrap()
            .note
            .revision;
        let current = notes::get(&target, &id).unwrap();
        let restored = restore(&mut target, &id, current.revision, version, 100).unwrap();
        assert_eq!(restored.attachment_count, 1);
        assert_eq!(&*files::read(&target, &id, &aid).unwrap(), b"history only");
    }
}
