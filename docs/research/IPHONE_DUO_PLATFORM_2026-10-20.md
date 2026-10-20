# iPhone Duo: Apple platform research

Date: 2026-10-20. Status: research only; no source implementation, simulator run, physical-device acceptance, SDK installation, or distribution action. Primary Apple documentation and Apple staff answers were retrieved live. Context7 resolved `/websites/developer_apple_uikit` and corroborated arrangement API availability and scene lifecycle documentation. This note separates documented behavior from recommendations for Chur.

## Compatibility is different from an adapted experience

| Build SDK | Apple's documented behavior |
| --- | --- |
| iOS 26 or earlier | Runs in a compatibility presentation: centered on the inner display; outer content avoids the status bar and camera. |
| iOS 27 | Dynamically resizes to most of the inner display, avoiding the right status-bar area. |
| iOS 27.1 or later | Uses the full display; supported system navigation, toolbar, and tab controls can move to the vertical region below the status bar. Rebuilding requires reviewing the changed layout. |

The correct baseline for native Duo adaptation is **Xcode 27.1 / iOS 27.1 SDK**, rather than merely an app that launches on Duo. These are build SDK requirements, not a reason by themselves to raise Chur's minimum supported iOS version. [Apple preparation guide](https://developer.apple.com/iphone-duo/prepare/).

Xcode 27.1 includes Swift 6.4 and the iOS 27.1 SDK, requires macOS Tahoe 26.6 or later, and supports older deployment targets. Apple's current matrix also lists Xcode 27.2 RC; this research uses 27.1 as the documented Duo baseline, without recommending an RC toolchain. [SDK and system requirements](https://developer.apple.com/xcode/system-requirements).

## Resize using the current container

Size UIKit content from `view.bounds` or `window.bounds`; re-evaluate during layout and transitions. `layoutSubviews`, `viewDidLayoutSubviews`, and `viewWillTransition(to:with:)` are relevant hooks. SwiftUI uses `GeometryReader` / `onGeometryChange`. Read scale from traits or the environment. Create a window with `UIWindow(windowScene:)`; do not cache a `UIScreen`. Avoid layout decisions based on device orientation, `.phone` / `.pad`, known device dimensions, or an assumption that an iPhone always has compact width. Full-bleed media also needs review for cropping on wider containers. [Apple preparation guide](https://developer.apple.com/iphone-duo/prepare/).

For Chur, this means shared Compose content needs the host's actual changing constraints and insets. It does not follow from Apple's UIKit guidance that a custom Compose canvas automatically adopts all native Duo behavior; that requires separate JetBrains evidence and runtime verification.

## Confirmed new APIs and their role

| Need | Confirmed public API / mechanism |
| --- | --- |
| Layout around fold and camera | UIKit `UIView.reservedRegions(kind:options:)`; SwiftUI `GeometryProxy.reservedRegions(kind:options:layoutDirectionBehavior:)`. Returned regions expose frames; division and occlusion have different meanings. |
| Primary/secondary content layouts | UIKit `UIArrangementViewController`; SwiftUI `ArrangementView`. Split and overlay arrangements adapt two views to available space and reserved divisions. |
| Observe vertical chrome | UIKit `UITraitCollection.verticalBarEdge`; SwiftUI `EnvironmentValues.toolbarVerticalEdge`. |
| Control vertical presentation | UIKit `UIViewController.preferredVerticalBarBehavior`; SwiftUI `toolbarVerticalBehavior(_:)`. |
| Adapt toolbar items | UIKit `UIBarButtonItem.axisBehavior`, `visibilityPriority`, `UINavigationItem.additionalOverflowItems`; SwiftUI `axisBehavior(_:)`, `visibilityPriority(_:)`, `ToolbarOverflowMenu`. |

These spellings come from the [Apple platform overview](https://developer.apple.com/documentation/technologyoverviews/preparing-your-app-for-iphone-duo), retrieved through its [Markdown representation](https://developer.apple.com/documentation/technologyoverviews/preparing-your-app-for-iphone-duo.md). Use the plural **`reservedRegions`**, despite singular wording in the spoken video. Context7 confirms iOS 27.1 availability for [UIArrangementViewController](https://developer.apple.com/documentation/uikit/uiarrangementviewcontroller). Exact availability and Kotlin/Native exposure of each individual symbol still need checking against the chosen SDK before implementation.

Reserved division regions are active when partially folded; inactive regions can also be queried. Camera occlusions are distinct. System alerts, action sheets, menus, and popovers reposition around reserved areas. Continuous articles/lists should generally continue scrolling rather than jump between regions. Arrangements are suitable for intentional paired content, not indiscriminate wrapping of every screen. [Adaptive layouts session](https://developer.apple.com/videos/play/tech-talks/111463/).

Hinge data is a separate capability: SwiftUI `onHingeChange` and UIKit `UIHingeInteraction` expose discrete states and continuous angles. Apple explicitly recommends arrangement and region APIs for layout, reserving hinge observations for interactions and effects. A missing hinge is a supported condition. Multiple windows can be created on the inner display, but not the outer one; handle creation errors, or use `UIWindowSceneActivationAction`, which adapts to availability. [Displays and scenes session](https://developer.apple.com/videos/play/tech-talks/111464/).

## Native bars need native navigation ownership

Use `UINavigationController` and `UITabBarController`, configuring items on their child view controllers. Creating standalone `UIToolbar`, `UINavigationBar`, or `UITabBar` components does **not** make their custom presentation participate in the automatic vertical system layout. In SwiftUI, use navigation containers and their toolbar support. Icons plus meaningful titles allow adaptive representation and overflow; title-only and custom views need special attention. Inner portrait layouts may keep horizontal bars, so one permanent right-hand rail is not the documented model. Keyboard accessories remain attached to the keyboard. [Bars session](https://developer.apple.com/videos/play/tech-talks/111462/).

Recommendation for Chur: preserve a native container-owned shell and make shared content respond to the resulting bounds. Do not reproduce system bar positioning through device-name checks. The benefit is native adaptation and accessibility; the cost is reviewing the shell's container hierarchy, custom items, and content insets under the 27.1 SDK.

## Lifecycle and privacy: folding need not deactivate a scene

An Apple DTS engineer confirms that full-screen inner-to-outer folding can keep the same active scene; the system changes geometry and traits. With multiple windows, the most recently active one remains active when closed while the secondary scene becomes inactive and then backgrounds. [Apple staff: ScenePhase call](https://developer.apple.com/forums/thread/847838).

UIKit takes its app-switcher snapshot after the background delegate callback returns. Sensitive content must already be removed or covered; scene-based apps receive the corresponding scene delegate events. [Preparing UI for background](https://developer.apple.com/documentation/uikit/preparing-your-ui-to-run-in-the-background).

**Implications for Chur, not additional Apple guarantees:**

- Keep privacy-cover geometry attached to the live window, including resize while inactive and during display transitions.
- Do not assume that folding invokes the existing inactivity lock path. Whether a fold should lock is a separate product decision; ordinary resize continuity must preserve authorized state safely.
- Verify private views, native sheets, menus, viewer controls, and app-switcher snapshots across transitions. Geometry adaptation alone is not privacy acceptance.
- Do not enable multiple Chur vault scenes or outward-facing accessories merely to claim Duo support. They require explicit session, identity, lock propagation, and disclosure contracts.

Apple supports `CameraCaptureAccessory` for supplementary outer-display UI with active capture while the main UI uses the inner display. This is an intentional second audience, not a general requirement for Duo adaptation. [Displays and scenes session](https://developer.apple.com/videos/play/tech-talks/111464/).

## Verification boundaries

Apple's recommended simulator is the Duo destination in Xcode 27.1 Device Hub, whose controls open, close, fold, and rotate the device. Resizable iOS simulation and macOS 27 iPhone Mirroring are additional geometry checks. [Preparation session](https://developer.apple.com/videos/play/tech-talks/111461/).

The 27.1 release notes limit what simulator results prove: StandBy and most extension running/debugging are unavailable. The simulator permits `CameraCaptureAccessory` across both displays without an active capture session, unlike the documented device condition. Initial simulator launch can take several minutes. [Xcode 27.1 release notes](https://developer.apple.com/documentation/xcode-release-notes/xcode-27_1-release-notes), [Markdown](https://developer.apple.com/documentation/xcode-release-notes/xcode-27_1-release-notes.md).

Recommended acceptance matrix: closed outer display; fully open inner wide and tall layouts; partially folded book and tabletop; transitions during editing, media viewing, selection, authentication, and modal presentation; side-by-side app multitasking; large text, VoiceOver, and reduced transparency; privacy cover and background snapshots. Preserve current state across ordinary geometry changes and exercise error paths. This is a proposed future check, not observed acceptance.

No public Apple source retrieved here establishes automatic Compose support for reserved regions, exposes these APIs through Chur's current Kotlin platform libraries, proves the physical display-transition snapshot sequence, or certifies Chur's privacy behavior on Duo. Compile checks and simulator testing remain necessary, with physical-device testing for final privacy and camera acceptance.
