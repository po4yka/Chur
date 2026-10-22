# Public Utilities Validation — 2026-10-22

Status: implementation and the remaining report checks are complete within the observed coverage below. Physical iPhone testing is waived by the owner. This report does not claim exhaustive coverage of every device and setting combination.

## Scope

The local change adds a neutral public theme, an explicit eight-digit transition code with an authenticated setup trial, a version-row fallback, public launcher presentation, calculator history and scientific operations, compact notes controls, and native photo/video capture with a local gallery. Existing Markdown notes and JPEG files remain supported.

The transition code opens existing authentication. It does not unlock a vault. New and existing installations retain the visible entry until the user completes the setup trial. The owner subsequently authorized a commit and push to `main`. Store publication and deployment are outside this task.

## Observed checks

| Check | Result |
| --- | --- |
| Swift package XCTest | Passed: 80 tests, zero failures. These tests run on the macOS host. |
| iOS Simulator application build | Passed with Xcode. Includes the AVFoundation adapter and share extension. |
| Built iOS bundle | Three alternate icons, English and Russian localizations, no static private shortcuts, neutral share-extension label, existing license files present. |
| Backup rules and merged manifest | Passed. No vault path is included. |
| `git diff --check` | Passed at the last inspection. |
| Notes host tests and `churPublicShellIsolation` | Passed: 53 notes tests; isolation task passed in the final combined run. |
| App host tests, first attempt | 465 tests, one stale archive-destination expectation failed. The test now expects the bounded destination capacity. Discard, close, credential-clear, and operation-preservation assertions remain. |
| App host tests, parallel retry | 467 tests, two session-race tests timed out during concurrent native linking. The sequential retry passed all 467 tests with zero failures, errors, or skips. |
| `iosSimulatorArm64Test` | Passed after the host restart: 457 tests, zero failures, errors, or skips. The earlier stalled spawn ended with exit code 89 and remains recorded in the attempt log. |
| Android application and instrumentation build | Passed: application and instrumentation APKs built. Fixed a missing notes test callback and the launcher alias namespace mismatch. |
| Android UI and physical camera acceptance | Passed: 17 emulator UI/storage/launcher tests and the Pixel 8 Pro native capture test. Fixed the launcher alias namespace mismatch and camera preview resizing during recording. The latter caused ERROR_SOURCE_INACTIVE. |

## Shared host constraint

The final sequential Gradle run acquired the default build slot after the owner authorized termination of the stalled Veyrandi read-only `git check-attr` subprocess. The exact process command was checked before SIGTERM. No other project process was stopped. The host then reported a load average of about 673. Gradle started its JVM and task graph calculation. The host was subsequently restarted outside this task; the process and its temporary log were lost. A new Android/host run writes its log directly to the evidence directory. Host load is a constraint, not a passing test result.

## iOS runtime boundary

The dedicated iOS 27 simulator completed its initial boot. The test spawn then stalled for several minutes. A simple command also did not return. The dedicated simulator was shut down. Its next boot stalled, and creation of a separate iOS 26.5 simulator did not finish within 45 seconds. Other tasks use CoreSimulator, so its shared service and their simulators were not restarted.

After the host restart, the dedicated iOS simulator booted in six seconds. The built app installed and launched successfully. The notes start screen was inspected in `build/public-apps-evidence/ios-public-launch.png`; its title, search, and create action were visible without clipping. This is a start-screen check, not full interaction acceptance. Shared native tests passed on retry.

The owner explicitly waived physical iPhone acceptance for this implementation task because no iPhone is available. No physical iPhone check was performed. This waiver does not establish camera hardware readiness.

## Follow-up acceptance

| Previous gap | Observed result |
| --- | --- |
| Physical Android lenses, orientation, and playback | Passed on Pixel 8 Pro. The production CameraX test captures with the front camera and each exposed rear lens, captures in landscape, checks JPEG and H.264/AAC tracks, verifies video playback advances, exports video, and reopens the repository. Explicit silent video has no audio track. A recording completes when the app enters the background. |
| Camera and microphone denial | Camera denial shows Allow camera and System settings. A denied microphone request returns the no-sound state with an explicit message. The silent capture path is covered by the physical test. Permissions were restored after the check. |
| Note attachment recovery | Passed manually on Pixel. Created a test note, selected its PNG through the system Downloads picker, moved the note to Trash, restored it, and stopped/restarted the app. The text, attachment reference, filename, MIME type, size, and rendered image remained available. The test note remains available; only the temporary Downloads input was removed. |
| TalkBack | Enabled on Pixel while preserving the existing accessibility service. Opened the restored note and returned to the list through touch focus and double-tap activation. Restored the original service setting afterward. |
| VoiceOver | Enabled in the dedicated iOS 27 simulator. Created a note through its accessible action, entered `test` with the on-screen keyboard, acknowledged the first-save disclosure, and returned to the saved note in the list. Restored VoiceOver afterward. This verifies the actions with VoiceOver enabled; it is not a recorded speech-quality review. |
| RU/EN, themes, landscape, and tablet size | Passed the shared Compose UI matrix: three screens, two languages, two themes, and sizes 320×640, 640×320, and 700×800 dp. The matrix renders 36 states and exercises the calculator, notes search, and capture action. Representative screenshots were inspected. Scrollable controls remain reachable on narrow screens. |
| Large text and reduced motion | Android presentation tests pass at font scale 2 with system animations disabled. iOS notes were inspected with Reduce Motion enabled, both themes, portrait/landscape, and Text Size 8 after app restart. Russian labels and the saved note were visible in dark landscape. Simulator settings were restored. |
| Real disk exhaustion | Passed against production `PublicMediaAlbum` on an isolated 64 MiB Android emulator tmpfs. A real write returned ENOSPC; capture preparation was rejected while full and succeeded after the test file was removed. The phone's storage was not filled. |

The microphone-denial check found overlapping status text. The camera now places the message and silent-video indicator in one vertical overlay. The preview bounds do not change. A regression test checks that the two labels do not overlap, including font scale 2. All seven presentation tests pass after this fix; the updated screenshot was inspected.

The earlier 17-test Android run covers public UI, storage, and launcher behavior. The final seven-test presentation run includes the language/theme/size matrix and the overlay regression. The expanded physical camera run passes separately. These counts overlap and must not be added as unique tests.

## Evidence boundaries

The full-disk check proves the repository's capacity gate and recovery after actual ENOSPC. It does not fill the filesystem during a physical camera recording. Background recording finalization is tested separately. Tablet coverage uses the shared renderer at tablet dimensions, not a physical tablet. The accessibility checks exercise representative navigation and text entry; they do not establish every screen-reader gesture on every screen.

Physical iPhone camera checks were not run, by the owner's explicit decision. Simulator and shared tests do not prove iPhone lens, microphone, or AVFoundation hardware behavior. No check is described as passed solely because it was waived.

## Review

The structured source review completed with no P0 findings. Its secret scan passed. The review includes source, manifests, dependency lockfiles, and documentation. Binary launcher PNGs were inspected visually outside the source bundle. A clean source review is separate from the device results above.

## Local evidence

Build logs are in the ignored `build/public-apps-evidence/logs/` directory:

- `swift-tests.log`: completed Swift/XCTest run.
- `ios-build.log`: successful application build.
- `shared-parallel-attempt.log`: failed host/simulator attempt, retained for diagnosis.
- `android-host-final.log`: 467 app host tests passed; the combined command failed at the subsequently repaired instrumentation compilation.
- `ios-shared-final.log`: 457 shared iOS tests passed.
- `android-title-final-build.log`: final Android build.
- `android-ui-title-final.log` and `pixel-camera-accepted.log`: final device runs.
- `ios-title-final-build.log`: iOS build after the large-text title correction.
- `pixel-expanded-2.log`: expanded Pixel camera acceptance.
- `ui-matrix-4-reduced-motion.log`: original six-test matrix run.
- `final-overlay-build.log` and `final-overlay-ui.log`: final Android build and seven presentation tests.
- `ios-overlay-final-build.log`: iOS build after the status-overlay correction.
- `real-full-storage.log`: actual ENOSPC and repository recovery.
- `autoreview-final.log` and `autoreview-final-result.json`: final structured source review after the overlay fix.

Manual evidence includes `pixel-note-restored.xml`, `pixel-talkback-open-note.xml`, `pixel-camera-denied.xml`, and `pixel-microphone-denied.xml`. They are local evidence only. The last file records the overlap found before the fix. The corrected rendering is `public-camera-microphone-denied-320-font2.png`. The `matrix-rendered/` directory contains the shared UI renders. iOS screenshots include `ios-voiceover-note-saved.png` and `ios-ru-dark-large-landscape.png`.

Baseline Android screenshots are in `build/public-apps-evidence/baseline-android/`. They show the earlier application and do not prove the new interface works.
