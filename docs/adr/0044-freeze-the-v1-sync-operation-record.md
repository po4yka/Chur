# ADR-0044: Freeze the v1 Sync Operation Record

- **Status:** Accepted
- **Date:** 2026-08-29
- **Decision owners:** @po4yka
- **Related:** [`../sync/OPERATION_LOG.md`](../sync/OPERATION_LOG.md), [`../sync/ROLLBACK_PROTECTION.md`](../sync/ROLLBACK_PROTECTION.md), [`../format/CANONICAL_ENCODING_V1.md`](../format/CANONICAL_ENCODING_V1.md), [`0022`](0022-freeze-operation-chain-hash-and-identifier.md)

## Context

ADR-0022 froze the operation field set, chain hash, identifier, and cleartext privacy boundary. It deliberately left the field widths, encrypted-payload framing, signing input, and parser limits to the Gate 5 implementation. Without those values, two implementations cannot produce the same signature or safely bound a server record before allocation.

The omission strategy was also listed as an open cryptographic decision even though the accepted causality vector and checkpoint design already define the strongest v1 guarantee: a client detects missing known causes and rollback below a trusted floor, but cannot prove that the server did not hide a branch that no accepted operation or checkpoint names.

## Decision

- `protocol_version` is `u16` and v1 is `0x0001`; identifiers and selectors are fixed 16-byte values; sequences are `u64`; hashes are fixed 32-byte BLAKE3-256 values; signatures are fixed 64-byte Ed25519 values;
- `observed_heads` is a `u32` count followed by the sorted 24-byte entries ADR-0014 defines, with at most 31 entries;
- `encrypted_payload` is one variable-byte field. Its bytes are a 24-byte XChaCha20-Poly1305 nonce followed by ciphertext and its 16-byte tag. The plaintext is at most 1 MiB, so the field is between 40 and 1,048,616 bytes;
- the exact wire record is the fields of `OperationV1` in `OPERATION_LOG.md` §2 with no domain tag stored. The Ed25519 input is `CHUR\x00SYNC\x00OPERATION\x00V1` followed by the exact wire fields through `encrypted_payload`, excluding the signature;
- the payload AAD uses the same domain tag followed by the exact wire fields through `key_selector`, excluding `encrypted_payload` and the signature. The AEAD and signature are different primitives over explicitly different field lists;
- a response carries at most 256 operations and at most 16 MiB of record bytes. These transport bounds do not weaken the smaller pending and locked-staging bounds;
- v1 omission detection is the composition of per-device chain gaps, signed observed heads, and trusted checkpoint floors. Hiding a branch that none of those values names remains an explicit residual limitation that requires cross-device comparison or a future witness service.

## Alternatives considered

### Generic serialization

Rejected. A serializer default can change field representation or accept alternate encodings. Sync signatures require one byte representation shared by Rust, Android, iOS, and the CLI.

### Store the nonce as another outer field

Rejected. The server does not need it for routing, and the signed `encrypted_payload` already binds it. Keeping nonce and ciphertext together makes the encrypted value one bounded field without changing the cleartext field set ADR-0022 froze.

### Claim complete omission detection

Rejected. A server can hide an entire unobserved branch. No local hash chain or single-signer checkpoint proves global completeness.

## Consequences

### Positive

- encoders, parsers, signature implementations, and protocol vectors have one byte contract;
- a declared payload length is rejected before allocation and cannot force an unbounded batch;
- the security claim matches what the protocol can prove.

### Tradeoffs

- a logical operation larger than 1 MiB must be split into several operations;
- stronger global omission detection remains future work.

## Security impact

Affected invariants: SEC-040, SEC-041, SEC-042. The record has one canonical signing input, bounded attacker-controlled lengths, and no server-visible operation kind or private identifier. The residual omission limit is documented instead of being presented as a guarantee.

## Compatibility impact

No sync operation exists yet, so nothing migrates. Changing any field, width, limit that changes accepted bytes, or signing/AAD list requires a new protocol version.

## Validation

- positive and negative byte-exact operation vectors;
- maximum and over-limit observed-head and payload cases;
- signature, nonce, payload, and AAD substitution failures;
- replay, gap, rollback, fork, and malicious omission scenarios.

## Follow-up

- implement the explicit codec and limits in `chur-sync-protocol`;
- freeze the encrypted logical payload schemas before emitting them;
- publish the operation vectors and run the malicious-server harness.
