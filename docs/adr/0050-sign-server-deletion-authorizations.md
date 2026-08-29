# ADR-0050: Sign Opaque Server Deletion Authorizations

- **Status:** Accepted
- **Date:** 2026-08-29
- **Decision owners:** @po4yka
- **Related:** [`../sync/SYNC_PROTOCOL_V1.md`](../sync/SYNC_PROTOCOL_V1.md), [`../sync/SERVER_TRUST_MODEL.md`](../sync/SERVER_TRUST_MODEL.md), [`../sync/OPERATION_LOG.md`](../sync/OPERATION_LOG.md)

## Context

An object tombstone and its `store_id` are inside the encrypted operation payload. The server can verify the outer device signature but cannot decrypt the payload or prove that a separate cleartext `store_id` belongs to that tombstone. Authorizing deletion with only a transport token would let a stolen token destroy ciphertext, contrary to `SYNC_PROTOCOL_V1.md` §9. Adding `store_id` to the clear operation record would reveal per-operation object routing and break the closed outer record of ADR-0022.

The self-hosted server also needs one authenticated request for whole-account deletion. It cannot use a tombstone because an account is not a catalog object.

## Decision

- freeze `ServerDeletionAuthorizationV1` in `SYNC_PROTOCOL_V1.md` §9.1;
- the record signs only opaque identifiers: request, vault, authoring device, target kind, target, and an authorizing operation digest;
- object deletion carries the opaque `store_id` and the non-zero digest of an already uploaded signed tombstone operation. The server verifies that the digest exists but does not claim it can decrypt or classify the operation;
- account deletion carries the vault identifier as its target and a zero operation digest;
- the server verifies the signature under the current active device-membership chain. A transport token may upload and fetch, but cannot create this signature;
- `request_id` makes retries idempotent. Reusing it with different canonical bytes is rejected;
- the server keeps the authorization as its deletion audit record, subject to the operator retention limit. An acknowledgment is still not proof of erasure;
- the operation outer record remains unchanged and no private object identifier becomes visible.

## Alternatives considered

### Trust the transport session

Rejected. A stolen bearer token could delete every stored object without a device private key.

### Attach a `store_id` beside the encrypted tombstone

Rejected. The operation signature does not bind an extra transport field, and moving it into the signed clear record adds object-level routing metadata to every peer.

### Let the server decrypt tombstones

Rejected. It would give the server collection or operation keys and violate the server trust model.

## Consequences

### Positive

- deletion requires a current device signature and is safe against a transport-token-only attacker;
- object and account deletion share one bounded canonical record;
- operation privacy and the immutable object protocol stay unchanged.

### Tradeoffs

- the server cannot prove that the encrypted authorizing operation is semantically a tombstone. A malicious active device can request deletion, which is already within the power of an active device that can author a tombstone;
- clients and servers must retain one more signed record type and publish vectors for it.

## Security impact

Affected invariants: SEC-040, SEC-041, and SEC-042. The authorization binds deletion to an active device key without exposing encrypted payload fields. Server acknowledgment remains a reliability signal, not cryptographic erasure proof.

## Compatibility impact

This is a new Phase 3 record and endpoint. No released sync protocol exists, so there is no wire migration. Readers reject any version or target kind not allocated by §9.1.

## Validation

- canonical round-trip and signature vector;
- reject wrong vault, target, operation digest, device key, version, kind, and reused request identifier;
- prove a transport token alone cannot delete;
- idempotently repeat the exact request;
- delete an object only after its operation digest exists, and delete an account only with the zero-digest account form.
