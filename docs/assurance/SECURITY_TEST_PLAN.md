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
- future sync operation application.

After restart, state must be safe, bounded, and reconcilable.

## 5. Session/lifecycle tests

- lock during query/read/import/export/verify/migrate;
- stale handle after lock;
- background/foreground races;
- process death at private screens;
- no private navigation restoration;
- cache clearing;
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

Each `SEC-*` invariant has at least one named automated test or audit procedure. Missing mapping blocks the release gate for the affected feature.
