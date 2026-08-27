# ADR-0035: Freeze the Element Widths of the Three Object AAD Tuples

- **Status:** Accepted
- **Date:** 2026-08-27
- **Decision owners:** @po4yka
- **Related:** [`../CRYPTOGRAPHY.md`](../CRYPTOGRAPHY.md), [`../format/OBJECT_CONTAINER_V1.md`](../format/OBJECT_CONTAINER_V1.md), [`0008`](0008-freeze-object-container-v1-layout.md)

## Context

`CANONICAL_ENCODING_V1.md` §7.1 requires the specification that owns a canonical tuple to declare "the element list in order, with the type and width of every element". The two key-envelope tuples do that and state their exact byte counts. The three object tuples did not: `CRYPTOGRAPHY.md` §32, §35, and §38 listed bare field names, and §35 still called its list "Proposed".

Every name in those lists does have a width somewhere, because each is a field of the frozen preamble, the frozen chunk header, or the frozen `CanonicalManifest`. An implementer had to collect the widths from three sections of a second document and trust that nobody had read them differently. `stream_kind` is `u8` in the manifest and `stream_revision` is `u32`, but the same names are written `u16` and `u64` elsewhere in the corpus, so the ambiguity was real rather than theoretical. AAD is never parsed, so a width disagreement between a writer and a reader surfaces as an authentication failure on valid data.

`OBJECT_CONTAINER_V1.md` §9 carried a third copy of the chunk list, in a different notation, with no owner named.

## Decision

- `CRYPTOGRAPHY.md` §32, §35, and §38 write every element with its canonical-encoding type, and each states the total tuple length: 66 bytes for the manifest AAD, 109 for the chunk AAD, 102 for the final-commit AAD.
- Every width is the width the owning frozen structure already gives the field. No element changes type; the decision records the widths rather than choosing new ones.
- `OBJECT_CONTAINER_V1.md` §9 keeps its readable summary of what the chunk AAD binds and names `CRYPTOGRAPHY.md` §35 as the owner of the element list, so one list exists.
- §35 loses the word "Proposed"; the tuple is frozen with the container it belongs to.

## Alternatives considered

### Move the tuples into `OBJECT_CONTAINER_V1.md`

Rejected. The tag registry in `CANONICAL_ENCODING_V1.md` §15.5 records `CRYPTOGRAPHY.md` §32, §35, and §38 as the owners of these three tags. Moving the element lists without moving the tags would split one record across two owners, and moving both is a larger change than the defect requires.

### State only the total byte counts

Rejected. A total is a checksum on the element list, not a substitute for it: two different width assignments can sum to the same length, and an implementer needs the per-element width to write the encoder.

## Consequences

### Positive

- the three object tuples now meet the same standard as the two envelope tuples;
- a byte-count assertion per tuple becomes a cheap test that catches a width mistake at the first vector;
- the chunk AAD is a fixed 109 bytes, so a writer can encode it into a reusable buffer and vary only the index and the length.

### Tradeoffs

- three more totals to keep correct when a future container version changes a field width, which is the cost of the widths being stated at all.

## Security impact

Affected invariants: SEC-011, SEC-012, SEC-014.

No invariant changes. SEC-014 requires that a chunk not authenticate outside its object, stream, revision, and index. A tuple whose widths were not fixed could not demonstrate that property, because two implementations could disagree about where `chunk_index` begins.

## Compatibility impact

No persisted or wire bytes change. No container exists.

## Validation

- an encoder test asserts the three tuple lengths exactly;
- a negative test alters one element of each tuple and asserts the AEAD fails;
- the object-container vectors carry the encoded AAD of at least one chunk so another implementation can compare bytes rather than field names.
