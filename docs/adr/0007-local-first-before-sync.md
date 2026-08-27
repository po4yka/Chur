# ADR-0007: Stabilize the Local Vault Before Sync and Sharing

- **Status:** Accepted
- **Date:** 2026-08-26
- **Decision owners:** @po4yka
- **Related:** [`../../ROADMAP.md`](../../ROADMAP.md), [`../sync/SYNC_PROTOCOL_V1.md`](../sync/SYNC_PROTOCOL_V1.md)

## Context

Sync and sharing add device identity, key distribution, rollback, conflicts, tombstones, recovery, malicious-server behavior, and recipient revocation. Building them before local formats, migrations, recovery, and media I/O stabilize would multiply ambiguity and audit scope.

## Decision

Initial production scope is a local recoverable vault. Delivery order:

1. specifications, vectors, Rust core, catalog, object format, FFI;
2. local photo vault and platform-key recovery;
3. video/audio, decoy, portable backup;
4. sync after dedicated protocol review;
5. sharing after separate key-distribution review.

Background work before sync is limited to local maintenance or explicitly designed ciphertext-only backup.

## Alternatives considered

### Cloud-first account and sync

Rejected: security model depends on immature local bytes and recovery.

### Use an existing generic cloud-drive format immediately

Rejected: does not provide Chur's media catalog, per-object/collection lifecycle, or decoy architecture.

### Implement sharing simultaneously with local storage

Rejected: expands review surface and can freeze weak key-distribution assumptions.

## Consequences

### Positive

- smaller auditable core;
- stable immutable object format becomes sync foundation;
- local users are not dependent on server availability;
- recovery/migration failures discovered before multi-device propagation.

### Tradeoffs

- delayed cloud convenience and collaboration;
- later protocol work must integrate with existing local model;
- temporary manual backup/export needs strong design.

## Security impact

No server/account recovery is assumed for local guarantees. Sync and sharing cannot bypass their own release gates.

## Compatibility impact

Local object/container/catalog versions are designed with future opaque transfer and collection envelopes but do not commit to a server API.

## Validation

- local release gates complete first;
- portable backup cross-platform proof;
- sync threat model/protocol/vectors/malicious-server tests before implementation release;
- sharing audit before multi-user availability.

## Follow-up

- Gate 3 in [`../assurance/RELEASE_GATES.md`](../assurance/RELEASE_GATES.md) must complete before any sync or sharing implementation is released;
- publish the cross-platform portable-backup restore proof that Gate 4 requires;
- keep every sync and sharing specification Proposed until its own gate has evidence, so an unreviewed protocol cannot become normative by being written down.
