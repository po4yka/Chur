# ADR-0018: Freeze the Backup Package Framing and Manifest Key

- **Status:** Accepted
- **Date:** 2026-08-27
- **Decision owners:** @po4yka
- **Related:** [`../format/BACKUP_FORMAT_V1.md`](../format/BACKUP_FORMAT_V1.md), [`../format/CANONICAL_ENCODING_V1.md`](../format/CANONICAL_ENCODING_V1.md), [`../format/CATALOG_SCHEMA_V1.md`](../format/CATALOG_SCHEMA_V1.md), [`0013`](0013-allocate-v1-format-constants.md)

## Context

`BACKUP_FORMAT_V1.md` named `PublicBackupPreamble` in its package model and in restore step 1 and defined it nowhere: no length field, no record framing, no offsets. It also said the outer framing "may be a Chur-native archive" and that an optional `age` envelope "may wrap" it, which gave four possible shapes with no byte that separates them. §7 computed an ordered inventory commitment over entries whose order was never stated, so two conforming writers could commit to different bytes for the same vault. §4 named "a dedicated root-derived backup key or portable backup content key" while only the first had a registered HKDF label, and `CATALOG_SCHEMA_V1.md` §15 left raw SQLCipher pages permitted "by default".

## Decision

- `PublicBackupPreamble` is exactly 32 bytes at offset 0: magic `CHURBAK1`, `backup_version`, `canonical_encoding_profile`, `suite_id`, `flags`, `public_header_length`, `reserved`, and `record_count`, with `record_count` the only variable field;
- every component after it is a record with a fixed 12-byte header carrying `record_type`, `record_version`, `reserved`, and a `u64` `payload_length`;
- the first eight bytes of a file select the framing: `CHURBAK1` is a native package, the `age` binary and armored header prefixes select an `age` layer, and anything else is rejected. Exactly zero or one `age` layer is permitted and its plaintext must begin with `CHURBAK1`;
- inventory entries have one total order — `object_id`, then `stream_id`, then `stream_revision` for streams, then `slot_id` for slots — and the commitment is BLAKE3-256 over the new domain tag `CHUR\x00BACKUP\x00INVENTORY-COMMITMENT\x00V1` followed by those entries in that order;
- the backup manifest is sealed under `BackupManifestKey` alone, derived under `chur/v1/root/backup-manifest` with `vault_id` and `backup_id` as context; the "portable backup content key" alternative is deleted;
- a package carries the canonical catalog export and never raw SQLCipher pages.

## Alternatives considered

### Keep the Chur-native archive as a second outer shape

Rejected. A second shape needs its own magic, its own parser, and its own limits, and it buys nothing the record sequence does not already give.

### Add a key-source discriminant to the preamble instead of deleting the alternative

Rejected. Every portable slot restores the same `VaultRootSecret`, so the discriminant would always hold one value while widening the pre-authentication parse surface.

## Consequences

### Positive

- a restorer identifies the file, its version, and its record count before any credential exists;
- the same vault content yields the same inventory commitment from any implementation, and restore has one manifest-key derivation with no branch.

### Tradeoffs

- `record_count` is public, so the number of package records leaks unless an `age` layer is used; §9 already listed this;
- exporting the catalog canonically costs a serialization pass that a raw page copy would not.

## Security impact

Affected invariants: SEC-007, SEC-013, SEC-018. The pre-authentication surface is now a fixed 32-byte preamble plus fixed record headers, all bounded by §13 before allocation. Deleting the undefined second manifest key removes a path where an implementation could invent a derivation that the label registry does not cover.

## Compatibility impact

No backup packages exist, so nothing migrates. `backup_version` remains the versioning path.

## Validation

- vectors for a minimal package, an `age`-wrapped package, and a nested wrapper that must be rejected;
- vectors that reorder inventory entries and must produce a different commitment, restored on Android, iOS, and the CLI.

## Follow-up

- freeze the sealed field widths of `BackupManifestV1` itself;
- specify the incremental package of §6, which remains proposed.
