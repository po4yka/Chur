# ADR-0023: Define the Signed Checkpoint and the Bootstrap Attestation

- **Status:** Accepted
- **Date:** 2026-08-27
- **Related:** [`../sync/ROLLBACK_PROTECTION.md`](../sync/ROLLBACK_PROTECTION.md), [`../sync/DEVICE_IDENTITY.md`](../sync/DEVICE_IDENTITY.md), [`../sync/SYNC_PROTOCOL_V1.md`](../sync/SYNC_PROTOCOL_V1.md), [`0014`](0014-observed-heads-causality-vector.md)

## Context

Checkpoints carried four mechanisms: new-device bootstrap, rollback detection after reinstall, fork reconciliation, and history compaction. No document defined one. `ROLLBACK_PROTECTION.md` §6 listed candidate contents, floated "issuer/quorum signatures" without a quorum size, and closed by saying the format and trust rule did not exist.

Bootstrap had the sharper problem. Rollback protection is a local high-water mark, so it is empty at first enrollment, and `SYNC_PROTOCOL_V1.md` §6 then fetched membership and checkpoint state from the server. Against the adversary the trust model admits, a server colluding with a revoked device, that server can present a pre-revocation membership and a truncated history, all of it authentic, and the new device has nothing to compare it against.

## Decision

- `CheckpointV1` is a standalone signed record, not an operation, holding the protocol version, vault binding, issuer identity and sequence, membership generation and commitment, a list of `CheckpointHeadV1` entries carrying `(device_id, device_sequence, operation_digest)` including the issuer's own head, a collection epoch commitment, a catalog state commitment, and the issuer's signature under a registered domain tag;
- one device signs. There is no quorum;
- a device trusts its own checkpoint as its freshness floor after state loss; another device's checkpoint is accepted only as a lower bound that raises the floor and never lowers it; a head at an accepted sequence with a different digest is fork evidence;
- the enrollment record gains `membership_generation` and `bootstrap_checkpoint_commitment`, a 32-byte BLAKE3-256 commitment to the issuer's current checkpoint, signed by the enrolling device and small enough to travel in the out-of-band QR payload. The new device sets its floor from the checkpoint that commitment names before it accepts any operation, and rejects server state below it;
- `Checkpoint` is removed from the operation-kind list.

## Alternatives considered

### Quorum-signed checkpoints

Rejected. A quorum rule needs a membership the receiver already trusts, and membership is exactly what is under attack after state loss, so the rule is circular. A lower-bound trust rule needs no quorum: raising a floor can only reject more server responses, never fewer.

### Delete checkpoints and put compaction and post-reinstall detection out of v1 scope

Rejected. Bootstrap and fork reconciliation both need a signed head summary regardless, and the record is nine fields, so scoping it out removes less than defining it. Attesting the heads directly in the enrollment record was also rejected: the head list is unbounded in the channel a QR code has to carry, while a 32-byte commitment is not.

## Consequences

### Positive

- first enrollment no longer starts at a null high-water mark, the freshness floor rests on an authorized device's signature rather than on server-supplied state, and compaction and post-reinstall detection have a record to point at.

### Tradeoffs

- a checkpoint is issued at the end of every sync session in which an operation was accepted, so a busy device writes and stores more signed records;
- the trust rule protects integrity, not availability: a device that accepts an overstated checkpoint from a hostile peer stalls until the fork or rollback state is resolved.

## Security impact

Affected invariants: SEC-042. Carrying `operation_digest` in every head means a checkpoint pins a branch and not only a length, so a checkpoint is fork evidence rather than a length claim. The residual gap of `ROLLBACK_PROTECTION.md` §9 is unchanged: no checkpoint proves the server hid nothing that no signer had seen.

## Compatibility impact

No checkpoints or enrollment records exist yet, so nothing migrates. `protocol_version` governs both records.

## Validation

- bootstrap where the server offers membership, epochs, or heads below the attested checkpoint, all rejected;
- a checkpoint that fails its enrollment commitment, rejected before any operation is accepted;
- a peer checkpoint naming an accepted sequence with a different digest, raising fork state.

## Follow-up

- define the `catalog_state_commitment` input, owned by `format/CATALOG_SCHEMA_V1.md`, and the `membership_commitment` input, owned by `sync/DEVICE_IDENTITY.md`; both are named here and constructed nowhere;
- freeze the checkpoint field widths with the operation record's.
