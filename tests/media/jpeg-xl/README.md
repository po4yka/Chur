# JPEG XL test images

These files contain generated pixels and no user or camera data. The generated
image data is public domain under CC0 1.0. These are standalone JPEG XL files,
not JPEG XL compressed ProRAW DNG files.

| File | Upright pixels | Content |
| --- | --- | --- |
| still-codestream.jxl | 96 × 48 | Lossless codestream. Red left half; RGB 0/128/255 right half. |
| still-container.jxl | 96 × 48 | The same pixels in a standard JPEG XL container. |
| alpha.jxl | 32 × 16 | Transparent red, half-transparent green, opaque blue. |
| associated-alpha.jxl | 16 × 8 | Associated red 128/0/0 with alpha 128. Straight output is 255/0/0/128. |
| profiled.jxl | 16 × 8 | Red with a synthetic LittleCMS sRGB ICC profile. |
| large.jxl | 2050 × 1025 | RGB 32/128/224. Requires all three JPEG derivatives. |
| oriented.jxl | 96 × 48 | Stored 48 × 96 with orientation 6. |
| animated.jxl | 48 × 24 | Full red, green, blue frames; delays 80/120/160 ms; infinite loop. |
| finite-animation.jxl | 48 × 24 | The same frames and delays; exactly two loops. |
| oversized.jxl | 16385 × 1 | Valid image beyond the source edge limit. |
| icc-limit.jxl | 16 × 8 | Valid image with decoded ICC size 1 MiB + 64 bytes and encoded ICC below 1 MiB. Must fail before ICC allocation. |
| truncated.jxl | — | First 20 bytes of still-container.jxl. |
| malformed.jxl | — | Codestream signature followed by eight zero bytes. |

`manifest.json` records sizes and SHA-256 hashes. The tests exercise actual
imports, derived JPEGs, viewing, original export and peer sync. Invalid files
verify failure behavior and do not establish decoding support.

To regenerate, install Pillow with LittleCMS support, libjxl tools and headers,
a C compiler and pkg-config, then run `python3 generate-fixtures.py`. Committed
files use libjxl 0.12.0. The C generators set ICC, associated alpha and finite
loops through the reference encoder API. Encoder versions and ICC timestamps
can change encoded bytes; review and update the manifest after regeneration.
