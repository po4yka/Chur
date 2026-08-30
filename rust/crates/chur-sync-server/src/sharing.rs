use chur_core::limits::sync as bounds;
use chur_core::{ChurStatus, Error, Id, Result, ensure};
use chur_sync_protocol::collection_membership::{
    CollectionMembershipAction, CollectionMembershipOutcome, CollectionMembershipRecord,
    CollectionMembershipState,
};
use chur_sync_protocol::grant::{CollectionGrant, PermissionProfile};
use chur_sync_protocol::operation::Operation;
use rusqlite::{OptionalExtension, params};

use super::{ReferenceServer, RelayOutcome, map_sqlite, relay, to_sqlite};

impl ReferenceServer {
    /// Accepts one signed collection membership record and its outer operation.
    pub fn accept_collection_membership(
        &mut self,
        record: &CollectionMembershipRecord,
        outer: &Operation,
    ) -> Result<RelayOutcome> {
        if let Some(stored) = existing_membership(self, record)? {
            ensure!(
                stored == record.encode() && relay::operation_is_exact(&self.db, outer)?,
                SyncChainFork,
                "collection membership replay differs"
            );
            return Ok(RelayOutcome::Duplicate);
        }
        ensure!(
            record.issuer_identity_vault_id() == outer.vault_id()
                && record.issuer_device_id() == outer.device_id()
                && record.created_sequence() == outer.device_sequence(),
            AuthenticationFailed,
            "collection membership does not match its outer operation"
        );
        let issuer = relay::membership_state(&self.db, outer.vault_id())?;
        let operation_outcome = relay::validate_operation(&self.db, outer, &issuer)?;
        let mut state = match collection_state(self, record.collection_id())? {
            Some(state) => state,
            None => CollectionMembershipState::new(
                *record.source_vault_id(),
                *record.collection_id(),
                initial_epoch(record)?,
            )?,
        };
        if matches!(record.action(), CollectionMembershipAction::Upsert(_)) {
            restore_relayed_epoch(
                &mut state,
                record.collection_epoch(),
                record.issuer_identity_vault_id(),
            )?;
        }
        accept_relayed_membership(&mut state, record, &issuer)?;
        self.ensure_account_capacity(
            outer.vault_id(),
            added_bytes(
                operation_outcome,
                outer.encode().len(),
                record.encode().len(),
            )?,
        )?;
        let transaction = self
            .db
            .transaction()
            .map_err(|error| map_sqlite(error, "collection membership transaction failed"))?;
        relay::insert_operation(&transaction, outer, operation_outcome)?;
        let issuer_key = issuer
            .device(record.issuer_device_id())
            .ok_or_else(|| Error::new(ChurStatus::AuthenticationFailed, "issuer is unknown"))?
            .signing_public_key();
        transaction
            .execute(
                "INSERT INTO collection_membership_records (
                    collection_id, membership_generation, issuer_vault_id,
                    issuer_signing_public_key, recipient_vault_id,
                    recipient_device_id, record
                 ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
                params![
                    record.collection_id().as_bytes().as_slice(),
                    to_sqlite(
                        record.collection_membership_generation(),
                        "collection membership generation does not fit"
                    )?,
                    outer.vault_id().as_bytes().as_slice(),
                    issuer_key.as_slice(),
                    record.recipient_identity_vault_id().as_bytes().as_slice(),
                    record.recipient_device_id().as_bytes().as_slice(),
                    record.encode(),
                ],
            )
            .map_err(|error| map_sqlite(error, "collection membership storage failed"))?;
        transaction
            .commit()
            .map_err(|error| map_sqlite(error, "collection membership commit failed"))?;
        Ok(RelayOutcome::Stored)
    }

    /// Accepts one signed collection grant and its outer operation.
    pub fn accept_collection_grant(
        &mut self,
        grant: &CollectionGrant,
        outer: &Operation,
    ) -> Result<RelayOutcome> {
        if let Some(stored) = existing_grant(self, grant)? {
            ensure!(
                stored == grant.encode() && relay::operation_is_exact(&self.db, outer)?,
                Conflict,
                "collection grant replay differs"
            );
            return Ok(RelayOutcome::Duplicate);
        }
        ensure!(
            grant.grant_id() == outer.operation_id()
                && grant.sender_device_id() == outer.device_id()
                && grant.created_sequence() == outer.device_sequence(),
            AuthenticationFailed,
            "collection grant does not match its outer operation"
        );
        let issuer = relay::membership_state(&self.db, outer.vault_id())?;
        let operation_outcome = relay::validate_operation(&self.db, outer, &issuer)?;
        let mut state = collection_state(self, grant.collection_id())?
            .ok_or_else(|| Error::new(ChurStatus::NotFound, "collection membership is absent"))?;
        restore_relayed_epoch(&mut state, grant.collection_epoch(), outer.vault_id())?;
        state.validate_grant(grant, &issuer)?;
        self.ensure_account_capacity(
            outer.vault_id(),
            added_bytes(
                operation_outcome,
                outer.encode().len(),
                grant.encode().len(),
            )?,
        )?;
        let transaction = self
            .db
            .transaction()
            .map_err(|error| map_sqlite(error, "collection grant transaction failed"))?;
        relay::insert_operation(&transaction, outer, operation_outcome)?;
        transaction
            .execute(
                "INSERT INTO collection_grants (
                    grant_id, collection_id, collection_epoch, issuer_vault_id,
                    recipient_vault_id, recipient_device_id, record
                 ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
                params![
                    grant.grant_id().as_bytes().as_slice(),
                    grant.collection_id().as_bytes().as_slice(),
                    to_sqlite(grant.collection_epoch(), "collection epoch does not fit")?,
                    outer.vault_id().as_bytes().as_slice(),
                    grant.recipient_identity_vault_id().as_bytes().as_slice(),
                    grant.recipient_device_id().as_bytes().as_slice(),
                    grant.encode(),
                ],
            )
            .map_err(|error| map_sqlite(error, "collection grant storage failed"))?;
        transaction
            .commit()
            .map_err(|error| map_sqlite(error, "collection grant commit failed"))?;
        Ok(RelayOutcome::Stored)
    }

    /// Returns membership records visible to one recipient device.
    pub fn collection_memberships_for_recipient(
        &self,
        recipient_vault_id: Id,
        recipient_device_id: Id,
    ) -> Result<Vec<Vec<u8>>> {
        let collections = recipient_collections(self, recipient_vault_id, recipient_device_id)?;
        let mut records = Vec::new();
        let mut bytes = 0usize;
        for collection_id in collections {
            let state = collection_state(self, &collection_id)?.ok_or_else(|| {
                Error::new(
                    ChurStatus::CatalogCorrupt,
                    "recipient collection state is absent",
                )
            })?;
            let member = state
                .member(&recipient_vault_id, &recipient_device_id)
                .ok_or_else(|| {
                    Error::new(ChurStatus::CatalogCorrupt, "recipient membership is absent")
                })?;
            let cutoff = if member.is_active() {
                i64::MAX
            } else {
                to_sqlite(
                    member.membership_generation(),
                    "recipient membership generation does not fit",
                )?
            };
            let mut statement = self
                .db
                .prepare(
                    "SELECT record FROM collection_membership_records
                     WHERE collection_id = ?1 AND membership_generation <= ?2
                     ORDER BY membership_generation LIMIT ?3",
                )
                .map_err(|error| map_sqlite(error, "collection inbox prepare failed"))?;
            let rows = statement
                .query_map(
                    params![
                        collection_id.as_bytes().as_slice(),
                        cutoff,
                        i64::try_from(bounds::RESPONSE_OPERATIONS_MAX).unwrap_or(i64::MAX),
                    ],
                    |row| row.get::<_, Vec<u8>>(0),
                )
                .map_err(|error| map_sqlite(error, "collection inbox query failed"))?;
            for row in rows {
                if !push_bounded(
                    &mut records,
                    &mut bytes,
                    row.map_err(|error| map_sqlite(error, "collection inbox row failed"))?,
                )? {
                    return Ok(records);
                }
            }
        }
        Ok(records)
    }

    /// Returns current grants addressed to one active recipient device.
    pub fn collection_grants_for_recipient(
        &self,
        recipient_vault_id: Id,
        recipient_device_id: Id,
    ) -> Result<Vec<Vec<u8>>> {
        let collections = recipient_collections(self, recipient_vault_id, recipient_device_id)?;
        let mut records = Vec::new();
        let mut bytes = 0usize;
        for collection_id in collections {
            let state = collection_state(self, &collection_id)?.ok_or_else(|| {
                Error::new(
                    ChurStatus::CatalogCorrupt,
                    "recipient collection state is absent",
                )
            })?;
            if !state.is_authorized(
                &recipient_vault_id,
                &recipient_device_id,
                PermissionProfile::Read,
            ) {
                continue;
            }
            let membership_generation = state
                .member(&recipient_vault_id, &recipient_device_id)
                .ok_or_else(|| {
                    Error::new(ChurStatus::CatalogCorrupt, "recipient membership is absent")
                })?
                .membership_generation();
            let mut statement = self
                .db
                .prepare(
                    "SELECT record FROM collection_grants
                     WHERE collection_id = ?1 AND recipient_vault_id = ?2
                       AND recipient_device_id = ?3
                     ORDER BY collection_epoch, grant_id LIMIT ?4",
                )
                .map_err(|error| map_sqlite(error, "grant inbox prepare failed"))?;
            let rows = statement
                .query_map(
                    params![
                        collection_id.as_bytes().as_slice(),
                        recipient_vault_id.as_bytes().as_slice(),
                        recipient_device_id.as_bytes().as_slice(),
                        i64::try_from(bounds::RESPONSE_OPERATIONS_MAX).unwrap_or(i64::MAX),
                    ],
                    |row| row.get::<_, Vec<u8>>(0),
                )
                .map_err(|error| map_sqlite(error, "grant inbox query failed"))?;
            for row in rows {
                let record = row.map_err(|error| map_sqlite(error, "grant inbox row failed"))?;
                let grant = CollectionGrant::decode(&record).map_err(corrupt_sharing)?;
                if grant.collection_epoch() != state.collection_epoch()
                    || grant.collection_membership_generation() != membership_generation
                {
                    continue;
                }
                if !push_bounded(&mut records, &mut bytes, record)? {
                    return Ok(records);
                }
            }
        }
        Ok(records)
    }
}

fn existing_membership(
    server: &ReferenceServer,
    record: &CollectionMembershipRecord,
) -> Result<Option<Vec<u8>>> {
    server
        .db
        .query_row(
            "SELECT record FROM collection_membership_records
             WHERE collection_id = ?1 AND membership_generation = ?2",
            params![
                record.collection_id().as_bytes().as_slice(),
                to_sqlite(
                    record.collection_membership_generation(),
                    "collection membership generation does not fit"
                )?,
            ],
            |row| row.get(0),
        )
        .optional()
        .map_err(|error| map_sqlite(error, "collection membership replay lookup failed"))
}

fn existing_grant(server: &ReferenceServer, grant: &CollectionGrant) -> Result<Option<Vec<u8>>> {
    server
        .db
        .query_row(
            "SELECT record FROM collection_grants WHERE grant_id = ?1",
            [grant.grant_id().as_bytes().as_slice()],
            |row| row.get(0),
        )
        .optional()
        .map_err(|error| map_sqlite(error, "collection grant replay lookup failed"))
}

fn initial_epoch(record: &CollectionMembershipRecord) -> Result<u64> {
    ensure!(
        record.collection_membership_generation() == 1
            && matches!(record.action(), CollectionMembershipAction::Upsert(_)),
        AuthenticationFailed,
        "initial collection membership record is invalid"
    );
    Ok(record.collection_epoch())
}

fn accept_relayed_membership(
    state: &mut CollectionMembershipState,
    record: &CollectionMembershipRecord,
    issuer: &chur_sync_protocol::state::MembershipState,
) -> Result<()> {
    if state
        .recipient_pin(
            record.recipient_identity_vault_id(),
            record.recipient_device_id(),
        )
        .is_some_and(|pin| {
            pin.signing_public_key() != record.recipient_signing_public_key()
                || pin.hpke_public_key() != record.recipient_hpke_public_key()
        })
    {
        state.verify_recipient_keys(
            *record.recipient_identity_vault_id(),
            *record.recipient_device_id(),
            *record.recipient_signing_public_key(),
            *record.recipient_hpke_public_key(),
        )?;
    }
    ensure!(
        state.accept(record, issuer)? == CollectionMembershipOutcome::Applied,
        SyncHeadRollback,
        "collection membership replay is not a successor"
    );
    Ok(())
}

fn restore_relayed_epoch(
    state: &mut CollectionMembershipState,
    target_epoch: u64,
    issuer_vault_id: &Id,
) -> Result<()> {
    if target_epoch > state.collection_epoch() {
        ensure!(
            issuer_vault_id == state.source_vault_id(),
            AuthenticationFailed,
            "only the source vault can relay a newer collection epoch"
        );
        state.restore_collection_epoch(target_epoch)?;
    }
    Ok(())
}

fn collection_state(
    server: &ReferenceServer,
    collection_id: &Id,
) -> Result<Option<CollectionMembershipState>> {
    let mut statement = server
        .db
        .prepare(
            "SELECT issuer_signing_public_key, record
             FROM collection_membership_records
             WHERE collection_id = ?1 ORDER BY membership_generation",
        )
        .map_err(|error| map_sqlite(error, "collection membership restore prepare failed"))?;
    let rows = statement
        .query_map([collection_id.as_bytes().as_slice()], |row| {
            Ok((row.get::<_, Vec<u8>>(0)?, row.get::<_, Vec<u8>>(1)?))
        })
        .map_err(|error| map_sqlite(error, "collection membership restore query failed"))?;
    let rows = rows
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(|error| map_sqlite(error, "collection membership restore row failed"))?;
    let Some((_, first_bytes)) = rows.first() else {
        return Ok(None);
    };
    let first = CollectionMembershipRecord::decode(first_bytes).map_err(corrupt_sharing)?;
    let mut state = CollectionMembershipState::new(
        *first.source_vault_id(),
        *first.collection_id(),
        initial_epoch(&first).map_err(corrupt_sharing)?,
    )
    .map_err(corrupt_sharing)?;
    for (issuer_key, bytes) in rows {
        let record = CollectionMembershipRecord::decode(&bytes).map_err(corrupt_sharing)?;
        if matches!(record.action(), CollectionMembershipAction::Upsert(_)) {
            restore_relayed_epoch(
                &mut state,
                record.collection_epoch(),
                record.issuer_identity_vault_id(),
            )
            .map_err(corrupt_sharing)?;
        }
        if state
            .recipient_pin(
                record.recipient_identity_vault_id(),
                record.recipient_device_id(),
            )
            .is_some_and(|pin| {
                pin.signing_public_key() != record.recipient_signing_public_key()
                    || pin.hpke_public_key() != record.recipient_hpke_public_key()
            })
        {
            state
                .verify_recipient_keys(
                    *record.recipient_identity_vault_id(),
                    *record.recipient_device_id(),
                    *record.recipient_signing_public_key(),
                    *record.recipient_hpke_public_key(),
                )
                .map_err(corrupt_sharing)?;
        }
        let issuer_key: [u8; 32] = issuer_key
            .try_into()
            .map_err(|_| corrupt_sharing(Error::new(ChurStatus::CatalogCorrupt, "issuer key")))?;
        ensure!(
            state
                .restore_accepted(&record, &issuer_key)
                .map_err(corrupt_sharing)?
                == CollectionMembershipOutcome::Applied,
            CatalogCorrupt,
            "stored collection membership is not a successor"
        );
    }
    let grant_epoch: Option<i64> = server
        .db
        .query_row(
            "SELECT MAX(collection_epoch) FROM collection_grants WHERE collection_id = ?1",
            [collection_id.as_bytes().as_slice()],
            |row| row.get(0),
        )
        .map_err(|error| map_sqlite(error, "collection grant epoch lookup failed"))?;
    if let Some(grant_epoch) = grant_epoch {
        let grant_epoch =
            super::from_sqlite(grant_epoch, "stored collection grant epoch is invalid")?;
        if grant_epoch > state.collection_epoch() {
            state
                .restore_collection_epoch(grant_epoch)
                .map_err(corrupt_sharing)?;
        }
    }
    Ok(Some(state))
}

fn recipient_collections(server: &ReferenceServer, vault_id: Id, device_id: Id) -> Result<Vec<Id>> {
    let mut statement = server
        .db
        .prepare(
            "SELECT DISTINCT collection_id FROM collection_membership_records
             WHERE recipient_vault_id = ?1 AND recipient_device_id = ?2
             ORDER BY collection_id LIMIT ?3",
        )
        .map_err(|error| map_sqlite(error, "recipient collection lookup prepare failed"))?;
    let rows = statement
        .query_map(
            params![
                vault_id.as_bytes().as_slice(),
                device_id.as_bytes().as_slice(),
                i64::try_from(bounds::RESPONSE_OPERATIONS_MAX).unwrap_or(i64::MAX),
            ],
            |row| row.get::<_, Vec<u8>>(0),
        )
        .map_err(|error| map_sqlite(error, "recipient collection lookup failed"))?;
    rows.map(|row| {
        let bytes = row.map_err(|error| map_sqlite(error, "recipient collection row failed"))?;
        Id::from_slice(&bytes)
    })
    .collect()
}

fn added_bytes(outcome: RelayOutcome, operation: usize, sharing: usize) -> Result<usize> {
    sharing
        .checked_add(if outcome == RelayOutcome::Stored {
            operation
        } else {
            0
        })
        .ok_or_else(|| Error::new(ChurStatus::ResourceLimitExceeded, "relay bytes overflow"))
}

fn push_bounded(records: &mut Vec<Vec<u8>>, bytes: &mut usize, record: Vec<u8>) -> Result<bool> {
    if records.len() == bounds::RESPONSE_OPERATIONS_MAX {
        return Ok(false);
    }
    let next = bytes
        .checked_add(record.len())
        .ok_or_else(|| Error::new(ChurStatus::CatalogCorrupt, "sharing inbox size overflows"))?;
    if next > bounds::RESPONSE_BYTES_MAX {
        return Ok(false);
    }
    *bytes = next;
    records.push(record);
    Ok(true)
}

fn corrupt_sharing(_: Error) -> Error {
    Error::new(
        ChurStatus::CatalogCorrupt,
        "stored collection sharing state does not authenticate",
    )
}

#[cfg(test)]
mod tests {
    #![allow(clippy::expect_used)]

    use chur_crypto::secret::Key;
    use chur_sync_protocol::collection_membership::{
        CollectionMembershipAction, CollectionMembershipRecord,
    };
    use chur_sync_protocol::grant::{CollectionGrant, PermissionProfile};
    use chur_sync_protocol::identity::DeviceIdentity;
    use chur_sync_protocol::membership::EnrollmentRecord;
    use chur_sync_protocol::operation::{DeviceSigningKey, Operation};

    use super::*;

    #[test]
    fn signed_sharing_inbox_survives_restart_and_opens_only_for_its_recipient() {
        let root = crate::tests::TestRoot::new();
        let source_vault = id(1);
        let source_device = id(2);
        let source_key = DeviceSigningKey::from_seed([3; 32]);
        let source_enrollment = EnrollmentRecord::initial(
            source_vault,
            source_device,
            source_key.verifying_key(),
            [4; 32],
        )
        .expect("source enrollment")
        .sign(&source_key);
        let source_initial = operation(source_vault, source_device, id(5), 1, [0; 32], &source_key);
        let recipient_vault = id(6);
        let recipient_device = id(7);
        let recipient = DeviceIdentity::from_seeds([8; 32], [9; 32]);
        let recipient_enrollment = EnrollmentRecord::initial(
            recipient_vault,
            recipient_device,
            recipient.signing_public_key(),
            recipient.hpke_public_key(),
        )
        .expect("recipient enrollment")
        .sign(recipient.signing_key());
        let recipient_initial = operation(
            recipient_vault,
            recipient_device,
            id(10),
            1,
            [0; 32],
            recipient.signing_key(),
        );
        let second_vault = id(20);
        let second_device = id(21);
        let second_recipient = DeviceIdentity::from_seeds([22; 32], [23; 32]);
        let second_enrollment = EnrollmentRecord::initial(
            second_vault,
            second_device,
            second_recipient.signing_public_key(),
            second_recipient.hpke_public_key(),
        )
        .expect("second recipient enrollment")
        .sign(second_recipient.signing_key());
        let second_initial = operation(
            second_vault,
            second_device,
            id(24),
            1,
            [0; 32],
            second_recipient.signing_key(),
        );
        let collection_id = id(11);
        let membership = CollectionMembershipRecord::new(
            source_vault,
            collection_id,
            1,
            [0; 32],
            CollectionMembershipAction::Upsert(PermissionProfile::Read),
            recipient_vault,
            recipient_device,
            recipient.signing_public_key(),
            recipient.hpke_public_key(),
            1,
            source_vault,
            source_device,
            1,
            2,
        )
        .expect("collection membership")
        .sign(&source_key);
        let membership_outer = operation(
            source_vault,
            source_device,
            id(12),
            2,
            source_initial.digest(),
            &source_key,
        );
        let collection_key = Key::new([13; 32]);
        let grant = CollectionGrant::seal(
            id(14),
            source_vault,
            collection_id,
            1,
            1,
            recipient_vault,
            recipient_device,
            &recipient.hpke_public_key(),
            source_device,
            PermissionProfile::Read,
            1,
            3,
            &collection_key,
            &source_key,
        )
        .expect("grant");
        let grant_outer = operation(
            source_vault,
            source_device,
            id(14),
            3,
            membership_outer.digest(),
            &source_key,
        );

        let mut server = ReferenceServer::open(&root.0, 1_024, 65_536).expect("server");
        server
            .accept_initial_membership(&source_enrollment, &source_initial)
            .expect("source bootstrap");
        server
            .accept_initial_membership(&recipient_enrollment, &recipient_initial)
            .expect("recipient bootstrap");
        server
            .accept_initial_membership(&second_enrollment, &second_initial)
            .expect("second recipient bootstrap");
        assert!(
            server
                .accept_collection_membership(&membership, &membership_outer)
                .expect("membership")
                == RelayOutcome::Stored
        );
        assert!(
            server
                .accept_collection_membership(&membership, &membership_outer)
                .expect("membership replay")
                == RelayOutcome::Duplicate
        );
        let conflicting_membership = CollectionMembershipRecord::new(
            source_vault,
            collection_id,
            1,
            [0; 32],
            CollectionMembershipAction::Upsert(PermissionProfile::Contribute),
            recipient_vault,
            recipient_device,
            recipient.signing_public_key(),
            recipient.hpke_public_key(),
            1,
            source_vault,
            source_device,
            1,
            2,
        )
        .expect("conflicting membership")
        .sign(&source_key);
        assert!(
            server
                .accept_collection_membership(&conflicting_membership, &membership_outer)
                .expect_err("membership fork")
                .status()
                == ChurStatus::SyncChainFork
        );
        assert!(
            server
                .accept_collection_grant(&grant, &grant_outer)
                .expect("grant")
                == RelayOutcome::Stored
        );
        assert!(
            server
                .accept_collection_grant(&grant, &grant_outer)
                .expect("grant replay")
                == RelayOutcome::Duplicate
        );
        let conflicting_grant = CollectionGrant::seal(
            id(14),
            source_vault,
            collection_id,
            1,
            1,
            recipient_vault,
            recipient_device,
            &recipient.hpke_public_key(),
            source_device,
            PermissionProfile::Read,
            1,
            3,
            &collection_key,
            &source_key,
        )
        .expect("conflicting grant");
        assert!(
            server
                .accept_collection_grant(&conflicting_grant, &grant_outer)
                .expect_err("grant conflict")
                .status()
                == ChurStatus::Conflict
        );
        let second_membership = CollectionMembershipRecord::new(
            source_vault,
            collection_id,
            2,
            membership.commitment(),
            CollectionMembershipAction::Upsert(PermissionProfile::Contribute),
            second_vault,
            second_device,
            second_recipient.signing_public_key(),
            second_recipient.hpke_public_key(),
            1,
            source_vault,
            source_device,
            1,
            4,
        )
        .expect("second collection membership")
        .sign(&source_key);
        let second_membership_outer = operation(
            source_vault,
            source_device,
            id(25),
            4,
            grant_outer.digest(),
            &source_key,
        );
        server
            .accept_collection_membership(&second_membership, &second_membership_outer)
            .expect("second membership");
        let second_grant = CollectionGrant::seal(
            id(26),
            source_vault,
            collection_id,
            1,
            2,
            second_vault,
            second_device,
            &second_recipient.hpke_public_key(),
            source_device,
            PermissionProfile::Contribute,
            1,
            5,
            &collection_key,
            &source_key,
        )
        .expect("second grant");
        let second_grant_outer = operation(
            source_vault,
            source_device,
            id(26),
            5,
            second_membership_outer.digest(),
            &source_key,
        );
        server
            .accept_collection_grant(&second_grant, &second_grant_outer)
            .expect("second grant");
        let first_grants = server
            .collection_grants_for_recipient(recipient_vault, recipient_device)
            .expect("first grant inbox");
        assert_eq!(first_grants, vec![grant.encode()]);
        assert!(
            CollectionGrant::decode(&first_grants[0])
                .expect("decode grant")
                .open_collection_key(
                    &recipient_vault,
                    &recipient_device,
                    &recipient,
                    &source_key.verifying_key(),
                )
                .expect("open grant")
                == collection_key
        );

        let revocation = CollectionMembershipRecord::new(
            source_vault,
            collection_id,
            3,
            second_membership.commitment(),
            CollectionMembershipAction::Revoke,
            recipient_vault,
            recipient_device,
            recipient.signing_public_key(),
            recipient.hpke_public_key(),
            2,
            source_vault,
            source_device,
            1,
            6,
        )
        .expect("recipient revocation")
        .sign(&source_key);
        let revocation_outer = operation(
            source_vault,
            source_device,
            id(27),
            6,
            second_grant_outer.digest(),
            &source_key,
        );
        server
            .accept_collection_membership(&revocation, &revocation_outer)
            .expect("recipient revocation");
        let current_grant = CollectionGrant::seal(
            id(28),
            source_vault,
            collection_id,
            2,
            2,
            second_vault,
            second_device,
            &second_recipient.hpke_public_key(),
            source_device,
            PermissionProfile::Contribute,
            1,
            7,
            &Key::new([29; 32]),
            &source_key,
        )
        .expect("current grant");
        let current_grant_outer = operation(
            source_vault,
            source_device,
            id(28),
            7,
            revocation_outer.digest(),
            &source_key,
        );
        server
            .accept_collection_grant(&current_grant, &current_grant_outer)
            .expect("current grant");
        drop(server);

        let server = ReferenceServer::open(&root.0, 1_024, 65_536).expect("reopen");
        assert_eq!(
            server
                .collection_memberships_for_recipient(recipient_vault, recipient_device)
                .expect("membership inbox"),
            vec![
                membership.encode(),
                second_membership.encode(),
                revocation.encode(),
            ]
        );
        let grants = server
            .collection_grants_for_recipient(recipient_vault, recipient_device)
            .expect("grant inbox");
        assert!(grants.is_empty());
        assert_eq!(
            server
                .collection_memberships_for_recipient(second_vault, second_device)
                .expect("second membership inbox"),
            vec![
                membership.encode(),
                second_membership.encode(),
                revocation.encode(),
            ]
        );
        assert_eq!(
            server
                .collection_grants_for_recipient(second_vault, second_device)
                .expect("second grant inbox"),
            vec![current_grant.encode()]
        );
        assert!(
            server
                .collection_memberships_for_recipient(id(15), id(16))
                .expect("unrelated inbox")
                .is_empty()
        );
        assert!(
            server
                .collection_grants_for_recipient(id(15), id(16))
                .expect("unrelated grants")
                .is_empty()
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
            id(17),
            [vec![18; 24], vec![19; 16]].concat(),
            [0; 64],
        )
        .expect("operation")
        .sign(key)
    }
}
