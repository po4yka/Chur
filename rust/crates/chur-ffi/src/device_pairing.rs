//! User-approved own-device pairing through bounded opaque control records.

use chur_catalog::{CatalogDb, device_pairing};
use chur_core::{ChurStatus, Error, Id, Result, ensure};
use chur_crypto::Key;
use chur_format::codec::{Reader, Writer};
use chur_sync_protocol::device_pairing::PairingRequest;
use zeroize::Zeroizing;

use crate::api::{Status, borrow_bytes_mut, borrow_large, write_out};
use crate::panic::guard_status_for;
use crate::registry::{self, Entry, Handle, Kind};

const INPUT_MAX: u32 = chur_sync_protocol::device_pairing::APPROVAL_MAX as u32;

/// Prepares a durable fresh-device request without replacing the current identity.
///
/// # Safety
/// All pointers cover their declared ranges; `bytes_written` is writable and aligned.
#[unsafe(no_mangle)]
#[expect(unsafe_code, reason = "the pairing C ABI borrows caller-owned buffers")]
pub unsafe extern "C" fn chur_device_pairing_prepare(
    session: Handle,
    input: *const u8,
    input_length: u32,
    destination: *mut u8,
    capacity: usize,
    bytes_written: *mut usize,
) -> Status {
    // SAFETY: the caller guarantees the input, output, and out-parameter ranges above.
    unsafe {
        pairing_call(
            session,
            input,
            input_length,
            destination,
            capacity,
            bytes_written,
            |db, root, vault, input| {
                ensure!(
                    input.is_empty(),
                    NonCanonicalEncoding,
                    "pairing preparation has no input"
                );
                let request = device_pairing::prepare_request(db, root, vault)?;
                let mut writer = Writer::new();
                writer.u16(2);
                writer.variable(&request.encode())?;
                writer.string(&request.fingerprint())?;
                Ok(writer.finish())
            },
        )
    }
}

/// Authenticates a new device's public request before fingerprint comparison.
///
/// # Safety
/// All pointers cover their declared ranges; `bytes_written` is writable and aligned.
#[unsafe(no_mangle)]
#[expect(unsafe_code, reason = "the pairing C ABI borrows caller-owned buffers")]
pub unsafe extern "C" fn chur_device_pairing_inspect(
    session: Handle,
    input: *const u8,
    input_length: u32,
    destination: *mut u8,
    capacity: usize,
    bytes_written: *mut usize,
) -> Status {
    // SAFETY: the caller guarantees the input, output, and out-parameter ranges above.
    unsafe {
        pairing_call(
            session,
            input,
            input_length,
            destination,
            capacity,
            bytes_written,
            |_db, _root, vault, input| {
                let request = PairingRequest::decode(input)?;
                ensure!(
                    request.vault_id() == &vault,
                    AuthenticationFailed,
                    "pairing request belongs to another vault"
                );
                let mut writer = Writer::new();
                writer.u16(2).id(request.vault_id()).id(request.device_id());
                writer.string(&request.fingerprint())?;
                Ok(writer.finish())
            },
        )
    }
}

/// Signs an enrollment only after the host obtained explicit fingerprint approval.
///
/// # Safety
/// All pointers cover their declared ranges; `bytes_written` is writable and aligned.
#[unsafe(no_mangle)]
#[expect(unsafe_code, reason = "the pairing C ABI borrows caller-owned buffers")]
pub unsafe extern "C" fn chur_device_pairing_approve(
    session: Handle,
    input: *const u8,
    input_length: u32,
    destination: *mut u8,
    capacity: usize,
    bytes_written: *mut usize,
) -> Status {
    // SAFETY: the caller guarantees the input, output, and out-parameter ranges above.
    unsafe {
        pairing_call(
            session,
            input,
            input_length,
            destination,
            capacity,
            bytes_written,
            |db, root, vault, input| {
                let mut reader = Reader::new(input, ChurStatus::NonCanonicalEncoding);
                ensure!(
                    reader.u16()? == 2,
                    NonCanonicalEncoding,
                    "pairing approval request version is invalid"
                );
                let request = PairingRequest::decode(reader.variable(INPUT_MAX)?)?;
                let now = reader.u64()?;
                reader.finish()?;
                let prepared = device_pairing::approve(db, root, vault, &request, now)?;
                let mut writer = Writer::new();
                writer.u16(2);
                writer.variable(&prepared.checkpoint)?;
                writer.variable(&prepared.enrollment)?;
                writer.variable(&prepared.operation)?;
                writer.variable(&prepared.approval)?;
                Ok(writer.finish())
            },
        )
    }
}

/// Opens an authenticated approval and retains it until proof download and installation finish.
///
/// # Safety
/// All pointers cover their declared ranges; `bytes_written` is writable and aligned.
#[unsafe(no_mangle)]
#[expect(unsafe_code, reason = "the pairing C ABI borrows caller-owned buffers")]
pub unsafe extern "C" fn chur_device_pairing_start(
    session: Handle,
    input: *const u8,
    input_length: u32,
    destination: *mut u8,
    capacity: usize,
    bytes_written: *mut usize,
) -> Status {
    // SAFETY: the caller guarantees the input, output, and out-parameter ranges above.
    unsafe {
        pairing_call(
            session,
            input,
            input_length,
            destination,
            capacity,
            bytes_written,
            |db, root, vault, input| {
                let credentials = device_pairing::accept_start(db, root, vault, input)?;
                credentials_record(vault, &credentials)
            },
        )
    }
}

/// Installs the fresh local identity only after native history and checkpoint validation.
///
/// # Safety
/// All pointers cover their declared ranges; `bytes_written` is writable and aligned.
#[unsafe(no_mangle)]
#[expect(unsafe_code, reason = "the pairing C ABI borrows caller-owned buffers")]
pub unsafe extern "C" fn chur_device_pairing_finish(
    session: Handle,
    input: *const u8,
    input_length: u32,
    destination: *mut u8,
    capacity: usize,
    bytes_written: *mut usize,
) -> Status {
    // SAFETY: the caller guarantees the input, output, and out-parameter ranges above.
    unsafe {
        pairing_call(
            session,
            input,
            input_length,
            destination,
            capacity,
            bytes_written,
            |db, root, vault, input| {
                let mut reader = Reader::new(input, ChurStatus::NonCanonicalEncoding);
                let now = reader.u64()?;
                reader.finish()?;
                let device = device_pairing::finalize(db, root, vault, now)?;
                let mut writer = Writer::new();
                writer.u16(2).id(&device);
                Ok(writer.finish())
            },
        )
    }
}

#[expect(
    unsafe_code,
    reason = "the exported pairing functions establish the buffer contract"
)]
unsafe fn pairing_call(
    session: Handle,
    input: *const u8,
    input_length: u32,
    destination: *mut u8,
    capacity: usize,
    bytes_written: *mut usize,
    action: impl FnOnce(&mut CatalogDb, &Key, Id, &[u8]) -> Result<Vec<u8>>,
) -> Status {
    guard_status_for(session, || {
        // SAFETY: the export's caller guarantees the writable aligned out-parameter.
        unsafe { write_out(bytes_written, 0usize)? };
        ensure!(
            input_length <= INPUT_MAX,
            ResourceLimitExceeded,
            "pairing input exceeds the control record bound"
        );
        // SAFETY: the export's caller guarantees the readable input range.
        let input = unsafe { borrow_large(input, input_length)? };
        let entry = registry::get(session, Kind::Session)?;
        let Entry::Session { session, .. } = entry.as_ref() else {
            return Err(Error::new(
                ChurStatus::InvalidInput,
                "the handle is of another type",
            ));
        };
        let encoded = Zeroizing::new({
            let mut session = registry::lock(session);
            let root = session.root_secret()?.duplicate();
            let vault = session.vault_id();
            action(session.catalog()?, &root, vault, input)?
        });
        // SAFETY: the export's caller guarantees the writable output range.
        let output = unsafe { borrow_bytes_mut(destination, capacity)? };
        ensure!(
            encoded.len() <= output.len(),
            ResourceLimitExceeded,
            "pairing output exceeds the destination"
        );
        output[..encoded.len()].copy_from_slice(&encoded);
        // SAFETY: the export's caller guarantees the writable aligned out-parameter.
        unsafe { write_out(bytes_written, encoded.len()) }
    })
}

/// Lists authenticated enrolled devices without exposing secret keys.
///
/// # Safety
/// All pointers cover their declared ranges; `bytes_written` is writable and aligned.
#[unsafe(no_mangle)]
#[expect(unsafe_code, reason = "the pairing C ABI borrows caller-owned buffers")]
pub unsafe extern "C" fn chur_device_pairing_overview(
    session: Handle,
    input: *const u8,
    input_length: u32,
    destination: *mut u8,
    capacity: usize,
    bytes_written: *mut usize,
) -> Status {
    // SAFETY: the caller guarantees the input, output, and out-parameter ranges above.
    unsafe {
        pairing_call(
            session,
            input,
            input_length,
            destination,
            capacity,
            bytes_written,
            |db, root, vault, input| {
                ensure!(
                    input.is_empty(),
                    NonCanonicalEncoding,
                    "device overview has no input"
                );
                let devices = device_pairing::overview(db, root, vault)?;
                let mut writer = Writer::new();
                writer.u16(2).u32(u32::try_from(devices.len()).map_err(|_| {
                    Error::new(ChurStatus::ResourceLimitExceeded, "too many paired devices")
                })?);
                for device in devices {
                    writer.id(&device.device_id);
                    writer.string(&device.fingerprint)?;
                    writer
                        .u8(u8::from(device.active))
                        .u8(u8::from(device.current))
                        .u8(u8::from(device.retry_available));
                }
                Ok(writer.finish())
            },
        )
    }
}

/// Prepares an atomic signed peer revocation with durable retry records.
///
/// # Safety
/// All pointers cover their declared ranges; `bytes_written` is writable and aligned.
#[unsafe(no_mangle)]
#[expect(unsafe_code, reason = "the pairing C ABI borrows caller-owned buffers")]
pub unsafe extern "C" fn chur_device_pairing_revoke(
    session: Handle,
    input: *const u8,
    input_length: u32,
    destination: *mut u8,
    capacity: usize,
    bytes_written: *mut usize,
) -> Status {
    // SAFETY: the caller guarantees the input, output, and out-parameter ranges above.
    unsafe {
        pairing_call(
            session,
            input,
            input_length,
            destination,
            capacity,
            bytes_written,
            |db, root, vault, input| {
                let mut reader = Reader::new(input, ChurStatus::NonCanonicalEncoding);
                let target = reader.id()?;
                let now_ms = reader.u64()?;
                reader.finish()?;
                let prepared = device_pairing::prepare_revoke(db, root, vault, target, now_ms)?;
                let mut writer = Writer::new();
                writer.u16(2);
                writer.variable(&prepared.checkpoint)?;
                writer.variable(&prepared.revocation)?;
                writer.variable(&prepared.operation)?;
                Ok(writer.finish())
            },
        )
    }
}

/// Creates a signed checkpoint for the current authenticated membership.
///
/// # Safety
/// All pointers cover their declared ranges; `bytes_written` is writable and aligned.
#[unsafe(no_mangle)]
#[expect(unsafe_code, reason = "the pairing C ABI borrows caller-owned buffers")]
pub unsafe extern "C" fn chur_device_pairing_checkpoint(
    session: Handle,
    input: *const u8,
    input_length: u32,
    destination: *mut u8,
    capacity: usize,
    bytes_written: *mut usize,
) -> Status {
    // SAFETY: the caller guarantees the input, output, and out-parameter ranges above.
    unsafe {
        pairing_call(
            session,
            input,
            input_length,
            destination,
            capacity,
            bytes_written,
            |db, root, vault, input| {
                let mut reader = Reader::new(input, ChurStatus::NonCanonicalEncoding);
                let now_ms = reader.u64()?;
                reader.finish()?;
                device_pairing::current_checkpoint(db, root, vault, now_ms)
            },
        )
    }
}

/// Encodes the one current pairing-evidence record for initial acceptance and resume.
pub(crate) fn credentials_record(
    vault: Id,
    credentials: &device_pairing::PairCredentials,
) -> Result<Vec<u8>> {
    let mut writer = Writer::new();
    writer.u16(2).id(&vault).id(&credentials.device_id);
    writer
        .variable(&credentials.enrollment)?
        .variable(&credentials.checkpoint)?;
    Ok(writer.finish())
}
