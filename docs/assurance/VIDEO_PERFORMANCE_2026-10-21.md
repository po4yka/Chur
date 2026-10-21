# Encrypted video performance observations, 2026-10-21

These tests add repeatable measurements, not a speed improvement claim or a
device-independent release budget. They do not bypass authentication, create a
plaintext playback cache, change decoder choice, or transcode originals.

## Android, Pixel 8 Pro API 37

`VideoPerformanceAndroidTest` imports the real 64-second VFR/long-GOP fixture,
removes the input copy, and mounts the actual vault-backed app player three
times. Each run measures startup to the first observed rendered video buffer.
It then seeks forward and backward to 15.375, 57.750 and 31.125 seconds. Every
seek must render a new frame with a matching presentation timestamp; an audio
clock or stale frame cannot pass. Native export must retain the source hash.

The [raw device measurements](evidence/video-acceptance-2026-10-21/android-performance.json)
gave these results for the debug instrumentation build:

| Metric | Observed value |
| --- | --- |
| First rendered frame, three samples | 484, 451, 298 ms |
| First-frame P50 / P95 | 451 / 484 ms |
| Seek latency P50 / P95, nine samples | 874 / 944 ms |
| Rendered buffers in three approximately 5-second windows | 96, 98, 99 |
| Dropped buffers in those windows | 0, 0, 0 |
| Maximum sampled process PSS in those runs | 186927, 197620, 193819 KiB |

Percentiles use nearest rank. With three startup and nine seek samples, P95 is
not a population estimate. Filesystem and process caches are not flushed.
Startup includes mounting the Compose player and polling for its first frame.
Process PSS includes the test runner and UI; it excludes separate codec
processes and any GPU allocation not charged to this process. Memory sampling
does not establish an allocation peak or prove absence of leaks. The CPU values
in the JSON cover the app process only.

Run after building and installing the application and test APKs:

```sh
adb -s DEVICE shell am instrument -w -r -e chur.video.requireDecode true \
  -e class dev.po4yka.chur.android.VideoPerformanceAndroidTest \
  dev.po4yka.chur.test/androidx.test.runner.AndroidJUnitRunner
```

The required decode flag makes a connected-device run fail without a decoder.
Without that flag, an unsupported CI emulator writes an explicit unavailable
measurement with its reason. It never reports a zero latency or skips the test.

## iOS Simulator

`IosVideoPerformanceTest` measures encrypted 4K60, portrait and long-GOP/VFR
inputs. It records native readiness, first **decoded** frame latency, exact
seek-to-decoded-frame latency, and whole-process resident memory at fixed
checkpoints. The native process maximum includes previous tests. AVPlayer's
access-log dropped-frame counter is reported when nonnegative; otherwise it is
null with a reason. A short output-buffer sampling window is not an onscreen
frame-pacing measurement.

Run with a booted simulator:

```sh
CHUR_IOS_TEST_DEVICE=SIMULATOR ./gradlew :shared:app:iosSimulatorArm64Test
```

JSON reports go to `shared/app/build/reports/video-acceptance/ios` and are
uploaded with native test diagnostics. Simulator software decode, host load,
and process memory accounting differ from the physical Pixel run. These results
must not be used to rank Android against iOS hardware. A physical iPhone was
unavailable.

The final full native app suite passed 404 tests without failures or skips.
The Android app host suite passed 410 tests and the import host suite passed
33 tests. One earlier host run hit a Main dispatcher teardown race in launcher
tests; the full repeat passed. No launcher test or assertion was changed for
this video work. Android lint and the application/test APK builds passed.

The small 64-second file measures timestamp/seek mechanics. The short 4K60 file
does not establish sustained 4K60 throughput, energy use, high-bitrate recording
support, four-hour playback or thermal behavior. Define device-specific budgets
only after collecting stable release-build baselines on the target hardware.
