//! Private local notes. All rows and search terms remain in SQLCipher.
use crate::{
    CatalogDb,
    db::{as_sqlite_integer, map_sqlite},
    schema,
};
use chur_core::{Id, Result, ensure};
use rusqlite::{Connection, OptionalExtension, params};
use zeroize::Zeroize;

/// Persisted interpretation of a note body. Legacy text is never inferred as Markdown.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum NoteFormat {
    /// Literal UTF-8 text, including Markdown punctuation.
    PlainText = 0,
    /// UTF-8 Markdown source.
    Markdown = 1,
}
impl TryFrom<u8> for NoteFormat {
    type Error = chur_core::Error;
    fn try_from(value: u8) -> Result<Self> {
        match value {
            0 => Ok(Self::PlainText),
            1 => Ok(Self::Markdown),
            _ => Err(chur_core::err!(InvalidInput, "the note format is unknown")),
        }
    }
}
/// A private note. Debug intentionally omits private text.
pub struct Note {
    /// Opaque vault-local identity.
    pub id: Id,
    /// Committed optimistic revision.
    pub revision: u64,
    /// UTF-8 title, at most 256 bytes.
    pub title: String,
    /// UTF-8 body, at most 256 KiB; a Summary holds only its preview here.
    pub body: String,
    /// Explicit body format, committed with its revision.
    pub format: NoteFormat,
    /// Number of note-owned files, including files retained in Trash.
    pub attachment_count: u32,
    /// Native creation time in milliseconds.
    pub created_ms: u64,
    /// Monotonic update time in milliseconds.
    pub updated_ms: u64,
    /// Whether the note sorts before unpinned notes.
    pub pinned: bool,
    /// Trash time, absent for an active note.
    pub deleted_ms: Option<u64>,
}
impl core::fmt::Debug for Note {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        f.write_str("Note(<redacted>)")
    }
}
impl Drop for Note {
    fn drop(&mut self) {
        self.title.zeroize();
        self.body.zeroize();
    }
}
impl Note {
    /// Exact export size: Markdown source, or legacy title, two LF bytes and body.
    pub fn export_size(&self) -> u64 {
        (self.body.len()
            + if self.format == NoteFormat::PlainText {
                self.title.len() + 2
            } else {
                0
            }) as u64
    }
}
/// A list row never contains the whole body.
pub struct Summary {
    /// Record fields with a preview of at most 160 Unicode scalar values.
    pub note: Note,
    /// Exact export size computed from the complete stored body.
    pub export_size: u64,
}
/// A bounded keyset page bound to one query and catalog generation.
pub struct Page {
    /// At most one hundred summaries.
    pub notes: Vec<Summary>,
    /// Opaque cursor, absent at the end.
    pub next_cursor: Option<Vec<u8>>,
}
const COLUMNS: &str = "note_id,revision,title,body,created_ms,updated_ms,pinned,deleted_ms,format,(SELECT count(*) FROM private_note_attachments a WHERE a.note_id=private_notes.note_id)";
fn decode(row: &rusqlite::Row<'_>) -> rusqlite::Result<Note> {
    let bytes: Vec<u8> = row.get(0)?;
    let id = Id::from_slice(&bytes).map_err(|_| rusqlite::Error::InvalidQuery)?;
    Ok(Note {
        id,
        revision: row.get::<_, i64>(1)? as u64,
        attachment_count: row.get(9)?,
        title: row.get(2)?,
        body: row.get(3)?,
        created_ms: row.get::<_, i64>(4)? as u64,
        updated_ms: row.get::<_, i64>(5)? as u64,
        pinned: row.get(6)?,
        deleted_ms: row.get::<_, Option<i64>>(7)?.map(|n| n as u64),
        format: NoteFormat::try_from(row.get::<_, u8>(8)?)
            .map_err(|_| rusqlite::Error::InvalidQuery)?,
    })
}
pub(crate) fn get_conn(conn: &Connection, id: &Id) -> Result<Note> {
    conn.query_row(
        &format!("SELECT {COLUMNS} FROM private_notes WHERE note_id=?1"),
        [id.as_bytes().as_slice()],
        decode,
    )
    .optional()
    .map_err(|e| map_sqlite(e, "the private note could not be read"))?
    .ok_or_else(|| chur_core::err!(NotFound, "the private note does not exist"))
}
/// Reads one note from the current private catalog.
pub fn get(db: &CatalogDb, id: &Id) -> Result<Note> {
    get_conn(db.connection(), id)
}
/// Creates or replaces one active note under optimistic revision control.
#[expect(
    clippy::too_many_arguments,
    reason = "a CAS save commits all bounded note fields and their explicit format together"
)]
pub fn save(
    db: &mut CatalogDb,
    id: Option<Id>,
    expected: u64,
    title: &str,
    body: &str,
    pinned: bool,
    format: NoteFormat,
    now: u64,
) -> Result<Note> {
    ensure!(
        title.len() <= 256 && body.len() <= 262144,
        ResourceLimitExceeded,
        "the private note exceeds its text limit"
    );
    ensure!(
        !title.contains('\0') && !body.contains('\0'),
        InvalidInput,
        "the private note contains a null character"
    );
    let now = as_sqlite_integer(now, "the note time is out of range")?;
    let revision = as_sqlite_integer(expected, "the revision is out of range")?;
    let note_id = match id {
        Some(id) => id,
        None => {
            ensure!(
                expected == 0,
                InvalidInput,
                "a new note requires revision zero"
            );
            chur_crypto::random::id()?
        }
    };
    db.transaction(|tx|{
        if id.is_some(){
            let old=get_conn(tx,&note_id)?;
            ensure!(old.revision==expected,Conflict,"the private note changed");
            ensure!(old.deleted_ms.is_none(),Conflict,"the private note is in trash");
            if old.title==title && old.body==body && old.pinned==pinned && old.format==format{return Ok(old)}
            ensure!(revision<i64::MAX,ResourceLimitExceeded,"the note revision is exhausted");
            tx.execute("UPDATE private_notes SET title=?2,body=?3,pinned=?4,format=?6,revision=revision+1,updated_ms=max(updated_ms,?5) WHERE note_id=?1",params![note_id.as_bytes().as_slice(),title,body,pinned,now,format as u8]).map_err(|e|map_sqlite(e,"the private note could not be saved"))?;
        }else{
            let count:i64=tx.query_row("SELECT count(*) FROM private_notes",[],|r|r.get(0)).map_err(|e|map_sqlite(e,"the note count could not be read"))?;
            ensure!(count<10000,ResourceLimitExceeded,"the private note count limit was reached");
            tx.execute("INSERT INTO private_notes(note_id,revision,title,body,created_ms,updated_ms,pinned,format) VALUES(?1,1,?2,?3,?4,?4,?5,?6)",params![note_id.as_bytes().as_slice(),title,body,now,pinned,format as u8]).map_err(|e|map_sqlite(e,"the private note could not be created"))?;
        }
        reindex(tx,&note_id)?;schema::bump_generation(tx)?;get_conn(tx,&note_id)
    })
}
pub(crate) fn reindex(conn: &Connection, id: &Id) -> Result<()> {
    let rowid: i64 = conn
        .query_row(
            "SELECT rowid FROM private_notes WHERE note_id=?1",
            [id.as_bytes().as_slice()],
            |r| r.get(0),
        )
        .map_err(|e| map_sqlite(e, "the note search id could not be read"))?;
    conn.execute("DELETE FROM private_note_search WHERE rowid=?1", [rowid])
        .map_err(|e| map_sqlite(e, "the note search row could not be removed"))?;
    conn.execute("INSERT INTO private_note_search(rowid,title,body) SELECT rowid,title,body FROM private_notes WHERE note_id=?1",[id.as_bytes().as_slice()]).map_err(|e|map_sqlite(e,"the note search row could not be written"))?;
    Ok(())
}
fn delete(conn: &Connection, id: &Id) -> Result<()> {
    conn.execute("DELETE FROM private_note_search WHERE rowid=(SELECT rowid FROM private_notes WHERE note_id=?1)",[id.as_bytes().as_slice()]).map_err(|e|map_sqlite(e,"the note search row could not be removed"))?;
    conn.execute(
        "DELETE FROM private_notes WHERE note_id=?1",
        [id.as_bytes().as_slice()],
    )
    .map_err(|e| map_sqlite(e, "the private note could not be removed"))?;
    Ok(())
}
/// PIN=1, UNPIN=2, TRASH=3, RESTORE=4, PURGE=5. All mutations require the displayed revision.
pub fn change(
    db: &mut CatalogDb,
    id: &Id,
    expected: u64,
    action: u8,
    now: u64,
) -> Result<Option<Note>> {
    let time = as_sqlite_integer(now, "the note time is out of range")?;
    db.transaction(|tx|{
        let old=get_conn(tx,id)?;ensure!(old.revision==expected,Conflict,"the private note changed");
        ensure!(expected<i64::MAX as u64,ResourceLimitExceeded,"the note revision is exhausted");
        match action{
            1|2=>{ensure!(old.deleted_ms.is_none(),Conflict,"the private note is in trash");
                if old.pinned==(action==1){return Ok(Some(old))}
                tx.execute("UPDATE private_notes SET pinned=?2,revision=revision+1,updated_ms=max(updated_ms,?3) WHERE note_id=?1",params![id.as_bytes().as_slice(),action==1,time]).map_err(|e|map_sqlite(e,"the private note could not be pinned"))?;},
            3=>{ensure!(old.deleted_ms.is_none(),Conflict,"the private note is already in trash");
                let deadline=now.checked_add(crate::trash::RETENTION_MS).ok_or_else(||chur_core::err!(InvalidInput,"the trash deadline is out of range"))?;
                let deadline=as_sqlite_integer(deadline,"the trash deadline is out of range")?;
                tx.execute("UPDATE private_notes SET deleted_ms=?2,expires_ms=?3,revision=revision+1,updated_ms=max(updated_ms,?2) WHERE note_id=?1",params![id.as_bytes().as_slice(),time,deadline]).map_err(|e|map_sqlite(e,"the private note could not enter trash"))?;},
            4=>{ensure!(old.deleted_ms.is_some_and(|t|now<t.saturating_add(crate::trash::RETENTION_MS)),NotFound,"the private note cannot be restored");
                tx.execute("UPDATE private_notes SET deleted_ms=NULL,expires_ms=NULL,revision=revision+1,updated_ms=max(updated_ms,?2) WHERE note_id=?1",params![id.as_bytes().as_slice(),time]).map_err(|e|map_sqlite(e,"the private note could not be restored"))?;},
            5=>{ensure!(old.deleted_ms.is_some(),Conflict,"only a trashed private note can be removed");delete(tx,id)?;schema::bump_generation(tx)?;return Ok(None)},
            _=>return Err(chur_core::err!(InvalidInput,"the private note action is unknown")),
        }
        schema::bump_generation(tx)?;get_conn(tx,id).map(Some)
    })
}
/// Purges expired local notes; no media or synchronization rows are changed.
pub fn purge_expired(db: &mut CatalogDb, now: u64) -> Result<()> {
    let now = as_sqlite_integer(now, "the note time is out of range")?;
    db.transaction(|tx| {
        let ids = {
            let mut st = tx
                .prepare("SELECT note_id FROM private_notes WHERE expires_ms<=?1")
                .map_err(|e| map_sqlite(e, "the expired notes query failed"))?;
            let rows = st
                .query_map([now], |r| r.get::<_, Vec<u8>>(0))
                .map_err(|e| map_sqlite(e, "the expired notes query failed"))?;
            rows.collect::<rusqlite::Result<Vec<_>>>()
                .map_err(|e| map_sqlite(e, "the expired notes query failed"))?
        };
        for bytes in &ids {
            delete(tx, &Id::from_slice(bytes)?)?;
        }
        if !ids.is_empty() {
            schema::bump_generation(tx)?;
        }
        Ok(())
    })
}
/// Searches literal token prefixes with stable keyset pagination.
pub fn page(
    db: &CatalogDb,
    terms: &str,
    trashed: bool,
    limit: u16,
    cursor: Option<&[u8]>,
) -> Result<Page> {
    ensure!(
        terms.len() <= 256 && (1..=100).contains(&limit),
        InvalidInput,
        "the notes query is outside its limits"
    );
    let tokens: Vec<_> = terms.split_whitespace().collect();
    ensure!(
        tokens.len() <= 32,
        InvalidInput,
        "the notes query has too many terms"
    );
    let expression = zeroize::Zeroizing::new(
        tokens
            .iter()
            .map(|s| format!("\"{}\"*", s.replace('"', "\"\"")))
            .collect::<Vec<_>>()
            .join(" AND "),
    );
    let generation = schema::generation(db)?;
    let digest = chur_crypto::commit::commit(
        b"CHUR\0PRIVATE-NOTES\0QUERY\0V1",
        &[terms.as_bytes(), &[u8::from(trashed)]],
    );
    let (pin, time, id) = if let Some(c) = cursor {
        ensure!(c.len() == 65, InvalidInput, "the notes cursor is malformed");
        ensure!(
            c[..8] == generation.to_be_bytes() && c[8..40] == digest,
            Conflict,
            "the notes cursor is stale"
        );
        ensure!(c[40] <= 1, InvalidInput, "the notes cursor is malformed");
        let time = u64::from_be_bytes(
            c[41..49]
                .try_into()
                .map_err(|_| chur_core::err!(InvalidInput, "the notes cursor is malformed"))?,
        );
        (
            i64::from(c[40]),
            as_sqlite_integer(time, "the cursor time is out of range")?,
            c[49..].to_vec(),
        )
    } else {
        (2, i64::MAX, vec![255; 16])
    };
    let sql = "SELECT n.note_id,n.revision,n.title,substr(n.body,1,160),n.created_ms,n.updated_ms,n.pinned,n.deleted_ms,n.format,(SELECT count(*) FROM private_note_attachments a WHERE a.note_id=n.note_id),CASE WHEN n.format=0 THEN length(CAST(n.title AS BLOB))+2 ELSE 0 END+length(CAST(n.body AS BLOB)) FROM private_notes n WHERE (n.deleted_ms IS NOT NULL)=?1 AND (?2='' OR n.rowid IN (SELECT rowid FROM private_note_search WHERE private_note_search MATCH ?3)) AND (n.pinned,n.updated_ms,n.note_id)<(?4,?5,?6) ORDER BY n.pinned DESC,n.updated_ms DESC,n.note_id DESC LIMIT ?7";
    let mut stmt = db
        .connection()
        .prepare(sql)
        .map_err(|e| map_sqlite(e, "the notes query could not be prepared"))?;
    let rows = stmt
        .query_map(
            params![
                trashed,
                expression.as_str(),
                if expression.is_empty() {
                    "\"unused\""
                } else {
                    expression.as_str()
                },
                pin,
                time,
                id,
                i64::from(limit) + 1
            ],
            |row| {
                Ok(Summary {
                    note: decode(row)?,
                    export_size: row.get::<_, i64>(10)? as u64,
                })
            },
        )
        .map_err(|e| map_sqlite(e, "the notes query failed"))?;
    let mut notes = rows
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(|e| map_sqlite(e, "the notes query could not be read"))?;
    for summary in &mut notes {
        if summary.note.attachment_count > 0 {
            let full = get(db, &summary.note.id)?;
            summary.export_size = crate::note_attachments::export_size(db, &full)?;
        }
    }
    let next_cursor = if notes.len() > usize::from(limit) {
        notes.pop();
        notes.last().map(|last| {
            let n = &last.note;
            let mut c = Vec::with_capacity(65);
            c.extend_from_slice(&generation.to_be_bytes());
            c.extend_from_slice(&digest);
            c.push(u8::from(n.pinned));
            c.extend_from_slice(&n.updated_ms.to_be_bytes());
            c.extend_from_slice(n.id.as_bytes());
            c
        })
    } else {
        None
    };
    Ok(Page { notes, next_cursor })
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
    use super::*;
    use crate::{CatalogKey, CatalogLocation};
    fn db() -> CatalogDb {
        let root = chur_crypto::random::secret::<32>().expect("root");
        let id = chur_crypto::random::id().expect("id");
        let key = CatalogKey::derive(&root, &id).expect("key");
        let mut db = CatalogDb::open(&CatalogLocation::Memory, &key).expect("db");
        schema::open_at_current_version(&mut db, 1).expect("schema");
        db
    }
    fn status<T>(r: Result<T>) -> chur_core::ChurStatus {
        match r {
            Ok(_) => panic!("expected failure"),
            Err(e) => e.status(),
        }
    }
    #[test]
    fn format_changes_are_revisioned_and_archives_preserve_exact_source() {
        let mut db = db();
        let source = "# Heading\n\n- [x] done\n\n```rust\nlet n = 1;\n```\n";
        let plain = save(
            &mut db,
            None,
            0,
            "Title",
            source,
            false,
            NoteFormat::PlainText,
            1,
        )
        .expect("plain");
        assert_eq!(plain.export_size(), (source.len() + 7) as u64);
        let markdown = save(
            &mut db,
            Some(plain.id),
            1,
            "Title",
            source,
            false,
            NoteFormat::Markdown,
            2,
        )
        .expect("format edit");
        assert_eq!(markdown.revision, 2);
        assert_eq!(markdown.export_size(), source.len() as u64);
        assert_eq!(
            status(save(
                &mut db,
                Some(plain.id),
                1,
                "Wrong",
                "lost",
                false,
                NoteFormat::PlainText,
                3
            )),
            chur_core::ChurStatus::Conflict
        );
        let row = page(&db, "", false, 50, None)
            .expect("page")
            .notes
            .remove(0);
        assert_eq!(row.note.format, NoteFormat::Markdown);
        assert_eq!(row.export_size, source.len() as u64);
        let trashed = change(&mut db, &plain.id, 2, 3, 3)
            .expect("trash")
            .expect("note");
        assert_eq!(trashed.format, NoteFormat::Markdown);
        let restored = change(&mut db, &plain.id, 3, 4, 4)
            .expect("restore")
            .expect("note");
        let mut target = self::db();
        let fresh = crate::transfer::copy_note(&mut target, &restored, &[]).expect("archive copy");
        assert_ne!(fresh, plain.id);
        let copied = get(&target, &fresh).expect("copied");
        assert_eq!(copied.format, NoteFormat::Markdown);
        assert_eq!(copied.body, source);
        assert_eq!(copied.revision, 1);
        let empty = save(
            &mut target,
            None,
            0,
            "Empty",
            "",
            false,
            NoteFormat::Markdown,
            5,
        )
        .expect("empty markdown");
        assert_eq!(empty.export_size(), 0);
    }

    #[test]
    fn revisions_search_trash_and_expiry_are_atomic() {
        let mut db = db();
        let a = save(
            &mut db,
            None,
            0,
            "秘密 note",
            "body needle 😀",
            false,
            NoteFormat::PlainText,
            10,
        )
        .expect("create");
        assert_eq!(a.revision, 1);
        assert_eq!(
            page(&db, "needle", false, 10, None)
                .expect("search")
                .notes
                .len(),
            1
        );
        assert_eq!(
            status(save(
                &mut db,
                Some(a.id),
                9,
                "lost",
                "lost",
                false,
                NoteFormat::PlainText,
                11
            )),
            chur_core::ChurStatus::Conflict
        );
        assert_eq!(get(&db, &a.id).expect("unchanged").body, "body needle 😀");
        let same = save(
            &mut db,
            Some(a.id),
            1,
            &a.title,
            &a.body,
            false,
            NoteFormat::PlainText,
            11,
        )
        .expect("same");
        assert_eq!(same.revision, 1);
        let b = change(&mut db, &a.id, 1, 1, 11)
            .expect("pin")
            .expect("note");
        assert_eq!(b.revision, 2);
        assert!(b.pinned);
        let c = change(&mut db, &a.id, 2, 3, 12)
            .expect("trash")
            .expect("note");
        assert_eq!(c.revision, 3);
        assert!(
            page(&db, "needle", false, 10, None)
                .expect("active")
                .notes
                .is_empty()
        );
        assert_eq!(
            page(&db, "needle", true, 10, None)
                .expect("trash search")
                .notes
                .len(),
            1
        );
        let d = change(&mut db, &a.id, 3, 4, 13)
            .expect("restore")
            .expect("note");
        assert_eq!(d.revision, 4);
        assert_eq!(
            status(change(&mut db, &a.id, 4, 5, 14)),
            chur_core::ChurStatus::Conflict
        );
        change(&mut db, &a.id, 4, 3, 14).expect("trash");
        purge_expired(&mut db, 14 + crate::trash::RETENTION_MS).expect("expiry");
        assert_eq!(status(get(&db, &a.id)), chur_core::ChurStatus::NotFound);
        assert!(
            page(&db, "needle", true, 10, None)
                .expect("purged search")
                .notes
                .is_empty()
        );
    }
    #[test]
    fn bounded_unicode_preview_and_cursor_never_return_whole_bodies() {
        let mut db = db();
        let text = "😀".repeat(200);
        let first = save(
            &mut db,
            None,
            0,
            "first",
            &text,
            true,
            NoteFormat::PlainText,
            1,
        )
        .expect("create");
        save(
            &mut db,
            None,
            0,
            "second",
            "second body",
            false,
            NoteFormat::PlainText,
            2,
        )
        .expect("create");
        let page1 = page(&db, "", false, 1, None).expect("page");
        assert_eq!(page1.notes[0].note.id, first.id);
        assert_eq!(page1.notes[0].note.body.chars().count(), 160);
        assert_eq!(page1.notes[0].export_size, 807);
        let cursor = page1.next_cursor.expect("cursor");
        let page2 = page(&db, "", false, 1, Some(&cursor)).expect("next");
        assert_eq!(page2.notes[0].note.title, "second");
        assert!(page2.next_cursor.is_none());
        assert_eq!(
            status(page(&db, "second", false, 1, Some(&cursor))),
            chur_core::ChurStatus::Conflict
        );
        change(&mut db, &first.id, 1, 2, 3).expect("unpin");
        assert_eq!(
            status(page(&db, "", false, 1, Some(&cursor))),
            chur_core::ChurStatus::Conflict
        );
        assert_eq!(
            status(save(
                &mut db,
                None,
                0,
                &"界".repeat(86),
                "",
                false,
                NoteFormat::PlainText,
                1
            )),
            chur_core::ChurStatus::ResourceLimitExceeded
        );
        assert_eq!(
            status(save(
                &mut db,
                None,
                0,
                "",
                &"x".repeat(262145),
                false,
                NoteFormat::PlainText,
                1
            )),
            chur_core::ChurStatus::ResourceLimitExceeded
        );
        assert!(page(&db, "\" OR * !!!", false, 10, None).is_ok());
    }
}
