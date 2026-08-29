//! Atomic inbound acceptance for encrypted membership operations.

use chur_core::{ChurStatus, Error, Result};
use chur_sync_protocol::KeyDirectory;
use chur_sync_protocol::operation::Operation;
use chur_sync_protocol::operation_log::ApplyOutcome;
use chur_sync_protocol::payload::{OperationPayload, PayloadBody};
use chur_sync_protocol::state::MembershipState;

use crate::CatalogDb;
use crate::sync_log::DurableOperationLog;
use crate::sync_membership;

/// Authenticates, decrypts, and atomically commits one membership operation.
pub fn accept_membership_operation(
    db: &mut CatalogDb,
    log: &mut DurableOperationLog,
    membership: &mut MembershipState,
    keys: &KeyDirectory,
    record: &[u8],
) -> Result<ApplyOutcome> {
    let operation = Operation::decode(record)?;
    let payload = OperationPayload::open_for_operation(&operation, keys)?;
    let mut projected_membership = None;
    let outcome = log.accept_with(db, &operation, membership, |transaction| {
        projected_membership = Some(match payload.body() {
            PayloadBody::AddDevice(enrollment) => sync_membership::project_enrollment(
                transaction,
                membership,
                enrollment,
                operation.device_id(),
                operation.device_sequence(),
            )?,
            PayloadBody::RevokeDevice(revocation) => sync_membership::project_revocation(
                transaction,
                membership,
                revocation,
                operation.device_id(),
            )?,
            _ => {
                return Err(Error::new(
                    ChurStatus::InvalidInput,
                    "operation is not a membership operation",
                ));
            }
        });
        Ok(())
    })?;
    if outcome == ApplyOutcome::Applied {
        *membership = projected_membership.ok_or_else(|| {
            Error::new(
                ChurStatus::InternalFailure,
                "applied membership operation has no projection",
            )
        })?;
    }
    Ok(outcome)
}
