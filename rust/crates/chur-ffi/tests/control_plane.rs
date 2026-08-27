//! The control plane and the data plane driven through the real ABI.
//!
//! `docs/interop/FFI_CONTRACT.md` §15 names the tests this file owes: handle
//! misuse, invalid and null buffers, double close, leaked-handle cleanup, lock
//! during a read, poll after the terminal result and after close, a descriptor
//! closed early, cancellation, and no secret in an error.
//!
//! Every call goes through the exported symbol rather than through a Rust API,
//! so what is tested is the boundary and not the implementation behind it.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
// This file calls the exported symbols directly, which is the point: what is
// tested is the boundary and not the Rust API behind it. Every call satisfies
// the pointer contract the header states, and each one is visible at its call
// site rather than hidden behind a wrapper.
#![expect(
    unsafe_code,
    reason = "the tests drive the C ABI, which is unsafe to call by definition"
)]

use std::ffi::c_int;
use std::io::{Seek, Write};

use chur_core::ChurStatus;
use chur_ffi::api::*;
use chur_ffi::records::*;

const OK: i32 = 0;
const PASSWORD: &[u8] = b"correct horse battery staple";

fn status(value: i32) -> ChurStatus {
    ChurStatus::from_i32(value)
}

fn scratch() -> std::path::PathBuf {
    let mut path = std::env::temp_dir();
    path.push(format!(
        "chur-ffi-{}",
        chur_crypto::random::id().unwrap().to_hex()
    ));
    std::fs::create_dir_all(&path).unwrap();
    path
}

/// Opens a runtime over a fresh storage root.
fn open_runtime(root: &std::path::Path) -> u64 {
    let text = root.to_str().unwrap().as_bytes();
    let config = ChurRuntimeConfigV1 {
        root_path: text.as_ptr(),
        root_path_length: text.len() as u32,
    };
    let mut handle = 0u64;
    let code = unsafe { chur_runtime_open(&config, &mut handle) };
    assert_eq!(code, OK, "the runtime did not open");
    assert_ne!(handle, 0, "a live handle is never the null handle");
    handle
}

/// Creates a vault through the Rust API, because §6.2 exposes no creation
/// export: `PROVISIONING.md` §3 is a product flow the shell drives, and the
/// boundary exposes only unlock.
fn create_vault(root: &std::path::Path) {
    let dir = chur_catalog::paths::VaultRoot::new(root.to_path_buf());
    drop(
        chur_catalog::vault::create(&dir, PASSWORD, 1_700_000_000_000)
            .expect("create")
            .activate()
            .expect("activate"),
    );
}

fn unlock(runtime: u64, secret: &[u8], factor: u8) -> (i32, u64) {
    let request = ChurUnlockRequestV1 {
        factor,
        reserved: [0; 3],
        secret: secret.as_ptr(),
        secret_length: secret.len() as u32,
    };
    let mut session = 0u64;
    let code = unsafe { chur_vault_unlock(runtime, &request, &mut session) };
    (code, session)
}

fn timeline_query() -> ChurQueryV1 {
    ChurQueryV1 {
        scope: 1,
        sort: 1,
        kinds: 0,
        limit: 0,
        scope_id: [0; 16],
        cursor_present: 0,
        cursor: [0; 42],
        terms: core::ptr::null(),
        terms_length: 0,
    }
}

/// Imports one object through the ABI and returns its identifier.
fn import(session: u64, bytes: &[u8]) -> [u8; 16] {
    let mut file = tempfile();
    file.write_all(bytes).unwrap();
    file.rewind().unwrap();
    let fd = descriptor(&file);

    let content_type = b"image/jpeg";
    let filename = b"holiday.jpg";
    let request = ChurImportRequestV1 {
        seekable: 1,
        known_length_present: 1,
        media_class: 1,
        reserved: 0,
        width: 4_000,
        height: 3_000,
        duration_ms: 0,
        known_length: bytes.len() as u64,
        capture_time_ms: 1_700_000_000_000,
        capture_time_present: 1,
        reserved_two: [0; 7],
        content_type: content_type.as_ptr(),
        content_type_length: content_type.len() as u32,
        original_filename: filename.as_ptr(),
        original_filename_length: filename.len() as u32,
    };
    let mut operation = 0u64;
    assert_eq!(
        unsafe { chur_import_begin(session, fd, &request, &mut operation) },
        OK
    );
    let terminal = drain(operation);
    assert_eq!(status(terminal), status(OK), "the import failed");
    assert_eq!(unsafe { chur_operation_close(operation) }, OK);

    let mut buffer = vec![0u8; 63 + 79 * 8];
    let mut written = 0usize;
    let query = timeline_query();
    assert_eq!(
        unsafe {
            chur_catalog_query(
                session,
                &query,
                buffer.as_mut_ptr(),
                buffer.len(),
                &mut written,
            )
        },
        OK
    );
    let page = decode_page(&buffer[..written]).unwrap();
    *page.objects.last().unwrap().object_id.as_bytes()
}

/// Polls an operation until it is terminal and returns its status.
fn drain(operation: u64) -> i32 {
    loop {
        let mut progress = zeroed_progress();
        assert_eq!(unsafe { chur_operation_poll(operation, &mut progress) }, OK);
        if progress.terminal == 1 {
            return progress.status;
        }
        std::thread::yield_now();
    }
}

fn zeroed_progress() -> ChurProgressV1 {
    ChurProgressV1 {
        kind: 0,
        stage: 0,
        processed: 0,
        total: 0,
        terminal: 0,
        reserved: [0; 3],
        status: 0,
    }
}

fn tempfile() -> std::fs::File {
    let mut path = std::env::temp_dir();
    path.push(format!(
        "chur-ffi-src-{}",
        chur_crypto::random::id().unwrap().to_hex()
    ));
    std::fs::OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .truncate(true)
        .open(&path)
        .unwrap()
}

#[cfg(unix)]
fn descriptor(file: &std::fs::File) -> c_int {
    use std::os::fd::AsRawFd;
    file.as_raw_fd()
}

// ---------------------------------------------------------------------------

#[test]
fn a_null_pointer_is_invalid_input_rather_than_a_crash() {
    let mut handle = 0u64;
    assert_eq!(
        status(unsafe { chur_runtime_open(core::ptr::null(), &mut handle) }),
        ChurStatus::InvalidInput
    );
    let root = scratch();
    let text = root.to_str().unwrap().as_bytes();
    let config = ChurRuntimeConfigV1 {
        root_path: text.as_ptr(),
        root_path_length: text.len() as u32,
    };
    assert_eq!(
        status(unsafe { chur_runtime_open(&config, core::ptr::null_mut()) }),
        ChurStatus::InvalidInput
    );
    // A length with a null pointer is refused before anything is read.
    let broken = ChurRuntimeConfigV1 {
        root_path: core::ptr::null(),
        root_path_length: 8,
    };
    assert_eq!(
        status(unsafe { chur_runtime_open(&broken, &mut handle) }),
        ChurStatus::InvalidInput
    );
}

#[test]
fn the_null_handle_and_a_handle_never_issued_are_invalid_input() {
    for handle in [0u64, 0x0000_0001_ffff_ffff, u64::MAX] {
        assert_eq!(
            status(unsafe { chur_session_close(handle) }),
            ChurStatus::InvalidInput,
            "handle {handle:#x}"
        );
    }
}

#[test]
fn a_handle_of_another_type_is_refused() {
    let root = scratch();
    let runtime = open_runtime(&root);
    let mut progress = zeroed_progress();
    // A runtime handle passed where an operation handle belongs.
    assert_eq!(
        status(unsafe { chur_operation_poll(runtime, &mut progress) }),
        ChurStatus::InvalidInput
    );
    let mut size = 0u64;
    assert_eq!(
        status(unsafe { chur_object_reader_size(runtime, &mut size) }),
        ChurStatus::InvalidInput
    );
    assert_eq!(unsafe { chur_runtime_close(runtime) }, OK);
}

#[test]
fn close_is_idempotent_and_never_reports_not_found() {
    let root = scratch();
    let runtime = open_runtime(&root);
    assert_eq!(unsafe { chur_runtime_close(runtime) }, OK);
    // §3: every later close of the same value returns success and does nothing.
    for _ in 0..3 {
        assert_eq!(unsafe { chur_runtime_close(runtime) }, OK);
    }
}

#[test]
fn a_wrong_password_is_authentication_failed_and_carries_no_value() {
    let root = scratch();
    create_vault(&root);
    let runtime = open_runtime(&root);
    let (code, session) = unlock(runtime, b"wrong", 1);
    assert_eq!(status(code), ChurStatus::AuthenticationFailed);
    assert_eq!(session, 0, "a failed unlock wrote no handle");
    assert_eq!(unsafe { chur_runtime_close(runtime) }, OK);
}

#[test]
fn an_unallocated_factor_is_invalid_input() {
    let root = scratch();
    create_vault(&root);
    let runtime = open_runtime(&root);
    assert_eq!(
        status(unlock(runtime, PASSWORD, 9).0),
        ChurStatus::InvalidInput
    );
    assert_eq!(unsafe { chur_runtime_close(runtime) }, OK);
}

#[test]
fn an_import_reads_back_through_the_reader() {
    let root = scratch();
    create_vault(&root);
    let runtime = open_runtime(&root);
    let (code, session) = unlock(runtime, PASSWORD, 1);
    assert_eq!(code, OK);

    let expected: Vec<u8> = (0..300_000u32).map(|value| (value % 251) as u8).collect();
    let object_id = import(session, &expected);

    let reference = ChurObjectRefV1 { object_id };
    let mut reader = 0u64;
    assert_eq!(
        unsafe { chur_object_reader_open(session, &reference, 1, &mut reader) },
        OK
    );
    let mut size = 0u64;
    assert_eq!(unsafe { chur_object_reader_size(reader, &mut size) }, OK);
    assert_eq!(size, expected.len() as u64);

    let mut info = ChurContentInfoV1 {
        plaintext_size: 0,
        content_type: [0; 64],
        media_kind: 0,
        byte_range_supported: 0,
        complete: 0,
        reserved: [0; 4],
    };
    assert_eq!(
        unsafe { chur_object_reader_content_info(reader, &mut info) },
        OK
    );
    assert_eq!(info.plaintext_size, expected.len() as u64);
    assert_eq!(info.byte_range_supported, 1);
    assert_eq!(info.complete, 1);
    let terminator = info
        .content_type
        .iter()
        .position(|byte| *byte == 0)
        .unwrap();
    assert_eq!(&info.content_type[..terminator], b"image/jpeg");

    let mut read = vec![0u8; expected.len()];
    let mut at = 0usize;
    while at < read.len() {
        let mut written = 0usize;
        let code = unsafe {
            chur_object_reader_read_at(
                reader,
                at as u64,
                read[at..].as_mut_ptr(),
                read.len() - at,
                &mut written,
            )
        };
        assert_eq!(code, OK);
        assert!(written > 0, "a short read returned zero before the end");
        at += written;
    }
    assert_eq!(read, expected);

    // §6.3: offset == size is a success with zero bytes; above it is INVALID_INPUT.
    let mut one = [0u8; 1];
    let mut written = 99usize;
    assert_eq!(
        unsafe { chur_object_reader_read_at(reader, size, one.as_mut_ptr(), 1, &mut written) },
        OK
    );
    assert_eq!(written, 0);
    written = 99;
    assert_eq!(
        status(unsafe {
            chur_object_reader_read_at(reader, size + 1, one.as_mut_ptr(), 1, &mut written)
        }),
        ChurStatus::InvalidInput
    );
    assert_eq!(written, 0, "§6.3 sets the count on every failure");

    let mut state = 0u32;
    assert_eq!(
        unsafe { chur_object_reader_verify_complete(reader, &mut state) },
        OK
    );
    assert_eq!(state, 4, "COMPLETE_VERIFIED");

    assert_eq!(unsafe { chur_object_reader_close(reader) }, OK);
    assert_eq!(unsafe { chur_session_close(session) }, OK);
    assert_eq!(unsafe { chur_runtime_close(runtime) }, OK);
}

#[test]
fn a_query_buffer_smaller_than_the_page_writes_nothing() {
    let root = scratch();
    create_vault(&root);
    let runtime = open_runtime(&root);
    let (_, session) = unlock(runtime, PASSWORD, 1);
    import(session, &[7u8; 4_096]);

    let query = timeline_query();
    let mut small = vec![0xaau8; 10];
    let mut written = 99usize;
    assert_eq!(
        status(unsafe {
            chur_catalog_query(
                session,
                &query,
                small.as_mut_ptr(),
                small.len(),
                &mut written,
            )
        }),
        ChurStatus::ResourceLimitExceeded
    );
    assert_eq!(written, 0);
    assert!(
        small.iter().all(|byte| *byte == 0xaa),
        "a refused page wrote into the buffer"
    );
    assert_eq!(unsafe { chur_runtime_close(runtime) }, OK);
}

#[test]
fn locking_invalidates_every_handle_the_session_owns() {
    let root = scratch();
    create_vault(&root);
    let runtime = open_runtime(&root);
    let (_, session) = unlock(runtime, PASSWORD, 1);
    let object_id = import(session, &[3u8; 8_192]);
    let reference = ChurObjectRefV1 { object_id };
    let mut reader = 0u64;
    assert_eq!(
        unsafe { chur_object_reader_open(session, &reference, 1, &mut reader) },
        OK
    );

    assert_eq!(unsafe { chur_vault_lock(session, 1) }, OK);

    // §4: no handle revives after lock.
    let mut size = 0u64;
    assert_eq!(
        status(unsafe { chur_object_reader_size(reader, &mut size) }),
        ChurStatus::SessionExpired
    );
    // The session itself is locked rather than gone, and reports that.
    let query = timeline_query();
    let mut buffer = vec![0u8; 512];
    let mut written = 0usize;
    assert_eq!(
        status(unsafe {
            chur_catalog_query(
                session,
                &query,
                buffer.as_mut_ptr(),
                buffer.len(),
                &mut written,
            )
        }),
        ChurStatus::VaultLocked
    );
    // Close after lock is still success, §3.
    assert_eq!(unsafe { chur_object_reader_close(reader) }, OK);
    assert_eq!(unsafe { chur_session_close(session) }, OK);
    assert_eq!(unsafe { chur_runtime_close(runtime) }, OK);
}

#[test]
fn polling_after_the_terminal_result_returns_the_same_result() {
    let root = scratch();
    create_vault(&root);
    let runtime = open_runtime(&root);
    let (_, session) = unlock(runtime, PASSWORD, 1);

    let mut file = tempfile();
    file.write_all(&[9u8; 4_096]).unwrap();
    file.rewind().unwrap();
    let content_type = b"image/jpeg";
    let request = ChurImportRequestV1 {
        seekable: 1,
        known_length_present: 1,
        media_class: 1,
        reserved: 0,
        width: 100,
        height: 100,
        duration_ms: 0,
        known_length: 4_096,
        capture_time_ms: 0,
        capture_time_present: 0,
        reserved_two: [0; 7],
        content_type: content_type.as_ptr(),
        content_type_length: content_type.len() as u32,
        original_filename: core::ptr::null(),
        original_filename_length: 0,
    };
    let mut operation = 0u64;
    assert_eq!(
        unsafe { chur_import_begin(session, descriptor(&file), &request, &mut operation) },
        OK
    );
    let first = drain(operation);
    // §9: exactly one terminal result is observable, and the snapshot freezes.
    for _ in 0..5 {
        let mut progress = zeroed_progress();
        assert_eq!(unsafe { chur_operation_poll(operation, &mut progress) }, OK);
        assert_eq!(progress.terminal, 1);
        assert_eq!(progress.status, first);
        assert_eq!(progress.stage, 4);
    }
    assert_eq!(unsafe { chur_operation_close(operation) }, OK);
    // §3: polling a closed handle is SESSION_EXPIRED, not a partial snapshot.
    let mut progress = zeroed_progress();
    assert_eq!(
        status(unsafe { chur_operation_poll(operation, &mut progress) }),
        ChurStatus::SessionExpired
    );
    assert_eq!(unsafe { chur_runtime_close(runtime) }, OK);
}

#[test]
fn an_import_from_a_closed_descriptor_fails_without_committing() {
    let root = scratch();
    create_vault(&root);
    let runtime = open_runtime(&root);
    let (_, session) = unlock(runtime, PASSWORD, 1);
    let content_type = b"image/jpeg";
    let request = ChurImportRequestV1 {
        seekable: 0,
        known_length_present: 0,
        media_class: 1,
        reserved: 0,
        width: 0,
        height: 0,
        duration_ms: 0,
        known_length: 0,
        capture_time_ms: 0,
        capture_time_present: 0,
        reserved_two: [0; 7],
        content_type: content_type.as_ptr(),
        content_type_length: content_type.len() as u32,
        original_filename: core::ptr::null(),
        original_filename_length: 0,
    };
    let mut operation = 0u64;
    // §13: a descriptor that is not open is refused before any work.
    assert_eq!(
        status(unsafe { chur_import_begin(session, -1, &request, &mut operation) }),
        ChurStatus::InvalidInput
    );
    assert_eq!(operation, 0);

    // An empty source is a refusal that leaves nothing behind.
    let file = tempfile();
    assert_eq!(
        unsafe { chur_import_begin(session, descriptor(&file), &request, &mut operation) },
        OK
    );
    assert_eq!(status(drain(operation)), ChurStatus::InvalidInput);
    assert_eq!(unsafe { chur_operation_close(operation) }, OK);

    let query = timeline_query();
    let mut buffer = vec![0u8; 512];
    let mut written = 0usize;
    assert_eq!(
        unsafe {
            chur_catalog_query(
                session,
                &query,
                buffer.as_mut_ptr(),
                buffer.len(),
                &mut written,
            )
        },
        OK
    );
    let page = decode_page(&buffer[..written]).unwrap();
    assert_eq!(page.total_count, 0);
    assert!(page.objects.is_empty());
    assert_eq!(unsafe { chur_runtime_close(runtime) }, OK);
}

#[test]
fn an_export_writes_the_original_through_a_descriptor() {
    let root = scratch();
    create_vault(&root);
    let runtime = open_runtime(&root);
    let (_, session) = unlock(runtime, PASSWORD, 1);
    let expected: Vec<u8> = (0..70_000u32).map(|value| (value % 199) as u8).collect();
    let object_id = import(session, &expected);

    let destination = tempfile();
    let reference = ChurObjectRefV1 { object_id };
    let mut operation = 0u64;
    assert_eq!(
        unsafe {
            chur_export_begin(
                session,
                &reference,
                descriptor(&destination),
                &mut operation,
            )
        },
        OK
    );
    assert_eq!(status(drain(operation)), status(OK));
    assert_eq!(unsafe { chur_operation_close(operation) }, OK);

    let mut written = destination;
    written.rewind().unwrap();
    let mut out = Vec::new();
    std::io::Read::read_to_end(&mut written, &mut out).unwrap();
    assert_eq!(out, expected);
    assert_eq!(unsafe { chur_runtime_close(runtime) }, OK);
}

#[test]
fn an_integrity_scan_runs_over_every_object() {
    let root = scratch();
    create_vault(&root);
    let runtime = open_runtime(&root);
    let (_, session) = unlock(runtime, PASSWORD, 1);
    import(session, &[1u8; 4_096]);
    import(session, &[2u8; 4_096]);

    let request = ChurScanRequestV1 {
        single_object: 0,
        reserved: [0; 7],
        object_id: [0; 16],
    };
    let mut operation = 0u64;
    assert_eq!(
        unsafe { chur_integrity_scan_begin(session, &request, &mut operation) },
        OK
    );
    assert_eq!(status(drain(operation)), status(OK));
    let mut progress = zeroed_progress();
    assert_eq!(unsafe { chur_operation_poll(operation, &mut progress) }, OK);
    assert_eq!(progress.kind, 3);
    assert_eq!(progress.processed, 2);
    assert_eq!(unsafe { chur_operation_close(operation) }, OK);
    assert_eq!(unsafe { chur_runtime_close(runtime) }, OK);
}

#[test]
fn closing_the_runtime_releases_every_handle() {
    let root = scratch();
    create_vault(&root);
    let runtime = open_runtime(&root);
    let (_, session) = unlock(runtime, PASSWORD, 1);
    let object_id = import(session, &[5u8; 4_096]);
    let reference = ChurObjectRefV1 { object_id };
    let mut reader = 0u64;
    assert_eq!(
        unsafe { chur_object_reader_open(session, &reference, 1, &mut reader) },
        OK
    );

    assert_eq!(unsafe { chur_runtime_close(runtime) }, OK);
    // §15: no handle leaks. Every one now reports it is gone rather than
    // serving a stale session.
    let mut size = 0u64;
    assert_eq!(
        status(unsafe { chur_object_reader_size(reader, &mut size) }),
        ChurStatus::SessionExpired
    );
    let query = timeline_query();
    let mut buffer = vec![0u8; 512];
    let mut written = 0usize;
    assert_eq!(
        status(unsafe {
            chur_catalog_query(
                session,
                &query,
                buffer.as_mut_ptr(),
                buffer.len(),
                &mut written,
            )
        }),
        ChurStatus::SessionExpired
    );
}

#[test]
fn no_error_string_carries_a_value_the_caller_supplied() {
    // ERROR_MODEL.md: an error carries a stable code and a constant context.
    // The ABI returns only the code, so this asserts the property the code
    // itself has to hold: an invalid credential and an absent vault are one
    // external result.
    let empty = scratch();
    let runtime = open_runtime(&empty);
    let absent = unlock(runtime, PASSWORD, 1).0;
    assert_eq!(unsafe { chur_runtime_close(runtime) }, OK);

    let populated = scratch();
    create_vault(&populated);
    let runtime = open_runtime(&populated);
    let wrong = unlock(runtime, b"a different password", 1).0;
    assert_eq!(unsafe { chur_runtime_close(runtime) }, OK);

    assert_eq!(absent, wrong);
    assert_eq!(status(absent), ChurStatus::AuthenticationFailed);
}
