# ADR-0027: Freeze the Deletion Transaction and the Crypto-Erasure Point

- **Status:** Accepted
- **Date:** 2026-08-27
- **Decision owners:** @po4yka
- **Related:** [`../format/CATALOG_SCHEMA_V1.md`](../format/CATALOG_SCHEMA_V1.md), [`../security/SECURITY_INVARIANTS.md`](../security/SECURITY_INVARIANTS.md), [`0012`](0012-import-journal-durability-ordering.md)

## Context

Import, key-slot replacement, and vault initialization each carried a numbered ordering. Deletion carried none. `CATALOG_SCHEMA_V1.md` §14 said a tombstone precedes physical garbage collection and that "key envelopes may be destroyed according to policy" without naming the policy; §17 listed "deletion/tombstone/key-envelope removal" as one atomic boundary without saying what it contains; and SEC-026 asserted that a crypto-erasure claim requires destruction of every accessible key envelope with no procedure that satisfies it.

Nothing said which write is the erasure moment, in what order containers go, when garbage collection runs, or what a half-deleted object is. An implementation was free to unlink the container first and leave a live envelope behind, which is the one order that makes the security claim false while looking finished, and a crash between any two steps had no defined outcome.

## Decision

Fix the ordering in `CATALOG_SCHEMA_V1.md` §14.1: set `state` to `DELETING` in one transaction; then, in one further transaction, destroy every object-key envelope, write the tombstone, and set `state` to `TOMBSTONED`; then unlink derived containers, the original container, and the object's scratch entries; then delete the object row.

The second transaction is the erasure moment. Steps 1 and 2 are the atomic boundary of §17; the rest is idempotent garbage collection that carries no security property. Garbage collection runs at the first unlock of a session and after each deletion that session performs, never while locked. Recovery rolls a half-deleted object forward and never back. In a vault with no enrolled peer device a tombstone may be discarded once garbage collection for its object completes; every other vault keeps the membership rule of `sync/OPERATION_LOG.md` §11.

## Alternatives considered

### Unlink the container first, then destroy the envelope

Rejected. It reverses the security order: the visible artifact disappears while the key that opens every remaining copy — WAL pages and queued operations — survives. Destroying the envelope first makes all of those undecryptable in one commit. Neither order reaches a backup package written earlier, which carries its own envelope and its own portable slot.

### One transaction spanning the catalog and the filesystem

Rejected. No portable primitive commits a SQLCipher transaction together with a set of `unlink` calls, so the guarantee would be an aspiration. Splitting at the envelope makes the part that must be atomic small enough to be atomic.

### Run garbage collection on a timer or at lock

Rejected. Both need the catalog key, which lock has already zeroized, and a timer adds a wake-up whose only visible effect is disk activity correlated with deletion.

## Consequences

### Positive

- SEC-026 has a procedure that satisfies it, and the erasure moment is one durable commit;
- a crash at any step leaves a state that rolls forward with no security decision to make;
- ciphertext cleanup may lag without weakening the claim.

### Tradeoffs

- containers of a deleted object survive until the next unlock, so free space returns late and a deleted object's ciphertext is still on disk for an attacker who images the device before then;
- rows in `DELETING` or `TOMBSTONED` survive a crash and must be swept, which the reconciliation pass already visits.

## Security impact

Affected invariants: SEC-022, SEC-026.

The erasure claim now rests on one transaction rather than on completion of a multi-step cleanup. Roll-forward is required because rolling back would return an object to `ACTIVE` after its key was destroyed, presenting an item that can never be opened.

## Compatibility impact

No deletion has run against persisted data, so nothing migrates. No lifecycle value changes and `catalog_format_version` stays `0x0001`.

## Validation

- crash injection at each of the six steps, asserting roll-forward and no `ACTIVE` resurrection;
- assert that no object-key envelope for a `TOMBSTONED` object exists in the catalog or its WAL, and that a backup package written before the deletion still opens the object, which is the documented limit of the claim;
- garbage collection resuming a `DELETING` row and a `TOMBSTONED` row with containers present;
- a committed container with no object row and no `ImportTransaction` row is deleted.

## Follow-up

- tombstone retention for a synchronized vault stays with `sync/OPERATION_LOG.md` §11;
- deleting a whole collection, which rotates rather than destroys collection keys, is not covered here.
