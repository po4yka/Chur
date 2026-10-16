# ADR-0060: Import Arbitrary Files and Bound Private Document Previews

- **Status:** Proposed
- **Date:** 2026-10-16
- **Decision owners:** @po4yka
- **Related:** [`../interop/MEDIA_PIPELINE.md`](../interop/MEDIA_PIPELINE.md) §3.1, [`../security/PLAINTEXT_LIFECYCLE.md`](../security/PLAINTEXT_LIFECYCLE.md) §3–4, [`../format/OBJECT_CONTAINER_V1.md`](../format/OBJECT_CONTAINER_V1.md) §6 and §11

## Context

The vault format already admits opaque originals, but media pickers and decoder probes prevent a reliable arbitrary-file import. Documents also need a useful private viewer without sending plaintext to another app or keeping a plaintext copy on disk.

## Decision

- Add a separate multi-file document-picker action. Its sources bypass media probes and use the existing opaque class, even when their MIME hint names an image or video.
- Stream original bytes through the existing journal and encryptor. Accept empty opaque originals through the format's zero-chunk final commit. Select 1 MiB chunks for all opaque sources, including underreported lengths, so that the existing 1 TiB total bound remains reachable.
- Keep provider access alive only for the import, without persisted bookmarks, public paths, or plaintext scratch copies.
- Preview PDF through the native platform parser and UTF-8 text through a bounded literal text renderer. The documented limits apply only to preview. Unsupported, malformed, or large documents retain metadata and explicit export.
- Permit bounded transient document bytes and decoded text in UI memory under the plaintext policy. Clear byte arrays, drop text references, release native buffers, and discard raster surfaces on disposal or lock. Do not claim secure erasure of managed strings or platform surfaces.
- Keep document controls and scrolling independent from media-page gestures. Do not add clipboard, active HTML, archive extraction, or external application launch.

## Alternatives considered

A plaintext file copy simplifies platform preview but creates a second lifecycle and a lower size limit. An external viewer transfers plaintext outside the vault without a separate export decision. A new document format or class duplicates the existing opaque object model. These alternatives are not needed for this feature.

## Consequences

All file types can use the existing library and export operations. PDF and UTF-8 text have internal read-only previews; Office documents and archives do not gain an editor or a parser. Native PDF parsing expands the in-process attack surface. Preview limits bound ordinary data and raster allocations but do not prove a bound on all native parser work.

## Security impact

Sources and MIME hints remain untrusted. Read ranges authenticate before preview; incomplete originals are refused. A lock invalidates readers and removes private UI. There are no new production dependencies, plaintext files, network requests, or persisted grants. Platform PDF parser defects and copies retained by platform text/graphics code remain outside secure-erasure guarantees.

## Compatibility impact

No enum, schema, FFI record, or encoded layout changes. Existing readers already accept the selected chunk sizes and empty streams. Empty image, video, and audio imports remain invalid.

## Validation

Exercise arbitrary binary, misleading MIME/extension, unknown length, empty files, byte-for-byte export, large-source chunk boundaries, strict UTF-8/truncation, PDF page rendering, malformed PDF, lock, and source release. Build both native hosts and render the affected UI. Keep device acceptance separate from host and simulator checks.

## Follow-up

Consider an isolated Android PDF parser process if its IPC and plaintext ownership can preserve this policy. Rich Office previews and document editing need a separate design.
