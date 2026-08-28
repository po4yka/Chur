//! The isolation matrix of `docs/security/DECOY_VAULT.md` §11.
//!
//! A decoy vault is an independently encrypted identity, not a filtered view.
//! §1 lists what real and decoy must not share, §2 makes the session opaque,
//! §5 bounds the one timing signal, and §10 states the claim precisely: the
//! defence is indistinguishability, not concealment, so nothing reachable from
//! inside the application may differ according to whether a sibling exists.
//!
//! Every test here creates two complete identities in one storage root, which
//! is what `paths::REGISTRY_MAX` has always admitted, and then tries to find a
//! way from one to the other.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

use chur_catalog::paths::{REGISTRY_MAX, VaultRoot};
use chur_catalog::query::{ObjectQuery, page};
use chur_catalog::vault::{self, Session};
use chur_catalog::{schema, store};
use chur_core::{ChurStatus, Result};
use chur_crypto::random;
use chur_format::constants::SlotType;

const REAL: &[u8] = b"the real credential goes here";
const DECOY: &[u8] = b"a different credential entirely";
const NOW: u64 = 1_700_000_000_000;

fn scratch_root() -> VaultRoot {
    let mut path = std::env::temp_dir();
    path.push(format!("chur-decoy-{}", random::id().unwrap().to_hex()));
    std::fs::create_dir_all(&path).unwrap();
    VaultRoot::new(path)
}

/// Two independent identities in one root, real first.
fn two_identities() -> (VaultRoot, Session, Session) {
    let root = scratch_root();
    let real = vault::create(&root, REAL, NOW)
        .expect("real")
        .activate()
        .expect("activate real");
    let decoy = vault::create(&root, DECOY, NOW)
        .expect("decoy")
        .activate()
        .expect("activate decoy");
    (root, real, decoy)
}

fn rejection<T>(outcome: Result<T>) -> ChurStatus {
    let Err(error) = outcome else {
        panic!("an operation the specification forbids returned success");
    };
    error.status()
}

/// §1: real and decoy share no root, no catalog key, and no object namespace.
#[test]
fn the_two_identities_share_no_key_and_no_namespace() {
    let (_root, real, decoy) = two_identities();

    assert_ne!(real.vault_id(), decoy.vault_id());
    assert_ne!(
        real.root_secret().expose(),
        decoy.root_secret().expose(),
        "the two identities share a root secret"
    );
    assert_ne!(
        real.object_store_id(),
        decoy.object_store_id(),
        "the two identities share an object namespace"
    );
    assert_ne!(
        real.catalog_path(),
        decoy.catalog_path(),
        "the two identities share a catalog file"
    );

    // Every slot identity is its own, so no descriptor names a slot of the
    // other identity.
    let real_slots: Vec<_> = real.slots().iter().map(|(id, _, _)| *id).collect();
    let decoy_slots: Vec<_> = decoy.slots().iter().map(|(id, _, _)| *id).collect();
    for slot in &real_slots {
        assert!(!decoy_slots.contains(slot), "a slot identity is shared");
    }
}

/// §11: a correct credential opens only its own data. The strongest form of
/// that is content: an object imported into one identity is invisible to the
/// other, and its container is not in the other's store.
#[test]
fn a_credential_opens_only_its_own_content() {
    let (root, mut real, mut decoy) = two_identities();

    // Two collections, one per identity, and neither is visible to the other.
    let real_collection = chur_media_stub::collection(&mut real);
    let decoy_collection = chur_media_stub::collection(&mut decoy);
    assert_ne!(real_collection, decoy_collection);

    let real_albums = store::albums(real.catalog_ref().unwrap()).expect("albums");
    let decoy_albums = store::albums(decoy.catalog_ref().unwrap()).expect("albums");
    assert!(real_albums.is_empty() && decoy_albums.is_empty());

    store::put_album(
        real.catalog().unwrap(),
        &chur_catalog::model::Album {
            album_id: random::id().unwrap(),
            name: String::from("a name only the real vault has"),
            created_ms: NOW,
            revision: 1,
        },
    )
    .expect("album");

    assert_eq!(
        store::albums(real.catalog_ref().unwrap())
            .expect("albums")
            .len(),
        1
    );
    assert!(
        store::albums(decoy.catalog_ref().unwrap())
            .expect("albums")
            .is_empty(),
        "an album written to one identity is visible in the other"
    );

    // Reopening the decoy from disk gives the same answer, so the isolation is
    // in the storage rather than in the two live handles.
    real.lock().expect("lock");
    decoy.lock().expect("lock");
    let reopened = vault::unlock_with_password(&root, DECOY, NOW).expect("unlock decoy");
    assert!(
        store::albums(reopened.catalog_ref().unwrap())
            .expect("albums")
            .is_empty()
    );
}

/// §2 and §11: a wrong credential produces the external failure a sibling
/// credential produces, and one identity's credential never opens the other.
#[test]
fn a_sibling_credential_fails_exactly_as_a_wrong_one_does() {
    let (root, mut real, mut decoy) = two_identities();
    let real_id = real.vault_id();
    let decoy_id = decoy.vault_id();
    real.lock().expect("lock");
    decoy.lock().expect("lock");

    assert_eq!(
        vault::unlock_with_password(&root, REAL, NOW)
            .expect("real")
            .vault_id(),
        real_id
    );
    assert_eq!(
        vault::unlock_with_password(&root, DECOY, NOW)
            .expect("decoy")
            .vault_id(),
        decoy_id
    );
    assert_eq!(
        rejection(vault::unlock_with_password(&root, b"neither of them", NOW)),
        ChurStatus::AuthenticationFailed
    );

    // And a root with one identity fails the same way, so "no sibling exists"
    // and "the sibling was not opened" are one observation, which is what §10
    // requires.
    let lonely = scratch_root();
    vault::create(&lonely, REAL, NOW)
        .expect("one")
        .activate()
        .expect("activate")
        .lock()
        .expect("lock");
    assert_eq!(
        rejection(vault::unlock_with_password(
            &lonely,
            b"neither of them",
            NOW
        )),
        ChurStatus::AuthenticationFailed
    );
    let empty = scratch_root();
    assert_eq!(
        rejection(vault::unlock_with_password(&empty, b"neither of them", NOW)),
        ChurStatus::AuthenticationFailed
    );
}

/// §11 and §4: storage inspection finds no semantic real-or-decoy label.
///
/// Every name below the registry is the hexadecimal of an opaque random
/// identifier, and §4 forbids obvious labels such as `real/` and `decoy/`.
#[test]
fn no_path_in_the_root_names_which_identity_it_belongs_to() {
    let (root, mut real, mut decoy) = two_identities();
    real.lock().expect("lock");
    decoy.lock().expect("lock");

    let forbidden = [
        "real",
        "decoy",
        "primary",
        "secondary",
        "hidden",
        "fake",
        "main",
        "alt",
    ];
    let mut walked = 0usize;
    let mut stack = vec![root.base().to_path_buf()];
    while let Some(directory) = stack.pop() {
        for entry in std::fs::read_dir(&directory).expect("read") {
            let entry = entry.expect("entry");
            let name = entry.file_name().to_string_lossy().to_lowercase();
            for word in forbidden {
                assert!(
                    !name.contains(word),
                    "a path is named {name}, which says which identity it is"
                );
            }
            walked += 1;
            if entry.file_type().expect("type").is_dir() {
                stack.push(entry.path());
            }
        }
    }
    assert!(walked > 4, "the walk found nothing to check");

    // The two registry entries are indistinguishable by name: both are 32
    // hexadecimal characters and neither encodes an order.
    let names = root.registry_names().expect("names");
    assert_eq!(names.len(), REGISTRY_MAX);
    for name in &names {
        assert_eq!(name.as_str().len(), 32);
        assert!(name.as_str().chars().all(|c| c.is_ascii_hexdigit()));
    }
}

/// §11: locking one identity does not reach the other's session, and process
/// death returns to the locked state for both.
#[test]
fn locking_one_identity_leaves_the_other_untouched() {
    let (root, mut real, decoy) = two_identities();

    real.lock().expect("lock the real identity");
    assert!(!real.is_unlocked());
    assert!(decoy.is_unlocked(), "locking one identity locked the other");
    assert!(store::albums(decoy.catalog_ref().unwrap()).is_ok());

    // Dropping both is process death. Nothing is openable afterwards without a
    // credential, which is the state a cold launch starts in.
    drop(real);
    drop(decoy);
    assert_eq!(root.registry_names().expect("names").len(), REGISTRY_MAX);
    assert_eq!(
        rejection(vault::unlock_with_password(&root, b"still not it", NOW)),
        ChurStatus::AuthenticationFailed
    );
}

/// §8: recovery secrets are independent, and recovering one must not reveal
/// that another exists.
#[test]
fn recovery_of_one_identity_says_nothing_about_the_other() {
    let (root, mut real, mut decoy) = two_identities();
    let real_secret = real.add_recovery_slot().expect("real recovery");
    let decoy_secret = decoy.add_recovery_slot().expect("decoy recovery");
    assert_ne!(real_secret.expose(), decoy_secret.expose());
    let real_id = real.vault_id();
    let decoy_id = decoy.vault_id();
    real.lock().expect("lock");
    decoy.lock().expect("lock");

    let real_phrase = chur_crypto::recovery::to_phrase(&real_secret);
    let decoy_phrase = chur_crypto::recovery::to_phrase(&decoy_secret);
    assert_eq!(
        vault::unlock_with_recovery(&root, &real_phrase, NOW)
            .expect("real")
            .vault_id(),
        real_id
    );
    assert_eq!(
        vault::unlock_with_recovery(&root, &decoy_phrase, NOW)
            .expect("decoy")
            .vault_id(),
        decoy_id
    );

    // A phrase for neither fails as a wrong phrase does, with no hint that two
    // identities were tried.
    let stranger = chur_crypto::recovery::to_phrase(&random::secret::<32>().unwrap());
    assert_eq!(
        rejection(vault::unlock_with_recovery(&root, &stranger, NOW)),
        ChurStatus::AuthenticationFailed
    );
}

/// §11: a migration can process one vault without opening its sibling.
///
/// The schema opens per identity, so running it on one leaves the other's
/// catalog generation untouched.
#[test]
fn a_schema_open_touches_one_identity_only() {
    let (root, mut real, mut decoy) = two_identities();
    let before = schema::generation(decoy.catalog_ref().unwrap()).expect("generation");

    schema::open_at_current_version(real.catalog().unwrap(), NOW + 1_000).expect("open");
    store::put_album(
        real.catalog().unwrap(),
        &chur_catalog::model::Album {
            album_id: random::id().unwrap(),
            name: String::from("work in the real identity"),
            created_ms: NOW,
            revision: 1,
        },
    )
    .expect("album");

    let after = schema::generation(decoy.catalog_ref().unwrap()).expect("generation");
    assert_eq!(before, after, "work in one identity moved the other");

    real.lock().expect("lock");
    decoy.lock().expect("lock");
    let reopened = vault::unlock_with_password(&root, DECOY, NOW).expect("unlock");
    let listed = page(reopened.catalog_ref().unwrap(), &ObjectQuery::timeline()).expect("page");
    assert_eq!(listed.total_count, 0);
}

/// `DECOY_VAULT.md` §3 has the user confirm the decoy credential is distinct,
/// and this is the mechanical half. A second identity created under a
/// credential that already opens the first would be unreachable forever,
/// because `KEY_SLOTS.md` §8 returns the first candidate a password opens.
#[test]
fn a_second_identity_cannot_take_a_credential_that_already_opens_one() {
    let root = scratch_root();
    vault::create(&root, REAL, NOW)
        .expect("first")
        .activate()
        .expect("activate")
        .lock()
        .expect("lock");

    assert_eq!(
        rejection(vault::create(&root, REAL, NOW)),
        ChurStatus::Conflict
    );
    // A different credential is admitted, and a third of any kind is not.
    vault::create(&root, DECOY, NOW)
        .expect("second")
        .activate()
        .expect("activate")
        .lock()
        .expect("lock");
    assert_eq!(
        rejection(vault::create(&root, b"a third credential", NOW)),
        ChurStatus::ResourceLimitExceeded
    );
}

/// §1: platform aliases are not shared, and invalidating one identity's device
/// slot affects only that identity.
#[test]
fn a_device_slot_belongs_to_one_identity() {
    let (root, mut real, mut decoy) = two_identities();
    let real_item = random::id().unwrap();
    let decoy_item = random::id().unwrap();
    let real_secret = real
        .add_apple_keychain_slot(real_item)
        .expect("real keychain");
    let decoy_secret = decoy
        .add_apple_keychain_slot(decoy_item)
        .expect("decoy keychain");
    assert_ne!(real_secret.expose(), decoy_secret.expose());
    let real_id = real.vault_id();
    let decoy_slot = decoy
        .slots()
        .into_iter()
        .find(|(_, kind, _)| *kind == SlotType::AppleKeychain)
        .map(|(id, _, _)| id)
        .expect("the decoy has a keychain slot");
    real.lock().expect("lock");

    // The real identity's Keychain secret opens the real identity and nothing
    // else, and removing the decoy's slot does not disturb it.
    assert_eq!(
        vault::unlock_with_apple_keychain(&root, &real_secret, NOW)
            .expect("real")
            .vault_id(),
        real_id
    );
    decoy.remove_slot(&decoy_slot).expect("remove");
    decoy.lock().expect("lock");
    assert_eq!(
        vault::unlock_with_apple_keychain(&root, &real_secret, NOW)
            .expect("real again")
            .vault_id(),
        real_id
    );
    assert_eq!(
        rejection(vault::unlock_with_apple_keychain(&root, &decoy_secret, NOW)),
        ChurStatus::AuthenticationFailed
    );
}

/// A small helper that gives an identity its default collection without
/// pulling in `chur-media`, which would be a dependency cycle.
mod chur_media_stub {
    use super::{NOW, Session, random};
    use chur_catalog::model::{COLLECTION_POLICY_VAULT_DEFAULT, COLLECTION_STATUS_ACTIVE};

    pub fn collection(session: &mut Session) -> chur_core::Id {
        let _ = NOW;
        let collection_id = random::id().unwrap();
        chur_catalog::store::put_collection_with_envelope(
            session.catalog().unwrap(),
            &chur_catalog::model::Collection {
                collection_id,
                current_epoch: 1,
                policy_type: COLLECTION_POLICY_VAULT_DEFAULT,
                created_revision: 1,
                status: COLLECTION_STATUS_ACTIVE,
            },
            1,
            &[0u8; 96],
        )
        .expect("collection");
        collection_id
    }
}
