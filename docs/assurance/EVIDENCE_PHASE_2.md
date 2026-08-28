# Phase 2 Evidence

> **Status:** Evidence record for the Phase 2 scope and exit criteria of [`../../ROADMAP.md`](../../ROADMAP.md). It records what is true; it approves nothing.

[`RELEASE_GATES.md`](RELEASE_GATES.md) requires every gated release to record its evidence and, explicitly, "which of its items had no enforcing job". This document is that record for Phase 2. [`EVIDENCE_PHASE_0.md`](EVIDENCE_PHASE_0.md) and [`EVIDENCE_PHASE_1.md`](EVIDENCE_PHASE_1.md) remain the records for their own phases; §8 here corrects what Phase 2 made untrue in them.

Regenerate every number below with the commands each row names. Nothing here is transcribed from memory.

## 1. Package

| Item | Value |
| --- | --- |
| Source commit | the commit this file is read at; `git rev-parse HEAD` |
| Canonical encoding profile | `0x0001` |
| Container, descriptor, envelope, slot, backup, catalog versions | `0x0001` each, [`../format/CANONICAL_ENCODING_V1.md`](../format/CANONICAL_ENCODING_V1.md) §15.2 |
| Suite | `0x0001`; `0x0002` is the Android Keystore wrap, which the Keystore performs |
| FFI ABI | 1.3, capabilities `0x000000000000001F` |
| Waveform record | `record_version` `0x01`, [`../interop/MEDIA_PIPELINE.md`](../interop/MEDIA_PIPELINE.md) §6.1, [ADR-0042](../adr/0042-freeze-the-audio-waveform-record.md) |
| Catalog engine | SQLCipher 4.14.0 over SQLite 3.51.3, vendored, [ADR-0038](../adr/0038-adopt-sqlcipher-as-the-v1-catalog-engine.md) |
| Vector-set digest | `chur-cli vectors digest --dir ../test-vectors/v1`; 78 vectors, 16 of them the backup structures Phase 2 froze |
| Rust toolchain | `rust/rust-toolchain.toml`, exact |
| Gradle toolchain | `gradle/libs.versions.toml` and `gradle/wrapper/gradle-wrapper.properties`, with the distribution SHA-256 |
| Dependency locks | `rust/Cargo.lock`; the Gradle build resolves through the version catalog |
| New dependency | `androidx.media3` 1.9.0, Android only, recorded against the checklist in [`../DEPENDENCY_POLICY.md`](../DEPENDENCY_POLICY.md) |
| SBOM, checksums, signing | **absent.** Phase 2 produces a debuggable application and distributes nothing |

The five capability bits are `CHUR_CAP_DECOY_VAULT`, `CHUR_CAP_OBJECT_READER`, `CHUR_CAP_SEQUENTIAL_READER`, `CHUR_CAP_INTEGRITY_SCAN`, and `CHUR_CAP_BACKUP_PACKAGE`. `chur-ffi` `lib::tests::no_capability_is_declared_before_its_surface_exists` is what keeps that value honest, and it asserts each bit against the surface behind it rather than against a copy of the constant. `CHUR_CAP_SYNC` and `CHUR_CAP_CONCURRENT_READS` stay clear.

## 2. What runs

| Suite | Count | Command |
| --- | --- | --- |
| Rust | 429 tests, 0 failed | `cd rust && cargo test --workspace` |
| Kotlin | 213 tests, 0 failed | `./gradlew build -x lint` |
| Formatting | clean | `cd rust && cargo fmt --all --check` |
| Lints | clean at `-D warnings` | `cd rust && cargo clippy --workspace --all-targets -- -D warnings` |
| Vectors | 78, rebuilt and compared byte for byte | `cd rust && cargo run -p chur-cli -- vectors verify --dir ../test-vectors/v1` |
| Backup rules | 2 files, no vault path included | `python3 scripts/check-backup-rules.py` |

Phase 1 recorded 380 and 189. The 49 Rust tests and 24 Kotlin tests Phase 2 adds are the bounded-memory measurements, the cancellation cases, the two derived kinds, the backup package and its round trip, the CLI backup flow, the decoy isolation matrix, the waveform folding, and the disclosure copy.

`CC=/usr/bin/cc` is required on a workstation whose `CC` is a compiler cache; [`../../DEVELOPMENT.md`](../../DEVELOPMENT.md) records why.

## 3. Phase 2 scope

| Item | Evidence |
| --- | --- |
| Media3 and AVFoundation range readers | `ChurDataSource` (Android) and `ChurResourceLoader` (iOS) each hold one reader lease and call `chur_object_reader_read_at`. Both compile and link for their target; neither is executed by a job, because neither host runs one |
| seekable video and audio playback | `VaultPlayer` is one `expect` function with two actuals. Both check `ContentInfo.complete` before publishing a length, which [`../interop/FFI_CONTRACT.md`](../interop/FFI_CONTRACT.md) §6.1 requires: a player given a length treats a later failure as transport trouble and retries without end |
| encrypted poster frames and waveforms | `chur-format` `waveform::tests` (4) for the record, `chur-media` `tests/pipeline.rs` for the container round trip and the class-driven `needs` rule, `:shared:feature-import` `WaveformTest` (7) for the folding both hosts share |
| large-file import/export and cancellation | `chur-media` `tests/bounded_memory.rs` (4) and the three cancellation tests of `tests/pipeline.rs` |
| independent decoy vault identity | `chur-catalog` `tests/decoy_isolation.rs` (9), the [`../security/DECOY_VAULT.md`](../security/DECOY_VAULT.md) §11 matrix |
| stronger discreet-mode policies | `:shared:app` `DisclosureTest` (4) holds the copy to the rules of [`../product/DISCREET_MODE.md`](../product/DISCREET_MODE.md); `scripts/check-backup-rules.py` runs as the `backup-rules` job; the two open items of that document are decided in it |
| native portable encrypted backup | `chur-format` `backup::tests` (13), `chur-media` `tests/backup_flow.rs` (8), `chur-cli` `tests/backup_flow.rs` (4), and 16 vectors under the `backup` format word |
| performance and energy tuning | [`PERFORMANCE_BUDGETS.md`](PERFORMANCE_BUDGETS.md) §12 records the two rows Phase 2's criteria name. Energy is not measured; §8 there needs a device |

## 4. Exit criteria

| Criterion | State |
| --- | --- |
| multi-gigabyte objects remain bounded in memory | met, and measured rather than asserted. A metered `ReadAt` records the largest single request a reader makes, and `peak_read_size_does_not_grow_with_object_length` builds a container 64 times longer and finds the same number. The backup path holds one 109-byte inventory entry and one 256 KiB buffer whatever the vault's size, which is why the inventory is walked twice instead of collected |
| random seek and lock invalidation meet budgets | partly met. A 64 KiB seek at the 1 MiB video chunk costs 2.22 ms at p95 against a 150 ms candidate, and the native half of a lock costs 48 ms at p95 against a 100 ms candidate. §1 of the budgets requires a release-like build on a device from [ADR-0017](../adr/0017-freeze-the-supported-device-set.md), and the host is a workstation, so the numbers rank the work and approve nothing |
| real/decoy isolation tests pass | met. Nine tests over the §11 matrix, including the one §10 adds and §11 does not state: a root holding one identity fails a wrong credential exactly as a root holding two does, so "no sibling exists" and "the sibling was not opened" are one observation |
| backup restore succeeds across Android, iOS, and CLI | partly met. One implementation serves all three and the CLI runs it end to end as a subprocess. Both hosts reach it through §6.7 and the same `VaultRepository` call, and neither has been run on a device |

## 5. What has no enforcing job

[`RELEASE_GATES.md`](RELEASE_GATES.md#evidence-package) requires this list rather than permitting it.

- **every player.** `ChurDataSource` and `ChurResourceLoader` compile and are never executed. A `DataSource` that returned the wrong count, or a resource loader that answered a range request with the wrong offset, would pass every job this repository runs;
- **every device slot**, unchanged from Phase 1. No test calls a real Keystore or a real Keychain;
- **the backup restore on a host.** The CLI proves the code; nothing proves the two host paths that reach the same code;
- **energy and thermal behaviour**, [`PERFORMANCE_BUDGETS.md`](PERFORMANCE_BUDGETS.md) §8;
- **the backup and restore of the platform's own archive**, which [`../ANDROID.md`](../ANDROID.md) §13.4 requires to be proved by an actual run rather than by reading the XML. `scripts/check-backup-rules.py` is the reading half and says so in its own documentation;
- **the twenty-eight rows** of [`SECURITY_TEST_PLAN.md`](SECURITY_TEST_PLAN.md) §13 that still name a procedure rather than a target, and the five that are audit-only;
- **the independent review**, unchanged from Phase 1 and a Phase 1 exit criterion. [`SECURITY_REVIEW_SCOPE.md`](SECURITY_REVIEW_SCOPE.md) defines it and none has been commissioned.

## 6. What Phase 2 corrected rather than added

Four of the changes below fixed something that was wrong rather than absent, and each is recorded because a reader of the Phase 1 evidence would otherwise believe the old statement.

- **`Import::commit` read the whole container into memory to verify it.** Peak memory was the object's size, which [`PERFORMANCE_BUDGETS.md`](PERFORMANCE_BUDGETS.md) §4 forbids, and an object above `usize::MAX` failed outright. Phase 1 recorded "immutable encrypted originals — done" and that was true of the bytes and not of the bound;
- **every import wrote 256 KiB chunks.** `limits::container` caps a container at `CHUNK_COUNT_MAX` records, and that count times 1 MiB is exactly `TOTAL_PLAINTEXT_MAX`, so a video at 256 KiB stopped at a quarter of the 1 TiB bound the container specification states — by failing at the last chunk after hours of writing;
- **cancellation was declared and not implemented for three of four operations.** An export checked its flag once before it started, an integrity scan checked only between objects, and a materialization left its partial scratch file behind;
- **the Android backup policy was inverted.** The manifest opted out of backup entirely, which excluded the vault and also excluded the public shell that [`../product/DISCREET_MODE.md`](../product/DISCREET_MODE.md) deliberately puts in the platform backup. The note file also sat outside `filesDir/public/`, so §13.4's include set would have matched nothing even after the rules landed.

## 7. Known limitations

- **no job runs on a device**, which is the root of most of §5. The Android host builds and the iOS framework links; neither is installed anywhere by CI;
- **the iOS host application is a specification.** [`../../apps/iosApp/README.md`](../../apps/iosApp/README.md) describes it and the Xcode project is not in this repository. The Compose framework is therefore the whole iOS surface, which is why the viewer route and the resource loader are written in Kotlin/Native;
- **the §13 free-space preflight of the backup format is not implemented.** The standard library exposes no filesystem-capacity call and the only way to ask needs `unsafe` in a crate that forbids it. `free_space_required` is in the manifest for a caller that can ask, and a full destination fails on the write and takes the partial vault directory with it;
- **`age` unwrapping is not implemented.** [`../format/BACKUP_FORMAT_V1.md`](../format/BACKUP_FORMAT_V1.md) §2.3 permits exactly zero or one `age` layer and calls it transport only. A wrapped package is recognized and named as wrapped rather than reported as not a Chur backup, which is a different problem the user can act on;
- **incremental backup is not implemented**, which §6 of that document already calls future scope;
- **selection mode still carries five of the seven §11.4 actions**, unchanged from Phase 1: "move to album" and "more" need a picker the shell does not have;
- **no property-based test framework exists in the workspace**, unchanged from Phase 0, which is the one Gate 1 item still partly met.

## 8. Corrections to the earlier records

- [`EVIDENCE_PHASE_0.md`](EVIDENCE_PHASE_0.md) §3 said 26 of 59 invariant rows named a running target and §5 said thirty-three still named a procedure. Both were wrong by one in opposite directions: SEC-019's build-graph half had already landed, which §13 of the test plan recorded and that row did not. The counts are now 31 and 28, and the Phase 2 additions are SEC-031, SEC-034, SEC-035, and SEC-036;
- [`EVIDENCE_PHASE_1.md`](EVIDENCE_PHASE_1.md) §3 attributed `tests/argon2_cost.rs` to `chur-crypto`. It is in `chur-catalog`;
- [`EVIDENCE_PHASE_1.md`](EVIDENCE_PHASE_1.md) §7 said the panic transition has no gesture. It has one, decided in [`../product/DISCREET_MODE.md`](../product/DISCREET_MODE.md) and bound to a long press on the lock control;
- [`../format/BACKUP_FORMAT_V1.md`](../format/BACKUP_FORMAT_V1.md) §4 named `backup_id` as a context element of `chur/v1/root/backup-manifest`. [ADR-0034](../adr/0034-freeze-the-hkdf-context-element-lists.md) froze that label's list as `vault_id` alone, and the section is corrected.
