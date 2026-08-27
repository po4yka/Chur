# ADR-0022: Freeze the Operation Chain Hash, Identifier, and Cleartext Field Set

- **Status:** Accepted
- **Date:** 2026-08-27
- **Related:** [`../sync/OPERATION_LOG.md`](../sync/OPERATION_LOG.md), [`../sync/SERVER_TRUST_MODEL.md`](../sync/SERVER_TRUST_MODEL.md), [`../format/CANONICAL_ENCODING_V1.md`](../format/CANONICAL_ENCODING_V1.md), [`0014`](0014-observed-heads-causality-vector.md)

## Context

The hash chain is the whole anti-fork and anti-reordering mechanism of the operation log, and `OPERATION_LOG.md` §4 wrote it as `previous_operation_hash = hash(canonical prior signed record)`. No document named the algorithm, the digest length, a domain tag, or the value a device's first operation carries. `payload commitment` sat in the record with no definition at all. §5 left `operation_id` open between random and derived, and the derived option cannot be implemented as written, because the identifier is a field of the record whose signed bytes would derive it.

Separately, §1 promises to expose no private payload to the server while §2 kept `operation_kind` and the collection context beside `encrypted_payload` in the clear, so the server could read a timestamped per-device stream of delete, favorite, rename, and tag events and attribute each to a collection. §6 answered that with a "should".

## Decision

- `operation_digest = BLAKE3-256("CHUR\x00SYNC\x00OPERATION-CHAIN\x00V1" || the prior record's exact wire bytes, signature included)`, 32 bytes. `previous_operation_hash` carries it, and the genesis value at `device_sequence` 1 is 32 zero bytes;
- the tag is allocated in the constant registry §15.5 in this change;
- `payload commitment` is deleted. The AEAD tag authenticates the payload against the outer AAD and the signature covers `encrypted_payload` byte for byte;
- `operation_id` is 16 random bytes, a deduplication key only, never derived and never a content hash;
- the cleartext outer record is closed to ten fields. `operation_kind`, the collection, the epoch, and every object, album, tag, and device identifier move inside `encrypted_payload`. An opaque random 16-byte `key_selector` per `(collection, epoch)` stays outside for key selection;
- the payload AAD is the cleartext fields excluding the ciphertext and the signature, so nothing inside the ciphertext is ever named by the AAD that authenticates it.

## Alternatives considered

### Derive `operation_id` from the signed bytes

Rejected. It is circular as specified, and breaking the circle takes a two-pass encode whose only gain, deduplicating a re-signed identical operation, is already covered by comparing complete canonical bytes.

### SHA-256 for the chain, or a genesis derived from the enrollment record

Rejected. Suite `0x0001` names BLAKE3-256 as the commitment primitive, and a second hash for one field is a second primitive to review. A derived genesis requires the enrolling device to hold server-delivered enrollment bytes before its first operation, while a zero constant requires nothing and cannot collide with a real digest. Keeping the collection identifier outside for routing was rejected on the same page: the server routes on the opaque selector just as well, and collection attribution of every action was the leak itself.

## Consequences

### Positive

- two independent implementations can compute the same chain, and the digest doubles as the conflict tie-break of [`0021`](0021-freeze-conflict-tie-break-and-set-semantics.md);
- the server sees no action kind and no collection identity, so `OPERATION_LOG.md` §1 is now true.

### Tradeoffs

- a receiver must decrypt before it knows an operation's kind, so kind-based filtering is a post-decryption step, and the server can still group operations by `key_selector` and read the causal graph in `observed_heads`; both leaks are enumerated in `SERVER_TRUST_MODEL.md` §8.

## Security impact

Affected invariants: SEC-040, SEC-041, SEC-042. Hashing the prior record including its signature makes a re-signed variant of an accepted operation break the chain. Rejecting a non-zero genesis link and an all-zero later link removes the two ways a fabricated chain start could be presented. Moving the kind and collection inside the ciphertext removes a behavioural profile of the private library from the untrusted server.

## Compatibility impact

No operations exist yet, so nothing migrates. `protocol_version` governs the record. Adding a cleartext field later is a version change, never an extension of v1.

## Validation

- chain vectors including a genesis operation, a non-zero genesis link, and an all-zero link at sequence 2, all rejected;
- a re-signed copy of an accepted operation rejected by the next record's chain check;
- a conformance test asserting that no field outside the ten cleartext ones appears in an encoded record.

## Follow-up

- freeze the record's field widths and allocate `CHUR\x00SYNC\x00OPERATION\x00V1` for the signing and AAD domain, which `OPERATION_LOG.md` §2 owns.
