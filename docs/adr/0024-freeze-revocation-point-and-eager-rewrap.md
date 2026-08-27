# ADR-0024: Freeze the Accepted Revocation Point and Require Eager Rewrap

- **Status:** Accepted
- **Date:** 2026-08-27
- **Decision owners:** @po4yka
- **Related:** [`../sync/REVOCATION.md`](../sync/REVOCATION.md), [`../sync/DEVICE_IDENTITY.md`](../sync/DEVICE_IDENTITY.md), [`../sync/COLLECTION_GRANTS.md`](../sync/COLLECTION_GRANTS.md), [`0014`](0014-observed-heads-causality-vector.md)

## Context

`REVOCATION.md` said operations authored after a device's revocation are rejected, and nothing made that enforceable. No document gave `RevokeDevice` a field list, so "accepted revocation point" resolved to nothing, and operations carry no trusted time, so a receiver could not tell whether an unseen operation from a revoked device predated its revocation. The device chooses its own sequence numbers and a colluding server delivers them late.

Rewrap had the parallel gap. Object keys are wrapped under the collection key of an epoch, so an object whose envelope has not been rewrapped stays readable by the member just removed. The sync documents made rewrap step 4 of a list, `CRYPTOGRAPHY.md` made it conditional on a policy that was never written, and no text said who performs it, what happens when it is interrupted on a large vault, or when it has to finish.

## Decision

- `RevokeDeviceRecordV1` carries the protocol version, vault binding, `revoked_device_id`, `final_accepted_device_sequence`, `final_accepted_operation_digest`, `membership_generation`, `issuer_device_id`, `previous_membership_commitment`, and the issuer's signature;
- the accepted revocation point is the pair (`final_accepted_device_sequence`, `final_accepted_operation_digest`). An operation above the sequence is rejected unconditionally; one at the sequence with another digest is a fork; one at or below is accepted only when it chains to an already-accepted operation of that device and the chain forward from it reaches the pinned digest;
- rewrap is eager and owned by the device that signed the membership change. The resume cursor is derived, the highest `object_id` already at the target epoch, so an interrupted pass resumes without stored state and a duplicated pass is idempotent;
- the revocation is presented as complete only when the pass completes. A device holding `MANAGE_MEMBERS` takes over after 24 hours.

## Alternatives considered

### A sequence-only cutoff

Rejected. It pins a length, not a branch, so a revoked device can present a different pre-revocation history up to that sequence and a receiver that never saw the original cannot tell. Pinning the digest costs 32 bytes and closes it. A wall-clock cutoff was rejected outright: there is no trusted time, and `ROLLBACK_PROTECTION.md` §10 already forbids timestamps as ordering authority.

### Lazy rewrap, or rewrap by every device independently

Both rejected. Lazy rewrap leaves an exposure window whose length is a storage detail rather than a decision, and the exposed party is the member just removed. Independent rewrap multiplies the work by the device count for the same result and produces no single completion signal to gate the user-visible state on.

## Consequences

### Positive

- "operations authored after revocation are rejected" is now a check a receiver runs from signed bytes alone, and the window in which a removed member can still read an object has a defined end and a visible progress state.

### Tradeoffs

- a below-point operation is unacceptable until the receiver can obtain the operations between it and the pinned digest, so a server withholding the middle of a chain can deny a valid stale operation. This fails closed, which is the intended direction;
- eager rewrap makes a revocation on a large collection a bounded but not instant operation, and the interface has to say so.

## Security impact

Affected invariants: SEC-042, SEC-045. Rejecting above-point operations unconditionally removes late delivery as an attack, and pinning the digest removes branch substitution below the point. Eager rewrap narrows, but does not eliminate, what a removed member retains: an object key already copied stays copied, which SEC-045 requires the product to keep saying.

## Compatibility impact

No revocation records exist yet, so nothing migrates. `protocol_version` governs the record, which is versioned with the enrollment record.

## Validation

- operations from a revoked device above, at, and below the point, including a below-point operation on a substituted branch;
- rewrap interrupted at each commit boundary and resumed, and two devices rewrapping concurrently;
- revocation reported complete only after the final envelope is rewrapped.

## Follow-up

- freeze the revocation record's field widths with the enrollment record's;
- record the measured rewrap throughput against the budget in `assurance/PERFORMANCE_BUDGETS.md` §3.
