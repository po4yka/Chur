//! The signed checkpoint of `docs/sync/ROLLBACK_PROTECTION.md` §6.

use chur_core::limits::{COMMITMENT_LEN, ID_LEN, sync as bounds};
use chur_core::{ChurStatus, Id, Result, ensure};
use chur_crypto::{Commitment, commit, tuple::tag};
use chur_format::codec::{Reader, Writer};

use crate::operation::{DeviceSigningKey, PROTOCOL_VERSION_V1, verify_ed25519};

const SIGNATURE_LEN: usize = 64;
const FIXED_FIELDS_LEN: usize = 2 + (ID_LEN * 2) + 8 + 8 + (COMMITMENT_LEN * 3) + 4 + SIGNATURE_LEN;

/// One accepted device head recorded by a checkpoint.
#[derive(Clone, PartialEq, Eq)]
pub struct CheckpointHead {
    device_id: Id,
    device_sequence: u64,
    operation_digest: [u8; COMMITMENT_LEN],
}

impl CheckpointHead {
    /// Builds one accepted head.
    #[must_use]
    pub const fn new(
        device_id: Id,
        device_sequence: u64,
        operation_digest: [u8; COMMITMENT_LEN],
    ) -> Self {
        Self {
            device_id,
            device_sequence,
            operation_digest,
        }
    }
}

/// One canonical signed checkpoint.
#[derive(Clone, PartialEq, Eq)]
pub struct Checkpoint {
    vault_id: Id,
    issuer_device_id: Id,
    issuer_device_sequence: u64,
    membership_generation: u64,
    membership_commitment: [u8; COMMITMENT_LEN],
    heads: Vec<CheckpointHead>,
    collection_epoch_commitment: [u8; COMMITMENT_LEN],
    catalog_state_commitment: [u8; COMMITMENT_LEN],
    signature: [u8; SIGNATURE_LEN],
}

impl Checkpoint {
    /// Builds an unsigned checkpoint with a zero signature.
    #[expect(
        clippy::too_many_arguments,
        reason = "the arguments are the frozen wire fields"
    )]
    pub fn new(
        vault_id: Id,
        issuer_device_id: Id,
        issuer_device_sequence: u64,
        membership_generation: u64,
        membership_commitment: [u8; COMMITMENT_LEN],
        heads: Vec<CheckpointHead>,
        collection_epoch_commitment: [u8; COMMITMENT_LEN],
        catalog_state_commitment: [u8; COMMITMENT_LEN],
    ) -> Result<Self> {
        Self::from_fields(
            vault_id,
            issuer_device_id,
            issuer_device_sequence,
            membership_generation,
            membership_commitment,
            heads,
            collection_epoch_commitment,
            catalog_state_commitment,
            [0; SIGNATURE_LEN],
        )
    }

    #[expect(
        clippy::too_many_arguments,
        reason = "the arguments are the frozen wire fields"
    )]
    fn from_fields(
        vault_id: Id,
        issuer_device_id: Id,
        issuer_device_sequence: u64,
        membership_generation: u64,
        membership_commitment: [u8; COMMITMENT_LEN],
        heads: Vec<CheckpointHead>,
        collection_epoch_commitment: [u8; COMMITMENT_LEN],
        catalog_state_commitment: [u8; COMMITMENT_LEN],
        signature: [u8; SIGNATURE_LEN],
    ) -> Result<Self> {
        let checkpoint = Self {
            vault_id,
            issuer_device_id,
            issuer_device_sequence,
            membership_generation,
            membership_commitment,
            heads,
            collection_epoch_commitment,
            catalog_state_commitment,
            signature,
        };
        checkpoint.validate()?;
        Ok(checkpoint)
    }

    /// Signs every canonical field except the signature itself.
    #[must_use]
    pub fn sign(mut self, key: &DeviceSigningKey) -> Self {
        self.signature = key.sign_bytes(&self.signing_bytes());
        self
    }

    /// Verifies the issuer signature.
    pub fn verify_signature(&self, verifying_key: &[u8; 32]) -> Result<()> {
        verify_ed25519(verifying_key, &self.signature, &self.signing_bytes())
    }

    /// The portable commitment to the complete signed checkpoint.
    #[must_use]
    pub fn commitment(&self) -> Commitment {
        commit::commit(tag::SYNC_CHECKPOINT_COMMITMENT, &[&self.encode()])
    }

    /// Encodes the canonical checkpoint.
    #[must_use]
    pub fn encode(&self) -> Vec<u8> {
        let mut writer = Writer::with_capacity(
            FIXED_FIELDS_LEN + (self.heads.len() * bounds::CHECKPOINT_HEAD_LEN),
        );
        writer
            .u16(PROTOCOL_VERSION_V1)
            .id(&self.vault_id)
            .id(&self.issuer_device_id)
            .u64(self.issuer_device_sequence)
            .u64(self.membership_generation)
            .fixed(&self.membership_commitment);
        #[expect(
            clippy::cast_possible_truncation,
            reason = "checkpoint head count is bounded at 32"
        )]
        writer.u32(self.heads.len() as u32);
        for head in &self.heads {
            writer
                .id(&head.device_id)
                .u64(head.device_sequence)
                .fixed(&head.operation_digest);
        }
        writer
            .fixed(&self.collection_epoch_commitment)
            .fixed(&self.catalog_state_commitment)
            .fixed(&self.signature);
        writer.finish()
    }

    /// Decodes a canonical checkpoint.
    pub fn decode(bytes: &[u8]) -> Result<Self> {
        let mut reader = Reader::new(bytes, ChurStatus::NonCanonicalEncoding);
        ensure!(
            reader.u16()? == PROTOCOL_VERSION_V1,
            UnsupportedVersion,
            "checkpoint protocol version is not supported"
        );
        let vault_id = reader.id()?;
        let issuer_device_id = reader.id()?;
        let issuer_device_sequence = reader.u64()?;
        let membership_generation = reader.u64()?;
        let membership_commitment = reader.fixed::<COMMITMENT_LEN>()?;
        let head_count = usize::try_from(reader.u32()?).map_err(|_| {
            chur_core::Error::new(
                ChurStatus::ResourceLimitExceeded,
                "checkpoint head count does not fit this platform",
            )
        })?;
        ensure!(
            head_count <= bounds::CHECKPOINT_HEADS_MAX,
            ResourceLimitExceeded,
            "checkpoint head count exceeds the protocol limit"
        );
        let mut heads = Vec::with_capacity(head_count);
        for _ in 0..head_count {
            heads.push(CheckpointHead::new(
                reader.id()?,
                reader.u64()?,
                reader.fixed::<COMMITMENT_LEN>()?,
            ));
        }
        let collection_epoch_commitment = reader.fixed::<COMMITMENT_LEN>()?;
        let catalog_state_commitment = reader.fixed::<COMMITMENT_LEN>()?;
        let signature = reader.fixed::<SIGNATURE_LEN>()?;
        reader.finish()?;
        Self::from_fields(
            vault_id,
            issuer_device_id,
            issuer_device_sequence,
            membership_generation,
            membership_commitment,
            heads,
            collection_epoch_commitment,
            catalog_state_commitment,
            signature,
        )
    }

    fn signing_bytes(&self) -> Vec<u8> {
        let mut record = self.encode();
        record.truncate(record.len() - SIGNATURE_LEN);
        let mut bytes = Vec::with_capacity(tag::SYNC_CHECKPOINT.len() + record.len());
        bytes.extend_from_slice(tag::SYNC_CHECKPOINT);
        bytes.extend_from_slice(&record);
        bytes
    }

    fn validate(&self) -> Result<()> {
        ensure!(
            self.issuer_device_sequence != 0,
            NonCanonicalEncoding,
            "checkpoint issuer sequence is zero"
        );
        ensure!(
            self.membership_generation != 0,
            NonCanonicalEncoding,
            "checkpoint membership generation is zero"
        );
        ensure!(
            !self.heads.is_empty(),
            NonCanonicalEncoding,
            "checkpoint carries no heads"
        );
        ensure!(
            self.heads.len() <= bounds::CHECKPOINT_HEADS_MAX,
            ResourceLimitExceeded,
            "checkpoint head count exceeds the protocol limit"
        );
        let mut previous: Option<&Id> = None;
        let mut issuer_head_matches = false;
        for head in &self.heads {
            ensure!(
                head.device_sequence != 0 && head.operation_digest != [0; COMMITMENT_LEN],
                NonCanonicalEncoding,
                "checkpoint head is a genesis sentinel"
            );
            if let Some(previous) = previous {
                ensure!(
                    previous.as_bytes() < head.device_id.as_bytes(),
                    NonCanonicalEncoding,
                    "checkpoint heads are not sorted and unique"
                );
            }
            if head.device_id == self.issuer_device_id {
                issuer_head_matches = head.device_sequence == self.issuer_device_sequence;
            }
            previous = Some(&head.device_id);
        }
        ensure!(
            issuer_head_matches,
            NonCanonicalEncoding,
            "checkpoint does not carry the issuer's current head"
        );
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::expect_used, clippy::panic)]

    use super::*;

    fn id(byte: u8) -> Id {
        Id::new([byte; 16]).expect("id")
    }

    #[test]
    fn a_checkpoint_round_trips_with_its_issuer_head() {
        let key = DeviceSigningKey::from_seed([8; 32]);
        let checkpoint = Checkpoint::new(
            id(1),
            id(2),
            7,
            3,
            [4; 32],
            vec![CheckpointHead::new(id(2), 7, [5; 32])],
            [6; 32],
            [7; 32],
        )
        .expect("checkpoint")
        .sign(&key);
        checkpoint
            .verify_signature(&key.verifying_key())
            .expect("signature");
        let encoded = checkpoint.encode();
        assert_eq!(encoded.len(), 270);
        assert_eq!(
            Checkpoint::decode(&encoded).expect("decode").encode(),
            encoded
        );
        assert_ne!(checkpoint.commitment(), [0; COMMITMENT_LEN]);
    }
}
