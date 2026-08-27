# Chur Dependency Policy

> **Status:** Proposed normative supply-chain policy

Chur minimizes dependency and native-code surface because a vault inherits the security properties, maintenance quality, and build integrity of every component that can read secrets or produce persisted bytes.

## Principles

- prefer small, focused, actively maintained libraries;
- prefer standards-based primitives over custom constructions;
- prefer pure Rust in the secure core unless a mature native dependency provides a clear advantage;
- keep protocol bytes independent from serializer defaults and dependency versions;
- pin toolchains and lock dependency graphs;
- review transitive dependencies, features, licenses, and build scripts;
- remove unused dependencies promptly.

## Security-sensitive categories

The following require explicit review:

- cryptographic primitives and key handling;
- parsers, canonical encoders, compression, and image/media codecs;
- SQLCipher, SQLite, OpenSSL, or other C/C++ code;
- FFI and binding generators;
- networking, TLS, certificate validation, and serialization;
- code generation, Gradle plugins, Cargo build scripts, and CI actions;
- logging, crash reporting, analytics, and telemetry;
- backup, cloud, and identity SDKs.

## Approved cryptographic direction

The v1 design prefers audited or widely reviewed Rust implementations for:

```text
XChaCha20-Poly1305
Argon2id
HKDF-SHA-256
SHA-256
BLAKE3 for authenticated commitments when finalized
X25519 / Ed25519 for future sharing identities
secure randomness through getrandom/OS CSPRNG
zeroization helpers
```

A library being popular is not sufficient. Review API misuse resistance, maintenance, audit history, target support, constant-time claims, `unsafe`, and transitive dependencies.

## Adding a dependency

A pull request must document:

1. required capability;
2. considered alternatives, including standard-library or existing dependency solutions;
3. owner and maintenance activity;
4. license and notice obligations;
5. audit or review history;
6. Rust `unsafe` and native-code footprint;
7. build scripts and network access;
8. Android/iOS target compatibility;
9. binary-size and performance impact;
10. data, permissions, and telemetry behavior;
11. update and removal plan.

Security-critical additions require a dedicated reviewer.

## Version policy

- commit Gradle and Cargo lockfiles where applicable;
- pin GitHub Actions to immutable commit SHAs;
- avoid floating Git dependencies;
- record source revision for vendored code;
- upgrade deliberately with changelog and compatibility review;
- do not allow dependency updates to silently change persisted or wire bytes;
- keep a documented minimum Rust/toolchain policy once implementation begins.

## Vendored agent skills

`.agents/` and `.claude/` hold agent instruction files copied from third-party repositories. They are vendored content under this policy, not generated state: they are tracked, reviewed on update, and never run against a real vault, a production secret, or user media. They contribute no code to the Android, iOS, Rust, or CLI artifacts and are excluded from release evidence and from the SBOM.

`skills-lock.json` is their manifest. Each entry records the upstream repository, the path within it, a content hash, and the upstream commit the content was taken from. The commit satisfies "record source revision for vendored code" above; a content hash alone proves integrity but not provenance, so it is not a substitute.

Open item, owner: repository maintainer. The current entries predate this rule and carry no commit field. The field is populated at the next skill synchronization, before Gate 1.

## Cargo features

Disable default features unless they are understood. Features that add platform key access, network clients, file-system traversal, dynamic loading, serialization formats, or native libraries require review.

## Native dependencies

SQLCipher/OpenSSL/FFmpeg/libheif-like dependencies require:

- reproducible source or trusted binary provenance;
- target/architecture matrix;
- symbol and linkage inspection;
- license review;
- patch and vulnerability process;
- binary-size measurement;
- sandbox and codec attack-surface analysis;
- release artifact verification.

Do not download executable dependencies during a release build from mutable URLs.

## Build scripts and plugins

Cargo `build.rs`, Gradle plugins, KSP processors, and code generators execute with developer/CI privileges. Review them as code execution dependencies. Generated output must be reproducible and traceable to a pinned generator.

## Unsafe code

New `unsafe` code must:

- be isolated to a narrow module;
- state safety invariants adjacent to the block;
- have misuse and boundary tests;
- avoid exposing raw pointers to feature code;
- be included in security-review scope;
- be inspected when compiler or target assumptions change.

## Licenses

The repository uses BSD 3-Clause, but dependencies may use other compatible licenses. AGPL/GPL code must not be copied or linked without an explicit distribution and architecture decision. Preserve required notices and source-offer obligations.

## Vulnerability management

CI should eventually run:

```text
cargo audit or equivalent advisory checks
cargo deny for licenses/bans/sources
Gradle dependency vulnerability scanning
SBOM generation
secret scanning
pinned-action verification
```

An advisory is triaged by reachability, affected feature, attack preconditions, and availability of a fixed version. A waived advisory requires a documented owner, rationale, and expiry.

## Provenance and releases

Release artifacts should provide:

- source commit;
- dependency lockfiles;
- toolchain versions;
- SBOM;
- checksums/signatures;
- Android/iOS native architecture inventory;
- reproducible or independently repeatable build instructions;
- record of security gates passed.

## Telemetry SDKs

A dependency that captures logs, crashes, analytics, sessions, screens, or network traffic is denied by default for private-vault processes. Any future use requires a privacy review, strict redaction, opt-in policy where appropriate, and tests proving that private values cannot leave the device.
