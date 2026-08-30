//! Durable collection-operation streams in catalog v4.

use std::collections::BTreeMap;

use chur_core::{ChurStatus, Error, Id, Result, ensure};
use chur_crypto::Commitment;
use chur_sync_protocol::{
    KeyDirectory,
    collection_membership::CollectionMembershipState,
    collection_operation::CollectionOperation,
    collection_operation_log::CollectionOperationLog,
    operation_log::{ApplyOutcome, ForkState},
    payload::OperationPayload,
    state::MembershipState,
};
use rusqlite::{OptionalExtension, Transaction, params};

use crate::{
    db::{CatalogDb, as_sqlite_integer, from_sqlite_integer, map_sqlite},
    schema::bump_generation,
};

type StoredOperation = (Vec<u8>, Vec<u8>, i64, Vec<u8>, Vec<u8>, Vec<u8>);

/// One collection operation log rebuilt from authenticated durable rows.
pub struct DurableCollectionOperationLog {
    log: CollectionOperationLog,
}

impl DurableCollectionOperationLog {
    /// Starts and stores one empty collection epoch stream.
    pub fn provision(
        db: &mut CatalogDb,
        collection_id: Id,
        collection_epoch: u64,
        key_selector: Id,
    ) -> Result<Self> {
        db.transaction(|transaction| {
            ensure_stream(transaction, &collection_id, collection_epoch, &key_selector)?;
            bump_generation(transaction)
        })?;
        Ok(Self {
            log: CollectionOperationLog::new(collection_id, collection_epoch, key_selector),
        })
    }

    /// Validates and stores one operation without advancing memory before SQL commits.
    pub fn accept(
        &mut self,
        db: &mut CatalogDb,
        operation: &CollectionOperation,
        payload: &OperationPayload,
        issuer_membership: &MembershipState,
        source_membership: &MembershipState,
        collection_membership: &CollectionMembershipState,
    ) -> Result<ApplyOutcome> {
        let mut candidate = self.log.clone();
        let accepted = candidate.accept(
            operation,
            payload,
            issuer_membership,
            source_membership,
            collection_membership,
        );
        match accepted {
            Ok(ApplyOutcome::Applied) => {
                db.transaction(|transaction| {
                    ensure_stream(
                        transaction,
                        self.log.collection_id(),
                        self.log.collection_epoch(),
                        self.log.key_selector(),
                    )?;
                    insert_operation(transaction, operation)?;
                    bump_generation(transaction)
                })?;
                self.log = candidate;
                Ok(ApplyOutcome::Applied)
            }
            Ok(ApplyOutcome::Duplicate) => {
                check_stored_operation(db, operation)?;
                Ok(ApplyOutcome::Duplicate)
            }
            Ok(outcome @ (ApplyOutcome::PendingGap | ApplyOutcome::PendingCause)) => Ok(outcome),
            Err(error) if error.status() == ChurStatus::SyncChainFork => {
                let evidence = candidate
                    .fork(
                        operation.issuer_identity_vault_id(),
                        operation.issuer_device_id(),
                    )
                    .ok_or_else(|| {
                        Error::new(
                            ChurStatus::InternalFailure,
                            "collection fork has no evidence",
                        )
                    })?;
                db.transaction(|transaction| {
                    transaction
                        .execute(
                            "INSERT INTO sharing_operation_forks
                                 (key_selector, issuer_identity_vault_id, issuer_device_id,
                                  state, accepted_record, conflicting_record)
                             VALUES (?1, ?2, ?3, 1, ?4, ?5)
                             ON CONFLICT DO UPDATE SET
                                 state = 1, accepted_record = excluded.accepted_record,
                                 conflicting_record = excluded.conflicting_record",
                            params![
                                self.log.key_selector().as_bytes().as_slice(),
                                operation.issuer_identity_vault_id().as_bytes().as_slice(),
                                operation.issuer_device_id().as_bytes().as_slice(),
                                evidence.accepted_record(),
                                evidence.conflicting_record(),
                            ],
                        )
                        .map_err(|sqlite| {
                            map_sqlite(sqlite, "collection fork evidence could not be stored")
                        })?;
                    bump_generation(transaction)
                })?;
                self.log = candidate;
                Err(error)
            }
            Err(error) => Err(error),
        }
    }

    /// Latest accepted head for one participant.
    #[must_use]
    pub fn head(&self, identity_vault_id: &Id, device_id: &Id) -> Option<(u64, Commitment)> {
        self.log.head(identity_vault_id, device_id)
    }
}

/// Restores one current collection epoch from authenticated catalog rows.
pub fn load(
    db: &CatalogDb,
    collection_id: Id,
    collection_epoch: u64,
    key_selector: Id,
    keys: &KeyDirectory,
    memberships: &BTreeMap<Id, MembershipState>,
    collection_membership: &CollectionMembershipState,
) -> Result<DurableCollectionOperationLog> {
    let stored_stream: Option<(Vec<u8>, i64)> = db
        .connection()
        .query_row(
            "SELECT collection_id, collection_epoch
               FROM sharing_operation_streams WHERE key_selector = ?1",
            [key_selector.as_bytes().as_slice()],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .optional()
        .map_err(|error| map_sqlite(error, "collection operation stream could not be read"))?;
    let Some((stored_collection, stored_epoch)) = stored_stream else {
        return Ok(DurableCollectionOperationLog {
            log: CollectionOperationLog::new(collection_id, collection_epoch, key_selector),
        });
    };
    ensure!(
        Id::from_slice(&stored_collection).map_err(corrupt)? == collection_id
            && from_sqlite_integer(stored_epoch, "collection operation epoch is malformed")?
                == collection_epoch,
        CatalogCorrupt,
        "collection operation stream projection disagrees"
    );
    let source_membership = memberships
        .get(collection_membership.source_vault_id())
        .ok_or_else(|| Error::new(ChurStatus::CatalogCorrupt, "source membership is absent"))?;
    let mut statement = db
        .connection()
        .prepare(
            "SELECT issuer_identity_vault_id, issuer_device_id, device_sequence,
                    operation_id, digest, record
               FROM sharing_operations WHERE key_selector = ?1
              ORDER BY issuer_identity_vault_id, issuer_device_id, device_sequence",
        )
        .map_err(|error| map_sqlite(error, "collection operations could not be prepared"))?;
    let rows = statement
        .query_map([key_selector.as_bytes().as_slice()], |row| {
            Ok((
                row.get(0)?,
                row.get(1)?,
                row.get(2)?,
                row.get(3)?,
                row.get(4)?,
                row.get(5)?,
            ))
        })
        .map_err(|error| map_sqlite(error, "collection operations could not be read"))?;
    let mut pending = Vec::new();
    for row in rows {
        let row: StoredOperation =
            row.map_err(|error| map_sqlite(error, "a collection operation row could not be read"))?;
        let operation = CollectionOperation::decode(&row.5).map_err(corrupt)?;
        ensure_operation_projection(&operation, &row)?;
        let payload =
            OperationPayload::open_for_collection_operation(&operation, keys).map_err(corrupt)?;
        pending.push((operation, payload));
    }
    let mut log = CollectionOperationLog::new(collection_id, collection_epoch, key_selector);
    while !pending.is_empty() {
        let mut progress = false;
        let mut next = Vec::new();
        for (operation, payload) in pending {
            let issuer = memberships
                .get(operation.issuer_identity_vault_id())
                .ok_or_else(|| {
                    Error::new(ChurStatus::CatalogCorrupt, "issuer membership is absent")
                })?;
            match log
                .restore_accepted(
                    &operation,
                    &payload,
                    issuer,
                    source_membership,
                    collection_membership,
                )
                .map_err(corrupt)?
            {
                ApplyOutcome::Applied | ApplyOutcome::Duplicate => progress = true,
                ApplyOutcome::PendingGap | ApplyOutcome::PendingCause => {
                    next.push((operation, payload));
                }
            }
        }
        ensure!(
            progress,
            CatalogCorrupt,
            "collection operation history has a gap or missing cause"
        );
        pending = next;
    }
    restore_forks(db, &mut log, &key_selector)?;
    Ok(DurableCollectionOperationLog { log })
}

fn ensure_stream(
    transaction: &Transaction<'_>,
    collection_id: &Id,
    collection_epoch: u64,
    key_selector: &Id,
) -> Result<()> {
    transaction
        .execute(
            "INSERT INTO sharing_operation_streams
                 (key_selector, collection_id, collection_epoch) VALUES (?1, ?2, ?3)
             ON CONFLICT DO NOTHING",
            params![
                key_selector.as_bytes().as_slice(),
                collection_id.as_bytes().as_slice(),
                as_sqlite_integer(collection_epoch, "collection operation epoch is too large")?,
            ],
        )
        .map_err(|error| map_sqlite(error, "collection operation stream could not be stored"))?;
    let stored: (Vec<u8>, i64) = transaction
        .query_row(
            "SELECT collection_id, collection_epoch
               FROM sharing_operation_streams WHERE key_selector = ?1",
            [key_selector.as_bytes().as_slice()],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .map_err(|error| map_sqlite(error, "collection operation stream could not be checked"))?;
    ensure!(
        Id::from_slice(&stored.0).map_err(corrupt)? == *collection_id
            && from_sqlite_integer(stored.1, "collection operation epoch is malformed")?
                == collection_epoch,
        AuthenticationFailed,
        "collection operation selector collides with another stream"
    );
    Ok(())
}

fn insert_operation(transaction: &Transaction<'_>, operation: &CollectionOperation) -> Result<()> {
    transaction
        .execute(
            "INSERT INTO sharing_operations
                 (key_selector, issuer_identity_vault_id, issuer_device_id,
                  device_sequence, operation_id, digest, record)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            params![
                operation.key_selector().as_bytes().as_slice(),
                operation.issuer_identity_vault_id().as_bytes().as_slice(),
                operation.issuer_device_id().as_bytes().as_slice(),
                as_sqlite_integer(
                    operation.device_sequence(),
                    "collection sequence is too large"
                )?,
                operation.operation_id().as_bytes().as_slice(),
                operation.digest().as_slice(),
                operation.encode(),
            ],
        )
        .map_err(|error| map_sqlite(error, "collection operation could not be stored"))?;
    Ok(())
}

fn check_stored_operation(db: &CatalogDb, operation: &CollectionOperation) -> Result<()> {
    let stored: Option<Vec<u8>> = db
        .connection()
        .query_row(
            "SELECT record FROM sharing_operations
              WHERE key_selector = ?1 AND issuer_identity_vault_id = ?2
                AND issuer_device_id = ?3 AND device_sequence = ?4",
            params![
                operation.key_selector().as_bytes().as_slice(),
                operation.issuer_identity_vault_id().as_bytes().as_slice(),
                operation.issuer_device_id().as_bytes().as_slice(),
                as_sqlite_integer(
                    operation.device_sequence(),
                    "collection sequence is too large"
                )?,
            ],
            |row| row.get(0),
        )
        .optional()
        .map_err(|error| map_sqlite(error, "collection operation could not be checked"))?;
    ensure!(
        stored.as_deref() == Some(operation.encode().as_slice()),
        CatalogCorrupt,
        "durable collection operation differs from accepted replay"
    );
    Ok(())
}

fn ensure_operation_projection(
    operation: &CollectionOperation,
    row: &StoredOperation,
) -> Result<()> {
    ensure!(
        Id::from_slice(&row.0).map_err(corrupt)? == *operation.issuer_identity_vault_id()
            && Id::from_slice(&row.1).map_err(corrupt)? == *operation.issuer_device_id()
            && from_sqlite_integer(row.2, "collection sequence is malformed")?
                == operation.device_sequence()
            && Id::from_slice(&row.3).map_err(corrupt)? == *operation.operation_id()
            && row.4.as_slice() == operation.digest(),
        CatalogCorrupt,
        "collection operation projection disagrees with its record"
    );
    Ok(())
}

fn restore_forks(db: &CatalogDb, log: &mut CollectionOperationLog, selector: &Id) -> Result<()> {
    let mut statement = db.connection().prepare(
        "SELECT issuer_identity_vault_id, issuer_device_id, state, accepted_record, conflicting_record
           FROM sharing_operation_forks WHERE key_selector = ?1",
    ).map_err(|error| map_sqlite(error, "collection forks could not be prepared"))?;
    let rows = statement
        .query_map([selector.as_bytes().as_slice()], |row| {
            Ok((
                row.get::<_, Vec<u8>>(0)?,
                row.get::<_, Vec<u8>>(1)?,
                row.get::<_, i64>(2)?,
                row.get::<_, Vec<u8>>(3)?,
                row.get::<_, Vec<u8>>(4)?,
            ))
        })
        .map_err(|error| map_sqlite(error, "collection forks could not be read"))?;
    for row in rows {
        let (vault, device, state, accepted, conflicting) =
            row.map_err(|error| map_sqlite(error, "a collection fork row could not be read"))?;
        let state = match state {
            1 => ForkState::Detected,
            2 => ForkState::Acknowledged,
            _ => {
                return Err(Error::new(
                    ChurStatus::CatalogCorrupt,
                    "collection fork state is invalid",
                ));
            }
        };
        log.restore_fork(
            Id::from_slice(&vault).map_err(corrupt)?,
            Id::from_slice(&device).map_err(corrupt)?,
            state,
            accepted,
            conflicting,
        )
        .map_err(corrupt)?;
    }
    Ok(())
}

fn corrupt(error: Error) -> Error {
    Error::new(ChurStatus::CatalogCorrupt, error.context())
}

#[cfg(test)]
mod tests {
    #![allow(clippy::expect_used, clippy::panic)]

    use super::*;
    use chur_crypto::{Key, Nonce, random};
    use chur_sync_protocol::{
        KeyDomain,
        collection_membership::{CollectionMembershipAction, CollectionMembershipRecord},
        collection_operation::CollectionObservedHead,
        grant::PermissionProfile,
        membership::EnrollmentRecord,
        operation::DeviceSigningKey,
        payload::PayloadBody,
    };

    use crate::{
        db::{CatalogKey, CatalogLocation},
        schema, sharing,
    };

    fn id(byte: u8) -> Id {
        Id::new([byte; 16]).expect("id")
    }

    fn identity(vault: Id, device: Id, seed: u8) -> (DeviceSigningKey, MembershipState) {
        let key = DeviceSigningKey::from_seed([seed; 32]);
        let enrollment =
            EnrollmentRecord::initial(vault, device, key.verifying_key(), [seed + 1; 32])
                .expect("enrollment")
                .sign(&key);
        (
            key,
            MembershipState::bootstrap(&enrollment).expect("membership"),
        )
    }

    fn open() -> CatalogDb {
        let root: Key = random::secret::<32>().expect("root");
        let vault = random::id().expect("id");
        let key = CatalogKey::derive(&root, &vault).expect("key");
        let mut db = CatalogDb::open(&CatalogLocation::Memory, &key).expect("open");
        schema::open_at_current_version(&mut db, 1).expect("schema");
        db
    }

    #[test]
    fn cross_vault_operations_and_heads_restore_after_reopen() {
        let mut db = open();
        let (source_key, source_membership) = identity(id(1), id(2), 10);
        let (recipient_key, recipient_membership) = identity(id(3), id(4), 20);
        sharing::provision(&mut db, id(1), id(5), 1).expect("sharing");
        let record = CollectionMembershipRecord::new(
            id(1),
            id(5),
            1,
            [0; 32],
            CollectionMembershipAction::Upsert(PermissionProfile::Contribute),
            id(3),
            id(4),
            recipient_key.verifying_key(),
            [21; 32],
            1,
            id(1),
            id(2),
            1,
            1,
        )
        .expect("record")
        .sign(&source_key);
        let collection_membership =
            sharing::accept_membership(&mut db, &record, &source_membership)
                .expect("member")
                .0;
        let collection_key = Key::new([30; 32]);
        let domain = KeyDomain::collection(&collection_key, &id(5), 1).expect("domain");
        let selector = *domain.selector();
        let operation_key = Key::new(*domain.operation_key().expose());
        let mut keys = KeyDirectory::new(&Key::new([31; 32]), &id(1)).expect("keys");
        keys.insert(domain).expect("domain");
        let payload = OperationPayload::new(
            id(5),
            1,
            PayloadBody::CreateAlbum {
                album_id: id(6),
                name: "Shared".to_owned(),
            },
        )
        .expect("payload");
        let source = CollectionOperation::seal(
            id(7),
            id(1),
            id(2),
            1,
            [0; 32],
            Vec::new(),
            selector,
            &operation_key,
            Nonce::new([32; 24]),
            &payload.encode(),
        )
        .expect("source")
        .sign(&source_key);
        let recipient = CollectionOperation::seal(
            id(8),
            id(3),
            id(4),
            1,
            [0; 32],
            vec![CollectionObservedHead::new(id(1), id(2), 1)],
            selector,
            &operation_key,
            Nonce::new([33; 24]),
            &payload.encode(),
        )
        .expect("recipient")
        .sign(&recipient_key);
        let mut log =
            DurableCollectionOperationLog::provision(&mut db, id(5), 1, selector).expect("log");
        assert_eq!(
            log.accept(
                &mut db,
                &source,
                &payload,
                &source_membership,
                &source_membership,
                &collection_membership,
            )
            .expect("source"),
            ApplyOutcome::Applied
        );
        assert_eq!(
            log.accept(
                &mut db,
                &recipient,
                &payload,
                &recipient_membership,
                &source_membership,
                &collection_membership,
            )
            .expect("recipient"),
            ApplyOutcome::Applied
        );
        let memberships =
            BTreeMap::from([(id(1), source_membership), (id(3), recipient_membership)]);
        let restored = load(
            &db,
            id(5),
            1,
            selector,
            &keys,
            &memberships,
            &collection_membership,
        )
        .expect("restore");
        assert_eq!(restored.head(&id(1), &id(2)).map(|head| head.0), Some(1));
        assert_eq!(restored.head(&id(3), &id(4)).map(|head| head.0), Some(1));

        db.connection()
            .execute(
                "UPDATE sharing_operations SET digest = ?1 WHERE operation_id = ?2",
                params![[9u8; 32], id(8).as_bytes().as_slice()],
            )
            .expect("tamper");
        assert_eq!(
            load(
                &db,
                id(5),
                1,
                selector,
                &keys,
                &memberships,
                &collection_membership,
            )
            .err()
            .expect("tamper rejected")
            .status(),
            ChurStatus::CatalogCorrupt
        );
    }
}
