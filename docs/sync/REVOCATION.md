# Revocation Semantics

> **Status:** Proposed normative revocation policy

The types in §1 do not all ship together. Device revocation (§2), credential rotation, and server transport-token revocation are required by Gate 5 in [`../assurance/RELEASE_GATES.md`](../assurance/RELEASE_GATES.md) and ship with Phase 3, because a vault that can enrol a second device can lose one. Member revocation, permission downgrade, collection-grant revocation, and multi-recipient rewrap are required by Gate 6 and ship with Phase 4.

Revocation prevents future authorized access and operation acceptance. It cannot force deletion of keys or plaintext already obtained by an authorized device or recipient.

## 1. Revocation types

- device revocation from an account/vault;
- user/member revocation from a collection;
- permission downgrade;
- collection grant revocation;
- recovery/credential rotation;
- compromised object/collection/root key response;
- server transport token revocation.

These have different scopes and must not share ambiguous UI language.

## 2. Device revocation

A signed membership operation removes the device. Clients then:

- record the revoked device's final accepted `device_sequence` in the `RevokeDevice` operation, so later operations may omit it from `observed_heads` per [`OPERATION_LOG.md`](OPERATION_LOG.md) §4.4;
- reject later operations signed by it beyond accepted revocation point;
- stop issuing root/collection envelopes;
- rotate affected collection epochs according to policy;
- revoke server auth tokens;
- preserve old signatures for historical verification;
- warn if forked unseen operations exist.

## 3. Collection member revocation

Forward-looking procedure:

1. authenticate membership change;
2. create `SecurityCollectionKey[epoch+1]`;
3. issue grants to remaining verified devices;
4. rewrap active object keys to new epoch;
5. use new epoch for future objects/operations;
6. retire old key from active sessions after migration;
7. retain enough historical context to verify old operations.

Media bytes do not need re-encryption unless object key itself is suspected compromised.

## 4. Previously obtained data

A revoked party may retain:

- old collection/object keys;
- ciphertext copied before revocation;
- exported plaintext;
- screenshots or backups.

Chur must not claim retroactive deletion. Revocation protects new epochs/content and server-mediated future access.

## 5. Permission downgrade

Changing `MANAGE_MEMBERS` to `READ` affects operation authorization after accepted membership generation. It does not change decryption ability if the same collection key epoch remains shared. If content access must change, rotate epoch or split collections.

## 6. Compromise response

- object key compromised → re-encrypt object streams under new object key and envelopes;
- collection key compromised → new epoch, rewrap object keys, new grants;
- device identity key compromised → revoke/rotate device, investigate signed operations;
- root secret compromised → create new root and rewrap all domains, reassess every slot/backup/device;
- password compromised but root not otherwise exposed → replace password slot; old backups may remain vulnerable.

## 7. Offline/stale devices

A stale device may return with old keys and operations. It must first obtain and verify current membership. Operations authored after its revocation are rejected. Operations created before revocation but unseen require causal policy and may need explicit reconciliation.

## 8. Backups

Old backups may contain revoked slots/grants/keys. Revocation does not rewrite them. User guidance must explain replacing/destroying old backups when revocation of portable factors matters.

## 9. Crypto-erasure

Local key-envelope destruction can make local ciphertext inaccessible, but synced/recipient/backup copies remain outside guarantee. Server deletion is best-effort reliability/privacy behavior, not cryptographic proof.

## 10. UI language

Use precise statements:

- “This device will not receive future updates.”
- “New collection keys will exclude this member.”
- “Previously downloaded items may remain accessible.”

Avoid “all copies deleted” or “access instantly erased everywhere.”

## 11. Tests

- revoked device operations before/after revocation point;
- epoch rotation and rewrap completion;
- stale grant rejection;
- permission downgrade without key rotation;
- offline device return/fork;
- old backup restore warning/state;
- object/collection/root compromise drills;
- multi-device recipient partial revocation;
- no accidental loss for remaining members during rotation.
