use chur_core::limits::sync as bounds;
use chur_core::{ChurStatus, Error, Id, Result, ensure};
use chur_sync_protocol::collection_membership::{
    CollectionMembershipAction, CollectionMembershipOutcome, CollectionMembershipRecord,
    CollectionMembershipState,
};
use chur_sync_protocol::collection_operation::CollectionOperation;
use chur_sync_protocol::grant::{CollectionGrant, PermissionProfile};
use chur_sync_protocol::operation::Operation;
use rusqlite::{OptionalExtension, params};

use super::{ReferenceServer, RelayOutcome, map_sqlite, relay, to_sqlite};

pub(super) fn migrate_outer_associations(db: &mut rusqlite::Connection) -> Result<()> {
    for (table, column, definition) in [
        (
            "collection_membership_records",
            "outer_device_id",
            "outer_device_id BLOB CHECK(length(outer_device_id) = 16)",
        ),
        (
            "collection_membership_records",
            "outer_device_sequence",
            "outer_device_sequence INTEGER CHECK(outer_device_sequence > 0)",
        ),
        (
            "collection_grants",
            "outer_device_id",
            "outer_device_id BLOB CHECK(length(outer_device_id) = 16)",
        ),
        (
            "collection_grants",
            "outer_device_sequence",
            "outer_device_sequence INTEGER CHECK(outer_device_sequence > 0)",
        ),
        (
            "collection_grants",
            "key_selector",
            "key_selector BLOB CHECK(length(key_selector) = 16)",
        ),
    ] {
        if !column_exists(db, table, column)? {
            db.execute(&format!("ALTER TABLE {table} ADD COLUMN {definition}"), [])
                .map_err(|error| map_sqlite(error, "sharing schema migration failed"))?;
        }
    }

    let transaction = db
        .transaction()
        .map_err(|error| map_sqlite(error, "sharing association migration failed"))?;
    let membership_rows = {
        let mut statement = transaction
            .prepare(
                "SELECT collection_id, membership_generation, record
                   FROM collection_membership_records
                  WHERE outer_device_id IS NULL OR outer_device_sequence IS NULL",
            )
            .map_err(|error| map_sqlite(error, "sharing membership migration prepare failed"))?;
        statement
            .query_map([], |row| {
                Ok((
                    row.get::<_, Vec<u8>>(0)?,
                    row.get::<_, i64>(1)?,
                    row.get::<_, Vec<u8>>(2)?,
                ))
            })
            .map_err(|error| map_sqlite(error, "sharing membership migration query failed"))?
            .collect::<rusqlite::Result<Vec<_>>>()
            .map_err(|error| map_sqlite(error, "sharing membership migration row failed"))?
    };
    for (collection_id, generation, bytes) in membership_rows {
        let record = CollectionMembershipRecord::decode(&bytes).map_err(corrupt_sharing)?;
        ensure!(
            record.collection_id().as_bytes().as_slice() == collection_id
                && to_sqlite(
                    record.collection_membership_generation(),
                    "collection membership generation does not fit"
                )? == generation,
            CatalogCorrupt,
            "stored collection membership projection is invalid"
        );
        transaction
            .execute(
                "UPDATE collection_membership_records
                    SET outer_device_id = ?3, outer_device_sequence = ?4
                  WHERE collection_id = ?1 AND membership_generation = ?2",
                params![
                    collection_id,
                    generation,
                    record.issuer_device_id().as_bytes().as_slice(),
                    to_sqlite(
                        record.created_sequence(),
                        "outer membership sequence does not fit"
                    )?,
                ],
            )
            .map_err(|error| map_sqlite(error, "sharing membership migration failed"))?;
    }
    let grant_rows = {
        let mut statement = transaction
            .prepare(
                "SELECT grant_id, record FROM collection_grants
                  WHERE outer_device_id IS NULL OR outer_device_sequence IS NULL",
            )
            .map_err(|error| map_sqlite(error, "sharing grant migration prepare failed"))?;
        statement
            .query_map([], |row| {
                Ok((row.get::<_, Vec<u8>>(0)?, row.get::<_, Vec<u8>>(1)?))
            })
            .map_err(|error| map_sqlite(error, "sharing grant migration query failed"))?
            .collect::<rusqlite::Result<Vec<_>>>()
            .map_err(|error| map_sqlite(error, "sharing grant migration row failed"))?
    };
    for (grant_id, bytes) in grant_rows {
        let grant = CollectionGrant::decode(&bytes).map_err(corrupt_sharing)?;
        ensure!(
            grant.grant_id().as_bytes().as_slice() == grant_id,
            CatalogCorrupt,
            "stored collection grant projection is invalid"
        );
        transaction
            .execute(
                "UPDATE collection_grants
                    SET outer_device_id = ?2, outer_device_sequence = ?3
                  WHERE grant_id = ?1",
                params![
                    grant_id,
                    grant.sender_device_id().as_bytes().as_slice(),
                    to_sqlite(
                        grant.created_sequence(),
                        "outer grant sequence does not fit"
                    )?,
                ],
            )
            .map_err(|error| map_sqlite(error, "sharing grant migration failed"))?;
    }
    let selector_rows = {
        let mut statement = transaction
            .prepare(
                "SELECT grants.grant_id, operations.record
                   FROM collection_grants AS grants
                   JOIN operations ON operations.vault_id = grants.issuer_vault_id
                    AND operations.device_id = grants.outer_device_id
                    AND operations.device_sequence = grants.outer_device_sequence
                  WHERE grants.key_selector IS NULL",
            )
            .map_err(|error| map_sqlite(error, "sharing selector migration prepare failed"))?;
        statement
            .query_map([], |row| {
                Ok((row.get::<_, Vec<u8>>(0)?, row.get::<_, Vec<u8>>(1)?))
            })
            .map_err(|error| map_sqlite(error, "sharing selector migration query failed"))?
            .collect::<rusqlite::Result<Vec<_>>>()
            .map_err(|error| map_sqlite(error, "sharing selector migration row failed"))?
    };
    for (grant_id, operation_bytes) in selector_rows {
        let operation = Operation::decode(&operation_bytes).map_err(corrupt_sharing)?;
        transaction
            .execute(
                "UPDATE collection_grants SET key_selector = ?2 WHERE grant_id = ?1",
                params![grant_id, operation.key_selector().as_bytes().as_slice()],
            )
            .map_err(|error| map_sqlite(error, "sharing selector migration failed"))?;
    }
    transaction
        .commit()
        .map_err(|error| map_sqlite(error, "sharing association migration commit failed"))
}

fn column_exists(db: &rusqlite::Connection, table: &str, column: &str) -> Result<bool> {
    let mut statement = db
        .prepare(&format!("PRAGMA table_info({table})"))
        .map_err(|error| map_sqlite(error, "sharing schema inspection failed"))?;
    let columns = statement
        .query_map([], |row| row.get::<_, String>(1))
        .map_err(|error| map_sqlite(error, "sharing schema inspection query failed"))?;
    for stored in columns {
        if stored.map_err(|error| map_sqlite(error, "sharing schema column is invalid"))? == column
        {
            return Ok(true);
        }
    }
    Ok(false)
}

impl ReferenceServer {
    /// Accepts one opaque collection operation from its authenticated issuer.
    pub fn accept_collection_operation(
        &mut self,
        operation: &CollectionOperation,
    ) -> Result<RelayOutcome> {
        if let Some(stored) = self
            .db
            .query_row(
                "SELECT record FROM collection_operations
                  WHERE key_selector = ?1 AND issuer_vault_id = ?2
                    AND issuer_device_id = ?3 AND device_sequence = ?4",
                params![
                    operation.key_selector().as_bytes().as_slice(),
                    operation.issuer_identity_vault_id().as_bytes().as_slice(),
                    operation.issuer_device_id().as_bytes().as_slice(),
                    to_sqlite(
                        operation.device_sequence(),
                        "collection sequence does not fit"
                    )?,
                ],
                |row| row.get::<_, Vec<u8>>(0),
            )
            .optional()
            .map_err(|error| map_sqlite(error, "collection operation replay lookup failed"))?
        {
            ensure!(
                stored == operation.encode(),
                SyncChainFork,
                "collection operation replay differs"
            );
            return Ok(RelayOutcome::Duplicate);
        }
        let (collection_id, collection_epoch) =
            selector_collection(self, operation.key_selector())?;
        let state = collection_state(self, &collection_id)?.ok_or_else(|| {
            Error::new(ChurStatus::CatalogCorrupt, "selector collection is absent")
        })?;
        ensure!(
            state.collection_epoch() == collection_epoch,
            AuthenticationFailed,
            "collection selector is not current"
        );
        let issuer = relay::membership_state(&self.db, operation.issuer_identity_vault_id())?;
        let device = issuer.device(operation.issuer_device_id()).ok_or_else(|| {
            Error::new(
                ChurStatus::AuthenticationFailed,
                "collection operation issuer is unknown",
            )
        })?;
        ensure!(
            issuer.is_active(operation.issuer_device_id()),
            AuthenticationFailed,
            "collection operation issuer is revoked"
        );
        operation.verify_signature(device.signing_public_key())?;
        if operation.issuer_identity_vault_id() != state.source_vault_id() {
            ensure!(
                state.is_authorized(
                    operation.issuer_identity_vault_id(),
                    operation.issuer_device_id(),
                    PermissionProfile::Contribute
                ),
                AuthenticationFailed,
                "collection operation issuer cannot contribute"
            );
        }
        let previous: Option<(i64, Vec<u8>)> = self
            .db
            .query_row(
                "SELECT device_sequence, digest FROM collection_operations
              WHERE key_selector = ?1 AND issuer_vault_id = ?2 AND issuer_device_id = ?3
              ORDER BY device_sequence DESC LIMIT 1",
                params![
                    operation.key_selector().as_bytes().as_slice(),
                    operation.issuer_identity_vault_id().as_bytes().as_slice(),
                    operation.issuer_device_id().as_bytes().as_slice()
                ],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .optional()
            .map_err(|error| map_sqlite(error, "collection operation head lookup failed"))?;
        match previous {
            Some((sequence, digest)) => ensure!(
                to_sqlite(
                    operation.device_sequence(),
                    "collection sequence does not fit"
                )? == sequence + 1
                    && operation.previous_operation_hash().as_slice() == digest,
                SyncHeadRollback,
                "collection operation does not extend the relay head"
            ),
            None => ensure!(
                operation.device_sequence() == 1,
                SyncHeadRollback,
                "collection operation starts with a gap"
            ),
        }
        let reused: Option<Vec<u8>> = self.db.query_row(
            "SELECT record FROM collection_operations WHERE key_selector = ?1 AND operation_id = ?2",
            params![operation.key_selector().as_bytes().as_slice(), operation.operation_id().as_bytes().as_slice()],
            |row| row.get(0),
        ).optional().map_err(|error| map_sqlite(error, "collection operation identifier lookup failed"))?;
        ensure!(
            reused.is_none(),
            AuthenticationFailed,
            "collection operation identifier was reused"
        );
        for observed in operation.observed_heads() {
            let present: i64 = self
                .db
                .query_row(
                    "SELECT count(*) FROM collection_operations
                  WHERE key_selector = ?1 AND issuer_vault_id = ?2
                    AND issuer_device_id = ?3 AND device_sequence >= ?4",
                    params![
                        operation.key_selector().as_bytes().as_slice(),
                        observed.issuer_identity_vault_id().as_bytes().as_slice(),
                        observed.issuer_device_id().as_bytes().as_slice(),
                        to_sqlite(
                            observed.device_sequence(),
                            "observed collection sequence does not fit"
                        )?
                    ],
                    |row| row.get(0),
                )
                .map_err(|error| map_sqlite(error, "collection observed head lookup failed"))?;
            ensure!(
                present == 1,
                SyncHeadRollback,
                "collection operation has a missing cause"
            );
        }
        self.ensure_account_capacity(
            operation.issuer_identity_vault_id(),
            operation.encode().len(),
        )?;
        self.db
            .execute(
                "INSERT INTO collection_operations
                 (key_selector, issuer_vault_id, issuer_device_id, device_sequence,
                  operation_id, digest, record)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
                params![
                    operation.key_selector().as_bytes().as_slice(),
                    operation.issuer_identity_vault_id().as_bytes().as_slice(),
                    operation.issuer_device_id().as_bytes().as_slice(),
                    to_sqlite(
                        operation.device_sequence(),
                        "collection sequence does not fit"
                    )?,
                    operation.operation_id().as_bytes().as_slice(),
                    operation.digest().as_slice(),
                    operation.encode()
                ],
            )
            .map_err(|error| map_sqlite(error, "collection operation storage failed"))?;
        Ok(RelayOutcome::Stored)
    }

    /// Returns bounded opaque collection operations visible to one current device.
    pub fn collection_operations_for_recipient(
        &self,
        requester_vault_id: Id,
        requester_device_id: Id,
        key_selector: Id,
    ) -> Result<Vec<Vec<u8>>> {
        let (collection_id, collection_epoch) = selector_collection(self, &key_selector)?;
        let state = collection_state(self, &collection_id)?.ok_or_else(|| {
            Error::new(ChurStatus::CatalogCorrupt, "selector collection is absent")
        })?;
        ensure!(
            state.collection_epoch() == collection_epoch,
            AuthenticationFailed,
            "collection selector is not current"
        );
        if requester_vault_id == *state.source_vault_id() {
            ensure!(
                relay::membership_state(&self.db, &requester_vault_id)?
                    .is_active(&requester_device_id),
                AuthenticationFailed,
                "source requester is not active"
            );
        } else {
            ensure!(
                state.is_authorized(
                    &requester_vault_id,
                    &requester_device_id,
                    PermissionProfile::Read
                ),
                AuthenticationFailed,
                "requester cannot read this collection"
            );
            let grant_exists: i64 = self
                .db
                .query_row(
                    "SELECT count(*) FROM collection_grants AS grants
                  WHERE grants.collection_id = ?1 AND grants.collection_epoch = ?2
                    AND grants.recipient_vault_id = ?3 AND grants.recipient_device_id = ?4
                    AND grants.key_selector = ?5",
                    params![
                        collection_id.as_bytes().as_slice(),
                        to_sqlite(collection_epoch, "collection epoch does not fit")?,
                        requester_vault_id.as_bytes().as_slice(),
                        requester_device_id.as_bytes().as_slice(),
                        key_selector.as_bytes().as_slice()
                    ],
                    |row| row.get(0),
                )
                .map_err(|error| {
                    map_sqlite(error, "collection grant authorization lookup failed")
                })?;
            ensure!(
                grant_exists == 1,
                AuthenticationFailed,
                "requester has no current selector grant"
            );
        }
        let mut statement = self
            .db
            .prepare(
                "SELECT record FROM collection_operations WHERE key_selector = ?1
              ORDER BY issuer_vault_id, issuer_device_id, device_sequence LIMIT ?2",
            )
            .map_err(|error| map_sqlite(error, "collection operation inbox prepare failed"))?;
        let rows = statement
            .query_map(
                params![
                    key_selector.as_bytes().as_slice(),
                    i64::try_from(bounds::RESPONSE_OPERATIONS_MAX).unwrap_or(i64::MAX)
                ],
                |row| row.get::<_, Vec<u8>>(0),
            )
            .map_err(|error| map_sqlite(error, "collection operation inbox query failed"))?;
        let mut records = Vec::new();
        let mut bytes = 0;
        for row in rows {
            if !push_bounded(
                &mut records,
                &mut bytes,
                row.map_err(|error| map_sqlite(error, "collection operation inbox row failed"))?,
            )? {
                break;
            }
        }
        Ok(records)
    }

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
                    recipient_device_id, outer_device_id,
                    outer_device_sequence, record
                 ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
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
                    outer.device_id().as_bytes().as_slice(),
                    to_sqlite(
                        outer.device_sequence(),
                        "outer operation sequence does not fit"
                    )?,
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
                    recipient_vault_id, recipient_device_id, outer_device_id,
                    outer_device_sequence, key_selector, record
                 ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)",
                params![
                    grant.grant_id().as_bytes().as_slice(),
                    grant.collection_id().as_bytes().as_slice(),
                    to_sqlite(grant.collection_epoch(), "collection epoch does not fit")?,
                    outer.vault_id().as_bytes().as_slice(),
                    grant.recipient_identity_vault_id().as_bytes().as_slice(),
                    grant.recipient_device_id().as_bytes().as_slice(),
                    outer.device_id().as_bytes().as_slice(),
                    to_sqlite(
                        outer.device_sequence(),
                        "outer operation sequence does not fit"
                    )?,
                    outer.key_selector().as_bytes().as_slice(),
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
                    "SELECT sharing.record, operations.record
                       FROM collection_membership_records AS sharing
                       JOIN operations ON operations.vault_id = sharing.issuer_vault_id
                        AND operations.device_id = sharing.outer_device_id
                        AND operations.device_sequence = sharing.outer_device_sequence
                      WHERE sharing.collection_id = ?1
                        AND sharing.membership_generation <= ?2
                      ORDER BY sharing.membership_generation LIMIT ?3",
                )
                .map_err(|error| map_sqlite(error, "collection inbox prepare failed"))?;
            let rows = statement
                .query_map(
                    params![
                        collection_id.as_bytes().as_slice(),
                        cutoff,
                        i64::try_from(bounds::RESPONSE_OPERATIONS_MAX).unwrap_or(i64::MAX),
                    ],
                    |row| Ok((row.get::<_, Vec<u8>>(0)?, row.get::<_, Vec<u8>>(1)?)),
                )
                .map_err(|error| map_sqlite(error, "collection inbox query failed"))?;
            for row in rows {
                if !push_bounded(
                    &mut records,
                    &mut bytes,
                    row.map_err(|error| map_sqlite(error, "collection inbox row failed"))
                        .map(|(record, outer)| record_pair_bytes(&record, &outer))?,
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
                    "SELECT sharing.record, operations.record
                       FROM collection_grants AS sharing
                       JOIN operations ON operations.vault_id = sharing.issuer_vault_id
                        AND operations.device_id = sharing.outer_device_id
                        AND operations.device_sequence = sharing.outer_device_sequence
                      WHERE sharing.collection_id = ?1
                        AND sharing.recipient_vault_id = ?2
                        AND sharing.recipient_device_id = ?3
                      ORDER BY sharing.collection_epoch, sharing.grant_id LIMIT ?4",
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
                    |row| Ok((row.get::<_, Vec<u8>>(0)?, row.get::<_, Vec<u8>>(1)?)),
                )
                .map_err(|error| map_sqlite(error, "grant inbox query failed"))?;
            for row in rows {
                let (record, outer) =
                    row.map_err(|error| map_sqlite(error, "grant inbox row failed"))?;
                let grant = CollectionGrant::decode(&record).map_err(corrupt_sharing)?;
                if grant.collection_epoch() != state.collection_epoch()
                    || grant.collection_membership_generation() != membership_generation
                {
                    continue;
                }
                if !push_bounded(&mut records, &mut bytes, record_pair_bytes(&record, &outer))? {
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

fn selector_collection(server: &ReferenceServer, selector: &Id) -> Result<(Id, u64)> {
    let rows: Vec<(Vec<u8>, i64)> = {
        let mut statement = server
            .db
            .prepare(
                "SELECT DISTINCT collection_id, collection_epoch
                   FROM collection_grants WHERE key_selector = ?1",
            )
            .map_err(|error| map_sqlite(error, "collection selector lookup prepare failed"))?;
        statement
            .query_map([selector.as_bytes().as_slice()], |row| {
                Ok((row.get(0)?, row.get(1)?))
            })
            .map_err(|error| map_sqlite(error, "collection selector lookup failed"))?
            .collect::<rusqlite::Result<_>>()
            .map_err(|error| map_sqlite(error, "collection selector row failed"))?
    };
    ensure!(
        rows.len() == 1,
        AuthenticationFailed,
        "collection selector is unknown or ambiguous"
    );
    Ok((
        Id::from_slice(&rows[0].0).map_err(corrupt_sharing)?,
        u64::try_from(rows[0].1).map_err(|_| {
            Error::new(
                ChurStatus::CatalogCorrupt,
                "collection selector epoch is invalid",
            )
        })?,
    ))
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

fn record_pair_bytes(record: &[u8], outer: &[u8]) -> Vec<u8> {
    let mut bytes = Vec::with_capacity(4 + record.len() + outer.len());
    bytes.extend_from_slice(&(record.len() as u32).to_be_bytes());
    bytes.extend_from_slice(record);
    bytes.extend_from_slice(outer);
    bytes
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

    use chur_crypto::{Nonce, secret::Key};
    use chur_sync_protocol::collection_membership::{
        CollectionMembershipAction, CollectionMembershipRecord,
    };
    use chur_sync_protocol::collection_operation::CollectionOperation;
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
        let shared_operation = CollectionOperation::seal(
            id(30),
            source_vault,
            source_device,
            1,
            [0; 32],
            Vec::new(),
            *grant_outer.key_selector(),
            &Key::new([31; 32]),
            Nonce::new([32; 24]),
            b"opaque shared payload",
        )
        .expect("shared operation")
        .sign(&source_key);
        assert_eq!(
            server
                .accept_collection_operation(&shared_operation)
                .expect("shared operation"),
            RelayOutcome::Stored
        );
        assert_eq!(
            server
                .accept_collection_operation(&shared_operation)
                .expect("shared operation replay"),
            RelayOutcome::Duplicate
        );
        assert_eq!(
            server
                .collection_operations_for_recipient(
                    recipient_vault,
                    recipient_device,
                    *grant_outer.key_selector(),
                )
                .expect("shared operation inbox"),
            vec![shared_operation.encode()]
        );
        let unauthorized = CollectionOperation::seal(
            id(33),
            recipient_vault,
            recipient_device,
            1,
            [0; 32],
            Vec::new(),
            *grant_outer.key_selector(),
            &Key::new([31; 32]),
            Nonce::new([34; 24]),
            b"read-only author",
        )
        .expect("unauthorized operation")
        .sign(recipient.signing_key());
        assert!(
            server
                .accept_collection_operation(&unauthorized)
                .is_err_and(|error| error.status() == ChurStatus::AuthenticationFailed)
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
        assert_eq!(
            first_grants,
            vec![record_pair(&grant.encode(), &grant_outer.encode())]
        );
        assert!(
            grant
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
                record_pair(&membership.encode(), &membership_outer.encode()),
                record_pair(
                    &second_membership.encode(),
                    &second_membership_outer.encode()
                ),
                record_pair(&revocation.encode(), &revocation_outer.encode()),
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
                record_pair(&membership.encode(), &membership_outer.encode()),
                record_pair(
                    &second_membership.encode(),
                    &second_membership_outer.encode()
                ),
                record_pair(&revocation.encode(), &revocation_outer.encode()),
            ]
        );
        assert_eq!(
            server
                .collection_grants_for_recipient(second_vault, second_device)
                .expect("second grant inbox"),
            vec![record_pair(
                &current_grant.encode(),
                &current_grant_outer.encode()
            )]
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

    #[test]
    fn old_sharing_tables_gain_outer_operation_associations() {
        let root = crate::tests::TestRoot::new();
        let db = rusqlite::Connection::open(root.0.join("server.sqlite")).expect("old database");
        db.execute_batch(
            "CREATE TABLE collection_membership_records (
                 collection_id BLOB NOT NULL,
                 membership_generation INTEGER NOT NULL,
                 issuer_vault_id BLOB NOT NULL,
                 issuer_signing_public_key BLOB NOT NULL,
                 recipient_vault_id BLOB NOT NULL,
                 recipient_device_id BLOB NOT NULL,
                 record BLOB NOT NULL,
                 PRIMARY KEY(collection_id, membership_generation)
             );
             CREATE TABLE collection_grants (
                 grant_id BLOB PRIMARY KEY,
                 collection_id BLOB NOT NULL,
                 collection_epoch INTEGER NOT NULL,
                 issuer_vault_id BLOB NOT NULL,
                 recipient_vault_id BLOB NOT NULL,
                 recipient_device_id BLOB NOT NULL,
                 record BLOB NOT NULL
             );",
        )
        .expect("old schema");
        let source_key = DeviceSigningKey::from_seed([1; 32]);
        let recipient = DeviceIdentity::from_seeds([2; 32], [3; 32]);
        let membership = CollectionMembershipRecord::new(
            id(4),
            id(5),
            1,
            [0; 32],
            CollectionMembershipAction::Upsert(PermissionProfile::Read),
            id(6),
            id(7),
            recipient.signing_public_key(),
            recipient.hpke_public_key(),
            1,
            id(4),
            id(8),
            1,
            9,
        )
        .expect("membership")
        .sign(&source_key);
        db.execute(
            "INSERT INTO collection_membership_records VALUES (?1, 1, ?2, ?3, ?4, ?5, ?6)",
            params![
                id(5).as_bytes().as_slice(),
                id(4).as_bytes().as_slice(),
                source_key.verifying_key().as_slice(),
                id(6).as_bytes().as_slice(),
                id(7).as_bytes().as_slice(),
                membership.encode(),
            ],
        )
        .expect("old membership row");
        let grant = CollectionGrant::seal(
            id(10),
            id(4),
            id(5),
            1,
            1,
            id(6),
            id(7),
            &recipient.hpke_public_key(),
            id(8),
            PermissionProfile::Read,
            1,
            11,
            &Key::new([12; 32]),
            &source_key,
        )
        .expect("grant");
        db.execute(
            "INSERT INTO collection_grants VALUES (?1, ?2, 1, ?3, ?4, ?5, ?6)",
            params![
                id(10).as_bytes().as_slice(),
                id(5).as_bytes().as_slice(),
                id(4).as_bytes().as_slice(),
                id(6).as_bytes().as_slice(),
                id(7).as_bytes().as_slice(),
                grant.encode(),
            ],
        )
        .expect("old grant row");
        drop(db);

        let server = ReferenceServer::open(&root.0, 1_024, 65_536).expect("migrate");
        for table in ["collection_membership_records", "collection_grants"] {
            let mut statement = server
                .db
                .prepare(&format!("PRAGMA table_info({table})"))
                .expect("table info");
            let columns = statement
                .query_map([], |row| row.get::<_, String>(1))
                .expect("columns")
                .collect::<rusqlite::Result<Vec<_>>>()
                .expect("column rows");
            assert!(columns.iter().any(|column| column == "outer_device_id"));
            assert!(
                columns
                    .iter()
                    .any(|column| column == "outer_device_sequence")
            );
        }
        let membership_outer: (Vec<u8>, i64) = server
            .db
            .query_row(
                "SELECT outer_device_id, outer_device_sequence
                   FROM collection_membership_records",
                [],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .expect("migrated membership");
        assert_eq!(membership_outer, (id(8).as_bytes().to_vec(), 9));
        let grant_outer: (Vec<u8>, i64) = server
            .db
            .query_row(
                "SELECT outer_device_id, outer_device_sequence FROM collection_grants",
                [],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .expect("migrated grant");
        assert_eq!(grant_outer, (id(8).as_bytes().to_vec(), 11));
    }

    fn id(byte: u8) -> Id {
        Id::new([byte; 16]).expect("id")
    }

    fn record_pair(record: &[u8], outer: &[u8]) -> Vec<u8> {
        let mut bytes = Vec::with_capacity(4 + record.len() + outer.len());
        bytes.extend_from_slice(
            &u32::try_from(record.len())
                .expect("record length")
                .to_be_bytes(),
        );
        bytes.extend_from_slice(record);
        bytes.extend_from_slice(outer);
        bytes
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
