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

### 3.1 Documents and arbitrary files

The separate **Documents and files** action accepts all file types through the platform document picker. It marks the source for opaque import before any media probe or derivative generation. A filename extension or MIME hint never forces media decoding on this path. Unknown or invalid MIME hints become `application/octet-stream`; the normalized hint has at most 63 ASCII bytes. Original bytes, including a zero-byte file, are preserved. Unknown-length and non-seekable sources use bounded sequential reads. The container's 1 TiB total bound applies; a provider can impose a lower bound.

The iOS adapter holds a coordinated, security-scoped file descriptor until native import releases its duplicate. Android uses the provider descriptor and optional name/length metadata. Neither path creates a plaintext copy to make the source seekable. Picker references are transient and bound to the unlocked session.

Opaque originals use the existing `MediaClass.Opaque` value. They need no image derivative, dimensions, duration, new catalog schema, or format migration. The library's **Files** filter, albums, tags, favorites, trash, search by filename/metadata, and single or album export use the same object operations as media. Grid projections show a file glyph without private filenames. Names are read only for the open viewer/details and existing private search.

The viewer renders PDF pages and literal UTF-8 text inside the vault. PDF navigation is explicit; text scrolls. Preview bounds and plaintext lifetimes are specified in [`../security/PLAINTEXT_LIFECYCLE.md`](../security/PLAINTEXT_LIFECYCLE.md) §4. Other types, such as Office documents, archives, and executables, show metadata and explicit export. Preview failure never changes or truncates the original. No archive extraction, embedded code execution, network fetch, or automatic external viewer is part of this path. See [`../adr/0060-bounded-document-previews.md`](../adr/0060-bounded-document-previews.md).

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

A reported source length remains a hint: extra bytes are read and the authenticated final commit records the actual length. A stream that ends before its reported length is refused as `IO_FAILURE` and its unfinished transaction is abandoned. This prevents a cancelled or failed incoming producer from activating a truncated prefix. Unknown-length sources remain supported. Cancellation is checked after source reads, before commit, and during final verification.

Capture time comes from the provider when it publishes one; on Android that is the `DATE_TAKEN` column of the photo picker and the media store. Ordinary iOS photo import does not request Photos access for the asset's date ([`../IOS.md`](../IOS.md) §15.1). The separate **Full Live Photos** action requests Photos access and can use the selected asset's creation date. When the provider publishes no date, the platform reads the file, on both platforms: a photo's EXIF `DateTimeOriginal` with its `OffsetTimeOriginal`, and a video container's creation date, which names an instant and needs no time zone. An EXIF time with no offset is read in the device's time zone at import. A time that does not parse, or that falls before 1970, is absent, and the catalog substitutes the import time under [`CATALOG_SCHEMA_V1.md`](../format/CATALOG_SCHEMA_V1.md) §8.1.

## 5. Originals

Original bytes are preserved whenever feasible. Transforming import requires an explicit user/product policy because transcoding may lose metadata/quality and creates a new original content revision.

## 6. Derived assets

An original committed before a derivative failure remains imported. If Android cannot confirm that its decoder child stopped, retain that committed object ID, stop the batch before opening another source, and count the remaining inputs as skipped. Saved objects still receive their selected album membership. The notice asks the user to restart Chur before another media import; it does not report the saved originals as failed or bypass the decoder's retained ownership.

The iOS video poster path releases every owned `CGImage` returned by a Copy API after the `UIImage` has retained it, including failure paths. The image remains usable for bounded derivative rendering; no extra Core Foundation ownership survives the extraction.

Implemented picture/waveform kinds and reserved later kinds:

```text
small thumbnail
grid preview
screen preview
video poster frame
animated preview (future)
audio waveform
OCR text (current storage is catalog v10, not a derived asset stream)
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

### 6.2 Embedded audio artwork

Audio keeps canonical source dimensions of zero. After its waveform, import attempts optional thumbnail, grid preview, and screen preview derivatives from embedded artwork. Each picture carries its own dimensions and the existing source revision binding. No picture stream is claimed when artwork is absent or refused. The platform checks an 8 MiB encoded artwork limit after native metadata extraction, validates image bounds before pixel decoding, downsamples, normalizes orientation, and encodes the existing JPEG profile. Native metadata extraction can allocate before the size check. See [`../product/AUDIO_ARTWORK.md`](../product/AUDIO_ARTWORK.md) for display, privacy, and existing-object behavior.

## 7. Compound media

Full Live Photos use one immutable Original with MIME `application/vnd.chur.live-photo` and extension `.churlive`. The format is defined in [`LIVE_PHOTO_BUNDLE_V1.md`](../format/LIVE_PHOTO_BUNDLE_V1.md). It stores every Photos resource in order, including original and edited photo/movie resources, adjustments, and unknown positive resource roles. Each resource retains its exact bytes, name, UTI, MIME, length, and SHA-256. Audio stays inside its paired movie. This does not add a stream kind, catalog schema, or sync protocol.

The iOS **Full Live Photos** action is opt-in. It needs Photos access to enumerate all resources of the selected `PHAsset`; a provider's display-only `PHLivePhoto` is not a complete capture. Capture writes bounded sequential data to protected, backup-excluded scratch and removes incomplete output. Denied access, a resource failure, a changed inventory, a limit, cancellation, or lock fails the full import. It must not save only the still image. Rust validates the full table, exact package length, and all resource hashes before the final commit and catalog activation.

The package requires one nonempty original photo and one nonempty original paired video. Display uses the full-size photo/movie pair when both occur exactly once; otherwise it uses the original pair. Display selection does not remove other resources. Spatial media, separate RAW+JPEG pairs, sidecar metadata, and other compound formats need their own complete representation when supported. Missing parts produce an explicit incomplete or unsupported result.

## 8. Image display

Timeline uses encrypted thumbnail/preview assets. Full-resolution original is decrypted only for detailed viewing/export. Decoder buffers are session-scoped; disk plaintext cache is prohibited.

Import makes no screen preview for a photograph whose long edge is at or below the 2048 px target of §12, so the viewer shows its original. A platform image decoder needs the whole encoded file in memory, so the viewer reads such an original, only when its long edge is 2048 px or less and its size is 32 MiB or less, with `read_at` into one caller-owned native buffer of that size ([`FFI_CONTRACT.md`](FFI_CONTRACT.md) §7). The decoder reads that buffer in place, and the viewer clears it after the decode. The original never becomes a Kotlin `ByteArray` or a Swift `Data` ([`FFI_CONTRACT.md`](FFI_CONTRACT.md) §6). The viewer never reads the original of a quarantined object.

The settled viewer page can animate native GIF and WebP on Android, and GIF, WebP, and APNG on iOS when the native decoder exposes multiple frames. Animation uses no plaintext file or whole-file managed array. Its encoded source is at most 32 MiB, its source canvas is at most 2048 px per edge, and its rendered long edge is at most 1024 px. The decoder closes before the native source buffer is cleared. Reduced motion starts animation paused. Unsupported or larger animations keep the still preview; the original remains exportable.

A zoom can read an original of at most 32 MiB and decode at most a 4096 px long edge. This gives more detail without caching an unbounded full-resolution bitmap. Quarantined objects never use this path.

For a Live Photo, preview generation and original-image display read only the selected photo resource range. The image bounds apply to that resource, not to a whole-package decode. A `.churlive` package is never passed to an image decoder as JPEG.

## 9. Video/audio playback

Player asks for plaintext ranges. Rust:

1. validates session/reader;
2. resolves affected encrypted chunks;
3. authenticates and decrypts full chunks;
4. copies requested range;
5. reports verified range/EOF;
6. never equates range success with complete-object verification.

Seek latency and buffer sizes are benchmarked under performance budgets.

Live Photo playback exposes only the selected paired-movie byte range to the player. Offsets and EOF are relative to that resource. The original movie retains its audio tracks; the player must not read another resource or the package header as movie data.

## 10. Export/transcode/edit

Export may return original, selected derivative, or explicit transcoded output. UI states which. Any plaintext scratch follows `PLAINTEXT_LIFECYCLE.md`.

Generic original export, backup, selective copy, and sync preserve the complete Live Photo package bytes. Files restoration checks every resource hash before it creates output, then writes every resource and the complete portable `.churlive` bundle. Safe output names may differ from recorded names; the bundle retains the original metadata. Cancellation, lock, or a later output failure rolls back all files created by that restoration, including earlier published resources, without removing unrelated files.

iOS Photos restoration checks that Photos supports the complete role combination and submits every resource in one asset-creation request with its recorded role, name, and UTI. An unsupported combination or rejected request fails explicitly; it must not omit resources to save a partial asset. Android supports the portable package, exact resource export, and paired movie/audio playback. It does not create an Apple Photos database asset.

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

Video thumbnails and posters use an 8-bit SDR sRGB bitmap before JPEG encoding.
Android requests `ARGB_8888` from `MediaMetadataRetriever`, uses the platform's
HDR frame conversion, and draws into an sRGB bitmap. iOS sets
`AVAssetImageGenerator.dynamicRangePolicy` to `ForceSDR`, then draws into an
8-bit sRGB Core Graphics context. If that generator reports `AVErrorDecodeFailed`,
iOS retries with `MatchSource`; Core Graphics maps the HDR image into the same
SDR context. The platform owns the HDR tone map; the app
does not relabel PQ or HLG samples as sRGB. A failed frame conversion leaves
the original intact and reports an incomplete preview under §13. This policy
does not change the original video's codec, bit depth, color metadata, or bytes.
Android 10 has no native HDR poster conversion in this path. It reports an
unavailable preview for a PQ or HLG source and keeps the original for playback
and export. Main10 SDR preview remains available when its decoder is supported.

HEVC Main10 can contain SDR or HDR video. Native decoder and display support
are separate requirements. A successful MP4 metadata probe does not prove that
the device can decode its profile, level, resolution, or frame rate. Android
also reports unsupported video or audio tracks when the other track can play.
iOS loads the asset's `playable` key before readiness and rejects an unplayable
video even when its audio track lets `AVPlayerItem` become ready. In particular,
an unsupported `hev1` sample entry must not appear to play as audio-only video.
Chur does not rewrite sample entries in the original to make a decoder accept it.

## 12. Limits

- still image at most 16384 px in either dimension and at most 67108864 px in total; a source above either bound is rejected before decode;
- video long edge at most 7680 px and short edge at most 4320 px per track, in either orientation, at most 8 tracks;
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

A codec failure must not commit a catalog entry claiming required derivatives exist. If a media probe has no decoder, import preserves the original as an opaque file and reports that the device cannot preview or play it. A required thumbnail, poster, or waveform failure reports an incomplete preview. Missing optional audio artwork is not an error. Explicit file import keeps its existing opaque behavior.

The iOS audio picker uses a coordinated security-scoped URL and descriptor until import closes the source. It does not copy the file to plaintext scratch. System type detection replaces an extension allowlist; a detected container still requires a platform decoder for playback. Playback errors must be visible.

A regular Photos selection may contain a Live Photo, a RAW/JPEG pair, or edited resources. If that path keeps one selected representation, it warns the user to retain the Photos original. The separate **Full Live Photos** path preserves all resources under §7 and fails if full capture is unavailable. Complete capture does not authorize source deletion. Source deletion is offered only for an eligible loaded index. The native deletion callback checks current asset resources and their type again; a Live Photo, other compound asset, edited asset, unknown type, or mismatched asset is never deleted.

## 14. Tests

- JPEG/PNG/HEIF/RAW and orientation/color cases;
- MP4/MOV/HDR/long video random seek;
- audio formats and long recordings;
- cloud-backed unknown-length sources;
- malformed metadata and extreme dimensions/duration;
- compound asset partial failure;
- shared Live Photo format vectors, malformed tables/hashes, and exact package/resource import, export, and backup round trips;
- native matched Live Photo photo/movie capture and Photos restoration with audio; real captured, edited, HDR, and cloud-backed assets remain separate acceptance cases;
- cancellation/lock at every stage;
- storage full and process death;
- derivative revision invalidation;
- no plaintext cache/scratch leakage.

[`tests/media/hevc-main10`](../../tests/media/hevc-main10/README.md) contains
real 10-bit HEVC MP4 fixtures for SDR, PQ, and HLG with `hvc1` and `hev1` sample
entries. The iOS `hvc1` tests require native poster generation, encrypted playback
with a decoded video frame, seeking, and byte-identical original export. The
`hev1` cases verify native rejection, an unsupported result and user message, intact
original export, and reader invalidation after lock.
Android requires these results when its decoder reports support. If it has no
decoder, the tests require an explicit unsupported result and intact export;
Android 10 HDR preview follows the unavailable-preview policy in §11. A decoder
that reports support must decode the fixture. No decoder case is skipped.
The fixtures also exercise SDR sRGB preview output and cancellation or reader invalidation.
The Android CI emulator runs the Main10 instrumentation class in addition to
the notes and lock tests. The iOS simulator suite reads the same fixture files.
These tests do not establish physical-device HDR display output, performance
at 4K, or calibrated tone-map fidelity. Those remain device acceptance checks.

## 15. JPEG XL

Android and iOS use the same bounded Rust decoder for standalone `.jxl` images.
It accepts raw codestreams and standard containers. Signatures confirm the format
when a provider gives a generic or incorrect MIME type. The canonical type is
`image/jxl`. **Media files** opens an unrestricted file picker and imports
selected items through the media pipeline. This makes standalone JXL available
when the system photo picker does not list it. Explicit **Documents and files**
selection keeps its opaque-file contract. Photos/media selection and incoming
media shares use image import. Generic incoming shares with a JPEG XL signature
also use the media path.
Unsupported media shares retain the fallback warning; explicit file imports do
not receive that warning.

Import records upright dimensions, keeps the exact original and creates the
existing baseline JPEG derivatives. Transparent derivatives have a white opaque
background. The original viewer retains alpha, including associated-alpha input.
Rendering converts supported embedded ICC profiles to 8-bit sRGB. This is SDR
presentation, not native HDR output. Export does not decode or transcode the
original, so source bit depth, color metadata and animation bytes remain intact.
JPEG XL compressed ProRAW DNG is a separate RAW format; standalone JXL acceptance
does not establish support for that RAW compression.

JPEG XL animations use the settled-page viewer, reduced-motion policy,
play/pause controls and lock disposal. Display respects frame durations and loop
counts; zero loops means infinite playback. A finished finite animation can be
played again. The player retains one displayed raster. Animation sources remain
limited to 2048 pixels per edge and rendering to 1024 pixels. The general
animation byte and frame limits also apply.

Peer sync still transfers only the authenticated original. When an image has no
local derivatives, the grid can decode a transient thumbnail inside the session
cache and the viewer can decode a bounded screen still. Plain and mosaic cache
entries remain separate. In-flight results are discarded after lock invalidation.
This creates no plaintext file or catalog stream. Reads remain limited to 32 MiB;
catalog image bounds must be valid and quarantined objects are never decoded.
Full backups retain existing derivatives.

The decoder limits encoded input to 64 MiB, source geometry to 16384 pixels per
edge and 67108864 pixels total, tracked grids to 256 MiB, header prefix to 2 MiB,
and encoded and decoded ICC profiles to 1 MiB each. Dimensions and ICC
declarations are checked before upstream initialization. Only codestream events
reach the decoder; unused Exif, XML and Brotli metadata is not decompressed.
Output is at most 4096 pixels per edge, with bounded nearest-sample scaling.
Failures return status codes. Upstream grids are released on close but cannot
be guaranteed to be zeroized; controlled source, row and output buffers are
cleared. This limit is recorded in the FFI contract and dependency policy.

`tests/media/jpeg-xl` contains real synthetic codestream/container, orientation,
alpha, associated-alpha, ICC, finite/infinite animation and boundary fixtures.
Native and platform tests cover import, JPEG derivatives, displayed pixels,
original export, original-only sync presentation and session invalidation.
The peer protocol test pairs real vaults, exchanges signed operations and grants,
activates JXL originals and rejects modified ciphertext before export.
