//! The leaked-handle check of `docs/interop/FFI_CONTRACT.md` §15.
//!
//! It is its own integration binary because the registry is process-global and
//! the harness runs a binary's tests on parallel threads: a count taken while
//! another test held handles would measure that test instead of this one.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
#![expect(
    unsafe_code,
    reason = "the test drives the C ABI, which is unsafe to call by definition"
)]

use chur_ffi::api::*;
use chur_ffi::records::*;

const OK: i32 = 0;
const PASSWORD: &[u8] = b"correct horse battery staple";

#[test]
fn closing_the_runtime_leaves_no_live_handle() {
    assert_eq!(
        chur_ffi::registry_live_handles(),
        0,
        "the registry is not empty before the first handle"
    );

    let mut root = std::env::temp_dir();
    root.push(format!(
        "chur-leak-{}",
        chur_crypto::random::id().unwrap().to_hex()
    ));
    std::fs::create_dir_all(&root).unwrap();
    let directory = chur_catalog::paths::VaultRoot::new(root.clone());
    drop(
        chur_catalog::vault::create(&directory, PASSWORD, 1)
            .expect("create")
            .activate()
            .expect("activate"),
    );

    let text = root.to_str().unwrap().as_bytes();
    let config = ChurRuntimeConfigV1 {
        root_path: text.as_ptr(),
        root_path_length: text.len() as u32,
    };
    let mut runtime = 0u64;
    assert_eq!(unsafe { chur_runtime_open(&config, &mut runtime) }, OK);

    let request = ChurUnlockRequestV1 {
        factor: 1,
        reserved: [0; 3],
        secret: PASSWORD.as_ptr(),
        secret_length: PASSWORD.len() as u32,
    };
    let mut session = 0u64;
    assert_eq!(
        unsafe { chur_vault_unlock(runtime, &request, &mut session) },
        OK
    );

    let scan = ChurScanRequestV1 {
        single_object: 0,
        reserved: [0; 7],
        object_id: [0; 16],
    };
    let mut operation = 0u64;
    assert_eq!(
        unsafe { chur_integrity_scan_begin(session, &scan, &mut operation) },
        OK
    );

    assert_eq!(
        chur_ffi::registry_live_handles(),
        3,
        "a runtime, a session, and an operation"
    );

    // §14: closing the runtime ends every session it opened and everything
    // those sessions own, transitively.
    assert_eq!(unsafe { chur_runtime_close(runtime) }, OK);
    assert_eq!(
        chur_ffi::registry_live_handles(),
        0,
        "closing the runtime left a handle behind"
    );

    // A slot is reused, and the value it issues is not one already seen.
    let mut again = 0u64;
    assert_eq!(unsafe { chur_runtime_open(&config, &mut again) }, OK);
    assert_ne!(again, runtime, "§3 never reissues a handle value");
    assert_eq!(unsafe { chur_runtime_close(again) }, OK);
    assert_eq!(chur_ffi::registry_live_handles(), 0);
}
