# ADR-0038: Adopt SQLCipher as the v1 Catalog Engine

- **Status:** Accepted
- **Date:** 2026-08-27
- **Decision owners:** @po4yka
- **Related:** [`0004`](0004-rust-owned-private-catalog.md), [`../format/CATALOG_SCHEMA_V1.md`](../format/CATALOG_SCHEMA_V1.md), [`../DEPENDENCY_POLICY.md`](../DEPENDENCY_POLICY.md)

## Context

[ADR-0004](0004-rust-owned-private-catalog.md) decided that Rust owns the private catalog and named SQLCipher as the preferred physical implementation, "pending mobile build-size, performance, WAL, backup, and licensing validation". It stays Proposed until that evidence exists, and [`../format/CATALOG_SCHEMA_V1.md`](../format/CATALOG_SCHEMA_V1.md) §15 lists the same validation as outstanding.

Phase 1 cannot start without an engine: the catalog is the vault state, the import journal, and the query surface. This ADR records the validation and decides the engine. It does not restate ADR-0004; the ownership decision there is unchanged.

Two facts constrain the choice more than the alternatives comparison does. [`../format/CATALOG_SCHEMA_V1.md`](../format/CATALOG_SCHEMA_V1.md) §16.4 requires an in-database FTS5 table, and §11 requires a journal reservation to be durable against power loss rather than only against process loss. Both are properties of SQLite that a custom store would have to reproduce.

## Decision

The v1 catalog is SQLCipher, opened from Rust through `rusqlite` with the `bundled-sqlcipher-vendored-openssl` feature. Default features are off.

- the source is vendored and built from the Cargo dependency graph, so no release build downloads an executable dependency from a mutable URL, which [`../DEPENDENCY_POLICY.md`](../DEPENDENCY_POLICY.md) "Native dependencies" forbids;
- the key is applied through the raw-key pragma, not through SQLCipher's own password KDF. The catalog key is already a full-entropy HKDF output under `chur/v1/root/catalog-database`, so PBKDF2 over it would add cost without adding entropy and would put a second KDF profile into the at-rest format;
- `synchronous` is `FULL` and `journal_mode` is `WAL`. `NORMAL` in WAL mode returns from a commit once the write reaches the operating system, which does not satisfy §11;
- `cipher_memory_security` is `ON`, and it is set before the key so that the key derivation SQLCipher performs is itself inside the protection;
- the connection is closed explicitly at lock, before the root is zeroized, which is step 5 of [`../security/PLAINTEXT_LIFECYCLE.md`](../security/PLAINTEXT_LIFECYCLE.md) §8.

ADR-0004 moves to Accepted on this evidence.

## Validation performed

| Item ADR-0004 required | Result |
| --- | --- |
| Android build prototype | `aarch64-linux-android` cross-compiles with NDK 28, 84 s from a cold dependency cache |
| iOS build prototype | `aarch64-apple-ios` cross-compiles with the Xcode toolchain, 73 s from a cold dependency cache |
| CLI build prototype | host build, 96 s from a cold dependency cache, including the test run below |
| library versions | SQLCipher 4.14.0 community over SQLite 3.51.3 |
| FTS5 availability | the `unicode61` tokenizer with `remove_diacritics 2` and a 2-and-3-character prefix index creates and matches, which is the exact profile §16.4 fixes |
| WAL and journal encryption | asserted by a test, not by inspection: a catalog written to a file starts with no `SQLite format 3` header, and a value written into a table does not appear anywhere in the file bytes |
| licensing | SQLCipher community edition is BSD-style; OpenSSL 3.x is Apache-2.0. Both are compatible with the repository's BSD 3-Clause and neither carries a source-offer obligation |
| binary size | `libcrypto.a` is 13.2 MiB as a static archive for `aarch64-linux-android`. The archive is not the shipped size: the linker takes only the objects SQLCipher references, which are AES, SHA-2, HMAC, PBKDF2, and the CSPRNG. `libssl.a` is not linked at all, because SQLCipher uses `libcrypto` only |
| crash and migration tests | the migration ladder of §18 is one step per version with the version recorded in the same transaction as the schema it describes, so a crash leaves the database at the version whose schema is present |
| dependency and feature review | recorded below |

The two items ADR-0004 lists that this evidence does not close are performance and energy measurement on a device from [ADR-0017](0017-freeze-the-supported-device-set.md), and backup correctness. Both are Phase 1 exit work rather than engine-selection evidence: the first needs hardware, and the second needs [`../format/BACKUP_FORMAT_V1.md`](../format/BACKUP_FORMAT_V1.md) implemented, which is Phase 2.

## Dependency review

[`../DEPENDENCY_POLICY.md`](../DEPENDENCY_POLICY.md) "Adding a dependency" requires eleven answers.

1. **Capability.** An encrypted, transactional, indexed local store with full-text search.
2. **Alternatives.** ADR-0004 considered and rejected Room KMP, plain SQLite with per-field encrypted blobs, a custom append-only store, and SQLCipher opened by KMP. The custom store is the only one that avoids the C dependency, and it would have to reproduce SQLite's transaction model, its keyset paging, and FTS5.
3. **Owner and activity.** SQLCipher is maintained by Zetetic; `rusqlite` and `libsqlite3-sys` are maintained by the rusqlite organisation; `openssl-src` by the rust-openssl maintainers. All three are actively released.
4. **Licence.** SQLCipher community: BSD-style. OpenSSL 3.x: Apache-2.0. `rusqlite`: MIT. No notice obligation beyond retaining the licence texts.
5. **Audit history.** OpenSSL and SQLite are among the most reviewed C code in use. SQLCipher's codec has no published third-party audit, which is why ADR-0004 already classifies page encryption as defence in depth rather than as protection from an unlocked process.
6. **Unsafe and native footprint.** The whole engine is C. `chur-catalog` itself adds no `unsafe`: `rusqlite` owns the `unsafe` and the crate keeps `unsafe_code = "forbid"` from the workspace.
7. **Build scripts and network.** `libsqlite3-sys` and `openssl-sys` each run a `build.rs` that compiles vendored source. Neither fetches from the network.
8. **Target compatibility.** Verified above for `aarch64-linux-android` and `aarch64-apple-ios`, and by the enforcing workflow for the other mobile targets.
9. **Size and performance.** Measured above; the device measurement is outstanding for the same reason as every other [ADR-0017](0017-freeze-the-supported-device-set.md) row.
10. **Data, permissions, telemetry.** None. No dependency in this graph opens a socket or reads a file the catalog does not name.
11. **Update and removal.** Version is pinned by `Cargo.lock`. Removal means a `catalog_format_version` step under §18 and a new ADR that supersedes this one, because the logical schema is engine-independent and the physical pages are not.

Default features are disabled deliberately. `rusqlite`'s defaults pull `ffi-sqlite-wasm-rs`, which drags a `wasm-bindgen` graph in for a WebAssembly VFS no Chur target uses.

## Consequences

### Positive

- the query surface of §16, the keyset paging of §16.2, and the search of §16.4 are engine features rather than code this repository has to write and prove;
- the import journal shares a transaction domain with the catalog state, which is what §11 requires and what a separate journal file could not give;
- page encryption keeps the metadata, the indexes, and the FTS postings encrypted at rest without a per-column nonce scheme.

### Tradeoffs

- a large C dependency and a vendored OpenSSL enter the release artifact, and both must be tracked for advisories;
- cross-compilation is now part of every build of the catalog crate, so a broken NDK or Xcode toolchain fails more of the workspace than before;
- the at-rest guarantee is only as good as the codec, and no third-party audit of it exists.

## Security impact

Affected invariants: SEC-019, SEC-020, SEC-032.

Adding vendored OpenSSL adds attack surface that Chur's own cryptography does not use: no Chur key, nonce, tag, or commitment is produced by OpenSSL. It reaches only SQLCipher's page codec. A vulnerability in it is therefore a vulnerability in the at-rest defence-in-depth layer, not in the object containers, the envelopes, or the slots, all of which stay in the pure-Rust suite of [`../CRYPTOGRAPHY.md`](../CRYPTOGRAPHY.md).

## Compatibility impact

None to any persisted format. The catalog's physical pages were never portable: [`../format/CATALOG_SCHEMA_V1.md`](../format/CATALOG_SCHEMA_V1.md) §15 already forbids a backup from carrying raw pages, WAL segments, or a file copy, and §19 already forbids syncing them.

## Follow-up

- measure catalog open, page query, and search latency on the device roles of [ADR-0017](0017-freeze-the-supported-device-set.md) and record them under [`../assurance/PERFORMANCE_BUDGETS.md`](../assurance/PERFORMANCE_BUDGETS.md);
- add the advisory scan of [`../DEPENDENCY_POLICY.md`](../DEPENDENCY_POLICY.md) "Vulnerability management" for the OpenSSL graph specifically, because its advisory rate is higher than the rest of the dependency set combined.
