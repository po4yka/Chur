# Audio artwork

> **Status:** Proposed product and platform contract

## Import and storage

Media import reads embedded artwork with the platform audio metadata decoder. MP3 and M4A are the baseline tested containers. Other containers depend on the platform decoder. Chur does not fetch covers from the network or search adjacent files.

The audio original remains byte-for-byte unchanged. Its canonical pixel dimensions remain zero. The waveform is generated first. Artwork is optional and uses the existing thumbnail, grid preview, and screen preview streams, with their own image dimensions and source content revision. No new catalog format or stream kind is required. A missing, unsupported, corrupt, or oversized cover must not make the audio import fail.

The codec checks the encoded artwork limit of 8 MiB immediately after the native metadata API returns it. These APIs can allocate the metadata value before Chur can inspect its size. This limit bounds subsequent copies and decoding, not the internal allocation made by the framework. Image headers must pass the existing still-image limits before pixel decoding. The codec downsamples to the target long edge of 320, 640, or 2048 pixels, applies orientation, and writes a new JPEG. It does not persist raw artwork tags or image metadata.

A recoverable storage or resource-limit failure while saving optional artwork reports the original as imported, with a warning that artwork could not be saved. Remaining artwork work stops. Session and integrity failures retain their existing error handling.

Cancellation stops further derivation. Encoded artwork and temporary derivative buffers are released or cleared after use; decoded images remain subject to the session image cache rules. Derivatives use the existing encrypted object storage and backup paths. No plaintext cover file or persistent disk image cache is created.

## Display and privacy

The library grid, list, and album contents display the stored thumbnail. Hidden thumbnails apply the same mosaic rendition to audio artwork as to photos and video posters. Missing artwork uses neutral audio artwork.

The audio viewer reads screen preview, grid preview, then thumbnail. It must not pass the audio original to an image decoder. The cover remains visible during audio playback, with the waveform and playback controls available. Video rendering keeps its existing poster behavior.

Decoded covers are scoped to the unlocked session and cleared on lock. The feature does not publish covers through a system media session, lock-screen metadata, notifications, or public Notes.

## Existing objects and checks

An audio object that already has a valid picture derivative can display it. This import change does not regenerate missing covers for audio already stored in the vault. Those objects retain neutral artwork and their existing waveform; a new media import can extract an embedded cover. Explicit Documents and files import remains opaque.

Checks cover real synthetic audio containers with and without artwork, corrupt image bytes, image limits, orientation, cancellation, encrypted derivative round trips, original preservation, viewer fallback, concealment, and audio playback presentation. Physical-device and file-provider acceptance remain separate from local builds and simulator tests.
