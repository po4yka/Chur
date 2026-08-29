# Security Test Plan

> **Status:** Proposed normative assurance matrix

This plan maps Chur's threat model and security invariants to executable evidence.

## 1. Test layers

```text
Cryptographic KATs and vectors
Format/parser/property tests
Catalog/transaction/fault tests
FFI misuse/race tests
KMP state/navigation/storage tests
Android/iOS platform security tests
Cross-platform compatibility tests
Backup/sync malicious-peer tests
Independent review
```

## 2. Cryptographic tests

- randomness APIs fail creation on failure;
- Argon2id vectors and parameter bounds;
- HKDF labels and outputs;
- slot wrap/unwrap and wrong AAD/key/tag;
- collection/object envelope vectors;
- XChaCha chunk vectors and nonce uniqueness;
- manifest/final-commit authentication;
- HPKE/signature vectors when sharing exists;
- secret redaction/zeroization behavior where observable.

## 3. Format corruption matrix

For every field/record:

- bit flip;
- truncation before/inside/after;
- duplicate/reordered record;
- wrong length/count/index;
- non-canonical encoding;
- unknown version/suite;
- resource limit boundary;
- substitution from another vault/object/stream/revision;
- trailing data;
- missing final commit.

Expected stable error/state is recorded.

## 4. Transaction and fault injection

Inject failure at every write, flush, rename, DB commit, and cleanup step for:

- vault initialization;
- slot create/replace/delete;
- import;
- metadata/derived revision;
- deletion/tombstone;
- collection rotation;
- catalog migration;
- backup creation/restore;
- sync operation application.

After restart, state must be safe, bounded, and reconcilable.

## 5. Session/lifecycle tests

- lock during query/read/import/export/verify/migrate;
- stale handle after lock;
- background/foreground races;
- process death at private screens;
- no private navigation restoration;
- cache clearing;
- search query text does not survive lock, process death, or navigation restoration, and reaches no catalog table;
- panic lock latency;
- simultaneous scenes/activities according to policy;
- public shell remains isolated.

## 6. Data-leakage tests

Inject unique canary values into filename, caption, EXIF, GPS, password input, and search query. Inspect:

- Room/DataStore/public preferences;
- files/cache/backup manifests;
- logs and crash reports;
- notifications/widgets/search/shortcuts;
- saved state/navigation bundles;
- FFI errors and callbacks;
- app-switcher snapshots;
- network requests.

Canaries must appear only in authenticated private storage or intended transient plaintext.

## 7. Real/decoy isolation

- separate credentials/roots/catalogs/aliases/namespaces;
- no cross-query or cache result;
- equivalent external failure;
- independent recovery/migration/deletion;
- no notification/backup setting leak;
- process death returns public locked;
- filesystem names contain no semantic labels.

## 8. Android matrix

- supported API levels and ABIs;
- TEE/StrongBox/software-backed capability;
- biometric enrollment/lock-screen changes;
- Auto Backup/device transfer/reinstall;
- Photo Picker local/cloud/large inputs;
- Media3 seeks/EOF/corruption;
- `FLAG_SECURE`/recents/external display;
- WorkManager while locked;
- storage-full and low-memory behavior.

## 9. iOS matrix

- supported devices/simulators/OS versions;
- Keychain access-control and invalidation;
- protected-data transitions;
- iCloud backup/device restore/reinstall;
- PhotosPicker/Files/iCloud-backed inputs;
- AVPlayer resource-loader seeks/cancellation;
- app-switcher/capture/external scene;
- background URL session/task while locked;
- low-memory and multi-scene behavior.

## 10. Compatibility

Required direction:

```text
create Android → open iOS → verify CLI
create iOS → open Android → verify CLI
create CLI → open both platforms
```

Cover every accepted format/migration version and backup flow.

## 11. Performance/security interaction

Test that resource controls remain enforced under:

- maximum approved object size/count;
- slow provider/network;
- repeated wrong password;
- concurrent range reads;
- low memory/storage;
- cancellation storms;
- malicious length/KDF values.

Optimization must not bypass authentication or completeness checks.

## 12. Evidence

Release evidence includes:

- exact commit/toolchains;
- vector-set digest;
- test matrix and device list;
- fuzz campaign summary;
- known failures/waivers with expiry;
- migration/restore results;
- independent review status.

## 13. Coverage mapping

Every `SEC-*` invariant of [`../security/SECURITY_INVARIANTS.md`](../security/SECURITY_INVARIANTS.md) maps below to the procedure that produces its evidence: a section of this plan, a named harness, or an explicit audit-only marker. A bare section number is a section of this plan. A concrete test target replaces the section reference in a row when it lands, and a row carries at most one, so the mapping stays readable in both directions. Missing mapping blocks the release gate for the affected feature.

A row states what would produce the evidence, not that it runs today. Whether it runs is governed by [`RELEASE_GATES.md`](RELEASE_GATES.md#enforcement): until a job executes the procedure, the row is unenforced whatever it names.

Thirty-one rows now name a test target rather than a section of this plan: nineteen at the end of Phase 0, seven more in Phase 1, and four in Phase 2 — SEC-031, SEC-034, SEC-035, and SEC-036. Each one runs in the `test`, `gradle`, `kotlin-native`, `backup-rules`, or `fuzz` job of that workflow, so all thirty-one are enforced. Every remaining row names a procedure that no job executes.

| Invariant | Evidence procedure |
| --- | --- |
| SEC-001 | §2 |
| SEC-002 | §2; §6 |
| SEC-003 | §2 |
| SEC-004 | §2 |
| SEC-005 | `chur-crypto` `kdf::tests::every_label_derives_a_distinct_key_from_one_input` |
| SEC-006 | `chur-format` `slot::tests::a_recovery_slot_and_a_password_slot_unwrap_the_same_root` |
| SEC-007 | `chur-crypto` `password::tests::parameters_below_the_floor_are_refused` |
| SEC-008 | §4 |
| SEC-009 | `chur-media` `tests/fault_injection.rs::a_slot_change_never_leaves_a_vault_nobody_can_open` |
| SEC-010 | `chur-crypto` compile-fail doctests on `secret::Secret` |
| SEC-011 | `chur-crypto` `aead::tests::two_chunk_indexes_never_share_a_nonce` |
| SEC-012 | §2 |
| SEC-013 | `chur-format` `container::tests::a_forged_chunk_header_is_rejected_without_a_key` |
| SEC-014 | `chur-format` `container::tests::opening_under_another_identity_fails` |
| SEC-015 | §3 |
| SEC-016 | `chur-format` `container::tests::a_missing_final_commit_is_object_incomplete` |
| SEC-017 | `chur-format` `tests/corruption.rs::every_bit_of_a_small_container_is_caught` |
| SEC-018 | `chur-format` `tests/migration.rs::a_container_from_a_later_version_is_unsupported_not_corrupt` |
| SEC-019 | `:shared:feature-notes` `churPublicShellIsolation` for the half a build graph can see; audit-only for the rest |
| SEC-020 | §6 |
| SEC-021 | `chur-format` `envelope::tests::the_whole_chain_recovers_the_object_key` |
| SEC-022 | §4 |
| SEC-023 | `chur-catalog` `store::tests::an_activation_commits_the_object_stream_envelope_and_revision_together` |
| SEC-024 | `chur-media` `tests/pipeline.rs::reconciliation_kills_an_import_a_crash_left_behind` |
| SEC-025 | §4; §10 |
| SEC-026 | `chur-catalog` `deletion::tests::step_two_destroys_every_envelope_and_writes_the_tombstone` |
| SEC-027 | `chur-catalog` `paths::tests::no_path_carries_a_name_that_is_not_hexadecimal` |
| SEC-028 | `chur-ffi` `tests/control_plane.rs::locking_invalidates_every_handle_the_session_owns` |
| SEC-029 | §5 |
| SEC-030 | §5 |
| SEC-031 | `chur-media` `tests/pipeline.rs::an_export_stops_where_it_is_cancelled_rather_than_at_the_end`; `chur-media` `tests/pipeline.rs::a_cancelled_import_activates_nothing_and_leaves_no_live_transaction`; `chur-media` `tests/pipeline.rs::a_cancelled_scan_records_no_verdict` |
| SEC-032 | audit-only; §5 covers only what the runtime makes observable |
| SEC-033 | §6 |
| SEC-034 | `scripts/check-backup-rules.py` for the backup-exclusion half; §6; §8; §9 for the rest, asserted against the caps in [`../security/PLAINTEXT_LIFECYCLE.md`](../security/PLAINTEXT_LIFECYCLE.md) §5 |
| SEC-035 | `chur-catalog` `tests/decoy_isolation.rs::the_two_identities_share_no_key_and_no_namespace` |
| SEC-036 | `chur-catalog` `tests/decoy_isolation.rs::a_credential_opens_only_its_own_content` |
| SEC-037 | audit-only; API review of the surface in [`../interop/FFI_CONTRACT.md`](../interop/FFI_CONTRACT.md) at each change |
| SEC-038 | `chur-format` `descriptor::tests::a_failed_slot_unwrap_still_returns_the_authentication_failure` |
| SEC-039 | repository check: no forbidden claim listed in [`../security/DECOY_VAULT.md`](../security/DECOY_VAULT.md) §10 or [`../product/DISCREET_MODE.md`](../product/DISCREET_MODE.md) "Forbidden claims" appears in `docs/`, `DESIGN.md`, `README.md`, or a localized string resource |
| SEC-040 | `chur-sync-protocol` `tests/malicious_server.rs::replay_omission_key_substitution_rollback_and_equivocation_fail_closed`; `chur-media` `tests/sync_download.rs::only_a_complete_authentic_download_can_be_published` |
| SEC-041 | §2; `chur-sync-protocol` `tests/malicious_server.rs::replay_omission_key_substitution_rollback_and_equivocation_fail_closed` |
| SEC-042 | `chur-sync-protocol` `tests/malicious_server.rs::replay_omission_key_substitution_rollback_and_equivocation_fail_closed`; `chur-media` `tests/sync_download.rs::only_a_complete_authentic_download_can_be_published` |
| SEC-043 | §2 |
| SEC-044 | §2 |
| SEC-045 | SERVER_TRUST_MODEL §10 harness for the behaviour; audit-only for the claim wording |
| SEC-046 | audit-only; design review of any deduplication proposal |
| SEC-047 | [`FUZZING.md`](FUZZING.md) §2 targets, ten of which exist |
| SEC-048 | `chur-format` `codec::tests::a_boolean_other_than_zero_or_one_is_non_canonical` |
| SEC-049 | `chur-format` `container::tests::the_seek_formula_matches_the_walked_record_offsets` |
| SEC-050 | `chur-ffi` `panic::tests::a_panic_carrying_a_value_does_not_return_it` |
| SEC-051 | `:shared:core-model` `ChurStatusTest.an_unknown_value_fails_closed` |
| SEC-052 | `chur-format` `tests/migration.rs` |
| SEC-053 | §2; §8; §9 |
| SEC-054 | audit-only; key-domain review at each catalog schema change |
| SEC-055 | `chur-catalog` `vault::tests::the_last_portable_slot_cannot_be_removed` |
| SEC-056 | `chur-format` `tests/migration.rs::a_writer_emits_only_the_current_approved_version` |
| SEC-057 | §3 |
| SEC-058 | `chur-catalog` `model::tests::only_an_original_has_no_source_content_revision` |
| SEC-059 | §5; §8; §9 |

Phase 2 gave four rows a target. SEC-031 gained the three cancellation tests that made the guarantee real rather than declared: an export used to check its flag once before it started and then run to the end, and a scan checked only between objects. SEC-035 and SEC-036 gained the isolation harness of [`../security/DECOY_VAULT.md`](../security/DECOY_VAULT.md) §11, which had never had one. SEC-034 gained the backup-exclusion half, which is now a checked-in rule file and a job that fails on a path under `vaults/` or `registry/`; the scratch caps of §5 there remain a procedure.

SEC-019, SEC-032, SEC-037, SEC-046, SEC-054, and the claim half of SEC-045 have no automated evidence. That is a stated gap, carried into the evidence package of every gated release under [`RELEASE_GATES.md`](RELEASE_GATES.md#enforcement), and not a claim of coverage. Adding or changing an invariant adds or changes its row here in the same pull request.
