//! Sharing identity provisioning through the real C ABI.

#![allow(clippy::expect_used)]
#![expect(unsafe_code, reason = "the test drives the C ABI pointer contract")]

use chur_ffi::api::{chur_runtime_close, chur_runtime_open, chur_session_close, chur_vault_unlock};
use chur_ffi::records::{ChurRuntimeConfigV1, ChurUnlockRequestV1};
use chur_ffi::sharing::chur_sharing_identity;
use chur_format::codec::Reader;
use chur_sync_protocol::{
    identity::fingerprint, membership::EnrollmentRecord, operation::Operation,
};

const PASSWORD: &[u8] = b"correct horse battery staple";

#[test]
fn identity_provisioning_is_private_atomic_and_idempotent() {
    let path = std::env::temp_dir().join(format!(
        "chur-ffi-sharing-{}",
        chur_crypto::random::id().expect("random id").to_hex()
    ));
    let root = chur_catalog::paths::VaultRoot::new(&path);
    drop(
        chur_catalog::vault::create(&root, PASSWORD, 1)
            .expect("create")
            .activate()
            .expect("activate"),
    );
    let path_bytes = path.to_str().expect("UTF-8 path").as_bytes();
    let config = ChurRuntimeConfigV1 {
        root_path: path_bytes.as_ptr(),
        root_path_length: path_bytes.len() as u32,
    };
    let mut runtime = 0;
    assert_eq!(unsafe { chur_runtime_open(&config, &mut runtime) }, 0);
    let unlock = ChurUnlockRequestV1 {
        factor: 1,
        reserved: [0; 3],
        secret: PASSWORD.as_ptr(),
        secret_length: PASSWORD.len() as u32,
    };
    let mut session = 0;
    assert_eq!(
        unsafe { chur_vault_unlock(runtime, &unlock, &mut session) },
        0
    );

    let mut bytes = vec![0u8; 4096];
    let mut written = 0;
    assert_eq!(
        unsafe { chur_sharing_identity(session, bytes.as_mut_ptr(), bytes.len(), &mut written) },
        0
    );
    bytes.truncate(written);
    let mut reader = Reader::new(&bytes, chur_core::ChurStatus::NonCanonicalEncoding);
    assert_eq!(reader.u16().expect("version"), 1);
    let vault_id = reader.id().expect("vault");
    let device_id = reader.id().expect("device");
    let signing = reader.fixed::<32>().expect("signing key");
    let hpke = reader.fixed::<32>().expect("HPKE key");
    let display = reader.variable(49).expect("fingerprint");
    let enrollment = EnrollmentRecord::decode(reader.variable(270).expect("enrollment"))
        .expect("valid enrollment");
    let operation = Operation::decode(reader.variable(16_777_216).expect("operation"))
        .expect("valid operation");
    reader.finish().expect("complete record");
    assert_eq!(enrollment.vault_id(), &vault_id);
    assert_eq!(enrollment.device_id(), &device_id);
    assert_eq!(enrollment.signing_public_key(), &signing);
    assert_eq!(enrollment.hpke_public_key(), &hpke);
    assert_eq!(
        display,
        fingerprint(&vault_id, &device_id, &signing, &hpke).as_bytes()
    );
    assert_eq!(operation.device_id(), &device_id);
    assert_eq!(operation.device_sequence(), 1);

    let mut replay = vec![0u8; 4096];
    let mut replay_written = 0;
    assert_eq!(
        unsafe {
            chur_sharing_identity(
                session,
                replay.as_mut_ptr(),
                replay.len(),
                &mut replay_written,
            )
        },
        0
    );
    assert_eq!(&replay[..replay_written], bytes.as_slice());
    assert_eq!(unsafe { chur_session_close(session) }, 0);
    assert_eq!(unsafe { chur_runtime_close(runtime) }, 0);
    std::fs::remove_dir_all(path).expect("cleanup");
}
