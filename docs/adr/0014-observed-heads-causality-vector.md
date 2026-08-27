# ADR-0014: Add an Observed-Heads Causality Vector to the Operation Record

- **Status:** Accepted
- **Date:** 2026-08-27
- **Decision owners:** @po4yka
- **Related:** [`../sync/OPERATION_LOG.md`](../sync/OPERATION_LOG.md), [`../sync/CONFLICT_RESOLUTION.md`](../sync/CONFLICT_RESOLUTION.md), [`../sync/REVOCATION.md`](../sync/REVOCATION.md), [`../format/CANONICAL_ENCODING_V1.md`](../format/CANONICAL_ENCODING_V1.md)

## Context

`CONFLICT_RESOLUTION.md` §1 listed "causal references / observed heads" as an ordering input, and §3 gives a scalar field to the causally later update. The signed record in `OPERATION_LOG.md` §2 carried `device_id`, `device_sequence`, and `previous_operation_hash` only. That hash chains a device to its own prior operation and says nothing about what its author had accepted from other devices, so a receiver could order a device against itself and against nothing else. Every causal rule in `CONFLICT_RESOLUTION.md` was unenforceable, and the tie-breaker, which is meant to apply only to concurrent operations, would have decided every pair. Wall-clock time is already forbidden as ordering authority, so no substitute existed.

## Decision

Add `observed_heads` to the signed outer record: a canonical list of (`device_id`, `device_sequence`) entries for the other devices whose operations the author had accepted, sorted by ascending `device_id`, with at most 31 entries under a maximum of 32 active devices per vault. The authoring device is excluded, so the record's own (`device_id`, `device_sequence`) is the dot and `observed_heads` is the vector. An operation whose referenced heads are not held is buffered, not rejected, under a bound of 1024 operations and 8 MiB per vault. `RevokeDevice` records the revoked device's final accepted head, and an operation authored after its author observed that revocation must omit the device. `OPERATION_LOG.md` §4 is normative for the field.

## Alternatives considered

### Dotted version vectors

Rejected. Their advantage is a server-held per-key context echoed back on each read-modify-write, which bounds the vector by concurrent writers rather than by replicas. Chur cannot accept ordering context from an untrusted server without giving it a hand in semantics, and the writer set here is a signed bounded membership, so the plain vector is already bounded without server help. The per-operation dot that gives the scheme its name is present either way as (`device_id`, `device_sequence`).

### Reject an operation whose referenced heads are not held

Rejected. Server reordering, paging, and omission are expected behavior, so rejection turns ordinary delivery order into a security error and discards valid signed operations. Bounded buffering keeps the failure recoverable and the memory cost fixed.

### One entry per device ever enrolled

Rejected. The vector would grow with every enrollment and never shrink, so a revoked device would pin an entry in every future record. Recording the final head once in `RevokeDevice` keeps ordering against that device's earlier operations without the permanent cost.

## Consequences

### Positive

- happens-before is computable from signed bytes, so the causal rules become enforceable and the tie-breaker applies only to genuine concurrency;
- an operation that observed a hidden operation names its head, so server omission leaves evidence instead of passing silently.

### Tradeoffs

- up to 748 bytes per operation, which dominates a small metadata operation;
- 32 active devices is a hard cap until a new record version raises it, and receivers need a bounded pending set with a re-request path.

## Security impact

Affected invariants: SEC-041 and SEC-042. The vector is inside the signed record, so the server cannot alter a claimed causal position, and hiding one operation now requires hiding every operation that observed it. The residual gaps in `ROLLBACK_PROTECTION.md` §9 remain. Buffering is bounded in count and bytes, so an unresolvable reference costs bounded memory rather than unbounded retention.

## Compatibility impact

No operations exist yet, so nothing migrates. `protocol_version` governs the record; a change to the entry shape or to the 32-device maximum requires a new version and a dual-reader policy, never a redefinition of v1.

## Validation

- byte-exact vectors for empty, one-entry, and 31-entry `observed_heads`, and negative vectors for unsorted, duplicate, zero-sequence, self-referencing, and over-count vectors;
- delivery permutations that require buffering, pending-set bound exhaustion, and operations naming a device revoked before and after authoring;
- concurrent and causally ordered pairs producing the same catalog on Android, iOS, and the CLI.

## Follow-up

- the deterministic tie-break key was frozen by [`0021`](0021-freeze-conflict-tie-break-and-set-semantics.md), which reads the operation digest rather than `operation_id`;
- the `previous_operation_hash` algorithm, its domain tag, and its genesis value were fixed by [`0022`](0022-freeze-operation-chain-hash-and-identifier.md);
- the checkpoint structure, which commits to the same per-device heads with their digests, was defined by [`0023`](0023-define-signed-checkpoint-and-bootstrap-attestation.md);
- the operation record's field widths and signing domain tag remain open, owned by `sync/OPERATION_LOG.md` §2.
