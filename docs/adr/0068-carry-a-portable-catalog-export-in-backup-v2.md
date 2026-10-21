# ADR-0068: Carry a Portable Catalog Export and a Record Commitment in Backup Version 2

- **Status:** Accepted
- **Date:** 2026-10-21
- **Decision owners:** @po4yka
- **Related:** [`../format/BACKUP_FORMAT_V2.md`](../format/BACKUP_FORMAT_V2.md), [`../format/BACKUP_FORMAT_V1.md`](../format/BACKUP_FORMAT_V1.md), [`../format/CATALOG_SCHEMA_V1.md`](../format/CATALOG_SCHEMA_V1.md) §15 and §18, [`../format/CANONICAL_ENCODING_V1.md`](../format/CANONICAL_ENCODING_V1.md) §2 and §15, [`0018`](0018-freeze-the-backup-package-framing-and-manifest-key.md), [`0038`](0038-adopt-sqlcipher-as-the-v1-catalog-engine.md), [`0039`](0039-freeze-the-catalog-header-commitment.md), [`0043`](0043-the-backup-manifest-carries-a-commitment-not-an-inventory.md), [`0066`](0066-pad-object-containers-to-size-buckets.md)

## Context

ADR-0018 and `CATALOG_SCHEMA_V1.md` §15 require that a backup package carries a canonical catalog export and never raw SQLCipher pages. The implementation did not do this. `chur-media::backup` copied the encrypted SQLCipher file into the `CatalogExport` record. That record carries one client's page format and SQLCipher build options. No other platform build is required to read it, and a round trip on one build does not prove a restore on another. `BACKUP_FORMAT_V1.md` recorded the divergence, and the portable-backup release gate stayed open because of it.

The final backup commit had a second gap. It seals the record count, the two entry counts, and the inventory commitment. The inventory commitment covers the container records only. The descriptor, the catalog export, and the identity envelope were bound to the package by their own authentication alone, and none of those binds the backup identity. An earlier fix compared only `catalog_generation` to refuse a catalog from another package of the same vault.

Every version 1 package that exists holds the raw SQLCipher copy. A restore must continue to read them.

## Decision

Allocate `backup_version` `0x0002` under the versioning path of ADR-0018. Every writer produces version 2. Readers accept version 1 and version 2.

**Catalog export.** The catalog travels as `CatalogExportV1`, a logical stream defined in `BACKUP_FORMAT_V2.md` §3 and allocated `export_version` `0x0001`:

- a header with `export_version`, `catalog_format_version`, `catalog_generation`, and `table_count`;
- every ordinary catalog table in ascending byte order of its name, with its column names in declared order and its row count;
- every row in ascending order of the table's primary key, or of all columns in declared order for a table without one;
- every value as one of four types: NULL, a SQLite INTEGER as eight bytes of two's complement, strict UTF-8 text, or a blob. A floating-point value has no encoding.

The stream has no SQLite page, header, salt, WAL segment, or FTS5 shadow table. The two FTS5 indexes are derived from rows the stream carries, and an import rebuilds them.

**Catalog export records.** The stream is cut into chunks of exactly 1 MiB, with a last chunk of 1 byte to 1 MiB. Each chunk is one record of type `0x03`, sealed with XChaCha20-Poly1305 under `BackupManifestKey` and a random nonce. The AAD is:

```text
CanonicalTuple("CHUR\x00BACKUP\x00CATALOG-EXPORT-AAD\x00V1",
    backup_version:u16, suite_id:u16, vault_id:bytes[16], backup_id:bytes[16],
    record_index:u64, record_type:u8, chunk_index:u32, is_final:bool)
```

The catalog export records are one contiguous run that starts at record index 2.

**Record commitment.** A running BLAKE3-256 commitment under the new tag `CHUR\x00BACKUP\x00RECORD-COMMITMENT\x00V1` covers, in package order, each record that is neither a container nor the final commit: `record_index:u64`, the 12-byte record header, and the payload. The version 2 final commit appends `catalog_export_record_count:u32` and `record_commitment:bytes[32]` to the version 1 fields. The inventory commitment of `BACKUP_FORMAT_V1.md` §7.2 continues to cover the containers. Restore verifies the record commitment and the inventory commitment before it writes a byte into the vault namespace.

**Restore.** A version 2 restore creates a new SQLCipher catalog with a new salt, imports the rows in one transaction, checks every foreign key, and rebuilds the search indexes. The record commitment is computed over the exact buffers the restore uses, with each catalog export record hashed as it is read and opened, and compared before any container is extracted or the descriptor is installed. Every catalog table is `STRICT`, so SQLite refuses a value of the wrong type, and one row holds at most 32 MiB of text and blobs. The restored descriptor commits to the header of that new catalog.

**Containers.** A container is read twice from the package: once for the step-4 inventory check and once to extract it. The extracted copy is now verified on its own, chunk by chunk under the object key, with its length and manifest commitment compared with its inventory entry. Before this change only its final commit was checked after extraction, so a package file changed between the two reads could install a container that differed from the one checked. This applies to version 1 and version 2 packages.

**Schema versions.** The export's `catalog_format_version` names the schema of its rows, and import builds the fresh catalog at exactly that version. Version 15 is the first version that writes an export, so an older value is `VAULT_CORRUPT` and a newer value than the reader knows is `UNSUPPORTED_VERSION`. When a later schema version exists, the reader keeps the ability to build each earlier schema from version 15 forward, imports the rows into it, and then runs the `CATALOG_SCHEMA_V1.md` §18 migration steps to the current version, as for a live catalog.

**Version 1.** A version 1 package restores through its unchanged path: the raw SQLCipher copy, its page authentication, its header commitment check, and the generation check.

## Alternatives considered

### A dedicated HKDF label for the export key

Rejected. The manifest and the final commit already share `BackupManifestKey` and differ only in their domain tag, and the export records follow the same rule. A new label would add a registry row and a context list under ADR-0034 without new separation: the AAD tag already prevents one record type from opening as another, and random 24-byte nonces make nonce reuse negligible at the bound of 65536 records.

### Seal the whole export as one record

Rejected. A catalog can be larger than any buffer a phone should hold, and a single record would have to be decrypted whole before the first row is checked. Fixed-size chunks keep memory at one chunk and one value.

### Export a SQL text dump

Rejected. SQL text needs an SQL parser in every restore implementation and has more than one valid spelling for one value. The typed stream has one encoding for each value.

### Export the FTS5 shadow tables

Rejected. They are SQLite's internal index structure, a storage detail of the same kind as SQLCipher pages.

### Commit to the non-container records inside the inventory commitment

Rejected. The inventory commitment is frozen by ADR-0018 and recomputed by version 1 readers. A separate commitment leaves version 1 unchanged.

## Consequences

### Positive

- the package carries no SQLCipher page, so the catalog in a backup does not depend on one client's SQLCipher build or page options;
- a catalog export record moved, removed, or taken from another backup of the same vault fails its AEAD before it is parsed;
- the descriptor, every catalog export record, and the identity envelope are bound to the package by the final commit;
- a whole-package restore can compare catalogs by their logical export, which the tests do table by table.

### Tradeoffs

- a backup reads every catalog row and a restore writes every row and rebuilds two FTS5 indexes, which costs more time than a file copy;
- the record count of a package is larger, by one record for each MiB of catalog;
- a reader must keep the version 1 restore path for as long as version 1 packages can exist.

## Security impact

Affected invariants: SEC-007, SEC-013, SEC-018. The pre-authentication surface is unchanged: the same 32-byte preamble and 12-byte record headers. Each catalog export record is authenticated before it is parsed, and the stream parser bounds every name, count, and value before it allocates. Table and column names must be lower-case ASCII identifiers that equal the names of the fresh catalog, so an imported name cannot change the SQL it is quoted into. A row that breaks a constraint or a reference is `VAULT_CORRUPT`, and the partial vault directory is removed.

## Compatibility impact

Version 1 packages restore as before. A build that reads only version 1 refuses a version 2 package as `UNSUPPORTED_VERSION`. The historical vector `backup-v1-unsupported-version` names `0x0002`, which was unallocated when it was frozen. `TEST_VECTORS.md` §8 keeps its bytes, the manifest marks it `superseded_by` `backup-v2-unsupported-version`, which records the current rule with `0x0003`, and the reader test that runs every reject vector skips it.

## Validation

- vectors `backup-v2-*` for the preamble, the export stream, a sealed export record, the record commitment, and the version 2 final commit, and for a spliced, reordered, and truncated export and an altered descriptor;
- `chur-media` `tests/backup_flow.rs`: a populated vault round trip whose restored catalog equals the source in every table; moved, cut, and foreign export records that install nothing; and a version 1 package written by the build before this change, with a version 1 object container, that restores and then travels through a version 2 package;
- `chur-catalog` `catalog_export` tests for the import refusals and for every exported table being `STRICT`;
- `chur-media` `a_container_changed_after_its_check_installs_nothing`, whose source changes one container byte between the two reads;
- `chur-cli` `tests/reject_vectors.rs`, which runs every reject vector that is not superseded through its reader.

## Follow-up

- restore of a version 2 package on each supported platform, which the portable-backup release gate still requires;
- the independent review of the backup construction, which the gate still requires.
