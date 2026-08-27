# Chur Object Container v1

> **Status:** Proposed normative format; the public byte layout, v1 constants, and commitment constructions in this document are frozen. Sealed-record plaintext schemas and deterministic vectors remain outstanding.

`ChurObjectV1` stores one immutable encrypted stream, such as an original photo/video/audio file or an encrypted derived asset. The object key envelope is stored separately.

## 1. Properties

- independent random object key;
- independent authenticated chunks;
- bounded random access;
- one-pass streaming import even when final size is initially unknown;
- explicit authenticated completeness;
- immutable committed bytes;
- no plaintext filename or media metadata in the public preamble.

## 2. Logical layout

```text
+-------------------------------+
| PublicPreambleV1              |
+-------------------------------+
| EncryptedManifestRecordV1     |
+-------------------------------+
| ChunkRecordV1[0]              |
+-------------------------------+
| ChunkRecordV1[1]              |
+-------------------------------+
| ...                           |
+-------------------------------+
| ChunkRecordV1[N-1]            |
+-------------------------------+
| FinalCommitRecordV1           |
+-------------------------------+
```

Committed containers are immutable. Metadata updates or new previews create separate revisions/assets.

## 3. Public preamble

`PublicPreambleV1` is exactly 28 bytes and begins at file offset 0. Integers are unsigned big-endian per [`CANONICAL_ENCODING_V1.md`](CANONICAL_ENCODING_V1.md) §2.

```text
offset  size  field                           v1 value
0x00     8    magic                           43 48 55 52 4F 42 4A 31   "CHUROBJ1"
0x08     2    container_version:u16           0x0001
0x0A     2    canonical_encoding_profile:u16  0x0001
0x0C     2    suite_id:u16                    0x0001
0x0E     2    flags:u16                       0x0000
0x10     4    public_header_length:u32        0x0000001C   (28)
0x14     4    manifest_record_length:u32      variable
0x18     2    chunk_record_profile:u16        0x0001
0x1A     2    reserved:u16                    0x0000
0x1C          end of preamble
```

`suite_id` `0x0001` denotes XChaCha20-Poly1305 for AEAD, BLAKE3-256 for commitments, and HKDF-SHA-256 for key derivation. `chunk_record_profile` `0x0001` denotes the chunk record framing in §8. The eight magic bytes are reserved to this format and must not be reused by another Chur file format.

A v1 reader must reject the container unless:

- `magic` matches all eight bytes;
- `flags`, `reserved`, and `public_header_length` hold exactly their listed v1 values;
- `manifest_record_length` is between 40 and 65536 inclusive;
- `container_version`, `canonical_encoding_profile`, `suite_id`, and `chunk_record_profile` are supported values.

An unknown version, profile, or suite fails as `UNSUPPORTED_*`. A fixed field that holds any other value fails as `OBJECT_CORRUPT`; it is never ignored.

`container_version` and `suite_id` are bound into every chunk AAD (§9) and into the final-commit AAD (§11). Every other preamble field except `manifest_record_length` is a constant compared byte for byte, so `manifest_record_length` is the only variable field in the preamble. A modified value is detected as a manifest AEAD failure, never as a successful parse.

Forbidden public fields:

- user filename/path;
- MIME type;
- dimensions/duration;
- EXIF/GPS/date;
- album/collection name;
- plaintext content hash;
- real/decoy role.

## 4. Object-derived keys

```text
ManifestKey    = HKDF(ObjectKey, "chur/v1/object/manifest")
ContentKey     = HKDF(ObjectKey, "chur/v1/object/content")
FinalCommitKey = HKDF(ObjectKey, "chur/v1/object/final-commit")
```

Separate containers/assets may use additional domain keys. Derived keys are not persisted.

## 5. Encrypted manifest

Manifest plaintext conceptually includes:

```text
object_id
stream_id
stream_kind
stream_revision
source_content_revision when derived
chunk_size
nonce_prefix[16]
manifest_generation
immutable media properties permitted by policy
commitment_profile
```

Plaintext field widths and the schema of `immutable media properties permitted by policy` are not yet frozen. They are sealed bytes and do not affect the public layout below.

`EncryptedManifestRecordV1` begins at offset `0x1C` and occupies `manifest_record_length` bytes:

```text
offset  size                         field
0x00    24                           manifest_nonce
0x18    manifest_record_length - 24  manifest_ciphertext_and_tag
```

The manifest is sealed with a fresh nonce under `ManifestKey`. Its commitment is:

```text
manifest_commitment = BLAKE3-256(
      "CHUR\x00OBJECT\x00MANIFEST-COMMITMENT\x00V1"
   || manifest_nonce
   || manifest_ciphertext_and_tag
)
```

The domain tag is a fixed ASCII byte constant with no length prefix, per [`CANONICAL_ENCODING_V1.md`](CANONICAL_ENCODING_V1.md) §3 and §7. The output is 32 bytes. The commitment covers the sealed record, so it is computable before any key is available. It is bound into every chunk AAD (§9) and into the final commit (§11).

The manifest must not contain the wrapped `ObjectKey`, avoiding circular dependency.

## 6. Chunk size

Initial benchmark candidates:

- 256 KiB for small/photo/derived streams;
- 1 MiB for video and large audio.

Chunk size is recorded in the authenticated manifest and constrained by a supported range. It is not inferred from file type outside the encrypted manifest.

## 7. Nonce construction

For content chunks:

```text
prefix = random 16 bytes per stream revision
nonce = prefix || chunk_index_u64_be
```

Requirements:

- prefix generated by Rust CSPRNG;
- every index is reserved durably in the import journal before it is used (§14.2);
- index starts at zero, increases by one, and is never encrypted twice (§14.3);
- no overflow;
- an abandoned import is dead and never restarts under the same key/prefix (§14.4);
- a new stream revision always uses a fresh prefix.

Manifest and final-commit nonces are independently random and use separate derived keys.

## 8. Chunk record

`ChunkRecordV1` is the framing selected by `chunk_record_profile` `0x0001`. Its header is exactly 20 bytes:

```text
offset  size               field                v1 value
0x00     1                 record_type:u8       0x01
0x01     1                 record_version:u8    0x01
0x02     2                 reserved:u16         0x0000
0x04     8                 chunk_index:u64
0x0C     4                 plaintext_length:u32
0x10     4                 ciphertext_length:u32
0x14     ciphertext_length ciphertext_and_tag
```

The first record after the manifest record begins at offset `0x1C + manifest_record_length`. Each later record begins immediately after the previous one.

`chunk_index` and `plaintext_length` are redundant with record order and with `ciphertext_length`. They are retained so that the parser validates structure without decryption.

A reader dispatches on `record_type` before it reads any other field:

- `0x01` is a `ChunkRecordV1` with the 20-byte header above;
- `0x02` is a `FinalCommitRecordV1` with the different 32-byte header of §11. It must never be parsed with the chunk header. It ends the chunk sequence, does not increment the chunk counter, and is not a chunk record for §10;
- any other value fails as `OBJECT_CORRUPT`.

Every record must carry `record_version` `0x01` and `reserved` `0x0000`.

A reader must additionally reject a `ChunkRecordV1` unless:

- `ciphertext_length` equals `plaintext_length + 16` for suite `0x0001`;
- `chunk_index` equals the number of chunk records already read, so indexes start at zero, increase by one, and never repeat or reorder;
- the record and its header fit in the remaining file bytes under checked `u64` arithmetic;
- `plaintext_length` equals the manifest `chunk_size` for every record except the last, and is between 1 and `chunk_size` inclusive for the last record.

The last rule is the only one that needs the decrypted manifest; the rules above it are checkable without any key. It gives one plaintext under one `chunk_size` exactly one valid chunking, so container bytes are reproducible, and it is what makes the seek in §12 a computation rather than a scan.

## 9. Chunk AAD

Canonical AAD binds:

```text
domain tag
container version and suite
object ID
stream ID and kind
stream revision
manifest commitment
chunk index
plaintext length
```

Total object length/count are not required in each chunk AAD because they may be unknown at import start. They are authenticated in the final commit.

## 10. Ordered commitment

The writer updates one BLAKE3-256 hasher over the exact wire bytes of every chunk record, in ascending `chunk_index` order, after a fixed domain tag:

```text
ordered_chunk_commitment = BLAKE3-256(
      "CHUR\x00OBJECT\x00ORDERED-COMMITMENT\x00V1"
   || ChunkRecordV1[0]
   || ChunkRecordV1[1]
   || ...
   || ChunkRecordV1[N-1]
)
```

`ChunkRecordV1[i]` means all `20 + ciphertext_length` bytes of that record as written, header included. Feeding the header commits `chunk_index` and both lengths together with the ciphertext, so framing cannot be altered without changing the commitment. The output is 32 bytes. The `FinalCommitRecordV1` is never fed to this hasher.

For a zero-chunk object the commitment is `BLAKE3-256` of the domain tag alone.

Because the commitment covers framing, a future `chunk_record_profile` produces a different commitment for the same plaintext. Only profile `0x0001` is defined in v1.

The commitment value alone is not trusted; it is sealed inside the authenticated final commit.

## 11. Final commit

`FinalCommitRecordV1` is the last record in the file:

```text
offset  size                      field                         v1 value
0x00     1                        record_type:u8                0x02
0x01     1                        record_version:u8             0x01
0x02     2                        reserved:u16                  0x0000
0x04     4                        commit_ciphertext_length:u32
0x08    24                        commit_nonce
0x20     commit_ciphertext_length ciphertext_and_tag
```

`commit_ciphertext_length` must be between 16 and 4096 inclusive. No bytes may follow the record.

Final commit plaintext includes:

```text
object_id
stream_id
stream_revision
manifest_commitment
chunk_count
total_plaintext_length
last_chunk_plaintext_length
ordered_chunk_commitment
commit_generation
```

It is sealed under `FinalCommitKey` with fresh nonce and canonical AAD bound to container identity/version/suite. Plaintext field widths are not yet frozen. They are sealed bytes and do not affect the public layout above.

Absence or invalidity means the object is incomplete or corrupt, never complete.

## 12. Random access

For fixed-size non-final chunks, a reader calculates the containing chunk from plaintext offset, reads only required records, authenticates full affected chunks, and copies the requested range.

Because §8 fixes every non-final chunk at `chunk_size`, both values are computed without scanning:

```text
chunk_index   = plaintext_offset / chunk_size
record_offset = 0x1C + manifest_record_length
              + chunk_index * (20 + chunk_size + 16)
```

The reader returns:

```text
VerifiedRange(offset, length, session_generation)
```

It must not claim `CompleteVerifiedObject` unless manifest, every expected chunk/commitment, and final commit are validated.

## 13. Empty and small objects

The frozen layout defines:

- **zero-byte stream** — no chunk records; the final commit record follows the manifest record directly, with `chunk_count` 0, `total_plaintext_length` 0, `last_chunk_plaintext_length` 0, and `ordered_chunk_commitment` equal to `BLAKE3-256` of the ordered-commitment domain tag alone;
- **one partial chunk** — one record whose `plaintext_length` is less than `chunk_size`; it is also the last chunk, so `last_chunk_plaintext_length` equals that value;
- **exact multiple of chunk size** — every record carries `plaintext_length` equal to `chunk_size`, the last one included, so `last_chunk_plaintext_length` equals `chunk_size` and no zero-length trailing record is written.

Open, and listed under Follow-up in [`../adr/0008-freeze-object-container-v1-layout.md`](../adr/0008-freeze-object-container-v1-layout.md): the approved chunk-size range, the maximum supported plaintext size, and the maximum chunk count. All offset and index arithmetic is checked `u64`.

These cases require vectors.

## 14. Import transaction

```text
create temp opaque object
write preamble + encrypted manifest
per chunk: reserve the index in the journal, then write the chunk record
write encrypted final commit
fsync file
structurally verify and compare ordered commitment
atomic rename to committed namespace
commit catalog entry/envelope
```

The catalog never exposes a temp/incomplete container as active media.

### 14.1 Journal record

The import journal is the `ImportTransaction` state of [`CATALOG_SCHEMA_V1.md`](CATALOG_SCHEMA_V1.md) §11, which also fixes where it is stored. One record per transaction holds:

```text
transaction_id
temp container path ID
object_id, stream_id, stream_kind, stream_revision
reference to the inactive object-key envelope holding the wrapped ObjectKey
nonce_prefix
chunk_size
reserved_index      highest chunk index ever reserved, or none
stage
```

The envelope reference, `nonce_prefix`, and `chunk_size` are written when the transaction opens and are never rewritten; a resume reads them and never regenerates them. Only `reserved_index` and `stage` change while the transaction runs.

`reserved_index` also fixes the journaled ciphertext length, so that length is not stored twice:

```text
journaled_ciphertext_length = 0x1C + manifest_record_length
                            + reserved_index * (20 + chunk_size + 16)
```

It is both the offset at which the reserved record begins and the end of the last record the journal proves durable.

### 14.2 Durability ordering

The preamble and manifest record are written and fsynced before index 0 is reserved. For each chunk index `i`, in this order and no other:

1. set `reserved_index` to `i` in the journal record;
2. make that journal update durable;
3. write chunk record `i` to the container;
4. fsync the container.

Step 2 completes before step 3 begins, and step 4 completes before step 1 runs again for `i + 1`. A writer therefore never encrypts an index that is not already durably reserved, and never reserves an index while an earlier record is still only in a page cache. At any crash point:

- every index ever encrypted under this `(ContentKey, nonce_prefix)` pair is between 0 and `reserved_index` inclusive;
- every record below `reserved_index` is durable in the container;
- only the record at `journaled_ciphertext_length` is uncertain.

The rule holds for any journal that can make one record durable before it returns; it does not depend on where the journal is stored.

### 14.3 Resume

A resumed transaction:

- takes the `ObjectKey`, `nonce_prefix`, `chunk_size`, and `reserved_index` from the journal record, and never derives a next index from container bytes;
- when `reserved_index` is none, truncates the container to zero and writes the preamble and manifest again under a fresh manifest nonce; no chunk index has been reserved, so the object key and prefix are kept and the import starts at index 0;
- otherwise reads the record at `journaled_ciphertext_length`, which must parse as a `ChunkRecordV1` under §8 with `chunk_index` equal to `reserved_index`, and whose AEAD tag must verify under the §9 AAD for that index;
- truncates the container to the end of that record, discarding any trailing bytes, then reserves and writes index `reserved_index + 1` per §14.2, reading the source from plaintext offset `(reserved_index + 1) * chunk_size`.

A reserved index is never encrypted a second time, and `reserved_index` never decreases. If the record at `journaled_ciphertext_length` is absent, short, or unauthentic, its index has already consumed its nonce and §8 forbids a gap in the sequence, so the transaction is dead under §14.4 and the container is discarded rather than rewritten.

### 14.4 Abandonment

A transaction is dead when the check in §14.3 fails, when the user cancels the import, or when reconciliation finds a journal record whose temp container is absent or a temp container with no journal record. Death is recorded as one durable `stage` update, and cleanup then runs in this order:

1. destroy the object-key envelope, which makes the `ContentKey` and every byte written under it unrecoverable;
2. delete the temp container;
3. delete the journal record.

The `(ObjectKey, nonce_prefix)` pair of a dead transaction is retired and is never donated to another object. A retry is a new transaction with a new `ObjectKey`, a fresh prefix from the CSPRNG, and index 0; reusing the object or stream identifier is possible only under a new stream revision, which §7 already requires to take a fresh prefix.

## 15. Corruption classification

- malformed preamble/record → `OBJECT_CORRUPT`;
- unsupported version/suite → `UNSUPPORTED_*`;
- missing final commit with valid prefix → `OBJECT_INCOMPLETE`;
- AEAD/tag/commitment mismatch → `OBJECT_CORRUPT`;
- missing key envelope → inaccessible key state, not necessarily corrupt bytes;
- truncated current transfer whose reserved chunk record authenticates → resumable incomplete state per §14.3;
- truncated current transfer whose reserved chunk record is absent or unauthentic → dead transaction per §14.4, never resumed and never re-encrypted under the same index.

## 16. Parser limits

- preamble exactly 28 bytes, manifest record 40 to 65536 bytes, final-commit ciphertext 16 to 4096 bytes;
- approved chunk-size range;
- checked offsets and additions;
- ciphertext length exactly consistent with suite;
- no duplicate/out-of-order chunk index in complete scan;
- no trailing records after final commit;
- no decompression in core container v1 unless separately specified;
- bounded read buffers.

## 17. Test matrix

- zero/one/many chunks;
- partial final chunk/exact multiple;
- random seek across boundaries;
- missing, duplicated, reordered, substituted chunks;
- wrong manifest commitment;
- truncated tag/commit;
- forged lengths/counts;
- prefix/index overflow conditions;
- unknown suite/version/profile;
- wrong magic, non-zero `flags` or `reserved`, wrong `public_header_length`;
- zero-chunk and single-chunk containers with byte-exact expected output;
- interrupted write at every byte/record boundary;
- Android/iOS/CLI compatibility;
- crash between the durable index reservation and the chunk record write, and resume;
- crash between the chunk record write and the next index reservation, and resume;
- both resumes assert that the first index encrypted is above the journaled reservation and that no `(nonce_prefix, chunk_index)` pair is produced twice, which is also what fails a writer that journals a chunk after writing it.
