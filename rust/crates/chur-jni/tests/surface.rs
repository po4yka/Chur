//! The adapter tracks the export surface, which is ADR-0040's follow-up item.
//!
//! `chur-jni` holds no logic, so it owes no behavioural test: the behaviour is
//! `chur-ffi`'s and is tested there. What it does owe is completeness. A new
//! `chur_*` export with no JNI function is an export Android cannot call, and a
//! JNI function with no export is a symbol that links to nothing after a
//! rename. This test is that check, in both directions.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

use std::collections::BTreeSet;

const HEADER: &str = include_str!("../../chur-ffi/include/chur.h");
const ADAPTER: &str = include_str!("../src/exports.rs");

/// The JNI method name one `chur_*` export maps to.
///
/// The rule is the whole mapping: drop `chur_`, then camel-case what is left.
fn method_name(export: &str) -> String {
    let stem = export.strip_prefix("chur_").unwrap_or(export);
    let mut name = String::with_capacity(stem.len());
    let mut capitalize = false;
    for character in stem.chars() {
        if character == '_' {
            capitalize = true;
        } else if capitalize {
            name.extend(character.to_uppercase());
            capitalize = false;
        } else {
            name.push(character);
        }
    }
    name
}

/// Every `chur_*` function the header declares.
fn declared_exports() -> BTreeSet<String> {
    let mut source = String::with_capacity(HEADER.len());
    let mut rest = HEADER;
    while let Some(start) = rest.find("/*") {
        source.push_str(&rest[..start]);
        let Some(end) = rest[start..].find("*/") else {
            break;
        };
        rest = &rest[start + end + 2..];
    }
    source.push_str(rest);
    let source: String = source
        .lines()
        .filter(|line| !line.trim_start().starts_with('#'))
        .collect::<Vec<_>>()
        .join("\n");

    let mut out = BTreeSet::new();
    for statement in source.split(';') {
        let statement: String = statement.split_whitespace().collect::<Vec<_>>().join(" ");
        if statement.starts_with("typedef") {
            continue;
        }
        let Some(open) = statement.find('(') else {
            continue;
        };
        let Some(name) = statement[..open].rsplit(' ').next() else {
            continue;
        };
        let name = name.trim_start_matches('*');
        if name.starts_with("chur_") {
            out.insert(name.to_owned());
        }
    }
    out
}

/// Every JNI method this adapter exports.
fn adapter_methods() -> BTreeSet<String> {
    const PREFIX: &str = "pub extern \"system\" fn Java_dev_po4yka_chur_ffi_ChurJni_";
    let mut out = BTreeSet::new();
    for line in ADAPTER.lines() {
        let Some(rest) = line.trim_start().strip_prefix(PREFIX) else {
            continue;
        };
        let end = rest
            .find(['<', '('])
            .unwrap_or_else(|| panic!("a JNI declaration has no argument list: {line}"));
        out.insert(rest[..end].to_owned());
    }
    out
}

#[test]
fn every_export_has_exactly_one_jni_function() {
    let expected: BTreeSet<String> = declared_exports()
        .iter()
        .map(|name| method_name(name))
        .collect();
    let actual = adapter_methods();
    assert_eq!(
        expected, actual,
        "the JNI adapter and the C header declare different surfaces"
    );
    assert!(
        expected.len() >= 47,
        "the surface shrank unexpectedly: {} methods",
        expected.len()
    );
}

#[test]
fn the_name_mapping_is_the_documented_rule() {
    for (export, method) in [
        ("chur_runtime_open", "runtimeOpen"),
        ("chur_abi_version_major", "abiVersionMajor"),
        ("chur_status_is_known", "statusIsKnown"),
        (
            "chur_vault_creation_add_recovery_slot",
            "vaultCreationAddRecoverySlot",
        ),
        ("chur_object_reader_read_at", "objectReaderReadAt"),
    ] {
        assert_eq!(method_name(export), method, "{export}");
    }
}
