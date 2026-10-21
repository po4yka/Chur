# Synthetic HEVC Main10 MP4 fixtures

These six files contain procedural moving color patterns and a 440 Hz AAC tone.
They contain no user media. Each video is HEVC Main 10, 10-bit 4:2:0, 192 × 96,
24 fps, 30 frames and 1.25 seconds. Key frames occur every 12 frames for seek tests.
The top 32 rows contain six neutral gray patches, each 32 pixels wide. Their
10-bit limited-range luma values are 64, 240, 416, 592, 768 and 940; both chroma
values are 512. Sample their centers at `(16 + 32 * index, 16)`. The first patch
is black and the last is white. The lower 64 rows contain a moving color pattern.
Lossy encoding and platform conversion permit small pixel differences. Poster
tests can require a dark first patch, a much lighter last patch, neutral gray
channels and ordered brightness. HDR tone mapping can compress adjacent steps.

| Prefix | Color primaries | Transfer function | Matrix |
| --- | --- | --- | --- |
| `sdr10` | BT.709 | BT.709 | BT.709 |
| `hdr10` | BT.2020 | PQ / SMPTE ST 2084 | BT.2020 non-constant |
| `hlg` | BT.2020 | HLG / ARIB STD-B67 | BT.2020 non-constant |

Each prefix has an `-hvc1.mp4` file and an `-hev1.mp4` file. FFmpeg remuxes the
same coded samples with the two sample-entry tags. This tests tag handling; it
does not test in-band parameter changes. HDR10 includes mastering display
metadata (1000 cd/m² maximum, 0.005 cd/m² minimum) and content light metadata
(MaxCLL 1000, MaxFALL 400). These are synthetic signaled values, not measurements.

The patterns exercise native decode, metadata, poster generation, encrypted
playback, seek and exact-byte export. They are not calibrated HDR charts and do
not establish luminance, tone mapping, gamut accuracy, display output, camera
compatibility, long-file behavior or hardware decoder support. The generator
uses synthetic 10-bit encoded signal values and sets each transfer function;
it does not convert a scene-linear source to measured PQ or HLG luminance.
Main10 SDR and HDR are separate cases. A simulator result does not establish
device acceptance.

Regenerate from the repository root:

```sh
python3 tests/media/hevc-main10/generate.py
```

The script requires Python 3 and FFmpeg with libx265 and AAC. It uses one encoding
thread, fixed source parameters, fixed key-frame spacing, no copied metadata and
bounded commands. It stages all six outputs in a temporary directory, probes
their actual stream formats and HDR10 side data, and decodes every file before
it replaces the fixtures. It writes file lengths, SHA-256 hashes, tool version
and ffprobe metadata to `manifest.json`. Bytes can change with the encoder or
muxer version; the checked-in files and hashes are the test inputs.

Android already exposes `tests/media` as instrumentation assets. Use asset paths
such as `hevc-main10/sdr10-hvc1.mp4`; no duplicate fixture copy is required.
