# Live Photo format vectors

`header.bin` and `payload.bin` form one structural encoding vector for
`docs/format/LIVE_PHOTO_BUNDLE_V1.md`. `vector.json` lists its exact bytes and
digests. It covers UTF-8 names, unknown resource role 42, and embedded NUL bytes
in payloads. All bytes were generated locally for this project.

The payload strings test the package grammar. They are not encoded pictures or
movies, and this vector is not evidence of native Live Photo support. Native
Photos and Android decoder tests use a separate matched image/movie fixture
with real media and audio.
