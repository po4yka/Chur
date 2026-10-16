//! Key-free media publication and receive plans for enrolled devices of one vault.

use std::collections::{BTreeMap, BTreeSet};

use chur_core::{ChurStatus, Error, Id, Result, ensure};
use chur_crypto::Key;
use chur_format::constants::ObjectState;
use chur_format::envelope::ObjectKeyEnvelope;
use chur_sync_protocol::{
    KeyDirectory, KeyDomain,
    materialization::MaterializedState,
    object_grant::object_key_commitment,
    operation::Operation,
    payload::{MetadataField, MetadataFieldId, OperationPayload, PayloadBody},
    state::MembershipState,
};
use rusqlite::params;

use crate::{
    CatalogDb,
    db::map_sqlite,
    model::COLLECTION_POLICY_SHARED,
    sharing_publication::{self, Candidate},
    sharing_receive::{ReceivePlan, SharedObject},
    store, sync_keys, sync_log, sync_membership, sync_receive,
};

/// Signed own-vault operations for one verified source container.
pub struct Publication {
    /// Committed source identity.
    pub candidate: Candidate,
    /// Durable creation operation.
    pub create_operation: Operation,
    /// Durable key-free commit operation.
    pub commit_operation: Operation,
}

/// Local collections that belong to this vault rather than an external source.
pub fn collection_ids(db: &CatalogDb) -> Result<Vec<Id>> {
    let mut statement = db
        .connection()
        .prepare(
            "SELECT collection_id FROM collections WHERE policy_type != ?1 ORDER BY collection_id",
        )
        .map_err(|error| map_sqlite(error, "own collections could not be listed"))?;
    let rows = statement
        .query_map([i64::from(COLLECTION_POLICY_SHARED)], |row| {
            row.get::<_, Vec<u8>>(0)
        })
        .map_err(|error| map_sqlite(error, "own collections could not be read"))?;
    rows.map(|row| {
        crate::row::id(
            &row.map_err(|error| map_sqlite(error, "own collection ID could not be read"))?,
            "own collection ID is malformed",
        )
    })
    .collect()
}

/// Whether an existing collection is locally owned.
pub fn is_local_collection(db: &CatalogDb, collection_id: &Id) -> Result<bool> {
    Ok(store::collection(db, collection_id)?.policy_type != COLLECTION_POLICY_SHARED)
}

/// Verified source history retained only for this unlocked catalog connection.
pub(crate) struct SourceCache {
    generation: u64,
    vault_id: Id,
    keys: KeyDirectory,
    log: sync_log::DurableOperationLog,
    state: MaterializedState,
    declarations: BTreeSet<(Id, u64)>,
    creates: BTreeMap<(Id, Id), Operation>,
    commits: BTreeMap<(Id, Id), Operation>,
}

fn take_source_cache(
    db: &mut CatalogDb,
    root: &Key,
    vault_id: Id,
    membership: &MembershipState,
) -> Result<SourceCache> {
    let generation = crate::schema::generation(db)?;
    if let Some(cache) = db.own_source_cache.take()
        && cache.generation == generation
        && cache.vault_id == vault_id
    {
        // The selector alone is insufficient: compare the complete derived key.
        let domain = KeyDomain::root(root, &vault_id)?;
        cache.keys.domain(domain.selector())?;
        cache.keys.check_insert(&domain)?;
        return Ok(cache);
    }
    #[cfg(test)]
    {
        db.own_source_replays += 1;
    }
    let keys = sync_keys::key_directory(db, root, vault_id)?;
    let log = sync_log::load(db, membership)?;
    let state = sync_receive::load_materialized_state(db, &keys)?;
    let mut cache = SourceCache {
        generation,
        vault_id,
        keys,
        log,
        state,
        declarations: BTreeSet::new(),
        creates: BTreeMap::new(),
        commits: BTreeMap::new(),
    };
    for issuer in sync_log::device_ids(db)? {
        let mut after = 0;
        loop {
            let page = sync_log::records_after(db, &issuer, after)?;
            if page.is_empty() {
                break;
            }
            for record in page {
                let operation = Operation::decode(&record)?;
                after = operation.device_sequence();
                let payload = OperationPayload::open_for_operation(&operation, &cache.keys)?;
                match payload.body() {
                    PayloadBody::DeclareCollection {
                        collection_key_envelope,
                    } => {
                        cache.declarations.insert((
                            *collection_key_envelope.collection_id(),
                            collection_key_envelope.collection_epoch(),
                        ));
                    }
                    PayloadBody::CreateObject { object_id, .. } => {
                        cache
                            .creates
                            .insert((*payload.collection_id(), *object_id), operation);
                    }
                    PayloadBody::CommitObject { object_id, .. } => {
                        cache
                            .commits
                            .insert((*payload.collection_id(), *object_id), operation);
                    }
                    _ => {}
                }
            }
        }
    }
    Ok(cache)
}

// This cache is discarded on every failed call. Apply in place rather than
// clone the whole gallery for each new object, then persist the accepted log.
fn author_cached_content(
    db: &mut CatalogDb,
    cache: &mut SourceCache,
    membership: &MembershipState,
    domain: &KeyDomain,
    device: Id,
    signing_key: &chur_sync_protocol::operation::DeviceSigningKey,
    payload: &OperationPayload,
) -> Result<Operation> {
    ensure!(
        matches!(
            payload.body(),
            PayloadBody::CreateObject { .. } | PayloadBody::CommitObject { .. }
        ),
        InvalidInput,
        "source cache authors only immutable object facts"
    );
    let operation = cache.log.author(
        chur_crypto::random::id()?,
        *membership.vault_id(),
        device,
        *domain.selector(),
        domain.operation_key(),
        chur_crypto::Nonce::random()?,
        &payload.encode(),
        signing_key,
        membership,
    )?;
    payload.validate_for_operation(
        &operation,
        domain.collection_id(),
        domain.collection_epoch(),
    )?;
    ensure!(
        cache.state.apply(&operation, payload)?
            == chur_sync_protocol::convergence::MergeOutcome::Applied,
        InvalidInput,
        "source content does not change accepted state"
    );
    ensure!(
        cache
            .log
            .accept_with(db, &operation, membership, |_| Ok(()))?
            == chur_sync_protocol::operation_log::ApplyOutcome::Applied,
        InternalFailure,
        "source content was not applied"
    );
    Ok(operation)
}

/// Authors immutable media facts in the ordinary device operation chain.
pub fn publish(
    db: &mut CatalogDb,
    root: &Key,
    vault_id: Id,
    collection_id: Id,
    candidates: &[Candidate],
) -> Result<Vec<Publication>> {
    ensure!(
        candidates.len() == 1 && is_local_collection(db, &collection_id)?,
        InvalidInput,
        "own publication requires one local source object"
    );
    // These checks run on every call, including a cache hit after revocation.
    let mut membership = sync_membership::load(db)?
        .ok_or_else(|| Error::new(ChurStatus::RecoveryRequired, "own membership is absent"))?;
    ensure!(
        membership.vault_id() == &vault_id,
        AuthenticationFailed,
        "own membership names another vault"
    );
    let (device, identity) = sync_keys::local_identity(db, root, &membership)?
        .ok_or_else(|| Error::new(ChurStatus::RecoveryRequired, "own identity is absent"))?;
    let mut cache = take_source_cache(db, root, vault_id, &membership)?;
    let result = (|| {
        let collection = store::collection(db, &collection_id)?;
        if !cache
            .declarations
            .contains(&(collection_id, collection.current_epoch))
        {
            let envelope = chur_format::envelope::CollectionKeyEnvelope::decode(
                &store::active_collection_envelope(db, &collection_id, collection.current_epoch)?,
            )?;
            let payload = OperationPayload::new(
                vault_id,
                0,
                PayloadBody::DeclareCollection {
                    collection_key_envelope: envelope,
                },
            )?;
            let domain = KeyDomain::root(root, &vault_id)?;
            let operation = cache.log.author(
                chur_crypto::random::id()?,
                vault_id,
                device,
                *domain.selector(),
                domain.operation_key(),
                chur_crypto::Nonce::random()?,
                &payload.encode(),
                identity.signing_key(),
                &membership,
            )?;
            ensure!(
                sync_receive::accept_operation(
                    db,
                    &mut cache.log,
                    &mut membership,
                    &mut cache.state,
                    &mut cache.keys,
                    root,
                    0,
                    &operation.encode()
                )? == chur_sync_protocol::operation_log::ApplyOutcome::Applied,
                InternalFailure,
                "local collection declaration was not applied"
            );
            cache
                .declarations
                .insert((collection_id, collection.current_epoch));
        }
        let item = sharing_publication::candidate(db, &collection_id, &candidates[0].object_id)?;
        ensure!(
            item.stream_id == candidates[0].stream_id
                && item.store_id == candidates[0].store_id
                && item.container_length == candidates[0].container_length
                && item.container_commitment == candidates[0].container_commitment,
            Conflict,
            "own source object changed before publication"
        );
        let collection_key =
            sync_keys::collection_key(db, root, vault_id, collection_id, collection.current_epoch)?;
        let domain =
            KeyDomain::collection(&collection_key, &collection_id, collection.current_epoch)?;
        let index = (collection_id, item.object_id);
        let create_operation = match cache.creates.get(&index) {
            Some(operation) => operation.clone(),
            None => {
                let operation = author_cached_content(
                    db,
                    &mut cache,
                    &membership,
                    &domain,
                    device,
                    identity.signing_key(),
                    &OperationPayload::new(
                        collection_id,
                        collection.current_epoch,
                        PayloadBody::CreateObject {
                            object_id: item.object_id,
                            object_generation: item.object_generation,
                            store_id: item.store_id,
                            stream_id: item.stream_id,
                            metadata_fields: sharing_publication::metadata_fields(
                                db,
                                &item.object_id,
                            )?,
                        },
                    )?,
                )?;
                cache.creates.insert(index, operation.clone());
                operation
            }
        };
        let commit_operation = match cache.commits.get(&index) {
            Some(operation) => operation.clone(),
            None => {
                let envelope =
                    ObjectKeyEnvelope::decode(&store::active_envelope(db, &item.object_id)?)?;
                let key = sync_keys::collection_key(
                    db,
                    root,
                    vault_id,
                    collection_id,
                    envelope.collection_epoch(),
                )?;
                let content = envelope.open(&key)?;
                let operation = author_cached_content(
                    db,
                    &mut cache,
                    &membership,
                    &domain,
                    device,
                    identity.signing_key(),
                    &OperationPayload::new(
                        collection_id,
                        collection.current_epoch,
                        PayloadBody::CommitObject {
                            object_id: item.object_id,
                            object_generation: item.object_generation,
                            store_id: item.store_id,
                            container_length: item.container_length,
                            container_commitment: item.container_commitment,
                            object_key_commitment: object_key_commitment(
                                &collection_id,
                                &item.object_id,
                                &content,
                            ),
                        },
                    )?,
                )?;
                cache.commits.insert(index, operation.clone());
                operation
            }
        };
        Ok(vec![Publication {
            candidate: item,
            create_operation,
            commit_operation,
        }])
    })();
    if result.is_ok() {
        cache.generation = crate::schema::generation(db)?;
        db.own_source_cache = Some(cache);
    }
    result
}

/// Authors an own-vault tombstone when this object has a durable content cause.
pub fn publish_deletion(
    db: &mut CatalogDb,
    root: &Key,
    vault_id: Id,
    object_id: &Id,
    now_ms: u64,
) -> Result<()> {
    let keys = sync_keys::key_directory(db, root, vault_id)?;
    let mut state = sync_receive::load_materialized_state(db, &keys)?;
    let Some((_, collection, generation, _visible, _)) = state
        .committed_objects()
        .find(|(id, _, _, _, _)| id == object_id)
    else {
        return Ok(());
    };
    if state.object_is_purged(object_id) {
        return Ok(());
    }
    let membership = sync_membership::load(db)?.ok_or_else(|| {
        Error::new(
            ChurStatus::RecoveryRequired,
            "own deletion membership is absent",
        )
    })?;
    let (device, identity) =
        sync_keys::local_identity(db, root, &membership)?.ok_or_else(|| {
            Error::new(
                ChurStatus::RecoveryRequired,
                "own deletion identity is absent",
            )
        })?;
    let current = store::collection(db, &collection)?;
    let key = sync_keys::collection_key(db, root, vault_id, collection, current.current_epoch)?;
    let domain = KeyDomain::collection(&key, &collection, current.current_epoch)?;
    let mut log = sync_log::load(db, &membership)?;
    sync_receive::author_content_operation(
        db,
        &mut log,
        &membership,
        &mut state,
        &domain,
        device,
        identity.signing_key(),
        &OperationPayload::new(
            collection,
            current.current_epoch,
            PayloadBody::PurgeObject {
                object_id: *object_id,
                object_generation: generation,
                authored_at_ms: now_ms,
            },
        )?,
    )?;
    Ok(())
}

/// Resolves missing local containers from accepted own-vault operations.
pub fn pending_for_collection(
    db: &mut CatalogDb,
    root: &Key,
    vault_id: Id,
    collection_id: Id,
) -> Result<ReceivePlan> {
    pending_for_collection_in(db, root, vault_id, collection_id, true)
}
fn pending_for_collection_in(
    db: &mut CatalogDb,
    root: &Key,
    vault_id: Id,
    collection_id: Id,
    dispatch: bool,
) -> Result<ReceivePlan> {
    ensure!(
        is_local_collection(db, &collection_id)?,
        AuthenticationFailed,
        "collection is not locally owned"
    );
    let keys = sync_keys::key_directory(db, root, vault_id)?;
    let state = sync_receive::load_materialized_state(db, &keys)?;
    let collection = store::collection(db, &collection_id)?;
    let key =
        sync_keys::collection_key(db, root, vault_id, collection_id, collection.current_epoch)?;
    let domain = KeyDomain::collection(&key, &collection_id, collection.current_epoch)?;
    let mut objects = Vec::new();
    for (object_id, scope, generation, visible, commit) in state.committed_objects() {
        if scope != collection_id {
            continue;
        }
        match crate::object_grants::ensure_not_deleted(db, &object_id) {
            Ok(()) => {}
            Err(error) if error.status() == ChurStatus::NotFound => continue,
            Err(error) => return Err(error),
        }
        if store::object_present(db, &object_id)? {
            let local = store::object(db, &object_id)?;
            ensure!(
                local.collection_id == collection_id
                    && local.primary_stream_id == *commit.stream_id(),
                AuthenticationFailed,
                "own object differs from its accepted commit"
            );
            if (!visible && local.state == ObjectState::Active)
                || (visible && local.state == ObjectState::SharedDeleted)
            {
                db.transaction(|tx| {
                    tx.execute("UPDATE objects SET state = ?2, object_generation = ?3 WHERE object_id = ?1",
                        params![object_id.as_bytes().as_slice(), i64::from(if visible { ObjectState::Active } else { ObjectState::SharedDeleted }.value()),
                            crate::db::as_sqlite_integer(generation, "own object generation is too large")?])
                        .map_err(|error| map_sqlite(error, "own object lifecycle could not be projected"))?;
                    if visible { store::reindex_search(tx, &object_id)?; }
                    else { tx.execute("DELETE FROM object_search WHERE rowid = ?1", [store::row_key(&object_id)])
                        .map_err(|error| map_sqlite(error, "own hidden object search could not be removed"))?; }
                    crate::schema::bump_generation(tx)
                })?;
            }
            continue;
        }
        if !visible {
            continue;
        }
        let metadata = [
            MetadataFieldId::OriginalFilename,
            MetadataFieldId::MediaType,
            MetadataFieldId::CaptureTime,
            MetadataFieldId::Caption,
            MetadataFieldId::Rating,
        ]
        .into_iter()
        .filter_map(|field| {
            state
                .metadata(&object_id, field)
                .map(|value| MetadataField::new(field, value.to_vec()))
        })
        .collect::<Result<Vec<_>>>()?;
        objects.push(SharedObject {
            source_vault_id: vault_id,
            collection_id,
            object_id,
            object_generation: generation,
            stream_id: *commit.stream_id(),
            store_id: *commit.store_id(),
            container_length: commit.container_length(),
            container_commitment: *commit.container_commitment(),
            object_key_commitment: *commit.object_key_commitment(),
            metadata,
        });
    }
    if dispatch {
        let tickets = crate::receive_queue::tickets(db, &collection_id)?;
        objects.sort_unstable_by_key(|object| {
            (
                tickets.get(&object.object_id).copied().unwrap_or(0),
                object.object_id,
            )
        });
        objects.truncate(crate::receive_queue::PAGE_MAX);
        crate::receive_queue::dispatched(
            db,
            &collection_id,
            &objects
                .iter()
                .map(|object| object.object_id)
                .collect::<Vec<_>>(),
        )?;
    }
    Ok(ReceivePlan {
        selector: *domain.selector(),
        source_vault_id: vault_id,
        collection_id,
        objects,
        pending_operations: 0,
    })
}

/// Resolves one missing own-vault object without trusting transport metadata.
pub fn pending_object_for_collection(
    db: &mut CatalogDb,
    root: &Key,
    vault_id: Id,
    collection_id: Id,
    object_id: Id,
) -> Result<SharedObject> {
    crate::object_grants::ensure_not_deleted(db, &object_id)?;
    pending_for_collection_in(db, root, vault_id, collection_id, false)?
        .objects
        .into_iter()
        .find(|object| object.object_id == object_id)
        .ok_or_else(|| {
            Error::new(
                ChurStatus::NotFound,
                "own object is absent, deleted, or already active",
            )
        })
}

#[cfg(test)]
mod tests {
    #![allow(clippy::expect_used, clippy::unwrap_used)]
    use super::*;
    use crate::{
        db::{CatalogKey, CatalogLocation},
        model::{Collection, MetadataRevision, Object, Stream},
        schema,
    };
    use chur_crypto::Nonce;
    use chur_format::constants::{IntegritySummary, MediaClass, StreamKind};
    use chur_format::envelope::CollectionKeyEnvelope;
    use chur_sync_protocol::{
        identity::{DeviceIdentity, DeviceIdentityEnvelope},
        membership::EnrollmentRecord,
    };

    fn id(byte: u8) -> Id {
        Id::new([byte; 16]).unwrap()
    }

    #[test]
    fn enrolled_peer_receives_key_free_media_and_disposable_key_without_sharing_acl() {
        let directory = std::env::temp_dir().join(format!(
            "chur-own-sync-{}",
            chur_crypto::random::id().unwrap().to_hex()
        ));
        std::fs::create_dir_all(&directory).unwrap();
        let source_path = directory.join("source.db");
        let peer_path = directory.join("peer.db");
        let root = Key::new([1; 32]);
        let vault = id(2);
        let collection = id(3);
        let source_id = id(4);
        let peer_id = id(5);
        let source_identity = DeviceIdentity::from_seeds([6; 32], [7; 32]);
        let peer_identity = DeviceIdentity::from_seeds([8; 32], [9; 32]);
        let catalog_key = CatalogKey::derive(&root, &vault).unwrap();
        let mut source =
            CatalogDb::open(&CatalogLocation::File(&source_path), &catalog_key).unwrap();
        schema::open_at_current_version(&mut source, 1).unwrap();
        let initial = EnrollmentRecord::initial(
            vault,
            source_id,
            source_identity.signing_public_key(),
            source_identity.hpke_public_key(),
        )
        .unwrap()
        .sign(source_identity.signing_key());
        let (mut membership, mut log, _) = sync_receive::provision_initial_membership(
            &mut source,
            &root,
            source_identity.signing_key(),
            &initial,
        )
        .unwrap();
        let source_envelope = DeviceIdentityEnvelope::seal_for_local(
            &root,
            vault,
            source_id,
            1,
            Nonce::new([10; 24]),
            &source_identity,
        )
        .unwrap();
        sync_keys::store_local_identity_envelope(&mut source, &root, &membership, &source_envelope)
            .unwrap();
        let checkpoint = log
            .issue_own_checkpoint(
                &mut source,
                &membership,
                &source_id,
                source_identity.signing_key(),
                2,
            )
            .unwrap();
        let enrollment = EnrollmentRecord::new(
            vault,
            peer_id,
            peer_identity.signing_public_key(),
            peer_identity.hpke_public_key(),
            2,
            source_id,
            2,
            *membership.commitment(),
            checkpoint.commitment(),
        )
        .unwrap()
        .sign(source_identity.signing_key());
        let domain = KeyDomain::root(&root, &vault).unwrap();
        sync_receive::author_membership_operation(
            &mut source,
            &mut log,
            &mut membership,
            &domain,
            source_id,
            source_identity.signing_key(),
            PayloadBody::AddDevice(enrollment),
        )
        .unwrap();
        let collection_key = Key::new([11; 32]);
        let collection_envelope = CollectionKeyEnvelope::seal(
            &root,
            vault,
            collection,
            1,
            1,
            Nonce::new([12; 24]),
            &collection_key,
        )
        .unwrap();
        store::put_collection_with_envelope(
            &mut source,
            &Collection {
                collection_id: collection,
                current_epoch: 1,
                policy_type: crate::model::COLLECTION_POLICY_VAULT_DEFAULT,
                created_revision: 1,
                status: 1,
            },
            1,
            &collection_envelope.encode(),
        )
        .unwrap();
        source.checkpoint().unwrap();
        std::fs::copy(&source_path, &peer_path).unwrap();
        let mut peer = CatalogDb::open(&CatalogLocation::File(&peer_path), &catalog_key).unwrap();
        let peer_envelope = DeviceIdentityEnvelope::seal_for_local(
            &root,
            vault,
            peer_id,
            1,
            Nonce::new([13; 24]),
            &peer_identity,
        )
        .unwrap();
        sync_keys::store_local_identity_envelope(&mut peer, &root, &membership, &peer_envelope)
            .unwrap();
        let object = id(14);
        let stream = id(15);
        let content = Key::new([16; 32]);
        let envelope = ObjectKeyEnvelope::seal(
            &collection_key,
            vault,
            collection,
            1,
            object,
            1,
            Nonce::new([17; 24]),
            &content,
        )
        .unwrap();
        store::activate_object(
            &mut source,
            &store::ObjectActivation {
                object: Object {
                    object_id: object,
                    object_generation: 1,
                    collection_id: collection,
                    primary_stream_id: stream,
                    media_kind: MediaClass::Image,
                    capture_time_ms: 1,
                    import_time_ms: 1,
                    capture_time_substituted: false,
                    plaintext_size: 4096,
                    width: 10,
                    height: 10,
                    duration_ms: 0,
                    favorite: false,
                    state: ObjectState::Active,
                    integrity_summary: IntegritySummary::CompleteVerified,
                    thumbnail_ready: false,
                    active_metadata_revision: 1,
                },
                stream: Stream {
                    stream_id: stream,
                    object_id: object,
                    stream_kind: StreamKind::Original,
                    stream_revision: 1,
                    source_content_revision: 0,
                    container_path_id: id(18),
                    container_version: 1,
                    suite_id: 1,
                    ciphertext_size: 4200,
                    plaintext_size: 4096,
                    chunk_size: 262144,
                    complete_verified_ms: Some(1),
                    final_commitment: [19; 32],
                },
                envelope: envelope.encode(),
                envelope_generation: 1,
                metadata: MetadataRevision {
                    object_id: object,
                    revision: 1,
                    active: true,
                    record: vec![1],
                    original_filename: Some("own.jpg".into()),
                    caption: None,
                    content_type: "image/jpeg".into(),
                    capture_time_ms: Some(1),
                    width: 10,
                    height: 10,
                    duration_ms: 0,
                },
            },
        )
        .unwrap();
        let candidate = sharing_publication::candidate(&source, &collection, &object).unwrap();
        let published = publish(&mut source, &root, vault, collection, &[candidate])
            .unwrap()
            .remove(0);
        let replay = publish(&mut source, &root, vault, collection, &[candidate])
            .unwrap()
            .remove(0);
        assert_eq!(
            published.commit_operation.encode(),
            replay.commit_operation.encode()
        );
        assert_eq!(source.own_source_replays, 1);
        for _ in 0..16 {
            let repeated = publish(&mut source, &root, vault, collection, &[candidate])
                .unwrap()
                .remove(0);
            assert_eq!(
                repeated.commit_operation.encode(),
                published.commit_operation.encode()
            );
        }
        assert_eq!(
            source.own_source_replays, 1,
            "unchanged source scans reuse one authenticated replay"
        );
        assert!(
            publish(
                &mut source,
                &Key::new([250; 32]),
                vault,
                collection,
                &[candidate]
            )
            .is_err(),
            "a cached history does not accept another root"
        );
        assert!(
            publish(&mut source, &root, id(249), collection, &[candidate]).is_err(),
            "a cached history does not accept another vault"
        );
        source.transaction(crate::schema::bump_generation).unwrap();
        publish(&mut source, &root, vault, collection, &[candidate]).unwrap();
        assert_eq!(
            source.own_source_replays, 2,
            "a changed catalog invalidates the verified replay"
        );
        let mut keys = sync_keys::key_directory(&peer, &root, vault).unwrap();
        let mut peer_log = sync_log::load(&peer, &membership).unwrap();
        let mut state = sync_receive::load_materialized_state(&peer, &keys).unwrap();
        for record in sync_log::records_after(&source, &source_id, 2).unwrap() {
            assert_eq!(
                sync_receive::accept_operation(
                    &mut peer,
                    &mut peer_log,
                    &mut membership,
                    &mut state,
                    &mut keys,
                    &root,
                    3,
                    &record
                )
                .unwrap(),
                chur_sync_protocol::operation_log::ApplyOutcome::Applied
            );
        }
        let plan = pending_for_collection(&mut peer, &root, vault, collection).unwrap();
        assert_eq!(plan.objects.len(), 1);
        assert_eq!(
            plan.objects[0].object_key_commitment,
            object_key_commitment(&collection, &object, &content)
        );
        let request =
            crate::object_grants::request(&mut peer, &root, vault, collection, object).unwrap();
        assert_eq!(request.recipient_device_id(), &peer_id);
        // Recovery can retain this unaccepted private request. An approved
        // fresh ordinary identity must issue its own addressed request.
        let fresh_id = id(23);
        let fresh = DeviceIdentity::from_seeds([24; 32], [25; 32]);
        let mut source_membership = membership.clone();
        let mut source_log = sync_log::load(&source, &source_membership).unwrap();
        let checkpoint = source_log
            .issue_own_checkpoint(
                &mut source,
                &source_membership,
                &source_id,
                source_identity.signing_key(),
                4,
            )
            .unwrap();
        let sequence = source_log.head(&source_id).unwrap().0 + 1;
        let enrolled = EnrollmentRecord::new(
            vault,
            fresh_id,
            fresh.signing_public_key(),
            fresh.hpke_public_key(),
            sequence,
            source_id,
            source_membership.generation() + 1,
            *source_membership.commitment(),
            checkpoint.commitment(),
        )
        .unwrap()
        .sign(source_identity.signing_key());
        let enrolled_op = sync_receive::author_membership_operation(
            &mut source,
            &mut source_log,
            &mut source_membership,
            &KeyDomain::root(&root, &vault).unwrap(),
            source_id,
            source_identity.signing_key(),
            PayloadBody::AddDevice(enrolled),
        )
        .unwrap();
        sync_receive::accept_operation(
            &mut peer,
            &mut peer_log,
            &mut membership,
            &mut state,
            &mut keys,
            &root,
            5,
            &enrolled_op.encode(),
        )
        .unwrap();
        let fresh_envelope = DeviceIdentityEnvelope::seal_for_local(
            &root,
            vault,
            fresh_id,
            1,
            Nonce::new([26; 24]),
            &fresh,
        )
        .unwrap();
        sync_keys::store_local_identity_envelope(&mut peer, &root, &membership, &fresh_envelope)
            .unwrap();
        let fresh_request =
            crate::object_grants::request(&mut peer, &root, vault, collection, object).unwrap();
        assert_eq!(fresh_request.recipient_device_id(), &fresh_id);
        assert_ne!(fresh_request.prekey_id(), request.prekey_id());
        let request = fresh_request;
        let grant = crate::object_grants::issue(&mut source, &root, vault, &request).unwrap();
        crate::object_grants::accept(&mut peer, &root, vault, &[], &grant).unwrap();
        assert_eq!(
            crate::object_grants::pending_envelope(&peer, &object)
                .unwrap()
                .open(&collection_key)
                .unwrap()
                .expose(),
            content.expose()
        );
        let private_count: i64 = peer
            .connection()
            .query_row("SELECT count(*) FROM object_key_requests", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(
            private_count, 0,
            "one-use private key is gone after acceptance"
        );
        peer.close().unwrap();
        let mut peer = CatalogDb::open(&CatalogLocation::File(&peer_path), &catalog_key).unwrap();
        crate::object_grants::accept(&mut peer, &root, vault, &[], &grant).unwrap();
        assert_eq!(
            pending_for_collection(&mut peer, &root, vault, collection)
                .unwrap()
                .objects
                .len(),
            1
        );
        source.close().unwrap();
        peer.close().unwrap();
        std::fs::remove_dir_all(directory).unwrap();
    }
}
