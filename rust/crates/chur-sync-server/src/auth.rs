use chur_core::{ChurStatus, Error, Id, Result, ensure};
use chur_sync_protocol::state::DeviceStatus;
use rusqlite::{OptionalExtension, params};
use sha2::{Digest, Sha256};

use crate::{ReferenceServer, map_sqlite};

impl ReferenceServer {
    /// Installs or rotates one opaque transport credential for an active device.
    ///
    /// The embedding transport must expose this only after authenticating its
    /// enrollment or control-plane flow. The server stores only a SHA-256 digest.
    pub fn set_transport_token(
        &mut self,
        vault_id: Id,
        device_id: Id,
        token: &[u8; 32],
    ) -> Result<()> {
        let membership = super::relay::membership_state(&self.db, &vault_id)?;
        ensure!(
            membership
                .device(&device_id)
                .is_some_and(|device| device.status() == DeviceStatus::Active),
            AuthenticationFailed,
            "transport token device is not active"
        );
        self.db
            .execute(
                "INSERT INTO transport_tokens (vault_id, device_id, token_sha256)
                 VALUES (?1, ?2, ?3)
                 ON CONFLICT(vault_id, device_id) DO UPDATE SET
                     token_sha256 = excluded.token_sha256",
                params![
                    vault_id.as_bytes().as_slice(),
                    device_id.as_bytes().as_slice(),
                    token_digest(token).as_slice(),
                ],
            )
            .map_err(|error| map_sqlite(error, "transport token storage failed"))?;
        Ok(())
    }

    /// Authenticates one opaque transport credential and returns its active device.
    pub fn authenticate_transport(&self, vault_id: Id, token: &[u8; 32]) -> Result<Id> {
        let device: Option<Vec<u8>> = self
            .db
            .query_row(
                "SELECT device_id FROM transport_tokens
                 WHERE vault_id = ?1 AND token_sha256 = ?2",
                params![
                    vault_id.as_bytes().as_slice(),
                    token_digest(token).as_slice(),
                ],
                |row| row.get(0),
            )
            .optional()
            .map_err(|error| map_sqlite(error, "transport token lookup failed"))?;
        let device = device
            .as_deref()
            .and_then(|bytes| Id::from_slice(bytes).ok())
            .ok_or_else(authentication_failed)?;
        let membership = super::relay::membership_state(&self.db, &vault_id)
            .map_err(|_| authentication_failed())?;
        ensure!(
            membership
                .device(&device)
                .is_some_and(|known| known.status() == DeviceStatus::Active),
            AuthenticationFailed,
            "transport token is not accepted"
        );
        Ok(device)
    }

    /// Revokes one device's transport credential without changing membership.
    pub fn revoke_transport_token(&mut self, vault_id: Id, device_id: Id) -> Result<()> {
        self.db
            .execute(
                "DELETE FROM transport_tokens WHERE vault_id = ?1 AND device_id = ?2",
                params![
                    vault_id.as_bytes().as_slice(),
                    device_id.as_bytes().as_slice(),
                ],
            )
            .map_err(|error| map_sqlite(error, "transport token revocation failed"))?;
        Ok(())
    }
}

fn token_digest(token: &[u8; 32]) -> [u8; 32] {
    Sha256::digest(token).into()
}

pub(super) fn revoke_in_transaction(
    transaction: &rusqlite::Transaction<'_>,
    vault_id: &Id,
    device_id: &Id,
) -> Result<()> {
    transaction
        .execute(
            "DELETE FROM transport_tokens WHERE vault_id = ?1 AND device_id = ?2",
            params![
                vault_id.as_bytes().as_slice(),
                device_id.as_bytes().as_slice(),
            ],
        )
        .map_err(|error| map_sqlite(error, "transport token revocation failed"))?;
    Ok(())
}

fn authentication_failed() -> Error {
    Error::new(
        ChurStatus::AuthenticationFailed,
        "transport token is not accepted",
    )
}

#[cfg(test)]
mod tests {
    #![allow(clippy::expect_used)]

    use chur_core::{ChurStatus, Id};
    use chur_sync_protocol::{
        membership::EnrollmentRecord,
        operation::{DeviceSigningKey, Operation},
    };

    use crate::ReferenceServer;

    #[test]
    fn transport_tokens_are_hashed_rotatable_and_durable() {
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
        let first = [9; 32];
        let replacement = [10; 32];

        let mut server = ReferenceServer::open(&root.0, 1_024, 32_768).expect("server");
        server
            .accept_initial_membership(&enrollment, &operation)
            .expect("bootstrap");
        assert_eq!(
            server
                .set_transport_token(vault, id(11), &first)
                .expect_err("unknown device")
                .status(),
            ChurStatus::AuthenticationFailed
        );
        server
            .set_transport_token(vault, device, &first)
            .expect("set token");
        assert_eq!(
            server
                .authenticate_transport(vault, &first)
                .expect("authenticate"),
            device
        );
        server
            .set_transport_token(vault, device, &replacement)
            .expect("rotate token");
        assert_eq!(
            server
                .authenticate_transport(vault, &first)
                .expect_err("old token")
                .status(),
            ChurStatus::AuthenticationFailed
        );
        drop(server);

        let mut server = ReferenceServer::open(&root.0, 1_024, 32_768).expect("reopen");
        assert_eq!(
            server
                .authenticate_transport(vault, &replacement)
                .expect("authenticate after restart"),
            device
        );
        assert_eq!(
            server
                .authenticate_transport(id(12), &replacement)
                .expect_err("cross-vault token")
                .status(),
            ChurStatus::AuthenticationFailed
        );
        server
            .revoke_transport_token(vault, device)
            .expect("revoke token");
        server
            .revoke_transport_token(vault, device)
            .expect("repeat token revocation");
        assert_eq!(
            server
                .authenticate_transport(vault, &replacement)
                .expect_err("revoked credential")
                .status(),
            ChurStatus::AuthenticationFailed
        );
    }

    fn id(byte: u8) -> Id {
        Id::new([byte; 16]).expect("id")
    }
}
