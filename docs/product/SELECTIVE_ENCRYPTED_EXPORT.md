# Selective encrypted archives

> **Status:** Accepted product contract; physical-device and release acceptance remain separate

## Export

Use **More → Encrypted archive…** for selected Library or Search items. Selection includes photos, videos, audio, documents, and arbitrary files. The action uses the selected identifiers, including selected items outside the loaded page. It does not mean all results of the current filter.

Use **Export album → Encrypted archive…** for an album and its descendants. This includes the whole subtree regardless of the current filter or loaded page. Overlapping membership is copied once; empty selected albums and manual membership order are preserved. Selected roots are detached from any unselected parent. Selecting individual files does not include their other album names.

In **Private notes**, Select page or a long press starts selection. The action keeps identifiers and saved revisions across pages. Search, Trash, a new editor, and lock clear selection. The editor also offers an encrypted archive of its saved note. Unsaved changes require Save and export; a failed save keeps the draft and creates no archive.

The dialog asks for a new archive password and a repeat, then offers **Save to Files** or **Share**. The password is not the vault key. Use a strong, distinct password and keep it: Chur does not provide a recovery phrase for this archive. Files suggests a neutral filename (`chur-archive.churbak`), with no note title or album name. Share can use a neutral host-generated filename.

One request accepts up to 1000 file identifiers, 1000 album roots, and 1000 note revisions. The expanded album tree has at most 1000 albums and 10000 unique originals. Invalid, missing, trashed, corrupt, or stale selected entries refuse the operation. A concurrent source catalog change also refuses publication.

## What the archive contains

The package uses the existing [CHURBAK1 backup format](../format/BACKUP_FORMAT_V1.md). Rust creates a new temporary vault with an independent root and credentials, then decrypts and encrypts the selected data again. It never filters a copy of the source catalog or exports its root key.

Original bytes, selected display metadata, caption, favourite state, attached tags, selected album hierarchy and order, and private note text and pin state are retained. Available media previews are encrypted again. Objects, streams, albums, tags, and notes receive fresh identifiers. Note revisions and import history start anew.

The archive excludes unselected entries, unselected parent or sibling album names, unused tags, Trash, original credential slots, device keys, source sync and sharing state, operation logs, and the sibling vault identity. Search indexes are built from the included rows only. Existing full backup remains a separate action with its existing credentials and scope.

## Open the archive

There are two paths:

- **Restore a backup or archive** installs a separate vault under the archive password. It is available during initial setup and from Settings → Backup → Restore archive as a separate vault. The current vault is locked first and its data is not replaced. The normal local identity-capacity and duplicate-identity checks still apply; use an empty installation if this device cannot add another vault.
- **Import encrypted archive** is available in the Library import menu and Settings → Backup. It adds fresh copies into the currently unlocked vault. Existing files, albums, tags, and notes are kept, including entries with equal names. Importing the same archive again creates more file and note copies; files, albums, and notes do not merge by name or source identifier. Tags follow the existing unique-name rule: an equal-name destination tag is reused without changing it; otherwise import creates a new tag.

Import authenticates the complete package and its content in an isolated temporary vault before it changes the destination. A wrong password, truncation, or failed authentication adds nothing. After authentication, originals and their display metadata commit one at a time; notes commit as complete rows. Cancellation or a later storage failure keeps saved originals and notes and stops further work. The outcome reports that count. Tags, previews, and the final album organization can remain incomplete, and newly created empty albums can remain. Existing destination records are not replaced. Lock stops the job; unlock and refresh to inspect any completed items. No imported source credential or sync identity becomes part of the current vault.

## Lifetime, limits, and failure

Password fields and selections use session memory and have no saved-state registry. Picker credentials are held as erasable bytes, not a saved password string. Lock, disposal, picker cancellation, and operation completion clear owned buffers. Managed strings and platform input copies do not provide a physical-erasure guarantee.

Plaintext travels in bounded memory buffers. Staging on disk is an encrypted temporary vault in app-private storage. Normal completion and failure remove owned staging; the native session cleanup also handles abandoned staging after a process interruption. This is logical removal, not a physical-erasure claim.

Selective operations limit the encrypted catalog snapshot to 4 GiB. They verify all physical SQLCipher pages before migration or destination mutation. This verification is one native stage: cancellation is checked before and after the SQLCipher scan. Container transfer and verification check cancellation between chunks. Normal full-backup size policy is unchanged.

Export uses normal progress and Cancel controls. The source's revocable reader boundary remains active during transfer. Native output has a hard byte limit that matches the host scratch reservation. Share, and iOS Files, use the existing scratch limits (up to 4 GiB per file and the remaining aggregate budget). Android Files writes directly to the selected provider. The provider or storage can refuse a write at a smaller size.

Only a complete successful operation under the original session can be published. Failed output is removed where the provider permits it. A process exit can leave an empty or incomplete provider file; Chur never reports it as a complete archive. Blocking operating-system writes retain the existing cancellation limitation. Published encrypted copies remain outside the vault and are not revoked by later deletion or password changes.

## Validation

Tests must cover export → separate restore and export → import into an existing vault, mixed file classes, album descendants, note revisions and UTF-8 bounds, new keys and identifiers, source immutability, excluded metadata, damaged packages, cancellation, reader revocation, destination failure, and staging cleanup. Native, host, emulator, simulator, and physical-device checks are distinct evidence levels.
