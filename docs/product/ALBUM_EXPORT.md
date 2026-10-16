# Album export to a folder

> **Status:** Proposed product and platform contract

## Scope

Album export writes plaintext copies to a directory the user selects with the native folder picker. It does not change or delete vault objects, memberships, or imported originals. It does not grant encrypted collection access.

The action exports the selected album and all its descendant albums. It creates a new folder for the selected album inside the chosen directory and preserves the album hierarchy below it. An empty album produces an empty folder. An object in more than one exported album produces one copy in each album, because each membership is part of that folder's contents.

The export queries the catalog directly. It must include every page of the exported albums and must not inherit the visible media filter, search terms, selection, or loaded page. A catalog change that invalidates pagination must stop the export and report a partial result instead of silently omitting or repeating members.

## User flow

1. The user opens an album and selects **Export album**.
2. Chur explains that the album and its subalbums will be copied, that existing destination files will remain intact, and that the destination may retain unencrypted copies.
3. The user selects a destination with the platform folder picker. Cancelling the disclosure or picker creates no output.
4. Chur exports one original at a time through the existing Rust verified export operation. The operation shows item progress and offers Cancel.
5. Chur reports how many copies completed. On failure or cancellation, it states that completed copies remain outside the vault. It must not report a partial export as complete.

The album action remains available when the visible page is empty or a filter hides every item. It is disabled while another tracked operation runs.

## Names and publication

Album names and original filenames are private input until the user confirms export. Each platform adapter must convert them to safe individual path components. It must reject path traversal, separators, empty components, and reserved names, and must bound component length.

Folder and file creation must not overwrite or merge with existing output. Name collisions receive a distinct name. A destination name must remain inside the user-selected directory, including when the catalog contains a malicious filename.

Each active file has an explicit incomplete name. Only a successfully verified and completed native export can publish the final name. Publication must preserve the no-overwrite rule. If a provider cannot support required creation, rename, or cleanup operations, the adapter fails without advertising a completed file.

Cancellation, locking, integrity failure, and provider failure close the current operation and attempt to remove its incomplete output. Published copies and created album folders remain. Abrupt process termination can leave an explicitly incomplete file in the external directory; Chur must not claim that it can always remove external copies after termination. The export stores no destination bookmark, private path, or resume job for later cleanup.

## Session and resource boundaries

- The operation requires the same unlocked session that launched the picker. A result returned after lock or session replacement is discarded.
- A picker temporarily uses the existing host-activity lifecycle policy. It does not enable plaintext work after the vault locks.
- Rust retains ownership of verification, decryption, and bounded byte transfer. The UI does not collect complete originals in Kotlin or Swift memory.
- Catalog membership is read in bounded pages. Files are exported sequentially.
- Directory handles and provider access leases close on success, error, cancellation, and lock.
- Errors and progress contain counts and safe status text. They do not include private names, paths, object IDs, or destination URLs.
- The feature creates no app-private plaintext scratch solely to export a folder. Existing share-sheet scratch limits do not impose a new album-export size limit.

## Android

Use `ACTION_OPEN_DOCUMENT_TREE` through the Activity Result API. Retain only the transient access required for this operation; do not persist a URI grant or put the URI in saved navigation state.

Create output documents within the selected tree through `DocumentsContract`. Stream into a `ParcelFileDescriptor`, then rename the incomplete document on successful publication. Remove the incomplete document if the operation fails and the provider permits it. The selected root directory must never be deleted by cleanup.

## iOS

Use a native document picker for a folder without copying the folder into the app. Retain security-scoped access for the complete export and balance its release on every completion path.

Coordinate access to file-provider destinations while the native writer owns the descriptor. Create files exclusively and keep them incomplete until publication. Do not move the export to an internal plaintext staging directory to avoid provider coordination.

## Required evidence

Tests cover pagination beyond the visible page, nested and empty albums, repeated memberships, changed catalog generations, cancellation between and during files, native/export/publication failure, partial-result counts, and directory lease cleanup.

Platform checks cover unsafe names, collisions, existing-file preservation, incomplete-file cleanup, expired picker results, and provider access lifetime. A platform build alone does not prove behavior with an external cloud or removable-storage provider. Device and provider acceptance must be recorded separately from unit tests and compilation.
