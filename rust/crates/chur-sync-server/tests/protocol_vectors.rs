//! Published sync-vector consumption by the reference relay.

#![allow(clippy::expect_used, clippy::panic)]

use std::sync::atomic::{AtomicU64, Ordering};

use chur_core::{ChurStatus, Id, Result};
use chur_sync_protocol::checkpoint::{Checkpoint, collection_epoch_commitment};
use chur_sync_protocol::membership::{EnrollmentRecord, RevocationRecord};
use chur_sync_protocol::operation::Operation;
use chur_sync_server::{ReferenceServer, RelayOutcome};
use serde_json::Value;

const MANIFEST: &str = include_str!("../../../../test-vectors/v1/manifest.json");

#[test]
fn published_sync_vectors_decode_and_bootstrap_the_reference_relay() {
    let manifest: Value = serde_json::from_str(MANIFEST).expect("manifest");
    let initial = EnrollmentRecord::decode(&expected_bytes(
        &manifest,
        "operation-v1-initial-enrollment",
        "record",
    ))
    .expect("initial enrollment");
    let operation = Operation::decode(&expected_bytes(
        &manifest,
        "operation-v1-signed-record",
        "record",
    ))
    .expect("operation");
    let checkpoint = Checkpoint::decode(&expected_bytes(
        &manifest,
        "operation-v1-checkpoint",
        "record",
    ))
    .expect("checkpoint");
    let successor = EnrollmentRecord::decode(&expected_bytes(
        &manifest,
        "operation-v1-successor-enrollment",
        "record",
    ))
    .expect("successor enrollment");
    let revocation = RevocationRecord::decode(&expected_bytes(
        &manifest,
        "operation-v1-revocation",
        "record",
    ))
    .expect("revocation");

    assert_eq!(successor.membership_generation(), 2);
    assert_eq!(revocation.membership_generation(), 3);
    assert_eq!(
        successor.commitment(),
        *revocation.previous_membership_commitment()
    );

    let epoch_vector = vector(&manifest, "operation-v1-collection-epoch-commitment");
    let entries = epoch_vector["inputs"]["entries"]
        .as_array()
        .expect("epoch entries")
        .iter()
        .map(|entry| {
            let collection_id = Id::from_slice(&decode_hex(
                entry["collection_id"].as_str().expect("collection id"),
            ))
            .expect("collection id bytes");
            let epoch = entry["current_epoch"].as_u64().expect("current epoch");
            (collection_id, epoch)
        })
        .collect::<Vec<_>>();
    assert_eq!(
        collection_epoch_commitment(&entries).expect("epoch commitment"),
        array32(&expected_bytes(
            &manifest,
            "operation-v1-collection-epoch-commitment",
            "collection_epoch_commitment",
        ))
    );

    assert_rejection(
        &manifest,
        "operation-v1-version-two",
        ChurStatus::UnsupportedVersion,
        Operation::decode(&input_bytes(
            &manifest,
            "operation-v1-version-two",
            "record",
        )),
    );
    assert_rejection(
        &manifest,
        "operation-v1-truncated-signature",
        ChurStatus::NonCanonicalEncoding,
        Operation::decode(&input_bytes(
            &manifest,
            "operation-v1-truncated-signature",
            "record",
        )),
    );
    let modified = Operation::decode(&input_bytes(
        &manifest,
        "operation-v1-modified-signature",
        "record",
    ))
    .expect("structurally valid modified signature");
    assert_rejection(
        &manifest,
        "operation-v1-modified-signature",
        ChurStatus::AuthenticationFailed,
        modified.verify_signature(&array32(&input_bytes(
            &manifest,
            "operation-v1-modified-signature",
            "signing_public_key",
        ))),
    );
    assert_rejection(
        &manifest,
        "operation-v1-hpke-suite-two",
        ChurStatus::UnsupportedVersion,
        EnrollmentRecord::decode(&input_bytes(
            &manifest,
            "operation-v1-hpke-suite-two",
            "record",
        )),
    );
    assert_rejection(
        &manifest,
        "operation-v1-compacted-checkpoint",
        ChurStatus::UnsupportedVersion,
        Checkpoint::decode(&input_bytes(
            &manifest,
            "operation-v1-compacted-checkpoint",
            "record",
        )),
    );
    assert_rejection(
        &manifest,
        "operation-v1-self-issued-revocation",
        ChurStatus::NonCanonicalEncoding,
        RevocationRecord::decode(&input_bytes(
            &manifest,
            "operation-v1-self-issued-revocation",
            "record",
        )),
    );

    let root = TestRoot::new();
    let mut server = ReferenceServer::open(&root.0, 1_024, 32_768).expect("server");
    assert_eq!(
        server
            .accept_initial_membership(&initial, &operation)
            .expect("bootstrap relay"),
        RelayOutcome::Stored
    );
    assert_eq!(
        server
            .accept_checkpoint(&checkpoint)
            .expect("checkpoint relay"),
        RelayOutcome::Stored
    );
    assert_eq!(
        server
            .checkpoint(*checkpoint.vault_id(), checkpoint.commitment())
            .expect("checkpoint fetch"),
        checkpoint.encode()
    );
}

fn expected_bytes(manifest: &Value, vector_id: &str, field: &str) -> Vec<u8> {
    decode_hex(
        vector(manifest, vector_id)["expected"][field]
            .as_str()
            .expect("expected byte field"),
    )
}

fn input_bytes(manifest: &Value, vector_id: &str, field: &str) -> Vec<u8> {
    decode_hex(
        vector(manifest, vector_id)["inputs"][field]
            .as_str()
            .expect("input byte field"),
    )
}

fn assert_rejection<T>(manifest: &Value, vector_id: &str, status: ChurStatus, result: Result<T>) {
    assert_eq!(
        vector(manifest, vector_id)["error_code"],
        status.name(),
        "manifest error code"
    );
    match result {
        Err(error) => assert_eq!(error.status(), status, "{vector_id}"),
        Ok(_) => panic!("{vector_id} was accepted"),
    }
}

fn vector<'a>(manifest: &'a Value, vector_id: &str) -> &'a Value {
    manifest["vectors"]
        .as_array()
        .expect("vectors")
        .iter()
        .find(|entry| entry["vector_id"] == vector_id)
        .expect("named vector")
}

fn decode_hex(value: &str) -> Vec<u8> {
    hex::decode(value).expect("lowercase hexadecimal bytes")
}

fn array32(bytes: &[u8]) -> [u8; 32] {
    bytes.try_into().expect("32-byte value")
}

struct TestRoot(std::path::PathBuf);

impl TestRoot {
    fn new() -> Self {
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let path = std::env::temp_dir().join(format!(
            "chur-sync-vector-server-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::create_dir(&path).expect("test root");
        Self(path)
    }
}

impl Drop for TestRoot {
    fn drop(&mut self) {
        std::fs::remove_dir_all(&self.0).expect("remove test root");
    }
}
