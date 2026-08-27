//! The header-consistency harness.
//!
//! `include/chur.h` is hand-written and is the authority for the C side, so
//! nothing generates it and nothing checks it. This test is that check. It
//! parses the header and asserts three things against the Rust side:
//!
//! - every `chur_status_t` value in the header is a registered status, and
//!   every registered status appears in the header;
//! - every capability, build-flavor, and integrity constant matches;
//! - every function the header declares is exported by this crate.
//!
//! `docs/ERROR_MODEL.md` requires a code to be added to the table and to the
//! FFI header in one change. A drift between them would otherwise surface as a
//! host mapping an allocated code to `INTERNAL_FAILURE`.

#![allow(clippy::unwrap_used, clippy::panic, clippy::expect_used)]

use std::collections::{BTreeMap, BTreeSet};

use chur_core::ChurStatus;

const HEADER: &str = include_str!("../include/chur.h");

/// Every `#define NAME VALUE` whose name starts with `prefix`.
fn defines(prefix: &str) -> BTreeMap<String, String> {
    let mut out = BTreeMap::new();
    for line in HEADER.lines() {
        let line = line.trim();
        let Some(rest) = line.strip_prefix("#define ") else {
            continue;
        };
        let mut parts = rest.splitn(2, char::is_whitespace);
        let Some(name) = parts.next() else { continue };
        let Some(value) = parts.next() else { continue };
        if name.starts_with(prefix) {
            out.insert(name.to_owned(), value.trim().to_owned());
        }
    }
    out
}

/// The name of every function the header declares.
fn declared_functions() -> BTreeSet<String> {
    let mut out = BTreeSet::new();
    for line in HEADER.lines() {
        let line = line.trim();
        if !line.ends_with(");") {
            continue;
        }
        let Some(open) = line.find('(') else { continue };
        let head = &line[..open];
        let Some(name) = head.rsplit(char::is_whitespace).next() else {
            continue;
        };
        if name.starts_with("chur_") {
            out.insert(name.to_owned());
        }
    }
    out
}

/// The status names the header carries, mapped to their values.
fn header_statuses() -> BTreeMap<String, i32> {
    let mut out = BTreeMap::new();
    for (name, value) in defines("CHUR_") {
        // Capability, flavor, and integrity constants are not statuses.
        if name.starts_with("CHUR_CAP_")
            || name.starts_with("CHUR_FLAVOR_")
            || name.starts_with("CHUR_INTEGRITY_")
            || name.starts_with("CHUR_PANIC_")
            || name == "CHUR_H"
            || name == "CHUR_OK"
        {
            continue;
        }
        let Ok(parsed) = value.parse::<i32>() else {
            panic!("{name} is not a decimal status value: {value}");
        };
        out.insert(name, parsed);
    }
    out
}

#[test]
fn the_header_carries_every_registered_status_and_no_other() {
    let header = header_statuses();
    let expected: BTreeMap<String, i32> = ChurStatus::ALL
        .iter()
        .map(|status| (format!("CHUR_{}", status.name()), status.as_i32()))
        .collect();
    let header_names: BTreeSet<&String> = header.keys().collect();
    let expected_names: BTreeSet<&String> = expected.keys().collect();
    assert_eq!(
        header_names, expected_names,
        "the header and the ERROR_MODEL.md registry disagree about which codes exist"
    );
    for (name, value) in &expected {
        assert_eq!(header.get(name), Some(value), "{name} has the wrong value");
    }
    assert_eq!(header.len(), 33);
}

#[test]
fn success_is_zero_in_the_header() {
    assert_eq!(
        defines("CHUR_OK").get("CHUR_OK").map(String::as_str),
        Some("0")
    );
    assert_eq!(chur_ffi::CHUR_OK, 0);
}

#[test]
fn the_capability_bits_match() {
    let header = defines("CHUR_CAP_");
    let expected = [
        ("CHUR_CAP_DECOY_VAULT", chur_ffi::CHUR_CAP_DECOY_VAULT, 0),
        (
            "CHUR_CAP_OBJECT_READER",
            chur_ffi::CHUR_CAP_OBJECT_READER,
            1,
        ),
        (
            "CHUR_CAP_SEQUENTIAL_READER",
            chur_ffi::CHUR_CAP_SEQUENTIAL_READER,
            2,
        ),
        (
            "CHUR_CAP_INTEGRITY_SCAN",
            chur_ffi::CHUR_CAP_INTEGRITY_SCAN,
            3,
        ),
        (
            "CHUR_CAP_BACKUP_PACKAGE",
            chur_ffi::CHUR_CAP_BACKUP_PACKAGE,
            4,
        ),
        ("CHUR_CAP_SYNC", chur_ffi::CHUR_CAP_SYNC, 5),
        (
            "CHUR_CAP_CONCURRENT_READS",
            chur_ffi::CHUR_CAP_CONCURRENT_READS,
            6,
        ),
    ];
    assert_eq!(header.len(), expected.len());
    for (name, rust_value, bit) in expected {
        assert_eq!(rust_value, 1u64 << bit, "{name} is the wrong Rust bit");
        assert_eq!(
            header.get(name).map(String::as_str),
            Some(format!("(UINT64_C(1) << {bit})").as_str()),
            "{name} is the wrong header bit"
        );
    }
}

#[test]
fn the_build_flavor_bits_match() {
    let header = defines("CHUR_FLAVOR_");
    assert_eq!(header.len(), 3);
    for (name, rust_value, bit) in [
        ("CHUR_FLAVOR_RELEASE", chur_ffi::CHUR_FLAVOR_RELEASE, 0),
        (
            "CHUR_FLAVOR_DEBUG_ASSERTIONS",
            chur_ffi::CHUR_FLAVOR_DEBUG_ASSERTIONS,
            1,
        ),
        (
            "CHUR_FLAVOR_TEST_HOOKS",
            chur_ffi::CHUR_FLAVOR_TEST_HOOKS,
            2,
        ),
    ] {
        assert_eq!(rust_value, 1u32 << bit, "{name} is the wrong Rust bit");
        assert_eq!(
            header.get(name).map(String::as_str),
            Some(format!("(UINT32_C(1) << {bit})").as_str())
        );
    }
}

#[test]
fn the_integrity_states_match_the_constant_registry() {
    let header = defines("CHUR_INTEGRITY_");
    let expected = chur_format::constants::IntegritySummary::ALL;
    assert_eq!(header.len(), expected.len());
    for state in expected {
        let name = format!(
            "CHUR_INTEGRITY_{}",
            match state {
                chur_format::constants::IntegritySummary::Unverified => "UNVERIFIED",
                chur_format::constants::IntegritySummary::Verifying => "VERIFYING",
                chur_format::constants::IntegritySummary::RangeVerified => "RANGE_VERIFIED",
                chur_format::constants::IntegritySummary::CompleteVerified => "COMPLETE_VERIFIED",
                chur_format::constants::IntegritySummary::Incomplete => "INCOMPLETE",
                chur_format::constants::IntegritySummary::Quarantined => "QUARANTINED",
                chur_format::constants::IntegritySummary::Unsupported => "UNSUPPORTED",
                chur_format::constants::IntegritySummary::MigrationRequired => "MIGRATION_REQUIRED",
                _ => panic!("an integrity state has no header name"),
            }
        );
        assert_eq!(
            header.get(&name).map(String::as_str),
            Some(format!("{:#04x}", state.value()).as_str()),
            "{name} disagrees with the registry"
        );
    }
}

#[test]
fn the_panic_fallbacks_match() {
    // ADR-0037. A drift here would let a host accept a value a panicking
    // library returned.
    let header = defines("CHUR_PANIC_");
    assert_eq!(header.len(), 5);
    for (name, expected) in [
        ("CHUR_PANIC_ABI_VERSION", "(UINT32_C(0))"),
        ("CHUR_PANIC_FORMAT_MIN", "(UINT16_C(0xffff))"),
        ("CHUR_PANIC_FORMAT_MAX", "(UINT16_C(0))"),
        ("CHUR_PANIC_CAPABILITIES", "(UINT64_C(0))"),
        ("CHUR_PANIC_BUILD_FLAVOR", "(UINT32_C(0))"),
    ] {
        assert_eq!(
            header.get(name).map(String::as_str),
            Some(expected),
            "{name}"
        );
    }
    assert_eq!(chur_ffi::PANIC_ABI_VERSION, 0);
    assert_eq!(chur_ffi::PANIC_FORMAT_MIN, 0xffff);
    assert_eq!(chur_ffi::PANIC_FORMAT_MAX, 0);
    assert_eq!(chur_ffi::PANIC_CAPABILITIES, 0);
    assert_eq!(chur_ffi::PANIC_BUILD_FLAVOR, 0);
}

#[test]
fn every_declared_function_is_exported() {
    let declared = declared_functions();
    let exported: BTreeSet<String> = [
        "chur_abi_version_major",
        "chur_abi_version_minor",
        "chur_capabilities",
        "chur_object_format_min",
        "chur_object_format_max",
        "chur_key_slot_format_min",
        "chur_key_slot_format_max",
        "chur_build_flavor",
        "chur_status_is_known",
    ]
    .iter()
    .map(|name| (*name).to_owned())
    .collect();
    assert_eq!(
        declared, exported,
        "the header declares a different function set than the crate exports"
    );

    // Calling each one proves the list above is not a stale copy.
    assert_eq!(chur_ffi::chur_abi_version_major(), 1);
    assert_eq!(chur_ffi::chur_abi_version_minor(), 0);
    assert_eq!(chur_ffi::chur_capabilities(), 0);
    assert_eq!(chur_ffi::chur_object_format_min(), 1);
    assert_eq!(chur_ffi::chur_object_format_max(), 1);
    assert_eq!(chur_ffi::chur_key_slot_format_min(), 1);
    assert_eq!(chur_ffi::chur_key_slot_format_max(), 1);
    assert_ne!(chur_ffi::chur_build_flavor(), 0);
    assert!(chur_ffi::chur_status_is_known(100));
}
