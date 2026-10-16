//! Bounded control records for the SQLCipher-owned local content index.
#![expect(
    unsafe_code,
    reason = "the C ABI validates caller pointers for each call"
)]
use crate::{
    api::{self, Status},
    panic::guard_status_for,
    registry::{self, Entry, Handle, Kind},
};
use chur_catalog::content_index;
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
            .ok_or_else(|| chur_core::err!(InvalidInput, "the content request is malformed"))?;
        ensure!(
            end <= self.bytes.len(),
            InvalidInput,
            "the content request is truncated"
        );
        let out = &self.bytes[self.at..end];
        self.at = end;
        Ok(out)
    }
    fn byte(&mut self) -> Result<u8> {
        Ok(self.take(1)?[0])
    }
    fn number(&mut self) -> Result<u32> {
        Ok(u32::from_be_bytes(self.take(4)?.try_into().map_err(
            |_| chur_core::err!(InvalidInput, "the content number is malformed"),
        )?))
    }
    fn id(&mut self) -> Result<Id> {
        Id::from_slice(self.take(16)?)
    }
    fn end(&self) -> Result<()> {
        ensure!(
            self.at == self.bytes.len(),
            InvalidInput,
            "the content request has trailing bytes"
        );
        Ok(())
    }
}
/// # Safety
/// All pointers follow the exported synchronous control contract.
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
        // SAFETY: the exported caller provides a writable count for this call.
        unsafe { api::write_out(written, 0)? };
        ensure!(
            len <= content_index::TEXT_MAX + 64,
            ResourceLimitExceeded,
            "the content request exceeds its bound"
        );
        ensure!(
            len > 0 && !request.is_null(),
            InvalidInput,
            "the content request pointer is null or empty"
        );
        ensure!(
            capacity >= CAPACITY,
            ResourceLimitExceeded,
            "the content output buffer is too small"
        );
        // SAFETY: request is non-null, bounded, and readable for the synchronous call.
        let input = unsafe { core::slice::from_raw_parts(request, len) };
        // SAFETY: the caller guarantees a writable output buffer of at least capacity bytes.
        let output = unsafe { api::borrow_bytes_mut(destination, CAPACITY)? };
        let mut r = Reader {
            bytes: input,
            at: 0,
        };
        ensure!(
            r.byte()? == 1,
            InvalidInput,
            "the content request version is unsupported"
        );
        let entry = registry::get(session, Kind::Session)?;
        let Entry::Session {
            session: guarded, ..
        } = entry.as_ref()
        else {
            return Err(chur_core::err!(InvalidInput, "the handle is not a session"));
        };
        let mut guard = registry::lock(guarded);
        let mut out = Zeroizing::new(Vec::new());
        body(guard.catalog()?, &mut r, &mut out)?;
        ensure!(
            out.len() <= CAPACITY,
            ResourceLimitExceeded,
            "the content response exceeds its bound"
        );
        output[..out.len()].copy_from_slice(&out);
        // SAFETY: the exported caller provides a writable count for this call.
        unsafe { api::write_out(written, out.len()) }
    })
}
/// Lists at most one hundred current original streams by opaque object ID.
/// # Safety
/// The request must be readable for `len` bytes. The destination must be
/// writable for `capacity` bytes and the count pointer must be writable.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn chur_content_index_candidates(
    session: Handle,
    request: *const u8,
    len: usize,
    destination: *mut u8,
    capacity: usize,
    written: *mut usize,
) -> Status {
    // SAFETY: caller forwards the documented synchronous buffer contract.
    unsafe {
        control(
            session,
            request,
            len,
            destination,
            capacity,
            written,
            |db, r, out| {
                let rebuild = r.byte()?;
                ensure!(
                    rebuild <= 1,
                    InvalidInput,
                    "the content rebuild flag is invalid"
                );
                let after_bytes = r.take(16)?;
                let after = if after_bytes == [0u8; 16] {
                    None
                } else {
                    Some(Id::from_slice(after_bytes)?)
                };
                let limit = r.number()?;
                r.end()?;
                let items = content_index::candidates(db, after.as_ref(), limit, rebuild == 1)?;
                out.push(1);
                out.extend_from_slice(&(items.len() as u32).to_be_bytes());
                for item in items {
                    out.extend_from_slice(item.object_id.as_bytes());
                    out.extend_from_slice(item.primary_stream_id.as_bytes());
                    out.push(item.media_class.value());
                    out.extend_from_slice(&item.plaintext_size.to_be_bytes());
                    out.extend_from_slice(&(item.content_type.len() as u32).to_be_bytes());
                    out.extend_from_slice(item.content_type.as_bytes());
                }
                Ok(())
            },
        )
    }
}
/// Commits bounded text only if its current original stream still matches.
/// # Safety
/// All pointers follow the same synchronous buffer contract as candidates.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn chur_content_index_put(
    session: Handle,
    request: *const u8,
    len: usize,
    destination: *mut u8,
    capacity: usize,
    written: *mut usize,
) -> Status {
    // SAFETY: caller forwards the documented synchronous buffer contract.
    unsafe {
        control(
            session,
            request,
            len,
            destination,
            capacity,
            written,
            |db, r, _| {
                let object = r.id()?;
                let source = r.id()?;
                let status = r.byte()?;
                let engine = r.number()?;
                let n = r.number()? as usize;
                ensure!(
                    n <= content_index::TEXT_MAX,
                    ResourceLimitExceeded,
                    "the content text exceeds its bound"
                );
                let text = core::str::from_utf8(r.take(n)?)
                    .map_err(|_| chur_core::err!(InvalidInput, "the content text is not UTF-8"))?;
                r.end()?;
                content_index::put(db, &object, &source, status, engine, text)
            },
        )
    }
}
/// Removes derived content text without removing metadata search.
/// # Safety
/// All pointers follow the same synchronous buffer contract as candidates.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn chur_content_index_clear(
    session: Handle,
    request: *const u8,
    len: usize,
    destination: *mut u8,
    capacity: usize,
    written: *mut usize,
) -> Status {
    // SAFETY: caller forwards the documented synchronous buffer contract.
    unsafe {
        control(
            session,
            request,
            len,
            destination,
            capacity,
            written,
            |db, r, _| {
                r.end()?;
                content_index::clear(db)
            },
        )
    }
}
