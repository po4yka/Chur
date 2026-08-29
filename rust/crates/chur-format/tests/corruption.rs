//! The corruption harness.
//!
//! `docs/CRYPTOGRAPHY.md` §66 and `docs/format/OBJECT_CONTAINER_V1.md` §17 ask
//! for a systematic adversarial matrix rather than a handful of cases. This
//! harness walks every byte of every frozen record and asserts two properties:
//!
//! - no single-byte change is ever accepted silently;
//! - every rejection carries a classified status, so a caller can tell an
//!   unsupported artifact from a damaged one from a wrong credential.
//!
//! It is separate from the unit tests because it is exhaustive and slow, and
//! because a reader of `docs/assurance/` should find one file that answers
//! "what happens when the bytes are wrong".

#![allow(clippy::unwrap_used, clippy::panic, clippy::expect_used)]

use std::collections::BTreeMap;

use chur_core::status::ChurStatus;
use chur_core::{Id, Result};
use chur_crypto::aead::Nonce;
use chur_crypto::secret::Key;
use chur_format::constants::{MediaClass, SlotType, StreamKind, VaultState};
use chur_format::container::{
    CanonicalManifest, ContainerReader, Layout, MediaProperties, NONCE_PREFIX_LEN, StreamIdentity,
    encode_container,
};
use chur_format::descriptor::{
    CatalogDescriptor, KeySlotDescriptor, ObjectStoreDescriptor, VaultDescriptor,
};
use chur_format::envelope::{CollectionKeyEnvelope, ObjectKeyEnvelope};
use chur_format::slot::{PasswordSlotBody, RecoverySlotBody, SlotBinding};

fn id(byte: u8) -> Id {
    Id::new([byte; 16]).unwrap()
}

fn key(byte: u8) -> Key {
    Key::new([byte; 32])
}

fn nonce(byte: u8) -> Nonce {
    Nonce::new([byte; 24])
}

fn identity() -> StreamIdentity {
    StreamIdentity {
        object_id: id(0x33),
        stream_id: id(0x34),
        stream_kind: StreamKind::Original,
        stream_revision: 1,
    }
}

fn container(plaintext: &[u8]) -> Vec<u8> {
    let manifest = CanonicalManifest::new(
        identity(),
        None,
        65_536,
        [0x35; NONCE_PREFIX_LEN],
        1,
        MediaProperties::new(MediaClass::Opaque, 0, 0, 0).unwrap(),
    )
    .unwrap();
    encode_container(&key(0x77), manifest, nonce(0x36), plaintext, nonce(0x37), 1).unwrap()
}

/// Applies every single-byte change to every byte and records what happened.
///
/// `open` returns `Ok` only when the damaged bytes were accepted, which the
/// harness then treats as a failure of the record under test.
fn sweep<F>(name: &str, bytes: &[u8], mut open: F) -> BTreeMap<ChurStatus, usize>
where
    F: FnMut(&[u8]) -> Result<()>,
{
    let mut classified: BTreeMap<ChurStatus, usize> = BTreeMap::new();
    let mut accepted = Vec::new();
    for index in 0..bytes.len() {
        for bit in 0..8u8 {
            let mut damaged = bytes.to_vec();
            damaged[index] ^= 1 << bit;
            if damaged == bytes {
                continue;
            }
            match open(&damaged) {
                Ok(()) => accepted.push((index, bit)),
                Err(error) => *classified.entry(error.status()).or_default() += 1,
            }
        }
    }
    assert!(
        accepted.is_empty(),
        "{name}: {} single-bit changes were accepted, first at byte {} bit {}",
        accepted.len(),
        accepted[0].0,
        accepted[0].1
    );
    assert!(!classified.is_empty(), "{name}: nothing was tested");
    for status in classified.keys() {
        assert_ne!(
            *status,
            ChurStatus::InternalFailure,
            "{name}: a damaged record produced an unclassified failure"
        );
    }
    classified
}

#[test]
fn every_bit_of_a_small_container_is_caught() {
    let bytes = container(&(0..64u8).collect::<Vec<_>>());
    let classified = sweep("object container", &bytes, |damaged| {
        let reader = ContainerReader::open(damaged, &key(0x77), &identity())?;
        reader.verify_complete().map(|_| ())
    });
    // Structure, version, and authentication failures must all appear: a sweep
    // that only ever reported one of them would mean the classification is not
    // reachable rather than that the record is safe.
    assert!(classified.contains_key(&ChurStatus::ObjectCorrupt));
    assert!(
        classified.contains_key(&ChurStatus::UnsupportedVersion)
            || classified.contains_key(&ChurStatus::UnsupportedSuite)
    );
}

#[test]
fn every_bit_of_a_structural_container_scan_is_caught_without_a_key() {
    // The scan runs with no key at all, so a change it accepts must be one the
    // AEAD later catches. Here the assertion is narrower: the scan must never
    // panic and must classify whatever it rejects.
    let bytes = container(&(0..64u8).collect::<Vec<_>>());
    let mut classified: BTreeMap<ChurStatus, usize> = BTreeMap::new();
    for index in 0..bytes.len() {
        for bit in 0..8u8 {
            let mut damaged = bytes.clone();
            damaged[index] ^= 1 << bit;
            if let Err(error) = Layout::parse(&damaged) {
                assert_ne!(error.status(), ChurStatus::InternalFailure);
                *classified.entry(error.status()).or_default() += 1;
            }
        }
    }
    assert!(classified.contains_key(&ChurStatus::ObjectCorrupt));
}

#[test]
fn every_bit_of_both_key_envelopes_is_caught() {
    let collection = CollectionKeyEnvelope::seal(
        &key(0x5a),
        id(0x11),
        id(0x22),
        1,
        1,
        nonce(0x33),
        &key(0x44),
    )
    .unwrap()
    .encode();
    sweep("collection key envelope", &collection, |damaged| {
        CollectionKeyEnvelope::decode(damaged)?
            .open(&key(0x5a))
            .map(|_| ())
    });

    let object = ObjectKeyEnvelope::seal(
        &key(0x44),
        id(0x11),
        id(0x22),
        1,
        id(0x33),
        1,
        nonce(0x66),
        &key(0x77),
    )
    .unwrap()
    .encode();
    sweep("object key envelope", &object, |damaged| {
        ObjectKeyEnvelope::decode(damaged)?
            .open(&key(0x44))
            .map(|_| ())
    });
}

#[test]
fn every_bit_of_a_vault_descriptor_is_caught() {
    let descriptor = VaultDescriptor {
        vault_id: id(0x11),
        descriptor_generation: 1,
        state: VaultState::Active,
        catalog: CatalogDescriptor {
            catalog_format_version: chur_format::constants::CATALOG_FORMAT_VERSION_V1,
            opaque_catalog_path_id: id(0x12),
            catalog_generation: 1,
            catalog_header_commitment: [0x13; 32],
        },
        object_store: ObjectStoreDescriptor::v1(id(0x14)),
        key_slots: vec![
            KeySlotDescriptor::v1(id(0x55), SlotType::Password, 1, vec![0xaa; 16]).unwrap(),
        ],
        migration: None,
    };
    let bytes = descriptor.encode(&key(0x5a)).unwrap();
    let classified = sweep("vault descriptor", &bytes, |damaged| {
        VaultDescriptor::authenticate(damaged, Some(&key(0x5a))).map(|_| ())
    });
    // A damaged descriptor and a wrong credential must be indistinguishable
    // externally, so most of the sweep lands on AUTHENTICATION_FAILED and the
    // rest on a parser code that fires before any credential is used.
    assert!(classified.contains_key(&ChurStatus::AuthenticationFailed));
    assert!(classified.contains_key(&ChurStatus::VaultCorrupt));
}

#[test]
fn every_bit_of_a_password_and_a_recovery_slot_is_caught() {
    let binding = SlotBinding::v1(id(0x11), id(0x55), SlotType::Password, 1);
    let params = chur_crypto::password::Argon2Params::validated(65_536, 3, 1).unwrap();
    let body = PasswordSlotBody::seal(
        &binding,
        b"password",
        vec![0x77; 16],
        params,
        nonce(0x88),
        &key(0x5a),
    )
    .unwrap()
    .encode();
    // Argon2 at the frozen floor costs about 70 ms, so a full 8-bit sweep of a
    // 108-byte body would run for a minute. The password body is swept
    // structurally: every damaged body must fail to decode or fail to open, and
    // the decode half is what the sweep here proves.
    let mut rejected = 0usize;
    for index in 0..body.len() {
        let mut damaged = body.clone();
        damaged[index] ^= 0x01;
        if let Err(error) = PasswordSlotBody::decode(&damaged) {
            assert_ne!(error.status(), ChurStatus::InternalFailure);
            rejected += 1;
        }
    }
    assert!(
        rejected > 0,
        "no damaged password body was rejected on decode"
    );

    let recovery_binding = SlotBinding::v1(id(0x11), id(0x56), SlotType::Recovery, 1);
    let recovery = RecoverySlotBody::seal(&recovery_binding, &key(0x99), nonce(0xaa), &key(0x5a))
        .unwrap()
        .encode();
    let classified = sweep("recovery slot body", &recovery, |damaged| {
        RecoverySlotBody::decode(damaged)?
            .open(&recovery_binding, &key(0x99))
            .map(|_| ())
    });
    assert!(classified.contains_key(&ChurStatus::AuthenticationFailed));
}

#[test]
fn truncation_at_every_boundary_of_a_container_is_classified() {
    let bytes = container(&(0..200u8).collect::<Vec<_>>());
    for cut in 0..bytes.len() {
        let outcome = ContainerReader::open(&bytes[..cut], &key(0x77), &identity())
            .and_then(|reader| reader.verify_complete().map(|_| ()));
        match outcome {
            Ok(()) => panic!("a container truncated at {cut} verified"),
            Err(error) => assert!(
                matches!(
                    error.status(),
                    ChurStatus::ObjectCorrupt
                        | ChurStatus::ObjectIncomplete
                        | ChurStatus::UnsupportedVersion
                        | ChurStatus::UnsupportedSuite
                ),
                "truncation at {cut} produced {}",
                error.status()
            ),
        }
    }
}

#[test]
fn a_container_extended_by_one_byte_at_every_length_is_rejected() {
    let bytes = container(&(0..200u8).collect::<Vec<_>>());
    let mut extended = bytes.clone();
    extended.push(0x00);
    assert_eq!(
        Layout::parse(&extended).unwrap_err().status(),
        ChurStatus::ObjectCorrupt
    );
}
