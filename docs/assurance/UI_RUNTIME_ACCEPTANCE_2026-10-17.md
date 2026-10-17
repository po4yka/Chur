# Android UI runtime acceptance and ANR investigation — 2026-10-17

The latest complete Android UI suite passed **189/189 tests in 37 classes** after the form-helper correction in `774a748db5476b457c721b6a3be3bed241a7ed4a`. There were no failures, errors, ignored tests, skips, missing IDs, extra IDs or duplicate IDs. The application APK stayed unchanged. The follow-up sections below record the test correction and its validation.

The first investigation also passed 189/189 through published ref `3048268c757d0b5b89de278543386fbbdda67286`. Application and test source did not change during that first investigation. Its earlier results remain below as historical evidence.

The existing unstaged relocation of a method in `BackNavigationTest.kt` and the user's `docs/research` files remain outside this report commit. No application correction was justified by the observed ANR or failed UI lookup. Later evidence confirmed a test-helper defect, as recorded in the follow-up.

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

The display remained 1080 × 2424 at density 420, with normal font scale. No ANR watchdog, authentication check, secure-window flag or network boundary was disabled. That earlier boot recorded no application or system ANR/crash during either of its two complete executions. These observations establish a stable tested configuration; they do not isolate which environment change removed each earlier failure.

## Complete execution and retained failure

Source inventory and runtime discovery each contain the same 189 unique IDs. The suite uses the existing [three-process plan](OCR_AUDIT_2026-10-17.md#final-validation): `ChurLockTileColdStartTest`, then `ChurLockTileTest`, then all remaining tests with only those two already executed classes excluded. The target process stops before each invocation. All groups use the same test-device PIN argument. The API 37 local-network permission starts denied without a fixed user decision, so its runtime-permission test executes.

The first complete execution ran all 189 unique IDs: 188 passed and one failed. `BackNavigationTest.theCreationFormClearsTheBarsAndNextMovesToTheRepeat` lost the exact `Create vault` accessibility node after `ACTION_SHOW_ON_SCREEN`. The title, keyboard appearance, Next-to-Repeat focus, scroll viewport and initial action reachability had passed. The unchanged isolated method then passed. At that stage, repeated animated scroll actions without waiting for each scroll to settle were a timing hypothesis. The failed frame was not captured. The follow-up probe below found zero scroll requests, so that hypothesis does not explain the captured failure. Neither the application nor the helper was changed to obtain that earlier result.

The second complete execution in the first investigation passed on the same APKs, with no device intervention during the runner:

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

The Android app and test builds, Android host tests, lint and iOS Simulator compilation from the UI implementation remain separate build evidence. The first investigation reused their exact APKs. The follow-up rebuilt the test APK and kept the application APK unchanged. Physical-device frame pacing and haptics, iOS runtime UI, full TalkBack/VoiceOver acceptance, CI and production acceptance remain separate checks.

## Follow-up: form scroll correction — 17 October 2026

The historical 189/189 result above applies to the original test APK and the source through `3048268c757d0b5b89de278543386fbbdda67286`. That result and the first 188/189 result remain separate records. The later tests below found a defect in the test helper. They did not establish a defect in the application.

The unchanged creation method first passed ten normal runs. Three complete priority groups then passed all eleven tests in each group. A test with slow animation reproduced the creation failure. A second run, with temporary diagnostics only, reproduced the same failure. The runner logs report 16.542 and 16.276 seconds of JUnit time for the two failed runs. Both remain failed results.

The diagnostic run found zero scroll requests. The helper accepted accessibility-label presence as proof that the action was on screen. It then requested `ACTION_SHOW_ON_SCREEN`, which returned `false`; the helper did not check that result. The live Compose node remained below the keyboard, and the actual scroll value was zero. The keyboard was visible, the scroll viewport ended above it, and Chur had window focus. Clearing the accessibility cache did not reveal the action. This confirms a test synchronization defect: accessibility-node presence did not prove current visibility, and the refused action left the form unscrolled. The earlier repeated-scroll hypothesis does not explain this captured failure, because no scroll request occurred.

Commit `774a748db5476b457c721b6a3be3bed241a7ed4a` changes only the form-check helper. It uses the current Compose control and sends one exact scroll request. It rounds the scroll distance outward to a whole pixel. It then waits for the actual scroll offset and the full control bounds. The final check reads a new accessibility node and requires the keyboard to remain visible, the viewport to end above the original keyboard top, and the action to remain above the keyboard. The creation, unlock and recovery callers keep their original keyboard and focus checks. Disabled actions remain disabled. No timeout, authentication check, privacy control or application source changed. The temporary diagnostic code was removed.

The corrected test APK built successfully in 19 seconds. The corrected helper then passed fifteen checks: ten creation checks, one unlock check and one recovery check with slow animation, followed by one normal check for each form. The system animation setting returned to its exact original state: animator scale unset; window and transition scales at 1. These focused results do not replace a complete suite result.

## Follow-up: unresolved FocusEvent and emulator interruption

The original 12:25 FocusEvent remains without a matching PID and main-thread trace. A bounded streaming search of the existing private bugreport main text found no `82a7304` token and no `5419ms` token. It found five `FocusEvent(hasFocus=false)` entries, but none matched the required 12:25 context. This final search did not read or extract another ZIP entry. The raw archive retained mode 600. This negative search does not identify the cause of the original FocusEvent. The separate ART-startup trace must not be used as its explanation.

Recovered system records from about 12:26 contain only `system_server` PID 648. The subject reports a WindowManagerService monitor and display-handler stall of 22 seconds. The delayed, non-atomic snapshots show polling or condition waits and no lock owner. They do not contain the original target stack or window token. They support a separate system window/display stall, but do not prove the cause of the earlier FocusEvent. Three repeats of the exact historical eleven-test sequence passed 11/11, with JUnit times of 36.669, 36.380 and 37.549 seconds.

The first complete-suite attempt after the helper correction stopped when the emulator VM stopped. It had 66 unique terminal passes out of 189 expected tests, no terminal failures and 123 missing results. The viewer-grid test had started and had no terminal result. The retained manifest marks this execution as interrupted and incomplete. It is not a complete passing result and is separate from the creation-helper failure.

A new cold boot used a private temporary directory for the emulator process. The child process used task-specific `TMPDIR` and `ANDROID_TMP` values in a mode-700 directory. The AVD was not wiped, and shared emulator services and network boundaries were left intact. This is a diagnostic isolation step, not an identified fix for the VM stop.

## Follow-up: complete validation after the correction

The fresh execution after the VM restart passed the exact 189-test inventory in 37 classes. Discovery and terminal results have no missing, extra or duplicate IDs. Every runner finished with exit status zero. No test was skipped, ignored, given a longer timeout or narrowed for this result.

| Fresh process group | Passed | Failed or skipped | JUnit time |
| --- | ---: | ---: | ---: |
| Cold-start tile | 1 | 0 | 0.265 s |
| Tile with a host | 2 | 0 | 1.104 s |
| All remaining tests | 186 | 0 | 768.454 s |
| Exact inventory union | 189 | 0 | 769.823 s |

All three form-helper callers passed inside this full sequence. The viewer-grid test that had no terminal result when the VM stopped also passed. The interrupted 66/189 attempt remains incomplete; this fresh full run does not replace its record.

Before and after execution, the installed and local debug APK hash was `f271ba2eece8bc348f13559bd048177ef86027b77deba17d451739879b2e1277`. The corrected test APK hash was `94f8cc4b4a0c6c2389b2c6e768b494a0faa708a6f34dbdc2e0d92e8e0e5fa282`. The test source file, including the preserved user method relocation, had SHA-256 `f68f3b1a33403bee48a8fc69decfb305fdd2115298c3cd2fb079fb3fd61e11fc`. That relocation is outside the correction commit.

Post-run checks found the VM running on API 37 with 16 KiB pages, no ANR/crash event, and the expected launcher focus. Animator scale was unset, window and transition scales were 1, accessibility time-to-act was unset, display density was 420 and font scale was 1. Local-network permission remained denied without fixed user flags. Device authentication, secure windows, private data boundaries and Reduce Motion were preserved. No manual input, resume or mutating device action occurred during the runner; a separate read-only host console monitor checked VM status.

Eleven new allowlisted public-fixture images were pulled and inspected at 320 dp and 200% font. Recovery, Clear, Cancel and Lock controls fit. In the initial empty-search frame, the thumbnail switch is partly clipped at the scroll boundary; a separate revealed-control frame shows the whole switch, and the interaction test verifies visibility and both toggles. Long text can be clipped at scroll boundaries or use ellipsis. No new control-reachability blocker was observed. The live Reduce Motion observer test also passed in this exact full sequence.

The final test-APK build and fifteen focused checks are local evidence. The complete Android UI run is separate runtime evidence. CI, physical-device motion and haptics, iOS runtime UI and full assistive-technology acceptance remain separate checks. The original FocusEvent cause and the trigger for the earlier VM stop remain unproven.
