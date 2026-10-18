# Synthetic media format fixtures

The files contain generated pixels and tones, except for the public AndroidX DNG test fixture.

- `still.avif`, `still.png`, and `still.tiff`: a red 96 × 48 image. Pillow wrote
  PNG and LZW TIFF. ImageMagick wrote AVIF with quality 70.
- `animated.gif`, `animated.webp`, and `animated.png`: a red frame and a blue
  frame, each 96 × 48, with a 100 ms delay and an infinite loop. Pillow wrote
  all three formats. The PNG is APNG, with an `acTL` chunk.
- `synthetic.dng`: an uncompressed 96 × 48, 16-bit Bayer image. Pillow wrote
  TIFF with DNG 1.1 tags, an RGGB pattern, a white level of 65535, an identity
  color matrix, and D65 illumination. The camera model is `Synthetic Chur test`.
  ExifTool validation reports no warnings. This is camera RAW data, not a renamed TIFF.
- `camera.dng`: the 600 × 337 AndroidX camera RAW test fixture,
  [`dng_with_exif_with_xmp.dng`](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/exifinterface/exifinterface/src/androidTest/res/raw/dng_with_exif_with_xmp.dng).
  Source: Android Open Source Project, AndroidX ExifInterface test resources.
  License: [Apache 2.0](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt).
  SHA-256: `784ed2b71c5e42192b66dd3c0a238892dfbd2aa82ef6291dd8d53af970b4edd0`.
- `rotated.mp4`: a white circle on a red 192 × 96 H.264 video, 0.4 seconds at 10 fps. FFmpeg
  copied it into MP4 with a 90-degree display matrix. Its display size is 96 × 192.
- `clip.mkv` and `clip.webm`: the same unrotated clip, with H.264 and VP9 respectively.
- `stereo.wav`, `stereo-8bit.wav`, `stereo.flac`, and `stereo.opus`: one second
  of 48 kHz stereo audio. The first half is silent. The second half has a 440 Hz
  left channel at amplitude 24000 and a 660 Hz right channel at amplitude 8000.
  Python's `wave` module wrote 16-bit signed and 8-bit unsigned PCM WAVE files.
  FFmpeg encoded the 16-bit WAVE as FLAC and Ogg Opus.

The tests compare decoded dimensions, display orientation, waveform timing, and
the exact original bytes after encrypted import and export. They also check the
archive path when a device has no native TIFF decoder. Native AVIF decode is
tested on Android 14 and later, where the platform requires that decoder.
