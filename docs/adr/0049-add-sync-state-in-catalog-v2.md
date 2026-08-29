# ADR-0049: Add Durable Sync State in Catalog v2

- **Status:** Accepted
- **Date:** 2026-08-29
- **Decision owners:** @po4yka
- **Related:** [`../format/CATALOG_SCHEMA_V2.md`](../format/CATALOG_SCHEMA_V2.md), [`../format/VAULT_DESCRIPTOR_V1.md`](../format/VAULT_DESCRIPTOR_V1.md), [`../sync/ROLLBACK_PROTECTION.md`](../sync/ROLLBACK_PROTECTION.md)

## Context

Phase 3 needs accepted membership, log heads, fork evidence, checkpoint floors, operation bytes, epoch rotations, and private identity envelopes to survive process death. Storing them only in memory would let restart erase freshness and fork state. Adding tables to catalog v1 without changing `catalog_format_version` would make two different physical schemas claim the same version and would leave existing v1 vaults without required tables.

The v1 vault descriptor already has `MigrationDescriptorV1`, a `MIGRATING` state, catalog-version fields, generations, and an opaque checkpoint identifier. No new descriptor format is needed.

## Decision

- allocate `catalog_format_version` `0x0002` for the v1 schema plus the sync tables of `CATALOG_SCHEMA_V2.md`;
- `VaultDescriptorV1` remains the descriptor format and accepts catalog versions 1 and 2. Its catalog sub-descriptor stores the actual catalog format version instead of emitting a constant;
- a v1-to-v2 upgrade installs an authenticated descriptor in `MIGRATING` state before changing SQLCipher pages. Its migration descriptor names descriptor 1 to 1, catalog 1 to 2, the next migration generation, and a random checkpoint identifier;
- one SQLCipher transaction creates every v2 table and index, decodes active object envelopes into the new epoch projection, and changes the catalog's recorded version to 2. No existing row is rewritten;
- after a full WAL checkpoint, the implementation recomputes the catalog-header commitment and atomically installs an `ACTIVE` descriptor at the next descriptor generation with catalog version 2 and no migration descriptor;
- restart with `MIGRATING` plus catalog v1 reruns the idempotent migration transaction. Restart with `MIGRATING` plus catalog v2 performs the checkpoint and final descriptor install. Other version pairs fail closed;
- downgrade does not open catalog v2. Backup restore runs the same version checks and migration before the restored descriptor becomes active;
- sync state and its operation records live only in SQLCipher. Locked background staging remains the separate bounded disposable directory of `SYNC_PROTOCOL_V1.md` §7.

## Alternatives considered

### Add optional tables to catalog v1

Rejected. Version 1 would no longer identify one physical schema, and an older binary could open and mutate a catalog whose sync invariants it does not understand.

### Store freshness state in public files

Rejected. It would duplicate Rust-owned catalog authority and expose device relationships and causal heads outside SQLCipher.

### Introduce VaultDescriptorV2

Rejected. The v1 descriptor already carries the catalog version and migration state needed for this change.

## Consequences

### Positive

- accepted heads, checkpoint floors, and fork evidence survive restart atomically with materialized state;
- older binaries fail on an explicit catalog version instead of silently dropping sync state;
- migration reuses an implemented descriptor record rather than adding another journal format.

### Tradeoffs

- the first Phase 3 unlock upgrades the private catalog even if sync is not enabled;
- migration touches the descriptor and SQLCipher file and therefore needs crash-injection coverage at both atomic boundaries.

## Security impact

Affected invariants: SEC-041 and SEC-042. Rollback and fork state is durable, and locked staging cannot advance it. The descriptor is installed as migrating before catalog mutation, so a crash cannot present a v2 catalog as v1 active state.

## Compatibility impact

Catalog v1 upgrades forward to v2. Descriptor format, object containers, key slots, and backup framing remain v1. A binary that supports only catalog v1 returns `MIGRATION_REQUIRED` for catalog v2.

## Validation

- crash before and after installing the migrating descriptor, SQL transaction commit, WAL checkpoint, and active descriptor rename;
- repeat every recovery state and reach one catalog v2/active descriptor result;
- reject active descriptor/catalog version disagreement and every unsupported version pair;
- restore a catalog v1 backup and migrate it before activation;
- verify an older reader does not open catalog v2.
