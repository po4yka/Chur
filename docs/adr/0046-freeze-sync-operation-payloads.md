# ADR-0046: Freeze the v1 Sync Operation Payloads

- **Status:** Accepted
- **Date:** 2026-08-29
- **Decision owners:** @po4yka
- **Related:** [`../sync/OPERATION_PAYLOAD_V1.md`](../sync/OPERATION_PAYLOAD_V1.md), [`../sync/OPERATION_LOG.md`](../sync/OPERATION_LOG.md), [`0021`](0021-freeze-conflict-tie-break-and-set-semantics.md), [`0044`](0044-freeze-the-v1-sync-operation-record.md)

## Context

ADR-0044 froze the signed encrypted operation envelope but left its plaintext undefined. Without a discriminant registry and exact bodies, clients can decrypt the same record and still disagree about its meaning, parser bounds, conflict subject, or collection epoch. The missing timestamp in a deletion also made the retained-tombstone deadlines impossible to calculate.

## Decision

- `OperationPayloadV1` has one common header: `payload_version:u16`, `operation_kind:u8`, `collection_id:bytes[16]`, and `collection_epoch:u64`, followed by the exact body selected by the kind;
- kind values `0x01` through `0x10` are allocated in the order listed in `OPERATION_PAYLOAD_V1.md`; unknown values fail closed;
- root-domain membership operations use `collection_id = vault_id` and `collection_epoch = 0`. Collection operations require a non-zero epoch;
- add operations use the outer `operation_id` as their observed-remove token. Remove operations carry a sorted unique list of the exact tokens they observed;
- deletion carries `authored_at_ms:u64` only for retention scheduling. It is never an ordering, authorization, freshness, or conflict input;
- enrollment, revocation, collection-key, and object-key envelope bodies embed the complete canonical records their owning specifications already define. A receiver validates that every repeated identifier, epoch, sequence, issuer, and vault binding matches the outer operation and common payload header;
- nested counts and byte strings have explicit bounds. No v1 payload is compressed or contains an extension map.

## Alternatives considered

### A generic map or serializer

Rejected. Alternate key order, unknown-field behavior, and serializer defaults would create several signed meanings for one logical operation.

### One generic subject/body record

Rejected. Unused fields and opaque bodies make cross-field validation ambiguous at the trust boundary. A closed discriminated union is smaller than carrying defensive rules for every impossible field combination.

### Server timestamp for tombstone retention

Rejected. The server is untrusted and cannot choose when client security evidence expires. The signed author timestamp is useful only as a conservative local cleanup input; the hard checkpoint floor remains the resurrection defense.

## Consequences

### Positive

- Rust, Android, iOS, and the CLI can publish byte-exact payload vectors;
- the convergence engine receives validated semantic operations rather than untyped bytes;
- the server still sees only the outer opaque selector and ciphertext.

### Tradeoffs

- adding a logical operation or metadata field requires a new allocated value;
- an incorrect device clock can delay or advance a cleanup candidate, but cannot make a stale operation acceptable or lower a checkpoint floor.

## Security impact

Affected invariants: SEC-034, SEC-040, SEC-041, SEC-042. The change closes the decrypted parser boundary, preserves metadata confidentiality, and makes deletion retention measurable without trusting the server clock.

## Compatibility impact

No payload has shipped. A byte or semantic change to an allocated kind requires a new `payload_version`; an additive kind may use the next free discriminant when every unsupported reader still fails closed.

## Validation

- byte-exact positive vectors for every kind;
- truncation, trailing-byte, unknown-kind, duplicate-token, unsorted-token, nested-record mismatch, and size-limit negatives;
- permutation tests over scalar, observed-remove, delete, and restore operations.

## Follow-up

- implement the explicit codec and semantic validation in `chur-sync-protocol`;
- publish the payload vectors and consume them through the malicious-server harness.
