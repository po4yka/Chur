# Playback diagnostic inputs

`hevc-dts.mp4` contains the unchanged procedural Main10 video from
`../hevc-main10/sdr10-hvc1.mp4` and a real DTS core audio stream encoded
from a 440 Hz sine. FFmpeg fully decodes both streams. The input is valid media.
DTS is identified from the encoded stream; the installed FFmpeg MP4 muxer uses
an `mp4a` sample entry with a DTS object type, not a forced `dtsc` tag. The manifest
records the actual sample entry. DTS decoder availability depends on the device;
tests must inspect actual track
support and report either playback or an unsupported-audio diagnostic. They must
not declare this codec universally unsupported.

`truncated-container.mp4` is an exact prefix of the same real source MP4. It ends
before the required `moov` atom. ffprobe rejects it. This represents invalid
original media inside an otherwise correctly authenticated vault object. It is
not an AEAD failure and must not produce an authenticated-integrity warning.

Regenerate from the repository root:

```sh
python3 tests/media/video-diagnostics/generate.py
```

The script requires Python 3 and FFmpeg with the `dca` DTS core encoder. FFmpeg
marks that encoder experimental, so the generation command explicitly enables
it. No application dependency is added. The script stages outputs in a temporary
directory, probes the real codec tags, decodes the valid input completely,
requires rejection of the malformed input, and records lengths, hashes, tool
version and provenance in `manifest.json`. The byte inputs are synthetic test
media, not user files. They contain no person, location or private identifier.
