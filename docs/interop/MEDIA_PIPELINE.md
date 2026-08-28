# Media Pipeline

> **Status:** Proposed cross-platform media-processing contract

Chur separates codec-specific platform work from Rust-owned identity, canonical metadata, encryption, persistence, and integrity.

## 1. Responsibilities

### Rust owns

- object/stream IDs and revisions;
- keys, nonces, AAD, containers, envelopes;
- canonical metadata schema and validation;
- encrypted originals and derived assets;
- catalog transactions and integrity state;
- import/export reader/writer lifecycle.

### Platform owns when required

- Photos/MediaStore/Files provider interaction;
- codec probing and decoding;
- HEIF/RAW/HDR/ProRes/system-format support;
- image resizing/color conversion;
- AVFoundation/Media3 player integration;
- platform share/save APIs.

Platform results are transient inputs to Rust, not a second private database.

## 2. Import stages

```text
Select source
→ acquire file representation/descriptor
→ validate capability and bounds
→ create Rust import transaction/object key
→ stream original encryption
→ probe canonical metadata
→ generate required derivatives
→ encrypt metadata/derivatives
→ final commit/fsync
→ catalog activation
→ release source/temp resources
```

Stages are cancellable and journaled. Lock cancels the transaction.

## 3. Source capability model

Adapter reports bounded facts:

```text
seekable: yes/no
known_length: optional u64
content_type_hint: untrusted
provider_kind: local/cloud/unknown
compound_asset_parts: bounded list
```

Rust must not trust size/type hints as authenticated truth.

## 4. Canonical metadata

Normalized model may include:

- media kind;
- MIME/UTType hints and detected codec/container;
- width/height/orientation;
- duration/timescale;
- capture/import timestamps;
- EXIF/GPS;
- color/HDR profile;
- audio channels/sample rate;
- compound asset relationships.

Rust validates ranges and serializes/encrypts the canonical representation. Raw provider dictionaries are not persisted wholesale without review.

## 5. Originals

Original bytes are preserved whenever feasible. Transforming import requires an explicit user/product policy because transcoding may lose metadata/quality and creates a new original content revision.

## 6. Derived assets

Planned kinds:

```text
small thumbnail
grid preview
screen preview
video poster frame
animated preview (future)
audio waveform
OCR text (future)
face record (future)
embedding record (future)
```

Each derived asset binds to:

```text
object_id
source_content_revision
asset_kind
asset_revision
generator_profile/version
```

Stale assets are never shown as current after original replacement.

The first four kinds are pictures and §12 gives each a long edge and a JPEG
quality. The audio waveform is not a picture, and §6.1 gives it its bytes; the
remaining kinds are future scope and have neither.

### 6.1 The audio waveform record

A waveform is a peak envelope over equal slices of a recording, not an image of
one. A drawing resamples it to whatever width it has, and it is drawn by shared
code that runs on both hosts, so one host must write what the other reads. The
record is therefore fixed here rather than left to each platform:

```text
offset  size          field                v1 value
0x00     1            record_version:u8    0x01
0x01     1            reserved:u8          0x00
0x02     2            bucket_count:u16     1 to 4096
0x04     4            duration_ms:u32      0 to 14400000
0x08     bucket_count peaks:u8[]           linear amplitude, 0 to 255
```

Integers are unsigned big-endian per
[`../format/CANONICAL_ENCODING_V1.md`](../format/CANONICAL_ENCODING_V1.md) §2.
A reader rejects a record whose `record_version` is not `0x01`, whose `reserved`
byte is not zero, or whose length is not `8 + bucket_count`; a record with
trailing bytes is rejected rather than truncated, as §8 there requires of every
canonical decoder. A record is at most 4104 bytes, which is the §12 bound.

Peaks are normalized against the loudest bucket, so a quiet recording draws at
full height and a silent one draws flat. The bucket count is the generator's
choice inside the bound, not a format constant: v1 generators produce 512.

The slices are equal, which is the one thing a generator must get right when it
does not know the recording's length in advance — a container that reports no
duration is ordinary. A generator that wrapped its index at the bucket count
would fold a later passage onto an earlier one and produce a superposition of
envelopes rather than one; v1 generators widen the slice instead, halving the
resolution each time the samples outgrow the buckets, so a recording of unknown
length ends at between half and all of the buckets it would otherwise have
used.

§11 applies unchanged. Two decoders of one recording may produce different peak
values, and the declared generator profile is what makes that a known difference
rather than a silent one. What may not differ is the record that carries them.

## 7. Compound media

Live Photos, spatial media, RAW+JPEG pairs, sidecar metadata, and similar items are represented as one logical object with multiple immutable streams/relationships when supported. Missing parts produce an explicit incomplete/unsupported state, not silent flattening.

## 8. Image display

Timeline uses encrypted thumbnail/preview assets. Full-resolution original is decrypted only for detailed viewing/export. Decoder buffers are session-scoped; disk plaintext cache is prohibited.

## 9. Video/audio playback

Player asks for plaintext ranges. Rust:

1. validates session/reader;
2. resolves affected encrypted chunks;
3. authenticates and decrypts full chunks;
4. copies requested range;
5. reports verified range/EOF;
6. never equates range success with complete-object verification.

Seek latency and buffer sizes are benchmarked under performance budgets.

## 10. Export/transcode/edit

Export may return original, selected derivative, or explicit transcoded output. UI states which. Any plaintext scratch follows `PLAINTEXT_LIFECYCLE.md`.

Editing is future scope. A non-destructive edit should store encrypted edit instructions/derived revision rather than mutate original container.

## 11. Color and orientation

Derivative generation must define:

- orientation normalization;
- color-space conversion;
- HDR-to-SDR behavior;
- alpha handling;
- metadata retention/removal;
- deterministic generator profile where interoperability matters.

Pixel-identical cross-platform thumbnails may be impractical; cryptographic binding and declared generator profile are required even when output differs.

## 12. Limits

- still image at most 16384 px in either dimension and at most 67108864 px in total; a source above either bound is rejected before decode;
- video at most 7680 by 4320 px per track, at most 8 tracks;
- video or audio duration at most 14400000 ms (4 hours);
- metadata revision at most 128 fields, each field value at most 8192 bytes, whole revision at most 65536 bytes;
- derivative long-edge targets: small thumbnail 320 px, grid preview 640 px, screen preview 2048 px, video poster frame 2048 px. The audio waveform has no long edge, because §6.1 makes it a data record rather than a picture; it is bounded instead at 4096 buckets and 4104 bytes for the whole record;
- derivative codec: baseline JPEG with 4:2:0 chroma, quality 80 for the small thumbnail, 82 for the grid preview, and 85 for the screen preview and poster frame. JPEG is the v1 derivative codec because Android and iOS both encode and decode it without an added native dependency; a different codec takes a new generator profile under §11 rather than a silent change;
- decode and import buffers at most 268435456 bytes (256 MiB) in flight per import;
- one derivative generation is cancelled after 30 seconds of wall-clock work, and cancellation reports `CANCELLED`, never corruption;
- no recursive archive/container expansion without separate parser policy.

## 13. Failure behavior

Distinguish:

```text
unsupported codec
provider/network unavailable
permission denied
malformed source
resource limit exceeded
cancelled
storage full
cryptographic/container corruption
```

A codec failure must not commit a catalog entry claiming required derivatives exist; policy may permit original-only import with explicit status.

## 14. Tests

- JPEG/PNG/HEIF/RAW and orientation/color cases;
- MP4/MOV/HDR/long video random seek;
- audio formats and long recordings;
- cloud-backed unknown-length sources;
- malformed metadata and extreme dimensions/duration;
- compound asset partial failure;
- cancellation/lock at every stage;
- storage full and process death;
- derivative revision invalidation;
- no plaintext cache/scratch leakage.
