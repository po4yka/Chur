# Interactive Chur companion

> **Status:** Experimental — opt-in public artwork; native device approval pending

## Integration and controls

The public **Notes list** has a **Show Chur / Hide Chur** button. The initial state is off. It is composition-local, not persisted: leaving and recreating the list resets the opt-in and position. Calculator, Camera, the note editor, settings and private routes do not create the companion.

The 144dp rectangular target is movable within the Notes content. The controls row, app bar and bottom88dp action strip stay outside its bounds. An undersized area does not create a native renderer. The touch target deliberately includes transparent margins; this is not alpha-pixel hit testing. Tap greets, drag holds, release settles, cancellation returns idle. A second pointer, including simultaneous primary-up/secondary-down, cancels. Accessibility provides Greet, Hide and Reset position actions.

The surface is removed during search, the keyboard, trash, Notes-owned filter/purge dialogs, import processing and first-write disclosure. Other platform/global modal windows retain their normal touch precedence; this feature does not change modal or privacy-cover ordering. It must not be described as a general global-modal visibility observer.

Reduced Motion keeps direct dragging but uses an open-eyed static pose with no periodic blink, lean or tail animation. A successful static frame stops the display clock. Native lifecycle and hierarchy-visibility gates stop GPU rendering while inactive/hidden. Only an active attached view hidden by an ancestor uses a200ms visibility check to avoid losing the resume notification; pause/detach/disposal cancels it.

## Ownership and privacy

Common Compose owns the interaction reducer and optional public-list placement. Native adapters own renderer clocks, GLB decoding and GPU resources. Their only inputs are bundled artwork, clip names/serials, reduced-motion preference and normalized movement. No note, vault, controller, key, file path, telemetry or network request crosses this boundary. Existing Android/iOS native privacy covers are unchanged and remain above Compose.

No persisted/wire format, migration, permission, account, cryptographic primitive or FFI vault ABI changes. Private-state/logging invariants remain unchanged. The threat-model consideration is additional native parser code in the app process; it parses only one trusted, bundled asset, never imported/user-supplied glTF.

## Native implementations

Both standard application targets include Filament1.77.3. Android uses TextureView/transparent swapchain. The iOS checked-in Xcode project and its `project.yml` compile Swift→Objective-C++→Filament Metal; its prebuild script prepares the checksum-pinned official SDK and stages the selected platform's static archives. A missing model or renderer initialization failure uses the transparent PNG fallback. Fallback does not provide3D animation.

The adapters share authored coordinates (+Yup,+Zfront), origin camera, square orthographic safe bounds±1.125, warm100klux sunlight and no bloom/postprocessing. Narrow surfaces expand the frame rather than crop. They restore base transforms before sampling, snapshot the displayed authored pose for interruptions, apply a sole eye controller, then bounded tail offsets. The first two tail joints remain stiff. Native pixel parity is still a device gate, not established by matching constants.

## Dependency review

- **Capability/alternatives:** glTF skin/morph animation and transparent native rendering. Compose has no existing3D renderer. RealityKit/USDZ needs a separate iOS asset/parity path; WebView/game engines introduce a larger integration surface.
- **Owner/license:** Google Filament, Apache2.0; official release pinned to1.77.3. License text is bundled on both platforms. No claim of independent security audit of this release.
- **Native surface:** Filament, backend, gltfio and its SDK static dependencies (including mesh/texture codecs). No runtime shader compiler, downloaded model or user-provided parser input. Android uses engine+gltfio AARs, without filament-utils.
- **Build network/reproducibility:** Android uses Maven artifacts and generated Gradle locks. iOS preparation downloads only the official release URL and checks SHA-256 `40bc8e5a51040cc4b0bf8aaff7bf339ce16e13485644f60dbe1cb023b284fde7`; an existing matching archive can be supplied through `FILAMENT_ARCHIVE`. SDK/cache files are ignored and not committed.
- **Targets/size:** Android arm64/x86_64 and iOS arm64/device+Simulator through the app's existing platform baselines. Downloaded Android AARs total about10.7MiB across all included ABIs; the official iOS SDK is much larger than the linked app delta. Final package-size, memory, thermal and frame-time impact are unmeasured.
- **Updates/removal:** keep SDK/runtime/tool versions in sync; recheck asset contract, locks, native builds, notices/advisories and device behavior. Removing the feature removes both native adapters/dependencies, prebuild staging, bundled art and Notes control.

The GLB/PNG are intentional product artwork, not compiled application binaries or test recordings. GLB SHA-256: `feb2a8450932520e63673a4b07cc4d5c37f1170cb7bafe9a880683a6582fa429`. The adjacent manifest records clip/rig/eye/tail constraints. Nine authored clips are present; this UI uses idle/tap/hold/release. Heavy cinematic scenes are excluded.

## Verification and remaining gates

Local cloud checks cover2,634 Kotlin assertions,10,110 actual Kotlin/C++ clip/eye/tail sample pairs at30/60/120Hz, partial Android API signatures, and C++ syntax against exact Filament headers. The math pairs differ by at most1.1e-7. Dependency graphs are resolved with generated locks. None is a substitute for compiling Compose/the APK or Swift/UIKit/the app.

An independent actual-vertex skin/morph sweep covered1,377 clip/tail/±12°lean combinations within the common square frame; minimum margin is approximately4.6pixels at144pixels. Sixty-six Cycles-rendered diagnostic poses showed no obvious eye/silhouette/alpha defect. Those are asset diagnostics, not Filament screenshots or correctly timed live gesture recordings. Dark-tail contrast on dark backgrounds still needs native pixel review.

The PR adds an Android presentation test covering default-off, opt-in/hide and suppression during search/disclosure/dialogs, plus128 pure presence-policy cases. Existing CI assembles instrumentation; it does not execute those device tests. The macOS job now compiles the actual Xcode app/Metal bridge in addition to Kotlin/Native checks.

Local Android SDK installation remains blocked on agreement approval; Linux has no Apple SDK. CI results must be reported separately for the exact PR head. Before release, run synthetic-data device checks for live drag/cancel/repeat/multitouch, keyboard/modal/privacy transitions, accessibility, fallback, pixel transparency/color and performance.

Commands:

- `KOTLIN_COMPILER_LIB=/path/to/kotlin/lib tests/spirit/check-core.sh`
- `KOTLIN_COMPILER_LIB=/path/to/kotlin/lib python3 tests/spirit/check-native-parity.py`
- `./gradlew -I tests/spirit/verify-dependencies.gradle :shared:app:verifySpiritDependencies :apps:androidApp:verifySpiritDependencies`
- Standard project builds/tests from `DEVELOPMENT.md`; Apple setup in `apps/iosApp/SpiritRenderer/README.md`.

Official sources: https://github.com/google/filament/tree/v1.77.3 and https://repo.maven.apache.org/maven2/com/google/android/filament/filament-android/1.77.3/

### Distribution notices

Both applications bundle the Filament Apache-2.0 license and a conservative
runtime dependency notice inventory taken verbatim from the pinned upstream
v1.77.3 source (including codecs and material helpers). Each inventory entry
records its upstream path and source-content SHA-256. Review the inventory
when changing the SDK version or linked native archives.
