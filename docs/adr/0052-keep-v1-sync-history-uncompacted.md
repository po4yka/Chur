# ADR-0052: Commit Collection Epochs and Keep V1 Sync History Uncompacted

- **Status:** Accepted
- **Date:** 2026-08-29
- **Decision owners:** @po4yka
- **Related:** [`../sync/ROLLBACK_PROTECTION.md`](../sync/ROLLBACK_PROTECTION.md), [`../sync/OPERATION_LOG.md`](../sync/OPERATION_LOG.md), [`0023`](0023-define-signed-checkpoint-and-bootstrap-attestation.md)

## Context

`CheckpointV1` includes `collection_epoch_commitment` and `catalog_state_commitment`, but no source defines their inputs. A materialized-state commitment is useful only with a canonical portable snapshot that a new device can verify and restore. Catalog v2 has no such snapshot. Hashing SQLCipher pages would be platform-specific, and hashing a second ad hoc catalog encoding would create another source of truth.

## Decision

- `collection_epoch_commitment` commits to every current `(collection_id, current_epoch)` pair in ascending collection identifier order under `CHUR\x00SYNC\x00COLLECTION-EPOCHS\x00V1`;
- V1 does not compact signed operation history;
- `catalog_state_commitment` is 32 zero bytes and means that no compacted catalog snapshot exists;
- V1 readers reject a non-zero catalog state commitment;
- tombstone retention can permit object ciphertext and local-row garbage collection, but it does not remove the signed tombstone operation.

## Consequences

Bootstrap remains simple: a new or stale device reconstructs state from authenticated operation chains through the checkpoint heads. Storage grows with operation history. A later protocol version can add compaction only after it freezes a canonical snapshot, a separate state-commitment domain, migration rules, and vectors.

## Security impact

The design does not claim that local SQLCipher bytes are portable state. Retaining authenticated tombstones prevents stale-device resurrection after physical object deletion. The signed epoch commitment lets bootstrap reject an epoch set below the issuer's checkpoint.

## Validation

- identical sorted epoch lists produce the same commitment;
- order changes, duplicates, and epoch zero are rejected;
- a V1 checkpoint with a non-zero catalog state commitment is rejected;
- object GC leaves the signed tombstone history available.
