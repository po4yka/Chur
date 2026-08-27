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

Where [`../CRYPTOGRAPHY.md`](../CRYPTOGRAPHY.md) §32 derives a commitment over the decrypted canonical manifest, this section governs container bytes under the authority hierarchy in [`../README.md`](../README.md).

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
- index starts at zero and increases once per successfully journaled chunk;
- no overflow;
- abandoned import cannot restart under the same key/prefix;
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
manifest ciphertext commitment
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
manifest_ciphertext_commitment
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
stream authenticated chunks and journal progress
write encrypted final commit
fsync file
structurally verify and compare ordered commitment
atomic rename to committed namespace
commit catalog entry/envelope
```

The catalog never exposes a temp/incomplete container as active media.

## 15. Corruption classification

- malformed preamble/record → `OBJECT_CORRUPT`;
- unsupported version/suite → `UNSUPPORTED_*`;
- missing final commit with valid prefix → `OBJECT_INCOMPLETE`;
- AEAD/tag/commitment mismatch → `OBJECT_CORRUPT`;
- missing key envelope → inaccessible key state, not necessarily corrupt bytes;
- truncated current transfer with valid journal → resumable incomplete state.

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
- import resume without nonce reuse.
