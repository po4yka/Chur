//! Durable accepted operation logs and fork evidence in catalog v2.

use std::collections::{BTreeMap, BTreeSet};

use chur_core::{ChurStatus, Error, Id, Result, bail, ensure};
use chur_crypto::Commitment;
use chur_sync_protocol::{
    checkpoint::Checkpoint,
    operation::Operation,
    operation_log::{ApplyOutcome, ForkState, OperationLog},
    state::{DeviceStatus, MembershipState},
};
use rusqlite::{OptionalExtension, Transaction, params};

use crate::{
    db::{CatalogDb, as_sqlite_integer, from_sqlite_integer, map_sqlite},
    schema::bump_generation,
};

/// A protocol log rebuilt from accepted durable rows.
pub struct DurableOperationLog {
    log: OperationLog,
    forked_devices: BTreeSet<Id>,
}

impl DurableOperationLog {
    /// Latest accepted head for one device.
    #[must_use]
    pub fn head(&self, device_id: &Id) -> Option<(u64, Commitment)> {
        self.log.head(device_id)
    }

    /// Durable checkpoint floor for one device.
    #[must_use]
    pub fn floor(&self, device_id: &Id) -> Option<(u64, Commitment)> {
        self.log.floor(device_id)
    }

    /// Validates one received operation and commits its logical projection,
    /// record, and accepted head in one catalog transaction.
    pub fn accept_with(
        &mut self,
        db: &mut CatalogDb,
        operation: &Operation,
        membership: &MembershipState,
        apply: impl FnOnce(&Transaction<'_>) -> Result<()>,
    ) -> Result<ApplyOutcome> {
        if self.forked_devices.contains(operation.device_id()) {
            bail!(
                SyncChainFork,
                "the device chain is frozen after a durable fork"
            );
        }
        let mut candidate = self.log.clone();
        match candidate.accept(operation, membership) {
            Ok(ApplyOutcome::Applied) => {
                let record = operation.encode();
                let digest = operation.digest();
                db.transaction(|transaction| {
                    apply(transaction)?;
                    transaction
                        .execute(
                            "INSERT INTO sync_operations
                                 (device_id, device_sequence, operation_id, digest, record)
                             VALUES (?1, ?2, ?3, ?4, ?5)",
                            params![
                                operation.device_id().as_bytes().as_slice(),
                                as_sqlite_integer(
                                    operation.device_sequence(),
                                    "the operation sequence is too large"
                                )?,
                                operation.operation_id().as_bytes().as_slice(),
                                digest.as_slice(),
                                record,
                            ],
                        )
                        .map_err(|error| {
                            map_sqlite(error, "the accepted operation could not be written")
                        })?;
                    transaction
                        .execute(
                            "INSERT INTO sync_heads
                                 (device_id, accepted_sequence, accepted_digest,
                                  floor_sequence, floor_digest)
                             VALUES (?1, ?2, ?3, NULL, NULL)
                             ON CONFLICT(device_id) DO UPDATE SET
                                 accepted_sequence = excluded.accepted_sequence,
                                 accepted_digest = excluded.accepted_digest",
                            params![
                                operation.device_id().as_bytes().as_slice(),
                                as_sqlite_integer(
                                    operation.device_sequence(),
                                    "the operation sequence is too large"
                                )?,
                                digest.as_slice(),
                            ],
                        )
                        .map_err(|error| {
                            map_sqlite(error, "the accepted head could not be written")
                        })?;
                    bump_generation(transaction)
                })?;
                self.log = candidate;
                Ok(ApplyOutcome::Applied)
            }
            Ok(outcome) => Ok(outcome),
            Err(error) if error.status() == ChurStatus::SyncChainFork => {
                let evidence = candidate.fork(operation.device_id()).ok_or_else(|| {
                    Error::new(
                        ChurStatus::InternalFailure,
                        "the protocol reported a fork without evidence",
                    )
                })?;
                let state = match evidence.state() {
                    ForkState::Detected => 1i64,
                    ForkState::Acknowledged => 2i64,
                };
                db.transaction(|transaction| {
                    transaction
                        .execute(
                            "INSERT INTO sync_forks
                                 (device_id, state, accepted_record, conflicting_record)
                             VALUES (?1, ?2, ?3, ?4)
                             ON CONFLICT(device_id) DO UPDATE SET
                                 state = excluded.state,
                                 accepted_record = excluded.accepted_record,
                                 conflicting_record = excluded.conflicting_record",
                            params![
                                operation.device_id().as_bytes().as_slice(),
                                state,
                                evidence.accepted_record(),
                                evidence.conflicting_record(),
                            ],
                        )
                        .map_err(|sqlite| {
                            map_sqlite(sqlite, "fork evidence could not be written")
                        })?;
                    bump_generation(transaction)
                })?;
                self.log = candidate;
                self.forked_devices.insert(*operation.device_id());
                Err(error)
            }
            Err(error) => Err(error),
        }
    }

    /// Marks durable fork evidence as seen without unfreezing the chain.
    pub fn acknowledge_fork(&mut self, db: &mut CatalogDb, device_id: &Id) -> Result<()> {
        ensure!(
            self.forked_devices.contains(device_id),
            NotFound,
            "device chain has no durable fork"
        );
        db.transaction(|transaction| {
            let changed = transaction
                .execute(
                    "UPDATE sync_forks SET state = 2 WHERE device_id = ?1",
                    [device_id.as_bytes().as_slice()],
                )
                .map_err(|error| map_sqlite(error, "fork evidence could not be acknowledged"))?;
            ensure!(changed == 1, NotFound, "device chain has no durable fork");
            bump_generation(transaction)
        })
    }

    /// Clears fork state only after membership revoked the forked device.
    pub fn resolve_by_revocation(
        &mut self,
        db: &mut CatalogDb,
        device_id: &Id,
        membership: &MembershipState,
    ) -> Result<()> {
        ensure!(
            matches!(
                membership.device(device_id).map(|device| device.status()),
                Some(DeviceStatus::Revoked { .. })
            ),
            AuthenticationFailed,
            "device is not revoked"
        );
        ensure!(
            self.forked_devices.contains(device_id),
            NotFound,
            "device chain has no durable fork"
        );
        db.transaction(|transaction| {
            let changed = transaction
                .execute(
                    "DELETE FROM sync_forks WHERE device_id = ?1",
                    [device_id.as_bytes().as_slice()],
                )
                .map_err(|error| map_sqlite(error, "fork evidence could not be cleared"))?;
            ensure!(changed == 1, NotFound, "device chain has no durable fork");
            bump_generation(transaction)
        })?;
        self.forked_devices.remove(device_id);
        Ok(())
    }
}

/// Rebuilds and verifies the accepted log, heads, floors, and fork evidence.
pub fn load(db: &CatalogDb, membership: &MembershipState) -> Result<DurableOperationLog> {
    let devices = device_ids(db)?;
    let total: i64 = db
        .connection()
        .query_row("SELECT count(*) FROM sync_operations", [], |row| row.get(0))
        .map_err(|error| map_sqlite(error, "accepted operations could not be counted"))?;
    let total = from_sqlite_integer(total, "the operation count is negative")?;
    let mut next: BTreeMap<Id, u64> = devices.iter().map(|device| (*device, 1)).collect();
    let mut restored = 0u64;
    let mut log = OperationLog::new();
    while restored < total {
        let mut progressed = false;
        for device_id in &devices {
            let sequence = next[device_id];
            let Some((operation, digest)) = operation_at(db, device_id, sequence)? else {
                continue;
            };
            ensure!(
                operation.digest() == digest,
                CatalogCorrupt,
                "an accepted operation digest is malformed"
            );
            match log
                .restore_accepted(&operation, membership)
                .map_err(corrupt_log)?
            {
                ApplyOutcome::Applied => {
                    next.insert(
                        *device_id,
                        sequence.checked_add(1).ok_or_else(|| {
                            Error::new(
                                ChurStatus::CatalogCorrupt,
                                "an accepted operation sequence has no successor",
                            )
                        })?,
                    );
                    restored += 1;
                    progressed = true;
                }
                ApplyOutcome::PendingCause => {}
                _ => bail!(
                    CatalogCorrupt,
                    "accepted operation history has a gap or duplicate"
                ),
            }
        }
        ensure!(
            progressed,
            CatalogCorrupt,
            "accepted operation history has a missing or cyclic cause"
        );
    }
    restore_and_validate_heads(db, membership, &mut log)?;
    let forked_devices = validate_forks(db, membership)?;
    Ok(DurableOperationLog {
        log,
        forked_devices,
    })
}

fn device_ids(db: &CatalogDb) -> Result<Vec<Id>> {
    let mut statement = db
        .connection()
        .prepare("SELECT device_id FROM sync_devices ORDER BY device_id")
        .map_err(|error| map_sqlite(error, "sync devices could not be prepared"))?;
    let rows = statement
        .query_map([], |row| row.get::<_, Vec<u8>>(0))
        .map_err(|error| map_sqlite(error, "sync devices could not be read"))?;
    let mut devices = Vec::new();
    for row in rows {
        let bytes = row.map_err(|error| map_sqlite(error, "a sync device could not be read"))?;
        devices.push(crate::row::id(&bytes, "a sync device id is malformed")?);
    }
    Ok(devices)
}

fn operation_at(
    db: &CatalogDb,
    device_id: &Id,
    sequence: u64,
) -> Result<Option<(Operation, Commitment)>> {
    let row: Option<(Vec<u8>, Vec<u8>, Vec<u8>)> = db
        .connection()
        .query_row(
            "SELECT operation_id, digest, record FROM sync_operations
              WHERE device_id = ?1 AND device_sequence = ?2",
            params![
                device_id.as_bytes().as_slice(),
                as_sqlite_integer(sequence, "the operation sequence is too large")?,
            ],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
        )
        .optional()
        .map_err(|error| map_sqlite(error, "an accepted operation could not be read"))?;
    row.map(|(operation_id, digest, record)| {
        let operation = Operation::decode(&record).map_err(corrupt_log)?;
        ensure!(
            operation.device_id() == device_id
                && operation.device_sequence() == sequence
                && operation.operation_id()
                    == &crate::row::id(&operation_id, "an operation id is malformed")?,
            CatalogCorrupt,
            "an accepted operation contradicts its catalog row"
        );
        Ok((
            operation,
            commitment(&digest, "an operation digest is malformed")?,
        ))
    })
    .transpose()
}

fn restore_and_validate_heads(
    db: &CatalogDb,
    membership: &MembershipState,
    log: &mut OperationLog,
) -> Result<()> {
    let mut statement = db
        .connection()
        .prepare(
            "SELECT device_id, accepted_sequence, accepted_digest, floor_sequence, floor_digest
               FROM sync_heads ORDER BY device_id",
        )
        .map_err(|error| map_sqlite(error, "sync heads could not be prepared"))?;
    let mut rows = statement
        .query([])
        .map_err(|error| map_sqlite(error, "sync heads could not be read"))?;
    let mut head_devices = BTreeSet::new();
    while let Some(row) = rows
        .next()
        .map_err(|error| map_sqlite(error, "a sync head could not be read"))?
    {
        let device: Vec<u8> = row
            .get(0)
            .map_err(|error| map_sqlite(error, "a head device could not be read"))?;
        let accepted_sequence: Option<i64> = row
            .get(1)
            .map_err(|error| map_sqlite(error, "an accepted sequence could not be read"))?;
        let accepted_digest: Option<Vec<u8>> = row
            .get(2)
            .map_err(|error| map_sqlite(error, "an accepted digest could not be read"))?;
        let floor_sequence: Option<i64> = row
            .get(3)
            .map_err(|error| map_sqlite(error, "a floor sequence could not be read"))?;
        let floor_digest: Option<Vec<u8>> = row
            .get(4)
            .map_err(|error| map_sqlite(error, "a floor digest could not be read"))?;
        let device_id = crate::row::id(&device, "a head device id is malformed")?;
        head_devices.insert(device_id);
        match (accepted_sequence, accepted_digest) {
            (Some(sequence), Some(digest)) => ensure!(
                log.head(&device_id)
                    == Some((
                        from_sqlite_integer(sequence, "an accepted sequence is negative")?,
                        commitment(&digest, "an accepted digest is malformed")?,
                    )),
                CatalogCorrupt,
                "an accepted head contradicts operation history"
            ),
            (None, None) => ensure!(
                log.head(&device_id).is_none(),
                CatalogCorrupt,
                "operation history has no accepted head projection"
            ),
            _ => bail!(CatalogCorrupt, "an accepted head pair is partial"),
        }
        match (floor_sequence, floor_digest) {
            (Some(sequence), Some(digest)) => log
                .restore_floor(
                    &device_id,
                    from_sqlite_integer(sequence, "a floor sequence is negative")?,
                    commitment(&digest, "a floor digest is malformed")?,
                    membership,
                )
                .map_err(corrupt_log)?,
            (None, None) => {}
            _ => bail!(CatalogCorrupt, "a checkpoint floor pair is partial"),
        }
    }
    let mut operation_devices = db
        .connection()
        .prepare("SELECT DISTINCT device_id FROM sync_operations")
        .map_err(|error| map_sqlite(error, "operation devices could not be prepared"))?;
    let devices = operation_devices
        .query_map([], |row| row.get::<_, Vec<u8>>(0))
        .map_err(|error| map_sqlite(error, "operation devices could not be read"))?;
    for device in devices {
        let device =
            device.map_err(|error| map_sqlite(error, "an operation device could not be read"))?;
        ensure!(
            head_devices.contains(&crate::row::id(
                &device,
                "an operation device id is malformed"
            )?),
            CatalogCorrupt,
            "operation history has no accepted head row"
        );
    }
    Ok(())
}

fn validate_forks(db: &CatalogDb, membership: &MembershipState) -> Result<BTreeSet<Id>> {
    let mut statement = db
        .connection()
        .prepare(
            "SELECT device_id, state, accepted_record, conflicting_record
               FROM sync_forks ORDER BY device_id",
        )
        .map_err(|error| map_sqlite(error, "fork evidence could not be prepared"))?;
    let mut rows = statement
        .query([])
        .map_err(|error| map_sqlite(error, "fork evidence could not be read"))?;
    let mut forked = BTreeSet::new();
    while let Some(row) = rows
        .next()
        .map_err(|error| map_sqlite(error, "fork evidence could not be read"))?
    {
        let device: Vec<u8> = row
            .get(0)
            .map_err(|error| map_sqlite(error, "a fork device could not be read"))?;
        let state: i64 = row
            .get(1)
            .map_err(|error| map_sqlite(error, "a fork state could not be read"))?;
        let accepted: Vec<u8> = row
            .get(2)
            .map_err(|error| map_sqlite(error, "accepted fork evidence could not be read"))?;
        let conflicting: Vec<u8> = row
            .get(3)
            .map_err(|error| map_sqlite(error, "conflicting fork evidence could not be read"))?;
        let device_id = crate::row::id(&device, "a fork device id is malformed")?;
        ensure!(
            matches!(state, 1 | 2) && membership.device(&device_id).is_some(),
            CatalogCorrupt,
            "fork evidence has an invalid state or device"
        );
        if !accepted.is_empty() {
            verify_operation_evidence(&accepted, &device_id, membership)?;
        }
        if verify_operation_evidence(&conflicting, &device_id, membership).is_err() {
            verify_checkpoint_evidence(&conflicting, &device_id, membership)?;
        }
        forked.insert(device_id);
    }
    Ok(forked)
}

fn verify_operation_evidence(
    bytes: &[u8],
    device_id: &Id,
    membership: &MembershipState,
) -> Result<()> {
    let operation = Operation::decode(bytes).map_err(corrupt_log)?;
    ensure!(
        operation.device_id() == device_id && operation.vault_id() == membership.vault_id(),
        CatalogCorrupt,
        "fork operation evidence names another chain"
    );
    let device = membership.device(device_id).ok_or_else(|| {
        Error::new(
            ChurStatus::CatalogCorrupt,
            "fork operation device is unknown",
        )
    })?;
    ensure!(
        device
            .signing_public_keys()
            .any(|key| operation.verify_signature(key).is_ok()),
        CatalogCorrupt,
        "fork operation evidence has no valid signature"
    );
    Ok(())
}

fn verify_checkpoint_evidence(
    bytes: &[u8],
    device_id: &Id,
    membership: &MembershipState,
) -> Result<()> {
    let checkpoint = Checkpoint::decode(bytes).map_err(corrupt_log)?;
    ensure!(
        checkpoint.vault_id() == membership.vault_id()
            && checkpoint
                .heads()
                .iter()
                .any(|head| head.device_id() == device_id),
        CatalogCorrupt,
        "fork checkpoint evidence names another chain"
    );
    let issuer = membership
        .device(checkpoint.issuer_device_id())
        .ok_or_else(|| Error::new(ChurStatus::CatalogCorrupt, "checkpoint issuer is unknown"))?;
    ensure!(
        issuer
            .signing_public_keys()
            .any(|key| checkpoint.verify_signature(key).is_ok()),
        CatalogCorrupt,
        "fork checkpoint evidence has no valid signature"
    );
    Ok(())
}

fn commitment(bytes: &[u8], context: &'static str) -> Result<Commitment> {
    bytes
        .try_into()
        .map_err(|_| Error::new(ChurStatus::CatalogCorrupt, context))
}

fn corrupt_log(_: Error) -> Error {
    Error::new(
        ChurStatus::CatalogCorrupt,
        "stored operation history does not authenticate",
    )
}

#[cfg(test)]
mod tests {
    #![allow(clippy::expect_used, clippy::panic)]

    use super::*;
    use crate::{
        db::{CatalogKey, CatalogLocation},
        schema::open_at_current_version,
        sync_membership,
    };
    use chur_crypto::{Key, Nonce, random};
    use chur_sync_protocol::{
        membership::EnrollmentRecord,
        operation::{DeviceSigningKey, ObservedHead},
    };

    fn id(byte: u8) -> Id {
        Id::new([byte; 16]).expect("id")
    }

    fn setup() -> (CatalogDb, MembershipState, DeviceSigningKey) {
        let root: Key = random::secret::<32>().expect("root");
        let catalog_key = CatalogKey::derive(&root, &id(1)).expect("catalog key");
        let mut db = CatalogDb::open(&CatalogLocation::Memory, &catalog_key).expect("catalog");
        open_at_current_version(&mut db, 1).expect("schema");
        let key = DeviceSigningKey::from_seed([2; 32]);
        let enrollment = EnrollmentRecord::initial(id(1), id(3), key.verifying_key(), [4; 32])
            .expect("enrollment")
            .sign(&key);
        let membership = sync_membership::provision(&mut db, &enrollment).expect("membership");
        (db, membership, key)
    }

    fn operation(
        key: &DeviceSigningKey,
        sequence: u64,
        previous: Commitment,
        marker: u8,
    ) -> Operation {
        Operation::seal(
            id(marker),
            id(1),
            id(3),
            sequence,
            previous,
            Vec::<ObservedHead>::new(),
            id(9),
            &Key::new([8; 32]),
            Nonce::new([marker; 24]),
            &[marker],
        )
        .expect("operation")
        .sign(key)
    }

    #[test]
    fn accepted_operations_and_heads_replay_after_reopen() {
        let (mut db, membership, key) = setup();
        let mut log = load(&db, &membership).expect("empty log");
        let first = operation(&key, 1, [0; 32], 5);
        let second = operation(&key, 2, first.digest(), 6);
        assert_eq!(
            log.accept_with(&mut db, &first, &membership, |_| Ok(()))
                .expect("first"),
            ApplyOutcome::Applied
        );
        assert_eq!(
            log.accept_with(&mut db, &second, &membership, |_| Ok(()))
                .expect("second"),
            ApplyOutcome::Applied
        );
        let restored = load(&db, &membership).expect("restore");
        assert_eq!(restored.head(&id(3)), Some((2, second.digest())));
    }

    #[test]
    fn a_failed_projection_rolls_back_the_operation_and_in_memory_candidate() {
        let (mut db, membership, key) = setup();
        let mut log = load(&db, &membership).expect("empty log");
        let first = operation(&key, 1, [0; 32], 5);
        let outcome = log.accept_with(&mut db, &first, &membership, |_| {
            Err(Error::new(ChurStatus::Conflict, "projection failed"))
        });
        assert!(outcome.is_err());
        assert!(log.head(&id(3)).is_none());
        assert!(
            load(&db, &membership)
                .expect("restore")
                .head(&id(3))
                .is_none()
        );
    }

    #[test]
    fn fork_evidence_survives_reopen_and_freezes_only_its_device() {
        let (mut db, membership, key) = setup();
        let mut log = load(&db, &membership).expect("empty log");
        let first = operation(&key, 1, [0; 32], 5);
        log.accept_with(&mut db, &first, &membership, |_| Ok(()))
            .expect("first");
        let conflict = operation(&key, 1, [0; 32], 6);
        let Err(error) = log.accept_with(&mut db, &conflict, &membership, |_| Ok(())) else {
            panic!("fork was accepted");
        };
        assert_eq!(error.status(), ChurStatus::SyncChainFork);

        let mut restored = load(&db, &membership).expect("restore fork");
        assert_eq!(
            restored
                .accept_with(&mut db, &first, &membership, |_| Ok(()))
                .expect_err("frozen")
                .status(),
            ChurStatus::SyncChainFork
        );
        restored
            .acknowledge_fork(&mut db, &id(3))
            .expect("acknowledge");
        let state: i64 = db
            .connection()
            .query_row("SELECT state FROM sync_forks", [], |row| row.get(0))
            .expect("fork state");
        assert_eq!(state, 2);
    }
}
