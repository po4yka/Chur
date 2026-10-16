//! Own-device media operation and download-plan ABI.

use crate::api::{Status, borrow_bytes, borrow_bytes_mut, write_out};
use crate::panic::guard_status_for;
use crate::registry::{self, Entry, Handle, Kind};
use chur_core::{ChurStatus, Error, Result, ensure};
use chur_format::codec::{Reader, Writer};

type Session = chur_catalog::vault::Session;

/// Lists locally owned collections.
/// # Safety
/// All pointers cover their declared lengths; `written` is writable.
#[unsafe(no_mangle)]
#[expect(unsafe_code, reason = "the C ABI borrows caller-owned buffers")]
pub unsafe extern "C" fn chur_own_sync_collections(
    session: Handle,
    input: *const u8,
    length: u32,
    output: *mut u8,
    capacity: usize,
    written: *mut usize,
) -> Status {
    unsafe {
        call(
            session,
            input,
            length,
            output,
            capacity,
            written,
            |session, bytes| {
                ensure!(
                    bytes.is_empty(),
                    NonCanonicalEncoding,
                    "collection request must be empty"
                );
                let ids = chur_catalog::own_sync::collection_ids(session.catalog_ref()?)?;
                let mut writer = Writer::new();
                writer.u16(1).u32(ids.len().try_into().map_err(|_| {
                    Error::new(
                        ChurStatus::ResourceLimitExceeded,
                        "too many own collections",
                    )
                })?);
                for id in ids {
                    writer.id(&id);
                }
                Ok(writer.finish())
            },
        )
    }
}

/// Returns a bounded page of the current device's durable ordinary operations.
/// # Safety
/// All pointers cover their declared lengths; `written` is writable.
#[unsafe(no_mangle)]
#[expect(unsafe_code, reason = "the C ABI borrows caller-owned buffers")]
pub unsafe extern "C" fn chur_own_sync_operations(
    session: Handle,
    input: *const u8,
    length: u32,
    output: *mut u8,
    capacity: usize,
    written: *mut usize,
) -> Status {
    unsafe {
        call(
            session,
            input,
            length,
            output,
            capacity,
            written,
            |session, bytes| {
                let mut reader = Reader::new(bytes, ChurStatus::NonCanonicalEncoding);
                let after = reader.u64()?;
                reader.finish()?;
                let root = session.root_secret()?;
                let membership = chur_catalog::sync_membership::load(session.catalog_ref()?)?
                    .ok_or_else(|| {
                        Error::new(ChurStatus::RecoveryRequired, "own membership is absent")
                    })?;
                let (device, _) = chur_catalog::sync_keys::local_identity(
                    session.catalog_ref()?,
                    root,
                    &membership,
                )?
                .ok_or_else(|| {
                    Error::new(ChurStatus::RecoveryRequired, "own identity is absent")
                })?;
                let records =
                    chur_catalog::sync_log::records_after(session.catalog_ref()?, &device, after)?;
                let mut writer = Writer::new();
                writer.u16(1).u32(records.len() as u32);
                for record in records {
                    writer.u64(
                        chur_sync_protocol::operation::Operation::decode(&record)?
                            .device_sequence(),
                    );
                    writer.variable(&record)?;
                }
                Ok(writer.finish())
            },
        )
    }
}

/// Projects accepted own-vault content into the bounded shared download-plan wire form.
/// # Safety
/// All pointers cover their declared lengths; `written` is writable.
#[unsafe(no_mangle)]
#[expect(unsafe_code, reason = "the C ABI borrows caller-owned buffers")]
pub unsafe extern "C" fn chur_own_sync_plan(
    session: Handle,
    input: *const u8,
    length: u32,
    output: *mut u8,
    capacity: usize,
    written: *mut usize,
) -> Status {
    unsafe {
        call(
            session,
            input,
            length,
            output,
            capacity,
            written,
            |session, bytes| {
                let mut reader = Reader::new(bytes, ChurStatus::NonCanonicalEncoding);
                let collection = reader.id()?;
                reader.finish()?;
                let root = session.root_secret()?.duplicate();
                let vault = session.vault_id();
                let plan = chur_catalog::own_sync::pending_for_collection(
                    session.catalog()?,
                    &root,
                    vault,
                    collection,
                )?;
                crate::product::finish_pending_deletions(session, crate::api::now_ms())?;
                let mut writer = Writer::new();
                writer
                    .u16(1)
                    .id(&plan.source_vault_id)
                    .id(&plan.collection_id)
                    .id(&plan.selector)
                    .u32(0)
                    .u32(plan.objects.len().min(256) as u32);
                for object in plan.objects.iter().take(256) {
                    writer
                        .id(&object.object_id)
                        .id(&object.store_id)
                        .u64(object.container_length);
                }
                Ok(writer.finish())
            },
        )
    }
}

/// Authors key-free own-vault operations after complete source upload validation.
/// # Safety
/// All pointers cover their declared lengths; `written` is writable.
#[unsafe(no_mangle)]
#[expect(unsafe_code, reason = "the C ABI borrows caller-owned buffers")]
pub unsafe extern "C" fn chur_own_sync_author(
    session: Handle,
    input: *const u8,
    length: u32,
    output: *mut u8,
    capacity: usize,
    written: *mut usize,
) -> Status {
    let owner = session;
    unsafe {
        call(
            session,
            input,
            length,
            output,
            capacity,
            written,
            |session, bytes| {
                let mut reader = Reader::new(bytes, ChurStatus::NonCanonicalEncoding);
                let collection = reader.id()?;
                let object = reader.id()?;
                let store = reader.id()?;
                let length = reader.u64()?;
                let hash: [u8; 32] = reader.fixed()?;
                reader.finish()?;
                let item = chur_catalog::sharing_publication::candidate(
                    session.catalog_ref()?,
                    &collection,
                    &object,
                )?;
                ensure!(
                    item.store_id == store
                        && item.container_length == length
                        && crate::sharing_publish::cached_sha256(owner, session, &item)? == hash,
                    Conflict,
                    "own source changed after upload"
                );
                let root = session.root_secret()?.duplicate();
                let vault = session.vault_id();
                let publication = chur_catalog::own_sync::publish(
                    session.catalog()?,
                    &root,
                    vault,
                    collection,
                    &[item],
                )?
                .pop()
                .ok_or_else(|| {
                    Error::new(ChurStatus::InternalFailure, "own publication is empty")
                })?;
                let mut writer = Writer::new();
                writer.u16(1).u32(1);
                writer
                    .variable(&publication.create_operation.encode())?
                    .variable(&publication.commit_operation.encode())?;
                writer
                    .id(&item.object_id)
                    .id(&item.store_id)
                    .u64(item.container_length)
                    .fixed(&hash);
                Ok(writer.finish())
            },
        )
    }
}

#[expect(
    unsafe_code,
    reason = "the common ABI implementation checks borrowed ranges"
)]
unsafe fn call(
    session: Handle,
    input: *const u8,
    length: u32,
    output: *mut u8,
    capacity: usize,
    written: *mut usize,
    run: impl FnOnce(&mut Session, &[u8]) -> Result<Vec<u8>>,
) -> Status {
    guard_status_for(session, || {
        unsafe {
            write_out(written, 0usize)?;
        }
        ensure!(length <= 88, InvalidInput, "own sync request is too large");
        let bytes = if length == 0 {
            &[]
        } else {
            unsafe { borrow_bytes(input, length)? }
        };
        let entry = registry::get(session, Kind::Session)?;
        let Entry::Session { session, .. } = entry.as_ref() else {
            return Err(Error::new(
                ChurStatus::InvalidInput,
                "handle is not a session",
            ));
        };
        let encoded = run(&mut registry::lock(session), bytes)?;
        ensure!(
            encoded.len() <= capacity,
            ResourceLimitExceeded,
            "own sync output buffer is too small"
        );
        let destination = unsafe { borrow_bytes_mut(output, capacity)? };
        destination[..encoded.len()].copy_from_slice(&encoded);
        unsafe { write_out(written, encoded.len()) }
    })
}
