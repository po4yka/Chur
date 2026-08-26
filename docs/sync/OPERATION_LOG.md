# Authenticated Operation Log

> **Status:** Proposed future protocol

The operation log represents private catalog changes as canonical encrypted and signed records. The local SQLCipher catalog is a materialized view; raw database pages are never the sync protocol.

## 1. Goals

- authenticate device authorship;
- detect replay and per-device rollback/forks;
- provide deterministic idempotent application;
- synchronize mutable metadata separately from immutable media;
- support tombstones and conflict resolution;
- expose no private payload to server.

## 2. Operation structure

Conceptual outer record:

```text
OperationV1
├── protocol_version
├── operation_id
├── vault/account binding
├── device_id
├── device_sequence
├── previous_operation_hash
├── operation_kind
├── collection/key epoch context when required
├── encrypted_payload
├── payload commitment
└── Ed25519 signature
```

Private payload is encrypted under an appropriate root/collection/operation key before signing.

## 3. Operation kinds

Initial logical set may include:

```text
CreateObject
CommitObject
UpdateMetadata
CreateAlbum
RenameAlbum
AddAlbumMembership
RemoveAlbumMembership
SetFavorite
AddTag / RemoveTag
DeleteObject / RestoreObject if policy permits
CreateCollectionEpoch
AddDevice / RevokeDevice
Checkpoint
```

Kinds and payload schemas are versioned. Unknown critical kinds fail closed.

## 4. Per-device chain

For each device:

```text
sequence = previous sequence + 1
previous_operation_hash = hash(canonical prior signed record)
```

Clients store the latest accepted head. A duplicate identical record is idempotent; a different record at an accepted sequence is a fork/security error.

## 5. Operation ID

Random or deterministic-from-signed-bytes ID must be collision-resistant and cannot be a plaintext-content hash. It supports deduplication/idempotency but signature/chain validation remains authoritative.

## 6. Encryption

Payload encryption context binds:

- operation version/kind;
- vault/collection;
- device and sequence;
- operation ID;
- relevant object/album IDs;
- key epoch.

The server can route by minimal opaque fields. Fields not required for routing should remain encrypted.

## 7. Signatures

Signature covers the canonical complete outer record excluding signature field. It authenticates device identity and prevents server modification.

Signing occurs only after local catalog transaction prepares a durable pending operation. Failed upload does not reuse sequence for a different operation.

## 8. Local transaction

```text
begin catalog transaction
apply local logical change
allocate operation sequence
create encrypted signed operation
store pending operation and new local device head
commit atomically
upload asynchronously
```

If signing fails, logical change must not commit as sync-visible state unless an explicit local-only policy exists.

## 9. Receive/apply

1. bound and canonically parse outer record;
2. verify known device/key and signature;
3. validate sequence/previous hash/fork state;
4. decrypt and validate payload/schema/epoch;
5. apply idempotently under catalog transaction;
6. update accepted head/checkpoint;
7. schedule object transfer/derived work separately.

## 10. Checkpoints

Signed checkpoints may summarize accepted per-device heads and materialized state commitment. They improve bootstrap and rollback detection but do not prove server completeness without cross-device/witness comparison.

## 11. Tombstones

Deletion operations create durable tombstones with causal ordering. Garbage collection waits until retention/acknowledgment policy ensures stale devices cannot legitimately resurrect deleted state.

## 12. Limits

- maximum record/payload size;
- maximum operations per response/batch;
- sequence checked arithmetic;
- bounded device/collection references;
- no nested arbitrary collections without limits;
- signature/KDF work bounded per batch;
- decompression forbidden or separately constrained.

## 13. Tests

- valid chain and batching;
- duplicate identical operation;
- different operation same sequence;
- missing/interleaved sequence;
- wrong previous hash/signature/key/domain;
- unknown/revoked device;
- payload tamper/wrong epoch;
- local crash before/after operation commit;
- server replay/rollback/fork/omission;
- deterministic conflict application;
- cross-platform vectors.
