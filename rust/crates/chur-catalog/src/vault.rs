//! Vault provisioning, unlock, and lock.
//!
//! `docs/security/PROVISIONING.md` §3 fixes the creation order,
//! `docs/format/VAULT_DESCRIPTOR_V1.md` §9 the byte-level transaction inside
//! it, `docs/security/KEY_SLOTS.md` §8 the unlock flow and its constant work,
//! and `docs/security/PLAINTEXT_LIFECYCLE.md` §8 the lock sequence.
//!
//! The session lives here because ADR-0004 ties it to the catalog: the catalog
//! key exists only in an unlocked session, and lock closes the database before
//! zeroizing the key. Nothing above this module ever holds a root secret.

use chur_core::{ChurStatus, Error, Id, Result, bail, ensure};
use chur_crypto::{
    Key, Nonce, commit,
    password::{self, Argon2Params},
    random, recovery,
};
use chur_format::constants::{SlotType, VaultState};
use chur_format::descriptor::{
    CatalogDescriptor, KeySlotDescriptor, ObjectStoreDescriptor, VaultDescriptor,
};
use chur_format::slot::{AppleKeychainSlotBody, PasswordSlotBody, RecoverySlotBody, SlotBinding};

use crate::db::{CatalogDb, CatalogKey, CatalogLocation};
use crate::paths::{RegistryName, VaultRoot};
use crate::schema;

/// The salt length a v1 writer produces, `KEY_SLOT_BODIES_V1.md` §8.
const SALT_LEN: usize = 16;

/// The number of Argon2id derivations one password attempt runs, §8.
///
/// It is a constant and not a candidate count. A list shorter than two is
/// padded with dummy candidates, so an attempt costs the same whether it
/// succeeds, fails, or matches a sibling identity.
const PASSWORD_DERIVATIONS: usize = 2;

/// A vault being created, `docs/security/PROVISIONING.md` §3.
///
/// The type exists because the flow has a middle: the recovery slot is offered
/// at step 5, after the password slot is verified at step 4 and before the
/// descriptor reaches `ACTIVE` at step 6. A single `create` function would have
/// to either skip the offer or take a callback, and both hide the ordering the
/// specification fixes.
///
/// Dropping it without [`VaultCreation::activate`] leaves the temporary
/// descriptor and the vault directory, which [`abandon`] and
/// [`VaultRoot::sweep_temporary`] remove. Nothing openable exists in the
/// meantime, because §11 enumerates only installed `.vd` entries.
pub struct VaultCreation {
    root_dir: VaultRoot,
    root_secret: Key,
    descriptor: VaultDescriptor,
    entry_name: RegistryName,
    catalog: CatalogDb,
}

/// An unlocked vault session.
///
/// Every handle above this one captures the session generation, so lock makes
/// them all fail in one step, `docs/interop/FFI_CONTRACT.md` §4.
pub struct Session {
    root_dir: VaultRoot,
    root_secret: Key,
    descriptor: VaultDescriptor,
    entry_name: RegistryName,
    catalog: Option<CatalogDb>,
}

/// Creates a vault, running steps 3 and 4 of `PROVISIONING.md` §3.
///
/// Step 3 is the transaction of `VAULT_DESCRIPTOR_V1.md` §9, whose first step
/// generates the `VaultRootSecret` from the OS CSPRNG. No password, device
/// identifier, or other user input contributes to it; the password derives a
/// KEK only, as SEC-001 requires.
///
/// Step 4 verifies the password slot by unwrapping the committed root with the
/// entered password, before the descriptor reaches `ACTIVE`. The verification
/// reads the encoded slot back rather than reusing the value in memory, so it
/// proves the bytes that were written are the bytes that open.
pub fn create(root_dir: &VaultRoot, password: &[u8], now_ms: u64) -> Result<VaultCreation> {
    create_with_params(root_dir, password, Argon2Params::v1_default(), now_ms)
}

/// Creates a vault under a calibrated Argon2id profile,
/// `docs/security/PASSWORD_PROFILE.md` §6.
///
/// The profile is validated before any work, so a value below the frozen floor
/// writes no slot.
pub fn create_with_params(
    root_dir: &VaultRoot,
    password: &[u8],
    params: Argon2Params,
    now_ms: u64,
) -> Result<VaultCreation> {
    // §6 of PASSWORD_PROFILE: a creation that cannot allocate the floor must
    // not write a slot.
    password::check_memory_available(params)?;
    let canonical = password::canonical_bytes(password)?;

    // §11: the registry holds at most two entries.
    let existing = root_dir.registry_names()?;
    ensure!(
        existing.len() < crate::paths::REGISTRY_MAX,
        ResourceLimitExceeded,
        "the registry already holds the two identities §11 admits"
    );

    // §9 step 1: a random vault identity and root secret.
    let vault_id = random::id()?;
    let root_secret: Key = random::secret::<32>()?;
    let object_store_id = random::id()?;
    let catalog_path_id = random::id()?;
    root_dir.prepare(&object_store_id)?;

    // §9 step 2: the encrypted catalog, created and keyed.
    let catalog_key = CatalogKey::derive(&root_secret, &vault_id)?;
    let catalog_path = root_dir.catalog(&object_store_id, &catalog_path_id);
    let mut catalog = CatalogDb::open(&CatalogLocation::File(&catalog_path), &catalog_key)?;
    schema::open_at_current_version(&mut catalog, now_ms)?;
    let catalog_generation = schema::generation(&catalog)?;
    let header_commitment = catalog_header_commitment(&catalog_path)?;

    // §9 step 4: at least one key slot. ADR-0032 makes it a password slot.
    let slot = seal_password_slot(&vault_id, &canonical, params, &root_secret, 1)?;

    // §9 step 5: descriptor generation 0 as INITIALIZING, written to a name
    // §11 does not enumerate.
    let mut descriptor = VaultDescriptor {
        vault_id,
        descriptor_generation: 0,
        state: VaultState::Initializing,
        catalog: CatalogDescriptor {
            opaque_catalog_path_id: catalog_path_id,
            catalog_generation,
            catalog_header_commitment: header_commitment,
        },
        object_store: ObjectStoreDescriptor::v1(object_store_id),
        key_slots: vec![slot],
        migration: None,
    };
    let entry_name = RegistryName::random()?;
    write_temporary(root_dir, &entry_name, &descriptor, &root_secret)?;

    // §9 step 7 and PROVISIONING step 4: verify the slot from the bytes that
    // were written, not from the value still in memory.
    let written = std::fs::read(root_dir.registry_temporary(&entry_name))
        .map_err(|_| chur_core::err!(IoFailure, "the descriptor could not be read back"))?;
    let parsed = VaultDescriptor::authenticate(&written, Some(&root_secret))?;
    let verified = open_password_slot(&parsed, &canonical)?;
    ensure!(
        verified.expose() == root_secret.expose(),
        VaultIncomplete,
        "the written password slot did not return the committed root"
    );

    descriptor.descriptor_generation = 1;
    Ok(VaultCreation {
        root_dir: root_dir.clone(),
        root_secret,
        descriptor,
        entry_name,
        catalog,
    })
}

impl VaultCreation {
    /// The vault identity, which the caller needs for nothing but diagnostics.
    #[must_use]
    pub fn vault_id(&self) -> Id {
        self.descriptor.vault_id
    }

    /// Adds the recovery slot of `PROVISIONING.md` §4, step 5 of §3.
    ///
    /// It returns the recovery secret so the caller can render the presentation
    /// of `RECOVERY.md` §2. The value is never stored and is never shown again
    /// afterwards; a user who loses it rotates the slot under §8 there.
    pub fn add_recovery_slot(&mut self) -> Result<Key> {
        let secret: Key = random::secret::<32>()?;
        let slot = seal_recovery_slot(&self.descriptor.vault_id, &secret, &self.root_secret, 1)?;
        self.descriptor.key_slots.push(slot);
        Ok(secret)
    }

    /// Reaches `ACTIVE` and opens the session, step 6 of §3.
    ///
    /// The descriptor is written at generation 1 with state `ACTIVE` and
    /// installed by an atomic rename, which is §9's last step. Until that rename
    /// no `.vd` entry exists, so a crash at any earlier point leaves no openable
    /// vault.
    pub fn activate(mut self) -> Result<Session> {
        self.descriptor.state = VaultState::Active;
        let bytes = self.descriptor.encode(&self.root_secret)?;
        let temporary = self.root_dir.registry_temporary(&self.entry_name);
        let installed = self.root_dir.registry_entry(&self.entry_name);
        write_durably(&temporary, &bytes)?;
        std::fs::rename(&temporary, &installed)
            .map_err(|_| chur_core::err!(IoFailure, "the descriptor could not be installed"))?;
        sync_directory(&self.root_dir.registry())?;
        Ok(Session {
            root_dir: self.root_dir,
            root_secret: self.root_secret,
            descriptor: self.descriptor,
            entry_name: self.entry_name,
            catalog: Some(self.catalog),
        })
    }

    /// Removes everything the interrupted creation wrote.
    ///
    /// §9 requires a crash before `ACTIVE` to be recoverable or removable
    /// without exposing a partially trusted vault. Nothing here was ever
    /// openable, so removal is the whole recovery.
    pub fn abandon(self) -> Result<()> {
        let store = self.descriptor.object_store.opaque_root_path_id;
        let temporary = self.root_dir.registry_temporary(&self.entry_name);
        let directory = self.root_dir.vault(&store);
        drop(self.catalog);
        let _ = std::fs::remove_file(temporary);
        std::fs::remove_dir_all(&directory)
            .map_err(|_| chur_core::err!(IoFailure, "the abandoned vault could not be removed"))?;
        Ok(())
    }
}

/// One password unlock attempt over the whole registry, `KEY_SLOTS.md` §8.
///
/// The attempt runs exactly [`PASSWORD_DERIVATIONS`] Argon2id derivations
/// whatever the device holds. Argon2 output is salt-bound and every slot has
/// its own random salt, so one derivation can never be tried against a second
/// slot; a constant candidate count, not a reused derivation, is what removes
/// the cost signal.
///
/// Every candidate, real or dummy, runs to completion before any result is
/// used, so peak Argon2 memory is one profile allocation and the attempt costs
/// the same whether it succeeds, fails, or matches a sibling identity.
pub fn unlock_with_password(root_dir: &VaultRoot, password: &[u8], now_ms: u64) -> Result<Session> {
    let canonical = password::canonical_bytes(password)?;
    let candidates = password_candidates(root_dir)?;

    // §8: the memory the profile requires is checked once, before the first
    // candidate; a device that cannot allocate it runs no candidate at all.
    let profile = candidates
        .first()
        .map_or_else(Argon2Params::v1_default, |candidate| candidate.params);
    password::check_memory_available(profile)?;

    let mut opened: Option<(usize, Key)> = None;
    for index in 0..PASSWORD_DERIVATIONS {
        match candidates.get(index) {
            Some(candidate) => {
                if let Ok(root) = candidate.body.open(&candidate.binding, &canonical)
                    && opened.is_none()
                {
                    opened = Some((index, root));
                }
            }
            None => {
                // A dummy candidate runs the parameters of the first real
                // candidate over a fresh random 16-byte salt and discards the
                // output, §8.
                let salt = random::array::<SALT_LEN>()?;
                let _ = password::derive_kek(&canonical, &salt, profile);
            }
        }
    }

    match opened {
        Some((index, root)) => {
            let candidate = &candidates[index];
            finish_unlock(
                root_dir,
                &candidate.entry_name,
                &candidate.bytes,
                root,
                now_ms,
            )
        }
        None => {
            // §8 step 5 still runs over a random substitute root, so an invalid
            // credential and a credential valid for a sibling vault cost the
            // same work and return the same error.
            let bytes = candidates
                .first()
                .map(|candidate| candidate.bytes.clone())
                .unwrap_or_default();
            let _ = VaultDescriptor::authenticate(&bytes, None);
            bail!(
                AuthenticationFailed,
                "no candidate slot returned a root that authenticated a descriptor"
            )
        }
    }
}

/// Unlocks with the recovery phrase of `RECOVERY.md`.
///
/// Recovery runs no Argon2id: `KEY_SLOTS.md` §6 says the mnemonic is a
/// presentation encoding of 32 canonical random bytes, not a low-entropy
/// password, so its KEK comes from HKDF alone.
pub fn unlock_with_recovery(root_dir: &VaultRoot, phrase: &str, now_ms: u64) -> Result<Session> {
    let secret = recovery::decode(phrase)?;
    unlock_with_slot(root_dir, SlotType::Recovery, &secret, now_ms)
}

/// Unlocks with the `DeviceUnlockSecret` an Apple Keychain returned.
pub fn unlock_with_apple_keychain(
    root_dir: &VaultRoot,
    device_unlock_secret: &Key,
    now_ms: u64,
) -> Result<Session> {
    unlock_with_slot(
        root_dir,
        SlotType::AppleKeychain,
        device_unlock_secret,
        now_ms,
    )
}

fn unlock_with_slot(
    root_dir: &VaultRoot,
    slot_type: SlotType,
    secret: &Key,
    now_ms: u64,
) -> Result<Session> {
    for name in root_dir.registry_names()? {
        let bytes = read_entry(root_dir, &name)?;
        let Ok(descriptor) = VaultDescriptor::parse(&bytes) else {
            // §11: an entry that fails the parser limits is skipped before any
            // credential is used, and its failure is attributed to no credential.
            continue;
        };
        for entry in descriptor
            .key_slots
            .iter()
            .filter(|entry| entry.slot_type == slot_type)
        {
            let binding = entry.binding(descriptor.vault_id);
            let opened = match slot_type {
                SlotType::Recovery => RecoverySlotBody::decode(&entry.slot_body)
                    .and_then(|body| body.open(&binding, secret)),
                SlotType::AppleKeychain => AppleKeychainSlotBody::decode(&entry.slot_body)
                    .and_then(|body| body.open(&binding, secret)),
                _ => Err(Error::new(
                    ChurStatus::UnsupportedSuite,
                    "that slot family is not an unlock method in v1",
                )),
            };
            if let Ok(root) = opened {
                return finish_unlock(root_dir, &name, &bytes, root, now_ms);
            }
        }
    }
    bail!(
        AuthenticationFailed,
        "no candidate slot returned a root that authenticated a descriptor"
    )
}

/// Steps 5 to 7 of `KEY_SLOTS.md` §8.
fn finish_unlock(
    root_dir: &VaultRoot,
    entry_name: &RegistryName,
    bytes: &[u8],
    root_secret: Key,
    now_ms: u64,
) -> Result<Session> {
    let descriptor = VaultDescriptor::authenticate(bytes, Some(&root_secret))?;
    ensure!(
        descriptor.state == VaultState::Active,
        VaultIncomplete,
        "the descriptor is not in the only ordinarily openable state"
    );
    let catalog_key = CatalogKey::derive(&root_secret, &descriptor.vault_id)?;
    let path = root_dir.catalog(
        &descriptor.object_store.opaque_root_path_id,
        &descriptor.catalog.opaque_catalog_path_id,
    );
    // §5 of the descriptor specification: the descriptor commits to the catalog
    // header, so a substituted catalog file fails here rather than at the first
    // query that reads a row from it.
    let commitment = catalog_header_commitment(&path)?;
    ensure!(
        chur_crypto::secret::constant_time_eq(
            &commitment,
            &descriptor.catalog.catalog_header_commitment
        ),
        VaultCorrupt,
        "the catalog file is not the one this descriptor commits to"
    );
    let mut catalog = CatalogDb::open(&CatalogLocation::File(&path), &catalog_key)?;
    let version = schema::open_at_current_version(&mut catalog, now_ms)?;
    ensure!(
        version == chur_format::constants::CATALOG_FORMAT_VERSION_V1,
        CatalogCorrupt,
        "the catalog format version disagrees with the descriptor"
    );
    Ok(Session {
        root_dir: root_dir.clone(),
        root_secret,
        descriptor,
        entry_name: entry_name.clone(),
        catalog: Some(catalog),
    })
}

impl Session {
    /// The catalog, while the session is unlocked.
    pub fn catalog(&mut self) -> Result<&mut CatalogDb> {
        self.catalog
            .as_mut()
            .ok_or_else(|| chur_core::err!(VaultLocked, "the session is locked"))
    }

    /// The catalog for reading only.
    pub fn catalog_ref(&self) -> Result<&CatalogDb> {
        self.catalog
            .as_ref()
            .ok_or_else(|| chur_core::err!(VaultLocked, "the session is locked"))
    }

    /// The vault identity.
    #[must_use]
    pub fn vault_id(&self) -> Id {
        self.descriptor.vault_id
    }

    /// The opaque object-store root identifier, which resolves every path.
    #[must_use]
    pub fn object_store_id(&self) -> Id {
        self.descriptor.object_store.opaque_root_path_id
    }

    /// The storage root.
    #[must_use]
    pub fn root_dir(&self) -> &VaultRoot {
        &self.root_dir
    }

    /// The root secret, for the derivations only this crate and `chur-media`
    /// perform.
    ///
    /// `docs/interop/FFI_CONTRACT.md` §12 keeps object, collection, and root
    /// keys away from application feature code; nothing above `chur-ffi` can
    /// reach this, because `Session` is never handed out across the boundary.
    #[must_use]
    pub fn root_secret(&self) -> &Key {
        &self.root_secret
    }

    /// Adds a recovery slot to an active vault, `RECOVERY.md` §8.
    pub fn add_recovery_slot(&mut self) -> Result<Key> {
        let secret: Key = random::secret::<32>()?;
        let generation = self.next_slot_generation(SlotType::Recovery);
        let slot = seal_recovery_slot(
            &self.descriptor.vault_id,
            &secret,
            &self.root_secret,
            generation,
        )?;
        self.commit_slots(|slots| slots.push(slot))?;
        Ok(secret)
    }

    /// Adds the Apple Keychain slot of `KEY_SLOTS.md` §5, step 7 of
    /// `PROVISIONING.md` §3.
    ///
    /// The `DeviceUnlockSecret` is generated here and returned so the platform
    /// can store it as a `ThisDeviceOnly` Keychain item. Rust wraps the root
    /// under a KEK derived from it, so the Keychain never holds vault
    /// ciphertext and the envelope stays test-vectorable at the Rust layer.
    pub fn add_apple_keychain_slot(&mut self, keychain_item_id: Id) -> Result<Key> {
        let secret: Key = random::secret::<32>()?;
        let generation = self.next_slot_generation(SlotType::AppleKeychain);
        let slot_id = random::id()?;
        let binding = SlotBinding::v1(
            self.descriptor.vault_id,
            slot_id,
            SlotType::AppleKeychain,
            generation,
        );
        let body = AppleKeychainSlotBody::seal(
            &binding,
            &secret,
            keychain_item_id,
            Nonce::random()?,
            &self.root_secret,
        )?;
        let slot =
            KeySlotDescriptor::v1(slot_id, SlotType::AppleKeychain, generation, body.encode())?;
        self.commit_slots(|slots| slots.push(slot))?;
        Ok(secret)
    }

    /// Removes one slot, `KEY_SLOTS.md` §9.
    ///
    /// At least one verified recovery path must remain through every update, so
    /// removing the last portable slot is refused. That is what makes a device
    /// slot never the only slot, `PROVISIONING.md` §5.
    pub fn remove_slot(&mut self, slot_id: &Id) -> Result<()> {
        let remaining: Vec<&KeySlotDescriptor> = self
            .descriptor
            .key_slots
            .iter()
            .filter(|entry| entry.slot_id != *slot_id)
            .collect();
        ensure!(
            remaining.len() < self.descriptor.key_slots.len(),
            NotFound,
            "no slot carries that id"
        );
        let portable = remaining
            .iter()
            .any(|entry| matches!(entry.slot_type, SlotType::Password | SlotType::Recovery));
        ensure!(
            portable,
            Conflict,
            "removing the last portable slot would leave no verified recovery path"
        );
        let slot_id = *slot_id;
        self.commit_slots(|slots| slots.retain(|entry| entry.slot_id != slot_id))
    }

    /// Replaces the password slot, `KEY_SLOTS.md` §3 and §9.
    ///
    /// The replacement is created and verified before the old slot goes, which
    /// is why the whole change is one descriptor generation: an intermediate
    /// generation carrying neither slot would be a vault nobody can open.
    pub fn replace_password(&mut self, password: &[u8], params: Argon2Params) -> Result<()> {
        password::check_memory_available(params)?;
        let canonical = password::canonical_bytes(password)?;
        let generation = self.next_slot_generation(SlotType::Password);
        let slot = seal_password_slot(
            &self.descriptor.vault_id,
            &canonical,
            params,
            &self.root_secret,
            generation,
        )?;
        let binding = slot.binding(self.descriptor.vault_id);
        let verified = PasswordSlotBody::decode(&slot.slot_body)?.open(&binding, &canonical)?;
        ensure!(
            verified.expose() == self.root_secret.expose(),
            VaultIncomplete,
            "the replacement password slot did not return the committed root"
        );
        self.commit_slots(|slots| {
            slots.retain(|entry| entry.slot_type != SlotType::Password);
            slots.push(slot);
        })
    }

    /// The lock sequence of `PLAINTEXT_LIFECYCLE.md` §8.
    ///
    /// Steps 1 to 4 and 7 belong to the caller: the session generation and the
    /// decoded caches live above this crate. Steps 5, 6, and 8 are here, and
    /// their order is the point. The catalog closes before the key is dropped,
    /// because a connection that outlived the key would still hold decrypted
    /// pages.
    pub fn lock(&mut self) -> Result<()> {
        if let Some(catalog) = self.catalog.take() {
            catalog.close()?;
        }
        // Step 8: every scratch entry, whatever its journal state.
        let scratch = self.root_dir.scratch(&self.object_store_id());
        if scratch.exists() {
            std::fs::remove_dir_all(&scratch).map_err(|_| {
                chur_core::err!(IoFailure, "the scratch directory could not be cleared")
            })?;
            std::fs::create_dir_all(&scratch).map_err(|_| {
                chur_core::err!(IoFailure, "the scratch directory could not be recreated")
            })?;
        }
        // Step 6: the root is zeroized when this session drops, which the
        // caller does immediately after locking. Overwriting it here would
        // leave a `Session` whose `root_secret` is a valid-looking zero key.
        Ok(())
    }

    /// The key slots this vault carries, for the settings screen.
    ///
    /// It returns only the public triple `KEY_SLOTS.md` §2 calls common: the
    /// identity, the family, and the generation. A slot body carries the
    /// wrapped root and never leaves this crate.
    #[must_use]
    pub fn slots(&self) -> Vec<(Id, SlotType, u64)> {
        self.descriptor
            .key_slots
            .iter()
            .map(|entry| (entry.slot_id, entry.slot_type, entry.slot_generation))
            .collect()
    }

    /// Whether the session still holds an open catalog.
    #[must_use]
    pub fn is_unlocked(&self) -> bool {
        self.catalog.is_some()
    }

    fn next_slot_generation(&self, slot_type: SlotType) -> u64 {
        self.descriptor
            .key_slots
            .iter()
            .filter(|entry| entry.slot_type == slot_type)
            .map(|entry| entry.slot_generation)
            .max()
            .map_or(1, |current| current + 1)
    }

    /// Runs the slot transaction of `KEY_SLOTS.md` §9.
    ///
    /// Write the new descriptor to a temporary name, fsync it, read it back and
    /// verify it, then install it by an atomic rename. A crash at any point
    /// leaves the previous descriptor installed, which is the one the vault was
    /// already openable with.
    fn commit_slots(&mut self, change: impl FnOnce(&mut Vec<KeySlotDescriptor>)) -> Result<()> {
        let mut candidate = self.descriptor.clone();
        change(&mut candidate.key_slots);
        candidate.descriptor_generation = self
            .descriptor
            .descriptor_generation
            .checked_add(1)
            .ok_or_else(|| {
                chur_core::err!(VaultCorrupt, "the descriptor generation has no successor")
            })?;
        write_temporary(
            &self.root_dir,
            &self.entry_name,
            &candidate,
            &self.root_secret,
        )?;
        let temporary = self.root_dir.registry_temporary(&self.entry_name);
        let written = std::fs::read(&temporary)
            .map_err(|_| chur_core::err!(IoFailure, "the descriptor could not be read back"))?;
        let verified = VaultDescriptor::authenticate(&written, Some(&self.root_secret))?;
        ensure!(
            verified.descriptor_generation == candidate.descriptor_generation,
            VaultCorrupt,
            "the written descriptor is not the one that was built"
        );
        std::fs::rename(&temporary, self.root_dir.registry_entry(&self.entry_name))
            .map_err(|_| chur_core::err!(IoFailure, "the descriptor could not be installed"))?;
        sync_directory(&self.root_dir.registry())?;
        self.descriptor = candidate;
        Ok(())
    }
}

/// One password candidate, `KEY_SLOTS.md` §8.
struct PasswordCandidate {
    entry_name: RegistryName,
    bytes: Vec<u8>,
    binding: SlotBinding,
    body: PasswordSlotBody,
    params: Argon2Params,
}

/// The candidate list of §8: the highest-generation password slot of each
/// descriptor, in the registry enumeration order.
fn password_candidates(root_dir: &VaultRoot) -> Result<Vec<PasswordCandidate>> {
    let mut candidates = Vec::new();
    for name in root_dir.registry_names()? {
        let bytes = read_entry(root_dir, &name)?;
        let Ok(descriptor) = VaultDescriptor::parse(&bytes) else {
            continue;
        };
        let Some(entry) = descriptor.password_slot() else {
            continue;
        };
        let Ok(body) = PasswordSlotBody::decode(&entry.slot_body) else {
            continue;
        };
        candidates.push(PasswordCandidate {
            entry_name: name,
            binding: entry.binding(descriptor.vault_id),
            params: body.params(),
            body,
            bytes,
        });
    }
    Ok(candidates)
}

fn read_entry(root_dir: &VaultRoot, name: &RegistryName) -> Result<Vec<u8>> {
    std::fs::read(root_dir.registry_entry(name))
        .map_err(|_| chur_core::err!(IoFailure, "a registry entry could not be read"))
}

fn seal_password_slot(
    vault_id: &Id,
    canonical: &[u8],
    params: Argon2Params,
    root: &Key,
    generation: u64,
) -> Result<KeySlotDescriptor> {
    let slot_id = random::id()?;
    // The binding is built directly rather than from a placeholder descriptor:
    // a descriptor with an empty body fails the 16-byte minimum of §13, and the
    // binding is exactly the six fields that exist before the body does.
    let binding = SlotBinding::v1(*vault_id, slot_id, SlotType::Password, generation);
    let salt = random::array::<SALT_LEN>()?.to_vec();
    let body = PasswordSlotBody::seal(&binding, canonical, salt, params, Nonce::random()?, root)?;
    KeySlotDescriptor::v1(slot_id, SlotType::Password, generation, body.encode())
}

fn seal_recovery_slot(
    vault_id: &Id,
    secret: &Key,
    root: &Key,
    generation: u64,
) -> Result<KeySlotDescriptor> {
    let slot_id = random::id()?;
    let binding = SlotBinding::v1(*vault_id, slot_id, SlotType::Recovery, generation);
    let body = RecoverySlotBody::seal(&binding, secret, Nonce::random()?, root)?;
    KeySlotDescriptor::v1(slot_id, SlotType::Recovery, generation, body.encode())
}

fn open_password_slot(descriptor: &VaultDescriptor, canonical: &[u8]) -> Result<Key> {
    let Some(entry) = descriptor.password_slot() else {
        bail!(VaultIncomplete, "the descriptor carries no password slot");
    };
    let binding = entry.binding(descriptor.vault_id);
    PasswordSlotBody::decode(&entry.slot_body)?.open(&binding, canonical)
}

fn write_temporary(
    root_dir: &VaultRoot,
    entry_name: &RegistryName,
    descriptor: &VaultDescriptor,
    root: &Key,
) -> Result<()> {
    let bytes = descriptor.encode(root)?;
    write_durably(&root_dir.registry_temporary(entry_name), &bytes)
}

/// Writes a file and fsyncs it, which §9 requires before the verification step.
fn write_durably(path: &std::path::Path, bytes: &[u8]) -> Result<()> {
    use std::io::Write as _;
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)
            .map_err(|_| chur_core::err!(IoFailure, "the descriptor directory is absent"))?;
    }
    let mut file = std::fs::File::create(path)
        .map_err(|_| chur_core::err!(IoFailure, "the descriptor could not be created"))?;
    file.write_all(bytes)
        .map_err(|_| chur_core::err!(IoFailure, "the descriptor could not be written"))?;
    file.sync_all()
        .map_err(|_| chur_core::err!(IoFailure, "the descriptor could not be made durable"))?;
    Ok(())
}

/// Makes a rename durable.
///
/// A rename is atomic but not durable: on a crash the directory entry can be
/// absent even though the file it names is intact, which for §9 would mean a
/// vault that vanished rather than one that was never installed.
fn sync_directory(path: &std::path::Path) -> Result<()> {
    let directory = std::fs::File::open(path)
        .map_err(|_| chur_core::err!(IoFailure, "the registry could not be opened"))?;
    // A directory fsync is not supported on every platform; where it is not,
    // the rename is still atomic and the loss window is the platform's own.
    let _ = directory.sync_all();
    Ok(())
}

/// The commitment the catalog descriptor carries, `VAULT_DESCRIPTOR_V1.md` §5.
///
/// SQLCipher writes a random 16-byte salt at the start of the database and
/// never rewrites it, so those bytes are a stable non-secret identity for this
/// catalog file. Committing to them binds the descriptor to one file: a catalog
/// substituted from another vault, or an older copy restored underneath this
/// one, fails at unlock rather than at the first query that reads a row.
fn catalog_header_commitment(path: &std::path::Path) -> Result<commit::Commitment> {
    use std::io::Read as _;
    let mut file = std::fs::File::open(path)
        .map_err(|_| chur_core::err!(IoFailure, "the catalog file could not be opened"))?;
    let mut header = [0u8; 16];
    file.read_exact(&mut header)
        .map_err(|_| chur_core::err!(CatalogCorrupt, "the catalog file has no header"))?;
    Ok(commit::commit(
        chur_crypto::tag::CATALOG_HEADER_COMMITMENT,
        &[&header],
    ))
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::panic, clippy::expect_used)]

    use super::*;
    use crate::query::{ObjectQuery, page};

    /// A private directory for one test.
    fn scratch() -> VaultRoot {
        let mut path = std::env::temp_dir();
        path.push(format!("chur-vault-{}", random::id().expect("id").to_hex()));
        std::fs::create_dir_all(&path).expect("create");
        VaultRoot::new(path)
    }

    const PASSWORD: &[u8] = b"correct horse battery staple";

    fn rejection<T>(outcome: Result<T>) -> ChurStatus {
        let Err(error) = outcome else {
            panic!("the vault accepted something the specification forbids");
        };
        error.status()
    }

    fn make(root_dir: &VaultRoot) -> Session {
        create(root_dir, PASSWORD, 1_700_000_000_000)
            .expect("create")
            .activate()
            .expect("activate")
    }

    #[test]
    fn a_created_vault_unlocks_with_its_password() {
        let root_dir = scratch();
        let session = make(&root_dir);
        let vault_id = session.vault_id();
        drop(session);

        let mut reopened =
            unlock_with_password(&root_dir, PASSWORD, 1_700_000_001_000).expect("unlock");
        assert_eq!(reopened.vault_id(), vault_id);
        assert!(reopened.is_unlocked());
        page(
            reopened.catalog().expect("catalog"),
            &ObjectQuery::timeline(),
        )
        .expect("query");
    }

    #[test]
    fn a_wrong_password_is_one_external_result() {
        let root_dir = scratch();
        drop(make(&root_dir));
        assert_eq!(
            rejection(unlock_with_password(&root_dir, b"wrong", 1)),
            ChurStatus::AuthenticationFailed
        );
    }

    #[test]
    fn an_empty_registry_fails_the_same_way_as_a_wrong_password() {
        let root_dir = scratch();
        assert_eq!(
            rejection(unlock_with_password(&root_dir, PASSWORD, 1)),
            ChurStatus::AuthenticationFailed,
            "an absent vault must not be distinguishable from a wrong credential"
        );
    }

    #[test]
    fn a_password_valid_for_a_sibling_identity_fails_like_any_other() {
        let root_dir = scratch();
        drop(make(&root_dir));
        let sibling = create(&root_dir, b"the other identity", 1)
            .expect("create")
            .activate()
            .expect("activate");
        let sibling_id = sibling.vault_id();
        drop(sibling);

        // Each password opens exactly its own identity and neither leaks which.
        let first = unlock_with_password(&root_dir, PASSWORD, 1).expect("unlock");
        let second = unlock_with_password(&root_dir, b"the other identity", 1).expect("unlock");
        assert_ne!(first.vault_id(), second.vault_id());
        assert!(second.vault_id() == sibling_id || first.vault_id() == sibling_id);
        drop(first);
        drop(second);
        assert_eq!(
            rejection(unlock_with_password(&root_dir, b"neither", 1)),
            ChurStatus::AuthenticationFailed
        );
    }

    #[test]
    fn the_registry_admits_only_two_identities() {
        let root_dir = scratch();
        drop(make(&root_dir));
        drop(
            create(&root_dir, b"second", 1)
                .expect("create")
                .activate()
                .expect("activate"),
        );
        assert_eq!(
            rejection(create(&root_dir, b"third", 1)),
            ChurStatus::ResourceLimitExceeded
        );
    }

    #[test]
    fn creation_interrupted_before_activation_leaves_no_openable_vault() {
        let root_dir = scratch();
        let creation = create(&root_dir, PASSWORD, 1).expect("create");
        // The temporary descriptor exists and is not a candidate.
        assert!(root_dir.registry_names().expect("names").is_empty());
        assert_eq!(
            rejection(unlock_with_password(&root_dir, PASSWORD, 1)),
            ChurStatus::AuthenticationFailed
        );
        creation.abandon().expect("abandon");
        assert_eq!(root_dir.sweep_temporary().expect("sweep"), 0);
        assert!(root_dir.registry_names().expect("names").is_empty());
    }

    #[test]
    fn an_abandoned_creation_leaves_no_directory_behind() {
        let root_dir = scratch();
        let creation = create(&root_dir, PASSWORD, 1).expect("create");
        let store = creation.descriptor.object_store.opaque_root_path_id;
        assert!(root_dir.vault(&store).exists());
        creation.abandon().expect("abandon");
        assert!(!root_dir.vault(&store).exists());
    }

    #[test]
    fn a_dropped_creation_is_swept_rather_than_left_as_a_candidate() {
        let root_dir = scratch();
        drop(create(&root_dir, PASSWORD, 1).expect("create"));
        assert!(root_dir.registry_names().expect("names").is_empty());
        assert_eq!(root_dir.sweep_temporary().expect("sweep"), 1);
    }

    #[test]
    fn the_recovery_slot_offered_during_creation_unlocks_the_vault() {
        let root_dir = scratch();
        let mut creation = create(&root_dir, PASSWORD, 1).expect("create");
        let secret = creation.add_recovery_slot().expect("recovery");
        let phrase = recovery::to_phrase(&secret);
        let session = creation.activate().expect("activate");
        let vault_id = session.vault_id();
        drop(session);

        let reopened = unlock_with_recovery(&root_dir, &phrase, 1).expect("recover");
        assert_eq!(reopened.vault_id(), vault_id);
    }

    #[test]
    fn a_recovery_slot_added_after_activation_unlocks_the_vault() {
        let root_dir = scratch();
        let mut session = make(&root_dir);
        let secret = session.add_recovery_slot().expect("recovery");
        let phrase = recovery::to_phrase(&secret);
        let vault_id = session.vault_id();
        drop(session);
        assert_eq!(
            unlock_with_recovery(&root_dir, &phrase, 1)
                .expect("recover")
                .vault_id(),
            vault_id
        );
    }

    #[test]
    fn a_wrong_recovery_phrase_is_the_same_external_result() {
        let root_dir = scratch();
        let mut session = make(&root_dir);
        session.add_recovery_slot().expect("recovery");
        drop(session);
        let other: Key = random::secret::<32>().expect("secret");
        let phrase = recovery::to_phrase(&other);
        assert_eq!(
            rejection(unlock_with_recovery(&root_dir, &phrase, 1)),
            ChurStatus::AuthenticationFailed
        );
    }

    #[test]
    fn an_apple_keychain_slot_unlocks_the_vault() {
        let root_dir = scratch();
        let mut session = make(&root_dir);
        let item = random::id().expect("id");
        let secret = session.add_apple_keychain_slot(item).expect("slot");
        let vault_id = session.vault_id();
        drop(session);
        assert_eq!(
            unlock_with_apple_keychain(&root_dir, &secret, 1)
                .expect("unlock")
                .vault_id(),
            vault_id
        );
        let other: Key = random::secret::<32>().expect("secret");
        assert_eq!(
            rejection(unlock_with_apple_keychain(&root_dir, &other, 1)),
            ChurStatus::AuthenticationFailed
        );
    }

    #[test]
    fn the_last_portable_slot_cannot_be_removed() {
        let root_dir = scratch();
        let mut session = make(&root_dir);
        let item = random::id().expect("id");
        session.add_apple_keychain_slot(item).expect("slot");
        let password_slot = session
            .descriptor
            .password_slot()
            .expect("a password slot")
            .slot_id;
        assert_eq!(
            rejection(session.remove_slot(&password_slot)),
            ChurStatus::Conflict,
            "removing the last portable slot left no verified recovery path"
        );
        session.add_recovery_slot().expect("recovery");
        session.remove_slot(&password_slot).expect("now removable");
    }

    #[test]
    fn a_password_replacement_verifies_before_the_old_slot_goes() {
        let root_dir = scratch();
        let mut session = make(&root_dir);
        session
            .replace_password(b"a new password", Argon2Params::v1_default())
            .expect("replace");
        drop(session);
        assert!(
            unlock_with_password(&root_dir, b"a new password", 1)
                .expect("unlock")
                .is_unlocked()
        );
        assert_eq!(
            rejection(unlock_with_password(&root_dir, PASSWORD, 1)),
            ChurStatus::AuthenticationFailed
        );
    }

    #[test]
    fn a_slot_change_advances_the_descriptor_generation() {
        let root_dir = scratch();
        let mut session = make(&root_dir);
        assert_eq!(session.descriptor.descriptor_generation, 1);
        session.add_recovery_slot().expect("recovery");
        assert_eq!(session.descriptor.descriptor_generation, 2);
    }

    #[test]
    fn a_substituted_catalog_file_fails_at_unlock() {
        let first = scratch();
        let second = scratch();
        let session = make(&first);
        let store = session.object_store_id();
        let catalog_id = session.descriptor.catalog.opaque_catalog_path_id;
        drop(session);

        let stranger = make(&second);
        let other_store = stranger.object_store_id();
        let other_catalog = stranger.descriptor.catalog.opaque_catalog_path_id;
        drop(stranger);

        std::fs::copy(
            second.catalog(&other_store, &other_catalog),
            first.catalog(&store, &catalog_id),
        )
        .expect("substitute the catalog");
        assert_eq!(
            rejection(unlock_with_password(&first, PASSWORD, 1)),
            ChurStatus::VaultCorrupt,
            "a catalog from another vault opened"
        );
    }

    #[test]
    fn lock_closes_the_catalog_and_clears_the_scratch_directory() {
        let root_dir = scratch();
        let mut session = make(&root_dir);
        let scratch_dir = root_dir.scratch(&session.object_store_id());
        std::fs::write(scratch_dir.join("aabbccdd"), b"decoded frame").expect("scratch");
        assert!(session.is_unlocked());

        session.lock().expect("lock");
        assert!(!session.is_unlocked());
        assert_eq!(
            rejection(session.catalog()),
            ChurStatus::VaultLocked,
            "the catalog survived the lock"
        );
        let remaining = std::fs::read_dir(&scratch_dir).expect("read").count();
        assert_eq!(remaining, 0, "a scratch entry survived the lock");
    }

    #[test]
    fn locking_twice_is_not_a_failure() {
        let root_dir = scratch();
        let mut session = make(&root_dir);
        session.lock().expect("lock");
        session.lock().expect("lock again");
    }

    #[test]
    fn a_descriptor_that_fails_the_parser_is_skipped_without_a_credential() {
        let root_dir = scratch();
        drop(make(&root_dir));
        let damaged = RegistryName::random().expect("name");
        std::fs::write(root_dir.registry_entry(&damaged), b"not a descriptor").expect("write");
        // The vault still opens: §11 skips the unparsable entry before any
        // credential is used and attributes its failure to no credential.
        assert!(unlock_with_password(&root_dir, PASSWORD, 1).is_ok());
    }

    #[test]
    fn the_catalog_of_a_new_vault_is_installed_and_empty() {
        let root_dir = scratch();
        let mut session = make(&root_dir);
        let result = page(
            session.catalog().expect("catalog"),
            &ObjectQuery::timeline(),
        )
        .expect("page");
        assert!(result.objects.is_empty());
        assert_eq!(result.total_count, 0);
    }
}
