# ADR-0062: Create Independent Selective Archives

- **Status:** Proposed
- **Date:** 2026-10-16
- **Decision owners:** @po4yka
- **Related:** [Product contract](../product/SELECTIVE_ENCRYPTED_EXPORT.md), [Backup format](../format/BACKUP_FORMAT_V1.md), [Private notes](0061-local-private-notes.md)

## Context

A full backup contains the selected vault identity, its root-bound catalog, and its portable credentials. Removing unselected files from that package cannot establish a separate access boundary. Users need to export selected content under a separate password and to read it either as an independent vault or by importing copies into an existing vault.

## Decision

Build a fresh temporary vault with a new root and the archive password. Resolve a bounded logical selection, then stream authenticated plaintext from revocable readers into new encrypted containers and a new catalog. Copy only the selected display metadata, attached tags, selected album subtrees, and selected notes. Generate new identifiers and rebuild search projections. Wrap this independent vault in the existing CHURBAK1 package.

Separate restore keeps the existing authenticated restore protocol. Import first restores and verifies the complete package in isolated encrypted staging, then adds fresh copies into the unlocked destination. It does not replace files, albums, or notes or merge them by name. Tags retain the destination catalog’s unique-name rule: reuse an existing equal-name tag, otherwise create a new tag. Committed originals with display metadata, and complete notes, remain if a later step fails or the user cancels. Tags, previews, and final album organization can remain incomplete, including new empty albums.

Password fields, selection, and file-picker ownership belong to the current session. An old result cannot consume a new session's credential. The destination remains unpublished until success. A native output limit matches each host scratch reservation. Source keys, credential slots, sync or sharing identity, unrelated catalog rows, and the sibling identity never cross this boundary.

## Alternatives considered

A filtered source backup retains the source root and credentials and can disclose unrelated catalog history. An encrypted ZIP adds a new format and crypto implementation without the authenticated restore machinery. A new standalone format also requires a second parser. Reusing CHURBAK1 with independent data and keys avoids these costs.

## Consequences

Export decrypts and encrypts selected content and needs temporary encrypted storage. It is slower than copying a full encrypted backup. Import does not promise a transaction across all items; whole-package authentication precedes the first mutation, then completed items are durable. Source edits invalidate an export snapshot. Existing media, catalog v9, and package formats remain unchanged; ABI 2.22 adds two operation entry points. No production dependency is added.

## Validation

Verify exact restored bytes and selected metadata, independent keys and credentials, no unrelated or sync state, no mutation before package authentication, bounded streaming, cancellation and deletion barriers, destination limits, and cleanup. Compile and exercise the Kotlin/JNI and Kotlin/C paths separately from Rust tests.
