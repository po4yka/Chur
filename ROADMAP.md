# Chur Roadmap

> **Status:** Proposed delivery plan; this document owns the phase definitions

Chur is developed in security-gated phases. Dates are intentionally omitted until implementation velocity and review capacity are known. Advancement depends on evidence, not feature count.

## Current status

**Phase 0 implementation.** The normative documentation set, the byte-exact v1 formats, the Rust core that reads and writes them, the deterministic vector set, the harnesses, and the enforcing workflow exist. The remaining Phase 0 items are the two approvals, which are decisions rather than code.

## Phase 0 — specification and repository foundation

### Scope

| Item | State |
| --- | --- |
| complete the normative documentation set | done |
| create architecture decision records | done, 36 |
| scaffold KMP/CMP and Rust workspaces | Rust and KMP done; no Compose Multiplatform module exists yet, because the first screen is Phase 1 |
| pin toolchains and dependencies | done: `rust-toolchain.toml`, `gradle/libs.versions.toml`, a wrapper distribution SHA-256, and both lockfiles |
| establish canonical encoding and byte-exact v1 formats | done |
| implement `chur-cli` foundations | done: vector generation and verification, container inspection, the two benchmarks, the ABI handshake |
| publish deterministic positive and negative vectors | done, 62 |
| add fuzzing, corruption, migration, and FFI harnesses | done: ten fuzz targets, a bitwise corruption sweep, a version-domain migration harness, a header-consistency harness, and a C ABI harness |
| land the continuous-integration workflow that enforces the release gates | done, [ADR-0031](docs/adr/0031-continuous-integration-owns-gate-enforcement.md) |
| prototype Android Keystore and iOS Keychain slots | done |
| benchmark candidate chunk sizes and Argon2id profiles | done on a workstation; a measurement on the [ADR-0017](docs/adr/0017-freeze-the-supported-device-set.md) device set is outstanding, and no candidate above the frozen floor is approved until then |

### Exit criteria

| Criterion | State |
| --- | --- |
| no unresolved circular key dependencies | met. The one circle the implementation found is recorded and broken: the manifest key and AAD bind fields sealed inside the manifest, so a reader supplies the stream identity from the catalog, per [`docs/format/OBJECT_CONTAINER_V1.md`](docs/format/OBJECT_CONTAINER_V1.md) §4 |
| parser limits specified and tested | met. `chur-core::limits` gathers every bound beside the section that owns it and checks their consistency at compile time; ten fuzz targets and the corruption harness exercise them |
| Android, iOS, and CLI consume identical vectors | met at the index level. One generated source embeds `test-vectors/v1`, and the same suite runs in `jvmTest`, `testAndroidHostTest`, and `iosSimulatorArm64Test`. Decoding a private record on a platform is not in scope: [`docs/format/CANONICAL_ENCODING_V1.md`](docs/format/CANONICAL_ENCODING_V1.md) §13 reserves that for Rust, so the platform side checks the index and the FFI handshake |
| security invariants mapped to tests | met. Eighteen rows of [`docs/assurance/SECURITY_TEST_PLAN.md`](docs/assurance/SECURITY_TEST_PLAN.md) §13 name a running test target; every other row names a procedure no job executes, and the six audit-only rows are named as such |
| release gates and review scope approved | **outstanding.** This is a decision, not an artifact |
| the minimum job set of ADR-0031 runs on every pull request | met. The four minimum jobs run, and the vector, C ABI, fuzz, Gradle, and Kotlin/Native jobs joined them as their subjects landed |

Gate 1 may be declared once the two approvals are recorded.

## Phase 1 — local recoverable photo vault

### Scope

- functional Notes public shell;
- one private vault;
- vault creation and first run per [`docs/security/PROVISIONING.md`](docs/security/PROVISIONING.md);
- password, device, and recovery key slots;
- Rust-owned encrypted catalog;
- photo import through platform pickers;
- immutable encrypted originals;
- encrypted metadata, thumbnails, and previews;
- timeline, albums, favorites, viewer, and export;
- catalog search as bounded by [`docs/format/CATALOG_SCHEMA_V1.md`](docs/format/CATALOG_SCHEMA_V1.md) §16, over the in-database FTS5 table of §16.4;
- immediate, timed, background, and panic lock;
- app-switcher privacy handling;
- interrupted-import recovery and integrity inspection.

### Explicit exclusions

- cloud account;
- sync;
- sharing;
- decoy vault;
- local AI indexing;
- claims of production security before independent review.

### Exit criteria

- initialization, import, key-slot, and migration fault injection passes, matching Gate 2; the complete matrix, including media, large-file, and decoy paths, is a Phase 2 exit criterion;
- no private data persists in public storage or navigation state;
- platform-key invalidation and recovery work on supported devices;
- local format and Rust core receive independent review before production use.

## Phase 2 — video, audio, and decoy

### Scope

- Media3 and AVFoundation range readers;
- seekable video and audio playback;
- encrypted poster frames and waveforms;
- large-file import/export and cancellation;
- independent decoy vault identity;
- stronger discreet-mode policies;
- native portable encrypted backup;
- performance and energy tuning.

### Exit criteria

- multi-gigabyte objects remain bounded in memory;
- random seek and lock invalidation meet budgets;
- real/decoy isolation tests pass;
- backup restore succeeds across Android, iOS, and CLI.

## Phase 3 — encrypted synchronization

### Scope

- opaque object storage in a deployment the user controls, per [`docs/sync/SERVER_TRUST_MODEL.md`](docs/sync/SERVER_TRUST_MODEL.md) §11;
- reference sync-server implementation with its operator documentation;
- ciphertext-only background transfers;
- device identities;
- signed per-device operation logs;
- replay, rollback, and fork detection;
- deterministic conflict resolution;
- tombstones and garbage collection;
- multi-device recovery;
- device revocation, and the collection-epoch rotation and rewrap it forces.

### Exit criteria

- server trust model and sync protocol finalized;
- malicious-server test harness operational;
- protocol vectors published;
- independent review of identity, log, and rollback design.

## Phase 4 — collection sharing

### Scope

- recipient verification;
- X25519 HPKE collection grants;
- Ed25519 sender/device authentication;
- permissions and membership changes;
- collection epochs and rewrapping;
- revocation semantics;
- multi-recipient and multi-device interoperability.

### Exit criteria

- separate sharing-protocol audit;
- clear forward-only revocation guarantees;
- recovery and device-loss behavior tested;
- no claim that previously authorized recipients can be forced to delete plaintext.

## Later exploration

- encrypted documents;
- local OCR and captions;
- encrypted semantic indexes and embeddings;
- embedding search indexes and private multimodal search, both beyond the bounded catalog query of [`docs/format/CATALOG_SCHEMA_V1.md`](docs/format/CATALOG_SCHEMA_V1.md) §16;
- optional hybrid post-quantum recipients;
- shared family or team vaults;
- advanced padding and batching;
- additional functional public shells.

## Permanent non-goals

Unless the threat model changes explicitly, Chur does not promise:

- protection of plaintext from a compromised unlocked kernel;
- physical secure overwrite on flash storage;
- universal screenshot prevention on iOS;
- cryptographically undetectable hidden volumes;
- server-assisted password reset that can recover the root secret without a user-held recovery factor;
- global plaintext-hash deduplication.

## Roadmap governance

A phase may start experimentally before the previous phase ships, but production release gates remain ordered. Scope changes that affect security boundaries require an ADR, threat-model update, and revised assurance plan.
