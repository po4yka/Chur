# Chur Sync Protocol v1

> **Status:** Proposed future protocol outline; no production implementation is authorized by this document alone

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

Clients verify each device chain independently before applying operations.

## 6. Bootstrap new device

1. authenticate account transport;
2. generate/verify device identity;
3. obtain signed membership and encrypted root/collection state;
4. authenticate recovery/enrollment path;
5. fetch checkpoint and operation chains;
6. verify signatures/heads/forks;
7. build temporary catalog;
8. fetch ciphertext objects lazily/eagerly by policy;
9. commit local vault and platform slot.

## 7. Background mode

While locked, clients may transfer already committed ciphertext and signed opaque records. They must not decrypt payloads, open private catalog, resolve conflicts, or generate derivatives. Decrypted application occurs after explicit unlock.

## 8. Conflict resolution

Defined in [`CONFLICT_RESOLUTION.md`](CONFLICT_RESOLUTION.md). The server never chooses semantic winner. Clients must converge from the same valid operation set.

## 9. Deletion

Deletion is a signed tombstone. Server physical deletion may occur after retention/acknowledgment policy, but server acknowledgment is not proof of erasure. Key-envelope destruction and collection revocation are client-side decisions.

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
