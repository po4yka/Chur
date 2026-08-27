//! Chur FFI boundary.
//!
//! The boundary splits into a structured control plane and a bounded streaming
//! data plane, both on one stable C ABI (ADR-0006, frozen by ADR-0016). No
//! binding generator is part of it: `include/chur.h` is hand-written and is the
//! authority for the C side, and this crate is the authority for the Rust side.
//!
//! Every export contains panics. §11 of that contract is unconditional, and
//! [`panic::guard`] is how it is met: a caught panic never crosses the boundary
//! and its payload is dropped inside. A handshake export has no status channel,
//! so it returns a value the host refuses instead, per ADR-0037.
//!
//! The handshake of §2 is here; the control plane and the data plane are in
//! [`api`]. A handshake function is callable from any thread before runtime
//! initialization and cannot fail, which is what a platform gate needs before
//! it decides whether to load the library at all. [`chur_capabilities`] reports
//! the capabilities that actually exist and no others.
//!
//! Normative sources:
//!
//! - `docs/interop/FFI_CONTRACT.md` (ABI handshake, handles, buffer ownership)
//! - `docs/ERROR_MODEL.md` (`chur_status_t` and its values)
//! - `docs/security/SECURITY_INVARIANTS.md` (SEC-050, SEC-051)

pub mod api;

/// The number of live handles in this process.
///
/// `docs/interop/FFI_CONTRACT.md` §15 asks for a leaked-handle test, and the
/// registry is private, so the count is exposed here. It is a Rust function
/// rather than an export: nothing outside this library needs it, and §6.2
/// freezes the exported set.
#[must_use]
pub fn registry_live_handles() -> usize {
    registry::live()
}
mod operation;
mod panic;
pub mod records;
mod registry;
mod runtime;

use chur_core::ChurStatus;
use chur_format::constants::{CONTAINER_VERSION_V1, SLOT_VERSION_V1};

use crate::panic::guard;

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

/// The value a handshake export returns when its body panics, ADR-0037.
///
/// Each one is a value the host already refuses: a major version of 0 is not
/// this ABI, an inverted format range contains no version, a capability mask of
/// 0 offers nothing, a flavor with no bit set is neither release nor debug, and
/// an unknown status is unknown.
pub const PANIC_ABI_VERSION: u32 = 0;
/// See [`PANIC_ABI_VERSION`]. An empty range: the minimum exceeds the maximum.
pub const PANIC_FORMAT_MIN: u16 = u16::MAX;
/// See [`PANIC_ABI_VERSION`]. An empty range: the maximum is below the minimum.
pub const PANIC_FORMAT_MAX: u16 = 0;
/// See [`PANIC_ABI_VERSION`]. No capability is offered.
pub const PANIC_CAPABILITIES: u64 = 0;
/// See [`PANIC_ABI_VERSION`]. Neither release nor debug, which a host refuses.
pub const PANIC_BUILD_FLAVOR: u32 = 0;

// ADR-0037: each fallback is a value the host already refuses, so a panicking
// library fails the gate instead of reporting a value it did not compute.
const _: () = assert!(PANIC_ABI_VERSION != ABI_VERSION_MAJOR);
const _: () = assert!(PANIC_FORMAT_MIN > PANIC_FORMAT_MAX);
const _: () = assert!(PANIC_CAPABILITIES == 0);
const _: () = assert!(PANIC_BUILD_FLAVOR & CHUR_FLAVOR_RELEASE == 0);
const _: () = assert!(PANIC_BUILD_FLAVOR & CHUR_FLAVOR_DEBUG_ASSERTIONS == 0);

/// The capabilities this build offers.
///
/// A bit is set in the change that lands the surface it names, never before. A
/// host that reads a set bit is entitled to call the functions behind it, so
/// declaring a capability the data plane does not implement would be a false
/// handshake rather than a harmless placeholder.
///
/// Four bits are clear and each for its own reason. `CHUR_CAP_DECOY_VAULT` is
/// Phase 2 and the registry admits the second identity but no flow provisions
/// it. `CHUR_CAP_BACKUP_PACKAGE` is Phase 2 and
/// `docs/format/BACKUP_FORMAT_V1.md` is specified and not implemented.
/// `CHUR_CAP_SYNC` is Phase 3. `CHUR_CAP_CONCURRENT_READS` requires benchmarks
/// and correctness tests first, and until they exist every reader handle is
/// serialized per `docs/interop/FFI_CONTRACT.md` §8.
const CAPABILITIES: u64 =
    CHUR_CAP_OBJECT_READER | CHUR_CAP_SEQUENTIAL_READER | CHUR_CAP_INTEGRITY_SCAN;

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
    guard(PANIC_ABI_VERSION, || ABI_VERSION_MAJOR)
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
    guard(PANIC_ABI_VERSION, || ABI_VERSION_MINOR)
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
    guard(PANIC_CAPABILITIES, || CAPABILITIES)
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
    guard(PANIC_FORMAT_MIN, || CONTAINER_VERSION_V1)
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
    guard(PANIC_FORMAT_MAX, || CONTAINER_VERSION_V1)
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
    guard(PANIC_FORMAT_MIN, || SLOT_VERSION_V1)
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
    guard(PANIC_FORMAT_MAX, || SLOT_VERSION_V1)
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
    guard(PANIC_BUILD_FLAVOR, || {
        // No test hooks are compiled into any build. `docs/CRYPTOGRAPHY.md` §9
        // forbids a production build that can select deterministic randomness,
        // and the crate offers no feature that would set CHUR_FLAVOR_TEST_HOOKS.
        if cfg!(debug_assertions) {
            CHUR_FLAVOR_DEBUG_ASSERTIONS
        } else {
            CHUR_FLAVOR_RELEASE
        }
    })
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
    guard(false, || ChurStatus::is_allocated(value))
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
        // A set bit entitles a host to call the functions behind it, so each
        // one is asserted against the surface that implements it rather than
        // against a copy of the constant.
        let declared = chur_capabilities();
        assert_ne!(declared & CHUR_CAP_OBJECT_READER, 0, "the reader exists");
        assert_ne!(
            declared & CHUR_CAP_SEQUENTIAL_READER,
            0,
            "sequential export exists"
        );
        assert_ne!(
            declared & CHUR_CAP_INTEGRITY_SCAN,
            0,
            "the integrity scan exists"
        );
        for (bit, why) in [
            (CHUR_CAP_DECOY_VAULT, "no flow provisions a decoy identity"),
            (
                CHUR_CAP_BACKUP_PACKAGE,
                "the backup format is not implemented",
            ),
            (CHUR_CAP_SYNC, "sync is Phase 3"),
            (
                CHUR_CAP_CONCURRENT_READS,
                "every reader handle is still serialized",
            ),
        ] {
            assert_eq!(declared & bit, 0, "a capability is declared but {why}");
        }
        for bit in 7..64 {
            assert_eq!(
                declared & (1 << bit),
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
