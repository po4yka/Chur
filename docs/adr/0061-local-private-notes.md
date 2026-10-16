# ADR-0061: Store Local Private Notes in the Encrypted Catalog

- **Status:** Proposed
- **Date:** 2026-10-16
- **Decision owners:** @po4yka
- **Related:** [`../product/PRIVATE_NOTES.md`](../product/PRIVATE_NOTES.md), [`../format/CATALOG_SCHEMA_V9.md`](../format/CATALOG_SCHEMA_V9.md), [`0004`](0004-rust-owned-private-catalog.md), [`0038`](0038-adopt-sqlcipher-as-the-v1-catalog-engine.md)

## Context

The public Notes shell intentionally stores ordinary public text and restores editor state. It cannot hold private notes. Immutable media originals also do not provide atomic text updates with a stable note identity. The requested scope is local private notes plus encrypted backup, with no note synchronization.

## Decision

- Rust owns a separate note entity in SQLCipher catalog v9. It owns identifiers, bounds, revisions, transactions, full-text search, Trash, and export.
- A note is not a media object or a caption. No media enum or immutable-container contract changes.
- The shared UI uses a separate model and controller. It reuses visual tokens, private input settings, and export sinks; it uses no public NoteStore or public editor persistence.
- Saving is explicit and uses compare-and-swap revisions. Failed save retains the in-memory editor until lock or disposal. Leaving with changes requires a choice; lock always clears it immediately.
- The private editor has no saved-state registry or automatic save from disposal. Clipboard writes and Cut are disabled; Paste remains an explicit action.
- Add Browse → Private notes as a contextual flow. The four main vault destinations remain the same.
- Existing encrypted backup includes the note tables. Existing sync, sharing, and connected-computer media APIs do not enumerate notes.
- A native cancellable export formats one saved revision as UTF-8 text and reuses the platform's deliberate plaintext-export lifecycle.

## Alternatives considered

Reusing the public editor would send private drafts to public persistence or saved-instance state. Using media captions would overload a metadata field and would not give a correct text export. Replacing an opaque media object on each save would change identifiers and separate related updates into several transactions. Each adds a boundary or consistency problem that a private catalog entity avoids.

## Consequences

The schema and ABI gain a bounded local notes surface. Lists return previews instead of all bodies. An older client cannot open a v9 catalog. Unsaved session text is lost on lock or process death, and the editor states this rule. New synchronization or sharing for notes needs its own protocol design.

## Security impact

Title, body, search data, and Trash remain encrypted at rest under the existing catalog key. Access and callbacks are bound to the session generation and selected identity. Managed strings and platform input/layout copies do not provide a secure-erasure guarantee. Keyboard settings request less retention but do not establish a trusted keyboard. Logical purge and secure-delete settings do not retract old backups or promise per-note cryptographic erasure.

## Compatibility impact

Catalog v8 migrates to v9 through the existing authenticated protocol. ABI 2.21 adds five exports and bounded private control records. Public Notes, media containers, and existing sync records do not change. No production dependency is added.

## Validation

Exercise authenticated and interrupted migration, strict record bounds, failed-call atomicity, optimistic conflicts, paging/search, real native persistence, lock and identity isolation, clipboard/saved-state exclusion, deliberate export, and selected-identity backup/restore. Verify native hosts and large-font UI separately from physical-device acceptance.
