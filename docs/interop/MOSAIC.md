# Mosaic effect and concealed gallery thumbnails

> **Status:** Accepted

## Source and adaptation

The universal effect comes from the user-owned local Pixelate source snapshot:

- Source: `/Users/npochaev/GitHub/Pixelate/pixelate/src/commonMain/kotlin/com/pixelate/Pixelate.kt`
- SHA-256: `1d00f12c65ae89f18e4017d3172cccbd3f7132856c9b1353593b09972d6cf75c`
- Target: `shared/app/src/commonMain/kotlin/dev/po4yka/chur/app/effects/Mosaic.kt`
- The local source has no Git metadata. A source commit ID is not available.

Only the Kotlin effect was copied and adapted. The source sample, platform configuration, and
Gradle module were not copied. Chur adds parameter checks, a buffer limit, cached masks and paint,
and canvas restoration. The source hash identifies the original file before these changes.

## Universal effect

The Chur API uses `Mosaic` and `Modifier.mosaic`. The previous `Pixelate` and
`Modifier.pixelate` names remain as deprecated forwarding functions for source compatibility.
Only the names changed; parameters, defaults, and rendering behavior are unchanged.

`Modifier.mosaic` and `Mosaic` use the same defaults: block ratio `0.22f`, minimum cell `4.dp`,
maximum cell `14.dp`, and edge feather enabled. The effect renders content to a coarse bitmap,
expands it to a bitmap with twice the width and height with `FilterQuality.Low`, then draws the
result at the content size with `FilterQuality.None`. The block ratio is a fraction of the content
height before the minimum and maximum cell limits apply. Edge feather reduces alpha at the edges;
it does not blur the image or blend the original content into the result.

The ratio and both cell sizes must be finite and positive. The minimum must not exceed the
maximum. A cell is at least one physical pixel. Dimension calculations use Double and Long
arithmetic before conversion to bitmap dimensions. The combined ARGB buffers have a 16 MiB limit.
Extreme layouts use a coarser grid to meet this limit, even if that exceeds the requested maximum
cell size. Layout sizes above the integer destination coordinate range are rejected before allocation.
Empty or subpixel layouts draw no content. Neither validation nor rendering errors display the
original as a fallback.

The bitmap buffers, canvases, edge masks, and layer paint belong to a `drawWithCache` instance.
Each frame clears both buffers before use. A new size creates new buffers. Canvas scopes and
feather layers restore their state when drawing throws.

`MinimumSafeBlockRatio = 0.18f` is a historical reference for the source text sample. It does not
guarantee that a photograph, a face, a silhouette, or text is hidden. The effect changes pixels
only. Source data and accessibility semantics remain present. It is not an encryption or lock control.

### Software canvas restriction

The universal effect draws to software bitmap canvases. On Android, these canvases cannot draw
hardware-backed bitmaps or content that requires hardware graphics layers. Decode images to
software bitmaps before use. An unsupported draw fails; it must not reveal the original content.
The effect does not support arbitrary video, platform views, or hardware layer subtrees.

Do not apply the universal effect to gallery rows. It captures its content on each draw. The gallery
uses cached image renditions of existing decrypted thumbnails instead.

## Gallery contract

Gallery concealment is a session setting. It starts enabled after each unlock. The switch in Library
and Search applies to grid and list items in all gallery views. A mosaic uses a central square crop,
an opaque fixed gray background, an 8 by 8 coarse image, and a 16 by 16 intermediate image. It has
no edge feather. The final display uses nearest-neighbor sampling and existing item clipping.

The thumbnail cache shares its 256-entry limit and estimated 32 MiB decoded-bitmap budget between
plain and mosaic renditions. Eviction is FIFO: a cache hit does not change insertion order.
The byte estimate covers only cached bitmaps, not source decode, work in progress, or images held
by presentation maps. An image larger than the byte budget can be returned without being cached.
There is no new disk cache. A clear changes its epoch; an older load cannot publish data after that
clear. Session generation and rendition are checked before image publication. A mode change must
show the requested rendition or a neutral placeholder, without an original-image transition.

The gallery requests only the IDs in its current viewport. A shared viewer loader selects the
current object and at most one neighbor on each side. The loader fixes the rendition to Plain;
platform routes do not choose the viewer rendition or calculate the neighbor window.

The full-screen viewer always requests the plain rendition. Opening and closing it does not change
the gallery switch. Lock, session replacement, and real/decoy identity changes discard old loads
and images. Opaque privacy covers keep priority over both gallery modes.

## Validation boundary

Common tests check parameters, grid sizes, physical pixel limits, and buffer bounds. iOS tests use
real Skia canvases to check buffer clearing, alpha masks, independent raster dimensions, and canvas
restoration. Android instrumented tests resize the same modifier and check fresh pixels. Spatial
bitmap fixtures check the coarse reduction, intermediate filtering, and alpha over gray. Android
host tests use framework stubs and cannot prove pixel output. Android instrumented tests must run
on a device or emulator. Record the device, OS, performance measurements, accessibility checks,
and any missing runtime evidence in the implementation handoff.

The local results and remaining acceptance gates are in
[`../assurance/PIXELATE_GALLERY_2026-10-16.md`](../assurance/PIXELATE_GALLERY_2026-10-16.md).

## Performance observations

`ThumbnailPerformanceTest` owns the Android measurement workload. It imports 48 synthetic images,
warms both renditions, and records two trials with alternating Plain/Mosaic order. Each sample uses
the same 32 scroll-to-index and redraw steps; it is not a continuous-fling benchmark. The test checks
the switch state for each sample and records display size, density, refresh rate, and build type.

`p95FrameCompletionLatencyMs` is the 95th percentile of `FrameCompleted - IntendedVsync` for
completed application frames. It is not display cadence. Nonempty samples are required, but there
is no timing pass threshold. Compare quiet repeated runs on representative devices before making
performance claims.
