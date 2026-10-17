# Android UI runtime acceptance and ANR investigation — 2026-10-17

The complete Android UI suite passed **189/189 tests in 37 classes**, with no failures, errors, ignored tests, skips, missing IDs, extra IDs or duplicate IDs. This closes the Android runtime check for the UI changes through published ref `3048268c757d0b5b89de278543386fbbdda67286`. Application and test source did not change during this investigation.

The existing unstaged relocation of a method in `BackNavigationTest.kt` and the user's `docs/research` files remain outside this report commit. No application correction was justified by the observed ANR or the single failed UI lookup described below.

## Recovered startup ANR

Android DropBox retained the actual `data_app_anr` entry for Chur PID 1785, recorded at 12:33:11 on 17 October. Its subject is failure to complete process startup. The captured main thread is runnable inside this platform startup path:

```text
adler32_z
art::DexFile::CalculateChecksum
art::dex::DexFileVerifier::CheckHeader / Verify
dalvik.system.DexFile.openDexFileNative
dalvik.system.DexPathList / PathClassLoader
android.app.LoadedApk.getResources
android.app.ContextImpl.createAppContext
android.app.ActivityThread.handleBindApplication
```

There is no Chur callback in that main-thread stack. The captured work is ART DEX verification during classloader setup. The thread has accumulated 2.779 seconds on CPU and 33.587 seconds waiting on the CPU run queue. These are cumulative thread counters, not the duration of the ANR interval. The [Linux scheduler documentation](https://docs.kernel.org/6.13/scheduler/sched-stats.html#proc-pid-schedstat) defines those fields.

Guest CPU pressure reports `some avg10=89.00`; I/O pressure reports `some avg10=28.43` and `full avg10=1.53`. These values measure time stalled on resources, not CPU utilization. See the [kernel PSI documentation](https://www.kernel.org/doc/html/v5.7/accounting/psi.html#pressure-interface).

The trace supports CPU starvation during ART startup under high system pressure. It does not show an application deadlock. One snapshot cannot identify the exact host workload or establish the whole startup timeline. The separate FocusEvent timeout at 12:25 has no recovered matching stack; this startup diagnosis must not be assigned to that earlier event. The later idle main-thread snapshot from PID 3426 is also a different process.

## Emulator recovery

Low disk space first prevented a normal emulator boot. Only ignored, rebuildable Rust and Android intermediate output was removed. The APKs, reports, source and prior synthetic device state were retained. Other host work was left running.

A subsequent emulator process exited with SIGABRT. The macOS crash report shows `std::__throw_bad_function_call` through `grpc::internal::CallbackWithSuccessTag::Proceed` in an emulator worker thread. This is a native emulator crash; it is separate from the Android application ANR.

The successful runs used the existing `ChurUxAudit20261017` Google APIs AVD, API 37, 16 KiB pages, Android Emulator 37.2.12.0, and console port 5584. The emulator started without a snapshot, with software rendering:

```sh
emulator -avd ChurUxAudit20261017 -port 5584 -no-snapshot \
  -no-audio -no-boot-anim -gpu software -no-window
```

The display remained 1080 × 2424 at density 420, with normal font scale. No ANR watchdog, authentication check, secure-window flag or network boundary was disabled. The current boot recorded no application or system ANR/crash during either complete execution. These observations establish a stable tested configuration; they do not isolate which environment change removed each earlier failure.

## Complete execution and retained failure

Source inventory and runtime discovery each contain the same 189 unique IDs. The suite uses the existing [three-process plan](OCR_AUDIT_2026-10-17.md#final-validation): `ChurLockTileColdStartTest`, then `ChurLockTileTest`, then all remaining tests with only those two already executed classes excluded. The target process stops before each invocation. All groups use the same test-device PIN argument. The API 37 local-network permission starts denied without a fixed user decision, so its runtime-permission test executes.

The first complete execution ran all 189 unique IDs: 188 passed and one failed. `BackNavigationTest.theCreationFormClearsTheBarsAndNextMovesToTheRepeat` lost the exact `Create vault` accessibility node after `ACTION_SHOW_ON_SCREEN`. The title, keyboard appearance, Next-to-Repeat focus, scroll viewport and initial action reachability had passed. The unchanged isolated method then passed. The helper sends repeated animated scroll actions without waiting for each scroll to settle; that is a timing risk, but the failed frame was not captured and does not prove the exact cause. Neither the application nor the helper was changed to obtain the final result.

The second complete execution passed on the same APKs, with no device intervention during the runner:

| Fresh process group | Passed | Failed or skipped | JUnit time |
| --- | ---: | ---: | ---: |
| Cold-start tile | 1 | 0 | 0.007 s |
| Tile with a host | 2 | 0 | 0.866 s |
| All remaining tests | 186 | 0 | 723.644 s |
| Exact inventory union | 189 | 0 | 724.517 s |

The previously failed creation method passed inside this complete sequence. The first failed run remains diagnostic evidence; its result is not replaced by the isolated pass or the successful second run. No test was skipped, narrowed, given a longer timeout or weakened.

| Installed and local artifact | SHA-256, unchanged before and after execution |
| --- | --- |
| Debug APK | `f271ba2eece8bc348f13559bd048177ef86027b77deba17d451739879b2e1277` |
| Android test APK | `f13314c7938c52b203cc0e37d47857e0206965da2f36a1d04f795149977e37d0` |

## Privacy, motion and visual checks

The full suite includes secure windows, Lock and panic, private playback and focus release, thumbnail concealment, recovery authentication, private-note disposal, real OCR, decoder cancellation and its actual 30-second deadline. The live motion test checks explicit animation scales 0 and 1, removal of settings, and restoration of normal motion. After execution the animator scale is unset, window and transition scales are 1, and accessibility time-to-act is unset.

Eleven fresh public-fixture images were rendered and inspected at 320 dp and 200% font. They cover creation, the labelled recovery checkbox, reduced motion, collapsed and expanded index controls, stopped progress, clear confirmation, empty search, thumbnail concealment, recently added, and reserved operation progress with Cancel. Scrolling reaches the form and search controls; the confirmation actions remain visible. Navigation labels can use ellipsis at this width and font size. These captures contain synthetic public data and do not require disabling private-window capture protection.

The Android app and test builds, Android host tests, lint and iOS Simulator compilation from the UI implementation remain separate build evidence. This investigation reused their exact APKs; it did not rebuild or change application source. Physical-device frame pacing and haptics, iOS runtime UI, full TalkBack/VoiceOver acceptance, CI and production acceptance remain separate checks.
