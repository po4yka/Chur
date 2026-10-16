# Pixelate gallery evidence: 2026-10-16

> **Status:** Implemented locally. The authorized lock alignment is applied. All requested Gradle commands passed in the isolated validation checkout. The final full Android run passed 69 tests with no failures, errors, or skips. TalkBack smoke is complete within the limits below. iOS VoiceOver activation, app-switcher appearance, valid iOS frame measurements, and physical-device acceptance remain unverified.

The implementation contract and source hash are in [`../interop/MOSAIC.md`](../interop/MOSAIC.md).
No commit or publication is part of this change.

## 1. Scope, builds, and automated checks

The final validation checkout is
`/Users/npochaev/.codex/worktrees/pixelate-validation/Chur`, at baseline `b7aadc7` with only the
Pixelate, gallery, test, documentation, and authorized lock changes copied into it. This checkout
excludes concurrent work in Rust and `SyncCoordinator`, and other unrelated changes in the main
working tree. Later concurrent changes also added visible-item loading and a decoded-byte cache
budget in files shared with this implementation. Those changes were preserved in the main tree;
they are not part of the isolated result. The results in this table apply to the isolated scope. At final handoff, the main checkout had
advanced to `b713b02` through concurrent work; those commits were preserved.

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

The final full run passed all 69 tests with no failures, errors, or skips in 7 minutes 41 seconds.
It used the new task-owned `ChurPixelateFinalIsolated20261016` emulator on port 5588: API 37,
arm64, 16 KiB pages, 4 CPUs, 4 GiB RAM, SwiftShader, hardware keyboard disabled,
1080×1920 at density 420. The secure test PIN was configured. Its log is
`/tmp/chur-pixelate-isolated-connected-final.log`; its XML is
`/tmp/chur-pixelate-isolated-connected-final-result.xml`. No class filter was used.

Further complete runs exposed three separate test defects: the refusal message could reach its
AX live region before the editable field received its error; Back could arrive during the system
owner prompt animation; and fast repeated Settings scrolling could skip a row before the AX tree
updated. The test now waits for both real AX properties, uses the existing prompt animation wait
for cancellation, and uses the existing page-settling scroll helper for switch and auto-lock rows.
A recovery fixture also appended a character to a random three-letter word. Such a word must match
in full under the existing recovery contract; the fixture now adds a suffix only to words with at
least four letters. Case conversion, persistence checks, exact AX assertions, and timer checks
remain intact. Each failed scenario passed a separate diagnostic check before the final full run.
The diagnostic results do not replace the full run above.

After validation, the task-owned port-5588 PIN was cleared, its emulator and AVD were removed,
and its PIN file was deleted. Cleanup was verified in
`/tmp/chur-pixelate-final-device-cleanup.txt`. Ports 5560 and 5554 were left intact because other
work used them. The isolated validation checkout is retained; it contains no new commit.

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

The final successful isolated run recorded this local debug sample on the emulator described above:

| Mode or event | Completed frames | p95 frame duration | Process PSS |
| --- | ---: | ---: | ---: |
| Plain thumbnails | 50 | 34.032540 ms | 229289 KiB |
| Mosaic thumbnails | 53 | 73.822414 ms | 181977 KiB |
| Before lock | — | — | 181977 KiB |
| After lock | — | — | 182457 KiB |

`BackNavigationTest.thumbnailRenditionsReportFrameAndMemoryMetrics` imports 48 synthetic JPEG
images. It warms both cache renditions and both rendering modes, then runs the same 32
scroll-to-index and redraw steps for each mode. It reads completed frame durations from Android
`gfxinfo framestats` and process PSS from `Debug.getPss()`.

The final JSON is `/tmp/chur-pixelate-isolated-performance-final.json`. Mosaic p95 was higher than
Plain in this sample. Earlier emulator samples had Plain/Mosaic p95 of 37.7/33.7 ms and
276.4/209.2 ms. The changing direction and scale show substantial shared-host and emulator noise.
These results do not establish a speed improvement or a frame-time budget. The higher final Mosaic
p95 remains a performance risk to check on representative physical devices with quiet repeated runs.
The small frame count, debug build, and software GPU limit comparisons.

PSS after lock does not prove that memory was returned to the OS or that private heap data was
zeroed. Cache and session invalidation have separate behavioral checks. iOS did not produce valid
frame data; its concrete profiler limit is recorded below.

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
The final build showed mosaic thumbnails after unlock. A local capture is
`/tmp/chur-pixelate-ios-final-picker.png`; it shows the source-retention dialog over the gallery. Its native accessibility tree reported
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

## 6. Review and remaining evidence

The final scoped structured review passed at the P0 threshold with no accepted or actionable
findings. Lower-priority findings are outside that review threshold; this is not an all-severity
clean claim. Local outputs are `/tmp/chur-pixelate-isolated-review.md` and
`/tmp/chur-pixelate-isolated-review.json`. The final isolated diff passed `git diff --check`.
The final APK passed the 16 KiB zip alignment check
(`/tmp/chur-pixelate-isolated-alignment.log`). This task did not create a commit or publish the change.

- Complete iOS VoiceOver activation and speech, app-switcher visual checks, and valid Plain/Mosaic
  profiling on a physical iPhone. The simulator and native input limits are recorded above.
- Repeat affected privacy and gallery flows on physical Android and iOS devices. No physical
  device result is present in this record.
- Use representative devices and quiet repeated runs before making performance claims. Do not
  substitute CPU samples or a hitch-only percentile for all-frame p95.
- Validate the concurrent changes in the combined main tree separately. The green commands here
  apply to the isolated Pixelate scope and do not certify those additional changes.

Simulator smoke and canvas tests do not replace physical-device checks. Mosaic pixels can retain
recognizable colors and shapes. Lock, encryption, and opaque privacy covers remain separate
privacy controls. Paths in this record are local temporary evidence, not committed artifacts.

## 7. Quality improvements after review

This follow-up implements the five code-quality proposals without changing existing public
signatures, parameter names, constants, or defaults. It adds the viewer-specific facade; it does
not replace the public gallery facade. No dependency, Rust source, FFI surface, or storage format
was changed by this task. No commit or publication is part of the task.

The validation checkout is `/Users/npochaev/.codex/worktrees/pixelate-quality/Chur`, based on
`b713b02`. It also includes the pre-existing navigation test fixes from the main working tree.
An initial 87-test attempt inadvertently included the unfinished parallel
`damagedFileCleanupRequiresConfirmationAndKeepsHealthyMedia` case without its matching production
action. The case is absent from `b713b02`; it and the unfinished cleanup production work are both
excluded from the corrected isolated scope. The complete case remains unchanged in the main
checkout. This is scope isolation, not a skipped or filtered test in the final runner.
Other unfinished main-tree changes are excluded from this validation scope. Only this task's
changes were transferred to the main checkout with a three-way merge; unrelated edits were kept.

| Proposal | Implementation | Evidence |
| --- | --- | --- |
| Match test coverage to its claims | Retry the same Mosaic cache key after a render error, then check the successful cache hit; resize the same Android ComposeView and Modifier; use asymmetric spatial and alpha fixtures on Android and iOS | Cache read/render counts, live 128×128 to 192×64 resize, and anchor pixels that distinguish the 8×8 reduction from direct 16×16 scaling |
| Put viewer policy in shared code | `rememberViewerThumbnails` selects the current object and immediate loaded neighbours, fixes Plain, and uses the same private loader as the gallery facade | Three common window tests; both platform routes call the shared viewer facade |
| Clarify internal names | Geometry uses content size, intermediate size, and nominal cell size in pixels; cache names distinguish epoch and estimated resident decoded bytes; routes use visible media IDs | Public signatures and defaults were checked in the scoped diff |
| Separate performance observations | `ThumbnailPerformanceTest` owns the 48-photo workload, two alternating trials, actual switch-state assertions, and display/build metadata | Nonempty frame samples per mode and trial; `p95FrameCompletionLatencyMs` replaces the misleading metric name |
| Keep the contract current | KDoc and `MOSAIC.md` specify height-based ratio, edge alpha, shared 256-entry/estimated 32 MiB FIFO budget, viewport requests, and viewer window | No new cache policy or rendering algorithm was introduced |

The first complete attempt also exposed a test-only AX snapshot race: exact assertions fetched
nodes again after the awaited state had been observed. The test now captures the actual live-region
and field-error values from one root snapshot and retains both exact assertions. A long-press case
also failed in that attempt; its source was left unchanged. It passed both the control run and
the corrected complete suite. The initial log is
`/tmp/chur-pixelate-quality-connected-initial.log`.

The completed checks at this stage are:

| Check | Result | Local log |
| --- | --- | --- |
| `jvmTest testAndroidHostTest :shared:feature-notes:churPublicShellIsolation` | 21 JVM plus 324 Android host tests; no failures, errors, or skips | `/tmp/chur-pixelate-quality-host.log` |
| `iosSimulatorArm64Test` | 227 tests; no failures, errors, or skips | `/tmp/chur-pixelate-quality-ios-tests.log` |
| `compileKotlinIosArm64` | passed | `/tmp/chur-pixelate-quality-ios-device.log` |
| `linkDebugFrameworkIosSimulatorArm64` | passed | `/tmp/chur-pixelate-quality-ios-framework.log` |
| Android assemble, lint, and test APK | passed | `/tmp/chur-pixelate-quality-android-build.log` |
| Android raster diagnostics | 2 universal-effect tests and 1 Mosaic test; no failures or skips | `/tmp/chur-pixelate-quality-PixelateRenderingTest.log`, `/tmp/chur-pixelate-quality-ThumbnailMosaicAndroidTest.log` |
| Dedicated Android performance diagnostic | 1 test, both modes in two alternating trials; no failure or skip | `/tmp/chur-pixelate-quality-performance-diagnostic.log` |
| APK 16 KiB zip alignment | passed | `/tmp/chur-pixelate-quality-alignment.log` |
| Structured review | no accepted or actionable P0 finding; lower priorities were not assessed by this gate | `/tmp/chur-pixelate-quality-review.md` |
| Full Android connected suite | 86 tests; no failures, errors, or skips | `/tmp/chur-pixelate-quality-connected.log`, `/tmp/chur-pixelate-quality-connected-result.xml` |

The Android diagnostics use real bitmap and canvas implementations on task-owned
`ChurPixelateQuality20261016`, `emulator-5590`, API 37, arm64, 16 KiB pages, 1080×1920 at
density 420, 4 CPUs, 4 GiB RAM, and SwiftShader. A synthetic secure screen-lock PIN is configured
for the complete suite's device-authentication cases. Its value is not included in this record.
Host bitmap stubs are not render evidence. These checks add no physical-device or manual
VoiceOver/app-switcher acceptance beyond the earlier record.

The final complete runner used all 86 tests in the corrected isolated scope, with no class or
method filter. It exited with status zero. After its APK was built, the new resize test was made
stricter: the blue pixel at (184, 8) must be outside the old 128-pixel width, while the left half
and areas outside the new bounds must be transparent. This assertion-only refinement does not
change production code. Its final device result is recorded separately below.

The final suite produced these observations on the emulator described above:

| Trial | Order | Rendition | Frames | p95 frame completion latency, ms | Process PSS, KiB |
| --- | --- | --- | --- | --- | --- |
| 1 | 1 | Plain | 90 | 403.946 | 182867 |
| 1 | 2 | Mosaic | 90 | 371.080 | 169875 |
| 2 | 1 | Mosaic | 91 | 283.764 | 170195 |
| 2 | 2 | Plain | 92 | 445.129 | 173203 |

The metric is `FrameCompleted - IntendedVsync`; it is not frame cadence or a physical-device
performance claim. The debug build used SwiftShader and 32 scroll-to-index/redraw steps after
warming both renditions. There were 48 workload photos and 51 gallery objects. Process PSS was
173203 KiB before lock and 173427 KiB after lock; these values do not prove a memory reduction.
The source observations are `/tmp/chur-pixelate-quality-performance-final.json`.

The strengthened resize test then passed on the same real Android canvas: two tests, zero failures,
errors, or skips; Gradle exited with status zero. The log and XML are
`/tmp/chur-pixelate-quality-resize-final.log` and `/tmp/chur-pixelate-quality-resize-final.xml`.
The final source review reported no accepted or actionable P0 finding. It did not assess all
severity levels. The transfer audit found every quality change in the main checkout and confirmed
that the concurrent route callbacks and the unrelated cleanup test were retained.

Cleanup removed only the task-owned `emulator-5590`, its `ChurPixelateQuality20261016` AVD,
and the synthetic PIN file. The test screen lock was cleared and an empty credential was verified
before deletion. Other Android and iOS simulators were left intact. Both checkouts passed
`git diff --check`. The main checkout advanced through concurrent work to `f93b6f2` during this
run; that combined revision was not the validation baseline. This task made no commit or publication.

## 8. Neutral effect naming

The application now uses `Mosaic` and `Modifier.mosaic`. Internal geometry, raster helpers, and
all three effect test classes use the same name. The contract is now `interop/MOSAIC.md`; its
source path and SHA-256 still identify the original Pixelate snapshot. Historical evidence names
and local artifact paths above remain unchanged.

`Pixelate` and `Modifier.pixelate` remain as deprecated forwarding functions with `ReplaceWith`
suggestions. Existing source signatures, parameter names, constants, defaults, and rendering
behavior are preserved. A read-only comparison confirmed that implementation tokens differ only
by name. The main checkout's concurrent changes were retained.

The same isolated `b713b02` quality scope was used for these checks after the rename:

| Check | Result | Local log |
| --- | --- | --- |
| `jvmTest testAndroidHostTest :shared:feature-notes:churPublicShellIsolation` | 21 JVM and 324 Android host tests; zero failures, errors, or skips | `/tmp/chur-mosaic-rename-host.log` |
| `iosSimulatorArm64Test` | 227 tests, including real Skia raster tests; zero failures, errors, or skips | `/tmp/chur-mosaic-rename-ios.log` |
| Android debug assembly, lint, and test APK | passed | `/tmp/chur-mosaic-rename-android.log` |
| `compileKotlinIosArm64` | passed | `/tmp/chur-mosaic-rename-ios-compile.log` |

Each Gradle invocation used `--no-daemon` and exited with status zero. The Android command used
`:apps:androidApp:assembleDebug :apps:androidApp:lintDebug :apps:androidApp:assembleDebugAndroidTest`.
An initial host invocation lacked `ANDROID_HOME`; it was set to the installed SDK before rerunning.
Android device instrumentation was not repeated for this naming-only change; the section 7
runtime results remain separate evidence. Both checkouts passed `git diff --check`.
No commit or publication was performed by this task.
