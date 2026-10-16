# Pixelate gallery evidence: 2026-10-16

> **Status:** Implemented locally. The authorized lock alignment is applied. Host tests, iOS tests, Android builds and lint, iOS device compilation, the simulator framework, and the Xcode app build passed in the isolated validation checkout. An earlier complete Android connected run passed all 69 tests. The final isolated connected run and the current iOS VoiceOver and performance checks are pending.

The implementation contract and source hash are in [`../interop/PIXELATE.md`](../interop/PIXELATE.md).
No commit or publication is part of this change.

## 1. Scope, builds, and automated checks

The final validation checkout is
`/Users/npochaev/.codex/worktrees/pixelate-validation/Chur`, at baseline `b7aadc7` with only the
Pixelate, gallery, test, documentation, and authorized lock changes copied into it. This checkout
excludes concurrent work in Rust and `SyncCoordinator`, and other unrelated changes in the main
working tree. Later concurrent changes also added visible-item loading and a decoded-byte cache
budget in files shared with this implementation. Those changes were preserved in the main tree;
they are not part of the isolated result. The results in this table apply to the isolated scope.

| Check | Observed result | Command or local evidence |
| --- | --- | --- |
| Host tests and public-shell isolation | 335 tests; no failures, errors, or skips | `./gradlew --no-daemon jvmTest testAndroidHostTest :shared:feature-notes:churPublicShellIsolation`; `/tmp/chur-pixelate-isolated-host.log` |
| Android debug APK, lint, and test APK | passed | `./gradlew --no-daemon :apps:androidApp:assembleDebug :apps:androidApp:lintDebug :apps:androidApp:assembleDebugAndroidTest`; `/tmp/chur-pixelate-isolated-android-build.log` |
| Android APK 16 KiB alignment | passed for the final isolated APK | `zipalign -c -P 16 -v 4 apps/androidApp/build/outputs/apk/debug/androidApp-debug.apk` |
| iOS simulator tests | 217 tests; no failures, errors, or skips | `./gradlew --no-daemon iosSimulatorArm64Test`; `/tmp/chur-pixelate-isolated-ios-tests.log` |
| iOS device Kotlin compilation | passed | `./gradlew --no-daemon compileKotlinIosArm64`; `/tmp/chur-pixelate-isolated-ios-device.log` |
| iOS simulator framework and export checks | passed | `./gradlew --no-daemon linkDebugFrameworkIosSimulatorArm64`; `/tmp/chur-pixelate-isolated-ios-framework.log` |
| iOS simulator host app | passed; iPhone 17 Pro, iOS 26.5 | `xcodebuild -project apps/iosApp/Chur.xcodeproj -scheme Chur -configuration Debug -destination 'platform=iOS Simulator,id=52EA6B11-175C-497B-B888-2B890CA37956' -derivedDataPath /tmp/chur-pixelate-isolated-derived-data CODE_SIGNING_ALLOWED=NO build`; `/tmp/chur-pixelate-isolated-xcode.log` |

The host test total is 314 Android host tests plus 21 JVM tests. Android host tests use framework
stubs. Their result does not prove bitmap output on Android. iOS tests use real Skia canvases for
bitmap output.

The two-line change in `shared/core-model/gradle.lockfile` moves `jvmMainCompileClasspath` from
Kotlin stdlib `2.4.10` to the already selected `2.4.20`. The user authorized its application in the
follow-up request. It remains in both working trees and adds no dependency. Host validation is
no longer blocked by the old lock entry.

The first real iOS raster run found that the effect set the content scope size to the coarse
bitmap size, then applied a second scale. Commands that used the default content size drew only
a small corner. The effect now keeps the original logical content size while it draws to the
coarse bitmap. Tests check that scope size, full-frame pixels, frame replacement, resize, edge
alpha, and canvas restoration after a content error. The complete isolated simulator run above
passed with the pixel assertions retained.

The combined main working tree is a separate validation scope. Its follow-up iOS command failed
in `SyncCoordinatorTest.refresh_without_a_saved_server_does_not_provision_a_sync_identity`, a test
from concurrent work excluded from the isolated checkout. The log is
`/tmp/chur-pixelate-followup-ios-tests.log`. This record does not claim that all checks pass on the
combined main working tree.

## 2. Android runtime checks

The full command is:

```sh
./gradlew :apps:androidApp:connectedDebugAndroidTest --no-configuration-cache --console=plain
```

The follow-up full run in the main working tree passed: 69 tests, no failures, errors, or skips.
Its log is `/tmp/chur-pixelate-followup-connected.log`. The test target was the task-owned
`ChurPixelateValidation20261016` emulator, API 37, arm64, with 16 KiB pages. A secure test screen
lock and the runner's test PIN were configured, so the device-slot and recovery cases executed.
The PIN value is not part of this record.

The first isolated attempt stopped after 32 tests. Two checks used stale accessibility snapshots;
they now read current Compose semantics atomically for the unlock busy interval and the Library
tab action. No assertion was removed. The third reported failure was an aborted recovery test:
a separate `ExportShareLeaseTest` instrumentation run on emulator-5560 called `am set-debug-app`,
which killed the active suite process. The sanitized log is
`/tmp/chur-pixelate-isolated-interference.txt`.

The final full run uses a new task-owned emulator on port 5588. Its log is
`/tmp/chur-pixelate-isolated-connected-final.log`; its result is pending. The earlier main run
does not replace this final check.

The follow-up fixes address the five failures from the earlier run:

- The task-owned emulator has a secure screen lock for `AndroidSecurityTest.deviceSlotKeyPolicy`.
- Banner and sync tests scroll the Settings section until each current control is available.
- Settings tests act on current Compose semantics instead of a detached accessibility snapshot.
- Search puts its header in a scrollable area with a maximum height of half the viewport. The
  results retain the remaining height through layout weight. The photo result is reachable while
  the keyboard is open; the viewer test asserts that the IME remains visible before the tap.

No bitmap assertion or production security control was removed to make these checks pass.

The full run includes these new instrumented cases:

- `PixelateRenderingTest.both_apis_render_current_content_and_optional_feather_on_a_software_canvas`
- `ThumbnailMosaicAndroidTest.mosaic_center_crop_is_opaque_and_does_not_keep_previous_pixels`
- `BackNavigationTest.thumbnailConcealmentFollowsTheSessionAcrossViewerViewsSearchAndSelection`
- `BackNavigationTest.thumbnailRenditionsReportFrameAndMemoryMetrics`

Android coverage includes both universal effect APIs on a software canvas, image crop and alpha,
cache invalidation, session concealment, viewer behavior, browse modes, search, and selection.
Build success alone does not establish runtime coverage.

## 3. Android performance sample

The earlier connected run recorded this local debug sample on `sdk_gphone16k_arm64`, API 37:

| Mode or event | Completed frames | p95 frame duration | Process PSS |
| --- | ---: | ---: | ---: |
| Plain thumbnails | 50 | 37.703324 ms | 186807 KiB |
| Mosaic thumbnails | 54 | 33.709353 ms | 174663 KiB |
| Before lock | — | — | 174663 KiB |
| After lock | — | — | 175047 KiB |

`BackNavigationTest.thumbnailRenditionsReportFrameAndMemoryMetrics` imports 48 synthetic JPEG
images. It warms both cache renditions and both rendering modes, then runs the same 32
scroll-to-index and redraw steps for each mode. It reads completed frame durations from Android
`gfxinfo framestats` and process PSS from `Debug.getPss()`.

This historical JSON is `/tmp/chur-pixelate-android-performance-final.json`. The first run, under
more host load, recorded p95 values of 434.203588 ms for Plain and 379.507194 ms for Mosaic. This
large change between runs is a confounding factor. The small frame count, debug configuration,
and shared host load limit comparisons. These values do not establish a speed improvement, a
frame-time budget, or physical-device performance. PSS after lock does not prove that memory was
returned to the OS or that private heap data was zeroed. Cache and session invalidation have
separate behavioral checks.

The performance output from the final isolated connected run is pending. iOS frame and memory
checks are also pending; no iOS performance result is asserted here.

## 4. iOS visual smoke and current acceptance

An earlier local smoke flow used a synthetic PNG on an iPhone 17 Pro simulator with iOS 26.5. It
observed:

- Gallery concealment was enabled after unlock.
- The switch changed between plain and mosaic thumbnails.
- Opening an item showed the original in the viewer.
- Returning from the viewer kept the gallery in mosaic mode.
- A new unlock restored concealment. Grid and list controls remained visible and kept their geometry.

Local screenshots are `/tmp/chur-pixelate-ios-mosaic.png` and
`/tmp/chur-pixelate-ios-viewer.png`. They contain synthetic test content. The temporary mirror from
that run was stopped and its simulator was shut down. Other simulators were left intact.

That earlier VoiceOver attempt confirmed one merged `Hide thumbnails` switch in the native
accessibility tree, but did not establish gesture navigation or speech.

Current acceptance uses the final isolated Xcode build on task-owned iPhone 17 Pro simulator
`52EA6B11-175C-497B-B888-2B890CA37956`, iOS 26.5. Forty-eight synthetic PNG fixtures, each
480×320, were added to Photos. Nine were selected through the native picker; the gallery then
contained ten synthetic photos, including the existing fixture. The source images were kept.
The final build showed mosaic thumbnails after unlock. Its native accessibility tree reported
one selected `Hide thumbnails` switch and `Thumbnail hidden` on each photo.

With VoiceOver enabled, real Control-Option-Right keyboard input moved the visible VoiceOver
focus from Grid to List and then to one rectangle around the switch and its label. Activation
with Control-Option-Space did not produce an observed state change. These commands follow the
[Apple external keyboard guide](https://support.apple.com/en-ie/guide/iphone/iph6c494dc6/ios).
The native Device Hub connection then failed twice and the app returned to the public Notes
screen. This run does not establish switch activation or speech output with VoiceOver. It also
does not establish the final app-switcher cover appearance. No security control was disabled.
The earlier visual viewer/return flow above remains separate evidence. A fresh manual run on a
physical iPhone is required for the incomplete assistive-technology and app-switcher checks.

An app-scoped Instruments `Frame Lifetimes` recording attached to Chur PID 41133 on the task-owned
simulator and completed after 10 seconds. The export contained 46 rows: 23 had zero start time
and sentinel display ID 4294967295; the other 23 had unsigned timestamps outside the 10.523-second
capture interval. No valid all-frame p95 or Plain/Mosaic comparison can be computed from that
export. Idle RSS changed from 241776 to 172432 KiB; this is neither a mode comparison nor a lock
cleanup result. The result is `/tmp/chur-pixelate-validation-20261016/chur-final-feasibility.json`,
with its trace, TOC, and frame XML beside it. Earlier simulator template attempts also failed or
returned invalid timing. No connected physical iPhone was available. The task-owned simulator
was shut down and deleted after this check; the prior fixture simulator remains shut down.
Test resources are described in `/tmp/chur-pixelate-validation-20261016/README.txt`.

## 5. Android manual accessibility

A separate manual run used the task-owned `emulator-5560`, API 37, arm64, 16 KiB pages,
1080×1920 at density 420, with TalkBack `17.0.0.889642762`. The fixture was an isolated synthetic
vault with one patterned image. This manual run was separate from instrumentation and timing.

Native TalkBack focus and its speech-text display changed in response to real keyboard events.
The observed output was:

| Action | Observed speech text |
| --- | --- |
| Focus the merged switch | `On. Hide thumbnails. Switch` |
| Activate, move to Sort, return | `Off. Hide thumbnails. Switch` |
| Activate again, move away, return | `On. Hide thumbnails. Switch` |
| Focus the photo | `Thumbnail hidden. Photo. In list` |
| Open the photo | `Photo. Out of list` |
| Use TalkBack Back | `Library` |
| Reach the switch after return | `On. Hide thumbnails. Switch` |

Toggling kept the switch in the focus order: next was Sort, and previous returned to the switch.
Back returned focus to the Library title; the next item was Browse. It did not restore focus to
the prior photo. This is an observed boundary; its baseline or regression origin was not established.
No direct audio listening or speech-quality check was performed.

The detailed record and capture paths are in `/tmp/chur-pixelate-talkback-evidence.txt`.
`FLAG_SECURE` and privacy covers remained enabled. Native app pixels were black in screenshots;
only the system speech overlay was visible. These captures do not prove viewer bitmap output.

Cleanup disabled TalkBack, reset its test settings, restored notification permission and app-op
state, removed the synthetic Chur installation and temporary vault copies, and restored emulator
keyboard settings. The secure test PIN was retained for the final full suite. No pre-existing
user vault or unrelated emulator was changed.

## 6. Remaining evidence

- Finish the final isolated connected run and retain its test totals and performance sample.
- Complete iOS VoiceOver activation and speech, app-switcher visual checks, and valid Plain/Mosaic profiling on a physical iPhone. The simulator and native input limits are recorded above.
- Complete the final review after any Android suite fix. The final isolated APK passed the 16 KiB zip alignment check (`/tmp/chur-pixelate-isolated-alignment.log`).
- Repeat affected privacy and gallery flows on physical Android and iOS devices. No physical
  device result is present in this record.
- Use representative devices and quiet repeated runs before making performance claims. If the
  simulator profiler cannot produce valid frame samples, record that limit instead of substituting
  CPU samples or a hitch-only percentile for all-frame p95.

Simulator smoke and canvas tests do not replace physical-device checks. Mosaic pixels can retain
recognizable colors and shapes. Lock, encryption, and opaque privacy covers remain separate
privacy controls. Paths in this record are local temporary evidence, not committed artifacts.
