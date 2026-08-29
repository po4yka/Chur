//! Locked ciphertext staging and unlocked inbound processing.

use chur_core::{ChurStatus, Error, Id, Result};
use chur_crypto::Key;
use chur_crypto::commit;
use chur_sync_protocol::checkpoint::Checkpoint;
use chur_sync_protocol::operation_log::{ApplyOutcome, CheckpointOutcome};

use crate::paths::VaultRoot;
use crate::sync_staging::LockedStaging;
use crate::{CatalogDb, sync_keys, sync_log, sync_membership, sync_receive};

const STAGED_RECORD_TAG: &[u8] = b"CHUR\0SYNC\0STAGED-RECORD\0V1";

/// An opaque record family known to the transport while keys are unavailable.
#[derive(Clone, Copy)]
#[repr(u8)]
pub enum StagedKind {
    /// One signed encrypted operation.
    Operation = 1,
    /// One signed checkpoint.
    Checkpoint = 2,
}

/// Result of one bounded unlocked inbox pass.
#[derive(Debug, Default, PartialEq, Eq)]
pub struct ProcessReport {
    /// Records accepted into durable catalog state.
    pub applied: usize,
    /// Exact replays or unchanged checkpoints removed from the inbox.
    pub duplicates: usize,
    /// Records retained until a missing causal predecessor arrives.
    pub pending: usize,
    /// Invalid records removed after full unlocked validation.
    pub rejected: usize,
    /// Stable status of the first rejected record.
    pub first_rejection: Option<ChurStatus>,
}

/// Durably stores an opaque inbound record without opening vault keys.
pub fn stage_inbound(
    root: &VaultRoot,
    vault_id: Id,
    kind: StagedKind,
    staged_at_ms: u64,
    record: &[u8],
) -> Result<()> {
    let mut framed = Vec::with_capacity(record.len() + 1);
    framed.push(kind as u8);
    framed.extend_from_slice(record);
    let digest = commit::commit(STAGED_RECORD_TAG, &[&framed]);
    let id = Id::from_slice(&digest[..16])?;
    LockedStaging::open(root.sync_inbox(&vault_id))?.stage(id, staged_at_ms, &framed)
}

/// Validates and applies retained records after the vault unlocks.
pub fn process_staged(
    db: &mut CatalogDb,
    root: &Key,
    vault_id: Id,
    staging: &mut LockedStaging,
    now_ms: u64,
) -> Result<ProcessReport> {
    let mut membership = sync_membership::load(db)?.ok_or_else(|| {
        Error::new(
            ChurStatus::InvalidInput,
            "sync is not provisioned for this vault",
        )
    })?;
    if membership.vault_id() != &vault_id {
        return Err(Error::new(
            ChurStatus::InvalidInput,
            "staged sync vault does not match the unlocked vault",
        ));
    }
    let mut log = sync_log::load(db, &membership)?;
    let mut keys = sync_keys::key_directory(db, root, vault_id)?;
    let mut state = sync_receive::load_materialized_state(db, &keys)?;
    let mut report = ProcessReport::default();

    loop {
        let records = staging.records(now_ms)?;
        if records.is_empty() {
            report.pending = 0;
            break;
        }
        let mut removed = false;
        let mut pending = 0;
        for record in records {
            match process_one(
                db,
                root,
                &mut membership,
                &mut log,
                &mut keys,
                &mut state,
                record.staged_at_ms(),
                record.bytes(),
            ) {
                Ok(RecordOutcome::Applied) => {
                    staging.remove(record.id())?;
                    report.applied += 1;
                    removed = true;
                }
                Ok(RecordOutcome::Duplicate) => {
                    staging.remove(record.id())?;
                    report.duplicates += 1;
                    removed = true;
                }
                Ok(RecordOutcome::Pending) => pending += 1,
                Err(error) if is_rejection(error.status()) => {
                    staging.remove(record.id())?;
                    report.rejected += 1;
                    report.first_rejection.get_or_insert(error.status());
                    removed = true;
                }
                Err(error) => return Err(error),
            }
        }
        report.pending = pending;
        if !removed {
            break;
        }
    }
    Ok(report)
}

enum RecordOutcome {
    Applied,
    Duplicate,
    Pending,
}

#[expect(
    clippy::too_many_arguments,
    reason = "one staged record updates the same durable sync state loaded once by its bounded batch"
)]
fn process_one(
    db: &mut CatalogDb,
    root: &Key,
    membership: &mut chur_sync_protocol::state::MembershipState,
    log: &mut sync_log::DurableOperationLog,
    keys: &mut chur_sync_protocol::KeyDirectory,
    state: &mut chur_sync_protocol::materialization::MaterializedState,
    staged_at_ms: u64,
    framed: &[u8],
) -> Result<RecordOutcome> {
    let (&kind, record) = framed
        .split_first()
        .ok_or_else(|| Error::new(ChurStatus::InvalidInput, "staged sync record is empty"))?;
    match kind {
        value if value == StagedKind::Operation as u8 => {
            match sync_receive::accept_operation(
                db,
                log,
                membership,
                state,
                keys,
                root,
                staged_at_ms,
                record,
            ) {
                Ok(ApplyOutcome::Applied) => Ok(RecordOutcome::Applied),
                Ok(ApplyOutcome::Duplicate) => Ok(RecordOutcome::Duplicate),
                Ok(ApplyOutcome::PendingGap | ApplyOutcome::PendingCause) => {
                    Ok(RecordOutcome::Pending)
                }
                Err(error) if error.status() == ChurStatus::NotFound => Ok(RecordOutcome::Pending),
                Err(error) => Err(error),
            }
        }
        value if value == StagedKind::Checkpoint as u8 => {
            let checkpoint = Checkpoint::decode(record)?;
            match log.accept_checkpoint(db, &checkpoint, membership, false, staged_at_ms)? {
                CheckpointOutcome::Raised => Ok(RecordOutcome::Applied),
                CheckpointOutcome::Unchanged => Ok(RecordOutcome::Duplicate),
            }
        }
        _ => Err(Error::new(
            ChurStatus::InvalidInput,
            "staged sync record kind is invalid",
        )),
    }
}

fn is_rejection(status: ChurStatus) -> bool {
    matches!(
        status,
        ChurStatus::AuthenticationFailed
            | ChurStatus::InvalidInput
            | ChurStatus::ResourceLimitExceeded
            | ChurStatus::Conflict
            | ChurStatus::SyncChainFork
            | ChurStatus::SyncHeadRollback
            | ChurStatus::UnsupportedVersion
            | ChurStatus::UnsupportedSuite
            | ChurStatus::NonCanonicalEncoding
            | ChurStatus::ObjectCorrupt
    )
}
