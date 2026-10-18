# Native Liquid Glass and Android glass rendering in Chur

> **Status:** Research snapshot. The recommended UIKit / Haze 2 approach was selected for implementation; this research alone does not establish runtime or device acceptance.

Date: 2026-10-18. Local snapshot: `main`, `560e185`; working tree initially clean. Two research sub-agents investigated iOS and Android independently. Primary platform documentation, upstream implementation sources, release metadata, and Context7 documentation were consulted. Repository artifacts remain in English.

## Recommendation

Keep KMP, the Rust security boundary, shared application state, and Compose screen content. Introduce platform-specific navigation and control presentation:

- **iOS:** native UIKit navigation bars, tab bars, toolbars, and selected controls. These receive Apple's actual Liquid Glass on iOS 26+. Chur already has a UIKit host, so a UIKit coordinator is a better starting point than introducing a SwiftUI application shell solely for this feature.
- **Android:** retain Compose controls and implement the optical material through Android graphics APIs. Evaluate **Haze 2 Glass** first and **Kyant Backdrop** as the comparison candidate. A native rendering backend does not make these controls an OS-owned Apple material.
- Keep glass primarily on navigation and floating controls. Preserve ordinary content surfaces, legibility, and opaque authentication/privacy covers. Do not add glass to every card or private thumbnail.

The [official JetBrains migration guide](https://kotlinlang.org/docs/multiplatform/ios-liquid-glass.html) demonstrates native navigation around shared Compose content, including a Kotlin-driven UIKit alternative. This is a supported architecture; KMP does not require identical rendering on both platforms.

There is no public Android API identified in this research that supplies Apple's complete named Liquid Glass material. Android offers system rendering primitives, including a new native backdrop operation on full SDK **37.2**. Reproducing the optical effect remains an application/library responsibility. [Android AGSL overview](https://developer.android.com/develop/ui/views/graphics/agsl), [RenderNode API](https://developer.android.com/reference/android/graphics/RenderNode#setBackdropRenderEffect(android.graphics.RenderEffect)).

## Fit with the current application

| Observed source | Implication |
| --- | --- |
| [`gradle/libs.versions.toml`](../../gradle/libs.versions.toml) pins Kotlin 2.4.21, CMP 1.12.1, iOS deployment 18.0, Android minSdk 29 and compile/targetSdk 37 | Preserve the supported-device set. Gate newer effects by runtime capability; a glass feature does not itself justify increasing minimum OS versions. |
| [`IosEntryPoint.kt`](../../shared/app/src/iosMain/kotlin/dev/po4yka/chur/app/IosEntryPoint.kt) wraps a single `ComposeUIViewController` in `ChurRootViewController` | UIKit already owns the host, while Compose currently owns the rendered application. |
| [`VaultShell.kt`](../../shared/app/src/commonMain/kotlin/dev/po4yka/chur/app/vault/VaultShell.kt) uses Material `Scaffold`, `TopAppBar`, `NavigationBar`, and selection bars | A theme or dependency switch cannot turn the current bars into native iOS widgets. Extract chrome from content at this seam. |
| [`ChurController.kt`](../../shared/app/src/commonMain/kotlin/dev/po4yka/chur/app/ChurController.kt) exposes shared state and commands; substantial vault assembly also lives in [`IosRoutes.kt`](../../shared/app/src/iosMain/kotlin/dev/po4yka/chur/app/IosRoutes.kt) | Reuse the existing controller. Expose bounded screen/chrome projections and actions rather than duplicating business logic or exporting private repositories to Swift. This is more than an isolated visual modifier. |
| [`DESIGN.md` §25.3](../../DESIGN.md#253-component-ownership) currently assigns ordinary navigation to shared Compose | Native iOS chrome requires an explicit change to that ownership contract. This research proposes the change and does not silently supersede the current specification. |
| [`IosPrivacyCover.kt`](../../shared/app/src/iosMain/kotlin/dev/po4yka/chur/app/IosPrivacyCover.kt) adds an opaque view to the window; Android has secure-window integration | All new chrome, controllers, popups, and effect layers must remain within the existing privacy lifecycle. |

## Architecture choices

| Approach | Actual native iOS glass? | Cost and assessment |
| --- | --- | --- |
| UIKit shell/coordinator with Compose screen content | Yes, for native bars and explicitly native controls | **Recommended.** Moderate navigation refactor; fits the current host and avoids a new SwiftUI path-state layer. |
| SwiftUI `TabView` / `NavigationStack` with Compose content | Yes | Good alternative when SwiftUI ownership is desired. Adds Swift wrappers and state bridging here; no requirement to rewrite screen bodies. |
| Compose navigation with selected native interop controls | Yes, component by component | Smaller initial scope, but overlay ordering, layout, focus, and stack behavior need explicit ownership. Useful for an isolated native toolbar. |
| Entire iOS UI in SwiftUI, sharing KMP logic only | Yes | Largest refactor and duplicated presentation work. Appropriate if native behavior throughout every screen is a separate product goal; unnecessary just to obtain native chrome. |
| Shared Compose shader material on both platforms | No on iOS | Shares more drawing code but does not meet the requested native Apple implementation. Useful only for Android or intentional approximations. |

UIKit can host Compose controllers and Compose can embed UIKit views; Swift is optional for UIKit interop. SwiftUI can likewise be hosted through `UIHostingController`. [JetBrains UIKit integration](https://kotlinlang.org/docs/multiplatform/compose-uikit-integration.html), [SwiftUI integration](https://kotlinlang.org/docs/multiplatform/compose-swiftui-integration.html).

## iOS: use the operating system's implementation

Prefer existing native navigation/tab/toolbar components. For a custom surface, use `UIVisualEffectView` with **`UIGlassEffect`**. Group related native glass surfaces with **`UIGlassContainerEffect`**; native foreground labels/buttons belong in the effect view's `contentView`. Both effect classes were introduced in iOS 26. SwiftUI alternatives include `.glassEffect`, `GlassEffectContainer`, and native glass button styles. Apple's adoption guidance distinguishes navigation/control material from content presentation. [UIGlassEffect](https://developer.apple.com/documentation/uikit/uiglasseffect), [UIGlassContainerEffect](https://developer.apple.com/documentation/uikit/uiglasscontainereffect), [UIKit adoption session](https://developer.apple.com/videos/play/wwdc2025/284/), [Liquid Glass adoption](https://developer.apple.com/documentation/technologyoverviews/adopting-liquid-glass).

Keep iOS 18 deployment support: ordinary native bars use the style available on the running OS; instantiate iOS 26 effect APIs only with availability protection. Build against an SDK that includes the selected APIs. Whether Chur's Kotlin/Native platform libraries expose each new UIKit symbol needs a compile check during implementation. A narrow Swift factory is an option if the required symbol is unavailable through Kotlin bindings; this research has not established that such a factory is needed.

### Compositing and interaction constraints

The statement “native glass cannot sample Compose” is too broad. CMP has experimental `UIKitInteropProperties(placedAsOverlay = true)` support specifically for transparent native views and native shader effects above the Compose canvas. However, this places the native view above **all** Compose pixels in the overlapping area, including composables nominally drawn later. Keep an embedded glass control's foreground native instead of assuming a Compose label can appear above its native background. [CMP release documentation](https://kotlinlang.org/docs/multiplatform/whats-new-compose-110.html#overlay-placement-for-interop-views), [interop implementation](https://github.com/JetBrains/compose-multiplatform-core/blob/jb-main/compose/ui/ui/src/iosMain/kotlin/androidx/compose/ui/viewinterop/UIKitInteropProperties.ios.kt).

A native shell avoids depending on interop overlays for the entire navigation system. It still needs correct safe-area handling, content extension underneath bars, keyboard insets, and hit testing. Native scroll-edge/minimization behavior is a separate acceptance item: a Compose `LazyColumn` is not automatically a native `UIScrollView` relationship. Verify interactive-back cancellation and transitions rather than inferring them from a screenshot. The [DroidKaigi 2026 implementation note](https://github.com/DroidKaigi/conference-app-2026/blob/main/docs/ios-liquid-glass.md) supplies useful native-tab-over-Compose implementation evidence, but does not validate Chur.

Do not maintain independently mutable native and Compose navigation histories. Define one owner and explicit events for tab selection, push/pop completion, cancelled gestures, and session invalidation. Persist no private titles, routes, or selected object projections after lock. Native controllers can retain those values even when Compose has disposed its screen.

## Android: capabilities by OS version

| Runtime tier | System capability | Proposed Chur appearance |
| --- | --- | --- |
| API 29–30 | No `RenderEffect` / `RuntimeShader` | Opaque or tinted material. Avoid adding CPU blur solely to make old devices visually identical. |
| API 31–32 | `RenderEffect` blur and filter chains | Blur/tint with an explicit source; reduced optics. No AGSL lens refraction. |
| API 33+ | `RuntimeShader` / AGSL and runtime-shader effects | Full library-defined lens/refraction, highlights, tint, and blur. A library normally supplies captured background input. |
| Full SDK 37.2 | `RenderNode.setBackdropRenderEffect` | Experimental native same-window backdrop path where supported; preserve a legible alternative when unavailable. Major API 37 alone is insufficient. |

`RenderEffect` ordinarily filters its own render-node contents; it does not automatically obtain the pixels behind an arbitrary control. Window blur is another facility, intended for cross-window effects, and is not a replacement for per-control optics. [RenderEffect reference](https://developer.android.com/reference/android/graphics/RenderEffect), [AGSL reference](https://developer.android.com/develop/ui/views/graphics/agsl), [window-blur guidance](https://source.android.com/docs/core/display/window-blurs).

The official Android 17 QPR2 page labels that release beta at the time of research. Treat 37.2 as a specifically gated newer capability, rather than a universal Android 17 feature. [Android 17 QPR2](https://developer.android.com/about/versions/17/qpr2).

### The new system backdrop does not remove all integration work

Haze 2.0.1 has an experimental, default-off native backend. Its pinned renderer uses reflection to access the new method and full-SDK runtime gating; consuming this release does **not intrinsically require** changing Chur's compileSdk 37 to 37.2. A direct app call to the API would need the corresponding SDK. Hardware acceleration, compatible effects, attachment-time configuration, and fallback remain relevant. [Pinned renderer](https://github.com/chrisbanes/haze/blob/2.0.1/haze/src/androidMain/kotlin/dev/chrisbanes/haze/HazeBackdropRenderer.android.kt), [build versions](https://github.com/chrisbanes/haze/blob/2.0.1/gradle/build-logic/convention/src/main/kotlin/dev/chrisbanes/gradle/Versions.kt).

Native backdrop samples pixels already drawn earlier in the same window surface. It cannot apply a fallback source's selection filters, include later drawing, or cross another window/surface. Source-backed rendering is therefore still useful where exact input selection matters. [Pinned Haze input contract](https://raw.githubusercontent.com/chrisbanes/haze/2.0.1/docs/core-concepts.md).

Upstream evidence is inconsistent: some release documentation leaves physical 37.2 acceptance as a separate gate, while the pinned benchmark document reports physical Pixel 8a 37.2 measurements and native trace counters. Describe this backend as experimental with published physical benchmark evidence, not as never tested. In that sample native glass used less peak GPU memory but more CPU time than source-backed glass; native is not an automatic performance win. Neither measurement establishes Chur's frame rate. [Pinned benchmark record](https://raw.githubusercontent.com/chrisbanes/haze/2.0.1/docs/benchmark-results.md).

## Open-source comparison

Versions below are observed releases, not automatic dependency recommendations. Main-branch features and repository activity do not prove released API compatibility or future maintenance.

| Project | Observed release / license | What is actually implemented | Fit for Chur |
| --- | --- | --- | --- |
| [Calf](https://github.com/MohamedRejeb/Calf/releases/tag/v0.14.0) | v0.14.0, 2026-09-13; [Apache-2.0](https://github.com/MohamedRejeb/Calf/blob/v0.14.0/LICENSE) | Adaptive iOS navigation bar creates a real UIKit `UITabBar`; individual controls can have different implementations. | Best selective-wrapper candidate; compare direct UIKit ownership before taking a broad UI dependency. |
| [Compose Cupertino](https://github.com/alexzhirkevich/compose-cupertino) | Latest GitHub release observed: 0.1.0-alpha04, 2024-04-07; [Apache-2.0](https://github.com/alexzhirkevich/compose-cupertino/blob/f66875aa6f3848b30c38e42a89fc99c9ac24a585/LICENSE.txt) | Separate Compose-drawn and native modules; native navigation wrappers exist. Inspected baseline is Kotlin 2.0.20 / CMP 1.7.0. | Useful prior art; older baseline and no confirmed dedicated Liquid Glass layer make it a weaker foundation. |
| [Haze](https://github.com/chrisbanes/haze/releases/tag/2.0.1) | 2.0.1, 2026-09-29; Apache-2.0 | Haze 2 adds experimental `haze-glass`, refraction/depth blur/lighting and explicit Content/Sources/Backdrop inputs; native 37.2 backend is opt-in. | **First Android evaluation candidate.** Broader input/lifecycle controls are useful for a vault. Its iOS renderer is not Apple's material. |
| [Kyant Backdrop / AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) | 2.0.1, 2026-08-26; Apache-2.0 | Compose optics with blur, vibrancy, lens, and highlights; samples demonstrate controls. No high-level control kit or 37.2 native backdrop backend established by the inspected sources. | **Comparison candidate** for optical quality and grouping. Choose one renderer after evaluation. |
| [Cloudy](https://github.com/skydoves/Cloudy) | Latest release observed: 0.7.1, 2026-07-12; Apache-2.0 | Blur toolkit; main also documents backdrop/lens APIs under 1.0.0-alpha01. Release and main feature sets must be distinguished. | Alternative if blur is the main need; verify artifact-specific functionality before adoption. |
| [Liquid](https://github.com/FletchMcKee/liquid) | 1.1.1, 2026-01-18; Apache-2.0 | Focused source/effect modifiers with API-dependent optics and documented rendering constraints. | Smaller alternative; evaluate only if the first candidates fail a concrete requirement. |

The most important Calf caveat is source-confirmed: its [native navigation bar](https://github.com/MohamedRejeb/Calf/blob/v0.14.0/calf-ui/src/iosMain/kotlin/com/mohamedrejeb/calf/ui/navigation/AdaptiveNavigationBar.ios.kt) uses UIKit, while its [iOS LiquidGlassButton](https://github.com/MohamedRejeb/Calf/blob/v0.14.0/calf-ui/src/iosMain/kotlin/com/mohamedrejeb/calf/ui/button/LiquidGlassButton.kt) uses Compose and Kyant Backdrop. “Adaptive” and “LiquidGlass” names do not prove native implementation. Calf's [compatibility table](https://mohamedrejeb.github.io/Calf/ui/) maps 0.14.0 to Kotlin 2.4.20 / CMP 1.12.0, close to Chur's pins but still requiring resolution and compilation checks.

Backdrop distinguishes installability from effect availability: its Android checks require 31 for RenderEffect and 33 for runtime shaders; lens optics are unavailable without runtime shaders. [Capability checks](https://github.com/Kyant0/AndroidLiquidGlass/blob/kmp/backdrop/src/androidMain/kotlin/com/kyant/backdrop/Platform.kt), [lens implementation](https://github.com/Kyant0/AndroidLiquidGlass/blob/kmp/backdrop/src/commonMain/kotlin/com/kyant/backdrop/effects/Lens.kt). Other floors and constraints are documented in [Cloudy](https://github.com/skydoves/Cloudy) and [Liquid limitations](https://fletchmckee.github.io/liquid/limitations/).

No production dependency was added. Adoption must preserve notices and follow Chur's [dependency policy](../DEPENDENCY_POLICY.md), including transitive/native footprint, target compatibility, privacy, binary size, and measured cost. Do not ship multiple glass engines without a demonstrated need.

## Privacy, media, accessibility, and performance

These are feature constraints arising from Chur's existing contracts, not a reason to replace the requested capability with a refusal:

1. **Lock remains opaque and immediate.** Glass, blur, or mosaic is not concealment. Never place private media behind authentication/recovery fields, even blurred. Cover the scene before transitions; dispose native stacks, private titles, selections, effects, and source layers on session invalidation. [Chur design](../../DESIGN.md), [plaintext lifecycle](../security/PLAINTEXT_LIFECYCLE.md).
2. **Account for retained pixels.** Haze offers `ClearWhenUnavailable` for sensitive source inputs; retained-last-frame behavior is inappropriate at a private/public boundary. Native backdrop cannot use fallback-source filters to exclude sensitive regions. Reset the whole session-scoped renderer, not just the visible composable. This does not establish secure erasure of OS/GPU memory. [Haze input contract](https://raw.githubusercontent.com/chrisbanes/haze/2.0.1/docs/core-concepts.md).
3. **Keep secure media secure.** A `SurfaceView` is a separate surface; capturing a Compose source does not capture its video pixels. Native backdrop also has surface/window boundaries. Chur's Android player uses `PlayerView`, and iOS uses native player layers. Inspect these actual paths before promising glass over playing video. Use a legible scrim where sampling is unavailable rather than weakening protected playback. [Haze camera scenario](https://chrisbanes.github.io/haze/latest/scenarios/camera/), [PixelCopy protected-source restriction](https://developer.android.com/reference/android/view/PixelCopy#ERROR_SOURCE_INVALID).
4. **Preserve screenshot protection.** `FLAG_SECURE` protects system screenshots/non-secure displays; it does not establish that application-owned effect caches retain no pixels. Do not use screenshot/MediaProjection capture or remove secure-window flags for this effect. [Android secure-activity guidance](https://developer.android.com/security/fraud-prevention/activities).
5. **Preserve accessibility behavior.** Respect Reduce Transparency, Reduce Motion, contrast, Dynamic Type/font scale, VoiceOver/TalkBack, focus, and target size. Native glass backgrounds do not automatically convert Compose semantics into native controls. Haze's custom glass requires the app to map accessibility preferences explicitly. Existing Chur iOS motion handling observes Reduce Motion; transparency needs separate handling. [JetBrains iOS accessibility](https://kotlinlang.org/docs/multiplatform/compose-ios-accessibility.html), [Haze glass documentation](https://raw.githubusercontent.com/chrisbanes/haze/main/docs/effects/glass/index.md).
6. **Measure bounded surfaces.** Restrict blur/refraction to visible chrome, share appropriate source captures, and avoid full-screen multi-pass effects per tile. Measure frame timing, thermal behavior, memory, and lock disposal on real hardware. Simulator appearance and upstream benchmarks do not satisfy Chur's [performance budgets](../assurance/PERFORMANCE_BUDGETS.md).

## Implementation sequence and completion bar

This is a proposed follow-up, not work performed by this research.

1. **Deliver one real navigation slice.** Separate `VaultShell` content from bars and selection chrome; expose its current state/actions to a platform presenter. Implement Library/Albums native tabs and an album push/pop on iOS, retaining the actual shared content and lock commands. Extend the current design ownership contract in the same change. Avoid a parallel demo that never connects to Chur.
2. **Complete native iOS behavior.** Integrate the remaining tabs, contextual selection actions, menus, viewer controls where appropriate, and single-owner navigation events. Keep private projections session-scoped. Use ordinary native styling on iOS 18 and native glass on 26+; verify 26-symbol compilation rather than guessing binding support.
3. **Deliver the Android material on the same surface.** Compare Haze 2 Glass with Backdrop using identical live content. Select one dependency. Exercise API 29/31/33 tiers and explicitly choose whether experimental 37.2 native sampling is enabled. If “system backdrop only, no source capture” is mandatory, limit full optics to capable 37.2 devices and use a simpler material elsewhere.
4. **Observe acceptance on the integrated app.** Check scrolling thumbnails, album navigation, selection mode, empty/error states, IME, rotation, pop cancellation, video/audio, light/dark and bright/detailed backgrounds. Verify accessible controls and readable content with reduced transparency/motion and large text.
5. **Verify the private/public boundary.** Lock during scrolling, transitions and playback; switch real/decoy sessions; background and resume; inspect switcher appearance, stale labels, accessibility, and retained source output. Confirm no private frame returns during the next public-shell render. Measure on a mid-range API 33 device and capable 37.2 hardware, plus physical iOS 26+; retain baseline OS coverage.

The first useful deliverable is a working native iOS navigation slice plus the corresponding Android material, with observed interaction and lock behavior. A dependency declaration or screenshot alone is insufficient.

## Evidence boundaries

This task produced research and an architecture recommendation. Only this report was added to the repository. No app source, design contract, dependency pins, minimum OS targets, or release settings were changed; no builds, tests, installations, simulator sessions, performance measurements, commits, or external writes were performed.

Context7 resolved Compose/KMP documentation and returned the native-shell and overlay integration guidance; agents additionally checked Apple and Android APIs and upstream source. Exact library compatibility, Kotlin availability handling, optical fidelity over Chur's media paths, and private-frame disposal remain implementation acceptance items. Upstream release/main documentation conflicts are called out above rather than resolved by assumption.
