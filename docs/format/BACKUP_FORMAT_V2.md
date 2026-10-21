# Chur Backup Format v2

> **Status:** Accepted delta of [`BACKUP_FORMAT_V1.md`](BACKUP_FORMAT_V1.md), [ADR-0068](../adr/0068-carry-a-portable-catalog-export-in-backup-v2.md)

Version 2 of the backup package changes two things. The catalog travels as a portable logical export in sealed records, not as a copy of the SQLCipher file. The final backup commit also seals a commitment over every record that is not a container. Everything that this document does not change is as [`BACKUP_FORMAT_V1.md`](BACKUP_FORMAT_V1.md) defines it: the outer framing, the record header, the manifest plaintext, the inventory entries and their commitment, the portable slots, and the limits of §13 there.

## 1. Scope

Every writer produces version 2. A reader accepts version 1 and version 2. A version 1 package restores through the path of [`BACKUP_FORMAT_V1.md`](BACKUP_FORMAT_V1.md) §8 with no change (§8 below).

## 2. Preamble and record order

The public preamble is the 32 bytes of `BACKUP_FORMAT_V1.md` §2.1 with `backup_version` `0x0002`. `record_count` is between 2 and 1114112: the version 1 bound of 1048576 plus at most 65536 catalog export records. Every record header carries `record_version` `0x01`.

The manifest plaintext is the 117 bytes of version 1, and its `backup_version` field holds `0x0002`. The manifest AAD and the final-commit AAD carry `backup_version` `0x0002`, so a record sealed for one version does not open as the record of the other.

The records appear in exactly this order, and a reader refuses any other order as `VAULT_CORRUPT`:

```text
index 0                  encrypted backup manifest            0x01
index 1                  portable vault descriptor            0x02
index 2 .. 2+N-1         catalog export records, N >= 1        0x03
next M records           object container entries             0x04
next 0 or 1 record       identity envelope                    0x05
last                     final backup commit                  0x07
```

`record_index` is the zero-based position of a record after the preamble.

## 3. Catalog export stream

`CatalogExportV1` is one byte stream. It uses the primitives of [`CANONICAL_ENCODING_V1.md`](CANONICAL_ENCODING_V1.md) §2. It holds no SQLite page, file header, salt, WAL segment, or FTS5 shadow table.

```text
CatalogExportV1 =
    export_version:u16              0x0001
    catalog_format_version:u16
    catalog_generation:u64
    table_count:u32                 1 to 256
    table[table_count]

table =
    table_name:string               ascending byte order across tables
    column_count:u16                1 to 64
    column_name:string[column_count]  in declared column order
    row_count:u64
    value[row_count * column_count]   row by row, column by column

value =
    0x01                            NULL
  | 0x02 integer:bytes[8]           SQLite INTEGER, 64-bit two's complement, big-endian
  | 0x03 text:string                strict UTF-8, u32 byte length, at most 16 MiB
  | 0x04 blob:variable bytes        u32 length, at most 16 MiB
```

### 3.1 Tables

The stream carries every ordinary table of the catalog. It does not carry a virtual table, an FTS5 shadow table, or a table whose name begins with `sqlite_`. A table or column name is 1 to 64 bytes of lower-case ASCII letters, digits, and `_`. A reader refuses any other name as `VAULT_CORRUPT`.

### 3.2 Rows

Rows are in ascending order of the table's declared primary key columns, in primary-key position order. A table without a declared primary key orders its rows by all columns in declared order. Every catalog column uses the `BINARY` collation, so the order is byte order for text and blobs and numeric order for integers.

### 3.3 Values

[`CANONICAL_ENCODING_V1.md`](CANONICAL_ENCODING_V1.md) §2 forbids signed integers unless a focused specification defines them. This section defines them: a SQLite INTEGER is the eight bytes of its 64-bit two's complement form, most significant byte first. A floating-point value has no encoding. A writer that finds one stops with `CATALOG_CORRUPT`, and no catalog column holds one.

### 3.4 Search indexes

The two FTS5 indexes, `object_search` and `private_note_search`, are not in the stream. Their terms are derived from rows that the stream carries. An import rebuilds them with the rules of the live catalog: an object is in `object_search` while its state is `ACTIVE`, with its active metadata, its tag names, and the content text of its current primary stream; every note is in `private_note_search`.

### 3.5 End

The stream ends exactly after the last value of the last table. A reader refuses bytes or records after it, and a stream that ends inside a value, as `VAULT_CORRUPT`.

## 4. Catalog export records

The stream is cut into chunks. Every chunk except the last holds exactly 1048576 bytes. The last chunk holds 1 to 1048576 bytes. One stream therefore has one chunk sequence, and a stream whose length is a multiple of 1048576 ends with a full last chunk.

Chunk `k` is the payload of record `2 + k`:

```text
payload = nonce:bytes[24] || XChaCha20-Poly1305(BackupManifestKey, nonce, chunk, aad)

aad = CanonicalTuple(
      "CHUR\x00BACKUP\x00CATALOG-EXPORT-AAD\x00V1",
      backup_version:u16 = 0x0002, suite_id:u16 = 0x0001,
      vault_id:bytes[16], backup_id:bytes[16],
      record_index:u64, record_type:u8 = 0x03,
      chunk_index:u32, is_final:bool)
```

`BackupManifestKey` is the key of `BACKUP_FORMAT_V1.md` §4. The nonce is random. A record opens only for its vault, its backup, its record index, its chunk index, and its final flag. A record from another backup, a record moved to another place, and an export cut at a record boundary each fail the AEAD as `VAULT_CORRUPT`. A reader also refuses a non-final chunk that is not exactly 1048576 bytes and a payload above 1048616 bytes.

## 5. Record commitment

```text
record_commitment = BLAKE3-256(
      "CHUR\x00BACKUP\x00RECORD-COMMITMENT\x00V1"
   || for each record that is neither a container entry nor the final commit,
      in package order:
         record_index:u64 || record header (12 bytes) || payload
)
```

The domain tag is a fixed byte constant with no length prefix, as in `BACKUP_FORMAT_V1.md` §7.2. The covered records are the manifest, the descriptor, every catalog export record, and the identity envelope when there is one. The payload is fed as the package holds it. For a sealed record that is the nonce and the ciphertext.

## 6. Final backup commit

The version 2 plaintext is 100 bytes:

```text
backup_id:bytes[16]
record_count:u64
stream_entry_count:u32
slot_entry_count:u32
inventory_commitment:bytes[32]
catalog_export_record_count:u32     1 to 65536
record_commitment:bytes[32]
```

It is sealed as in version 1, under the final-commit AAD with `backup_version` `0x0002`.

## 7. Restore

Restore follows `BACKUP_FORMAT_V1.md` §8 with these changes:

1. Step 4 also checks the record order of §2 and requires `catalog_export_record_count` to equal the number of catalog export records. The record commitment of §5 is computed over the exact buffers the restore uses: the manifest, the descriptor, and the envelope it already holds, and each catalog export record as the import reads and opens it. No byte reaches the catalog that the commitment did not cover. The commitment is compared after the import and before any container is extracted or the descriptor is installed. A difference is `VAULT_CORRUPT`.
2. Step 6 creates a new SQLCipher catalog with a new salt, then imports the stream in one transaction:
   - the reader builds the schema at the stream's `catalog_format_version` (§9);
   - the stream must name exactly the tables of that schema, in §3.1 order, with exactly its columns in declared order;
   - every row is inserted; a row that breaks a constraint is `VAULT_CORRUPT`. Every catalog table is `STRICT`, so a value whose type differs from its column's declared type breaks a constraint, and an import refuses a schema with a table that is not `STRICT`. The text and blob values of one row total at most 32 MiB;
   - every foreign key is checked after the last row; a violation is `VAULT_CORRUPT`;
   - the search indexes are rebuilt by §3.4;
   - the `vault_state` row must hold the stream's `catalog_format_version` and `catalog_generation`.
3. The generation check of `BACKUP_FORMAT_V1.md` §8 step 6 applies to the imported catalog.
4. The restored descriptor commits to the header of the new catalog. The header commitment that the package descriptor carries belongs to the source file and is not compared.
5. Each container is copied from the package in a second read, after the step-4 check of the package bytes. The extracted file is checked again on its own: its length and manifest commitment against the inventory entry, and every chunk and the final commit under the object key. A container that changed between the two reads therefore does not install. The same rule applies to version 1 packages.

A failure at any step removes the partial vault directory, so nothing openable remains.

## 8. Version 1 packages

A version 1 package carries one `CatalogExport` record that holds a copy of the source SQLCipher file, and a 64-byte final commit with no record commitment. A reader restores it through `BACKUP_FORMAT_V1.md` §8: it writes the copy, compares its header with the descriptor's commitment, authenticates every page, and checks the generation. A version 2 writer never produces this shape.

## 9. Catalog schema versions

The stream's `catalog_format_version` names the schema of its rows, and import builds the fresh catalog at exactly that version. Version 15 is the first catalog version that writes a stream. A reader refuses a lower value as `VAULT_CORRUPT`, because no writer can produce it, and a value above the newest schema it knows as `UNSUPPORTED_VERSION`.

When a later catalog version exists, a reader keeps the ability to build each schema from version 15 forward. It imports an older stream into the schema of the stream's version, then runs the [`CATALOG_SCHEMA_V1.md`](CATALOG_SCHEMA_V1.md) §18 migration steps in order to the current version, as it does for a live catalog, and records the migrated version in the restored descriptor.

## 10. Limits

- `record_count` between 2 and 1114112;
- 1 to 65536 catalog export records, each payload at most 1048616 bytes;
- 1 to 256 tables, 1 to 64 columns in each, names of 1 to 64 bytes;
- a text or blob value at most 16777216 bytes, and the text and blob values of one row at most 33554432 bytes;
- `restore_limited` bounds the sum of the catalog export payloads before it reads one;
- a reader allocates no more for a value than the stream holds, so a forged length cannot cause a large allocation.

## 11. Test vectors

`backup-v1-unsupported-version` is superseded by `backup-v2-unsupported-version`, because it names `0x0002`. `backup-v2-public-preamble`, `backup-v2-catalog-export-stream`, `backup-v2-catalog-export-record`, `backup-v2-record-commitment`, and `backup-v2-final-commit` are positive. `backup-v2-catalog-export-spliced-from-another-backup`, `backup-v2-catalog-export-reordered`, `backup-v2-catalog-export-truncated`, `backup-v2-descriptor-altered`, and `backup-v2-unsupported-version` are negative. Whole packages stay out of the vector set because their nonces are random. `chur-media` `tests/backup_flow.rs` restores them, including a version 1 package that a build before ADR-0068 wrote.
