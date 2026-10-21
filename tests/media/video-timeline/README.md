# Synthetic video timeline reference

`reference-storyboard.jpg` contains twelve actual decoded frames from
`../video-acceptance/main10-long-gop-vfr.mp4`. The source is the repository's
procedural 64-second Main10 SDR moving pattern with variable frame intervals
and long GOPs. It contains no user or third-party media.

The JPEG has four columns and three rows of 160 × 90 cells, in time order,
for a total of 640 × 270 pixels. Slot `i` uses the source time
`64 * (i + 0.5) / 12` seconds. Each decoded frame fits within its black cell
without a change to its aspect ratio. The file is smaller than 2 MiB.

This reference supplies valid bytes for encrypted timeline storage and UI
checks. It does not prove native Android or iOS frame extraction. Those tests
must generate an atlas from their own encrypted source and check its pixels.

Regenerate from the repository root with Python 3 and FFmpeg:

```sh
python3 tests/media/video-timeline/generate.py
```

The script performs accurate input seeking and decodes one actual frame for
each slot. It scales and pads each frame, tiles the twelve images with FFmpeg,
then probes and decodes the JPEG before replacement. The manifest records the
source and output SHA-256 hashes, slot times, intermediate decoded PNG hashes,
output dimensions and tool version. The PNG hashes must differ across slots.
Identical tool versions produce the same bytes; version changes can alter them.
