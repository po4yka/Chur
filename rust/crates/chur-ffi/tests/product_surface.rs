//! The Phase-1 product surface of `docs/interop/FFI_CONTRACT.md` §6.5, driven
//! through the exported symbols.
//!
//! One test walks the whole product flow: create a vault, offer the recovery
//! slot, import, favourite, album, tag, read the detail record, store and read
//! a thumbnail, and delete. The other tests are the refusals each export owes.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
#![expect(
    unsafe_code,
    reason = "the tests drive the C ABI, which is unsafe to call by definition"
)]

use std::io::{Seek, Write};

use chur_core::ChurStatus;
use chur_ffi::api::*;
use chur_ffi::product::*;
use chur_ffi::records::*;

const OK: i32 = 0;
const PASSWORD: &[u8] = b"correct horse battery staple";

fn status(value: i32) -> ChurStatus {
    ChurStatus::from_i32(value)
}

fn scratch() -> std::path::PathBuf {
    let mut path = std::env::temp_dir();
    path.push(format!(
        "chur-product-{}",
        chur_crypto::random::id().unwrap().to_hex()
    ));
    std::fs::create_dir_all(&path).unwrap();
    path
}

fn open_runtime(root: &std::path::Path) -> u64 {
    let text = root.to_str().unwrap().as_bytes();
    let config = ChurRuntimeConfigV1 {
        root_path: text.as_ptr(),
        root_path_length: text.len() as u32,
    };
    let mut handle = 0u64;
    assert_eq!(unsafe { chur_runtime_open(&config, &mut handle) }, OK);
    handle
}

fn create_request(password: &[u8]) -> ChurCreateRequestV1 {
    ChurCreateRequestV1 {
        password: password.as_ptr(),
        password_length: password.len() as u32,
        memory_kib: 0,
        iterations: 0,
        parallelism: 0,
    }
}

fn tempfile() -> std::fs::File {
    let mut path = std::env::temp_dir();
    path.push(format!(
        "chur-product-src-{}",
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
fn descriptor(file: &std::fs::File) -> i32 {
    use std::os::fd::AsRawFd;
    file.as_raw_fd()
}

fn drain(operation: u64) -> i32 {
    loop {
        let mut progress = ChurProgressV1 {
            kind: 0,
            stage: 0,
            processed: 0,
            total: 0,
            terminal: 0,
            reserved: [0; 3],
            status: 0,
        };
        assert_eq!(unsafe { chur_operation_poll(operation, &mut progress) }, OK);
        if progress.terminal == 1 {
            return progress.status;
        }
        std::thread::yield_now();
    }
}

fn import(session: u64, bytes: &[u8], filename: &[u8]) -> [u8; 16] {
    let mut file = tempfile();
    file.write_all(bytes).unwrap();
    file.rewind().unwrap();
    let content_type = b"image/jpeg";
    let request = ChurImportRequestV1 {
        seekable: 1,
        known_length_present: 1,
        media_class: 1,
        reserved: 0,
        width: 1_200,
        height: 900,
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
        unsafe { chur_import_begin(session, descriptor(&file), &request, &mut operation) },
        OK
    );
    assert_eq!(drain(operation), OK);
    assert_eq!(unsafe { chur_operation_close(operation) }, OK);
    latest_object(session)
}

fn page(session: u64, scope: u8, scope_id: [u8; 16], terms: &[u8]) -> DecodedPage {
    let query = ChurQueryV1 {
        scope,
        sort: 1,
        kinds: 0,
        limit: 0,
        scope_id,
        cursor_present: 0,
        cursor: [0; 42],
        terms: terms.as_ptr(),
        terms_length: terms.len() as u32,
    };
    let mut buffer = vec![0u8; 63 + 79 * 200];
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
    decode_page(&buffer[..written]).unwrap()
}

fn latest_object(session: u64) -> [u8; 16] {
    let page = page(session, 1, [0; 16], b"");
    *page.objects.first().unwrap().object_id.as_bytes()
}

#[test]
fn the_whole_product_flow_runs_through_the_boundary() {
    let root = scratch();
    let runtime = open_runtime(&root);

    // PROVISIONING §2: first launch finds no vault.
    let mut present = 9u8;
    assert_eq!(unsafe { chur_vault_present(runtime, &mut present) }, OK);
    assert_eq!(present, 0);

    // §3 steps 3 to 6, with the recovery offer of step 5 in its place.
    let request = create_request(PASSWORD);
    let mut creation = 0u64;
    assert_eq!(
        unsafe { chur_vault_create_begin(runtime, &request, &mut creation) },
        OK
    );
    let mut phrase_bytes = vec![0u8; RECOVERY_PHRASE_MAX];
    let mut written = 0usize;
    assert_eq!(
        unsafe {
            chur_vault_creation_add_recovery_slot(
                creation,
                phrase_bytes.as_mut_ptr(),
                phrase_bytes.len(),
                &mut written,
            )
        },
        OK
    );
    let phrase = String::from_utf8(phrase_bytes[..written].to_vec()).expect("UTF-8");
    assert_eq!(phrase.split_whitespace().count(), 24, "a 24-word phrase");
    let mut session = 0u64;
    assert_eq!(
        unsafe { chur_vault_creation_activate(creation, &mut session) },
        OK
    );
    assert_eq!(unsafe { chur_vault_present(runtime, &mut present) }, OK);
    assert_eq!(present, 1);

    // §6.5: the slot list carries the two slots and no body.
    let mut slots = vec![0u8; 1_024];
    assert_eq!(
        unsafe { chur_vault_slots(session, slots.as_mut_ptr(), slots.len(), &mut written) },
        OK
    );
    assert_eq!(written, 4 + 2 * 25, "two slots at 25 bytes each");

    // Import, then every library mutation the four destinations need.
    let bytes: Vec<u8> = (0..40_000u32).map(|value| (value % 253) as u8).collect();
    let object_id = import(session, &bytes, "Bäckerei.jpg".as_bytes());
    let reference = ChurObjectRefV1 { object_id };

    assert_eq!(
        unsafe { chur_object_set_favorite(session, &reference, 1) },
        OK
    );
    assert_eq!(page(session, 3, [0; 16], b"").objects.len(), 1);

    let name = b"Holiday";
    let mut album_id = [0u8; 16];
    assert_eq!(
        unsafe {
            chur_album_create(
                session,
                name.as_ptr(),
                name.len() as u32,
                album_id.as_mut_ptr(),
            )
        },
        OK
    );
    assert_eq!(
        unsafe { chur_album_set_membership(session, album_id.as_ptr(), &reference, 1) },
        OK
    );
    assert_eq!(page(session, 2, album_id, b"").objects.len(), 1);

    let mut albums = vec![0u8; 4_096];
    assert_eq!(
        unsafe { chur_album_list(session, albums.as_mut_ptr(), albums.len(), &mut written) },
        OK
    );
    let listed = decode_album_list(&albums[..written]).unwrap();
    assert_eq!(listed.len(), 1);
    assert_eq!(listed[0].name, "Holiday");
    assert_eq!(listed[0].member_count, 1);

    let tag_name = "Sommer";
    let mut tag_id = [0u8; 16];
    assert_eq!(
        unsafe {
            chur_tag_create(
                session,
                tag_name.as_ptr(),
                tag_name.len() as u32,
                tag_id.as_mut_ptr(),
            )
        },
        OK
    );
    assert_eq!(
        unsafe { chur_object_set_tag(session, tag_id.as_ptr(), &reference, 1) },
        OK
    );
    assert_eq!(page(session, 4, tag_id, b"").objects.len(), 1);
    // §16.4: a tag is indexed, and the tokenizer folds the diacritic.
    assert_eq!(page(session, 5, [0; 16], b"sommer").objects.len(), 1);
    assert_eq!(page(session, 5, [0; 16], b"backerei").objects.len(), 1);

    // §6.5: the detail record, which is the only record carrying private text.
    let mut detail = vec![0u8; 8_192];
    assert_eq!(
        unsafe {
            chur_object_metadata(
                session,
                &reference,
                detail.as_mut_ptr(),
                detail.len(),
                &mut written,
            )
        },
        OK
    );
    let metadata = decode_object_metadata(&detail[..written]).unwrap();
    assert_eq!(metadata.filename, "Bäckerei.jpg");
    assert_eq!(metadata.content_type, "image/jpeg");
    assert_eq!(metadata.plaintext_size, bytes.len() as u64);
    assert_eq!(metadata.width, 1_200);
    assert!(!metadata.capture_time_substituted);
    assert_eq!(metadata.tags.len(), 1);
    assert_eq!(metadata.tags[0].1, "Sommer");

    // MEDIA_PIPELINE §6: the platform hands over a thumbnail and reads it back.
    let thumbnail: Vec<u8> = (0..3_000u32).map(|value| (value % 97) as u8).collect();
    assert_eq!(
        unsafe {
            chur_derived_put(
                session,
                &reference,
                2,
                320,
                240,
                thumbnail.as_ptr(),
                thumbnail.len() as u32,
            )
        },
        OK
    );
    assert!(page(session, 1, [0; 16], b"").objects[0].thumbnail_ready);
    let mut read = vec![0u8; 8_192];
    assert_eq!(
        unsafe {
            chur_derived_read(
                session,
                &reference,
                2,
                read.as_mut_ptr(),
                read.len(),
                &mut written,
            )
        },
        OK
    );
    assert_eq!(&read[..written], thumbnail.as_slice());

    // Deletion runs the whole §14.1 transaction.
    assert_eq!(unsafe { chur_object_delete(session, &reference) }, OK);
    assert!(page(session, 1, [0; 16], b"").objects.is_empty());
    assert!(page(session, 3, [0; 16], b"").objects.is_empty());
    assert!(page(session, 5, [0; 16], b"backerei").objects.is_empty());
    assert_eq!(
        status(unsafe {
            chur_object_metadata(
                session,
                &reference,
                read.as_mut_ptr(),
                read.len(),
                &mut written,
            )
        }),
        ChurStatus::NotFound
    );

    // The recovery phrase from step 5 still opens the vault.
    assert_eq!(unsafe { chur_runtime_close(runtime) }, OK);
    let runtime = open_runtime(&root);
    let unlock = ChurUnlockRequestV1 {
        factor: 2,
        reserved: [0; 3],
        secret: phrase.as_ptr(),
        secret_length: phrase.len() as u32,
    };
    let mut recovered = 0u64;
    assert_eq!(
        unsafe { chur_vault_unlock(runtime, &unlock, &mut recovered) },
        OK
    );
    assert_eq!(unsafe { chur_runtime_close(runtime) }, OK);
}

#[test]
fn an_abandoned_creation_leaves_no_vault() {
    let root = scratch();
    let runtime = open_runtime(&root);
    let request = create_request(PASSWORD);
    let mut creation = 0u64;
    assert_eq!(
        unsafe { chur_vault_create_begin(runtime, &request, &mut creation) },
        OK
    );
    let mut present = 9u8;
    assert_eq!(unsafe { chur_vault_present(runtime, &mut present) }, OK);
    assert_eq!(present, 0, "nothing is openable before activation");

    assert_eq!(unsafe { chur_vault_creation_abandon(creation) }, OK);
    assert_eq!(unsafe { chur_vault_present(runtime, &mut present) }, OK);
    assert_eq!(present, 0);

    // §3: the abandon closed the handle, so a later call on it is
    // SESSION_EXPIRED rather than a second creation. INVALID_INPUT is reserved
    // for a value this process never issued, and this one was issued.
    let mut session = 0u64;
    assert_eq!(
        status(unsafe { chur_vault_creation_activate(creation, &mut session) }),
        ChurStatus::SessionExpired
    );
    assert_eq!(session, 0);
    assert_eq!(unsafe { chur_runtime_close(runtime) }, OK);
}

#[test]
fn a_creation_handle_activates_once() {
    let root = scratch();
    let runtime = open_runtime(&root);
    let request = create_request(PASSWORD);
    let mut creation = 0u64;
    assert_eq!(
        unsafe { chur_vault_create_begin(runtime, &request, &mut creation) },
        OK
    );
    let mut first = 0u64;
    assert_eq!(
        unsafe { chur_vault_creation_activate(creation, &mut first) },
        OK
    );
    let mut second = 0u64;
    assert_eq!(
        status(unsafe { chur_vault_creation_activate(creation, &mut second) }),
        ChurStatus::SessionExpired
    );
    assert_eq!(second, 0);
    assert_eq!(unsafe { chur_runtime_close(runtime) }, OK);
}

#[test]
fn the_last_portable_slot_cannot_be_removed_through_the_boundary() {
    let root = scratch();
    let runtime = open_runtime(&root);
    let request = create_request(PASSWORD);
    let mut creation = 0u64;
    assert_eq!(
        unsafe { chur_vault_create_begin(runtime, &request, &mut creation) },
        OK
    );
    let mut session = 0u64;
    assert_eq!(
        unsafe { chur_vault_creation_activate(creation, &mut session) },
        OK
    );

    let mut slots = vec![0u8; 1_024];
    let mut written = 0usize;
    assert_eq!(
        unsafe { chur_vault_slots(session, slots.as_mut_ptr(), slots.len(), &mut written) },
        OK
    );
    // One slot: the password. Its identifier is the first 16 bytes after the
    // count.
    assert_eq!(written, 4 + 25);
    let slot_id: [u8; 16] = slots[4..20].try_into().unwrap();
    assert_eq!(
        status(unsafe { chur_vault_remove_slot(session, slot_id.as_ptr()) }),
        ChurStatus::Conflict,
        "KEY_SLOTS.md §9 keeps a verified recovery path through every update"
    );

    // With a recovery slot present it becomes removable.
    let mut phrase = vec![0u8; RECOVERY_PHRASE_MAX];
    assert_eq!(
        unsafe {
            chur_vault_add_recovery_slot(session, phrase.as_mut_ptr(), phrase.len(), &mut written)
        },
        OK
    );
    assert_eq!(
        unsafe { chur_vault_remove_slot(session, slot_id.as_ptr()) },
        OK
    );
    assert_eq!(unsafe { chur_runtime_close(runtime) }, OK);
}

#[test]
fn a_password_change_takes_effect_and_retires_the_old_one() {
    let root = scratch();
    let runtime = open_runtime(&root);
    let request = create_request(PASSWORD);
    let mut creation = 0u64;
    assert_eq!(
        unsafe { chur_vault_create_begin(runtime, &request, &mut creation) },
        OK
    );
    let mut session = 0u64;
    assert_eq!(
        unsafe { chur_vault_creation_activate(creation, &mut session) },
        OK
    );

    let replacement = b"a different password entirely";
    let change = ChurUnlockRequestV1 {
        factor: 1,
        reserved: [0; 3],
        secret: replacement.as_ptr(),
        secret_length: replacement.len() as u32,
    };
    assert_eq!(unsafe { chur_vault_change_password(session, &change) }, OK);
    assert_eq!(unsafe { chur_runtime_close(runtime) }, OK);

    let runtime = open_runtime(&root);
    let mut opened = 0u64;
    let old = ChurUnlockRequestV1 {
        factor: 1,
        reserved: [0; 3],
        secret: PASSWORD.as_ptr(),
        secret_length: PASSWORD.len() as u32,
    };
    assert_eq!(
        status(unsafe { chur_vault_unlock(runtime, &old, &mut opened) }),
        ChurStatus::AuthenticationFailed
    );
    assert_eq!(
        unsafe { chur_vault_unlock(runtime, &change, &mut opened) },
        OK
    );
    assert_eq!(unsafe { chur_runtime_close(runtime) }, OK);
}

#[test]
fn a_boolean_argument_is_strictly_zero_or_one() {
    let root = scratch();
    let runtime = open_runtime(&root);
    let request = create_request(PASSWORD);
    let mut creation = 0u64;
    assert_eq!(
        unsafe { chur_vault_create_begin(runtime, &request, &mut creation) },
        OK
    );
    let mut session = 0u64;
    assert_eq!(
        unsafe { chur_vault_creation_activate(creation, &mut session) },
        OK
    );
    let object_id = import(session, &[1u8; 2_048], b"a.jpg");
    let reference = ChurObjectRefV1 { object_id };
    assert_eq!(
        status(unsafe { chur_object_set_favorite(session, &reference, 2) }),
        ChurStatus::NonCanonicalEncoding
    );
    assert_eq!(unsafe { chur_runtime_close(runtime) }, OK);
}

#[test]
fn a_derivative_above_its_long_edge_is_refused_at_the_boundary() {
    let root = scratch();
    let runtime = open_runtime(&root);
    let request = create_request(PASSWORD);
    let mut creation = 0u64;
    assert_eq!(
        unsafe { chur_vault_create_begin(runtime, &request, &mut creation) },
        OK
    );
    let mut session = 0u64;
    assert_eq!(
        unsafe { chur_vault_creation_activate(creation, &mut session) },
        OK
    );
    let object_id = import(session, &[1u8; 2_048], b"a.jpg");
    let reference = ChurObjectRefV1 { object_id };
    let bytes = [7u8; 64];
    assert_eq!(
        status(unsafe { chur_derived_put(session, &reference, 2, 321, 240, bytes.as_ptr(), 64) }),
        ChurStatus::ResourceLimitExceeded
    );
    // The original kind is not a derived asset.
    assert_eq!(
        status(unsafe { chur_derived_put(session, &reference, 1, 320, 240, bytes.as_ptr(), 64) }),
        ChurStatus::InvalidInput
    );
    assert_eq!(unsafe { chur_runtime_close(runtime) }, OK);
}

/// A stand-in for the Keystore cipher, as in `chur-catalog`'s vault tests.
///
/// Rust neither performs nor verifies this AEAD, so what these tests check is
/// that the alias, the nonce, the wrapped bytes, and the AAD cross the boundary
/// unchanged and that the unwrapped root opens the vault.
fn keystore_wrap(aad: &[u8], root: &[u8; 32]) -> ([u8; 12], [u8; 48]) {
    let nonce = chur_crypto::random::array::<12>().unwrap();
    let mask = chur_crypto::commit::commit(b"chur/test/keystore-stand-in", &[&nonce, aad]);
    let mut wrapped = [0u8; 48];
    for (index, byte) in root.iter().enumerate() {
        wrapped[index] = byte ^ mask[index];
    }
    wrapped[32..].copy_from_slice(&mask[..16]);
    (nonce, wrapped)
}

fn keystore_unwrap(gcm_nonce: &[u8], aad: &[u8], wrapped: &[u8]) -> [u8; 32] {
    let mask = chur_crypto::commit::commit(b"chur/test/keystore-stand-in", &[gcm_nonce, aad]);
    assert_eq!(wrapped[32..], mask[..16]);
    let mut root = [0u8; 32];
    for (index, byte) in root.iter_mut().enumerate() {
        *byte = wrapped[index] ^ mask[index];
    }
    root
}

/// Reads a big-endian `u32` and the bytes it counts.
fn take_bounded<'a>(bytes: &'a [u8], at: &mut usize) -> &'a [u8] {
    let length = u32::from_be_bytes(bytes[*at..*at + 4].try_into().unwrap()) as usize;
    *at += 4;
    let value = &bytes[*at..*at + length];
    *at += length;
    value
}

/// Enrolls a Keystore slot on an open session and returns the alias.
fn enroll_keystore(session: u64) -> Vec<u8> {
    let mut buffer = [0u8; 512];
    let mut written = 0usize;
    assert_eq!(
        unsafe {
            chur_vault_keystore_begin(session, buffer.as_mut_ptr(), buffer.len(), &mut written)
        },
        OK
    );
    let record = &buffer[..written];
    let mut at = 0usize;
    let alias = take_bounded(record, &mut at).to_vec();
    let aad = take_bounded(record, &mut at).to_vec();
    let root: [u8; 32] = record[at..at + 32].try_into().unwrap();
    let (nonce, wrapped) = keystore_wrap(&aad, &root);
    assert_eq!(
        unsafe { chur_vault_keystore_commit(session, nonce.as_ptr(), wrapped.as_ptr()) },
        OK
    );
    alias
}

/// Opens a session on a fresh vault.
fn fresh_session(runtime: u64) -> u64 {
    let request = create_request(PASSWORD);
    let mut creation = 0u64;
    assert_eq!(
        unsafe { chur_vault_create_begin(runtime, &request, &mut creation) },
        OK
    );
    let mut session = 0u64;
    assert_eq!(
        unsafe { chur_vault_creation_activate(creation, &mut session) },
        OK
    );
    session
}

#[test]
fn the_keystore_slot_enrolls_and_unlocks_through_the_boundary() {
    let root = scratch();
    let runtime = open_runtime(&root);
    let session = fresh_session(runtime);
    let alias = enroll_keystore(session);
    assert_eq!(alias.len(), 32);
    assert_eq!(unsafe { chur_vault_lock(session, 1) }, OK);
    assert_eq!(unsafe { chur_session_close(session) }, OK);

    // The material is readable while the vault is locked, which is what a host
    // needs: it cannot ask the Keystore before it has the alias and the nonce.
    let mut buffer = [0u8; 512];
    let mut written = 0usize;
    assert_eq!(
        unsafe {
            chur_vault_keystore_material(runtime, buffer.as_mut_ptr(), buffer.len(), &mut written)
        },
        OK
    );
    let record = &buffer[..written];
    assert_eq!(u32::from_be_bytes(record[..4].try_into().unwrap()), 1);
    let mut at = 4usize;
    let stored_alias = take_bounded(record, &mut at).to_vec();
    let aad = take_bounded(record, &mut at).to_vec();
    let nonce = &record[at..at + 12];
    let wrapped = &record[at + 12..at + 60];
    assert_eq!(stored_alias, alias);

    let unwrapped = keystore_unwrap(nonce, &aad, wrapped);
    let unlock = ChurUnlockRequestV1 {
        factor: 4,
        reserved: [0; 3],
        secret: unwrapped.as_ptr(),
        secret_length: unwrapped.len() as u32,
    };
    let mut reopened = 0u64;
    assert_eq!(
        unsafe { chur_vault_unlock(runtime, &unlock, &mut reopened) },
        OK
    );
    assert_ne!(reopened, 0);
    assert_eq!(unsafe { chur_session_close(reopened) }, OK);
    assert_eq!(unsafe { chur_runtime_close(runtime) }, OK);
}

#[test]
fn a_root_the_keystore_did_not_return_is_authentication_failed() {
    let root = scratch();
    let runtime = open_runtime(&root);
    let session = fresh_session(runtime);
    enroll_keystore(session);
    assert_eq!(unsafe { chur_session_close(session) }, OK);

    let wrong = [7u8; 32];
    let unlock = ChurUnlockRequestV1 {
        factor: 4,
        reserved: [0; 3],
        secret: wrong.as_ptr(),
        secret_length: wrong.len() as u32,
    };
    let mut reopened = 0u64;
    assert_eq!(
        status(unsafe { chur_vault_unlock(runtime, &unlock, &mut reopened) }),
        ChurStatus::AuthenticationFailed
    );
    assert_eq!(reopened, 0);
    assert_eq!(unsafe { chur_runtime_close(runtime) }, OK);
}

#[test]
fn a_commit_with_no_enrollment_is_refused() {
    let root = scratch();
    let runtime = open_runtime(&root);
    let session = fresh_session(runtime);
    let nonce = [0u8; 12];
    let wrapped = [0u8; 48];
    assert_eq!(
        status(unsafe { chur_vault_keystore_commit(session, nonce.as_ptr(), wrapped.as_ptr()) }),
        ChurStatus::Conflict
    );
    assert_eq!(unsafe { chur_session_close(session) }, OK);
    assert_eq!(unsafe { chur_runtime_close(runtime) }, OK);
}

#[test]
fn a_material_buffer_smaller_than_the_record_writes_nothing() {
    let root = scratch();
    let runtime = open_runtime(&root);
    let session = fresh_session(runtime);
    enroll_keystore(session);
    assert_eq!(unsafe { chur_session_close(session) }, OK);

    let mut small = [0u8; 8];
    let mut written = 7usize;
    assert_eq!(
        status(unsafe {
            chur_vault_keystore_material(runtime, small.as_mut_ptr(), small.len(), &mut written)
        }),
        ChurStatus::ResourceLimitExceeded
    );
    assert_eq!(written, 0, "the count is set on every failure");
    assert_eq!(small, [0u8; 8], "a refused write leaves the buffer alone");
    assert_eq!(unsafe { chur_runtime_close(runtime) }, OK);
}
