# Extended video acceptance, 2026-10-21

## Inputs

The [fixture matrix](../../tests/media/video-acceptance/README.md) contains
4K60 Main10, a 64-second VFR stream with 16-second GOPs, a portrait display
matrix, and an unchanged public Dolby Vision MOV from AndroidX Media.
The three generated files are synthetic moving patterns. The camera origin
of the public MOV is inferred from its iPhone capture metadata. Its Dolby
Vision configuration and RPU data are real. The MOV lasts only 0.107 seconds;
it tests an initial frame and original preservation, not sustained playback.
The manifest pins lengths, SHA-256 hashes, coded formats and actual timestamps.

## Checks

Android checks native sample cadence, sync-frame gaps, rotation, upright
posters, and fixture hashes. The actual app player then reads encrypted vault
objects after the input copy has been removed. Supported cases must decode a
video frame and seek to a new frame at the requested time. An audio clock alone
cannot pass. Native export must retain the original SHA-256. A held reader must
fail with `SESSION_EXPIRED` after lock.

iOS uses the same files through the authenticated resource loader. It checks
coded and display dimensions, native decoded frames, exact seeks, native export
SHA-256 and lock invalidation. A stale frame after seek is released; the test
waits for a frame whose native presentation time is within 150 ms of the target.

## Reproduce

Build the application and test APKs before installing them. Use `-r -t` to
update the test build without clearing app data. Select the device explicitly.

```sh
./gradlew :apps:androidApp:assembleDebug :apps:androidApp:assembleDebugAndroidTest
adb -s DEVICE install -r -t apps/androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb -s DEVICE install -r -t apps/androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk
adb -s DEVICE shell am instrument -w -r -e chur.video.requireDecode true \
  -e class dev.po4yka.chur.android.VideoAcceptanceAndroidTest \
  dev.po4yka.chur.test/androidx.test.runner.AndroidJUnitRunner
CHUR_IOS_TEST_DEVICE=SIMULATOR ./gradlew :shared:app:iosSimulatorArm64Test
```

`chur.video.requireDecode=true` makes the connected-device lane require a
decoder for every input. The CI emulator lane instead requires explicit native
unsupported status and original export when no decoder exists. It does not skip
these inputs. Both lanes use the same test class. CI requires exactly two tests,
checks the instrumentation result, and rejects failures or skipped tests.

Reports are saved under the Android app's external files `video-acceptance`
directory and `shared/app/build/reports/video-acceptance/ios`. CI uploads the
reports with the existing device diagnostics and native test XML.

## Observed result

Pixel 8 Pro, Android API 37: both instrumentation tests passed. All four inputs
decoded; the three longer inputs also sought to new video frames. All four
original exports and lock checks passed. See the [device report](evidence/video-acceptance-2026-10-21/android-extended.json).
API 35 emulator: the two extended tests and one performance test passed. Its
unsupported decoder result is distinct from the Pixel's positive decode result.
iOS 27 Simulator: all four extended inputs passed native frame decode, the
applicable exact seeks, original export and reader invalidation. The native
reports are in `evidence/video-acceptance-2026-10-21/ios-acceptance-*.json`.

A physical iPhone was unavailable. Simulator results do not establish iPhone
camera coverage, physical HDR display output, calibrated tone-map fidelity,
sustained 4K60 playback, large-file throughput or thermal behavior. The 64-second
low-resolution input tests timestamp and seek mechanics, not a four-hour or
high-bitrate recording.
