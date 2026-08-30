# ADR-0056: Add Collection-Scoped Sharing Operation Streams

- **Status:** Accepted
- **Date:** 2026-08-30
- **Decision owners:** @po4yka
- **Related:** [`../sync/COLLECTION_OPERATION_LOG.md`](../sync/COLLECTION_OPERATION_LOG.md), [`../sync/OPERATION_LOG.md`](../sync/OPERATION_LOG.md), [`../sync/COLLECTION_MEMBERSHIP.md`](../sync/COLLECTION_MEMBERSHIP.md), [`0044`](0044-freeze-the-v1-sync-operation-record.md)

## Context

`OperationV1` has one device chain for one identity vault. Its observed heads contain only device identifiers because every device belongs to that vault. A shared collection has authors from several identity vaults. Reusing the vault log would omit unrelated records from a device chain, and reusing its observed-head format would make cross-vault causes ambiguous.

The server must route shared ciphertext without a clear collection identifier. A collection epoch already has one opaque `key_selector`, and authorized recipients can derive it from the granted collection key.

## Decision

- `CollectionOperationV1` is the canonical record in `COLLECTION_OPERATION_LOG.md`.
- Its issuer identity vault and device are clear signed fields. Its collection, epoch, action, and content identifiers stay in the encrypted payload.
- Each `(key_selector, issuer identity vault, issuer device)` has an independent chain. A new collection epoch has a new selector and starts new chains at sequence one.
- An observed head identifies both the issuer identity vault and device. Heads are sorted by that pair.
- The stream carries content kinds `0x01` through `0x0C` and object-key rewrap kind `0x10`. Identity membership, collection membership, grants, and epoch creation stay in their existing signed security-operation paths.
- The reference server maps an opaque selector to collection authorization when it accepts a signed membership or grant pair. It does not decrypt a collection operation.
- A recipient stores and validates collection streams separately from its own identity-vault operation log.

## Consequences

Shared authors can express causal relationships across identity vaults without receiving unrelated vault operations. The server can relay by an opaque epoch selector and does not learn the collection identifier from a content record. Epoch rotation resets stream chains, so a removed recipient cannot request the new stream without a new grant.

`OperationV1` stays unchanged. The new record, log, catalog tables, server endpoints, vectors, and mobile boundaries require separate validation.

## Validation

- canonical encode, decode, encryption, signature, and selector negatives;
- per-issuer sequence, gap, replay, identifier reuse, and fork cases;
- cross-vault observed-head ordering and missing-cause handling;
- source, `READ`, `CONTRIBUTE`, `MANAGE_MEMBERS`, and revoked-author checks;
- epoch reset, multi-recipient convergence, restart recovery, and malicious relay tests.
