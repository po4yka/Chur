# Conflict Resolution

> **Status:** Proposed future deterministic merge model

Clients that receive the same valid operation set must converge without server-selected semantics. Immutable media bytes simplify conflicts; mutable metadata, memberships, favorites, tags, and deletions require explicit rules.

## 1. Ordering model

Each operation carries:

```text
device_id
device_sequence
observed_heads
operation_id
```

Causality is preferred over wall-clock time. `observed_heads` is the signed vector of accepted per-device heads defined in [`OPERATION_LOG.md`](OPERATION_LOG.md) §4; it decides happens-before and concurrency for every rule below. Concurrent operations use a deterministic tie-breaker only where a single value is required.

The tie-break between two concurrent operations is one comparison:

```text
winner = the operation whose operation_digest is the greater value,
         read as a 32-byte unsigned big-endian integer
```

`operation_digest` is defined in [`OPERATION_LOG.md`](OPERATION_LOG.md) §4 and is the same value the author's next operation carries as `previous_operation_hash`, so every receiver already holds it. Two distinct operations have distinct digests under a collision-resistant hash, so the rule is total: every concurrent pair has exactly one winner and every device computes the same one.

Two earlier terms are removed rather than defined. "Causal class" said only that happens-before is consulted first, which §4.2 of the operation log now states directly. "Operation kind priority" named a priority no kind ever carried; per-kind behaviour is stated by §3, §5, and §6 below. `operation_id` is not an input; it is a deduplication key, per [`OPERATION_LOG.md`](OPERATION_LOG.md) §5.

An author can grind its own digest by re-signing, one signature per attempt. The tie-break decides which of two concurrent values a device displays and never what is authorized, and the same author could reach any outcome by issuing one later operation that observes both, so grinding buys nothing.

The causal input is fixed by [`../adr/0014-observed-heads-causality-vector.md`](../adr/0014-observed-heads-causality-vector.md) and this key by [`../adr/0021-freeze-conflict-tie-break-and-set-semantics.md`](../adr/0021-freeze-conflict-tie-break-and-set-semantics.md). Timestamps alone are forbidden as authority.

## 2. Immutable objects

Object content is immutable. Two imports are two objects even if bytes match, unless local keyed deduplication deliberately links them. There is no byte-level merge.

## 3. Metadata fields

For scalar fields such as caption/display date:

- causally later update wins;
- concurrent updates retain conflict metadata and select deterministic displayed winner;
- UI may expose alternate value/history;
- compaction occurs only after devices have accepted a resolving operation.

A resolver emits a new signed operation observing both conflicting versions.

## 4. Albums

Album creation uses random album ID, so same-name concurrent albums remain separate. Rename follows scalar-field rules.

## 5. Membership, tags, favorites

Membership, tags, and favorites are observed-remove sets with unique add tokens. Causal remove context was rejected: evaluating a remove under it needs the element's causal history, while a token set travels inside the remove operation and is checkable from the two operations alone.

- an add carries an add token, and that token is the `operation_id` of the add operation. Tokens are unique because identifiers are 16 random bytes and an identifier is never reused;
- an element is present when it holds at least one add token that no accepted remove lists;
- a remove lists exactly the add tokens its author had observed for that element and removes those. A concurrent add the author had not seen survives, so an element re-added concurrently with a remove stays present;
- add is idempotent by token, and a replayed remove removes the same tokens;
- "remove all including future concurrent adds" is not representable; it takes a later operation that observes those adds.

Last-writer-wins sets are not used. They drop a concurrent add with no conflict surface, which for an album membership means a photo the user added disappears silently.

## 6. Deletion

Deletion creates a tombstone. A tombstone concurrent with a metadata edit of the same object wins for visibility: the object is not shown, and no tie-break is consulted. The concurrent edit is not discarded; it is applied to the object's retained state, so a later restore shows the edited value rather than a stale one. A concurrent object creation with same random object ID is invalid/improbable and rejected.

Restoration, if supported, is an explicit operation that references the tombstone and creates a new active generation. Silent resurrection from stale device is forbidden.

## 7. Collection membership and keys

Security collection membership changes are security operations, not ordinary CRDT merges. Revocation/epoch rotation follows signed membership ordering and may block conflicting stale grants. Security state chooses safety over automatic availability.

## 8. Device membership conflicts

Conflicting enrollment/revocation/fork requires security reconciliation, not automatic scalar merge. A revoked device cannot win by later wall-clock timestamp.

## 9. Derived assets

Derived assets are cache-like records bound to source revision/generator profile. Concurrent valid assets may coexist; client selects compatible current asset or regenerates. They do not overwrite original content.

## 10. Compaction

V1 does not compact operation history. A checkpoint records the uncompacted-state sentinel defined in [`ROLLBACK_PROTECTION.md`](ROLLBACK_PROTECTION.md) §6.1. Conflict and tombstone operations remain available after object ciphertext and local garbage collection complete. A later protocol version needs a canonical portable state snapshot before it can discard this history.

## 11. UX

Most conflicts resolve invisibly/deterministically. Expose only meaningful user choices, such as two concurrent captions or delete/edit conflict, after decryption. Do not reveal device IDs or private values to server.

## 12. Tests

- same operation delivered repeatedly/out of order;
- concurrent scalar updates;
- add/remove membership and tag races;
- delete versus edit/add/favorite;
- album same-name creation;
- resolver operation observing both branches;
- revoked/stale device operations;
- randomized operation permutations converge to same catalog;
- compaction/checkpoint preserves result;
- Android/iOS/CLI vector equivalence.
