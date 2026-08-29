//! Phase 3 locked staging and unlocked validation C ABI.

use chur_catalog::sync_engine::{self, ProcessReport, StagedKind};
use chur_catalog::sync_staging::LockedStaging;
use chur_core::limits::sync as bounds;
use chur_core::{ChurStatus, Error, Id, Result, ensure};
use chur_crypto::Key;

use crate::api::{Status, borrow_large, write_out};
use crate::panic::guard_status;
use crate::registry::{self, Entry, Handle, Kind};

/// Caller-owned summary written by [`chur_sync_process`].
#[derive(Clone, Copy)]
#[repr(C)]
pub struct ChurSyncReportV1 {
    /// Records accepted into durable catalog state.
    pub applied: u64,
    /// Exact replays or unchanged checkpoints.
    pub duplicates: u64,
    /// Records retained for missing causal predecessors.
    pub pending: u64,
    /// Invalid records rejected after unlocked validation.
    pub rejected: u64,
    /// First stable rejection status, or zero.
    pub first_rejection: i32,
    /// Reserved and zero in ABI 1.4.
    pub reserved: [u8; 4],
}

/// Stages one opaque operation or checkpoint while its vault may be locked.
///
/// # Safety
///
/// `vault_id` points to 16 readable bytes. `record` points to `record_length`
/// readable bytes for this call, or is null when that length is zero.
#[unsafe(no_mangle)]
#[expect(
    unsafe_code,
    reason = "FFI_CONTRACT.md section 6.8 fixes this exported symbol"
)]
pub unsafe extern "C" fn chur_sync_stage(
    runtime: Handle,
    vault_id: *const u8,
    kind: u8,
    staged_at_ms: u64,
    record: *const u8,
    record_length: u32,
) -> Status {
    guard_status(|| {
        let entry = registry::get(runtime, Kind::Runtime)?;
        let Entry::Runtime(guarded) = entry.as_ref() else {
            return Err(Error::new(
                ChurStatus::InvalidInput,
                "the handle is of another type",
            ));
        };
        // SAFETY: the caller guarantees both readable ranges above for this call.
        let vault_id = Id::from_slice(unsafe { fixed_id(vault_id)? })?;
        ensure!(
            u64::from(record_length) <= bounds::RESPONSE_BYTES_MAX as u64,
            ResourceLimitExceeded,
            "the staged record exceeds the sync response bound"
        );
        // SAFETY: the caller guarantees this readable range for the call.
        let record = unsafe { borrow_large(record, record_length)? };
        let kind = match kind {
            1 => StagedKind::Operation,
            2 => StagedKind::Checkpoint,
            _ => {
                return Err(Error::new(
                    ChurStatus::InvalidInput,
                    "the staged record kind is not allocated",
                ));
            }
        };
        let guard = registry::lock(guarded);
        sync_engine::stage_inbound(guard.root(), vault_id, kind, staged_at_ms, record)
    })
}

/// Validates and applies the current vault's locked inbox after unlock.
///
/// # Safety
///
/// `out_report` points to one writable, aligned [`ChurSyncReportV1`].
#[unsafe(no_mangle)]
#[expect(
    unsafe_code,
    reason = "FFI_CONTRACT.md section 6.8 fixes this exported symbol"
)]
pub unsafe extern "C" fn chur_sync_process(
    session: Handle,
    now_ms: u64,
    out_report: *mut ChurSyncReportV1,
) -> Status {
    guard_status(|| {
        // SAFETY: the caller guarantees the writable out-parameter above.
        unsafe { write_out(out_report, ffi_report(&ProcessReport::default()))? };
        let entry = registry::get(session, Kind::Session)?;
        let Entry::Session { session, .. } = entry.as_ref() else {
            return Err(Error::new(
                ChurStatus::InvalidInput,
                "the handle is of another type",
            ));
        };
        let mut session = registry::lock(session);
        let vault_id = session.vault_id();
        let root_dir = session.root_dir().clone();
        let root = Key::new(*session.root_secret().expose());
        let mut staging = LockedStaging::open(root_dir.sync_inbox(&vault_id))?;
        let report =
            sync_engine::process_staged(session.catalog()?, &root, vault_id, &mut staging, now_ms)?;
        // SAFETY: the caller guarantees the writable out-parameter above.
        unsafe { write_out(out_report, ffi_report(&report)) }
    })
}

fn ffi_report(report: &ProcessReport) -> ChurSyncReportV1 {
    ChurSyncReportV1 {
        applied: report.applied as u64,
        duplicates: report.duplicates as u64,
        pending: report.pending as u64,
        rejected: report.rejected as u64,
        first_rejection: report.first_rejection.map_or(0, ChurStatus::as_i32),
        reserved: [0; 4],
    }
}

#[expect(
    unsafe_code,
    reason = "the caller's fixed identifier pointer contract is stated above"
)]
unsafe fn fixed_id<'a>(pointer: *const u8) -> Result<&'a [u8]> {
    if pointer.is_null() {
        return Err(Error::new(
            ChurStatus::InvalidInput,
            "the vault identifier pointer is null",
        ));
    }
    // SAFETY: the caller guarantees 16 initialized bytes for this call.
    Ok(unsafe { core::slice::from_raw_parts(pointer, 16) })
}
