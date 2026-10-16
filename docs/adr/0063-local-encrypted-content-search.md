# ADR-0063: Keep Local OCR Text in the Encrypted Catalog

- **Status:** Proposed
- **Date:** 2026-10-16
- **Decision owners:** @po4yka
- **Related:** [`../product/CONTENT_SEARCH.md`](../product/CONTENT_SEARCH.md), [`../format/CATALOG_SCHEMA_V10.md`](../format/CATALOG_SCHEMA_V10.md), [`0004`](0004-rust-owned-private-catalog.md), [`0060`](0060-bounded-document-previews.md)

## Context

Filename, caption, and tag search cannot find a word that occurs only in a scanned page or photograph. Recognized text reveals document contents. It needs the same storage and session boundary as the original.

## Decision

- Add a local, rebuildable content index in SQLCipher catalog v10. Extend the existing object FTS row with a body column so one query can match terms across metadata and content.
- Platform code extracts bounded UTF-8 text. Rust validates its size, status, engine version, active object, and original stream identity before an atomic row and FTS update.
- Support UTF-8 text, JSON, XML, PDF pages, and image originals, including images imported as files. Other document formats remain storable and exportable but have no content extractor.
- Start a scan from Search in an unlocked foreground session. Provide pending-item scan, retry, rebuild, progress, stop, and explicit index clear. An extraction failure leaves the original and the last successful index intact.
- Use local OCR only. Android uses a disposable decoder child with the existing custody lock; iOS uses Vision and memory-backed Core Graphics images. No OCR request uses network, public Notes, OS search, or plaintext scratch files.
- Bind candidate enumeration, reader opening, writes, and clear to the expected unlocked generation. Cancel foreground extraction on background; invalidate callbacks and UI projections on lock or session replacement.
- The full encrypted backup includes the catalog index. Selective archives, sync, and sharing omit the local index; the receiving device can rebuild it from originals.
- Purge deletes OCR rows and FTS terms before the existing WAL checkpoint barrier. Destroying the original's object key alone does not erase text stored under the catalog key.

## Alternatives considered

A platform database would add a second encryption and deletion boundary. Public or OS search indexes would disclose content outside the vault. Cloud OCR would transmit originals or recognized text. A separate FTS table would complicate queries that combine a filename term with a content term.

## Consequences

The catalog and ABI change to v10 and 2.24. Older clients must reject the new catalog. OCR is approximate and resource bounded; partial extraction is explicit. Source originals remain immutable. Updating extraction rules requires a new engine version and a rebuild. Recognition does not add embeddings or semantic search.

## Security impact

This change affects SEC-010 (redacted secrets), SEC-015 (authenticated plaintext), and the session and deletion boundaries. SQLCipher protects persistent index pages, WAL, and full backups. OCR still uses managed strings, native parser memory, and pixel buffers during an open session. Clearing owned byte and pixel buffers does not establish physical memory erasure. Index clear and object purge do not retract historical backups. Platform parser and OCR engine updates remain part of dependency and release review.

## Compatibility impact

Catalog v9 upgrades through the authenticated v10 migration and crash-resume chain. ABI 2.24 adds bounded local control records. Media containers and signed sync or sharing records do not change. Downgrade requires a compatible pre-migration backup; older clients cannot open a v10 catalog.

## Validation

Test authenticated migration and interrupted resume, encrypted files, Unicode search, mixed metadata/content terms, pagination during writes, index replacement, Trash and purge, source identity, strict records, stale generations, cancellation custody, actual platform OCR, and compact large-font Search controls. Build evidence and emulator/simulator checks remain distinct from physical-device acceptance.
