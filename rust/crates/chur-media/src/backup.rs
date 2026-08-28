//! Creating and restoring a portable backup package.
//!
//! `docs/format/BACKUP_FORMAT_V1.md` owns the bytes and `chur_format::backup`
//! writes them. This module is the transaction around them: §7's streaming
//! creation and §8's restore, over a real vault on a real filesystem.
//!
//! Three properties are the reason the code looks as it does.
//!
//! **Nothing is decrypted.** §1 requires the package to preserve immutable
//! object containers without opening them, so a container entry is a byte copy.
//! Neither creation nor restore ever holds an object key, and a backup of a
//! vault this build cannot read still copies it correctly.
//!
//! **Memory does not scale with the vault.** The inventory is walked twice
//! rather than collected: `docs/format/CATALOG_SCHEMA_V1.md` §21 admits a
//! million objects, and a materialized inventory would be over a hundred
//! megabytes. One entry and one copy buffer are in flight at a time.
//!
//! **Completeness is authenticated.** The ordered inventory commitment of §7.2
//! is recomputed while the package is read, and the final backup commit seals
//! it together with the record count the public preamble declared. A package
//! with a container removed, a record added, or two records reordered fails
//! there rather than restoring a vault that is quietly missing something.

use std::io::{Read, Seek, SeekFrom, Write};
use std::path::Path;

use chur_catalog::paths::{RegistryName, VaultRoot};
use chur_catalog::vault::{self, Session};
use chur_catalog::{schema, store};
use chur_core::{Id, Result, bail, ensure, limits::backup as bounds};
use chur_crypto::{Key, Nonce, random};
use chur_format::backup::{
    BackupManifest, FinalBackupCommit, Framing, InventoryCommitter, PublicPreamble,
    RECORD_HEADER_LEN, RecordHeader, RecordType, SlotInventoryEntry, StreamInventoryEntry,
    framing_of, manifest_key,
};
use chur_format::constants::{CATALOG_FORMAT_VERSION_V1, SlotType, VaultState};
use chur_format::container::PublicPreamble as ContainerPreamble;
use chur_format::descriptor::VaultDescriptor;

use crate::progress::{self, Progress};

/// How much of a container is read to recompute its manifest commitment.
///
/// The manifest record follows the container preamble, and its length is the
/// preamble's one field, so the head is read in two steps and never as a guess.
const CONTAINER_HEAD_MAX: usize = 65_536;

/// The buffer one container copy holds.
const COPY_BUFFER: usize = 262_144;

/// What a created package holds.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct BackupSummary {
    /// The package's identity.
    pub backup_id: Id,
    /// The vault identity it carries. §11: one package, one identity.
    pub vault_id: Id,
    /// Records written, which the public preamble declares.
    pub record_count: u64,
    /// Stream inventory entries.
    pub stream_count: u32,
    /// Portable slot inventory entries.
    pub slot_count: u32,
    /// Bytes written.
    pub package_length: u64,
}

/// What a restore installed.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RestoreSummary {
    /// The package's identity.
    pub backup_id: Id,
    /// The vault identity that was installed.
    pub vault_id: Id,
    /// Containers written.
    pub stream_count: u32,
    /// When the package was created.
    pub created_time_ms: u64,
}

// ---------------------------------------------------------------------------
// §3 Portable slots
// ---------------------------------------------------------------------------

/// Whether a slot family travels in a package, §3.
///
/// A device-bound slot is excluded, and not as an optimization: an Android
/// Keystore alias and a `ThisDeviceOnly` Keychain item name key material that
/// exists on one device only, so a package carrying them would restore a vault
/// with a slot nothing can open, on a device that cannot be told why.
const fn is_portable(slot_type: SlotType) -> bool {
    match slot_type {
        SlotType::Password | SlotType::Recovery => true,
        SlotType::AndroidKeystore | SlotType::AppleKeychain | SlotType::PeerDevice => false,
        _ => false,
    }
}

/// The descriptor a package carries: the live one with §3's exclusions applied.
///
/// The state is forced to `ACTIVE` and the generation to 1. A restore installs a
/// new vault rather than continuing this one, and carrying a generation from the
/// source device would make the restored descriptor claim a history it does not
/// have — `VAULT_DESCRIPTOR_V1.md` §10 uses the generation for local rollback
/// detection, which is a per-device fact.
fn portable_descriptor(live: &VaultDescriptor) -> Result<VaultDescriptor> {
    let key_slots: Vec<_> = live
        .key_slots
        .iter()
        .filter(|slot| is_portable(slot.slot_type))
        .cloned()
        .collect();
    ensure!(
        !key_slots.is_empty(),
        Conflict,
        "the vault has no portable slot, so a package of it could never be opened"
    );
    ensure!(
        key_slots.len() <= bounds::SLOT_ENTRIES_MAX,
        ResourceLimitExceeded,
        "the vault holds more portable slots than §13 admits"
    );
    Ok(VaultDescriptor {
        vault_id: live.vault_id,
        descriptor_generation: 1,
        state: VaultState::Active,
        catalog: live.catalog,
        object_store: live.object_store,
        key_slots,
        migration: None,
    })
}

/// The slot inventory entries of §7.1, in the order that section fixes.
///
/// "Slot entries follow, sorted by ascending `slot_id` byte order." A
/// descriptor stores its slots in the order they were enrolled and nothing
/// sorts them, so a writer that walked the descriptor would produce a
/// commitment that depended on enrolment history. §7.1's whole purpose is that
/// two conforming writers over the same content emit the same sequence.
fn slot_entries(descriptor: &VaultDescriptor) -> Vec<SlotInventoryEntry> {
    let mut entries: Vec<SlotInventoryEntry> = descriptor
        .key_slots
        .iter()
        .map(|slot| SlotInventoryEntry {
            slot_id: slot.slot_id,
            slot_type: slot.slot_type.value(),
            slot_generation: slot.slot_generation,
        })
        .collect();
    entries.sort_unstable_by_key(|entry| *entry.slot_id.as_bytes());
    entries
}

// ---------------------------------------------------------------------------
// §7 Streaming creation
// ---------------------------------------------------------------------------

/// Writes a full backup package of the session's vault, §5 and §7.
///
/// The walk runs twice. The first pass computes the ordered inventory
/// commitment and the record count, which the manifest and the public preamble
/// both need before any content is written; the second writes the records. Both
/// read the same ordered query, so neither holds the inventory.
///
/// # Errors
///
/// Returns [`ChurStatus::Cancelled`] when `progress` asks it to stop, and the
/// storage and catalog errors of the walk.
pub fn create(
    session: &mut Session,
    destination: &mut (impl Write + Seek),
    now_ms: u64,
    progress: &mut impl Progress,
) -> Result<BackupSummary> {
    // §7 step 1: a consistent catalog snapshot. Committed pages can live in the
    // write-ahead log, and a package that copied only the database file would
    // carry a catalog older than the containers beside it.
    session.checkpoint_catalog()?;

    let vault_id = session.vault_id();
    let store_id = session.object_store_id();
    let backup_id = random::id()?;
    let descriptor = portable_descriptor(session.descriptor())?;
    let descriptor_bytes = session.seal_descriptor(&descriptor)?;
    let catalog_path = session.catalog_path();
    let catalog_length = file_length(&catalog_path)?;
    let root_dir = session.root_dir().clone();

    // Pass one: the inventory commitment and the counts.
    let mut committer = InventoryCommitter::new();
    let mut content_length = 0u64;
    store::for_each_stream_ordered(session.catalog_ref()?, |object_id, stream| {
        if progress.cancelled() {
            return Err(progress::cancelled("the backup was cancelled"));
        }
        let entry = inventory_entry(&root_dir, &store_id, object_id, stream)?;
        content_length = content_length.saturating_add(entry.ciphertext_length);
        committer.add_stream(&entry)
    })?;
    for entry in slot_entries(&descriptor) {
        committer.add_slot(&entry)?;
    }
    let stream_count = committer.stream_count();
    let slot_count = committer.slot_count();
    let inventory_commitment = committer.finish();

    // The manifest, the descriptor, the catalog export, one record per stream,
    // and the final commit.
    let record_count = u64::from(stream_count) + 4;
    let preamble = PublicPreamble::new(record_count)?;

    let manifest = BackupManifest {
        backup_id,
        vault_id,
        created_time_ms: now_ms,
        base_backup_id: None,
        catalog_generation: schema::generation(session.catalog_ref()?)?,
        catalog_format_version: CATALOG_FORMAT_VERSION_V1,
        stream_entry_count: stream_count,
        slot_entry_count: slot_count,
        inventory_commitment,
        free_space_required: content_length
            .saturating_add(catalog_length)
            .saturating_add(bounds::RESTORE_HEADROOM),
    };
    let key = manifest_key(session.root_secret(), &vault_id)?;
    let manifest_payload = manifest.seal(&key, &Nonce::random()?)?;
    ensure!(
        manifest_payload.len() <= bounds::MANIFEST_PAYLOAD_MAX,
        ResourceLimitExceeded,
        "the manifest record exceeds the §13 payload bound"
    );

    let mut written = write_all(destination, &preamble.encode())?;
    written += write_record(destination, RecordType::Manifest, &manifest_payload)?;
    written += write_record(destination, RecordType::Descriptor, &descriptor_bytes)?;
    written += write_streamed_record(
        destination,
        RecordType::CatalogExport,
        &catalog_path,
        catalog_length,
        progress,
    )?;

    // Pass two: the containers, in the same order the commitment was taken in.
    let mut copied = 0u64;
    store::for_each_stream_ordered(session.catalog_ref()?, |object_id, stream| {
        if progress.cancelled() {
            return Err(progress::cancelled("the backup was cancelled"));
        }
        let entry = inventory_entry(&root_dir, &store_id, object_id, stream)?;
        let path = root_dir.container(&store_id, &stream.container_path_id);
        let entry_bytes = entry.encode();
        let payload_length = entry_bytes.len() as u64 + entry.ciphertext_length;
        let header = RecordHeader {
            record_type: RecordType::Container,
            payload_length,
        };
        copied += write_all(destination, &header.encode())?;
        copied += write_all(destination, &entry_bytes)?;
        copied += copy_file(destination, &path, entry.ciphertext_length, progress)?;
        progress.advance(copied);
        Ok(())
    })?;
    written += copied;

    let commit = FinalBackupCommit {
        backup_id,
        record_count,
        stream_entry_count: stream_count,
        slot_entry_count: slot_count,
        inventory_commitment,
    };
    let commit_payload = commit.seal(&key, &vault_id, &Nonce::random()?)?;
    written += write_record(destination, RecordType::FinalCommit, &commit_payload)?;
    destination
        .flush()
        .map_err(|_| chur_core::err!(IoFailure, "the package destination could not be flushed"))?;

    Ok(BackupSummary {
        backup_id,
        vault_id,
        record_count,
        stream_count,
        slot_count,
        package_length: written,
    })
}

/// Builds one stream's inventory entry, §7.1.
///
/// The manifest commitment is not a catalog column, so it is recomputed from
/// the container's own head. That is the honest source: the entry must describe
/// the bytes the package carries, and a value copied from a row would describe
/// what the catalog believes about them.
fn inventory_entry(
    root_dir: &VaultRoot,
    store_id: &Id,
    object_id: &Id,
    stream: &chur_catalog::model::Stream,
) -> Result<StreamInventoryEntry> {
    let path = root_dir.container(store_id, &stream.container_path_id);
    let ciphertext_length = file_length(&path)?;
    Ok(StreamInventoryEntry {
        object_id: *object_id,
        stream_id: stream.stream_id,
        stream_kind: stream.stream_kind,
        stream_revision: stream.stream_revision,
        ciphertext_length,
        manifest_commitment: manifest_commitment_of(&path)?,
        ordered_chunk_commitment: stream.final_commitment,
    })
}

/// Recomputes one container's manifest commitment from its first records.
fn manifest_commitment_of(path: &Path) -> Result<[u8; 32]> {
    let mut file = std::fs::File::open(path)
        .map_err(|_| chur_core::err!(NotFound, "a container named by the catalog is absent"))?;
    let mut preamble = vec![0u8; ContainerPreamble::LEN];
    file.read_exact(&mut preamble).map_err(|_| {
        chur_core::err!(ObjectIncomplete, "a container is shorter than its preamble")
    })?;
    let head = ContainerPreamble::decode(&preamble)?;
    let length = usize::try_from(head.manifest_record_length())
        .map_err(|_| chur_core::err!(ObjectCorrupt, "a manifest record length exceeds a usize"))?;
    ensure!(
        length <= CONTAINER_HEAD_MAX,
        ResourceLimitExceeded,
        "a manifest record exceeds the head this reader admits"
    );
    let mut record = vec![0u8; length];
    file.read_exact(&mut record)
        .map_err(|_| chur_core::err!(ObjectIncomplete, "a container has no manifest record"))?;
    // The record is a 24-byte nonce followed by the sealed manifest, and the
    // commitment of `OBJECT_CONTAINER_V1.md` §5 is over both.
    let nonce_len = chur_core::limits::NONCE_LEN;
    ensure!(
        record.len() > nonce_len,
        ObjectCorrupt,
        "a manifest record is shorter than its nonce"
    );
    let nonce = Nonce::from_slice(&record[..nonce_len])?;
    Ok(chur_format::container::manifest_commitment(
        &nonce,
        &record[nonce_len..],
    ))
}

fn file_length(path: &Path) -> Result<u64> {
    std::fs::metadata(path)
        .map(|metadata| metadata.len())
        .map_err(|_| chur_core::err!(NotFound, "a file the package needs is absent"))
}

fn write_all(destination: &mut impl Write, bytes: &[u8]) -> Result<u64> {
    destination
        .write_all(bytes)
        .map_err(|_| chur_core::err!(IoFailure, "the package destination rejected a write"))?;
    Ok(bytes.len() as u64)
}

fn write_record(
    destination: &mut impl Write,
    record_type: RecordType,
    payload: &[u8],
) -> Result<u64> {
    let header = RecordHeader {
        record_type,
        payload_length: payload.len() as u64,
    };
    let mut written = write_all(destination, &header.encode())?;
    written += write_all(destination, payload)?;
    Ok(written)
}

/// Writes one record whose payload is a file, without reading the file whole.
fn write_streamed_record(
    destination: &mut impl Write,
    record_type: RecordType,
    path: &Path,
    length: u64,
    progress: &impl Progress,
) -> Result<u64> {
    let header = RecordHeader {
        record_type,
        payload_length: length,
    };
    let mut written = write_all(destination, &header.encode())?;
    written += copy_file(destination, path, length, progress)?;
    Ok(written)
}

/// Copies exactly `length` bytes of a file into the package.
///
/// The probe is read once per buffer rather than once per file. A vault can
/// hold one object of a terabyte, and a copy that checked only between files
/// would make `chur_vault_lock` wait for that whole copy: the lock drains and
/// joins every operation before it takes the session, so an operation that
/// cannot stop is a lock that cannot complete, against a p95 budget of 100 ms
/// in `docs/assurance/PERFORMANCE_BUDGETS.md` §2.
fn copy_file(
    destination: &mut impl Write,
    path: &Path,
    length: u64,
    progress: &impl Progress,
) -> Result<u64> {
    let mut file = std::fs::File::open(path)
        .map_err(|_| chur_core::err!(NotFound, "a file the package needs is absent"))?;
    let mut buffer = vec![0u8; COPY_BUFFER];
    let mut remaining = length;
    while remaining > 0 {
        if progress.cancelled() {
            return Err(progress::cancelled("the backup was cancelled"));
        }
        let take = usize::try_from(remaining.min(COPY_BUFFER as u64))
            .map_err(|_| chur_core::err!(InternalFailure, "a copy step exceeds a usize"))?;
        file.read_exact(&mut buffer[..take]).map_err(|_| {
            chur_core::err!(
                VaultIncomplete,
                "a file shrank while the package was being written"
            )
        })?;
        destination
            .write_all(&buffer[..take])
            .map_err(|_| chur_core::err!(IoFailure, "the package destination rejected a write"))?;
        remaining -= take as u64;
    }
    Ok(length)
}

// ---------------------------------------------------------------------------
// §8 Restore transaction
// ---------------------------------------------------------------------------

/// One record's place in the package, from the header scan.
struct RecordSlot {
    record_type: RecordType,
    offset: u64,
    payload_length: u64,
}

/// Restores a package into `root_dir`, §8.
///
/// The steps run in §8's order and the two that matter are the two that come
/// before anything is written: the package is authenticated whole — every
/// container's inventory entry recomputed into the commitment the final commit
/// seals — before the first byte lands in the vault namespace, and the
/// descriptor is installed last, by the same atomic rename that ends a vault
/// creation. A restore interrupted at any earlier point leaves no openable
/// vault, exactly as an abandoned creation does.
///
/// It takes no clock. A restore installs bytes the package already fixed and
/// records no time of its own: the manifest carries the creation time and the
/// catalog carries every row's, so there is nothing here for a clock to stamp.
///
/// # Errors
///
/// Returns [`ChurStatus::VaultCorrupt`] for a package that does not parse or
/// does not authenticate, [`ChurStatus::AuthenticationFailed`] when the
/// credential opens no portable slot, [`ChurStatus::Conflict`] when the registry
/// is full, and [`ChurStatus::StorageUnavailable`] when a write fails.
///
/// §13 also asks a restore to refuse to begin unless the destination holds the
/// package length plus 64 MiB. That preflight is not run here. The standard
/// library exposes no filesystem-capacity call, and the only way to ask the
/// platform is `statvfs`, which needs `unsafe` in a crate that forbids it — a
/// worse trade than an error one step later, because the manifest carries
/// `free_space_required` for a caller that can ask, and a destination that
/// fills fails on the write and takes the partial vault directory with it, so
/// nothing openable survives either way.
pub fn restore(
    root_dir: &VaultRoot,
    source: &mut (impl Read + Seek),
    password: &[u8],
    progress: &mut impl Progress,
) -> Result<RestoreSummary> {
    // §11 and `VAULT_DESCRIPTOR_V1.md` §11: the registry holds two identities.
    ensure!(
        root_dir.registry_names()?.len() < chur_catalog::paths::REGISTRY_MAX,
        Conflict,
        "the registry already holds the two identities §11 admits"
    );

    let package_length = source
        .seek(SeekFrom::End(0))
        .map_err(|_| chur_core::err!(IoFailure, "the package length could not be read"))?;
    let slots = scan(source, package_length)?;

    // §8 steps 2 and 3. The root comes from a portable slot of the package's own
    // descriptor, so the manifest key exists only for a credential that opens
    // one; nothing else in the package is read before that.
    let descriptor_bytes = read_payload_bounded(
        source,
        find(&slots, RecordType::Descriptor)?,
        chur_core::limits::descriptor::LENGTH_MAX as u64,
    )?;
    let (descriptor, root_secret) = open_portable_descriptor(&descriptor_bytes, password)?;
    let key = manifest_key(&root_secret, &descriptor.vault_id)?;

    let manifest_payload = read_payload_bounded(
        source,
        find(&slots, RecordType::Manifest)?,
        bounds::MANIFEST_PAYLOAD_MAX as u64,
    )?;
    let manifest = BackupManifest::open(&manifest_payload, &key, &descriptor.vault_id)?;

    let commit_payload =
        read_payload_bounded(source, find(&slots, RecordType::FinalCommit)?, 4_096)?;
    let commit = FinalBackupCommit::open(
        &commit_payload,
        &key,
        &descriptor.vault_id,
        &manifest.backup_id,
    )?;

    // §2.1: `record_count` is the one variable preamble field, and the final
    // commit authenticates it. §4: the manifest and the commit must agree.
    ensure!(
        commit.record_count == slots.len() as u64
            && commit.stream_entry_count == manifest.stream_entry_count
            && commit.slot_entry_count == manifest.slot_entry_count
            && commit.inventory_commitment == manifest.inventory_commitment,
        VaultCorrupt,
        "the final backup commit contradicts the package it closes"
    );

    // §8 step 4: completeness before anything is written. Every container entry
    // is read in package order and folded into the commitment the commit sealed.
    let mut committer = InventoryCommitter::new();
    let mut entries = Vec::with_capacity(slots.len());
    for slot in slots
        .iter()
        .filter(|s| s.record_type == RecordType::Container)
    {
        if progress.cancelled() {
            return Err(progress::cancelled("the restore was cancelled"));
        }
        let entry = read_container_entry(source, slot)?;
        // §5: completeness verification checks every inventory entry before
        // activation. That means checking the container against the entry that
        // describes it, not only that an entry is present — a package whose
        // ciphertext was altered would otherwise install and fail at the first
        // read, after the vault existed.
        verify_container(source, slot, &entry, progress)?;
        committer.add_stream(&entry)?;
        entries.push((entry, slot.offset));
    }
    for entry in slot_entries(&descriptor) {
        committer.add_slot(&entry)?;
    }
    ensure!(
        committer.stream_count() == commit.stream_entry_count
            && committer.slot_count() == commit.slot_entry_count
            && committer.finish() == commit.inventory_commitment,
        VaultCorrupt,
        "the package's contents do not match the inventory its final commit seals"
    );

    // §8 steps 5 to 9. Everything below writes into the vault namespace, and
    // the descriptor rename at the end is what makes any of it openable.
    //
    // The local path identifiers are drawn fresh rather than taken from the
    // package. `VAULT_DESCRIPTOR_V1.md` §6 makes them opaque random names of
    // this device's storage layout, not of the identity, and reusing the source
    // device's would collide with whatever already occupies that name here —
    // including, when a package is restored beside the vault it came from,
    // that very vault. The descriptor is re-sealed under the root the package's
    // own slot returned, which is the same operation a creation performs.
    let store_id = random::id()?;
    let catalog_path_id = random::id()?;
    let local = VaultDescriptor {
        catalog: chur_format::descriptor::CatalogDescriptor {
            opaque_catalog_path_id: catalog_path_id,
            ..descriptor.catalog
        },
        object_store: chur_format::descriptor::ObjectStoreDescriptor::v1(store_id),
        ..descriptor.clone()
    };
    root_dir.prepare(&store_id)?;
    let catalog_path = root_dir.catalog(&store_id, &catalog_path_id);
    let installed = (|| -> Result<()> {
        extract(
            source,
            find(&slots, RecordType::CatalogExport)?,
            &catalog_path,
            progress,
        )?;
        // `VAULT_DESCRIPTOR_V1.md` §5: the descriptor commits to the catalog it
        // names. Checking it here rather than at the next unlock is the
        // difference between a package that does not install and a vault that
        // installs and then cannot be opened.
        ensure!(
            chur_crypto::secret::constant_time_eq(
                &vault::catalog_header_commitment(&catalog_path)?,
                &local.catalog.catalog_header_commitment
            ),
            VaultCorrupt,
            "the package's catalog is not the one its descriptor commits to"
        );
        // §8 step 6: the object-key references are validated against the
        // catalog that was restored, not against the package's own claim. The
        // walk is in the §7.1 order the container records were written in, so
        // the k-th record is the k-th row and the two are compared rather than
        // assumed.
        let catalog_key = chur_catalog::db::CatalogKey::derive(&root_secret, &local.vault_id)?;
        let catalog = chur_catalog::db::CatalogDb::open(
            &chur_catalog::db::CatalogLocation::File(&catalog_path),
            &catalog_key,
        )?;
        let mut index = 0usize;
        let mut done = 0u64;
        store::for_each_stream_ordered(&catalog, |object_id, stream| {
            if progress.cancelled() {
                return Err(progress::cancelled("the restore was cancelled"));
            }
            let Some((entry, offset)) = entries.get(index) else {
                bail!(
                    VaultCorrupt,
                    "the restored catalog names more streams than the package carries"
                );
            };
            ensure!(
                entry.object_id == *object_id
                    && entry.stream_id == stream.stream_id
                    && entry.stream_revision == stream.stream_revision
                    && entry.ordered_chunk_commitment == stream.final_commitment,
                VaultCorrupt,
                "a container entry does not describe the stream the catalog holds"
            );
            let head = RECORD_HEADER_LEN as u64 + chur_format::backup::STREAM_ENTRY_LEN as u64;
            extract_range(
                source,
                offset + head,
                entry.ciphertext_length,
                &root_dir.container(&store_id, &stream.container_path_id),
                progress,
            )?;
            index += 1;
            done += entry.ciphertext_length;
            progress.advance(done);
            Ok(())
        })?;
        ensure!(
            index == entries.len(),
            VaultCorrupt,
            "the package carries containers the restored catalog does not name"
        );
        catalog.close()
    })();
    if let Err(error) = installed {
        // Nothing openable was created, so removal is the whole recovery — the
        // same rule `VAULT_DESCRIPTOR_V1.md` §9 applies to an abandoned
        // creation. The directory is this restore's own, because its name was
        // drawn above, so removing it can reach nothing else.
        let _ = std::fs::remove_dir_all(root_dir.vault(&store_id));
        return Err(error);
    }

    // §8 step 9. The descriptor is installed by the atomic rename of §9's last
    // step, then authenticated from the bytes that were written rather than
    // from the value still in memory — the same read-back a creation performs.
    //
    // It is not verified by unlocking. An unlock resolves a credential across
    // the whole registry and returns the first identity it opens, so a device
    // that already holds an identity with this password would hand back that
    // one, and the check would fail on a restore that had in fact succeeded.
    // The root is already here; authenticating with it asks the question this
    // step actually has.
    let entry_name = RegistryName::random()?;
    let local_bytes = local.encode(&root_secret)?;
    vault::install_descriptor(root_dir, &entry_name, &local_bytes)?;
    let confirmed = (|| -> Result<()> {
        let written = std::fs::read(root_dir.registry_entry(&entry_name))
            .map_err(|_| chur_core::err!(IoFailure, "the descriptor could not be read back"))?;
        let parsed = VaultDescriptor::authenticate(&written, Some(&root_secret))?;
        ensure!(
            parsed.vault_id == descriptor.vault_id
                && parsed.object_store.opaque_root_path_id == store_id,
            VaultCorrupt,
            "the installed descriptor is not the one this restore wrote"
        );
        Ok(())
    })();
    if let Err(error) = confirmed {
        // The entry is installed at this point, so the recovery has to remove
        // it as well. Leaving it would consume one of the two identities §11
        // admits, permanently and with nothing able to open it.
        let _ = std::fs::remove_file(root_dir.registry_entry(&entry_name));
        let _ = std::fs::remove_dir_all(root_dir.vault(&store_id));
        return Err(error);
    }

    Ok(RestoreSummary {
        backup_id: manifest.backup_id,
        vault_id: manifest.vault_id,
        stream_count: commit.stream_entry_count,
        created_time_ms: manifest.created_time_ms,
    })
}

/// Walks the record headers, §2.2.
///
/// It reads headers only, so the scan of a 40 GB package costs twelve bytes per
/// record. §13 requires the 32-byte preamble plus every header and payload to
/// total the package length exactly, and this is where that is checked.
fn scan(source: &mut (impl Read + Seek), package_length: u64) -> Result<Vec<RecordSlot>> {
    let mut head = vec![0u8; chur_format::backup::PREAMBLE_LEN];
    source
        .seek(SeekFrom::Start(0))
        .map_err(|_| chur_core::err!(IoFailure, "the package could not be positioned"))?;
    source
        .read_exact(&mut head)
        .map_err(|_| chur_core::err!(VaultCorrupt, "the package is shorter than its preamble"))?;
    match framing_of(&head)? {
        Framing::Native => {}
        Framing::AgeBinary | Framing::AgeArmored => bail!(
            UnsupportedVersion,
            "the package is age-wrapped; unwrap it with the age tool and restore the result"
        ),
    }
    let preamble = PublicPreamble::decode(&head)?;

    let mut slots = Vec::new();
    let mut offset = chur_format::backup::PREAMBLE_LEN as u64;
    for _ in 0..preamble.record_count() {
        let mut header = [0u8; RECORD_HEADER_LEN];
        source.read_exact(&mut header).map_err(|_| {
            chur_core::err!(VaultCorrupt, "the package ends inside a record header")
        })?;
        let parsed = RecordHeader::decode(&header)?;
        let next = offset
            .checked_add(RECORD_HEADER_LEN as u64)
            .and_then(|value| value.checked_add(parsed.payload_length))
            .filter(|value| *value <= package_length)
            .ok_or_else(|| {
                chur_core::err!(VaultCorrupt, "a record claims to end past the package")
            })?;
        slots.push(RecordSlot {
            record_type: parsed.record_type,
            offset,
            payload_length: parsed.payload_length,
        });
        offset = next;
        source
            .seek(SeekFrom::Start(offset))
            .map_err(|_| chur_core::err!(IoFailure, "the package could not be positioned"))?;
    }
    // §13: the preamble plus every header and payload total the length exactly,
    // and §2.2 puts no bytes after the final commit.
    ensure!(
        offset == package_length,
        VaultCorrupt,
        "the package carries bytes outside its declared records"
    );
    ensure!(
        slots.first().map(|slot| slot.record_type) == Some(RecordType::Manifest)
            && slots.last().map(|slot| slot.record_type) == Some(RecordType::FinalCommit),
        VaultCorrupt,
        "the manifest is not first or the final commit is not last"
    );
    Ok(slots)
}

fn find(slots: &[RecordSlot], record_type: RecordType) -> Result<&RecordSlot> {
    let mut found = slots.iter().filter(|slot| slot.record_type == record_type);
    let Some(slot) = found.next() else {
        bail!(VaultCorrupt, "the package is missing a required record");
    };
    ensure!(
        found.next().is_none(),
        VaultCorrupt,
        "the package carries two of a record it may carry once"
    );
    Ok(slot)
}

/// Reads one record's payload, refusing one above `maximum`.
///
/// Every payload this reads is a small fixed-shape record. A container payload
/// is never read this way; it is copied by range.
fn read_payload_bounded(
    source: &mut (impl Read + Seek),
    slot: &RecordSlot,
    maximum: u64,
) -> Result<Vec<u8>> {
    ensure!(
        slot.payload_length <= maximum,
        ResourceLimitExceeded,
        "a package record exceeds the bound its type carries"
    );
    let length = usize::try_from(slot.payload_length)
        .map_err(|_| chur_core::err!(ResourceLimitExceeded, "a record exceeds a usize"))?;
    source
        .seek(SeekFrom::Start(slot.offset + RECORD_HEADER_LEN as u64))
        .map_err(|_| chur_core::err!(IoFailure, "the package could not be positioned"))?;
    let mut payload = vec![0u8; length];
    source
        .read_exact(&mut payload)
        .map_err(|_| chur_core::err!(VaultCorrupt, "the package ends inside a record"))?;
    Ok(payload)
}

/// Reads one container record's inventory entry, without its ciphertext.
fn read_container_entry(
    source: &mut (impl Read + Seek),
    slot: &RecordSlot,
) -> Result<StreamInventoryEntry> {
    let entry_len = chur_format::backup::STREAM_ENTRY_LEN as u64;
    ensure!(
        slot.payload_length >= entry_len,
        VaultCorrupt,
        "a container record is shorter than its inventory entry"
    );
    source
        .seek(SeekFrom::Start(slot.offset + RECORD_HEADER_LEN as u64))
        .map_err(|_| chur_core::err!(IoFailure, "the package could not be positioned"))?;
    let mut bytes = vec![0u8; chur_format::backup::STREAM_ENTRY_LEN];
    source
        .read_exact(&mut bytes)
        .map_err(|_| chur_core::err!(VaultCorrupt, "the package ends inside a container entry"))?;
    let entry = StreamInventoryEntry::decode(&bytes)?;
    ensure!(
        slot.payload_length == entry_len + entry.ciphertext_length,
        VaultCorrupt,
        "a container record's length contradicts its inventory entry"
    );
    Ok(entry)
}

/// Recomputes one container's two commitments from the package's own bytes.
///
/// Neither computation needs a key. §5 of `OBJECT_CONTAINER_V1.md` takes the
/// manifest commitment over the manifest record's nonce and ciphertext, and §10
/// takes the ordered chunk commitment over the chunk records in order; the chunk
/// records are contiguous between the manifest and the final commit, and the
/// commitment is a hash of their concatenation, so the whole range hashes at
/// once without the chunk boundaries the encrypted manifest would give.
///
/// This is what makes a damaged package fail before it installs rather than at
/// the first read afterwards, and it is why a backup can verify a vault whose
/// containers this build could not open.
fn verify_container(
    source: &mut (impl Read + Seek),
    slot: &RecordSlot,
    entry: &StreamInventoryEntry,
    progress: &impl Progress,
) -> Result<()> {
    let content =
        slot.offset + RECORD_HEADER_LEN as u64 + chur_format::backup::STREAM_ENTRY_LEN as u64;
    let mut preamble = vec![0u8; ContainerPreamble::LEN];
    read_exact_at(source, content, &mut preamble)?;
    let head = ContainerPreamble::decode(&preamble)?;
    let manifest_length = u64::from(head.manifest_record_length());
    let first_chunk = ContainerPreamble::LEN as u64 + manifest_length;
    ensure!(
        entry.ciphertext_length >= first_chunk + chur_format::container::FINAL_COMMIT_RECORD_LEN,
        VaultCorrupt,
        "a container in the package is shorter than its own records"
    );
    ensure!(
        manifest_length <= CONTAINER_HEAD_MAX as u64,
        ResourceLimitExceeded,
        "a manifest record exceeds the head this reader admits"
    );

    let mut record = vec![0u8; usize::try_from(manifest_length).unwrap_or(CONTAINER_HEAD_MAX)];
    read_exact_at(source, content + ContainerPreamble::LEN as u64, &mut record)?;
    let nonce_len = chur_core::limits::NONCE_LEN;
    ensure!(
        record.len() > nonce_len,
        VaultCorrupt,
        "a manifest record is shorter than its nonce"
    );
    let nonce = Nonce::from_slice(&record[..nonce_len])?;
    ensure!(
        chur_format::container::manifest_commitment(&nonce, &record[nonce_len..])
            == entry.manifest_commitment,
        VaultCorrupt,
        "a container's manifest does not match the inventory entry that describes it"
    );

    let chunks_end = entry.ciphertext_length - chur_format::container::FINAL_COMMIT_RECORD_LEN;
    let mut committer =
        chur_crypto::commit::Committer::new(chur_crypto::tuple::tag::OBJECT_ORDERED_COMMITMENT);
    hash_range(
        source,
        content + first_chunk,
        chunks_end - first_chunk,
        &mut committer,
        progress,
    )?;
    ensure!(
        committer.finish() == entry.ordered_chunk_commitment,
        VaultCorrupt,
        "a container's chunks do not match the inventory entry that describes them"
    );
    Ok(())
}

/// Reads exactly `buffer.len()` bytes at `offset`.
fn read_exact_at(source: &mut (impl Read + Seek), offset: u64, buffer: &mut [u8]) -> Result<()> {
    source
        .seek(SeekFrom::Start(offset))
        .map_err(|_| chur_core::err!(IoFailure, "the package could not be positioned"))?;
    source
        .read_exact(buffer)
        .map_err(|_| chur_core::err!(VaultCorrupt, "the package ends inside a record"))
}

/// Feeds a byte range of the package into a commitment, one buffer at a time.
fn hash_range(
    source: &mut (impl Read + Seek),
    offset: u64,
    length: u64,
    committer: &mut chur_crypto::commit::Committer,
    progress: &impl Progress,
) -> Result<()> {
    source
        .seek(SeekFrom::Start(offset))
        .map_err(|_| chur_core::err!(IoFailure, "the package could not be positioned"))?;
    let mut buffer = vec![0u8; COPY_BUFFER];
    let mut remaining = length;
    while remaining > 0 {
        if progress.cancelled() {
            return Err(progress::cancelled("the restore was cancelled"));
        }
        let take = usize::try_from(remaining.min(COPY_BUFFER as u64))
            .map_err(|_| chur_core::err!(InternalFailure, "a hash step exceeds a usize"))?;
        source
            .read_exact(&mut buffer[..take])
            .map_err(|_| chur_core::err!(VaultCorrupt, "the package ends inside a record"))?;
        committer.update(&buffer[..take]);
        remaining -= take as u64;
    }
    Ok(())
}

/// Writes one record's payload out to a file.
fn extract(
    source: &mut (impl Read + Seek),
    slot: &RecordSlot,
    path: &Path,
    progress: &impl Progress,
) -> Result<()> {
    extract_range(
        source,
        slot.offset + RECORD_HEADER_LEN as u64,
        slot.payload_length,
        path,
        progress,
    )
}

/// Copies a byte range of the package into a file, one buffer at a time.
fn extract_range(
    source: &mut (impl Read + Seek),
    offset: u64,
    length: u64,
    path: &Path,
    progress: &impl Progress,
) -> Result<()> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)
            .map_err(|_| chur_core::err!(IoFailure, "a restore directory could not be created"))?;
    }
    source
        .seek(SeekFrom::Start(offset))
        .map_err(|_| chur_core::err!(IoFailure, "the package could not be positioned"))?;
    let mut file = std::fs::File::create(path)
        .map_err(|_| chur_core::err!(IoFailure, "a restored file could not be created"))?;
    let mut buffer = vec![0u8; COPY_BUFFER];
    let mut remaining = length;
    while remaining > 0 {
        if progress.cancelled() {
            return Err(progress::cancelled("the restore was cancelled"));
        }
        let take = usize::try_from(remaining.min(COPY_BUFFER as u64))
            .map_err(|_| chur_core::err!(InternalFailure, "a copy step exceeds a usize"))?;
        source
            .read_exact(&mut buffer[..take])
            .map_err(|_| chur_core::err!(VaultCorrupt, "the package ends inside a record"))?;
        file.write_all(&buffer[..take]).map_err(|_| {
            chur_core::err!(StorageUnavailable, "a restored file could not be written")
        })?;
        remaining -= take as u64;
    }
    file.sync_all()
        .map_err(|_| chur_core::err!(IoFailure, "a restored file could not be made durable"))?;
    Ok(())
}

/// Opens the package's descriptor with the credential, §8 steps 2 and 3.
fn open_portable_descriptor(bytes: &[u8], password: &[u8]) -> Result<(VaultDescriptor, Key)> {
    let parsed = VaultDescriptor::parse(bytes)?;
    for slot in &parsed.key_slots {
        ensure!(
            is_portable(slot.slot_type),
            VaultCorrupt,
            "the package carries a device-bound slot §3 excludes"
        );
    }
    let root = vault::open_password_slot_of(&parsed, password)?;
    // §8 step 5 of KEY_SLOTS: the descriptor is authenticated under the root the
    // slot returned, so a package whose body was edited fails here.
    let authenticated = VaultDescriptor::authenticate(bytes, Some(&root))?;
    ensure!(
        authenticated.state == VaultState::Active,
        VaultIncomplete,
        "the package's descriptor is not in the only ordinarily openable state"
    );
    Ok((authenticated, root))
}
