//! The signed collection membership record of
//! `docs/sync/COLLECTION_MEMBERSHIP.md`.

use chur_core::{ChurStatus, Error, Id, Result, ensure};
use chur_crypto::{Commitment, commit, tuple::tag};
use chur_format::codec::{Reader, Writer};

use crate::grant::PermissionProfile;
use crate::operation::{DeviceSigningKey, PROTOCOL_VERSION_V1, verify_ed25519};

const PUBLIC_KEY_LEN: usize = 32;
const SIGNATURE_LEN: usize = 64;
const UPSERT_ACTION: u8 = 1;
const REVOKE_ACTION: u8 = 2;

/// One collection membership action.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum CollectionMembershipAction {
    /// Add a recipient device or replace its permission profile.
    Upsert(PermissionProfile),
    /// Remove one recipient device and advance the collection epoch.
    Revoke,
}

impl CollectionMembershipAction {
    fn encode(self) -> (u8, u8) {
        match self {
            Self::Upsert(permission) => (UPSERT_ACTION, permission as u8),
            Self::Revoke => (REVOKE_ACTION, 0),
        }
    }

    fn decode(action: u8, permission: u8) -> Result<Self> {
        match action {
            UPSERT_ACTION => Ok(Self::Upsert(PermissionProfile::decode(permission)?)),
            REVOKE_ACTION if permission == 0 => Ok(Self::Revoke),
            REVOKE_ACTION => Err(Error::new(
                ChurStatus::NonCanonicalEncoding,
                "collection revocation has a permission profile",
            )),
            _ => Err(Error::new(
                ChurStatus::UnsupportedVersion,
                "collection membership action is not supported",
            )),
        }
    }
}

/// One fixed signed collection membership change.
#[derive(Clone, PartialEq, Eq)]
pub struct CollectionMembershipRecord {
    source_vault_id: Id,
    collection_id: Id,
    collection_membership_generation: u64,
    previous_membership_commitment: Commitment,
    action: CollectionMembershipAction,
    recipient_identity_vault_id: Id,
    recipient_device_id: Id,
    recipient_signing_public_key: [u8; PUBLIC_KEY_LEN],
    recipient_hpke_public_key: [u8; PUBLIC_KEY_LEN],
    collection_epoch: u64,
    issuer_identity_vault_id: Id,
    issuer_device_id: Id,
    issuer_membership_generation: u64,
    created_sequence: u64,
    issuer_signature: [u8; SIGNATURE_LEN],
}

impl CollectionMembershipRecord {
    /// Exact canonical encoded length.
    pub const LEN: usize = 292;

    /// Builds one unsigned collection membership change.
    #[expect(
        clippy::too_many_arguments,
        reason = "the arguments are the frozen wire fields"
    )]
    pub fn new(
        source_vault_id: Id,
        collection_id: Id,
        collection_membership_generation: u64,
        previous_membership_commitment: Commitment,
        action: CollectionMembershipAction,
        recipient_identity_vault_id: Id,
        recipient_device_id: Id,
        recipient_signing_public_key: [u8; 32],
        recipient_hpke_public_key: [u8; 32],
        collection_epoch: u64,
        issuer_identity_vault_id: Id,
        issuer_device_id: Id,
        issuer_membership_generation: u64,
        created_sequence: u64,
    ) -> Result<Self> {
        Self::from_fields(
            source_vault_id,
            collection_id,
            collection_membership_generation,
            previous_membership_commitment,
            action,
            recipient_identity_vault_id,
            recipient_device_id,
            recipient_signing_public_key,
            recipient_hpke_public_key,
            collection_epoch,
            issuer_identity_vault_id,
            issuer_device_id,
            issuer_membership_generation,
            created_sequence,
            [0; SIGNATURE_LEN],
        )
    }

    /// Signs the canonical record with its issuer key.
    #[must_use]
    pub fn sign(mut self, key: &DeviceSigningKey) -> Self {
        self.issuer_signature = key.sign_bytes(&self.signing_bytes());
        self
    }

    /// Encodes the canonical record.
    #[must_use]
    pub fn encode(&self) -> Vec<u8> {
        let (action, permission) = self.action.encode();
        let mut writer = Writer::with_capacity(Self::LEN);
        writer
            .u16(PROTOCOL_VERSION_V1)
            .id(&self.source_vault_id)
            .id(&self.collection_id)
            .u64(self.collection_membership_generation)
            .fixed(&self.previous_membership_commitment)
            .u8(action)
            .id(&self.recipient_identity_vault_id)
            .id(&self.recipient_device_id)
            .fixed(&self.recipient_signing_public_key)
            .fixed(&self.recipient_hpke_public_key)
            .u8(permission)
            .u64(self.collection_epoch)
            .id(&self.issuer_identity_vault_id)
            .id(&self.issuer_device_id)
            .u64(self.issuer_membership_generation)
            .u64(self.created_sequence)
            .fixed(&self.issuer_signature);
        writer.finish()
    }

    /// Decodes one canonical membership record.
    pub fn decode(bytes: &[u8]) -> Result<Self> {
        let mut reader = Reader::new(bytes, ChurStatus::NonCanonicalEncoding);
        ensure!(
            reader.u16()? == PROTOCOL_VERSION_V1,
            UnsupportedVersion,
            "collection membership version is not supported"
        );
        let source_vault_id = reader.id()?;
        let collection_id = reader.id()?;
        let collection_membership_generation = reader.u64()?;
        let previous_membership_commitment = reader.fixed::<32>()?;
        let action_value = reader.u8()?;
        let recipient_identity_vault_id = reader.id()?;
        let recipient_device_id = reader.id()?;
        let recipient_signing_public_key = reader.fixed::<PUBLIC_KEY_LEN>()?;
        let recipient_hpke_public_key = reader.fixed::<PUBLIC_KEY_LEN>()?;
        let permission = reader.u8()?;
        let action = CollectionMembershipAction::decode(action_value, permission)?;
        let collection_epoch = reader.u64()?;
        let issuer_identity_vault_id = reader.id()?;
        let issuer_device_id = reader.id()?;
        let issuer_membership_generation = reader.u64()?;
        let created_sequence = reader.u64()?;
        let issuer_signature = reader.fixed::<SIGNATURE_LEN>()?;
        reader.finish()?;
        Self::from_fields(
            source_vault_id,
            collection_id,
            collection_membership_generation,
            previous_membership_commitment,
            action,
            recipient_identity_vault_id,
            recipient_device_id,
            recipient_signing_public_key,
            recipient_hpke_public_key,
            collection_epoch,
            issuer_identity_vault_id,
            issuer_device_id,
            issuer_membership_generation,
            created_sequence,
            issuer_signature,
        )
    }

    /// Verifies the issuer signature.
    pub fn verify_signature(&self, key: &[u8; PUBLIC_KEY_LEN]) -> Result<()> {
        verify_ed25519(key, &self.issuer_signature, &self.signing_bytes())
    }

    /// The membership-chain head after this record.
    #[must_use]
    pub fn commitment(&self) -> Commitment {
        commit::commit(tag::SHARING_MEMBERSHIP_CHAIN, &[&self.encode()])
    }

    /// Source vault that owns the collection.
    #[must_use]
    pub const fn source_vault_id(&self) -> &Id {
        &self.source_vault_id
    }

    /// Security collection whose membership changes.
    #[must_use]
    pub const fn collection_id(&self) -> &Id {
        &self.collection_id
    }

    /// Generation created by this record.
    #[must_use]
    pub const fn collection_membership_generation(&self) -> u64 {
        self.collection_membership_generation
    }

    /// Previous membership-chain head.
    #[must_use]
    pub const fn previous_membership_commitment(&self) -> &Commitment {
        &self.previous_membership_commitment
    }

    /// Membership action.
    #[must_use]
    pub const fn action(&self) -> CollectionMembershipAction {
        self.action
    }

    /// Recipient identity vault.
    #[must_use]
    pub const fn recipient_identity_vault_id(&self) -> &Id {
        &self.recipient_identity_vault_id
    }

    /// Recipient device.
    #[must_use]
    pub const fn recipient_device_id(&self) -> &Id {
        &self.recipient_device_id
    }

    /// Recipient signing public key.
    #[must_use]
    pub const fn recipient_signing_public_key(&self) -> &[u8; PUBLIC_KEY_LEN] {
        &self.recipient_signing_public_key
    }

    /// Recipient HPKE public key.
    #[must_use]
    pub const fn recipient_hpke_public_key(&self) -> &[u8; PUBLIC_KEY_LEN] {
        &self.recipient_hpke_public_key
    }

    /// Collection epoch after this record.
    #[must_use]
    pub const fn collection_epoch(&self) -> u64 {
        self.collection_epoch
    }

    /// Issuer identity vault.
    #[must_use]
    pub const fn issuer_identity_vault_id(&self) -> &Id {
        &self.issuer_identity_vault_id
    }

    /// Issuer device.
    #[must_use]
    pub const fn issuer_device_id(&self) -> &Id {
        &self.issuer_device_id
    }

    /// Issuer's authenticated identity-membership generation.
    #[must_use]
    pub const fn issuer_membership_generation(&self) -> u64 {
        self.issuer_membership_generation
    }

    /// Containing operation sequence.
    #[must_use]
    pub const fn created_sequence(&self) -> u64 {
        self.created_sequence
    }

    #[expect(
        clippy::too_many_arguments,
        reason = "the arguments are the frozen wire fields"
    )]
    fn from_fields(
        source_vault_id: Id,
        collection_id: Id,
        collection_membership_generation: u64,
        previous_membership_commitment: Commitment,
        action: CollectionMembershipAction,
        recipient_identity_vault_id: Id,
        recipient_device_id: Id,
        recipient_signing_public_key: [u8; PUBLIC_KEY_LEN],
        recipient_hpke_public_key: [u8; PUBLIC_KEY_LEN],
        collection_epoch: u64,
        issuer_identity_vault_id: Id,
        issuer_device_id: Id,
        issuer_membership_generation: u64,
        created_sequence: u64,
        issuer_signature: [u8; SIGNATURE_LEN],
    ) -> Result<Self> {
        let record = Self {
            source_vault_id,
            collection_id,
            collection_membership_generation,
            previous_membership_commitment,
            action,
            recipient_identity_vault_id,
            recipient_device_id,
            recipient_signing_public_key,
            recipient_hpke_public_key,
            collection_epoch,
            issuer_identity_vault_id,
            issuer_device_id,
            issuer_membership_generation,
            created_sequence,
            issuer_signature,
        };
        record.validate()?;
        Ok(record)
    }

    fn validate(&self) -> Result<()> {
        ensure!(
            self.source_vault_id != self.recipient_identity_vault_id,
            NonCanonicalEncoding,
            "collection member belongs to the source vault"
        );
        ensure!(
            self.recipient_signing_public_key != [0; PUBLIC_KEY_LEN]
                && self.recipient_hpke_public_key != [0; PUBLIC_KEY_LEN],
            NonCanonicalEncoding,
            "collection member public key is zero"
        );
        validate_counter(
            self.collection_membership_generation,
            "collection membership generation is invalid",
        )?;
        validate_counter(self.collection_epoch, "collection epoch is invalid")?;
        validate_counter(
            self.issuer_membership_generation,
            "issuer membership generation is invalid",
        )?;
        validate_counter(
            self.created_sequence,
            "membership creation sequence is invalid",
        )?;
        ensure!(
            (self.collection_membership_generation == 1
                && self.previous_membership_commitment == [0; 32])
                || (self.collection_membership_generation > 1
                    && self.previous_membership_commitment != [0; 32]),
            NonCanonicalEncoding,
            "collection membership predecessor is invalid"
        );
        Ok(())
    }

    fn signing_bytes(&self) -> Vec<u8> {
        let encoded = self.encode();
        let mut bytes = Vec::with_capacity(tag::SHARING_COLLECTION_MEMBERSHIP.len() + 228);
        bytes.extend_from_slice(tag::SHARING_COLLECTION_MEMBERSHIP);
        bytes.extend_from_slice(&encoded[..Self::LEN - SIGNATURE_LEN]);
        bytes
    }
}

fn validate_counter(value: u64, context: &'static str) -> Result<()> {
    if value != 0 && value != u64::MAX {
        return Ok(());
    }
    Err(Error::new(ChurStatus::NonCanonicalEncoding, context))
}

#[cfg(test)]
mod tests {
    #![allow(clippy::expect_used, clippy::panic)]

    use super::*;

    fn id(byte: u8) -> Id {
        Id::new([byte; 16]).expect("id")
    }

    #[test]
    fn an_upsert_is_one_signed_canonical_record() {
        let signer = DeviceSigningKey::from_seed([12; 32]);
        let record = CollectionMembershipRecord::new(
            id(1),
            id(2),
            1,
            [0; 32],
            CollectionMembershipAction::Upsert(PermissionProfile::Contribute),
            id(3),
            id(4),
            [5; 32],
            [6; 32],
            1,
            id(1),
            id(7),
            1,
            8,
        )
        .expect("record")
        .sign(&signer);

        assert_eq!(record.encode().len(), CollectionMembershipRecord::LEN);
        record
            .verify_signature(&signer.verifying_key())
            .expect("signature");
        assert_eq!(
            CollectionMembershipRecord::decode(&record.encode())
                .expect("decode")
                .encode(),
            record.encode()
        );
        assert_ne!(record.commitment(), [0; 32]);
    }

    #[test]
    fn unknown_actions_permissions_and_modified_signatures_fail_closed() {
        let signer = DeviceSigningKey::from_seed([12; 32]);
        let record = CollectionMembershipRecord::new(
            id(1),
            id(2),
            1,
            [0; 32],
            CollectionMembershipAction::Upsert(PermissionProfile::Contribute),
            id(3),
            id(4),
            [5; 32],
            [6; 32],
            1,
            id(1),
            id(7),
            1,
            8,
        )
        .expect("record")
        .sign(&signer);

        let mut unknown_action = record.encode();
        unknown_action[74] = 0xff;
        assert!(CollectionMembershipRecord::decode(&unknown_action).is_err());

        let mut revoke_with_permission = record.encode();
        revoke_with_permission[74] = REVOKE_ACTION;
        assert!(CollectionMembershipRecord::decode(&revoke_with_permission).is_err());

        let mut modified_signature = record.encode();
        modified_signature[228] ^= 1;
        let modified = CollectionMembershipRecord::decode(&modified_signature).expect("record");
        assert!(modified.verify_signature(&signer.verifying_key()).is_err());
    }
}
