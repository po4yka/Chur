//! Bounded note-owned files. Metadata and bytes are encrypted by SQLCipher together.
use crate::{
    CatalogDb,
    db::{as_sqlite_integer, map_sqlite},
    notes::{self, Note, NoteFormat},
    schema,
};
use base64ct::{Base64, Encoding};
use chur_core::{Id, Result, ensure};
use rusqlite::{Connection, OptionalExtension, params};
use zeroize::{Zeroize, Zeroizing};
/// Maximum bytes in one attachment.
pub const FILE_MAX: usize = 8 * 1024 * 1024;
/// Maximum total attachment bytes owned by one note.
pub const TOTAL_MAX: usize = 16 * 1024 * 1024;
/// Maximum number of attachments in a note.
pub const COUNT_MAX: usize = 32;
/// Maximum portable JSON bytes, including Base64 and escaped source.
pub const PACKAGE_MAX: usize = 24 * 1024 * 1024;
/// Note-scoped attachment metadata. Debug never exposes user labels.
pub struct Attachment {
    /// Opaque note-scoped identity retained by portable imports.
    pub id: Id,
    /// Validated basename; never a filesystem path.
    pub name: String,
    /// Sniffed safe content type.
    pub mime_type: String,
    /// Exact plaintext bytes.
    pub size: u64,
}
impl core::fmt::Debug for Attachment {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        f.write_str("Attachment(<redacted>)")
    }
}
impl Drop for Attachment {
    fn drop(&mut self) {
        self.name.zeroize();
        self.mime_type.zeroize();
    }
}
/// Owned import bytes; always erased on drop.
pub struct AttachmentData {
    /// Validated metadata.
    pub attachment: Attachment,
    /// Bytes erased on drop.
    pub data: Zeroizing<Vec<u8>>,
}
/// Sniffs a deliberately small allowlist. Every other file remains opaque, never executable content.
pub fn mime_type(data: &[u8]) -> &'static str {
    if data.starts_with(b"\x89PNG\r\n\x1a\n") {
        "image/png"
    } else if data.starts_with(b"\xff\xd8\xff") {
        "image/jpeg"
    } else if data.starts_with(b"GIF87a") || data.starts_with(b"GIF89a") {
        "image/gif"
    } else if data.len() >= 12 && &data[..4] == b"RIFF" && &data[8..12] == b"WEBP" {
        "image/webp"
    } else if data.starts_with(b"%PDF-") {
        "application/pdf"
    } else {
        "application/octet-stream"
    }
}
pub(crate) fn valid_name(name: &str) -> bool {
    !name.trim().is_empty()
        && name.len() <= 240
        && !name.contains(['/', '\\', ':'])
        && !name.chars().any(char::is_control)
        && name != "."
        && name != ".."
}
pub(crate) fn supported_mime(mime: &str) -> bool {
    matches!(
        mime,
        "image/png"
            | "image/jpeg"
            | "image/gif"
            | "image/webp"
            | "application/pdf"
            | "application/octet-stream"
    )
}
/// Applies the same basename, MIME and aggregate bounds to direct and portable imports.
pub fn validate(all: &[AttachmentData]) -> Result<()> {
    ensure!(
        all.len() <= COUNT_MAX,
        ResourceLimitExceeded,
        "the note has too many attachments"
    );
    let mut total = 0usize;
    let mut ids = std::collections::BTreeSet::new();
    for item in all {
        let a = &item.attachment;
        ensure!(
            ids.insert(a.id),
            InvalidInput,
            "the note repeats an attachment identity"
        );
        ensure!(
            valid_name(&a.name),
            InvalidInput,
            "the attachment filename is invalid"
        );
        ensure!(
            item.data.len() <= FILE_MAX,
            ResourceLimitExceeded,
            "the attachment exceeds its byte bound"
        );
        ensure!(
            a.size == item.data.len() as u64 && a.mime_type == mime_type(&item.data),
            InvalidInput,
            "the attachment metadata disagrees with its bytes"
        );
        total = total.checked_add(item.data.len()).ok_or_else(|| {
            chur_core::err!(
                ResourceLimitExceeded,
                "the attachment total is out of range"
            )
        })?;
    }
    ensure!(
        total <= TOTAL_MAX,
        ResourceLimitExceeded,
        "the note attachments exceed their total byte bound"
    );
    Ok(())
}
pub(crate) fn list_conn(c: &Connection, id: &Id) -> Result<Vec<Attachment>> {
    let mut stmt=c.prepare("SELECT CASE WHEN length(attachment_id)=16 THEN attachment_id END, CASE WHEN length(CAST(name AS BLOB)) BETWEEN 1 AND 240 THEN name END, CASE WHEN length(CAST(mime_type AS BLOB))<=128 THEN mime_type END, length(data) FROM private_note_attachments WHERE note_id=?1 ORDER BY attachment_id LIMIT 33").map_err(|e|map_sqlite(e,"the attachments could not be listed"))?;
    let rows = stmt
        .query_map([id.as_bytes().as_slice()], |r| {
            let id: Vec<u8> = r.get(0)?;
            Ok(Attachment {
                id: Id::from_slice(&id).map_err(|_| rusqlite::Error::InvalidQuery)?,
                name: r.get(1)?,
                mime_type: r.get(2)?,
                size: r.get::<_, i64>(3)? as u64,
            })
        })
        .map_err(|e| map_sqlite(e, "the attachments could not be listed"))?;
    let result = rows
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(|e| map_sqlite(e, "the attachments could not be read"))?;
    ensure!(
        result.len() <= COUNT_MAX
            && result.iter().all(|a| valid_name(&a.name)
                && supported_mime(&a.mime_type)
                && a.size <= FILE_MAX as u64)
            && result.iter().map(|a| a.size).sum::<u64>() <= TOTAL_MAX as u64,
        CatalogCorrupt,
        "the stored attachments exceed their bounds"
    );
    Ok(result)
}
/// Lists metadata without bringing file bytes into host memory. Trash retains its files.
pub fn list(db: &CatalogDb, id: &Id) -> Result<Vec<Attachment>> {
    notes::get(db, id)?;
    list_conn(db.connection(), id)
}
/// Reads exactly one bounded file from the current unlocked catalog.
pub fn read(db: &CatalogDb, id: &Id, attachment: &Id) -> Result<Zeroizing<Vec<u8>>> {
    notes::get(db, id)?;
    // SQLite evaluates the length predicate before returning the BLOB. Even a
    // malicious authenticated archive with removed CHECKs cannot trigger an
    // oversized Rust allocation here.
    let row: Option<(i64, Option<Vec<u8>>)> = db
        .connection()
        .query_row(
            "SELECT length(data),CASE WHEN typeof(data)='blob' AND length(data)<=?3 THEN data END FROM private_note_attachments WHERE note_id=?1 AND attachment_id=?2",
            params![id.as_bytes().as_slice(), attachment.as_bytes().as_slice(), FILE_MAX as i64],
            |r| Ok((r.get(0)?,r.get(1)?)),
        )
        .optional()
        .map_err(|e| map_sqlite(e, "the attachment could not be read"))?;
    let (size, bytes) =
        row.ok_or_else(|| chur_core::err!(NotFound, "the attachment does not exist"))?;
    ensure!(
        size >= 0 && size <= FILE_MAX as i64,
        CatalogCorrupt,
        "the stored attachment exceeds its byte bound"
    );
    let bytes = Zeroizing::new(bytes.ok_or_else(|| {
        chur_core::err!(CatalogCorrupt, "the stored attachment bytes are absent")
    })?);
    Ok(bytes)
}
fn insert(c: &Connection, id: &Id, item: &AttachmentData) -> Result<()> {
    c.execute("INSERT INTO private_note_attachments(note_id,attachment_id,name,mime_type,data) VALUES(?1,?2,?3,?4,?5)",params![id.as_bytes().as_slice(),item.attachment.id.as_bytes().as_slice(),&item.attachment.name,&item.attachment.mime_type,item.data.as_slice()]).map_err(|e|map_sqlite(e,"the attachment could not be saved"))?;
    Ok(())
}
/// Adds a fresh attachment under the displayed revision. Existing IDs cannot silently replace files.
pub fn put(
    db: &mut CatalogDb,
    id: &Id,
    expected: u64,
    item: AttachmentData,
    now: u64,
) -> Result<Note> {
    validate(core::slice::from_ref(&item))?;
    let time = as_sqlite_integer(now, "the attachment time is out of range")?;
    db.transaction(|tx| {
        let old = notes::get_conn(tx, id)?;
        ensure!(
            old.revision == expected && old.deleted_ms.is_none(),
            Conflict,
            "the private note changed"
        );
        ensure!(expected < i64::MAX as u64, ResourceLimitExceeded, "the note revision is exhausted");
        let all = list_conn(tx, id)?;
        ensure!(
            all.len() < COUNT_MAX
                && all.iter().map(|a| a.size).sum::<u64>() + item.data.len() as u64 <= TOTAL_MAX as u64,
            ResourceLimitExceeded,
            "the note attachments exceed their bound"
        );
        ensure!(
            !all.iter().any(|a| a.id == item.attachment.id),
            Conflict,
            "the attachment identity already exists"
        );
        crate::note_workspace::snapshot(tx,id)?;
        insert(tx, id, &item)?;
        tx.execute(
            "UPDATE private_notes SET revision=revision+1,updated_ms=max(updated_ms,?2) WHERE note_id=?1",
            params![id.as_bytes().as_slice(), time],
        ).map_err(|e| map_sqlite(e, "the attachment revision could not be saved"))?;
        schema::bump_generation(tx)?;
        notes::get_conn(tx, id)
    })
}
/// Detaches and erases the note-owned row atomically. No other note can share its identity.
pub fn remove(
    db: &mut CatalogDb,
    id: &Id,
    expected: u64,
    attachment: &Id,
    now: u64,
) -> Result<Note> {
    let time = as_sqlite_integer(now, "the attachment time is out of range")?;
    db.transaction(|tx| {
        let old = notes::get_conn(tx, id)?;
        ensure!(
            old.revision == expected && old.deleted_ms.is_none(),
            Conflict,
            "the private note changed"
        );
        ensure!(expected < i64::MAX as u64, ResourceLimitExceeded, "the note revision is exhausted");
        crate::note_workspace::snapshot(tx,id)?;
        let changed = tx.execute(
            "DELETE FROM private_note_attachments WHERE note_id=?1 AND attachment_id=?2",
            params![id.as_bytes().as_slice(), attachment.as_bytes().as_slice()],
        ).map_err(|e| map_sqlite(e, "the attachment could not be removed"))?;
        ensure!(changed == 1, NotFound, "the attachment does not exist");
        tx.execute(
            "UPDATE private_notes SET revision=revision+1,updated_ms=max(updated_ms,?2) WHERE note_id=?1",
            params![id.as_bytes().as_slice(), time],
        ).map_err(|e| map_sqlite(e, "the attachment revision could not be saved"))?;
        schema::bump_generation(tx)?;
        notes::get_conn(tx, id)
    })
}
/// Imports a complete portable note in one transaction; partial attachments never become visible.
pub fn import(
    db: &mut CatalogDb,
    title: &str,
    body: &str,
    format: NoteFormat,
    all: &[AttachmentData],
    now: u64,
) -> Result<Note> {
    validate(all)?;
    ensure!(
        title.len() <= 256 && body.len() <= 262144 && !title.contains('\0') && !body.contains('\0'),
        InvalidInput,
        "the imported note exceeds its text bounds"
    );
    let id = chur_crypto::random::id()?;
    let time = as_sqlite_integer(now, "the note time is out of range")?;
    db.transaction(|tx| {
        let count: i64 = tx.query_row("SELECT count(*) FROM private_notes", [], |r| r.get(0))
            .map_err(|e| map_sqlite(e, "the note count could not be read"))?;
        ensure!(count < 10000, ResourceLimitExceeded, "the private note count limit was reached");
        tx.execute(
            "INSERT INTO private_notes(note_id,revision,title,body,format,created_ms,updated_ms,pinned) VALUES(?1,1,?2,?3,?4,?5,?5,0)",
            params![id.as_bytes().as_slice(), title, body, format as u8, time],
        ).map_err(|e| map_sqlite(e, "the imported note could not be saved"))?;
        for item in all {
            insert(tx, &id, item)?;
        }
        notes::reindex(tx, &id)?;
        schema::bump_generation(tx)?;
        notes::get_conn(tx, &id)
    })
}
#[derive(serde::Serialize)]
struct Package<'a> {
    version: u8,
    title: &'a str,
    body: &'a str,
    format: &'a str,
    attachments: Vec<PackageAttachment<'a>>,
}
#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct PackageAttachment<'a> {
    id: String,
    name: &'a str,
    mime_type: &'a str,
    data: &'a str,
}
fn package<'a>(n: &'a Note, all: &'a [Attachment], data: &'a [Zeroizing<String>]) -> Package<'a> {
    Package {
        version: 1,
        title: &n.title,
        body: &n.body,
        format: if n.format == NoteFormat::Markdown {
            "MARKDOWN"
        } else {
            "PLAIN_TEXT"
        },
        attachments: all
            .iter()
            .enumerate()
            .map(|(i, a)| PackageAttachment {
                id: a.id.to_hex(),
                name: &a.name,
                mime_type: &a.mime_type,
                data: data.get(i).map(|s| s.as_str()).unwrap_or(""),
            })
            .collect(),
    }
}
fn json(p: &Package<'_>) -> Result<Zeroizing<Vec<u8>>> {
    // Reserve the bounded worst-case escaping size before any plaintext is written;
    // growth must not leave earlier plaintext allocations behind in the allocator.
    let reserve = 256
        + 6 * (p.title.len() + p.body.len())
        + p.attachments
            .iter()
            .map(|a| 128 + 6 * (a.name.len() + a.mime_type.len()) + a.data.len())
            .sum::<usize>();
    let mut output = Zeroizing::new(Vec::with_capacity(reserve));
    serde_json::to_writer(&mut *output, p)
        .map_err(|_| chur_core::err!(InternalFailure, "the note package could not be encoded"))?;
    Ok(output)
}
/// Calculates exact JSON size from metadata only; file bytes are never loaded for list rows.
pub fn export_size(db: &CatalogDb, n: &Note) -> Result<u64> {
    let all = list_conn(db.connection(), &n.id)?;
    if all.is_empty() {
        return Ok(n.export_size());
    }
    let size = json(&package(n, &all, &[]))?.len()
        + all
            .iter()
            .map(|a| 4 * (a.size as usize).div_ceil(3))
            .sum::<usize>();
    ensure!(
        size <= PACKAGE_MAX,
        ResourceLimitExceeded,
        "the note package exceeds its byte bound"
    );
    Ok(size as u64)
}
/// Exports the exact source alone, or a portable attachment package when files exist.
pub fn export_bytes(db: &CatalogDb, n: &Note) -> Result<Zeroizing<Vec<u8>>> {
    let all = list(db, &n.id)?;
    if all.is_empty() {
        let mut out = Zeroizing::new(Vec::with_capacity(n.export_size() as usize));
        if n.format == NoteFormat::PlainText {
            out.extend_from_slice(n.title.as_bytes());
            out.extend_from_slice(b"\n\n");
        }
        out.extend_from_slice(n.body.as_bytes());
        return Ok(out);
    }
    let size = export_size(db, n)?;
    let mut encoded = Vec::with_capacity(all.len());
    for a in &all {
        let bytes = read(db, &n.id, &a.id)?;
        encoded.push(Zeroizing::new(Base64::encode_string(&bytes)));
    }
    let out = json(&package(n, &all, &encoded))?;
    ensure!(
        out.len() == size as usize,
        InternalFailure,
        "the package size disagrees with its encoding"
    );
    Ok(out)
}
/// Reads bounded files for a selected archive. IDs are preserved so Markdown sources remain exact.
pub fn all(db: &CatalogDb, id: &Id) -> Result<Vec<AttachmentData>> {
    list(db, id)?
        .into_iter()
        .map(|a| {
            let data = read(db, id, &a.id)?;
            Ok(AttachmentData {
                attachment: a,
                data,
            })
        })
        .collect()
}
/// Adds already-validated files to a note created in this transaction by the archive copier.
pub(crate) fn copy_into(tx: &Connection, id: &Id, all: &[AttachmentData]) -> Result<()> {
    validate(all)?;
    for item in all {
        insert(tx, id, item)?;
    }
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
    fn file(bytes: &[u8]) -> AttachmentData {
        AttachmentData {
            attachment: Attachment {
                id: chur_crypto::random::id().unwrap(),
                name: "資料 🔐.bin".to_owned(),
                mime_type: mime_type(bytes).to_owned(),
                size: bytes.len() as u64,
            },
            data: Zeroizing::new(bytes.to_vec()),
        }
    }
    fn note(db: &mut CatalogDb) -> Note {
        notes::save(
            db,
            None,
            0,
            "題名",
            "![image](attachment:abc)\n\nexact **source**\n",
            false,
            NoteFormat::Markdown,
            1,
        )
        .unwrap()
    }
    #[test]
    fn attachment_cas_retention_purge_and_exact_portable_size() {
        let mut db = db();
        let n = note(&mut db);
        let f = file(b"\x89PNG\r\n\x1a\nimage\0bytes");
        let id = f.attachment.id;
        let saved = put(&mut db, &n.id, n.revision, f, 2).unwrap();
        assert_eq!(saved.revision, 2);
        assert_eq!(saved.attachment_count, 1);
        assert_eq!(
            &*read(&db, &n.id, &id).unwrap(),
            b"\x89PNG\r\n\x1a\nimage\0bytes"
        );
        assert_eq!(
            remove(&mut db, &n.id, 1, &id, 3).err().unwrap().status(),
            chur_core::ChurStatus::Conflict
        );
        let bytes = export_bytes(&db, &saved).unwrap();
        assert_eq!(export_size(&db, &saved).unwrap(), bytes.len() as u64);
        let json: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(json["version"], 1);
        assert_eq!(json["body"], saved.body);
        assert_eq!(json["attachments"][0]["id"], id.to_hex());
        assert_eq!(json["attachments"][0]["mimeType"], "image/png");
        assert_eq!(
            Base64::decode_vec(json["attachments"][0]["data"].as_str().unwrap()).unwrap(),
            b"\x89PNG\r\n\x1a\nimage\0bytes"
        );
        let trash = notes::change(&mut db, &n.id, 2, 3, 3).unwrap().unwrap();
        assert_eq!(list(&db, &n.id).unwrap().len(), 1);
        assert!(put(&mut db, &n.id, trash.revision, file(b"new"), 4).is_err());
        let restored = notes::change(&mut db, &n.id, trash.revision, 4, 4)
            .unwrap()
            .unwrap();
        let detached = remove(&mut db, &n.id, restored.revision, &id, 5).unwrap();
        assert_eq!(detached.attachment_count, 0);
        assert!(read(&db, &n.id, &id).is_err());
        let f = file(b"data");
        let n = put(&mut db, &n.id, detached.revision, f, 6).unwrap();
        let n = notes::change(&mut db, &n.id, n.revision, 3, 7)
            .unwrap()
            .unwrap();
        notes::change(&mut db, &n.id, n.revision, 5, 8).unwrap();
        let count: i64 = db
            .connection()
            .query_row("SELECT count(*) FROM private_note_attachments", [], |r| {
                r.get(0)
            })
            .unwrap();
        assert_eq!(count, 0);
    }
    #[test]
    fn native_validation_and_atomic_import_reject_partial_files() {
        let mut db = db();
        let n = note(&mut db);
        for name in ["../bad", "a/b", "a\\b", "bad\0name", ".."] {
            let mut f = file(b"x");
            f.attachment.name = name.to_owned();
            assert!(put(&mut db, &n.id, 1, f, 2).is_err());
        }
        let mut f = file(b"payload");
        f.attachment.mime_type = "image/png".to_owned();
        assert!(put(&mut db, &n.id, 1, f, 2).is_err());
        let mut f = file(b"x");
        f.data = Zeroizing::new(vec![1; FILE_MAX + 1]);
        f.attachment.size = f.data.len() as u64;
        assert!(put(&mut db, &n.id, 1, f, 2).is_err());
        assert_eq!(notes::get(&db, &n.id).unwrap().revision, 1);
        let f = file(b"one");
        let id = f.attachment.id;
        let mut duplicate = file(b"two");
        duplicate.attachment.id = id;
        assert!(
            import(
                &mut db,
                "import",
                "body",
                NoteFormat::Markdown,
                &[f, duplicate],
                3
            )
            .is_err()
        );
        assert_eq!(
            notes::page(&db, "", false, 100, None).unwrap().notes.len(),
            1
        );
        db.connection().execute_batch("CREATE TRIGGER refuse_attachment BEFORE INSERT ON private_note_attachments BEGIN SELECT RAISE(ABORT,'fixture'); END;").unwrap();
        assert!(
            import(
                &mut db,
                "import",
                "body",
                NoteFormat::Markdown,
                &[file(b"file")],
                3
            )
            .is_err()
        );
        assert_eq!(
            notes::page(&db, "", false, 100, None).unwrap().notes.len(),
            1
        );
    }
    #[test]
    fn selected_archive_copies_owned_files_atomically_and_expiry_erases_them() {
        let mut source = db();
        let mut target = db();
        let n = note(&mut source);
        let f = file(b"%PDF-1.7\nbytes");
        let id = f.attachment.id;
        let n = put(&mut source, &n.id, 1, f, 2).unwrap();
        let attachments = all(&source, &n.id).unwrap();
        let new = crate::transfer::copy_note(&mut target, &n, &attachments, None).unwrap();
        let copy = notes::get(&target, &new).unwrap();
        assert_ne!(new, n.id);
        assert_eq!(copy.body, n.body);
        assert_eq!(copy.attachment_count, 1);
        assert_eq!(&*read(&target, &new, &id).unwrap(), b"%PDF-1.7\nbytes");
        let n = notes::change(&mut source, &n.id, 2, 3, 3).unwrap().unwrap();
        notes::purge_expired(&mut source, 3 + crate::trash::RETENTION_MS).unwrap();
        assert!(read(&source, &n.id, &id).is_err());
        assert_eq!(list(&target, &new).unwrap().len(), 1);
    }
    #[test]
    fn malformed_authenticated_catalog_cannot_return_oversized_files_or_unbounded_rows() {
        let mut db = db();
        let n = note(&mut db);
        let id = chur_crypto::random::id().unwrap();
        db.connection()
            .execute_batch("PRAGMA ignore_check_constraints=ON")
            .unwrap();
        db.connection().execute("INSERT INTO private_note_attachments(note_id,attachment_id,name,mime_type,data) VALUES(?1,?2,'large.bin','application/octet-stream',zeroblob(?3))",params![n.id.as_bytes().as_slice(),id.as_bytes().as_slice(),FILE_MAX as i64+1]).unwrap();
        assert_eq!(
            read(&db, &n.id, &id).err().unwrap().status(),
            chur_core::ChurStatus::CatalogCorrupt
        );
        assert!(list(&db, &n.id).is_err());
        db.connection()
            .execute("DELETE FROM private_note_attachments", [])
            .unwrap();
        for number in 1..=33u8 {
            db.connection().execute("INSERT INTO private_note_attachments(note_id,attachment_id,name,mime_type,data) VALUES(?1,?2,'file.bin','application/octet-stream',x'')",params![n.id.as_bytes().as_slice(),vec![number;16]]).unwrap();
        }
        assert_eq!(
            list(&db, &n.id).err().unwrap().status(),
            chur_core::ChurStatus::CatalogCorrupt
        );
        db.connection()
            .execute("DELETE FROM private_note_attachments", [])
            .unwrap();
        db.connection().execute("INSERT INTO private_note_attachments(note_id,attachment_id,name,mime_type,data) VALUES(?1,?2,'../escape.pdf','application/pdf',x'')",params![n.id.as_bytes().as_slice(),id.as_bytes().as_slice()]).unwrap();
        assert_eq!(
            list(&db, &n.id).err().unwrap().status(),
            chur_core::ChurStatus::CatalogCorrupt
        );
        db.connection()
            .execute(
                "UPDATE private_note_attachments SET name='file.bin',mime_type='text/html'",
                [],
            )
            .unwrap();
        assert_eq!(
            list(&db, &n.id).err().unwrap().status(),
            chur_core::ChurStatus::CatalogCorrupt
        );
    }

    #[test]
    fn v12_migration_is_transactional_and_preserves_notes() {
        let mut db = db();
        let n = note(&mut db);
        db.connection().execute_batch("DROP TABLE private_note_attachments; UPDATE vault_state SET catalog_format_version=12; CREATE TRIGGER refuse_v13 BEFORE UPDATE OF catalog_format_version ON vault_state WHEN NEW.catalog_format_version=13 BEGIN SELECT RAISE(ABORT,'fixture'); END;").unwrap();
        assert!(schema::migrate_v12_to_v13(&mut db).is_err());
        assert_eq!(schema::recorded_version(db.connection()).unwrap(), Some(12));
        let count: i64 = db
            .connection()
            .query_row(
                "SELECT count(*) FROM sqlite_master WHERE name='private_note_attachments'",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(count, 0);
        db.connection()
            .execute_batch("DROP TRIGGER refuse_v13")
            .unwrap();
        schema::migrate_v12_to_v13(&mut db).unwrap();
        let actual = notes::get(&db, &n.id).unwrap();
        assert_eq!(actual.body, n.body);
        assert_eq!(actual.revision, 1);
        assert_eq!(actual.attachment_count, 0);
    }
}
