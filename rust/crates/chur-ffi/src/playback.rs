//! Bounded scalar access to SQLCipher-owned playback positions.
#![expect(
    unsafe_code,
    reason = "the C ABI validates caller pointers for each synchronous call"
)]

use crate::{
    api,
    panic::guard_status_for,
    records::ChurObjectRefV1,
    registry::{self, Entry, Handle, Kind},
};
use chur_catalog::playback;
use chur_core::Id;

/// Reads a local position. An absent or stale value is zero.
/// # Safety
/// `object` is null or points to a valid aligned reference, and `out_position_ms`
/// is null or points to writable aligned u64 storage. Both live for this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn chur_playback_position_get(
    session: Handle,
    object: *const ChurObjectRefV1,
    out_position_ms: *mut u64,
) -> api::Status {
    guard_status_for(session, || {
        // SAFETY: the caller supplies writable aligned output storage for this call.
        unsafe { api::write_out(out_position_ms, 0)? };
        // SAFETY: the caller supplies a valid aligned object reference for this call.
        let object = unsafe { api::read_request(object)? };
        let id = Id::from_slice(&object.object_id)?;
        let entry = registry::get(session, Kind::Session)?;
        let Entry::Session {
            session: guarded, ..
        } = entry.as_ref()
        else {
            return Err(chur_core::err!(InvalidInput, "the handle is not a session"));
        };
        let guard = registry::lock(guarded);
        let value = playback::get(guard.catalog_ref()?, &id)?;
        // SAFETY: the output storage remains exclusively writable through this call.
        unsafe { api::write_out(out_position_ms, value) }
    })
}

/// Saves one bounded local position. No caller pointer is retained.
/// # Safety
/// `object` is null or points to a valid aligned reference that lives for this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn chur_playback_position_put(
    session: Handle,
    object: *const ChurObjectRefV1,
    position_ms: u64,
    duration_ms: u64,
) -> api::Status {
    guard_status_for(session, || {
        // SAFETY: the caller supplies a valid aligned object reference for this call.
        let object = unsafe { api::read_request(object)? };
        let id = Id::from_slice(&object.object_id)?;
        let entry = registry::get(session, Kind::Session)?;
        let Entry::Session {
            session: guarded, ..
        } = entry.as_ref()
        else {
            return Err(chur_core::err!(InvalidInput, "the handle is not a session"));
        };
        let mut guard = registry::lock(guarded);
        playback::put(guard.catalog()?, &id, position_ms, duration_ms)
    })
}
