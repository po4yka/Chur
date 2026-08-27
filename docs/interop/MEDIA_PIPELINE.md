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
face/embedding records (future)
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
- derivative long-edge targets: small thumbnail 320 px, grid preview 640 px, screen preview 2048 px, video poster frame 2048 px;
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
