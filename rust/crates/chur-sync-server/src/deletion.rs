use std::fs;
use std::io::ErrorKind;
use std::path::Path;

use chur_core::{ChurStatus, Error, Id, Result, ensure};
use chur_sync_protocol::deletion::{DeletionTargetKind, ServerDeletionAuthorization};
use chur_sync_protocol::state::DeviceStatus;
use rusqlite::{Connection, OptionalExtension, params};

use super::{ReferenceServer, corrupt_row, map_sqlite};

/// Durable result of applying one signed deletion request.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DeletionOutcome {
    /// The authorized target was removed.
    Deleted,
    /// The exact request was already applied.
    Duplicate,
}

impl ReferenceServer {
    /// Verifies and applies one object or whole-account deletion authorization.
    ///
    /// The request carries no transport token, so its signature is the only
    /// proof of authority, and the signer key comes from the stored membership.
    /// The signature cannot be checked before that membership is rebuilt.
    /// Until the signature verifies, every failure is therefore
    /// `AUTHENTICATION_FAILED`, as in `authenticate_transport`: an unknown
    /// vault, a failed storage read, and stored membership that does not
    /// replay look the same as a wrong key (`ERROR_MODEL.md` principle 2).
    /// The replay rule of `SYNC_PROTOCOL_V1.md` §9.1 is the one exception: an
    /// exact replay succeeds, and a reused request identifier is `CONFLICT`.
    /// Only an authenticated request can get `CATALOG_CORRUPT`.
    pub fn apply_deletion(
        &mut self,
        authorization: &ServerDeletionAuthorization,
    ) -> Result<DeletionOutcome> {
        if let Some(stored) = self
            .db
            .query_row(
                "SELECT record FROM deletion_requests
                 WHERE vault_id = ?1 AND request_id = ?2",
                params![
                    authorization.vault_id().as_bytes().as_slice(),
                    authorization.request_id().as_bytes().as_slice(),
                ],
                |row| row.get::<_, Vec<u8>>(0),
            )
            .optional()
            .map_err(|_| signer_not_accepted())?
        {
            ensure!(
                stored == authorization.encode(),
                Conflict,
                "deletion request identifier was reused"
            );
            return Ok(DeletionOutcome::Duplicate);
        }

        let membership = super::relay::membership_state(&self.db, authorization.vault_id())
            .map_err(|_| signer_not_accepted())?;
        let device = membership
            .device(authorization.device_id())
            .ok_or_else(|| {
                Error::new(
                    ChurStatus::AuthenticationFailed,
                    "deletion signer is not enrolled",
                )
            })?;
        ensure!(
            device.status() == DeviceStatus::Active,
            AuthenticationFailed,
            "deletion signer is revoked"
        );
        authorization.verify_signature(device.signing_public_key())?;

        match authorization.target_kind() {
            DeletionTargetKind::Object => self.delete_object(authorization)?,
            DeletionTargetKind::Account => self.delete_account(authorization)?,
        }
        Ok(DeletionOutcome::Deleted)
    }

    fn delete_object(&mut self, authorization: &ServerDeletionAuthorization) -> Result<()> {
        let operation_exists: bool = self
            .db
            .query_row(
                "SELECT EXISTS(
                    SELECT 1 FROM operations WHERE vault_id = ?1 AND digest = ?2
                 )",
                params![
                    authorization.vault_id().as_bytes().as_slice(),
                    authorization.authorizing_operation_digest().as_slice(),
                ],
                |row| row.get(0),
            )
            .map_err(|error| map_sqlite(error, "deletion operation lookup failed"))?;
        ensure!(
            operation_exists,
            AuthenticationFailed,
            "authorizing deletion operation is absent"
        );
        let transfer_id: Vec<u8> = self
            .db
            .query_row(
                "SELECT transfer_id FROM object_transfers
                 WHERE vault_id = ?1 AND store_id = ?2",
                params![
                    authorization.vault_id().as_bytes().as_slice(),
                    authorization.target_id().as_bytes().as_slice(),
                ],
                |row| row.get(0),
            )
            .optional()
            .map_err(|error| map_sqlite(error, "deletion object lookup failed"))?
            .ok_or_else(|| Error::new(ChurStatus::NotFound, "deletion object is absent"))?;
        let transfer_id =
            Id::from_slice(&transfer_id).map_err(corrupt_row("stored transfer id is invalid"))?;
        remove_file_if_present(
            &self.object_path(*authorization.vault_id(), *authorization.target_id()),
        )?;
        remove_file_if_present(&self.partial_path(*authorization.vault_id(), transfer_id))?;

        let transaction = self
            .db
            .transaction()
            .map_err(|error| map_sqlite(error, "object deletion transaction failed"))?;
        insert_receipt(&transaction, authorization)?;
        transaction
            .execute(
                "DELETE FROM object_transfers WHERE vault_id = ?1 AND store_id = ?2",
                params![
                    authorization.vault_id().as_bytes().as_slice(),
                    authorization.target_id().as_bytes().as_slice(),
                ],
            )
            .map_err(|error| map_sqlite(error, "object deletion record failed"))?;
        transaction
            .commit()
            .map_err(|error| map_sqlite(error, "object deletion commit failed"))?;
        Ok(())
    }

    fn delete_account(&mut self, authorization: &ServerDeletionAuthorization) -> Result<()> {
        remove_dir_if_present(
            &self
                .root
                .join("objects")
                .join(authorization.vault_id().to_hex()),
        )?;
        remove_dir_if_present(
            &self
                .root
                .join("uploads")
                .join(authorization.vault_id().to_hex()),
        )?;
        let transaction = self
            .db
            .transaction()
            .map_err(|error| map_sqlite(error, "account deletion transaction failed"))?;
        insert_receipt(&transaction, authorization)?;
        for sql in [
            "DELETE FROM object_transfers WHERE vault_id = ?1",
            "DELETE FROM operations WHERE vault_id = ?1",
            "DELETE FROM membership_records WHERE vault_id = ?1",
            "DELETE FROM checkpoints WHERE vault_id = ?1",
            "DELETE FROM transport_tokens WHERE vault_id = ?1",
            "DELETE FROM collection_operations WHERE issuer_vault_id = ?1",
            "DELETE FROM collection_grants
              WHERE issuer_vault_id = ?1 OR recipient_vault_id = ?1",
            "DELETE FROM collection_membership_records
              WHERE issuer_vault_id = ?1 OR recipient_vault_id = ?1",
        ] {
            transaction
                .execute(sql, params![authorization.vault_id().as_bytes().as_slice()])
                .map_err(|error| map_sqlite(error, "account record deletion failed"))?;
        }
        transaction
            .execute(
                "DELETE FROM deletion_requests
                 WHERE vault_id = ?1 AND request_id != ?2",
                params![
                    authorization.vault_id().as_bytes().as_slice(),
                    authorization.request_id().as_bytes().as_slice(),
                ],
            )
            .map_err(|error| map_sqlite(error, "old deletion receipt removal failed"))?;
        transaction
            .commit()
            .map_err(|error| map_sqlite(error, "account deletion commit failed"))?;
        self.db
            .execute_batch("PRAGMA wal_checkpoint(TRUNCATE);")
            .map_err(|error| map_sqlite(error, "account deletion checkpoint failed"))?;
        Ok(())
    }
}

fn signer_not_accepted() -> Error {
    Error::new(
        ChurStatus::AuthenticationFailed,
        "deletion signer is not accepted",
    )
}

pub(super) fn account_was_deleted(db: &Connection, vault_id: &Id) -> Result<bool> {
    db.query_row(
        "SELECT EXISTS(
            SELECT 1 FROM deletion_requests WHERE vault_id = ?1 AND target_kind = 2
         )",
        params![vault_id.as_bytes().as_slice()],
        |row| row.get(0),
    )
    .map_err(|error| map_sqlite(error, "account deletion marker lookup failed"))
}

fn insert_receipt(
    db: &rusqlite::Transaction<'_>,
    authorization: &ServerDeletionAuthorization,
) -> Result<()> {
    db.execute(
        "INSERT INTO deletion_requests (
            vault_id, request_id, target_kind, target_id, record
         ) VALUES (?1, ?2, ?3, ?4, ?5)",
        params![
            authorization.vault_id().as_bytes().as_slice(),
            authorization.request_id().as_bytes().as_slice(),
            authorization.target_kind() as u8,
            authorization.target_id().as_bytes().as_slice(),
            authorization.encode(),
        ],
    )
    .map_err(|error| map_sqlite(error, "deletion receipt storage failed"))?;
    Ok(())
}

fn remove_file_if_present(path: &Path) -> Result<()> {
    match fs::remove_file(path) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == ErrorKind::NotFound => Ok(()),
        Err(_) => Err(Error::new(
            ChurStatus::StorageUnavailable,
            "authorized object removal failed",
        )),
    }
}

fn remove_dir_if_present(path: &Path) -> Result<()> {
    match fs::remove_dir_all(path) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == ErrorKind::NotFound => Ok(()),
        Err(_) => Err(Error::new(
            ChurStatus::StorageUnavailable,
            "authorized account storage removal failed",
        )),
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::expect_used)]

    use chur_core::Id;
    use chur_sync_protocol::{
        deletion::ServerDeletionAuthorization,
        membership::EnrollmentRecord,
        operation::{DeviceSigningKey, Operation},
    };
    use sha2::{Digest, Sha256};

    use super::*;

    #[test]
    fn signed_object_and_account_deletions_are_scoped_and_idempotent() {
        let root = crate::tests::TestRoot::new();
        let vault = id(1);
        let device = id(2);
        let key = DeviceSigningKey::from_seed([3; 32]);
        let enrollment = EnrollmentRecord::initial(vault, device, key.verifying_key(), [4; 32])
            .expect("enrollment")
            .sign(&key);
        let operation = Operation::new(
            id(5),
            vault,
            device,
            1,
            [0; 32],
            Vec::new(),
            id(6),
            [vec![7; 24], vec![8; 16]].concat(),
            [0; 64],
        )
        .expect("operation")
        .sign(&key);
        let transfer = id(9);
        let store = id(10);
        let bytes = b"opaque";

        let mut server = ReferenceServer::open(&root.0, 32, 32_768).expect("server");
        server
            .accept_initial_membership(&enrollment, &operation)
            .expect("bootstrap");
        let token = [15; 32];
        server
            .set_transport_token(vault, device, &token)
            .expect("transport token");
        server
            .begin_upload(vault, transfer, store, bytes.len() as u64)
            .expect("begin upload");
        server
            .append_upload(vault, transfer, 0, bytes, Sha256::digest(bytes).into())
            .expect("upload");
        server
            .finish_upload(vault, transfer, Sha256::digest(bytes).into())
            .expect("finish");

        // Phase-4 sharing rows of the account, both as an issuer and as a
        // recipient, must go with the account rows.
        server
            .db
            .execute(
                "INSERT INTO collection_membership_records (
                     collection_id, membership_generation, issuer_vault_id,
                     issuer_signing_public_key, recipient_vault_id, recipient_device_id,
                     outer_device_id, outer_device_sequence, record
                 ) VALUES (?1, 1, ?1, ?2, ?3, ?4, ?4, 1, X'00')",
                params![
                    id(30).as_bytes().as_slice(),
                    key.verifying_key().as_slice(),
                    id(31).as_bytes().as_slice(),
                    id(32).as_bytes().as_slice(),
                ],
            )
            .expect("issuer-side sharing row");
        server
            .db
            .execute(
                "INSERT INTO collection_membership_records (
                     collection_id, membership_generation, issuer_vault_id,
                     issuer_signing_public_key, recipient_vault_id, recipient_device_id,
                     outer_device_id, outer_device_sequence, record
                 ) VALUES (?1, 1, ?2, ?3, ?4, ?5, ?5, 1, X'00')",
                params![
                    id(33).as_bytes().as_slice(),
                    id(34).as_bytes().as_slice(),
                    &[7u8; 32],
                    vault.as_bytes().as_slice(),
                    device.as_bytes().as_slice(),
                ],
            )
            .expect("recipient-side sharing row");
        server
            .db
            .execute(
                "INSERT INTO collection_grants (
                     grant_id, collection_id, collection_epoch, issuer_vault_id,
                     recipient_vault_id, recipient_device_id, outer_device_id,
                     outer_device_sequence, key_selector, record
                 ) VALUES (?1, ?2, 1, ?3, ?4, ?5, ?6, 1, ?7, X'00')",
                params![
                    id(35).as_bytes().as_slice(),
                    id(30).as_bytes().as_slice(),
                    vault.as_bytes().as_slice(),
                    id(31).as_bytes().as_slice(),
                    id(32).as_bytes().as_slice(),
                    device.as_bytes().as_slice(),
                    id(36).as_bytes().as_slice(),
                ],
            )
            .expect("grant sharing row");
        server
            .db
            .execute(
                "INSERT INTO collection_operations (
                     key_selector, issuer_vault_id, issuer_device_id, device_sequence,
                     operation_id, digest, record
                 ) VALUES (?1, ?2, ?3, 1, ?4, ?5, X'00')",
                params![
                    id(36).as_bytes().as_slice(),
                    vault.as_bytes().as_slice(),
                    device.as_bytes().as_slice(),
                    id(37).as_bytes().as_slice(),
                    &[0u8; 32],
                ],
            )
            .expect("collection operation sharing row");

        let forged =
            ServerDeletionAuthorization::object(id(13), vault, device, store, operation.digest())
                .expect("forged authorization")
                .sign(&DeviceSigningKey::from_seed([14; 32]));
        assert_eq!(
            server
                .apply_deletion(&forged)
                .expect_err("wrong signing key")
                .status(),
            ChurStatus::AuthenticationFailed
        );
        let object =
            ServerDeletionAuthorization::object(id(11), vault, device, store, operation.digest())
                .expect("object authorization")
                .sign(&key);
        assert_eq!(
            server.apply_deletion(&object).expect("delete object"),
            DeletionOutcome::Deleted
        );
        assert_eq!(
            server
                .apply_deletion(&object)
                .expect("replay object delete"),
            DeletionOutcome::Duplicate
        );
        assert!(server.read_object(vault, store, 0, 8).is_err());
        let reused_request = ServerDeletionAuthorization::account(id(11), vault, device).sign(&key);
        assert_eq!(
            server
                .apply_deletion(&reused_request)
                .expect_err("request identifier reuse")
                .status(),
            ChurStatus::Conflict
        );

        let account = ServerDeletionAuthorization::account(id(12), vault, device).sign(&key);
        assert_eq!(
            server.apply_deletion(&account).expect("delete account"),
            DeletionOutcome::Deleted
        );
        let token_count: i64 = server
            .db
            .query_row(
                "SELECT count(*) FROM transport_tokens WHERE vault_id = ?1",
                [vault.as_bytes().as_slice()],
                |row| row.get(0),
            )
            .expect("token count");
        assert_eq!(token_count, 0);
        for sql in [
            "SELECT count(*) FROM collection_membership_records
             WHERE issuer_vault_id = ?1 OR recipient_vault_id = ?1",
            "SELECT count(*) FROM collection_grants
             WHERE issuer_vault_id = ?1 OR recipient_vault_id = ?1",
            "SELECT count(*) FROM collection_operations WHERE issuer_vault_id = ?1",
        ] {
            let sharing_count: i64 = server
                .db
                .query_row(sql, [vault.as_bytes().as_slice()], |row| row.get(0))
                .expect("sharing row count");
            assert_eq!(sharing_count, 0);
        }
        drop(server);
        let mut server = ReferenceServer::open(&root.0, 32, 32_768).expect("reopen");
        assert_eq!(
            server
                .apply_deletion(&account)
                .expect("replay account delete"),
            DeletionOutcome::Duplicate
        );
        assert!(server.accept_operation(&operation).is_err());
        assert!(
            server
                .accept_initial_membership(&enrollment, &operation)
                .is_err()
        );
    }

    /// A deletion that is not authenticated learns nothing about stored state.
    ///
    /// The signer key comes from the stored membership, so damaged membership
    /// authenticates no request. Before this rule, a caller with no device key
    /// got `CATALOG_CORRUPT` for damaged membership and `NOT_FOUND` for an
    /// unknown vault. The replay rule of `SYNC_PROTOCOL_V1.md` §9.1 still
    /// answers before authentication.
    #[test]
    fn an_unauthenticated_deletion_learns_nothing_about_stored_state() {
        let root = crate::tests::TestRoot::new();
        let (vault, device, store, transfer) = (id(1), id(2), id(10), id(9));
        let key = DeviceSigningKey::from_seed([3; 32]);
        let enrollment = EnrollmentRecord::initial(vault, device, key.verifying_key(), [4; 32])
            .expect("enrollment")
            .sign(&key);
        let operation = Operation::new(
            id(5),
            vault,
            device,
            1,
            [0; 32],
            Vec::new(),
            id(6),
            [vec![7; 24], vec![8; 16]].concat(),
            [0; 64],
        )
        .expect("operation")
        .sign(&key);
        let bytes = b"opaque";
        let checksum: [u8; 32] = Sha256::digest(bytes).into();
        let mut server = ReferenceServer::open(&root.0, 32, 32_768).expect("server");
        server
            .accept_initial_membership(&enrollment, &operation)
            .expect("bootstrap");
        server
            .begin_upload(vault, transfer, store, bytes.len() as u64)
            .expect("begin upload");
        server
            .append_upload(vault, transfer, 0, bytes, checksum)
            .expect("upload");
        server
            .finish_upload(vault, transfer, checksum)
            .expect("finish");
        let signed = |request: u8, signer: &DeviceSigningKey| {
            ServerDeletionAuthorization::object(
                id(request),
                vault,
                device,
                store,
                operation.digest(),
            )
            .expect("authorization")
            .sign(signer)
        };
        let applied = signed(11, &key);
        assert_eq!(
            server
                .apply_deletion(&applied)
                .expect("authenticated delete"),
            DeletionOutcome::Deleted
        );

        let mut initial = enrollment.encode();
        *initial.last_mut().expect("signature") ^= 1;
        server
            .db
            .execute(
                "UPDATE membership_records SET record = ?1 WHERE vault_id = ?2",
                params![initial, vault.as_bytes().as_slice()],
            )
            .expect("damaged membership");
        let observed = [
            server.apply_deletion(&signed(12, &DeviceSigningKey::from_seed([14; 32]))),
            server.apply_deletion(&signed(13, &key)),
            server.apply_deletion(
                &ServerDeletionAuthorization::account(id(15), id(20), device).sign(&key),
            ),
            server.apply_deletion(&applied),
            server.apply_deletion(
                &ServerDeletionAuthorization::account(id(11), vault, device).sign(&key),
            ),
        ]
        .map(|result| result.map_err(|error| error.status()));
        assert_eq!(
            observed,
            [
                // A wrong key.
                Err(ChurStatus::AuthenticationFailed),
                // The enrolled key: membership does not replay, so no key verifies.
                Err(ChurStatus::AuthenticationFailed),
                // An unknown vault.
                Err(ChurStatus::AuthenticationFailed),
                // An exact replay.
                Ok(DeletionOutcome::Duplicate),
                // A reused request identifier.
                Err(ChurStatus::Conflict),
            ]
        );
    }

    fn id(byte: u8) -> Id {
        Id::new([byte; 16]).expect("id")
    }
}
