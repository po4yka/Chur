//! Disposable object-key delivery through authenticated public records.

use chur_core::{ChurStatus, Error, Id, Result, ensure};
use chur_sync_protocol::grant::PermissionProfile;
use chur_sync_protocol::object_grant::{ObjectKeyGrant, ObjectPrekey};
use chur_sync_protocol::state::DeviceStatus;
use rusqlite::{OptionalExtension, params};
use sha2::{Digest, Sha256};

use crate::{ReferenceServer, RelayOutcome, map_sqlite, relay, sharing};

const PAGE_MAX: usize = 64;

pub(super) fn create_schema(db: &rusqlite::Connection) -> Result<()> {
    db.execute_batch(
        "CREATE TABLE IF NOT EXISTS object_key_requests (
             prekey_id BLOB PRIMARY KEY CHECK(length(prekey_id) = 16),
             source_vault_id BLOB NOT NULL CHECK(length(source_vault_id) = 16),
             collection_id BLOB NOT NULL CHECK(length(collection_id) = 16),
             recipient_vault_id BLOB NOT NULL CHECK(length(recipient_vault_id) = 16),
             recipient_device_id BLOB NOT NULL CHECK(length(recipient_device_id) = 16),
             record BLOB NOT NULL
         );
         CREATE INDEX IF NOT EXISTS object_key_request_source
             ON object_key_requests(source_vault_id, prekey_id);
         CREATE TABLE IF NOT EXISTS object_key_dismissals (
             prekey_id BLOB NOT NULL REFERENCES object_key_requests(prekey_id) ON DELETE CASCADE,
             source_vault_id BLOB NOT NULL CHECK(length(source_vault_id) = 16),
             source_device_id BLOB NOT NULL CHECK(length(source_device_id) = 16),
             PRIMARY KEY(prekey_id, source_device_id)
         );
         CREATE INDEX IF NOT EXISTS object_key_dismissal_source
             ON object_key_dismissals(source_vault_id, source_device_id);
         CREATE TABLE IF NOT EXISTS object_key_grants (
             grant_id BLOB PRIMARY KEY CHECK(length(grant_id) = 16),
             prekey_id BLOB NOT NULL UNIQUE REFERENCES object_key_requests(prekey_id),
             source_vault_id BLOB NOT NULL CHECK(length(source_vault_id) = 16),
             source_device_id BLOB NOT NULL CHECK(length(source_device_id) = 16),
             recipient_vault_id BLOB NOT NULL CHECK(length(recipient_vault_id) = 16),
             recipient_device_id BLOB NOT NULL CHECK(length(recipient_device_id) = 16),
             record BLOB NOT NULL
         );
         CREATE INDEX IF NOT EXISTS object_key_grant_recipient
             ON object_key_grants(recipient_vault_id, recipient_device_id, grant_id);
         CREATE TABLE IF NOT EXISTS object_key_rejections (
             grant_id BLOB PRIMARY KEY CHECK(length(grant_id) = 16),
             prekey_id BLOB NOT NULL REFERENCES object_key_requests(prekey_id) ON DELETE CASCADE,
             source_vault_id BLOB NOT NULL CHECK(length(source_vault_id) = 16),
             source_device_id BLOB NOT NULL CHECK(length(source_device_id) = 16),
             recipient_vault_id BLOB NOT NULL CHECK(length(recipient_vault_id) = 16),
             recipient_device_id BLOB NOT NULL CHECK(length(recipient_device_id) = 16),
             grant_digest BLOB NOT NULL CHECK(length(grant_digest) = 32)
         );
         CREATE INDEX IF NOT EXISTS object_key_rejected_source
             ON object_key_rejections(prekey_id, source_device_id);
         CREATE TABLE IF NOT EXISTS object_key_receipts (
             prekey_id BLOB NOT NULL CHECK(length(prekey_id) = 16),
             grant_id BLOB PRIMARY KEY CHECK(length(grant_id) = 16),
             source_vault_id BLOB NOT NULL CHECK(length(source_vault_id) = 16),
             recipient_vault_id BLOB NOT NULL CHECK(length(recipient_vault_id) = 16),
             recipient_device_id BLOB NOT NULL CHECK(length(recipient_device_id) = 16),
             request_digest BLOB NOT NULL CHECK(length(request_digest) = 32),
             grant_digest BLOB NOT NULL CHECK(length(grant_digest) = 32),
             key_commitment BLOB NOT NULL CHECK(length(key_commitment) = 32)
         );
         CREATE INDEX IF NOT EXISTS object_key_receipt_request ON object_key_receipts(prekey_id);",
    )
    .map_err(|error| map_sqlite(error, "object key delivery schema creation failed"))
}

/// Membership changes retire unusable public deliveries in the same transaction.
/// An issuer change keeps recipient requests available for another source device.
pub(super) fn retire_device_deliveries(
    db: &rusqlite::Connection,
    vault: &Id,
    device: &Id,
) -> Result<()> {
    retire_reader_deliveries(db, vault, device, None)?;
    db.execute(
        "DELETE FROM object_key_rejections WHERE source_vault_id = ?1 AND source_device_id = ?2",
        params![vault.as_bytes().as_slice(), device.as_bytes().as_slice()],
    )
    .map_err(|error| map_sqlite(error, "inactive source quarantines could not be retired"))?;
    db.execute(
        "DELETE FROM object_key_dismissals WHERE source_vault_id = ?1 AND source_device_id = ?2",
        params![vault.as_bytes().as_slice(), device.as_bytes().as_slice()],
    )
    .map_err(|error| map_sqlite(error, "inactive source dismissals could not be retired"))?;
    db.execute(
        "DELETE FROM object_key_grants WHERE source_vault_id = ?1 AND source_device_id = ?2",
        params![vault.as_bytes().as_slice(), device.as_bytes().as_slice()],
    )
    .map_err(|error| map_sqlite(error, "inactive source deliveries could not be retired"))?;
    Ok(())
}

pub(super) fn retire_reader_deliveries(
    db: &rusqlite::Connection,
    vault: &Id,
    device: &Id,
    collection: Option<&Id>,
) -> Result<()> {
    let collection = collection.map(|id| id.as_bytes().as_slice());
    db.execute("DELETE FROM object_key_grants WHERE prekey_id IN (
        SELECT prekey_id FROM object_key_requests WHERE recipient_vault_id = ?1 AND recipient_device_id = ?2
        AND (?3 IS NULL OR collection_id = ?3))", params![vault.as_bytes().as_slice(), device.as_bytes().as_slice(), collection])
        .map_err(|error| map_sqlite(error, "inactive recipient grants could not be retired"))?;
    db.execute(
        "DELETE FROM object_key_requests WHERE recipient_vault_id = ?1 AND recipient_device_id = ?2
        AND (?3 IS NULL OR collection_id = ?3)",
        params![
            vault.as_bytes().as_slice(),
            device.as_bytes().as_slice(),
            collection
        ],
    )
    .map_err(|error| map_sqlite(error, "inactive recipient requests could not be retired"))?;
    Ok(())
}

impl ReferenceServer {
    /// Stores one signed public prekey for its currently authorized recipient.
    pub fn accept_object_key_request(
        &mut self,
        vault: Id,
        device: Id,
        prekey: &ObjectPrekey,
    ) -> Result<RelayOutcome> {
        ensure!(
            prekey.recipient_vault_id() == &vault && prekey.recipient_device_id() == &device,
            AuthenticationFailed,
            "object request transport does not match its recipient"
        );
        let source = request_source(self, prekey)?;
        let encoded = prekey.encode();
        let previous: Option<Vec<u8>> = self
            .db
            .query_row(
                "SELECT record FROM object_key_requests WHERE prekey_id = ?1",
                [prekey.prekey_id().as_bytes().as_slice()],
                |row| row.get(0),
            )
            .optional()
            .map_err(|error| map_sqlite(error, "object request replay lookup failed"))?;
        if let Some(previous) = previous {
            ensure!(
                previous == encoded,
                Conflict,
                "object request identifier was reused"
            );
            // A source that previously lacked the object may have caught up.
            // Put this retry behind existing work before making it visible to
            // those sources again. Unanswered retries keep their FIFO position.
            let transaction = self
                .db
                .transaction()
                .map_err(|error| map_sqlite(error, "object request retry transaction failed"))?;
            let changed = transaction
                .execute(
                    "UPDATE object_key_requests
                 SET rowid = (SELECT COALESCE(MAX(rowid), 0) + 1 FROM object_key_requests)
                 WHERE prekey_id = ?1 AND EXISTS (
                     SELECT 1 FROM object_key_dismissals WHERE prekey_id = ?1)
                 AND NOT EXISTS (SELECT 1 FROM object_key_grants WHERE prekey_id = ?1)",
                    [prekey.prekey_id().as_bytes().as_slice()],
                )
                .map_err(|error| map_sqlite(error, "object request retry queue failed"))?;
            if changed != 0 {
                transaction
                    .execute(
                        "DELETE FROM object_key_dismissals WHERE prekey_id = ?1",
                        [prekey.prekey_id().as_bytes().as_slice()],
                    )
                    .map_err(|error| {
                        map_sqlite(error, "object request attempts could not be reset")
                    })?;
            }
            transaction
                .commit()
                .map_err(|error| map_sqlite(error, "object request retry commit failed"))?;
            return Ok(if changed == 0 {
                RelayOutcome::Duplicate
            } else {
                RelayOutcome::Stored
            });
        }
        let receipt: Option<Vec<u8>> = self
            .db
            .query_row(
                "SELECT request_digest FROM object_key_receipts WHERE prekey_id = ?1 LIMIT 1",
                [prekey.prekey_id().as_bytes().as_slice()],
                |row| row.get(0),
            )
            .optional()
            .map_err(|error| map_sqlite(error, "object request receipt lookup failed"))?;
        if let Some(receipt) = receipt {
            ensure!(
                receipt == digest(&encoded),
                Conflict,
                "acknowledged object request identifier was reused"
            );
            // A genuine older backup can retain the private prekey after the
            // live recipient acknowledged and erased it. Exact public retries
            // must be deliverable again; the receipt still rejects ID changes.
        }
        self.ensure_account_capacity(&vault, encoded.len() as u64)?;
        self.db
            .execute(
                "INSERT INTO object_key_requests(prekey_id, source_vault_id, collection_id,
                 recipient_vault_id, recipient_device_id, record) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
                params![
                    prekey.prekey_id().as_bytes().as_slice(),
                    source.as_bytes().as_slice(),
                    prekey.collection_id().as_bytes().as_slice(),
                    vault.as_bytes().as_slice(),
                    device.as_bytes().as_slice(),
                    encoded
                ],
            )
            .map_err(|error| map_sqlite(error, "object request storage failed"))?;
        Ok(RelayOutcome::Stored)
    }

    /// Lists at most 64 ungranted requests for an active canonical source device.
    pub fn object_key_requests_for_source(&self, vault: Id, device: Id) -> Result<Vec<Vec<u8>>> {
        active_device_key(self, &vault, &device)?;
        let mut statement = self.db.prepare(
            "SELECT request.record FROM object_key_requests AS request
              WHERE request.source_vault_id = ?1 AND NOT EXISTS (
                  SELECT 1 FROM object_key_grants AS grant WHERE grant.prekey_id = request.prekey_id)
              AND NOT EXISTS (SELECT 1 FROM object_key_dismissals AS dismissal
                  WHERE dismissal.prekey_id = request.prekey_id AND dismissal.source_device_id = ?2)
              AND NOT EXISTS (SELECT 1 FROM object_key_rejections AS rejection
                  WHERE rejection.prekey_id = request.prekey_id AND rejection.source_device_id = ?2)
              ORDER BY request.rowid"
        ).map_err(|error| map_sqlite(error, "object request listing prepare failed"))?;
        let rows = statement
            .query_map(
                params![vault.as_bytes().as_slice(), device.as_bytes().as_slice()],
                |row| row.get::<_, Vec<u8>>(0),
            )
            .map_err(|error| map_sqlite(error, "object request listing failed"))?;
        let mut records = Vec::new();
        for row in rows {
            let bytes = row.map_err(|error| map_sqlite(error, "object request row failed"))?;
            // Older relays could admit noncontributory points. Invalid public
            // requests must not hold valid work behind a failed decoding step.
            let prekey = match ObjectPrekey::decode(&bytes) {
                Ok(prekey) => prekey,
                Err(_) => continue,
            };
            match request_source(self, &prekey) {
                Ok(source) if source == vault => records.push(bytes),
                Ok(_) => {
                    return Err(corrupt_record(Error::new(
                        ChurStatus::CatalogCorrupt,
                        "object request source differs",
                    )));
                }
                Err(error) if error.status() == ChurStatus::AuthenticationFailed => continue,
                Err(error) => return Err(error),
            }
            if records.len() == PAGE_MAX {
                break;
            }
        }
        Ok(records)
    }

    /// Stores a signed source response for the exact outstanding request.
    pub fn accept_object_key_grant(
        &mut self,
        vault: Id,
        device: Id,
        grant: &ObjectKeyGrant,
    ) -> Result<RelayOutcome> {
        ensure!(
            grant.source_vault_id() == &vault && grant.source_device_id() == &device,
            AuthenticationFailed,
            "object grant transport does not match its source"
        );
        authorize_grant(self, grant)?;
        let encoded = grant.encode();
        let rejected: bool = self
            .db
            .query_row(
                "SELECT EXISTS(SELECT 1 FROM object_key_rejections
             WHERE grant_id = ?1 OR (prekey_id = ?2 AND source_device_id = ?3))",
                params![
                    grant.grant_id().as_bytes().as_slice(),
                    grant.prekey_id().as_bytes().as_slice(),
                    device.as_bytes().as_slice()
                ],
                |row| row.get(0),
            )
            .map_err(|error| map_sqlite(error, "object grant quarantine lookup failed"))?;
        ensure!(
            !rejected,
            AuthenticationFailed,
            "recipient quarantined this source for the request"
        );
        let receipt: Option<Vec<u8>> = self
            .db
            .query_row(
                "SELECT grant_digest FROM object_key_receipts WHERE grant_id = ?1",
                [grant.grant_id().as_bytes().as_slice()],
                |row| row.get(0),
            )
            .optional()
            .map_err(|error| map_sqlite(error, "object grant receipt lookup failed"))?;
        if let Some(receipt) = receipt {
            ensure!(
                receipt == digest(&encoded),
                Conflict,
                "acknowledged object grant identifier was reused"
            );
            match self.object_key_request(grant.prekey_id()) {
                Ok(_) => {}
                Err(error) if error.status() == ChurStatus::NotFound => {
                    return Ok(RelayOutcome::Duplicate);
                }
                Err(error) => return Err(error),
            }
        }
        let accepted_key: Option<Vec<u8>> = self
            .db
            .query_row(
                "SELECT key_commitment FROM object_key_receipts WHERE prekey_id = ?1 LIMIT 1",
                [grant.prekey_id().as_bytes().as_slice()],
                |row| row.get(0),
            )
            .optional()
            .map_err(|error| map_sqlite(error, "acknowledged content key lookup failed"))?;
        if let Some(accepted_key) = accepted_key {
            ensure!(
                accepted_key == grant.object_key_commitment().as_slice(),
                AuthenticationFailed,
                "redelivered object grant changes its previously accepted content key"
            );
        }
        let previous: Option<Vec<u8>> = self
            .db
            .query_row(
                "SELECT record FROM object_key_grants WHERE grant_id = ?1",
                [grant.grant_id().as_bytes().as_slice()],
                |row| row.get(0),
            )
            .optional()
            .map_err(|error| map_sqlite(error, "object grant replay lookup failed"))?;
        if let Some(previous) = previous {
            ensure!(
                previous == encoded,
                Conflict,
                "object grant or request identifier was reused"
            );
            return Ok(RelayOutcome::Duplicate);
        }
        let request = self.object_key_request(grant.prekey_id())?;
        let prekey = ObjectPrekey::decode(&request).map_err(corrupt_record)?;
        ensure!(
            request_source(self, &prekey)? == vault
                && prekey.collection_id() == grant.collection_id()
                && prekey.object_id() == grant.object_id()
                && prekey.object_generation() == grant.object_generation()
                && prekey.container_commitment() == grant.container_commitment()
                && prekey.recipient_vault_id() == grant.recipient_vault_id()
                && prekey.recipient_device_id() == grant.recipient_device_id(),
            AuthenticationFailed,
            "object grant does not match its signed request"
        );
        let previous: Option<Vec<u8>> = self
            .db
            .query_row(
                "SELECT record FROM object_key_grants WHERE prekey_id = ?1",
                [grant.prekey_id().as_bytes().as_slice()],
                |row| row.get(0),
            )
            .optional()
            .map_err(|error| map_sqlite(error, "object grant replacement lookup failed"))?;
        if let Some(previous) = previous.as_ref() {
            let previous = ObjectKeyGrant::decode(previous).map_err(corrupt_record)?;
            ensure!(
                previous.object_key_commitment() == grant.object_key_commitment(),
                AuthenticationFailed,
                "replacement object grant changes the committed content key"
            );
        }
        self.ensure_account_capacity(
            &vault,
            encoded
                .len()
                .saturating_sub(previous.as_ref().map_or(0, Vec::len)) as u64,
        )?;
        let transaction = self
            .db
            .transaction()
            .map_err(|error| map_sqlite(error, "object grant replacement transaction failed"))?;
        transaction
            .execute(
                "DELETE FROM object_key_grants WHERE prekey_id = ?1",
                [grant.prekey_id().as_bytes().as_slice()],
            )
            .map_err(|error| map_sqlite(error, "previous object grant removal failed"))?;
        transaction.execute(
            "INSERT INTO object_key_grants(grant_id, prekey_id, source_vault_id, source_device_id,
                 recipient_vault_id, recipient_device_id, record) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            params![grant.grant_id().as_bytes().as_slice(), grant.prekey_id().as_bytes().as_slice(),
                vault.as_bytes().as_slice(), device.as_bytes().as_slice(),
                grant.recipient_vault_id().as_bytes().as_slice(), grant.recipient_device_id().as_bytes().as_slice(), encoded],
        ).map_err(|error| map_sqlite(error, "object grant storage failed"))?;
        transaction
            .commit()
            .map_err(|error| map_sqlite(error, "object grant replacement commit failed"))?;
        Ok(RelayOutcome::Stored)
    }

    /// Lists at most 64 grants addressed to this active, currently authorized reader.
    pub fn object_key_grants_for_recipient(&self, vault: Id, device: Id) -> Result<Vec<Vec<u8>>> {
        active_device_key(self, &vault, &device)?;
        let mut statement = self.db.prepare(
            "SELECT record FROM object_key_grants WHERE recipient_vault_id = ?1 AND recipient_device_id = ?2 ORDER BY grant_id"
        ).map_err(|error| map_sqlite(error, "object grant listing prepare failed"))?;
        let rows = statement
            .query_map(
                params![vault.as_bytes().as_slice(), device.as_bytes().as_slice()],
                |row| row.get::<_, Vec<u8>>(0),
            )
            .map_err(|error| map_sqlite(error, "object grant listing failed"))?;
        let mut records = Vec::new();
        for row in rows {
            let bytes = row.map_err(|error| map_sqlite(error, "object grant row failed"))?;
            let grant = ObjectKeyGrant::decode(&bytes).map_err(corrupt_record)?;
            ensure!(
                grant.recipient_vault_id() == &vault && grant.recipient_device_id() == &device,
                CatalogCorrupt,
                "stored object grant recipient differs"
            );
            match authorize_grant(self, &grant) {
                Ok(()) => records.push(bytes),
                Err(error) if error.status() == ChurStatus::AuthenticationFailed => continue,
                Err(error) => return Err(error),
            }
            if records.len() == PAGE_MAX {
                break;
            }
        }
        Ok(records)
    }

    /// Removes public delivery bytes after the addressed recipient durably accepts.
    pub fn acknowledge_object_key_grant(
        &mut self,
        vault: Id,
        device: Id,
        grant_id: &Id,
    ) -> Result<()> {
        active_device_key(self, &vault, &device)?;
        let bytes: Option<Vec<u8>> = self
            .db
            .query_row(
                "SELECT record FROM object_key_grants WHERE grant_id = ?1",
                [grant_id.as_bytes().as_slice()],
                |row| row.get(0),
            )
            .optional()
            .map_err(|error| map_sqlite(error, "object grant acknowledgement lookup failed"))?;
        let Some(bytes) = bytes else {
            return self.authorize_receipt(vault, device, grant_id);
        };
        let grant = ObjectKeyGrant::decode(&bytes).map_err(corrupt_record)?;
        ensure!(
            grant.recipient_vault_id() == &vault && grant.recipient_device_id() == &device,
            AuthenticationFailed,
            "only the addressed recipient acknowledges an object grant"
        );
        let request = self.object_key_request(grant.prekey_id())?;
        self.retire_object_key_request(
            &ObjectPrekey::decode(&request).map_err(corrupt_record)?,
            grant.source_vault_id(),
            (grant.grant_id(), &bytes),
            &request,
        )
    }

    /// Rejects one unusable delivery without accepting its content-key commitment.
    /// The issuer stays quarantined for this request across recipient retries.
    pub fn reject_object_key_grant(&mut self, vault: Id, device: Id, grant_id: &Id) -> Result<()> {
        active_device_key(self, &vault, &device)?;
        let bytes: Option<Vec<u8>> = self
            .db
            .query_row(
                "SELECT record FROM object_key_grants WHERE grant_id = ?1",
                [grant_id.as_bytes().as_slice()],
                |row| row.get(0),
            )
            .optional()
            .map_err(|error| map_sqlite(error, "rejected object grant lookup failed"))?;
        let Some(bytes) = bytes else {
            let recipient: Option<(Vec<u8>, Vec<u8>)> = self.db.query_row(
                "SELECT recipient_vault_id, recipient_device_id FROM object_key_rejections WHERE grant_id = ?1",
                [grant_id.as_bytes().as_slice()], |row| Ok((row.get(0)?, row.get(1)?)),
            ).optional().map_err(|error| map_sqlite(error, "object rejection replay lookup failed"))?;
            if let Some((recipient, recipient_device)) = recipient {
                ensure!(
                    recipient == vault.as_bytes() && recipient_device == device.as_bytes(),
                    AuthenticationFailed,
                    "object rejection belongs to another recipient"
                );
                return Ok(());
            }
            return self.authorize_receipt(vault, device, grant_id);
        };
        let grant = ObjectKeyGrant::decode(&bytes).map_err(corrupt_record)?;
        ensure!(
            grant.recipient_vault_id() == &vault && grant.recipient_device_id() == &device,
            AuthenticationFailed,
            "only the addressed recipient rejects an object grant"
        );
        let request = ObjectPrekey::decode(&self.object_key_request(grant.prekey_id())?)
            .map_err(corrupt_record)?;
        ensure!(
            request_source(self, &request)? == *grant.source_vault_id(),
            AuthenticationFailed,
            "object request has another source"
        );
        // A 128-byte public quarantine replaces the larger queued grant, so
        // rejection frees source quota and remains possible at the quota limit.
        self.ensure_account_capacity(
            grant.source_vault_id(),
            128u64.saturating_sub(bytes.len() as u64),
        )?;
        let transaction = self
            .db
            .transaction()
            .map_err(|error| map_sqlite(error, "object grant rejection transaction failed"))?;
        transaction.execute(
            "INSERT INTO object_key_rejections(grant_id, prekey_id, source_vault_id, source_device_id,
             recipient_vault_id, recipient_device_id, grant_digest) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            params![grant_id.as_bytes().as_slice(), grant.prekey_id().as_bytes().as_slice(),
                grant.source_vault_id().as_bytes().as_slice(), grant.source_device_id().as_bytes().as_slice(),
                vault.as_bytes().as_slice(), device.as_bytes().as_slice(), digest(&bytes).as_slice()],
        ).map_err(|error| map_sqlite(error, "object grant quarantine storage failed"))?;
        transaction
            .execute(
                "DELETE FROM object_key_grants WHERE grant_id = ?1",
                [grant_id.as_bytes().as_slice()],
            )
            .map_err(|error| map_sqlite(error, "rejected object grant removal failed"))?;
        transaction
            .commit()
            .map_err(|error| map_sqlite(error, "object grant rejection commit failed"))
    }

    /// Drops a delivery whose object is absent locally, without accepting a key
    /// or quarantining its issuer. An older backup can request the key again.
    pub fn discard_object_key_grant(&mut self, vault: Id, device: Id, grant_id: &Id) -> Result<()> {
        active_device_key(self, &vault, &device)?;
        let bytes: Option<Vec<u8>> = self
            .db
            .query_row(
                "SELECT record FROM object_key_grants WHERE grant_id = ?1",
                [grant_id.as_bytes().as_slice()],
                |row| row.get(0),
            )
            .optional()
            .map_err(|error| map_sqlite(error, "discarded object grant lookup failed"))?;
        let Some(bytes) = bytes else {
            return self.authorize_receipt(vault, device, grant_id);
        };
        let grant = ObjectKeyGrant::decode(&bytes).map_err(corrupt_record)?;
        ensure!(
            grant.recipient_vault_id() == &vault && grant.recipient_device_id() == &device,
            AuthenticationFailed,
            "only the addressed recipient discards an object grant"
        );
        let request = ObjectPrekey::decode(&self.object_key_request(grant.prekey_id())?)
            .map_err(corrupt_record)?;
        ensure!(
            request_source(self, &request)? == *grant.source_vault_id(),
            AuthenticationFailed,
            "discarded object request has another source"
        );
        let transaction = self
            .db
            .transaction()
            .map_err(|error| map_sqlite(error, "object grant discard transaction failed"))?;
        transaction
            .execute(
                "DELETE FROM object_key_grants WHERE prekey_id = ?1",
                [grant.prekey_id().as_bytes().as_slice()],
            )
            .map_err(|error| map_sqlite(error, "discarded object grant removal failed"))?;
        transaction
            .execute(
                "DELETE FROM object_key_requests WHERE prekey_id = ?1",
                [grant.prekey_id().as_bytes().as_slice()],
            )
            .map_err(|error| map_sqlite(error, "discarded object request removal failed"))?;
        transaction
            .commit()
            .map_err(|error| map_sqlite(error, "object grant discard commit failed"))
    }

    /// Dismisses an unavailable request for this active source device only.
    pub fn acknowledge_object_key_request(
        &mut self,
        vault: Id,
        device: Id,
        prekey_id: &Id,
    ) -> Result<()> {
        active_device_key(self, &vault, &device)?;
        let bytes = match self.object_key_request(prekey_id) {
            Ok(bytes) => bytes,
            Err(error) if error.status() == ChurStatus::NotFound => {
                return Ok(());
            }
            Err(error) => return Err(error),
        };
        let prekey = ObjectPrekey::decode(&bytes).map_err(corrupt_record)?;
        let source: Vec<u8> = self
            .db
            .query_row(
                "SELECT source_vault_id FROM object_key_requests WHERE prekey_id = ?1",
                [prekey.prekey_id().as_bytes().as_slice()],
                |row| row.get(0),
            )
            .map_err(|error| map_sqlite(error, "object request source lookup failed"))?;
        ensure!(
            source == vault.as_bytes(),
            AuthenticationFailed,
            "only the canonical source acknowledges an object request"
        );
        let granted: bool = self
            .db
            .query_row(
                "SELECT EXISTS(SELECT 1 FROM object_key_grants WHERE prekey_id = ?1)",
                [prekey_id.as_bytes().as_slice()],
                |row| row.get(0),
            )
            .map_err(|error| map_sqlite(error, "object request grant lookup failed"))?;
        ensure!(
            !granted,
            Conflict,
            "a granted request requires recipient acknowledgement"
        );
        let dismissed: bool = self.db.query_row(
            "SELECT EXISTS(SELECT 1 FROM object_key_dismissals WHERE prekey_id = ?1 AND source_device_id = ?2)",
            params![prekey_id.as_bytes().as_slice(), device.as_bytes().as_slice()], |row| row.get(0),
        ).map_err(|error| map_sqlite(error, "object request dismissal lookup failed"))?;
        if !dismissed {
            self.ensure_account_capacity(&vault, 48)?;
            self.db.execute(
                "INSERT INTO object_key_dismissals(prekey_id, source_vault_id, source_device_id) VALUES (?1, ?2, ?3)",
                params![prekey_id.as_bytes().as_slice(), vault.as_bytes().as_slice(), device.as_bytes().as_slice()],
            ).map_err(|error| map_sqlite(error, "object request dismissal storage failed"))?;
        }
        Ok(())
    }

    fn object_key_request(&self, prekey_id: &Id) -> Result<Vec<u8>> {
        self.db
            .query_row(
                "SELECT record FROM object_key_requests WHERE prekey_id = ?1",
                [prekey_id.as_bytes().as_slice()],
                |row| row.get(0),
            )
            .optional()
            .map_err(|error| map_sqlite(error, "object request lookup failed"))?
            .ok_or_else(|| Error::new(ChurStatus::NotFound, "object request is absent"))
    }

    fn retire_object_key_request(
        &mut self,
        prekey: &ObjectPrekey,
        source: &Id,
        grant: (&Id, &[u8]),
        request: &[u8],
    ) -> Result<()> {
        let key_commitment = *ObjectKeyGrant::decode(grant.1)
            .map_err(corrupt_record)?
            .object_key_commitment();
        let transaction = self.db.transaction().map_err(|error| {
            map_sqlite(error, "object delivery acknowledgement transaction failed")
        })?;
        transaction.execute(
            "INSERT INTO object_key_receipts(prekey_id, grant_id, source_vault_id, recipient_vault_id,
                 recipient_device_id, request_digest, grant_digest, key_commitment) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)
                 ON CONFLICT(grant_id) DO NOTHING",
            params![prekey.prekey_id().as_bytes().as_slice(), grant.0.as_bytes().as_slice(), source.as_bytes().as_slice(),
                prekey.recipient_vault_id().as_bytes().as_slice(), prekey.recipient_device_id().as_bytes().as_slice(),
                digest(request).as_slice(), digest(grant.1).as_slice(), key_commitment.as_slice()],
        ).map_err(|error| map_sqlite(error, "object delivery receipt storage failed"))?;
        transaction
            .execute(
                "DELETE FROM object_key_grants WHERE prekey_id = ?1",
                [prekey.prekey_id().as_bytes().as_slice()],
            )
            .map_err(|error| map_sqlite(error, "acknowledged object grant removal failed"))?;
        transaction
            .execute(
                "DELETE FROM object_key_requests WHERE prekey_id = ?1",
                [prekey.prekey_id().as_bytes().as_slice()],
            )
            .map_err(|error| map_sqlite(error, "acknowledged object request removal failed"))?;
        transaction
            .commit()
            .map_err(|error| map_sqlite(error, "object delivery acknowledgement commit failed"))
    }

    fn authorize_receipt(&self, vault: Id, device: Id, grant_id: &Id) -> Result<()> {
        let receipt: Option<(Vec<u8>, Vec<u8>)> = self.db.query_row(
            "SELECT recipient_vault_id, recipient_device_id FROM object_key_receipts WHERE grant_id = ?1",
            [grant_id.as_bytes().as_slice()], |row| Ok((row.get(0)?, row.get(1)?)),
        ).optional().map_err(|error| map_sqlite(error, "object delivery receipt authorization failed"))?;
        if let Some((recipient, recipient_device)) = receipt {
            ensure!(
                recipient == vault.as_bytes() && recipient_device == device.as_bytes(),
                AuthenticationFailed,
                "object delivery receipt belongs to another principal"
            );
        }
        Ok(())
    }
}

fn request_source(server: &ReferenceServer, prekey: &ObjectPrekey) -> Result<Id> {
    let key = active_device_key(
        server,
        prekey.recipient_vault_id(),
        prekey.recipient_device_id(),
    )?;
    prekey.verify(&key)?;
    let Some(state) = sharing::collection_state(server, prekey.collection_id())? else {
        // Private collection declarations are encrypted in the ordinary vault log.
        // Without sharing evidence, a request can name only its own vault.
        return Ok(*prekey.recipient_vault_id());
    };
    if state.source_vault_id() == prekey.recipient_vault_id() {
        return Ok(*prekey.recipient_vault_id());
    }
    ensure!(
        state.is_authorized(
            prekey.recipient_vault_id(),
            prekey.recipient_device_id(),
            PermissionProfile::Read
        ),
        AuthenticationFailed,
        "object request recipient has no current read access"
    );
    ensure!(
        state
            .recipient_pin(prekey.recipient_vault_id(), prekey.recipient_device_id())
            .is_some_and(|pin| pin.signing_public_key() == &key),
        AuthenticationFailed,
        "object recipient key pin is not current"
    );
    Ok(*state.source_vault_id())
}

fn authorize_grant(server: &ReferenceServer, grant: &ObjectKeyGrant) -> Result<()> {
    let key = active_device_key(server, grant.source_vault_id(), grant.source_device_id())?;
    grant.verify(&key)?;
    active_device_key(
        server,
        grant.recipient_vault_id(),
        grant.recipient_device_id(),
    )?;
    let state = sharing::collection_state(server, grant.collection_id())?;
    if grant.source_vault_id() == grant.recipient_vault_id() {
        ensure!(
            state
                .as_ref()
                .is_none_or(|state| state.source_vault_id() == grant.source_vault_id()),
            AuthenticationFailed,
            "own object grant cannot replace another vault's source"
        );
        return Ok(());
    }
    let state = state.ok_or_else(unauthorized)?;
    ensure!(
        state.source_vault_id() == grant.source_vault_id()
            && state.is_authorized(
                grant.recipient_vault_id(),
                grant.recipient_device_id(),
                PermissionProfile::Read
            ),
        AuthenticationFailed,
        "object grant is not from the canonical source to a current reader"
    );
    Ok(())
}

fn active_device_key(server: &ReferenceServer, vault: &Id, device: &Id) -> Result<[u8; 32]> {
    let membership = relay::membership_state(&server.db, vault)?;
    let member = membership.device(device).ok_or_else(unauthorized)?;
    ensure!(
        member.status() == DeviceStatus::Active,
        AuthenticationFailed,
        "object delivery device is revoked"
    );
    Ok(*member.signing_public_key())
}

fn unauthorized() -> Error {
    Error::new(
        ChurStatus::AuthenticationFailed,
        "object delivery principal is not authorized",
    )
}
fn corrupt_record(_: Error) -> Error {
    Error::new(
        ChurStatus::CatalogCorrupt,
        "stored object delivery record is invalid",
    )
}
fn digest(bytes: &[u8]) -> [u8; 32] {
    Sha256::digest(bytes).into()
}

#[cfg(test)]
mod tests {
    #![allow(clippy::expect_used, clippy::unwrap_used)]

    use axum::body::{Body, to_bytes};
    use axum::http::{Request, StatusCode};
    use chur_core::Id;
    use chur_crypto::Key;
    use chur_sync_protocol::collection_membership::{
        CollectionMembershipAction, CollectionMembershipRecord,
    };
    use chur_sync_protocol::grant::PermissionProfile;
    use chur_sync_protocol::identity::DeviceIdentity;
    use chur_sync_protocol::membership::EnrollmentRecord;
    use chur_sync_protocol::object_grant::{ObjectKeyGrant, ObjectPrekey};
    use chur_sync_protocol::operation::{DeviceSigningKey, Operation};
    use tower::ServiceExt;

    use crate::{ReferenceServer, http};

    fn id(value: u8) -> Id {
        Id::new([value; 16]).unwrap()
    }

    fn operation(
        vault: Id,
        device: Id,
        operation_id: Id,
        sequence: u64,
        previous: [u8; 32],
        key: &DeviceSigningKey,
    ) -> Operation {
        Operation::new(
            operation_id,
            vault,
            device,
            sequence,
            previous,
            Vec::new(),
            id(90),
            [vec![91; 24], vec![92; 16]].concat(),
            [0; 64],
        )
        .unwrap()
        .sign(key)
    }

    struct Fixture {
        root: crate::tests::TestRoot,
        server: ReferenceServer,
        source: DeviceIdentity,
        recipient: DeviceIdentity,
        membership: CollectionMembershipRecord,
        membership_outer: Operation,
    }

    fn fixture() -> Fixture {
        let root = crate::tests::TestRoot::new();
        let mut server = ReferenceServer::open(&root.0, 1_024, 1_048_576).unwrap();
        let source = DeviceIdentity::from_seeds([3; 32], [4; 32]);
        let recipient = DeviceIdentity::from_seeds([7; 32], [8; 32]);
        let mut source_initial = None;
        for (vault, device, identity, token, operation_id) in [
            (id(1), id(2), &source, [10; 32], id(11)),
            (id(5), id(6), &recipient, [12; 32], id(13)),
        ] {
            let enrollment = EnrollmentRecord::initial(
                vault,
                device,
                identity.signing_public_key(),
                identity.hpke_public_key(),
            )
            .unwrap()
            .sign(identity.signing_key());
            let initial = operation(
                vault,
                device,
                operation_id,
                1,
                [0; 32],
                identity.signing_key(),
            );
            server
                .accept_initial_membership(&enrollment, &initial)
                .unwrap();
            server.set_transport_token(vault, device, &token).unwrap();
            if vault == id(1) {
                source_initial = Some(initial);
            }
        }
        let membership = CollectionMembershipRecord::new(
            id(1),
            id(14),
            1,
            [0; 32],
            CollectionMembershipAction::Upsert(PermissionProfile::Read),
            id(5),
            id(6),
            recipient.signing_public_key(),
            recipient.hpke_public_key(),
            1,
            id(1),
            id(2),
            1,
            2,
        )
        .unwrap()
        .sign(source.signing_key());
        let membership_outer = operation(
            id(1),
            id(2),
            id(15),
            2,
            source_initial.unwrap().digest(),
            source.signing_key(),
        );
        server
            .accept_collection_membership(&membership, &membership_outer)
            .unwrap();
        Fixture {
            root,
            server,
            source,
            recipient,
            membership,
            membership_outer,
        }
    }

    // Actual Ed25519-signed test records for this fixture's recipient seed7.
    // Only the disposable X25519 point is noncontributory (zero or one).
    fn signed_invalid_prekeys() -> [Vec<u8>; 2] {
        [hex::decode("0001141414141414141414141414141414140e0e0e0e0e0e0e0e0e0e0e0e0e0e0e0e63636363636363636363636363636363000000000000000111111111111111111111111111111111111111111111111111111111111111110505050505050505050505050505050506060606060606060606060606060606000000000000000100000000000000000000000000000000000000000000000000000000000000001cf1bb0579ff644a941f34502a170ec8be959b7b3a36cfff9b42686704dcc014047a4a681e7ff226309ccb8035025178ad29cde09e4774aed2c6d58ef007180c").unwrap(),
         hex::decode("0001141414141414141414141414141414140e0e0e0e0e0e0e0e0e0e0e0e0e0e0e0e63636363636363636363636363636363000000000000000111111111111111111111111111111111111111111111111111111111111111110505050505050505050505050505050506060606060606060606060606060606000000000000000101000000000000000000000000000000000000000000000000000000000000007c680cf8840461ed4f9abbc8170fbe07ecf7004d7891a2ae66e156e0c251edae05e6ce71001910dbc2ead74703893414853023303a2e774198c1903e637ca90d").unwrap()]
    }

    fn request(recipient: &DeviceIdentity, number: u8) -> ObjectPrekey {
        ObjectPrekey::sign(
            id(number),
            id(14),
            id(16),
            1,
            [17; 32],
            id(5),
            id(6),
            1,
            &Key::new([number; 32]),
            recipient.signing_key(),
        )
        .unwrap()
    }

    fn grant(f: &Fixture, prekey: &ObjectPrekey, number: u8) -> ObjectKeyGrant {
        ObjectKeyGrant::seal(
            id(number),
            id(1),
            id(2),
            *prekey.object_id(),
            prekey.object_generation(),
            *prekey.container_commitment(),
            prekey,
            &Key::new([18; 32]),
            f.source.signing_key(),
        )
        .unwrap()
    }

    #[test]
    fn object_key_delivery_survives_restart_and_supports_exact_redelivery() {
        let mut f = fixture();
        let prekey = request(&f.recipient, 20);
        assert_eq!(
            f.server
                .accept_object_key_request(id(5), id(6), &prekey)
                .unwrap(),
            crate::RelayOutcome::Stored
        );
        assert_eq!(
            f.server
                .accept_object_key_request(id(5), id(6), &prekey)
                .unwrap(),
            crate::RelayOutcome::Duplicate
        );
        assert_eq!(
            f.server
                .object_key_requests_for_source(id(1), id(2))
                .unwrap(),
            vec![prekey.encode()]
        );
        let grant = grant(&f, &prekey, 21);
        assert_eq!(
            f.server
                .accept_object_key_grant(id(1), id(2), &grant)
                .unwrap(),
            crate::RelayOutcome::Stored
        );
        assert_eq!(
            f.server
                .accept_object_key_grant(id(1), id(2), &grant)
                .unwrap(),
            crate::RelayOutcome::Duplicate
        );
        assert!(
            f.server
                .object_key_requests_for_source(id(1), id(2))
                .unwrap()
                .is_empty()
        );
        drop(f.server);
        let mut server = ReferenceServer::open(&f.root.0, 1_024, 1_048_576).unwrap();
        let delivered = server
            .object_key_grants_for_recipient(id(5), id(6))
            .unwrap();
        assert_eq!(delivered, vec![grant.encode()]);
        let opened = ObjectKeyGrant::decode(&delivered[0])
            .unwrap()
            .open(&prekey, &Key::new([20; 32]), &f.source.signing_public_key())
            .unwrap();
        assert!(opened == Key::new([18; 32]));
        assert_eq!(
            server
                .acknowledge_object_key_grant(id(1), id(2), grant.grant_id())
                .unwrap_err()
                .status(),
            chur_core::ChurStatus::AuthenticationFailed
        );
        server
            .acknowledge_object_key_grant(id(5), id(6), grant.grant_id())
            .unwrap();
        server
            .acknowledge_object_key_grant(id(5), id(6), grant.grant_id())
            .unwrap();
        assert!(
            server
                .object_key_grants_for_recipient(id(5), id(6))
                .unwrap()
                .is_empty()
        );
        assert_eq!(
            server
                .accept_object_key_request(id(5), id(6), &prekey)
                .unwrap(),
            crate::RelayOutcome::Stored
        );
        assert_eq!(
            server
                .accept_object_key_grant(id(1), id(2), &grant)
                .unwrap(),
            crate::RelayOutcome::Stored
        );
        assert!(
            server
                .object_key_requests_for_source(id(1), id(2))
                .unwrap()
                .is_empty()
        );
        server
            .acknowledge_object_key_grant(id(5), id(6), grant.grant_id())
            .unwrap();
        let rows: (i64, i64) = server.db.query_row("SELECT (SELECT count(*) FROM object_key_requests), (SELECT count(*) FROM object_key_grants)", [], |row| Ok((row.get(0)?, row.get(1)?))).unwrap();
        assert_eq!(rows, (0, 0));
    }

    #[test]
    fn a_revoked_grant_issuer_does_not_strand_the_pending_native_request() {
        native_source_recovery(0);
    }

    #[test]
    fn a_missing_sources_ack_does_not_starve_another_native_source_with_the_key() {
        native_source_recovery(1);
    }

    #[test]
    fn a_wrong_key_grant_can_be_rejected_without_poisoning_a_healthy_sources_key() {
        native_source_recovery(2);
    }

    #[test]
    fn an_invalid_wrapped_grant_can_be_rejected_without_erasing_the_live_prekey() {
        native_source_recovery(3);
    }

    fn native_source_recovery(scenario: u8) {
        let missing_source = scenario == 1;
        let rejecting_grant = scenario >= 2;
        use chur_catalog::{
            object_grants, paths::VaultRoot, sharing_publication, sharing_receive, sharing_service,
            store, sync_keys, sync_log, sync_membership, sync_receive, vault,
        };
        use chur_media::{backup, import, progress::Uninterrupted};
        use chur_sync_protocol::object_grant::object_key_commitment;
        let directory = crate::tests::TestRoot::new();
        let source_path = VaultRoot::new(directory.0.join("source"));
        let recipient_path = VaultRoot::new(directory.0.join("recipient"));
        let password = b"genuine pending prekey backup password";
        let now = 1_700_000_000_000;
        let mut source = vault::create(&source_path, password, now)
            .unwrap()
            .activate()
            .unwrap();
        let mut recipient = vault::create(&recipient_path, password, now)
            .unwrap()
            .activate()
            .unwrap();
        let source_root = source.root_secret().unwrap().duplicate();
        let recipient_root = recipient.root_secret().unwrap().duplicate();
        let source_vault = source.vault_id();
        let recipient_vault = recipient.vault_id();
        let (source_enrollment, source_initial) = sync_receive::provision_local_identity(
            source.catalog().unwrap(),
            &source_root,
            source_vault,
        )
        .unwrap();
        let (recipient_enrollment, recipient_initial) = sync_receive::provision_local_identity(
            recipient.catalog().unwrap(),
            &recipient_root,
            recipient_vault,
        )
        .unwrap();
        let photo = vec![42u8; 4_096];
        let running = import::begin(
            &mut source,
            import::SourceCapability {
                requested_object_id: None,
                seekable: true,
                known_length: Some(photo.len() as u64),
                content_type_hint: "image/jpeg".into(),
                original_filename: Some("photo.jpg".into()),
                capture_time_ms: None,
            },
            import::CanonicalMedia {
                media_class: chur_format::constants::MediaClass::Image,
                width: 64,
                height: 64,
                duration_ms: 0,
            },
            now,
        )
        .unwrap();
        let object_id = import::stream_into(
            running,
            &mut source,
            &mut std::io::Cursor::new(&photo),
            "image/jpeg",
            now,
            &mut Uninterrupted,
        )
        .unwrap();
        let collection = store::object(source.catalog_ref().unwrap(), &object_id)
            .unwrap()
            .collection_id;
        let recipient_memberships = [sharing_service::IssuerMembershipRecord::Enrollment(
            recipient_enrollment.clone(),
        )];
        let recipient_operations = [recipient_initial.clone()];
        let prepared = sharing_service::prepare_share_for_device(
            source.catalog().unwrap(),
            &source_root,
            source_vault,
            collection,
            sharing_service::IssuerEvidence {
                membership: &recipient_memberships,
                operations: &recipient_operations,
            },
            *recipient_enrollment.device_id(),
            PermissionProfile::Read,
            true,
        )
        .unwrap();
        let candidate =
            sharing_publication::candidate(source.catalog_ref().unwrap(), &collection, &object_id)
                .unwrap();
        let publication = sharing_publication::publish_page(
            source.catalog().unwrap(),
            &source_root,
            source_vault,
            collection,
            &[candidate],
        )
        .unwrap()
        .remove(0);
        let source_memberships = [sharing_service::IssuerMembershipRecord::Enrollment(
            source_enrollment.clone(),
        )];
        let source_operations: Vec<_> = sync_log::records_after(
            source.catalog_ref().unwrap(),
            source_enrollment.device_id(),
            0,
        )
        .unwrap()
        .iter()
        .map(|record| Operation::decode(record).unwrap())
        .collect();
        let evidence = [sharing_service::IssuerEvidence {
            membership: &source_memberships,
            operations: &source_operations,
        }];
        sharing_receive::receive(
            recipient.catalog().unwrap(),
            &recipient_root,
            recipient_vault,
            &evidence,
            &[(
                prepared.membership().clone(),
                prepared.membership_operation().clone(),
            )],
            prepared.grant(),
            prepared.grant_operation(),
            &[publication.create_operation, publication.commit_operation],
        )
        .unwrap();
        let prekey = object_grants::request(
            recipient.catalog().unwrap(),
            &recipient_root,
            recipient_vault,
            collection,
            object_id,
        )
        .unwrap();
        let mut package = std::io::Cursor::new(Vec::new());
        backup::create(&mut recipient, &mut package, now, &mut Uninterrupted).unwrap();
        let mut missing_replica = if missing_source {
            let mut snapshot = std::io::Cursor::new(Vec::new());
            backup::create(&mut source, &mut snapshot, now, &mut Uninterrupted).unwrap();
            snapshot.set_position(0);
            let restored = VaultRoot::new(directory.0.join("missing-source"));
            backup::restore(&restored, &mut snapshot, password, &mut Uninterrupted).unwrap();
            let mut restored = vault::unlock_with_password(&restored, password, now + 1).unwrap();
            chur_catalog::deletion::begin(restored.catalog().unwrap(), &object_id).unwrap();
            chur_catalog::deletion::erase(restored.catalog().unwrap(), &object_id, now).unwrap();
            assert!(chur_media::keys::object_key(&restored, &object_id).is_err());
            Some(restored)
        } else {
            None
        };
        let mut server =
            ReferenceServer::open(directory.0.join("server"), 65_536, 1_048_576).unwrap();
        server
            .accept_initial_membership(&source_enrollment, &source_initial)
            .unwrap();
        server
            .accept_initial_membership(&recipient_enrollment, &recipient_initial)
            .unwrap();
        server
            .accept_collection_membership(prepared.membership(), prepared.membership_operation())
            .unwrap();
        server
            .accept_collection_grant(prepared.grant(), prepared.grant_operation())
            .unwrap();
        server
            .accept_object_key_request(recipient_vault, *recipient_enrollment.device_id(), &prekey)
            .unwrap();
        let mut source_membership = sync_membership::load(source.catalog_ref().unwrap())
            .unwrap()
            .unwrap();
        let (_, original_identity) = sync_keys::local_identity(
            source.catalog_ref().unwrap(),
            &source_root,
            &source_membership,
        )
        .unwrap()
        .unwrap();
        let mut rejected = None;
        if !missing_source {
            let grant = if rejecting_grant {
                let key = if scenario == 2 {
                    chur_crypto::Key::new([99; 32])
                } else {
                    chur_media::keys::object_key(&source, &object_id).unwrap()
                };
                let alternate;
                let recipient_prekey = if scenario == 3 {
                    let membership = sync_membership::load(recipient.catalog_ref().unwrap())
                        .unwrap()
                        .unwrap();
                    let (_, identity) = sync_keys::local_identity(
                        recipient.catalog_ref().unwrap(),
                        &recipient_root,
                        &membership,
                    )
                    .unwrap()
                    .unwrap();
                    alternate = ObjectPrekey::sign(
                        *prekey.prekey_id(),
                        collection,
                        object_id,
                        candidate.object_generation,
                        candidate.container_commitment,
                        recipient_vault,
                        *recipient_enrollment.device_id(),
                        prekey.generation(),
                        &chur_crypto::Key::new([101; 32]),
                        identity.signing_key(),
                    )
                    .unwrap();
                    &alternate
                } else {
                    &prekey
                };
                ObjectKeyGrant::seal(
                    chur_crypto::random::id().unwrap(),
                    source_vault,
                    *source_enrollment.device_id(),
                    object_id,
                    candidate.object_generation,
                    candidate.container_commitment,
                    recipient_prekey,
                    &key,
                    original_identity.signing_key(),
                )
                .unwrap()
            } else {
                object_grants::issue(
                    source.catalog().unwrap(),
                    &source_root,
                    source_vault,
                    &prekey,
                )
                .unwrap()
            };
            server
                .accept_object_key_grant(source_vault, *source_enrollment.device_id(), &grant)
                .unwrap();
            if rejecting_grant {
                rejected = Some(grant);
            }
        }
        let survivor = DeviceIdentity::generate().unwrap();
        let survivor_id = chur_crypto::random::id().unwrap();
        let mut source_log =
            sync_log::load(source.catalog_ref().unwrap(), &source_membership).unwrap();
        let checkpoint = source_log
            .issue_own_checkpoint(
                source.catalog().unwrap(),
                &source_membership,
                source_enrollment.device_id(),
                original_identity.signing_key(),
                now + 1,
            )
            .unwrap();
        let enrollment = EnrollmentRecord::new(
            source_vault,
            survivor_id,
            survivor.signing_public_key(),
            survivor.hpke_public_key(),
            4,
            *source_enrollment.device_id(),
            2,
            *source_membership.commitment(),
            checkpoint.commitment(),
        )
        .unwrap()
        .sign(original_identity.signing_key());
        let domain = chur_sync_protocol::KeyDomain::root(&source_root, &source_vault).unwrap();
        let enrollment_outer = sync_receive::author_membership_operation(
            source.catalog().unwrap(),
            &mut source_log,
            &mut source_membership,
            &domain,
            *source_enrollment.device_id(),
            original_identity.signing_key(),
            chur_sync_protocol::payload::PayloadBody::AddDevice(enrollment.clone()),
        )
        .unwrap();
        let envelope = chur_sync_protocol::identity::DeviceIdentityEnvelope::seal_for_local(
            &source_root,
            source_vault,
            survivor_id,
            1,
            chur_crypto::Nonce::random().unwrap(),
            &survivor,
        )
        .unwrap();
        sync_keys::store_local_identity_envelope(
            source.catalog().unwrap(),
            &source_root,
            &source_membership,
            &envelope,
        )
        .unwrap();
        server.accept_checkpoint(&checkpoint).unwrap();
        server
            .accept_enrollment(&enrollment, &enrollment_outer)
            .unwrap();
        if missing_source {
            let missing = missing_replica.as_mut().unwrap();
            assert_eq!(
                object_grants::issue(
                    missing.catalog().unwrap(),
                    &source_root,
                    source_vault,
                    &prekey
                )
                .err()
                .unwrap()
                .status(),
                chur_core::ChurStatus::NotFound
            );
            server
                .acknowledge_object_key_request(
                    source_vault,
                    *source_enrollment.device_id(),
                    prekey.prekey_id(),
                )
                .unwrap();
            assert!(
                server
                    .object_key_requests_for_source(source_vault, *source_enrollment.device_id())
                    .unwrap()
                    .is_empty()
            );
        } else if rejecting_grant {
            let rejected = rejected.as_ref().unwrap();
            assert_eq!(
                object_grants::accept(
                    recipient.catalog().unwrap(),
                    &recipient_root,
                    recipient_vault,
                    &evidence,
                    rejected
                )
                .unwrap_err()
                .status(),
                chur_core::ChurStatus::AuthenticationFailed
            );
            let private_count: i64 = recipient.catalog().unwrap().transaction(|tx| Ok(tx.query_row(
                "SELECT count(*) FROM object_key_requests WHERE prekey_id = ?1 AND length(private_key) = 32",
                [prekey.prekey_id().as_bytes().as_slice()], |row| row.get(0)).unwrap())).unwrap();
            assert_eq!(
                private_count, 1,
                "a failed grant must preserve its live private prekey"
            );
            server
                .reject_object_key_grant(
                    recipient_vault,
                    *recipient_enrollment.device_id(),
                    rejected.grant_id(),
                )
                .unwrap();
            server
                .reject_object_key_grant(
                    recipient_vault,
                    *recipient_enrollment.device_id(),
                    rejected.grant_id(),
                )
                .unwrap();
            server
                .accept_object_key_request(
                    recipient_vault,
                    *recipient_enrollment.device_id(),
                    &prekey,
                )
                .unwrap();
            assert!(
                server
                    .accept_object_key_grant(source_vault, *source_enrollment.device_id(), rejected)
                    .is_err(),
                "recipient retries must not lift a bad issuer quarantine"
            );
            assert!(
                server
                    .object_key_requests_for_source(source_vault, *source_enrollment.device_id())
                    .unwrap()
                    .is_empty()
            );
            let fresh_bad = ObjectKeyGrant::seal(
                chur_crypto::random::id().unwrap(),
                source_vault,
                *source_enrollment.device_id(),
                object_id,
                candidate.object_generation,
                candidate.container_commitment,
                &prekey,
                &chur_crypto::Key::new([99; 32]),
                original_identity.signing_key(),
            )
            .unwrap();
            assert!(
                server
                    .accept_object_key_grant(
                        source_vault,
                        *source_enrollment.device_id(),
                        &fresh_bad
                    )
                    .is_err(),
                "a fresh grant identifier must not bypass request-scoped issuer quarantine"
            );
        } else {
            let revocation = chur_sync_protocol::membership::RevocationRecord::new(
                source_vault,
                *source_enrollment.device_id(),
                4,
                enrollment_outer.digest(),
                3,
                survivor_id,
                *source_membership.commitment(),
            )
            .unwrap()
            .sign(survivor.signing_key());
            let revocation_outer = sync_receive::author_membership_operation(
                source.catalog().unwrap(),
                &mut source_log,
                &mut source_membership,
                &domain,
                survivor_id,
                survivor.signing_key(),
                chur_sync_protocol::payload::PayloadBody::RevokeDevice(revocation.clone()),
            )
            .unwrap();
            server
                .accept_revocation(&revocation, &revocation_outer)
                .unwrap();
        }
        assert!(
            server
                .object_key_grants_for_recipient(recipient_vault, *recipient_enrollment.device_id())
                .unwrap()
                .is_empty()
        );
        assert_eq!(
            server
                .object_key_requests_for_source(source_vault, survivor_id)
                .unwrap(),
            vec![prekey.encode()],
            "another active source sees the original request despite the first source being unavailable"
        );
        let replacement = object_grants::issue(
            source.catalog().unwrap(),
            &source_root,
            source_vault,
            &prekey,
        )
        .unwrap();
        assert_eq!(replacement.source_device_id(), &survivor_id);
        server
            .accept_object_key_grant(source_vault, survivor_id, &replacement)
            .unwrap();
        let packages = server
            .share_acceptance_packages(recipient_vault, *recipient_enrollment.device_id())
            .unwrap();
        assert_eq!(packages.len(), 1);
        let mut reader =
            chur_format::codec::Reader::new(&packages[0], chur_core::ChurStatus::InvalidInput);
        assert_eq!(reader.u16().unwrap(), 1);
        assert_eq!(reader.u32().unwrap(), 1);
        let mut current_records = Vec::new();
        for _ in 0..reader.u32().unwrap() {
            let encoded = reader.variable(1048576).unwrap();
            current_records.push(if encoded.len() == EnrollmentRecord::LEN {
                sharing_service::IssuerMembershipRecord::Enrollment(
                    EnrollmentRecord::decode(encoded).unwrap(),
                )
            } else {
                sharing_service::IssuerMembershipRecord::Revocation(
                    chur_sync_protocol::membership::RevocationRecord::decode(encoded).unwrap(),
                )
            });
        }
        assert_eq!(
            current_records.len(),
            if missing_source || rejecting_grant {
                2
            } else {
                3
            },
            "an old collection grant still returns current device membership evidence"
        );
        let mut current_operations = Vec::new();
        for _ in 0..reader.u32().unwrap() {
            current_operations.push(Operation::decode(reader.variable(1048576).unwrap()).unwrap());
        }
        for _ in 0..reader.u32().unwrap() {
            reader.variable(1048576).unwrap();
            reader.variable(1048576).unwrap();
        }
        reader.variable(1048576).unwrap();
        reader.variable(1048576).unwrap();
        reader.finish().unwrap();
        let current_evidence = [sharing_service::IssuerEvidence {
            membership: &current_records,
            operations: &current_operations,
        }];
        object_grants::accept(
            recipient.catalog().unwrap(),
            &recipient_root,
            recipient_vault,
            &current_evidence,
            &replacement,
        )
        .unwrap();
        let envelope = object_grants::pending_envelope(
            recipient.catalog_ref().unwrap(),
            &collection,
            &object_id,
        )
        .unwrap();
        let collection_key = chur_format::envelope::CollectionKeyEnvelope::decode(
            &store::active_collection_envelope(
                source.catalog_ref().unwrap(),
                &collection,
                envelope.collection_epoch(),
            )
            .unwrap(),
        )
        .unwrap()
        .open(&source_root)
        .unwrap();
        let key = envelope.open(&collection_key).unwrap();
        assert_eq!(
            &object_key_commitment(&collection, &object_id, &key),
            replacement.object_key_commitment()
        );
        let stream = store::streams(source.catalog_ref().unwrap(), &object_id)
            .unwrap()
            .remove(0);
        let file = chur_media::store::ContainerFile::open(
            &source_path,
            &source.object_store_id(),
            &stream.container_path_id,
        )
        .unwrap();
        let identity = chur_format::container::StreamIdentity {
            object_id,
            stream_id: stream.stream_id,
            stream_kind: stream.stream_kind,
            stream_revision: stream.stream_revision,
        };
        let mut reader = chur_format::container::StreamReader::open(file, &key, &identity).unwrap();
        assert_eq!(&*reader.read_range(0, photo.len() as u64).unwrap(), &photo);
        server
            .acknowledge_object_key_grant(
                recipient_vault,
                *recipient_enrollment.device_id(),
                replacement.grant_id(),
            )
            .unwrap();
        let quarantines: i64 = server
            .db
            .query_row("SELECT count(*) FROM object_key_rejections", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(
            quarantines, 0,
            "successful delivery removes request-scoped quarantine"
        );
    }

    #[test]
    fn pending_prekey_backup_restores_and_redelivers_after_the_original_grant_was_acknowledged() {
        pending_prekey_recovery(false);
    }

    #[test]
    fn pending_prekey_backup_recovers_after_the_live_device_deletes_and_discards_its_grant() {
        pending_prekey_recovery(true);
    }

    fn pending_prekey_recovery(discard_deleted: bool) {
        use chur_catalog::{
            object_grants, paths::VaultRoot, sharing_publication, sharing_receive, sharing_service,
            store, sync_keys, sync_log, sync_membership, sync_receive, vault,
        };
        use chur_media::{backup, import, progress::Uninterrupted};
        use chur_sync_protocol::object_grant::object_key_commitment;
        let directory = crate::tests::TestRoot::new();
        let source_path = VaultRoot::new(directory.0.join("source"));
        let recipient_path = VaultRoot::new(directory.0.join("recipient"));
        let password = b"genuine pending prekey backup password";
        let now = 1_700_000_000_000;
        let mut source = vault::create(&source_path, password, now)
            .unwrap()
            .activate()
            .unwrap();
        let mut recipient = vault::create(&recipient_path, password, now)
            .unwrap()
            .activate()
            .unwrap();
        let source_root = source.root_secret().unwrap().duplicate();
        let recipient_root = recipient.root_secret().unwrap().duplicate();
        let source_vault = source.vault_id();
        let recipient_vault = recipient.vault_id();
        let (source_enrollment, source_initial) = sync_receive::provision_local_identity(
            source.catalog().unwrap(),
            &source_root,
            source_vault,
        )
        .unwrap();
        let (recipient_enrollment, recipient_initial) = sync_receive::provision_local_identity(
            recipient.catalog().unwrap(),
            &recipient_root,
            recipient_vault,
        )
        .unwrap();
        let photo = vec![42u8; 4_096];
        let running = import::begin(
            &mut source,
            import::SourceCapability {
                requested_object_id: None,
                seekable: true,
                known_length: Some(photo.len() as u64),
                content_type_hint: "image/jpeg".into(),
                original_filename: Some("photo.jpg".into()),
                capture_time_ms: None,
            },
            import::CanonicalMedia {
                media_class: chur_format::constants::MediaClass::Image,
                width: 64,
                height: 64,
                duration_ms: 0,
            },
            now,
        )
        .unwrap();
        let object_id = import::stream_into(
            running,
            &mut source,
            &mut std::io::Cursor::new(&photo),
            "image/jpeg",
            now,
            &mut Uninterrupted,
        )
        .unwrap();
        let collection = store::object(source.catalog_ref().unwrap(), &object_id)
            .unwrap()
            .collection_id;
        let recipient_memberships = [sharing_service::IssuerMembershipRecord::Enrollment(
            recipient_enrollment.clone(),
        )];
        let recipient_operations = [recipient_initial.clone()];
        let prepared = sharing_service::prepare_share_for_device(
            source.catalog().unwrap(),
            &source_root,
            source_vault,
            collection,
            sharing_service::IssuerEvidence {
                membership: &recipient_memberships,
                operations: &recipient_operations,
            },
            *recipient_enrollment.device_id(),
            PermissionProfile::Read,
            true,
        )
        .unwrap();
        let candidate =
            sharing_publication::candidate(source.catalog_ref().unwrap(), &collection, &object_id)
                .unwrap();
        let publication = sharing_publication::publish_page(
            source.catalog().unwrap(),
            &source_root,
            source_vault,
            collection,
            &[candidate],
        )
        .unwrap()
        .remove(0);
        let source_memberships = [sharing_service::IssuerMembershipRecord::Enrollment(
            source_enrollment.clone(),
        )];
        let source_operations: Vec<_> = sync_log::records_after(
            source.catalog_ref().unwrap(),
            source_enrollment.device_id(),
            0,
        )
        .unwrap()
        .iter()
        .map(|record| Operation::decode(record).unwrap())
        .collect();
        let evidence = [sharing_service::IssuerEvidence {
            membership: &source_memberships,
            operations: &source_operations,
        }];
        sharing_receive::receive(
            recipient.catalog().unwrap(),
            &recipient_root,
            recipient_vault,
            &evidence,
            &[(
                prepared.membership().clone(),
                prepared.membership_operation().clone(),
            )],
            prepared.grant(),
            prepared.grant_operation(),
            &[publication.create_operation, publication.commit_operation],
        )
        .unwrap();
        let prekey = object_grants::request(
            recipient.catalog().unwrap(),
            &recipient_root,
            recipient_vault,
            collection,
            object_id,
        )
        .unwrap();
        let mut package = std::io::Cursor::new(Vec::new());
        backup::create(&mut recipient, &mut package, now, &mut Uninterrupted).unwrap();
        let mut server =
            ReferenceServer::open(directory.0.join("server"), 65_536, 1_048_576).unwrap();
        server
            .accept_initial_membership(&source_enrollment, &source_initial)
            .unwrap();
        server
            .accept_initial_membership(&recipient_enrollment, &recipient_initial)
            .unwrap();
        server
            .accept_collection_membership(prepared.membership(), prepared.membership_operation())
            .unwrap();
        server
            .accept_collection_grant(prepared.grant(), prepared.grant_operation())
            .unwrap();
        server
            .accept_object_key_request(recipient_vault, *recipient_enrollment.device_id(), &prekey)
            .unwrap();
        let grant = object_grants::issue(
            source.catalog().unwrap(),
            &source_root,
            source_vault,
            &prekey,
        )
        .unwrap();
        server
            .accept_object_key_grant(source_vault, *source_enrollment.device_id(), &grant)
            .unwrap();
        object_grants::accept(
            recipient.catalog().unwrap(),
            &recipient_root,
            recipient_vault,
            &evidence,
            &grant,
        )
        .unwrap();
        if discard_deleted {
            let object = sharing_receive::pending_object_for_collection(
                recipient.catalog().unwrap(),
                &recipient_root,
                recipient_vault,
                collection,
                object_id,
            )
            .unwrap();
            let expected = chur_media::sync_download::Expectation::new(
                object.object_id,
                object.stream_id,
                object.container_length,
                object.container_commitment,
            )
            .unwrap();
            let ciphertext = std::fs::read(
                source_path.container(&source.object_store_id(), &candidate.store_id),
            )
            .unwrap();
            chur_media::sync_download::append_range(
                &recipient_path,
                &recipient.object_store_id(),
                &object_id,
                0,
                &ciphertext,
                &expected,
            )
            .unwrap();
            chur_media::sync_download::activate_shared(&mut recipient, &object, now + 1).unwrap();
            chur_catalog::deletion::begin(recipient.catalog().unwrap(), &object_id).unwrap();
            chur_catalog::deletion::erase(recipient.catalog().unwrap(), &object_id, now + 2)
                .unwrap();
            assert!(chur_media::keys::object_key(&recipient, &object_id).is_err());
            assert_eq!(
                object_grants::accept(
                    recipient.catalog().unwrap(),
                    &recipient_root,
                    recipient_vault,
                    &evidence,
                    &grant
                )
                .unwrap_err()
                .status(),
                chur_core::ChurStatus::NotFound
            );
            server
                .discard_object_key_grant(
                    recipient_vault,
                    *recipient_enrollment.device_id(),
                    grant.grant_id(),
                )
                .unwrap();
        } else {
            server
                .acknowledge_object_key_grant(
                    recipient_vault,
                    *recipient_enrollment.device_id(),
                    grant.grant_id(),
                )
                .unwrap();
        }
        let restored_path = VaultRoot::new(directory.0.join("restored"));
        backup::restore(&restored_path, &mut package, password, &mut Uninterrupted).unwrap();
        let mut restored = vault::unlock_with_password(&restored_path, password, now + 1).unwrap();
        let restored_record: Vec<u8> = restored
            .catalog()
            .unwrap()
            .transaction(|tx| {
                Ok(tx
                    .query_row(
                        "SELECT record FROM object_key_requests WHERE object_id = ?1",
                        [object_id.as_bytes().as_slice()],
                        |row| row.get(0),
                    )
                    .unwrap())
            })
            .unwrap();
        let recovered = ObjectPrekey::decode(&restored_record).unwrap();
        assert_eq!(
            recovered.encode(),
            prekey.encode(),
            "the old pending request must come from the actual restored catalog"
        );
        assert_eq!(
            server
                .accept_object_key_request(
                    recipient_vault,
                    *recipient_enrollment.device_id(),
                    &recovered
                )
                .unwrap(),
            crate::RelayOutcome::Stored
        );
        assert_eq!(
            server
                .accept_object_key_grant(source_vault, *source_enrollment.device_id(), &grant)
                .unwrap(),
            crate::RelayOutcome::Stored
        );
        let delivered = server
            .object_key_grants_for_recipient(recipient_vault, *recipient_enrollment.device_id())
            .unwrap();
        assert_eq!(delivered.len(), 1);
        let mut private: Vec<u8> = restored
            .catalog()
            .unwrap()
            .transaction(|tx| {
                Ok(tx
                    .query_row(
                        "SELECT private_key FROM object_key_requests WHERE prekey_id = ?1",
                        [prekey.prekey_id().as_bytes().as_slice()],
                        |row| row.get(0),
                    )
                    .unwrap())
            })
            .unwrap();
        let mut secret = Key::zeroed();
        secret.expose_mut().copy_from_slice(&private);
        private.fill(0);
        let key = ObjectKeyGrant::decode(&delivered[0])
            .unwrap()
            .open(&recovered, &secret, source_enrollment.signing_public_key())
            .unwrap();
        assert_eq!(
            &object_key_commitment(&collection, &object_id, &key),
            grant.object_key_commitment()
        );
        let stream = store::streams(source.catalog_ref().unwrap(), &object_id)
            .unwrap()
            .remove(0);
        let file = chur_media::store::ContainerFile::open(
            &source_path,
            &source.object_store_id(),
            &stream.container_path_id,
        )
        .unwrap();
        let identity = chur_format::container::StreamIdentity {
            object_id,
            stream_id: stream.stream_id,
            stream_kind: stream.stream_kind,
            stream_revision: stream.stream_revision,
        };
        let mut reader = chur_format::container::StreamReader::open(file, &key, &identity).unwrap();
        assert_eq!(&*reader.read_range(0, photo.len() as u64).unwrap(), &photo);
        // A source may have evicted its public grant cache while the backup
        // was offline. A fresh signed HPKE response must still deliver the same key.
        server
            .acknowledge_object_key_grant(
                recipient_vault,
                *recipient_enrollment.device_id(),
                grant.grant_id(),
            )
            .unwrap();
        server
            .accept_object_key_request(
                recipient_vault,
                *recipient_enrollment.device_id(),
                &recovered,
            )
            .unwrap();
        source
            .catalog()
            .unwrap()
            .transaction(|tx| {
                tx.execute(
                    "DELETE FROM issued_object_key_grants WHERE prekey_id = ?1",
                    [prekey.prekey_id().as_bytes().as_slice()],
                )
                .unwrap();
                Ok(())
            })
            .unwrap();
        let replacement = object_grants::issue(
            source.catalog().unwrap(),
            &source_root,
            source_vault,
            &recovered,
        )
        .unwrap();
        assert_ne!(replacement.grant_id(), grant.grant_id());
        assert_eq!(
            server
                .accept_object_key_grant(source_vault, *source_enrollment.device_id(), &replacement)
                .unwrap(),
            crate::RelayOutcome::Stored
        );
        assert!(
            replacement
                .open(&recovered, &secret, source_enrollment.signing_public_key())
                .unwrap()
                == key
        );
        let membership = sync_membership::load(restored.catalog_ref().unwrap())
            .unwrap()
            .unwrap();
        assert!(
            sync_keys::portable_identity_envelope(
                restored.catalog_ref().unwrap(),
                &recipient_root,
                &membership
            )
            .unwrap()
            .is_some()
        );
    }

    #[test]
    fn source_request_fifo_keeps_reposted_low_ids_behind_an_older_legitimate_request() {
        let mut f = fixture();
        for value in 1..65 {
            let poison = request(&f.recipient, value);
            f.server
                .accept_object_key_request(id(5), id(6), &poison)
                .unwrap();
        }
        let legitimate = request(&f.recipient, 200);
        f.server
            .accept_object_key_request(id(5), id(6), &legitimate)
            .unwrap();
        let poisoned_page = f
            .server
            .object_key_requests_for_source(id(1), id(2))
            .unwrap();
        assert_eq!(poisoned_page.len(), 64);
        for record in poisoned_page {
            let poison = ObjectPrekey::decode(&record).unwrap();
            f.server
                .acknowledge_object_key_request(id(1), id(2), poison.prekey_id())
                .unwrap();
            f.server
                .accept_object_key_request(id(5), id(6), &poison)
                .unwrap();
        }
        assert_eq!(
            f.server
                .object_key_requests_for_source(id(1), id(2))
                .unwrap()
                .first(),
            Some(&legitimate.encode())
        );
    }

    #[test]
    fn account_deletion_removes_delivery_bytes_and_acknowledgement_receipts() {
        for delete_source in [true, false] {
            let mut f = fixture();
            for number in 20..24 {
                let prekey = request(&f.recipient, number);
                f.server
                    .accept_object_key_request(id(5), id(6), &prekey)
                    .unwrap();
                if number != 22 {
                    let grant = grant(&f, &prekey, number + 10);
                    f.server
                        .accept_object_key_grant(id(1), id(2), &grant)
                        .unwrap();
                    if number == 20 {
                        f.server
                            .acknowledge_object_key_grant(id(5), id(6), grant.grant_id())
                            .unwrap();
                    }
                }
            }
            f.server
                .reject_object_key_grant(id(5), id(6), &id(33))
                .unwrap();
            let pending = request(&f.recipient, 22);
            f.server
                .acknowledge_object_key_request(id(1), id(2), pending.prekey_id())
                .unwrap();
            let (vault, device, signer) = if delete_source {
                (id(1), id(2), f.source.signing_key())
            } else {
                (id(5), id(6), f.recipient.signing_key())
            };
            let authorization = chur_sync_protocol::deletion::ServerDeletionAuthorization::account(
                id(40),
                vault,
                device,
            )
            .sign(signer);
            f.server.apply_deletion(&authorization).unwrap();
            for table in [
                "object_key_requests",
                "object_key_grants",
                "object_key_receipts",
                "object_key_dismissals",
                "object_key_rejections",
            ] {
                let count: i64 = f
                    .server
                    .db
                    .query_row(&format!("SELECT count(*) FROM {table}"), [], |row| {
                        row.get(0)
                    })
                    .unwrap();
                assert_eq!(count, 0, "{table} retained a deleted account");
            }
        }
    }

    #[test]
    fn source_dismissals_are_quota_charged_and_idempotent_at_the_limit() {
        let mut f = fixture();
        let prekey = request(&f.recipient, 20);
        f.server
            .accept_object_key_request(id(5), id(6), &prekey)
            .unwrap();
        let used: i64 = f.server.db.query_row(
            "SELECT (SELECT SUM(length(record)) FROM operations WHERE vault_id = ?1)
             + (SELECT SUM(length(record)) FROM membership_records WHERE vault_id = ?1)
             + (SELECT SUM(length(record)) FROM collection_membership_records WHERE issuer_vault_id = ?1)",
            [id(1).as_bytes().as_slice()], |row| row.get(0),
        ).unwrap();
        f.server.max_account_bytes = u64::try_from(used).unwrap() + 47;
        assert_eq!(
            f.server
                .acknowledge_object_key_request(id(1), id(2), prekey.prekey_id())
                .unwrap_err()
                .status(),
            chur_core::ChurStatus::ResourceLimitExceeded
        );
        let attempts: i64 = f
            .server
            .db
            .query_row("SELECT count(*) FROM object_key_dismissals", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(attempts, 0);
        f.server.max_account_bytes = u64::try_from(used).unwrap() + 48;
        f.server
            .acknowledge_object_key_request(id(1), id(2), prekey.prekey_id())
            .unwrap();
        f.server
            .acknowledge_object_key_request(id(1), id(2), prekey.prekey_id())
            .unwrap();
        assert!(f.server.ensure_account_capacity(&id(1), 1).is_err());
        f.server
            .accept_object_key_request(id(5), id(6), &prekey)
            .unwrap();
        f.server.ensure_account_capacity(&id(1), 48).unwrap();
    }

    #[test]
    fn rejecting_a_grant_at_the_source_quota_limit_replaces_its_bytes_with_bounded_public_state() {
        let mut f = fixture();
        let prekey = request(&f.recipient, 20);
        f.server
            .accept_object_key_request(id(5), id(6), &prekey)
            .unwrap();
        let response = grant(&f, &prekey, 21);
        let base: i64 = f.server.db.query_row(
            "SELECT (SELECT SUM(length(record)) FROM operations WHERE vault_id = ?1)
             + (SELECT SUM(length(record)) FROM membership_records WHERE vault_id = ?1)
             + (SELECT SUM(length(record)) FROM collection_membership_records WHERE issuer_vault_id = ?1)",
            [id(1).as_bytes().as_slice()], |row| row.get(0),
        ).unwrap();
        f.server.max_account_bytes = u64::try_from(base).unwrap() + ObjectKeyGrant::LEN as u64;
        f.server
            .accept_object_key_grant(id(1), id(2), &response)
            .unwrap();
        f.server
            .reject_object_key_grant(id(5), id(6), response.grant_id())
            .unwrap();
        f.server
            .reject_object_key_grant(id(5), id(6), response.grant_id())
            .unwrap();
        let released = ObjectKeyGrant::LEN as u64 - 128;
        f.server.ensure_account_capacity(&id(1), released).unwrap();
        assert!(
            f.server
                .ensure_account_capacity(&id(1), released + 1)
                .is_err()
        );
        assert!(
            f.server
                .reject_object_key_grant(id(1), id(2), response.grant_id())
                .is_err()
        );
        drop(f.server);
        let server = ReferenceServer::open(&f.root.0, 1_024, 1_048_576).unwrap();
        assert!(
            server
                .object_key_requests_for_source(id(1), id(2))
                .unwrap()
                .is_empty()
        );
        let stored: i64 = server
            .db
            .query_row("SELECT count(*) FROM object_key_requests", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(
            stored, 1,
            "a rejection must retain the original public request across restart"
        );
    }

    #[test]
    fn object_key_delivery_rejects_forgery_wrong_transport_and_contradictory_reuse() {
        let mut f = fixture();
        let prekey = request(&f.recipient, 20);
        assert_eq!(
            f.server
                .accept_object_key_request(id(1), id(2), &prekey)
                .unwrap_err()
                .status(),
            chur_core::ChurStatus::AuthenticationFailed
        );
        let mut forged = prekey.encode();
        *forged.last_mut().unwrap() ^= 1;
        assert!(
            f.server
                .accept_object_key_request(id(5), id(6), &ObjectPrekey::decode(&forged).unwrap())
                .is_err()
        );
        f.server
            .accept_object_key_request(id(5), id(6), &prekey)
            .unwrap();
        let replacement = ObjectPrekey::sign(
            id(20),
            id(14),
            id(22),
            1,
            [17; 32],
            id(5),
            id(6),
            1,
            &Key::new([20; 32]),
            f.recipient.signing_key(),
        )
        .unwrap();
        assert_eq!(
            f.server
                .accept_object_key_request(id(5), id(6), &replacement)
                .unwrap_err()
                .status(),
            chur_core::ChurStatus::Conflict
        );
        let wrong_grant = grant(&f, &replacement, 23);
        assert_eq!(
            f.server
                .accept_object_key_grant(id(1), id(2), &wrong_grant)
                .unwrap_err()
                .status(),
            chur_core::ChurStatus::AuthenticationFailed
        );
        let correct = grant(&f, &prekey, 24);
        assert_eq!(
            f.server
                .accept_object_key_grant(id(5), id(6), &correct)
                .unwrap_err()
                .status(),
            chur_core::ChurStatus::AuthenticationFailed
        );
        f.server
            .accept_object_key_grant(id(1), id(2), &correct)
            .unwrap();
        let wrong_key = ObjectKeyGrant::seal(
            id(26),
            id(1),
            id(2),
            *prekey.object_id(),
            prekey.object_generation(),
            *prekey.container_commitment(),
            &prekey,
            &Key::new([19; 32]),
            f.source.signing_key(),
        )
        .unwrap();
        assert_eq!(
            f.server
                .accept_object_key_grant(id(1), id(2), &wrong_key)
                .unwrap_err()
                .status(),
            chur_core::ChurStatus::AuthenticationFailed
        );
        let duplicate_request_new_grant = grant(&f, &prekey, 25);
        assert_eq!(
            f.server
                .accept_object_key_grant(id(1), id(2), &duplicate_request_new_grant)
                .unwrap(),
            crate::RelayOutcome::Stored
        );
        f.server
            .acknowledge_object_key_grant(id(5), id(6), correct.grant_id())
            .unwrap();
        assert_eq!(
            f.server
                .object_key_grants_for_recipient(id(5), id(6))
                .unwrap(),
            vec![duplicate_request_new_grant.encode()]
        );
        f.server
            .acknowledge_object_key_grant(id(5), id(6), duplicate_request_new_grant.grant_id())
            .unwrap();
        assert_eq!(
            f.server
                .accept_object_key_request(id(5), id(6), &replacement)
                .unwrap_err()
                .status(),
            chur_core::ChurStatus::Conflict
        );
    }

    #[test]
    fn object_key_delivery_pages_advance_after_grants_and_source_cancellation() {
        let mut f = fixture();
        for number in 30..100 {
            let prekey = request(&f.recipient, number);
            f.server
                .accept_object_key_request(id(5), id(6), &prekey)
                .unwrap();
        }
        assert_eq!(
            f.server
                .object_key_requests_for_source(id(1), id(2))
                .unwrap()
                .len(),
            64
        );
        let mut count = 0;
        while let Some(bytes) = f
            .server
            .object_key_requests_for_source(id(1), id(2))
            .unwrap()
            .first()
        {
            let prekey = ObjectPrekey::decode(bytes).unwrap();
            let grant = grant(&f, &prekey, 100 + count);
            f.server
                .accept_object_key_grant(id(1), id(2), &grant)
                .unwrap();
            count += 1;
        }
        assert_eq!(count, 70);
        assert_eq!(
            f.server
                .object_key_grants_for_recipient(id(5), id(6))
                .unwrap()
                .len(),
            64
        );
        let mut accepted = 0;
        loop {
            let grants = f
                .server
                .object_key_grants_for_recipient(id(5), id(6))
                .unwrap();
            if grants.is_empty() {
                break;
            }
            for bytes in grants {
                let grant = ObjectKeyGrant::decode(&bytes).unwrap();
                f.server
                    .acknowledge_object_key_grant(id(5), id(6), grant.grant_id())
                    .unwrap();
                accepted += 1;
            }
        }
        assert_eq!(accepted, 70);
        let unavailable = request(&f.recipient, 200);
        f.server
            .accept_object_key_request(id(5), id(6), &unavailable)
            .unwrap();
        assert!(
            f.server
                .acknowledge_object_key_request(id(5), id(6), unavailable.prekey_id())
                .is_err()
        );
        f.server
            .acknowledge_object_key_request(id(1), id(2), unavailable.prekey_id())
            .unwrap();
        f.server
            .acknowledge_object_key_request(id(1), id(2), unavailable.prekey_id())
            .unwrap();
        assert_eq!(
            f.server
                .accept_object_key_request(id(5), id(6), &unavailable)
                .unwrap(),
            crate::RelayOutcome::Stored
        );
        f.server
            .acknowledge_object_key_request(id(1), id(2), unavailable.prekey_id())
            .unwrap();
        assert!(
            f.server
                .object_key_requests_for_source(id(1), id(2))
                .unwrap()
                .is_empty()
        );
    }

    #[test]
    fn revoked_collection_reader_cannot_request_or_receive_object_keys() {
        let mut f = fixture();
        let pending = request(&f.recipient, 20);
        let granted = request(&f.recipient, 21);
        f.server
            .accept_object_key_request(id(5), id(6), &pending)
            .unwrap();
        f.server
            .accept_object_key_request(id(5), id(6), &granted)
            .unwrap();
        let grant = grant(&f, &granted, 22);
        f.server
            .accept_object_key_grant(id(1), id(2), &grant)
            .unwrap();
        let revocation = CollectionMembershipRecord::new(
            id(1),
            id(14),
            2,
            f.membership.commitment(),
            CollectionMembershipAction::Revoke,
            id(5),
            id(6),
            f.recipient.signing_public_key(),
            f.recipient.hpke_public_key(),
            2,
            id(1),
            id(2),
            1,
            3,
        )
        .unwrap()
        .sign(f.source.signing_key());
        let outer = operation(
            id(1),
            id(2),
            id(23),
            3,
            f.membership_outer.digest(),
            f.source.signing_key(),
        );
        f.server
            .accept_collection_membership(&revocation, &outer)
            .unwrap();
        assert!(
            f.server
                .object_key_requests_for_source(id(1), id(2))
                .unwrap()
                .is_empty()
        );
        assert!(
            f.server
                .object_key_grants_for_recipient(id(5), id(6))
                .unwrap()
                .is_empty()
        );
        let remaining: (i64, i64) = f.server.db.query_row("SELECT (SELECT count(*) FROM object_key_grants), (SELECT count(*) FROM object_key_requests)", [], |row| Ok((row.get(0)?, row.get(1)?))).unwrap();
        assert_eq!(
            remaining,
            (0, 0),
            "read revocation retires addressed bytes atomically"
        );
        assert_eq!(
            f.server
                .accept_object_key_request(id(5), id(6), &pending)
                .unwrap_err()
                .status(),
            chur_core::ChurStatus::AuthenticationFailed
        );
        assert_eq!(
            f.server
                .accept_object_key_grant(id(1), id(2), &grant)
                .unwrap_err()
                .status(),
            chur_core::ChurStatus::AuthenticationFailed
        );
    }

    #[test]
    fn revoked_recipient_device_retires_pending_and_granted_requests() {
        let mut f = fixture();
        let invalid = request(&f.recipient, 23);
        f.server
            .accept_object_key_request(id(5), id(6), &invalid)
            .unwrap();
        let invalid_grant = grant(&f, &invalid, 24);
        f.server
            .accept_object_key_grant(id(1), id(2), &invalid_grant)
            .unwrap();
        f.server
            .reject_object_key_grant(id(5), id(6), invalid_grant.grant_id())
            .unwrap();
        let pending = request(&f.recipient, 20);
        let granted = request(&f.recipient, 21);
        f.server
            .accept_object_key_request(id(5), id(6), &pending)
            .unwrap();
        f.server
            .accept_object_key_request(id(5), id(6), &granted)
            .unwrap();
        f.server
            .accept_object_key_grant(id(1), id(2), &grant(&f, &granted, 22))
            .unwrap();
        f.server
            .acknowledge_object_key_request(id(1), id(2), pending.prekey_id())
            .unwrap();
        let membership = crate::relay::membership_state(&f.server.db, &id(5)).unwrap();
        let initial =
            Operation::decode(&f.server.operations_after(id(5), id(6), 0).unwrap()[0]).unwrap();
        let checkpoint = chur_sync_protocol::checkpoint::Checkpoint::new(
            id(5),
            id(6),
            1,
            1,
            *membership.commitment(),
            vec![chur_sync_protocol::checkpoint::CheckpointHead::new(
                id(6),
                1,
                initial.digest(),
            )],
            [31; 32],
            [0; 32],
        )
        .unwrap()
        .sign(f.recipient.signing_key());
        f.server.accept_checkpoint(&checkpoint).unwrap();
        let survivor = DeviceIdentity::from_seeds([32; 32], [33; 32]);
        let enrollment = EnrollmentRecord::new(
            id(5),
            id(30),
            survivor.signing_public_key(),
            survivor.hpke_public_key(),
            2,
            id(6),
            2,
            *membership.commitment(),
            checkpoint.commitment(),
        )
        .unwrap()
        .sign(f.recipient.signing_key());
        let enrolled = operation(
            id(5),
            id(6),
            id(34),
            2,
            initial.digest(),
            f.recipient.signing_key(),
        );
        f.server.accept_enrollment(&enrollment, &enrolled).unwrap();
        let revocation = chur_sync_protocol::membership::RevocationRecord::new(
            id(5),
            id(6),
            2,
            enrolled.digest(),
            3,
            id(30),
            enrollment.commitment(),
        )
        .unwrap()
        .sign(survivor.signing_key());
        let revoked = operation(id(5), id(30), id(35), 1, [0; 32], survivor.signing_key());
        f.server.accept_revocation(&revocation, &revoked).unwrap();
        assert!(
            f.server
                .object_key_requests_for_source(id(1), id(2))
                .unwrap()
                .is_empty()
        );
        assert!(
            f.server
                .object_key_grants_for_recipient(id(5), id(6))
                .is_err()
        );
        let remaining: (i64, i64) = f.server.db.query_row("SELECT (SELECT count(*) FROM object_key_grants), (SELECT count(*) FROM object_key_requests)", [], |row| Ok((row.get(0)?, row.get(1)?))).unwrap();
        assert_eq!(remaining, (0, 0));
        let attempts: i64 = f
            .server
            .db
            .query_row("SELECT count(*) FROM object_key_dismissals", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(attempts, 0);
        let quarantines: i64 = f
            .server
            .db
            .query_row("SELECT count(*) FROM object_key_rejections", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(
            quarantines, 0,
            "membership changes remove request or issuer quarantine"
        );
    }

    #[test]
    fn revoked_source_device_cannot_issue_or_retrieve_object_keys() {
        let mut f = fixture();
        let invalid = request(&f.recipient, 23);
        f.server
            .accept_object_key_request(id(5), id(6), &invalid)
            .unwrap();
        let invalid_grant = grant(&f, &invalid, 24);
        f.server
            .accept_object_key_grant(id(1), id(2), &invalid_grant)
            .unwrap();
        f.server
            .reject_object_key_grant(id(5), id(6), invalid_grant.grant_id())
            .unwrap();
        let prekey = request(&f.recipient, 20);
        f.server
            .accept_object_key_request(id(5), id(6), &prekey)
            .unwrap();
        let grant = grant(&f, &prekey, 21);
        f.server
            .accept_object_key_grant(id(1), id(2), &grant)
            .unwrap();
        let unavailable = request(&f.recipient, 22);
        f.server
            .accept_object_key_request(id(5), id(6), &unavailable)
            .unwrap();
        f.server
            .acknowledge_object_key_request(id(1), id(2), unavailable.prekey_id())
            .unwrap();
        let membership = crate::relay::membership_state(&f.server.db, &id(1)).unwrap();
        let checkpoint = chur_sync_protocol::checkpoint::Checkpoint::new(
            id(1),
            id(2),
            2,
            1,
            *membership.commitment(),
            vec![chur_sync_protocol::checkpoint::CheckpointHead::new(
                id(2),
                2,
                f.membership_outer.digest(),
            )],
            [31; 32],
            [0; 32],
        )
        .unwrap()
        .sign(f.source.signing_key());
        f.server.accept_checkpoint(&checkpoint).unwrap();
        let survivor = DeviceIdentity::from_seeds([32; 32], [33; 32]);
        let enrollment = EnrollmentRecord::new(
            id(1),
            id(30),
            survivor.signing_public_key(),
            survivor.hpke_public_key(),
            3,
            id(2),
            2,
            *membership.commitment(),
            checkpoint.commitment(),
        )
        .unwrap()
        .sign(f.source.signing_key());
        let enrollment_outer = operation(
            id(1),
            id(2),
            id(34),
            3,
            f.membership_outer.digest(),
            f.source.signing_key(),
        );
        f.server
            .accept_enrollment(&enrollment, &enrollment_outer)
            .unwrap();
        let revocation = chur_sync_protocol::membership::RevocationRecord::new(
            id(1),
            id(2),
            3,
            enrollment_outer.digest(),
            3,
            id(30),
            enrollment.commitment(),
        )
        .unwrap()
        .sign(survivor.signing_key());
        let outer = operation(id(1), id(30), id(35), 1, [0; 32], survivor.signing_key());
        f.server.accept_revocation(&revocation, &outer).unwrap();
        let attempts: i64 = f
            .server
            .db
            .query_row("SELECT count(*) FROM object_key_dismissals", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(
            attempts, 0,
            "revocation retires the source device's dismissal state"
        );
        assert_eq!(
            f.server
                .object_key_requests_for_source(id(1), id(2))
                .unwrap_err()
                .status(),
            chur_core::ChurStatus::AuthenticationFailed
        );
        assert_eq!(
            f.server
                .accept_object_key_grant(id(1), id(2), &grant)
                .unwrap_err()
                .status(),
            chur_core::ChurStatus::AuthenticationFailed
        );
        assert!(
            f.server
                .object_key_grants_for_recipient(id(5), id(6))
                .unwrap()
                .is_empty()
        );
        let quarantines: i64 = f
            .server
            .db
            .query_row("SELECT count(*) FROM object_key_rejections", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(
            quarantines, 0,
            "membership changes remove request or issuer quarantine"
        );
    }

    async fn http_call(
        app: &axum::Router,
        method: &str,
        vault: Id,
        token: u8,
        path: &str,
        body: Vec<u8>,
    ) -> (StatusCode, Vec<u8>) {
        let response = app
            .clone()
            .oneshot(
                Request::builder()
                    .method(method)
                    .uri(format!("/v1/vaults/{}/sharing/{path}", vault.to_hex()))
                    .header(
                        "Authorization",
                        format!("Bearer {}", hex::encode([token; 32])),
                    )
                    .body(Body::from(body))
                    .unwrap(),
            )
            .await
            .unwrap();
        let status = response.status();
        (
            status,
            to_bytes(response.into_body(), 1_048_576)
                .await
                .unwrap()
                .to_vec(),
        )
    }

    fn framed(record: &[u8]) -> Vec<u8> {
        let mut bytes = 1u32.to_be_bytes().to_vec();
        bytes.extend_from_slice(&(record.len() as u32).to_be_bytes());
        bytes.extend_from_slice(record);
        bytes
    }

    #[test]
    fn legacy_noncontributory_prekeys_cannot_block_later_valid_source_requests() {
        for invalid in signed_invalid_prekeys() {
            let mut f = fixture();
            f.server
                .db
                .execute(
                    "INSERT INTO object_key_requests(prekey_id, source_vault_id, collection_id,
                 recipient_vault_id, recipient_device_id, record) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
                    rusqlite::params![
                        id(20).as_bytes().as_slice(),
                        id(1).as_bytes().as_slice(),
                        id(14).as_bytes().as_slice(),
                        id(5).as_bytes().as_slice(),
                        id(6).as_bytes().as_slice(),
                        invalid
                    ],
                )
                .unwrap();
            let healthy = request(&f.recipient, 21);
            f.server
                .accept_object_key_request(id(5), id(6), &healthy)
                .unwrap();
            assert_eq!(
                f.server
                    .object_key_requests_for_source(id(1), id(2))
                    .unwrap(),
                vec![healthy.encode()]
            );
        }
    }

    #[tokio::test]
    async fn noncontributory_signed_prekeys_are_rejected_before_http_admission() {
        let f = fixture();
        let healthy = request(&f.recipient, 21);
        let app = http::router(f.server, [99; 32]);
        for invalid in signed_invalid_prekeys() {
            assert_eq!(
                http_call(&app, "POST", id(5), 12, "object-key-requests", invalid)
                    .await
                    .0,
                StatusCode::BAD_REQUEST
            );
        }
        assert_eq!(
            http_call(&app, "GET", id(1), 10, "object-key-requests", Vec::new()).await,
            (StatusCode::OK, 0u32.to_be_bytes().to_vec())
        );
        assert_eq!(
            http_call(
                &app,
                "POST",
                id(5),
                12,
                "object-key-requests",
                healthy.encode()
            )
            .await
            .0,
            StatusCode::CREATED
        );
        assert_eq!(
            http_call(&app, "GET", id(1), 10, "object-key-requests", Vec::new()).await,
            (StatusCode::OK, framed(&healthy.encode()))
        );
    }

    #[tokio::test]
    async fn absent_object_grants_have_a_discard_route_that_allows_exact_backup_redelivery() {
        let mut f = fixture();
        let prekey = request(&f.recipient, 20);
        f.server
            .accept_object_key_request(id(5), id(6), &prekey)
            .unwrap();
        let response = grant(&f, &prekey, 21);
        f.server
            .accept_object_key_grant(id(1), id(2), &response)
            .unwrap();
        let app = http::router(f.server, [99; 32]);
        let body = response.grant_id().as_bytes().to_vec();
        assert_eq!(
            http_call(
                &app,
                "POST",
                id(1),
                10,
                "object-key-grants/discard",
                body.clone()
            )
            .await
            .0,
            StatusCode::UNAUTHORIZED
        );
        assert_eq!(
            http_call(
                &app,
                "POST",
                id(5),
                99,
                "object-key-grants/discard",
                body.clone()
            )
            .await
            .0,
            StatusCode::UNAUTHORIZED
        );
        assert_eq!(
            http_call(
                &app,
                "POST",
                id(5),
                12,
                "object-key-grants/discard",
                vec![1; 15]
            )
            .await
            .0,
            StatusCode::BAD_REQUEST
        );
        for _ in 0..2 {
            assert_eq!(
                http_call(
                    &app,
                    "POST",
                    id(5),
                    12,
                    "object-key-grants/discard",
                    body.clone()
                )
                .await
                .0,
                StatusCode::NO_CONTENT
            );
        }
        assert_eq!(
            http_call(&app, "GET", id(5), 12, "object-key-grants", Vec::new()).await,
            (StatusCode::OK, 0u32.to_be_bytes().to_vec())
        );
        assert_eq!(
            http_call(
                &app,
                "POST",
                id(5),
                12,
                "object-key-requests",
                prekey.encode()
            )
            .await
            .0,
            StatusCode::CREATED
        );
        assert_eq!(
            http_call(&app, "GET", id(1), 10, "object-key-requests", Vec::new()).await,
            (StatusCode::OK, framed(&prekey.encode()))
        );
        assert_eq!(
            http_call(
                &app,
                "POST",
                id(1),
                10,
                "object-key-grants",
                response.encode()
            )
            .await
            .0,
            StatusCode::CREATED
        );
    }

    #[tokio::test]
    async fn rejected_grants_have_an_authenticated_idempotent_http_route() {
        let mut f = fixture();
        let prekey = request(&f.recipient, 20);
        f.server
            .accept_object_key_request(id(5), id(6), &prekey)
            .unwrap();
        let response = grant(&f, &prekey, 21);
        f.server
            .accept_object_key_grant(id(1), id(2), &response)
            .unwrap();
        let app = http::router(f.server, [99; 32]);
        let body = response.grant_id().as_bytes().to_vec();
        assert_eq!(
            http_call(
                &app,
                "POST",
                id(1),
                10,
                "object-key-grants/reject",
                body.clone()
            )
            .await
            .0,
            StatusCode::UNAUTHORIZED
        );
        assert_eq!(
            http_call(
                &app,
                "POST",
                id(5),
                99,
                "object-key-grants/reject",
                body.clone()
            )
            .await
            .0,
            StatusCode::UNAUTHORIZED
        );
        assert_eq!(
            http_call(
                &app,
                "POST",
                id(5),
                12,
                "object-key-grants/reject",
                vec![1; 15]
            )
            .await
            .0,
            StatusCode::BAD_REQUEST
        );
        for _ in 0..2 {
            assert_eq!(
                http_call(
                    &app,
                    "POST",
                    id(5),
                    12,
                    "object-key-grants/reject",
                    body.clone()
                )
                .await
                .0,
                StatusCode::NO_CONTENT
            );
        }
        assert_eq!(
            http_call(&app, "GET", id(5), 12, "object-key-grants", Vec::new()).await,
            (StatusCode::OK, 0u32.to_be_bytes().to_vec())
        );
        assert_eq!(
            http_call(
                &app,
                "POST",
                id(5),
                12,
                "object-key-requests",
                prekey.encode()
            )
            .await
            .0,
            StatusCode::OK
        );
        assert_eq!(
            http_call(&app, "GET", id(1), 10, "object-key-requests", Vec::new()).await,
            (StatusCode::OK, 0u32.to_be_bytes().to_vec())
        );
    }

    #[tokio::test]
    async fn authenticated_object_key_request_has_a_real_http_delivery_route() {
        let f = fixture();
        let prekey = request(&f.recipient, 20);
        let grant = grant(&f, &prekey, 21);
        let app = http::router(f.server, [99; 32]);
        assert_eq!(
            http_call(
                &app,
                "POST",
                id(5),
                12,
                "object-key-requests",
                prekey.encode()
            )
            .await
            .0,
            StatusCode::CREATED
        );
        assert_eq!(
            http_call(&app, "GET", id(1), 10, "object-key-requests", Vec::new()).await,
            (StatusCode::OK, framed(&prekey.encode()))
        );
        assert_eq!(
            http_call(&app, "POST", id(1), 10, "object-key-grants", grant.encode())
                .await
                .0,
            StatusCode::CREATED
        );
        assert_eq!(
            http_call(&app, "GET", id(5), 12, "object-key-grants", Vec::new()).await,
            (StatusCode::OK, framed(&grant.encode()))
        );
        assert_eq!(
            http_call(
                &app,
                "POST",
                id(1),
                10,
                "object-key-grants/ack",
                grant.grant_id().as_bytes().to_vec()
            )
            .await
            .0,
            StatusCode::UNAUTHORIZED
        );
        assert_eq!(
            http_call(
                &app,
                "POST",
                id(5),
                12,
                "object-key-grants/ack",
                grant.grant_id().as_bytes().to_vec()
            )
            .await
            .0,
            StatusCode::NO_CONTENT
        );
        assert_eq!(
            http_call(&app, "GET", id(5), 12, "object-key-grants", Vec::new()).await,
            (StatusCode::OK, 0u32.to_be_bytes().to_vec())
        );
        let retry = ObjectPrekey::sign(
            id(22),
            id(14),
            id(16),
            1,
            [17; 32],
            id(5),
            id(6),
            1,
            &Key::new([22; 32]),
            f.recipient.signing_key(),
        )
        .unwrap();
        assert_eq!(
            http_call(
                &app,
                "POST",
                id(5),
                12,
                "object-key-requests",
                retry.encode()
            )
            .await
            .0,
            StatusCode::CREATED
        );
        assert_eq!(
            http_call(
                &app,
                "POST",
                id(1),
                10,
                "object-key-requests/ack",
                retry.prekey_id().as_bytes().to_vec()
            )
            .await
            .0,
            StatusCode::NO_CONTENT
        );
        assert_eq!(
            http_call(
                &app,
                "POST",
                id(5),
                12,
                "object-key-requests",
                retry.encode()
            )
            .await
            .0,
            StatusCode::CREATED
        );
    }
}
