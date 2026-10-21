# ADR-0066: Pad Object Containers to Size Buckets

- **Status:** Accepted
- **Date:** 2026-10-21
- **Decision owners:** @po4yka
- **Related:** [`../format/OBJECT_CONTAINER_V1.md`](../format/OBJECT_CONTAINER_V1.md) §18, [`0008`](0008-freeze-object-container-v1-layout.md), [`0013`](0013-allocate-v1-format-constants.md), [`0020`](0020-set-the-v1-parser-limits.md), [`../CRYPTOGRAPHY.md`](../CRYPTOGRAPHY.md) §5 and §58

## Context

A v1 container discloses the exact plaintext length of its object. §8 of the container specification fixes every non-final chunk at `chunk_size`, and every record has a fixed overhead, so the file length alone gives the chunk count, the last chunk length, and the total plaintext length. `Geometry::derive` in `chur-format` performs this computation without a key. An attacker who copies the object store therefore learns the exact byte size of every photo, video, and document, which can identify a known file.

`CRYPTOGRAPHY.md` §5 lists ciphertext-size leakage as a non-goal "without optional padding", and §58 sketches object bucket padding without a rule. The v1 layout reserves no field for padding: `flags` and `reserved` must hold zero, and §3 makes any other value `OBJECT_CORRUPT`. A v1-compatible extension is therefore not possible.

## Decision

Allocate `container_version` `0x0002` for a bucket-padded container, and make it the version that every writer produces.

Version 2 keeps every byte layout of v1: the preamble, the manifest, the chunk record, the final commit record, the AAD tuples, the commitments, and the parser limits. Only two rules change:

- the chunk stream carries the object followed by zero bytes, up to `padded_length(total_plaintext_length)`; it is chunked by the canonical rule of §8, so a padding byte can share a record with object bytes or fill a padding-only record;
- the final commit keeps `total_plaintext_length` as the true object length, while `chunk_count` and `last_chunk_plaintext_length` describe the padded stream. A reader requires that `(chunk_count - 1) * chunk_size + last_chunk_plaintext_length` equals `padded_length(total_plaintext_length)`.

The bucket rule, with `e` the smallest exponent such that `2^e` is at least the length `L`:

```text
L = 0             -> 0
1 <= L <= 1 MiB   -> max(4 KiB, 2^e)
L > 1 MiB         -> L rounded up to a multiple of 2^max(20, e - 5)
```

Each padding byte must be `0x00`. A reader refuses any other value as `OBJECT_CORRUPT`, both when it reads a range from a record that holds padding and when it verifies the whole container.

Every chunk AAD binds `container_version`, so a v2 chunk does not authenticate under a v1 label and a v1 chunk does not authenticate under a v2 label. Readers accept both versions. The vault descriptor ceiling of `VAULT_DESCRIPTOR_V1.md` §6 accepts `0x0002`. A new vault records it, and an existing vault raises its ceiling in one descriptor generation before its first v2 container is written. `chur_object_format_max` returns `2`.

## Storage cost

| Object size | Buckets | Largest padding |
| --- | --- | --- |
| 1 B to 4 KiB | one, 4 KiB | under 4 KiB |
| 4 KiB to 1 MiB | powers of two, 8 buckets | under half of the bucket, under 512 KiB |
| 1 MiB to 32 MiB | whole MiB, 31 buckets | under 1 MiB |
| above 32 MiB | 16 buckets in each power-of-two interval | under one sixteenth of the object, 6.25 % |

Expected cost for typical media: a 3 MB photo pays on average about 0.5 MiB, about 15 %; a 100 MB video about 2 MiB, about 2 %; a thumbnail below 64 KiB at most the next power of two. The padding adds records only at the end of the stream, so the cost is also the extra write and read time of those bytes during import, verification, backup, and sync transfer. No bucket passes the next power of two, so an object inside the 1 TiB bound of §16 stays inside it when padded. With a chunk size that is not a power of two, the chunk-count bound applies to the padded stream, and the importer checks it before the first chunk.

## Alternatives considered

### A v1-compatible extension

Rejected. The v1 preamble has no reserved field that a reader ignores, and the frozen manifest and final commit have no optional field. Any change is a new version under ADR-0008.

### Record a padding length in a new manifest or final-commit field

Rejected. A new field changes the frozen 128-byte final commit and the frozen manifest lengths. The bucket of a known length is a pure function, so the existing fields already record everything a reader must check.

### Random-length padding

Rejected. §58 forbids unauthenticated and random padding. A deterministic bucket gives one valid container per plaintext and chunk size, which keeps the v1 reproducibility property.

### Padmé buckets at every size

Rejected. Padmé has a smaller relative cost for small objects, but it makes many more buckets below 1 MiB. A power-of-two rule below 1 MiB discloses fewer bits for thumbnails and small documents, and its absolute cost there is less than 512 KiB.

## Consequences

### Positive

- the file length discloses only the bucket and the chunk size, not the exact object size;
- objects of one stream kind in one bucket have equal container lengths;
- `read_range`, `StreamReader::size`, the catalog `plaintext_size`, backup inventories, sync transfers, and every size shown to the user keep the true object length, because the authenticated final commit carries it.

### Tradeoffs

- the storage and I/O cost in the table above;
- the chunk size, the stream kind, and the bucket still leak, and an object of 0 bytes is still visible as empty;
- an application build that reads only v1 refuses a vault whose ceiling is `0x0002`.

## Security impact

Affected invariants: SEC-013, SEC-014, SEC-017. Padding bytes are inside the chunk AEAD and inside the ordered commitment, so they are authenticated like object bytes. The relation between the final commit and the bucket makes a removed padding record, a truncated padding record, and an extra padding byte an `OBJECT_CORRUPT` failure. A padding chunk takes a journal reservation under §14.2 before it is sealed, so no `(nonce_prefix, chunk_index)` pair is used twice.

## Compatibility impact

Existing v1 containers are not rewritten and keep reading. A v2 container is a new artifact under a new version value, so no accepted v1 byte is reinterpreted. Only writers change.

The peer protocol carries no container-version negotiation. A sync session therefore requires that both peers run a build that reads `0x0002`. The peer protocol version is raised in the same release, so a build that reads only v1 does not complete a session with a build that writes v2. If an older peer receives a v2 container all the same, its reader refuses the preamble as `UNSUPPORTED_VERSION` before any key is used. The download does not verify, the object is not activated, and the transfer fails with that status. It keeps no partial object, and the container on the sending peer is not changed. A restored backup keeps the container-version range of its source vault, so an older build also refuses a restored vault as a whole.

## Validation

- vectors `object-v2-one-padded-chunk`, `object-v2-padding-only-record`, and the rejections `object-v2-non-zero-padding-byte`, `object-v2-padding-short-of-bucket`, and `object-v2-padding-past-bucket` in `test-vectors/v1/`;
- `chur-format` tests for the bucket edges, the round trip at each edge, equal container lengths in one bucket, v1 reading beside v2, a version relabel, truncation, extension, and every deviation above.

## Follow-up

- benchmark the import and verification cost of the padding on the lowest supported device, as §58 requires before a padding profile is the default;
- a re-encryption path that converts existing v1 containers to v2 is not part of this decision.
