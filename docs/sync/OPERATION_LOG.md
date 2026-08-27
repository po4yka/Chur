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
├── observed_heads
├── key_selector
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

## 4. Per-device chain and observed heads

For each device:

```text
sequence = previous sequence + 1
previous_operation_hash = hash(canonical prior signed record)
```

Clients store the latest accepted head. A duplicate identical record is idempotent; a different record at an accepted sequence is a fork/security error.

The chain orders a device against itself only. Cross-device ordering comes from `observed_heads`, the signed vector of accepted heads the author held when it created the operation. See [`../adr/0014-observed-heads-causality-vector.md`](../adr/0014-observed-heads-causality-vector.md).

### 4.1 Structure and limits

```text
ObservedHeadV1 =
    device_id:bytes[16]
    device_sequence:u64

observed_heads = list of ObservedHeadV1
```

Encoding follows [`../format/CANONICAL_ENCODING_V1.md`](../format/CANONICAL_ENCODING_V1.md): a `u32` count followed by 24-byte elements sorted by ascending `device_id` byte value. Duplicate `device_id`, unsorted entries, a `device_sequence` of zero, an entry for the authoring device, and a count above the maximum are non-canonical and fail closed.

- the authoring device is excluded, so `(device_id, device_sequence)` together with `observed_heads` is the complete causal position of the operation;
- one entry is present for every other device from which the author has accepted at least one operation, and for no other device;
- `device_sequence` is the highest sequence accepted from that device;
- a vault has at most 32 active devices, so `observed_heads` holds at most 31 entries, 748 bytes including the count.

### 4.2 Happens-before

Operation B observes operation A when B's entry for A's device is at or above A's `device_sequence`, or when both share an author and A has the lower sequence. A and B are concurrent when neither observes the other. This relation decides every causal rule in [`CONFLICT_RESOLUTION.md`](CONFLICT_RESOLUTION.md); a tie-breaker applies only to concurrent operations.

### 4.3 Referenced heads not held

An operation that observes a head the receiver does not hold is not a fork and is not applied out of order:

- hold it in a pending set bounded by 1024 operations and 8 MiB per vault, whichever bound is reached first;
- request the missing heads and retry the pending set after each accepted batch;
- when a bound is reached, or when a sync session ends with the operation still unresolved, drop it and fetch it again later; nothing is applied before its observed heads are held;
- an entry naming an unknown device is a missing head, because enrollment is itself an operation;
- a referenced head the server never delivers is omission or fork, handled by [`ROLLBACK_PROTECTION.md`](ROLLBACK_PROTECTION.md) §9.

### 4.4 Revocation

A revoked device must not pin the vector:

- the `RevokeDevice` operation records the revoked device's final accepted head, so ordering against that device's earlier operations survives without an entry in every later operation;
- an operation whose author had observed a revocation must omit the revoked device. A receiver checks this from the same vector, because the entry for the revoking device shows whether the author had accepted the revocation; presence after that point is non-canonical;
- an author that had not yet observed the revocation may still name the device, and its operation is handled under [`REVOCATION.md`](REVOCATION.md) §7;
- a revoked device stops counting against the 32-device maximum once its revocation is accepted.

## 5. Operation ID

`operation_id` is 16 random bytes from the vault CSPRNG, encoded as bytes per [`../format/CANONICAL_ENCODING_V1.md`](../format/CANONICAL_ENCODING_V1.md) §8. It is never derived from the signed bytes: it is a field of the record whose signed bytes would derive it, so the derived form is circular, and it is never a plaintext-content hash.

It is a deduplication and idempotency key only. Two received records are the same operation when their complete canonical bytes are identical; that is what §4 means by a duplicate identical record. Records sharing an `operation_id` but differing in any other byte are not duplicates: at the same `(device_id, device_sequence)` they are a fork under §4, and at any other position the second one is rejected as reuse of an identifier. Signature and chain validation remain authoritative.

`operation_id` is not a conflict tie-break input. The tie-break reads `operation_digest` from §4, which commits to the whole record including `observed_heads`, and which the receiver has already computed for the chain.

## 6. Encryption

The cleartext outer record is closed. It carries exactly the fields of §2 and nothing else:

```text
protocol_version
operation_id
vault/account binding
device_id
device_sequence
previous_operation_hash
observed_heads
key_selector
encrypted_payload
Ed25519 signature
```

Every other field of an operation lives inside `encrypted_payload`: `operation_kind`, the collection and key epoch the operation belongs to, and every object, album, tag, and device identifier the operation names. Without this, the server reads a timestamped per-device stream of delete, favorite, rename, and tag events attributed to a collection, which is a behavioural profile of a private library and contradicts §1. A new routing need is a `protocol_version` change, not a new cleartext field.

`key_selector` is 16 random bytes assigned to a `(collection, epoch)` pair when that epoch is created, and it selects the key the receiver decrypts with. It is opaque: it carries no collection identity and no ordering, and the server learns only that two operations use the same epoch. Root-domain operations carry the vault's root selector.

The payload AAD is the canonical concatenation of the cleartext fields above, excluding `encrypted_payload` and the signature, after the operation signing domain tag. AAD must be readable before decryption, so no field inside the ciphertext may appear in it: `operation_kind`, collection, epoch, and object identifiers are authenticated as payload plaintext by the AEAD tag, and the signature of §7 binds the sealed payload to the outer record.

What the server still observes is enumerated in [`SERVER_TRUST_MODEL.md`](SERVER_TRUST_MODEL.md) §8.

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
3. validate sequence, previous hash, fork state, and observed heads per §4.2 to §4.4;
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
- bounded device/collection references, including the `observed_heads` maximum in §4.1;
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
