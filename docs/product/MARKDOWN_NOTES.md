# Markdown notes

Public Notes and the selected vault's private notes use the same source editor and offline preview. Their stores, access rules, backup behavior, and draft lifecycle remain independent.

## Format and editing

New notes use Markdown. Public JSON v1 and private catalog v11 notes migrate as **plain text**. The format control changes interpretation without changing the body. Format is persisted; private format-only changes require the saved revision.

Source is authoritative: preview never serializes over it. The editor supports headings, emphasis, strikethrough, inline/fenced code, nested lists, quotes, reference links, GFM tables and task lists. Formatting commands edit the selection or selected lines. List continuation, indentation, find/replace, outline, bounded undo/redo and Ctrl/Cmd shortcuts work in source mode. Preview task checkboxes update the original source token. Code highlighting is local.

The dialect is the renderer's CommonMark/GFM parser, pinned with the application. This is a source editor with preview, not a WYSIWYG rich-document editor. Footnotes, math and arbitrary rich objects are not promised as visual-editing features; unsupported syntax remains available in source and exact-body Markdown export.

Images remain source references and show descriptive text in preview. There is no network image loader, file-path resolver, CDN, WebView, script execution or automatic URL fetch. Managed attachments, inline image retrieval, OCR integration, drawing, audio, organization and synchronization remain separate features. External HTTP/HTTPS/mail links require confirmation before opening another application; other URI schemes are inert. HTML cannot execute in Compose preview.

## Files

Import `.md`/`.markdown` with the system picker. Input must be valid UTF-8, contain no NUL and fit within 256 KiB. Body bytes are not trimmed or normalized; the filename supplies a title bounded to 256 UTF-8 bytes. Private import creates a session draft requiring Save. Picker callbacks are invalidated on disposal/session change; private pickers use the existing bounded host-activity lifecycle.

Markdown export writes **exact UTF-8 body bytes**, without inserting a title, BOM or newline. It uses `.md` and `text/markdown`. Plain text export remains `title + LF + LF + body` with `.txt`. Public export uses the system file destination. Private export continues through the native saved-revision operation, deliberate plaintext warning, cancellation, cleanup and selected-session checks. Encrypted archives and backups retain format and original source.

## Durability and privacy

Public cache state advances only after persistence succeeds. Failed writes retain the editor draft for retry. Back offers save/discard; Save and leave waits for persisted content acknowledgement. Public pinning is available in the editor and reflected in list order.

Private Save remains explicit. Lock, panic, identity change, disposal or process loss clears unsaved session state. Rendering, undo, find/replace and outline use neither saved-instance state nor public persistence. Copy/Cut remain disabled. Undo is route-owned and bounded, not durable version history. Managed strings and platform layout/input copies do not provide secure erasure.

Storage contracts: public JSON v2 and [private catalog v12](../format/CATALOG_SCHEMA_V12.md). Private notes require breaking native ABI 3.0. Catalog upgrades use authenticated migration; old clients reject the newer catalog/ABI.
