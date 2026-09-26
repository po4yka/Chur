//! Recipient-side acceptance of signed collection content and download plans.

use std::collections::BTreeMap;

use chur_core::{ChurStatus, Error, Id, Result, ensure};
use chur_crypto::Key;
use chur_format::constants::{ObjectState, StreamKind};
use chur_format::envelope::ObjectKeyEnvelope;
use chur_sync_protocol::{
    KeyDirectory, KeyDomain,
    collection_membership::{CollectionMembershipRecord, CollectionMembershipState},
    collection_operation::CollectionOperation,
    grant::{CollectionGrant, PermissionProfile},
    operation::Operation,
    operation_log::ApplyOutcome,
    payload::{MetadataField, OperationPayload, PayloadBody},
};

use crate::model::{Object, Stream};
use crate::{CatalogDb, sharing, sharing_log, sharing_service, store, sync_keys, sync_membership};

/// Authenticated immutable facts needed to fetch and verify one shared container.
pub struct SharedObject {
    /// Source vault that signed the object commit.
    pub source_vault_id: Id,
    /// Security collection that grants access to the object key.
    pub collection_id: Id,
    /// Stable object identifier.
    pub object_id: Id,
    /// Committed object generation.
    pub object_generation: u64,
    /// Original stream identifier.
    pub stream_id: Id,
    /// Opaque remote store identifier.
    pub store_id: Id,
    /// Exact encoded container length.
    pub container_length: u64,
    /// Signed final chunk commitment.
    pub container_commitment: [u8; 32],
    /// Signed wrapping of the object key.
    pub object_key_envelope: ObjectKeyEnvelope,
    /// Initial private metadata from the creation operation.
    pub metadata: Vec<MetadataField>,
}

/// Current selector and objects that have authenticated commits but no local copy.
pub struct ReceivePlan {
    /// Current collection selector for the paginated transport.
    pub selector: Id,
    /// Source vault identifier.
    pub source_vault_id: Id,
    /// Shared collection identifier.
    pub collection_id: Id,
    /// Committed objects absent from the local catalog.
    pub objects: Vec<SharedObject>,
    /// Records awaiting an earlier signed chain step or causal predecessor.
    pub pending_operations: usize,
}

/// Applies one bounded page only after the issuer chain and addressed grant verify.
#[expect(
    clippy::too_many_arguments,
    reason = "share acceptance needs signed grant and issuer evidence"
)]
pub fn receive(
    db: &mut CatalogDb,
    root: &Key,
    local_vault_id: Id,
    issuers: &[sharing_service::IssuerEvidence<'_>],
    membership_records: &[(CollectionMembershipRecord, Operation)],
    grant: &CollectionGrant,
    grant_operation: &Operation,
    records: &[CollectionOperation],
) -> Result<ReceivePlan> {
    let collection_state = sharing_service::accept_share(
        db,
        root,
        issuers,
        membership_records,
        grant,
        grant_operation,
    )?;
    let (states, _) = sharing_service::authenticate_issuers(issuers)?;
    let mut memberships = states
        .into_iter()
        .map(|(vault_id, history)| {
            history
                .into_iter()
                .next_back()
                .map(|(_, state)| (vault_id, state))
                .ok_or_else(|| {
                    Error::new(ChurStatus::AuthenticationFailed, "issuer history is empty")
                })
        })
        .collect::<Result<BTreeMap<_, _>>>()?;
    let local_membership = sync_membership::load(db)?.ok_or_else(|| {
        Error::new(
            ChurStatus::RecoveryRequired,
            "recipient membership is absent",
        )
    })?;
    ensure!(
        local_membership.vault_id() == &local_vault_id,
        AuthenticationFailed,
        "recipient membership names another vault"
    );
    memberships.insert(local_vault_id, local_membership);
    let collection_key = sync_keys::collection_key(
        db,
        root,
        local_vault_id,
        *collection_state.collection_id(),
        collection_state.collection_epoch(),
    )?;
    let domain = KeyDomain::collection(
        &collection_key,
        collection_state.collection_id(),
        collection_state.collection_epoch(),
    )?;
    let selector = *domain.selector();
    let mut keys = KeyDirectory::new(root, &local_vault_id)?;
    keys.insert(domain)?;
    let mut log = sharing_log::load(
        db,
        *collection_state.collection_id(),
        collection_state.collection_epoch(),
        selector,
        &keys,
        &memberships,
        &collection_state,
    )?;
    let source = memberships
        .get(collection_state.source_vault_id())
        .ok_or_else(|| {
            Error::new(
                ChurStatus::AuthenticationFailed,
                "source membership is absent",
            )
        })?;
    let mut pending_operations = 0;
    for record in records {
        let payload = OperationPayload::open_for_collection_operation(record, &keys)?;
        let issuer = memberships
            .get(record.issuer_identity_vault_id())
            .ok_or_else(|| {
                Error::new(
                    ChurStatus::AuthenticationFailed,
                    "collection operation issuer is unknown",
                )
            })?;
        match log.accept(db, record, &payload, issuer, source, &collection_state)? {
            ApplyOutcome::Applied | ApplyOutcome::Duplicate => {}
            ApplyOutcome::PendingGap | ApplyOutcome::PendingCause => pending_operations += 1,
        }
    }
    let (objects, missing_creations) = pending_objects(db, &keys, &collection_state, &selector)?;
    Ok(ReceivePlan {
        selector,
        source_vault_id: *collection_state.source_vault_id(),
        collection_id: *collection_state.collection_id(),
        objects,
        pending_operations: pending_operations + missing_creations,
    })
}

/// Resolves pending objects from the accepted, encrypted local operation log.
pub fn pending_for_collection(
    db: &CatalogDb,
    root: &Key,
    local_vault_id: Id,
    collection_id: Id,
) -> Result<ReceivePlan> {
    let state = sharing::load(db, &collection_id)?
        .ok_or_else(|| Error::new(ChurStatus::NotFound, "shared collection is absent"))?;
    let local_membership = sync_membership::load(db)?.ok_or_else(|| {
        Error::new(
            ChurStatus::RecoveryRequired,
            "recipient membership is absent",
        )
    })?;
    let (device_id, _) = sync_keys::local_identity(db, root, &local_membership)?
        .ok_or_else(|| Error::new(ChurStatus::RecoveryRequired, "recipient identity is absent"))?;
    ensure!(
        local_membership.vault_id() == &local_vault_id
            && state.is_authorized(&local_vault_id, &device_id, PermissionProfile::Read),
        AuthenticationFailed,
        "local device cannot read the shared collection"
    );
    let collection_key = sync_keys::collection_key(
        db,
        root,
        local_vault_id,
        collection_id,
        state.collection_epoch(),
    )?;
    let domain = KeyDomain::collection(&collection_key, &collection_id, state.collection_epoch())?;
    let selector = *domain.selector();
    let mut keys = KeyDirectory::new(root, &local_vault_id)?;
    keys.insert(domain)?;
    let (objects, missing_creations) = pending_objects(db, &keys, &state, &selector)?;
    Ok(ReceivePlan {
        selector,
        source_vault_id: *state.source_vault_id(),
        collection_id,
        objects,
        pending_operations: missing_creations,
    })
}

struct Draft {
    generation: u64,
    store_id: Id,
    stream_id: Id,
    metadata: Vec<MetadataField>,
    commit: Option<(u64, [u8; 32], ObjectKeyEnvelope)>,
    deleted: bool,
}

/// Rebuilds the immutable create/commit projection from the accepted local log.
pub fn pending_objects(
    db: &CatalogDb,
    keys: &KeyDirectory,
    collection_state: &CollectionMembershipState,
    selector: &Id,
) -> Result<(Vec<SharedObject>, usize)> {
    let domain = keys.domain(selector)?;
    ensure!(
        domain.collection_id() == collection_state.collection_id()
            && domain.collection_epoch() == collection_state.collection_epoch(),
        AuthenticationFailed,
        "shared object projection uses another collection epoch"
    );
    let payloads = sharing_log::records(db, selector)?
        .into_iter()
        .map(|record| OperationPayload::open_for_collection_operation(&record, keys))
        .collect::<Result<Vec<_>>>()?;
    let (drafts, missing_creations) =
        project_drafts(&payloads, collection_state.source_vault_id())?;
    let mut objects = Vec::new();
    for (object_id, draft) in drafts {
        let Some((container_length, container_commitment, object_key_envelope)) = draft.commit
        else {
            continue;
        };
        if draft.deleted {
            continue;
        }
        let expected = SharedObject {
            source_vault_id: *collection_state.source_vault_id(),
            collection_id: *collection_state.collection_id(),
            object_id,
            object_generation: draft.generation,
            stream_id: draft.stream_id,
            store_id: draft.store_id,
            container_length,
            container_commitment,
            object_key_envelope,
            metadata: draft.metadata,
        };
        if store::object_present(db, &object_id)? {
            let local = store::object(db, &object_id)?;
            let streams = store::streams(db, &object_id)?;
            ensure_present_matches(&local, &streams, &expected)?;
            continue;
        }
        objects.push(expected);
    }
    Ok((objects, missing_creations))
}

fn ensure_present_matches(
    local: &Object,
    streams: &[Stream],
    expected: &SharedObject,
) -> Result<()> {
    ensure!(
        local.state == ObjectState::Active
            && local.collection_id == expected.collection_id
            && local.object_generation == expected.object_generation
            && local.primary_stream_id == expected.stream_id
            && streams.iter().any(|stream| {
                stream.stream_kind == StreamKind::Original
                    && stream.stream_id == expected.stream_id
                    && stream.ciphertext_size == expected.container_length
                    && stream.final_commitment == expected.container_commitment
            }),
        Conflict,
        "existing shared object contradicts its signed commit"
    );
    Ok(())
}

fn project_drafts(
    payloads: &[OperationPayload],
    source_vault_id: &Id,
) -> Result<(BTreeMap<Id, Draft>, usize)> {
    let mut drafts = BTreeMap::<Id, Draft>::new();
    let mut missing_creations = 0;
    for payload in payloads {
        if let PayloadBody::CreateObject {
            object_id,
            object_generation,
            store_id,
            stream_id,
            metadata_fields,
        } = payload.body()
        {
            ensure!(
                drafts
                    .insert(
                        *object_id,
                        Draft {
                            generation: *object_generation,
                            store_id: *store_id,
                            stream_id: *stream_id,
                            metadata: metadata_fields.clone(),
                            commit: None,
                            deleted: false,
                        }
                    )
                    .is_none(),
                AuthenticationFailed,
                "shared object has two creation records"
            );
        }
    }
    for payload in payloads {
        match payload.body() {
            PayloadBody::CreateObject { .. } => {}
            PayloadBody::CommitObject {
                object_id,
                object_generation,
                store_id,
                container_length,
                container_commitment,
                object_key_envelope,
            } => {
                let Some(draft) = drafts.get_mut(object_id) else {
                    missing_creations += 1;
                    continue;
                };
                ensure!(
                    draft.generation == *object_generation
                        && draft.store_id == *store_id
                        && draft.commit.is_none()
                        && object_key_envelope.vault_id() == source_vault_id,
                    AuthenticationFailed,
                    "shared commit contradicts its creation"
                );
                draft.commit = Some((
                    *container_length,
                    *container_commitment,
                    object_key_envelope.clone(),
                ));
            }
            PayloadBody::DeleteObject { object_id, .. } => {
                if let Some(draft) = drafts.get_mut(object_id) {
                    draft.deleted = true;
                }
            }
            _ => {}
        }
    }
    Ok((drafts, missing_creations))
}

#[cfg(test)]
mod tests {
    #![allow(clippy::expect_used)]
    use chur_crypto::{Key, Nonce};
    use chur_format::constants::{IntegritySummary, MediaClass};

    use super::*;

    fn id(byte: u8) -> Id {
        Id::new([byte; 16]).expect("id")
    }

    #[test]
    fn commit_before_create_in_device_order_projects_and_incomplete_page_waits() {
        let collection = id(1);
        let source = id(2);
        let object = id(3);
        let store = id(4);
        let envelope = ObjectKeyEnvelope::seal(
            &Key::new([5; 32]),
            source,
            collection,
            1,
            object,
            1,
            Nonce::new([6; 24]),
            &Key::new([7; 32]),
        )
        .expect("envelope");
        let create = OperationPayload::new(
            collection,
            1,
            PayloadBody::CreateObject {
                object_id: object,
                object_generation: 1,
                store_id: store,
                stream_id: id(8),
                metadata_fields: Vec::new(),
            },
        )
        .expect("create");
        let commit = OperationPayload::new(
            collection,
            1,
            PayloadBody::CommitObject {
                object_id: object,
                object_generation: 1,
                store_id: store,
                container_length: 64,
                container_commitment: [9; 32],
                object_key_envelope: envelope,
            },
        )
        .expect("commit");

        let (incomplete, pending) =
            project_drafts(std::slice::from_ref(&commit), &source).expect("incomplete page");
        assert!(incomplete.is_empty());
        assert_eq!(pending, 1);

        let (reversed, pending) =
            project_drafts(&[commit, create], &source).expect("reversed order");
        assert_eq!(pending, 0);
        let draft = reversed.get(&object).expect("created object");
        assert_eq!(draft.commit.as_ref().map(|commit| commit.0), Some(64));
    }

    #[test]
    fn present_object_must_match_signed_generation_and_original_commit() {
        let collection = id(1);
        let source = id(2);
        let object_id = id(3);
        let stream_id = id(4);
        let expected = SharedObject {
            source_vault_id: source,
            collection_id: collection,
            object_id,
            object_generation: 2,
            stream_id,
            store_id: id(5),
            container_length: 64,
            container_commitment: [9; 32],
            object_key_envelope: ObjectKeyEnvelope::seal(
                &Key::new([6; 32]),
                source,
                collection,
                1,
                object_id,
                1,
                Nonce::new([7; 24]),
                &Key::new([8; 32]),
            )
            .expect("envelope"),
            metadata: Vec::new(),
        };
        let local = Object {
            object_id,
            object_generation: 2,
            collection_id: collection,
            primary_stream_id: stream_id,
            media_kind: MediaClass::Image,
            capture_time_ms: 1,
            import_time_ms: 1,
            capture_time_substituted: false,
            plaintext_size: 32,
            width: 1,
            height: 1,
            duration_ms: 0,
            favorite: false,
            state: ObjectState::Active,
            integrity_summary: IntegritySummary::CompleteVerified,
            thumbnail_ready: false,
            active_metadata_revision: 1,
        };
        let stream = Stream {
            stream_id,
            object_id,
            stream_kind: StreamKind::Original,
            stream_revision: 1,
            source_content_revision: 0,
            container_path_id: object_id,
            container_version: 1,
            suite_id: 1,
            ciphertext_size: 64,
            plaintext_size: 32,
            chunk_size: 262_144,
            complete_verified_ms: Some(1),
            final_commitment: [9; 32],
        };
        assert!(ensure_present_matches(&local, std::slice::from_ref(&stream), &expected).is_ok());

        let mut old_generation = local.clone();
        old_generation.object_generation = 1;
        assert_eq!(
            ensure_present_matches(&old_generation, std::slice::from_ref(&stream), &expected)
                .expect_err("generation mismatch")
                .status(),
            ChurStatus::Conflict,
        );
        let mut other_commit = stream.clone();
        other_commit.final_commitment = [10; 32];
        assert_eq!(
            ensure_present_matches(&local, &[other_commit], &expected)
                .expect_err("commitment mismatch")
                .status(),
            ChurStatus::Conflict,
        );
        let mut inactive = local.clone();
        inactive.state = ObjectState::Tombstoned;
        assert_eq!(
            ensure_present_matches(&inactive, std::slice::from_ref(&stream), &expected)
                .expect_err("inactive object")
                .status(),
            ChurStatus::Conflict,
        );
    }
}
