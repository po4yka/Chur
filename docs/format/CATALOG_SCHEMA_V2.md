# Private Catalog Schema v2

> **Status:** Normative Phase 3 schema extension

Catalog v2 is catalog v1 plus the durable encrypted-sync state below. Every v1 table, constraint, index, transaction boundary, parser rule, and limit remains unchanged unless this document says otherwise. Raw SQLCipher pages are never a sync format.

## 1. Version and migration

`catalog_format_version` is `0x0002`. The only forward migration is v1 to v2, under [ADR-0049](../adr/0049-add-sync-state-in-catalog-v2.md). It creates the tables and indexes below in one SQLCipher transaction and changes no existing materialized row.

The vault descriptor remains `VaultDescriptorV1`. Its catalog sub-descriptor records version 2 after the crash-safe descriptor/catalog migration commits.

## 2. Sync state

`sync_state` has at most one row:

```text
only_row                         INTEGER, primary key and exactly 1
membership_generation           non-negative INTEGER
membership_commitment           32-byte BLOB
latest_own_checkpoint_commitment nullable 32-byte BLOB
```

No row means sync has not been provisioned. A row is created atomically with generation-1 membership.

## 3. Membership and device identities

`sync_membership_records` stores the accepted canonical chain:

```text
membership_generation  INTEGER primary key
record_kind             INTEGER, 1 enrollment or 2 revocation
device_id               16-byte BLOB
commitment              unique 32-byte BLOB
record                   canonical signed BLOB
```

`sync_devices` is the current projection:

```text
device_id                 16-byte BLOB primary key
signing_public_key         32-byte BLOB
hpke_public_key            32-byte BLOB
status                     INTEGER, 1 active or 2 revoked
membership_generation      INTEGER
revoked_sequence           nullable non-negative INTEGER
revoked_digest             nullable 32-byte BLOB
```

The two revocation columns are both null for an active device and both present for a revoked device.

`sync_signing_keys(device_id, membership_generation, public_key)` retains every accepted signing key. Its primary key is `(device_id, membership_generation)`.

`sync_identity_envelopes(device_id, identity_generation, active, recovery_only, body)` stores root-wrapped private identity records. The primary key is `(device_id, identity_generation)`, and a partial unique index permits one active envelope per device. One device-local catalog carries at most one active private identity across all device identifiers. A writer authenticates its recovery-only envelope under the vault root, matches both public keys to active membership, and advances the per-device identity generation by exactly one before it retires the previous local identity.

Catalog v2 has no selector or derived operation-key table. [ADR-0051](../adr/0051-derive-sync-operation-keys-and-selectors.md) derives the root selector and one selector per retained collection-key epoch after unlock. The catalog already stores the wrapped source keys and accepted operation bytes; persisting derived values would create duplicate authority.

## 4. Accepted operation log and floors

`sync_operations` stores only fully authenticated accepted records:

```text
device_id      16-byte BLOB
device_sequence non-negative INTEGER
operation_id   unique 16-byte BLOB
digest         32-byte BLOB
record         canonical signed BLOB
primary key    (device_id, device_sequence)
```

The transaction that first applies an operation inserts this row and updates its materialized catalog state together. Pending gaps and causes remain outside accepted state and are fetched again.

`sync_heads` stores accepted heads and checkpoint floors:

```text
device_id       16-byte BLOB primary key
accepted_sequence nullable INTEGER
accepted_digest nullable 32-byte BLOB
floor_sequence  nullable INTEGER
floor_digest    nullable 32-byte BLOB
```

Each sequence/digest pair is either both null or both present. A floor is not cleared until the accepted chain reaches the exact sequence and digest.

## 5. Forks and checkpoints

`sync_forks(device_id, state, accepted_record, conflicting_record)` stores one unresolved fork per device. `state` is 1 detected or 2 acknowledged. Resolution deletes the row only after checkpoint reconciliation or accepted device revocation; evidence is exported to the user's incident record before deletion.

`sync_checkpoints` stores the latest accepted checkpoint per issuer:

```text
issuer_device_id  16-byte BLOB primary key
commitment        unique 32-byte BLOB
record            canonical signed BLOB
accepted_at_ms    non-negative INTEGER
own               INTEGER, strict 0 or 1
```

`accepted_at_ms` is local UX metadata and never ordering authority.

## 6. Collection epoch rotation

`sync_rotations` stores one current or completed rotation per collection:

```text
collection_id         16-byte BLOB primary key, references collections
target_epoch          non-negative INTEGER
owner_device_id       16-byte BLOB, references sync_devices
membership_generation non-negative INTEGER
accepted_at_ms        non-negative INTEGER
collection_envelope   canonical CollectionKeyEnvelopeV1 BLOB
completed             INTEGER, strict 0 or 1
```

Completion is true only after the next-missing query of ADR-0047 returns no active old-epoch object envelope. The local accepted-at value controls 24-hour takeover; no server timestamp enters this table.

`sync_object_envelope_epochs` is the indexed projection needed for that query:

```text
object_id           16-byte BLOB primary key, references objects
collection_id       16-byte BLOB, references collections
collection_epoch    non-negative INTEGER
envelope_generation non-negative INTEGER
```

Catalog v1 stored an object envelope as one canonical BLOB and could not query its epoch. Migration decodes the highest-generation active envelope of every object and inserts this projection in the same transaction that installs v2. A malformed envelope aborts migration. Every later envelope replacement updates the canonical BLOB and this projection together.

## 7. Indexes and limits

Required indexes:

- `sync_operations_by_id(operation_id)` is unique;
- `sync_operations_by_digest(device_id, digest)` supports chain and checkpoint reconciliation;
- `sync_membership_by_device(device_id, membership_generation)` supports identity history;
- `sync_old_epoch_objects(collection_id, collection_epoch, object_id)` indexes the envelope-epoch projection; no cursor table exists;
- one partial unique active identity-envelope index per device.

Canonical record parsers enforce their wire bounds before insert. A sync response remains limited to 256 operations and 16 MiB, locked staging to 4096 records, 64 MiB, and seven days. SQLCipher errors, invalid column widths, broken null pairs, and invalid discriminants fail as `CATALOG_CORRUPT`; they do not become default states.

## 8. Atomic boundaries

The following are single catalog transactions:

- provision initial membership, device projection, signing-key history, and sync state;
- accept enrollment or revocation and advance the membership commitment;
- accept and apply one operation, including its materialized projection and head;
- persist fork evidence before returning `SYNC_CHAIN_FORK`;
- accept a checkpoint and raise all floors;
- activate a collection epoch and its rotation owner;
- replace one object envelope during eager rewrap and recompute completion.

Locked staging performs none of these transactions.

## 9. Tests

- v1-to-v2 migration and every crash boundary of ADR-0049;
- constraints for key widths, discriminants, null pairs, uniqueness, and foreign keys;
- atomic operation application and rollback on a failing projection write;
- durable fork state and checkpoint floors after reopen;
- recovery-only identity cannot sign an ordinary operation;
- next-missing rewrap survives reverse-order concurrent completion;
- a locked staging write changes no catalog page or accepted head.
