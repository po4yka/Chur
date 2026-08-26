# ADR-0005: Isolate Real and Decoy Vaults Cryptographically

- **Status:** Accepted
- **Date:** 2026-08-26
- **Related:** [`../security/DECOY_VAULT.md`](../security/DECOY_VAULT.md)

## Context

A UI filter over one vault would allow code, catalog, keys, caches, and errors to reveal real content during coercive inspection or implementation failure. Chur also must avoid claiming an undetectable hidden filesystem volume.

## Decision

Real and decoy are independent vault identities with separate:

- random roots and all derived/random keys;
- key slots, platform aliases/items, and recovery secrets;
- catalogs and object namespaces;
- caches, navigation, sessions, and handle generations;
- backup/sync identity by default.

The session gate returns an opaque session rather than a general `isDecoy` flag.

## Alternatives considered

### Single vault with `isDecoy` column/filter

Rejected: same root/catalog and easy accidental leakage.

### Single root with separate collection key

Rejected: root compromise/cross-reference and shared platform/recovery state.

### Filesystem hidden-volume construction

Not selected: significantly different threat model and forensic guarantees; unsafe to imply without specialized design/audit.

## Consequences

### Positive

- strong data/state isolation;
- decoy is a real encrypted vault;
- ordinary feature code cannot access sibling identity;
- independent recovery/deletion.

### Tradeoffs

- duplicated storage/configuration;
- physical size/aliases/I/O may reveal extra encrypted data;
- more migration/platform test combinations;
- sync may weaken deniability/correlation.

## Security impact

External credential failure should not reveal which identity exists. Product copy must state forensic limitations.

## Compatibility impact

Each identity has its own descriptor and version/migration path. Combined backups are forbidden by default.

## Validation

- isolation tests across storage, aliases, caches, logs, navigation, backup, migration;
- timing/error review;
- process-death and lock tests;
- no semantic `real`/`decoy` physical labels.
