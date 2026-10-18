# Live Photo bundle v1

This content format stores all resources of one Live Photo in one immutable
Original stream. It does not change the encrypted container, catalog, or sync
protocol. Its MIME type is `application/vnd.chur.live-photo`; its extension is
`.churlive`. Resource bytes are not transcoded, compressed, or normalized. Audio
inside the paired movie remains in that movie.

## Encoding

All integers are big-endian. The fixed header has 24 bytes:

| Offset | Size | Field |
| --- | --- | --- |
| 0 | 8 | ASCII `CHURLIVE` |
| 8 | 2 | Version, exactly 1 |
| 10 | 2 | Resource count, 2 through 32 |
| 12 | 4 | Complete header length, including this fixed header |
| 16 | 8 | Signed capture time in epoch milliseconds; -1 means absent |

The resource table follows. Each entry has these fields, in this order:

| Size | Field |
| --- | --- |
| 4 | Positive Photos resource type raw value, at most 2147483647 |
| 8 | Unsigned resource byte length |
| 32 | SHA-256 of the exact resource bytes |
| 2 | UTF-8 filename byte length, 1 through 1024 |
| 2 | UTF-8 UTI byte length, 1 through 255 |
| 2 | ASCII MIME byte length, 3 through 63 |
| variable | Filename bytes, then UTI bytes, then MIME bytes |

Strings have no terminator. UTF-8 must be valid and contain no NUL. MIME uses
the existing normalized file MIME grammar. Names are retained as metadata;
they are never used as paths without the existing safe export-name policy.
The header has no padding or extra fields and is at most 65536 bytes.
Capture time must be nonnegative or exactly -1.

Resource payloads follow the table in the same order with no gaps. The first
payload starts at the complete header length. Every later offset is the checked
sum of the preceding offset and length. The complete file length must equal the
header length plus all resource lengths. Trailing bytes, truncation, integer
overflow, an unknown version, or a checksum mismatch are errors. The total
bundle is at most 2147483648 bytes. This is an I/O limit, not a decode limit;
existing bounded image/video decode rules still apply independently.

## Resource meaning

Resource type values are the public `PHAssetResourceType` raw values. Exactly
one original photo (1) and one original paired video (9) are required; both
must be nonempty. All other resources, including adjustment data, alternate
photos, audio, and unknown future positive roles, are retained in order with
their exact names, UTI, length, digest, and bytes. Other roles may repeat.
The display pair uses full-size photo (5) and full-size paired video (10) when
both are present exactly once; otherwise it uses the original pair. Display
selection never removes resources from storage or export.

## Capture, storage, and restoration

Full capture requires access to the selected `PHAsset` and enumeration of all
its resources. A provider's display-only `PHLivePhoto` is not proof of complete
capture. Resource errors, denied access, a changed asset inventory, resource
limits, or cancellation must fail the full import; they must not silently save
only the still image. Capture uses bounded sequential writes and protected,
backup-excluded scratch with deterministic cleanup. An incomplete file is
never offered to the importer.

Import validates the full table and all resource hashes. The original package
is committed as one object, so cancellation or a missing resource cannot
activate a partial Live Photo. Previews use the selected photo resource.
Video players use only the selected paired-video byte range and retain its
audio tracks. Generic original export, backup, selective copy, and sync retain
the complete package bytes.

Files restoration exports every resource plus the complete portable bundle.
Safe output names can differ from the recorded original names; the bundle
retains the original names and roles. Partial output files are removed on
failure. iOS Photos restoration supplies all resources to one creation request
with their original roles, names, and UTIs, after checking the supported role
combination. An unsupported combination or rejected request is an explicit
failure; resources must not be omitted to make creation succeed. Android
supports the portable bundle, exact resource export, and paired video/audio
playback; it does not claim to create an Apple Photos database asset.

## Verification

Use the same binary golden header across Swift, Kotlin, and Rust. Exercise
missing resources, digests, invalid strings/counts/lengths, trailing bytes,
unknown roles, cancellation, lock, and failed output cleanup. Compare resource
and complete package bytes after encrypted import/export and backup. Native
iOS tests must create a matched photo/movie pair with audio, request a
`PHLivePhoto`, save it as one Photos asset, and compare the resources read back.
Android tests must decode the photo and play the bounded movie with audio,
then compare every exported resource. Real captured, edited, HDR, and
cloud-backed Live Photos remain separate acceptance cases unless exercised.
