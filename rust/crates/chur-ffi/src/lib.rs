//! Chur FFI boundary.
//!
//! The boundary splits into a structured control plane and a bounded streaming
//! data plane, both on one stable C ABI (ADR-0006, frozen by ADR-0016). No
//! binding generator is part of it: `include/chur.h` is hand-written and is the
//! authority for the C side, and this crate is the authority for the Rust side.
//!
//! What exists today is the ABI handshake of `docs/interop/FFI_CONTRACT.md` §2.
//! Every function here is callable from any thread before runtime
//! initialization and cannot fail, which is what a platform gate needs before
//! it decides whether to load the library at all. The data-plane functions land
//! with the media runtime; until then [`chur_capabilities`] reports the
//! capabilities that actually exist, which is none.
//!
//! Normative sources:
//!
//! - `docs/interop/FFI_CONTRACT.md` (ABI handshake, handles, buffer ownership)
//! - `docs/ERROR_MODEL.md` (`chur_status_t` and its values)
//! - `docs/security/SECURITY_INVARIANTS.md` (SEC-050, SEC-051)

use chur_core::ChurStatus;
use chur_format::constants::{CONTAINER_VERSION_V1, SLOT_VERSION_V1};

/// Major component of the native API version.
///
/// A different major value fails loading, reports `ABI_INCOMPATIBLE`, and the
/// library is not called again in that process.
pub const ABI_VERSION_MAJOR: u32 = 1;

/// Minor component of the native API version.
///
/// A minor difference is negotiated only within explicitly compatible
/// behaviour; it never selects a cryptographic suite from untrusted input.
pub const ABI_VERSION_MINOR: u32 = 0;

/// Capability bit: independent decoy identity supported.
pub const CHUR_CAP_DECOY_VAULT: u64 = 1 << 0;
/// Capability bit: random-access authenticated reader available.
pub const CHUR_CAP_OBJECT_READER: u64 = 1 << 1;
/// Capability bit: sequential reader available.
pub const CHUR_CAP_SEQUENTIAL_READER: u64 = 1 << 2;
/// Capability bit: background integrity scan available.
pub const CHUR_CAP_INTEGRITY_SCAN: u64 = 1 << 3;
/// Capability bit: portable backup package import and export available.
pub const CHUR_CAP_BACKUP_PACKAGE: u64 = 1 << 4;
/// Capability bit: ciphertext sync available.
pub const CHUR_CAP_SYNC: u64 = 1 << 5;
/// Capability bit: one reader handle serves parallel reads.
pub const CHUR_CAP_CONCURRENT_READS: u64 = 1 << 6;

/// Build-flavor bit: this is a release build.
pub const CHUR_FLAVOR_RELEASE: u32 = 1 << 0;
/// Build-flavor bit: debug assertions are compiled in.
pub const CHUR_FLAVOR_DEBUG_ASSERTIONS: u32 = 1 << 1;
/// Build-flavor bit: test hooks are compiled in.
pub const CHUR_FLAVOR_TEST_HOOKS: u32 = 1 << 2;

/// The capabilities this build offers.
///
/// A bit is set in the change that lands the surface it names, never before. A
/// host that reads a set bit is entitled to call the functions behind it, so
/// declaring a capability the data plane does not implement would be a false
/// handshake rather than a harmless placeholder. Every bit is therefore clear
/// in this build.
const CAPABILITIES: u64 = 0;

/// The major ABI version.
// SAFETY: the function takes no pointer, reads no caller memory, and returns a
// scalar by value, so the only unsafe property is the unmangled symbol name. It
// is unique to this library and is declared in include/chur.h.
#[expect(
    unsafe_code,
    reason = "FFI_CONTRACT.md section 2 fixes this exported symbol name; a mangled name is not callable from the platform host"
)]
#[unsafe(no_mangle)]
pub extern "C" fn chur_abi_version_major() -> u32 {
    ABI_VERSION_MAJOR
}

/// The minor ABI version.
// SAFETY: the function takes no pointer, reads no caller memory, and returns a
// scalar by value, so the only unsafe property is the unmangled symbol name. It
// is unique to this library and is declared in include/chur.h.
#[expect(
    unsafe_code,
    reason = "FFI_CONTRACT.md section 2 fixes this exported symbol name; a mangled name is not callable from the platform host"
)]
#[unsafe(no_mangle)]
pub extern "C" fn chur_abi_version_minor() -> u32 {
    ABI_VERSION_MINOR
}

/// The capability bitmask.
///
/// An unknown set bit is ignored by the host and never enables behaviour.
// SAFETY: the function takes no pointer, reads no caller memory, and returns a
// scalar by value, so the only unsafe property is the unmangled symbol name. It
// is unique to this library and is declared in include/chur.h.
#[expect(
    unsafe_code,
    reason = "FFI_CONTRACT.md section 2 fixes this exported symbol name; a mangled name is not callable from the platform host"
)]
#[unsafe(no_mangle)]
pub extern "C" fn chur_capabilities() -> u64 {
    CAPABILITIES
}

/// Lowest `container_version` this build reads.
// SAFETY: the function takes no pointer, reads no caller memory, and returns a
// scalar by value, so the only unsafe property is the unmangled symbol name. It
// is unique to this library and is declared in include/chur.h.
#[expect(
    unsafe_code,
    reason = "FFI_CONTRACT.md section 2 fixes this exported symbol name; a mangled name is not callable from the platform host"
)]
#[unsafe(no_mangle)]
pub extern "C" fn chur_object_format_min() -> u16 {
    CONTAINER_VERSION_V1
}

/// Highest `container_version` this build reads.
// SAFETY: the function takes no pointer, reads no caller memory, and returns a
// scalar by value, so the only unsafe property is the unmangled symbol name. It
// is unique to this library and is declared in include/chur.h.
#[expect(
    unsafe_code,
    reason = "FFI_CONTRACT.md section 2 fixes this exported symbol name; a mangled name is not callable from the platform host"
)]
#[unsafe(no_mangle)]
pub extern "C" fn chur_object_format_max() -> u16 {
    CONTAINER_VERSION_V1
}

/// Lowest key-slot format version this build reads.
// SAFETY: the function takes no pointer, reads no caller memory, and returns a
// scalar by value, so the only unsafe property is the unmangled symbol name. It
// is unique to this library and is declared in include/chur.h.
#[expect(
    unsafe_code,
    reason = "FFI_CONTRACT.md section 2 fixes this exported symbol name; a mangled name is not callable from the platform host"
)]
#[unsafe(no_mangle)]
pub extern "C" fn chur_key_slot_format_min() -> u16 {
    SLOT_VERSION_V1
}

/// Highest key-slot format version this build reads.
// SAFETY: the function takes no pointer, reads no caller memory, and returns a
// scalar by value, so the only unsafe property is the unmangled symbol name. It
// is unique to this library and is declared in include/chur.h.
#[expect(
    unsafe_code,
    reason = "FFI_CONTRACT.md section 2 fixes this exported symbol name; a mangled name is not callable from the platform host"
)]
#[unsafe(no_mangle)]
pub extern "C" fn chur_key_slot_format_max() -> u16 {
    SLOT_VERSION_V1
}

/// The build flavor bitfield.
///
/// A release application refuses a library with the debug-assertions or
/// test-hooks bit set, so the value is computed from the compilation rather
/// than from a feature a caller could ask for.
// SAFETY: the function takes no pointer, reads no caller memory, and returns a
// scalar by value, so the only unsafe property is the unmangled symbol name. It
// is unique to this library and is declared in include/chur.h.
#[expect(
    unsafe_code,
    reason = "FFI_CONTRACT.md section 2 fixes this exported symbol name; a mangled name is not callable from the platform host"
)]
#[unsafe(no_mangle)]
pub extern "C" fn chur_build_flavor() -> u32 {
    let mut flavor = 0;
    if cfg!(debug_assertions) {
        flavor |= CHUR_FLAVOR_DEBUG_ASSERTIONS;
    } else {
        flavor |= CHUR_FLAVOR_RELEASE;
    }
    // No test hooks are compiled into any build. `docs/CRYPTOGRAPHY.md` §9
    // forbids a production build that can select deterministic randomness, and
    // the crate offers no feature that would set this bit.
    flavor
}

/// The `chur_status_t` value of success.
pub const CHUR_OK: i32 = chur_core::CHUR_OK;

/// Whether an `int32_t` is a status value this build allocates.
///
/// A host that receives an unrecognized value maps it to `INTERNAL_FAILURE` and
/// must never treat it as success, retryable, or benign.
// SAFETY: the function takes no pointer, reads no caller memory, and returns a
// scalar by value, so the only unsafe property is the unmangled symbol name. It
// is unique to this library and is declared in include/chur.h.
#[expect(
    unsafe_code,
    reason = "FFI_CONTRACT.md section 2 fixes this exported symbol name; a mangled name is not callable from the platform host"
)]
#[unsafe(no_mangle)]
pub extern "C" fn chur_status_is_known(value: i32) -> bool {
    ChurStatus::is_allocated(value)
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::panic, clippy::expect_used)]

    use super::*;

    #[test]
    fn the_handshake_answers_every_documented_fact() {
        assert_eq!(chur_abi_version_major(), 1);
        assert_eq!(chur_abi_version_minor(), 0);
        assert_eq!(chur_object_format_min(), 1);
        assert_eq!(chur_object_format_max(), 1);
        assert_eq!(chur_key_slot_format_min(), 1);
        assert_eq!(chur_key_slot_format_max(), 1);
    }

    #[test]
    fn the_format_ranges_are_ordered() {
        assert!(chur_object_format_min() <= chur_object_format_max());
        assert!(chur_key_slot_format_min() <= chur_key_slot_format_max());
    }

    #[test]
    fn no_capability_is_declared_before_its_surface_exists() {
        assert_eq!(chur_capabilities(), 0);
        for bit in 7..64 {
            assert_eq!(
                chur_capabilities() & (1 << bit),
                0,
                "bit {bit} is reserved and must be zero in v1"
            );
        }
    }

    #[test]
    fn the_build_flavor_never_declares_test_hooks() {
        assert_eq!(chur_build_flavor() & CHUR_FLAVOR_TEST_HOOKS, 0);
        let release = chur_build_flavor() & CHUR_FLAVOR_RELEASE != 0;
        let debug = chur_build_flavor() & CHUR_FLAVOR_DEBUG_ASSERTIONS != 0;
        assert!(release ^ debug, "a build is release or debug, never both");
    }

    #[test]
    fn the_status_predicate_agrees_with_the_registry() {
        assert!(!chur_status_is_known(CHUR_OK));
        for status in ChurStatus::ALL {
            assert!(chur_status_is_known(status.as_i32()));
        }
        assert!(!chur_status_is_known(42));
        assert!(!chur_status_is_known(-1));
    }
}
