//! One JNI function per `chur_*` export, ADR-0040.
//!
//! The Java side is `dev.po4yka.chur.ffi.ChurNative`, so every symbol here is
//! `Java_dev_po4yka_chur_ffi_ChurJni_<method>`. The mapping is one to one
//! and deliberately mechanical: a dispatcher on an opcode would hide which
//! export a call reaches from every tool that reads symbols.
//!
//! Every function is `extern "system"`, which is what the JVM calls with, and
//! returns the export's status unchanged.

#![expect(
    unsafe_code,
    reason = "ADR-0040: JNI requires an exported symbol whose name encodes the Java class and method"
)]
use chur_ffi::api::Status;
use chur_ffi::records::{
    ChurContentInfoV1, ChurCreateRequestV1, ChurImportRequestV1, ChurObjectRefV1, ChurProgressV1,
    ChurQueryV1, ChurRuntimeConfigV1, ChurScanRequestV1, ChurUnlockRequestV1,
};
use chur_ffi::sync::ChurSyncReportV1;
use jni::JNIEnv;
use jni::objects::{JByteArray, JByteBuffer, JClass, JIntArray, JLongArray, JString};
use jni::sys::{jboolean, jint, jlong};

use crate::convert::{
    INTERNAL_FAILURE, INVALID_INPUT, byte_array, direct_buffer, fixed_array, string_bytes,
    write_bytes, write_ints, write_long, write_longs,
};

/// The identifier length of `docs/format/CANONICAL_ENCODING_V1.md` §8.
const ID_LEN: usize = 16;

/// The secret length of `docs/interop/FFI_CONTRACT.md` §6.5.
const SECRET_LEN: usize = 32;

/// The cursor length of `docs/format/CATALOG_SCHEMA_V1.md` §16.2.
const CURSOR_LEN: usize = 42;

// ---------------------------------------------------------------------------
// Handshake, §2. None can fail, so none returns a status.
// ---------------------------------------------------------------------------

/// `abiVersionMajor` of the handshake.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_abiVersionMajor(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jint {
    chur_ffi::chur_abi_version_major() as jint
}

/// `abiVersionMinor` of the handshake.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_abiVersionMinor(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jint {
    chur_ffi::chur_abi_version_minor() as jint
}

/// `capabilities` of the handshake.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_capabilities(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jlong {
    chur_ffi::chur_capabilities() as jlong
}

/// `objectFormatMin` of the handshake.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_objectFormatMin(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jint {
    jint::from(chur_ffi::chur_object_format_min())
}

/// `objectFormatMax` of the handshake.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_objectFormatMax(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jint {
    jint::from(chur_ffi::chur_object_format_max())
}

/// `keySlotFormatMin` of the handshake.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_keySlotFormatMin(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jint {
    jint::from(chur_ffi::chur_key_slot_format_min())
}

/// `keySlotFormatMax` of the handshake.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_keySlotFormatMax(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jint {
    jint::from(chur_ffi::chur_key_slot_format_max())
}

/// `buildFlavor` of the handshake.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_buildFlavor(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jint {
    chur_ffi::chur_build_flavor() as jint
}

/// Whether a status value is one this build allocates.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_statusIsKnown(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    value: jint,
) -> jboolean {
    jboolean::from(chur_ffi::chur_status_is_known(value))
}

// ---------------------------------------------------------------------------
// Runtime, session, and creation
// ---------------------------------------------------------------------------

/// Opens the process runtime over a storage root.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_runtimeOpen<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    root: JString<'local>,
    out_runtime: JLongArray<'local>,
) -> jint {
    let Some(bytes) = string_bytes(&mut env, &root) else {
        return INVALID_INPUT;
    };
    let config = ChurRuntimeConfigV1 {
        root_path: bytes.as_ptr(),
        root_path_length: match u32::try_from(bytes.len()) {
            Ok(value) => value,
            Err(_) => return INVALID_INPUT,
        },
    };
    let mut handle = 0u64;
    // SAFETY: `config` is a live local and `handle` is a live local, so both
    // satisfy the pointer contract of the export for the length of the call.
    let status = unsafe { chur_ffi::api::chur_runtime_open(&config, &mut handle) };
    finish_handle(&mut env, status, &out_runtime, handle)
}

/// Closes the runtime and every handle it owns.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_runtimeClose(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    runtime: jlong,
) -> jint {
    // SAFETY: the handle is a scalar the export validates itself.
    unsafe { chur_ffi::api::chur_runtime_close(handle_of(runtime)) }
}

/// Stages one opaque sync record while the vault may be locked.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_syncStage<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    runtime: jlong,
    vault_id: JByteArray<'local>,
    kind: jint,
    staged_at_ms: jlong,
    record: JByteBuffer<'local>,
    length: jint,
) -> jint {
    let Some(vault_id) = fixed_array(&mut env, &vault_id, ID_LEN) else {
        return INVALID_INPUT;
    };
    let Some((address, capacity)) = direct_buffer(&env, &record) else {
        return INVALID_INPUT;
    };
    let (Ok(kind), Ok(staged_at_ms), Ok(length)) = (
        u8::try_from(kind),
        u64::try_from(staged_at_ms),
        u32::try_from(length),
    ) else {
        return INVALID_INPUT;
    };
    if length as usize > capacity {
        return INVALID_INPUT;
    }
    // SAFETY: the identifier and direct buffer remain live for this call.
    unsafe {
        chur_ffi::sync::chur_sync_stage(
            handle_of(runtime),
            vault_id.as_ptr(),
            kind,
            staged_at_ms,
            address,
            length,
        )
    }
}

/// Processes the current unlocked vault's sync inbox.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_syncProcess<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    now_ms: jlong,
    out_counts: JLongArray<'local>,
    out_status: JIntArray<'local>,
) -> jint {
    let Ok(now_ms) = u64::try_from(now_ms) else {
        return INVALID_INPUT;
    };
    let mut report = ChurSyncReportV1 {
        applied: 0,
        duplicates: 0,
        pending: 0,
        rejected: 0,
        first_rejection: 0,
        reserved: [0; 4],
    };
    // SAFETY: `report` is a live writable local.
    let status =
        unsafe { chur_ffi::sync::chur_sync_process(handle_of(session), now_ms, &mut report) };
    if status != 0 {
        return status;
    }
    if !write_longs(
        &mut env,
        &out_counts,
        &[
            report.applied as jlong,
            report.duplicates as jlong,
            report.pending as jlong,
            report.rejected as jlong,
        ],
        0,
    ) || !write_ints(&mut env, &out_status, &[report.first_rejection])
    {
        return INVALID_INPUT;
    }
    0
}

/// Whether the storage root holds a vault.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_vaultPresent<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    runtime: jlong,
    out_present: JByteArray<'local>,
) -> jint {
    let mut present = 0u8;
    // SAFETY: `present` is a live local.
    let status = unsafe { chur_ffi::product::chur_vault_present(handle_of(runtime), &mut present) };
    if status != 0 {
        return status;
    }
    if write_bytes(&mut env, &out_present, &[present]) {
        0
    } else {
        INVALID_INPUT
    }
}

/// Begins vault creation.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_vaultCreateBegin<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    runtime: jlong,
    password: JByteArray<'local>,
    memory_kib: jint,
    iterations: jint,
    parallelism: jint,
    out_creation: JLongArray<'local>,
) -> jint {
    let Some(bytes) = byte_array(&mut env, &password) else {
        return INVALID_INPUT;
    };
    let request = ChurCreateRequestV1 {
        password: bytes.as_ptr(),
        password_length: match u32::try_from(bytes.len()) {
            Ok(value) => value,
            Err(_) => return INVALID_INPUT,
        },
        memory_kib: memory_kib as u32,
        iterations: iterations as u32,
        parallelism: parallelism as u32,
    };
    let mut handle = 0u64;
    // SAFETY: `request` and `handle` are live locals.
    let status = unsafe {
        chur_ffi::product::chur_vault_create_begin(handle_of(runtime), &request, &mut handle)
    };
    finish_handle(&mut env, status, &out_creation, handle)
}

/// Offers the recovery slot during creation.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_vaultCreationAddRecoverySlot<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    creation: jlong,
    destination: JByteBuffer<'local>,
    out_written: JIntArray<'local>,
) -> jint {
    let Some((address, capacity)) = direct_buffer(&env, &destination) else {
        return INVALID_INPUT;
    };
    let mut written = 0usize;
    // SAFETY: `written` is a live local and the buffer is direct.
    let status = unsafe {
        chur_ffi::product::chur_vault_creation_add_recovery_slot(
            handle_of(creation),
            address,
            capacity,
            &mut written,
        )
    };
    finish_written(&mut env, status, &out_written, written)
}

/// Reaches `ACTIVE` and opens the session.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_vaultCreationActivate<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    creation: jlong,
    out_session: JLongArray<'local>,
) -> jint {
    let mut handle = 0u64;
    // SAFETY: `handle` is a live local.
    let status = unsafe {
        chur_ffi::product::chur_vault_creation_activate(handle_of(creation), &mut handle)
    };
    finish_handle(&mut env, status, &out_session, handle)
}

/// Abandons a creation.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_vaultCreationAbandon(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    creation: jlong,
) -> jint {
    // SAFETY: the handle is a scalar the export validates itself.
    unsafe { chur_ffi::product::chur_vault_creation_abandon(handle_of(creation)) }
}

/// Unlocks a vault.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_vaultUnlock<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    runtime: jlong,
    factor: jint,
    secret: JByteArray<'local>,
    out_session: JLongArray<'local>,
) -> jint {
    let Some(bytes) = byte_array(&mut env, &secret) else {
        return INVALID_INPUT;
    };
    let Ok(factor) = u8::try_from(factor) else {
        return INVALID_INPUT;
    };
    let request = ChurUnlockRequestV1 {
        factor,
        reserved: [0; 3],
        secret: bytes.as_ptr(),
        secret_length: match u32::try_from(bytes.len()) {
            Ok(value) => value,
            Err(_) => return INVALID_INPUT,
        },
    };
    let mut handle = 0u64;
    // SAFETY: `request` and `handle` are live locals.
    let status =
        unsafe { chur_ffi::api::chur_vault_unlock(handle_of(runtime), &request, &mut handle) };
    finish_handle(&mut env, status, &out_session, handle)
}

/// Locks a session.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_vaultLock(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    session: jlong,
    reason: jint,
) -> jint {
    // SAFETY: both arguments are scalars the export validates itself.
    unsafe { chur_ffi::api::chur_vault_lock(handle_of(session), reason as u32) }
}

/// Closes a session handle.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_sessionClose(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    session: jlong,
) -> jint {
    // SAFETY: the handle is a scalar the export validates itself.
    unsafe { chur_ffi::api::chur_session_close(handle_of(session)) }
}

/// Provisions or returns the ordinary local collection-sharing identity.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_sharingIdentity<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    destination: JByteBuffer<'local>,
    out_written: JIntArray<'local>,
) -> jint {
    let Some((address, capacity)) = direct_buffer(&env, &destination) else {
        return INVALID_INPUT;
    };
    let mut written = 0usize;
    // SAFETY: `written` is a live local and the buffer is direct.
    let status = unsafe {
        chur_ffi::sharing::chur_sharing_identity(
            handle_of(session),
            address,
            capacity,
            &mut written,
        )
    };
    finish_written(&mut env, status, &out_written, written)
}

/// Prepares one recipient membership and collection-key grant.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_sharingPrepare<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    collection_id: JByteArray<'local>,
    recipient_enrollment: JByteArray<'local>,
    permissions: jint,
    fingerprint_verified: jboolean,
    destination: JByteBuffer<'local>,
    out_written: JIntArray<'local>,
) -> jint {
    let Some(collection_id) = fixed_array(&mut env, &collection_id, ID_LEN) else {
        return INVALID_INPUT;
    };
    let Some(recipient_enrollment) = byte_array(&mut env, &recipient_enrollment) else {
        return INVALID_INPUT;
    };
    let Ok(recipient_enrollment_length) = u32::try_from(recipient_enrollment.len()) else {
        return INVALID_INPUT;
    };
    let Ok(permissions) = u8::try_from(permissions) else {
        return INVALID_INPUT;
    };
    let Some((address, capacity)) = direct_buffer(&env, &destination) else {
        return INVALID_INPUT;
    };
    let mut written = 0usize;
    // SAFETY: the vectors, `written`, and direct buffer stay live for the call.
    let status = unsafe {
        chur_ffi::sharing::chur_sharing_prepare(
            handle_of(session),
            collection_id.as_ptr(),
            recipient_enrollment.as_ptr(),
            recipient_enrollment_length,
            permissions,
            u8::from(fingerprint_verified != 0),
            address,
            capacity,
            &mut written,
        )
    };
    finish_written(&mut env, status, &out_written, written)
}

/// Revokes one recipient and writes one bounded rotation batch.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_sharingRevoke<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    collection_id: JByteArray<'local>,
    recipient_vault_id: JByteArray<'local>,
    recipient_device_id: JByteArray<'local>,
    accepted_at_ms: jlong,
    destination: JByteBuffer<'local>,
    out_written: JIntArray<'local>,
) -> jint {
    let Some(collection_id) = fixed_array(&mut env, &collection_id, ID_LEN) else {
        return INVALID_INPUT;
    };
    let Some(recipient_vault_id) = fixed_array(&mut env, &recipient_vault_id, ID_LEN) else {
        return INVALID_INPUT;
    };
    let Some(recipient_device_id) = fixed_array(&mut env, &recipient_device_id, ID_LEN) else {
        return INVALID_INPUT;
    };
    let Ok(accepted_at_ms) = u64::try_from(accepted_at_ms) else {
        return INVALID_INPUT;
    };
    let Some((address, capacity)) = direct_buffer(&env, &destination) else {
        return INVALID_INPUT;
    };
    let mut written = 0usize;
    // SAFETY: the identifier vectors, `written`, and direct buffer stay live.
    let status = unsafe {
        chur_ffi::sharing::chur_sharing_revoke(
            handle_of(session),
            collection_id.as_ptr(),
            recipient_vault_id.as_ptr(),
            recipient_device_id.as_ptr(),
            accepted_at_ms,
            address,
            capacity,
            &mut written,
        )
    };
    finish_written(&mut env, status, &out_written, written)
}

/// Authenticates and installs one recipient share bundle.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_sharingAccept<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    bundle: JByteBuffer<'local>,
    length: jint,
) -> jint {
    let Some((address, capacity)) = direct_buffer(&env, &bundle) else {
        return INVALID_INPUT;
    };
    let Ok(length) = usize::try_from(length) else {
        return INVALID_INPUT;
    };
    if length > capacity {
        return INVALID_INPUT;
    }
    let Ok(length) = u32::try_from(length) else {
        return INVALID_INPUT;
    };
    // SAFETY: the direct buffer covers `length` live readable bytes.
    unsafe { chur_ffi::sharing::chur_sharing_accept(handle_of(session), address, length) }
}

// ---------------------------------------------------------------------------
// Catalog queries
// ---------------------------------------------------------------------------

/// Writes one page into a direct buffer.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_catalogQuery<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    scope: jint,
    sort: jint,
    kinds: jint,
    limit: jint,
    scope_id: JByteArray<'local>,
    cursor: JByteArray<'local>,
    terms: JByteArray<'local>,
    destination: JByteBuffer<'local>,
    out_written: JIntArray<'local>,
) -> jint {
    let Some(scope_bytes) = fixed_array(&mut env, &scope_id, ID_LEN) else {
        return INVALID_INPUT;
    };
    let mut scope_array = [0u8; ID_LEN];
    scope_array.copy_from_slice(&scope_bytes);

    let cursor_bytes = byte_array(&mut env, &cursor);
    let mut cursor_array = [0u8; CURSOR_LEN];
    let cursor_present = match &cursor_bytes {
        Some(bytes) if bytes.len() == CURSOR_LEN => {
            cursor_array.copy_from_slice(bytes);
            1
        }
        Some(_) => return INVALID_INPUT,
        None => 0,
    };
    let terms_bytes = byte_array(&mut env, &terms).unwrap_or_default();
    let Some((address, capacity)) = direct_buffer(&env, &destination) else {
        return INVALID_INPUT;
    };
    let (Ok(scope), Ok(sort), Ok(kinds), Ok(limit)) = (
        u8::try_from(scope),
        u8::try_from(sort),
        u16::try_from(kinds),
        u32::try_from(limit),
    ) else {
        return INVALID_INPUT;
    };
    let query = ChurQueryV1 {
        scope,
        sort,
        kinds,
        limit,
        scope_id: scope_array,
        cursor_present,
        cursor: cursor_array,
        terms: terms_bytes.as_ptr(),
        terms_length: match u32::try_from(terms_bytes.len()) {
            Ok(value) => value,
            Err(_) => return INVALID_INPUT,
        },
    };
    let mut written = 0usize;
    // SAFETY: `query` and `written` are live locals, and `address` with
    // `capacity` come from a direct buffer the JVM keeps alive for the call.
    let status = unsafe {
        chur_ffi::api::chur_catalog_query(
            handle_of(session),
            &query,
            address,
            capacity,
            &mut written,
        )
    };
    finish_written(&mut env, status, &out_written, written)
}

// ---------------------------------------------------------------------------
// Operations
// ---------------------------------------------------------------------------

/// Starts an import from a descriptor.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_importBegin<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    source_fd: jint,
    media_class: jint,
    width: jint,
    height: jint,
    duration_ms: jlong,
    known_length: jlong,
    capture_time_ms: jlong,
    content_type: JString<'local>,
    original_filename: JString<'local>,
    out_import: JLongArray<'local>,
) -> jint {
    let Some(content) = string_bytes(&mut env, &content_type) else {
        return INVALID_INPUT;
    };
    let filename = string_bytes(&mut env, &original_filename);
    let (Ok(media_class), Ok(width), Ok(height)) = (
        u8::try_from(media_class),
        u32::try_from(width),
        u32::try_from(height),
    ) else {
        return INVALID_INPUT;
    };
    // A negative value is the JVM's way of saying absent, because a Kotlin
    // `Long?` would box on every call.
    let request = ChurImportRequestV1 {
        seekable: 1,
        known_length_present: u8::from(known_length >= 0),
        media_class,
        reserved: 0,
        width,
        height,
        duration_ms: duration_ms.max(0) as u64,
        known_length: known_length.max(0) as u64,
        capture_time_ms: capture_time_ms.max(0) as u64,
        capture_time_present: u8::from(capture_time_ms >= 0),
        reserved_two: [0; 7],
        content_type: content.as_ptr(),
        content_type_length: match u32::try_from(content.len()) {
            Ok(value) => value,
            Err(_) => return INVALID_INPUT,
        },
        original_filename: filename
            .as_ref()
            .map_or(core::ptr::null(), |bytes| bytes.as_ptr()),
        original_filename_length: filename
            .as_ref()
            .and_then(|bytes| u32::try_from(bytes.len()).ok())
            .unwrap_or(0),
    };
    let mut handle = 0u64;
    // SAFETY: every pointer in `request` refers to a live local, and `handle`
    // is a live local.
    let status = unsafe {
        chur_ffi::api::chur_import_begin(handle_of(session), source_fd, &request, &mut handle)
    };
    finish_handle(&mut env, status, &out_import, handle)
}

/// Starts an export to a descriptor.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_exportBegin<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    object_id: JByteArray<'local>,
    destination_fd: jint,
    out_export: JLongArray<'local>,
) -> jint {
    let Some(reference) = object_reference(&mut env, &object_id) else {
        return INVALID_INPUT;
    };
    let mut handle = 0u64;
    // SAFETY: `reference` and `handle` are live locals.
    let status = unsafe {
        chur_ffi::api::chur_export_begin(
            handle_of(session),
            &reference,
            destination_fd,
            &mut handle,
        )
    };
    finish_handle(&mut env, status, &out_export, handle)
}

/// Starts an integrity scan.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_integrityScanBegin<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    object_id: JByteArray<'local>,
    out_scan: JLongArray<'local>,
) -> jint {
    // A null identifier means every object, which the JVM expresses as null
    // rather than as a flag the caller could set inconsistently.
    let single = byte_array(&mut env, &object_id);
    let mut request = ChurScanRequestV1 {
        single_object: 0,
        reserved: [0; 7],
        object_id: [0; ID_LEN],
    };
    if let Some(bytes) = single {
        if bytes.len() != ID_LEN {
            return INVALID_INPUT;
        }
        request.single_object = 1;
        request.object_id.copy_from_slice(&bytes);
    }
    let mut handle = 0u64;
    // SAFETY: `request` and `handle` are live locals.
    let status = unsafe {
        chur_ffi::api::chur_integrity_scan_begin(handle_of(session), &request, &mut handle)
    };
    finish_handle(&mut env, status, &out_scan, handle)
}

/// Copies an operation's progress snapshot.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_operationPoll<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    operation: jlong,
    out_counts: JLongArray<'local>,
    out_states: JIntArray<'local>,
) -> jint {
    let mut progress = ChurProgressV1 {
        kind: 0,
        stage: 0,
        processed: 0,
        total: 0,
        terminal: 0,
        reserved: [0; 3],
        status: 0,
    };
    // SAFETY: `progress` is a live local.
    let status = unsafe { chur_ffi::api::chur_operation_poll(handle_of(operation), &mut progress) };
    if status != 0 {
        return status;
    }
    let counts = [progress.processed as jlong, progress.total as jlong];
    let states = [
        progress.kind as jint,
        progress.stage as jint,
        jint::from(progress.terminal),
        progress.status,
    ];
    if write_longs(&mut env, &out_counts, &counts, 0) && write_ints(&mut env, &out_states, &states)
    {
        0
    } else {
        INVALID_INPUT
    }
}

/// Cancels an operation.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_operationCancel(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    operation: jlong,
) -> jint {
    // SAFETY: the handle is a scalar the export validates itself.
    unsafe { chur_ffi::api::chur_operation_cancel(handle_of(operation)) }
}

/// Closes an operation handle.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_operationClose(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    operation: jlong,
) -> jint {
    // SAFETY: the handle is a scalar the export validates itself.
    unsafe { chur_ffi::api::chur_operation_close(handle_of(operation)) }
}

// ---------------------------------------------------------------------------
// Object reader
// ---------------------------------------------------------------------------

/// Opens a random-access reader.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_objectReaderOpen<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    object_id: JByteArray<'local>,
    stream_kind: jint,
    out_reader: JLongArray<'local>,
) -> jint {
    let Some(reference) = object_reference(&mut env, &object_id) else {
        return INVALID_INPUT;
    };
    let Ok(kind) = u32::try_from(stream_kind) else {
        return INVALID_INPUT;
    };
    let mut handle = 0u64;
    // SAFETY: `reference` and `handle` are live locals.
    let status = unsafe {
        chur_ffi::api::chur_object_reader_open(handle_of(session), &reference, kind, &mut handle)
    };
    finish_handle(&mut env, status, &out_reader, handle)
}

/// Writes the authenticated plaintext size.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_objectReaderSize<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    reader: jlong,
    out_size: JLongArray<'local>,
) -> jint {
    let mut size = 0u64;
    // SAFETY: `size` is a live local.
    let status = unsafe { chur_ffi::api::chur_object_reader_size(handle_of(reader), &mut size) };
    if status != 0 {
        return status;
    }
    if write_long(&mut env, &out_size, 0, size as jlong) {
        0
    } else {
        INVALID_INPUT
    }
}

/// Writes the content information.
///
/// The C structure is decomposed here rather than copied whole, because its
/// padding is the host compiler's and the JVM has no equivalent shape.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_objectReaderContentInfo<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    reader: jlong,
    out_numbers: JLongArray<'local>,
    out_content_type: JByteArray<'local>,
) -> jint {
    let mut info = ChurContentInfoV1 {
        plaintext_size: 0,
        content_type: [0; 64],
        media_kind: 0,
        byte_range_supported: 0,
        complete: 0,
        reserved: [0; 4],
    };
    // SAFETY: `info` is a live local.
    let status =
        unsafe { chur_ffi::api::chur_object_reader_content_info(handle_of(reader), &mut info) };
    if status != 0 {
        return status;
    }
    let numbers = [
        info.plaintext_size as jlong,
        jlong::from(info.media_kind),
        jlong::from(info.byte_range_supported),
        jlong::from(info.complete),
    ];
    let terminator = info
        .content_type
        .iter()
        .position(|byte| *byte == 0)
        .unwrap_or(info.content_type.len());
    if write_longs(&mut env, &out_numbers, &numbers, 0)
        && write_bytes(
            &mut env,
            &out_content_type,
            &info.content_type[..terminator],
        )
    {
        0
    } else {
        INVALID_INPUT
    }
}

/// Reads a plaintext range into a direct buffer.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_objectReaderReadAt<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    reader: jlong,
    offset: jlong,
    destination: JByteBuffer<'local>,
    out_written: JIntArray<'local>,
) -> jint {
    let Some((address, capacity)) = direct_buffer(&env, &destination) else {
        return INVALID_INPUT;
    };
    if offset < 0 {
        return INVALID_INPUT;
    }
    let mut written = 0usize;
    // SAFETY: `written` is a live local, and the buffer is direct and kept
    // alive by the JVM for the call.
    let status = unsafe {
        chur_ffi::api::chur_object_reader_read_at(
            handle_of(reader),
            offset as u64,
            address,
            capacity,
            &mut written,
        )
    };
    finish_written(&mut env, status, &out_written, written)
}

/// Runs complete verification and writes the state it reached.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_objectReaderVerifyComplete<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    reader: jlong,
    out_state: JIntArray<'local>,
) -> jint {
    let mut state = 0u32;
    // SAFETY: `state` is a live local.
    let status =
        unsafe { chur_ffi::api::chur_object_reader_verify_complete(handle_of(reader), &mut state) };
    if status != 0 {
        return status;
    }
    if write_ints(&mut env, &out_state, &[state as jint]) {
        0
    } else {
        INVALID_INPUT
    }
}

/// Closes a reader handle.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_objectReaderClose(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    reader: jlong,
) -> jint {
    // SAFETY: the handle is a scalar the export validates itself.
    unsafe { chur_ffi::api::chur_object_reader_close(handle_of(reader)) }
}

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

/// Turns a JVM `long` into a handle.
///
/// The JVM has no unsigned integer, so a handle whose generation has the high
/// bit set arrives negative. The reinterpretation is the decoding, not a loss.
const fn handle_of(value: jlong) -> u64 {
    value as u64
}

/// Builds an object reference from a 16-byte array.
fn object_reference(env: &mut JNIEnv<'_>, object_id: &JByteArray<'_>) -> Option<ChurObjectRefV1> {
    let bytes = fixed_array(env, object_id, ID_LEN)?;
    let mut value = [0u8; ID_LEN];
    value.copy_from_slice(&bytes);
    Some(ChurObjectRefV1 { object_id: value })
}

/// Writes a handle back on success, or reports the export's status.
fn finish_handle(
    env: &mut JNIEnv<'_>,
    status: Status,
    target: &JLongArray<'_>,
    handle: u64,
) -> jint {
    if status != 0 {
        return status;
    }
    if write_long(env, target, 0, handle as jlong) {
        0
    } else {
        INTERNAL_FAILURE
    }
}

/// Writes a byte count back, which the export sets on every call.
fn finish_written(
    env: &mut JNIEnv<'_>,
    status: Status,
    target: &JIntArray<'_>,
    written: usize,
) -> jint {
    let Ok(count) = jint::try_from(written) else {
        return INTERNAL_FAILURE;
    };
    if !write_ints(env, target, &[count]) {
        return INVALID_INPUT;
    }
    status
}

/// Writes a 32-byte secret back and clears the local copy.
///
/// `docs/interop/FFI_CONTRACT.md` §12 requires immediate best-effort clearing
/// on the foreign side; this clears the Rust side, and the Kotlin adapter
/// clears its array as soon as it is done.
fn finish_secret(
    env: &mut JNIEnv<'_>,
    status: Status,
    target: &JByteArray<'_>,
    secret: &mut [u8; SECRET_LEN],
) -> jint {
    let outcome = if status != 0 {
        status
    } else if write_bytes(env, target, secret) {
        0
    } else {
        INVALID_INPUT
    };
    secret.fill(0);
    outcome
}

// ---------------------------------------------------------------------------
// The §6.5 product surface
// ---------------------------------------------------------------------------

/// Adds a recovery slot to an active vault.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_vaultAddRecoverySlot<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    destination: JByteBuffer<'local>,
    out_written: JIntArray<'local>,
) -> jint {
    let Some((address, capacity)) = direct_buffer(&env, &destination) else {
        return INVALID_INPUT;
    };
    let mut written = 0usize;
    // SAFETY: `written` is a live local and the buffer is direct.
    let status = unsafe {
        chur_ffi::product::chur_vault_add_recovery_slot(
            handle_of(session),
            address,
            capacity,
            &mut written,
        )
    };
    finish_written(&mut env, status, &out_written, written)
}

/// Adds the Apple Keychain slot. Android calls it for a decoy-free parity test
/// only; the Android Keystore slot is a platform wrap and takes no secret here.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_vaultAddDeviceSlot<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    item_id: JByteArray<'local>,
    out_secret: JByteArray<'local>,
) -> jint {
    let Some(item) = fixed_array(&mut env, &item_id, ID_LEN) else {
        return INVALID_INPUT;
    };
    let mut secret = [0u8; SECRET_LEN];
    // SAFETY: `item` and `secret` are live locals of the required lengths.
    let status = unsafe {
        chur_ffi::product::chur_vault_add_device_slot(
            handle_of(session),
            item.as_ptr(),
            secret.as_mut_ptr(),
        )
    };
    finish_secret(&mut env, status, &out_secret, &mut secret)
}

/// Begins the Android Keystore enrollment.
///
/// The buffer receives a root secret. The caller overwrites it as soon as the
/// Keystore wrap returns, which ADR-0041 requires of every holder.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_vaultKeystoreBegin<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    destination: JByteBuffer<'local>,
    out_written: JIntArray<'local>,
) -> jint {
    let Some((address, capacity)) = direct_buffer(&env, &destination) else {
        return INVALID_INPUT;
    };
    let mut written = 0usize;
    // SAFETY: `written` is a live local and the buffer is direct.
    let status = unsafe {
        chur_ffi::product::chur_vault_keystore_begin(
            handle_of(session),
            address,
            capacity,
            &mut written,
        )
    };
    finish_written(&mut env, status, &out_written, written)
}

/// Stores what the Keystore wrap returned.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_vaultKeystoreCommit<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    gcm_nonce: JByteArray<'local>,
    wrapped_root_secret: JByteArray<'local>,
) -> jint {
    let Some(nonce) = fixed_array(&mut env, &gcm_nonce, 12) else {
        return INVALID_INPUT;
    };
    let Some(wrapped) = fixed_array(&mut env, &wrapped_root_secret, 48) else {
        return INVALID_INPUT;
    };
    // SAFETY: both vectors are live locals of the required lengths.
    unsafe {
        chur_ffi::product::chur_vault_keystore_commit(
            handle_of(session),
            nonce.as_ptr(),
            wrapped.as_ptr(),
        )
    }
}

/// Writes every enrolled Keystore slot's unwrap material. Nothing in it is
/// secret.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_vaultKeystoreMaterial<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    runtime: jlong,
    destination: JByteBuffer<'local>,
    out_written: JIntArray<'local>,
) -> jint {
    let Some((address, capacity)) = direct_buffer(&env, &destination) else {
        return INVALID_INPUT;
    };
    let mut written = 0usize;
    // SAFETY: `written` is a live local and the buffer is direct.
    let status = unsafe {
        chur_ffi::product::chur_vault_keystore_material(
            handle_of(runtime),
            address,
            capacity,
            &mut written,
        )
    };
    finish_written(&mut env, status, &out_written, written)
}

/// Removes one slot.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_vaultRemoveSlot<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    slot_id: JByteArray<'local>,
) -> jint {
    let Some(slot) = fixed_array(&mut env, &slot_id, ID_LEN) else {
        return INVALID_INPUT;
    };
    // SAFETY: `slot` is a live 16-byte local.
    unsafe { chur_ffi::product::chur_vault_remove_slot(handle_of(session), slot.as_ptr()) }
}

/// Replaces the password slot.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_vaultChangePassword<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    password: JByteArray<'local>,
) -> jint {
    let Some(bytes) = byte_array(&mut env, &password) else {
        return INVALID_INPUT;
    };
    let request = ChurUnlockRequestV1 {
        factor: 1,
        reserved: [0; 3],
        secret: bytes.as_ptr(),
        secret_length: match u32::try_from(bytes.len()) {
            Ok(value) => value,
            Err(_) => return INVALID_INPUT,
        },
    };
    // SAFETY: `request` refers to a live local.
    unsafe { chur_ffi::product::chur_vault_change_password(handle_of(session), &request) }
}

/// Writes the slot list into a direct buffer.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_vaultSlots<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    destination: JByteBuffer<'local>,
    out_written: JIntArray<'local>,
) -> jint {
    let Some((address, capacity)) = direct_buffer(&env, &destination) else {
        return INVALID_INPUT;
    };
    let mut written = 0usize;
    // SAFETY: `written` is a live local and the buffer is direct.
    let status = unsafe {
        chur_ffi::product::chur_vault_slots(handle_of(session), address, capacity, &mut written)
    };
    finish_written(&mut env, status, &out_written, written)
}

/// Sets or clears the favourite flag.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_objectSetFavorite<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    object_id: JByteArray<'local>,
    favorite: jboolean,
) -> jint {
    let Some(reference) = object_reference(&mut env, &object_id) else {
        return INVALID_INPUT;
    };
    // SAFETY: `reference` is a live local.
    unsafe {
        chur_ffi::product::chur_object_set_favorite(
            handle_of(session),
            &reference,
            u8::from(favorite != 0),
        )
    }
}

/// Deletes an object.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_objectDelete<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    object_id: JByteArray<'local>,
) -> jint {
    let Some(reference) = object_reference(&mut env, &object_id) else {
        return INVALID_INPUT;
    };
    // SAFETY: `reference` is a live local.
    unsafe { chur_ffi::product::chur_object_delete(handle_of(session), &reference) }
}

/// Writes one object's metadata record into a direct buffer.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_objectMetadata<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    object_id: JByteArray<'local>,
    destination: JByteBuffer<'local>,
    out_written: JIntArray<'local>,
) -> jint {
    let Some(reference) = object_reference(&mut env, &object_id) else {
        return INVALID_INPUT;
    };
    let Some((address, capacity)) = direct_buffer(&env, &destination) else {
        return INVALID_INPUT;
    };
    let mut written = 0usize;
    // SAFETY: `reference` and `written` are live locals and the buffer is direct.
    let status = unsafe {
        chur_ffi::product::chur_object_metadata(
            handle_of(session),
            &reference,
            address,
            capacity,
            &mut written,
        )
    };
    finish_written(&mut env, status, &out_written, written)
}

/// Creates an album.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_albumCreate<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    name: JString<'local>,
    out_album_id: JByteArray<'local>,
) -> jint {
    let Some(bytes) = string_bytes(&mut env, &name) else {
        return INVALID_INPUT;
    };
    let Ok(length) = u32::try_from(bytes.len()) else {
        return INVALID_INPUT;
    };
    let mut album = [0u8; ID_LEN];
    // SAFETY: `bytes` and `album` are live locals.
    let status = unsafe {
        chur_ffi::product::chur_album_create(
            handle_of(session),
            bytes.as_ptr(),
            length,
            album.as_mut_ptr(),
        )
    };
    if status != 0 {
        return status;
    }
    if write_bytes(&mut env, &out_album_id, &album) {
        0
    } else {
        INVALID_INPUT
    }
}

/// Adds or removes one album membership.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_albumSetMembership<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    album_id: JByteArray<'local>,
    object_id: JByteArray<'local>,
    member: jboolean,
) -> jint {
    let Some(album) = fixed_array(&mut env, &album_id, ID_LEN) else {
        return INVALID_INPUT;
    };
    let Some(reference) = object_reference(&mut env, &object_id) else {
        return INVALID_INPUT;
    };
    // SAFETY: `album` and `reference` are live locals.
    unsafe {
        chur_ffi::product::chur_album_set_membership(
            handle_of(session),
            album.as_ptr(),
            &reference,
            u8::from(member != 0),
        )
    }
}

/// Writes the album list into a direct buffer.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_albumList<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    destination: JByteBuffer<'local>,
    out_written: JIntArray<'local>,
) -> jint {
    let Some((address, capacity)) = direct_buffer(&env, &destination) else {
        return INVALID_INPUT;
    };
    let mut written = 0usize;
    // SAFETY: `written` is a live local and the buffer is direct.
    let status = unsafe {
        chur_ffi::product::chur_album_list(handle_of(session), address, capacity, &mut written)
    };
    finish_written(&mut env, status, &out_written, written)
}

/// Creates a tag.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_tagCreate<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    name: JString<'local>,
    out_tag_id: JByteArray<'local>,
) -> jint {
    let Some(bytes) = string_bytes(&mut env, &name) else {
        return INVALID_INPUT;
    };
    let Ok(length) = u32::try_from(bytes.len()) else {
        return INVALID_INPUT;
    };
    let mut tag = [0u8; ID_LEN];
    // SAFETY: `bytes` and `tag` are live locals.
    let status = unsafe {
        chur_ffi::product::chur_tag_create(
            handle_of(session),
            bytes.as_ptr(),
            length,
            tag.as_mut_ptr(),
        )
    };
    if status != 0 {
        return status;
    }
    if write_bytes(&mut env, &out_tag_id, &tag) {
        0
    } else {
        INVALID_INPUT
    }
}

/// Applies or removes one tag on one object.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_objectSetTag<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    tag_id: JByteArray<'local>,
    object_id: JByteArray<'local>,
    tagged: jboolean,
) -> jint {
    let Some(tag) = fixed_array(&mut env, &tag_id, ID_LEN) else {
        return INVALID_INPUT;
    };
    let Some(reference) = object_reference(&mut env, &object_id) else {
        return INVALID_INPUT;
    };
    // SAFETY: `tag` and `reference` are live locals.
    unsafe {
        chur_ffi::product::chur_object_set_tag(
            handle_of(session),
            tag.as_ptr(),
            &reference,
            u8::from(tagged != 0),
        )
    }
}

/// Encrypts and records one derived asset from a direct buffer.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_derivedPut<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    object_id: JByteArray<'local>,
    kind: jint,
    width: jint,
    height: jint,
    source: JByteBuffer<'local>,
    length: jint,
) -> jint {
    let Some(reference) = object_reference(&mut env, &object_id) else {
        return INVALID_INPUT;
    };
    let Some((address, capacity)) = direct_buffer(&env, &source) else {
        return INVALID_INPUT;
    };
    let (Ok(kind), Ok(width), Ok(height), Ok(length)) = (
        u32::try_from(kind),
        u32::try_from(width),
        u32::try_from(height),
        u32::try_from(length),
    ) else {
        return INVALID_INPUT;
    };
    if length as usize > capacity {
        return INVALID_INPUT;
    }
    // SAFETY: `reference` is a live local, and the buffer is direct with at
    // least `length` bytes, which the check above enforces.
    unsafe {
        chur_ffi::product::chur_derived_put(
            handle_of(session),
            &reference,
            kind,
            width,
            height,
            address,
            length,
        )
    }
}

/// Reads one derived asset into a direct buffer.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_derivedRead<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    object_id: JByteArray<'local>,
    kind: jint,
    destination: JByteBuffer<'local>,
    out_written: JIntArray<'local>,
) -> jint {
    let Some(reference) = object_reference(&mut env, &object_id) else {
        return INVALID_INPUT;
    };
    let Some((address, capacity)) = direct_buffer(&env, &destination) else {
        return INVALID_INPUT;
    };
    let Ok(kind) = u32::try_from(kind) else {
        return INVALID_INPUT;
    };
    let mut written = 0usize;
    // SAFETY: `reference` and `written` are live locals and the buffer is direct.
    let status = unsafe {
        chur_ffi::product::chur_derived_read(
            handle_of(session),
            &reference,
            kind,
            address,
            capacity,
            &mut written,
        )
    };
    finish_written(&mut env, status, &out_written, written)
}

/// Starts a backup package write, `FFI_CONTRACT.md` §6.7.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_backupCreate<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    destination_fd: jint,
    out_operation: JLongArray<'local>,
) -> jint {
    let mut handle = 0u64;
    // SAFETY: `handle` is a live local.
    let status = unsafe {
        chur_ffi::product::chur_backup_create(handle_of(session), destination_fd, &mut handle)
    };
    finish_handle(&mut env, status, &out_operation, handle)
}

/// Starts a restore from a backup package, §6.7.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_chur_ffi_ChurJni_backupRestore<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    runtime: jlong,
    source_fd: jint,
    password: JByteArray<'local>,
    out_operation: JLongArray<'local>,
) -> jint {
    let Some(secret) = byte_array(&mut env, &password) else {
        return INVALID_INPUT;
    };
    let Ok(length) = u32::try_from(secret.len()) else {
        return INVALID_INPUT;
    };
    let mut handle = 0u64;
    // SAFETY: `secret` and `handle` are live locals.
    let status = unsafe {
        chur_ffi::product::chur_backup_restore(
            handle_of(runtime),
            source_fd,
            secret.as_ptr(),
            length,
            &mut handle,
        )
    };
    finish_handle(&mut env, status, &out_operation, handle)
}
