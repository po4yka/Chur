# ADR-0047: Resume Rewrap from the Next Missing Envelope

- **Status:** Accepted
- **Date:** 2026-08-29
- **Decision owners:** @po4yka
- **Related:** [`0024`](0024-freeze-revocation-point-and-eager-rewrap.md), [`../sync/REVOCATION.md`](../sync/REVOCATION.md), [`../format/CATALOG_SCHEMA_V1.md`](../format/CATALOG_SCHEMA_V1.md)

## Context

ADR-0024 made eager rewrap resumable by deriving the highest `object_id` already at the target epoch. That value is not a safe cursor once two authorized devices work concurrently. A device can commit a later object before an earlier object; resuming after the highest completed identifier then skips the hole and can report revocation complete while an old-epoch envelope remains.

The defect needs no new wire field. The catalog already records the epoch of every active object-key envelope.

## Decision

- no rewrap cursor is stored or derived;
- each step queries the smallest active `object_id` that has no authenticated object-key envelope at the target epoch;
- a step opens the current envelope, writes one authenticated target-epoch envelope in a catalog transaction, and then queries again;
- an object already holding an authenticated target-epoch envelope is complete and is skipped. A concurrent second result for that object is verified and discarded rather than creating another active envelope;
- the pass is complete only when the query returns no object. Objects created after rotation already use the target epoch and therefore never enter the query;
- the owner starts immediately. Another authorized device may run the same loop after the rotation has remained incomplete for 24 hours according to that device's local accepted-at time. The server supplies no trusted takeover timestamp, and concurrent workers are safe.

## Alternatives considered

### Highest completed identifier

Rejected. It assumes completion is a prefix, which concurrent workers do not guarantee.

### Stored queue or lease

Rejected. A queue adds recovery state and a server lease gives the untrusted server progress authority. Re-querying the indexed catalog predicate is the durable state.

## Consequences

### Positive

- interruption, retry, and arbitrary concurrent completion order cannot skip an old-epoch envelope;
- no cursor journal, lease, or new protocol record exists.

### Tradeoffs

- each committed envelope performs one indexed next-missing query;
- takeover can start at different wall-clock instants on different devices, which changes only duplicated work, not the result or authorization.

## Security impact

Affected invariant: SEC-045. Completion now means every active object key is actually wrapped to the post-revocation epoch. An untrusted server cannot advance the scan or choose a false completion point.

## Compatibility impact

No rewrap state has shipped. This supersedes only the resume-cursor sentence of ADR-0024; its revocation point, eager ownership, completion, and 24-hour takeover decisions remain in force.

## Validation

- interruption after every object and resumption;
- two workers complete objects in reverse and interleaved order;
- a later object completes before an earlier one and completion remains false;
- an object created during rotation starts at the target epoch and needs no rewrap.
