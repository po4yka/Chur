# Chur Roadmap

> **Status:** Proposed delivery plan; this document owns the phase definitions

Chur is developed in security-gated phases. Dates are intentionally omitted until implementation velocity and review capacity are known. Advancement depends on evidence, not feature count.

## Current status

**Phase 1 implementation.** The vault exists and runs: Rust owns the catalog, the containers, the key slots, and the import journal; the C ABI carries a product surface as well as a control plane; and both hosts build an installable application over it. What Phase 1 still owes is the independent review, which is an engagement rather than code, and the fault-injection matrix of Gate 2. Phase 0 owes the same two approvals it owed before, which are decisions rather than code.

## Phase 0 — specification and repository foundation

### Scope

| Item | State |
| --- | --- |
| complete the normative documentation set | done |
| create architecture decision records | done, 36 |
| scaffold KMP/CMP and Rust workspaces | done. `:shared:app` is a Compose Multiplatform module holding the one screen Phase 0 owns, the ABI gate; the Notes public shell and every private screen are Phase 1 |
| pin toolchains and dependencies | done: `rust-toolchain.toml`, `gradle/libs.versions.toml`, a wrapper distribution SHA-256, `Cargo.lock`, and the four mobile Rust targets built and symbol-checked on every pull request |
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
| security invariants mapped to tests | met. Twenty-seven rows of [`docs/assurance/SECURITY_TEST_PLAN.md`](docs/assurance/SECURITY_TEST_PLAN.md) §13 name a running test target, nineteen of them at the end of Phase 0 and seven more in Phase 1; every other row names a procedure no job executes, and the audit-only rows are named as such |
| release gates and review scope approved | **outstanding.** Both are decisions, not artifacts. [`docs/assurance/EVIDENCE_PHASE_0.md`](docs/assurance/EVIDENCE_PHASE_0.md) §8 states what each one accepts and §5 states its cost |
| the minimum job set of ADR-0031 runs on every pull request | met. The four minimum jobs run, and the vector, C ABI, fuzz, Gradle, and Kotlin/Native jobs joined them as their subjects landed |

Gate 1 may be declared once the two approvals are recorded.

## Phase 1 — local recoverable photo vault

### Scope

| Item | State |
| --- | --- |
| functional Notes public shell | done: a list, an editor, search, pinning, and a JSON store that keeps notes across launches. It depends on no private module, so it cannot reach the vault even by accident |
| one private vault | done |
| vault creation and first run per [`docs/security/PROVISIONING.md`](docs/security/PROVISIONING.md) | done: the descriptor transaction of §9, an abandoned creation that leaves nothing openable, and the recovery phrase shown once |
| password, device, and recovery key slots | done in code, unproved on a device. All four families enroll and unlock, and the Android Keystore family reaches the platform through [ADR-0041](docs/adr/0041-the-android-keystore-slot-exchanges-root-bytes.md) and the §6.6 surface it adds. No test calls a real Keystore or Keychain, because no job runs on a device |
| Rust-owned encrypted catalog | done: SQLCipher with the pragma order of [ADR-0038](docs/adr/0038-adopt-sqlcipher-as-the-v1-catalog-engine.md), the schema of [`docs/format/CATALOG_SCHEMA_V1.md`](docs/format/CATALOG_SCHEMA_V1.md), and keyset paging |
| photo import through platform pickers | **partly done.** The Android host imports through `PickVisualMedia` and requests no permission. The iOS picker is specified in [`apps/iosApp/README.md`](apps/iosApp/README.md) and lives in an Xcode project that is not in this repository |
| immutable encrypted originals | done: the container is written once, and the catalog holds no operation that rewrites one |
| encrypted metadata, thumbnails, and previews | done. All three are derived, encrypted, and read back by Rust, and both hosts decode them through one cache whose only per-platform part is the decode |
| timeline, albums, favorites, viewer, and export | done |
| catalog search as bounded by [`docs/format/CATALOG_SCHEMA_V1.md`](docs/format/CATALOG_SCHEMA_V1.md) §16, over the in-database FTS5 table of §16.4 | done |
| immediate, timed, background, and panic lock | **partly done.** All four transitions run, and three of them are reachable from a screen. The panic transition has no gesture bound to it, because [`docs/product/DISCREET_MODE.md`](docs/product/DISCREET_MODE.md) records the gesture as an open specification item and reserves the decision to itself |
| app-switcher privacy handling | done: `FLAG_SECURE` and a cover on Android, the cover on iOS |
| interrupted-import recovery and integrity inspection | done: the journal ordering of [`docs/format/OBJECT_CONTAINER_V1.md`](docs/format/OBJECT_CONTAINER_V1.md) §14.2, resumption on the next unlock, and a whole-vault integrity scan |

### Explicit exclusions

None of these is started, which is the intent:

- cloud account;
- sync;
- sharing;
- decoy vault;
- local AI indexing;
- claims of production security before independent review.

### Exit criteria

| Criterion | State |
| --- | --- |
| initialization, import, key-slot, and migration fault injection passes, matching Gate 2; the complete matrix, including media, large-file, and decoy paths, is a Phase 2 exit criterion | met for the four flows this criterion names. `chur-media` `tests/fault_injection.rs` enumerates the ordered interruption points of each and walks every one; adding a point is adding a variant. The media, large-file, and decoy paths are Phase 2 and are not in it |
| no private data persists in public storage or navigation state | met, and enforced by construction rather than by inspection: the public shell's module cannot see a private module, the routes hold no object identifier, and both hosts disable backup for the directories Chur writes into |
| platform-key invalidation and recovery work on supported devices | **outstanding.** Invalidation is implemented on both sides and mapped to `PLATFORM_KEY_INVALIDATED`, and nothing has run on a device from [ADR-0017](docs/adr/0017-freeze-the-supported-device-set.md) |
| local format and Rust core receive independent review before production use | **outstanding.** [`docs/assurance/SECURITY_REVIEW_SCOPE.md`](docs/assurance/SECURITY_REVIEW_SCOPE.md) defines the review and none has been commissioned |

[`docs/assurance/EVIDENCE_PHASE_1.md`](docs/assurance/EVIDENCE_PHASE_1.md) records what runs, what does not, and where each number comes from.

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
