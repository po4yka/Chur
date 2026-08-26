# Migration Policy

> **Status:** Proposed normative compatibility and transaction policy

Migrations preserve confidentiality, integrity, recoverability, and canonical meaning across key-slot, descriptor, catalog, object, backup, FFI, and sync versions.

## 1. Version domains

Version independently:

```text
canonical encoding profile
vault descriptor
key slots/password profile
catalog schema
object-key envelope
object container
backup format
FFI ABI
sync operations/protocol
collection grants
```

A dependency or application version is not a format version.

## 2. Rules

- never reinterpret accepted v1 bytes without a new version;
- readers fail closed on unknown critical versions/suites;
- writers emit only current approved versions;
- deprecated formats may be read solely to migrate;
- migration steps are explicit and sequential unless a direct path is tested;
- no downgrade writes older formats;
- byte changes require vectors and specification updates.

## 3. Migration transaction

```text
preflight and authenticate source
estimate space/resource requirements
create encrypted checkpoint/backup where policy permits
mark descriptor/catalog MIGRATING
write new temp state
verify new state completely
atomically switch active generation
retain old state until commit/cleanup policy
clear migration marker
```

A crash at any step must reopen into old valid state, resumable migration, or explicit recovery—not ambiguous mixed state.

## 4. Catalog migrations

- run inside Rust-owned catalog layer;
- use schema version table and migration transaction ID;
- preserve object/envelope references;
- reconcile filesystem after commit;
- test real/decoy independently;
- do not expose private rows to KMP during migration;
- close/lock on failure.

## 5. Object/container migrations

Prefer lazy migration when old object remains safely readable and immutable. Options:

- metadata/envelope rewrap only;
- copy-on-write new container then atomic reference switch;
- full decrypt/re-encrypt for key/algorithm construction changes.

Never mutate committed container in place.

## 6. Key and algorithm migration

- password parameter upgrade creates a new slot generation;
- collection rotation creates new epoch and rewraps object keys;
- root rotation rewraps all root-domain envelopes transactionally;
- AEAD/container change produces new object stream/container version;
- old keys are destroyed only after complete inventory verification and backup policy.

## 7. Backup before migration

A backup may be recommended/required for destructive or large migration, but its creation must not expose plaintext. Backup compatibility with source version is tested. The app must not claim safety if insufficient storage prevents both rollback and verified copy-on-write.

## 8. Downgrade

Older applications must refuse newer unsupported vaults rather than modifying them. Store/application rollback cannot roll back encrypted data safely unless an authenticated compatible snapshot is deliberately restored.

## 9. Sync coordination

Future migrations distinguish:

- local materialized catalog schema change, invisible to wire peers;
- wire operation version change;
- object-container version coexistence;
- collection key/suite migration;
- minimum client capability policy.

A server does not decide client cryptographic migration.

## 10. Test matrix

For every supported source→target step:

- empty/minimal/maximal catalog;
- mixed object/slot/container versions;
- real and decoy;
- missing/corrupt objects;
- crash/failure at every checkpoint;
- insufficient storage/memory;
- cancellation/background/process death;
- backup restore before/after migration;
- Android/iOS/CLI equivalence;
- no nonce reuse/key loss;
- downgrade rejection.

## 11. Release policy

A migration ships only when:

- source/target specs and vectors exist;
- deterministic fixtures pass;
- fault injection passes;
- recovery/rollback behavior documented;
- performance/space budget measured;
- security review covers key/format changes;
- user-facing copy explains required downtime/risk without exposing private data.
