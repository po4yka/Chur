//! Bounded private-note control records and cancellable plaintext export.
#![expect(
    unsafe_code,
    reason = "the C ABI validates caller pointers for each call"
)]
use crate::{
    api::{self, Status},
    operation::{Operation, OperationKind, Stage},
    panic::guard_status_for,
    registry::{self, Entry, Handle, Kind},
};
use chur_catalog::notes;
use chur_core::{Id, Result, ensure};
use zeroize::Zeroizing;
const CAPACITY: usize = 1024 * 1024;
struct Reader<'a> {
    bytes: &'a [u8],
    at: usize,
}
impl<'a> Reader<'a> {
    fn take(&mut self, n: usize) -> Result<&'a [u8]> {
        let end = self
            .at
            .checked_add(n)
            .ok_or_else(|| chur_core::err!(InvalidInput, "the note request is malformed"))?;
        ensure!(
            end <= self.bytes.len(),
            InvalidInput,
            "the note request is truncated"
        );
        let out = &self.bytes[self.at..end];
        self.at = end;
        Ok(out)
    }
    fn byte(&mut self) -> Result<u8> {
        Ok(self.take(1)?[0])
    }
    fn number(&mut self) -> Result<u64> {
        Ok(u64::from_be_bytes(self.take(8)?.try_into().map_err(
            |_| chur_core::err!(InvalidInput, "the note number is malformed"),
        )?))
    }
    fn string(&mut self, max: usize) -> Result<&'a str> {
        let len = u32::from_be_bytes(
            self.take(4)?
                .try_into()
                .map_err(|_| chur_core::err!(InvalidInput, "the note length is malformed"))?,
        ) as usize;
        ensure!(
            len <= max,
            ResourceLimitExceeded,
            "the note text exceeds its bound"
        );
        core::str::from_utf8(self.take(len)?)
            .map_err(|_| chur_core::err!(InvalidInput, "the note text is not UTF-8"))
    }
    fn end(self) -> Result<()> {
        ensure!(
            self.at == self.bytes.len(),
            InvalidInput,
            "the note request has trailing bytes"
        );
        Ok(())
    }
}
fn string(out: &mut Vec<u8>, s: &str) {
    out.extend_from_slice(&(s.len() as u32).to_be_bytes());
    out.extend_from_slice(s.as_bytes());
}
fn encode(out: &mut Vec<u8>, n: &notes::Note, text: &str, size: u64) {
    out.extend_from_slice(n.id.as_bytes());
    out.extend_from_slice(&n.revision.to_be_bytes());
    out.extend_from_slice(&n.created_ms.to_be_bytes());
    out.extend_from_slice(&n.updated_ms.to_be_bytes());
    out.extend_from_slice(&n.deleted_ms.unwrap_or(u64::MAX).to_be_bytes());
    out.push(u8::from(n.pinned));
    out.push(n.format as u8);
    out.extend_from_slice(&size.to_be_bytes());
    string(out, &n.title);
    string(out, text);
}
fn with_catalog<T>(
    session: Handle,
    body: impl FnOnce(&mut chur_catalog::CatalogDb) -> Result<T>,
) -> Result<T> {
    let entry = registry::get(session, Kind::Session)?;
    let Entry::Session {
        session: guarded, ..
    } = entry.as_ref()
    else {
        return Err(chur_core::err!(InvalidInput, "the handle is not a session"));
    };
    let mut guard = registry::lock(guarded);
    body(guard.catalog()?)
}
/// The caller provides a readable bounded request and a writable output buffer.
/// All output pointers must remain valid for this synchronous call.
unsafe fn control(
    session: Handle,
    request: *const u8,
    len: usize,
    destination: *mut u8,
    capacity: usize,
    written: *mut usize,
    body: impl FnOnce(&mut chur_catalog::CatalogDb, &mut Reader<'_>, &mut Vec<u8>) -> Result<()>,
) -> Status {
    guard_status_for(session, || {
        // SAFETY: the exported function delegates its documented pointer contract.
        unsafe { api::write_out(written, 0)? };
        ensure!(
            len <= CAPACITY,
            ResourceLimitExceeded,
            "the note request exceeds its bound"
        );
        ensure!(
            capacity >= CAPACITY,
            ResourceLimitExceeded,
            "the note output buffer is too small"
        );
        ensure!(
            len == 0 || !request.is_null(),
            InvalidInput,
            "the note request pointer is null"
        );
        // SAFETY: len is bounded to 1 MiB, the pointer is non-null for a nonempty
        // request, and the caller guarantees readable, unaliased bytes for the call.
        let input = if len == 0 {
            &[]
        } else {
            unsafe { core::slice::from_raw_parts(request, len) }
        };
        // SAFETY: capacity was checked above; only the fixed record bound is borrowed.
        let output = unsafe { api::borrow_bytes_mut(destination, CAPACITY)? };
        let mut reader = Reader {
            bytes: input,
            at: 0,
        };
        let mut record = Zeroizing::new(Vec::new());
        with_catalog(session, |db| {
            notes::purge_expired(db, api::now_ms())?;
            body(db, &mut reader, &mut record)
        })?;
        reader.end()?;
        ensure!(
            record.len() <= capacity,
            ResourceLimitExceeded,
            "the note output exceeds its bound"
        );
        output[..record.len()].copy_from_slice(&record);
        unsafe { api::write_out(written, record.len()) }
    })
}
/// Lists bounded summaries. Request: trash u8, limit u64, terms u32+UTF8, cursor u32+bytes.
/// # Safety
/// Request and output pointers follow `control`'s synchronous contract.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn chur_private_notes_query(
    session: Handle,
    request: *const u8,
    len: usize,
    destination: *mut u8,
    capacity: usize,
    written: *mut usize,
) -> Status {
    unsafe {
        control(
            session,
            request,
            len,
            destination,
            capacity,
            written,
            |db, r, out| {
                let trash = r.byte()?;
                ensure!(trash <= 1, InvalidInput, "the note trash flag is invalid");
                let limit = u16::try_from(r.number()?)
                    .map_err(|_| chur_core::err!(InvalidInput, "the note page limit is invalid"))?;
                let terms = r.string(256)?;
                let n = u32::from_be_bytes(
                    r.take(4)?
                        .try_into()
                        .map_err(|_| chur_core::err!(InvalidInput, "the cursor is malformed"))?,
                ) as usize;
                ensure!(
                    n == 0 || n == 65,
                    InvalidInput,
                    "the note cursor is malformed"
                );
                let cursor = r.take(n)?;
                ensure!(
                    r.at == r.bytes.len(),
                    InvalidInput,
                    "the request has trailing bytes"
                );
                let page = notes::page(
                    db,
                    terms,
                    trash == 1,
                    limit,
                    if n == 0 { None } else { Some(cursor) },
                )?;
                out.extend_from_slice(&(page.notes.len() as u32).to_be_bytes());
                for item in page.notes {
                    encode(out, &item.note, &item.note.body, item.export_size);
                }
                let cursor = page.next_cursor.unwrap_or_default();
                out.extend_from_slice(&(cursor.len() as u32).to_be_bytes());
                out.extend_from_slice(&cursor);
                Ok(())
            },
        )
    }
}
/// Gets one note. Request is exactly its sixteen-byte id.
/// # Safety
/// Request and output pointers follow `control`'s synchronous contract.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn chur_private_note_get(
    session: Handle,
    request: *const u8,
    len: usize,
    destination: *mut u8,
    capacity: usize,
    written: *mut usize,
) -> Status {
    unsafe {
        control(
            session,
            request,
            len,
            destination,
            capacity,
            written,
            |db, r, out| {
                let id = Id::from_slice(r.take(16)?)?;
                ensure!(
                    r.at == r.bytes.len(),
                    InvalidInput,
                    "the request has trailing bytes"
                );
                let n = notes::get(db, &id)?;
                encode(out, &n, &n.body, n.export_size());
                Ok(())
            },
        )
    }
}
/// Saves under CAS. Request: id16 (zero creates), expected u64, pinned u8, format u8, title/body u32+UTF8.
/// # Safety
/// Request and output pointers follow `control`'s synchronous contract.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn chur_private_note_save(
    session: Handle,
    request: *const u8,
    len: usize,
    destination: *mut u8,
    capacity: usize,
    written: *mut usize,
) -> Status {
    unsafe {
        control(
            session,
            request,
            len,
            destination,
            capacity,
            written,
            |db, r, out| {
                let bytes = r.take(16)?;
                let id = if bytes.iter().all(|b| *b == 0) {
                    None
                } else {
                    Some(Id::from_slice(bytes)?)
                };
                let expected = r.number()?;
                let pin = r.byte()?;
                ensure!(pin <= 1, InvalidInput, "the pin flag is invalid");
                let format = notes::NoteFormat::try_from(r.byte()?)?;
                let title = r.string(256)?;
                let body = r.string(262144)?;
                ensure!(
                    r.at == r.bytes.len(),
                    InvalidInput,
                    "the request has trailing bytes"
                );
                let n = notes::save(
                    db,
                    id,
                    expected,
                    title,
                    body,
                    pin == 1,
                    format,
                    api::now_ms(),
                )?;
                encode(out, &n, &n.body, n.export_size());
                Ok(())
            },
        )
    }
}
/// Changes one note. Request: id16, expected u64, action u8 (1..5). Purge returns an empty record.
/// # Safety
/// Request and output pointers follow `control`'s synchronous contract.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn chur_private_note_change(
    session: Handle,
    request: *const u8,
    len: usize,
    destination: *mut u8,
    capacity: usize,
    written: *mut usize,
) -> Status {
    unsafe {
        control(
            session,
            request,
            len,
            destination,
            capacity,
            written,
            |db, r, out| {
                let id = Id::from_slice(r.take(16)?)?;
                let expected = r.number()?;
                let action = r.byte()?;
                ensure!(
                    r.at == r.bytes.len(),
                    InvalidInput,
                    "the request has trailing bytes"
                );
                if let Some(n) = notes::change(db, &id, expected, action, api::now_ms())? {
                    encode(out, &n, &n.body, n.export_size());
                }
                Ok(())
            },
        )
    }
}
/// Starts an owned cancellable UTF-8 export: Markdown body, or plain title/two LF/body.
/// The worker releases the session mutex before it writes to the provider.
/// # Safety
/// `id` reads sixteen bytes, `out` writes one handle, and `fd` is an open writable descriptor.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn chur_private_note_export_begin(
    session: Handle,
    id: *const u8,
    expected: u64,
    fd: core::ffi::c_int,
    out: *mut Handle,
) -> Status {
    guard_status_for(session, || {
        unsafe { api::write_out(out, 0)? };
        let id = Id::from_slice(unsafe { api::borrow_bytes(id, 16)? })?;
        // Validate the descriptor before retaining a plaintext snapshot.
        let mut destination = unsafe { api::duplicate_descriptor(fd)? };
        let bytes = with_catalog(session, |db| {
            notes::purge_expired(db, api::now_ms())?;
            let n = notes::get(db, &id)?;
            ensure!(n.revision == expected, Conflict, "the private note changed");
            let mut b = Zeroizing::new(Vec::with_capacity(n.export_size() as usize));
            if n.format == notes::NoteFormat::PlainText {
                b.extend_from_slice(n.title.as_bytes());
                b.extend_from_slice(b"\n\n");
            }
            b.extend_from_slice(n.body.as_bytes());
            Ok(b)
        })?;
        let operation =
            Operation::spawn(OperationKind::Export, bytes.len() as u64, move |shared| {
                use std::io::Write;
                let mut done = 0;
                for chunk in bytes.chunks(4096) {
                    ensure!(
                        !shared.cancelled(),
                        Cancelled,
                        "the private note export was cancelled"
                    );
                    destination.write_all(chunk).map_err(|_| {
                        chur_core::err!(IoFailure, "the private note export could not be written")
                    })?;
                    done += chunk.len() as u64;
                    shared.advance(done, Stage::Running);
                }
                ensure!(
                    !shared.cancelled(),
                    Cancelled,
                    "the private note export was cancelled"
                );
                destination.flush().map_err(|_| {
                    chur_core::err!(IoFailure, "the private note export could not be flushed")
                })?;
                ensure!(
                    !shared.cancelled(),
                    Cancelled,
                    "the private note export was cancelled"
                );
                shared.advance(done, Stage::Committing);
                Ok(None)
            })?;
        let handle = registry::insert(Entry::Operation {
            owner: session,
            operation,
        })?;
        unsafe { api::write_out(out, handle) }
    })
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
    use super::*;
    use crate::records::{ChurCreateRequestV1, ChurRuntimeConfigV1};
    use std::os::fd::AsRawFd;

    #[test]
    fn trashed_note_exports_exact_utf8_but_stale_expired_and_purged_notes_do_not() {
        let root = std::env::temp_dir().join(format!(
            "chur-note-export-{}",
            chur_crypto::random::id().unwrap().to_hex()
        ));
        std::fs::create_dir(&root).unwrap();
        let path = root.to_str().unwrap().as_bytes();
        let config = ChurRuntimeConfigV1 {
            root_path: path.as_ptr(),
            root_path_length: path.len() as u32,
        };
        let password = b"private note export fixture password";
        let request = ChurCreateRequestV1 {
            password: password.as_ptr(),
            password_length: password.len() as u32,
            memory_kib: 0,
            iterations: 0,
            parallelism: 0,
        };
        let mut runtime = 0;
        let mut creation = 0;
        let mut session = 0;
        unsafe {
            assert_eq!(api::chur_runtime_open(&config, &mut runtime), 0);
            assert_eq!(
                crate::product::chur_vault_create_begin(runtime, &request, &mut creation),
                0
            );
            assert_eq!(
                crate::product::chur_vault_creation_activate(creation, &mut session),
                0
            );
        }
        let now = api::now_ms();
        let note = with_catalog(session, |db| {
            let note = notes::save(
                db,
                None,
                0,
                "Заметка",
                "Текст\nwith emoji 🗒",
                false,
                notes::NoteFormat::PlainText,
                now,
            )?;
            Ok(notes::change(db, &note.id, note.revision, 3, now)?.unwrap())
        })
        .unwrap();
        let destination = root.join("note.txt");
        let output = std::fs::File::create(&destination).unwrap();
        let mut operation = 0;
        assert_eq!(
            unsafe {
                chur_private_note_export_begin(
                    session,
                    note.id.as_bytes().as_ptr(),
                    note.revision,
                    output.as_raw_fd(),
                    &mut operation,
                )
            },
            0
        );
        let entry = registry::get(operation, Kind::Operation).unwrap();
        let Entry::Operation {
            operation: worker, ..
        } = entry.as_ref()
        else {
            panic!("operation")
        };
        worker.join();
        assert_eq!(worker.poll().status, 0);
        assert_eq!(
            std::fs::read(&destination).unwrap(),
            "Заметка\n\nТекст\nwith emoji 🗒".as_bytes()
        );
        assert_eq!(unsafe { api::chur_operation_close(operation) }, 0);
        drop(entry);
        drop(output);

        let markdown = with_catalog(session, |db| {
            notes::save(
                db,
                None,
                0,
                "Omitted title",
                "# Source\n\n- [x] exact\n",
                false,
                notes::NoteFormat::Markdown,
                now,
            )
        })
        .unwrap();
        let markdown_path = root.join("note.md");
        let markdown_output = std::fs::File::create(&markdown_path).unwrap();
        let mut markdown_operation = 0;
        assert_eq!(
            unsafe {
                chur_private_note_export_begin(
                    session,
                    markdown.id.as_bytes().as_ptr(),
                    markdown.revision,
                    markdown_output.as_raw_fd(),
                    &mut markdown_operation,
                )
            },
            0
        );
        let markdown_entry = registry::get(markdown_operation, Kind::Operation).unwrap();
        let Entry::Operation {
            operation: markdown_worker,
            ..
        } = markdown_entry.as_ref()
        else {
            panic!("operation")
        };
        markdown_worker.join();
        assert_eq!(markdown_worker.poll().status, 0);
        assert_eq!(
            std::fs::read(&markdown_path).unwrap(),
            markdown.body.as_bytes()
        );
        assert_eq!(
            std::fs::metadata(&markdown_path).unwrap().len(),
            markdown.export_size()
        );
        assert_eq!(unsafe { api::chur_operation_close(markdown_operation) }, 0);
        drop(markdown_entry);
        drop(markdown_output);

        let expired = with_catalog(session, |db| {
            let old = now - chur_catalog::trash::RETENTION_MS - 1;
            let note = notes::save(
                db,
                None,
                0,
                "expired",
                "body",
                false,
                notes::NoteFormat::PlainText,
                old,
            )?;
            Ok(notes::change(db, &note.id, note.revision, 3, old)?.unwrap())
        })
        .unwrap();
        let purged = with_catalog(session, |db| {
            let note = notes::save(
                db,
                None,
                0,
                "purged",
                "body",
                false,
                notes::NoteFormat::PlainText,
                now,
            )?;
            let note = notes::change(db, &note.id, note.revision, 3, now)?.unwrap();
            notes::change(db, &note.id, note.revision, 5, now)?;
            Ok(note)
        })
        .unwrap();
        for (note, revision, status) in [
            (&note, note.revision - 1, chur_core::ChurStatus::Conflict),
            (&expired, expired.revision, chur_core::ChurStatus::NotFound),
            (&purged, purged.revision, chur_core::ChurStatus::NotFound),
        ] {
            let output = std::fs::File::create(&destination).unwrap();
            let mut operation = 99;
            assert_eq!(
                unsafe {
                    chur_private_note_export_begin(
                        session,
                        note.id.as_bytes().as_ptr(),
                        revision,
                        output.as_raw_fd(),
                        &mut operation,
                    )
                },
                status as i32
            );
            assert_eq!(operation, 0);
            assert_eq!(output.metadata().unwrap().len(), 0);
        }
        assert_eq!(unsafe { api::chur_session_close(session) }, 0);
        assert_eq!(unsafe { api::chur_runtime_close(runtime) }, 0);
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn undersized_or_null_output_never_reaches_a_mutation() {
        let mut written = 999usize;
        let mut called = false;
        // SAFETY: the zero-length request/output are not dereferenced and written is live.
        let status = unsafe {
            control(
                0,
                core::ptr::null(),
                0,
                core::ptr::null_mut(),
                0,
                &mut written,
                |_, _, _| {
                    called = true;
                    Ok(())
                },
            )
        };
        assert_eq!(status, chur_core::ChurStatus::ResourceLimitExceeded as i32);
        assert!(!called);
        assert_eq!(written, 0);
        // A claimed sufficient capacity with no memory is refused before opening a catalog.
        let status = unsafe {
            control(
                0,
                core::ptr::null(),
                0,
                core::ptr::null_mut(),
                CAPACITY,
                &mut written,
                |_, _, _| {
                    called = true;
                    Ok(())
                },
            )
        };
        assert_eq!(status, chur_core::ChurStatus::InvalidInput as i32);
        assert!(!called);
    }
}
