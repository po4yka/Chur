# Procedural native Live Photo fixture

These media files contain a generated color image, moving test pattern and 440 Hz
tone. They contain no user media. The JPEG and MOV share a content identifier.
The MOV has H.264 video, AAC audio and a timed `still-image-time` metadata track.
`native.churlive` packages the exact two resources using Live Photo bundle v1.
The native iOS tests use `PHLivePhoto` and PhotoKit to verify this association and
a resource-preserving save/readback cycle. The fixture does not represent a real
camera capture, edit history, HDR or iCloud download.

Rebuild on macOS with FFmpeg and the installed Apple Swift SDK:

```sh
ffmpeg -hide_banner -loglevel error \
  -f lavfi -i 'testsrc2=size=64x64:rate=15:duration=2' \
  -f lavfi -i 'sine=frequency=440:sample_rate=44100:duration=2' \
  -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest -y /tmp/chur-live-source.mov
swift tests/media/live-photo-native/generate.swift \
  /tmp/chur-live-source.mov tests/media/live-photo-native
```

The generator uses passthrough sample writing for the movie resources. Encoding
may vary by installed tool versions; the checked-in fixture and hashes are the
validation input, not a claim of reproducible codec output.
