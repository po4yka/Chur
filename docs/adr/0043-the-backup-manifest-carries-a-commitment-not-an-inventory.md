# ADR-0043: The Backup Manifest Carries the Inventory's Commitment, Not Its Entries

- **Status:** Accepted
- **Date:** 2026-08-28
- **Decision owners:** @po4yka
- **Related:** [`../format/BACKUP_FORMAT_V1.md`](../format/BACKUP_FORMAT_V1.md), [`../assurance/PERFORMANCE_BUDGETS.md`](../assurance/PERFORMANCE_BUDGETS.md), [`0034`](0034-freeze-the-hkdf-context-element-lists.md)

## Context

`BACKUP_FORMAT_V1.md` §4 lists an "object inventory: IDs, versions, ciphertext lengths, commitments" among the manifest's contents, and §13 caps the manifest record payload at 16 MiB and the inventory at 1048576 stream entries. §7.1 fixes the entry at 109 bytes. Those three statements cannot all hold: a full inventory at the entry bound is about 109 MB, seven times the payload cap.

The bounds are not merely inconsistent on paper. `CATALOG_SCHEMA_V1.md` §21 admits a million objects, so a real vault can reach the entry bound, and `PERFORMANCE_BUDGETS.md` §4 requires that multi-gigabyte objects do not scale memory with size — a rule a manifest that materialized its own inventory would break before the first byte of content was read.

Two smaller gaps sat beside it. §4's manifest key sentence named `vault_id` and `backup_id` as the context elements of `chur/v1/root/backup-manifest`, and [ADR-0034](0034-freeze-the-hkdf-context-element-lists.md) froze that label's element list as `vault_id` alone. And no AAD was specified for the manifest or for the final backup commit, so §7's "authenticated final backup commit" named a record with no defined authentication.

## Decision

- the manifest carries the §7.2 ordered inventory commitment, the two entry counts, and the catalog generation. It does not carry the entries;
- each stream inventory entry travels in the head of the record it describes: an object container record is its 109-byte entry followed by the container's own ciphertext. A slot inventory entry is derived from the portable descriptor;
- a reader recomputes the commitment as it walks the package and compares it against the value the final backup commit seals, so completeness is authenticated with one entry in memory at a time;
- §5's "completeness verification checks every inventory entry" is implemented as recomputing each container's manifest commitment and ordered chunk commitment from the package's own bytes. Neither needs a key: `OBJECT_CONTAINER_V1.md` §5 takes the first over the manifest record and §10 takes the second over the chunk records in order, and those records are a contiguous range between the manifest and the final commit;
- two domain tags are allocated: `CHUR\x00BACKUP\x00MANIFEST-AAD\x00V1` and `CHUR\x00BACKUP\x00FINAL-COMMIT-AAD\x00V1`. Both records are sealed under `BackupManifestKey` and differ only in the tag, so neither opens as the other;
- both AADs bind `backup_version`, `suite_id`, and `vault_id`. They do not bind `backup_id`, because a restore must open the manifest before it can learn which backup it is reading. The identifier is inside both sealed plaintexts and the two must name the same backup;
- §4's manifest-key sentence is corrected to the frozen element list.

## Alternatives considered

### Raise the manifest payload cap to fit the inventory

Rejected. It resolves the arithmetic and keeps the memory cost: a restore would allocate over a hundred megabytes for a package it has not authenticated yet, which is the allocation-before-authentication shape §2.1 avoids for `record_count`.

### Lower the entry bound to what 16 MiB holds

Rejected. It would cap a package at about 153000 streams, so a vault the catalog admits could not be backed up at all. A format that cannot carry a legal vault is a worse answer than a format that carries it in a different shape.

### Give the inventory its own record type

Rejected as unnecessary rather than wrong. It would need a new `record_type` allocation, and it would still put the whole inventory in one place. Distributing the entries costs no allocation and puts each one beside the bytes it describes, which is also where a reader wants it.

### Bind `backup_id` in the AAD and carry it in the public preamble

Rejected. §2.1 fixes the preamble at 32 bytes with `record_count` as its only variable field, and widening it to fit an identifier changes a frozen public header to gain protection the sealed plaintext already provides. The residual case a bound identifier would catch is an authentic older package of the same vault, and §10 already states that a backup can be authentic but old and that detecting it needs an external checkpoint.

## Consequences

- a package of a million-object vault is read and written with one entry and one 256 KiB buffer in flight;
- verification is a BLAKE3 pass over the package's content and no decryption, so a package can be verified for a vault this build could not open, and the restore of a damaged or reordered package fails before anything is installed;
- `free_space_required` stays in the manifest for a caller that can ask the platform for capacity. The Rust restore does not run the §13 preflight itself: the standard library exposes no capacity call, and the only way to ask needs `unsafe` in a crate that forbids it;
- the two new tags bring the §15.5 registry to twenty, and the prefix-free argument there is extended to cover them.
