//! Phase 4 collection-sharing identity C ABI.

use chur_core::{ChurStatus, Error, Id, ensure};
use chur_crypto::Key;
use chur_format::codec::Writer;
use chur_sync_protocol::{
    grant::PermissionProfile, identity::fingerprint, membership::EnrollmentRecord,
};

use crate::api::{Status, borrow_bytes, borrow_bytes_mut, write_out};
use crate::panic::guard_status;
use crate::registry::{self, Entry, Handle, Kind};

const RECORD_VERSION_V1: u16 = 1;

fn permission_profile(value: u8) -> Result<PermissionProfile, Error> {
    match value {
        0x01 => Ok(PermissionProfile::Read),
        0x03 => Ok(PermissionProfile::Contribute),
        0x07 => Ok(PermissionProfile::ManageMembers),
        _ => Err(Error::new(
            ChurStatus::UnsupportedVersion,
            "sharing permission profile is not supported",
        )),
    }
}

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

/// Prepares one recipient membership and HPKE collection-key grant.
///
/// # Safety
///
/// Fixed and variable input pointers cover their declared readable ranges.
/// `destination` covers `capacity` writable bytes and `bytes_written` points
/// to one writable, aligned `size_t` for this call.
#[unsafe(no_mangle)]
#[expect(
    unsafe_code,
    reason = "FFI_CONTRACT.md section 6.10 fixes this exported symbol"
)]
pub unsafe extern "C" fn chur_sharing_prepare(
    session: Handle,
    collection_id: *const u8,
    recipient_enrollment: *const u8,
    recipient_enrollment_length: u32,
    permissions: u8,
    fingerprint_verified: u8,
    destination: *mut u8,
    capacity: usize,
    bytes_written: *mut usize,
) -> Status {
    guard_status(|| {
        // SAFETY: the caller guarantees the writable out-parameter above.
        let _ = unsafe { write_out(bytes_written, 0usize) };
        // SAFETY: the caller guarantees the fixed input range above.
        let collection_id = Id::from_slice(unsafe { borrow_bytes(collection_id, 16)? })?;
        // SAFETY: the caller guarantees the variable input range above.
        let enrollment = EnrollmentRecord::decode(unsafe {
            borrow_bytes(recipient_enrollment, recipient_enrollment_length)?
        })?;
        let permissions = permission_profile(permissions)?;
        let fingerprint_verified = match fingerprint_verified {
            0 => false,
            1 => true,
            _ => {
                return Err(Error::new(
                    ChurStatus::InvalidInput,
                    "fingerprint verification must be zero or one",
                ));
            }
        };
        let entry = registry::get(session, Kind::Session)?;
        let Entry::Session { session, .. } = entry.as_ref() else {
            return Err(Error::new(
                ChurStatus::InvalidInput,
                "the handle is of another type",
            ));
        };
        let encoded = {
            let mut session = registry::lock(session);
            let source_vault_id = session.vault_id();
            let root = Key::new(*session.root_secret().expose());
            let prepared = chur_catalog::sharing_service::prepare_share(
                session.catalog()?,
                &root,
                source_vault_id,
                collection_id,
                &enrollment,
                permissions,
                fingerprint_verified,
            )?;
            let mut writer = Writer::new();
            writer.u16(RECORD_VERSION_V1);
            writer.variable(&prepared.membership().encode())?;
            writer.variable(&prepared.membership_operation().encode())?;
            writer.variable(&prepared.grant().encode())?;
            writer.variable(&prepared.grant_operation().encode())?;
            writer.finish()
        };
        // SAFETY: the caller guarantees the destination range above.
        let buffer = unsafe { borrow_bytes_mut(destination, capacity)? };
        ensure!(
            encoded.len() <= buffer.len(),
            ResourceLimitExceeded,
            "the destination buffer is smaller than the prepared share record"
        );
        buffer[..encoded.len()].copy_from_slice(&encoded);
        // SAFETY: the caller guarantees the writable out-parameter above.
        unsafe { write_out(bytes_written, encoded.len()) }
    })
}
