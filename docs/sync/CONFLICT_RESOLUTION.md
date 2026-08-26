# Conflict Resolution

> **Status:** Proposed future deterministic merge model

Clients that receive the same valid operation set must converge without server-selected semantics. Immutable media bytes simplify conflicts; mutable metadata, memberships, favorites, tags, and deletions require explicit rules.

## 1. Ordering model

Each operation carries:

```text
device_id
device_sequence
causal references / observed heads
operation_id
```

Causality is preferred over wall-clock time. Concurrent operations use a deterministic tie-breaker only where a single value is required.

Proposed deterministic total tie-break key:

```text
causal class
operation kind priority when specified
lexicographic operation_id
```

The final rule requires vectors and ADR; timestamps alone are forbidden as authority.

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

Use operation-based add/remove semantics with unique add tokens or causal remove context:

- add is idempotent by operation/token;
- remove removes observed adds;
- concurrent unseen add may survive remove according to documented observed-remove set semantics;
- explicit “remove all including future concurrent adds” is not representable without a later resolving operation.

Simpler last-writer-wins sets require an ADR demonstrating acceptable deletion behavior.

## 6. Deletion

Deletion creates a tombstone and normally dominates concurrent metadata edits for visibility. A concurrent object creation with same random object ID is invalid/improbable and rejected.

Restoration, if supported, is an explicit operation that references the tombstone and creates a new active generation. Silent resurrection from stale device is forbidden.

## 7. Collection membership and keys

Security collection membership changes are security operations, not ordinary CRDT merges. Revocation/epoch rotation follows signed membership ordering and may block conflicting stale grants. Security state chooses safety over automatic availability.

## 8. Device membership conflicts

Conflicting enrollment/revocation/fork requires security reconciliation, not automatic scalar merge. A revoked device cannot win by later wall-clock timestamp.

## 9. Derived assets

Derived assets are cache-like records bound to source revision/generator profile. Concurrent valid assets may coexist; client selects compatible current asset or regenerates. They do not overwrite original content.

## 10. Compaction

Operation/history compaction requires an authenticated checkpoint proving retained state and tombstones. Do not discard conflict/tombstone evidence until retention and device-acknowledgment policy permits.

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
