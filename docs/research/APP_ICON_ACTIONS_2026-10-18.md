# App icon actions for Chur: research and implementation plan

> **Status:** Proposed research and implementation plan; no feature implementation or device acceptance is claimed

Date: 2026-10-18. Repository inspected on `main`. Recommendations below are proposals, not amendments to accepted security contracts.

Implementation follow-up: the requested fourth **Lock and hide** command and the single-authentication contract are specified in [App icon actions](../product/APP_ICON_ACTIONS.md). This research retains the original three-command proposal and platform sources; the product contract describes the implemented scope.

## Recommendation

Add the operating system's app-icon menu with three fixed actions: **Import**, **Search**, and **New private note**. Use static iOS Home Screen Quick Actions and fixed, programmatically published Android dynamic App Shortcuts. Keep the same action IDs, order, and meaning on both platforms; publication differs because Android static shortcuts clear the existing activity task. Do not implement a custom long-press gesture inside Compose.

Every entry is a command to open an existing workflow. It contains no private content, object identifier, destination album, search text, session handle, or authentication material. Authentication remains inside Chur. Do not personalize the menu according to vault existence, open/closed state, recent activity, or real/decoy identity.

The menu discloses product capabilities to anyone who can inspect the app icon. Neutral text does not make it a hidden entrance. This fits Chur's existing disclosure of vault functionality; it does not promise concealment of that functionality. See [Discreet Mode](../product/DISCREET_MODE.md#widgets-search-shortcuts-and-assistants).

## Proposed user experience

| Rank | Stable command ID suffix | Menu title | Result after authorization |
| --- | --- | --- | --- |
| 0 | `import` | Import / Импорт | Open Library's existing import-source chooser, initially targeting All items. The user chooses the source and files; nothing is imported automatically. |
| 1 | `search` | Search / Поиск | Open the private media/file Search destination with an empty query and focus its field. Do not start OCR or expose a previous query. This is not public Notes search or private-note search. |
| 2 | `new-private-note` | New private note / Приватная заметка | Open a new empty private-note editor. Create no catalog record until the user explicitly saves. Never create a public-shell note. |

Use `dev.po4yka.chur.action.<suffix>` for iOS types and Android intent actions; Android shortcut IDs can use the suffixes. Use existing resource/localization conventions and short localized labels, with platform-appropriate import, search, and note icons. Icons are bundled glyphs, never thumbnails. English and Russian above describe proposed copy; they do not introduce a requirement to translate unrelated UI.

Do not fill a fourth slot just because a platform permits one. **Encrypt files** is a reasonable later candidate because the [standalone encrypted archive workflow](../product/EXTERNAL_FILE_ENCRYPTION.md) already exists. Public Camera is a separate, unencrypted workflow and must not be advertised as private capture. Recent files, albums, favorites, export, deletion, recovery, and real/decoy selection are excluded. A launcher Lock action would launch the app and is not a replacement for the existing immediate in-app panic lock or Android Quick Settings tile.

## What exists in Chur

The following are observed source facts, not runtime verification:

| Area | Existing source and implication |
| --- | --- |
| Android baseline | [`gradle/libs.versions.toml`](../../gradle/libs.versions.toml) sets minSdk 29. Native `ShortcutManager` is sufficient; no legacy support dependency is needed. |
| Android entry | [`AndroidManifest.xml`](../../apps/androidApp/src/main/AndroidManifest.xml) declares exported `MainActivity` with `singleTask`. [`MainActivity.kt`](../../apps/androidApp/src/main/kotlin/dev/po4yka/chur/android/MainActivity.kt) receives shares in `onCreate` and `onNewIntent`; the latter currently handles shares only. Neither declares the proposed shortcuts. |
| iOS entry | [`ChurApp.swift`](../../apps/iosApp/Sources/ChurApp.swift) uses UIKit `AppDelegate` and `SceneDelegate`, hosting Compose. `willConnectTo` creates the root and starts the controller; `sceneDidBecomeActive` refreshes incoming shares. Quick-action callbacks are absent. |
| iOS configuration | [`project.yml`](../../apps/iosApp/project.yml) sets deployment target 18.0. [`Info.plist`](../../apps/iosApp/Info.plist) disables multiple scenes and contains no `UIApplicationShortcutItems`. The plist and XcodeGen source must remain consistent. |
| Shared routing | [`ChurApp.kt`](../../shared/app/src/commonMain/kotlin/dev/po4yka/chur/app/ChurApp.kt) defines `AppRoute`; [`ChurController.kt`](../../shared/app/src/commonMain/kotlin/dev/po4yka/chur/app/ChurController.kt) owns startup, unlock, routes, lock epochs, and session lifetime. `goTo` is a route setter, not an authorization API. |
| Vault screens | [`ChurRoutes.kt`](../../apps/androidApp/src/main/kotlin/dev/po4yka/chur/android/ChurRoutes.kt) and [`IosRoutes.kt`](../../shared/app/src/iosMain/kotlin/dev/po4yka/chur/app/IosRoutes.kt) hold import chooser, destination, search, and private-note visibility in local Compose state. An external command cannot currently select these inner states merely by navigating to `AppRoute.Vault`. |
| Private notes | [`PrivateNotesRoute.kt`](../../shared/app/src/commonMain/kotlin/dev/po4yka/chur/app/vault/PrivateNotesRoute.kt) binds a session-scoped controller. [`PrivateNotesController.kt`](../../shared/app/src/commonMain/kotlin/dev/po4yka/chur/app/PrivateNotesController.kt) already implements `create()` as an empty editor without a saved record. Add a deliberate entry hook rather than duplicating creation logic. |
| Existing contracts | [Private Notes](../product/PRIVATE_NOTES.md), [Content Search](../product/CONTENT_SEARCH.md), and [Incoming Shares](../product/INCOMING_SHARES.md) already define positive workflows, dirty-editor behavior, session isolation, and queue ownership. Reuse them. |

### Important authentication contract decision

The [provisioning contract](../security/PROVISIONING.md) says the optional whole-app gate authenticates a temporary vault session, closes it, and then displays the public shell. `ChurController.completeUnlock()` implements this for `AppUnlock`/`AppRecover`; ordinary vault unlock calls `enterVault()` instead. Blindly chaining the new action after `AppUnlock` would therefore require a second authentication. Calling `goTo(Vault)` would not solve it: the session is closed.

**Proposed bounded contract change:** an explicit private launcher command may use the successfully authenticated session to enter its requested private workflow directly, including when whole-app lock is enabled. Ordinary launches still close the temporary session and open the public shell. Preserve credential validation, lock-epoch checks, recovery confirmation, privacy cover, and the identity selected by the supplied credential. Update `PROVISIONING.md` and the focused launcher-action contract together before implementing this exception. A new command is not an authentication factor.

This is a product/security decision within the proposed implementation, not a claim that today's contract already permits retaining that session. If that exception is rejected, the accurate alternative is two authentication steps; it must not be hidden behind direct navigation or a weakened gate.

## Shared command handling

Implement a small shared enum and a one-item in-memory request in `shared/app`, owned by `ChurController`. A host maps only an allowlisted platform action to this enum. Use a process-local request token for acknowledgement; once authorization succeeds, bind delivery to the current session generation. No persistent command queue, general deep-link router, saved private navigation, Rust API, catalog migration, or new production dependency is needed.

| State or event | Proposed behavior |
| --- | --- |
| Startup or native handshake incomplete | Retain only the command in process memory. Dispatch after successful initialization, not immediately after asynchronous `begin()`. On startup refusal, discard it and show the existing failure surface. |
| No vault | Show the existing public Settings/setup entry with a neutral explanation. Do not automatically create a vault. Clear the command; after explicit setup the user can select the workflow normally. |
| Locked vault | Show the ordinary session gate, retaining the command while that gate is active. Failed authentication keeps the command for retry; Back/cancel clears it. |
| Whole-app lock | Follow the explicitly amended single-authentication rule above; never bypass the gate or preserve the temporary session on an ordinary launch. |
| Already unlocked, idle | Deliver to that session's existing vault UI. Do not select or infer a different vault identity, and do not unnecessarily reopen storage or authenticate again. |
| Command successfully dispatched | Acknowledge once. Recompositions, activity recreation, and later resume must not reopen a picker, duplicate a note editor, or repeat import. Keep acknowledgement in process state. |
| Session lock, panic, identity change, canceled gate, or process death | Invalidate requests/deliveries from that flow. Do not replay a command into the next session. An internal cleanup at the beginning of the requested unlock must not accidentally erase the command waiting for that unlock. |
| Background transition | Preserve existing cover and lock policy, including owned picker/permission exceptions. Drop a command when its gate is abandoned or its session is revoked; shortcut handling must not add an exemption from background lock. |
| Another command arrives before dispatch | The latest valid command can replace an undispatched command at the same idle/gate stage. It cannot interrupt an active workflow. |
| Dirty editor, picker, import/export, recovery/setup, or incoming-share confirmation is active | Keep that workflow and its data. Refuse the new navigation with a neutral notice asking the user to finish/cancel it, and discard that command. No deferred surprise navigation and no silent draft loss. A queued share remains owned by its existing inbox. |
| Unknown/malformed external action | Ignore the command and perform safe normal launch/resume. Never reflect untrusted input into labels, errors, logs, or private queries. |

For dispatch, expose a narrow session-bound entry request to both platform vault routes. Import opens the existing chooser; Search selects the existing destination, clears query/selection, and focuses the field; New private note opens `PrivateNotesRoute` and invokes the existing `create()` once when ready. Do not consume the request until the target screen can accept it. Revalidate session generation before accepting delayed picker results, as existing import does.

Keep the command enum, readiness, acknowledgement, and cancellation policy shared. Leave native picker presentation in the existing platform hosts. This small seam prevents Android/iOS behavior drift without refactoring the large route files wholesale.

## Platform implementation

### iOS

1. Declare three static `UIApplicationShortcutItems` in `project.yml` and `Info.plist`, with stable types, localized titles, and platform icons. No `userInfo` is required. Static publication makes them available before the first app launch.
2. In `SceneDelegate.scene(_:willConnectTo:options:)`, read `connectionOptions.shortcutItem` for a cold launch and pass its allowlisted type to the shared controller after storage/gate readiness.
3. Implement `windowScene(_:performActionFor:completionHandler:)` for an existing scene. Acknowledge whether the host accepted the recognized command; asynchronous authentication is handled by Chur. Call completion exactly once, including refusal/unknown-action cases.
4. Keep dispatch on the main/UI path and wait until the scene is active before presenting the chooser or keyboard. Preserve the current privacy-cover ordering. Do not also dispatch the same item from `AppDelegate` or `sceneDidBecomeActive`.
5. Use the UIKit host already in the project. No SwiftUI app conversion, extension, App Intent, Siri donation, entitlement, or custom URL scheme is needed.

### Android

1. Publish the same three fixed commands with native `ShortcutManager`/`ShortcutInfo`. Dynamic means programmatic registration here, not private-data personalization. Give each command an explicit `MainActivity` component, one allowlisted action, a bundled icon, localized labels, and rank 0–2. Query `getMaxShortcutCountPerActivity()` and retain priority if capacity is smaller.
2. Use `FLAG_ACTIVITY_SINGLE_TOP | FLAG_ACTIVITY_CLEAR_TOP` with the existing `singleTask` activity. Do not set `CLEAR_TASK`. Handle commands in both `onCreate` and `onNewIntent`, through one decoder that keeps `ACTION_SEND`/`ACTION_SEND_MULTIPLE` handling intact. Treat exported activity input as untrusted; launcher origin is not authorization.
3. Consume and sanitize the stored launch intent after accepting a command so recreation does not replay it. A new cold-launch intent after process death is a new invocation; an old restored activity intent is not. Cover private content before any navigation.
4. Reconcile publication on foreground startup and after effective app/system locale change. Dynamic shortcuts are not backed up; recreate them on the first foreground launch after restore. Include neutral refresh of pinned copies on locale change, and disable removed command IDs when a future version actually removes an action. Do not register on every recomposition or tie publication to unlock state.
5. Run publication on a worker dispatcher: `setDynamicShortcuts` can take seconds. Use fixed IDs and idempotent publication, check its boolean result for rate limiting, and respect the queried capacity. Handle publication failure without breaking ordinary app launch; report a neutral diagnostic and retry at the next legitimate refresh point, without a background polling service. An unsupported launcher remains usable through the normal in-app controls. See the [platform API contract](https://developer.android.com/reference/android/content/pm/ShortcutManager#setDynamicShortcuts(java.util.List%3Candroid.content.pm.ShortcutInfo%3E)).
6. Do not add Google Shortcuts Integration, `pushDynamicShortcut`, conversation metadata, direct-share targets, or a programmatic pinning prompt. A launcher may still let the user pin an action; every pinned invocation must pass the same gate. Removing an action from the dynamic menu alone does not revoke its pinned copy. Omitting assistant integration does not guarantee metadata is inaccessible to a privileged launcher or active voice service; the published metadata must remain generic regardless.

**Tradeoff:** Android actions appear after the app has first published them, unlike static iOS actions. This is preferable to destroying the existing activity task. Static Android shortcuts plus a trampoline could make pre-first-launch discovery possible, but add another exported entry and task behavior to maintain; no current requirement justifies that complexity. The source for task behavior is [Android: Manage shortcuts](https://developer.android.com/develop/ui/compose/system/shortcuts/managing-shortcuts).

## Delivery slices

Each slice delivers executable behavior, not a separate process/report milestone. No issues, commits, PRs, or external writes are created by this research.

| Order | Runnable outcome | Likely changed files | Acceptance |
| --- | --- | --- | --- |
| 1 | Import action works end to end on both platforms, including cold/locked launches | Small shared action type; `ChurController.kt`; both route files; iOS `ChurApp.swift`/plist/XcodeGen configuration; Android `MainActivity.kt`, a small shortcut publisher/decoder, resources/locale receiver; focused product contract plus provisioning amendment | Authentication succeeds normally; one chooser opens; cancel makes no change; selected content really imports into the credential-selected identity. No private extras or task destruction. |
| 2 | Search and New private note join the menu | Same adapters and route hooks; `PrivateNotesRoute.kt`; localized labels/icons | Empty private Search receives focus without OCR; a blank private-note editor opens once and saves a real note only on Save. Public Notes stay separate. |
| 3 | The three actions survive supported lifecycle and launcher variations | Focused regression tests and native UI tests beside existing tests; publication lifecycle handling | No duplicate delivery, draft loss, gate bypass, stale-session write, or pinned-action bypass; real-device icon menu is exercised on both platforms. |

The feature is incomplete if it only parses action names, opens the home screen, or refuses every invocation. Positive import, search, and saved private-note behavior are acceptance requirements. No backend, Rust/FFI contract, encryption format, permission expansion, launcher icon artwork change, or general navigation rewrite is expected.

## Validation plan for implementation

Add focused tests to existing suites rather than a new verification framework:

- Shared controller: startup readiness; no-vault path; locked/unlocked/app-locked invocation; the proposed whole-app exception versus ordinary app launch; password failure and cancel; epoch/generation revocation; duplicate delivery; dirty/active-flow refusal; identical routing with either credential-selected identity.
- Route behavior: one import chooser; successful import and cancellation; empty Search without OCR; empty editor without a saved record, then successful explicit Save; repeated actions and recreation do not create duplicates.
- Android adapter/UI: explicit action allowlist, restored-intent sanitization, `onCreate` and `onNewIntent`, no duplicate activity/task, locale/restore publication, existing incoming shares, and user-pinned invocation after lock. Launching an intent with `adb` proves routing only, not launcher-menu registration or presentation.
- iOS adapter/UI: cold `connectionOptions.shortcutItem`, warm scene callback and exactly-once completion, scene activation ordering, cancel/background while unlocking, and a real home-screen long press. Existing Swift package tests exclude `ChurApp.swift`; passing them alone cannot validate these scene callbacks.
- Privacy/UI: fixed labels across locked/unlocked/absent/decoy states; no metadata in system surfaces or restored state; recents cover; VoiceOver/TalkBack, long localized labels, and normal Back behavior. Do not assert that an app controls OS-managed menu layout or guarantees identical menus across launchers.

Use the repository's documented Gradle tasks, narrowed to affected modules/tests first. Representative commands for that later implementation are:

```sh
build-gate -- ./gradlew :shared:app:testAndroidHostTest --tests '*LauncherAction*' --max-workers=4
build-gate -- ./gradlew :shared:app:iosSimulatorArm64Test --max-workers=4
build-gate -- ./gradlew :apps:androidApp:assembleDebug :apps:androidApp:lintDebug :apps:androidApp:assembleDebugAndroidTest --max-workers=4
```

The test name above is proposed, not an existing passing suite. The iOS task requires a booted owned simulator and `CHUR_IOS_TEST_DEVICE` per [DEVELOPMENT.md](../../DEVELOPMENT.md#build-commands); use the existing host-project build workflow for UIKit integration and gate its top-level heavy build. Complete targeted native UI/device tests afterward; an Android test APK build does not execute instrumentation. Do not run Rust's whole suite solely for this host-navigation feature unless implementation changes Rust or its contract.

For device coverage, include supported minimum OS behavior (Android API 29 and iOS 18), the current release used by the project, and at least a Google launcher plus one relevant OEM launcher. OS support does not imply every launcher exposes all commands. Unsigned builds, simulators, and injected callbacks do not establish signed physical-device Home Screen acceptance.

## Evidence and limits of this research

Repository behavior was assessed by reading platform manifests/hosts, controller startup/unlock/lock code, route state, private-note creation, and focused product/security contracts. Platform behavior was checked against the official sources below. This task changes research documentation only; builds, tests, app installation, launcher interaction, authentication, import, and note saving were not executed. No runtime completion claim follows from the plan.


## Official platform research and sources

### Scope and evidence

This note covers the menu shown when someone touches and holds an application's Home Screen or launcher icon. It records current first-party documentation, not observed device behavior. No application build, launcher test, or physical-device verification was performed. The preceding sections contain the Chur-specific proposal.

Context7 was queried first for each platform: `library "UIKit"` resolved `/websites/developer_apple_uikit`; `library "Android Developers"` resolved `/websites/developer_android`. One `docs` query followed each resolution. Both completed successfully. UIKit results covered registration and scene callbacks; Android results were narrower, chiefly launcher access and intent handling. Official Apple and Android documentation retrieved through web browsing supplied the remaining details. The current Android guide redirects older `ui/views/launch/shortcuts` URLs to `ui/compose/system/shortcuts`; the underlying feature remains a platform launcher API.

### iOS and iPadOS: Home Screen quick actions

| Concern | Finding |
| --- | --- |
| User interaction and count | Touch and hold opens a system menu. Apple HIG describes up to **four app actions**, alongside system items such as removing the app. Prefer concise localized titles and familiar SF Symbols; avoid emoji and unpredictable rearrangement. [Apple HIG](https://developer.apple.com/design/human-interface-guidelines/home-screen-quick-actions) |
| Static registration | Declare `UIApplicationShortcutItems` in `Info.plist`. Every item has an app-defined type and localized title; subtitle and icon are available. [Property-list schema](https://developer.apple.com/documentation/bundleresources/information-property-list/uiapplicationshortcutitems), [Apple sample](https://developer.apple.com/documentation/uikit/add-home-screen-quick-actions) |
| Dynamic registration | Set `UIApplication.shared.shortcutItems` with `UIApplicationShortcutItem` values; replacing the array changes registration. Static actions take display priority. Static actions exist before first launch, whereas dynamic actions require a prior launch. Old dynamic items can remain after an update until the updated app runs. [UIApplicationShortcutItem](https://developer.apple.com/documentation/uikit/uiapplicationshortcutitem) |
| Availability | Quick-action API availability begins at iOS 9; the current sample describes touch-and-hold on iOS 13 and later. Old API descriptions that mention only 3D Touch do not describe the present interaction. [shortcutItems](https://developer.apple.com/documentation/uikit/uiapplication/shortcutitems), [Apple sample](https://developer.apple.com/documentation/uikit/add-home-screen-quick-actions) |
| Cold scene | Read `connectionOptions.shortcutItem` in `scene(_:willConnectTo:options:)`; retain the request until scene activation and navigation readiness. [Apple sample](https://developer.apple.com/documentation/uikit/add-home-screen-quick-actions), [connectionOptions.shortcutItem](https://developer.apple.com/documentation/uikit/uiscene/connectionoptions/shortcutitem) |
| Existing scene | Handle `windowScene(_:performActionFor:completionHandler:)` and finish its completion handler with whether the action was handled. Both lifecycle entries should feed one application dispatcher. [Apple sample](https://developer.apple.com/documentation/uikit/add-home-screen-quick-actions) |
| Payload | `userInfo` is optional; keys/values must support `NSSecureCoding` and property-list encoding. This is a serialization constraint. `NSSecureCoding` protects against object substitution, and does not promise payload secrecy. [userInfo](https://developer.apple.com/documentation/uikit/uiapplicationshortcutitem/userinfo), [NSSecureCoding](https://developer.apple.com/documentation/foundation/nssecurecoding) |

SwiftUI does not require a different launcher feature. Apple documents `@UIApplicationDelegateAdaptor` for delegate integration and a `UIWindowSceneDelegate` supplied through `application(_:configurationForConnecting:options:)` for scene events including quick actions. A SwiftUI app still needs both initial connection and existing-scene handling; navigation can consume a shared request model once ready. This is an integration recommendation based on those lifecycle entry points. Chur already has UIKit delegates, so introducing the SwiftUI lifecycle would add unnecessary work. [Apple delegate integration](https://developer.apple.com/documentation/swiftui/uiapplicationdelegateadaptor)

Home Screen quick actions and **App Intents App Shortcuts** are distinct integrations. App Intents exposes structured actions/entities to Siri, Spotlight, Shortcuts and other system experiences; `AppShortcut` adds titles, images, parameters and spoken phrases. Home Screen registration alone does not implement those experiences, and implementing App Intents is unnecessary for this icon menu. [App Intents](https://developer.apple.com/documentation/appintents), [App Shortcuts](https://developer.apple.com/documentation/appintents/app-shortcuts)

### Android: App Shortcuts

| Type | Registration and behavior |
| --- | --- |
| Static | Package `res/xml/shortcuts.xml`; reference it with `android.app.shortcuts` metadata on the `MAIN`/`LAUNCHER` activity, or its launcher alias. These represent version-stable actions. [Create shortcuts](https://developer.android.com/develop/ui/compose/system/shortcuts/creating-shortcuts) |
| Dynamic | Publish/update runtime `ShortcutInfo` objects. Platform `ShortcutManager` and its dynamic API are available from API **25**. Dynamic registration can publish a fixed generic command set; it need not personalize labels or inspect private content. [ShortcutManager](https://developer.android.com/reference/android/content/pm/ShortcutManager) |
| Pinned | Separate launcher icons, rather than additional menu rows. Programmatic pinning is available from API **26**, depends on launcher support, and requests user approval. Users can also drag static/dynamic shortcuts out of the menu. [Create shortcuts](https://developer.android.com/develop/ui/compose/system/shortcuts/creating-shortcuts) |

Google recommends publishing **four** distinct actions; most launchers display four, but this is not a universal display guarantee. The current best-practices page mentions a combined API capacity of 15; do not hard-code that as device capacity. Query `getMaxShortcutCountPerActivity()`, which returns the per-launcher-icon static-plus-dynamic publishing maximum. Keep the desired menu small and ordered by rank. [Best practices](https://developer.android.com/develop/ui/compose/system/shortcuts/best-practices), [maximum-count reference](https://developer.android.com/reference/android/content/pm/ShortcutManager#getMaxShortcutCountPerActivity())

#### Lifecycle and task behavior change the implementation choice

Static shortcuts force `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK` on the first intent; custom flags are unavailable. Android explicitly documents destruction of existing activities. **A `singleTask` activity is therefore insufficient reason to assume that a static shortcut preserves the current session, editor or operation.** Dynamic shortcuts permit custom flags; Android recommends `FLAG_ACTIVITY_SINGLE_TOP | FLAG_ACTIVITY_CLEAR_TOP` for reusing a single-activity app through `onNewIntent()`. [Manage shortcuts: intent flags](https://developer.android.com/develop/ui/compose/system/shortcuts/managing-shortcuts#intent-flags)

Decode the request from the initial intent in `onCreate`, and from the callback parameter in `onNewIntent`. `getIntent()` otherwise retains the old intent; call `setIntent` if subsequent logic reads the activity property. `singleTask` normally reuses an existing activity but removes activities above it, and intent flags can override manifest launch behavior. Route both callbacks through the same readiness/authentication gate. [Activity reference](https://developer.android.com/reference/android/app/Activity#onNewIntent(android.content.Intent)), [tasks and launch modes](https://developer.android.com/guide/components/activities/tasks-and-back-stack)

Use explicit app components and fixed command actions. `ShortcutInfo.Builder.setIntent` supports primitive extras that the system persists; `setExtras` stores additional persistent metadata. The platform requires a short label and intent when publishing a new shortcut. [ShortcutInfo.Builder](https://developer.android.com/reference/android/content/pm/ShortcutInfo.Builder)

#### Registration, restore and stale copies

`setDynamicShortcuts` replaces the published dynamic list and updates mutable pinned copies with matching IDs. Its call can take seconds, belongs on a worker thread, and returns `false` when rate-limited; exceeding the device maximum throws. Do not run reconciliation on every UI recomposition. [setDynamicShortcuts](https://developer.android.com/reference/android/content/pm/ShortcutManager#setDynamicShortcuts(java.util.List%3Candroid.content.pm.ShortcutInfo%3E))

Dynamic shortcuts are not backed up; republish on restore/launch as needed. Pinned copies may survive removal from the dynamic list; disable obsolete mutable shortcut IDs rather than assuming removal deletes launcher icons. Preserve the meaning of stable IDs when updating them. The app should also reconcile localized labels when its effective language changes. The language trigger is an implementation recommendation; the documented restore and pinned behavior makes reconciliation necessary. [Manage shortcuts](https://developer.android.com/develop/ui/compose/system/shortcuts/managing-shortcuts), [identity/update guidance](https://developer.android.com/develop/ui/compose/system/shortcuts/best-practices)

The Google Shortcuts Integration Library is optional and broadens dynamic shortcut delivery to participating Google surfaces. It is unnecessary for a launcher-only implementation. Omitting it is not a guarantee that no privileged system component can access platform shortcut metadata: the default/current launcher and active voice interaction service can hold shortcut-host access. [Create shortcuts: Google integration](https://developer.android.com/develop/ui/compose/system/shortcuts/creating-shortcuts#add-the-google-shortcuts-integration-library), [LauncherApps.hasShortcutHostPermission](https://developer.android.com/reference/android/content/pm/LauncherApps#hasShortcutHostPermission())

### Privacy and execution boundary

The following are design recommendations derived from the system-surface and persistence behavior above:

- Treat every published title, subtitle, icon, ID, intent and extra as data outside Chur's private vault UI. Use generic commands only. Do not publish filenames, note snippets, album names, thumbnails, vault IDs, counts, decryption keys or session tokens.
- A launcher request expresses navigation intent, not authorization. Validate a closed command set; wait for bootstrap and the normal vault authentication; recheck session validity before performing the requested operation. Cancellation must discard the request rather than reopen a picker or editor later.
- Do not start import, export, encryption or deletion invisibly from the launcher callback. Open the existing user flow with its permission, destination and confirmation steps. Export and share retain their explicit disclosure and plaintext-lifecycle boundaries.
- Dynamic Android publication can remain fixed and independent of locked/unlocked state, real/decoy state and private content. This prevents menu contents from becoming a state oracle. User-pinned copies need the same authenticated dispatch even without an in-app pin button.
- Avoid long-lived, person or conversation metadata for these generic commands. Android documents extra caching for long-lived shortcuts and greater prominence for person-associated ones. [ShortcutInfo.Builder](https://developer.android.com/reference/android/content/pm/ShortcutInfo.Builder)

### Verification still required

Documentation establishes API contracts, not delivered launcher behavior. Implementation acceptance must exercise fresh installation, cold process, background process, locked vault, unlock cancellation, already-open editor/import, repeated invocation, recreation, app update, localization and Android pinned copies. Check a supported stock launcher and at least one relevant OEM launcher; verify titles, rank, clipping, icon contrast and accessibility labels on actual target OS versions. Confirm that protected operations remain gated and command payloads contain no private data.

Two documentation tensions remain explicit: Apple HIG specifies four actions while its sample tells apps to provide all dynamic items and let the system choose what fits; Android describes a 15-item API capacity while also exposing a device maximum and warning that launchers usually show four. The bounded three-command proposal avoids relying on the larger capacities. Launcher-specific behavior, callback ordering under Chur's lifecycle and in-progress operation preservation require runtime evidence.
