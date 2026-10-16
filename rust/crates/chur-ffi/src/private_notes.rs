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
/// Saves under CAS. Request: id16 (zero creates), expected u64, pinned u8, title/body u32+UTF8.
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
                let title = r.string(256)?;
                let body = r.string(262144)?;
                ensure!(
                    r.at == r.bytes.len(),
                    InvalidInput,
                    "the request has trailing bytes"
                );
                let n = notes::save(db, id, expected, title, body, pin == 1, api::now_ms())?;
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
/// Starts an owned cancellable plaintext UTF-8 export: title, two LF bytes, body.
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
            ensure!(
                n.revision == expected && n.deleted_ms.is_none(),
                Conflict,
                "the private note changed"
            );
            let mut b = Zeroizing::new(Vec::with_capacity(n.export_size() as usize));
            b.extend_from_slice(n.title.as_bytes());
            b.extend_from_slice(b"\n\n");
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
