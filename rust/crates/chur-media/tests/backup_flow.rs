//! Creating a portable package and restoring it into an empty root.
//!
//! `docs/ROADMAP.md` Phase 2 makes "backup restore succeeds across Android,
//! iOS, and CLI" an exit criterion. All three run this code; what differs
//! between them is the file the package is written to. These tests are that
//! code path end to end, plus the failures
//! `docs/format/BACKUP_FORMAT_V1.md` §14 names: a truncated package, a
//! reordered one, a wrong credential, and a device-bound slot that must not
//! travel.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

use std::io::Cursor;

use chur_catalog::paths::VaultRoot;
use chur_catalog::query::{ObjectQuery, page};
use chur_catalog::vault::{self, Session};
use chur_core::{ChurStatus, Id, Result};
use chur_crypto::{Key, Nonce, random};
use chur_format::constants::{MediaClass, StreamKind};
use chur_media::import::{CanonicalMedia, SourceCapability};
use chur_media::progress::Uninterrupted;
use chur_media::{backup, export, import};
use chur_sync_protocol::{identity::DeviceIdentity, membership::EnrollmentRecord};
use zeroize::Zeroizing;

const PASSWORD: &[u8] = b"correct horse battery staple";
const NOW: u64 = 1_700_000_000_000;

fn scratch_root() -> VaultRoot {
    let mut path = std::env::temp_dir();
    path.push(format!("chur-backup-{}", random::id().unwrap().to_hex()));
    std::fs::create_dir_all(&path).unwrap();
    VaultRoot::new(path)
}

fn new_vault() -> (VaultRoot, Session) {
    let root = scratch_root();
    let session = vault::create(&root, PASSWORD, NOW)
        .expect("create")
        .activate()
        .expect("activate");
    (root, session)
}

fn plaintext(length: usize, seed: u64) -> Zeroizing<Vec<u8>> {
    let mut bytes = vec![0u8; length];
    let mut state = seed | 1;
    for byte in &mut bytes {
        state ^= state << 13;
        state ^= state >> 7;
        state ^= state << 17;
        *byte = (state & 0xff) as u8;
    }
    Zeroizing::new(bytes)
}

fn import_one(session: &mut Session, length: usize, name: &str, seed: u64) -> (Id, Vec<u8>) {
    let bytes = plaintext(length, seed);
    let object_id = import::import_bytes(
        session,
        SourceCapability {
            seekable: true,
            known_length: Some(length as u64),
            content_type_hint: String::from("image/jpeg"),
            original_filename: Some(String::from(name)),
            capture_time_ms: Some(NOW - 86_400_000),
        },
        CanonicalMedia {
            media_class: MediaClass::Image,
            width: 800,
            height: 600,
            duration_ms: 0,
        },
        "image/jpeg",
        &bytes,
        NOW,
    )
    .expect("import");
    (object_id, bytes.to_vec())
}

fn exported(session: &Session, object_id: &Id) -> Vec<u8> {
    let mut out = Vec::new();
    export::export_stream(
        session,
        object_id,
        StreamKind::Original,
        &mut out,
        &mut Uninterrupted,
    )
    .expect("export");
    out
}

fn rejection<T>(outcome: Result<T>) -> ChurStatus {
    let Err(error) = outcome else {
        panic!("the backup path accepted something the specification forbids");
    };
    error.status()
}

/// A package with content, restored into an empty root, produces a vault whose
/// objects export the bytes that went in. This is the Phase 2 exit criterion.
#[test]
fn a_package_restores_a_vault_whose_objects_export_the_original_bytes() {
    let (_root, mut session) = new_vault();
    let (first, first_bytes) = import_one(&mut session, 300_000, "a.jpg", 0x1111);
    let (second, second_bytes) = import_one(&mut session, 4_096, "b.jpg", 0x2222);
    let source_vault = session.vault_id();

    let mut package = Cursor::new(Vec::new());
    let summary =
        backup::create(&mut session, &mut package, NOW, &mut Uninterrupted).expect("create");
    assert_eq!(summary.vault_id, source_vault);
    assert_eq!(summary.stream_count, 2);
    assert_eq!(summary.slot_count, 1);
    // The manifest, the descriptor, the catalog, two containers, the commit.
    assert_eq!(summary.record_count, 6);
    assert_eq!(summary.package_length, package.get_ref().len() as u64);
    session.lock().expect("lock");

    let destination = scratch_root();
    let restored =
        backup::restore(&destination, &mut package, PASSWORD, &mut Uninterrupted).expect("restore");
    assert_eq!(restored.vault_id, source_vault);
    assert_eq!(restored.backup_id, summary.backup_id);
    assert_eq!(restored.stream_count, 2);

    let opened = vault::unlock_with_password(&destination, PASSWORD, NOW + 2_000).expect("unlock");
    assert_eq!(opened.vault_id(), source_vault);
    let listed = page(opened.catalog_ref().unwrap(), &ObjectQuery::timeline()).expect("page");
    assert_eq!(listed.total_count, 2);
    assert_eq!(exported(&opened, &first), first_bytes);
    assert_eq!(exported(&opened, &second), second_bytes);
}

/// An empty vault is the §14 minimal case: the package holds no container and
/// its inventory commitment is over the slot entries alone.
#[test]
fn an_empty_vault_makes_a_package_that_restores_to_an_empty_vault() {
    let (_root, mut session) = new_vault();
    let mut package = Cursor::new(Vec::new());
    let summary =
        backup::create(&mut session, &mut package, NOW, &mut Uninterrupted).expect("create");
    assert_eq!(summary.stream_count, 0);
    assert_eq!(summary.record_count, 4);
    session.lock().expect("lock");

    let destination = scratch_root();
    backup::restore(&destination, &mut package, PASSWORD, &mut Uninterrupted).expect("restore");
    let opened = vault::unlock_with_password(&destination, PASSWORD, NOW + 2_000).expect("unlock");
    let listed = page(opened.catalog_ref().unwrap(), &ObjectQuery::timeline()).expect("page");
    assert_eq!(listed.total_count, 0);
}

#[test]
fn a_sync_backup_requires_a_portable_identity() {
    let (_root, mut session) = new_vault();
    let identity = DeviceIdentity::from_seeds([1; 32], [2; 32]);
    let enrollment = EnrollmentRecord::initial(
        session.vault_id(),
        Id::new([3; 16]).expect("device"),
        identity.signing_public_key(),
        identity.hpke_public_key(),
    )
    .expect("enrollment")
    .sign(identity.signing_key());
    chur_catalog::sync_membership::provision(session.catalog().expect("catalog"), &enrollment)
        .expect("membership");

    let mut package = Cursor::new(Vec::new());
    assert_eq!(
        rejection(backup::create(
            &mut session,
            &mut package,
            NOW,
            &mut Uninterrupted,
        )),
        ChurStatus::VaultIncomplete
    );
}

#[test]
fn a_sync_backup_authenticates_and_restores_its_recovery_identity() {
    let (_root, mut session) = new_vault();
    let root = session.root_secret().duplicate();
    let vault_id = session.vault_id();
    let device_id = Id::new([11; 16]).expect("device");
    let identity = DeviceIdentity::from_seeds([12; 32], [13; 32]);
    let enrollment = EnrollmentRecord::initial(
        vault_id,
        device_id,
        identity.signing_public_key(),
        identity.hpke_public_key(),
    )
    .expect("enrollment")
    .sign(identity.signing_key());
    let membership =
        chur_catalog::sync_membership::provision(session.catalog().expect("catalog"), &enrollment)
            .expect("membership");
    let mut log =
        chur_catalog::sync_log::load(session.catalog_ref().expect("catalog"), &membership)
            .expect("log");
    let operation = log
        .author(
            Id::new([14; 16]).expect("operation"),
            vault_id,
            device_id,
            Id::new([15; 16]).expect("selector"),
            &Key::new([16; 32]),
            Nonce::new([17; 24]),
            b"checkpoint seed operation",
            identity.signing_key(),
            &membership,
        )
        .expect("operation");
    log.accept_with(
        session.catalog().expect("catalog"),
        &operation,
        &membership,
        |_| Ok(()),
    )
    .expect("accept");
    log.issue_own_checkpoint(
        session.catalog().expect("catalog"),
        &membership,
        &device_id,
        identity.signing_key(),
        NOW,
    )
    .expect("checkpoint");
    let envelope = chur_sync_protocol::identity::DeviceIdentityEnvelope::seal_for_recovery(
        &root,
        vault_id,
        device_id,
        1,
        Nonce::new([18; 24]),
        &identity,
    )
    .expect("envelope");
    chur_catalog::sync_keys::store_portable_identity_envelope(
        session.catalog().expect("catalog"),
        &root,
        &membership,
        &envelope,
    )
    .expect("store identity");

    let mut package = Cursor::new(Vec::new());
    let summary =
        backup::create(&mut session, &mut package, NOW, &mut Uninterrupted).expect("backup");
    assert_eq!(summary.record_count, 5);
    let whole = package.into_inner();
    session.lock().expect("lock");

    let destination = scratch_root();
    backup::restore(
        &destination,
        &mut Cursor::new(whole.clone()),
        PASSWORD,
        &mut Uninterrupted,
    )
    .expect("restore");
    let opened = vault::unlock_with_password(&destination, PASSWORD, NOW + 1).expect("unlock");
    let membership = chur_catalog::sync_membership::load(opened.catalog_ref().expect("catalog"))
        .expect("membership")
        .expect("sync membership");
    assert!(
        chur_catalog::sync_keys::portable_identity_envelope(
            opened.catalog_ref().expect("catalog"),
            opened.root_secret(),
            &membership,
        )
        .expect("identity")
        .is_some()
    );

    let mut damaged = whole;
    let envelope_payload = record_payload_byte(&damaged, 0x05);
    damaged[envelope_payload] ^= 1;
    let destination = scratch_root();
    assert_eq!(
        rejection(backup::restore(
            &destination,
            &mut Cursor::new(damaged),
            PASSWORD,
            &mut Uninterrupted,
        )),
        ChurStatus::VaultCorrupt
    );
    assert!(destination.registry_names().expect("registry").is_empty());
}

/// §7's whole point: the package is authenticated as a unit. A truncated one,
/// one with a byte changed inside a container, and one with two container
/// records swapped must each fail, and none of them may install a vault.
#[test]
fn a_damaged_package_installs_nothing() {
    let (_root, mut session) = new_vault();
    import_one(&mut session, 8_192, "a.jpg", 0x3333);
    import_one(&mut session, 8_192, "b.jpg", 0x4444);
    let mut package = Cursor::new(Vec::new());
    backup::create(&mut session, &mut package, NOW, &mut Uninterrupted).expect("create");
    session.lock().expect("lock");
    let whole = package.into_inner();

    // A package that ends early.
    let destination = scratch_root();
    let truncated = whole[..whole.len() - 64].to_vec();
    assert_eq!(
        rejection(backup::restore(
            &destination,
            &mut Cursor::new(truncated),
            PASSWORD,
            &mut Uninterrupted,
        )),
        ChurStatus::VaultCorrupt
    );
    assert!(destination.registry_names().unwrap().is_empty());

    // A byte changed inside a container's ciphertext, which changes the
    // container's ordered commitment and therefore the inventory. The offset is
    // found rather than guessed: the package is mostly catalog, and a byte
    // picked by arithmetic would land there and test a different rule.
    let destination = scratch_root();
    let mut flipped = whole.clone();
    let at = container_byte(&flipped);
    flipped[at] ^= 0x01;
    let status = rejection(backup::restore(
        &destination,
        &mut Cursor::new(flipped),
        PASSWORD,
        &mut Uninterrupted,
    ));
    assert_eq!(status, ChurStatus::VaultCorrupt);
    assert!(destination.registry_names().unwrap().is_empty());

    // A byte changed inside the catalog export. `VAULT_DESCRIPTOR_V1.md` §5
    // has the descriptor commit to the catalog's header, and the restore checks
    // that before it installs anything.
    let destination = scratch_root();
    let mut catalog = whole.clone();
    let at = catalog_header_byte(&catalog);
    catalog[at] ^= 0x01;
    assert_eq!(
        rejection(backup::restore(
            &destination,
            &mut Cursor::new(catalog),
            PASSWORD,
            &mut Uninterrupted,
        )),
        ChurStatus::VaultCorrupt
    );
    assert!(destination.registry_names().unwrap().is_empty());

    // Bytes appended after the final commit. §2.2 puts none there.
    let destination = scratch_root();
    let mut extended = whole.clone();
    extended.push(0);
    assert_eq!(
        rejection(backup::restore(
            &destination,
            &mut Cursor::new(extended),
            PASSWORD,
            &mut Uninterrupted,
        )),
        ChurStatus::VaultCorrupt
    );
    assert!(destination.registry_names().unwrap().is_empty());
}

/// An offset inside the first container record's ciphertext.
///
/// A container begins with the `CHUROBJ1` magic, and the flip is placed well
/// past it so the damage lands in a chunk record rather than in the preamble.
fn container_byte(package: &[u8]) -> usize {
    let at = package
        .windows(8)
        .position(|window| window == b"CHUROBJ1")
        .expect("the package carries no container");
    at + 4_000
}

/// An offset inside the catalog export's header.
///
/// A SQLCipher file begins with 16 random salt bytes, and the §5 commitment the
/// descriptor carries is over exactly those. The record is found by walking the
/// package's own headers, which is what a reader does.
fn catalog_header_byte(package: &[u8]) -> usize {
    let mut offset = 32usize;
    loop {
        let header = &package[offset..offset + 12];
        let length = usize::try_from(u64::from_be_bytes(header[4..12].try_into().unwrap()))
            .expect("a record length fits a usize");
        if header[0] == 0x03 {
            return offset + 12 + 4;
        }
        offset += 12 + length;
    }
}

fn record_payload_byte(package: &[u8], record_type: u8) -> usize {
    let mut offset = 32usize;
    loop {
        let header = &package[offset..offset + 12];
        let length = usize::try_from(u64::from_be_bytes(header[4..12].try_into().unwrap()))
            .expect("a record length fits a usize");
        if header[0] == record_type {
            return offset + 12 + length / 2;
        }
        offset += 12 + length;
    }
}

/// §8 step 2 obtains the factor before anything else is read. A credential that
/// opens no portable slot fails before a byte reaches the destination.
#[test]
fn a_wrong_credential_restores_nothing() {
    let (_root, mut session) = new_vault();
    import_one(&mut session, 4_096, "a.jpg", 0x5555);
    let mut package = Cursor::new(Vec::new());
    backup::create(&mut session, &mut package, NOW, &mut Uninterrupted).expect("create");
    session.lock().expect("lock");

    let destination = scratch_root();
    assert_eq!(
        rejection(backup::restore(
            &destination,
            &mut package,
            b"not the password",
            &mut Uninterrupted,
        )),
        ChurStatus::AuthenticationFailed
    );
    assert!(destination.registry_names().unwrap().is_empty());
    assert!(!destination.base().join("vaults").exists());
}

/// §3 excludes every device-bound slot. A vault whose only extra slot is an
/// Apple Keychain slot still backs up, and the package carries the password and
/// recovery slots alone — a package carrying a `ThisDeviceOnly` item would
/// restore a vault with a slot nothing on the new device can open.
#[test]
fn a_device_bound_slot_does_not_travel() {
    let (_root, mut session) = new_vault();
    session.add_recovery_slot().expect("recovery");
    session
        .add_apple_keychain_slot(random::id().unwrap())
        .expect("keychain");
    assert_eq!(session.slots().len(), 3);

    let mut package = Cursor::new(Vec::new());
    let summary =
        backup::create(&mut session, &mut package, NOW, &mut Uninterrupted).expect("create");
    assert_eq!(summary.slot_count, 2);
    session.lock().expect("lock");

    let destination = scratch_root();
    backup::restore(&destination, &mut package, PASSWORD, &mut Uninterrupted).expect("restore");
    let opened = vault::unlock_with_password(&destination, PASSWORD, NOW + 2_000).expect("unlock");
    assert_eq!(opened.slots().len(), 2);
    assert!(
        opened
            .slots()
            .iter()
            .all(|(_, kind, _)| *kind != chur_format::constants::SlotType::AppleKeychain),
        "a device-bound slot reached the restored vault"
    );
}

/// §11: one package holds one vault identity. A restore into a root that
/// already holds the two identities `VAULT_DESCRIPTOR_V1.md` §11 admits is
/// refused rather than making a third.
#[test]
fn a_restore_into_a_full_registry_is_refused() {
    let (_root, mut session) = new_vault();
    let mut package = Cursor::new(Vec::new());
    backup::create(&mut session, &mut package, NOW, &mut Uninterrupted).expect("create");
    session.lock().expect("lock");

    let destination = scratch_root();
    vault::create(&destination, b"first identity here", NOW)
        .expect("first")
        .activate()
        .expect("activate")
        .lock()
        .expect("lock");
    vault::create(&destination, b"second identity here", NOW)
        .expect("second")
        .activate()
        .expect("activate")
        .lock()
        .expect("lock");

    assert_eq!(
        rejection(backup::restore(
            &destination,
            &mut package,
            PASSWORD,
            &mut Uninterrupted,
        )),
        ChurStatus::Conflict
    );
}

/// §2.3: an `age`-wrapped package is named rather than reported as not a Chur
/// backup. The two are different problems and the user can only act on one of
/// them.
#[test]
fn an_age_wrapped_package_is_named_as_wrapped() {
    let destination = scratch_root();
    let mut wrapped = Cursor::new(b"age-encryption.org/v1\nnot really".to_vec());
    assert_eq!(
        rejection(backup::restore(
            &destination,
            &mut wrapped,
            PASSWORD,
            &mut Uninterrupted,
        )),
        ChurStatus::UnsupportedVersion
    );

    let mut foreign = Cursor::new(b"PK\x03\x04not a backup at all".to_vec());
    assert_eq!(
        rejection(backup::restore(
            &destination,
            &mut foreign,
            PASSWORD,
            &mut Uninterrupted,
        )),
        ChurStatus::VaultCorrupt
    );
}

/// The package copies ciphertext and never decrypts it: a restore of a vault
/// whose containers this build could not open would still be byte-exact. The
/// observable form of that is simpler — the container bytes in the package are
/// the container bytes on disk.
#[test]
fn a_container_travels_as_its_own_ciphertext() {
    let (root, mut session) = new_vault();
    let (object_id, _) = import_one(&mut session, 20_000, "a.jpg", 0x6666);
    let store_id = session.object_store_id();
    let stream = chur_catalog::store::streams(session.catalog_ref().unwrap(), &object_id)
        .expect("streams")
        .into_iter()
        .find(|stream| stream.stream_kind == StreamKind::Original)
        .expect("original");
    let on_disk =
        std::fs::read(root.container(&store_id, &stream.container_path_id)).expect("container");

    let mut package = Cursor::new(Vec::new());
    backup::create(&mut session, &mut package, NOW, &mut Uninterrupted).expect("create");
    let bytes = package.into_inner();

    let at = bytes
        .windows(on_disk.len())
        .position(|window| window == on_disk.as_slice());
    assert!(
        at.is_some(),
        "the package does not carry the container's own bytes"
    );
}

/// A caller that cancels after the first progress report.
///
/// `chur_vault_lock` drains and joins every operation before it takes the
/// session, so an operation that cannot stop is a lock that cannot complete.
/// A vault can hold one object of a terabyte, and a copy that checked its flag
/// only between files would make the lock wait for that whole copy.
struct CancelAtOnce {
    cancelled: std::cell::Cell<bool>,
}

impl chur_media::progress::Progress for CancelAtOnce {
    fn cancelled(&self) -> bool {
        let seen = self.cancelled.get();
        self.cancelled.set(true);
        seen
    }

    fn advance(&mut self, _processed: u64) {}
}

#[test]
fn a_backup_stops_inside_a_container_rather_than_between_files() {
    let (_root, mut session) = new_vault();
    // Several buffers' worth, so a copy that only checked between files would
    // run to the end of this one.
    import_one(&mut session, 262_144 * 4, "big.jpg", 0x7777);

    let mut package = Cursor::new(Vec::new());
    let mut progress = CancelAtOnce {
        cancelled: std::cell::Cell::new(false),
    };
    assert_eq!(
        rejection(backup::create(
            &mut session,
            &mut package,
            NOW,
            &mut progress
        )),
        ChurStatus::Cancelled
    );
    assert!(
        (package.into_inner().len() as u64) < 262_144 * 4,
        "a cancelled backup wrote the whole object"
    );
}

/// The same for the restore half: a package with one large object must stop
/// inside it, not after it.
#[test]
fn a_restore_stops_inside_a_container_rather_than_between_files() {
    let (_root, mut session) = new_vault();
    import_one(&mut session, 262_144 * 4, "big.jpg", 0x8888);
    let mut package = Cursor::new(Vec::new());
    backup::create(&mut session, &mut package, NOW, &mut Uninterrupted).expect("create");
    session.lock().expect("lock");

    let destination = scratch_root();
    let mut progress = CancelAtOnce {
        cancelled: std::cell::Cell::new(false),
    };
    assert_eq!(
        rejection(backup::restore(
            &destination,
            &mut package,
            PASSWORD,
            &mut progress,
        )),
        ChurStatus::Cancelled
    );
    assert!(destination.registry_names().unwrap().is_empty());
}

/// A package restored beside the vault it came from must not touch that vault.
///
/// The local path identifiers of `VAULT_DESCRIPTOR_V1.md` §6 name this device's
/// storage layout, not the identity, so a restore draws its own rather than
/// reusing the source device's. Before that, restoring a package next to its
/// own vault wrote into that vault's directory, and a failure on the way
/// removed it.
#[test]
fn a_restore_beside_its_source_leaves_the_source_alone() {
    let (root, mut session) = new_vault();
    let (object_id, bytes) = import_one(&mut session, 40_000, "a.jpg", 0x9999);
    let source_store = session.object_store_id();
    let source_vault_id = session.vault_id();

    let mut package = Cursor::new(Vec::new());
    backup::create(&mut session, &mut package, NOW, &mut Uninterrupted).expect("create");
    session.lock().expect("lock");

    // The same root, which already holds the identity this package carries.
    let restored =
        backup::restore(&root, &mut package, PASSWORD, &mut Uninterrupted).expect("restore");
    assert_eq!(restored.vault_id, source_vault_id);
    assert_eq!(root.registry_names().unwrap().len(), 2);

    // The source directory is untouched and still opens its own objects.
    assert!(
        root.vault(&source_store).exists(),
        "the restore removed the vault it was taken from"
    );
    let reopened = vault::unlock_with_password(&root, PASSWORD, NOW + 2_000).expect("unlock");
    assert_eq!(reopened.vault_id(), source_vault_id);
    assert_eq!(exported(&reopened, &object_id), bytes);
}

/// §7.1 orders slot entries by ascending `slot_id`, not by the order a
/// descriptor happens to store them in. Two writers over one vault must emit
/// one sequence, so a package's commitment may not depend on enrolment order.
#[test]
fn the_slot_inventory_order_does_not_depend_on_enrolment_order() {
    // Enrolling a second slot appends it to the descriptor, so at least one of
    // these two vaults has a descriptor whose slots are not in ascending
    // identifier order. Both must still restore, which is what proves the
    // commitment was taken in the sorted order rather than the stored one.
    for _ in 0..8 {
        let (_root, mut session) = new_vault();
        session.add_recovery_slot().expect("recovery");
        session
            .add_apple_keychain_slot(random::id().unwrap())
            .expect("keychain");
        let mut package = Cursor::new(Vec::new());
        let summary =
            backup::create(&mut session, &mut package, NOW, &mut Uninterrupted).expect("create");
        assert_eq!(summary.slot_count, 2);
        session.lock().expect("lock");

        let destination = scratch_root();
        backup::restore(&destination, &mut package, PASSWORD, &mut Uninterrupted).expect("restore");
    }
}
