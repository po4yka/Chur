# Synthetic AVC viewing-copy reference

`reference-sdr.mp4` is a software conversion of the repository fixture
`../hevc-main10/sdr10-hvc1.mp4`. The source contains procedural moving pixels,
six neutral gray bars and a 440 Hz tone. It contains no user or third-party media.

The reference has H.264 Main, 8-bit 4:2:0, limited-range BT.709 SDR, an `avc1`
sample entry, 192 × 96 pixels, 24 fps, 1.25 seconds and AAC LC mono audio at
48 kHz. It retains the gray bar positions documented in the source directory.
It provides valid AVC/AAC bytes for storage and UI tests. It is not evidence
that Android or iOS performed a native transcode or HDR tone map. Native tests
must generate and inspect their own viewing copies from the encrypted originals.

Regenerate from the repository root:

```sh
python3 tests/media/video-compatibility/generate.py
```

The script requires Python 3 and FFmpeg with libx264 and AAC. It uses the fixed
source, one encoder thread, CRF 24, H.264 Main, explicit BT.709 tags and AAC at
64 kbit/s. It probes the actual output and fully decodes both streams before it
replaces the fixture. `manifest.json` records source and output SHA-256 hashes,
length, tool version and stream metadata. Identical tool versions produce the
same bytes; codec or muxer version changes can change the output.
