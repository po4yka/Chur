//! Locked staging and unlocked processing through the real C ABI.

#![allow(clippy::expect_used)]
#![expect(unsafe_code, reason = "the test drives the C ABI pointer contract")]

use chur_catalog::sync_receive;
use chur_ffi::api::{chur_runtime_close, chur_runtime_open, chur_session_close, chur_vault_unlock};
use chur_ffi::records::{ChurRuntimeConfigV1, ChurUnlockRequestV1};
use chur_ffi::sync::{ChurSyncReportV1, chur_sync_process, chur_sync_stage};
use chur_sync_protocol::identity::DeviceIdentity;
use chur_sync_protocol::membership::EnrollmentRecord;

const PASSWORD: &[u8] = b"correct horse battery staple";

#[test]
fn locked_stage_is_validated_and_removed_after_unlock() {
    let path = std::env::temp_dir().join(format!(
        "chur-ffi-sync-{}",
        chur_crypto::random::id().expect("random id").to_hex()
    ));
    let root = chur_catalog::paths::VaultRoot::new(&path);
    let mut session = chur_catalog::vault::create(&root, PASSWORD, 1)
        .expect("create")
        .activate()
        .expect("activate");
    let identity = DeviceIdentity::generate().expect("identity");
    let vault_id = session.vault_id();
    let device_id = chur_crypto::random::id().expect("device id");
    let enrollment = EnrollmentRecord::initial(
        vault_id,
        device_id,
        identity.signing_public_key(),
        identity.hpke_public_key(),
    )
    .expect("enrollment")
    .sign(identity.signing_key());
    let root_secret = chur_crypto::Key::new(*session.root_secret().expose());
    let (_, _, operation) = sync_receive::provision_initial_membership(
        session.catalog().expect("catalog"),
        &root_secret,
        identity.signing_key(),
        &enrollment,
    )
    .expect("provision sync");
    drop(session);

    let path_bytes = path.to_str().expect("UTF-8 path").as_bytes();
    let config = ChurRuntimeConfigV1 {
        root_path: path_bytes.as_ptr(),
        root_path_length: path_bytes.len() as u32,
    };
    let mut runtime = 0;
    assert_eq!(unsafe { chur_runtime_open(&config, &mut runtime) }, 0);
    let bytes = operation.encode();
    assert_eq!(
        unsafe {
            chur_sync_stage(
                runtime,
                vault_id.as_bytes().as_ptr(),
                1,
                2,
                bytes.as_ptr(),
                bytes.len() as u32,
            )
        },
        0
    );
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
    let mut report = ChurSyncReportV1 {
        applied: 0,
        duplicates: 0,
        pending: 0,
        rejected: 0,
        first_rejection: 0,
        reserved: [9; 4],
    };

    assert_eq!(unsafe { chur_sync_process(session, 2, &mut report) }, 0);
    assert_eq!(report.duplicates, 1);
    assert_eq!(report.pending, 0);
    assert_eq!(report.reserved, [0; 4]);
    assert_eq!(unsafe { chur_session_close(session) }, 0);
    assert_eq!(unsafe { chur_runtime_close(runtime) }, 0);
    std::fs::remove_dir_all(path).expect("cleanup");
}
