# Documents and arbitrary files

> **Status:** Accepted product and platform contract; release gates remain separate

## Import and storage

Use **Library → Import → Documents and files** and choose files with the native document picker. All file types are accepted as opaque encrypted originals. This explicit action skips media probing and derivative generation, even if the selected bytes are an image, video, or audio file. Use the media import action when playback, thumbnails, waveforms, or embedded audio covers are wanted.

Rust streams and encrypts the original bytes through the existing object-container and journal path. Names and MIME hints stay in encrypted metadata and are treated as untrusted input. Files use the existing library, album, favorite, tag, Trash, backup, sync, and collection-sharing paths; there is no separate public document database. Provider access and source handles last only for the operation. A provider can fail, revoke access, or report an unknown length. Chur does not require a complete plaintext staging copy solely for file import.

## Viewing and search

The **Files** type filter shows opaque files. The viewer displays metadata and offers explicit export when it cannot preview the contents.

- Text preview reads at most a 1 MiB authenticated UTF-8 prefix. Malformed text is refused and truncation is stated.
- PDF preview accepts at most 32 MiB of encoded input, renders one bounded page at a time, and supports page navigation. A protected, malformed, over-limit, or unavailable preview falls back to metadata and export.
- Office formats and other binaries can be stored and exported without an inline preview. HTML and XML are not executed; no embedded script, automatic external viewer, or link navigation is enabled.
- Filenames, captions, and tags use the ordinary private search. The separate foreground [content-index action](CONTENT_SEARCH.md) adds text extraction and local OCR for supported inputs. Private-note bodies have their own search screen.

Preview limits do not reduce the original-storage or streaming-export limits. Viewing uses authenticated native ranges, transient text/raster memory, and session-owned readers. Lock and disposal release these resources. [Plaintext Lifecycle](../security/PLAINTEXT_LIFECYCLE.md) owns exact raster, buffer, and cleanup limits.

## Export and transfer

A file can be saved/shared as a plaintext copy with the normal warning, exported through its [album tree](ALBUM_EXPORT.md), included in [selective encrypted export](SELECTIVE_ENCRYPTED_EXPORT.md), or preserved in a full encrypted backup. Export retains original bytes, including an empty file. The destination may keep a plaintext copy.

[Share to Chur](INCOMING_SHARES.md) is another ingress path. It can classify supported media through codecs and fall back to an opaque file. [External file encryption](EXTERNAL_FILE_ENCRYPTION.md) is a separate tool: it creates an encrypted archive without adding files to this library.

## Validation boundary

Native round trips and platform tests cover arbitrary/empty binary data, MIME hints, source lifetime, cancellation, lock, exact-byte export, text/PDF preview bounds, and malformed preview fallback. A successful import does not validate the safety of a file for another application. A local test or platform build does not prove every cloud/removable provider or physical-device combination.
