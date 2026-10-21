# Native HDR output acceptance, 2026-10-21

## Android path

The production video `PlayerView` now declares `surface_type="surface_view"`
in its own layout. The audio layout still creates no video surface. This keeps
the HDR output choice explicit when the UI changes. No global color mode,
display brightness or decoder override is set.

`VideoHdrOutputAndroidTest` plays the six existing Main10 inputs through the
actual vault-backed app player. It checks coded 10-bit metadata, a displayed
and valid `SurfaceView`, rendered video buffers, and the color transfer,
standard and range from the **MediaCodec output** `MediaFormat`. It reads the
HDR capabilities of the display attached to that surface before and after each
case. SDR controls are tested between HDR cases.

The [device report](evidence/video-acceptance-2026-10-21/android-hdr.json) records
a successful Pixel 8 Pro API 37 run. Both `hvc1` and `hev1` reached these paths:

| Input | Decoder output | App SurfaceFlinger output layer |
| --- | --- | --- |
| SDR Main10 | BT.709, SDR transfer, limited range | Native format control; no HDR dataspace assertion |
| HDR10 | BT.2020, PQ transfer, limited range | `BT2020_ITU_PQ` (298188800) |
| HLG | BT.2020, HLG transfer, limited range | `BT2020_ITU_HLG` (302383104) |

This is stronger than a codec capability query or an advancing audio clock:
the observed app video layer carries the expected HDR dataspace. Its vendor
buffer format is opaque to this test; source Main10 metadata alone does not
measure the physical buffer's precision. Display ratio values are capability
observations and are not luminance measurements.

## Reproduce on an HDR-capable Android device

Build and install the application and test APKs as described in the
[extended video report](VIDEO_ACCEPTANCE_2026-10-21.md), then run:

```sh
python3 scripts/test-android-hdr-output.py --serial DEVICE
```

The script runs exactly one instrumentation test. It records all six decoder
output formats and requires matching app video layer dataspaces for the four
HDR cases. A bounded wait allows SurfaceFlinger to compose the first frame
after the decoder callback. The script does not store other apps' layers,
device identifiers, raw dumps or pixel content. Failure to observe any required
output fails the check. JSON and instrumentation text go to
`build/reports/video-acceptance/android-hdr` by default.

Google documents the decoder/display requirements and the reason to prefer
`SurfaceView` for HDR in [HDR video playback](https://developer.android.com/media/grow/hdr-playback)
and [Media3 surface types](https://developer.android.com/media/media3/ui/surface).

## iOS path and limits

`IosHdrOutputTest` reads PQ, HLG and the public camera Dolby Vision input through
the encrypted resource loader. It requests a native 10-bit bi-planar video
output and checks the decoded pixel format, BT.2020 primaries and PQ/HLG transfer
attachments. It also attaches the player to an `AVPlayerLayer` in an offscreen
UIView and records the layer's first-frame readiness, native HDR modes and
`eligibleForHDRPlayback`. External playback remains disabled.

These are native buffer/layer checks on iOS Simulator. No physical iPhone was
available. They do not establish an iPhone's screen output. The Dolby Vision
test verifies the HLG base layer, not application of its RPU metadata. The Pixel
run also does not claim Dolby Vision RPU rendering. Neither platform result
measures luminance, gamut, tone-map fidelity or sustained thermal performance.
Those require a physical device and suitable measurement equipment.
