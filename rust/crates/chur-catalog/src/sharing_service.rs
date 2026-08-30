//! Outbound collection-sharing orchestration over durable protocol primitives.

use chur_core::{ChurStatus, Error, Id, Result, ensure};
use chur_crypto::{Key, random};
use chur_sync_protocol::{
    KeyDomain,
    collection_membership::{
        CollectionMembershipAction, CollectionMembershipRecord, RecipientVerification,
    },
    grant::{CollectionGrant, PermissionProfile},
    membership::EnrollmentRecord,
    operation::Operation,
    payload::PayloadBody,
    state::MembershipState,
};

use crate::{CatalogDb, sharing, store, sync_keys, sync_log, sync_membership, sync_receive};

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
                && member.signing_public_key() == recipient_enrollment.signing_public_key()
                && member.hpke_public_key() == recipient_enrollment.hpke_public_key()
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
                *recipient_enrollment.signing_public_key(),
                *recipient_enrollment.hpke_public_key(),
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
            *recipient_enrollment.signing_public_key(),
            *recipient_enrollment.hpke_public_key(),
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
                *recipient_enrollment.signing_public_key(),
                *recipient_enrollment.hpke_public_key(),
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
            *recipient_enrollment.signing_public_key(),
            *recipient_enrollment.hpke_public_key(),
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
            recipient_enrollment.hpke_public_key(),
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
    #![allow(clippy::expect_used)]

    use chur_format::envelope::CollectionKeyEnvelope;
    use chur_sync_protocol::identity::DeviceIdentity;

    use super::*;
    use crate::{
        db::{CatalogKey, CatalogLocation},
        model::{COLLECTION_POLICY_VAULT_DEFAULT, COLLECTION_STATUS_ACTIVE, Collection},
        schema,
    };
    use chur_crypto::Nonce;

    fn id(byte: u8) -> Id {
        Id::new([byte; 16]).expect("id")
    }

    #[test]
    fn verified_share_is_hpke_openable_and_idempotent() {
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
    }
}
