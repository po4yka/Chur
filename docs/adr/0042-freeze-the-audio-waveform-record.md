# ADR-0042: Freeze the Audio Waveform as a Peak-Envelope Record

- **Status:** Accepted
- **Date:** 2026-08-28
- **Decision owners:** @po4yka
- **Related:** [`../interop/MEDIA_PIPELINE.md`](../interop/MEDIA_PIPELINE.md), [`../format/CANONICAL_ENCODING_V1.md`](../format/CANONICAL_ENCODING_V1.md), [`0016`](0016-freeze-the-v1-c-abi.md)

## Context

`MEDIA_PIPELINE.md` §6 lists an audio waveform among the derived asset kinds and `CANONICAL_ENCODING_V1.md` §15.4 allocates it `stream_kind` `0x06`. Neither says what its bytes are. §12 gives every other listed kind a long-edge target and a JPEG quality and gives the waveform neither, so it is not a picture; nothing else in the repository said what it is instead.

That gap blocked Phase 2. A waveform is drawn by the shared Compose surface, so a record Android writes is one iOS reads. Without a fixed format the two hosts would produce different bytes for the same purpose, and a vault restored from an Android backup would carry waveforms an iPhone cannot draw. The kind also had no bound: §12 bounds every pixel kind by its long edge, and a kind with no edge had nothing bounding it at all.

## Decision

`MEDIA_PIPELINE.md` gains §6.1, which fixes the record:

```text
record_version:u8 = 0x01, reserved:u8 = 0x00, bucket_count:u16, duration_ms:u32,
peaks:u8[bucket_count]
```

- a waveform is a peak envelope over equal slices of the recording, one unsigned byte per slice, normalized against the loudest bucket;
- `bucket_count` is 1 to 4096 and `duration_ms` is inside the §12 four-hour bound, so a record is at most 4104 bytes. §12 gains that bound;
- a reader rejects a wrong `record_version`, a non-zero `reserved`, a length that contradicts the count, and any trailing byte;
- the bucket count is a generator choice inside the bound, not a format constant. v1 generators produce 512;
- §11 applies unchanged: two decoders of one recording may produce different peak values, and the declared generator profile is what makes that a known difference. The record that carries them may not differ.

Rust validates the record before it seals one, so a waveform that the shared renderer could not draw is refused at `derived::put` rather than discovered later.

## Alternatives considered

### Render the waveform as a JPEG, like the other derived kinds

Rejected. It would need no new format, and that is its only merit. A picture fixes the width, the colour, and the theme at import time, so a drawing at another width resamples pixels rather than data, a dark-mode surface draws a light-mode image, and a scrubber cannot highlight a position without compositing. §6 also already groups the waveform with the OCR, face, and embedding records rather than with the four pictures, and §12 already declines to give it a long edge; encoding it as a picture would contradict both.

### Leave the bytes to each platform and bound them by size alone

Rejected. It is the shape the code had before this decision, and it is not a format: two hosts would produce records only their own renderer reads, and the failure would appear at restore rather than at import. §11 permits derivative *output* to differ across platforms; it does not permit the record to.

### Store raw PCM or a downsampled signal

Rejected. Either scales with the recording. A four-hour import would carry megabytes of derived data for a drawing that shows a few hundred columns, and §12's bound on import buffers exists to stop exactly that.

### Make the bucket count a format constant

Rejected. A fixed count makes every record the same size, which is a small simplification, and costs the ability to spend fewer bytes on a two-second voice memo or more on a long recording. The count is two bytes and the reader validates it against the length, so carrying it costs less than the flexibility it buys.

## Consequences

- both hosts fold samples into buckets in common Kotlin, so the folding is tested without a device; only the platform decoder that supplies samples is untested here, as it is for every other derivative kind;
- the record is a v1 persisted format. A change to its layout is a new `record_version` and a new generator profile under §11, not an edit;
- `stream_kind` `0x06` now has bytes, so `derived::needs` decides a waveform by media class rather than by a pixel edge it does not have. The same change gives the video poster frame the same treatment: a poster is needed for every video, and the previous rule refused one for any video already inside 2048 px;
- nothing in the C ABI changes. The kind was already accepted by `chur_derived_put` and `chur_derived_read`.
