# ADR-0055: Add Sharing State in Catalog v3

- **Status:** Accepted
- **Date:** 2026-08-30
- **Decision owners:** @po4yka
- **Related:** [`../format/CATALOG_SCHEMA_V3.md`](../format/CATALOG_SCHEMA_V3.md), [`../sync/COLLECTION_MEMBERSHIP.md`](../sync/COLLECTION_MEMBERSHIP.md), [`0049`](0049-add-sync-state-in-catalog-v2.md)

## Context

Phase 4 must retain accepted collection membership chains, recipient key pins, and grants across a crash. Adding those tables without advancing the catalog version would give the same version two different physical schemas.

## Decision

- Catalog format `0x0003` adds only sharing state.
- Membership records and grants remain complete canonical protocol records. SQL columns are checked projections, not another encoder.
- A recipient pin stores both public keys and whether it was accepted on first use or explicitly verified.
- The catalog writes a membership record, its chain head, recipient projection, and pin in one immediate SQLCipher transaction.
- A repeated grant identifier is idempotent only for identical bytes. Different bytes are a security conflict.
- Migration `v2 -> v3` creates empty sharing tables. Migration `v1 -> v3` still runs the tested `v1 -> v2` step first.

## Consequences

An unlocked client can restore sharing authorization before it processes inbound operations or issues grants. No collection key or private identity key enters the new tables. Old grants remain as historical evidence after forward-only revocation.

## Validation

- fresh install and authenticated `v2 -> v3` migration;
- crash-resume on either side of the SQL commit;
- membership, pin, and grant round trip;
- corrupt projection, conflicting grant ID, stale chain, and key-substitution rejection.
