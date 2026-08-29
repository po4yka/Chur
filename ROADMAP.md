# Chur Roadmap

> **Status:** Proposed delivery plan; this document owns the phase definitions

Chur is developed in security-gated phases. Dates are intentionally omitted until implementation velocity and review capacity are known. Advancement depends on evidence, not feature count.

## Current status

**Phase 3 implementation.** The canonical encrypted synchronization protocol, durable client state, reference self-hosted server, ciphertext transport, locked inbox, recovery identity, revocation, rotation, malicious-server harness, and protocol vectors are implemented. Gate 5 is not approved: no job runs the background path on a device or a user-operated deployment, and the protocol-focused independent review has not been commissioned. Earlier phase device, review, and approval gaps remain open.

## Phase 0 — specification and repository foundation

### Scope

| Item | State |
| --- | --- |
| complete the normative documentation set | done |
| create architecture decision records | done, 43 |
| scaffold KMP/CMP and Rust workspaces | done. `:shared:app` is a Compose Multiplatform module holding the one screen Phase 0 owns, the ABI gate; the Notes public shell and every private screen are Phase 1 |
| pin toolchains and dependencies | done: `rust-toolchain.toml`, `gradle/libs.versions.toml`, a wrapper distribution SHA-256, `Cargo.lock`, and the four mobile Rust targets built and symbol-checked on every pull request |
| establish canonical encoding and byte-exact v1 formats | done |
| implement `chur-cli` foundations | done: vector generation and verification, container inspection, the two benchmarks, the ABI handshake |
| publish deterministic positive and negative vectors | done, 78. It was 62 at the end of Phase 0; Phase 2 added the backup structures its own format needed |
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
| security invariants mapped to tests | met. Thirty-one rows of [`docs/assurance/SECURITY_TEST_PLAN.md`](docs/assurance/SECURITY_TEST_PLAN.md) §13 name a running test target, nineteen of them at the end of Phase 0, seven more in Phase 1, and four in Phase 2; every other row names a procedure no job executes, and the audit-only rows are named as such |
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

| Item | State |
| --- | --- |
| Media3 and AVFoundation range readers | done. `ChurDataSource` and `ChurResourceLoader` both hold one reader lease and call `chur_object_reader_read_at`; neither sees a container, a key, or a path, so a codec is downstream of every cryptographic check |
| seekable video and audio playback | done in code, unproved on a device. `VaultPlayer` links and compiles for both hosts and the seam is one `expect` function; no job runs a player, because no job runs on a device |
| encrypted poster frames and waveforms | done. [`docs/interop/MEDIA_PIPELINE.md`](docs/interop/MEDIA_PIPELINE.md) §6.1 fixes the waveform record, which the kind had never had, and [ADR-0042](docs/adr/0042-freeze-the-audio-waveform-record.md) argues it. `derived::needs` decides both kinds by media class, which is what made a poster reachable for a 1080p video at all |
| large-file import/export and cancellation | done. The import's commit no longer reads the whole container to verify it, video takes the 1 MiB chunk that makes the 1 TiB bound reachable, and one refill loop serves every caller. Export, materialization, and the integrity scan observe cancellation per chunk rather than per object |
| independent decoy vault identity | done. The registry always admitted two and no flow provisioned the second; the settings entry does, `CHUR_CAP_DECOY_VAULT` is set, and `vault::create` refuses a credential that already opens an identity here. `tests/decoy_isolation.rs` is the [`docs/security/DECOY_VAULT.md`](docs/security/DECOY_VAULT.md) §11 matrix |
| stronger discreet-mode policies | done. Both of that document's open items are decided in it: the session gate is a documented visible route and no secret gesture, and panic is a long press on the lock control with a matching accessibility action. The public-shell disclosure is implemented in all five parts, and the Android backup policy is split per [`docs/ANDROID.md`](docs/ANDROID.md) §13.4 with a job that fails on a vault path |
| native portable encrypted backup | done. [`docs/format/BACKUP_FORMAT_V1.md`](docs/format/BACKUP_FORMAT_V1.md) is implemented end to end, [ADR-0043](docs/adr/0043-the-backup-manifest-carries-a-commitment-not-an-inventory.md) settles the two decisions it left open, and the §13 free-space preflight is the one part deliberately not implemented |
| performance and energy tuning | **partly done.** The two budget rows Phase 2's exit criteria name are measured and recorded in [`docs/assurance/PERFORMANCE_BUDGETS.md`](docs/assurance/PERFORMANCE_BUDGETS.md) §12. Energy is not: §8 there asks for battery and thermal measurement of long import, backup, integrity scan, and migration, and that needs a device |

### Exit criteria

| Criterion | State |
| --- | --- |
| multi-gigabyte objects remain bounded in memory | met. `chur-media` `tests/bounded_memory.rs` measures the property rather than asserting it: a metered `ReadAt` records the largest single request, and a container 64 times longer does not change it. The backup path holds one inventory entry and one buffer whatever the vault's size |
| random seek and lock invalidation meet budgets | **partly met.** Both are measured and both are far inside the §2 candidates on this host, and §1 there requires a release-like build on a device from [ADR-0017](docs/adr/0017-freeze-the-supported-device-set.md), which this is not. The benchmark runs through the binary both platforms build, so the device measurement needs a device and no new code |
| real/decoy isolation tests pass | met. Nine tests over the §11 matrix: independent roots, catalogs, namespaces, and slots; a sibling credential failing exactly as a wrong one does, and a root holding one identity failing the same way; no path naming which identity it belongs to; locking, recovery, migration, and device-slot removal each touching one identity only |
| backup restore succeeds across Android, iOS, and CLI | **partly met.** One implementation serves all three, and the CLI runs it end to end in `chur-cli` `tests/backup_flow.rs`. The two hosts reach it through §6.7 and the same `VaultRepository` call, and neither has been run on a device |

## Phase 3 — encrypted synchronization

### Scope

| Item | State |
| --- | --- |
| opaque object storage in a deployment the user controls | done. `chur-sync-server` stores immutable ciphertext under opaque vault and object identifiers; [`docs/sync/SERVER_OPERATOR.md`](docs/sync/SERVER_OPERATOR.md) is the operator contract |
| reference sync-server implementation with operator documentation | done. The Axum service exposes authenticated membership, operation, checkpoint, resumable object, token-rotation, and signed-deletion routes |
| ciphertext-only background transfers | done in code, unproved on a device. Ktor uses OkHttp on Android and Darwin on iOS; `LockedSyncPuller` stages bounded opaque records through ABI 1.4 without opening the catalog or advancing accepted cursors |
| device identities | done. Ed25519 and X25519 keys are separated, the portable identity is root-wrapped in the catalog, and backup carries its recovery form |
| signed per-device operation logs | done. Authoring, acceptance, durable heads, checkpoint floors, and historical verification-key retention are implemented |
| replay, rollback, and fork detection | done. The operation state machine and malicious-server harness reject replay conflicts, rollback, equivocation, omission against a checkpoint, and key substitution |
| deterministic conflict resolution | done. Causal scalar registers, observed-remove sets, materialization, and digest tie-breaking converge for the same authenticated operation set |
| tombstones and garbage collection | done. Delete and restore follow causality; collection is gated by authenticated checkpoint coverage and retention |
| multi-device recovery | done. Portable identity recovery restores the sync identity and its authenticated bootstrap state without exporting device-bound slots |
| device revocation and forced collection-epoch rotation and rewrap | done. Revocation pins the final accepted device operation, revokes transport credentials, advances the epoch, and resumes bounded rewrap work |

### Exit criteria

| Criterion | State |
| --- | --- |
| server trust model and sync protocol finalized | met. The accepted v1 documents define the server's observable data, client trust rules, canonical records, limits, deletion authorization, and locked behavior |
| malicious-server test harness operational | met. `chur-sync-protocol` `tests/malicious_server.rs` exercises replay, omission, key substitution, rollback, and equivocation |
| protocol vectors published | met. The v1 set has 94 vectors and two fixtures, including accepted and rejected sync records, and the reference server consumes them |
| independent review of identity, log, and rollback design | **outstanding.** [`docs/assurance/SECURITY_REVIEW_SCOPE.md`](docs/assurance/SECURITY_REVIEW_SCOPE.md) defines the engagement; no repository job can supply an independent reviewer |

[`docs/assurance/EVIDENCE_PHASE_3.md`](docs/assurance/EVIDENCE_PHASE_3.md) records the observed implementation and verification evidence.

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
