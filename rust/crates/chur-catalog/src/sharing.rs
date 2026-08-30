//! Durable collection-sharing membership and recipient key pins.

use std::collections::BTreeMap;

use chur_core::{ChurStatus, Error, Id, Result, bail, ensure};
use chur_sync_protocol::{
    collection_membership::{
        CollectionMembershipOutcome, CollectionMembershipRecord, CollectionMembershipState,
        RecipientPin, RecipientVerification,
    },
    grant::CollectionGrant,
    state::MembershipState,
};
use rusqlite::{OptionalExtension, Transaction, params};

use crate::{
    db::{CatalogDb, as_sqlite_integer, from_sqlite_integer, map_sqlite},
    schema::bump_generation,
};

const TOFU: i64 = 1;
const VERIFIED: i64 = 2;
type StoredStateRow = (Vec<u8>, i64, i64, Vec<u8>, i64);

struct StoredPin {
    signing_public_key: [u8; 32],
    hpke_public_key: [u8; 32],
    verification: RecipientVerification,
}

/// Result of storing one canonical collection grant.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum GrantStoreOutcome {
    /// A new grant was stored.
    Stored,
    /// The exact canonical grant was already stored.
    Duplicate,
}

/// Creates the empty durable sharing state for one security collection.
pub fn provision(
    db: &mut CatalogDb,
    source_vault_id: Id,
    collection_id: Id,
    initial_epoch: u64,
) -> Result<CollectionMembershipState> {
    let state = CollectionMembershipState::new(source_vault_id, collection_id, initial_epoch)?;
    db.transaction(|transaction| {
        transaction
            .execute(
                "INSERT INTO sharing_collections
                     (collection_id, source_vault_id, initial_epoch,
                      membership_generation, membership_commitment, current_epoch)
                 VALUES (?1, ?2, ?3, 0, ?4, ?3)",
                params![
                    collection_id.as_bytes().as_slice(),
                    source_vault_id.as_bytes().as_slice(),
                    as_sqlite_integer(initial_epoch, "the initial collection epoch is too large")?,
                    state.commitment().as_slice(),
                ],
            )
            .map_err(|error| map_sqlite(error, "sharing state could not be provisioned"))?;
        bump_generation(transaction)?;
        Ok(())
    })?;
    Ok(state)
}

/// Accepts one collection-membership successor atomically.
pub fn accept_membership(
    db: &mut CatalogDb,
    record: &CollectionMembershipRecord,
    issuer_membership: &MembershipState,
) -> Result<(CollectionMembershipState, CollectionMembershipOutcome)> {
    let state = load(db, record.collection_id())?.ok_or_else(|| {
        Error::new(
            ChurStatus::VaultIncomplete,
            "collection sharing is not provisioned",
        )
    })?;
    db.transaction(|transaction| {
        let outcome = project_membership(transaction, &state, record, issuer_membership)?;
        if outcome.1 == CollectionMembershipOutcome::Applied {
            bump_generation(transaction)?;
        }
        Ok(outcome)
    })
}

/// Projects one accepted membership successor inside an existing transaction.
pub fn project_membership(
    transaction: &Transaction<'_>,
    current: &CollectionMembershipState,
    record: &CollectionMembershipRecord,
    issuer_membership: &MembershipState,
) -> Result<(CollectionMembershipState, CollectionMembershipOutcome)> {
    let mut candidate = current.clone();
    let outcome = candidate.accept(record, issuer_membership)?;
    if outcome == CollectionMembershipOutcome::Duplicate {
        check_head(transaction, current)?;
        return Ok((candidate, outcome));
    }
    let issuer = issuer_membership
        .device(record.issuer_device_id())
        .ok_or_else(|| Error::new(ChurStatus::AuthenticationFailed, "issuer is unknown"))?;
    transaction
        .execute(
            "INSERT INTO sharing_membership_records
                 (collection_id, membership_generation, commitment,
                  issuer_signing_public_key, recipient_identity_vault_id,
                  recipient_device_id, record)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            params![
                record.collection_id().as_bytes().as_slice(),
                as_sqlite_integer(
                    record.collection_membership_generation(),
                    "the collection membership generation is too large"
                )?,
                record.commitment().as_slice(),
                issuer.signing_public_key().as_slice(),
                record.recipient_identity_vault_id().as_bytes().as_slice(),
                record.recipient_device_id().as_bytes().as_slice(),
                record.encode(),
            ],
        )
        .map_err(|error| {
            map_sqlite(
                error,
                "the collection membership record could not be written",
            )
        })?;
    upsert_pin(
        transaction,
        record.collection_id(),
        record.recipient_identity_vault_id(),
        record.recipient_device_id(),
        candidate
            .recipient_pin(
                record.recipient_identity_vault_id(),
                record.recipient_device_id(),
            )
            .ok_or_else(|| Error::new(ChurStatus::InternalFailure, "accepted member has no pin"))?,
    )?;
    let changed = transaction
        .execute(
            "UPDATE sharing_collections
                SET membership_generation = ?2, membership_commitment = ?3, current_epoch = ?4
              WHERE collection_id = ?1
                AND membership_generation = ?5
                AND membership_commitment = ?6
                AND current_epoch = ?7",
            params![
                record.collection_id().as_bytes().as_slice(),
                as_sqlite_integer(
                    candidate.generation(),
                    "the collection membership generation is too large"
                )?,
                candidate.commitment().as_slice(),
                as_sqlite_integer(
                    candidate.collection_epoch(),
                    "the collection epoch is too large"
                )?,
                as_sqlite_integer(
                    current.generation(),
                    "the previous collection membership generation is too large"
                )?,
                current.commitment().as_slice(),
                as_sqlite_integer(
                    current.collection_epoch(),
                    "the previous collection epoch is too large"
                )?,
            ],
        )
        .map_err(|error| {
            map_sqlite(
                error,
                "the collection membership head could not be advanced",
            )
        })?;
    ensure!(
        changed == 1,
        Conflict,
        "the collection membership head changed concurrently"
    );
    Ok((candidate, outcome))
}

/// Projects one authenticated collection epoch inside an existing transaction.
pub fn project_collection_epoch(
    transaction: &Transaction<'_>,
    current: &CollectionMembershipState,
    target_epoch: u64,
) -> Result<CollectionMembershipState> {
    let mut candidate = current.clone();
    if !candidate.advance_collection_epoch(target_epoch)? {
        check_head(transaction, current)?;
        return Ok(candidate);
    }
    let changed = transaction
        .execute(
            "UPDATE sharing_collections SET current_epoch = ?2
              WHERE collection_id = ?1 AND membership_generation = ?3
                AND membership_commitment = ?4 AND current_epoch = ?5",
            params![
                current.collection_id().as_bytes().as_slice(),
                as_sqlite_integer(target_epoch, "the target collection epoch is too large")?,
                as_sqlite_integer(
                    current.generation(),
                    "the collection membership generation is too large"
                )?,
                current.commitment().as_slice(),
                as_sqlite_integer(
                    current.collection_epoch(),
                    "the previous collection epoch is too large"
                )?,
            ],
        )
        .map_err(|error| map_sqlite(error, "the sharing collection epoch could not advance"))?;
    ensure!(
        changed == 1,
        Conflict,
        "the sharing collection epoch changed concurrently"
    );
    Ok(candidate)
}

/// Replays one collection's accepted membership and recipient pins.
pub fn load(db: &CatalogDb, collection_id: &Id) -> Result<Option<CollectionMembershipState>> {
    let stored: Option<StoredStateRow> = db
        .connection()
        .query_row(
            "SELECT source_vault_id, initial_epoch, membership_generation,
                    membership_commitment, current_epoch
               FROM sharing_collections WHERE collection_id = ?1",
            [collection_id.as_bytes().as_slice()],
            |row| {
                Ok((
                    row.get(0)?,
                    row.get(1)?,
                    row.get(2)?,
                    row.get(3)?,
                    row.get(4)?,
                ))
            },
        )
        .optional()
        .map_err(|error| map_sqlite(error, "sharing state could not be read"))?;
    let Some((source_bytes, initial_epoch, generation, head_bytes, current_epoch)) = stored else {
        let partial: i64 = db
            .connection()
            .query_row(
                "SELECT
                    (SELECT count(*) FROM sharing_membership_records WHERE collection_id = ?1) +
                    (SELECT count(*) FROM sharing_recipient_pins WHERE collection_id = ?1) +
                    (SELECT count(*) FROM sharing_grants WHERE collection_id = ?1)",
                [collection_id.as_bytes().as_slice()],
                |row| row.get(0),
            )
            .map_err(|error| map_sqlite(error, "partial sharing state could not be inspected"))?;
        ensure!(
            partial == 0,
            CatalogCorrupt,
            "sharing rows exist without collection state"
        );
        return Ok(None);
    };
    let source_vault_id = crate::row::id(&source_bytes, "the source vault id is malformed")?;
    let initial_epoch = from_sqlite_integer(initial_epoch, "the initial epoch is negative")?;
    let stored_generation = from_sqlite_integer(
        generation,
        "the collection membership generation is negative",
    )?;
    let stored_head = fixed(&head_bytes, "the collection membership head is malformed")?;
    let stored_epoch = from_sqlite_integer(current_epoch, "the collection epoch is negative")?;
    let collection_epoch: Option<i64> = db
        .connection()
        .query_row(
            "SELECT current_epoch FROM collections WHERE collection_id = ?1",
            [collection_id.as_bytes().as_slice()],
            |row| row.get(0),
        )
        .optional()
        .map_err(|error| map_sqlite(error, "the collection epoch could not be read"))?;
    if let Some(collection_epoch) = collection_epoch {
        let collection_epoch =
            from_sqlite_integer(collection_epoch, "the collection epoch is negative")?;
        ensure!(
            stored_epoch == collection_epoch
                || collection_epoch
                    .checked_add(1)
                    .is_some_and(|pending| pending == stored_epoch),
            CatalogCorrupt,
            "sharing and collection epochs contradict"
        );
    }
    let pins = load_pins(db, collection_id)?;
    let mut state = CollectionMembershipState::new(source_vault_id, *collection_id, initial_epoch)
        .map_err(corrupt_sharing)?;

    let mut statement = db
        .connection()
        .prepare(
            "SELECT membership_generation, commitment, issuer_signing_public_key,
                    recipient_identity_vault_id, recipient_device_id, record
               FROM sharing_membership_records
              WHERE collection_id = ?1 ORDER BY membership_generation",
        )
        .map_err(|error| {
            map_sqlite(error, "collection membership records could not be prepared")
        })?;
    let mut rows = statement
        .query([collection_id.as_bytes().as_slice()])
        .map_err(|error| map_sqlite(error, "collection membership records could not be read"))?;
    while let Some(row) = rows
        .next()
        .map_err(|error| map_sqlite(error, "a collection membership record could not be read"))?
    {
        let projected_generation: i64 = row
            .get(0)
            .map_err(|error| map_sqlite(error, "a membership generation could not be read"))?;
        let projected_commitment: Vec<u8> = row
            .get(1)
            .map_err(|error| map_sqlite(error, "a membership commitment could not be read"))?;
        let issuer_key: Vec<u8> = row
            .get(2)
            .map_err(|error| map_sqlite(error, "an issuer key could not be read"))?;
        let recipient_vault: Vec<u8> = row
            .get(3)
            .map_err(|error| map_sqlite(error, "a recipient vault id could not be read"))?;
        let recipient_device: Vec<u8> = row
            .get(4)
            .map_err(|error| map_sqlite(error, "a recipient device id could not be read"))?;
        let bytes: Vec<u8> = row.get(5).map_err(|error| {
            map_sqlite(error, "a collection membership record could not be read")
        })?;
        let record = CollectionMembershipRecord::decode(&bytes).map_err(corrupt_sharing)?;
        let generation = from_sqlite_integer(
            projected_generation,
            "a collection membership generation is negative",
        )?;
        let commitment = fixed(
            &projected_commitment,
            "a collection membership commitment is malformed",
        )?;
        let recipient_vault =
            crate::row::id(&recipient_vault, "a recipient vault id is malformed")?;
        let recipient_device =
            crate::row::id(&recipient_device, "a recipient device id is malformed")?;
        ensure!(
            record.collection_id() == collection_id
                && record.collection_membership_generation() == generation
                && record.commitment() == commitment
                && record.recipient_identity_vault_id() == &recipient_vault
                && record.recipient_device_id() == &recipient_device,
            CatalogCorrupt,
            "a collection membership projection contradicts its record"
        );

        restore_pin_for_record(&mut state, &pins, &record)?;
        let issuer_key = fixed(&issuer_key, "an issuer signing key is malformed")?;
        ensure!(
            state
                .restore_accepted(&record, &issuer_key)
                .map_err(corrupt_sharing)?
                == CollectionMembershipOutcome::Applied,
            CatalogCorrupt,
            "the stored collection membership chain contains a duplicate"
        );
    }

    for ((identity_vault_id, device_id), pin) in &pins {
        let differs = state
            .recipient_pin(identity_vault_id, device_id)
            .is_none_or(|restored| {
                restored.signing_public_key() != &pin.signing_public_key
                    || restored.hpke_public_key() != &pin.hpke_public_key
                    || restored.verification() != pin.verification
            });
        if differs {
            ensure!(
                pin.verification == RecipientVerification::Verified,
                CatalogCorrupt,
                "a TOFU pin contradicts accepted membership"
            );
            state
                .verify_recipient_keys(
                    *identity_vault_id,
                    *device_id,
                    pin.signing_public_key,
                    pin.hpke_public_key,
                )
                .map_err(corrupt_sharing)?;
        }
    }
    state
        .restore_collection_epoch(stored_epoch)
        .map_err(corrupt_sharing)?;
    ensure!(
        state.generation() == stored_generation && state.commitment() == &stored_head,
        CatalogCorrupt,
        "sharing state contradicts its membership chain"
    );
    validate_pins(&state, &pins)?;
    load_grants(db, collection_id)?;
    Ok(Some(state))
}

/// Explicitly verifies or replaces one durable recipient key pin.
pub fn verify_recipient_keys(
    db: &mut CatalogDb,
    collection_id: &Id,
    identity_vault_id: Id,
    device_id: Id,
    signing_public_key: [u8; 32],
    hpke_public_key: [u8; 32],
) -> Result<CollectionMembershipState> {
    let current = load(db, collection_id)?.ok_or_else(|| {
        Error::new(
            ChurStatus::VaultIncomplete,
            "collection sharing is not provisioned",
        )
    })?;
    let mut candidate = current.clone();
    candidate.verify_recipient_keys(
        identity_vault_id,
        device_id,
        signing_public_key,
        hpke_public_key,
    )?;
    db.transaction(|transaction| {
        check_head(transaction, &current)?;
        upsert_pin(
            transaction,
            collection_id,
            &identity_vault_id,
            &device_id,
            candidate
                .recipient_pin(&identity_vault_id, &device_id)
                .ok_or_else(|| Error::new(ChurStatus::InternalFailure, "verified pin is absent"))?,
        )?;
        bump_generation(transaction)?;
        Ok(())
    })?;
    Ok(candidate)
}

/// Validates and stores one canonical collection grant atomically.
pub fn store_grant(
    db: &mut CatalogDb,
    grant: &CollectionGrant,
    sender_membership: &MembershipState,
) -> Result<GrantStoreOutcome> {
    let state = load(db, grant.collection_id())?.ok_or_else(|| {
        Error::new(
            ChurStatus::VaultIncomplete,
            "collection sharing is not provisioned",
        )
    })?;
    db.transaction(|transaction| {
        let outcome = project_grant(transaction, &state, grant, sender_membership)?;
        if outcome == GrantStoreOutcome::Stored {
            bump_generation(transaction)?;
        }
        Ok(outcome)
    })
}

/// Loads and validates all stored grants for one collection.
pub fn load_grants(db: &CatalogDb, collection_id: &Id) -> Result<Vec<CollectionGrant>> {
    let mut statement = db
        .connection()
        .prepare(
            "SELECT grant_id, recipient_identity_vault_id, recipient_device_id,
                    membership_generation, collection_epoch, record
               FROM sharing_grants WHERE collection_id = ?1 ORDER BY grant_id",
        )
        .map_err(|error| map_sqlite(error, "collection grants could not be prepared"))?;
    let rows = statement
        .query_map([collection_id.as_bytes().as_slice()], |row| {
            Ok((
                row.get::<_, Vec<u8>>(0)?,
                row.get::<_, Vec<u8>>(1)?,
                row.get::<_, Vec<u8>>(2)?,
                row.get::<_, i64>(3)?,
                row.get::<_, i64>(4)?,
                row.get::<_, Vec<u8>>(5)?,
            ))
        })
        .map_err(|error| map_sqlite(error, "collection grants could not be read"))?;
    let mut grants = Vec::new();
    for row in rows {
        let (grant_id, recipient_vault, recipient_device, generation, epoch, bytes) =
            row.map_err(|error| map_sqlite(error, "a collection grant could not be read"))?;
        let grant_id = crate::row::id(&grant_id, "a grant id is malformed")?;
        let recipient_vault =
            crate::row::id(&recipient_vault, "a grant recipient vault id is malformed")?;
        let recipient_device = crate::row::id(
            &recipient_device,
            "a grant recipient device id is malformed",
        )?;
        let generation = from_sqlite_integer(
            generation,
            "a grant collection membership generation is negative",
        )?;
        let epoch = from_sqlite_integer(epoch, "a grant collection epoch is negative")?;
        let grant = CollectionGrant::decode(&bytes).map_err(corrupt_sharing)?;
        ensure!(
            grant.grant_id() == &grant_id
                && grant.collection_id() == collection_id
                && grant.recipient_identity_vault_id() == &recipient_vault
                && grant.recipient_device_id() == &recipient_device
                && grant.collection_membership_generation() == generation
                && grant.collection_epoch() == epoch,
            CatalogCorrupt,
            "a collection grant projection contradicts its record"
        );
        grants.push(grant);
    }
    Ok(grants)
}

/// Loads one exact accepted collection-membership record.
pub fn membership_record_at(
    db: &CatalogDb,
    collection_id: &Id,
    generation: u64,
) -> Result<CollectionMembershipRecord> {
    let bytes: Vec<u8> = db
        .connection()
        .query_row(
            "SELECT record FROM sharing_membership_records
              WHERE collection_id = ?1 AND membership_generation = ?2",
            params![
                collection_id.as_bytes().as_slice(),
                as_sqlite_integer(
                    generation,
                    "the collection membership generation is too large"
                )?,
            ],
            |row| row.get(0),
        )
        .map_err(|error| map_sqlite(error, "collection membership record could not be read"))?;
    let record = CollectionMembershipRecord::decode(&bytes).map_err(corrupt_sharing)?;
    ensure!(
        record.collection_id() == collection_id
            && record.collection_membership_generation() == generation,
        CatalogCorrupt,
        "collection membership lookup contradicts its record"
    );
    Ok(record)
}

/// Projects one validated collection grant inside an existing transaction.
pub fn project_grant(
    transaction: &Transaction<'_>,
    current: &CollectionMembershipState,
    grant: &CollectionGrant,
    sender_membership: &MembershipState,
) -> Result<GrantStoreOutcome> {
    let encoded = grant.encode();
    let existing: Option<Vec<u8>> = transaction
        .query_row(
            "SELECT record FROM sharing_grants WHERE grant_id = ?1",
            [grant.grant_id().as_bytes().as_slice()],
            |row| row.get(0),
        )
        .optional()
        .map_err(|error| map_sqlite(error, "an existing collection grant could not be read"))?;
    if let Some(existing) = existing {
        ensure!(
            existing == encoded,
            Conflict,
            "a grant identifier names different canonical bytes"
        );
        return Ok(GrantStoreOutcome::Duplicate);
    }
    current.validate_grant(grant, sender_membership)?;
    check_head(transaction, current)?;
    transaction
        .execute(
            "INSERT INTO sharing_grants
                 (grant_id, collection_id, recipient_identity_vault_id,
                  recipient_device_id, membership_generation, collection_epoch, record)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            params![
                grant.grant_id().as_bytes().as_slice(),
                grant.collection_id().as_bytes().as_slice(),
                grant.recipient_identity_vault_id().as_bytes().as_slice(),
                grant.recipient_device_id().as_bytes().as_slice(),
                as_sqlite_integer(
                    grant.collection_membership_generation(),
                    "the grant collection membership generation is too large"
                )?,
                as_sqlite_integer(
                    grant.collection_epoch(),
                    "the grant collection epoch is too large"
                )?,
                encoded,
            ],
        )
        .map_err(|error| map_sqlite(error, "the collection grant could not be written"))?;
    Ok(GrantStoreOutcome::Stored)
}

fn load_pins(db: &CatalogDb, collection_id: &Id) -> Result<BTreeMap<(Id, Id), StoredPin>> {
    let mut statement = db
        .connection()
        .prepare(
            "SELECT recipient_identity_vault_id, recipient_device_id,
                    signing_public_key, hpke_public_key, verification
               FROM sharing_recipient_pins WHERE collection_id = ?1",
        )
        .map_err(|error| map_sqlite(error, "recipient pins could not be prepared"))?;
    let rows = statement
        .query_map([collection_id.as_bytes().as_slice()], |row| {
            Ok((
                row.get::<_, Vec<u8>>(0)?,
                row.get::<_, Vec<u8>>(1)?,
                row.get::<_, Vec<u8>>(2)?,
                row.get::<_, Vec<u8>>(3)?,
                row.get::<_, i64>(4)?,
            ))
        })
        .map_err(|error| map_sqlite(error, "recipient pins could not be read"))?;
    let mut pins = BTreeMap::new();
    for row in rows {
        let (identity, device, signing, hpke, verification) =
            row.map_err(|error| map_sqlite(error, "a recipient pin could not be read"))?;
        let identity = crate::row::id(&identity, "a recipient vault id is malformed")?;
        let device = crate::row::id(&device, "a recipient device id is malformed")?;
        let verification = match verification {
            TOFU => RecipientVerification::TrustOnFirstUse,
            VERIFIED => RecipientVerification::Verified,
            _ => bail!(CatalogCorrupt, "a recipient verification state is invalid"),
        };
        ensure!(
            pins.insert(
                (identity, device),
                StoredPin {
                    signing_public_key: fixed(&signing, "a recipient signing key is malformed")?,
                    hpke_public_key: fixed(&hpke, "a recipient HPKE key is malformed")?,
                    verification,
                },
            )
            .is_none(),
            CatalogCorrupt,
            "recipient pins contain a duplicate"
        );
    }
    Ok(pins)
}

fn restore_pin_for_record(
    state: &mut CollectionMembershipState,
    pins: &BTreeMap<(Id, Id), StoredPin>,
    record: &CollectionMembershipRecord,
) -> Result<()> {
    let recipient = (
        *record.recipient_identity_vault_id(),
        *record.recipient_device_id(),
    );
    let stored = pins.get(&recipient).ok_or_else(|| {
        Error::new(
            ChurStatus::CatalogCorrupt,
            "a collection member has no recipient pin",
        )
    })?;
    let current = state.recipient_pin(&recipient.0, &recipient.1);
    let key_change = current.is_some_and(|pin| {
        pin.signing_public_key() != record.recipient_signing_public_key()
            || pin.hpke_public_key() != record.recipient_hpke_public_key()
    });
    let final_verified_key = current.is_none()
        && stored.verification == RecipientVerification::Verified
        && stored.signing_public_key == *record.recipient_signing_public_key()
        && stored.hpke_public_key == *record.recipient_hpke_public_key();
    let verification_upgrade = current.is_some_and(|pin| {
        pin.verification() == RecipientVerification::TrustOnFirstUse
            && stored.verification == RecipientVerification::Verified
            && stored.signing_public_key == *record.recipient_signing_public_key()
            && stored.hpke_public_key == *record.recipient_hpke_public_key()
    });
    if key_change || final_verified_key || verification_upgrade {
        state
            .verify_recipient_keys(
                recipient.0,
                recipient.1,
                *record.recipient_signing_public_key(),
                *record.recipient_hpke_public_key(),
            )
            .map_err(corrupt_sharing)?;
    }
    Ok(())
}

fn validate_pins(
    state: &CollectionMembershipState,
    pins: &BTreeMap<(Id, Id), StoredPin>,
) -> Result<()> {
    for ((identity, device), stored) in pins {
        let restored = state.recipient_pin(identity, device).ok_or_else(|| {
            Error::new(
                ChurStatus::CatalogCorrupt,
                "a stored recipient pin was not restored",
            )
        })?;
        ensure!(
            restored.signing_public_key() == &stored.signing_public_key
                && restored.hpke_public_key() == &stored.hpke_public_key
                && restored.verification() == stored.verification,
            CatalogCorrupt,
            "a recipient pin contradicts the membership chain"
        );
    }
    Ok(())
}

fn upsert_pin(
    transaction: &Transaction<'_>,
    collection_id: &Id,
    identity_vault_id: &Id,
    device_id: &Id,
    pin: &RecipientPin,
) -> Result<()> {
    transaction
        .execute(
            "INSERT INTO sharing_recipient_pins
                 (collection_id, recipient_identity_vault_id, recipient_device_id,
                  signing_public_key, hpke_public_key, verification)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)
             ON CONFLICT(collection_id, recipient_identity_vault_id, recipient_device_id)
             DO UPDATE SET signing_public_key = excluded.signing_public_key,
                           hpke_public_key = excluded.hpke_public_key,
                           verification = excluded.verification",
            params![
                collection_id.as_bytes().as_slice(),
                identity_vault_id.as_bytes().as_slice(),
                device_id.as_bytes().as_slice(),
                pin.signing_public_key().as_slice(),
                pin.hpke_public_key().as_slice(),
                match pin.verification() {
                    RecipientVerification::TrustOnFirstUse => TOFU,
                    RecipientVerification::Verified => VERIFIED,
                },
            ],
        )
        .map_err(|error| map_sqlite(error, "the recipient pin could not be written"))?;
    Ok(())
}

fn check_head(transaction: &Transaction<'_>, current: &CollectionMembershipState) -> Result<()> {
    let present: i64 = transaction
        .query_row(
            "SELECT count(*) FROM sharing_collections
              WHERE collection_id = ?1 AND membership_generation = ?2
                AND membership_commitment = ?3 AND current_epoch = ?4",
            params![
                current.collection_id().as_bytes().as_slice(),
                as_sqlite_integer(
                    current.generation(),
                    "the collection membership generation is too large"
                )?,
                current.commitment().as_slice(),
                as_sqlite_integer(
                    current.collection_epoch(),
                    "the collection epoch is too large"
                )?,
            ],
            |row| row.get(0),
        )
        .map_err(|error| {
            map_sqlite(error, "the collection membership head could not be checked")
        })?;
    ensure!(
        present == 1,
        Conflict,
        "the collection membership head changed concurrently"
    );
    Ok(())
}

fn fixed(bytes: &[u8], context: &'static str) -> Result<[u8; 32]> {
    bytes
        .try_into()
        .map_err(|_| Error::new(ChurStatus::CatalogCorrupt, context))
}

fn corrupt_sharing(_: Error) -> Error {
    Error::new(
        ChurStatus::CatalogCorrupt,
        "stored collection sharing state does not authenticate",
    )
}

#[cfg(test)]
mod tests {
    #![allow(clippy::expect_used, clippy::panic)]

    use super::*;
    use crate::{
        db::{CatalogKey, CatalogLocation},
        schema::open_at_current_version,
    };
    use chur_crypto::{Key, random};
    use chur_sync_protocol::{
        collection_membership::{CollectionMembershipAction, RecipientVerification},
        grant::PermissionProfile,
        identity::DeviceIdentity,
        membership::EnrollmentRecord,
        operation::DeviceSigningKey,
        state::MembershipState,
    };

    fn id(byte: u8) -> Id {
        Id::new([byte; 16]).expect("id")
    }

    fn catalog() -> CatalogDb {
        let root: Key = random::secret::<32>().expect("root");
        let key = CatalogKey::derive(&root, &id(1)).expect("key");
        let mut db = CatalogDb::open(&CatalogLocation::Memory, &key).expect("catalog");
        open_at_current_version(&mut db, 1).expect("schema");
        db
    }

    #[test]
    fn accepted_membership_and_tofu_pin_replay_after_reopen() {
        let mut db = catalog();
        let source_key = DeviceSigningKey::from_seed([1; 32]);
        let source_record =
            EnrollmentRecord::initial(id(1), id(2), source_key.verifying_key(), [3; 32])
                .expect("source enrollment")
                .sign(&source_key);
        let source = MembershipState::bootstrap(&source_record).expect("source membership");
        provision(&mut db, id(1), id(4), 7).expect("provision sharing");
        let record = CollectionMembershipRecord::new(
            id(1),
            id(4),
            1,
            [0; 32],
            CollectionMembershipAction::Upsert(PermissionProfile::Read),
            id(5),
            id(6),
            [7; 32],
            [8; 32],
            7,
            id(1),
            id(2),
            1,
            1,
        )
        .expect("membership")
        .sign(&source_key);

        let (accepted, outcome) =
            accept_membership(&mut db, &record, &source).expect("accept membership");
        assert!(outcome == CollectionMembershipOutcome::Applied);
        assert_eq!(accepted.generation(), 1);

        let restored = load(&db, &id(4)).expect("load").expect("sharing state");
        assert_eq!(restored.commitment(), &record.commitment());
        assert!(
            restored.recipient_verification(&id(5), &id(6))
                == Some(RecipientVerification::TrustOnFirstUse)
        );

        db.connection()
            .execute(
                "UPDATE sharing_membership_records SET commitment = ?1",
                [[9u8; 32]],
            )
            .expect("tamper projection");
        let Err(error) = load(&db, &id(4)) else {
            panic!("corrupt projection loaded")
        };
        assert!(error.status() == ChurStatus::CatalogCorrupt);
    }

    #[test]
    fn verified_key_replacement_and_revocation_replay() {
        let mut db = catalog();
        let source_key = DeviceSigningKey::from_seed([1; 32]);
        let source_record =
            EnrollmentRecord::initial(id(1), id(2), source_key.verifying_key(), [3; 32])
                .expect("source enrollment")
                .sign(&source_key);
        let source = MembershipState::bootstrap(&source_record).expect("source membership");
        db.connection()
            .execute(
                "INSERT INTO collections VALUES (?1, 7, 1, 1, 1)",
                [id(4).as_bytes().as_slice()],
            )
            .expect("collection");
        let initial = provision(&mut db, id(1), id(4), 7).expect("provision sharing");
        let first = CollectionMembershipRecord::new(
            id(1),
            id(4),
            1,
            *initial.commitment(),
            CollectionMembershipAction::Upsert(PermissionProfile::Contribute),
            id(5),
            id(6),
            [7; 32],
            [8; 32],
            7,
            id(1),
            id(2),
            1,
            1,
        )
        .expect("first membership")
        .sign(&source_key);
        accept_membership(&mut db, &first, &source).expect("accept first membership");

        verify_recipient_keys(&mut db, &id(4), id(5), id(6), [10; 32], [11; 32])
            .expect("verify replacement");
        let replacement = CollectionMembershipRecord::new(
            id(1),
            id(4),
            2,
            first.commitment(),
            CollectionMembershipAction::Upsert(PermissionProfile::ManageMembers),
            id(5),
            id(6),
            [10; 32],
            [11; 32],
            7,
            id(1),
            id(2),
            1,
            2,
        )
        .expect("replacement")
        .sign(&source_key);
        accept_membership(&mut db, &replacement, &source).expect("accept replacement");
        let revoke = CollectionMembershipRecord::new(
            id(1),
            id(4),
            3,
            replacement.commitment(),
            CollectionMembershipAction::Revoke,
            id(5),
            id(6),
            [10; 32],
            [11; 32],
            8,
            id(1),
            id(2),
            1,
            3,
        )
        .expect("revocation")
        .sign(&source_key);
        accept_membership(&mut db, &revoke, &source).expect("accept revocation");

        let restored = load(&db, &id(4)).expect("load").expect("sharing state");
        assert_eq!(restored.generation(), 3);
        assert_eq!(restored.collection_epoch(), 8);
        assert!(
            restored.recipient_verification(&id(5), &id(6))
                == Some(RecipientVerification::Verified)
        );
        assert!(!restored.member(&id(5), &id(6)).expect("member").is_active());

        db.connection()
            .execute(
                "UPDATE sharing_collections SET current_epoch = 9 WHERE collection_id = ?1",
                [id(4).as_bytes().as_slice()],
            )
            .expect("tamper epoch");
        let Err(error) = load(&db, &id(4)) else {
            panic!("divergent sharing epoch loaded")
        };
        assert!(error.status() == ChurStatus::CatalogCorrupt);
    }

    #[test]
    fn grant_storage_is_idempotent_and_rejects_an_identifier_conflict() {
        let mut db = catalog();
        let source_key = DeviceSigningKey::from_seed([1; 32]);
        let source_record =
            EnrollmentRecord::initial(id(1), id(2), source_key.verifying_key(), [3; 32])
                .expect("source enrollment")
                .sign(&source_key);
        let source = MembershipState::bootstrap(&source_record).expect("source membership");
        provision(&mut db, id(1), id(4), 7).expect("provision sharing");
        let recipient = DeviceIdentity::from_seeds([5; 32], [6; 32]);
        let membership = CollectionMembershipRecord::new(
            id(1),
            id(4),
            1,
            [0; 32],
            CollectionMembershipAction::Upsert(PermissionProfile::Read),
            id(7),
            id(8),
            recipient.signing_public_key(),
            recipient.hpke_public_key(),
            7,
            id(1),
            id(2),
            1,
            1,
        )
        .expect("membership")
        .sign(&source_key);
        accept_membership(&mut db, &membership, &source).expect("accept membership");
        let collection_key = Key::new([9; 32]);
        let grant = CollectionGrant::seal(
            id(10),
            id(1),
            id(4),
            7,
            1,
            id(7),
            id(8),
            &recipient.hpke_public_key(),
            id(2),
            PermissionProfile::Read,
            1,
            2,
            &collection_key,
            &source_key,
        )
        .expect("grant");

        assert!(store_grant(&mut db, &grant, &source).expect("store") == GrantStoreOutcome::Stored);
        assert!(
            store_grant(&mut db, &grant, &source).expect("replay") == GrantStoreOutcome::Duplicate
        );
        assert!(load_grants(&db, &id(4)).is_ok_and(|grants| grants == [grant.clone()]));

        let conflicting = CollectionGrant::seal(
            id(10),
            id(1),
            id(4),
            7,
            1,
            id(7),
            id(8),
            &recipient.hpke_public_key(),
            id(2),
            PermissionProfile::Read,
            1,
            3,
            &collection_key,
            &source_key,
        )
        .expect("conflicting grant");
        let Err(error) = store_grant(&mut db, &conflicting, &source) else {
            panic!("conflicting grant id was accepted")
        };
        assert!(error.status() == ChurStatus::Conflict);

        db.connection()
            .execute("UPDATE sharing_grants SET collection_epoch = 8", [])
            .expect("tamper grant projection");
        let Err(error) = load_grants(&db, &id(4)) else {
            panic!("corrupt grant projection loaded")
        };
        assert!(error.status() == ChurStatus::CatalogCorrupt);
    }
}
