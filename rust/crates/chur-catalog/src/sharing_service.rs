//! Outbound collection-sharing orchestration over durable protocol primitives.

use std::collections::BTreeMap;

use chur_core::{ChurStatus, Error, Id, Result, ensure};
use chur_crypto::{Key, Nonce, random};
use chur_format::envelope::CollectionKeyEnvelope;
use chur_sync_protocol::{
    KeyDomain,
    collection_membership::{
        CollectionMembershipAction, CollectionMembershipRecord, CollectionMembershipState,
        RecipientVerification,
    },
    grant::{CollectionGrant, PermissionProfile},
    membership::{EnrollmentRecord, RevocationRecord},
    operation::Operation,
    operation_log::{ApplyOutcome, OperationLog},
    payload::{OperationPayload, PayloadBody},
    state::{DeviceStatus, MembershipState},
};

use crate::{
    CatalogDb,
    model::{COLLECTION_POLICY_SHARED, COLLECTION_STATUS_ACTIVE, Collection},
    schema, sharing, store, sync_keys, sync_log, sync_membership, sync_receive, sync_rotation,
};

/// One canonical record in an issuer identity-membership chain.
#[derive(Clone)]
pub enum IssuerMembershipRecord {
    /// Device enrollment or key rotation.
    Enrollment(EnrollmentRecord),
    /// Device revocation and accepted operation head.
    Revocation(RevocationRecord),
}

/// Complete public evidence needed to authenticate one sharing issuer.
#[derive(Clone, Copy)]
pub struct IssuerEvidence<'a> {
    /// Canonical membership chain from generation one.
    pub membership: &'a [IssuerMembershipRecord],
    /// Complete operation prefixes for every referenced issuer device.
    pub operations: &'a [Operation],
}

/// Canonical records the sender uploads to the sharing relay, in dependency order.
pub struct PreparedShare {
    membership: CollectionMembershipRecord,
    membership_operation: Operation,
    grant: CollectionGrant,
    grant_operation: Operation,
}

impl PreparedShare {
    /// Collection membership change or exact replay needed by the recipient.
    #[must_use]
    pub const fn membership(&self) -> &CollectionMembershipRecord {
        &self.membership
    }

    /// Authenticated source-vault operation that carries the membership change.
    #[must_use]
    pub const fn membership_operation(&self) -> &Operation {
        &self.membership_operation
    }

    /// HPKE grant addressed to the recipient device.
    #[must_use]
    pub const fn grant(&self) -> &CollectionGrant {
        &self.grant
    }

    /// Authenticated source-vault operation that carries the grant.
    #[must_use]
    pub const fn grant_operation(&self) -> &Operation {
        &self.grant_operation
    }
}

/// Durable records produced by one complete forward-only member revocation.
pub struct PreparedShareRevocation {
    membership: CollectionMembershipRecord,
    membership_operation: Operation,
    rotation_operations: Vec<Operation>,
    grants: Vec<(CollectionGrant, Operation)>,
    rotation_complete: bool,
}

impl PreparedShareRevocation {
    /// Signed recipient revocation.
    #[must_use]
    pub const fn membership(&self) -> &CollectionMembershipRecord {
        &self.membership
    }

    /// Outer source-vault operation for the revocation.
    #[must_use]
    pub const fn membership_operation(&self) -> &Operation {
        &self.membership_operation
    }

    /// Newly authored epoch and object-key rewrap operations.
    #[must_use]
    pub fn rotation_operations(&self) -> &[Operation] {
        &self.rotation_operations
    }

    /// Current-epoch grants for every remaining active recipient device.
    #[must_use]
    pub fn grants(&self) -> &[(CollectionGrant, Operation)] {
        &self.grants
    }

    /// Whether eager object-key rewrap reached the end.
    #[must_use]
    pub const fn rotation_complete(&self) -> bool {
        self.rotation_complete
    }
}

/// Adds or updates one recipient and issues its current collection-key grant.
///
/// A retry returns the accepted records instead of advancing either chain.
pub fn prepare_share(
    db: &mut CatalogDb,
    root: &Key,
    source_vault_id: Id,
    collection_id: Id,
    recipient_enrollment: &EnrollmentRecord,
    permissions: PermissionProfile,
    fingerprint_verified: bool,
) -> Result<PreparedShare> {
    let recipient_membership = MembershipState::bootstrap(recipient_enrollment)?;
    let recipient_vault_id = *recipient_membership.vault_id();
    let recipient_device_id = *recipient_enrollment.device_id();
    prepare_share_to_device(
        db,
        root,
        source_vault_id,
        collection_id,
        recipient_vault_id,
        recipient_device_id,
        *recipient_enrollment.signing_public_key(),
        *recipient_enrollment.hpke_public_key(),
        permissions,
        fingerprint_verified,
    )
}

/// Authenticates a recipient vault history and shares to one active device.
#[expect(
    clippy::too_many_arguments,
    reason = "the request names the source, recipient evidence, target device, and policy"
)]
pub fn prepare_share_for_device(
    db: &mut CatalogDb,
    root: &Key,
    source_vault_id: Id,
    collection_id: Id,
    recipient: IssuerEvidence<'_>,
    recipient_device_id: Id,
    permissions: PermissionProfile,
    fingerprint_verified: bool,
) -> Result<PreparedShare> {
    let (states, _) = authenticate_issuers(std::slice::from_ref(&recipient))?;
    ensure!(
        states.len() == 1,
        InvalidInput,
        "recipient evidence must contain one identity vault"
    );
    let (recipient_vault_id, history) = states
        .first_key_value()
        .ok_or_else(|| Error::new(ChurStatus::InvalidInput, "recipient evidence is empty"))?;
    let recipient_membership = history
        .last_key_value()
        .map(|(_, state)| state)
        .ok_or_else(|| {
            Error::new(
                ChurStatus::AuthenticationFailed,
                "recipient membership history is empty",
            )
        })?;
    let recipient_device = recipient_membership
        .device(&recipient_device_id)
        .ok_or_else(|| {
            Error::new(
                ChurStatus::AuthenticationFailed,
                "recipient device is not enrolled",
            )
        })?;
    ensure!(
        recipient_device.status() == DeviceStatus::Active,
        AuthenticationFailed,
        "recipient device is revoked"
    );
    prepare_share_to_device(
        db,
        root,
        source_vault_id,
        collection_id,
        *recipient_vault_id,
        recipient_device_id,
        *recipient_device.signing_public_key(),
        *recipient_device.hpke_public_key(),
        permissions,
        fingerprint_verified,
    )
}

#[expect(
    clippy::too_many_arguments,
    reason = "the internal boundary carries one authenticated recipient device"
)]
fn prepare_share_to_device(
    db: &mut CatalogDb,
    root: &Key,
    source_vault_id: Id,
    collection_id: Id,
    recipient_vault_id: Id,
    recipient_device_id: Id,
    recipient_signing_public_key: [u8; 32],
    recipient_hpke_public_key: [u8; 32],
    permissions: PermissionProfile,
    fingerprint_verified: bool,
) -> Result<PreparedShare> {
    ensure!(
        recipient_vault_id != source_vault_id,
        InvalidInput,
        "a source-vault device uses device enrollment instead of sharing"
    );
    let source_membership = sync_membership::load(db)?.ok_or_else(|| {
        Error::new(
            ChurStatus::RecoveryRequired,
            "collection sharing has no local device membership",
        )
    })?;
    ensure!(
        source_membership.vault_id() == &source_vault_id,
        CatalogCorrupt,
        "local membership belongs to another vault"
    );
    let (source_device_id, identity) = sync_keys::local_identity(db, root, &source_membership)?
        .ok_or_else(|| {
            Error::new(
                ChurStatus::RecoveryRequired,
                "collection sharing has no ordinary local identity",
            )
        })?;
    let collection = store::collection(db, &collection_id)?;
    let collection_key = sync_keys::collection_key(
        db,
        root,
        source_vault_id,
        collection_id,
        collection.current_epoch,
    )?;
    let domain = KeyDomain::collection(&collection_key, &collection_id, collection.current_epoch)?;
    let mut sharing_state = match sharing::load(db, &collection_id)? {
        Some(state) => state,
        None => sharing::provision(db, source_vault_id, collection_id, collection.current_epoch)?,
    };
    let mut log = sync_log::load(db, &source_membership)?;

    let existing = sharing_state
        .member(&recipient_vault_id, &recipient_device_id)
        .filter(|member| {
            member.is_active()
                && member.signing_public_key() == &recipient_signing_public_key
                && member.hpke_public_key() == &recipient_hpke_public_key
                && member.permissions() == permissions
        })
        .map(|member| member.membership_generation());
    let (membership, membership_operation) = if let Some(generation) = existing {
        let membership = sharing::membership_record_at(db, &collection_id, generation)?;
        let operation = operation_for(
            db,
            membership.issuer_device_id(),
            membership.created_sequence(),
        )?;
        (membership, operation)
    } else {
        if fingerprint_verified
            && sharing_state
                .recipient_pin(&recipient_vault_id, &recipient_device_id)
                .is_some()
        {
            sharing_state = sharing::verify_recipient_keys(
                db,
                &collection_id,
                recipient_vault_id,
                recipient_device_id,
                recipient_signing_public_key,
                recipient_hpke_public_key,
            )?;
        }
        let sequence = next_sequence(&log, &source_device_id)?;
        let membership = CollectionMembershipRecord::new(
            source_vault_id,
            collection_id,
            sharing_state.generation().checked_add(1).ok_or_else(|| {
                Error::new(
                    ChurStatus::ResourceLimitExceeded,
                    "collection membership generation has no successor",
                )
            })?,
            *sharing_state.commitment(),
            CollectionMembershipAction::Upsert(permissions),
            recipient_vault_id,
            recipient_device_id,
            recipient_signing_public_key,
            recipient_hpke_public_key,
            sharing_state.collection_epoch(),
            source_vault_id,
            source_device_id,
            source_membership.generation(),
            sequence,
        )?
        .sign(identity.signing_key());
        let operation = sync_receive::author_sharing_operation(
            db,
            &mut log,
            &source_membership,
            &mut sharing_state,
            &domain,
            random::id()?,
            source_device_id,
            identity.signing_key(),
            PayloadBody::ChangeCollectionMembership(membership.clone()),
        )?;
        if fingerprint_verified {
            sharing_state = sharing::verify_recipient_keys(
                db,
                &collection_id,
                recipient_vault_id,
                recipient_device_id,
                recipient_signing_public_key,
                recipient_hpke_public_key,
            )?;
        }
        (membership, operation)
    };

    if fingerprint_verified
        && sharing_state.recipient_verification(&recipient_vault_id, &recipient_device_id)
            != Some(RecipientVerification::Verified)
    {
        sharing_state = sharing::verify_recipient_keys(
            db,
            &collection_id,
            recipient_vault_id,
            recipient_device_id,
            recipient_signing_public_key,
            recipient_hpke_public_key,
        )?;
    }
    let current_generation = sharing_state
        .member(&recipient_vault_id, &recipient_device_id)
        .ok_or_else(|| Error::new(ChurStatus::InternalFailure, "prepared member is absent"))?
        .membership_generation();
    let existing_grant = sharing::load_grants(db, &collection_id)?
        .into_iter()
        .find(|grant| {
            grant.recipient_identity_vault_id() == &recipient_vault_id
                && grant.recipient_device_id() == &recipient_device_id
                && grant.collection_epoch() == sharing_state.collection_epoch()
                && grant.collection_membership_generation() == current_generation
                && grant.permissions() == permissions
        });
    let (grant, grant_operation) = if let Some(grant) = existing_grant {
        let operation = operation_for(db, grant.sender_device_id(), grant.created_sequence())?;
        (grant, operation)
    } else {
        let sequence = next_sequence(&log, &source_device_id)?;
        let grant_id = random::id()?;
        let grant = CollectionGrant::seal(
            grant_id,
            source_vault_id,
            collection_id,
            sharing_state.collection_epoch(),
            current_generation,
            recipient_vault_id,
            recipient_device_id,
            &recipient_hpke_public_key,
            source_device_id,
            permissions,
            source_membership.generation(),
            sequence,
            &collection_key,
            identity.signing_key(),
        )?;
        let operation = sync_receive::author_sharing_operation(
            db,
            &mut log,
            &source_membership,
            &mut sharing_state,
            &domain,
            grant_id,
            source_device_id,
            identity.signing_key(),
            PayloadBody::IssueCollectionGrant(grant.clone()),
        )?;
        (grant, operation)
    };
    Ok(PreparedShare {
        membership,
        membership_operation,
        grant,
        grant_operation,
    })
}

/// Revokes one recipient, rotates the collection, eagerly rewraps every active
/// object key, and issues current grants to all remaining recipients.
#[expect(
    clippy::too_many_arguments,
    reason = "the request names the source, target recipient, time, and batch bound"
)]
pub fn prepare_share_revocation(
    db: &mut CatalogDb,
    root: &Key,
    source_vault_id: Id,
    collection_id: Id,
    recipient_vault_id: Id,
    recipient_device_id: Id,
    accepted_at_ms: u64,
    rotation_operation_limit: usize,
) -> Result<PreparedShareRevocation> {
    ensure!(
        rotation_operation_limit != 0,
        InvalidInput,
        "the revocation rotation batch limit is zero"
    );
    let source_membership = sync_membership::load(db)?.ok_or_else(|| {
        Error::new(
            ChurStatus::RecoveryRequired,
            "collection revocation has no local device membership",
        )
    })?;
    ensure!(
        source_membership.vault_id() == &source_vault_id,
        CatalogCorrupt,
        "local membership belongs to another vault"
    );
    let (source_device_id, identity) = sync_keys::local_identity(db, root, &source_membership)?
        .ok_or_else(|| {
            Error::new(
                ChurStatus::RecoveryRequired,
                "collection revocation has no ordinary local identity",
            )
        })?;
    let mut sharing_state = sharing::load(db, &collection_id)?
        .ok_or_else(|| Error::new(ChurStatus::NotFound, "collection has no sharing membership"))?;
    ensure!(
        sharing_state.source_vault_id() == &source_vault_id,
        AuthenticationFailed,
        "collection belongs to another source vault"
    );
    let target = sharing_state
        .member(&recipient_vault_id, &recipient_device_id)
        .ok_or_else(|| Error::new(ChurStatus::NotFound, "collection recipient is unknown"))?;
    let target_signing_key = *target.signing_public_key();
    let target_hpke_key = *target.hpke_public_key();
    let mut log = sync_log::load(db, &source_membership)?;
    let (membership, membership_operation) = if target.is_active() {
        let old_key = sync_keys::collection_key(
            db,
            root,
            source_vault_id,
            collection_id,
            sharing_state.collection_epoch(),
        )?;
        let old_domain =
            KeyDomain::collection(&old_key, &collection_id, sharing_state.collection_epoch())?;
        let sequence = next_sequence(&log, &source_device_id)?;
        let membership = CollectionMembershipRecord::new(
            source_vault_id,
            collection_id,
            sharing_state.generation().checked_add(1).ok_or_else(|| {
                Error::new(
                    ChurStatus::ResourceLimitExceeded,
                    "collection membership generation has no successor",
                )
            })?,
            *sharing_state.commitment(),
            CollectionMembershipAction::Revoke,
            recipient_vault_id,
            recipient_device_id,
            target_signing_key,
            target_hpke_key,
            sharing_state
                .collection_epoch()
                .checked_add(1)
                .ok_or_else(|| {
                    Error::new(
                        ChurStatus::ResourceLimitExceeded,
                        "collection epoch has no successor",
                    )
                })?,
            source_vault_id,
            source_device_id,
            source_membership.generation(),
            sequence,
        )?
        .sign(identity.signing_key());
        let operation = sync_receive::author_sharing_operation(
            db,
            &mut log,
            &source_membership,
            &mut sharing_state,
            &old_domain,
            random::id()?,
            source_device_id,
            identity.signing_key(),
            PayloadBody::ChangeCollectionMembership(membership.clone()),
        )?;
        (membership, operation)
    } else {
        let membership =
            sharing::membership_record_at(db, &collection_id, target.membership_generation())?;
        ensure!(
            membership.action() == CollectionMembershipAction::Revoke
                && membership.recipient_identity_vault_id() == &recipient_vault_id
                && membership.recipient_device_id() == &recipient_device_id,
            CatalogCorrupt,
            "revoked recipient has no matching membership record"
        );
        let operation = operation_for(
            db,
            membership.issuer_device_id(),
            membership.created_sequence(),
        )?;
        (membership, operation)
    };

    let target_epoch = sharing_state.collection_epoch();
    let previous_epoch = target_epoch.checked_sub(1).ok_or_else(|| {
        Error::new(
            ChurStatus::CatalogCorrupt,
            "revoked collection has no previous epoch",
        )
    })?;
    let previous_key =
        sync_keys::collection_key(db, root, source_vault_id, collection_id, previous_epoch)?;
    let previous_domain = KeyDomain::collection(&previous_key, &collection_id, previous_epoch)?;
    let mut keys = sync_keys::key_directory(db, root, source_vault_id)?;
    let collection = store::collection(db, &collection_id)?;
    let mut rotation_operations = Vec::new();
    if collection.current_epoch == previous_epoch {
        let current_key: Key = random::secret::<32>()?;
        let envelope = CollectionKeyEnvelope::seal(
            root,
            source_vault_id,
            collection_id,
            target_epoch,
            target_epoch,
            Nonce::random()?,
            &current_key,
        )?;
        rotation_operations.push(sync_receive::author_rotation_operation(
            db,
            &mut log,
            &source_membership,
            &mut keys,
            root,
            &previous_domain,
            source_device_id,
            identity.signing_key(),
            accepted_at_ms,
            &OperationPayload::new(
                collection_id,
                previous_epoch,
                PayloadBody::CreateCollectionEpoch {
                    previous_collection_epoch: previous_epoch,
                    membership_generation: source_membership.generation(),
                    collection_key_envelope: envelope,
                },
            )?,
        )?);
    } else {
        ensure!(
            collection.current_epoch == target_epoch,
            SyncHeadRollback,
            "collection epoch is outside the pending revocation"
        );
    }
    let current_key =
        sync_keys::collection_key(db, root, source_vault_id, collection_id, target_epoch)?;
    let current_domain = KeyDomain::collection(&current_key, &collection_id, target_epoch)?;
    while rotation_operations.len() < rotation_operation_limit {
        let rotation =
            sync_rotation::load(db, source_vault_id, collection_id, &source_membership, root)?;
        let Some(object_id) = rotation.next_missing_object().copied() else {
            ensure!(
                rotation.is_complete(),
                InternalFailure,
                "collection rotation stopped before eager rewrap completed"
            );
            break;
        };
        let old_envelope = rotation.envelope(&object_id).ok_or_else(|| {
            Error::new(
                ChurStatus::CatalogCorrupt,
                "rotation target has no object-key envelope",
            )
        })?;
        let generation = old_envelope
            .envelope_generation()
            .checked_add(1)
            .ok_or_else(|| {
                Error::new(
                    ChurStatus::ResourceLimitExceeded,
                    "object envelope generation has no successor",
                )
            })?;
        let envelope = old_envelope.rewrap(
            &previous_key,
            &current_key,
            collection_id,
            target_epoch,
            generation,
            Nonce::random()?,
        )?;
        rotation_operations.push(sync_receive::author_rotation_operation(
            db,
            &mut log,
            &source_membership,
            &mut keys,
            root,
            &current_domain,
            source_device_id,
            identity.signing_key(),
            accepted_at_ms,
            &OperationPayload::new(
                collection_id,
                target_epoch,
                PayloadBody::RewrapObjectKey {
                    object_id,
                    object_key_envelope: envelope,
                },
            )?,
        )?);
    }

    let rotation_complete =
        sync_rotation::load(db, source_vault_id, collection_id, &source_membership, root)?
            .is_complete();
    let mut grants = Vec::new();
    if rotation_complete {
        let recipients = sharing_state
            .active_members()
            .map(|(vault_id, device_id, member)| {
                (
                    *vault_id,
                    *device_id,
                    *member.hpke_public_key(),
                    member.permissions(),
                    member.membership_generation(),
                )
            })
            .collect::<Vec<_>>();
        let existing_grants = sharing::load_grants(db, &collection_id)?;
        grants.reserve(recipients.len());
        for (vault_id, device_id, hpke_key, permissions, generation) in recipients {
            if let Some(grant) = existing_grants.iter().find(|grant| {
                grant.recipient_identity_vault_id() == &vault_id
                    && grant.recipient_device_id() == &device_id
                    && grant.collection_epoch() == target_epoch
                    && grant.collection_membership_generation() == generation
                    && grant.permissions() == permissions
            }) {
                grants.push((
                    grant.clone(),
                    operation_for(db, grant.sender_device_id(), grant.created_sequence())?,
                ));
                continue;
            }
            let sequence = next_sequence(&log, &source_device_id)?;
            let grant_id = random::id()?;
            let grant = CollectionGrant::seal(
                grant_id,
                source_vault_id,
                collection_id,
                target_epoch,
                generation,
                vault_id,
                device_id,
                &hpke_key,
                source_device_id,
                permissions,
                source_membership.generation(),
                sequence,
                &current_key,
                identity.signing_key(),
            )?;
            let operation = sync_receive::author_sharing_operation(
                db,
                &mut log,
                &source_membership,
                &mut sharing_state,
                &current_domain,
                grant_id,
                source_device_id,
                identity.signing_key(),
                PayloadBody::IssueCollectionGrant(grant.clone()),
            )?;
            grants.push((grant, operation));
        }
    }
    Ok(PreparedShareRevocation {
        membership,
        membership_operation,
        rotation_operations,
        grants,
        rotation_complete,
    })
}

/// Authenticates and installs one current grant for the local recipient device.
///
/// Issuer operation logs are checked in memory and are not mixed into the local
/// identity-vault log. The accepted sharing state and wrapped collection key
/// commit in one catalog transaction.
pub fn accept_share(
    db: &mut CatalogDb,
    root: &Key,
    issuers: &[IssuerEvidence<'_>],
    membership_records: &[(CollectionMembershipRecord, Operation)],
    grant: &CollectionGrant,
    grant_operation: &Operation,
) -> Result<CollectionMembershipState> {
    ensure!(
        !membership_records.is_empty(),
        InvalidInput,
        "a share has no collection membership chain"
    );
    let (issuer_states, authenticated_operations) = authenticate_issuers(issuers)?;
    let first = &membership_records[0].0;
    let mut collection_state = CollectionMembershipState::new(
        *first.source_vault_id(),
        *first.collection_id(),
        first.collection_epoch(),
    )?;
    for (record, operation) in membership_records {
        let issuer = issuer_state(
            &issuer_states,
            record.issuer_identity_vault_id(),
            record.issuer_membership_generation(),
        )?;
        require_authenticated_operation(&authenticated_operations, operation)?;
        ensure!(
            operation.vault_id() == record.issuer_identity_vault_id()
                && operation.device_id() == record.issuer_device_id()
                && operation.device_sequence() == record.created_sequence(),
            AuthenticationFailed,
            "collection membership does not match its authenticated operation"
        );
        collection_state.accept(record, issuer)?;
    }
    ensure!(
        u64::try_from(membership_records.len()).ok() == Some(collection_state.generation()),
        AuthenticationFailed,
        "collection membership evidence is not the complete chain"
    );
    ensure!(
        grant.source_vault_id() == collection_state.source_vault_id()
            && grant.collection_id() == collection_state.collection_id(),
        AuthenticationFailed,
        "collection grant belongs to another share"
    );
    require_authenticated_operation(&authenticated_operations, grant_operation)?;
    let sender = issuer_state(
        &issuer_states,
        grant_operation.vault_id(),
        grant.sender_membership_generation(),
    )?;
    ensure!(
        grant_operation.device_id() == grant.sender_device_id()
            && grant_operation.device_sequence() == grant.created_sequence(),
        AuthenticationFailed,
        "collection grant does not match its authenticated operation"
    );
    collection_state.validate_grant(grant, sender)?;

    let local_membership = sync_membership::load(db)?.ok_or_else(|| {
        Error::new(
            ChurStatus::RecoveryRequired,
            "share acceptance has no local device membership",
        )
    })?;
    ensure!(
        local_membership.vault_id() == grant.recipient_identity_vault_id(),
        AuthenticationFailed,
        "collection grant names another recipient vault"
    );
    let (local_device_id, local_identity) = sync_keys::local_identity(db, root, &local_membership)?
        .ok_or_else(|| {
            Error::new(
                ChurStatus::RecoveryRequired,
                "share acceptance has no ordinary local identity",
            )
        })?;
    let sender_device = sender.device(grant.sender_device_id()).ok_or_else(|| {
        Error::new(
            ChurStatus::AuthenticationFailed,
            "collection grant sender is unknown",
        )
    })?;
    let collection_key = grant.open_collection_key(
        local_membership.vault_id(),
        &local_device_id,
        &local_identity,
        sender_device.signing_public_key(),
    )?;
    let domain = KeyDomain::collection(
        &collection_key,
        collection_state.collection_id(),
        collection_state.collection_epoch(),
    )?;
    verify_current_payload(
        grant_operation,
        &domain,
        &PayloadBody::IssueCollectionGrant(grant.clone()),
    )?;
    for (record, operation) in membership_records {
        if operation.key_selector() == domain.selector() {
            verify_current_payload(
                operation,
                &domain,
                &PayloadBody::ChangeCollectionMembership(record.clone()),
            )?;
        }
    }

    install_share(
        db,
        root,
        *local_membership.vault_id(),
        &issuer_states,
        membership_records,
        grant,
        sender,
        &collection_state,
        &collection_key,
    )?;
    Ok(collection_state)
}

type IssuerStates = BTreeMap<Id, BTreeMap<u64, MembershipState>>;
type AuthenticatedOperations = BTreeMap<(Id, Id, u64), Vec<u8>>;

fn authenticate_issuers(
    issuers: &[IssuerEvidence<'_>],
) -> Result<(IssuerStates, AuthenticatedOperations)> {
    let mut states = BTreeMap::new();
    let mut accepted_operations = BTreeMap::new();
    for evidence in issuers {
        let first = evidence.membership.first().ok_or_else(|| {
            Error::new(
                ChurStatus::InvalidInput,
                "an issuer membership chain is empty",
            )
        })?;
        let initial = match first {
            IssuerMembershipRecord::Enrollment(record) => record,
            IssuerMembershipRecord::Revocation(_) => {
                return Err(Error::new(
                    ChurStatus::AuthenticationFailed,
                    "an issuer membership chain does not start with enrollment",
                ));
            }
        };
        let vault_id = *initial.vault_id();
        let mut current = MembershipState::bootstrap(initial)?;
        let mut snapshots = BTreeMap::from([(current.generation(), current.clone())]);
        let mut enrollments = vec![(*initial.issuer_device_id(), initial.created_sequence())];
        let mut revoked = Vec::new();
        for record in &evidence.membership[1..] {
            match record {
                IssuerMembershipRecord::Enrollment(record) => {
                    current.accept_enrollment(
                        record,
                        record.issuer_device_id(),
                        record.created_sequence(),
                    )?;
                    enrollments.push((*record.issuer_device_id(), record.created_sequence()));
                }
                IssuerMembershipRecord::Revocation(record) => {
                    current.accept_revocation(record, record.issuer_device_id())?;
                    revoked.push((
                        *record.revoked_device_id(),
                        record.final_accepted_device_sequence(),
                        *record.final_accepted_operation_digest(),
                    ));
                }
            }
            ensure!(
                snapshots
                    .insert(current.generation(), current.clone())
                    .is_none(),
                AuthenticationFailed,
                "issuer membership repeats a generation"
            );
        }

        let mut log = OperationLog::new();
        let mut pending = evidence.operations.iter().collect::<Vec<_>>();
        while !pending.is_empty() {
            let mut next = Vec::new();
            let mut progressed = false;
            for operation in pending {
                let mut candidate = log.clone();
                match candidate.restore_accepted(operation, &current) {
                    Ok(ApplyOutcome::Applied | ApplyOutcome::Duplicate) => {
                        log = candidate;
                        progressed = true;
                    }
                    Ok(ApplyOutcome::PendingGap | ApplyOutcome::PendingCause) => {
                        next.push(operation);
                    }
                    Err(error) => return Err(error),
                }
            }
            ensure!(
                progressed,
                SyncHeadRollback,
                "issuer operations have a gap or an absent causal predecessor"
            );
            pending = next;
        }
        for operation in evidence.operations {
            let key = (
                vault_id,
                *operation.device_id(),
                operation.device_sequence(),
            );
            let encoded = operation.encode();
            ensure!(
                accepted_operations
                    .insert(key, encoded.clone())
                    .is_none_or(|existing| existing == encoded),
                SyncChainFork,
                "issuer operations conflict at one sequence"
            );
        }
        for (device_id, sequence) in enrollments {
            ensure!(
                accepted_operations.contains_key(&(vault_id, device_id, sequence)),
                SyncHeadRollback,
                "issuer enrollment has no authenticated outer operation"
            );
        }
        for (device_id, sequence, digest) in revoked {
            ensure!(
                log.head(&device_id) == Some((sequence, digest)),
                SyncHeadRollback,
                "revoked issuer operation chain does not reach its pinned head"
            );
        }
        ensure!(
            states.insert(vault_id, snapshots).is_none(),
            InvalidInput,
            "issuer evidence repeats one vault"
        );
    }
    Ok((states, accepted_operations))
}

fn issuer_state<'a>(
    states: &'a IssuerStates,
    vault_id: &Id,
    generation: u64,
) -> Result<&'a MembershipState> {
    states
        .get(vault_id)
        .and_then(|history| history.get(&generation))
        .ok_or_else(|| {
            Error::new(
                ChurStatus::AuthenticationFailed,
                "sharing issuer membership generation is absent",
            )
        })
}

fn require_authenticated_operation(
    operations: &AuthenticatedOperations,
    operation: &Operation,
) -> Result<()> {
    ensure!(
        operations
            .get(&(
                *operation.vault_id(),
                *operation.device_id(),
                operation.device_sequence(),
            ))
            .is_some_and(|accepted| accepted == &operation.encode()),
        AuthenticationFailed,
        "sharing record has no matching authenticated issuer operation"
    );
    Ok(())
}

fn verify_current_payload(
    operation: &Operation,
    domain: &KeyDomain,
    expected: &PayloadBody,
) -> Result<()> {
    ensure!(
        operation.key_selector() == domain.selector(),
        AuthenticationFailed,
        "sharing operation uses another collection key selector"
    );
    let payload = OperationPayload::decode(&operation.open_payload(domain.operation_key())?)?;
    payload.validate_for_operation(operation, domain.collection_id(), domain.collection_epoch())?;
    ensure!(
        payload.body() == expected,
        AuthenticationFailed,
        "sharing operation carries another canonical record"
    );
    Ok(())
}

#[expect(
    clippy::too_many_arguments,
    reason = "the transaction receives the authenticated share and its local wrapping context"
)]
fn install_share(
    db: &mut CatalogDb,
    root: &Key,
    local_vault_id: Id,
    issuer_states: &IssuerStates,
    membership_records: &[(CollectionMembershipRecord, Operation)],
    grant: &CollectionGrant,
    sender: &MembershipState,
    incoming: &CollectionMembershipState,
    collection_key: &Key,
) -> Result<()> {
    let existing_state = sharing::load(db, incoming.collection_id())?;
    let existing_collection = match store::collection(db, incoming.collection_id()) {
        Ok(collection) => Some(collection),
        Err(error) if error.status() == ChurStatus::NotFound => None,
        Err(error) => return Err(error),
    };
    ensure!(
        existing_state.is_some() == existing_collection.is_some(),
        CatalogCorrupt,
        "shared collection and authorization state are incomplete"
    );
    let accepted_generation = existing_state
        .as_ref()
        .map_or(0, CollectionMembershipState::generation);
    ensure!(
        accepted_generation <= incoming.generation(),
        SyncHeadRollback,
        "incoming collection membership rolls back durable state"
    );
    let accepted_records = usize::try_from(accepted_generation).map_err(|_| {
        Error::new(
            ChurStatus::ResourceLimitExceeded,
            "accepted membership generation exceeds the address space",
        )
    })?;
    for (record, _) in membership_records.iter().take(accepted_records) {
        ensure!(
            sharing::membership_record_at(
                db,
                incoming.collection_id(),
                record.collection_membership_generation(),
            )?
            .encode()
                == record.encode(),
            SyncChainFork,
            "incoming collection membership conflicts with durable history"
        );
    }
    if let Some(collection) = existing_collection {
        ensure!(
            collection.policy_type == COLLECTION_POLICY_SHARED
                && collection.status == COLLECTION_STATUS_ACTIVE
                && collection.current_epoch <= incoming.collection_epoch(),
            Conflict,
            "collection identifier already has another local policy or epoch"
        );
    }
    let existing_grant = sharing::load_grants(db, incoming.collection_id())?
        .into_iter()
        .any(|stored| stored.encode() == grant.encode());
    let existing_envelope = if existing_collection
        .is_some_and(|collection| collection.current_epoch == incoming.collection_epoch())
    {
        let body = store::active_collection_envelope(
            db,
            incoming.collection_id(),
            incoming.collection_epoch(),
        )?;
        ensure!(
            sync_keys::collection_key(
                db,
                root,
                local_vault_id,
                *incoming.collection_id(),
                incoming.collection_epoch(),
            )?
            .expose()
                == collection_key.expose(),
            AuthenticationFailed,
            "incoming grant changes the accepted collection key"
        );
        Some(body)
    } else {
        None
    };
    if accepted_generation == incoming.generation() && existing_grant && existing_envelope.is_some()
    {
        return Ok(());
    }

    let envelope = existing_envelope.map_or_else(
        || {
            CollectionKeyEnvelope::seal(
                root,
                local_vault_id,
                *incoming.collection_id(),
                incoming.collection_epoch(),
                1,
                Nonce::random()?,
                collection_key,
            )
            .map(|envelope| envelope.encode())
        },
        Ok,
    )?;
    let created_revision = existing_collection.map_or_else(
        || {
            schema::generation(db)?.checked_add(1).ok_or_else(|| {
                Error::new(
                    ChurStatus::ResourceLimitExceeded,
                    "catalog generation has no successor",
                )
            })
        },
        |collection| Ok(collection.created_revision),
    )?;
    let collection = Collection {
        collection_id: *incoming.collection_id(),
        current_epoch: incoming.collection_epoch(),
        policy_type: COLLECTION_POLICY_SHARED,
        created_revision,
        status: COLLECTION_STATUS_ACTIVE,
    };
    db.transaction(|transaction| {
        store::project_collection_with_envelope(transaction, &collection, 1, &envelope)?;
        let mut current = match &existing_state {
            Some(state) => state.clone(),
            None => {
                let state = CollectionMembershipState::new(
                    *incoming.source_vault_id(),
                    *incoming.collection_id(),
                    membership_records[0].0.collection_epoch(),
                )?;
                sharing::project_provision(transaction, &state)?;
                state
            }
        };
        for (record, _) in membership_records.iter().skip(accepted_records) {
            let issuer = issuer_state(
                issuer_states,
                record.issuer_identity_vault_id(),
                record.issuer_membership_generation(),
            )?;
            current = sharing::project_membership(transaction, &current, record, issuer)?.0;
        }
        ensure!(
            current.generation() == incoming.generation()
                && current.commitment() == incoming.commitment()
                && current.collection_epoch() == incoming.collection_epoch(),
            AuthenticationFailed,
            "projected share does not match authenticated state"
        );
        sharing::project_grant(transaction, &current, grant, sender)?;
        schema::bump_generation(transaction)?;
        Ok(())
    })
}

fn next_sequence(log: &sync_log::DurableOperationLog, device_id: &Id) -> Result<u64> {
    log.head(device_id).map_or(Ok(1), |(sequence, _)| {
        sequence.checked_add(1).ok_or_else(|| {
            Error::new(
                ChurStatus::ResourceLimitExceeded,
                "local operation sequence has no successor",
            )
        })
    })
}

fn operation_for(db: &CatalogDb, device_id: &Id, sequence: u64) -> Result<Operation> {
    let bytes = sync_log::record_at(db, device_id, sequence)?.ok_or_else(|| {
        Error::new(
            ChurStatus::CatalogCorrupt,
            "sharing record has no authenticated outer operation",
        )
    })?;
    Operation::decode(&bytes).map_err(|_| {
        Error::new(
            ChurStatus::CatalogCorrupt,
            "sharing outer operation is malformed",
        )
    })
}

#[cfg(test)]
mod tests {
    #![allow(clippy::expect_used, clippy::panic)]

    use chur_format::envelope::{CollectionKeyEnvelope, ObjectKeyEnvelope};
    use chur_sync_protocol::identity::DeviceIdentity;

    use super::*;
    use crate::{
        db::{CatalogKey, CatalogLocation},
        model::{
            COLLECTION_POLICY_SHARED, COLLECTION_POLICY_VAULT_DEFAULT, COLLECTION_STATUS_ACTIVE,
            Collection,
        },
        schema,
    };
    use chur_crypto::Nonce;

    fn id(byte: u8) -> Id {
        Id::new([byte; 16]).expect("id")
    }

    #[test]
    fn multi_recipient_device_loss_rotates_forward_and_replays() {
        let source_vault = id(1);
        let collection_id = id(2);
        let root = Key::new([3; 32]);
        let catalog_key = CatalogKey::derive(&root, &source_vault).expect("catalog key");
        let mut db = CatalogDb::open(&CatalogLocation::Memory, &catalog_key).expect("catalog");
        schema::open_at_current_version(&mut db, 1).expect("schema");
        sync_receive::provision_local_identity(&mut db, &root, source_vault)
            .expect("local identity");
        let collection_key = Key::new([4; 32]);
        let envelope = CollectionKeyEnvelope::seal(
            &root,
            source_vault,
            collection_id,
            1,
            1,
            Nonce::new([5; 24]),
            &collection_key,
        )
        .expect("collection envelope");
        store::put_collection_with_envelope(
            &mut db,
            &Collection {
                collection_id,
                current_epoch: 1,
                policy_type: COLLECTION_POLICY_VAULT_DEFAULT,
                created_revision: 1,
                status: COLLECTION_STATUS_ACTIVE,
            },
            1,
            &envelope.encode(),
        )
        .expect("collection");
        let object_id = id(14);
        let object_key = Key::new([15; 32]);
        let object_envelope = ObjectKeyEnvelope::seal(
            &collection_key,
            source_vault,
            collection_id,
            1,
            object_id,
            1,
            Nonce::new([16; 24]),
            &object_key,
        )
        .expect("object envelope");
        db.transaction(|transaction| {
            transaction
                .execute(
                    "INSERT INTO objects VALUES (
                         ?1, 1, ?2, ?3, 1, 1, 1, 0, 1, 1, 1, 0, 0, 1, 1, 0, 1, 72
                     )",
                    rusqlite::params![
                        object_id.as_bytes().as_slice(),
                        collection_id.as_bytes().as_slice(),
                        id(17).as_bytes().as_slice(),
                    ],
                )
                .expect("object");
            transaction
                .execute(
                    "INSERT INTO object_key_envelopes VALUES (?1, 1, 1, ?2)",
                    rusqlite::params![object_id.as_bytes().as_slice(), object_envelope.encode(),],
                )
                .expect("object envelope");
            transaction
                .execute(
                    "INSERT INTO sync_object_envelope_epochs VALUES (?1, ?2, 1, 1)",
                    rusqlite::params![
                        object_id.as_bytes().as_slice(),
                        collection_id.as_bytes().as_slice(),
                    ],
                )
                .expect("envelope projection");
            Ok(())
        })
        .expect("object projection");
        let recipient_vault = id(6);
        let recipient_device = id(7);
        let recipient = DeviceIdentity::from_seeds([8; 32], [9; 32]);
        let enrollment = EnrollmentRecord::initial(
            recipient_vault,
            recipient_device,
            recipient.signing_public_key(),
            recipient.hpke_public_key(),
        )
        .expect("recipient enrollment")
        .sign(recipient.signing_key());

        let first = prepare_share(
            &mut db,
            &root,
            source_vault,
            collection_id,
            &enrollment,
            PermissionProfile::Contribute,
            true,
        )
        .expect("share");
        let replay = prepare_share(
            &mut db,
            &root,
            source_vault,
            collection_id,
            &enrollment,
            PermissionProfile::Contribute,
            true,
        )
        .expect("replay");
        let source_membership = sync_membership::load(&db)
            .expect("source membership")
            .expect("present");
        let source_key = source_membership
            .device(first.grant().sender_device_id())
            .expect("sender")
            .signing_public_key();
        assert_eq!(
            first
                .grant()
                .open_collection_key(&recipient_vault, &recipient_device, &recipient, source_key,)
                .expect("open")
                .expose(),
            collection_key.expose()
        );
        assert_eq!(first.membership().encode(), replay.membership().encode());
        assert_eq!(first.grant().encode(), replay.grant().encode());
        assert_eq!(
            first.membership_operation().encode(),
            replay.membership_operation().encode()
        );
        assert_eq!(
            first.grant_operation().encode(),
            replay.grant_operation().encode()
        );
        assert!(
            sharing::load(&db, &collection_id)
                .expect("sharing state")
                .expect("present")
                .recipient_verification(&recipient_vault, &recipient_device)
                == Some(RecipientVerification::Verified)
        );
        assert_eq!(
            sync_log::records_after(&db, first.grant().sender_device_id(), 0)
                .expect("operations")
                .len(),
            3
        );

        let second_vault = id(10);
        let second_device = id(11);
        let second = DeviceIdentity::from_seeds([12; 32], [13; 32]);
        let second_enrollment = EnrollmentRecord::initial(
            second_vault,
            second_device,
            second.signing_public_key(),
            second.hpke_public_key(),
        )
        .expect("second enrollment")
        .sign(second.signing_key());
        let mut recipient_membership =
            MembershipState::bootstrap(&second_enrollment).expect("recipient membership");
        let recipient_operation_key = Key::new([21; 32]);
        let recipient_selector = id(22);
        let mut recipient_log = OperationLog::new();
        let initial_operation = recipient_log
            .author(
                id(23),
                second_vault,
                second_device,
                recipient_selector,
                &recipient_operation_key,
                Nonce::new([24; 24]),
                b"initial",
                second.signing_key(),
                &recipient_membership,
            )
            .expect("initial operation");
        assert!(
            recipient_log
                .accept(&initial_operation, &recipient_membership)
                .is_ok()
        );
        let peer_device = id(25);
        let peer = DeviceIdentity::from_seeds([26; 32], [27; 32]);
        let peer_enrollment = EnrollmentRecord::new(
            second_vault,
            peer_device,
            peer.signing_public_key(),
            peer.hpke_public_key(),
            2,
            second_device,
            2,
            *recipient_membership.commitment(),
            [28; 32],
        )
        .expect("peer enrollment")
        .sign(second.signing_key());
        let peer_operation = recipient_log
            .author(
                id(29),
                second_vault,
                second_device,
                recipient_selector,
                &recipient_operation_key,
                Nonce::new([30; 24]),
                b"peer",
                second.signing_key(),
                &recipient_membership,
            )
            .expect("peer operation");
        recipient_membership
            .accept_enrollment(&peer_enrollment, &second_device, 2)
            .expect("accept peer");
        let recipient_records = [
            IssuerMembershipRecord::Enrollment(second_enrollment.clone()),
            IssuerMembershipRecord::Enrollment(peer_enrollment),
        ];
        let recipient_operations = [initial_operation, peer_operation];
        let incomplete_recipient = IssuerEvidence {
            membership: &recipient_records,
            operations: &recipient_operations[..1],
        };
        let Err(error) = prepare_share_for_device(
            &mut db,
            &root,
            source_vault,
            collection_id,
            incomplete_recipient,
            peer_device,
            PermissionProfile::Read,
            true,
        ) else {
            panic!("incomplete recipient evidence was accepted");
        };
        assert_eq!(error.status(), ChurStatus::SyncHeadRollback);
        let second_primary_share = prepare_share_for_device(
            &mut db,
            &root,
            source_vault,
            collection_id,
            IssuerEvidence {
                membership: &recipient_records,
                operations: &recipient_operations,
            },
            second_device,
            PermissionProfile::Read,
            true,
        )
        .expect("second primary share");
        let second_share = prepare_share_for_device(
            &mut db,
            &root,
            source_vault,
            collection_id,
            IssuerEvidence {
                membership: &recipient_records,
                operations: &recipient_operations,
            },
            peer_device,
            PermissionProfile::Read,
            true,
        )
        .expect("second share");
        assert_eq!(second_share.grant().recipient_device_id(), &peer_device);
        assert_eq!(
            second_share
                .grant()
                .open_collection_key(&second_vault, &peer_device, &peer, source_key)
                .expect("peer grant")
                .expose(),
            collection_key.expose()
        );
        let revoked = prepare_share_revocation(
            &mut db,
            &root,
            source_vault,
            collection_id,
            second_vault,
            peer_device,
            1_000,
            1,
        )
        .expect("revoke share");
        assert!(revoked.membership().action() == CollectionMembershipAction::Revoke);
        assert!(!revoked.rotation_complete());
        assert_eq!(revoked.rotation_operations().len(), 1);
        assert!(revoked.grants().is_empty());
        let continued = prepare_share_revocation(
            &mut db,
            &root,
            source_vault,
            collection_id,
            second_vault,
            peer_device,
            1_500,
            4_096,
        )
        .expect("continue revocation");
        assert!(continued.rotation_complete());
        assert_eq!(continued.rotation_operations().len(), 1);
        assert_eq!(continued.grants().len(), 2);
        assert!(continued.grants().iter().all(|(grant, _)| {
            grant.collection_epoch() == 2 && grant.recipient_device_id() != &peer_device
        }));
        let first_remaining = continued
            .grants()
            .iter()
            .find(|(grant, _)| grant.recipient_device_id() == &recipient_device)
            .expect("first recipient grant")
            .0
            .open_collection_key(&recipient_vault, &recipient_device, &recipient, source_key)
            .expect("first recipient rotated key");
        let second_remaining = continued
            .grants()
            .iter()
            .find(|(grant, _)| grant.recipient_device_id() == &second_device)
            .expect("second recipient primary grant")
            .0
            .open_collection_key(&second_vault, &second_device, &second, source_key)
            .expect("second recipient rotated key");
        assert_eq!(first_remaining.expose(), second_remaining.expose());
        let rotation =
            sync_rotation::load(&db, source_vault, collection_id, &source_membership, &root)
                .expect("rotation");
        assert_eq!(
            rotation
                .envelope(&object_id)
                .expect("rewrapped object")
                .open(&first_remaining)
                .expect("open rewrapped object")
                .expose(),
            object_key.expose()
        );
        let replayed_revocation = prepare_share_revocation(
            &mut db,
            &root,
            source_vault,
            collection_id,
            second_vault,
            peer_device,
            2_000,
            4_096,
        )
        .expect("replay revocation");
        assert_eq!(
            revoked.membership().encode(),
            replayed_revocation.membership().encode()
        );
        assert!(replayed_revocation.rotation_operations().is_empty());
        assert_eq!(replayed_revocation.grants().len(), 2);
        assert_eq!(
            continued
                .grants()
                .iter()
                .map(|(grant, _)| grant.encode())
                .collect::<Vec<_>>(),
            replayed_revocation
                .grants()
                .iter()
                .map(|(grant, _)| grant.encode())
                .collect::<Vec<_>>()
        );
        let final_state = sharing::load(&db, &collection_id)
            .expect("sharing state")
            .expect("present");
        assert_eq!(final_state.collection_epoch(), 2);
        assert!(
            final_state
                .validate_grant(first.grant(), &source_membership)
                .is_err()
        );
        assert!(
            final_state
                .validate_grant(second_primary_share.grant(), &source_membership)
                .is_err()
        );
        assert!(
            final_state
                .validate_grant(second_share.grant(), &source_membership)
                .is_err()
        );
    }

    #[test]
    fn recipient_authenticates_and_installs_a_share_atomically() {
        let source_vault = id(21);
        let collection_id = id(22);
        let source_root = Key::new([23; 32]);
        let source_catalog_key = CatalogKey::derive(&source_root, &source_vault).expect("key");
        let mut source =
            CatalogDb::open(&CatalogLocation::Memory, &source_catalog_key).expect("source");
        schema::open_at_current_version(&mut source, 1).expect("schema");
        sync_receive::provision_local_identity(&mut source, &source_root, source_vault)
            .expect("source identity");
        let source_collection_key = Key::new([24; 32]);
        let source_envelope = CollectionKeyEnvelope::seal(
            &source_root,
            source_vault,
            collection_id,
            1,
            1,
            Nonce::new([25; 24]),
            &source_collection_key,
        )
        .expect("source envelope");
        store::put_collection_with_envelope(
            &mut source,
            &Collection {
                collection_id,
                current_epoch: 1,
                policy_type: COLLECTION_POLICY_VAULT_DEFAULT,
                created_revision: 1,
                status: COLLECTION_STATUS_ACTIVE,
            },
            1,
            &source_envelope.encode(),
        )
        .expect("source collection");

        let recipient_vault = id(26);
        let recipient_root = Key::new([27; 32]);
        let recipient_catalog_key =
            CatalogKey::derive(&recipient_root, &recipient_vault).expect("recipient key");
        let mut recipient =
            CatalogDb::open(&CatalogLocation::Memory, &recipient_catalog_key).expect("recipient");
        schema::open_at_current_version(&mut recipient, 1).expect("schema");
        let (recipient_enrollment, _) = sync_receive::provision_local_identity(
            &mut recipient,
            &recipient_root,
            recipient_vault,
        )
        .expect("recipient identity");
        let prepared = prepare_share(
            &mut source,
            &source_root,
            source_vault,
            collection_id,
            &recipient_enrollment,
            PermissionProfile::Read,
            true,
        )
        .expect("prepare share");
        let source_device = *prepared.grant().sender_device_id();
        let source_enrollment =
            sync_membership::enrollment_for_device(&source, &source_device).expect("enrollment");
        let operations = sync_log::records_after(&source, &source_device, 0)
            .expect("source operations")
            .iter()
            .map(|bytes| Operation::decode(bytes).expect("operation"))
            .collect::<Vec<_>>();
        let evidence = IssuerEvidence {
            membership: &[IssuerMembershipRecord::Enrollment(source_enrollment)],
            operations: &operations,
        };
        let membership = [(
            prepared.membership().clone(),
            prepared.membership_operation().clone(),
        )];
        let before = schema::generation(&recipient).expect("generation");
        let Err(error) = accept_share(
            &mut recipient,
            &recipient_root,
            &[IssuerEvidence {
                membership: evidence.membership,
                operations: &operations[1..],
            }],
            &membership,
            prepared.grant(),
            prepared.grant_operation(),
        ) else {
            panic!("incomplete issuer chain was accepted");
        };
        assert_eq!(error.status(), ChurStatus::SyncHeadRollback);
        recipient
            .connection()
            .execute_batch(
                "CREATE TRIGGER reject_received_grant
                 BEFORE INSERT ON sharing_grants
                 BEGIN SELECT RAISE(ABORT, 'test rejection'); END;",
            )
            .expect("trigger");
        assert!(
            accept_share(
                &mut recipient,
                &recipient_root,
                &[evidence],
                &membership,
                prepared.grant(),
                prepared.grant_operation(),
            )
            .is_err()
        );
        assert_eq!(
            store::collection(&recipient, &collection_id)
                .expect_err("failed transaction left a collection")
                .status(),
            ChurStatus::NotFound
        );
        assert!(
            sharing::load(&recipient, &collection_id)
                .expect("sharing state")
                .is_none()
        );
        recipient
            .connection()
            .execute_batch("DROP TRIGGER reject_received_grant")
            .expect("drop trigger");
        accept_share(
            &mut recipient,
            &recipient_root,
            &[evidence],
            &membership,
            prepared.grant(),
            prepared.grant_operation(),
        )
        .expect("accept share");
        let after = schema::generation(&recipient).expect("generation");
        assert!(after > before);
        assert_eq!(
            store::collection(&recipient, &collection_id)
                .expect("shared collection")
                .policy_type,
            COLLECTION_POLICY_SHARED
        );
        assert_eq!(
            sync_keys::collection_key(
                &recipient,
                &recipient_root,
                recipient_vault,
                collection_id,
                1,
            )
            .expect("recipient collection key")
            .expose(),
            source_collection_key.expose()
        );
        accept_share(
            &mut recipient,
            &recipient_root,
            &[IssuerEvidence {
                membership: evidence.membership,
                operations: evidence.operations,
            }],
            &membership,
            prepared.grant(),
            prepared.grant_operation(),
        )
        .expect("exact replay");
        assert_eq!(schema::generation(&recipient).expect("generation"), after);
    }
}
