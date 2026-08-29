use chur_core::limits::sync as bounds;
use chur_core::{ChurStatus, Error, Id, Result, ensure};
use chur_sync_protocol::membership::{EnrollmentRecord, RevocationRecord};
use chur_sync_protocol::operation::Operation;
use chur_sync_protocol::state::{DeviceStatus, MembershipState};
use rusqlite::{Connection, OptionalExtension, Transaction, params};

use super::{ReferenceServer, from_sqlite, map_sqlite, to_sqlite};

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
        self.ensure_account_capacity(operation.vault_id(), operation.encode().len())?;
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
        let after = to_sqlite(after_sequence, "operation cursor does not fit")?;
        let mut statement = self
            .db
            .prepare(
                "SELECT record FROM operations
                 WHERE vault_id = ?1 AND device_id = ?2 AND device_sequence > ?3
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

    fn ensure_account_capacity(&self, vault_id: &Id, added: usize) -> Result<()> {
        let used: i64 = self
            .db
            .query_row(
                "SELECT
                    COALESCE((SELECT SUM(expected_length) FROM object_transfers WHERE vault_id = ?1), 0)
                  + COALESCE((SELECT SUM(length(record)) FROM operations WHERE vault_id = ?1), 0)
                  + COALESCE((SELECT SUM(length(record)) FROM membership_records WHERE vault_id = ?1), 0)",
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

fn validate_operation(
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

fn operation_is_exact(db: &Connection, operation: &Operation) -> Result<bool> {
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

fn insert_operation(db: &Connection, operation: &Operation, outcome: RelayOutcome) -> Result<()> {
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

fn membership_state(db: &Connection, vault_id: &Id) -> Result<MembershipState> {
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
    let initial = EnrollmentRecord::decode(&record)?;
    let mut state = MembershipState::bootstrap(&initial)?;
    ensure!(
        initial.vault_id() == vault_id
            && initial.device_id().as_bytes() == outer_device.as_slice()
            && initial.created_sequence()
                == from_sqlite(outer_sequence, "stored outer sequence is invalid")?,
        CatalogCorrupt,
        "initial membership association is invalid"
    );
    for (kind, outer_device, outer_sequence, record) in records {
        let outer_device = Id::from_slice(&outer_device)?;
        let outer_sequence = from_sqlite(outer_sequence, "stored outer sequence is invalid")?;
        match kind {
            ENROLLMENT_KIND => {
                let enrollment = EnrollmentRecord::decode(&record)?;
                state.accept_enrollment(&enrollment, &outer_device, outer_sequence)?;
            }
            REVOCATION_KIND => {
                let revocation = RevocationRecord::decode(&record)?;
                state.accept_revocation(&revocation, &outer_device)?;
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
    from_sqlite(count, "stored membership count is invalid")
}

fn new_record_bytes(outcome: RelayOutcome, operation: usize, membership: usize) -> Result<usize> {
    membership
        .checked_add(if outcome == RelayOutcome::Stored {
            operation
        } else {
            0
        })
        .ok_or_else(|| {
            Error::new(
                ChurStatus::ResourceLimitExceeded,
                "relay record size overflows",
            )
        })
}

fn bounded_records(
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
        membership::{EnrollmentRecord, RevocationRecord},
        operation::{DeviceSigningKey, Operation},
    };

    use super::*;

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
        let second_enrollment = EnrollmentRecord::new(
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
