# Pet shell and guided onboarding: local validation

Date: 2026-10-22. Base revision: `c3d689d1`. This report describes the pet and onboarding change from that base.

## Delivered behavior

- Four public shells, including the offline Chur pet, launcher aliases, quick actions, and the iOS alternate icon.
- Session-only greeting, bounded drag, firefly treat, toy, sleep, and wake. Accessible equivalents, Russian and English text, and static Reduced Motion poses.
- Moon hold and its accessibility action use the existing code transition. Authentication and the discreet-mode trial remain mandatory. Code input clears on dismissal, submission, and background.
- Conservative first-install detection before storage initialization. Existing files, preferences, read errors, and unknown guide versions suppress automatic onboarding.
- Shared guide state and stable target identifiers. Real operation results advance action steps. The guide never receives credentials, recovery words, query text, names, or private content.
- Private guidance covers creation or restore, recovery confirmation, Library, and Settings. Lock, session replacement, and background remove private guidance. Public help teaches only the selected shell.
- Guide status has a version and completion status only. Android stores it in no-backup storage; iOS excludes its directory from backup. There is no identity or private route in that record.
- UIKit private screens reserve space for the guide dock. Each Compose or native target has an independent registration owner. A disappearing screen cannot remove another screen's target.

No Rust, vault format, network service, purchase, notification, or production dependency was added. The Android lock file records the existing CameraX/debug test and lint dependency graph required by the current build.

## Fixes found during private UI acceptance

- Android could not import a normal image selected from Downloads. The Live Photo
  probe reopened the provider descriptor through `/proc/self/fd`, which failed
  with `EACCES` despite the valid URI grant. It now duplicates the granted
  descriptor and uses positional reads. Pipe rejection, resource bounds,
  checksums, cancellation, cursor position, and descriptor ownership stay intact.
- The viewer blocked launcher shortcuts and also hid its guide. Those states are
  now separate: the return-to-Library lesson stays visible during viewing, while
  modals, background, and lock still hide private guidance.
- Successful searches from the Library search bar now complete the search lesson,
  including empty results. The former check accepted only the separate Search tab.
- The creation target now requires a valid matching password. The discreet-mode
  target is the Public applications button instead of its expanding card.
- Native UI tests wait for completed layout and active system windows. A pending
  tap or picker transition is not treated as a completed user operation.

## Automated checks

| Check | Observed result | Local log |
| --- | --- | --- |
| Spirit core | 2,634 assertions passed | `/tmp/chur-pet-core-final.log` |
| Kotlin/C++ motion parity | 10,110 pairs passed; maximum absolute error 4.18e-7 | `/tmp/chur-pet-parity-final.log` |
| Broad KMP/JVM/host and public-shell isolation | Passed | `/tmp/chur-pet-kmp.log` |
| Final shared-app Kotlin/Native simulator tests | 473 passed; no failures or skips; reused by final run | `/tmp/chur-private-regression2.log` |
| Final shared-app Android host tests | 484 passed; no failures or skips | `/tmp/chur-private-regression2.log` |
| Import module Android host tests | 33 passed; no failures or skips | `/tmp/chur-private-regression2.log` |
| Final iOS arm64 compilation and Android lint | Passed | `/tmp/chur-private-regression2.log` |
| Swift package tests | 80 passed | `/tmp/chur-pet-swift-tests.log` |
| Final Android pet, launcher, and Live Photo instrumentation | 22 passed; no failures or skips; 145.96 seconds | `/tmp/chur-private-android-regression2.log` |
| Android app and instrumentation APK assembly | Passed | `/tmp/chur-private-regression2.log` |
| Full iOS Simulator app, embedded extension, and UI-test build | Passed | `/tmp/chur-private-ios-ui23.log` |
| Android full private onboarding UI | 1 passed; 27.448 seconds | `/tmp/chur-private-android-final.log` |
| iOS full private onboarding UI | 1 passed; 146.970 seconds | `/tmp/chur-private-ios-ui23.log` |
| Backup exclusions and whitespace checks | Passed | `scripts/check-backup-rules.py`, `git diff --check` |

The first follow-up Android regression run had two launcher baseline failures
because the preceding full-journey test left discreet mode enabled. With the
documented fresh-install precondition, all 22 tests passed without weakening
assertions or changing launcher behavior.

The first follow-up host run exposed Android framework calls in the desktop JNI
reader. The reader now uses an explicit desktop FileChannel adapter and an
Android descriptor adapter with the same parser. The rerun passed; no test was
skipped and Android permission failures do not fall back to path reopening.

The last Kotlin/Native pass includes the retained-action security tests and the new guide dock, recovery confirmation, and discreet-trial tests. Earlier failed UIKit selectors were corrected to use a stable lock button tag; no security assertions were removed.

## Native UI evidence

The isolated Android API 37 emulator is `emulator-5560`. The isolated iOS 27 simulator is `ChurPetOnboarding20261022` (`AE6A4A93-2959-433E-BBDE-E5A88B6BB3C9`). No physical device was used.

Observed on iOS: the accessible Open access action opens the code form and Cancel returns to an awake pet. Reduced Motion shows a static sleeping pose with closed eyes; Treat and Toy are disabled until wake. Also observed: first-launch four-shell choice; transparent native character; real drag; moon hold without the sleep action; rejected code remaining in the form with cleared input; cancellation; public pet tutorial through greeting, accessible move, treat, toy, sleep, and wake. The native surface does not show the previous opaque white square.

Android instrumentation uses real pointer injection for drag and moon hold. It checks the composed screen for visible character pixels, not only a native texture. Pet tests use hardware composition, as the production host does; a software layer cannot validate TextureView composition.

The controller tests on Android and Kotlin/Native exercise creation and recovery confirmation with a real native core. Skipping the recovery tip leaves the mandatory phrase confirmation pending. The correct transition code reaches the locked authentication state; only successful authentication completes the discreet-mode trial.

The Android private UI test starts from the four-shell chooser and uses the real
activity, core, and DocumentsUI picker. It confirms the recovery phrase, cancels
one import, imports a PNG through a URI grant, keeps the original, opens the
saved item, searches, reviews Auto-lock/Access/backup, and completes the pet-code
trial through normal password authentication. It then verifies enabled discreet
mode, manual replay, and removal of private guidance after background/return.

The iOS XCTest follows the same creation and recovery-confirmation path. It
cancels the first Photos selection, imports the seeded PNG through the system
picker, keeps the original, opens the saved item, searches, and completes the
settings guidance and code trial. It verifies discreet mode, manual replay, and
removal of the private Import hint after Home and return. Result bundle:
`/tmp/chur-pet-ios/Logs/Test/Test-Chur-2026.10.22_20-54-12-+0400.xcresult`.
The test submits authentication with the keyboard Go action; an extra Unlock
tap after that action was removed from the test.

The iOS system icon confirmation can make the scene inactive after the trial
succeeds. Normal privacy locking remains in force; UI acceptance checks normal
re-entry after this system alert, rather than adding an onboarding exemption.

## Performance and acceptance limits

On Android, the native TextureView delivered 22 frames in 1.1 seconds (within the 30fps cap) and zero new frames in a 1.1-second background interval after draining pending work. Six dispose/reopen cycles passed visible-pixel checks. PSS samples in KiB were `189733, 197445, 191221, 194853, 195509, 196645, 193909`. The final sample is 4,176 KiB above the initial sample; the samples are not monotonic. This does not establish zero retained memory or a long-running leak result. Evidence: `/tmp/chur-pet-proof/android-runtime.txt`.

On iOS Simulator, six Settings-to-pet cycles changed process physical footprint from 93.7 MiB to 92.3 MiB. Peak footprint was 95.0 MiB. This is a bounded observation, not a long-running leak test. Local samples: `/tmp/chur-pet-proof/ios-memory-before-cycles.txt` and `/tmp/chur-pet-proof/ios-memory-after-cycles.txt`.

Measured final debug artifacts: Android APK 119,063,332 bytes; iOS Simulator app bundle 157,594,010 bytes (sum of regular files). These are different package formats and must not be compared as platform efficiency measurements.

The main private creation journey is complete on the Android emulator and iOS
Simulator. The alternate archive-restore branch was not run as a full UI journey;
no restore, backup export, or panic action was performed automatically.

Physical Android and iOS acceptance is still open. Full TalkBack/VoiceOver traversal, forced native renderer failure with PNG fallback, and sustained leak/thermal tests were not completed. iOS background GPU frame count was not measured; Android frame evidence must not be reported as iOS evidence.

The code supports resize, large type, unavailable targets, and static motion. Passing source, host, simulator, and numerical checks does not establish every physical display, assistive technology, or lifecycle path. No signed release package or exact pre-change package baseline was measured, so no release size delta is claimed.

## Reproduction

Use `DEVELOPMENT.md` for toolchain setup. Keep the existing build gate for heavy local builds. Use a synthetic test device. Relevant commands:

```sh
KOTLIN_COMPILER_LIB=/path/to/kotlin/lib tests/spirit/check-core.sh
KOTLIN_COMPILER_LIB=/path/to/kotlin/lib python3 tests/spirit/check-native-parity.py
./gradlew jvmTest testAndroidHostTest iosSimulatorArm64Test compileKotlinIosArm64 linkDebugFrameworkIosSimulatorArm64 :shared:feature-notes:churPublicShellIsolation :apps:androidApp:lintDebug --no-watch-fs
./gradlew :apps:androidApp:assembleDebug :apps:androidApp:assembleDebugAndroidTest --no-watch-fs
adb -s emulator-5560 shell am instrument -w -e class dev.po4yka.chur.android.PublicShellPresentationTest,dev.po4yka.chur.android.LauncherActionsTest,dev.po4yka.chur.android.LivePhotoAndroidTest dev.po4yka.chur.test/androidx.test.runner.AndroidJUnitRunner
swift test --package-path apps/iosApp
xcodebuild -project apps/iosApp/Chur.xcodeproj -scheme Chur -configuration Debug -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
python3 scripts/check-backup-rules.py
git diff --check
```

See the Private onboarding UI acceptance section in `DEVELOPMENT.md` for both full-journey commands. Install the freshly assembled app and test APK before instrumentation. Clear only the disposable app data before the launcher regression suite: the full onboarding test leaves discreet mode enabled, while the launcher baseline tests require a new installation. Local output under `/tmp` is temporary evidence and is not a published artifact.

Source review: TruffleHog passed and the P0 review reported no actionable findings (`/tmp/chur-onboarding-review-final3.log`). This review does not replace UI or device evidence.
