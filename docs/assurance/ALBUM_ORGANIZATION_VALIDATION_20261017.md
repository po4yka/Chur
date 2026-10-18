# Album organization validation — 2026-10-17

## Result and scope

The six confirmed album audit defects are fixed in the local working tree. Three sub-agents worked on synchronization, catalog integrity, and the shared UI. No commit, push, publication, or deployment was performed.

The checkout contained unrelated work before and during this task. Media import, playback, viewer animation, Swift import, and back-navigation changes belong to other workstreams. Their changes were preserved.

| Defect | Change and regression evidence |
| --- | --- |
| Album edits did not enter the own-sync log, and received edits did not update catalog rows | Create, rename, add, and remove now update the catalog and signed outbox in one transaction. Receive projects authenticated operations into the catalog. Tests cover rollback, restart, concurrent renames, duplicate legacy bootstrap, and late object download. |
| Select all implied a larger scope than the loaded page | Android and iOS use one loaded-object selection helper. The action says **Select loaded items**. Tests cover 128, 256, and 300 loaded objects and exclude objects that were not fetched. |
| Removing a selection could partially commit | One batch operation validates and removes the complete selection in a transaction. Invalid members preserve rows, catalog generation, and the signed-log head. Android JNI, iOS C ABI, and host tests also verify original bytes, other albums, and restart. |
| Move destinations with the same name were ambiguous | The picker shows full paths, supports search, excludes the moved subtree, and disambiguates identical full paths. Tests cover corrupt paths, duplicate names, and a long destination list. Android presentation tests run at 320 dp and double text size. |
| Album counts included quarantined objects that the page hid | Counts now apply the same visibility condition as the album page. Catalog regression tests cover the mismatch. |
| Re-adding an existing member failed when the album reached its member limit | Existing membership is checked before quota enforcement. The operation remains idempotent at the limit. |

An additional integration defect was fixed: sync could change catalog rows while the open album list and object page stayed stale. Manual sync, automatic unlock pull, and staged receive now refresh catalog projections. Session and query guards reject results from an obsolete session. Four native-backed host regressions cover the refresh paths, including partial sync failure.

## Storage and protocol changes

- Catalog version 11 stores pending local membership additions until the object's immutable sync history exists. Migration seeds existing memberships. Tests cover fresh catalogs, old-version migration, interrupted migration, restore, and future-version refusal.
- A cached bootstrap and targeted reconciliation avoid a complete album scan for every edit. Pending rows survive restart and are removed atomically with membership changes.
- Identical concurrent legacy album declarations now converge. Conflicting names or collections and causal identifier reuse still fail authentication. A late initial declaration cannot overwrite an explicit rename. The wire encoding did not change.
- ABI 2.25 adds `chur_album_remove_objects`. The C header, JNI bridge, Kotlin/Native bridge, capability handshake checks, and contract documentation agree.

## Observed validation

All results below were observed locally. CI and physical-device acceptance were not run.

| Check | Observed result |
| --- | --- |
| Rust workspace, locked dependencies | **811 passed**, 0 failed, 0 ignored, 0 filtered |
| Rust formatting and Clippy, all targets with warnings denied | Passed |
| C ABI compile, link, and handshake against the current static library | Passed, ABI 2.25 |
| Android host tests: core-ffi, core-vault, core-sync, app | **444 passed**, 0 failures, errors, or skipped tests |
| Android app and instrumentation APK build; Android lint | Passed |
| Android API 37 emulator instrumentation: native album, album navigation, album export | **9 passed** |
| iOS Simulator app build and launch, iOS 27.0 | Passed |
| iOS native album regression with the real C ABI and SQLCipher | **1 passed**; included in the complete iOS run below |
| Complete shared-app iOS Simulator test suite | Follow-up: **231 passed**, 0 failures, errors, or skipped tests |
| Live Android-to-iOS network synchronization | Follow-up: **22 mobile process phases passed**; final snapshots match |
| Kotlin compilation for the iOS device target, `compileKotlinIosArm64` | Passed |
| iOS manual UI smoke | Created a disposable vault and album, opened Albums and Move album; inspected the rendered dialog |
| Structured closeout review | No P0 findings; this review reports blockers only |
| `git diff --check` | Passed |

The initial iOS suite failure was
`PrivatePlayerIosTest.native_embedded_audio_and_subtitles_can_be_selected_and_subtitles_can_be_disabled`.
AVFoundation on iOS 27 reports an additional forced-subtitle presentation for the single `mov_text` track. The media workstream correction selects the regular presentation, requires exactly one regular option, and retains selection, disable, and wrong-group checks. The follow-up sub-agent verified the existing correction with the complete iOS suite: **231/231 passed**. No extra production player change was needed. Evidence: `/tmp/chur-album-subtitle-ios-tests.log` and `/tmp/chur-album-subtitle-ios-results/` (53 XML reports).

The first new Android selection presentation test used an inconsistent synthetic screen state and selected another menu. Its fixture now uses the Library screen and loaded tiles; the count and scope assertions were retained. The final complete nine-test instrumentation run passed.

### Reproduction commands

Run Rust commands from `rust/`:

```sh
CARGO_TARGET_DIR=/tmp/chur-album-sync-target cargo test --locked --workspace
cargo fmt --all --check
CARGO_TARGET_DIR=/tmp/chur-album-sync-target cargo clippy --locked --workspace --all-targets -- -D warnings
CARGO_TARGET_DIR=/tmp/chur-album-sync-target cargo build --locked -p chur-ffi
```

Run Gradle commands from the repository root. The isolated project cache avoids collisions with parallel Gradle work:

```sh
./gradlew --no-configuration-cache --project-cache-dir /tmp/chur-album-gradle-cache \
  :shared:core-ffi:testAndroidHostTest :shared:core-vault:testAndroidHostTest \
  :shared:core-sync:testAndroidHostTest :shared:app:testAndroidHostTest \
  :apps:androidApp:assembleDebug :apps:androidApp:assembleDebugAndroidTest \
  :apps:androidApp:lintDebug --console=plain

adb -s emulator-5558 shell am instrument -w -r \
  -e class dev.po4yka.chur.android.AlbumOrganizationNativeTest,dev.po4yka.chur.android.AlbumNavigationPresentationTest,dev.po4yka.chur.android.AlbumExportPresentationTest \
  dev.po4yka.chur.test/androidx.test.runner.AndroidJUnitRunner

CHUR_IOS_TEST_DEVICE=5CD826E3-3408-44D8-A55F-98CE0C42D7D7 \
  ./gradlew --no-configuration-cache --project-cache-dir /tmp/chur-album-gradle-ios-cache \
  :shared:app:iosSimulatorArm64Test --console=plain
./gradlew --no-configuration-cache --project-cache-dir /tmp/chur-album-gradle-ios-cache \
  compileKotlinIosArm64 --console=plain
```

The observed Gradle runs also used `/tmp/chur-album-validation.init.gradle` to separate test reports from parallel runs. It changes report destinations only.

### Local evidence

- `/tmp/chur-album-rust-workspace.log`
- `/tmp/chur-album-clippy.log`
- `/tmp/chur-album-c-abi.log`
- `/tmp/chur-album-android-final.log`
- `/tmp/chur-album-android-test-rebuild.log`
- `/tmp/chur-album-android-instrumentation-final.log`
- `/tmp/chur-album-ios-tests.log`
- `/tmp/chur-album-ios-device-compile.log`
- `/tmp/chur-album-test-results/_shared_app/iosSimulatorArm64Test/`
- `/tmp/chur-album-autoreview-final.json`
- `/tmp/chur-album-ios-move-dialog.jpg`
- iOS app: `/tmp/chur-album-validation-ios/Build/Products/Debug-iphonesimulator/Chur.app`

Temporary evidence paths are local and can be removed by normal system cleanup.

## Live Android-to-iOS follow-up

The follow-up used Android API 37 on `emulator-5558`, iOS 27.0 on Simulator
`5CD826E3-3408-44D8-A55F-98CE0C42D7D7`, and the real Rust `chur-sync-server`
on host loopback port 17880. Android reached it through `adb reverse`.
Both runtimes used `VaultRepository`, `RepositorySyncBoundary`,
`FileSyncStateStore`, and `SyncCoordinator` with its default platform HTTP
client. No mock engine, HTTP security exception, TLS trust override, or
production network-policy change was used.

The host transferred only the initial encrypted backup, source public device
ID, pairing request, and pairing approval during setup. Album operations,
object key requests and grants, and encrypted originals crossed the actual
HTTP server. Final catalog snapshots were copied to the host only for comparison.

The acceptance entry points are:

- `scripts/test-album-live-sync.py`: starts and stops only its temporary relay and its own `adb reverse` mapping.
- `shared/app/src/liveSyncTest/kotlin/dev/po4yka/chur/app/AlbumLiveSyncScenario.kt`: common assertions and phase sequence.
- `apps/androidApp/src/androidTest/kotlin/dev/po4yka/chur/android/AlbumLiveSyncDeviceTest.kt`: Android instrumentation phases.
- `shared/app/src/iosTest/kotlin/dev/po4yka/chur/app/AlbumLiveSyncIosTest.kt`: iOS native phases.

The two entry-point tests are opt-in. A default run without the phase arguments
prints `NOT RUN`; that result is not live-sync evidence. The reported live
result below comes from all explicit phases with real network access.

| Scenario | Observed result |
| --- | --- |
| Bootstrap, encrypted backup restore, and device pairing | Passed; the paired iOS device has a distinct device ID |
| Android creates an album and adds an original before its first upload | iOS receives the album and original; exact content and counts match |
| iOS renames that album, creates a second album, and imports another original | Android receives both album edits and the original; exact content matches |
| Android renames the iOS album, removes the first original from the first album, and adds the second original | iOS receives the new name and membership; originals and the retained album remain intact |
| iOS removes the second original from the first album | Android observes an empty album; both originals and other membership remain intact |
| Relay stopped; both devices rename, create, and change membership offline | Local changes persist; sync returns failure and reports network unavailability; each device still sees the older remote state |
| New processes open the same roots while the relay is still stopped | Both offline snapshots remain exact; a failed retry does not change the catalog |
| Relay restarted with the same data directory; devices reconnect | All five album IDs, names, counts, and member IDs match across devices; both originals remain readable |

There were **22 successful phases in 22 distinct mobile processes**:
12 Android processes and 10 iOS processes. The PID check is recorded in
`evidence-summary.json`. Every phase closed its repository; the next phase
opened the same root with a new repository and a new durable sync-state store.
The offline check stopped the configured relay; it did not disable the devices'
other network interfaces.

Final state on both devices:

| Album | Members |
| --- | --- |
| Android offline | Android original |
| iOS offline | Android original |
| Retained originals | Android original and iOS original |
| Android offline created | Empty |
| iOS offline created | Empty |

Both final sync statuses say `Up to date.`. Both final snapshot SHA-256 values
are `2cd08efdbc106e3ae1c5212c85e0a5166361df06439829fc636fcfd95afe1877`.
The relay process was stopped after the run; the temporary `adb reverse`
mapping was removed. Fixture data and logs were retained for inspection.

The initial acceptance attempt requested a normal identity immediately after
restore. Restored identity is recovery-only until pairing finishes. The driver
now compares device IDs after successful pairing. No production identity or
restore behavior was changed to bypass this rule.

Build and run from the repository root:

```sh
./gradlew --no-configuration-cache --project-cache-dir /tmp/chur-album-live-gradle \
  :apps:androidApp:assembleDebug :apps:androidApp:assembleDebugAndroidTest \
  :shared:app:linkDebugTestIosSimulatorArm64 --console=plain
python3 scripts/test-album-live-sync.py \
  --android-serial emulator-5558 \
  --ios-simulator 5CD826E3-3408-44D8-A55F-98CE0C42D7D7 \
  --server-binary /tmp/chur-album-sync-target/debug/chur-sync-server
```

The server binary was built with
`CARGO_TARGET_DIR=/tmp/chur-album-sync-target cargo build --locked -p chur-sync-server`
from `rust/`.

Evidence:

- `/tmp/chur-album-live-run-final.log`: all 22 phase outcomes, exit status zero.
- `/var/folders/z7/0w5jl61d0qq811sptzvn99n5ww3hp6/T/chur-album-live-md93rm54/`: per-phase logs, final snapshots, and `evidence-summary.json`.
- `/tmp/chur-album-live-rebuild.log`: both current test artifacts built successfully.
- `/tmp/chur-album-live-autoreview.json`: structured review found no P0 defect in the six-file acceptance change.
- `python3 -m py_compile scripts/test-album-live-sync.py` and `git diff --check`: passed.

## Remaining boundaries

- Parent relationships, manual album order, and album deletion remain local under the existing album-navigation contract. Cross-device tree synchronization needs a separate protocol design and migration.
- A membership for media received through an external shared collection cannot become an own-vault membership operation without an own-vault object declaration. Its durable local intent remains pending. This change does not expand access rights.
- Live mobile native runtimes and the real loopback HTTP relay are covered above. The test calls the production repository and sync coordinator directly; it does not automate the complete Settings/pairing UI.
- Mixed-version convergence needs validation: an older client can reject identical concurrent legacy album declarations that the updated materializer accepts. The signed wire format is unchanged, but the conflict-resolution rule changed.
- The Android checks used an API 37 emulator. The iOS checks used an iOS 27 Simulator. Physical devices and a remote TLS deployment were not tested.
- Remaining acceptance work is physical-device and mixed-version interoperability, remote TLS transport, and the full pairing UI journey.
