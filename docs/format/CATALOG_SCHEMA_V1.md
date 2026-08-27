# Private Catalog Schema v1

> **Status:** Proposed normative logical schema. The v1 catalog has two representations and no third: a physical SQLCipher schema for the live local database, and the canonical serialization of [`CANONICAL_ENCODING_V1.md`](CANONICAL_ENCODING_V1.md) when exported into a portable backup. SQLCipher build, linkage, WAL, migration, and performance validation remain outstanding.

The private catalog is Rust-owned. It stores queryable private metadata, object and collection relationships, key envelopes, journals, integrity state, and future sync projections. Room and DataStore never open or mirror it.

## 1. Logical entities

```text
VaultState
SecurityCollection
CollectionKeyEnvelope
MediaObject
ObjectStream
ObjectKeyEnvelope
MetadataRevision
DerivedAsset
Album
AlbumMembership
Favorite
Tag / ObjectTag
ImportTransaction
ScratchEntry
IntegrityRecord
MigrationState
Tombstone
SyncProjection (future)
```

## 2. Vault state

Tracks:

- `catalog_format_version`, `0x0001` for v1, allocated in [`CANONICAL_ENCODING_V1.md`](CANONICAL_ENCODING_V1.md) §15.2 and mirrored by the vault descriptor in [`VAULT_DESCRIPTOR_V1.md`](VAULT_DESCRIPTOR_V1.md) §5; a disagreement between the two is `CATALOG_CORRUPT`;
- catalog generation;
- active migration transaction;
- object-store reconciliation checkpoint;
- last successful integrity checkpoint;
- feature capability flags that affect schema semantics.

It does not store plaintext root secret or unwrapped keys.

## 3. Security collections

Fields conceptually include:

```text
collection_id
current_epoch
policy_type
created_revision
status
```

Collection names/descriptions are private metadata. A logical album may reference a collection but is not automatically a key domain.

## 4. Collection-key envelopes

Collection keys are wrapped under a root-derived collection-envelope key or future recipient grants. Catalog rows include version, epoch, suite, nonce, ciphertext, generation, and status.

Unwrapped collection keys exist only in the active Rust session/key cache.

## 5. Media objects

Conceptual fields:

```text
object_id
object_generation
primary_stream_id
creation/import metadata (private)
logical media kind (private)
state: ACTIVE / DELETING / TOMBSTONED / CORRUPT
integrity_summary
```

Physical path is an opaque random store identifier, not a user filename.

### 5.1 Lifecycle and integrity states

An object row carries two independent enums and this specification is the authority for both. `state` is the lifecycle:

| `state` | Meaning |
| --- | --- |
| `ACTIVE` | the container and final commit are durably written and the object is listable |
| `DELETING` | deletion has started and the object is no longer listable, §14.1 |
| `TOMBSTONED` | every object-key envelope is destroyed and a tombstone row exists, §14.1 |
| `CORRUPT` | a structural or cryptographic check proved the object unusable and no repair path remains |

`integrity_summary` is the verification verdict, and it is meaningful only while `state` is `ACTIVE`:

| `integrity_summary` | Meaning |
| --- | --- |
| `UNVERIFIED` | committed, never verified on this device |
| `VERIFYING` | a scan of §13 is in progress |
| `RANGE_VERIFIED` | some ranges authenticated; complete verification has not run |
| `COMPLETE_VERIFIED` | manifest, every expected chunk, lengths, ordered commitment, and final commit are valid |
| `INCOMPLETE` | required records are absent; resume or restore may still succeed |
| `QUARANTINED` | the container is absent or unreadable, so the object must not be presented or silently retried |
| `UNSUPPORTED` | `container_version` or `suite_id` is outside the range this build reads |
| `MIGRATION_REQUIRED` | readable, but a migration must run before use |

Proven corruption is a lifecycle change, not an integrity value: a check that proves corruption sets `state` to `CORRUPT`. Quarantine is the opposite case and stays an integrity value, because an absent container is a fact about this device rather than a verdict on the object. Two names used by lower-authority documents are values of neither enum: `Incoming` is the `stage` of an `ImportTransaction` row, §11, and a purged object is the absence of the row after garbage collection, §14.1.

Transitions: `ACTIVE` to `DELETING` to `TOMBSTONED` is the only deletion path and it never reverses; `ACTIVE` to `CORRUPT` is terminal; `integrity_summary` changes only while `state` is `ACTIVE`.

The user-facing states of [`../../DESIGN.md`](../../DESIGN.md) §20.1 are derived from the pair and are never stored:

| `state` | `integrity_summary` | Presented state |
| --- | --- | --- |
| `ACTIVE` | `UNVERIFIED` or `RANGE_VERIFIED` | Verification recommended |
| `ACTIVE` | `VERIFYING` | Verification in progress |
| `ACTIVE` | `COMPLETE_VERIFIED` | Verified |
| `ACTIVE` | `INCOMPLETE` | Incomplete |
| `ACTIVE` | `QUARANTINED` | Quarantined |
| `ACTIVE` | `UNSUPPORTED` | Unsupported format |
| `ACTIVE` | `MIGRATION_REQUIRED` | Migration required |
| `CORRUPT` | any | Corrupt |
| `DELETING` or `TOMBSTONED` | any | not presented |

## 6. Object streams

Each original or derived stream records:

- stream ID and kind;
- stream revision;
- source content revision when derived;
- opaque container path ID;
- container version/suite;
- committed ciphertext size;
- complete-verification state and timestamp;
- final-commit commitment reference.

No active row points to a temporary uncommitted container.

## 7. Object-key envelopes

Rows follow [`OBJECT_KEY_ENVELOPE_V1.md`](OBJECT_KEY_ENVELOPE_V1.md). Uniqueness must prevent ambiguous active generations.

## 8. Metadata revisions

Private metadata is revisioned rather than mutating ciphertext in place. Fields may include:

- original filename/path source;
- capture/import times;
- MIME/UTType;
- dimensions/duration;
- EXIF/GPS;
- captions, ratings, flags;
- codec-specific normalized properties.

The logical model may store queryable plaintext inside an unlocked SQLCipher database, but persisted pages remain encrypted and the database key is session-scoped.

## 9. Albums and memberships

Albums are logical groupings. Membership operations are revisioned and future-syncable. A many-to-many object↔album relationship does not create duplicate object keys unless collection access policy differs.

## 10. Derived assets

Asset kinds include thumbnail, preview, poster frame, waveform, OCR, faces, and embeddings. Each binds to object and source content revision so stale derivatives cannot be presented as current.

## 11. Import journal

The import journal is a catalog table, not a separate file or directory. An `ImportTransaction` row is the journal record of [`OBJECT_CONTAINER_V1.md`](OBJECT_CONTAINER_V1.md) §14.1, and §14.2 to §14.4 govern its durability ordering, resume, and abandonment. A row may also carry a source capability summary without a private path, and the expected source length when it is known.

The journal shares the catalog transaction domain, so a chunk-index reservation and the catalog state that activates the object cannot disagree after a crash. A reservation is durable when its catalog transaction commits under a synchronization mode that survives power loss, not only process loss; a mode that only flushes to the operating system does not satisfy §14.2 step 2.

## 12. Scratch journal

Tracks opaque protected plaintext scratch entries for cleanup. It must not store user-facing filename unless encrypted within the private catalog and required for export semantics.

## 13. Integrity records

Record structural and cryptographic status with stable codes, not secret error strings. Integrity scans can be partial/range or complete; only complete verification updates complete status.

## 14. Tombstones

Deletion creates a tombstone before physical garbage collection so future sync or crash recovery cannot resurrect removed objects. The retention rule is normative in [`../sync/OPERATION_LOG.md`](../sync/OPERATION_LOG.md) §11. Tombstone retention and crypto-erasure are distinct:

- key envelopes may be destroyed according to policy;
- ciphertext cleanup may lag;
- remote/backup copies may persist.

## 15. Physical SQLCipher direction

Proposed:

- Rust opens SQLCipher directly;
- key derived from root catalog domain;
- WAL/journal uses encrypted database configuration;
- file placed in private protected storage;
- connection closes before key zeroization on lock;
- no Room schema or DAO for private data;
- a portable backup carries the canonical catalog export named in [`BACKUP_FORMAT_V1.md`](BACKUP_FORMAT_V1.md) §2, and never raw SQLCipher pages, WAL segments, or a file copy of the live database. Raw pages are a local storage detail: they carry one client's page format and SQLCipher build options into the package, and no other platform build is required to read them.

Prototype must validate Android/iOS build size, linkage, WAL behavior, migration, performance, and backup correctness.

## 16. Indexes and leakage

Within an unlocked encrypted DB, indexes may support timeline, albums, tags, and metadata search. Persisted database/page sizes still leak approximate scale. Index names/schema should avoid user labels but schema itself is not assumed secret after binary analysis.

## 17. Transactions

Atomic boundaries are required for:

- object commit plus envelope/catalog activation;
- password/slot replacement state references;
- collection epoch rotation;
- metadata revision activation;
- deletion/tombstone/key-envelope removal;
- migrations;
- sync operation application.

Filesystem and DB commit order is specified per operation with recovery reconciliation.

## 18. Migrations

Schema versions are values of the `catalog_format_version` namespace of [`CANONICAL_ENCODING_V1.md`](CANONICAL_ENCODING_V1.md) §15.2. They begin at `0x0001` and increase by one with no gap and no branch, so "prior supported version" below is always exactly one step back and a multi-step upgrade runs each step in order.

Every schema version has:

- forward migration from prior supported version;
- crash checkpoints;
- backup/space requirements;
- deterministic fixtures;
- downgrade policy;
- reconciliation with object/container versions.

Skipping untested version steps is forbidden.

## 19. Sync projection

Raw SQLCipher pages are not synced. Rust emits canonical encrypted operations from logical changes. The local catalog is a materialized state; the operation log is a separate protocol.

## 20. Test requirements

- schema constraints and uniqueness;
- transaction crash at each write/fsync/commit point;
- orphan object and dangling row reconciliation;
- collection rotation and multiple envelopes;
- metadata/derived revision invalidation;
- tombstone retention/GC;
- lock closes DB and invalidates queries;
- SQLCipher WAL/backup inspection;
- migration matrix and corruption cases;
- public storage contains no mirrored private rows.

## 21. Limits

Catalog policy bounds, enforced on insert and re-checked on restore:

- at most 1000000 media objects per vault;
- at most 16 streams per object: one original and at most 15 derived;
- at most 1024 security collections per vault;
- at most 64 object-key envelopes per object, of which at most 4 are active at once;
- at most 8 collection-key envelopes per collection epoch, of which at most 1 is active;
- at most 10000 albums per vault and at most 100000 memberships per album;
- at most 10000 tags per vault and at most 128 tags per object;
- at most 1024 metadata revisions per object;
- at most 128 concurrent `ImportTransaction` rows.

A value above any bound is `RESOURCE_LIMIT_EXCEEDED`. These are catalog policy, not encoded field widths, so raising one is a `catalog_format_version` change only when it changes a stored width.
