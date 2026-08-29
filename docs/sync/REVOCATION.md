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

- record the accepted revocation point defined in [`DEVICE_IDENTITY.md`](DEVICE_IDENTITY.md) §9 in the `RevokeDevice` operation, so later operations may omit the device from `observed_heads` per [`OPERATION_LOG.md`](OPERATION_LOG.md) §4.4;
- reject every operation signed by it above the accepted revocation point, unconditionally and regardless of when the server delivers it;
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
4. rewrap every active object key of the collection to the new epoch, per §3.1;
5. use new epoch for future objects/operations;
6. retire old key from active sessions after the rewrap of §3.1 completes;
7. retain enough historical context to verify old operations.

Media bytes do not need re-encryption unless object key itself is suspected compromised.

### 3.1 Rewrap ownership, resumption, and completion

Rewrap is eager, and the device that signed the membership change owns it. Lazy rewrap was rejected: an object whose envelope is still under the old epoch stays readable by the member just removed, so a lazy policy is an exposure window whose length is a storage detail rather than a decision.

- step 2 takes effect immediately, so every object created after it is already under the new epoch and is never rewrapped;
- the owning device repeatedly selects the smallest active `object_id` with no authenticated envelope at the target epoch and commits its rewrapped envelope in one catalog transaction. There is no cursor: after interruption or concurrent work, the same indexed query finds the next real hole. [ADR-0047](../adr/0047-resume-rewrap-from-the-next-missing-envelope.md) supersedes the unsafe highest-completed cursor of ADR-0024;
- rewrap is idempotent per object. An envelope already at the target epoch is skipped, so a resumed, retried, or duplicated pass converges to the same result;
- the revocation is presented to the user as complete only when the walk reaches the end. Until then the interface states that rotation is in progress and that objects not yet rewrapped remain readable by the removed member;
- if the owning device has not finished within 24 hours of the accepted membership change according to this device's local accepted-at time, any other authorized device continues the same next-missing loop. The server supplies no trusted timestamp. Two devices running concurrently are safe because a target-epoch envelope is verified and skipped per object;
- an envelope that cannot be rewrapped, because its object key is unavailable to the rewrapping device, is reported to the user and never silently skipped.

Throughput and completion targets are in [`../assurance/PERFORMANCE_BUDGETS.md`](../assurance/PERFORMANCE_BUDGETS.md) §3.

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

A stale device may return with old keys and operations. It must first obtain and verify current membership.

Acceptance is decided by the revocation point of [`DEVICE_IDENTITY.md`](DEVICE_IDENTITY.md) §9, never by arrival time:

- `device_sequence` above `final_accepted_device_sequence`: rejected unconditionally. No later delivery, no server claim, and no clock makes it acceptable;
- `device_sequence` equal to it with any other `operation_digest`: a fork under [`ROLLBACK_PROTECTION.md`](ROLLBACK_PROTECTION.md) §4;
- `device_sequence` at or below it: accepted only when the operation chains through `previous_operation_hash` to an operation of that device the receiver has already accepted, and when the chain running forward from it reaches `final_accepted_operation_digest`. A receiver that cannot obtain the intervening operations does not accept it. The point pins one branch, so a revoked device cannot substitute a pre-revocation history the issuer never saw;
- an accepted below-point operation then applies under the ordinary rules of [`CONFLICT_RESOLUTION.md`](CONFLICT_RESOLUTION.md), with no special reconciliation, because its causal position is signed in `observed_heads`.

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
