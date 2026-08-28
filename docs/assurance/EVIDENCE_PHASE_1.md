# Phase 1 Evidence

> **Status:** Evidence record for the Phase 1 scope and exit criteria of [`../../ROADMAP.md`](../../ROADMAP.md). It records what is true; it approves nothing.

[`RELEASE_GATES.md`](RELEASE_GATES.md) requires every gated release to record its evidence and, explicitly, "which of its items had no enforcing job". This document is that record for Phase 1. [`EVIDENCE_PHASE_0.md`](EVIDENCE_PHASE_0.md) remains the record for Gate 0 and Gate 1 and is not restated here; §7 of it is corrected where Phase 1 made a limitation untrue.

Regenerate every number below with the commands each row names. Nothing here is transcribed from memory.

## 1. Package

| Item | Value |
| --- | --- |
| Source commit | the commit this file is read at; `git rev-parse HEAD` |
| Canonical encoding profile | `0x0001` |
| Container, descriptor, envelope, slot, backup, catalog versions | `0x0001` each, [`../format/CANONICAL_ENCODING_V1.md`](../format/CANONICAL_ENCODING_V1.md) §15.2 |
| Suite | `0x0001`; `0x0002` is the Android Keystore wrap, which the Keystore performs |
| FFI ABI | 1.2, capabilities `0x000000000000000E` |
| Catalog engine | SQLCipher 4.14.0 over SQLite 3.51.3, vendored, [ADR-0038](../adr/0038-adopt-sqlcipher-as-the-v1-catalog-engine.md) |
| Vector-set digest | `chur-cli vectors digest --dir ../test-vectors/v1`; 62 vectors, unchanged in Phase 1 |
| Rust toolchain | `rust/rust-toolchain.toml`, exact |
| Gradle toolchain | `gradle/libs.versions.toml` and `gradle/wrapper/gradle-wrapper.properties`, with the distribution SHA-256 |
| Dependency locks | `rust/Cargo.lock`; the Gradle build resolves through the version catalog |
| SBOM, checksums, signing | **absent.** Phase 1 produces a debuggable application and distributes nothing |

The three capability bits are `CHUR_CAP_OBJECT_READER`, `CHUR_CAP_SEQUENTIAL_READER`, and `CHUR_CAP_INTEGRITY_SCAN`. `chur-ffi` `lib::tests::no_capability_is_declared_before_its_surface_exists` is what keeps that value honest.

## 2. What runs

| Suite | Count | Command |
| --- | --- | --- |
| Rust | 380 tests, 0 failed | `cd rust && cargo test --workspace` |
| Kotlin | 189 tests, 0 failed | `./gradlew build -x lint` |
| Formatting | clean | `cd rust && cargo fmt --all --check` |
| Lints | clean at `-D warnings` | `cd rust && cargo clippy --workspace --all-targets -- -D warnings` |
| Vectors | 62, rebuilt and compared byte for byte | `cd rust && cargo run -p chur-cli -- vectors verify --dir ../test-vectors/v1` |

`CC=/usr/bin/cc` is required on a workstation whose `CC` is a compiler cache; [`../../DEVELOPMENT.md`](../../DEVELOPMENT.md) records why.

## 3. Phase 1 scope

The state column of [`../../ROADMAP.md`](../../ROADMAP.md) is the summary. This is where each state comes from.

| Item | Evidence |
| --- | --- |
| Notes public shell | `:shared:feature-notes` `FileNoteStoreTest`, five tests over persistence, escaping, removal, an absent file, and an unreadable one; `churPublicShellIsolation` proves the module reaches no private module |
| one private vault | `chur-catalog` `vault::tests`, twenty tests over creation, unlock, slot changes, and lock |
| creation and first run | `vault::tests::creation_interrupted_before_activation_leaves_no_openable_vault`, `an_abandoned_creation_leaves_no_directory_behind`, `a_dropped_creation_is_swept_rather_than_left_as_a_candidate` |
| password and recovery slots | `vault::tests::a_created_vault_unlocks_with_its_password`, `the_recovery_slot_offered_during_creation_unlocks_the_vault`, `a_recovery_slot_added_after_activation_unlocks_the_vault`; `chur-catalog` `tests/argon2_cost.rs` proves a password unlock performs exactly two derivations whether it succeeds or fails |
| device slot | `vault::tests::an_apple_keychain_slot_unlocks_the_vault` and `an_android_keystore_slot_unlocks_the_vault`; `chur-ffi` `tests/product_surface.rs::the_keystore_slot_enrolls_and_unlocks_through_the_boundary` drives the Android flow through the C ABI. Neither platform's key service runs in any test; see §7 |
| Rust-owned catalog | `chur-catalog` `db`, `schema`, `store`, `query`, `journal`, `deletion`, `paths`, `model`, and `vault` modules; `db::tests::a_written_catalog_is_not_readable_as_plaintext_sqlite` is the one that shows the file is encrypted at rest |
| import through pickers | `:apps:androidApp` imports through `PickVisualMedia` and the manifest declares no permission; the iOS picker is specified and not built, see §6 |
| immutable originals | the container is written once and no catalog operation rewrites one; `chur-media` `tests/pipeline.rs::an_imported_object_reads_back_byte_for_byte` and `a_committed_container_carries_the_epoch_timestamp` |
| metadata, thumbnails, previews | `tests/pipeline.rs::a_derived_asset_round_trips_and_sets_the_thumbnail_flag`, `a_derivative_above_its_long_edge_is_refused`. Both hosts decode through one `ThumbnailCache` in `:shared:app`, whose only per-platform part is the decode itself |
| timeline, albums, favorites, viewer, export | `chur-catalog` `query::tests`, eighteen tests including `paging_is_keyset_and_every_page_is_disjoint_and_complete` and `every_scope_answers_from_a_covering_index_and_never_sorts`; `tests/pipeline.rs::an_export_writes_the_original_bytes` |
| catalog search | `query::tests::search_matches_a_filename_a_caption_and_a_tag`, `a_search_query_carrying_fts_syntax_is_matched_literally`, `a_search_query_above_its_bound_is_refused` |
| lock | `:shared:core-vault` `VaultStateTest` and `VaultRepositoryHostTest`, including `the_idle_timer_locks_only_once_the_timeout_has_passed` and `a_call_refreshes_the_idle_clock`; `chur-ffi` `tests/control_plane.rs::locking_invalidates_every_handle_the_session_owns` |
| app-switcher privacy | `FLAG_SECURE` and a cover on Android, a cover on iOS. Neither is under test: no job runs an instrumented device |
| interrupted-import recovery | `chur-catalog` `journal::tests`, sixteen tests over the ordering of [`../format/OBJECT_CONTAINER_V1.md`](../format/OBJECT_CONTAINER_V1.md) §14.2; `chur-media` `tests/fault_injection.rs::an_interrupted_import_is_recoverable_and_exposes_no_partial_object`, which walks every point of that ordering |
| integrity inspection | `tests/pipeline.rs::an_integrity_scan_confirms_an_intact_object`, `an_absent_container_is_quarantined_rather_than_corrupt`, `a_flipped_ciphertext_bit_is_proven_corruption` |

## 4. The boundary

[`../interop/FFI_CONTRACT.md`](../interop/FFI_CONTRACT.md) §6.2 froze a control plane that could not create a vault, mark a favourite, delete an object, or read a thumbnail. §6.5 adds the product surface those flows need and §6.6 adds the three exports the Android Keystore family needs, each raising the ABI minor version because a host built against 1.0 still links.

| Fact | Value | Test |
| --- | --- | --- |
| C functions declared in `chur.h` | 50 | `chur-ffi` `tests/header.rs::every_declared_function_is_exported` |
| JNI functions in `chur-jni` | 50 | `chur-jni` `tests/surface.rs`, which compares both directions |
| Status codes | every registered code, and no other | `tests/header.rs::the_header_carries_every_registered_status_and_no_other` |

The C harness of the `abi` job links the real static library, so the symbols are proved to exist with C linkage and the declared types rather than merely to compile.

`:shared:core-ffi` `ChurVaultHostTest` is the only place both halves of the boundary run in one process, and it earned its keep: it found that `ChurStatus.from(0)` folded success into `INTERNAL_FAILURE`, and that a ranged read handed the reader a buffer sized to the whole range rather than to what was left. Both were real defects, and the Rust tests had not caught either. It now also drives the §6.6 Keystore round trip, which is where a Kotlin decoder that disagreed with the Rust encoder would show.

## 5. Enforcing jobs

`.github/workflows/rust.yml` runs eleven jobs on every pull request and on the default branch. The list is unchanged from [`EVIDENCE_PHASE_0.md`](EVIDENCE_PHASE_0.md) §4, with one addition inside an existing job: the `gradle` job now also runs `:shared:feature-notes:churPublicShellIsolation`.

## 6. Exit criteria, and what is missing

| Criterion | State |
| --- | --- |
| fault injection matching Gate 2 | met for the four flows Gate 2 names. `chur-media` `tests/fault_injection.rs` declares the ordered interruption points of initialization, import, key-slot change, and an unreadable vault, and walks every one: an interrupted creation is never openable and is swept, an interrupted import appears in no scope and the next unlock reclaims it, every observable state around a slot change opens with exactly the credentials that should open it, and a catalog from the future is `MIGRATION_REQUIRED` rather than corruption. The media, large-file, and decoy paths are Phase 2 |
| no private data in public storage or navigation state | met by construction: the public shell's module declares no dependency on a private module and `churPublicShellIsolation` enforces it; the routes of `AppRoute` carry no object identifier; both hosts disable backup for the directories Chur writes into |
| platform-key invalidation and recovery on supported devices | **outstanding.** No job runs on a device from [ADR-0017](../adr/0017-freeze-the-supported-device-set.md), and the Android side has no unlock factor to invalidate |
| independent review | **outstanding.** [`SECURITY_REVIEW_SCOPE.md`](SECURITY_REVIEW_SCOPE.md) defines it and none has been commissioned |

## 7. Known limitations

Stated plainly, because the reader of this file is deciding whether to trust the vault with photographs.

- **no test has ever called a real Keystore or Keychain.** Both device slots are implemented end to end and both are exercised with a stand-in cipher, because Rust neither performs nor verifies the Keystore's AEAD and a workstation has no Keystore to call. What is proved is that the alias, the AAD, the nonce, and the wrapped bytes cross the boundary unchanged and that the unwrapped root opens the vault. What is not proved is that `AndroidDeviceUnlock` drives the platform correctly, and that needs a device;
- **the Android Keystore slot exchanges root bytes with the host.** It is the one family that does, [ADR-0041](../adr/0041-the-android-keystore-slot-exchanges-root-bytes.md) argues why and what it costs, and the Apple model that avoids it is the better one and is blocked on a frozen v1 body;
- **the iOS host application is a specification.** [`../../apps/iosApp/README.md`](../../apps/iosApp/README.md) states what the Xcode project must do; the project is not in this repository. The framework links and exports its entry point, which the `kotlin-native` job proves, and nothing proves the application it belongs to;
- **no test runs on a device.** `FLAG_SECURE`, the privacy covers, the Keychain, the Keystore, and every performance number are workstation or simulator evidence;
- **selection carries five of the seven actions of `DESIGN.md` §11.4.** The count, select all, export, delete from this vault, and remove from album are there. "Move to album" and "more" are not, because both need a picker this shell does not have, and an action that opens nothing would be worse than one that is absent;
- **the panic transition has no gesture.** It runs, and [`../product/DISCREET_MODE.md`](../product/DISCREET_MODE.md) reserves the choice of gesture to itself and has not made it. *Phase 2 made it: a long press on the lock control, with the same action exposed as a custom accessibility action. See [`EVIDENCE_PHASE_2.md`](EVIDENCE_PHASE_2.md) §8*;
- **the idle clock measures vault calls, not attention.** A person reading one photograph without touching the boundary is idle by this definition and the session locks. `LockPolicy` is where that decision lives if it should change;
- **`chur-jni` and `chur-ffi` are the two crates that are not `unsafe_code = "forbid"`.** [ADR-0040](../adr/0040-add-a-rust-jni-adapter-crate.md) explains why the JNI adapter is one of them, and [`../DEPENDENCY_POLICY.md`](../DEPENDENCY_POLICY.md) records both;
- **no independent review has run.** Nothing here is a claim of production security, which [`../../ROADMAP.md`](../../ROADMAP.md) lists among the explicit exclusions of Phase 1.

## 8. Corrections to the Phase 0 record

Three statements of [`EVIDENCE_PHASE_0.md`](EVIDENCE_PHASE_0.md) stopped being true during Phase 1. That file's §7 now carries the corrections; they are listed here so a reader of one file finds the other.

1. **"six crates are documentation only"** — `chur-catalog`, `chur-media`, `chur-ffi`, and `chur-jni` hold the vault, the pipeline, the boundary, and the Android adapter. `chur-sync-protocol` is still documentation only, which is Phase 3;
2. **"`chur_capabilities` returns zero"** — it returns `0x0E`, and §1 above says which bits;
3. **the "recovery and process-death flows" row of §3** — the import journal, the descriptor transaction, and resumption after process death are implemented and tested. Gate 1's other outstanding row, property-based testing, is unchanged: there is still no property-based framework in the workspace.
