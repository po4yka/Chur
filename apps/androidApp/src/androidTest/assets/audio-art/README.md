# Synthetic audio artwork fixtures

These files contain generated test data. No third-party recording or artwork is used.
The tone is a 440 Hz sine wave at 22,050 samples per second. The picture is a red
96 × 48 JPEG. The standard clips last 0.15 seconds; `playback-art.mp3` lasts 3 seconds.
`large-art.mp3` has a 4096 × 2048 red JPEG. These flat images keep the fixtures small.

The fixtures were generated with FFmpeg. For example:

```sh
ffmpeg -f lavfi -i 'color=c=red:s=96x48:d=0.04' -frames:v 1 -q:v 2 cover.jpg
ffmpeg -f lavfi -i 'sine=frequency=440:duration=0.15:sample_rate=22050' -c:a libmp3lame -b:a 32k -write_xing 0 -id3v2_version 3 -map_metadata -1 no-art.mp3
ffmpeg -f lavfi -i 'sine=frequency=440:duration=0.15:sample_rate=22050' -i cover.jpg -map 0:a -map 1:v -c:a libmp3lame -b:a 32k -c:v copy -write_xing 0 -id3v2_version 3 -metadata:s:v title=Cover -metadata:s:v comment='Cover (front)' -map_metadata -1 with-art.mp3
```

The M4A files use AAC at 32 kbit/s and an attached JPEG (`-disposition:v attached_pic`).
The FLAC files use `-c:a flac` and an attached JPEG. `corrupt-art.*` replaces the
embedded JPEG with the same number of invalid image bytes. The audio track and
container lengths do not change. `oriented-art.mp3` contains the original JPEG
with an EXIF orientation value of 6, so its displayed dimensions are 48 × 96.

The Android tests also build an APIC record over the no-art MP3 to check the
8 MiB encoded-art limit and an image header above the allowed edge. These large
test inputs are created during the test and are not stored here.
