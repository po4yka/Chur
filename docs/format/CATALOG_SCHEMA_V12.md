# Catalog schema v12: explicit private note format

> **Status:** Accepted implementation contract; device and release gates remain separate

Version `0x000c` adds `private_notes.format INTEGER NOT NULL DEFAULT 0 CHECK(format IN (0,1))` to the Rust-owned SQLCipher catalog. `0` means literal UTF-8 plain text; `1` means UTF-8 Markdown source. Format is never inferred from punctuation, filename, or content. All existing v11 rows retain their exact source and receive `0`.

## Transactions and export

Format is part of the saved note revision. A format-only edit advances the revision and catalog generation atomically; an identical save preserves both. A stale revision changes neither source nor format. Pin, Trash, Restore, paging, and search preserve the discriminator. Search indexes the saved source text without executing or rewriting markup.

Markdown export is the exact UTF-8 body, including existing whitespace and line endings; it does not prepend a title or add a trailing newline. Plain text export retains `title + "\n\n" + body`. Native summary and full-record export sizes use the complete body and the saved format. Empty Markdown exports have zero bytes. The native export snapshots the exact requested saved revision, including format.

## Migration and archives

Fresh catalogs install v12. A v11 vault first installs an authenticated `MIGRATING` descriptor for `(11,12,migration_generation=11)`. One SQL transaction adds the checked column and records v12; failure rolls back the column, migration marker, and version. The step preserves note identifiers, revisions, timestamps, source, pin/Trash state, FTS rows, and catalog generation. Unlock resumes before or after the SQL commit, checkpoints the catalog, then installs the authenticated active descriptor. Earlier steps remain sequential.

Full backup includes the encrypted catalog, preserving the discriminator. Restore migrates historical v9–v11 note rows to plain text before publishing the new descriptor. Selective encrypted archives and import copy the saved discriminator and exact source to fresh note identities and revisions. No media container, sync, sharing, or backup envelope format changes. Earlier clients reject unsupported v12 catalogs; no downgrade is attempted.

The private-note control record and save request require [ABI 3.0](../interop/FFI_CONTRACT.md#625-local-private-notes-abi-30). Unknown discriminators are rejected. The native library and Kotlin host are shipped together.
