//! The canonical outer sync operation of `docs/sync/OPERATION_LOG.md` §2.

use chur_core::limits::{COMMITMENT_LEN, ID_LEN, sync as bounds};
use chur_core::{ChurStatus, Id, Result, ensure};
use chur_format::codec::{Reader, Writer};

const SIGNATURE_LEN: usize = 64;
const FIXED_FIELDS_LEN: usize = 2 + (ID_LEN * 4) + 8 + COMMITMENT_LEN + 4 + 4 + SIGNATURE_LEN;

/// Sync protocol v1.
pub const PROTOCOL_VERSION_V1: u16 = 0x0001;

/// One other device head observed by an operation's author.
#[derive(Clone, PartialEq, Eq)]
pub struct ObservedHead {
    device_id: Id,
    device_sequence: u64,
}

impl ObservedHead {
    /// Builds an observed head.
    #[must_use]
    pub const fn new(device_id: Id, device_sequence: u64) -> Self {
        Self {
            device_id,
            device_sequence,
        }
    }

    /// The device whose accepted sequence this entry names.
    #[must_use]
    pub const fn device_id(&self) -> &Id {
        &self.device_id
    }

    /// The highest accepted sequence from that device.
    #[must_use]
    pub const fn device_sequence(&self) -> u64 {
        self.device_sequence
    }
}

/// One signed, encrypted sync operation.
#[derive(Clone, PartialEq, Eq)]
pub struct Operation {
    operation_id: Id,
    vault_id: Id,
    device_id: Id,
    device_sequence: u64,
    previous_operation_hash: [u8; 32],
    observed_heads: Vec<ObservedHead>,
    key_selector: Id,
    encrypted_payload: Vec<u8>,
    signature: [u8; 64],
}

impl Operation {
    /// Builds an operation. Validation is shared with [`Self::decode`].
    #[expect(
        clippy::too_many_arguments,
        reason = "the arguments are the frozen wire fields"
    )]
    pub fn new(
        operation_id: Id,
        vault_id: Id,
        device_id: Id,
        device_sequence: u64,
        previous_operation_hash: [u8; 32],
        observed_heads: Vec<ObservedHead>,
        key_selector: Id,
        encrypted_payload: Vec<u8>,
        signature: [u8; 64],
    ) -> Result<Self> {
        let operation = Self {
            operation_id,
            vault_id,
            device_id,
            device_sequence,
            previous_operation_hash,
            observed_heads,
            key_selector,
            encrypted_payload,
            signature,
        };
        operation.validate()?;
        Ok(operation)
    }

    /// Encodes the canonical wire record.
    #[must_use]
    pub fn encode(&self) -> Vec<u8> {
        let mut writer = Writer::with_capacity(
            FIXED_FIELDS_LEN
                + (self.observed_heads.len() * bounds::OBSERVED_HEAD_LEN)
                + self.encrypted_payload.len(),
        );
        writer
            .u16(PROTOCOL_VERSION_V1)
            .id(&self.operation_id)
            .id(&self.vault_id)
            .id(&self.device_id)
            .u64(self.device_sequence)
            .fixed(&self.previous_operation_hash);
        #[expect(
            clippy::cast_possible_truncation,
            reason = "validate bounds observed_heads at 31 entries"
        )]
        writer.u32(self.observed_heads.len() as u32);
        for head in &self.observed_heads {
            writer.id(&head.device_id).u64(head.device_sequence);
        }
        writer.id(&self.key_selector);
        #[expect(
            clippy::cast_possible_truncation,
            reason = "validate bounds encrypted_payload below u32::MAX"
        )]
        writer
            .u32(self.encrypted_payload.len() as u32)
            .fixed(&self.encrypted_payload)
            .fixed(&self.signature);
        writer.finish()
    }

    /// Decodes one canonical wire record.
    ///
    /// # Errors
    ///
    /// Returns a structural or bounds error for malformed input.
    pub fn decode(bytes: &[u8]) -> Result<Self> {
        let mut reader = Reader::new(bytes, ChurStatus::NonCanonicalEncoding);
        ensure!(
            reader.u16()? == PROTOCOL_VERSION_V1,
            UnsupportedVersion,
            "sync operation protocol version is not supported"
        );
        let operation_id = reader.id()?;
        let vault_id = reader.id()?;
        let device_id = reader.id()?;
        let device_sequence = reader.u64()?;
        let previous_operation_hash = reader.fixed::<COMMITMENT_LEN>()?;
        let head_count = usize::try_from(reader.u32()?).map_err(|_| {
            chur_core::Error::new(
                ChurStatus::ResourceLimitExceeded,
                "observed head count does not fit this platform",
            )
        })?;
        ensure!(
            head_count <= bounds::OBSERVED_HEADS_MAX,
            ResourceLimitExceeded,
            "observed head count exceeds the protocol limit"
        );
        let mut observed_heads = Vec::with_capacity(head_count);
        for _ in 0..head_count {
            observed_heads.push(ObservedHead::new(reader.id()?, reader.u64()?));
        }
        let key_selector = reader.id()?;
        #[expect(
            clippy::cast_possible_truncation,
            reason = "the protocol maximum is below u32::MAX"
        )]
        let encrypted_payload = reader
            .variable(bounds::ENCRYPTED_PAYLOAD_MAX as u32)?
            .to_vec();
        let signature = reader.fixed::<SIGNATURE_LEN>()?;
        reader.finish()?;
        Self::new(
            operation_id,
            vault_id,
            device_id,
            device_sequence,
            previous_operation_hash,
            observed_heads,
            key_selector,
            encrypted_payload,
            signature,
        )
    }

    fn validate(&self) -> Result<()> {
        ensure!(
            self.device_sequence != 0,
            NonCanonicalEncoding,
            "device sequence is zero"
        );
        let genesis = self.previous_operation_hash == [0; COMMITMENT_LEN];
        ensure!(
            (self.device_sequence == 1) == genesis,
            NonCanonicalEncoding,
            "device sequence and previous operation hash disagree"
        );
        ensure!(
            self.observed_heads.len() <= bounds::OBSERVED_HEADS_MAX,
            ResourceLimitExceeded,
            "observed head count exceeds the protocol limit"
        );
        let mut previous: Option<&Id> = None;
        for head in &self.observed_heads {
            ensure!(
                head.device_sequence != 0,
                NonCanonicalEncoding,
                "observed device sequence is zero"
            );
            ensure!(
                head.device_id != self.device_id,
                NonCanonicalEncoding,
                "observed heads contain the authoring device"
            );
            if let Some(previous) = previous {
                ensure!(
                    previous.as_bytes() < head.device_id.as_bytes(),
                    NonCanonicalEncoding,
                    "observed heads are not sorted and unique"
                );
            }
            previous = Some(&head.device_id);
        }
        ensure!(
            self.encrypted_payload.len() >= bounds::ENCRYPTED_PAYLOAD_MIN,
            NonCanonicalEncoding,
            "encrypted payload is shorter than a nonce and tag"
        );
        ensure!(
            self.encrypted_payload.len() <= bounds::ENCRYPTED_PAYLOAD_MAX,
            ResourceLimitExceeded,
            "encrypted payload exceeds the protocol limit"
        );
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::expect_used, clippy::panic, clippy::unwrap_used)]

    use super::*;

    fn id(byte: u8) -> Id {
        Id::new([byte; 16]).expect("non-zero identifier")
    }

    #[test]
    fn a_valid_operation_round_trips_through_canonical_bytes() {
        let operation = Operation::new(
            id(1),
            id(2),
            id(3),
            8,
            [4; 32],
            vec![ObservedHead::new(id(5), 7)],
            id(6),
            [vec![7; 24], vec![8; 16]].concat(),
            [9; 64],
        )
        .expect("valid operation");

        let bytes = operation.encode();
        assert_eq!(bytes.len(), 242);
        assert!(Operation::decode(&bytes).expect("decode") == operation);
    }
}
