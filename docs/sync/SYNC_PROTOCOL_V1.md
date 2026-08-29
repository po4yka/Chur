# Chur Sync Protocol v1

> **Status:** Accepted normative Phase 3 protocol; production use still requires Gate 5 review

Sync transports immutable encrypted objects and authenticated encrypted catalog operations between authorized devices through an untrusted server.

## 1. Preconditions

Before v1 sync ships:

- local object/catalog formats are stable;
- device identity is implemented and reviewed;
- operation log/conflict/tombstone rules are frozen;
- rollback limitations are documented;
- portable recovery exists;
- protocol vectors and malicious-server tests pass.

## 2. Components

```text
Account transport authentication
Device identity directory
Signed membership state
Immutable ciphertext object service
Signed encrypted operation-log service
Opaque transfer sessions
Checkpoint/head exchange
```

Transport endpoints are implementation details; canonical records are protocol authority.

## 3. Object upload

```text
client commits immutable Chur object locally
→ request opaque upload session
→ upload ciphertext chunks/ranges with transport checksums
→ server records incomplete upload
→ client submits authenticated object finalization reference
→ upload becomes available only when complete
→ catalog CommitObject operation references object commitment
```

Server transport checksums do not replace object AEAD/final-commit verification.

## 4. Object download

- fetch expected ciphertext metadata from authenticated operation/catalog state;
- download resumably to temporary ciphertext;
- verify container structure/final commit with local object key;
- atomic install;
- activate catalog reference only after validation;
- retain incomplete transfer state without exposing media.

## 5. Operation synchronization

Devices exchange:

- known per-device heads;
- signed operations after those heads;
- membership/checkpoint state;
- opaque pagination tokens not trusted as completeness proof.

Clients verify each device chain independently before applying operations, and apply an operation only when the heads it observes are already held; otherwise it is buffered under [`OPERATION_LOG.md`](OPERATION_LOG.md) §4.3.

## 6. Bootstrap new device

1. authenticate account transport;
2. generate/verify device identity, including the fingerprint comparison [`SERVER_TRUST_MODEL.md`](SERVER_TRUST_MODEL.md) §7 requires for own-device enrollment;
3. obtain the signed enrollment record and take its `membership_generation` and `bootstrap_checkpoint_commitment` as the initial freshness floor, per [`DEVICE_IDENTITY.md`](DEVICE_IDENTITY.md) §4;
4. authenticate recovery/enrollment path;
5. fetch the checkpoint that commitment names, reject it unless it hashes to the commitment, then fetch operation chains from its heads;
6. verify signatures/heads/forks, and reject any membership generation, collection epoch, or per-device head the server offers below the attested checkpoint;
7. build temporary catalog;
8. fetch ciphertext objects lazily/eagerly by policy;
9. commit local vault and platform slot.

Server-supplied state never sets the floor. The enrolling device's signature over the enrollment record is what makes the floor trustworthy at the one moment when the new device holds no accepted head of its own; see [`ROLLBACK_PROTECTION.md`](ROLLBACK_PROTECTION.md) §7.

## 7. Background mode

While locked, clients may transfer already committed ciphertext and signed opaque records. They must not decrypt payloads, open private catalog, resolve conflicts, or generate derivatives. Decrypted application occurs after explicit unlock.

A locked device cannot check an inbound record against membership or head state, because both live in the encrypted catalog it may not open. Inbound records are staged, not accepted:

- the staging area holds ciphertext and signed bytes only. It is app-private, excluded from platform backup, and separate from the catalog, per SEC-034;
- it is bounded at 4096 records, 64 MiB per vault, and 7 days per record, whichever bound is reached first. Past a bound the oldest record is dropped. Dropping is safe because nothing is acknowledged while locked, so a dropped record is fetched again;
- staged records advance no accepted head, no membership generation, and no epoch. A locked device's freshness state is exactly what it was at lock;
- at unlock every staged record is validated from the start under [`OPERATION_LOG.md`](OPERATION_LOG.md) §9: signature, sequence, previous hash, membership at its generation, and observed heads. Staging is not partial validation and grants a record nothing.

## 8. Conflict resolution

Defined in [`CONFLICT_RESOLUTION.md`](CONFLICT_RESOLUTION.md). The server never chooses semantic winner. Clients must converge from the same valid operation set.

## 9. Deletion

Deletion is a signed tombstone. The retention rule that server physical deletion follows is normative in [`OPERATION_LOG.md`](OPERATION_LOG.md) §11.

The server deletes stored ciphertext only on an authenticated signed operation from an enrolled device. A transport session token authorizes fetch and upload, never deletion, so a stolen token alone destroys nothing. Server acknowledgment of a deletion is not proof of erasure. Key-envelope destruction and collection revocation are client-side decisions.

### 9.1 Server deletion authorization

The server never decrypts a tombstone, and the `store_id` it must remove is private payload data. The client therefore submits this separate canonical authorization after it has accepted the tombstone locally:

```text
ServerDeletionAuthorizationV1 =
    protocol_version:u16 = 0x0001
    request_id:bytes[16]
    vault_id:bytes[16]
    device_id:bytes[16]
    target_kind:u8
    target_id:bytes[16]
    authorizing_operation_digest:bytes[32]
    signature:bytes[64]
```

The signature input is `CHUR\x00SYNC\x00SERVER-DELETE\x00V1` followed by every field from `protocol_version` through `authorizing_operation_digest`. The exact record is 163 bytes. Identifiers are non-zero random `Id` values and the server scopes every lookup by `vault_id`.

`target_kind` allocates `0x01` for one immutable object and `0x02` for the whole account. An object authorization names its opaque `store_id` and a non-zero digest of an operation already stored for the vault. The server verifies the operation exists and the authorization signer is currently active; it cannot decrypt the operation and does not claim to prove its semantic kind. An account authorization requires `target_id == vault_id` and an all-zero operation digest.

The server stores `request_id` with the complete canonical bytes. An exact replay is idempotent; different bytes under the same identifier are rejected. Unknown versions and target kinds fail closed. A transport token cannot substitute for this signature.

## 10. Error/retry

- uploads/downloads are idempotent by opaque transfer/object IDs;
- network failure uses bounded backoff;
- authenticated corruption is not retried from the same bytes indefinitely;
- fork/rollback stops affected chain and requires reconciliation;
- unknown protocol/operation version fails closed;
- server 2xx response never bypasses client verification.

## 11. Metadata minimization

Server routing fields are random/opaque. No plaintext filename, MIME, album, caption, EXIF, search term, or content hash. Batch/padding may be added later under a versioned transport profile.

## 12. Protocol evolution

- version canonical records independently from API endpoints;
- server advertises capability, but client policy chooses approved suites;
- maintain mixed object-container versions through explicit readers/migration;
- no downgrade from authenticated newer membership/log state;
- mandatory changes require minimum-client policy signed by authorized membership, not arbitrary server command.

## 13. Test scenarios

- interrupted/resumed upload/download;
- server marks incomplete object complete;
- chunk substitution/reorder/truncation;
- operation replay/fork/omission;
- stale checkpoint/newer local head;
- new-device bootstrap and recovery;
- revoked device uploading operations;
- conflicting metadata/deletion;
- locked background transfer;
- account auth reset without vault access;
- Android/iOS/CLI interoperability.
