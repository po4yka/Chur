//! Signed opaque server deletion authorization.

use chur_core::{ChurStatus, Id, Result, ensure};
use chur_crypto::{Commitment, tuple::tag};
use chur_format::codec::{Reader, Writer};

use crate::operation::{DeviceSigningKey, PROTOCOL_VERSION_V1, verify_ed25519};

const SIGNATURE_LEN: usize = 64;

/// Opaque server-side deletion target.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum DeletionTargetKind {
    /// One immutable object stored under an opaque identifier.
    Object = 0x01,
    /// The whole self-hosted account and its records.
    Account = 0x02,
}

impl DeletionTargetKind {
    fn decode(value: u8) -> Result<Self> {
        match value {
            0x01 => Ok(Self::Object),
            0x02 => Ok(Self::Account),
            _ => Err(chur_core::Error::new(
                ChurStatus::UnsupportedVersion,
                "server deletion target kind is not supported",
            )),
        }
    }
}

/// Canonical signed authorization for object or account deletion.
#[derive(Clone, PartialEq, Eq)]
pub struct ServerDeletionAuthorization {
    request_id: Id,
    vault_id: Id,
    device_id: Id,
    target_kind: DeletionTargetKind,
    target_id: Id,
    authorizing_operation_digest: Commitment,
    signature: [u8; SIGNATURE_LEN],
}

impl ServerDeletionAuthorization {
    /// Exact encoded length.
    pub const LEN: usize = 2 + (16 * 4) + 1 + 32 + SIGNATURE_LEN;

    /// Creates an unsigned object-deletion authorization.
    pub fn object(
        request_id: Id,
        vault_id: Id,
        device_id: Id,
        store_id: Id,
        authorizing_operation_digest: Commitment,
    ) -> Result<Self> {
        let authorization = Self {
            request_id,
            vault_id,
            device_id,
            target_kind: DeletionTargetKind::Object,
            target_id: store_id,
            authorizing_operation_digest,
            signature: [0; SIGNATURE_LEN],
        };
        authorization.validate()?;
        Ok(authorization)
    }

    /// Creates an unsigned whole-account deletion authorization.
    #[must_use]
    pub fn account(request_id: Id, vault_id: Id, device_id: Id) -> Self {
        Self {
            request_id,
            vault_id,
            device_id,
            target_kind: DeletionTargetKind::Account,
            target_id: vault_id,
            authorizing_operation_digest: [0; 32],
            signature: [0; SIGNATURE_LEN],
        }
    }

    /// Signs every canonical field except the signature itself.
    #[must_use]
    pub fn sign(mut self, key: &DeviceSigningKey) -> Self {
        self.signature = key.sign_bytes(&self.signing_bytes());
        self
    }

    /// Verifies the authorization under one enrolled device key.
    pub fn verify_signature(&self, verifying_key: &[u8; 32]) -> Result<()> {
        verify_ed25519(verifying_key, &self.signature, &self.signing_bytes())
    }

    /// Encodes the exact 163-byte record.
    #[must_use]
    pub fn encode(&self) -> Vec<u8> {
        let mut writer = Writer::with_capacity(Self::LEN);
        writer
            .u16(PROTOCOL_VERSION_V1)
            .id(&self.request_id)
            .id(&self.vault_id)
            .id(&self.device_id)
            .u8(self.target_kind as u8)
            .id(&self.target_id)
            .fixed(&self.authorizing_operation_digest)
            .fixed(&self.signature);
        debug_assert_eq!(writer.len(), Self::LEN);
        writer.finish()
    }

    /// Decodes and validates one canonical authorization.
    pub fn decode(bytes: &[u8]) -> Result<Self> {
        ensure!(
            bytes.len() == Self::LEN,
            NonCanonicalEncoding,
            "server deletion authorization is not 163 bytes"
        );
        let mut reader = Reader::new(bytes, ChurStatus::NonCanonicalEncoding);
        ensure!(
            reader.u16()? == PROTOCOL_VERSION_V1,
            UnsupportedVersion,
            "server deletion authorization version is not supported"
        );
        let request_id = reader.id()?;
        let vault_id = reader.id()?;
        let device_id = reader.id()?;
        let target_kind = DeletionTargetKind::decode(reader.u8()?)?;
        let target_id = reader.id()?;
        let authorizing_operation_digest = reader.fixed::<32>()?;
        let signature = reader.fixed::<SIGNATURE_LEN>()?;
        reader.finish()?;
        let authorization = Self {
            request_id,
            vault_id,
            device_id,
            target_kind,
            target_id,
            authorizing_operation_digest,
            signature,
        };
        authorization.validate()?;
        Ok(authorization)
    }

    /// Idempotency identifier of this request.
    #[must_use]
    pub const fn request_id(&self) -> &Id {
        &self.request_id
    }

    /// Vault/account whose storage is affected.
    #[must_use]
    pub const fn vault_id(&self) -> &Id {
        &self.vault_id
    }

    /// Device that signed this authorization.
    #[must_use]
    pub const fn device_id(&self) -> &Id {
        &self.device_id
    }

    /// Kind of opaque server target.
    #[must_use]
    pub const fn target_kind(&self) -> DeletionTargetKind {
        self.target_kind
    }

    /// Opaque store identifier, or the vault identifier for account deletion.
    #[must_use]
    pub const fn target_id(&self) -> &Id {
        &self.target_id
    }

    /// Tombstone operation digest for object deletion, or zero for an account.
    #[must_use]
    pub const fn authorizing_operation_digest(&self) -> &Commitment {
        &self.authorizing_operation_digest
    }

    fn signing_bytes(&self) -> Vec<u8> {
        let mut record = self.encode();
        record.truncate(Self::LEN - SIGNATURE_LEN);
        let mut bytes = Vec::with_capacity(tag::SYNC_SERVER_DELETE.len() + record.len());
        bytes.extend_from_slice(tag::SYNC_SERVER_DELETE);
        bytes.extend_from_slice(&record);
        bytes
    }

    fn validate(&self) -> Result<()> {
        match self.target_kind {
            DeletionTargetKind::Object => ensure!(
                self.authorizing_operation_digest != [0; 32],
                NonCanonicalEncoding,
                "object deletion has no authorizing operation"
            ),
            DeletionTargetKind::Account => ensure!(
                self.target_id == self.vault_id && self.authorizing_operation_digest == [0; 32],
                NonCanonicalEncoding,
                "account deletion target or operation digest is invalid"
            ),
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::expect_used)]

    use chur_core::{ChurStatus, Id};
    use chur_crypto::Commitment;

    use super::*;
    use crate::operation::DeviceSigningKey;

    fn id(byte: u8) -> Id {
        Id::new([byte; 16]).expect("id")
    }

    #[test]
    fn object_and_account_authorizations_round_trip_and_authenticate() {
        let key = DeviceSigningKey::from_seed([7; 32]);
        let operation_digest: Commitment = [8; 32];
        let object =
            ServerDeletionAuthorization::object(id(1), id(2), id(3), id(4), operation_digest)
                .expect("object authorization")
                .sign(&key);
        assert_eq!(object.encode().len(), ServerDeletionAuthorization::LEN);
        let decoded = ServerDeletionAuthorization::decode(&object.encode()).expect("decode");
        assert_eq!(decoded.target_kind(), DeletionTargetKind::Object);
        assert_eq!(decoded.target_id(), &id(4));
        assert_eq!(decoded.authorizing_operation_digest(), &operation_digest);
        decoded
            .verify_signature(&key.verifying_key())
            .expect("signature");

        let account = ServerDeletionAuthorization::account(id(5), id(2), id(3)).sign(&key);
        assert_eq!(account.target_kind(), DeletionTargetKind::Account);
        assert_eq!(account.target_id(), account.vault_id());
        assert_eq!(account.authorizing_operation_digest(), &[0; 32]);
        assert!(ServerDeletionAuthorization::decode(&account.encode()).is_ok());
    }

    #[test]
    fn tamper_wrong_key_and_invalid_target_form_fail_closed() {
        let key = DeviceSigningKey::from_seed([7; 32]);
        let object = ServerDeletionAuthorization::object(id(1), id(2), id(3), id(4), [8; 32])
            .expect("object authorization")
            .sign(&key);
        assert_eq!(
            object
                .verify_signature(&DeviceSigningKey::from_seed([9; 32]).verifying_key())
                .expect_err("wrong key")
                .status(),
            ChurStatus::AuthenticationFailed
        );
        let mut bytes = object.encode();
        bytes[51] ^= 1;
        let changed = ServerDeletionAuthorization::decode(&bytes).expect("canonical tamper");
        assert_eq!(
            changed
                .verify_signature(&key.verifying_key())
                .expect_err("tamper")
                .status(),
            ChurStatus::AuthenticationFailed
        );
        bytes[50] = 2;
        assert!(ServerDeletionAuthorization::decode(&bytes).is_err());
    }
}
