//! The Phase-1 product surface of `docs/interop/FFI_CONTRACT.md` §6.5.
//!
//! §6.2 is the boundary a host needs to open a vault and read from it. It is
//! not the boundary a host needs to deliver Phase 1: with §6.2 alone no vault
//! is ever created, so none is ever unlocked, and three of the four
//! destinations of `DESIGN.md` §10 have nothing to show. These exports close
//! that gap and raise the minor ABI version, which §6.2 prescribes for an
//! addition.
//!
//! Every rule of [`crate::api`] applies here unchanged: the panic guard, the
//! pointer validation, the length bounds, and the retention rule.

use chur_catalog::model::{Album, Tag};
use chur_catalog::vault::{self, Session};
use chur_catalog::{deletion, store};
use chur_core::{ChurStatus, Error, Id, Result, ensure};
use chur_crypto::password::Argon2Params;
use chur_format::constants::StreamKind;
use zeroize::Zeroizing;

use crate::api::Status;
use crate::panic::guard_status;
use crate::records::{
    ChurCreateRequestV1, ChurObjectRefV1, ChurUnlockRequestV1, KEYSTORE_ENROLLMENT_MAX,
    KEYSTORE_MATERIAL_ENTRY_MAX,
};
use crate::registry::{self, Entry, Handle, Kind};

/// The length of a device secret this surface hands back, §12.
pub const SECRET_LEN: usize = 32;

/// The largest recovery phrase, §6.5.
///
/// Twenty-four words of the BIP-39 English list, whose longest entry is eight
/// characters, plus the separators. The bound is stated rather than derived at
/// run time so a caller can size a buffer before it calls.
pub const RECOVERY_PHRASE_MAX: usize = 24 * 9;

// ---------------------------------------------------------------------------
// Provisioning
// ---------------------------------------------------------------------------

/// Whether the runtime's storage root holds a vault, `PROVISIONING.md` §2.
///
/// First launch opens the public shell and creates nothing, so the shell needs
/// to know whether to offer creation or unlock. The answer is on-device routing
/// and is not a disclosure: an attacker with the sandbox already sees the
/// registry, and `DISCREET_MODE.md` bars a count, a name, or a thumbnail rather
/// than the fact that the directory exists.
///
/// # Safety
///
/// `out_present` points to a writable `uint8_t`.
#[unsafe(no_mangle)]
#[expect(
    unsafe_code,
    reason = "ADR-0016: the v1 C ABI requires an exported symbol"
)]
pub unsafe extern "C" fn chur_vault_present(runtime: Handle, out_present: *mut u8) -> Status {
    guard_status(|| {
        let entry = registry::get(runtime, Kind::Runtime)?;
        let Entry::Runtime(guarded) = entry.as_ref() else {
            return Err(wrong_type());
        };
        let present = {
            let guard = registry::lock(guarded);
            !guard.root().registry_names()?.is_empty()
        };
        // SAFETY: the caller guarantees `out_present` is writable.
        unsafe { crate::api::write_out(out_present, u8::from(present)) }
    })
}

/// Begins vault creation, `PROVISIONING.md` §3 steps 3 and 4.
///
/// # Safety
///
/// `request` points to a valid `ChurCreateRequestV1` and `out_creation` to a
/// writable handle.
#[unsafe(no_mangle)]
#[expect(
    unsafe_code,
    reason = "ADR-0016: the v1 C ABI requires an exported symbol"
)]
pub unsafe extern "C" fn chur_vault_create_begin(
    runtime: Handle,
    request: *const ChurCreateRequestV1,
    out_creation: *mut Handle,
) -> Status {
    guard_status(|| {
        let entry = registry::get(runtime, Kind::Runtime)?;
        // SAFETY: the caller guarantees the pointers above for the call.
        let request = unsafe { crate::api::read_request(request)? };
        let password =
            unsafe { crate::api::borrow_bytes(request.password, request.password_length)? };
        let Entry::Runtime(guarded) = entry.as_ref() else {
            return Err(wrong_type());
        };
        let root = {
            let guard = registry::lock(guarded);
            guard.root().clone()
        };
        // A zero means the frozen v1 value, so a host that has run no
        // calibration cannot accidentally ask for a weaker profile than
        // PASSWORD_PROFILE.md §4 allows.
        let default = Argon2Params::v1_default();
        let params = Argon2Params::validated(
            if request.memory_kib == 0 {
                default.memory_kib()
            } else {
                request.memory_kib
            },
            if request.iterations == 0 {
                default.iterations()
            } else {
                request.iterations
            },
            if request.parallelism == 0 {
                default.parallelism()
            } else {
                request.parallelism
            },
        )?;
        let creation = vault::create_with_params(&root, password, params, crate::api::now_ms())?;
        let handle = registry::insert(Entry::Creation {
            runtime,
            creation: std::sync::Mutex::new(Some(creation)),
        })?;
        unsafe { crate::api::write_out(out_creation, handle) }
    })
}

/// Offers the recovery slot of `PROVISIONING.md` §4, step 5 of §3.
///
/// It writes the recovery phrase rather than the 32 canonical bytes. The phrase
/// is a presentation encoding, `docs/security/RECOVERY.md` §2, and
/// `docs/format/CANONICAL_ENCODING_V1.md` §13 reserves every encoding for Rust:
/// a host that received the bytes would have to implement BIP-39 twice, once
/// per platform, to show the user anything.
///
/// The bytes are UTF-8 and are not NUL-terminated. §12 permits them as a
/// bounded buffer, and the host clears it as soon as the user has seen the
/// phrase.
///
/// # Safety
///
/// `destination` covers `capacity` writable bytes and `bytes_written` points to
/// a writable `size_t`.
#[unsafe(no_mangle)]
#[expect(
    unsafe_code,
    reason = "ADR-0016: the v1 C ABI requires an exported symbol"
)]
pub unsafe extern "C" fn chur_vault_creation_add_recovery_slot(
    creation: Handle,
    destination: *mut u8,
    capacity: usize,
    bytes_written: *mut usize,
) -> Status {
    guard_status(|| {
        // SAFETY: the caller guarantees `bytes_written` is writable.
        let _ = unsafe { crate::api::write_out(bytes_written, 0usize) };
        let entry = registry::get(creation, Kind::Creation)?;
        let Entry::Creation { creation, .. } = entry.as_ref() else {
            return Err(wrong_type());
        };
        let secret = {
            let mut guard = registry::lock(creation);
            let Some(pending) = guard.as_mut() else {
                return Err(Error::new(
                    ChurStatus::SessionExpired,
                    "the creation is already finished",
                ));
            };
            pending.add_recovery_slot()?
        };
        let phrase = chur_crypto::recovery::to_phrase(&secret);
        // SAFETY: the caller guarantees `destination` covers `capacity` bytes.
        let buffer = unsafe { crate::api::borrow_bytes_mut(destination, capacity)? };
        write_record(phrase.as_bytes(), buffer, bytes_written)
    })
}

/// Reaches `ACTIVE` and opens the session, step 6 of §3.
///
/// # Safety
///
/// `out_session` points to a writable handle.
#[unsafe(no_mangle)]
#[expect(
    unsafe_code,
    reason = "ADR-0016: the v1 C ABI requires an exported symbol"
)]
pub unsafe extern "C" fn chur_vault_creation_activate(
    creation: Handle,
    out_session: *mut Handle,
) -> Status {
    guard_status(|| {
        let entry = registry::get(creation, Kind::Creation)?;
        let Entry::Creation {
            runtime,
            creation: pending,
        } = entry.as_ref()
        else {
            return Err(wrong_type());
        };
        let session = {
            let mut guard = registry::lock(pending);
            let Some(taken) = guard.take() else {
                return Err(Error::new(
                    ChurStatus::SessionExpired,
                    "the creation is already finished",
                ));
            };
            taken.activate()?
        };
        let handle = registry::insert(Entry::Session {
            runtime: *runtime,
            session: std::sync::Mutex::new(session),
        })?;
        // SAFETY: the caller guarantees `out_session` is writable.
        unsafe { crate::api::write_out(out_session, handle) }
    })
}

/// Abandons a creation and closes its handle, §9 of the descriptor format.
///
/// # Safety
///
/// The handle is a value this process issued, or zero.
#[unsafe(no_mangle)]
#[expect(
    unsafe_code,
    reason = "ADR-0016: the v1 C ABI requires an exported symbol"
)]
pub unsafe extern "C" fn chur_vault_creation_abandon(creation: Handle) -> Status {
    guard_status(|| {
        let taken = registry::close(creation)?;
        if let Some(entry) = taken
            && let Entry::Creation { creation, .. } = entry.as_ref()
        {
            let pending = registry::lock(creation).take();
            if let Some(pending) = pending {
                pending.abandon()?;
            }
        }
        Ok(())
    })
}

// ---------------------------------------------------------------------------
// Key slots
// ---------------------------------------------------------------------------

/// Adds a recovery slot to an active vault, `RECOVERY.md` §8.
///
/// It writes the phrase, for the reason
/// [`chur_vault_creation_add_recovery_slot`] gives.
///
/// # Safety
///
/// As [`chur_vault_slots`].
#[unsafe(no_mangle)]
#[expect(
    unsafe_code,
    reason = "ADR-0016: the v1 C ABI requires an exported symbol"
)]
pub unsafe extern "C" fn chur_vault_add_recovery_slot(
    session: Handle,
    destination: *mut u8,
    capacity: usize,
    bytes_written: *mut usize,
) -> Status {
    guard_status(|| {
        // SAFETY: the caller guarantees `bytes_written` is writable.
        let _ = unsafe { crate::api::write_out(bytes_written, 0usize) };
        let entry = registry::get(session, Kind::Session)?;
        let secret = with_session_mut(&entry, Session::add_recovery_slot)?;
        let phrase = chur_crypto::recovery::to_phrase(&secret);
        // SAFETY: the caller guarantees `destination` covers `capacity` bytes.
        let buffer = unsafe { crate::api::borrow_bytes_mut(destination, capacity)? };
        write_record(phrase.as_bytes(), buffer, bytes_written)
    })
}

/// Adds the Apple Keychain slot of `KEY_SLOTS.md` §5, step 7 of provisioning.
///
/// # Safety
///
/// `item_id` points to 16 bytes and `out_secret` to 32 writable bytes.
#[unsafe(no_mangle)]
#[expect(
    unsafe_code,
    reason = "ADR-0016: the v1 C ABI requires an exported symbol"
)]
pub unsafe extern "C" fn chur_vault_add_device_slot(
    session: Handle,
    item_id: *const u8,
    out_secret: *mut u8,
) -> Status {
    guard_status(|| {
        let entry = registry::get(session, Kind::Session)?;
        // SAFETY: the caller guarantees 16 readable bytes.
        let bytes = unsafe { crate::api::borrow_bytes(item_id, 16)? };
        let item = Id::from_slice(bytes)?;
        let secret = with_session_mut(&entry, |session| session.add_apple_keychain_slot(item))?;
        // SAFETY: the caller guarantees 32 writable bytes.
        unsafe { crate::api::write_secret(out_secret, secret.expose()) }
    })
}

/// Begins the Android Keystore enrollment of `KEY_SLOTS.md` §4, §6.6.
///
/// The enrollment is two calls because the AEAD is the Keystore's. This one
/// writes what the wrap needs, including the root secret; the caller performs
/// the wrap and passes the result to [`chur_vault_keystore_commit`]. Nothing
/// reaches the descriptor until that second call, so an enrollment the platform
/// abandons leaves the vault exactly as it was.
///
/// The record is the canonical bytes of §6.6: a length-prefixed alias, a
/// length-prefixed AAD, and 32 bytes of root secret. The caller must overwrite
/// the buffer as soon as the wrap returns; ADR-0041 records why this family is
/// the one that hands the root out at all.
///
/// # Safety
///
/// `destination` covers `capacity` writable bytes and `bytes_written` points to
/// a writable `size_t`.
#[unsafe(no_mangle)]
#[expect(
    unsafe_code,
    reason = "ADR-0016: the v1 C ABI requires an exported symbol"
)]
pub unsafe extern "C" fn chur_vault_keystore_begin(
    session: Handle,
    destination: *mut u8,
    capacity: usize,
    bytes_written: *mut usize,
) -> Status {
    guard_status(|| {
        // SAFETY: the caller guarantees `bytes_written` is writable.
        let _ = unsafe { crate::api::write_out(bytes_written, 0usize) };
        let entry = registry::get(session, Kind::Session)?;
        let enrollment = with_session_mut(&entry, vault::Session::begin_android_keystore_slot)?;
        let mut encoded = Zeroizing::new(Vec::with_capacity(KEYSTORE_ENROLLMENT_MAX));
        push_bounded(&mut encoded, &enrollment.alias);
        push_bounded(&mut encoded, &enrollment.aad);
        encoded.extend_from_slice(enrollment.root_secret.expose());
        // SAFETY: the caller guarantees `destination` covers `capacity` bytes.
        let buffer = unsafe { crate::api::borrow_bytes_mut(destination, capacity)? };
        write_record(&encoded, buffer, bytes_written)
    })
}

/// Stores what the Keystore wrap returned, completing the slot, §6.6.
///
/// # Safety
///
/// `gcm_nonce` points to 12 readable bytes and `wrapped_root_secret` to 48.
#[unsafe(no_mangle)]
#[expect(
    unsafe_code,
    reason = "ADR-0016: the v1 C ABI requires an exported symbol"
)]
pub unsafe extern "C" fn chur_vault_keystore_commit(
    session: Handle,
    gcm_nonce: *const u8,
    wrapped_root_secret: *const u8,
) -> Status {
    guard_status(|| {
        let entry = registry::get(session, Kind::Session)?;
        // SAFETY: the caller guarantees the two lengths above.
        let nonce = unsafe { crate::api::borrow_bytes(gcm_nonce, 12)? };
        let wrapped = unsafe { crate::api::borrow_bytes(wrapped_root_secret, 48)? };
        let nonce: [u8; 12] = nonce
            .try_into()
            .map_err(|_| Error::new(ChurStatus::InvalidInput, "a GCM nonce is 12 bytes"))?;
        let wrapped: [u8; 48] = wrapped
            .try_into()
            .map_err(|_| Error::new(ChurStatus::InvalidInput, "a wrapped root is 48 bytes"))?;
        with_session_mut(&entry, |session| {
            session.finish_android_keystore_slot(nonce, wrapped)
        })
    })
}

/// What every enrolled Keystore slot needs for its unwrap, §6.6.
///
/// It runs on a locked runtime, because the material is what a caller needs
/// before it can unlock. None of it is secret: every field is stored in the
/// clear in the descriptor.
///
/// The record is a `u32` count followed by that many entries, each a
/// length-prefixed alias, a length-prefixed AAD, the 12-byte nonce, and the
/// 48-byte wrapped root.
///
/// # Safety
///
/// `destination` covers `capacity` writable bytes and `bytes_written` points to
/// a writable `size_t`.
#[unsafe(no_mangle)]
#[expect(
    unsafe_code,
    reason = "ADR-0016: the v1 C ABI requires an exported symbol"
)]
pub unsafe extern "C" fn chur_vault_keystore_material(
    runtime: Handle,
    destination: *mut u8,
    capacity: usize,
    bytes_written: *mut usize,
) -> Status {
    guard_status(|| {
        // SAFETY: the caller guarantees `bytes_written` is writable.
        let _ = unsafe { crate::api::write_out(bytes_written, 0usize) };
        let entry = registry::get(runtime, Kind::Runtime)?;
        let Entry::Runtime(guarded) = entry.as_ref() else {
            return Err(wrong_type());
        };
        let root = {
            let guard = registry::lock(guarded);
            guard.root().clone()
        };
        let material = vault::android_keystore_material(&root)?;
        let count = u32::try_from(material.len())
            .map_err(|_| Error::new(ChurStatus::InternalFailure, "the registry is unbounded"))?;
        let mut encoded = Vec::with_capacity(4 + material.len() * KEYSTORE_MATERIAL_ENTRY_MAX);
        encoded.extend_from_slice(&count.to_be_bytes());
        for entry in &material {
            push_bounded(&mut encoded, &entry.alias);
            push_bounded(&mut encoded, &entry.aad);
            encoded.extend_from_slice(&entry.gcm_nonce);
            encoded.extend_from_slice(&entry.wrapped_root_secret);
        }
        // SAFETY: the caller guarantees `destination` covers `capacity` bytes.
        let buffer = unsafe { crate::api::borrow_bytes_mut(destination, capacity)? };
        write_record(&encoded, buffer, bytes_written)
    })
}

/// Appends a big-endian `u32` length and the bytes it counts.
fn push_bounded(destination: &mut Vec<u8>, source: &[u8]) {
    // Every caller passes a value the parser bounds already limit to a `u32`.
    let length = u32::try_from(source.len()).unwrap_or(u32::MAX);
    destination.extend_from_slice(&length.to_be_bytes());
    destination.extend_from_slice(source);
}

/// Removes one slot, `KEY_SLOTS.md` §9.
///
/// # Safety
///
/// `slot_id` points to 16 readable bytes.
#[unsafe(no_mangle)]
#[expect(
    unsafe_code,
    reason = "ADR-0016: the v1 C ABI requires an exported symbol"
)]
pub unsafe extern "C" fn chur_vault_remove_slot(session: Handle, slot_id: *const u8) -> Status {
    guard_status(|| {
        let entry = registry::get(session, Kind::Session)?;
        // SAFETY: the caller guarantees 16 readable bytes.
        let bytes = unsafe { crate::api::borrow_bytes(slot_id, 16)? };
        let slot = Id::from_slice(bytes)?;
        with_session_mut(&entry, |session| session.remove_slot(&slot))
    })
}

/// Replaces the password slot, `KEY_SLOTS.md` §3 and §9.
///
/// # Safety
///
/// `request` points to a valid `ChurUnlockRequestV1` whose `secret` covers
/// `secret_length` bytes.
#[unsafe(no_mangle)]
#[expect(
    unsafe_code,
    reason = "ADR-0016: the v1 C ABI requires an exported symbol"
)]
pub unsafe extern "C" fn chur_vault_change_password(
    session: Handle,
    request: *const ChurUnlockRequestV1,
) -> Status {
    guard_status(|| {
        let entry = registry::get(session, Kind::Session)?;
        // SAFETY: the caller guarantees the pointers above for the call.
        let request = unsafe { crate::api::read_request(request)? };
        ensure!(
            request.factor == 1,
            InvalidInput,
            "a password change takes the password factor"
        );
        let password =
            unsafe { crate::api::borrow_bytes(request.secret, request.secret_length)? }.to_vec();
        with_session_mut(&entry, |session| {
            session.replace_password(&password, Argon2Params::v1_default())
        })
    })
}

/// Writes the slot list of §6.5.
///
/// # Safety
///
/// `destination` covers `capacity` writable bytes and `bytes_written` points to
/// a writable `size_t`.
#[unsafe(no_mangle)]
#[expect(
    unsafe_code,
    reason = "ADR-0016: the v1 C ABI requires an exported symbol"
)]
pub unsafe extern "C" fn chur_vault_slots(
    session: Handle,
    destination: *mut u8,
    capacity: usize,
    bytes_written: *mut usize,
) -> Status {
    guard_status(|| {
        // SAFETY: the caller guarantees `bytes_written` is writable.
        let _ = unsafe { crate::api::write_out(bytes_written, 0usize) };
        let entry = registry::get(session, Kind::Session)?;
        let Entry::Session {
            session: guarded, ..
        } = entry.as_ref()
        else {
            return Err(wrong_type());
        };
        let encoded = {
            let guard = registry::lock(guarded);
            crate::records::encode_slot_list(guard.slots())
        };
        // SAFETY: the caller guarantees `destination` covers `capacity` bytes.
        let buffer = unsafe { crate::api::borrow_bytes_mut(destination, capacity)? };
        write_record(&encoded, buffer, bytes_written)
    })
}

// ---------------------------------------------------------------------------
// Library
// ---------------------------------------------------------------------------

/// Sets or clears the favourite flag, `DESIGN.md` §11.
///
/// # Safety
///
/// `object` points to a valid `ChurObjectRefV1`.
#[unsafe(no_mangle)]
#[expect(
    unsafe_code,
    reason = "ADR-0016: the v1 C ABI requires an exported symbol"
)]
pub unsafe extern "C" fn chur_object_set_favorite(
    session: Handle,
    object: *const ChurObjectRefV1,
    favorite: u8,
) -> Status {
    guard_status(|| {
        let entry = registry::get(session, Kind::Session)?;
        // SAFETY: the caller guarantees the pointer above for the call.
        let object_id = unsafe { object_id_of(object)? };
        let flag = boolean(favorite)?;
        let now = crate::api::now_ms();
        with_catalog_mut(&entry, |catalog| {
            store::set_favorite(catalog, &object_id, flag, now)
        })
    })
}

/// Deletes an object, `CATALOG_SCHEMA_V1.md` §14.1.
///
/// The whole transaction runs here, including the unlinks: steps 1 and 2 are
/// the atomic boundary, and a host that had to drive steps 3 to 6 itself could
/// stop between them and leave a container the catalog no longer names.
///
/// # Safety
///
/// `object` points to a valid `ChurObjectRefV1`.
#[unsafe(no_mangle)]
#[expect(
    unsafe_code,
    reason = "ADR-0016: the v1 C ABI requires an exported symbol"
)]
pub unsafe extern "C" fn chur_object_delete(
    session: Handle,
    object: *const ChurObjectRefV1,
) -> Status {
    guard_status(|| {
        let entry = registry::get(session, Kind::Session)?;
        // SAFETY: the caller guarantees the pointer above for the call.
        let object_id = unsafe { object_id_of(object)? };
        let Entry::Session {
            session: guarded, ..
        } = entry.as_ref()
        else {
            return Err(wrong_type());
        };
        let mut guard = registry::lock(guarded);
        crate::api::delete_object(&mut guard, &object_id, crate::api::now_ms())
    })
}

/// Writes one object's metadata record, §6.5.
///
/// # Safety
///
/// As [`chur_vault_slots`], with a valid `ChurObjectRefV1`.
#[unsafe(no_mangle)]
#[expect(
    unsafe_code,
    reason = "ADR-0016: the v1 C ABI requires an exported symbol"
)]
pub unsafe extern "C" fn chur_object_metadata(
    session: Handle,
    object: *const ChurObjectRefV1,
    destination: *mut u8,
    capacity: usize,
    bytes_written: *mut usize,
) -> Status {
    guard_status(|| {
        // SAFETY: the caller guarantees `bytes_written` is writable.
        let _ = unsafe { crate::api::write_out(bytes_written, 0usize) };
        let entry = registry::get(session, Kind::Session)?;
        // SAFETY: the caller guarantees the pointer above for the call.
        let object_id = unsafe { object_id_of(object)? };
        let Entry::Session {
            session: guarded, ..
        } = entry.as_ref()
        else {
            return Err(wrong_type());
        };
        let encoded = {
            let guard = registry::lock(guarded);
            let catalog = guard.catalog_ref()?;
            let row = store::object(catalog, &object_id)?;
            let metadata = store::active_metadata(catalog, &object_id)?;
            let tags = store::object_tags(catalog, &object_id)?;
            crate::records::encode_object_metadata(&row, &metadata, &tags)
        };
        // SAFETY: the caller guarantees `destination` covers `capacity` bytes.
        let buffer = unsafe { crate::api::borrow_bytes_mut(destination, capacity)? };
        write_record(&encoded, buffer, bytes_written)
    })
}

/// Creates an album, `DESIGN.md` §12.
///
/// # Safety
///
/// `name` covers `name_length` bytes and `out_album_id` points to 16 writable
/// bytes.
#[unsafe(no_mangle)]
#[expect(
    unsafe_code,
    reason = "ADR-0016: the v1 C ABI requires an exported symbol"
)]
pub unsafe extern "C" fn chur_album_create(
    session: Handle,
    name: *const u8,
    name_length: u32,
    out_album_id: *mut u8,
) -> Status {
    guard_status(|| {
        let entry = registry::get(session, Kind::Session)?;
        // SAFETY: the caller guarantees the pointer above for the call.
        let text = unsafe { crate::api::borrow_bytes(name, name_length)? };
        let text = core::str::from_utf8(text)
            .map_err(|_| Error::new(ChurStatus::InvalidInput, "the album name is not UTF-8"))?;
        let album_id = chur_crypto::random::id()?;
        let album = Album {
            album_id,
            name: text.to_owned(),
            created_ms: crate::api::now_ms(),
            revision: 1,
        };
        with_catalog_mut(&entry, |catalog| store::put_album(catalog, &album))?;
        // SAFETY: the caller guarantees 16 writable bytes.
        unsafe { crate::api::write_id(out_album_id, &album_id) }
    })
}

/// Adds or removes one album membership.
///
/// # Safety
///
/// `album_id` points to 16 readable bytes and `object` to a valid reference.
#[unsafe(no_mangle)]
#[expect(
    unsafe_code,
    reason = "ADR-0016: the v1 C ABI requires an exported symbol"
)]
pub unsafe extern "C" fn chur_album_set_membership(
    session: Handle,
    album_id: *const u8,
    object: *const ChurObjectRefV1,
    member: u8,
) -> Status {
    guard_status(|| {
        let entry = registry::get(session, Kind::Session)?;
        // SAFETY: the caller guarantees the pointers above for the call.
        let album = Id::from_slice(unsafe { crate::api::borrow_bytes(album_id, 16)? })?;
        let object_id = unsafe { object_id_of(object)? };
        let flag = boolean(member)?;
        let now = crate::api::now_ms();
        with_catalog_mut(&entry, |catalog| {
            store::set_album_membership(catalog, &album, &object_id, flag, now)
        })
    })
}

/// Writes the album list of §6.5.
///
/// # Safety
///
/// As [`chur_vault_slots`].
#[unsafe(no_mangle)]
#[expect(
    unsafe_code,
    reason = "ADR-0016: the v1 C ABI requires an exported symbol"
)]
pub unsafe extern "C" fn chur_album_list(
    session: Handle,
    destination: *mut u8,
    capacity: usize,
    bytes_written: *mut usize,
) -> Status {
    guard_status(|| {
        // SAFETY: the caller guarantees `bytes_written` is writable.
        let _ = unsafe { crate::api::write_out(bytes_written, 0usize) };
        let entry = registry::get(session, Kind::Session)?;
        let Entry::Session {
            session: guarded, ..
        } = entry.as_ref()
        else {
            return Err(wrong_type());
        };
        let encoded = {
            let guard = registry::lock(guarded);
            let albums = store::albums(guard.catalog_ref()?)?;
            crate::records::encode_album_list(&albums)
        };
        // SAFETY: the caller guarantees `destination` covers `capacity` bytes.
        let buffer = unsafe { crate::api::borrow_bytes_mut(destination, capacity)? };
        write_record(&encoded, buffer, bytes_written)
    })
}

/// Creates a tag.
///
/// # Safety
///
/// As [`chur_album_create`].
#[unsafe(no_mangle)]
#[expect(
    unsafe_code,
    reason = "ADR-0016: the v1 C ABI requires an exported symbol"
)]
pub unsafe extern "C" fn chur_tag_create(
    session: Handle,
    name: *const u8,
    name_length: u32,
    out_tag_id: *mut u8,
) -> Status {
    guard_status(|| {
        let entry = registry::get(session, Kind::Session)?;
        // SAFETY: the caller guarantees the pointer above for the call.
        let text = unsafe { crate::api::borrow_bytes(name, name_length)? };
        let text = core::str::from_utf8(text)
            .map_err(|_| Error::new(ChurStatus::InvalidInput, "the tag name is not UTF-8"))?;
        let tag_id = chur_crypto::random::id()?;
        let tag = Tag {
            tag_id,
            name: text.to_owned(),
            created_ms: crate::api::now_ms(),
        };
        with_catalog_mut(&entry, |catalog| store::put_tag(catalog, &tag))?;
        // SAFETY: the caller guarantees 16 writable bytes.
        unsafe { crate::api::write_id(out_tag_id, &tag_id) }
    })
}

/// Applies or removes one tag on one object.
///
/// # Safety
///
/// As [`chur_album_set_membership`].
#[unsafe(no_mangle)]
#[expect(
    unsafe_code,
    reason = "ADR-0016: the v1 C ABI requires an exported symbol"
)]
pub unsafe extern "C" fn chur_object_set_tag(
    session: Handle,
    tag_id: *const u8,
    object: *const ChurObjectRefV1,
    tagged: u8,
) -> Status {
    guard_status(|| {
        let entry = registry::get(session, Kind::Session)?;
        // SAFETY: the caller guarantees the pointers above for the call.
        let tag = Id::from_slice(unsafe { crate::api::borrow_bytes(tag_id, 16)? })?;
        let object_id = unsafe { object_id_of(object)? };
        let flag = boolean(tagged)?;
        with_catalog_mut(&entry, |catalog| {
            store::set_object_tag(catalog, &tag, &object_id, flag)
        })
    })
}

// ---------------------------------------------------------------------------
// Derived assets
// ---------------------------------------------------------------------------

/// Encrypts and records one derived asset, `MEDIA_PIPELINE.md` §6.
///
/// The bytes are the platform's decode-and-resize output, which §1 puts on the
/// platform. They are copied once into a zeroizing buffer and the caller's
/// pointer is not retained.
///
/// # Safety
///
/// `object` points to a valid reference and `bytes` covers `length` bytes.
#[unsafe(no_mangle)]
#[expect(
    unsafe_code,
    reason = "ADR-0016: the v1 C ABI requires an exported symbol"
)]
pub unsafe extern "C" fn chur_derived_put(
    session: Handle,
    object: *const ChurObjectRefV1,
    kind: u32,
    width: u32,
    height: u32,
    bytes: *const u8,
    length: u32,
) -> Status {
    guard_status(|| {
        let entry = registry::get(session, Kind::Session)?;
        // SAFETY: the caller guarantees the pointers above for the call.
        let object_id = unsafe { object_id_of(object)? };
        let kind = stream_kind(kind)?;
        let payload = Zeroizing::new(unsafe { crate::api::borrow_large(bytes, length)? }.to_vec());
        let now = crate::api::now_ms();
        let Entry::Session {
            session: guarded, ..
        } = entry.as_ref()
        else {
            return Err(wrong_type());
        };
        let mut guard = registry::lock(guarded);
        chur_media::derived::put(&mut guard, &object_id, kind, width, height, &payload, now)?;
        Ok(())
    })
}

/// Reads one derived asset into a caller buffer.
///
/// # Safety
///
/// As [`chur_vault_slots`], with a valid `ChurObjectRefV1`.
#[unsafe(no_mangle)]
#[expect(
    unsafe_code,
    reason = "ADR-0016: the v1 C ABI requires an exported symbol"
)]
pub unsafe extern "C" fn chur_derived_read(
    session: Handle,
    object: *const ChurObjectRefV1,
    kind: u32,
    destination: *mut u8,
    capacity: usize,
    bytes_written: *mut usize,
) -> Status {
    guard_status(|| {
        // SAFETY: the caller guarantees `bytes_written` is writable.
        let _ = unsafe { crate::api::write_out(bytes_written, 0usize) };
        let entry = registry::get(session, Kind::Session)?;
        // SAFETY: the caller guarantees the pointer above for the call.
        let object_id = unsafe { object_id_of(object)? };
        let kind = stream_kind(kind)?;
        let Entry::Session {
            session: guarded, ..
        } = entry.as_ref()
        else {
            return Err(wrong_type());
        };
        let plaintext = {
            let guard = registry::lock(guarded);
            chur_media::derived::read(&guard, &object_id, kind)?
        };
        // SAFETY: the caller guarantees `destination` covers `capacity` bytes.
        let buffer = unsafe { crate::api::borrow_bytes_mut(destination, capacity)? };
        write_record(&plaintext, buffer, bytes_written)
    })
}

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

fn wrong_type() -> Error {
    Error::new(ChurStatus::InvalidInput, "the handle is of another type")
}

/// Rejects a boolean argument that is neither 0 nor 1.
fn boolean(value: u8) -> Result<bool> {
    match value {
        0 => Ok(false),
        1 => Ok(true),
        _ => Err(Error::new(
            ChurStatus::NonCanonicalEncoding,
            "a boolean argument is neither 0x00 nor 0x01",
        )),
    }
}

fn stream_kind(value: u32) -> Result<StreamKind> {
    u8::try_from(value)
        .ok()
        .and_then(StreamKind::from_value)
        .ok_or_else(|| {
            Error::new(
                ChurStatus::InvalidInput,
                "the call names an unallocated stream kind",
            )
        })
}

/// # Safety
///
/// `object` points to a valid `ChurObjectRefV1`.
#[expect(unsafe_code, reason = "the caller's pointer contract is stated above")]
unsafe fn object_id_of(object: *const ChurObjectRefV1) -> Result<Id> {
    // SAFETY: the caller guarantees a valid, aligned structure for the call.
    let reference = unsafe { crate::api::read_request(object)? };
    Id::from_slice(&reference.object_id)
}

fn with_session_mut<T>(
    entry: &std::sync::Arc<Entry>,
    body: impl FnOnce(&mut Session) -> Result<T>,
) -> Result<T> {
    let Entry::Session {
        session: guarded, ..
    } = entry.as_ref()
    else {
        return Err(wrong_type());
    };
    let mut guard = registry::lock(guarded);
    body(&mut guard)
}

fn with_catalog_mut(
    entry: &std::sync::Arc<Entry>,
    body: impl FnOnce(&mut chur_catalog::CatalogDb) -> Result<()>,
) -> Result<()> {
    with_session_mut(entry, |session| body(session.catalog()?))
}

/// Copies a record into a caller buffer, refusing a buffer that is too small.
///
/// # Safety
///
/// `bytes_written` points to a writable `size_t`.
fn write_record(record: &[u8], buffer: &mut [u8], bytes_written: *mut usize) -> Result<()> {
    ensure!(
        buffer.len() >= record.len(),
        ResourceLimitExceeded,
        "the destination buffer is smaller than the record"
    );
    buffer[..record.len()].copy_from_slice(record);
    // SAFETY: the caller guarantees `bytes_written` is writable, which every
    // export that reaches here has already checked once.
    #[expect(unsafe_code, reason = "the caller's pointer contract is stated above")]
    unsafe {
        crate::api::write_out(bytes_written, record.len())
    }
}

/// The deletion of §14.1, driven whole.
pub(crate) fn run_deletion(session: &mut Session, object_id: &Id, now_ms: u64) -> Result<()> {
    let store_id = session.object_store_id();
    let root = session.root_dir().clone();
    deletion::begin(session.catalog()?, object_id)?;
    let pending = deletion::sweep(session.catalog_ref()?)?;
    deletion::erase(session.catalog()?, object_id, now_ms)?;
    for entry in pending.iter().filter(|entry| entry.object_id == *object_id) {
        for container in &entry.containers {
            chur_media::store::unlink_container(&root, &store_id, container)?;
        }
    }
    deletion::finish(session.catalog()?, object_id)?;
    // §14: a vault with no enrolled peer discards the tombstone once garbage
    // collection has completed, and v1 enrols no peer.
    deletion::discard_tombstone(session.catalog()?, object_id)
}

// ---------------------------------------------------------------------------
// §6.7 The portable backup surface, ABI 1.3
// ---------------------------------------------------------------------------

/// Writes a full backup package to a descriptor, `BACKUP_FORMAT_V1.md` §7.
///
/// The descriptor must be writable and seekable, because §7 writes the public
/// preamble before the records and the record count is known only after the
/// inventory pass. A pipe is therefore not a destination: an application that
/// must upload a package writes it to a file and uploads that.
///
/// The call returns immediately with an operation handle. §9 makes the work
/// cancellable and §10 makes progress polled rather than pushed, exactly as an
/// import or an export is.
///
/// # Safety
///
/// `destination_fd` is open, writable, and seekable for the duration of the
/// call, and `out_operation` points to a writable handle. §13 has Rust
/// duplicate the descriptor, so the caller closes its own on its own schedule.
#[unsafe(no_mangle)]
#[expect(
    unsafe_code,
    reason = "ADR-0016: the v1 C ABI requires an exported symbol"
)]
pub unsafe extern "C" fn chur_backup_create(
    session: Handle,
    destination_fd: i32,
    out_operation: *mut Handle,
) -> Status {
    guard_status(|| {
        let entry = registry::get(session, Kind::Session)?;
        let Entry::Session { .. } = entry.as_ref() else {
            return Err(wrong_type());
        };
        // SAFETY: the caller guarantees the descriptor for the duration of the
        // call; the duplicate is Rust's from here on.
        let mut destination = unsafe { crate::api::duplicate_descriptor(destination_fd)? };
        let now = crate::api::now_ms();
        let operation = crate::operation::Operation::spawn(
            crate::operation::OperationKind::Backup,
            0,
            move |shared| {
                let Entry::Session { session, .. } = entry.as_ref() else {
                    return Err(wrong_type());
                };
                let mut guard = registry::lock(session);
                let mut progress =
                    crate::operation::SharedProgress::new(shared, crate::operation::Stage::Running);
                chur_media::backup::create(&mut guard, &mut destination, now, &mut progress)
                    .map(|_| ())
            },
        )?;
        let handle = registry::insert(Entry::Operation {
            owner: session,
            operation,
        })?;
        // SAFETY: the caller guarantees `out_operation` is writable.
        unsafe { crate::api::write_out(out_operation, handle) }
    })
}

/// Restores a package into the runtime's storage root, §8.
///
/// It takes the runtime rather than a session, and deliberately: a restore
/// installs an identity, so at the moment it runs there may be no session and
/// no vault at all. §8 step 2 obtains the credential from the package's own
/// portable descriptor.
///
/// The operation belongs to the runtime, so closing the runtime tears it down
/// and locking an unrelated session does not.
///
/// # Safety
///
/// `source_fd` is open, readable, and seekable for the duration of the call,
/// `password` points to `password_length` readable bytes, and `out_operation`
/// points to a writable handle. The password is not retained after the call
/// returns.
#[unsafe(no_mangle)]
#[expect(
    unsafe_code,
    reason = "ADR-0016: the v1 C ABI requires an exported symbol"
)]
pub unsafe extern "C" fn chur_backup_restore(
    runtime: Handle,
    source_fd: i32,
    password: *const u8,
    password_length: u32,
    out_operation: *mut Handle,
) -> Status {
    guard_status(|| {
        let entry = registry::get(runtime, Kind::Runtime)?;
        let Entry::Runtime(guarded) = entry.as_ref() else {
            return Err(wrong_type());
        };
        // SAFETY: the caller guarantees the pointer above for the call. §12
        // keeps the credential inside Rust: it is copied into a zeroizing
        // buffer here and the caller's pointer is not retained.
        let secret = zeroize::Zeroizing::new(
            unsafe { crate::api::borrow_bytes(password, password_length)? }.to_vec(),
        );
        let root = {
            let guard = registry::lock(guarded);
            guard.root().clone()
        };
        // SAFETY: as above for the descriptor.
        let mut source = unsafe { crate::api::duplicate_descriptor(source_fd)? };
        let now = crate::api::now_ms();
        let operation = crate::operation::Operation::spawn(
            crate::operation::OperationKind::Restore,
            0,
            move |shared| {
                let mut progress =
                    crate::operation::SharedProgress::new(shared, crate::operation::Stage::Running);
                chur_media::backup::restore(&root, &mut source, &secret, now, &mut progress)
                    .map(|_| ())
            },
        )?;
        let handle = registry::insert(Entry::Operation {
            owner: runtime,
            operation,
        })?;
        // SAFETY: the caller guarantees `out_operation` is writable.
        unsafe { crate::api::write_out(out_operation, handle) }
    })
}
