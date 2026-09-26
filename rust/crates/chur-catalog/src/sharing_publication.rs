//! Durable source-side publication of committed objects to a shared collection.

use std::collections::BTreeMap;

use chur_core::{ChurStatus, Error, Id, Result, ensure};
use chur_crypto::{Key, Nonce, random};
use chur_format::constants::{ObjectState, StreamKind};
use chur_format::envelope::ObjectKeyEnvelope;
use chur_sync_protocol::{
    KeyDomain,
    collection_operation::CollectionOperation,
    payload::{MetadataField, MetadataFieldId, OperationPayload, PayloadBody},
};
use rusqlite::params;

use crate::db::map_sqlite;
use crate::{CatalogDb, sharing, sharing_log, store, sync_keys, sync_membership, sync_rotation};

/// Maximum source objects returned to a host in one call.
pub const PAGE_MAX: usize = 64;

/// One active original stream in the requested source collection.
#[derive(Clone, Copy)]
pub struct Candidate {
    /// Stable object identifier.
    pub object_id: Id,
    /// Generation of the committed source object.
    pub object_generation: u64,
    /// Primary original stream identifier.
    pub stream_id: Id,
    /// Revision of the primary original stream.
    pub stream_revision: u32,
    /// Per-object remote transfer identifier; the source's local container path ID.
    pub store_id: Id,
    /// Committed container byte count.
    pub container_length: u64,
    /// Authenticated ordered chunk commitment.
    pub container_commitment: [u8; 32],
}

/// Stable signed records and ciphertext identity for one published object.
pub struct Publication {
    /// Source container identity.
    pub candidate: Candidate,
    /// Durable signed creation operation.
    pub create_operation: CollectionOperation,
    /// Durable signed commit operation.
    pub commit_operation: CollectionOperation,
}

/// Lists active originals in ascending object ID order. A zero cursor is the start.
pub fn candidates(
    db: &CatalogDb,
    collection_id: &Id,
    after_object_id: &[u8; 16],
) -> Result<Vec<Candidate>> {
    let mut statement = db
        .connection()
        .prepare(
            "SELECT object_id FROM objects
          WHERE collection_id = ?1 AND state = 1 AND object_id > ?2
          ORDER BY object_id LIMIT 64",
        )
        .map_err(|error| map_sqlite(error, "shared objects could not be listed"))?;
    let ids = statement
        .query_map(
            params![
                collection_id.as_bytes().as_slice(),
                after_object_id.as_slice()
            ],
            |row| row.get::<_, Vec<u8>>(0),
        )
        .map_err(|error| map_sqlite(error, "shared objects could not be listed"))?
        .map(|row| {
            let bytes =
                row.map_err(|error| map_sqlite(error, "shared object ID could not be read"))?;
            Id::from_slice(&bytes).map_err(|_| {
                Error::new(ChurStatus::CatalogCorrupt, "shared object ID is malformed")
            })
        })
        .collect::<Result<Vec<_>>>()?;
    ids.into_iter()
        .map(|id| candidate(db, collection_id, &id))
        .collect()
}

/// Resolves an active original stream without accepting an arbitrary file path.
pub fn candidate(db: &CatalogDb, collection_id: &Id, object_id: &Id) -> Result<Candidate> {
    let object = store::object(db, object_id)?;
    ensure!(
        object.state == ObjectState::Active && object.collection_id == *collection_id,
        NotFound,
        "object is not active in this collection"
    );
    let stream = store::streams(db, object_id)?
        .into_iter()
        .find(|stream| {
            stream.stream_kind == StreamKind::Original
                && stream.stream_id == object.primary_stream_id
        })
        .ok_or_else(|| {
            Error::new(
                ChurStatus::CatalogCorrupt,
                "active object has no original stream",
            )
        })?;
    ensure!(
        stream.stream_revision == 1,
        UnsupportedVersion,
        "shared original stream revision is not supported"
    );
    ensure!(
        stream.ciphertext_size != 0 && stream.final_commitment != [0; 32],
        CatalogCorrupt,
        "original stream is not committed"
    );
    Ok(Candidate {
        object_id: *object_id,
        object_generation: object.object_generation,
        stream_id: stream.stream_id,
        stream_revision: stream.stream_revision,
        store_id: stream.container_path_id,
        container_length: stream.ciphertext_size,
        container_commitment: stream.final_commitment,
    })
}

/// Refuses source transfer while the collection's forward-only rotation is open.
pub fn ensure_ready(
    db: &CatalogDb,
    root: &Key,
    source_vault_id: Id,
    collection_id: Id,
) -> Result<()> {
    let membership = sync_membership::load(db)?
        .ok_or_else(|| Error::new(ChurStatus::RecoveryRequired, "source membership is absent"))?;
    ensure!(
        membership.vault_id() == &source_vault_id,
        CatalogCorrupt,
        "source membership belongs to another vault"
    );
    let sharing_state = sharing::load(db, &collection_id)?.ok_or_else(|| {
        Error::new(
            ChurStatus::NotFound,
            "collection sharing is not provisioned",
        )
    })?;
    ensure!(
        sharing_state.source_vault_id() == &source_vault_id,
        AuthenticationFailed,
        "collection belongs to another vault"
    );
    ensure!(
        store::collection(db, &collection_id)?.current_epoch == sharing_state.collection_epoch()
            && sync_rotation::load(db, source_vault_id, collection_id, &membership, root)?
                .is_complete(),
        Conflict,
        "collection rotation is not complete"
    );
    Ok(())
}

/// Authors one CreateObject/CommitObject pair and reuses durable bytes on retry.
pub fn publish_page(
    db: &mut CatalogDb,
    root: &Key,
    source_vault_id: Id,
    collection_id: Id,
    candidates: &[Candidate],
) -> Result<Vec<Publication>> {
    ensure!(
        candidates.len() == 1,
        InvalidInput,
        "authoring requires exactly one source object"
    );
    ensure_ready(db, root, source_vault_id, collection_id)?;
    let membership = sync_membership::load(db)?
        .ok_or_else(|| Error::new(ChurStatus::RecoveryRequired, "source membership is absent"))?;
    ensure!(
        membership.vault_id() == &source_vault_id,
        CatalogCorrupt,
        "source membership belongs to another vault"
    );
    let (device_id, identity) =
        sync_keys::local_identity(db, root, &membership)?.ok_or_else(|| {
            Error::new(
                ChurStatus::RecoveryRequired,
                "local signing identity is absent",
            )
        })?;
    let sharing_state = sharing::load(db, &collection_id)?.ok_or_else(|| {
        Error::new(
            ChurStatus::NotFound,
            "collection sharing is not provisioned",
        )
    })?;
    ensure!(
        sharing_state.source_vault_id() == &source_vault_id,
        AuthenticationFailed,
        "collection belongs to another vault"
    );
    let epoch = sharing_state.collection_epoch();
    let collection_key =
        sync_keys::collection_key(db, root, source_vault_id, collection_id, epoch)?;
    let domain = KeyDomain::collection(&collection_key, &collection_id, epoch)?;
    let keys = sync_keys::key_directory(db, root, source_vault_id)?;
    let mut memberships = BTreeMap::new();
    memberships.insert(source_vault_id, membership.clone());
    let mut log = sharing_log::load(
        db,
        collection_id,
        epoch,
        *domain.selector(),
        &keys,
        &memberships,
        &sharing_state,
    )?;
    // ponytail: this scans the epoch log on each author call; add an indexed durable
    // object projection if measured large-vault latency or memory requires it.
    let mut existing =
        BTreeMap::<Id, (Option<CollectionOperation>, Option<CollectionOperation>)>::new();
    for operation in sharing_log::records(db, domain.selector())? {
        let payload = OperationPayload::open_for_collection_operation(&operation, &keys)?;
        match payload.body() {
            PayloadBody::CreateObject { object_id, .. } => {
                let prior = &mut existing.entry(*object_id).or_default().0;
                ensure!(
                    prior.is_none(),
                    CatalogCorrupt,
                    "object has two create operations"
                );
                *prior = Some(operation);
            }
            PayloadBody::CommitObject { object_id, .. } => {
                let prior = &mut existing.entry(*object_id).or_default().1;
                ensure!(
                    prior.is_none(),
                    CatalogCorrupt,
                    "object has two commit operations"
                );
                *prior = Some(operation);
            }
            _ => {}
        }
    }
    let mut published = Vec::with_capacity(candidates.len());
    for item in candidates {
        ensure!(
            same_candidate(item, &candidate(db, &collection_id, &item.object_id)?),
            Conflict,
            "object changed during publication"
        );
        let (stored_create, stored_commit) = existing.remove(&item.object_id).unwrap_or_default();
        let create_operation = match stored_create {
            Some(operation) => {
                let payload = OperationPayload::open_for_collection_operation(&operation, &keys)?;
                ensure!(
                    matches!(payload.body(), PayloadBody::CreateObject {
                    object_id, object_generation, store_id, stream_id, ..
                } if *object_id == item.object_id && *object_generation == item.object_generation
                    && *store_id == item.store_id && *stream_id == item.stream_id),
                    Conflict,
                    "stored creation does not match current object"
                );
                operation
            }
            None => {
                let payload = OperationPayload::new(
                    collection_id,
                    epoch,
                    PayloadBody::CreateObject {
                        object_id: item.object_id,
                        object_generation: item.object_generation,
                        store_id: item.store_id,
                        stream_id: item.stream_id,
                        metadata_fields: metadata_fields(db, &item.object_id)?,
                    },
                )?;
                log.author(
                    db,
                    random::id()?,
                    source_vault_id,
                    device_id,
                    domain.operation_key(),
                    Nonce::random()?,
                    &payload,
                    identity.signing_key(),
                    &membership,
                    &membership,
                    &sharing_state,
                )?
            }
        };
        let commit_operation = match stored_commit {
            Some(operation) => {
                let payload = OperationPayload::open_for_collection_operation(&operation, &keys)?;
                ensure!(
                    matches!(payload.body(), PayloadBody::CommitObject {
                    object_id, object_generation, store_id, container_length, container_commitment, ..
                } if *object_id == item.object_id && *object_generation == item.object_generation
                    && *store_id == item.store_id && *container_length == item.container_length
                    && *container_commitment == item.container_commitment),
                    Conflict,
                    "stored commit does not match current object"
                );
                operation
            }
            None => {
                let envelope = current_envelope(
                    db,
                    root,
                    source_vault_id,
                    collection_id,
                    epoch,
                    &collection_key,
                    &item.object_id,
                )?;
                let payload = OperationPayload::new(
                    collection_id,
                    epoch,
                    PayloadBody::CommitObject {
                        object_id: item.object_id,
                        object_generation: item.object_generation,
                        store_id: item.store_id,
                        container_length: item.container_length,
                        container_commitment: item.container_commitment,
                        object_key_envelope: envelope,
                    },
                )?;
                log.author(
                    db,
                    random::id()?,
                    source_vault_id,
                    device_id,
                    domain.operation_key(),
                    Nonce::random()?,
                    &payload,
                    identity.signing_key(),
                    &membership,
                    &membership,
                    &sharing_state,
                )?
            }
        };
        published.push(Publication {
            candidate: *item,
            create_operation,
            commit_operation,
        });
    }
    Ok(published)
}

fn same_candidate(left: &Candidate, right: &Candidate) -> bool {
    left.object_id == right.object_id
        && left.object_generation == right.object_generation
        && left.stream_id == right.stream_id
        && left.stream_revision == right.stream_revision
        && left.store_id == right.store_id
        && left.container_length == right.container_length
        && left.container_commitment == right.container_commitment
}

fn metadata_fields(db: &CatalogDb, object_id: &Id) -> Result<Vec<MetadataField>> {
    let metadata = store::active_metadata(db, object_id)?;
    let mut fields = Vec::new();
    if let Some(name) = metadata
        .original_filename
        .filter(|name| name.len() <= 4_096)
    {
        fields.push(MetadataField::new(
            MetadataFieldId::OriginalFilename,
            name.into_bytes(),
        )?);
    }
    fields.push(MetadataField::new(
        MetadataFieldId::MediaType,
        metadata.content_type.into_bytes(),
    )?);
    if let Some(time) = metadata.capture_time_ms {
        fields.push(MetadataField::new(
            MetadataFieldId::CaptureTime,
            time.to_be_bytes().to_vec(),
        )?);
    }
    if let Some(caption) = metadata.caption {
        fields.push(MetadataField::new(
            MetadataFieldId::Caption,
            caption.into_bytes(),
        )?);
    }
    Ok(fields)
}

fn current_envelope(
    db: &CatalogDb,
    root: &Key,
    source_vault_id: Id,
    collection_id: Id,
    epoch: u64,
    collection_key: &Key,
    object_id: &Id,
) -> Result<ObjectKeyEnvelope> {
    let envelope = ObjectKeyEnvelope::decode(&store::active_envelope(db, object_id)?)?;
    ensure!(
        envelope.vault_id() == &source_vault_id
            && envelope.collection_id() == &collection_id
            && envelope.object_id() == object_id
            && envelope.collection_epoch() <= epoch,
        CatalogCorrupt,
        "object envelope does not match source collection"
    );
    if envelope.collection_epoch() == epoch {
        return Ok(envelope);
    }
    let old_key = sync_keys::collection_key(
        db,
        root,
        source_vault_id,
        collection_id,
        envelope.collection_epoch(),
    )?;
    envelope.rewrap(
        &old_key,
        collection_key,
        collection_id,
        epoch,
        envelope
            .envelope_generation()
            .checked_add(1)
            .ok_or_else(|| {
                Error::new(
                    ChurStatus::ResourceLimitExceeded,
                    "object envelope generation overflowed",
                )
            })?,
        Nonce::random()?,
    )
}
