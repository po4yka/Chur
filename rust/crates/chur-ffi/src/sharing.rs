//! Phase 4 collection-sharing identity C ABI.

use chur_core::{ChurStatus, Error, ensure};
use chur_crypto::Key;
use chur_format::codec::Writer;
use chur_sync_protocol::identity::fingerprint;

use crate::api::{Status, borrow_bytes_mut, write_out};
use crate::panic::guard_status;
use crate::registry::{self, Entry, Handle, Kind};

const RECORD_VERSION_V1: u16 = 1;

/// Idempotently provisions or returns the ordinary local sharing identity.
///
/// # Safety
///
/// `destination` covers `capacity` writable bytes and `bytes_written` points
/// to one writable, aligned `size_t` for this call.
#[unsafe(no_mangle)]
#[expect(
    unsafe_code,
    reason = "FFI_CONTRACT.md section 6.9 fixes this exported symbol"
)]
pub unsafe extern "C" fn chur_sharing_identity(
    session: Handle,
    destination: *mut u8,
    capacity: usize,
    bytes_written: *mut usize,
) -> Status {
    guard_status(|| {
        // SAFETY: the caller guarantees the writable out-parameter above.
        let _ = unsafe { write_out(bytes_written, 0usize) };
        let entry = registry::get(session, Kind::Session)?;
        let Entry::Session { session, .. } = entry.as_ref() else {
            return Err(Error::new(
                ChurStatus::InvalidInput,
                "the handle is of another type",
            ));
        };
        let encoded = {
            let mut session = registry::lock(session);
            let vault_id = session.vault_id();
            let root = Key::new(*session.root_secret().expose());
            let (enrollment, operation) = chur_catalog::sync_receive::provision_local_identity(
                session.catalog()?,
                &root,
                vault_id,
            )?;
            let display = fingerprint(
                enrollment.vault_id(),
                enrollment.device_id(),
                enrollment.signing_public_key(),
                enrollment.hpke_public_key(),
            );
            let mut writer = Writer::new();
            writer
                .u16(RECORD_VERSION_V1)
                .id(enrollment.vault_id())
                .id(enrollment.device_id())
                .fixed(enrollment.signing_public_key())
                .fixed(enrollment.hpke_public_key());
            writer.variable(display.as_bytes())?;
            writer.variable(&enrollment.encode())?;
            writer.variable(&operation.encode())?;
            writer.finish()
        };
        // SAFETY: the caller guarantees the destination range above.
        let buffer = unsafe { borrow_bytes_mut(destination, capacity)? };
        ensure!(
            encoded.len() <= buffer.len(),
            ResourceLimitExceeded,
            "the destination buffer is smaller than the sharing identity record"
        );
        buffer[..encoded.len()].copy_from_slice(&encoded);
        // SAFETY: the caller guarantees the writable out-parameter above.
        unsafe { write_out(bytes_written, encoded.len()) }
    })
}
