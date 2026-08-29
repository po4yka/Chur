//! Atomic inbound acceptance for encrypted sync operations.

use std::collections::BTreeMap;

use chur_core::{ChurStatus, Error, Id, Result, ensure};
use chur_crypto::{Key, Nonce, random};
use chur_sync_protocol::convergence::MergeOutcome;
use chur_sync_protocol::materialization::MaterializedState;
use chur_sync_protocol::membership::EnrollmentRecord;
use chur_sync_protocol::operation::{DeviceSigningKey, Operation};
use chur_sync_protocol::operation_log::ApplyOutcome;
use chur_sync_protocol::payload::{OperationPayload, PayloadBody};
use chur_sync_protocol::state::MembershipState;
use chur_sync_protocol::{KeyDirectory, KeyDomain};

use crate::CatalogDb;
use crate::db::{from_sqlite_integer, map_sqlite};
use crate::sync_log::DurableOperationLog;
use crate::{sync_keys, sync_log, sync_membership, sync_rotation};

/// Provisions generation-one membership and its outer operation atomically.
pub fn provision_initial_membership(
    db: &mut CatalogDb,
    root: &Key,
    signing_key: &DeviceSigningKey,
    enrollment: &EnrollmentRecord,
) -> Result<(MembershipState, DurableOperationLog, Operation)> {
    let membership = MembershipState::bootstrap(enrollment)?;
    let domain = KeyDomain::root(root, membership.vault_id())?;
    let payload = OperationPayload::new(
        *membership.vault_id(),
        0,
        PayloadBody::AddDevice(enrollment.clone()),
    )?;
    let mut log = sync_log::load(db, &membership)?;
    let operation = log.author(
        random::id()?,
        *membership.vault_id(),
        *enrollment.device_id(),
        *domain.selector(),
        domain.operation_key(),
        Nonce::random()?,
        &payload.encode(),
        signing_key,
        &membership,
    )?;
    payload.validate_for_operation(
        &operation,
        domain.collection_id(),
        domain.collection_epoch(),
    )?;
    ensure!(
        log.accept_with(db, &operation, &membership, |transaction| {
            sync_membership::project_provision(transaction, enrollment)?;
            Ok(())
        })? == ApplyOutcome::Applied,
        InternalFailure,
        "fresh self-enrollment operation was not applied"
    );
    Ok((membership, log, operation))
}

/// Rebuilds convergent private content from authenticated accepted records.
pub fn load_materialized_state(db: &CatalogDb, keys: &KeyDirectory) -> Result<MaterializedState> {
    let devices = crate::sync_log::device_ids(db)?;
    let operation_count = db
        .connection()
        .query_row("SELECT count(*) FROM sync_operations", [], |row| {
            row.get::<_, i64>(0)
        })
        .map_err(|error| map_sqlite(error, "accepted operations could not be counted"))?;
    let operation_count =
        from_sqlite_integer(operation_count, "the accepted operation count is malformed")?;
    let mut next_sequence = devices
        .iter()
        .copied()
        .map(|device_id| (device_id, 1))
        .collect::<BTreeMap<_, _>>();
    let mut restored = 0;
    let mut state = MaterializedState::new();

    while restored < operation_count {
        let mut progressed = false;
        for device_id in &devices {
            let sequence = next_sequence[device_id];
            let Some((operation, stored_digest)) =
                crate::sync_log::operation_at(db, device_id, sequence)?
            else {
                continue;
            };
            ensure!(
                operation.digest() == stored_digest,
                CatalogCorrupt,
                "an accepted operation digest contradicts its record"
            );
            let payload = OperationPayload::open_for_operation(&operation, keys)
                .map_err(corrupt_materialized_state)?;
            if !matches!(
                payload.body(),
                PayloadBody::AddDevice(_)
                    | PayloadBody::RevokeDevice(_)
                    | PayloadBody::CreateCollectionEpoch { .. }
                    | PayloadBody::RewrapObjectKey { .. }
            ) {
                let mut candidate = state.clone();
                if candidate
                    .apply(&operation, &payload)
                    .map_err(corrupt_materialized_state)?
                    == MergeOutcome::PendingCause
                {
                    continue;
                }
                state = candidate;
            }
            next_sequence.insert(
                *device_id,
                sequence.checked_add(1).ok_or_else(|| {
                    Error::new(
                        ChurStatus::CatalogCorrupt,
                        "an accepted operation sequence overflowed",
                    )
                })?,
            );
            restored = restored.checked_add(1).ok_or_else(|| {
                Error::new(
                    ChurStatus::CatalogCorrupt,
                    "the accepted operation count overflowed",
                )
            })?;
            progressed = true;
        }
        ensure!(
            progressed,
            CatalogCorrupt,
            "accepted content history has a missing or cyclic cause"
        );
    }
    Ok(state)
}

fn corrupt_materialized_state(_error: Error) -> Error {
    Error::new(
        ChurStatus::CatalogCorrupt,
        "an accepted content operation could not be materialized",
    )
}

/// Returns tombstoned objects that the current durable checkpoint permits GC to discard.
pub fn gc_candidates(
    db: &CatalogDb,
    log: &DurableOperationLog,
    membership: &MembershipState,
    state: &MaterializedState,
    now_ms: u64,
) -> Result<Vec<Id>> {
    let active_devices = membership.active_device_ids().copied().collect::<Vec<_>>();
    let latest_operations = log.latest_operations()?;
    Ok(state.gc_candidates(
        now_ms,
        &active_devices,
        &latest_operations,
        log.own_checkpoint_covers_current_heads(db)?,
    ))
}

/// Authors and atomically persists one local convergent content operation.
#[expect(
    clippy::too_many_arguments,
    reason = "the operation joins durable state, device identity, key domain, and private payload"
)]
pub fn author_content_operation(
    db: &mut CatalogDb,
    log: &mut DurableOperationLog,
    membership: &MembershipState,
    state: &mut MaterializedState,
    domain: &KeyDomain,
    device_id: Id,
    signing_key: &DeviceSigningKey,
    payload: &OperationPayload,
) -> Result<Operation> {
    let operation = log.author(
        random::id()?,
        *membership.vault_id(),
        device_id,
        *domain.selector(),
        domain.operation_key(),
        Nonce::random()?,
        &payload.encode(),
        signing_key,
        membership,
    )?;
    payload.validate_for_operation(
        &operation,
        domain.collection_id(),
        domain.collection_epoch(),
    )?;
    let mut projected = state.clone();
    ensure!(
        projected.apply(&operation, payload)? == MergeOutcome::Applied,
        InvalidInput,
        "local content operation does not change the current state"
    );
    ensure!(
        log.accept_with(db, &operation, membership, |_| Ok(()))? == ApplyOutcome::Applied,
        InternalFailure,
        "fresh local content operation was not applied"
    );
    *state = projected;
    Ok(operation)
}

/// Authors and atomically persists one local device-membership operation.
pub fn author_membership_operation(
    db: &mut CatalogDb,
    log: &mut DurableOperationLog,
    membership: &mut MembershipState,
    root_domain: &KeyDomain,
    device_id: Id,
    signing_key: &DeviceSigningKey,
    body: PayloadBody,
) -> Result<Operation> {
    match &body {
        PayloadBody::AddDevice(enrollment) => ensure!(
            log.checkpoint_commitment(enrollment.issuer_device_id())
                == Some(enrollment.bootstrap_checkpoint_commitment()),
            AuthenticationFailed,
            "local enrollment does not name the issuer's durable checkpoint"
        ),
        PayloadBody::RevokeDevice(revocation) => ensure!(
            log.head(revocation.revoked_device_id())
                == Some((
                    revocation.final_accepted_device_sequence(),
                    *revocation.final_accepted_operation_digest(),
                )),
            AuthenticationFailed,
            "local revocation does not pin the latest accepted device head"
        ),
        _ => {
            return Err(Error::new(
                ChurStatus::InvalidInput,
                "operation is not a membership operation",
            ));
        }
    }
    let payload = OperationPayload::new(*membership.vault_id(), 0, body)?;
    let operation = log.author(
        random::id()?,
        *membership.vault_id(),
        device_id,
        *root_domain.selector(),
        root_domain.operation_key(),
        Nonce::random()?,
        &payload.encode(),
        signing_key,
        membership,
    )?;
    payload.validate_for_operation(
        &operation,
        root_domain.collection_id(),
        root_domain.collection_epoch(),
    )?;
    let mut projected_membership = None;
    ensure!(
        log.accept_with(db, &operation, membership, |transaction| {
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
                        ChurStatus::InternalFailure,
                        "checked membership body changed before projection",
                    ));
                }
            });
            Ok(())
        })? == ApplyOutcome::Applied,
        InternalFailure,
        "fresh local membership operation was not applied"
    );
    *membership = projected_membership.ok_or_else(|| {
        Error::new(
            ChurStatus::InternalFailure,
            "local membership operation has no projection",
        )
    })?;
    Ok(operation)
}

/// Authors and atomically persists one local collection rotation operation.
#[expect(
    clippy::too_many_arguments,
    reason = "rotation joins durable state, key routing, device identity, and private payload"
)]
pub fn author_rotation_operation(
    db: &mut CatalogDb,
    log: &mut DurableOperationLog,
    membership: &MembershipState,
    keys: &mut KeyDirectory,
    root: &Key,
    domain: &KeyDomain,
    device_id: Id,
    signing_key: &DeviceSigningKey,
    now_ms: u64,
    payload: &OperationPayload,
) -> Result<Operation> {
    ensure!(
        matches!(
            payload.body(),
            PayloadBody::CreateCollectionEpoch { .. } | PayloadBody::RewrapObjectKey { .. }
        ),
        InvalidInput,
        "operation is not a collection rotation operation"
    );
    let operation = log.author(
        random::id()?,
        *membership.vault_id(),
        device_id,
        *domain.selector(),
        domain.operation_key(),
        Nonce::random()?,
        &payload.encode(),
        signing_key,
        membership,
    )?;
    payload.validate_for_operation(
        &operation,
        domain.collection_id(),
        domain.collection_epoch(),
    )?;
    ensure!(
        accept_rotation_operation(db, log, membership, keys, root, now_ms, &operation.encode(),)?
            == ApplyOutcome::Applied,
        InternalFailure,
        "fresh local rotation operation was not applied"
    );
    Ok(operation)
}

/// Authenticates, decrypts, and atomically accepts one convergent content operation.
pub fn accept_content_operation(
    db: &mut CatalogDb,
    log: &mut DurableOperationLog,
    membership: &MembershipState,
    state: &mut MaterializedState,
    keys: &KeyDirectory,
    record: &[u8],
) -> Result<ApplyOutcome> {
    let operation = Operation::decode(record)?;
    let payload = OperationPayload::open_for_operation(&operation, keys)?;
    let mut projected_state = None;
    let outcome = log.accept_gated_with(
        db,
        &operation,
        membership,
        || {
            let mut candidate = state.clone();
            if candidate.apply(&operation, &payload)? == MergeOutcome::PendingCause {
                return Ok(false);
            }
            projected_state = Some(candidate);
            Ok(true)
        },
        |_| Ok(()),
    )?;
    if outcome == ApplyOutcome::Applied {
        *state = projected_state.ok_or_else(|| {
            Error::new(
                ChurStatus::InternalFailure,
                "applied content operation has no projection",
            )
        })?;
    }
    Ok(outcome)
}

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

/// Authenticates, decrypts, and atomically commits one collection rotation operation.
pub fn accept_rotation_operation(
    db: &mut CatalogDb,
    log: &mut DurableOperationLog,
    membership: &MembershipState,
    keys: &mut KeyDirectory,
    root: &Key,
    now_ms: u64,
    record: &[u8],
) -> Result<ApplyOutcome> {
    let operation = Operation::decode(record)?;
    let payload = OperationPayload::open_for_operation(&operation, keys)?;
    match payload.body() {
        PayloadBody::CreateCollectionEpoch {
            membership_generation,
            collection_key_envelope,
            ..
        } => {
            let collection_key = collection_key_envelope.open(root)?;
            let new_domain = KeyDomain::collection(
                &collection_key,
                payload.collection_id(),
                collection_key_envelope.collection_epoch(),
            )?;
            keys.check_insert(&new_domain)?;
            let outcome = log.accept_with(db, &operation, membership, |transaction| {
                sync_rotation::project_begin(
                    transaction,
                    *operation.vault_id(),
                    *payload.collection_id(),
                    membership,
                    *operation.device_id(),
                    *membership_generation,
                    now_ms,
                    collection_key_envelope.clone(),
                    root,
                )
            })?;
            if outcome == ApplyOutcome::Applied {
                keys.insert(new_domain)?;
            }
            Ok(outcome)
        }
        PayloadBody::RewrapObjectKey {
            object_key_envelope,
            ..
        } => {
            let current_epoch = payload.collection_epoch();
            let previous_epoch = current_epoch.checked_sub(1).ok_or_else(|| {
                Error::new(
                    ChurStatus::AuthenticationFailed,
                    "rewrap operation has no previous collection epoch",
                )
            })?;
            let previous_key = sync_keys::collection_key(
                db,
                root,
                *operation.vault_id(),
                *payload.collection_id(),
                previous_epoch,
            )?;
            let current_key = sync_keys::collection_key(
                db,
                root,
                *operation.vault_id(),
                *payload.collection_id(),
                current_epoch,
            )?;
            log.accept_with(db, &operation, membership, |transaction| {
                sync_rotation::project_rewrap(
                    transaction,
                    *operation.vault_id(),
                    *payload.collection_id(),
                    membership,
                    operation.device_id(),
                    now_ms,
                    &previous_key,
                    &current_key,
                    object_key_envelope.clone(),
                    root,
                )?;
                Ok(())
            })
        }
        _ => Err(Error::new(
            ChurStatus::InvalidInput,
            "operation is not a collection rotation operation",
        )),
    }
}
