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
- capture and import times, §8.1;
- MIME/UTType;
- dimensions/duration;
- EXIF/GPS;
- captions, ratings, flags;
- codec-specific normalized properties.

The logical model may store queryable plaintext inside an unlocked SQLCipher database, but persisted pages remain encrypted and the database key is session-scoped.

### 8.1 Timestamp provenance

Two times are stored per object and neither is trusted:

- **capture time** comes from the source's provider metadata, normalized into the canonical model of [`../interop/MEDIA_PIPELINE.md`](../interop/MEDIA_PIPELINE.md) §4 from hints that §3 of the same document says Rust must not trust. It is parsed, range-checked, and stored as the `u64` milliseconds of [`CANONICAL_ENCODING_V1.md`](CANONICAL_ENCODING_V1.md) §9. It is never corrected against the device clock, because a correction would silently rewrite the only record of when a photograph was taken;
- **import time** is read from the device clock when the catalog transaction of §17 commits. A wrong device clock produces a wrong import time and Chur does not detect it. Nothing cryptographic depends on either value, and neither is ordering proof.

When capture time is absent or fails its range check, it is set equal to import time and `capture_time_substituted` is set on the row, so the timeline still orders the object and the interface can decline to present a capture date it does not have.

The timeline sorts on capture time descending, with import time and then `object_id` as tie-breaks. The order is therefore total and identical on every device without any clock being trusted.

Both values are private metadata. They live inside the encrypted catalog and appear in no filename, no path, and no filesystem timestamp: committed containers carry a normalized modification time under [`OBJECT_CONTAINER_V1.md`](OBJECT_CONTAINER_V1.md) §14.

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

- key envelopes are destroyed at step 2 of §14.1, which is the erasure moment;
- ciphertext cleanup may lag, because steps 3 to 6 of §14.1 carry no security property;
- remote/backup copies may persist.

Retention: in a vault with no enrolled peer device a tombstone may be discarded once garbage collection for its object has completed, because nothing local can resurrect an object whose row, envelopes, and containers are all gone. Every other vault follows the membership rule of [`../sync/OPERATION_LOG.md`](../sync/OPERATION_LOG.md) §11, which stays normative for retention wherever a peer exists.

### 14.1 Deletion transaction

Deletion runs in this order and no other:

1. one catalog transaction sets the object row's `state` to `DELETING`; the object stops being listable from this point;
2. one catalog transaction destroys every object-key envelope for the object, writes the tombstone row, and sets `state` to `TOMBSTONED`. This transaction is the erasure moment: once it commits durably the `ContentKey` is unrecoverable and every remaining copy of the container, including WAL pages, local backup manifests, and queued sync operations, is ciphertext no reachable key opens;
3. unlink the derived-asset containers of §10;
4. unlink the original container;
5. delete the scratch entries of §12 that the object owns;
6. delete the object row, leaving the tombstone.

Steps 1 and 2 are the atomic boundary §17 requires for deletion. Steps 3 to 6 are garbage collection: each is idempotent, none is required for the crypto-erasure claim of [`../security/SECURITY_INVARIANTS.md`](../security/SECURITY_INVARIANTS.md) SEC-026, and a crash inside them loses no security property.

Garbage collection runs at the first unlock of a session and again after each deletion that session performs. It never runs while locked, because it needs the catalog key that lock has already zeroized. A run sweeps every row left in `DELETING` or `TOMBSTONED`, so an interrupted deletion always completes rather than being repaired.

Recovery of a half-deleted object rolls forward and never back, because rolling back would return to `ACTIVE` an object whose key may already be gone:

- `state` `DELETING` with envelopes present: step 2 never committed, so step 2 is run now;
- `state` `DELETING` or any other value with every envelope already destroyed: step 2 is completed, and the row is never returned to `ACTIVE`;
- `state` `TOMBSTONED` with containers still present: steps 3 to 6 are re-run;
- a container in the committed namespace with no object row and no `ImportTransaction` row is deleted; the import-temporary case is §14.4 of [`OBJECT_CONTAINER_V1.md`](OBJECT_CONTAINER_V1.md).

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

## 16. Query surface, indexes, and leakage

### 16.1 Object projection

One projection serves the timeline, album, favourite, tag, and search screens. It is the only object shape a page returns, it is fixed-width so it fits the caller buffer of [`../interop/FFI_CONTRACT.md`](../interop/FFI_CONTRACT.md) §6.2, and it carries no free-form user text:

```text
ObjectProjectionV1
    object_id                  16 bytes, opaque
    primary_stream_id          16 bytes, opaque
    media_kind                 u16
    capture_time_ms            u64
    import_time_ms             u64
    capture_time_substituted   u8    §8.1
    plaintext_size             u64
    width                      u32   0 when not applicable
    height                     u32   0 when not applicable
    duration_ms                u64   0 for a still
    favorite                   u8
    state                      u8    §5.1
    integrity_summary          u8    §5.1
    thumbnail_ready            u8
```

Filename, caption, EXIF, GPS, album names, and tag names are not in the projection. A detail screen fetches them for one object, so a page of 200 rows never carries 200 filenames across the boundary.

### 16.2 Query and paging

```text
ObjectQueryV1
    scope     timeline | album(album_id) | favorites | tag(tag_id) | search(terms) | quarantine
    kinds     u16 bitmask of media kinds, 0 for every kind
    sort      capture_desc (default) | capture_asc | import_desc
    cursor    opaque, empty for the first page
    limit     1 to 500, default 200
```

A page returns the projections, a `total_count` for the scope, and a `next_cursor` that is empty when the scope is exhausted. A `limit` above 500 is `RESOURCE_LIMIT_EXCEEDED`.

Paging is keyset, never offset. The cursor is the sort value of the last row returned followed by its `object_id`, and the next page selects the rows ordered strictly after that pair. Every page therefore costs the same whatever its position, and a page boundary stays valid while rows are inserted and deleted. The consequence is stated rather than hidden: a row whose sort key changes between two pages may be returned twice or skipped, so a caller that observes a change in `catalog_generation` restarts the scope instead of continuing the cursor. A cursor that does not parse, or that was issued for a different scope or sort, is `INVALID_INPUT`.

Rows with `state` `DELETING` or `TOMBSTONED` are never returned. A row with `integrity_summary` `QUARANTINED` is returned only in the `quarantine` scope, which is what keeps it out of the ordinary library under [`../../DESIGN.md`](../../DESIGN.md) §20.3.

### 16.3 Required indexes

Each one covers a scope under a sort, so a page is a range scan and never a sort:

- `objects(state, capture_time_ms, object_id)` and `objects(state, import_time_ms, object_id)`;
- `album_memberships(album_id, capture_time_ms, object_id)`;
- `favorites(capture_time_ms, object_id)`;
- `object_tags(tag_id, capture_time_ms, object_id)`;
- `object_streams(object_id, stream_kind)`;
- `derived_assets(object_id, kind, source_content_revision)`;
- `object_key_envelopes(object_id, status)`;
- `import_transactions(stage)`;
- `tombstones(authored_ms)`.

`capture_time_ms` is duplicated into the album-membership and favourite rows so those scopes do not join before sorting. The copy is rewritten in the same transaction that activates a metadata revision changing the capture time, per §8.1, so it cannot drift.

### 16.4 Search

v1 search is a SQLite FTS5 table inside the same SQLCipher database. It is not `LIKE` scanning and not a separate index file. `LIKE '%term%'` cannot use any index, so it is a full scan of every metadata revision and misses the first-content budget of [`../assurance/PERFORMANCE_BUDGETS.md`](../assurance/PERFORMANCE_BUDGETS.md) at the object limit of §21; FTS5 is a compile-time module of the SQLite that SQLCipher already builds, so it adds no dependency and its pages get the same at-rest encryption as every other page.

- the indexed columns are the original filename, the caption, and the object's tag names; nothing else is tokenized;
- the tokenizer is `unicode61` with `remove_diacritics 2`, and a prefix index of 2 and 3 characters serves as-you-type queries;
- a row is reindexed in the transaction that activates a metadata revision or changes a tag, so the index never outlives the revision it describes;
- the `SearchKey` of [`../security/KEY_HIERARCHY.md`](../security/KEY_HIERARCHY.md) §3 is not used in v1. It stays reserved for the separate encrypted index segments that OCR, face, and embedding indexes will need.

### 16.5 Leakage

An FTS index stores tokenized terms and their postings inside the database, so it is readable by whoever already holds the unlocked database key and by nobody else; it adds no capability to an attacker without that key. Term count changes the database size, which is part of the same signal as everything below.

Persisted database and page sizes leak approximate scale. Index names and schema should avoid user labels, but the schema itself is not assumed secret after binary analysis.

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
