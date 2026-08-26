# ADR-0004: Use a Rust-Owned Private Catalog

- **Status:** Proposed
- **Date:** 2026-08-26
- **Related:** [`../format/CATALOG_SCHEMA_V1.md`](../format/CATALOG_SCHEMA_V1.md)

## Context

Private metadata must be queryable for timeline, albums, tags, search, integrity, transactions, and future sync. Storing it in KMP Room would create a second owner outside Rust and risk plaintext/WAL/backup leaks. A custom encrypted record store would maximize control but add significant transaction/query complexity.

## Decision

Rust owns the private catalog API, schema, connection, migrations, transactions, and key. The preferred physical implementation is SQLCipher accessed from Rust, pending mobile build-size, performance, WAL, backup, and licensing validation.

Public-shell data remains in Room/DataStore.

## Alternatives considered

### Room KMP with encrypted columns

Rejected as default: Kotlin owns schema/serialization; query/index semantics become complex; boundary weakens.

### Plain SQLite with every field as custom encrypted blob

Viable but not preferred: difficult sorting/search/indexing and extensive nonce/version logic.

### Custom encrypted append-only store

Potentially auditable but substantial database/query/recovery engineering. May be revisited if SQLCipher prototype fails.

### SQLCipher opened by KMP

Rejected: private catalog ownership escapes Rust.

## Consequences

### Positive

- private query logic and migrations remain in Rust;
- transparent encrypted pages for metadata/indexes;
- mature SQLite transaction/query model;
- Room/public shell remains cleanly separated.

### Tradeoffs

- C/native dependency and cross-compilation;
- binary size and patch surface;
- SQLCipher is defense-in-depth, not protection from unlocked process;
- WAL/backup/configuration require dedicated tests.

## Security impact

Catalog key derives from root domain and exists only in unlocked Rust session. Object/collection keys remain wrapped inside the catalog. Lock closes DB before zeroizing key.

## Compatibility impact

Logical catalog schema is normative; physical engine can change through migration. Raw database pages are not sync protocol.

## Validation required before acceptance

- Android/iOS/CLI build prototype;
- size/performance/energy measurements;
- WAL/journal/backup encryption inspection;
- crash/migration tests;
- dependency/license/update review;
- comparison with custom encrypted-store alternative.
