# ADR-0008: Freeze the Object Container v1 Public Layout

- **Status:** Accepted
- **Date:** 2026-08-27
- **Related:** [`../format/OBJECT_CONTAINER_V1.md`](../format/OBJECT_CONTAINER_V1.md), [`0002`](0002-independent-aead-chunks.md), [`../format/CANONICAL_ENCODING_V1.md`](../format/CANONICAL_ENCODING_V1.md)

## Context

`OBJECT_CONTAINER_V1.md` is the highest-authority document for the single most important artifact of Phase 1, and it described its own bytes as proposals. It carried no magic value, no fixed preamble length, a chunk framing that explicitly permitted implementations to "omit redundant fields", and an ordered commitment whose BLAKE3 input sequence was only sketched. The final commit record had no framing at all, so the chunk sequence had no defined end and the "no trailing records" rule could not be checked.

The deferral chain was circular. `CRYPTOGRAPHY.md` referred byte-level constants to the format specifications, the format specifications referred them to test vectors, and `test-vectors/v1/` referred back to the specifications while remaining an empty scaffold. Nothing in `chur-format` could be written, and Android, iOS, and the CLI had no shared byte to verify against.

## Decision

Freeze the public byte layout of `ChurObjectV1` in `OBJECT_CONTAINER_V1.md`:

- an 8-byte magic `CHUROBJ1`, reserved to this format;
- a fixed 28-byte `PublicPreambleV1` with explicit offsets and a required v1 value for every field except `manifest_record_length`;
- v1 constant assignments `container_version` `0x0001`, `canonical_encoding_profile` `0x0001`, `suite_id` `0x0001`, `chunk_record_profile` `0x0001`;
- a fixed 20-byte `ChunkRecordV1` header that keeps `chunk_index` and `plaintext_length` even though both are redundant, so a parser validates structure without decryption;
- a `FinalCommitRecordV1` that shares only the first four discriminator bytes and then diverges, so a reader dispatches on `record_type` and the chunk sequence gains a defined end;
- a canonical chunking rule: every non-final chunk carries exactly `chunk_size` plaintext, so one plaintext under one `chunk_size` has exactly one valid container and a seek is a computation rather than a scan;
- `manifest_commitment` as BLAKE3-256 over a domain tag, the manifest nonce, and the manifest ciphertext and tag;
- `ordered_chunk_commitment` as BLAKE3-256 over a domain tag followed by the exact wire bytes of every chunk record in ascending index order.

Object identifiers stay out of the public preamble.

## Alternatives considered

### Keep deferring the constants to the first vector generator

Rejected. The generator is part of `chur-format`, which cannot be written without these values, so the deferral had no exit.

### Omit the redundant chunk-record fields

Rejected. `chunk_index` and `plaintext_length` cost 12 bytes per chunk, about 0.005 percent at a 256 KiB chunk size, and they let a reader detect reordering, duplication, and truncation before spending an AEAD verification.

### Commit over chunk descriptors rather than over record bytes

Rejected. Hashing the record bytes as written is one sentence to specify, has no field-order ambiguity, and commits the framing along with the ciphertext.

### Bind the whole preamble into the manifest AAD

Deferred. It would change the manifest AAD tuple defined in `CRYPTOGRAPHY.md` §32, which belongs to a separate reconciliation. Freezing every preamble field to a constant reduces the unauthenticated surface to `manifest_record_length`, whose corruption already surfaces as a manifest AEAD failure.

## Consequences

### Positive

- `chur-format` can be implemented, and the first vectors can be generated;
- two independent implementations produce identical container bytes;
- the parser is self-validating and bounded before any key material is used;
- `manifest_commitment` is computable without a key, so structural verification does not require an unlocked vault.

### Tradeoffs

- 20 bytes of framing per chunk and 32 bytes of record header for the final commit;
- the ordered commitment is tied to the chunk record framing, so a future `chunk_record_profile` changes the commitment for identical plaintext;
- the commitment covers the sealed manifest record rather than the manifest plaintext, so re-sealing an identical manifest under a fresh nonce changes every chunk AAD and the final commit; `CRYPTOGRAPHY.md` §32 defers to this specification for the construction.

## Security impact

Affected invariants: SEC-013, SEC-014, SEC-017, SEC-018.

The chunk AAD and final commit both consume `manifest_commitment`, so its construction had to be fixed before any chunk could be sealed. Freezing every preamble field to an exact value turns each of them into a compared constant, which removes the attacker-mutable public surface that an unauthenticated preamble would otherwise expose. Requiring `chunk_index` to equal the number of records already read makes reordering and duplication a parse failure rather than a cryptographic one.

The import-journal durability ordering that this ADR left open, and the nonce-reuse risk on resume it carried, are resolved by [`0012`](0012-import-journal-durability-ordering.md).

## Compatibility impact

No container bytes exist yet, so nothing migrates. `container_version`, `canonical_encoding_profile`, `suite_id`, and `chunk_record_profile` remain the versioning path; a change to any frozen field requires a new value and a dual-reader policy, never a redefinition of v1.

## Validation

- zero-chunk and single-chunk vectors with byte-exact expected output;
- negative vectors for wrong magic, non-zero `flags` or `reserved`, wrong `public_header_length`, and `ciphertext_length` inconsistent with `plaintext_length`;
- reorder, duplicate, and truncate vectors that must fail before AEAD verification;
- Android, iOS, and CLI consumption of the same vector files.

## Follow-up

- the vault-descriptor and backup-package magics were allocated by [`0013`](0013-allocate-v1-format-constants.md) in `format/CANONICAL_ENCODING_V1.md` §15.1;
- freeze the approved chunk-size range, the maximum supported plaintext size, and the maximum chunk count;
- freeze the sealed plaintext schemas of the manifest and the final commit;
- generate and publish the vectors listed under Validation;
- reconcile the final-commit AAD in `CRYPTOGRAPHY.md` §38 with §3 of this specification, which binds `container_version` as well as `suite_id`.
