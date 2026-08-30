# ADR-0057: Add Collection Operation Streams in Catalog v4

- **Status:** Accepted
- **Date:** 2026-08-30
- **Decision owners:** @po4yka
- **Related:** [`../format/CATALOG_SCHEMA_V4.md`](../format/CATALOG_SCHEMA_V4.md), [`../sync/COLLECTION_OPERATION_LOG.md`](../sync/COLLECTION_OPERATION_LOG.md), [`0055`](0055-add-sharing-state-in-catalog-v3.md), [`0056`](0056-add-collection-scoped-sharing-operation-streams.md)

## Context

Phase 4 must restore accepted collection-operation chains and fork evidence after a crash. Catalog v3 has collection membership, pins, and grants, but it has no collection-scoped content log. Adding tables without a new catalog version would give v3 two physical schemas.

## Decision

- Catalog format `0x0004` adds only durable collection-operation streams.
- One stream row binds an opaque key selector to one collection and epoch.
- Accepted operations keep the complete canonical signed record plus checked issuer, sequence, identifier, and digest projections.
- Fork evidence is durable and keeps a participant stream frozen after restart.
- Heads are rebuilt by authenticated replay. Collection streams do not add a second stored head projection or checkpoint scheme.
- Migration `v3 -> v4` creates empty tables and indexes in one authenticated migration transaction.

## Consequences

The catalog can restore cross-vault causality before it accepts new shared content. Historical epochs remain available as evidence. A selector collision, projection mismatch, chain gap, digest mismatch, identifier reuse, or fork fails closed.

## Validation

- fresh install and authenticated `v3 -> v4` migration;
- accepted operation and fork round trip;
- restart with cross-vault causal heads;
- corrupt projection, selector collision, gap, digest mismatch, and identifier reuse rejection;
- transaction rollback leaves both the log and materialized content unchanged.
