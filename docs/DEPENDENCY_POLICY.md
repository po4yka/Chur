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

Every entry now carries a `commit`. The forty-six vendored skills trace to twenty-six distinct upstream commits, and each recorded commit reproduces the vendored bytes exactly.

Two hashes are recorded, and they are not interchangeable:

- `computedHash` belongs to the external skill-synchronisation tool. It covers a skill's `SKILL.md` and nothing else. Thirty-three of the forty-six skills also vendor a `references/` directory, and for those thirty-three the recorded value reproduces neither the vendored content nor any state of that directory in upstream history. It verifies nothing and is kept only because the tool owns it;
- `contentHash` is the value this repository verifies. It covers the whole vendored directory: SHA-256 over every file under it, in ascending order of the file's path relative to that directory, feeding for each file the relative path as UTF-8 with `/` separators and then the file bytes.

`scripts/check-vendored-skills.py` recomputes `contentHash` for every entry, reports a skill that is vendored but unlocked or locked but absent, and fails on any mismatch. It runs offline and is a job of the enforcing workflow. With `--verify-upstream` and a clone of each upstream repository it also checks that every recorded commit still reproduces the vendored bytes, which is the provenance half and needs the network.

A skill synchronisation updates both hashes and the commit, and the checker is what proves it did.

## Recorded additions

### `ed25519-dalek` 3.0.0, Rust sync core

- **Capability:** RFC 8032 Ed25519 signatures for Phase 3 device identities, operation records, enrollment, revocation, and checkpoints. `CRYPTOGRAPHY.md` §5 and §52 already select Ed25519 and name this implementation direction.
- **Alternatives:** the Rust standard library has no Ed25519. A local implementation would create a new cryptographic primitive to audit. A TLS or general cryptography library adds unrelated protocol, native, and configuration surface.
- **Owner and maintenance:** the dalek-cryptography project maintains it in the active `curve25519-dalek` workspace. Version 3.0.0 uses Rust 2024 and MSRV 1.85, matching this workspace.
- **License:** BSD-3-Clause, the repository license. The transitive RustCrypto `ed25519` and `signature` interfaces are Apache-2.0 OR MIT.
- **Review history:** the dalek libraries received a public Quarkslab review in 2019. That is supporting evidence, not the independent Phase 3 protocol review required by Gate 5.
- **Unsafe and native footprint:** `ed25519-dalek` is pure Rust, has no `build.rs`, and forbids unsafe code when batch verification is disabled. Chur enables no batch or hazardous low-level API. Its `curve25519-dalek` dependency uses a build script only to select compiler and target capabilities, a derive macro, and target-specific unsafe optimized arithmetic; `sha2` also uses target-specific unsafe optimized code. These transitive paths perform no network, process, or application-file I/O and remain in the cryptographic review scope.
- **Features:** exact version `=3.0.0`, default features disabled, only `fast` and `zeroize` enabled. Serde, PEM, PKCS#8, batch verification, legacy compatibility, digest/prehash, RNG, and `hazmat` are absent.
- **Targets and size:** the crate is `no_std`, requires no platform API, and supports the Android/iOS Rust targets. `fast` includes the curve precomputation table; the release artifact size is measured with the Phase 3 native packages.
- **Data and telemetry:** it accepts in-process keys and bytes and performs no file, environment, process, network, permission, logging, or telemetry operation.
- **Update and removal:** protocol bytes and RFC 8032 verification semantics are pinned by vectors. Updates are deliberate lockfile changes with vector, mobile-target, advisory, and size checks. Removal requires another reviewed RFC 8032 implementation.

### `androidx.media3`, Android only

- **Capability:** playing a video or a recording from a vault-backed byte source. [`interop/MEDIA_PIPELINE.md`](interop/MEDIA_PIPELINE.md) §1 puts codec probing and decoding on the platform, and §9 has the player ask for plaintext ranges; Media3 is the platform's player on Android and the only one whose `DataSource` interface accepts a source that is neither a file nor a URL.
- **Alternatives:** the framework `MediaPlayer` accepts a `FileDescriptor` or a `Uri` and no custom source, so it would need either a plaintext file on disk — which [`security/PLAINTEXT_LIFECYCLE.md`](security/PLAINTEXT_LIFECYCLE.md) §5 bounds to the cases where a platform API accepts nothing else — or a local socket, which is a second boundary with no authentication of its own. Neither is smaller than adding the library.
- **Owner and maintenance:** Google, as part of AndroidX. Actively released.
- **License:** Apache 2.0, with the notice obligation the other AndroidX artifacts already carry.
- **Native footprint:** the artifacts used here are Java and Kotlin. `media3-exoplayer` loads the platform's own codecs through `MediaCodec` and this build adds no software decoder extension.
- **Build scripts and network:** none beyond the ordinary Maven resolution the Gradle lockfile pins.
- **Reach:** it never sees a container, a key, or a path. It receives plaintext ranges from `ChurDataSource`, which holds one reader lease and calls `chur_object_reader_read_at`, so every byte it decodes is downstream of an authenticated chunk. A codec failure is a decode failure and cannot reach ciphertext.
- **Telemetry:** none is enabled. The artifacts used are `media3-exoplayer`, `media3-datasource`, and `media3-ui`; no analytics or cast artifact is added.
- **Removal plan:** the seam is `VaultPlayer`, an `expect` function with one Android implementation. Replacing the player replaces that file.

iOS adds nothing: `AVFoundation` is part of the platform, and the resource-loader delegate that feeds it is written in Kotlin/Native beside the Android data source.

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

`unsafe_code = "forbid"` in `[workspace.lints.rust]` is the default for every crate in `rust/`. `forbid` cannot be lifted by an inner `allow` or `expect`, so a crate that must contain `unsafe` cannot inherit it. Two crates are exempt, `chur-ffi` and `chur-jni`, and no third is without an ADR.

`chur-ffi` is the first: the v1 C ABI of [`interop/FFI_CONTRACT.md`](interop/FFI_CONTRACT.md) requires `#[unsafe(no_mangle)] pub extern "C"` exports and a caller-allocated raw-buffer data plane, and neither compiles under `forbid`. `rust/crates/chur-ffi/Cargo.toml` therefore declares its own `[lints.rust]` and `[lints.clippy]` tables instead of inheriting, and sets `unsafe_code = "deny"`; a block overrides that level with `#[expect(unsafe_code, reason = ...)]` and an adjacent SAFETY comment.

`chur-jni` is the second, under [ADR-0040](adr/0040-add-a-rust-jni-adapter-crate.md). JNI requires a native function whose symbol name encodes the Java class and method, and [`interop/FFI_CONTRACT.md`](interop/FFI_CONTRACT.md) §6.2 forbids such a symbol in the Chur library, so the Android adapter is a second artifact. It holds no logic: every function reads the JVM arguments, calls one `chur_*` export, and writes the result back.

Two consequences follow and are requirements, not notes. A crate-local lint table replaces inheritance rather than extending it, so it repeats every workspace level, and a change to `[workspace.lints]` must be applied to it in the same pull request. Adding a third crate to this exception requires an ADR; loosening the workspace lint instead of adding a crate-local table is a defect.

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
