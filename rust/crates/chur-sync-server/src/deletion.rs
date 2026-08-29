use std::fs;
use std::io::ErrorKind;
use std::path::Path;

use chur_core::{ChurStatus, Error, Id, Result, ensure};
use chur_sync_protocol::deletion::{DeletionTargetKind, ServerDeletionAuthorization};
use chur_sync_protocol::state::DeviceStatus;
use rusqlite::{Connection, OptionalExtension, params};

use super::{ReferenceServer, map_sqlite};

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
            .map_err(|error| map_sqlite(error, "deletion replay lookup failed"))?
        {
            ensure!(
                stored == authorization.encode(),
                Conflict,
                "deletion request identifier was reused"
            );
            return Ok(DeletionOutcome::Duplicate);
        }

        let membership = super::relay::membership_state(&self.db, authorization.vault_id())?;
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
        let transfer_id = Id::from_slice(&transfer_id)?;
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
        server
            .begin_upload(vault, transfer, store, bytes.len() as u64)
            .expect("begin upload");
        server
            .append_upload(vault, transfer, 0, bytes, Sha256::digest(bytes).into())
            .expect("upload");
        server
            .finish_upload(vault, transfer, Sha256::digest(bytes).into())
            .expect("finish");

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

    fn id(byte: u8) -> Id {
        Id::new([byte; 16]).expect("id")
    }
}
