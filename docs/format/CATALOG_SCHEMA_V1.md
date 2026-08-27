# Private Catalog Schema v1

> **Status:** Proposed normative logical schema; SQLCipher is the preferred physical implementation pending prototype validation

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

- catalog schema version;
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
integrity summary
```

Physical path is an opaque random store identifier, not a user filename.

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

Deletion creates a tombstone before physical garbage collection so future sync or crash recovery cannot resurrect removed objects. Tombstone retention and crypto-erasure are distinct:

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
- backups copy through a Chur backup format, not raw live pages by default.

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
