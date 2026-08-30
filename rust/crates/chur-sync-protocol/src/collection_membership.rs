//! The signed collection membership record of
//! `docs/sync/COLLECTION_MEMBERSHIP.md`.

use std::collections::BTreeMap;

use chur_core::{ChurStatus, Error, Id, Result, ensure};
use chur_crypto::{Commitment, commit, tuple::tag};
use chur_format::codec::{Reader, Writer};

use crate::grant::{CollectionGrant, PermissionProfile, hpke_key_id};
use crate::operation::{DeviceSigningKey, Operation, PROTOCOL_VERSION_V1, verify_ed25519};
use crate::payload::{OperationPayload, PayloadBody};
use crate::state::MembershipState;

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

/// Result of applying one collection membership record.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum CollectionMembershipOutcome {
    /// The membership chain advanced.
    Applied,
    /// The exact current record was already applied.
    Duplicate,
}

/// How one recipient key pair was accepted.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum RecipientVerification {
    /// The first observed key pair was pinned on first use.
    TrustOnFirstUse,
    /// The user explicitly verified this key pair.
    Verified,
}

/// One pinned recipient key pair and its local verification state.
#[derive(Clone)]
pub struct RecipientPin {
    signing_public_key: [u8; PUBLIC_KEY_LEN],
    hpke_public_key: [u8; PUBLIC_KEY_LEN],
    verification: RecipientVerification,
}

impl RecipientPin {
    /// Pinned recipient signing key.
    #[must_use]
    pub const fn signing_public_key(&self) -> &[u8; PUBLIC_KEY_LEN] {
        &self.signing_public_key
    }

    /// Pinned recipient HPKE key.
    #[must_use]
    pub const fn hpke_public_key(&self) -> &[u8; PUBLIC_KEY_LEN] {
        &self.hpke_public_key
    }

    /// Whether this pin was accepted on first use or explicitly verified.
    #[must_use]
    pub const fn verification(&self) -> RecipientVerification {
        self.verification
    }
}

#[derive(Clone)]
/// One current or historical recipient-device membership entry.
pub struct CollectionMember {
    signing_public_key: [u8; PUBLIC_KEY_LEN],
    hpke_public_key: [u8; PUBLIC_KEY_LEN],
    permissions: PermissionProfile,
    membership_generation: u64,
    active: bool,
}

impl CollectionMember {
    /// Recipient signing key accepted for shared operations.
    #[must_use]
    pub const fn signing_public_key(&self) -> &[u8; PUBLIC_KEY_LEN] {
        &self.signing_public_key
    }

    /// Recipient HPKE key accepted for grants.
    #[must_use]
    pub const fn hpke_public_key(&self) -> &[u8; PUBLIC_KEY_LEN] {
        &self.hpke_public_key
    }

    /// Current cumulative permission profile.
    #[must_use]
    pub const fn permissions(&self) -> PermissionProfile {
        self.permissions
    }

    /// Generation that last changed this recipient device.
    #[must_use]
    pub const fn membership_generation(&self) -> u64 {
        self.membership_generation
    }

    /// Whether this recipient can author or receive current state.
    #[must_use]
    pub const fn is_active(&self) -> bool {
        self.active
    }
}

/// Accepted member, permission, epoch, and recipient-pin state for one collection.
#[derive(Clone)]
pub struct CollectionMembershipState {
    source_vault_id: Id,
    collection_id: Id,
    generation: u64,
    commitment: Commitment,
    collection_epoch: u64,
    last_record: Option<Vec<u8>>,
    pins: BTreeMap<(Id, Id), RecipientPin>,
    members: BTreeMap<(Id, Id), CollectionMember>,
}

impl CollectionMembershipState {
    /// Creates the empty generation-zero state for one existing collection epoch.
    pub fn new(source_vault_id: Id, collection_id: Id, collection_epoch: u64) -> Result<Self> {
        validate_counter(collection_epoch, "initial collection epoch is invalid")?;
        Ok(Self {
            source_vault_id,
            collection_id,
            generation: 0,
            commitment: [0; 32],
            collection_epoch,
            last_record: None,
            pins: BTreeMap::new(),
            members: BTreeMap::new(),
        })
    }

    /// Verifies and atomically applies one successor record.
    pub fn accept(
        &mut self,
        record: &CollectionMembershipRecord,
        issuer_membership: &MembershipState,
    ) -> Result<CollectionMembershipOutcome> {
        let encoded = record.encode();
        if let Some(outcome) = self.validate_next(record, &encoded)? {
            return Ok(outcome);
        }
        ensure!(
            issuer_membership.vault_id() == &record.issuer_identity_vault_id
                && issuer_membership.generation() == record.issuer_membership_generation,
            AuthenticationFailed,
            "collection membership issuer state does not match"
        );
        let issuer = issuer_membership
            .device(&record.issuer_device_id)
            .ok_or_else(|| {
                Error::new(
                    ChurStatus::AuthenticationFailed,
                    "collection membership issuer is unknown",
                )
            })?;
        ensure!(
            issuer_membership.is_active(&record.issuer_device_id),
            AuthenticationFailed,
            "collection membership issuer is revoked"
        );
        if record.issuer_identity_vault_id != self.source_vault_id {
            let manager = self
                .members
                .get(&(record.issuer_identity_vault_id, record.issuer_device_id))
                .ok_or_else(|| {
                    Error::new(
                        ChurStatus::AuthenticationFailed,
                        "collection membership issuer is not a collection member",
                    )
                })?;
            ensure!(
                manager.active
                    && manager.permissions == PermissionProfile::ManageMembers
                    && manager.signing_public_key == *issuer.signing_public_key(),
                AuthenticationFailed,
                "collection membership issuer cannot manage members"
            );
        }
        record.verify_signature(issuer.signing_public_key())?;
        self.apply_verified(record, encoded)
    }

    /// Replays one record already accepted into the protected local catalog.
    pub fn restore_accepted(
        &mut self,
        record: &CollectionMembershipRecord,
        issuer_signing_public_key: &[u8; PUBLIC_KEY_LEN],
    ) -> Result<CollectionMembershipOutcome> {
        let encoded = record.encode();
        if let Some(outcome) = self.validate_next(record, &encoded)? {
            return Ok(outcome);
        }
        if record.issuer_identity_vault_id != self.source_vault_id {
            let manager = self
                .members
                .get(&(record.issuer_identity_vault_id, record.issuer_device_id))
                .ok_or_else(|| {
                    Error::new(
                        ChurStatus::CatalogCorrupt,
                        "restored collection membership issuer is unknown",
                    )
                })?;
            ensure!(
                manager.active
                    && manager.permissions == PermissionProfile::ManageMembers
                    && manager.signing_public_key == *issuer_signing_public_key,
                CatalogCorrupt,
                "restored collection membership issuer cannot manage members"
            );
        }
        record
            .verify_signature(issuer_signing_public_key)
            .map_err(|_| {
                Error::new(
                    ChurStatus::CatalogCorrupt,
                    "restored collection membership signature is invalid",
                )
            })?;
        self.apply_verified(record, encoded)
    }

    /// Advances the collection epoch by one or accepts the same epoch idempotently.
    pub fn advance_collection_epoch(&mut self, target_epoch: u64) -> Result<bool> {
        validate_counter(target_epoch, "collection epoch is invalid")?;
        if target_epoch == self.collection_epoch {
            return Ok(false);
        }
        ensure!(
            self.collection_epoch
                .checked_add(1)
                .is_some_and(|next| next == target_epoch),
            SyncHeadRollback,
            "collection epoch is not the next epoch"
        );
        self.collection_epoch = target_epoch;
        Ok(true)
    }

    /// Restores a durable collection epoch that can be ahead of membership records.
    pub fn restore_collection_epoch(&mut self, target_epoch: u64) -> Result<()> {
        validate_counter(target_epoch, "restored collection epoch is invalid")?;
        ensure!(
            target_epoch >= self.collection_epoch,
            SyncHeadRollback,
            "restored collection epoch rolls back membership state"
        );
        self.collection_epoch = target_epoch;
        Ok(())
    }

    fn validate_next(
        &self,
        record: &CollectionMembershipRecord,
        encoded: &[u8],
    ) -> Result<Option<CollectionMembershipOutcome>> {
        if self
            .last_record
            .as_ref()
            .is_some_and(|accepted| accepted == encoded)
        {
            return Ok(Some(CollectionMembershipOutcome::Duplicate));
        }
        ensure!(
            record.source_vault_id == self.source_vault_id
                && record.collection_id == self.collection_id,
            AuthenticationFailed,
            "collection membership record belongs to another collection"
        );
        ensure!(
            self.generation
                .checked_add(1)
                .is_some_and(|generation| generation == record.collection_membership_generation)
                && record.previous_membership_commitment == self.commitment,
            SyncHeadRollback,
            "collection membership record is not the next chain entry"
        );
        Ok(None)
    }

    fn apply_verified(
        &mut self,
        record: &CollectionMembershipRecord,
        encoded: Vec<u8>,
    ) -> Result<CollectionMembershipOutcome> {
        let mut candidate = self.clone();
        let recipient = (
            record.recipient_identity_vault_id,
            record.recipient_device_id,
        );
        match candidate.pins.get(&recipient) {
            Some(pin) => ensure!(
                pin.signing_public_key == record.recipient_signing_public_key
                    && pin.hpke_public_key == record.recipient_hpke_public_key,
                AuthenticationFailed,
                "collection recipient key differs from its pin"
            ),
            None => {
                candidate.pins.insert(
                    recipient,
                    RecipientPin {
                        signing_public_key: record.recipient_signing_public_key,
                        hpke_public_key: record.recipient_hpke_public_key,
                        verification: RecipientVerification::TrustOnFirstUse,
                    },
                );
            }
        }

        match record.action {
            CollectionMembershipAction::Upsert(permissions) => {
                ensure!(
                    record.collection_epoch == candidate.collection_epoch,
                    SyncHeadRollback,
                    "collection member upsert changes the collection epoch"
                );
                if let Some(existing) = candidate.members.get(&recipient) {
                    ensure!(
                        !existing.active
                            || existing.permissions != permissions
                            || existing.signing_public_key != record.recipient_signing_public_key
                            || existing.hpke_public_key != record.recipient_hpke_public_key,
                        NonCanonicalEncoding,
                        "collection member upsert changes no state"
                    );
                }
                candidate.members.insert(
                    recipient,
                    CollectionMember {
                        signing_public_key: record.recipient_signing_public_key,
                        hpke_public_key: record.recipient_hpke_public_key,
                        permissions,
                        membership_generation: record.collection_membership_generation,
                        active: true,
                    },
                );
            }
            CollectionMembershipAction::Revoke => {
                ensure!(
                    candidate
                        .collection_epoch
                        .checked_add(1)
                        .is_some_and(|epoch| { epoch == record.collection_epoch }),
                    SyncHeadRollback,
                    "collection revocation does not advance the epoch"
                );
                let member = candidate.members.get_mut(&recipient).ok_or_else(|| {
                    Error::new(
                        ChurStatus::AuthenticationFailed,
                        "collection revocation names an unknown member",
                    )
                })?;
                ensure!(
                    member.active
                        && member.signing_public_key == record.recipient_signing_public_key
                        && member.hpke_public_key == record.recipient_hpke_public_key,
                    AuthenticationFailed,
                    "collection revocation does not match an active member"
                );
                member.active = false;
                member.membership_generation = record.collection_membership_generation;
                candidate.collection_epoch = record.collection_epoch;
            }
        }
        candidate.generation = record.collection_membership_generation;
        candidate.commitment = record.commitment();
        candidate.last_record = Some(encoded);
        *self = candidate;
        Ok(CollectionMembershipOutcome::Applied)
    }

    /// Whether a recipient device has the required cumulative permission.
    #[must_use]
    pub fn is_authorized(
        &self,
        identity_vault_id: &Id,
        device_id: &Id,
        required: PermissionProfile,
    ) -> bool {
        self.members
            .get(&(*identity_vault_id, *device_id))
            .is_some_and(|member| {
                member.active && (member.permissions as u8 & required as u8) == required as u8
            })
    }

    /// Validates one grant against current sender and recipient membership.
    pub fn validate_grant(
        &self,
        grant: &CollectionGrant,
        sender_membership: &MembershipState,
    ) -> Result<()> {
        ensure!(
            grant.source_vault_id() == &self.source_vault_id
                && grant.collection_id() == &self.collection_id
                && grant.collection_epoch() == self.collection_epoch,
            AuthenticationFailed,
            "collection grant does not match current collection state"
        );
        let member = self
            .member(
                grant.recipient_identity_vault_id(),
                grant.recipient_device_id(),
            )
            .ok_or_else(|| {
                Error::new(
                    ChurStatus::AuthenticationFailed,
                    "collection grant recipient is unknown",
                )
            })?;
        ensure!(
            member.active
                && member.membership_generation == grant.collection_membership_generation()
                && member.permissions == grant.permissions()
                && grant.recipient_hpke_key_id()
                    == &hpke_key_id(
                        grant.recipient_identity_vault_id(),
                        grant.recipient_device_id(),
                        &member.hpke_public_key,
                    ),
            AuthenticationFailed,
            "collection grant does not match current recipient membership"
        );
        ensure!(
            sender_membership.generation() == grant.sender_membership_generation(),
            AuthenticationFailed,
            "collection grant sender membership is stale"
        );
        let sender = sender_membership
            .device(grant.sender_device_id())
            .ok_or_else(|| {
                Error::new(
                    ChurStatus::AuthenticationFailed,
                    "collection grant sender is unknown",
                )
            })?;
        ensure!(
            sender_membership.is_active(grant.sender_device_id()),
            AuthenticationFailed,
            "collection grant sender is revoked"
        );
        if sender_membership.vault_id() != &self.source_vault_id {
            let manager = self
                .member(sender_membership.vault_id(), grant.sender_device_id())
                .ok_or_else(|| {
                    Error::new(
                        ChurStatus::AuthenticationFailed,
                        "collection grant sender is not a collection member",
                    )
                })?;
            ensure!(
                manager.active
                    && manager.permissions == PermissionProfile::ManageMembers
                    && manager.signing_public_key == *sender.signing_public_key(),
                AuthenticationFailed,
                "collection grant sender cannot manage members"
            );
        }
        grant.verify_sender_signature(sender.signing_public_key())
    }

    /// Authorizes one already-opened shared operation against current permissions.
    pub fn authorize_operation(
        &self,
        operation: &Operation,
        payload: &OperationPayload,
        issuer_membership: &MembershipState,
    ) -> Result<()> {
        payload.validate_for_operation(operation, &self.collection_id, self.collection_epoch)?;
        ensure!(
            issuer_membership.vault_id() == operation.vault_id(),
            AuthenticationFailed,
            "shared operation issuer membership belongs to another vault"
        );
        let issuer = issuer_membership
            .device(operation.device_id())
            .ok_or_else(|| {
                Error::new(
                    ChurStatus::AuthenticationFailed,
                    "shared operation issuer is unknown",
                )
            })?;
        ensure!(
            issuer_membership.is_active(operation.device_id()),
            AuthenticationFailed,
            "shared operation issuer is revoked"
        );
        operation.verify_signature(issuer.signing_public_key())?;
        if operation.vault_id() == &self.source_vault_id {
            return Ok(());
        }
        ensure!(
            !matches!(
                payload.body(),
                PayloadBody::AddDevice(_)
                    | PayloadBody::RevokeDevice(_)
                    | PayloadBody::CreateCollectionEpoch { .. }
                    | PayloadBody::RewrapObjectKey { .. }
            ),
            AuthenticationFailed,
            "shared security operation requires a source-vault device"
        );
        let member = self
            .member(operation.vault_id(), operation.device_id())
            .ok_or_else(|| {
                Error::new(
                    ChurStatus::AuthenticationFailed,
                    "shared operation issuer is not a collection member",
                )
            })?;
        let required = if matches!(
            payload.body(),
            PayloadBody::ChangeCollectionMembership(_) | PayloadBody::IssueCollectionGrant(_)
        ) {
            PermissionProfile::ManageMembers
        } else {
            PermissionProfile::Contribute
        };
        ensure!(
            member.active
                && member.signing_public_key == *issuer.signing_public_key()
                && (member.permissions as u8 & required as u8) == required as u8,
            AuthenticationFailed,
            "shared operation issuer lacks the required permission"
        );
        Ok(())
    }

    /// Explicitly verifies or replaces one recipient key pin.
    pub fn verify_recipient_keys(
        &mut self,
        identity_vault_id: Id,
        device_id: Id,
        signing_public_key: [u8; PUBLIC_KEY_LEN],
        hpke_public_key: [u8; PUBLIC_KEY_LEN],
    ) -> Result<()> {
        ensure!(
            signing_public_key != [0; PUBLIC_KEY_LEN] && hpke_public_key != [0; PUBLIC_KEY_LEN],
            InvalidInput,
            "verified recipient key is zero"
        );
        self.pins.insert(
            (identity_vault_id, device_id),
            RecipientPin {
                signing_public_key,
                hpke_public_key,
                verification: RecipientVerification::Verified,
            },
        );
        Ok(())
    }

    /// How one recipient key pair was accepted.
    #[must_use]
    pub fn recipient_verification(
        &self,
        identity_vault_id: &Id,
        device_id: &Id,
    ) -> Option<RecipientVerification> {
        self.pins
            .get(&(*identity_vault_id, *device_id))
            .map(|pin| pin.verification)
    }

    /// Pinned keys and verification state for one recipient device.
    #[must_use]
    pub fn recipient_pin(&self, identity_vault_id: &Id, device_id: &Id) -> Option<&RecipientPin> {
        self.pins.get(&(*identity_vault_id, *device_id))
    }

    /// Accepted recipient entry, including historical revoked entries.
    #[must_use]
    pub fn member(&self, identity_vault_id: &Id, device_id: &Id) -> Option<&CollectionMember> {
        self.members.get(&(*identity_vault_id, *device_id))
    }

    /// Latest accepted collection membership generation.
    #[must_use]
    pub const fn generation(&self) -> u64 {
        self.generation
    }

    /// Latest accepted collection membership commitment.
    #[must_use]
    pub const fn commitment(&self) -> &Commitment {
        &self.commitment
    }

    /// Current collection epoch.
    #[must_use]
    pub const fn collection_epoch(&self) -> u64 {
        self.collection_epoch
    }

    /// Source vault that owns this collection.
    #[must_use]
    pub const fn source_vault_id(&self) -> &Id {
        &self.source_vault_id
    }

    /// Collection governed by this state.
    #[must_use]
    pub const fn collection_id(&self) -> &Id {
        &self.collection_id
    }
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
    use crate::identity::DeviceIdentity;
    use crate::membership::EnrollmentRecord;
    use chur_crypto::Nonce;
    use chur_crypto::secret::Key;

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

    #[test]
    fn the_source_adds_one_recipient_with_cumulative_permissions() {
        let source_key = DeviceSigningKey::from_seed([1; 32]);
        let source_enrollment =
            EnrollmentRecord::initial(id(1), id(2), source_key.verifying_key(), [3; 32])
                .expect("source enrollment")
                .sign(&source_key);
        let source_membership =
            MembershipState::bootstrap(&source_enrollment).expect("source membership");
        let recipient_vault_id = id(4);
        let recipient_device_id = id(5);
        let record = CollectionMembershipRecord::new(
            id(1),
            id(6),
            1,
            [0; 32],
            CollectionMembershipAction::Upsert(PermissionProfile::Contribute),
            recipient_vault_id,
            recipient_device_id,
            [7; 32],
            [8; 32],
            1,
            id(1),
            id(2),
            1,
            9,
        )
        .expect("record")
        .sign(&source_key);
        let mut state = CollectionMembershipState::new(id(1), id(6), 1).expect("state");

        assert!(
            state.accept(&record, &source_membership) == Ok(CollectionMembershipOutcome::Applied)
        );
        assert!(state.is_authorized(
            &recipient_vault_id,
            &recipient_device_id,
            PermissionProfile::Read,
        ));
        assert!(state.is_authorized(
            &recipient_vault_id,
            &recipient_device_id,
            PermissionProfile::Contribute,
        ));
        assert!(!state.is_authorized(
            &recipient_vault_id,
            &recipient_device_id,
            PermissionProfile::ManageMembers,
        ));
    }

    #[test]
    fn a_grant_must_match_current_sender_and_recipient_membership() {
        let source_key = DeviceSigningKey::from_seed([1; 32]);
        let source_enrollment =
            EnrollmentRecord::initial(id(1), id(2), source_key.verifying_key(), [3; 32])
                .expect("source enrollment")
                .sign(&source_key);
        let source_membership =
            MembershipState::bootstrap(&source_enrollment).expect("source membership");
        let recipient = DeviceIdentity::from_seeds([7; 32], [8; 32]);
        let recipient_vault_id = id(4);
        let recipient_device_id = id(5);
        let record = CollectionMembershipRecord::new(
            id(1),
            id(6),
            1,
            [0; 32],
            CollectionMembershipAction::Upsert(PermissionProfile::Contribute),
            recipient_vault_id,
            recipient_device_id,
            recipient.signing_public_key(),
            recipient.hpke_public_key(),
            1,
            id(1),
            id(2),
            1,
            9,
        )
        .expect("record")
        .sign(&source_key);
        let mut state = CollectionMembershipState::new(id(1), id(6), 1).expect("state");
        state
            .accept(&record, &source_membership)
            .expect("membership");
        let grant = CollectionGrant::seal(
            id(10),
            id(1),
            id(6),
            1,
            1,
            recipient_vault_id,
            recipient_device_id,
            &recipient.hpke_public_key(),
            id(2),
            PermissionProfile::Contribute,
            1,
            11,
            &Key::new([12; 32]),
            &source_key,
        )
        .expect("grant");

        state
            .validate_grant(&grant, &source_membership)
            .expect("current grant");
    }

    #[test]
    fn key_substitution_blocks_until_verification_and_revoke_advances_epoch() {
        let source_key = DeviceSigningKey::from_seed([1; 32]);
        let source_enrollment =
            EnrollmentRecord::initial(id(1), id(2), source_key.verifying_key(), [3; 32])
                .expect("source enrollment")
                .sign(&source_key);
        let source_membership =
            MembershipState::bootstrap(&source_enrollment).expect("source membership");
        let recipient_vault_id = id(4);
        let recipient_device_id = id(5);
        let first = CollectionMembershipRecord::new(
            id(1),
            id(6),
            1,
            [0; 32],
            CollectionMembershipAction::Upsert(PermissionProfile::Read),
            recipient_vault_id,
            recipient_device_id,
            [7; 32],
            [8; 32],
            1,
            id(1),
            id(2),
            1,
            9,
        )
        .expect("first")
        .sign(&source_key);
        let mut state = CollectionMembershipState::new(id(1), id(6), 1).expect("state");
        state
            .accept(&first, &source_membership)
            .expect("first member");
        assert!(
            state.accept(&first, &source_membership) == Ok(CollectionMembershipOutcome::Duplicate)
        );
        assert!(
            state.recipient_verification(&recipient_vault_id, &recipient_device_id)
                == Some(RecipientVerification::TrustOnFirstUse)
        );

        let replacement = CollectionMembershipRecord::new(
            id(1),
            id(6),
            2,
            first.commitment(),
            CollectionMembershipAction::Upsert(PermissionProfile::Contribute),
            recipient_vault_id,
            recipient_device_id,
            [10; 32],
            [11; 32],
            1,
            id(1),
            id(2),
            1,
            12,
        )
        .expect("replacement")
        .sign(&source_key);
        assert!(state.accept(&replacement, &source_membership).is_err());
        state
            .verify_recipient_keys(recipient_vault_id, recipient_device_id, [10; 32], [11; 32])
            .expect("verify replacement");
        state
            .accept(&replacement, &source_membership)
            .expect("verified replacement");

        let revoke = CollectionMembershipRecord::new(
            id(1),
            id(6),
            3,
            replacement.commitment(),
            CollectionMembershipAction::Revoke,
            recipient_vault_id,
            recipient_device_id,
            [10; 32],
            [11; 32],
            2,
            id(1),
            id(2),
            1,
            13,
        )
        .expect("revoke")
        .sign(&source_key);
        state
            .accept(&revoke, &source_membership)
            .expect("revoke member");

        assert_eq!(state.collection_epoch(), 2);
        assert!(!state.is_authorized(
            &recipient_vault_id,
            &recipient_device_id,
            PermissionProfile::Read,
        ));
        assert!(
            state.recipient_verification(&recipient_vault_id, &recipient_device_id)
                == Some(RecipientVerification::Verified)
        );
        assert!(CollectionMembershipState::new(id(1), id(6), 0).is_err());

        let mut restored = CollectionMembershipState::new(id(1), id(6), 1).expect("restore");
        restored
            .restore_accepted(&first, &source_key.verifying_key())
            .expect("restore first");
        restored
            .verify_recipient_keys(recipient_vault_id, recipient_device_id, [10; 32], [11; 32])
            .expect("restore verified pin");
        restored
            .restore_accepted(&replacement, &source_key.verifying_key())
            .expect("restore replacement");
        restored
            .restore_accepted(&revoke, &source_key.verifying_key())
            .expect("restore revoke");
        assert_eq!(restored.generation(), state.generation());
        assert_eq!(restored.commitment(), state.commitment());
        assert_eq!(restored.collection_epoch(), state.collection_epoch());
    }

    #[test]
    fn collection_epoch_advances_once_and_restores_forward() {
        let mut state = CollectionMembershipState::new(id(1), id(2), 7).expect("state");

        assert!(state.advance_collection_epoch(8).expect("advance"));
        assert!(!state.advance_collection_epoch(8).expect("duplicate"));
        assert!(state.advance_collection_epoch(10).is_err());
        assert!(state.advance_collection_epoch(7).is_err());
        state.restore_collection_epoch(10).expect("restore");
        assert_eq!(state.collection_epoch(), 10);
        assert!(state.restore_collection_epoch(9).is_err());
        assert!(state.restore_collection_epoch(u64::MAX).is_err());
    }

    #[test]
    fn only_a_member_manager_can_change_membership_and_issue_grants() {
        let source_key = DeviceSigningKey::from_seed([1; 32]);
        let source_enrollment =
            EnrollmentRecord::initial(id(1), id(2), source_key.verifying_key(), [3; 32])
                .expect("source enrollment")
                .sign(&source_key);
        let source_membership =
            MembershipState::bootstrap(&source_enrollment).expect("source membership");
        let manager_key = DeviceSigningKey::from_seed([4; 32]);
        let manager_enrollment =
            EnrollmentRecord::initial(id(5), id(6), manager_key.verifying_key(), [7; 32])
                .expect("manager enrollment")
                .sign(&manager_key);
        let manager_membership =
            MembershipState::bootstrap(&manager_enrollment).expect("manager membership");
        let manager = CollectionMembershipRecord::new(
            id(1),
            id(8),
            1,
            [0; 32],
            CollectionMembershipAction::Upsert(PermissionProfile::ManageMembers),
            id(5),
            id(6),
            manager_key.verifying_key(),
            [7; 32],
            1,
            id(1),
            id(2),
            1,
            9,
        )
        .expect("manager")
        .sign(&source_key);
        let mut state = CollectionMembershipState::new(id(1), id(8), 1).expect("state");
        state
            .accept(&manager, &source_membership)
            .expect("add manager");
        let next = CollectionMembershipRecord::new(
            id(1),
            id(8),
            2,
            manager.commitment(),
            CollectionMembershipAction::Upsert(PermissionProfile::Read),
            id(10),
            id(11),
            [12; 32],
            [13; 32],
            1,
            id(5),
            id(6),
            1,
            14,
        )
        .expect("next")
        .sign(&manager_key);

        let mut insufficient = state.clone();
        insufficient
            .members
            .get_mut(&(id(5), id(6)))
            .expect("manager")
            .permissions = PermissionProfile::Contribute;
        assert!(insufficient.accept(&next, &manager_membership).is_err());
        let payload = OperationPayload::new(
            id(8),
            1,
            PayloadBody::ChangeCollectionMembership(next.clone()),
        )
        .expect("payload");
        let operation = Operation::seal(
            id(15),
            id(5),
            id(6),
            14,
            [16; 32],
            Vec::new(),
            id(17),
            &Key::new([18; 32]),
            Nonce::new([19; 24]),
            &payload.encode(),
        )
        .expect("operation")
        .sign(&manager_key);
        assert!(
            insufficient
                .authorize_operation(&operation, &payload, &manager_membership)
                .is_err()
        );
        state
            .authorize_operation(&operation, &payload, &manager_membership)
            .expect("manager operation");
        state
            .accept(&next, &manager_membership)
            .expect("member manager");

        let grant = CollectionGrant::seal(
            id(20),
            id(1),
            id(8),
            1,
            2,
            id(10),
            id(11),
            &[13; 32],
            id(6),
            PermissionProfile::Read,
            1,
            21,
            &Key::new([22; 32]),
            &manager_key,
        )
        .expect("manager grant");
        let grant_payload =
            OperationPayload::new(id(8), 1, PayloadBody::IssueCollectionGrant(grant.clone()))
                .expect("grant payload");
        let grant_operation = Operation::seal(
            id(20),
            id(5),
            id(6),
            21,
            [23; 32],
            Vec::new(),
            id(24),
            &Key::new([25; 32]),
            Nonce::new([26; 24]),
            &grant_payload.encode(),
        )
        .expect("grant operation")
        .sign(&manager_key);
        state
            .validate_grant(&grant, &manager_membership)
            .expect("manager grant authorization");
        state
            .authorize_operation(&grant_operation, &grant_payload, &manager_membership)
            .expect("manager grant operation");

        let mut grant_insufficient = state.clone();
        grant_insufficient
            .members
            .get_mut(&(id(5), id(6)))
            .expect("manager")
            .permissions = PermissionProfile::Contribute;
        assert!(
            grant_insufficient
                .validate_grant(&grant, &manager_membership)
                .is_err()
        );
        assert!(
            grant_insufficient
                .authorize_operation(&grant_operation, &grant_payload, &manager_membership)
                .is_err()
        );
    }

    #[test]
    fn read_cannot_author_content_but_contribute_can() {
        let source_key = DeviceSigningKey::from_seed([1; 32]);
        let source_enrollment =
            EnrollmentRecord::initial(id(1), id(2), source_key.verifying_key(), [3; 32])
                .expect("source enrollment")
                .sign(&source_key);
        let source_membership =
            MembershipState::bootstrap(&source_enrollment).expect("source membership");
        let recipient_key = DeviceSigningKey::from_seed([4; 32]);
        let recipient_enrollment =
            EnrollmentRecord::initial(id(5), id(6), recipient_key.verifying_key(), [7; 32])
                .expect("recipient enrollment")
                .sign(&recipient_key);
        let recipient_membership =
            MembershipState::bootstrap(&recipient_enrollment).expect("recipient membership");
        let first = CollectionMembershipRecord::new(
            id(1),
            id(8),
            1,
            [0; 32],
            CollectionMembershipAction::Upsert(PermissionProfile::Read),
            id(5),
            id(6),
            recipient_key.verifying_key(),
            [7; 32],
            1,
            id(1),
            id(2),
            1,
            9,
        )
        .expect("first")
        .sign(&source_key);
        let mut state = CollectionMembershipState::new(id(1), id(8), 1).expect("state");
        state
            .accept(&first, &source_membership)
            .expect("read member");
        let payload = OperationPayload::new(
            id(8),
            1,
            PayloadBody::CreateAlbum {
                album_id: id(10),
                name: "Shared".to_owned(),
            },
        )
        .expect("payload");
        let operation = Operation::seal(
            id(11),
            id(5),
            id(6),
            1,
            [0; 32],
            Vec::new(),
            id(12),
            &Key::new([13; 32]),
            Nonce::new([14; 24]),
            &payload.encode(),
        )
        .expect("operation")
        .sign(&recipient_key);

        assert!(
            state
                .authorize_operation(&operation, &payload, &recipient_membership)
                .is_err()
        );
        let upgrade = CollectionMembershipRecord::new(
            id(1),
            id(8),
            2,
            first.commitment(),
            CollectionMembershipAction::Upsert(PermissionProfile::Contribute),
            id(5),
            id(6),
            recipient_key.verifying_key(),
            [7; 32],
            1,
            id(1),
            id(2),
            1,
            15,
        )
        .expect("upgrade")
        .sign(&source_key);
        state
            .accept(&upgrade, &source_membership)
            .expect("upgrade member");
        state
            .authorize_operation(&operation, &payload, &recipient_membership)
            .expect("contribute");
    }
}
