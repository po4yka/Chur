# ADR-0054: Freeze v1 Collection Membership Records

- **Status:** Accepted
- **Date:** 2026-08-30
- **Decision owners:** @po4yka
- **Related:** [`../sync/COLLECTION_MEMBERSHIP.md`](../sync/COLLECTION_MEMBERSHIP.md), [`../sync/COLLECTION_GRANTS.md`](../sync/COLLECTION_GRANTS.md), [`../sync/REVOCATION.md`](../sync/REVOCATION.md)

## Context

A grant delivers a collection key, but it cannot by itself order member changes, authorize recipient operations, or prove that a removal advanced the collection epoch. Phase 4 needs one canonical membership chain that works for several recipients and several devices without treating a device as a user.

## Decision

- Each security collection has one signed membership chain. It starts at generation zero with the all-zero commitment.
- `CollectionMembershipRecordV1` is the fixed 292-byte record in `COLLECTION_MEMBERSHIP.md`.
- A record changes one recipient device. `UPSERT` adds the device or replaces its permission profile. `REVOKE` removes it.
- The record carries both recipient public keys. A first key pair is pinned on first use. A later change blocks until explicit verification or a future signed identity-rotation protocol authorizes it.
- A source-vault device can manage members while it is active in source membership. An external recipient can manage members only while its accepted profile is `MANAGE_MEMBERS`.
- A revoke advances the collection epoch by exactly one. A permission change that retains read access does not rotate the epoch.
- Each grant names the generation of the accepted membership record for its recipient device. A later record for another recipient does not stale that grant. A later record for the same recipient does.
- Operation payload kind `0x11` carries one membership record. Kind `0x12` carries one grant. Their sequence and identifiers bind to the containing signed operation.

## Consequences

Member ordering, permission checks, and revocation have one authenticated source. Multi-device recipients remain independent entries. Every removal has an observable forward-only epoch boundary, but it cannot erase keys or plaintext already received.

## Validation

- byte-exact record vectors and signature negatives;
- stale, replayed, conflicting, skipped, and key-substitution records;
- permission add, upgrade, downgrade, and revoke;
- one recipient with several devices and several independent recipients;
- revoke, epoch advance, eager rewrap, recovery, and lost-device flows.
