//! Replay, rollback, gap, and fork handling for signed device logs.

use std::collections::BTreeMap;

use chur_core::{ChurStatus, Error, Id, Result, ensure};
use chur_crypto::{Commitment, Key, Nonce};

use crate::{
    checkpoint::Checkpoint,
    convergence::CausalStamp,
    membership::EnrollmentRecord,
    operation::{DeviceSigningKey, ObservedHead, Operation},
    state::{DeviceStatus, MembershipState},
};

/// Result of offering one authenticated operation.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ApplyOutcome {
    /// The next operation was accepted.
    Applied,
    /// The exact accepted record was offered again.
    Duplicate,
    /// One or more earlier operations from this device are absent.
    PendingGap,
    /// One of the operation's cross-device causal heads is absent.
    PendingCause,
}

/// Result of accepting a signed checkpoint as a freshness lower bound.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CheckpointOutcome {
    /// At least one per-device floor increased.
    Raised,
    /// Every checkpoint head was at or below an equal local floor.
    Unchanged,
}

#[derive(Clone)]
struct AcceptedHead {
    sequence: u64,
    digest: Commitment,
}

#[derive(Clone)]
struct AcceptedRecord {
    digest: Commitment,
    bytes: Vec<u8>,
}

/// Persisted lifecycle of a detected fork.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum ForkState {
    /// Conflicting signed bytes were detected.
    Detected,
    /// The user has seen the report; the chain remains frozen.
    Acknowledged,
}

/// Signed records retained for reconciliation or incident evidence.
#[derive(Clone)]
pub struct ForkEvidence {
    state: ForkState,
    accepted_record: Vec<u8>,
    conflicting_record: Vec<u8>,
}

impl ForkEvidence {
    /// Current persisted fork state.
    #[must_use]
    pub const fn state(&self) -> ForkState {
        self.state
    }
    /// Previously accepted signed record.
    #[must_use]
    pub fn accepted_record(&self) -> &[u8] {
        &self.accepted_record
    }
    /// Conflicting signed record.
    #[must_use]
    pub fn conflicting_record(&self) -> &[u8] {
        &self.conflicting_record
    }
}

/// Accepted per-device operation heads and fork state.
#[derive(Clone, Default)]
pub struct OperationLog {
    heads: BTreeMap<Id, AcceptedHead>,
    accepted: BTreeMap<(Id, u64), AcceptedRecord>,
    operation_ids: BTreeMap<Id, Commitment>,
    floors: BTreeMap<Id, AcceptedHead>,
    checkpoints: BTreeMap<Id, Commitment>,
    forks: BTreeMap<Id, ForkEvidence>,
}

impl OperationLog {
    /// Empty accepted log.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Builds the next signed operation from the current accepted local view.
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
        ensure!(
            membership.vault_id() == &vault_id,
            AuthenticationFailed,
            "local operation belongs to another vault"
        );
        let device = membership.device(&device_id).ok_or_else(|| {
            Error::new(
                ChurStatus::AuthenticationFailed,
                "local operation author is not enrolled",
            )
        })?;
        ensure!(
            device.status() == DeviceStatus::Active
                && device.signing_public_key() == &signing_key.verifying_key(),
            AuthenticationFailed,
            "local operation key is not the current active device key"
        );
        let (device_sequence, previous_operation_hash) = self.head(&device_id).map_or_else(
            || Ok((1, [0; 32])),
            |(sequence, digest)| {
                Ok::<_, Error>((
                    sequence.checked_add(1).ok_or_else(|| {
                        Error::new(
                            ChurStatus::ResourceLimitExceeded,
                            "local device sequence has no successor",
                        )
                    })?,
                    digest,
                ))
            },
        )?;
        let observed_heads = self
            .heads
            .iter()
            .filter(|(observed_id, _)| {
                *observed_id != &device_id
                    && membership
                        .device(observed_id)
                        .is_some_and(|observed| observed.status() == DeviceStatus::Active)
            })
            .map(|(observed_id, head)| ObservedHead::new(*observed_id, head.sequence))
            .collect();
        let operation = Operation::seal(
            operation_id,
            vault_id,
            device_id,
            device_sequence,
            previous_operation_hash,
            observed_heads,
            key_selector,
            key,
            nonce,
            plaintext,
        )?
        .sign(signing_key);
        let mut candidate = self.clone();
        ensure!(
            candidate.accept(&operation, membership)? == ApplyOutcome::Applied,
            SyncHeadRollback,
            "local operation chain is not ready to advance"
        );
        Ok(operation)
    }

    /// Replays one record already committed in the protected local catalog.
    ///
    /// Unlike the receive path, this commits each prefix of a revoked device's
    /// already-accepted chain. The caller validates the final durable head
    /// after replay, so process restart does not need the whole chain in memory.
    pub fn restore_accepted(
        &mut self,
        operation: &Operation,
        membership: &MembershipState,
    ) -> Result<ApplyOutcome> {
        let device = membership.device(operation.device_id()).ok_or_else(|| {
            Error::new(
                ChurStatus::AuthenticationFailed,
                "operation author is not enrolled",
            )
        })?;
        let cutoff = match device.status() {
            DeviceStatus::Active => None,
            DeviceStatus::Revoked { sequence, digest } => Some((sequence, digest)),
        };
        self.accept_inner(operation, membership, cutoff)
    }

    /// Restores one authenticated checkpoint floor from the protected catalog.
    pub fn restore_floor(
        &mut self,
        device_id: &Id,
        sequence: u64,
        digest: Commitment,
        membership: &MembershipState,
    ) -> Result<()> {
        ensure!(
            membership.device(device_id).is_some() && sequence != 0 && digest != [0; 32],
            CatalogCorrupt,
            "a durable checkpoint floor is malformed"
        );
        if let Some(record) = self.accepted.get(&(*device_id, sequence)) {
            ensure!(
                record.digest == digest,
                SyncChainFork,
                "a durable floor conflicts with accepted history"
            );
        }
        if let Some(current) = self.floors.get(device_id) {
            ensure!(
                sequence > current.sequence
                    || (sequence == current.sequence && digest == current.digest),
                SyncHeadRollback,
                "a durable floor moves backwards or changes branch"
            );
        }
        self.floors
            .insert(*device_id, AcceptedHead { sequence, digest });
        Ok(())
    }

    /// Restores the commitment of a checkpoint whose signed record was verified.
    pub fn restore_checkpoint_commitment(
        &mut self,
        issuer_device_id: &Id,
        commitment: Commitment,
        membership: &MembershipState,
    ) -> Result<()> {
        ensure!(
            membership.device(issuer_device_id).is_some() && commitment != [0; 32],
            CatalogCorrupt,
            "a durable checkpoint commitment is malformed"
        );
        self.checkpoints.insert(*issuer_device_id, commitment);
        Ok(())
    }

    /// Validates and accepts one operation from an active device.
    pub fn accept(
        &mut self,
        operation: &Operation,
        membership: &MembershipState,
    ) -> Result<ApplyOutcome> {
        let device = membership.device(operation.device_id()).ok_or_else(|| {
            Error::new(
                ChurStatus::AuthenticationFailed,
                "operation author is not enrolled",
            )
        })?;
        match device.status() {
            DeviceStatus::Active => {
                if self.floor_satisfied(operation.device_id()) {
                    return self.accept_inner(operation, membership, None);
                }
                let mut candidate = self.clone();
                let outcome = match candidate.accept_inner(operation, membership, None) {
                    Ok(outcome) => outcome,
                    Err(error) => {
                        if error.status() == ChurStatus::SyncChainFork {
                            *self = candidate;
                        }
                        return Err(error);
                    }
                };
                if candidate.floor_satisfied(operation.device_id()) {
                    *self = candidate;
                    Ok(outcome)
                } else {
                    Ok(ApplyOutcome::PendingGap)
                }
            }
            DeviceStatus::Revoked { sequence, digest } => {
                let mut candidate = self.clone();
                let outcome =
                    match candidate.accept_inner(operation, membership, Some((sequence, digest))) {
                        Ok(outcome) => outcome,
                        Err(error) => {
                            if error.status() == ChurStatus::SyncChainFork {
                                *self = candidate;
                            }
                            return Err(error);
                        }
                    };
                if candidate.head_matches(operation.device_id(), sequence, &digest) {
                    *self = candidate;
                    Ok(outcome)
                } else {
                    Ok(ApplyOutcome::PendingGap)
                }
            }
        }
    }

    /// Accepts a signed checkpoint without lowering any local floor.
    pub fn accept_checkpoint(
        &mut self,
        checkpoint: &Checkpoint,
        membership: &MembershipState,
    ) -> Result<CheckpointOutcome> {
        ensure!(
            checkpoint.vault_id() == membership.vault_id(),
            AuthenticationFailed,
            "checkpoint belongs to another vault"
        );
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
            issuer.status() == DeviceStatus::Active,
            AuthenticationFailed,
            "checkpoint issuer is revoked"
        );
        if !issuer
            .signing_public_keys()
            .any(|key| checkpoint.verify_signature(key).is_ok())
        {
            return Err(Error::new(
                ChurStatus::AuthenticationFailed,
                "checkpoint signature did not verify under device key history",
            ));
        }

        let mut candidate = self.clone();
        let mut outcome = CheckpointOutcome::Unchanged;
        for checkpoint_head in checkpoint.heads() {
            ensure!(
                membership.device(checkpoint_head.device_id()).is_some(),
                AuthenticationFailed,
                "checkpoint names an unknown device"
            );
            let offered = AcceptedHead {
                sequence: checkpoint_head.device_sequence(),
                digest: *checkpoint_head.operation_digest(),
            };
            if matches!(
                candidate
                    .accepted
                    .get(&(*checkpoint_head.device_id(), offered.sequence)),
                Some(record) if record.digest != offered.digest
            ) {
                return self.reject_checkpoint_fork(
                    candidate,
                    checkpoint_head.device_id(),
                    checkpoint,
                );
            }
            if let Some(current) = candidate.floors.get(checkpoint_head.device_id()) {
                if offered.sequence < current.sequence {
                    continue;
                }
                if offered.sequence == current.sequence {
                    if offered.digest != current.digest {
                        return self.reject_checkpoint_fork(
                            candidate,
                            checkpoint_head.device_id(),
                            checkpoint,
                        );
                    }
                    continue;
                }
            }
            if let Some(current) = candidate.heads.get(checkpoint_head.device_id()) {
                if offered.sequence < current.sequence {
                    continue;
                }
                if offered.sequence == current.sequence {
                    if offered.digest != current.digest {
                        return self.reject_checkpoint_fork(
                            candidate,
                            checkpoint_head.device_id(),
                            checkpoint,
                        );
                    }
                    continue;
                }
            }
            candidate
                .floors
                .insert(*checkpoint_head.device_id(), offered);
            outcome = CheckpointOutcome::Raised;
        }
        candidate
            .checkpoints
            .insert(*checkpoint.issuer_device_id(), checkpoint.commitment());
        *self = candidate;
        Ok(outcome)
    }

    /// Establishes a new device's first freshness floor from its enrollment.
    pub fn bootstrap_from_enrollment(
        &mut self,
        enrollment: &EnrollmentRecord,
        checkpoint: &Checkpoint,
        membership: &MembershipState,
    ) -> Result<CheckpointOutcome> {
        ensure!(
            self.heads.is_empty()
                && self.accepted.is_empty()
                && self.floors.is_empty()
                && self.checkpoints.is_empty()
                && self.forks.is_empty(),
            Conflict,
            "bootstrap requires an empty operation log"
        );
        ensure!(
            enrollment.vault_id() == membership.vault_id()
                && enrollment.membership_generation() == membership.generation()
                && &enrollment.commitment() == membership.commitment()
                && membership.is_active(enrollment.device_id())
                && enrollment.device_id() != enrollment.issuer_device_id(),
            AuthenticationFailed,
            "enrollment is not the accepted membership head"
        );
        ensure!(
            checkpoint.commitment() == *enrollment.bootstrap_checkpoint_commitment()
                && checkpoint.vault_id() == membership.vault_id()
                && checkpoint.issuer_device_id() == enrollment.issuer_device_id(),
            AuthenticationFailed,
            "checkpoint does not match the enrollment attestation"
        );
        ensure!(
            checkpoint
                .membership_generation()
                .checked_add(1)
                .is_some_and(|generation| generation == enrollment.membership_generation())
                && checkpoint.membership_commitment()
                    == enrollment.previous_membership_commitment(),
            SyncHeadRollback,
            "checkpoint does not precede the enrolled membership generation"
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
            "bootstrap checkpoint issuer or signature is not accepted"
        );

        let mut floors = BTreeMap::new();
        for head in checkpoint.heads() {
            ensure!(
                membership.device(head.device_id()).is_some()
                    && head.device_id() != enrollment.device_id(),
                AuthenticationFailed,
                "bootstrap checkpoint names a device outside prior membership"
            );
            floors.insert(
                *head.device_id(),
                AcceptedHead {
                    sequence: head.device_sequence(),
                    digest: *head.operation_digest(),
                },
            );
        }
        self.floors = floors;
        self.checkpoints
            .insert(*checkpoint.issuer_device_id(), checkpoint.commitment());
        Ok(CheckpointOutcome::Raised)
    }

    /// Atomically accepts one device's chain through its checkpoint floor.
    pub fn accept_device_chain(
        &mut self,
        operations: &[Operation],
        membership: &MembershipState,
    ) -> Result<Vec<ApplyOutcome>> {
        let first = operations
            .first()
            .ok_or_else(|| Error::new(ChurStatus::InvalidInput, "device chain is empty"))?;
        let device_id = *first.device_id();
        if matches!(
            membership.device(&device_id).map(|device| device.status()),
            Some(DeviceStatus::Revoked { .. })
        ) {
            return self.accept_revoked_chain(operations, membership);
        }
        let mut candidate = self.clone();
        let mut outcomes = Vec::with_capacity(operations.len());
        for operation in operations {
            ensure!(
                operation.device_id() == &device_id,
                InvalidInput,
                "device chain mixes devices"
            );
            match candidate.accept_inner(operation, membership, None) {
                Ok(outcome) => outcomes.push(outcome),
                Err(error) => {
                    if error.status() == ChurStatus::SyncChainFork {
                        *self = candidate;
                    }
                    return Err(error);
                }
            }
        }
        if !candidate.floor_satisfied(&device_id) {
            return Ok(vec![ApplyOutcome::PendingGap]);
        }
        *self = candidate;
        Ok(outcomes)
    }

    /// Atomically accepts a contiguous revoked-device chain through its pinned point.
    pub fn accept_revoked_chain(
        &mut self,
        operations: &[Operation],
        membership: &MembershipState,
    ) -> Result<Vec<ApplyOutcome>> {
        let first = operations
            .first()
            .ok_or_else(|| Error::new(ChurStatus::InvalidInput, "revoked-device chain is empty"))?;
        let device_id = *first.device_id();
        let device = membership.device(&device_id).ok_or_else(|| {
            Error::new(
                ChurStatus::AuthenticationFailed,
                "operation author is not enrolled",
            )
        })?;
        let (cutoff_sequence, cutoff_digest) = match device.status() {
            DeviceStatus::Revoked { sequence, digest } => (sequence, digest),
            DeviceStatus::Active => {
                return Err(Error::new(
                    ChurStatus::InvalidInput,
                    "device is not revoked",
                ));
            }
        };
        let mut candidate = self.clone();
        let mut outcomes = Vec::with_capacity(operations.len());
        for operation in operations {
            ensure!(
                operation.device_id() == &device_id,
                InvalidInput,
                "revoked chain mixes devices"
            );
            match candidate.accept_inner(
                operation,
                membership,
                Some((cutoff_sequence, cutoff_digest)),
            ) {
                Ok(outcome) => outcomes.push(outcome),
                Err(error) => {
                    if error.status() == ChurStatus::SyncChainFork {
                        *self = candidate;
                    }
                    return Err(error);
                }
            }
        }
        if !candidate.head_matches(&device_id, cutoff_sequence, &cutoff_digest) {
            return Ok(vec![ApplyOutcome::PendingGap]);
        }
        *self = candidate;
        Ok(outcomes)
    }

    /// Fork evidence for a frozen device chain.
    #[must_use]
    pub fn fork(&self, device_id: &Id) -> Option<&ForkEvidence> {
        self.forks.get(device_id)
    }

    /// Records that the user has seen a fork report without unfreezing it.
    pub fn acknowledge_fork(&mut self, device_id: &Id) -> Result<()> {
        let evidence = self
            .forks
            .get_mut(device_id)
            .ok_or_else(|| Error::new(ChurStatus::NotFound, "device chain has no fork"))?;
        evidence.state = ForkState::Acknowledged;
        Ok(())
    }

    /// Clears a frozen chain only after membership accepted its revocation.
    pub fn resolve_by_revocation(
        &mut self,
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
        self.forks
            .remove(device_id)
            .ok_or_else(|| Error::new(ChurStatus::NotFound, "device chain has no fork"))?;
        Ok(())
    }

    fn accept_inner(
        &mut self,
        operation: &Operation,
        membership: &MembershipState,
        cutoff: Option<(u64, Commitment)>,
    ) -> Result<ApplyOutcome> {
        ensure!(
            operation.vault_id() == membership.vault_id(),
            AuthenticationFailed,
            "operation belongs to another vault"
        );
        if self.forks.contains_key(operation.device_id()) {
            return Err(Error::new(
                ChurStatus::SyncChainFork,
                "device chain is frozen after a fork",
            ));
        }
        let device = membership.device(operation.device_id()).ok_or_else(|| {
            Error::new(
                ChurStatus::AuthenticationFailed,
                "operation author is not enrolled",
            )
        })?;
        if !device
            .signing_public_keys()
            .any(|key| operation.verify_signature(key).is_ok())
        {
            return Err(Error::new(
                ChurStatus::AuthenticationFailed,
                "operation signature did not verify under device key history",
            ));
        }
        let digest = operation.digest();
        if let Some((sequence, pinned_digest)) = cutoff {
            ensure!(
                operation.device_sequence() <= sequence,
                AuthenticationFailed,
                "operation is above the accepted revocation point"
            );
            if operation.device_sequence() == sequence && digest != pinned_digest {
                return self.freeze(operation);
            }
        }

        let record_key = (*operation.device_id(), operation.device_sequence());
        if let Some(accepted) = self.accepted.get(&record_key) {
            if digest == accepted.digest {
                return Ok(ApplyOutcome::Duplicate);
            }
            return self.freeze(operation);
        }
        if self.floors.get(operation.device_id()).is_some_and(|floor| {
            operation.device_sequence() == floor.sequence && digest != floor.digest
        }) {
            return self.freeze(operation);
        }
        ensure!(
            self.operation_ids
                .get(operation.operation_id())
                .is_none_or(|accepted| accepted == &digest),
            AuthenticationFailed,
            "operation identifier was reused at another log position"
        );
        if let Some(head) = self.heads.get(operation.device_id()) {
            if operation.device_sequence() < head.sequence {
                return Err(Error::new(
                    ChurStatus::SyncHeadRollback,
                    "operation is below the accepted device head",
                ));
            }
            if operation.device_sequence() > head.sequence.saturating_add(1) {
                return Ok(ApplyOutcome::PendingGap);
            }
            if operation.previous_operation_hash() != &head.digest {
                return self.freeze(operation);
            }
        } else if operation.device_sequence() != 1 {
            return Ok(ApplyOutcome::PendingGap);
        }

        for observed in operation.observed_heads() {
            ensure!(
                membership.device(observed.device_id()).is_some(),
                AuthenticationFailed,
                "operation observes an unknown device"
            );
            if self
                .heads
                .get(observed.device_id())
                .is_none_or(|head| head.sequence < observed.device_sequence())
            {
                return Ok(ApplyOutcome::PendingCause);
            }
        }
        let bytes = operation.encode();
        self.accepted
            .insert(record_key, AcceptedRecord { digest, bytes });
        self.operation_ids.insert(*operation.operation_id(), digest);
        self.heads.insert(
            *operation.device_id(),
            AcceptedHead {
                sequence: operation.device_sequence(),
                digest,
            },
        );
        Ok(ApplyOutcome::Applied)
    }

    fn freeze(&mut self, operation: &Operation) -> Result<ApplyOutcome> {
        let same_sequence = (*operation.device_id(), operation.device_sequence());
        let accepted_record = self.accepted.get(&same_sequence).map_or_else(
            || {
                self.heads
                    .get(operation.device_id())
                    .and_then(|head| self.accepted.get(&(*operation.device_id(), head.sequence)))
                    .map_or_else(Vec::new, |record| record.bytes.clone())
            },
            |record| record.bytes.clone(),
        );
        self.forks.insert(
            *operation.device_id(),
            ForkEvidence {
                state: ForkState::Detected,
                accepted_record,
                conflicting_record: operation.encode(),
            },
        );
        Err(Error::new(
            ChurStatus::SyncChainFork,
            "signed operation conflicts with the accepted device chain",
        ))
    }

    fn head_matches(&self, device_id: &Id, sequence: u64, digest: &Commitment) -> bool {
        self.heads
            .get(device_id)
            .is_some_and(|head| head.sequence == sequence && &head.digest == digest)
    }

    fn floor_satisfied(&self, device_id: &Id) -> bool {
        self.floors.get(device_id).is_none_or(|floor| {
            self.accepted
                .get(&(*device_id, floor.sequence))
                .is_some_and(|record| record.digest == floor.digest)
        })
    }

    fn reject_checkpoint_fork(
        &mut self,
        mut candidate: Self,
        device_id: &Id,
        checkpoint: &Checkpoint,
    ) -> Result<CheckpointOutcome> {
        let accepted_record = checkpoint
            .heads()
            .iter()
            .find(|head| head.device_id() == device_id)
            .and_then(|head| self.accepted.get(&(*device_id, head.device_sequence())))
            .map_or_else(Vec::new, |record| record.bytes.clone());
        candidate.forks.insert(
            *device_id,
            ForkEvidence {
                state: ForkState::Detected,
                accepted_record,
                conflicting_record: checkpoint.encode(),
            },
        );
        *self = candidate;
        Err(Error::new(
            ChurStatus::SyncChainFork,
            "checkpoint conflicts with accepted device history",
        ))
    }

    /// Latest accepted head for one device.
    #[must_use]
    pub fn head(&self, device_id: &Id) -> Option<(u64, Commitment)> {
        self.heads
            .get(device_id)
            .map(|head| (head.sequence, head.digest))
    }

    /// Latest accepted causal position for every device chain.
    pub fn latest_operations(&self) -> Result<BTreeMap<Id, CausalStamp>> {
        self.heads
            .iter()
            .map(|(device_id, head)| {
                let record = self
                    .accepted
                    .get(&(*device_id, head.sequence))
                    .ok_or_else(|| {
                        Error::new(
                            ChurStatus::InternalFailure,
                            "an accepted device head has no operation record",
                        )
                    })?;
                let operation = Operation::decode(&record.bytes)?;
                ensure!(
                    operation.device_id() == device_id
                        && operation.device_sequence() == head.sequence
                        && operation.digest() == head.digest,
                    InternalFailure,
                    "an accepted device head does not match its operation record"
                );
                Ok((*device_id, CausalStamp::from_operation(&operation)))
            })
            .collect()
    }

    /// Durable checkpoint floor for one device.
    #[must_use]
    pub fn floor(&self, device_id: &Id) -> Option<(u64, Commitment)> {
        self.floors
            .get(device_id)
            .map(|floor| (floor.sequence, floor.digest))
    }

    /// Latest accepted checkpoint commitment from one issuer.
    #[must_use]
    pub fn checkpoint_commitment(&self, issuer_device_id: &Id) -> Option<&Commitment> {
        self.checkpoints.get(issuer_device_id)
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::expect_used, clippy::panic)]

    use super::*;
    use crate::{
        membership::{EnrollmentRecord, RevocationRecord},
        operation::DeviceSigningKey,
        state::MembershipState,
    };
    use chur_core::Id;
    use chur_crypto::{Key, Nonce};

    fn id(byte: u8) -> Id {
        Id::new([byte; 16]).expect("id")
    }

    fn operation_for(
        key: &DeviceSigningKey,
        device_id: Id,
        sequence: u64,
        previous: [u8; 32],
        marker: u8,
    ) -> Operation {
        Operation::seal(
            id(marker),
            id(1),
            device_id,
            sequence,
            previous,
            Vec::new(),
            id(9),
            &Key::new([8; 32]),
            Nonce::new([marker; 24]),
            &[marker],
        )
        .expect("operation")
        .sign(key)
    }

    fn operation(
        key: &DeviceSigningKey,
        sequence: u64,
        previous: [u8; 32],
        marker: u8,
    ) -> Operation {
        operation_for(key, id(2), sequence, previous, marker)
    }

    #[test]
    fn duplicate_is_idempotent_and_conflict_freezes_the_device_chain() {
        let key = DeviceSigningKey::from_seed([3; 32]);
        let enrollment = EnrollmentRecord::initial(id(1), id(2), key.verifying_key(), [4; 32])
            .expect("enrollment")
            .sign(&key);
        let membership = MembershipState::bootstrap(&enrollment).expect("membership");
        let mut log = OperationLog::new();
        let first = operation(&key, 1, [0; 32], 5);
        assert!(log.accept(&first, &membership).expect("first") == ApplyOutcome::Applied);
        assert!(log.accept(&first, &membership).expect("duplicate") == ApplyOutcome::Duplicate);
        let conflict = operation(&key, 1, [0; 32], 6);
        assert_eq!(
            log.accept(&conflict, &membership)
                .expect_err("fork")
                .status(),
            ChurStatus::SyncChainFork
        );
        let evidence = log.fork(&id(2)).expect("evidence");
        assert!(!evidence.accepted_record().is_empty());
        assert!(!evidence.conflicting_record().is_empty());
        log.acknowledge_fork(&id(2)).expect("acknowledge");
        assert!(log.fork(&id(2)).expect("evidence").state() == ForkState::Acknowledged);
        let second = operation(&key, 2, first.digest(), 7);
        assert_eq!(
            log.accept(&second, &membership)
                .expect_err("frozen")
                .status(),
            ChurStatus::SyncChainFork
        );
    }

    #[test]
    fn gaps_wait_and_old_exact_records_remain_idempotent() {
        let key = DeviceSigningKey::from_seed([3; 32]);
        let enrollment = EnrollmentRecord::initial(id(1), id(2), key.verifying_key(), [4; 32])
            .expect("enrollment")
            .sign(&key);
        let membership = MembershipState::bootstrap(&enrollment).expect("membership");
        let first = operation(&key, 1, [0; 32], 5);
        let second = operation(&key, 2, first.digest(), 7);
        let mut log = OperationLog::new();
        assert!(log.accept(&second, &membership).expect("gap") == ApplyOutcome::PendingGap);
        assert!(log.accept(&first, &membership).expect("first") == ApplyOutcome::Applied);
        assert!(log.accept(&second, &membership).expect("second") == ApplyOutcome::Applied);
        assert!(log.accept(&first, &membership).expect("old duplicate") == ApplyOutcome::Duplicate);
    }

    #[test]
    fn one_operation_identifier_cannot_name_a_second_log_position() {
        let key = DeviceSigningKey::from_seed([3; 32]);
        let enrollment = EnrollmentRecord::initial(id(1), id(2), key.verifying_key(), [4; 32])
            .expect("enrollment")
            .sign(&key);
        let membership = MembershipState::bootstrap(&enrollment).expect("membership");
        let first = operation(&key, 1, [0; 32], 5);
        let reused = Operation::seal(
            *first.operation_id(),
            id(1),
            id(2),
            2,
            first.digest(),
            Vec::new(),
            id(9),
            &Key::new([8; 32]),
            Nonce::new([6; 24]),
            &[6],
        )
        .expect("operation")
        .sign(&key);
        let mut log = OperationLog::new();
        assert!(log.accept(&first, &membership).expect("first") == ApplyOutcome::Applied);

        assert_eq!(
            log.accept(&reused, &membership)
                .expect_err("identifier reuse")
                .status(),
            ChurStatus::AuthenticationFailed
        );
        assert_eq!(log.head(&id(2)), Some((1, first.digest())));
    }

    #[test]
    fn key_rotation_keeps_historical_operations_verifiable() {
        let old_key = DeviceSigningKey::from_seed([3; 32]);
        let initial = EnrollmentRecord::initial(id(1), id(2), old_key.verifying_key(), [4; 32])
            .expect("initial")
            .sign(&old_key);
        let mut membership = MembershipState::bootstrap(&initial).expect("membership");
        let new_key = DeviceSigningKey::from_seed([5; 32]);
        let rotation = EnrollmentRecord::new(
            id(1),
            id(2),
            new_key.verifying_key(),
            [6; 32],
            2,
            id(2),
            2,
            initial.commitment(),
            [7; 32],
        )
        .expect("rotation")
        .sign(&old_key);
        membership
            .accept_enrollment(&rotation, &id(2), 2)
            .expect("accept rotation");

        let first = operation(&old_key, 1, [0; 32], 8);
        let second = operation(&new_key, 2, first.digest(), 9);
        let mut log = OperationLog::new();
        assert!(log.accept(&first, &membership).expect("old signature") == ApplyOutcome::Applied);
        assert!(log.accept(&second, &membership).expect("new signature") == ApplyOutcome::Applied);
    }

    #[test]
    fn local_authoring_allocates_the_chain_and_current_causal_vector() {
        let owner_key = DeviceSigningKey::from_seed([3; 32]);
        let initial = EnrollmentRecord::initial(id(1), id(2), owner_key.verifying_key(), [4; 32])
            .expect("initial")
            .sign(&owner_key);
        let mut membership = MembershipState::bootstrap(&initial).expect("membership");
        let peer_key = DeviceSigningKey::from_seed([5; 32]);
        let peer = EnrollmentRecord::new(
            id(1),
            id(6),
            peer_key.verifying_key(),
            [7; 32],
            2,
            id(2),
            2,
            initial.commitment(),
            [8; 32],
        )
        .expect("peer")
        .sign(&owner_key);
        membership
            .accept_enrollment(&peer, &id(2), 2)
            .expect("accept peer");
        let payload_key = Key::new([8; 32]);
        let mut log = OperationLog::new();

        let owner_first = log
            .author(
                id(10),
                id(1),
                id(2),
                id(9),
                &payload_key,
                Nonce::new([10; 24]),
                &[10],
                &owner_key,
                &membership,
            )
            .expect("owner first");
        assert_eq!(owner_first.device_sequence(), 1);
        assert!(owner_first.observed_heads().is_empty());
        assert_eq!(
            log.accept(&owner_first, &membership).expect("accept owner"),
            ApplyOutcome::Applied
        );

        let peer_first = log
            .author(
                id(11),
                id(1),
                id(6),
                id(9),
                &payload_key,
                Nonce::new([11; 24]),
                &[11],
                &peer_key,
                &membership,
            )
            .expect("peer first");
        assert!(peer_first.observed_heads() == [crate::operation::ObservedHead::new(id(2), 1)]);
        assert_eq!(
            log.accept(&peer_first, &membership).expect("accept peer"),
            ApplyOutcome::Applied
        );

        let owner_second = log
            .author(
                id(12),
                id(1),
                id(2),
                id(9),
                &payload_key,
                Nonce::new([12; 24]),
                &[12],
                &owner_key,
                &membership,
            )
            .expect("owner second");
        assert_eq!(owner_second.device_sequence(), 2);
        assert_eq!(
            owner_second.previous_operation_hash(),
            &owner_first.digest()
        );
        assert!(owner_second.observed_heads() == [crate::operation::ObservedHead::new(id(6), 1)]);
        assert_eq!(
            log.accept(&owner_second, &membership)
                .expect("accept owner second"),
            ApplyOutcome::Applied
        );
        let latest = log.latest_operations().expect("latest operations");
        assert_eq!(latest[&id(2)].operation_id(), owner_second.operation_id());
        assert_eq!(latest[&id(6)].operation_id(), peer_first.operation_id());
        let revocation = RevocationRecord::new(
            id(1),
            id(6),
            1,
            peer_first.digest(),
            3,
            id(2),
            peer.commitment(),
        )
        .expect("revocation")
        .sign(&owner_key);
        membership
            .accept_revocation(&revocation, &id(2))
            .expect("accept revocation");
        assert_eq!(
            membership.active_device_ids().copied().collect::<Vec<_>>(),
            vec![id(2)]
        );
        let owner_third = log
            .author(
                id(13),
                id(1),
                id(2),
                id(9),
                &payload_key,
                Nonce::new([13; 24]),
                &[13],
                &owner_key,
                &membership,
            )
            .expect("owner third");
        assert!(owner_third.observed_heads().is_empty());
        let Err(error) = log.author(
            id(14),
            id(1),
            id(2),
            id(9),
            &payload_key,
            Nonce::new([14; 24]),
            &[14],
            &DeviceSigningKey::from_seed([14; 32]),
            &membership,
        ) else {
            panic!("wrong current key authored an operation");
        };
        assert_eq!(error.status(), ChurStatus::AuthenticationFailed);
    }

    #[test]
    fn checkpoint_floor_requires_the_pinned_chain_before_advancing() {
        let key = DeviceSigningKey::from_seed([3; 32]);
        let enrollment = EnrollmentRecord::initial(id(1), id(2), key.verifying_key(), [4; 32])
            .expect("enrollment")
            .sign(&key);
        let membership = MembershipState::bootstrap(&enrollment).expect("membership");
        let first = operation(&key, 1, [0; 32], 5);
        let second = operation(&key, 2, first.digest(), 6);
        let checkpoint = crate::checkpoint::Checkpoint::new(
            id(1),
            id(2),
            2,
            1,
            enrollment.commitment(),
            vec![crate::checkpoint::CheckpointHead::new(
                id(2),
                2,
                second.digest(),
            )],
            [7; 32],
            [0; 32],
        )
        .expect("checkpoint")
        .sign(&key);
        let mut log = OperationLog::new();
        assert!(
            log.accept_checkpoint(&checkpoint, &membership)
                .expect("checkpoint")
                == CheckpointOutcome::Raised
        );
        assert!(
            log.accept(&first, &membership).expect("pending floor") == ApplyOutcome::PendingGap
        );
        assert!(log.head(&id(2)).is_none());
        assert_eq!(
            log.accept_device_chain(&[first, second], &membership)
                .expect("chain")
                .len(),
            2
        );
        assert_eq!(
            log.head(&id(2)),
            Some((2, *checkpoint.heads()[0].operation_digest()))
        );
        let conflicting = crate::checkpoint::Checkpoint::new(
            id(1),
            id(2),
            2,
            1,
            enrollment.commitment(),
            vec![crate::checkpoint::CheckpointHead::new(id(2), 2, [9; 32])],
            [7; 32],
            [0; 32],
        )
        .expect("conflicting checkpoint")
        .sign(&key);
        assert_eq!(
            log.accept_checkpoint(&conflicting, &membership)
                .expect_err("fork")
                .status(),
            ChurStatus::SyncChainFork,
        );
        assert!(log.fork(&id(2)).is_some());
    }

    #[test]
    fn new_device_bootstrap_uses_the_enrollment_attested_checkpoint_floor() {
        let owner_key = DeviceSigningKey::from_seed([3; 32]);
        let initial = EnrollmentRecord::initial(id(1), id(2), owner_key.verifying_key(), [4; 32])
            .expect("initial")
            .sign(&owner_key);
        let first = operation(&owner_key, 1, [0; 32], 5);
        let checkpoint = crate::checkpoint::Checkpoint::new(
            id(1),
            id(2),
            1,
            1,
            initial.commitment(),
            vec![crate::checkpoint::CheckpointHead::new(
                id(2),
                1,
                first.digest(),
            )],
            [10; 32],
            [0; 32],
        )
        .expect("checkpoint")
        .sign(&owner_key);
        let peer_key = DeviceSigningKey::from_seed([8; 32]);
        let peer = EnrollmentRecord::new(
            id(1),
            id(3),
            peer_key.verifying_key(),
            [9; 32],
            2,
            id(2),
            2,
            initial.commitment(),
            checkpoint.commitment(),
        )
        .expect("peer")
        .sign(&owner_key);
        let mut membership = MembershipState::bootstrap(&initial).expect("membership");
        membership
            .accept_enrollment(&peer, &id(2), 2)
            .expect("accept peer");

        let mut log = OperationLog::new();
        assert_eq!(
            log.bootstrap_from_enrollment(&peer, &checkpoint, &membership)
                .expect("bootstrap"),
            CheckpointOutcome::Raised
        );
        assert_eq!(
            log.checkpoint_commitment(&id(2)),
            Some(&checkpoint.commitment())
        );
        assert!(log.accept(&first, &membership).expect("floor") == ApplyOutcome::Applied);

        let stale = crate::checkpoint::Checkpoint::new(
            id(1),
            id(2),
            1,
            1,
            initial.commitment(),
            vec![crate::checkpoint::CheckpointHead::new(
                id(2),
                1,
                first.digest(),
            )],
            [6; 32],
            [0; 32],
        )
        .expect("stale checkpoint")
        .sign(&owner_key);
        assert_eq!(
            OperationLog::new()
                .bootstrap_from_enrollment(&peer, &stale, &membership)
                .expect_err("commitment mismatch")
                .status(),
            ChurStatus::AuthenticationFailed
        );
    }

    #[test]
    fn revoked_device_chain_is_atomic_through_its_pinned_digest() {
        let issuer_key = DeviceSigningKey::from_seed([3; 32]);
        let initial = EnrollmentRecord::initial(id(1), id(2), issuer_key.verifying_key(), [4; 32])
            .expect("initial")
            .sign(&issuer_key);
        let mut membership = MembershipState::bootstrap(&initial).expect("membership");
        let revoked_key = DeviceSigningKey::from_seed([5; 32]);
        let enrollment = crate::membership::EnrollmentRecord::new(
            id(1),
            id(6),
            revoked_key.verifying_key(),
            [7; 32],
            2,
            id(2),
            2,
            initial.commitment(),
            [8; 32],
        )
        .expect("enrollment")
        .sign(&issuer_key);
        membership
            .accept_enrollment(&enrollment, &id(2), 2)
            .expect("accept enrollment");
        let first = operation_for(&revoked_key, id(6), 1, [0; 32], 10);
        let second = operation_for(&revoked_key, id(6), 2, first.digest(), 11);
        let revocation = crate::membership::RevocationRecord::new(
            id(1),
            id(6),
            2,
            second.digest(),
            3,
            id(2),
            enrollment.commitment(),
        )
        .expect("revocation")
        .sign(&issuer_key);
        membership
            .accept_revocation(&revocation, &id(2))
            .expect("accept revocation");

        let mut log = OperationLog::new();
        assert!(log.accept(&first, &membership).expect("pending") == ApplyOutcome::PendingGap);
        assert!(log.head(&id(6)).is_none());
        assert_eq!(
            log.accept_revoked_chain(&[first.clone(), second.clone()], &membership)
                .expect("chain")
                .len(),
            2
        );
        assert_eq!(
            log.head(&id(6)),
            Some((2, revocation.final_accepted_operation_digest().to_owned()))
        );
        let mut restored = OperationLog::new();
        assert_eq!(
            restored
                .restore_accepted(&first, &membership)
                .expect("restore first"),
            ApplyOutcome::Applied
        );
        assert_eq!(restored.head(&id(6)), Some((1, first.digest())));
        assert_eq!(
            restored
                .restore_accepted(&second, &membership)
                .expect("restore second"),
            ApplyOutcome::Applied
        );
        restored
            .restore_floor(&id(6), 2, second.digest(), &membership)
            .expect("restore floor");
        assert_eq!(restored.floor(&id(6)), Some((2, second.digest())));
        let above = operation_for(
            &revoked_key,
            id(6),
            3,
            *revocation.final_accepted_operation_digest(),
            12,
        );
        assert_eq!(
            log.accept(&above, &membership)
                .expect_err("above cutoff")
                .status(),
            ChurStatus::AuthenticationFailed
        );
    }
}
