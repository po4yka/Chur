//! The constant Argon2id cost of one password attempt.
//!
//! `docs/security/KEY_SLOTS.md` §8: "An unlock attempt that uses a password
//! runs exactly two Argon2id derivations, whatever the device holds." A
//! constant that cannot be observed cannot be enforced, so this counts.
//!
//! It is an integration test rather than a unit test because the counter is
//! process-wide, and it is one test function rather than several for the same
//! reason: the test harness runs a binary's tests on parallel threads, so a
//! second function in this file would be counted by this one.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

use chur_catalog::paths::VaultRoot;
use chur_catalog::vault;
use chur_core::ChurStatus;
use chur_crypto::password::derivations_performed;
use chur_crypto::random;

const PASSWORD: &[u8] = b"correct horse battery staple";

fn scratch() -> VaultRoot {
    let mut path = std::env::temp_dir();
    path.push(format!("chur-argon2-{}", random::id().unwrap().to_hex()));
    std::fs::create_dir_all(&path).unwrap();
    VaultRoot::new(path)
}

/// Runs `body` and reports how many Argon2id derivations it performed.
fn derivations(body: impl FnOnce()) -> u64 {
    let before = derivations_performed();
    body();
    derivations_performed() - before
}

#[test]
fn every_password_attempt_costs_exactly_two_derivations() {
    let root = scratch();

    // An empty registry: both candidates are dummies.
    assert_eq!(
        derivations(|| {
            let outcome = vault::unlock_with_password(&root, PASSWORD, 1);
            assert_eq!(
                outcome.err().map(|error| error.status()),
                Some(ChurStatus::AuthenticationFailed)
            );
        }),
        2,
        "an attempt against an empty registry did not cost two derivations"
    );

    // One identity: one real candidate and one dummy. Creation itself costs two
    // derivations, one to seal the slot and one to verify it from the bytes
    // that were written, so it is measured outside the assertions below.
    drop(
        vault::create(&root, PASSWORD, 1)
            .expect("create")
            .activate()
            .expect("activate"),
    );

    assert_eq!(
        derivations(|| {
            vault::unlock_with_password(&root, PASSWORD, 1).expect("unlock");
        }),
        2,
        "a successful attempt against one identity did not cost two derivations"
    );
    assert_eq!(
        derivations(|| {
            assert!(vault::unlock_with_password(&root, b"wrong", 1).is_err());
        }),
        2,
        "a failed attempt against one identity did not cost two derivations"
    );

    // Two identities: both candidates are real, and the cost does not change.
    drop(
        vault::create(&root, b"the other identity", 1)
            .expect("create")
            .activate()
            .expect("activate"),
    );
    assert_eq!(root.registry_names().unwrap().len(), 2);

    for (label, password) in [
        ("the first identity", PASSWORD.to_vec()),
        ("the second identity", b"the other identity".to_vec()),
        ("neither identity", b"neither".to_vec()),
    ] {
        assert_eq!(
            derivations(|| {
                let _ = vault::unlock_with_password(&root, &password, 1);
            }),
            2,
            "an attempt matching {label} did not cost two derivations"
        );
    }

    // §6: the recovery mnemonic is a presentation encoding of 32 canonical
    // random bytes, not a low-entropy password, so its KEK comes from HKDF
    // alone and its unlock runs no Argon2id at all.
    let recovery_root = scratch();
    let mut creation = vault::create(&recovery_root, PASSWORD, 1).expect("create");
    let secret = creation.add_recovery_slot().expect("recovery");
    let phrase = chur_crypto::recovery::to_phrase(&secret);
    drop(creation.activate().expect("activate"));
    assert_eq!(
        derivations(|| {
            vault::unlock_with_recovery(&recovery_root, &phrase, 1).expect("recover");
        }),
        0,
        "a recovery unlock ran an Argon2id derivation"
    );
}
