# ADR-0020: Set the v1 Parser Limits

- **Status:** Accepted
- **Date:** 2026-08-27
- **Related:** [`../format/OBJECT_CONTAINER_V1.md`](../format/OBJECT_CONTAINER_V1.md), [`../format/VAULT_DESCRIPTOR_V1.md`](../format/VAULT_DESCRIPTOR_V1.md), [`../format/BACKUP_FORMAT_V1.md`](../format/BACKUP_FORMAT_V1.md), [`../security/KEY_SLOTS.md`](../security/KEY_SLOTS.md), [`../interop/MEDIA_PIPELINE.md`](../interop/MEDIA_PIPELINE.md), [`0008`](0008-freeze-object-container-v1-layout.md)

## Context

Every "Parser limits" section listed the categories of limit and no values. `CANONICAL_ENCODING_V1.md` §10 delegates maximums to the focused specifications and the focused specifications delegated to nothing, so two implementations would accept different inputs and a container written on Android could be refused on iOS. `ERROR_MODEL.md` defines `RESOURCE_LIMIT_EXCEEDED` as exceeding "policy" with no policy to compare against, `assurance/FUZZING.md` requires allocation limits to be exercised with no threshold to assert, and `ROADMAP.md` Phase 0 exit requires "parser limits specified and tested".

## Decision

Set concrete v1 values in each owning specification rather than in a new central document, because §10 already delegates there.

- object container: `chunk_size` 64 KiB to 8 MiB and a multiple of 4096, `chunk_count` at most 1048576, `total_plaintext_length` at most 1 TiB, peak read buffer `2 * chunk_size + 16`, nesting depth 1;
- vault descriptor: `descriptor_length` 220 to 65536, 1 to 16 slots, `slot_body_length` 16 to 4096 with 16384 total, migration descriptor exactly 32 bytes, nesting depth 2;
- backup package: `record_count` 2 to 1048576, at most 1048576 stream inventory entries, manifest record at most 16 MiB, zero or one `age` layer, no compression;
- key slots: at most 16 slots, `slot_body` 16 to 4096, nonce 24 bytes, wrapped root 48 bytes, Argon2 bounds as already stated in `CRYPTOGRAPHY.md` §18.3 and checked before any derivation;
- object-key envelope: record exactly 142 bytes, at most 64 envelopes per object with at most 4 active;
- media: 16384 px per dimension and 67108864 px total for a still image, 4 hours of duration, 8 tracks, 256 MiB of decode buffer, and baseline JPEG derivatives at 320, 640, and 2048 px long edges;
- catalog: object, collection, album, tag, and revision counts in a new `CATALOG_SCHEMA_V1.md` §21.

## Alternatives considered

### One central `LIMITS_V1.md`

Rejected. `CANONICAL_ENCODING_V1.md` §10 delegates to the owning specification, so a central table would be a second location for the same rule and would drift from the field it bounds.

## Consequences

### Positive

- `RESOURCE_LIMIT_EXCEEDED` has a comparable policy, fuzz targets have thresholds to assert, and the Phase 0 exit criterion can be evidenced;
- the bounds interlock: 1 MiB chunks times 1048576 chunks is exactly the 1 TiB object cap, and the 16-slot cap is the same number in the descriptor and in the slot specification.

### Tradeoffs

- a media file above 1 TiB, or above 4 hours, cannot be imported in v1;
- JPEG derivatives are larger than AVIF at equal quality;
- raising a limit later is a version bump, not a configuration change.

## Security impact

Affected invariants: SEC-007. Every bound is checked before allocation and before Argon2 runs, so a hostile package cannot force an unbounded allocation or an unbounded KDF. The descriptor bounds apply before any credential exists, which is what `VAULT_DESCRIPTOR_V1.md` §8 step 2 depends on.

## Compatibility impact

No persisted bytes exist. A later profile may raise a bound only under a new version, never by relaxing a released reader.

## Validation

- negative vectors one byte and one count above each bound, and fuzz targets asserting that a rejected input allocates no more than the stated ceiling;
- an import at the maximum chunk size and chunk count on both platforms.

## Follow-up

- benchmark the chunk-size candidates inside the approved range and record the result;
- confirm the 256 MiB decode ceiling against the lowest supported device.
