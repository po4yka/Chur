//! Disposable object-key requests and mutable local key custody.

use chur_core::{ChurStatus, Error, Id, Result, ensure};
use chur_crypto::{Key, Nonce, random};
use chur_format::envelope::ObjectKeyEnvelope;
use chur_sync_protocol::{
    grant::PermissionProfile,
    object_grant::{ObjectKeyGrant, ObjectPrekey, object_key_commitment},
    state::DeviceStatus,
};
use rusqlite::{OptionalExtension, Transaction, params};
use zeroize::Zeroizing;

use crate::db::map_sqlite;
use crate::{
    CatalogDb, sharing, sharing_publication, sharing_receive, sharing_service, store, sync_keys,
    sync_membership,
};

/// Requests the key of an authenticated pending object. A deleted object can
/// never cause another request, even when its server replays the creation log.
pub fn request(
    db: &mut CatalogDb,
    root: &Key,
    local_vault_id: Id,
    collection_id: Id,
    object_id: Id,
) -> Result<ObjectPrekey> {
    ensure_not_deleted(db, &object_id)?;
    let object = sharing_receive::pending_object_for_collection(
        db,
        root,
        local_vault_id,
        collection_id,
        object_id,
    )?;
    let stored: Option<Vec<u8>> = db
        .connection()
        .query_row(
            "SELECT record FROM object_key_requests WHERE object_id = ?1
         UNION ALL SELECT request_record FROM pending_object_keys WHERE object_id = ?1 LIMIT 1",
            [object_id.as_bytes().as_slice()],
            |row| row.get(0),
        )
        .optional()
        .map_err(|error| map_sqlite(error, "object key request could not be read"))?;
    if let Some(bytes) = stored {
        let prekey = ObjectPrekey::decode(&bytes)?;
        ensure!(
            prekey.object_generation() == object.object_generation
                && prekey.container_commitment() == &object.container_commitment,
            Conflict,
            "object changed after its key request"
        );
        return Ok(prekey);
    }
    let membership = sync_membership::load(db)?.ok_or_else(|| {
        Error::new(
            ChurStatus::RecoveryRequired,
            "recipient membership is absent",
        )
    })?;
    ensure!(
        membership.vault_id() == &local_vault_id,
        AuthenticationFailed,
        "recipient vault differs"
    );
    let (device_id, identity) = sync_keys::local_identity(db, root, &membership)?
        .ok_or_else(|| Error::new(ChurStatus::RecoveryRequired, "recipient identity is absent"))?;
    let private = random::secret::<32>()?;
    let prekey = ObjectPrekey::sign(
        random::id()?,
        collection_id,
        object_id,
        object.object_generation,
        object.container_commitment,
        local_vault_id,
        device_id,
        1,
        &private,
        identity.signing_key(),
    )?;
    db.transaction(|tx| {
        tx.execute(
            "INSERT INTO object_key_requests VALUES (?1, ?2, ?3, ?4, ?5)",
            params![
                object_id.as_bytes().as_slice(),
                collection_id.as_bytes().as_slice(),
                prekey.prekey_id().as_bytes().as_slice(),
                private.expose().as_slice(),
                prekey.encode()
            ],
        )
        .map_err(|error| map_sqlite(error, "object key request could not be stored"))?;
        Ok(())
    })?;
    Ok(prekey)
}

/// Delivers a key only for the exact active object requested by an authorized
/// recipient. Public grants can be retained without retaining a decryption key.
pub fn issue(
    db: &mut CatalogDb,
    root: &Key,
    source_vault_id: Id,
    prekey: &ObjectPrekey,
) -> Result<ObjectKeyGrant> {
    ensure_not_deleted(db, prekey.object_id())?;
    sharing_publication::ensure_ready(db, root, source_vault_id, *prekey.collection_id())?;
    let state = sharing::load(db, prekey.collection_id())?
        .ok_or_else(|| Error::new(ChurStatus::NotFound, "shared collection is absent"))?;
    ensure!(
        state.source_vault_id() == &source_vault_id
            && state.is_authorized(
                prekey.recipient_vault_id(),
                prekey.recipient_device_id(),
                PermissionProfile::Read
            ),
        AuthenticationFailed,
        "object key request recipient is not authorized"
    );
    let member = state
        .member(prekey.recipient_vault_id(), prekey.recipient_device_id())
        .ok_or_else(|| {
            Error::new(
                ChurStatus::AuthenticationFailed,
                "request recipient is absent",
            )
        })?;
    prekey.verify(member.signing_public_key())?;
    let candidate = sharing_publication::candidate(db, prekey.collection_id(), prekey.object_id())?;
    ensure!(
        candidate.object_generation == prekey.object_generation()
            && &candidate.container_commitment == prekey.container_commitment(),
        AuthenticationFailed,
        "object key request names another committed object"
    );
    let membership = sync_membership::load(db)?
        .ok_or_else(|| Error::new(ChurStatus::RecoveryRequired, "source membership is absent"))?;
    let (device_id, identity) = sync_keys::local_identity(db, root, &membership)?
        .ok_or_else(|| Error::new(ChurStatus::RecoveryRequired, "source identity is absent"))?;
    let previous: Option<(Vec<u8>, Vec<u8>)> = db.connection().query_row(
        "SELECT request_record, grant_record FROM issued_object_key_grants WHERE prekey_id = ?1",
        [prekey.prekey_id().as_bytes().as_slice()], |row| Ok((row.get(0)?, row.get(1)?)),
    ).optional().map_err(|error| map_sqlite(error, "issued object grant could not be read"))?;
    if let Some((request, grant)) = previous {
        ensure!(
            request == prekey.encode(),
            AuthenticationFailed,
            "prekey identity was reused"
        );
        let grant = ObjectKeyGrant::decode(&grant)?;
        ensure!(
            grant.source_vault_id() == &source_vault_id && grant.prekey_id() == prekey.prekey_id(),
            CatalogCorrupt,
            "cached object grant has another binding"
        );
        if membership.is_active(grant.source_device_id())
            && membership
                .device(grant.source_device_id())
                .is_some_and(|sender| grant.verify(sender.signing_public_key()).is_ok())
        {
            return Ok(grant);
        }
        // An authorized sibling reissues a public response whose old signer
        // was revoked or rotated. The recipient's disposable request survives.
    }
    let envelope = ObjectKeyEnvelope::decode(&store::active_envelope(db, prekey.object_id())?)?;
    let collection_key = sync_keys::collection_key(
        db,
        root,
        source_vault_id,
        *prekey.collection_id(),
        envelope.collection_epoch(),
    )?;
    let key = envelope.open(&collection_key)?;
    let grant = ObjectKeyGrant::seal(
        random::id()?,
        source_vault_id,
        device_id,
        candidate.object_id,
        candidate.object_generation,
        candidate.container_commitment,
        prekey,
        &key,
        identity.signing_key(),
    )?;
    db.transaction(|tx| {
        tx.execute(
            "DELETE FROM issued_object_key_grants WHERE prekey_id = ?1",
            [prekey.prekey_id().as_bytes().as_slice()],
        )
        .map_err(|error| map_sqlite(error, "stale public object grant could not be retired"))?;
        // This is a public response cache, not key custody or chain state.
        // Eviction is safe because a retry can issue another equivalent grant.
        tx.execute(
            "DELETE FROM issued_object_key_grants WHERE rowid IN (
            SELECT rowid FROM issued_object_key_grants ORDER BY rowid
            LIMIT max(0, (SELECT count(*) FROM issued_object_key_grants) - 4095))",
            [],
        )
        .map_err(|error| map_sqlite(error, "object grant cache could not be bounded"))?;
        tx.execute(
            "INSERT INTO issued_object_key_grants VALUES (?1, ?2, ?3, ?4)",
            params![
                prekey.prekey_id().as_bytes().as_slice(),
                candidate.object_id.as_bytes().as_slice(),
                prekey.encode(),
                grant.encode()
            ],
        )
        .map_err(|error| map_sqlite(error, "object key grant could not be stored"))?;
        Ok(())
    })?;
    Ok(grant)
}

/// Accepts a grant once, stores a local envelope, and destroys its disposable
/// private key before success. A restart or duplicate grant does not need it.
pub fn accept(
    db: &mut CatalogDb,
    root: &Key,
    local_vault_id: Id,
    issuers: &[sharing_service::IssuerEvidence<'_>],
    grant: &ObjectKeyGrant,
) -> Result<()> {
    ensure_not_deleted(db, grant.object_id())?;
    let state = sharing::load(db, grant.collection_id())?
        .ok_or_else(|| Error::new(ChurStatus::NotFound, "shared collection is absent"))?;
    let membership = sync_membership::load(db)?.ok_or_else(|| {
        Error::new(
            ChurStatus::RecoveryRequired,
            "recipient membership is absent",
        )
    })?;
    let (device_id, _) = sync_keys::local_identity(db, root, &membership)?
        .ok_or_else(|| Error::new(ChurStatus::RecoveryRequired, "recipient identity is absent"))?;
    ensure!(
        grant.recipient_vault_id() == &local_vault_id
            && grant.recipient_device_id() == &device_id
            && grant.source_vault_id() == state.source_vault_id()
            && state.is_authorized(&local_vault_id, &device_id, PermissionProfile::Read),
        AuthenticationFailed,
        "object grant addresses another collection member"
    );
    let (states, _) = sharing_service::authenticate_issuers(issuers)?;
    let source = states
        .get(grant.source_vault_id())
        .and_then(|history| history.last_key_value().map(|(_, state)| state))
        .ok_or_else(|| {
            Error::new(
                ChurStatus::AuthenticationFailed,
                "object grant source membership is absent",
            )
        })?;
    let sender = source.device(grant.source_device_id()).ok_or_else(|| {
        Error::new(
            ChurStatus::AuthenticationFailed,
            "object grant sender is unknown",
        )
    })?;
    ensure!(
        sender.status() == DeviceStatus::Active,
        AuthenticationFailed,
        "object grant sender is revoked"
    );
    grant.verify(sender.signing_public_key())?;
    if store::object_present(db, grant.object_id())? {
        let object = store::object(db, grant.object_id())?;
        ensure!(
            object.state == chur_format::constants::ObjectState::Active,
            NotFound,
            "object is not active"
        );
        ensure!(
            object.collection_id == *grant.collection_id()
                && object.object_generation == grant.object_generation(),
            AuthenticationFailed,
            "object grant differs from the active object"
        );
        let candidate =
            sharing_publication::candidate(db, grant.collection_id(), grant.object_id())?;
        ensure!(
            &candidate.container_commitment == grant.container_commitment(),
            AuthenticationFailed,
            "object grant differs from the active container"
        );
        let envelope = ObjectKeyEnvelope::decode(&store::active_envelope(db, grant.object_id())?)?;
        ensure!(
            envelope.vault_id() == &local_vault_id
                && envelope.collection_id() == grant.collection_id()
                && envelope.object_id() == grant.object_id(),
            AuthenticationFailed,
            "active object envelope has another binding"
        );
        let collection_key = sync_keys::collection_key(
            db,
            root,
            local_vault_id,
            *grant.collection_id(),
            envelope.collection_epoch(),
        )?;
        let key = envelope.open(&collection_key)?;
        ensure!(
            &object_key_commitment(grant.collection_id(), grant.object_id(), &key)
                == grant.object_key_commitment(),
            AuthenticationFailed,
            "object grant differs from the protected active key"
        );
        return db.checkpoint();
    }
    let expected = sharing_receive::pending_object_for_collection(
        db,
        root,
        local_vault_id,
        *grant.collection_id(),
        *grant.object_id(),
    )?;
    ensure!(
        expected.object_generation == grant.object_generation()
            && &expected.container_commitment == grant.container_commitment()
            && &expected.object_key_commitment == grant.object_key_commitment(),
        AuthenticationFailed,
        "object grant differs from the authenticated commit"
    );
    let previous: Option<Vec<u8>> = db
        .connection()
        .query_row(
            "SELECT grant_record FROM pending_object_keys WHERE object_id = ?1",
            [grant.object_id().as_bytes().as_slice()],
            |row| row.get(0),
        )
        .optional()
        .map_err(|error| map_sqlite(error, "pending object key could not be read"))?;
    if let Some(previous) = previous {
        let previous = ObjectKeyGrant::decode(&previous)?;
        ensure!(
            previous.prekey_id() == grant.prekey_id(),
            AuthenticationFailed,
            "object has a different pending request"
        );
        // The signature and accepted commit were checked above. A response
        // reissued after public-cache eviction can have a new HPKE nonce.
        return db.checkpoint();
    }
    let (record, private): (Vec<u8>, Vec<u8>) = db.connection().query_row(
        "SELECT record, private_key FROM object_key_requests WHERE object_id = ?1 AND prekey_id = ?2",
        params![grant.object_id().as_bytes().as_slice(), grant.prekey_id().as_bytes().as_slice()],
        |row| Ok((row.get(0)?, row.get(1)?)),
    ).map_err(|error| map_sqlite(error, "object grant has no live key request"))?;
    let private = Zeroizing::new(private);
    ensure!(
        private.len() == 32,
        CatalogCorrupt,
        "object prekey secret is malformed"
    );
    let mut secret = Key::zeroed();
    secret.expose_mut().copy_from_slice(&private);
    let prekey = ObjectPrekey::decode(&record)?;
    let key = grant.open(&prekey, &secret, sender.signing_public_key())?;
    ensure!(
        object_key_commitment(grant.collection_id(), grant.object_id(), &key)
            == expected.object_key_commitment,
        AuthenticationFailed,
        "object key commitment differs"
    );
    let collection = store::collection(db, grant.collection_id())?;
    let collection_key = sync_keys::collection_key(
        db,
        root,
        local_vault_id,
        *grant.collection_id(),
        collection.current_epoch,
    )?;
    let envelope = ObjectKeyEnvelope::seal(
        &collection_key,
        local_vault_id,
        *grant.collection_id(),
        collection.current_epoch,
        *grant.object_id(),
        1,
        Nonce::random()?,
        &key,
    )?;
    db.transaction(|tx| {
        tx.execute(
            "INSERT INTO pending_object_keys VALUES (?1, ?2, ?3, ?4, ?5)",
            params![
                grant.object_id().as_bytes().as_slice(),
                grant.collection_id().as_bytes().as_slice(),
                record,
                grant.encode(),
                envelope.encode()
            ],
        )
        .map_err(|error| map_sqlite(error, "pending object key could not be stored"))?;
        tx.execute(
            "DELETE FROM object_key_requests WHERE object_id = ?1",
            [grant.object_id().as_bytes().as_slice()],
        )
        .map_err(|error| map_sqlite(error, "object prekey could not be destroyed"))?;
        Ok(())
    })?;
    db.checkpoint()
}

/// Reads the local key custody row required by container activation.
pub fn pending_envelope(db: &CatalogDb, object_id: &Id) -> Result<ObjectKeyEnvelope> {
    ensure_not_deleted(db, object_id)?;
    let bytes: Vec<u8> = db
        .connection()
        .query_row(
            "SELECT envelope FROM pending_object_keys WHERE object_id = ?1",
            [object_id.as_bytes().as_slice()],
            |row| row.get(0),
        )
        .map_err(|error| map_sqlite(error, "object key grant has not arrived"))?;
    ObjectKeyEnvelope::decode(&bytes)
}

/// Removes temporary local key custody after atomic object activation.
pub fn remove_pending(tx: &Transaction<'_>, object_id: &Id) -> Result<()> {
    tx.execute(
        "DELETE FROM pending_object_keys WHERE object_id = ?1",
        [object_id.as_bytes().as_slice()],
    )
    .map_err(|error| map_sqlite(error, "pending object key could not be removed"))?;
    Ok(())
}

/// Returns a stable not-found verdict for locally erased objects.
pub(crate) fn ensure_not_deleted(db: &CatalogDb, object_id: &Id) -> Result<()> {
    let deleted: i64 = db
        .connection()
        .query_row(
            "SELECT count(*) FROM tombstones WHERE object_id = ?1",
            [object_id.as_bytes().as_slice()],
            |row| row.get(0),
        )
        .map_err(|error| map_sqlite(error, "local object tombstone could not be read"))?;
    ensure!(
        deleted == 0,
        NotFound,
        "object was permanently deleted on this device"
    );
    Ok(())
}

#[cfg(test)]
mod tests {
    #![allow(clippy::expect_used)]
    use super::*;
    use crate::{
        db::{CatalogKey, CatalogLocation},
        model::{COLLECTION_POLICY_VAULT_DEFAULT, COLLECTION_STATUS_ACTIVE, Collection},
        schema,
        sharing_log::DurableCollectionOperationLog,
        sync_log, sync_receive,
    };
    use chur_format::envelope::CollectionKeyEnvelope;
    use chur_sync_protocol::{
        KeyDomain,
        operation::Operation,
        payload::{OperationPayload, PayloadBody},
    };
    fn id(value: u8) -> Id {
        Id::new([value; 16]).expect("id")
    }
    #[test]
    fn unavailable_requests_do_not_starve_later_keys_or_evict_asynchronous_replies() {
        recipient_key_fixture(false);
    }
    #[test]
    fn active_object_rejects_wrong_signed_grant_before_idempotent_acknowledgment() {
        recipient_key_fixture(true);
    }
    fn recipient_key_fixture(check_active: bool) {
        let source_vault = id(21);
        let collection = id(22);
        let source_root = Key::new([23; 32]);
        let mut source = CatalogDb::open(
            &CatalogLocation::Memory,
            &CatalogKey::derive(&source_root, &source_vault).expect("source key"),
        )
        .expect("source");
        schema::open_at_current_version(&mut source, 1).expect("source schema");
        let (source_enrollment, _) =
            sync_receive::provision_local_identity(&mut source, &source_root, source_vault)
                .expect("source identity");
        let metadata_key = Key::new([24; 32]);
        let envelope = CollectionKeyEnvelope::seal(
            &source_root,
            source_vault,
            collection,
            1,
            1,
            Nonce::new([25; 24]),
            &metadata_key,
        )
        .expect("collection envelope");
        store::put_collection_with_envelope(
            &mut source,
            &Collection {
                collection_id: collection,
                current_epoch: 1,
                policy_type: COLLECTION_POLICY_VAULT_DEFAULT,
                created_revision: 1,
                status: COLLECTION_STATUS_ACTIVE,
            },
            1,
            &envelope.encode(),
        )
        .expect("source collection");
        let recipient_vault = id(26);
        let recipient_root = Key::new([27; 32]);
        let mut recipient = CatalogDb::open(
            &CatalogLocation::Memory,
            &CatalogKey::derive(&recipient_root, &recipient_vault).expect("recipient key"),
        )
        .expect("recipient");
        schema::open_at_current_version(&mut recipient, 1).expect("recipient schema");
        let (recipient_enrollment, _) = sync_receive::provision_local_identity(
            &mut recipient,
            &recipient_root,
            recipient_vault,
        )
        .expect("recipient identity");
        let prepared = sharing_service::prepare_share(
            &mut source,
            &source_root,
            source_vault,
            collection,
            &recipient_enrollment,
            PermissionProfile::Read,
            true,
        )
        .expect("authorized share");
        let records = sync_log::records_after(&source, source_enrollment.device_id(), 0)
            .expect("source evidence")
            .iter()
            .map(|bytes| Operation::decode(bytes).expect("operation"))
            .collect::<Vec<_>>();
        let membership_records = [sharing_service::IssuerMembershipRecord::Enrollment(
            source_enrollment.clone(),
        )];
        let issuers = [sharing_service::IssuerEvidence {
            membership: &membership_records,
            operations: &records,
        }];
        let changes = [(
            prepared.membership().clone(),
            prepared.membership_operation().clone(),
        )];
        let source_membership = sync_membership::load(&source)
            .expect("membership")
            .expect("present");
        let (_, identity) = sync_keys::local_identity(&source, &source_root, &source_membership)
            .expect("source identity")
            .expect("present");
        let shared = sharing::load(&source, &collection)
            .expect("state")
            .expect("present");
        let domain = KeyDomain::collection(&metadata_key, &collection, 1).expect("domain");
        let mut log = DurableCollectionOperationLog::provision(
            &mut source,
            collection,
            1,
            *domain.selector(),
        )
        .expect("collection log");
        let mut commits = Vec::new();
        let mut objects = Vec::new();
        for marker in 1..=66u8 {
            let object = random::id().expect("object");
            let content = Key::new([marker; 32]);
            for body in [
                PayloadBody::CreateObject {
                    object_id: object,
                    object_generation: 1,
                    store_id: object,
                    stream_id: object,
                    metadata_fields: vec![],
                },
                PayloadBody::CommitObject {
                    object_id: object,
                    object_generation: 1,
                    store_id: object,
                    container_length: 1024,
                    container_commitment: [marker; 32],
                    object_key_commitment: object_key_commitment(&collection, &object, &content),
                },
            ] {
                commits.push(
                    log.author(
                        &mut source,
                        random::id().expect("operation id"),
                        source_vault,
                        *source_enrollment.device_id(),
                        domain.operation_key(),
                        Nonce::random().expect("nonce"),
                        &OperationPayload::new(collection, 1, body).expect("payload"),
                        identity.signing_key(),
                        &source_membership,
                        &source_membership,
                        &shared,
                    )
                    .expect("signed committed object"),
                );
            }
            objects.push((object, content));
        }
        let plan = sharing_receive::receive(
            &mut recipient,
            &recipient_root,
            recipient_vault,
            &issuers,
            &changes,
            prepared.grant(),
            prepared.grant_operation(),
            &commits,
        )
        .expect("authenticated collection receive");
        assert_eq!(plan.objects.len(), 66);
        let mut requests = Vec::new();
        for (object, _) in &objects {
            requests.push(
                request(
                    &mut recipient,
                    &recipient_root,
                    recipient_vault,
                    collection,
                    *object,
                )
                .expect("every authenticated object can request its own key"),
            );
        }
        for index in [65usize, 0] {
            let (object, content) = &objects[index];
            let grant = ObjectKeyGrant::seal(
                random::id().expect("grant id"),
                source_vault,
                *source_enrollment.device_id(),
                *object,
                1,
                [index as u8 + 1; 32],
                &requests[index],
                content,
                identity.signing_key(),
            )
            .expect("asynchronous signed grant");
            accept(
                &mut recipient,
                &recipient_root,
                recipient_vault,
                &issuers,
                &grant,
            )
            .expect("grant acceptance");
            assert_eq!(
                pending_envelope(&recipient, object)
                    .expect("accepted local key")
                    .open(&metadata_key)
                    .expect("local envelope")
                    .expose(),
                content.expose()
            );
        }
        assert_eq!(
            request(
                &mut recipient,
                &recipient_root,
                recipient_vault,
                collection,
                objects[1].0
            )
            .expect("unavailable neighbor retained")
            .encode(),
            requests[1].encode()
        );
        let private_count: i64 = recipient
            .connection()
            .query_row("SELECT count(*) FROM object_key_requests", [], |row| {
                row.get(0)
            })
            .expect("pending count");
        assert_eq!(
            private_count, 64,
            "only accepted replies consume their own private keys"
        );
        if check_active {
            use crate::model::{MetadataRevision, Object, Stream};
            use chur_format::constants::{IntegritySummary, MediaClass, ObjectState, StreamKind};
            let (object, content) = &objects[65];
            let local = pending_envelope(&recipient, object).expect("accepted local key");
            store::activate_object(
                &mut recipient,
                &store::ObjectActivation {
                    object: Object {
                        object_id: *object,
                        object_generation: 1,
                        collection_id: collection,
                        primary_stream_id: *object,
                        media_kind: MediaClass::Image,
                        capture_time_ms: 1,
                        import_time_ms: 1,
                        capture_time_substituted: false,
                        plaintext_size: 16,
                        width: 1,
                        height: 1,
                        duration_ms: 0,
                        favorite: false,
                        state: ObjectState::Active,
                        integrity_summary: IntegritySummary::CompleteVerified,
                        thumbnail_ready: false,
                        active_metadata_revision: 1,
                    },
                    stream: Stream {
                        stream_id: *object,
                        object_id: *object,
                        stream_kind: StreamKind::Original,
                        stream_revision: 1,
                        source_content_revision: 0,
                        container_path_id: *object,
                        container_version: 1,
                        suite_id: 1,
                        ciphertext_size: 1024,
                        plaintext_size: 16,
                        chunk_size: 262144,
                        complete_verified_ms: Some(1),
                        final_commitment: [66; 32],
                    },
                    envelope: local.encode(),
                    envelope_generation: local.envelope_generation(),
                    metadata: MetadataRevision {
                        object_id: *object,
                        revision: 1,
                        active: true,
                        record: vec![1],
                        original_filename: None,
                        caption: None,
                        content_type: "image/jpeg".into(),
                        capture_time_ms: Some(1),
                        width: 1,
                        height: 1,
                        duration_ms: 0,
                    },
                },
            )
            .expect("activate healthy protected object");
            let bad = ObjectKeyGrant::seal(
                random::id().expect("bad grant id"),
                source_vault,
                *source_enrollment.device_id(),
                *object,
                1,
                [66; 32],
                &requests[65],
                &Key::new([201; 32]),
                identity.signing_key(),
            )
            .expect("authorized source signs a wrong object key");
            assert_eq!(
                accept(
                    &mut recipient,
                    &recipient_root,
                    recipient_vault,
                    &issuers,
                    &bad
                )
                .err()
                .map(|error| error.status()),
                Some(ChurStatus::AuthenticationFailed),
                "active media must not success-ack a mismatching key commitment"
            );
            let recipient_membership = sync_membership::load(&recipient)
                .expect("recipient membership")
                .expect("present");
            let (recipient_device, recipient_identity) =
                sync_keys::local_identity(&recipient, &recipient_root, &recipient_membership)
                    .expect("recipient identity")
                    .expect("present");
            for (generation, commitment) in [(2, [66; 32]), (1, [202; 32])] {
                let alternate = ObjectPrekey::sign(
                    random::id().expect("alternate request"),
                    collection,
                    *object,
                    generation,
                    commitment,
                    recipient_vault,
                    recipient_device,
                    1,
                    &Key::new([203; 32]),
                    recipient_identity.signing_key(),
                )
                .expect("bound alternate request");
                let malformed = ObjectKeyGrant::seal(
                    random::id().expect("alternate grant"),
                    source_vault,
                    *source_enrollment.device_id(),
                    *object,
                    generation,
                    commitment,
                    &alternate,
                    content,
                    identity.signing_key(),
                )
                .expect("authorized source signs incorrect binding");
                assert_eq!(
                    accept(
                        &mut recipient,
                        &recipient_root,
                        recipient_vault,
                        &issuers,
                        &malformed
                    )
                    .err()
                    .map(|error| error.status()),
                    Some(ChurStatus::AuthenticationFailed),
                    "an active original rejects another generation or container"
                );
            }
            let healthy = ObjectKeyGrant::seal(
                random::id().expect("healthy grant id"),
                source_vault,
                *source_enrollment.device_id(),
                *object,
                1,
                [66; 32],
                &requests[65],
                content,
                identity.signing_key(),
            )
            .expect("healthy retry");
            accept(
                &mut recipient,
                &recipient_root,
                recipient_vault,
                &issuers,
                &healthy,
            )
            .expect("valid active grant retry");
            assert_eq!(
                store::active_envelope(&recipient, object).expect("protected key remains"),
                local.encode()
            );
            assert!(
                pending_envelope(&recipient, object).is_err(),
                "active retry must not recreate a private request or pending key"
            );
        }
    }
}
