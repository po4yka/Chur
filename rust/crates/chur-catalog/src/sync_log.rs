//! Durable accepted operation logs and fork evidence in catalog v2.

use std::collections::{BTreeMap, BTreeSet};

use chur_core::limits::{catalog as catalog_bounds, sync as bounds};
use chur_core::{ChurStatus, Error, Id, Result, bail, ensure};
use chur_crypto::{Commitment, Key, Nonce};
use chur_sync_protocol::{
    checkpoint::{
        Checkpoint, CheckpointHead, UNCOMPACTED_CATALOG_STATE_COMMITMENT,
        collection_epoch_commitment,
    },
    convergence::CausalStamp,
    operation::{DeviceSigningKey, Operation},
    operation_log::{ApplyOutcome, CheckpointOutcome, ForkEvidence, ForkState, OperationLog},
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

    /// Latest accepted checkpoint commitment from one issuer.
    #[must_use]
    pub fn checkpoint_commitment(&self, issuer_device_id: &Id) -> Option<&Commitment> {
        self.log.checkpoint_commitment(issuer_device_id)
    }

    pub(crate) fn latest_operations(&self) -> Result<BTreeMap<Id, CausalStamp>> {
        self.log.latest_operations().map_err(corrupt_log)
    }

    /// Whether the latest own checkpoint covers every current accepted head.
    pub fn own_checkpoint_covers_current_heads(&self, db: &CatalogDb) -> Result<bool> {
        let row: Option<(Vec<u8>, Vec<u8>)> = db
            .connection()
            .query_row(
                "SELECT checkpoint.commitment, checkpoint.record
                   FROM sync_state AS state
                   JOIN sync_checkpoints AS checkpoint
                     ON checkpoint.commitment = state.latest_own_checkpoint_commitment
                  WHERE state.only_row = 1 AND checkpoint.own = 1",
                [],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .optional()
            .map_err(|error| map_sqlite(error, "the own checkpoint could not be read"))?;
        let Some((stored_commitment, record)) = row else {
            return Ok(false);
        };
        let checkpoint = Checkpoint::decode(&record).map_err(corrupt_log)?;
        ensure!(
            checkpoint.commitment()
                == commitment(
                    &stored_commitment,
                    "the own checkpoint commitment is malformed"
                )?,
            CatalogCorrupt,
            "the own checkpoint contradicts its catalog row"
        );
        let latest = self.latest_operations()?;
        Ok(checkpoint.heads().len() == latest.len()
            && checkpoint.heads().iter().all(|head| {
                latest.get(head.device_id()).is_some_and(|operation| {
                    operation.device_sequence() == head.device_sequence()
                        && operation.digest() == head.operation_digest()
                })
            }))
    }

    /// Signs and persists a checkpoint over the exact current heads and epochs.
    pub fn issue_own_checkpoint(
        &mut self,
        db: &mut CatalogDb,
        membership: &MembershipState,
        issuer_device_id: &Id,
        signing_key: &DeviceSigningKey,
        now_ms: u64,
    ) -> Result<Checkpoint> {
        let device = membership.device(issuer_device_id).ok_or_else(|| {
            Error::new(
                ChurStatus::AuthenticationFailed,
                "checkpoint issuer is not enrolled",
            )
        })?;
        ensure!(
            device.status() == DeviceStatus::Active
                && device.signing_public_key() == &signing_key.verifying_key(),
            AuthenticationFailed,
            "checkpoint key is not the current active device key"
        );
        let latest = self.latest_operations()?;
        let issuer = latest.get(issuer_device_id).ok_or_else(|| {
            Error::new(
                ChurStatus::InvalidInput,
                "checkpoint issuer has no accepted operation",
            )
        })?;
        let heads = latest
            .iter()
            .map(|(device_id, operation)| {
                CheckpointHead::new(*device_id, operation.device_sequence(), *operation.digest())
            })
            .collect();
        let checkpoint = Checkpoint::new(
            *membership.vault_id(),
            *issuer_device_id,
            issuer.device_sequence(),
            membership.generation(),
            *membership.commitment(),
            heads,
            current_collection_epoch_commitment(db)?,
            UNCOMPACTED_CATALOG_STATE_COMMITMENT,
        )?
        .sign(signing_key);
        self.accept_checkpoint(db, &checkpoint, membership, true, now_ms)?;
        Ok(checkpoint)
    }

    /// Builds the next signed operation without changing durable state.
    #[expect(
        clippy::too_many_arguments,
        reason = "the inputs are fresh wire values, encryption material, and authenticated state"
    )]
    pub fn author(
        &self,
        operation_id: Id,
        vault_id: Id,
        device_id: Id,
        key_selector: Id,
        key: &Key,
        nonce: Nonce,
        plaintext: &[u8],
        signing_key: &DeviceSigningKey,
        membership: &MembershipState,
    ) -> Result<Operation> {
        self.log.author(
            operation_id,
            vault_id,
            device_id,
            key_selector,
            key,
            nonce,
            plaintext,
            signing_key,
            membership,
        )
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
        self.accept_gated_with(db, operation, membership, || Ok(true), apply)
    }

    pub(crate) fn accept_gated_with(
        &mut self,
        db: &mut CatalogDb,
        operation: &Operation,
        membership: &MembershipState,
        gate: impl FnOnce() -> Result<bool>,
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
                if !gate()? {
                    return Ok(ApplyOutcome::PendingCause);
                }
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
                persist_fork(db, operation.device_id(), evidence, None)?;
                self.log = candidate;
                self.forked_devices.insert(*operation.device_id());
                Err(error)
            }
            Err(error) => Err(error),
        }
    }

    /// Accepts and persists one signed checkpoint and every raised floor.
    pub fn accept_checkpoint(
        &mut self,
        db: &mut CatalogDb,
        checkpoint: &Checkpoint,
        membership: &MembershipState,
        own: bool,
        now_ms: u64,
    ) -> Result<CheckpointOutcome> {
        let mut candidate = self.log.clone();
        match candidate.accept_checkpoint(checkpoint, membership) {
            Ok(outcome) => {
                let commitment = checkpoint.commitment();
                let existing_own: Option<Vec<u8>> = db
                    .connection()
                    .query_row(
                        "SELECT commitment FROM sync_checkpoints
                          WHERE issuer_device_id = ?1 AND own = 1",
                        [checkpoint.issuer_device_id().as_bytes().as_slice()],
                        |row| row.get(0),
                    )
                    .optional()
                    .map_err(|error| map_sqlite(error, "the own checkpoint could not be read"))?;
                ensure!(
                    own || existing_own
                        .as_deref()
                        .is_none_or(|stored| stored == commitment),
                    SyncHeadRollback,
                    "a remote checkpoint cannot replace the latest own checkpoint"
                );
                db.transaction(|transaction| {
                    transaction
                        .execute(
                            "INSERT INTO sync_checkpoints
                                 (issuer_device_id, commitment, record, accepted_at_ms, own)
                             VALUES (?1, ?2, ?3, ?4, ?5)
                             ON CONFLICT(issuer_device_id) DO UPDATE SET
                                 commitment = excluded.commitment,
                                 record = excluded.record,
                                 accepted_at_ms = excluded.accepted_at_ms,
                                 own = max(sync_checkpoints.own, excluded.own)",
                            params![
                                checkpoint.issuer_device_id().as_bytes().as_slice(),
                                commitment.as_slice(),
                                checkpoint.encode(),
                                as_sqlite_integer(
                                    now_ms,
                                    "the checkpoint acceptance time is too large"
                                )?,
                                i64::from(own),
                            ],
                        )
                        .map_err(|error| {
                            map_sqlite(error, "the checkpoint could not be written")
                        })?;
                    for head in checkpoint.heads() {
                        let Some((sequence, digest)) = candidate.floor(head.device_id()) else {
                            continue;
                        };
                        transaction
                            .execute(
                                "INSERT INTO sync_heads
                                     (device_id, accepted_sequence, accepted_digest,
                                      floor_sequence, floor_digest)
                                 VALUES (?1, NULL, NULL, ?2, ?3)
                                 ON CONFLICT(device_id) DO UPDATE SET
                                     floor_sequence = excluded.floor_sequence,
                                     floor_digest = excluded.floor_digest",
                                params![
                                    head.device_id().as_bytes().as_slice(),
                                    as_sqlite_integer(
                                        sequence,
                                        "the checkpoint floor sequence is too large"
                                    )?,
                                    digest.as_slice(),
                                ],
                            )
                            .map_err(|error| {
                                map_sqlite(error, "the checkpoint floor could not be written")
                            })?;
                    }
                    if own {
                        let changed = transaction
                            .execute(
                                "UPDATE sync_state
                                    SET latest_own_checkpoint_commitment = ?1
                                  WHERE only_row = 1",
                                [commitment.as_slice()],
                            )
                            .map_err(|error| {
                                map_sqlite(error, "the own checkpoint could not be projected")
                            })?;
                        ensure!(
                            changed == 1,
                            CatalogCorrupt,
                            "sync state has no membership chain"
                        );
                    }
                    bump_generation(transaction)
                })?;
                self.log = candidate;
                Ok(outcome)
            }
            Err(error) if error.status() == ChurStatus::SyncChainFork => {
                let device_id = checkpoint
                    .heads()
                    .iter()
                    .map(|head| head.device_id())
                    .find(|device_id| candidate.fork(device_id).is_some())
                    .ok_or_else(|| {
                        Error::new(
                            ChurStatus::InternalFailure,
                            "the protocol reported a checkpoint fork without evidence",
                        )
                    })?;
                let evidence = candidate.fork(device_id).ok_or_else(|| {
                    Error::new(
                        ChurStatus::InternalFailure,
                        "the protocol reported a checkpoint fork without evidence",
                    )
                })?;
                let fallback = if evidence.accepted_record().is_empty() {
                    let floor = self.log.floor(device_id).ok_or_else(|| {
                        Error::new(
                            ChurStatus::InternalFailure,
                            "checkpoint fork has no accepted floor",
                        )
                    })?;
                    Some(checkpoint_for_floor(db, device_id, floor)?)
                } else {
                    None
                };
                persist_fork(db, device_id, evidence, fallback.as_deref())?;
                self.log = candidate;
                self.forked_devices.insert(*device_id);
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
    restore_checkpoints(db, membership, &mut log)?;
    let forked_devices = validate_forks(db, membership)?;
    Ok(DurableOperationLog {
        log,
        forked_devices,
    })
}

/// Returns one bounded canonical page from a durable device chain.
pub fn records_after(db: &CatalogDb, device_id: &Id, after_sequence: u64) -> Result<Vec<Vec<u8>>> {
    let mut statement = db
        .connection()
        .prepare(
            "SELECT device_sequence, digest, record FROM sync_operations
              WHERE device_id = ?1 AND device_sequence > ?2
              ORDER BY device_sequence LIMIT ?3",
        )
        .map_err(|error| map_sqlite(error, "outbound operations could not be prepared"))?;
    let rows = statement
        .query_map(
            params![
                device_id.as_bytes().as_slice(),
                as_sqlite_integer(after_sequence, "the operation cursor is too large")?,
                as_sqlite_integer(
                    bounds::RESPONSE_OPERATIONS_MAX as u64,
                    "the operation page limit is too large"
                )?,
            ],
            |row| {
                Ok((
                    row.get::<_, i64>(0)?,
                    row.get::<_, Vec<u8>>(1)?,
                    row.get::<_, Vec<u8>>(2)?,
                ))
            },
        )
        .map_err(|error| map_sqlite(error, "outbound operations could not be read"))?;
    let mut records = Vec::new();
    let mut total = 0usize;
    for row in rows {
        let (sequence, stored_digest, record) =
            row.map_err(|error| map_sqlite(error, "an outbound operation could not be read"))?;
        let sequence = from_sqlite_integer(sequence, "an outbound operation sequence is negative")?;
        let digest = commitment(&stored_digest, "an outbound operation digest is malformed")?;
        let operation = Operation::decode(&record).map_err(corrupt_log)?;
        ensure!(
            operation.device_id() == device_id
                && operation.device_sequence() == sequence
                && operation.digest() == digest,
            CatalogCorrupt,
            "an outbound operation contradicts its catalog row"
        );
        let next = total.checked_add(record.len()).ok_or_else(|| {
            Error::new(
                ChurStatus::CatalogCorrupt,
                "the outbound operation page length overflowed",
            )
        })?;
        if next > bounds::RESPONSE_BYTES_MAX {
            break;
        }
        total = next;
        records.push(record);
    }
    Ok(records)
}

fn current_collection_epoch_commitment(db: &CatalogDb) -> Result<Commitment> {
    let limit = as_sqlite_integer(
        catalog_bounds::COLLECTIONS_MAX + 1,
        "the collection query limit is too large",
    )?;
    let mut statement = db
        .connection()
        .prepare(
            "SELECT collection_id, current_epoch FROM collections
              ORDER BY collection_id LIMIT ?1",
        )
        .map_err(|error| map_sqlite(error, "collection epochs could not be prepared"))?;
    let rows = statement
        .query_map([limit], |row| {
            Ok((row.get::<_, Vec<u8>>(0)?, row.get::<_, i64>(1)?))
        })
        .map_err(|error| map_sqlite(error, "collection epochs could not be read"))?;
    let mut epochs = Vec::new();
    for row in rows {
        let (collection_id, epoch) =
            row.map_err(|error| map_sqlite(error, "a collection epoch could not be read"))?;
        epochs.push((
            crate::row::id(&collection_id, "a collection id is malformed")?,
            from_sqlite_integer(epoch, "a collection epoch is negative")?,
        ));
    }
    ensure!(
        u64::try_from(epochs.len()).is_ok_and(|count| count <= catalog_bounds::COLLECTIONS_MAX),
        CatalogCorrupt,
        "the catalog exceeds the collection limit"
    );
    collection_epoch_commitment(&epochs).map_err(|_| {
        Error::new(
            ChurStatus::CatalogCorrupt,
            "the current collection epochs are malformed",
        )
    })
}

fn persist_fork(
    db: &mut CatalogDb,
    device_id: &Id,
    evidence: &ForkEvidence,
    accepted_fallback: Option<&[u8]>,
) -> Result<()> {
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
                    device_id.as_bytes().as_slice(),
                    state,
                    if evidence.accepted_record().is_empty() {
                        accepted_fallback.ok_or_else(|| {
                            Error::new(
                                ChurStatus::InternalFailure,
                                "fork evidence has no accepted signed record",
                            )
                        })?
                    } else {
                        evidence.accepted_record()
                    },
                    evidence.conflicting_record(),
                ],
            )
            .map_err(|error| map_sqlite(error, "fork evidence could not be written"))?;
        bump_generation(transaction)
    })
}

fn checkpoint_for_floor(
    db: &CatalogDb,
    device_id: &Id,
    floor: (u64, Commitment),
) -> Result<Vec<u8>> {
    let mut statement = db
        .connection()
        .prepare("SELECT record FROM sync_checkpoints ORDER BY issuer_device_id")
        .map_err(|error| map_sqlite(error, "checkpoint evidence could not be prepared"))?;
    let rows = statement
        .query_map([], |row| row.get::<_, Vec<u8>>(0))
        .map_err(|error| map_sqlite(error, "checkpoint evidence could not be read"))?;
    for row in rows {
        let record =
            row.map_err(|error| map_sqlite(error, "checkpoint evidence could not be read"))?;
        let checkpoint = Checkpoint::decode(&record).map_err(corrupt_log)?;
        if checkpoint.heads().iter().any(|head| {
            head.device_id() == device_id
                && head.device_sequence() == floor.0
                && head.operation_digest() == &floor.1
        }) {
            return Ok(record);
        }
    }
    bail!(CatalogCorrupt, "a checkpoint floor has no signed evidence")
}

pub(crate) fn device_ids(db: &CatalogDb) -> Result<Vec<Id>> {
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

pub(crate) fn operation_at(
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

fn restore_checkpoints(
    db: &CatalogDb,
    membership: &MembershipState,
    log: &mut OperationLog,
) -> Result<()> {
    let mut statement = db
        .connection()
        .prepare(
            "SELECT issuer_device_id, commitment, record, own
               FROM sync_checkpoints ORDER BY issuer_device_id",
        )
        .map_err(|error| map_sqlite(error, "checkpoints could not be prepared"))?;
    let mut rows = statement
        .query([])
        .map_err(|error| map_sqlite(error, "checkpoints could not be read"))?;
    let mut own_commitments = BTreeSet::new();
    while let Some(row) = rows
        .next()
        .map_err(|error| map_sqlite(error, "a checkpoint could not be read"))?
    {
        let issuer: Vec<u8> = row
            .get(0)
            .map_err(|error| map_sqlite(error, "a checkpoint issuer could not be read"))?;
        let stored_commitment: Vec<u8> = row
            .get(1)
            .map_err(|error| map_sqlite(error, "a checkpoint commitment could not be read"))?;
        let record: Vec<u8> = row
            .get(2)
            .map_err(|error| map_sqlite(error, "a checkpoint record could not be read"))?;
        let own: i64 = row
            .get(3)
            .map_err(|error| map_sqlite(error, "a checkpoint origin could not be read"))?;
        let issuer = crate::row::id(&issuer, "a checkpoint issuer is malformed")?;
        let checkpoint = Checkpoint::decode(&record).map_err(corrupt_log)?;
        let stored_commitment =
            commitment(&stored_commitment, "a checkpoint commitment is malformed")?;
        ensure!(
            checkpoint.issuer_device_id() == &issuer
                && checkpoint.commitment() == stored_commitment,
            CatalogCorrupt,
            "a checkpoint contradicts its catalog row"
        );
        verify_checkpoint_evidence(&record, &issuer, membership)?;
        let membership_commitment: Option<Vec<u8>> = db
            .connection()
            .query_row(
                "SELECT commitment FROM sync_membership_records
                  WHERE membership_generation = ?1",
                [as_sqlite_integer(
                    checkpoint.membership_generation(),
                    "the checkpoint membership generation is too large",
                )?],
                |row| row.get(0),
            )
            .optional()
            .map_err(|error| map_sqlite(error, "checkpoint membership could not be read"))?;
        ensure!(
            membership_commitment.as_deref() == Some(checkpoint.membership_commitment()),
            CatalogCorrupt,
            "a checkpoint names an unaccepted membership generation"
        );
        log.restore_checkpoint_commitment(&issuer, stored_commitment, membership)
            .map_err(corrupt_log)?;
        if own == 1 {
            own_commitments.insert(stored_commitment);
        }
    }
    let latest_own: Option<Vec<u8>> = db
        .connection()
        .query_row(
            "SELECT latest_own_checkpoint_commitment FROM sync_state WHERE only_row = 1",
            [],
            |row| row.get(0),
        )
        .optional()
        .map_err(|error| map_sqlite(error, "the own checkpoint head could not be read"))?
        .flatten();
    match latest_own {
        Some(bytes) => ensure!(
            own_commitments.contains(&commitment(&bytes, "the own checkpoint head is malformed")?),
            CatalogCorrupt,
            "the own checkpoint head has no checkpoint record"
        ),
        None => ensure!(
            own_commitments.is_empty(),
            CatalogCorrupt,
            "an own checkpoint has no head projection"
        ),
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
        verify_signed_evidence(&accepted, &device_id, membership)?;
        verify_signed_evidence(&conflicting, &device_id, membership)?;
        forked.insert(device_id);
    }
    Ok(forked)
}

fn verify_signed_evidence(
    bytes: &[u8],
    device_id: &Id,
    membership: &MembershipState,
) -> Result<()> {
    if verify_operation_evidence(bytes, device_id, membership).is_ok() {
        return Ok(());
    }
    verify_checkpoint_evidence(bytes, device_id, membership)
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
        model::{COLLECTION_POLICY_VAULT_DEFAULT, COLLECTION_STATUS_ACTIVE, Collection},
        schema::open_at_current_version,
        store, sync_membership,
    };
    use chur_crypto::{Key, Nonce, random};
    use chur_sync_protocol::{
        checkpoint::{Checkpoint, CheckpointHead},
        membership::EnrollmentRecord,
        operation::{DeviceSigningKey, ObservedHead},
        operation_log::CheckpointOutcome,
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

    fn checkpoint(
        key: &DeviceSigningKey,
        sequence: u64,
        digest: Commitment,
        membership: &MembershipState,
    ) -> Checkpoint {
        Checkpoint::new(
            id(1),
            id(3),
            sequence,
            membership.generation(),
            *membership.commitment(),
            vec![CheckpointHead::new(id(3), sequence, digest)],
            [6; 32],
            [0; 32],
        )
        .expect("checkpoint")
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
        assert_eq!(
            records_after(&db, &id(3), 0).expect("full outbound page"),
            vec![first.encode(), second.encode()]
        );
        assert_eq!(
            records_after(&db, &id(3), 1).expect("tail outbound page"),
            vec![second.encode()]
        );
        assert!(
            records_after(&db, &id(3), 2)
                .expect("empty outbound page")
                .is_empty()
        );
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

    #[test]
    fn checkpoint_floor_and_own_commitment_replay_after_reopen() {
        let (mut db, membership, key) = setup();
        let mut log = load(&db, &membership).expect("empty log");
        let checkpoint = checkpoint(&key, 1, [8; 32], &membership);
        assert_eq!(
            log.accept_checkpoint(&mut db, &checkpoint, &membership, true, 9)
                .expect("accept checkpoint"),
            CheckpointOutcome::Raised
        );
        assert_eq!(log.floor(&id(3)), Some((1, [8; 32])));
        let stored: Vec<u8> = db
            .connection()
            .query_row(
                "SELECT latest_own_checkpoint_commitment FROM sync_state",
                [],
                |row| row.get(0),
            )
            .expect("own commitment");
        assert_eq!(stored, checkpoint.commitment());

        let replay = Checkpoint::new(
            id(1),
            id(3),
            1,
            membership.generation(),
            *membership.commitment(),
            vec![CheckpointHead::new(id(3), 1, [8; 32])],
            [9; 32],
            [0; 32],
        )
        .expect("replayed checkpoint")
        .sign(&key);
        assert_eq!(
            log.accept_checkpoint(&mut db, &replay, &membership, false, 10)
                .expect_err("own rollback")
                .status(),
            ChurStatus::SyncHeadRollback
        );

        let restored = load(&db, &membership).expect("restore checkpoint");
        assert_eq!(restored.floor(&id(3)), Some((1, [8; 32])));
        assert_eq!(
            restored.checkpoint_commitment(&id(3)),
            Some(&checkpoint.commitment())
        );
    }

    #[test]
    fn own_checkpoint_covers_only_the_exact_current_heads() {
        let (mut db, membership, key) = setup();
        let mut log = load(&db, &membership).expect("empty log");
        let first = operation(&key, 1, [0; 32], 5);
        log.accept_with(&mut db, &first, &membership, |_| Ok(()))
            .expect("first");
        assert!(
            !log.own_checkpoint_covers_current_heads(&db)
                .expect("no checkpoint")
        );
        let current = checkpoint(&key, 1, first.digest(), &membership);
        log.accept_checkpoint(&mut db, &current, &membership, true, 9)
            .expect("current checkpoint");
        assert!(
            log.own_checkpoint_covers_current_heads(&db)
                .expect("covered")
        );

        let second = operation(&key, 2, first.digest(), 6);
        log.accept_with(&mut db, &second, &membership, |_| Ok(()))
            .expect("second");
        assert!(
            !log.own_checkpoint_covers_current_heads(&db)
                .expect("stale checkpoint")
        );
    }

    #[test]
    fn own_checkpoint_is_authored_from_durable_heads_and_collection_epochs() {
        let (mut db, membership, key) = setup();
        for (marker, epoch) in [(7, 3), (6, 2)] {
            store::put_collection(
                &mut db,
                &Collection {
                    collection_id: id(marker),
                    current_epoch: epoch,
                    policy_type: COLLECTION_POLICY_VAULT_DEFAULT,
                    created_revision: 1,
                    status: COLLECTION_STATUS_ACTIVE,
                },
            )
            .expect("collection");
        }
        let mut log = load(&db, &membership).expect("empty log");
        let first = operation(&key, 1, [0; 32], 5);
        log.accept_with(&mut db, &first, &membership, |_| Ok(()))
            .expect("first");
        let Err(error) = log.issue_own_checkpoint(
            &mut db,
            &membership,
            &id(3),
            &DeviceSigningKey::from_seed([9; 32]),
            9,
        ) else {
            panic!("wrong checkpoint key was accepted");
        };
        assert_eq!(error.status(), ChurStatus::AuthenticationFailed);

        let checkpoint = log
            .issue_own_checkpoint(&mut db, &membership, &id(3), &key, 10)
            .expect("checkpoint");
        assert_eq!(
            checkpoint.collection_epoch_commitment(),
            &collection_epoch_commitment(&[(id(6), 2), (id(7), 3)]).expect("epochs")
        );
        assert_eq!(
            checkpoint.catalog_state_commitment(),
            &UNCOMPACTED_CATALOG_STATE_COMMITMENT
        );
        assert_eq!(checkpoint.heads().len(), 1);
        assert_eq!(checkpoint.heads()[0].operation_digest(), &first.digest());
        assert!(
            log.own_checkpoint_covers_current_heads(&db)
                .expect("coverage")
        );
    }

    #[test]
    fn a_conflicting_checkpoint_persists_fork_evidence() {
        let (mut db, membership, key) = setup();
        let mut log = load(&db, &membership).expect("empty log");
        let first = operation(&key, 1, [0; 32], 5);
        log.accept_with(&mut db, &first, &membership, |_| Ok(()))
            .expect("first");
        let conflict = checkpoint(&key, 1, [9; 32], &membership);
        assert_eq!(
            log.accept_checkpoint(&mut db, &conflict, &membership, false, 9)
                .expect_err("fork")
                .status(),
            ChurStatus::SyncChainFork
        );
        let restored = load(&db, &membership).expect("restore fork");
        assert!(restored.forked_devices.contains(&id(3)));
    }

    #[test]
    fn conflicting_checkpoint_floor_retains_both_signed_checkpoints() {
        let (mut db, membership, key) = setup();
        let mut log = load(&db, &membership).expect("empty log");
        let accepted = checkpoint(&key, 1, [8; 32], &membership);
        log.accept_checkpoint(&mut db, &accepted, &membership, false, 8)
            .expect("accepted checkpoint");
        let conflict = checkpoint(&key, 1, [9; 32], &membership);
        assert_eq!(
            log.accept_checkpoint(&mut db, &conflict, &membership, false, 9)
                .expect_err("fork")
                .status(),
            ChurStatus::SyncChainFork
        );
        let (accepted_record, conflicting_record): (Vec<u8>, Vec<u8>) = db
            .connection()
            .query_row(
                "SELECT accepted_record, conflicting_record FROM sync_forks",
                [],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .expect("fork evidence");
        assert_eq!(accepted_record, accepted.encode());
        assert_eq!(conflicting_record, conflict.encode());
        assert!(load(&db, &membership).is_ok());
    }
}
