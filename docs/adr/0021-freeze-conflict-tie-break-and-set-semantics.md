# ADR-0021: Freeze the Conflict Tie-Break and Set Semantics

- **Status:** Accepted
- **Date:** 2026-08-27
- **Related:** [`../sync/CONFLICT_RESOLUTION.md`](../sync/CONFLICT_RESOLUTION.md), [`0014`](0014-observed-heads-causality-vector.md), [`0022`](0022-freeze-operation-chain-hash-and-identifier.md)

## Context

`CONFLICT_RESOLUTION.md` requires that clients receiving the same valid operation set converge without server-selected semantics, then left the mechanism unfinished. Its tie-break key held three terms: "causal class", never defined as a comparator; "operation kind priority when specified", never specified for any kind; and "lexicographic operation_id", whose generation was itself undecided. §5 offered a choice between unique add tokens and causal remove context, two set CRDTs that disagree on an add/remove race, and §6 said deletion "normally dominates". Four places where two honest devices could converge to different states. `0014` supplied the causal half and left the tie-break, which applies only to genuine concurrency, open.

## Decision

- the tie-break between two concurrent operations is the greater `operation_digest`, read as a 32-byte unsigned big-endian integer. The digest is defined by `0022` and is the value the author's next operation carries as `previous_operation_hash`, so every receiver has already computed it;
- "causal class" and "operation kind priority" are deleted. Happens-before decides every non-concurrent pair, and per-kind behaviour is stated by the field-class rules;
- membership, tags, and favorites are observed-remove sets. The add token is the add operation's `operation_id`; a remove lists the tokens its author had observed; a concurrent unseen add survives;
- a tombstone concurrent with a metadata edit wins for visibility, and the edit is retained and applied to the object's state so a later restore is not stale.

## Alternatives considered

### Tie-break on `operation_id` or on device identity

Rejected. `0022` makes `operation_id` 16 random bytes committing to nothing, so it is a free field an author sets without consequence, while the digest commits to `observed_heads` and the whole payload. Any fixed device order instead makes one device the permanent winner of every race.

### Last-writer-wins sets, or causal remove context instead of add tokens

Both rejected. A last-writer-wins set drops a concurrent add with no conflict surface; for an album membership that is a photo disappearing silently. Causal remove context needs the element's causal history to evaluate a remove, while a token list travels inside the remove and is checkable from two operations alone.

## Consequences

### Positive

- the total order comes from a value already required for the chain, so it costs no extra bytes and no extra hash, and every rule in the document now resolves to a comparator or an explicit per-class rule.

### Tradeoffs

- a remove carries one token per observed add of that element, so a heavily re-added element has a larger remove payload;
- an author can grind its own digest by re-signing, one signature per attempt. The tie-break decides display, never authorization, and the same author could reach any outcome with one later operation observing both, so the grind buys nothing.

## Security impact

Affected invariants: SEC-042. The tie-break input is inside the signed, chain-validated record, so a server cannot change which of two concurrent values wins by reordering, delaying, or withholding delivery. Deletion winning visibility over a concurrent edit stops a delayed edit from making a deleted object reappear.

## Compatibility impact

No operations exist yet, so nothing migrates. `protocol_version` governs the record; the add-token list is a payload field of remove operations and is fixed with the rest of the payload schemas.

## Validation

- randomized permutations of one operation set converge to one catalog on Android, iOS, and the CLI;
- concurrent scalar updates whose digests order in each direction;
- add/remove races with the add observed and unobserved;
- delete concurrent with edit, then restore, showing the edited value.

## Follow-up

- freeze the add-token list width and bound when the operation payload schemas are frozen.
