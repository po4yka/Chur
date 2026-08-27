# ADR-0012: Reserve Chunk Indexes in the Import Journal Before Use

- **Status:** Accepted
- **Date:** 2026-08-27
- **Related:** [`../format/OBJECT_CONTAINER_V1.md`](../format/OBJECT_CONTAINER_V1.md), [`../format/CATALOG_SCHEMA_V1.md`](../format/CATALOG_SCHEMA_V1.md), [`0002`](0002-independent-aead-chunks.md), [`0008`](0008-freeze-object-container-v1-layout.md)

## Context

A chunk nonce is `nonce_prefix || chunk_index`, so a resumed import that encrypts an index it already used produces two ciphertexts under one XChaCha20-Poly1305 nonce and loses confidentiality for that stream. Four documents required that this not happen, none said how, and ADR-0008 recorded it as the outstanding nonce-reuse risk. Writing the journal record after the chunk leaves journal index `i - 1` and container record `i` after a crash, and the resumed writer re-encrypts index `i` from a source that may have changed. Reversing the order alone is not enough: the reserved record may be torn, and §8 forbids a gap in the index sequence, so skipping or rewriting it is equally wrong. The journal's location was ambiguous as well, a catalog table in `CATALOG_SCHEMA_V1.md` §11 and a `journals/` directory in `ARCHITECTURE.md` §14.4 and `ANDROID.md` §12.

## Decision

Reserve before use, specified in `OBJECT_CONTAINER_V1.md` §14:

- the journal record holds the key-envelope reference, `nonce_prefix`, `chunk_size`, and one `reserved_index`, from which the journaled ciphertext length is computed rather than stored;
- per index: set `reserved_index`, make that update durable, write the chunk record, fsync the container, and only then reserve the next index;
- a resume takes prefix and index from the journal and continues at `reserved_index + 1` only if the record at the journaled ciphertext length parses at `reserved_index` and authenticates, truncating to the end of that record; otherwise the transaction is dead, its key envelope is destroyed before its temp container, and its `(ObjectKey, nonce_prefix)` pair is retired rather than donated.

The journal is an `ImportTransaction` row in the private catalog; there is no journal directory.

## Alternatives considered

### Journal the chunk after writing it

Rejected. It is the ordering that produces the two-plaintext nonce reuse above.

### Keep the journal in a filesystem directory

Rejected. It creates a second durability domain and a reconciliation between that directory and the catalog transaction that activates the same object, for no benefit, since the catalog is already open and already commits the same import.

## Consequences

### Positive

- the indexes ever encrypted under a prefix are a durable superset of what reached the container, so resume is safe without trusting bytes an attacker may have truncated, and it checks one record instead of rescanning.

### Negative / tradeoffs

- one durable journal commit and one container fsync per chunk, which the chunk-size benchmark must now include;
- a crash between a reservation and the completed chunk write kills the transaction, and the import restarts at index 0 with new key material;
- the journal cannot be excluded from a catalog backup file by file, so a restored vault finds no temp container and marks every open transaction dead.

## Security and privacy impact

Affected invariants: SEC-011, SEC-012, SEC-013, SEC-024. Reservation before use is what makes SEC-011 provable on the resume path: the used-index set is bounded by a durable value at every crash point, so the resumed writer picks an index above it without consulting container bytes. Destroying the key envelope before the temp container leaves an abandoned stream unreadable even where discarded blocks survive on flash.

## Compatibility and migration impact

No container bytes and no vectors change. The journal record is private catalog state, so its shape is covered by the catalog schema version, not by `container_version`.

## Validation

- fault injection between reservation and chunk write, and between chunk write and next reservation, with a resume after each;
- a property test that no `(nonce_prefix, chunk_index)` pair is produced twice across any crash and resume sequence, which also fails a writer that journals a chunk after writing it;
- a resume whose reserved record is short by one byte reports a dead transaction, not a completed import.

## Follow-up

- fix the required catalog synchronization mode in the SQLCipher prototype validation of `CATALOG_SCHEMA_V1.md` §15; measure the two synchronization points per chunk against the chunk-size range still open in ADR-0008; reconsider a journaled per-chunk plaintext commitment, which would let a torn reserved index be re-emitted byte for byte, only if crash restarts appear in field data.
