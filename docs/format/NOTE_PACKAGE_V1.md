# Portable note package v1

A `.churnote` file is plaintext UTF-8 JSON, suitable for explicit import/export between public and private note stores. It is not an encrypted archive. Private exports require the normal plaintext warning; use a selective encrypted archive for protected transfer.

The exact required shape is:

```json
{"version":1,"title":"Sample","body":"[File](attachment:0123456789abcdef0123456789abcdef)\n","format":"MARKDOWN","attachments":[{"id":"0123456789abcdef0123456789abcdef","name":"sample.txt","mimeType":"application/octet-stream","data":"SGVsbG8="}]}
```

The example file contains the UTF-8 bytes of `Hello`. `data` uses canonical padded standard Base64. `format` is `MARKDOWN` or `PLAIN_TEXT`. Source is retained exactly as a Unicode string; opening or previewing does not rewrite it. A fresh note identity is allocated on import, while note-scoped attachment IDs are preserved so source references continue to resolve. Package JSON whitespace and property order need not be retained.

Limits: package 24 MiB, title 256 UTF-8 bytes, body 256 KiB, 32 attachments, one file 8 MiB, aggregate file bytes 16 MiB. Title/body cannot contain NUL. Identifiers are nonzero and have exactly 32 lowercase hexadecimal digits. Names are nonblank basenames of at most 240 UTF-8 bytes; slash, backslash, colon, control characters, `.` and `..` are forbidden. Import rejects duplicate identifiers, unknown format/version, malformed UTF-8/Base64, mismatched sniffed MIME and exceeded limits. MIME values are `image/png`, `image/jpeg`, `image/gif`, `image/webp`, `application/pdf` or `application/octet-stream`, determined from content signatures. Other content remains opaque, even if its extension advertises an executable or image type.

No ZIP member paths or filesystem extraction exist. User labels and source references never become filesystem paths. Private import validates and commits source, metadata and files in one SQLCipher transaction. Public import writes independent opaque payloads before atomically publishing their catalog references and cleans incomplete writes. Text-only `.md` import/export remains available separately; `.md` alone cannot contain the actual attachment payloads.
