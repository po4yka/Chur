use chur_core::limits::sync as bounds;
use chur_core::{ChurStatus, Error, Id, Result, ensure};
use chur_sync_protocol::checkpoint::Checkpoint;
use chur_sync_protocol::membership::EnrollmentRecord;
use chur_sync_protocol::state::{DeviceStatus, MembershipState};
use rusqlite::{Connection, OptionalExtension, params};

use super::{ReferenceServer, RelayOutcome, map_sqlite};

impl ReferenceServer {
    /// Verifies and stores one signed checkpoint over already stored heads.
    pub fn accept_checkpoint(&mut self, checkpoint: &Checkpoint) -> Result<RelayOutcome> {
        let commitment = checkpoint.commitment();
        if let Some(stored) = self
            .db
            .query_row(
                "SELECT record FROM checkpoints WHERE vault_id = ?1 AND commitment = ?2",
                params![
                    checkpoint.vault_id().as_bytes().as_slice(),
                    commitment.as_slice(),
                ],
                |row| row.get::<_, Vec<u8>>(0),
            )
            .optional()
            .map_err(|error| map_sqlite(error, "checkpoint replay lookup failed"))?
        {
            ensure!(
                stored == checkpoint.encode(),
                Conflict,
                "checkpoint commitment was reused"
            );
            return Ok(RelayOutcome::Duplicate);
        }
        let membership = super::relay::membership_state(&self.db, checkpoint.vault_id())?;
        ensure!(
            checkpoint.membership_generation() == membership.generation()
                && checkpoint.membership_commitment() == membership.commitment(),
            SyncHeadRollback,
            "checkpoint membership is not current"
        );
        let issuer = membership
            .device(checkpoint.issuer_device_id())
            .ok_or_else(|| {
                Error::new(
                    ChurStatus::AuthenticationFailed,
                    "checkpoint issuer is unknown",
                )
            })?;
        ensure!(
            issuer.status() == DeviceStatus::Active
                && issuer
                    .signing_public_keys()
                    .any(|key| checkpoint.verify_signature(key).is_ok()),
            AuthenticationFailed,
            "checkpoint issuer or signature is not accepted"
        );
        let issuer_sequence = checkpoint
            .heads()
            .iter()
            .find(|head| head.device_id() == checkpoint.issuer_device_id())
            .map(|head| head.device_sequence())
            .ok_or_else(|| {
                Error::new(
                    ChurStatus::AuthenticationFailed,
                    "checkpoint lacks its issuer head",
                )
            })?;
        let same_sequence: Option<Vec<u8>> = self
            .db
            .query_row(
                "SELECT record FROM checkpoints
                 WHERE vault_id = ?1 AND issuer_device_id = ?2 AND issuer_sequence = ?3",
                params![
                    checkpoint.vault_id().as_bytes().as_slice(),
                    checkpoint.issuer_device_id().as_bytes().as_slice(),
                    super::to_sqlite(issuer_sequence, "checkpoint issuer sequence does not fit")?,
                ],
                |row| row.get(0),
            )
            .optional()
            .map_err(|error| map_sqlite(error, "checkpoint issuer sequence lookup failed"))?;
        ensure!(
            same_sequence.is_none(),
            SyncChainFork,
            "checkpoint issuer sequence was reused"
        );
        for head in checkpoint.heads() {
            ensure!(
                membership.device(head.device_id()).is_some(),
                AuthenticationFailed,
                "checkpoint names an unknown device"
            );
            let stored: Option<Vec<u8>> = self
                .db
                .query_row(
                    "SELECT digest FROM operations
                     WHERE vault_id = ?1 AND device_id = ?2 AND device_sequence = ?3",
                    params![
                        checkpoint.vault_id().as_bytes().as_slice(),
                        head.device_id().as_bytes().as_slice(),
                        super::to_sqlite(
                            head.device_sequence(),
                            "checkpoint head sequence does not fit"
                        )?,
                    ],
                    |row| row.get(0),
                )
                .optional()
                .map_err(|error| map_sqlite(error, "checkpoint head lookup failed"))?;
            ensure!(
                stored.as_deref() == Some(head.operation_digest().as_slice()),
                SyncHeadRollback,
                "checkpoint head is absent or differs"
            );
        }
        self.ensure_account_capacity(checkpoint.vault_id(), checkpoint.encode().len())?;
        self.db
            .execute(
                "INSERT INTO checkpoints (
                    vault_id, issuer_device_id, issuer_sequence, commitment, record
                 ) VALUES (?1, ?2, ?3, ?4, ?5)",
                params![
                    checkpoint.vault_id().as_bytes().as_slice(),
                    checkpoint.issuer_device_id().as_bytes().as_slice(),
                    super::to_sqlite(issuer_sequence, "checkpoint issuer sequence does not fit")?,
                    commitment.as_slice(),
                    checkpoint.encode(),
                ],
            )
            .map_err(|error| map_sqlite(error, "checkpoint storage failed"))?;
        Ok(RelayOutcome::Stored)
    }

    /// Returns the latest signed checkpoint per issuer, bounded deterministically.
    pub fn checkpoints(&self, vault_id: Id) -> Result<Vec<Vec<u8>>> {
        let mut statement = self
            .db
            .prepare(
                "SELECT current.record FROM checkpoints AS current
                 WHERE current.vault_id = ?1
                   AND current.issuer_sequence = (
                       SELECT MAX(candidate.issuer_sequence) FROM checkpoints AS candidate
                       WHERE candidate.vault_id = current.vault_id
                         AND candidate.issuer_device_id = current.issuer_device_id
                   )
                 ORDER BY current.issuer_device_id LIMIT ?2",
            )
            .map_err(|error| map_sqlite(error, "checkpoint page prepare failed"))?;
        let rows = statement
            .query_map(
                params![
                    vault_id.as_bytes().as_slice(),
                    i64::try_from(bounds::RESPONSE_OPERATIONS_MAX).unwrap_or(i64::MAX),
                ],
                |row| row.get::<_, Vec<u8>>(0),
            )
            .map_err(|error| map_sqlite(error, "checkpoint page query failed"))?;
        super::relay::bounded_records(rows, "stored checkpoint page is invalid")
    }

    /// Fetches the exact checkpoint named by a signed commitment.
    pub fn checkpoint(&self, vault_id: Id, commitment: [u8; 32]) -> Result<Vec<u8>> {
        self.db
            .query_row(
                "SELECT record FROM checkpoints WHERE vault_id = ?1 AND commitment = ?2",
                params![vault_id.as_bytes().as_slice(), commitment.as_slice()],
                |row| row.get(0),
            )
            .optional()
            .map_err(|error| map_sqlite(error, "checkpoint commitment lookup failed"))?
            .ok_or_else(|| Error::new(ChurStatus::NotFound, "checkpoint is absent"))
    }
}

pub(super) fn verify_enrollment_checkpoint(
    db: &Connection,
    enrollment: &EnrollmentRecord,
    membership: &MembershipState,
) -> Result<()> {
    let record: Vec<u8> = db
        .query_row(
            "SELECT record FROM checkpoints WHERE vault_id = ?1 AND commitment = ?2",
            params![
                enrollment.vault_id().as_bytes().as_slice(),
                enrollment.bootstrap_checkpoint_commitment().as_slice(),
            ],
            |row| row.get(0),
        )
        .optional()
        .map_err(|error| map_sqlite(error, "enrollment checkpoint lookup failed"))?
        .ok_or_else(|| {
            Error::new(
                ChurStatus::AuthenticationFailed,
                "enrollment checkpoint is not stored",
            )
        })?;
    let checkpoint = Checkpoint::decode(&record)?;
    let issuer_head = checkpoint
        .heads()
        .iter()
        .find(|head| head.device_id() == enrollment.issuer_device_id())
        .ok_or_else(|| {
            Error::new(
                ChurStatus::AuthenticationFailed,
                "enrollment checkpoint lacks its issuer head",
            )
        })?;
    ensure!(
        checkpoint.commitment() == *enrollment.bootstrap_checkpoint_commitment()
            && checkpoint.vault_id() == enrollment.vault_id()
            && checkpoint.issuer_device_id() == enrollment.issuer_device_id()
            && checkpoint.membership_generation() == membership.generation()
            && checkpoint.membership_commitment() == membership.commitment()
            && issuer_head.device_sequence() < enrollment.created_sequence(),
        AuthenticationFailed,
        "enrollment checkpoint does not attest the current prior state"
    );
    Ok(())
}

#[cfg(test)]
mod tests {
    #![allow(clippy::expect_used)]

    use chur_core::Id;
    use chur_sync_protocol::{
        checkpoint::{Checkpoint, CheckpointHead},
        membership::EnrollmentRecord,
        operation::{DeviceSigningKey, Operation},
    };

    use super::*;

    #[test]
    fn checkpoint_relay_requires_stored_heads_and_survives_restart() {
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
        let checkpoint = Checkpoint::new(
            vault,
            device,
            1,
            1,
            enrollment.commitment(),
            vec![CheckpointHead::new(device, 1, operation.digest())],
            [9; 32],
            [0; 32],
        )
        .expect("checkpoint")
        .sign(&key);

        let mut server = ReferenceServer::open(&root.0, 1_024, 32_768).expect("server");
        assert!(server.accept_checkpoint(&checkpoint).is_err());
        server
            .accept_initial_membership(&enrollment, &operation)
            .expect("bootstrap");
        let wrong_head = Checkpoint::new(
            vault,
            device,
            1,
            1,
            enrollment.commitment(),
            vec![CheckpointHead::new(device, 1, [99; 32])],
            [9; 32],
            [0; 32],
        )
        .expect("wrong-head checkpoint")
        .sign(&key);
        assert_eq!(
            server
                .accept_checkpoint(&wrong_head)
                .expect_err("wrong checkpoint head")
                .status(),
            ChurStatus::SyncHeadRollback
        );
        assert_eq!(
            server.accept_checkpoint(&checkpoint).expect("checkpoint"),
            crate::RelayOutcome::Stored
        );
        assert_eq!(
            server
                .accept_checkpoint(&checkpoint)
                .expect("checkpoint replay"),
            crate::RelayOutcome::Duplicate
        );
        drop(server);

        let server = ReferenceServer::open(&root.0, 1_024, 32_768).expect("reopen");
        assert_eq!(
            server.checkpoints(vault).expect("fetch"),
            vec![checkpoint.encode()]
        );
        assert_eq!(
            server
                .checkpoint(vault, checkpoint.commitment())
                .expect("fetch by commitment"),
            checkpoint.encode()
        );
    }

    fn id(byte: u8) -> Id {
        Id::new([byte; 16]).expect("id")
    }
}
