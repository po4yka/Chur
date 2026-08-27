# ADR-0028: Freeze the Catalog Query Surface, Index Set, and v1 Search

- **Status:** Accepted
- **Date:** 2026-08-27
- **Decision owners:** @po4yka
- **Related:** [`../format/CATALOG_SCHEMA_V1.md`](../format/CATALOG_SCHEMA_V1.md), [`../interop/FFI_CONTRACT.md`](../interop/FFI_CONTRACT.md), [`0004`](0004-rust-owned-private-catalog.md), [`0016`](0016-freeze-the-v1-c-abi.md)

## Context

`ARCHITECTURE.md` §27.3 declared `queryObjects(session, query): Page<ObjectProjection>` and `FFI_CONTRACT.md` §6.2 exported `chur_catalog_query` with a `ChurQueryV1`, while `ObjectQuery`, `ObjectProjection`, `Page`, and `ChurQueryV1` were defined nowhere: no fields, no sort key, no cursor semantics, no page-size bound. `CATALOG_SCHEMA_V1.md` §16 said only that indexes "may support timeline, albums, tags, and metadata search".

The timeline is the primary screen and albums, favourites, and search are Phase-1 scope, so the KMP and Rust teams could not work in parallel against anything. The search implementation was open between a `LIKE` scan and a full-text index, and that choice changes the schema, the index budget, and what a page-level compromise of an unlocked database yields.

## Decision

Define the surface in `CATALOG_SCHEMA_V1.md` §16:

- one fixed-width `ObjectProjectionV1` for every grid scope, carrying no free-form user text, so a 200-row page never carries 200 filenames across the boundary;
- an `ObjectQueryV1` with six scopes, a media-kind mask, three sorts, and `limit` bounded at 500 with a default of 200;
- keyset paging on `(sort value, object_id)`, never offset, with the double-return and skip consequences stated and a `catalog_generation` change as the restart signal;
- nine covering indexes, one per scope and sort, with `capture_time_ms` duplicated into album-membership and favourite rows so those scopes do not join before sorting;
- v1 search as a SQLite FTS5 table inside the SQLCipher database over filename, caption, and tag names, tokenizer `unicode61 remove_diacritics 2` with 2- and 3-character prefix indexes.

`SearchKey` is unused in v1 and stays reserved for OCR, face, and embedding segments, which resolves the either/or of `CRYPTOGRAPHY.md` §44.

## Alternatives considered

### `LIKE '%term%'` over decrypted columns

Rejected. No index serves it, so it is a full scan of every metadata revision; at the million-object limit of §21 it misses the first-content budget by orders of magnitude, and it would need a background scan to hide the latency, which is more machinery than FTS5.

### A separate encrypted index file under `SearchKey`

Rejected for v1. It needs its own format, nonce discipline, migration, and crash recovery, to protect an index that only a holder of the unlocked catalog key can read anyway. It remains the right shape for embeddings, which are large and rebuilt independently.

### Offset paging

Rejected. `LIMIT n OFFSET m` costs O(m) per page and silently duplicates or skips rows when the set changes underneath, with no signal the caller can act on.

## Consequences

### Positive

- both teams can build against a fixed projection, cursor, and index set;
- every scope is a range scan whose per-page cost is independent of position;
- search adds no dependency and no key.

### Tradeoffs

- SQLCipher must be built with FTS5 enabled, which is a build-configuration requirement on both platforms and a small binary-size cost;
- `capture_time_ms` is duplicated into two row types and must be rewritten with the metadata revision;
- a projection change is a compatibility event for the C ABI, not only for the schema.

## Security impact

Affected invariants: SEC-027.

The FTS index stores tokenized plaintext terms and postings inside the encrypted database. It is readable exactly by a holder of the unlocked database key, who can already read the metadata the terms come from, so it adds no capability to any attacker profile. Term count contributes to database size, which the threat model already treats as leaking approximate scale. The projection excludes filename, caption, EXIF, GPS, and album name so that a bulk query result is not a bulk metadata disclosure across the FFI boundary.

## Compatibility impact

No catalog exists, so nothing migrates. Adding a projection field or a scope raises the minor ABI version under `interop/FFI_CONTRACT.md` §2; changing or removing one raises the major version.

## Validation

- a keyset paging test over a set mutated between pages, asserting no crash and the documented duplicate-or-skip behaviour;
- query plans asserting that each of the six scopes uses its covering index and performs no sort;
- first-content latency at the §21 object limit against the performance budget;
- an FTS5 reindex test asserting that a stale revision's terms are never returned.

## Follow-up

- the exact `ChurQueryV1` and page encoding in `chur.h` land with the first `chur-catalog` implementation;
- ranking, snippets, and local-AI search presentation are out of v1.
