# ADR-0058: Record the Grant Acceptance Freeze in Catalog v5

- **Status:** Accepted
- **Date:** 2026-09-08
- **Decision owners:** @po4yka
- **Related:** [`../format/CATALOG_SCHEMA_V5.md`](../format/CATALOG_SCHEMA_V5.md), [`../sync/COLLECTION_MEMBERSHIP.md`](../sync/COLLECTION_MEMBERSHIP.md), [`0055`](0055-add-sharing-state-in-catalog-v3.md)

## Context

`COLLECTION_MEMBERSHIP.md` §5 freezes grant acceptance for a collection when one grant identifier arrives again with different bytes. The catalog rejected only the conflicting grant and kept no record of the conflict, so a reopen accepted new grants again and the freeze the specification names did not exist. Adding state without a new catalog version would give v4 two physical schemas.

## Decision

- Catalog format `0x0005` adds only a durable `grants_frozen` column on `sharing_collections`.
- The freeze is recorded in its own committed transaction once the conflicting reuse is observed, because the application transaction that rejects the grant rolls back.
- A frozen collection refuses every new grant; identical replay of a stored grant stays idempotent.
- The freeze is client state about a detected security conflict. It carries no canonical meaning, never enters an authenticated record, and no v1 surface unfreezes it.
- Migration `v4 -> v5` adds the column with its default and its `0`/`1` check in one authenticated migration transaction.

## Consequences

A grant-identifier collision stops grant acceptance for the collection across restarts until a human decides otherwise. Honest replay and concurrent head changes never set the freeze, because the trigger is the observed reuse of one identifier with different bytes.

## Validation

- fresh install and authenticated `v4 -> v5` migration;
- conflicting identifier reuse records the freeze and rejects the grant;
- a frozen collection refuses a different new grant while identical replay stays `Duplicate`;
- the freeze survives reopen.
