# ADR-0001: Rust Owns the Private Vault

- **Status:** Accepted
- **Date:** 2026-08-26
- **Decision owners:** @po4yka
- **Related:** [`../ARCHITECTURE.md`](../ARCHITECTURE.md), [`../CRYPTOGRAPHY.md`](../CRYPTOGRAPHY.md)

## Context

Chur targets Android and iOS with KMP/CMP while requiring one interoperable format, key hierarchy, migration path, integrity model, and future CLI/sync implementation. Duplicating these rules in Kotlin, Swift, and platform libraries would create inconsistent security boundaries and migration risk.

## Decision

Rust is the canonical owner of:

- key generation, derivation, wrapping, slots, and secret lifetimes;
- private catalog schema access and migrations;
- encrypted media containers and object-key envelopes;
- canonical encoding, AAD, integrity, and completeness decisions;
- import/export/repair transactions;
- future canonical sync and sharing records.

KMP owns application use cases, UDF state, navigation, and orchestration. Android/iOS own platform authorization, files/providers/codecs/players, lifecycle, and bounded FFI transport.

## Alternatives considered

### Implement cryptography/storage independently in Kotlin and Swift

Rejected: duplicated protocol logic, inconsistent updates, harder audit, no single CLI core.

### Use only platform encrypted-file APIs

Rejected: unsuitable for cross-platform chunked media, random access, collection/object envelope hierarchy, and common migrations.

### KMP owns format; Rust exposes primitive encryption

Rejected: private metadata serialization and transaction logic would escape the intended security boundary.

## Consequences

### Positive

- one portable protocol implementation;
- shared vectors/fuzzing/CLI;
- narrower audit target;
- consistent Android/iOS behavior;
- Rust memory/type tooling for secrets.

### Tradeoffs

- native build/FFI complexity;
- platform codecs still see transient plaintext;
- Rust catalog dependency must compile for mobile;
- interop API requires careful ownership/cancellation design.

## Security impact

Affected invariants: SEC-019, SEC-020.

This is the primary trust boundary. KMP/public storage must not become a shadow private database. Rust compromise remains high impact and requires independent review.

## Compatibility impact

Persisted bytes and migrations are independent from UI framework changes. FFI ABI is versioned separately from vault formats.

## Validation

- cross-platform vectors through the same Rust core;
- module dependency checks;
- storage inspection proving no private Room/DataStore mirror;
- CLI opens/verifies mobile-created vaults.

## Follow-up

- none of the Validation evidence exists yet; it lands with the first `chur-core` and `chur-cli` implementations in Phase 0;
- add the module dependency check that fails the build when a feature module imports FFI symbols or a platform key implementation directly;
- assign the first FFI ABI version separately from the vault format versions; [`0016`](0016-freeze-the-v1-c-abi.md) froze the handshake exports in [`../interop/FFI_CONTRACT.md`](../interop/FFI_CONTRACT.md) §2 and allocated no `(major, minor)` value for them.
