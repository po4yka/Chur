# iPhone Duo adaptation for Chur

> **Status:** Proposed research and implementation plan; source inspection only, no Duo runtime acceptance

Date: 2026-10-20. Repository baseline: `main`, `7190b1a`, clean before research. Two research sub-agents covered Apple platform documentation and Chur source inspection; the parent synthesized the findings. Recommendations do not amend accepted security contracts.

## Recommendation

Keep Chur's UIKit host and shared Compose content. First validate dynamic resizing and privacy on Xcode 27.1; then implement the collection/detail layout already specified in `DESIGN.md` §8.3–8.5. Add a narrow native bridge for Duo reserved regions where the Compose layout needs them. Rewriting the app in SwiftUI is unnecessary for this proposal.

This is an adaptation of existing content, navigation, and session ownership. Do not fork behavior by device name or infer usable logical dimensions from display pixels. Layout depends on the current container, system bars, keyboard, and reserved regions. Multiwindow and simultaneous use of both physical displays require separate product/security design.

## Platform evidence

Apple provides a dedicated [Duo developer hub](https://developer.apple.com/iphone-duo/) and identifies Xcode 27.1 as the toolchain for building and testing Duo experiences. Its [preparation guide](https://developer.apple.com/documentation/technologyoverviews/preparing-your-app-for-iphone-duo) describes dynamic resizing and SDK-dependent compatibility behavior. See the companion [platform research](IPHONE_DUO_PLATFORM_2026-10-20.md) for API names, compatibility tiers, and primary references.

Apple's [adaptive layouts session](https://developer.apple.com/videos/play/tech-talks/111463/) distinguishes fold division regions from camera occlusion regions. Custom content can query `UIView.reservedRegions(kind:options:)`; safe-area padding alone does not describe a division through the middle of content. Arrangements can place two related views in different regions. For Chur, this supports a proposed viewer plus controls/details composition, with continuous text remaining scrollable rather than moving arbitrarily between regions.

Native container behavior matters: [Raise the bar](https://developer.apple.com/videos/play/tech-talks/111462/) explains vertical navigation/toolbar/tab presentations, icon-and-title representations, fixed bar width, and placement of custom views. Existing native containers give Chur an integration point, but its custom controls still need inspection in every pose.

An [Apple DTS answer about scene phase](https://developer.apple.com/forums/thread/847838) confirms that a full-screen inner-display scene remains active through a hinge transition; its geometry and traits change. In multiwindow, the last active scene remains active on closing while the secondary scene backgrounds. Therefore Chur's existing inactive/background cover cannot be assumed to conceal content merely because someone folds the device. This is not evidence of a defect against the current policy: **lock or conceal on fold is a separate product decision**. If desired, it needs an explicit hinge-driven policy and acceptance tests, rather than weakening or repurposing background handling.

JetBrains documents [adaptive layouts in common code](https://kotlinlang.org/docs/multiplatform/compose-adaptive-layouts.html), including window size classes through the Material adaptive library and `currentWindowAdaptiveInfoV2()`. This confirms a shared layout strategy is supported; it does not establish Duo reserved-region integration in Compose or certify Chur's pinned versions. Context7 resolution and documentation retrieval succeeded for the official Kotlin Multiplatform documentation. No dependency upgrade is proposed by this research: first assess whether existing container measurement and `Grid.kt` abstractions suffice; evaluate the maintained adaptive library if implementing richer pane orchestration.

## Observed Chur implementation

Paths and line numbers refer to the inspected baseline, not runtime proof.

| Area | Source evidence | Implication |
| --- | --- | --- |
| Native host | [IosEntryPoint.kt](../../shared/app/src/iosMain/kotlin/dev/po4yka/chur/app/IosEntryPoint.kt):59,115; [IosVaultChrome.kt](../../shared/app/src/iosMain/kotlin/dev/po4yka/chur/app/vault/IosVaultChrome.kt):78 | UIKit tab/navigation controllers host Compose children. Shared routes already own navigation state. |
| Universal configuration | [project.yml](../../apps/iosApp/project.yml):71; [Info.plist](../../apps/iosApp/Info.plist):97 | iPhone/iPad target, iOS 18 deployment baseline, all four orientations. A new SDK requirement does not itself require raising minimum deployment. |
| Grid | [LibraryScreen.kt](../../shared/app/src/commonMain/kotlin/dev/po4yka/chur/app/vault/LibraryScreen.kt):381; [Grid.kt](../../shared/app/src/commonMain/kotlin/dev/po4yka/chur/app/theme/Grid.kt):29 | Real container width drives grid geometry; width classes use 600/840 dp boundaries. These are Chur thresholds, not measured Duo sizes. |
| Pane orchestration | [VaultShell.kt](../../shared/app/src/commonMain/kotlin/dev/po4yka/chur/app/vault/VaultShell.kt):581; [DESIGN.md](../../DESIGN.md):715,742 | Current shell selects a single content subtree. Medium/expanded/foldable collection-detail behavior remains a design gap. This does not prove device incompatibility. |
| Insets and modals | [PlatformVaultScaffold.ios.kt](../../shared/app/src/iosMain/kotlin/dev/po4yka/chur/app/vault/PlatformVaultScaffold.ios.kt):39; [IosPrivateModals.kt](../../shared/app/src/iosMain/kotlin/dev/po4yka/chur/app/vault/IosPrivateModals.kt):300,323 | Existing safe-area/keyboard handling and sheet/popover adaptation provide a starting point. Live trait changes with a modal open remain unverified. |
| Viewer | [ViewerScreen.kt](../../shared/app/src/commonMain/kotlin/dev/po4yka/chur/app/vault/ViewerScreen.kt):186,482 | Zoom is keyed by media identity and transforms normalize on resize. Folding and player continuity need runtime checks. |
| Privacy | [IosPrivacyCover.kt](../../shared/app/src/iosMain/kotlin/dev/po4yka/chur/app/IosPrivacyCover.kt):72; [ChurApp.swift](../../apps/iosApp/Sources/ChurApp.swift):313 | An opaque resizing cover and inactive/background hooks exist. No Duo transition leak is established by static inspection. |
| Scene ownership | [Info.plist](../../apps/iosApp/Info.plist):76; [ChurApp.swift](../../apps/iosApp/Sources/ChurApp.swift):10 | Multiple scenes are disabled; host ownership assumes one scene/controller. Do not enable a second scene as an incidental layout change. |

Pinned toolchain dependencies include Compose Multiplatform 1.12.1, Material 3 1.9.0, Kotlin 2.4.21, and iOS 18.0 deployment in [libs.versions.toml](../../gradle/libs.versions.toml). These are repository declarations, not a verified compatibility matrix for Xcode 27.1.

## Implementation order and cost

1. **P0 — Establish Duo compatibility.** Use Xcode 27.1 and its Duo simulation; check native bars, safe areas, window resizing, popovers, keyboard and privacy transitions on the unchanged app. Inspect custom toolbar item representations before changing them. Cost: toolchain setup and a bounded UI acceptance pass; it resolves whether any launch/layout blockers exist.
2. **P1 — Implement useful wide layouts.** Compose collection/list and detail/viewer together when actual space allows, retaining compact push navigation. Keep one shared route owner and one unlocked vault session. Synchronize native navigation with pane visibility rather than creating an independent stack. Cost: medium to high, because native per-destination controllers and shared scene lifetime must agree; benefit: satisfies the existing tablet/foldable design and improves iPad as well.
3. **P1 — Integrate reserved regions.** Use SDK-supported UIKit geometry and, where appropriate, native arrangements. Bridge only the layout information shared content needs, updating coordinates and density with layout. Avoid private controls and important image content crossing an active division region. Treat ordinary width adaptation and fold geometry as separate inputs. Cost: SDK/Kotlin binding verification plus focused host integration; benefit: correct partially folded behavior without device-name branches.
4. **P1 — Verify retirement across all visible content.** Lock/conceal must retire both panes, player, modal and retained private projections according to existing contracts. Preserve selection, route and draft only while authorized; resize is not permission to retain private state after lock. Cover every presented surface before snapshots. Do not change background-lock policy to make folding appear seamless.
5. **P2 — Refine dense and short layouts.** Check form widths, toolbar overflow, note editing with keyboard, large text and VoiceOver. [PrivateNotesScreen.kt](../../shared/app/src/commonMain/kotlin/dev/po4yka/chur/app/vault/PrivateNotesScreen.kt):292 contains a 360 dp minimum editor height; measure short-window behavior before declaring clipping a defect. Camera tabletop composition and dual-display experiences are subsequent feature decisions.

## Acceptance for a future implementation

| Dimension | Required observations |
| --- | --- |
| Geometry | Outer/inner displays, flat and supported partial-fold poses, portrait/landscape, narrow multitasking window, and transitions while a sheet/popover is open. No covered controls, invalid anchors, or clipped actions. |
| State continuity | Library scroll/selection, deep album route, viewer media ID/zoom/playback, search with keyboard, unsaved note, and active import/export retain intended state through layout changes. No duplicate operations or extra authentication caused solely by recomposition. |
| Security | During inactive/background, lock and interrupted authentication: opaque cover, correct policy-driven lock, no private pixels/titles in snapshots, no surviving session-bound pane/player/modal. Repeat resize while covered. |
| Accessibility | Large text up to the existing design QA requirement, VoiceOver order, Reduce Motion, and complete action access in vertical/overflow toolbars. |
| Regression | Existing iPhone and iPad behavior, public shells, cancellation/error paths, and compact navigation remain correct. |

After implementation, use the existing CI test entry point with the machine-wide gate:

```sh
build-gate -- ./gradlew --no-daemon --no-parallel --max-workers=1 iosSimulatorArm64Test
```

The source for that invocation is [.github/workflows/rust.yml](../../.github/workflows/rust.yml):433. Extend existing navigation/scene/modal tests for the changed behavior rather than creating a separate research checker. Builds and tests do not substitute for rendered Duo acceptance; physical hardware is additionally needed for real folding feel, performance and camera behavior.

## Observed limits

Local inspection returned **Xcode 27.0, build 27A266a**. `xcrun simctl list devicetypes` contained no Duo device type. No Xcode update, build, simulator launch, runtime test, dependency change or source implementation was performed. The research therefore identifies the adaptation path and source gaps; it does not claim that Chur currently works or fails on Duo. The separate Apple platform note records the primary-source details needed to perform that acceptance.
