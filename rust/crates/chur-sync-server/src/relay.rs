use chur_core::limits::sync as bounds;
use chur_core::{ChurStatus, Error, Id, Result, ensure};
use chur_sync_protocol::membership::{EnrollmentRecord, RevocationRecord};
use chur_sync_protocol::operation::Operation;
use chur_sync_protocol::state::{DeviceStatus, MembershipState};
use rusqlite::{Connection, OptionalExtension, Transaction, params};

use super::{ReferenceServer, corrupt_row, from_sqlite, map_sqlite, to_sqlite};

const ENROLLMENT_KIND: i64 = 1;
const REVOCATION_KIND: i64 = 2;

/// Durable result of submitting one canonical relay record.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RelayOutcome {
    /// New canonical bytes were stored.
    Stored,
    /// The exact canonical bytes were already stored.
    Duplicate,
}

impl ReferenceServer {
    /// Bootstraps an empty vault from its self-enrollment and outer operation.
    pub fn accept_initial_membership(
        &mut self,
        enrollment: &EnrollmentRecord,
        outer: &Operation,
    ) -> Result<RelayOutcome> {
        if let Some(stored) = existing_membership(
            &self.db,
            enrollment.vault_id(),
            enrollment.membership_generation(),
        )? {
            ensure!(
                stored == enrollment.encode() && operation_is_exact(&self.db, outer)?,
                SyncChainFork,
                "initial membership replay differs"
            );
            return Ok(RelayOutcome::Duplicate);
        }
        ensure!(
            membership_count(&self.db, enrollment.vault_id())? == 0,
            Conflict,
            "vault membership already exists"
        );
        ensure!(
            !crate::deletion::account_was_deleted(&self.db, enrollment.vault_id())?,
            Conflict,
            "deleted vault identifier cannot be reused"
        );
        let membership = MembershipState::bootstrap(enrollment)?;
        ensure!(
            outer.vault_id() == enrollment.vault_id()
                && outer.device_id() == enrollment.device_id()
                && outer.device_sequence() == enrollment.created_sequence(),
            AuthenticationFailed,
            "initial enrollment does not match its outer operation"
        );
        let operation_outcome = validate_operation(&self.db, outer, &membership)?;
        self.ensure_account_capacity(
            enrollment.vault_id(),
            new_record_bytes(
                operation_outcome,
                outer.encode().len(),
                enrollment.encode().len(),
            )?,
        )?;
        let transaction = self
            .db
            .transaction()
            .map_err(|error| map_sqlite(error, "initial membership transaction failed"))?;
        insert_operation(&transaction, outer, operation_outcome)?;
        insert_membership(&transaction, ENROLLMENT_KIND, enrollment, outer)?;
        crate::object_keys::retire_device_deliveries(
            &transaction,
            enrollment.vault_id(),
            enrollment.device_id(),
        )?;
        transaction
            .commit()
            .map_err(|error| map_sqlite(error, "initial membership commit failed"))?;
        Ok(RelayOutcome::Stored)
    }

    /// Accepts one signed successor enrollment and its outer operation.
    pub fn accept_enrollment(
        &mut self,
        enrollment: &EnrollmentRecord,
        outer: &Operation,
    ) -> Result<RelayOutcome> {
        if let Some(outcome) = self.membership_replay(
            enrollment.vault_id(),
            enrollment.membership_generation(),
            &enrollment.encode(),
            outer,
        )? {
            return Ok(outcome);
        }
        let mut membership = membership_state(&self.db, enrollment.vault_id())?;
        crate::checkpoint::verify_enrollment_checkpoint(&self.db, enrollment, &membership)?;
        let operation_outcome = validate_operation(&self.db, outer, &membership)?;
        membership.accept_enrollment(enrollment, outer.device_id(), outer.device_sequence())?;
        self.ensure_account_capacity(
            enrollment.vault_id(),
            new_record_bytes(
                operation_outcome,
                outer.encode().len(),
                enrollment.encode().len(),
            )?,
        )?;
        let transaction = self
            .db
            .transaction()
            .map_err(|error| map_sqlite(error, "enrollment transaction failed"))?;
        insert_operation(&transaction, outer, operation_outcome)?;
        insert_membership(&transaction, ENROLLMENT_KIND, enrollment, outer)?;
        crate::object_keys::retire_device_deliveries(
            &transaction,
            enrollment.vault_id(),
            enrollment.device_id(),
        )?;
        transaction
            .commit()
            .map_err(|error| map_sqlite(error, "enrollment commit failed"))?;
        Ok(RelayOutcome::Stored)
    }

    /// Accepts one signed device revocation and its outer operation.
    pub fn accept_revocation(
        &mut self,
        revocation: &RevocationRecord,
        outer: &Operation,
    ) -> Result<RelayOutcome> {
        if let Some(outcome) = self.membership_replay(
            revocation.vault_id(),
            revocation.membership_generation(),
            &revocation.encode(),
            outer,
        )? {
            return Ok(outcome);
        }
        let mut membership = membership_state(&self.db, revocation.vault_id())?;
        let operation_outcome = validate_operation(&self.db, outer, &membership)?;
        if revocation.final_accepted_device_sequence() == 0 {
            ensure!(
                outer
                    .observed_heads()
                    .iter()
                    .all(|head| head.device_id() != revocation.revoked_device_id()),
                AuthenticationFailed,
                "genesis revocation observes its target's authored history"
            );
        } else {
            let pinned_digest = operation_digest_at(
                &self.db,
                revocation.vault_id(),
                revocation.revoked_device_id(),
                revocation.final_accepted_device_sequence(),
            )?
            .ok_or_else(|| {
                Error::new(
                    ChurStatus::AuthenticationFailed,
                    "revocation point is not stored",
                )
            })?;
            ensure!(
                pinned_digest.as_slice() == revocation.final_accepted_operation_digest(),
                AuthenticationFailed,
                "revocation point digest differs"
            );
        }
        membership.accept_revocation(revocation, outer.device_id())?;
        self.ensure_account_capacity(
            revocation.vault_id(),
            new_record_bytes(
                operation_outcome,
                outer.encode().len(),
                revocation.encode().len(),
            )?,
        )?;
        let transaction = self
            .db
            .transaction()
            .map_err(|error| map_sqlite(error, "revocation transaction failed"))?;
        insert_operation(&transaction, outer, operation_outcome)?;
        insert_revocation(&transaction, revocation, outer)?;
        crate::object_keys::retire_device_deliveries(
            &transaction,
            revocation.vault_id(),
            revocation.revoked_device_id(),
        )?;
        crate::auth::revoke_in_transaction(
            &transaction,
            revocation.vault_id(),
            revocation.revoked_device_id(),
        )?;
        transaction
            .commit()
            .map_err(|error| map_sqlite(error, "revocation commit failed"))?;
        Ok(RelayOutcome::Stored)
    }

    /// Accepts one authenticated opaque operation under current membership.
    pub fn accept_operation(&mut self, operation: &Operation) -> Result<RelayOutcome> {
        let membership = membership_state(&self.db, operation.vault_id())?;
        let outcome = validate_operation(&self.db, operation, &membership)?;
        if outcome == RelayOutcome::Duplicate {
            return Ok(outcome);
        }
        self.ensure_account_capacity(operation.vault_id(), operation.encode().len() as u64)?;
        insert_operation(&self.db, operation, outcome)?;
        Ok(outcome)
    }

    /// Returns one bounded canonical device-chain page after a known sequence.
    pub fn operations_after(
        &self,
        vault_id: Id,
        device_id: Id,
        after_sequence: u64,
    ) -> Result<Vec<Vec<u8>>> {
        let membership = membership_state(&self.db, &vault_id)?;
        let cutoff = match membership.device(&device_id).map(|device| device.status()) {
            Some(DeviceStatus::Revoked { sequence, .. }) => {
                to_sqlite(sequence, "revocation cutoff does not fit")?
            }
            Some(DeviceStatus::Active) => i64::MAX,
            None => return Ok(Vec::new()),
        };
        let after = to_sqlite(after_sequence, "operation cursor does not fit")?;
        let mut statement = self
            .db
            .prepare(
                "SELECT record FROM operations
                 WHERE vault_id = ?1 AND device_id = ?2 AND device_sequence > ?3 AND device_sequence <= ?5
                 ORDER BY device_sequence LIMIT ?4",
            )
            .map_err(|error| map_sqlite(error, "operation page prepare failed"))?;
        let rows = statement
            .query_map(
                params![
                    vault_id.as_bytes().as_slice(),
                    device_id.as_bytes().as_slice(),
                    after,
                    i64::try_from(bounds::RESPONSE_OPERATIONS_MAX).unwrap_or(i64::MAX),
                    cutoff,
                ],
                |row| row.get::<_, Vec<u8>>(0),
            )
            .map_err(|error| map_sqlite(error, "operation page query failed"))?;
        bounded_records(rows, "stored operation page is invalid")
    }

    /// Returns bounded membership records after a known generation.
    pub fn membership_records_after(
        &self,
        vault_id: Id,
        after_generation: u64,
    ) -> Result<Vec<Vec<u8>>> {
        let after = to_sqlite(after_generation, "membership cursor does not fit")?;
        let mut statement = self
            .db
            .prepare(
                "SELECT record FROM membership_records
                 WHERE vault_id = ?1 AND membership_generation > ?2
                 ORDER BY membership_generation LIMIT ?3",
            )
            .map_err(|error| map_sqlite(error, "membership page prepare failed"))?;
        let rows = statement
            .query_map(
                params![
                    vault_id.as_bytes().as_slice(),
                    after,
                    i64::try_from(bounds::RESPONSE_OPERATIONS_MAX).unwrap_or(i64::MAX),
                ],
                |row| row.get::<_, Vec<u8>>(0),
            )
            .map_err(|error| map_sqlite(error, "membership page query failed"))?;
        bounded_records(rows, "stored membership page is invalid")
    }

    fn membership_replay(
        &self,
        vault_id: &Id,
        generation: u64,
        record: &[u8],
        outer: &Operation,
    ) -> Result<Option<RelayOutcome>> {
        let Some(stored) = existing_membership(&self.db, vault_id, generation)? else {
            return Ok(None);
        };
        ensure!(
            stored == record && operation_is_exact(&self.db, outer)?,
            SyncChainFork,
            "membership replay differs"
        );
        Ok(Some(RelayOutcome::Duplicate))
    }

    /// Enforces the reservation cap of one vault identity before new bytes land.
    ///
    /// `docs/sync/SERVER_OPERATOR.md` "Start" caps the reserved bytes of one
    /// vault identity, so the sum names every schema table that retains a
    /// record for one. `transport_tokens` is excluded: it holds one digest per
    /// enrolled device under `PRIMARY KEY(vault_id, device_id)`, and enrolment
    /// is already charged through `operations` and `membership_records`.
    ///
    /// This is the only enforcement point. A second copy of this sum lived in
    /// `begin_upload`, and the copy is how `collection_operations` and
    /// `deletion_requests` stayed out of the total: every byte those two tables
    /// held was free, so an authenticated device could append opaque records
    /// until the operator's disk was full. A table a later schema adds can now
    /// be missed in one place only, never in two.
    ///
    /// `deletion_requests` counts because the retained receipt belongs to that
    /// identity. No deletion route calls this function, so a full account can
    /// still delete itself, which `docs/sync/SERVER_TRUST_MODEL.md` requires.
    pub(super) fn ensure_account_capacity(&self, vault_id: &Id, added: u64) -> Result<()> {
        let used: i64 = self
            .db
            .query_row(
                "SELECT
                    COALESCE((SELECT SUM(expected_length) FROM object_transfers WHERE vault_id = ?1), 0)
                  + COALESCE((SELECT SUM(length(record)) FROM operations WHERE vault_id = ?1), 0)
                  + COALESCE((SELECT SUM(length(record)) FROM membership_records WHERE vault_id = ?1), 0)
                  + COALESCE((SELECT SUM(length(record)) FROM collection_membership_records WHERE issuer_vault_id = ?1), 0)
                  + COALESCE((SELECT SUM(length(record)) FROM collection_grants WHERE issuer_vault_id = ?1), 0)
                  + COALESCE((SELECT SUM(length(record)) FROM collection_operations WHERE issuer_vault_id = ?1), 0)
                  + COALESCE((SELECT COUNT(*) * 48 FROM shared_objects WHERE source_vault_id = ?1), 0)
                  + COALESCE((SELECT SUM(length(record)) FROM deletion_requests WHERE vault_id = ?1), 0)
                  + COALESCE((SELECT SUM(length(record)) FROM checkpoints WHERE vault_id = ?1), 0)
                  + COALESCE((SELECT SUM(length(record)) FROM object_key_requests WHERE recipient_vault_id = ?1), 0)
                  + COALESCE((SELECT SUM(length(record)) FROM object_key_grants WHERE source_vault_id = ?1), 0)
                  + COALESCE((SELECT COUNT(*) * 48 FROM object_key_dismissals WHERE source_vault_id = ?1), 0)
                  + COALESCE((SELECT COUNT(*) * 128 FROM object_key_rejections WHERE source_vault_id = ?1), 0)
                  + COALESCE((SELECT COUNT(*) * 192 FROM object_key_receipts WHERE recipient_vault_id = ?1), 0)",
                params![vault_id.as_bytes().as_slice()],
                |row| row.get(0),
            )
            .map_err(|error| map_sqlite(error, "account relay usage lookup failed"))?;
        let added = i64::try_from(added).map_err(|_| {
            Error::new(
                ChurStatus::ResourceLimitExceeded,
                "relay record size does not fit",
            )
        })?;
        ensure!(
            used.checked_add(added)
                .is_some_and(|total| total <= self.max_account_bytes as i64),
            ResourceLimitExceeded,
            "account ciphertext quota is exceeded"
        );
        Ok(())
    }
}

pub(super) fn validate_operation(
    db: &Connection,
    operation: &Operation,
    membership: &MembershipState,
) -> Result<RelayOutcome> {
    ensure!(
        operation.vault_id() == membership.vault_id(),
        AuthenticationFailed,
        "operation belongs to another vault"
    );
    let device = membership.device(operation.device_id()).ok_or_else(|| {
        Error::new(
            ChurStatus::AuthenticationFailed,
            "operation author is not enrolled",
        )
    })?;
    ensure!(
        device
            .signing_public_keys()
            .any(|key| operation.verify_signature(key).is_ok()),
        AuthenticationFailed,
        "operation signature did not verify under device key history"
    );
    if let DeviceStatus::Revoked { sequence, digest } = device.status() {
        ensure!(
            operation.device_sequence() <= sequence
                && (operation.device_sequence() != sequence || operation.digest() == digest),
            AuthenticationFailed,
            "revoked device operation exceeds its accepted cutoff"
        );
    }
    let existing = db
        .query_row(
            "SELECT record FROM operations
             WHERE vault_id = ?1 AND device_id = ?2 AND device_sequence = ?3",
            params![
                operation.vault_id().as_bytes().as_slice(),
                operation.device_id().as_bytes().as_slice(),
                to_sqlite(
                    operation.device_sequence(),
                    "operation sequence does not fit"
                )?,
            ],
            |row| row.get::<_, Vec<u8>>(0),
        )
        .optional()
        .map_err(|error| map_sqlite(error, "operation sequence lookup failed"))?;
    if let Some(existing) = existing {
        ensure!(
            existing == operation.encode(),
            SyncChainFork,
            "device operation fork detected"
        );
        return Ok(RelayOutcome::Duplicate);
    }
    ensure!(
        !operation_id_exists(db, operation)?,
        Conflict,
        "operation identifier was reused"
    );
    if operation.device_sequence() > 1 {
        let previous_sequence = operation.device_sequence() - 1;
        let previous: Option<Vec<u8>> = db
            .query_row(
                "SELECT digest FROM operations
                 WHERE vault_id = ?1 AND device_id = ?2 AND device_sequence = ?3",
                params![
                    operation.vault_id().as_bytes().as_slice(),
                    operation.device_id().as_bytes().as_slice(),
                    to_sqlite(
                        previous_sequence,
                        "previous operation sequence does not fit"
                    )?,
                ],
                |row| row.get(0),
            )
            .optional()
            .map_err(|error| map_sqlite(error, "previous operation lookup failed"))?;
        let previous = previous
            .ok_or_else(|| Error::new(ChurStatus::Conflict, "previous operation is absent"))?;
        ensure!(
            previous.as_slice() == operation.previous_operation_hash(),
            SyncChainFork,
            "previous operation digest differs"
        );
    }
    for head in operation.observed_heads() {
        let held: bool = db
            .query_row(
                "SELECT EXISTS(
                    SELECT 1 FROM operations
                    WHERE vault_id = ?1 AND device_id = ?2 AND device_sequence = ?3
                 )",
                params![
                    operation.vault_id().as_bytes().as_slice(),
                    head.device_id().as_bytes().as_slice(),
                    to_sqlite(head.device_sequence(), "observed sequence does not fit")?,
                ],
                |row| row.get(0),
            )
            .map_err(|error| map_sqlite(error, "observed operation lookup failed"))?;
        ensure!(held, Conflict, "observed operation is absent");
    }
    Ok(RelayOutcome::Stored)
}

fn operation_id_exists(db: &Connection, operation: &Operation) -> Result<bool> {
    db.query_row(
        "SELECT EXISTS(
            SELECT 1 FROM operations WHERE vault_id = ?1 AND operation_id = ?2
         )",
        params![
            operation.vault_id().as_bytes().as_slice(),
            operation.operation_id().as_bytes().as_slice(),
        ],
        |row| row.get(0),
    )
    .map_err(|error| map_sqlite(error, "operation identifier lookup failed"))
}

fn operation_digest_at(
    db: &Connection,
    vault_id: &Id,
    device_id: &Id,
    sequence: u64,
) -> Result<Option<Vec<u8>>> {
    db.query_row(
        "SELECT digest FROM operations
         WHERE vault_id = ?1 AND device_id = ?2 AND device_sequence = ?3",
        params![
            vault_id.as_bytes().as_slice(),
            device_id.as_bytes().as_slice(),
            to_sqlite(sequence, "operation sequence does not fit")?,
        ],
        |row| row.get(0),
    )
    .optional()
    .map_err(|error| map_sqlite(error, "revocation point lookup failed"))
}

pub(super) fn operation_is_exact(db: &Connection, operation: &Operation) -> Result<bool> {
    let stored: Option<Vec<u8>> = db
        .query_row(
            "SELECT record FROM operations
             WHERE vault_id = ?1 AND device_id = ?2 AND device_sequence = ?3",
            params![
                operation.vault_id().as_bytes().as_slice(),
                operation.device_id().as_bytes().as_slice(),
                to_sqlite(
                    operation.device_sequence(),
                    "operation sequence does not fit"
                )?,
            ],
            |row| row.get(0),
        )
        .optional()
        .map_err(|error| map_sqlite(error, "operation replay lookup failed"))?;
    Ok(stored.as_deref() == Some(operation.encode().as_slice()))
}

pub(super) fn insert_operation(
    db: &Connection,
    operation: &Operation,
    outcome: RelayOutcome,
) -> Result<()> {
    if outcome == RelayOutcome::Duplicate {
        return Ok(());
    }
    db.execute(
        "INSERT INTO operations (
            vault_id, device_id, device_sequence, operation_id, digest, record
         ) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
        params![
            operation.vault_id().as_bytes().as_slice(),
            operation.device_id().as_bytes().as_slice(),
            to_sqlite(
                operation.device_sequence(),
                "operation sequence does not fit"
            )?,
            operation.operation_id().as_bytes().as_slice(),
            operation.digest().as_slice(),
            operation.encode(),
        ],
    )
    .map_err(|error| map_sqlite(error, "operation storage failed"))?;
    Ok(())
}

fn insert_membership(
    db: &Transaction<'_>,
    kind: i64,
    enrollment: &EnrollmentRecord,
    outer: &Operation,
) -> Result<()> {
    insert_membership_bytes(
        db,
        enrollment.vault_id(),
        enrollment.membership_generation(),
        kind,
        outer,
        &enrollment.encode(),
    )
}

fn insert_revocation(
    db: &Transaction<'_>,
    revocation: &RevocationRecord,
    outer: &Operation,
) -> Result<()> {
    insert_membership_bytes(
        db,
        revocation.vault_id(),
        revocation.membership_generation(),
        REVOCATION_KIND,
        outer,
        &revocation.encode(),
    )
}

fn insert_membership_bytes(
    db: &Transaction<'_>,
    vault_id: &Id,
    generation: u64,
    kind: i64,
    outer: &Operation,
    record: &[u8],
) -> Result<()> {
    db.execute(
        "INSERT INTO membership_records (
            vault_id, membership_generation, record_kind,
            outer_device_id, outer_device_sequence, record
         ) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
        params![
            vault_id.as_bytes().as_slice(),
            to_sqlite(generation, "membership generation does not fit")?,
            kind,
            outer.device_id().as_bytes().as_slice(),
            to_sqlite(
                outer.device_sequence(),
                "outer operation sequence does not fit"
            )?,
            record,
        ],
    )
    .map_err(|error| map_sqlite(error, "membership storage failed"))?;
    Ok(())
}

pub(super) fn membership_state(db: &Connection, vault_id: &Id) -> Result<MembershipState> {
    let mut statement = db
        .prepare(
            "SELECT record_kind, outer_device_id, outer_device_sequence, record
             FROM membership_records WHERE vault_id = ?1 ORDER BY membership_generation",
        )
        .map_err(|error| map_sqlite(error, "membership restore prepare failed"))?;
    let rows = statement
        .query_map(params![vault_id.as_bytes().as_slice()], |row| {
            Ok((
                row.get::<_, i64>(0)?,
                row.get::<_, Vec<u8>>(1)?,
                row.get::<_, i64>(2)?,
                row.get::<_, Vec<u8>>(3)?,
            ))
        })
        .map_err(|error| map_sqlite(error, "membership restore query failed"))?;
    let records = rows
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(|error| map_sqlite(error, "membership restore row failed"))?;
    let mut records = records.into_iter();
    let (kind, outer_device, outer_sequence, record) = records
        .next()
        .ok_or_else(|| Error::new(ChurStatus::NotFound, "vault membership is absent"))?;
    ensure!(
        kind == ENROLLMENT_KIND,
        CatalogCorrupt,
        "initial membership kind is invalid"
    );
    let initial = EnrollmentRecord::decode(&record)
        .map_err(corrupt_row("stored initial membership is invalid"))?;
    let mut state = MembershipState::bootstrap(&initial)
        .map_err(corrupt_row("stored initial membership does not replay"))?;
    ensure!(
        initial.vault_id() == vault_id
            && initial.device_id().as_bytes() == outer_device.as_slice()
            && initial.created_sequence()
                == from_sqlite(outer_sequence, "stored outer sequence is invalid")?,
        CatalogCorrupt,
        "initial membership association is invalid"
    );
    for (kind, outer_device, outer_sequence, record) in records {
        let outer_device = Id::from_slice(&outer_device)
            .map_err(corrupt_row("stored outer device id is invalid"))?;
        let outer_sequence = from_sqlite(outer_sequence, "stored outer sequence is invalid")?;
        match kind {
            ENROLLMENT_KIND => {
                let enrollment = EnrollmentRecord::decode(&record)
                    .map_err(corrupt_row("stored enrollment is invalid"))?;
                state
                    .accept_enrollment(&enrollment, &outer_device, outer_sequence)
                    .map_err(corrupt_row("stored enrollment does not replay"))?;
            }
            REVOCATION_KIND => {
                let revocation = RevocationRecord::decode(&record)
                    .map_err(corrupt_row("stored revocation is invalid"))?;
                state
                    .accept_revocation(&revocation, &outer_device)
                    .map_err(corrupt_row("stored revocation does not replay"))?;
            }
            _ => {
                return Err(Error::new(
                    ChurStatus::CatalogCorrupt,
                    "stored membership kind is invalid",
                ));
            }
        }
    }
    Ok(state)
}

fn existing_membership(db: &Connection, vault_id: &Id, generation: u64) -> Result<Option<Vec<u8>>> {
    db.query_row(
        "SELECT record FROM membership_records
         WHERE vault_id = ?1 AND membership_generation = ?2",
        params![
            vault_id.as_bytes().as_slice(),
            to_sqlite(generation, "membership generation does not fit")?,
        ],
        |row| row.get(0),
    )
    .optional()
    .map_err(|error| map_sqlite(error, "membership replay lookup failed"))
}

fn membership_count(db: &Connection, vault_id: &Id) -> Result<u64> {
    let count: i64 = db
        .query_row(
            "SELECT COUNT(*) FROM membership_records WHERE vault_id = ?1",
            params![vault_id.as_bytes().as_slice()],
            |row| row.get(0),
        )
        .map_err(|error| map_sqlite(error, "membership count failed"))?;
    // SQLite computes this count, and no row stores it, so a negative value is
    // an implementation failure and not a damaged row.
    u64::try_from(count)
        .map_err(|_| Error::new(ChurStatus::InternalFailure, "membership count is invalid"))
}

// The width changes here rather than at each call site, because
// `ensure_account_capacity` counts bytes against the `u64` `max_account_bytes`.
fn new_record_bytes(outcome: RelayOutcome, operation: usize, membership: usize) -> Result<u64> {
    membership
        .checked_add(if outcome == RelayOutcome::Stored {
            operation
        } else {
            0
        })
        .map(|bytes| bytes as u64)
        .ok_or_else(|| {
            Error::new(
                ChurStatus::ResourceLimitExceeded,
                "relay record size overflows",
            )
        })
}

pub(super) fn bounded_records(
    rows: rusqlite::MappedRows<'_, impl FnMut(&rusqlite::Row<'_>) -> rusqlite::Result<Vec<u8>>>,
    context: &'static str,
) -> Result<Vec<Vec<u8>>> {
    let mut records = Vec::new();
    let mut bytes = 0usize;
    for row in rows {
        let record = row.map_err(|error| map_sqlite(error, context))?;
        let next = bytes
            .checked_add(record.len())
            .ok_or_else(|| Error::new(ChurStatus::CatalogCorrupt, context))?;
        if next > bounds::RESPONSE_BYTES_MAX {
            break;
        }
        bytes = next;
        records.push(record);
    }
    Ok(records)
}

#[cfg(test)]
mod tests {
    #![allow(clippy::expect_used)]

    use chur_core::Id;
    use chur_sync_protocol::{
        checkpoint::{Checkpoint, CheckpointHead},
        membership::{EnrollmentRecord, RevocationRecord},
        operation::{DeviceSigningKey, Operation},
    };

    use super::*;

    #[test]
    fn native_cached_genesis_revocation_survives_ready_winning_the_relay_race() {
        use chur_catalog::{
            device_pairing, paths::VaultRoot, sync_engine, sync_membership, sync_receive,
            sync_staging::LockedStaging, vault,
        };
        use chur_media::{backup, progress::Uninterrupted};
        use std::io::Cursor;
        let source_dir = crate::tests::TestRoot::new();
        let target_dir = crate::tests::TestRoot::new();
        let source_path = VaultRoot::new(source_dir.0.clone());
        let target_path = VaultRoot::new(target_dir.0.clone());
        let mut source = vault::create(&source_path, b"strong race test password", 1)
            .expect("source")
            .activate()
            .expect("activate");
        let secret = source.root_secret().expect("root").duplicate();
        let vault_id = source.vault_id();
        let (owner, _) = sync_receive::provision_local_identity(
            source.catalog().expect("catalog"),
            &secret,
            vault_id,
        )
        .expect("ordinary source");
        let mut bytes = Cursor::new(Vec::new());
        backup::create(&mut source, &mut bytes, 2, &mut Uninterrupted).expect("real backup");
        backup::restore(
            &target_path,
            &mut bytes,
            b"strong race test password",
            &mut Uninterrupted,
        )
        .expect("real restore");
        let mut target = vault::unlock_with_password(&target_path, b"strong race test password", 3)
            .expect("target");
        let request =
            device_pairing::prepare_request(target.catalog().expect("catalog"), &secret, vault_id)
                .expect("fresh request");
        let approval = device_pairing::approve(
            source.catalog().expect("catalog"),
            &secret,
            vault_id,
            &request,
            "https://sync.example",
            &[80; 32],
            4,
        )
        .expect("approval");
        device_pairing::accept_start(
            target.catalog().expect("catalog"),
            &secret,
            vault_id,
            &approval.approval,
        )
        .expect("accept approval");
        for record in chur_catalog::sync_log::records_after(
            source.catalog_ref().expect("catalog"),
            owner.device_id(),
            0,
        )
        .expect("proofs")
        {
            sync_engine::stage_inbound(
                &target_path,
                vault_id,
                sync_engine::StagedKind::Operation,
                5,
                &record,
            )
            .expect("stage");
        }
        let mut inbox = LockedStaging::open(target_path.sync_inbox(&vault_id)).expect("inbox");
        let report = sync_engine::process_staged(
            target.catalog().expect("catalog"),
            &secret,
            vault_id,
            &mut inbox,
            5,
        )
        .expect("process");
        assert_eq!(report.rejected, 0);
        device_pairing::finalize(target.catalog().expect("catalog"), &secret, vault_id, 6)
            .expect("finalize");
        let ready = Operation::decode(
            &chur_catalog::sync_log::record_at(
                target.catalog_ref().expect("catalog"),
                request.device_id(),
                1,
            )
            .expect("Ready lookup")
            .expect("Ready"),
        )
        .expect("Ready decode");
        let prepared = device_pairing::prepare_revoke(
            source.catalog().expect("catalog"),
            &secret,
            vault_id,
            *request.device_id(),
            7,
        )
        .expect("durable zero cutoff");
        let revocation = RevocationRecord::decode(&prepared.revocation).expect("revocation");
        assert_eq!(revocation.final_accepted_device_sequence(), 0);
        let outer = Operation::decode(&prepared.operation).expect("outer");
        let relay_dir = crate::tests::TestRoot::new();
        let mut relay = ReferenceServer::open(&relay_dir.0, 1024, 1048576).expect("relay");
        let initial = sync_membership::enrollment_for_device(
            source.catalog_ref().expect("catalog"),
            owner.device_id(),
        )
        .expect("initial");
        let first = Operation::decode(
            &chur_catalog::sync_log::record_at(
                source.catalog_ref().expect("catalog"),
                owner.device_id(),
                1,
            )
            .expect("lookup")
            .expect("initial operation"),
        )
        .expect("initial decode");
        relay
            .accept_initial_membership(&initial, &first)
            .expect("relay bootstrap");
        relay
            .accept_checkpoint(&Checkpoint::decode(&approval.checkpoint).expect("checkpoint"))
            .expect("relay checkpoint");
        relay
            .accept_enrollment(
                &EnrollmentRecord::decode(&approval.enrollment).expect("enrollment"),
                &Operation::decode(&approval.operation).expect("enrollment outer"),
            )
            .expect("relay enrollment");
        relay
            .set_transport_token(vault_id, *request.device_id(), &[80; 32])
            .expect("token");
        relay
            .accept_operation(&ready)
            .expect("Ready wins before revoke HTTP");
        relay
            .accept_revocation(&revocation, &outer)
            .expect("issuer prefix wins independently of arrival order");
        let retry = device_pairing::prepare_revoke(
            source.catalog().expect("catalog"),
            &secret,
            vault_id,
            *request.device_id(),
            8,
        )
        .expect("native retry");
        assert_eq!(retry.operation, prepared.operation);
        assert_eq!(
            relay
                .accept_revocation(
                    &RevocationRecord::decode(&retry.revocation).expect("retry record"),
                    &Operation::decode(&retry.operation).expect("retry outer")
                )
                .expect("relay retry"),
            RelayOutcome::Duplicate
        );
        drop(relay);
        let mut relay = ReferenceServer::open(&relay_dir.0, 1024, 1048576).expect("restart");
        assert!(
            relay
                .operations_after(vault_id, *request.device_id(), 0)
                .expect("cutoff page")
                .is_empty()
        );
        assert!(relay.authenticate_transport(vault_id, &[80; 32]).is_err());
        assert!(relay.accept_operation(&ready).is_err());
    }

    #[test]
    fn genesis_revocation_rejects_a_lost_device_first_operation() {
        let root = crate::tests::TestRoot::new();
        let mut server = ReferenceServer::open(&root.0, 1024, 32768).expect("server");
        let vault = id(1);
        let owner = id(2);
        let peer = id(8);
        let owner_key = DeviceSigningKey::from_seed([3; 32]);
        let peer_key = DeviceSigningKey::from_seed([9; 32]);
        let initial = EnrollmentRecord::initial(vault, owner, owner_key.verifying_key(), [4; 32])
            .expect("initial")
            .sign(&owner_key);
        let first = operation(vault, owner, id(5), 1, [0; 32], &owner_key);
        server
            .accept_initial_membership(&initial, &first)
            .expect("bootstrap");
        let checkpoint = Checkpoint::new(
            vault,
            owner,
            1,
            1,
            initial.commitment(),
            vec![CheckpointHead::new(owner, 1, first.digest())],
            [20; 32],
            [0; 32],
        )
        .expect("checkpoint")
        .sign(&owner_key);
        server.accept_checkpoint(&checkpoint).expect("checkpoint");
        let enrollment = EnrollmentRecord::new(
            vault,
            peer,
            peer_key.verifying_key(),
            [11; 32],
            2,
            owner,
            2,
            initial.commitment(),
            checkpoint.commitment(),
        )
        .expect("peer")
        .sign(&owner_key);
        let second = operation(vault, owner, id(10), 2, first.digest(), &owner_key);
        server
            .accept_enrollment(&enrollment, &second)
            .expect("enroll");
        let revoke =
            RevocationRecord::new(vault, peer, 0, [0; 32], 3, owner, enrollment.commitment())
                .expect("never-authored revocation")
                .sign(&owner_key);
        let third = operation(vault, owner, id(14), 3, second.digest(), &owner_key);
        server
            .set_transport_token(vault, peer, &[21; 32])
            .expect("peer token");
        let ready = operation(vault, peer, id(15), 1, [0; 32], &peer_key);
        server
            .accept_operation(&ready)
            .expect("concurrent Ready reaches relay first");
        server
            .accept_revocation(&revoke, &third)
            .expect("revoke lost approval");
        assert_eq!(
            server
                .accept_revocation(&revoke, &third)
                .expect("same durable revocation retry"),
            RelayOutcome::Duplicate
        );
        let retained: i64 = server
            .db
            .query_row(
                "SELECT count(*) FROM operations WHERE vault_id = ?1 AND device_id = ?2",
                params![vault.as_bytes().as_slice(), peer.as_bytes().as_slice()],
                |row| row.get(0),
            )
            .expect("retained signed history");
        assert_eq!(retained, 1);
        server
            .accept_operation(&operation(
                vault,
                owner,
                id(25),
                4,
                third.digest(),
                &owner_key,
            ))
            .expect("another active device continues");
        assert!(server.authenticate_transport(vault, &[21; 32]).is_err());
        assert!(
            server
                .operations_after(vault, peer, 0)
                .expect("revoked prefix delivery")
                .is_empty()
        );
        assert_eq!(
            server
                .accept_operation(&ready)
                .expect_err("revoked before genesis")
                .status(),
            ChurStatus::AuthenticationFailed
        );
    }

    #[test]
    fn signed_relay_survives_restart_and_rejects_forks_and_revoked_authors() {
        let root = crate::tests::TestRoot::new();
        let vault = id(1);
        let first_device = id(2);
        let first_key = DeviceSigningKey::from_seed([3; 32]);
        let enrollment =
            EnrollmentRecord::initial(vault, first_device, first_key.verifying_key(), [4; 32])
                .expect("initial enrollment")
                .sign(&first_key);
        let first = operation(vault, first_device, id(5), 1, [0; 32], &first_key);

        let mut server = ReferenceServer::open(&root.0, 1_024, 32_768).expect("server");
        assert_eq!(
            server
                .accept_initial_membership(&enrollment, &first)
                .expect("bootstrap"),
            RelayOutcome::Stored
        );
        assert_eq!(
            server
                .accept_initial_membership(&enrollment, &first)
                .expect("bootstrap replay"),
            RelayOutcome::Duplicate
        );
        let second = operation(vault, first_device, id(6), 2, first.digest(), &first_key);
        server.accept_operation(&second).expect("second operation");
        drop(server);

        let mut server = ReferenceServer::open(&root.0, 1_024, 32_768).expect("reopen");
        assert_eq!(
            server
                .operations_after(vault, first_device, 0)
                .expect("fetch"),
            vec![first.encode(), second.encode()]
        );
        let fork = operation(vault, first_device, id(7), 2, first.digest(), &first_key);
        assert_eq!(
            server.accept_operation(&fork).expect_err("fork").status(),
            chur_core::ChurStatus::SyncChainFork
        );

        let second_device = id(8);
        let second_key = DeviceSigningKey::from_seed([9; 32]);
        let third = operation(vault, first_device, id(10), 3, second.digest(), &first_key);
        let missing_checkpoint_enrollment = EnrollmentRecord::new(
            vault,
            second_device,
            second_key.verifying_key(),
            [11; 32],
            3,
            first_device,
            2,
            enrollment.commitment(),
            [12; 32],
        )
        .expect("missing checkpoint enrollment")
        .sign(&first_key);
        assert_eq!(
            server
                .accept_enrollment(&missing_checkpoint_enrollment, &third)
                .expect_err("missing bootstrap checkpoint")
                .status(),
            chur_core::ChurStatus::AuthenticationFailed
        );
        let bootstrap_checkpoint = Checkpoint::new(
            vault,
            first_device,
            2,
            1,
            enrollment.commitment(),
            vec![CheckpointHead::new(first_device, 2, second.digest())],
            [20; 32],
            [0; 32],
        )
        .expect("bootstrap checkpoint")
        .sign(&first_key);
        server
            .accept_checkpoint(&bootstrap_checkpoint)
            .expect("store bootstrap checkpoint");
        let second_enrollment = EnrollmentRecord::new(
            vault,
            second_device,
            second_key.verifying_key(),
            [11; 32],
            3,
            first_device,
            2,
            enrollment.commitment(),
            bootstrap_checkpoint.commitment(),
        )
        .expect("second enrollment")
        .sign(&first_key);
        server
            .accept_enrollment(&second_enrollment, &third)
            .expect("enroll second device");
        let second_device_operation =
            operation(vault, second_device, id(13), 1, [0; 32], &second_key);
        server
            .accept_operation(&second_device_operation)
            .expect("second device operation");
        let second_token = [21; 32];
        server
            .set_transport_token(vault, second_device, &second_token)
            .expect("second device token");

        let fourth = operation(vault, first_device, id(14), 4, third.digest(), &first_key);
        let revocation = RevocationRecord::new(
            vault,
            second_device,
            1,
            second_device_operation.digest(),
            3,
            first_device,
            second_enrollment.commitment(),
        )
        .expect("revocation")
        .sign(&first_key);
        let invalid_genesis = RevocationRecord::new(
            vault,
            second_device,
            0,
            [0; 32],
            3,
            first_device,
            second_enrollment.commitment(),
        )
        .expect("genesis syntax")
        .sign(&first_key);
        let observing_outer = Operation::new(
            id(24),
            vault,
            first_device,
            4,
            third.digest(),
            vec![chur_sync_protocol::operation::ObservedHead::new(
                second_device,
                1,
            )],
            id(16),
            [vec![17; 24], vec![18; 16]].concat(),
            [0; 64],
        )
        .expect("observing outer")
        .sign(&first_key);
        assert_eq!(
            server
                .accept_revocation(&invalid_genesis, &observing_outer)
                .expect_err("known author cannot be revoked at genesis")
                .status(),
            ChurStatus::AuthenticationFailed
        );
        let wrong_revocation = RevocationRecord::new(
            vault,
            second_device,
            1,
            [99; 32],
            3,
            first_device,
            second_enrollment.commitment(),
        )
        .expect("wrong revocation")
        .sign(&first_key);
        assert_eq!(
            server
                .accept_revocation(&wrong_revocation, &fourth)
                .expect_err("wrong revocation point")
                .status(),
            chur_core::ChurStatus::AuthenticationFailed
        );
        server
            .accept_revocation(&revocation, &fourth)
            .expect("revoke second device");
        let token_count: i64 = server
            .db
            .query_row(
                "SELECT count(*) FROM transport_tokens
                 WHERE vault_id = ?1 AND device_id = ?2",
                rusqlite::params![
                    vault.as_bytes().as_slice(),
                    second_device.as_bytes().as_slice(),
                ],
                |row| row.get(0),
            )
            .expect("token count");
        assert_eq!(token_count, 0);
        assert_eq!(
            server
                .authenticate_transport(vault, &second_token)
                .expect_err("revoked token")
                .status(),
            chur_core::ChurStatus::AuthenticationFailed
        );
        assert_eq!(
            server
                .membership_records_after(vault, 0)
                .expect("membership fetch"),
            vec![
                enrollment.encode(),
                second_enrollment.encode(),
                revocation.encode(),
            ]
        );
        let rejected = operation(
            vault,
            second_device,
            id(15),
            2,
            second_device_operation.digest(),
            &second_key,
        );
        assert_eq!(
            server
                .accept_operation(&rejected)
                .expect_err("revoked author")
                .status(),
            chur_core::ChurStatus::AuthenticationFailed
        );
    }

    /// Every table the schema keys to a vault identity reaches the cap.
    ///
    /// `docs/sync/SERVER_OPERATOR.md` caps the reserved bytes of one vault
    /// identity. `collection_operations` and `deletion_requests` were absent
    /// from the sum, so their stored bytes were free and an authenticated
    /// device could append opaque records without limit. The upload path held a
    /// second copy of the same sum, which is why it is asserted here too.
    #[test]
    fn stored_sharing_and_deletion_records_occupy_the_account_reservation() {
        let root = crate::tests::TestRoot::new();
        let mut server = ReferenceServer::open(&root.0, 8, 1_024).expect("server");
        let vault = id(1);
        server
            .ensure_account_capacity(&vault, 1_024)
            .expect("an empty account holds the whole reservation");

        server
            .db
            .execute(
                "INSERT INTO collection_operations (
                    key_selector, issuer_vault_id, issuer_device_id, device_sequence,
                    operation_id, digest, record
                 ) VALUES (?1, ?2, ?3, 1, ?4, ?5, ?6)",
                params![
                    id(2).as_bytes().as_slice(),
                    vault.as_bytes().as_slice(),
                    id(3).as_bytes().as_slice(),
                    id(4).as_bytes().as_slice(),
                    [5u8; 32].as_slice(),
                    vec![6u8; 512],
                ],
            )
            .expect("stored collection operation");
        server
            .db
            .execute(
                "INSERT INTO deletion_requests (
                    vault_id, request_id, target_kind, target_id, record
                 ) VALUES (?1, ?2, 1, ?3, ?4)",
                params![
                    vault.as_bytes().as_slice(),
                    id(7).as_bytes().as_slice(),
                    id(8).as_bytes().as_slice(),
                    vec![9u8; 512],
                ],
            )
            .expect("stored deletion receipt");

        assert_eq!(
            server
                .ensure_account_capacity(&vault, 1)
                .expect_err("the stored records fill the reservation")
                .status(),
            chur_core::ChurStatus::ResourceLimitExceeded
        );
        // Without the shared gate this passes: the upload path's own copy of
        // the sum reads zero for a vault holding only these two tables.
        assert_eq!(
            server
                .begin_upload(vault, id(10), id(11), 1)
                .expect_err("the stored records fill the reservation for an upload too")
                .status(),
            chur_core::ChurStatus::ResourceLimitExceeded
        );
    }

    fn id(byte: u8) -> Id {
        Id::new([byte; 16]).expect("id")
    }

    fn operation(
        vault_id: Id,
        device_id: Id,
        operation_id: Id,
        sequence: u64,
        previous: [u8; 32],
        key: &DeviceSigningKey,
    ) -> Operation {
        Operation::new(
            operation_id,
            vault_id,
            device_id,
            sequence,
            previous,
            Vec::new(),
            id(16),
            [vec![17; 24], vec![18; 16]].concat(),
            [0; 64],
        )
        .expect("operation")
        .sign(key)
    }
}
