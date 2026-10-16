# Catalog schema v10: local content search

> **Status:** Accepted implementation contract; security release gates remain separate

Version `0x000a` adds a rebuildable local index for document and image text. The index uses the existing Rust-owned SQLCipher catalog. The database, WAL, and FTS pages remain encrypted with the catalog key. It creates no public database, system search entry, remote OCR request, sync operation, or plaintext scratch file.

## Stored state

`content_index` has one row per indexed object. Its fields are the object ID, immutable primary stream ID, extraction engine version (`1`), status (`1` complete or `2` partial), and UTF-8 text. IDs are sixteen-byte blobs. Text is at most 262144 bytes and rejects NUL. An empty successful result is valid. It prevents a normal scan from repeating an image with no recognized text. Partial results require a rebuild to retry.

The current source must be active, outside quarantine, and an image or an opaque file with an image, PDF, text, JSON, or XML content type. Put checks the primary stream and lifecycle inside the same immediate transaction as the write. The engine version identifies the extraction contract and bounds. It does not identify the installed OS OCR model.

`object_search` is rebuilt with four columns: filename, caption, tag names, and content body. It remains contentless with `contentless_delete=1`, Unicode tokenization, two- and three-character prefixes, and FTS secure deletion. The separate bounded text row is needed to preserve content terms when filename, caption, tags, or recoverable deletion state changes. Existing escaped literal prefix queries can combine terms from metadata and content, including Cyrillic. Search returns the existing object projection; recognized text does not cross the query boundary.

## Lifecycle

Text replacement, combined FTS replacement, and catalog-generation change use one transaction. A scan uses a stable ascending object-ID cursor. It does not depend on generation, which changes after each write. Normal scans skip indexed current sources. Explicit rebuild includes them. A cancelled scan keeps only previously committed records, and a later scan resumes missing work.

Trash removes the object's FTS entry but keeps its encrypted text for Restore. Restore rebuilds the combined entry. Permanent deletion removes the content row and FTS entry before the existing verified WAL erasure barrier. Clear deletes all derived text, rebuilds metadata-only entries with a bounded SQL statement, and checkpoints the WAL before it reports success. Existing encrypted full backups preserve the catalog and index. Selective export and sync transfer originals and metadata without local derived text. Imported or restored originals can be indexed locally.

## Migration

Fresh catalogs install version 10. An existing version 9 catalog first receives an authenticated `MIGRATING` descriptor for `(9,10,migration_generation=9)`. One SQL transaction creates the text table, replaces the FTS schema, restores metadata search, and records version 10. A checkpoint precedes the final authenticated active descriptor. Unlock resumes on either side of the SQL commit. Backup restore runs the same SQL step before it publishes its new descriptor. Existing text from private Notes remains in its separate note table and FTS index.
