//! Resolving the keys one object's containers are written and read under.
//!
//! `docs/security/KEY_HIERARCHY.md` §3 gives the chain: the root derives the
//! collection-envelope key, that opens the collection key, the collection key
//! derives the object-envelope key, and that opens the object key. Every step
//! is a derivation or an AEAD open, and none of the results leaves this crate
//! or `chur-catalog`.

use chur_catalog::vault::Session;
use chur_core::{Id, Result};
use chur_crypto::{Key, Nonce, random};
use chur_format::envelope::{CollectionKeyEnvelope, ObjectKeyEnvelope};

/// Opens the collection key of one collection epoch.
pub fn collection_key(session: &Session, collection_id: &Id, epoch: u64) -> Result<Key> {
    let body = chur_catalog::store::active_collection_envelope(
        session.catalog_ref()?,
        collection_id,
        epoch,
    )?;
    CollectionKeyEnvelope::decode(&body)?.open(session.root_secret())
}

/// Opens the object key of one object.
pub fn object_key(session: &Session, object_id: &Id) -> Result<Key> {
    let object = chur_catalog::store::object(session.catalog_ref()?, object_id)?;
    let collection =
        chur_catalog::store::collection(session.catalog_ref()?, &object.collection_id)?;
    let collection_key = collection_key(session, &object.collection_id, collection.current_epoch)?;
    let body = chur_catalog::store::active_envelope(session.catalog_ref()?, object_id)?;
    ObjectKeyEnvelope::decode(&body)?.open(&collection_key)
}

/// Seals a fresh object key under a collection key.
///
/// The object key is drawn here and never leaves this call except inside the
/// envelope and as the value the caller writes the container with.
pub fn seal_object_key(
    vault_id: &Id,
    collection_id: &Id,
    collection_epoch: u64,
    collection_key: &Key,
    object_id: &Id,
    envelope_generation: u64,
) -> Result<(Key, Vec<u8>)> {
    let object_key: Key = random::secret::<32>()?;
    let envelope = ObjectKeyEnvelope::seal(
        collection_key,
        *vault_id,
        *collection_id,
        collection_epoch,
        *object_id,
        envelope_generation,
        Nonce::random()?,
        &object_key,
    )?;
    Ok((object_key, envelope.encode()))
}

/// Creates the collection every object of a single-vault install belongs to.
///
/// It is idempotent, so the first unlock of a vault created by an older build
/// reaches the same state as one created by this build.
pub fn ensure_default_collection(session: &mut Session) -> Result<Id> {
    if let Some(existing) = chur_catalog::store::default_collection(session.catalog_ref()?)? {
        return Ok(existing);
    }
    let collection_id = random::id()?;
    let collection_key: Key = random::secret::<32>()?;
    let root = session.root_secret().duplicate();
    let vault_id = session.vault_id();
    let envelope = CollectionKeyEnvelope::seal(
        &root,
        vault_id,
        collection_id,
        1,
        1,
        Nonce::random()?,
        &collection_key,
    )?;
    chur_catalog::store::put_collection_with_envelope(
        session.catalog()?,
        &chur_catalog::model::Collection {
            collection_id,
            current_epoch: 1,
            policy_type: chur_catalog::model::COLLECTION_POLICY_VAULT_DEFAULT,
            created_revision: 1,
            status: chur_catalog::model::COLLECTION_STATUS_ACTIVE,
        },
        1,
        &envelope.encode(),
    )?;
    Ok(collection_id)
}
