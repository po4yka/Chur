# App icon actions

Discreet Mode publishes only New note, Calculator, Camera, and Chur. These actions open public tools without unlocking a vault, and respect the whole-app gate. The fixed private actions below apply only outside Discreet Mode. iOS registers actions dynamically so no static private label survives a mode change. Android disables obsolete pinned actions; a launcher may retain their old labels. No shortcut contains a transition code.


> **Status:** Accepted implementation contract; physical-device and release gates remain separate

Touch and hold Chur's Home Screen or launcher icon to open the system menu. Outside Discreet Mode, Chur offers four fixed commands in priority order: **Import**, **Search**, **Lock and hide**, and **New private note**. A launcher may display fewer commands. The same capabilities are available inside the app.

## Commands

In Discreet Mode, **Chur** opens the public pet scene. It has no private guide, saved game progress, or authentication authority. The in-game moon gesture still uses the separate transition-code and normal authentication flow. A system launcher can display fewer than all four public actions.

- **Import** opens the existing import-source chooser for All items after normal vault authentication. The user still selects a source and files. No content is imported automatically.
- **Search** opens the private media/file Search destination, clears its query and filters, and focuses the search field. It does not search public Notes or private-note bodies, and does not start OCR.
- **Lock and hide** immediately replaces private navigation with the selected public shell or, when whole-app lock is enabled, the neutral app-unlock screen. It performs panic lock, cancels private work and invalidates the session. It requires no credential, overrides busy navigation, and does not delete content or change the launcher icon. This launcher command brings the app to the foreground; the in-app panic control and Android Quick Settings tile remain separate immediate lock controls.
- **New private note** opens an empty editor inside the credential-selected vault. It creates no saved record until Save. Public Notes and their storage are separate.

## Authorization and lifecycle

Import, Search, and New private note are navigation requests, not authentication factors. A cold request waits for successful native handshake and controller startup. With no vault, it opens public Settings and asks the user to set up Chur; it never creates a vault automatically. A locked vault uses its ordinary password/PIN, enrolled device factor, or recovery flow. A private command already authorized in an open idle session uses that session without another prompt.

The optional whole-app gate normally authenticates a temporary vault session, closes it, and opens the public shell. An explicit pending private launcher command is the exception: successful authentication enters that same session's requested private workflow directly. It does not authenticate a second time or choose an identity on the user's behalf. Ordinary launches keep their existing public-shell behavior. See [Provisioning](../security/PROVISIONING.md).

Requests and delivery tokens exist only in process memory. A request is delivered once to the current session generation and is not restored from a saved activity intent, scene state, or preferences. Canceling the gate, leaving it, lock, panic, identity change, and process death discard it. A failed credential can be retried while the same gate is active. Recovery retains the command only while its authorized flow continues. Startup failure does not dispatch it.

A newer command may replace an undispatched command at an idle authentication gate. It cannot silently interrupt a note editor, camera review, viewer/selection, picker, private dialog, recovery/setup, import/export, or incoming-share workflow. The app asks the user to finish or cancel that workflow first and discards the new navigation request. An accepted incoming share cancels deferred launcher navigation. Lock and hide always takes priority and deliberately discards unsaved private drafts under the existing panic-lock contract.

## Public metadata

The menu is the same whether the vault is absent, locked, open, or opened by either identity's credential. Titles, icons, ranks, IDs, and intent actions describe fixed capabilities only. They contain no filenames, thumbnails, album names, counts, queries, private-note text, vault identifiers, or session/credential material. The menu visibly describes Chur's vault functionality; it is not a secret entrance or a promise of concealment from the platform.

## Platform behavior

iOS registers the four current Home Screen Quick Actions dynamically after startup and refreshes them when Discreet Mode changes. It does not declare static private actions. The UIKit scene handles both initial connection and an existing scene. Private navigation waits until the scene is active. Panic masking covers the native window until neutral shared UI is rendered; a native sheet is dismissed without accepting its delayed private result.

Android publishes four native dynamic App Shortcuts after foreground startup and refreshes their localized metadata after locale changes. Dynamic publication runs off the UI thread and does not depend on session state. It preserves the single activity using `SINGLE_TOP | CLEAR_TOP`; static shortcuts' forced task clearing is not used. Restored installations republish on first launch. A launcher may let the user pin a command; pinned invocation uses exactly the same authorization rules. The activity is exported, so any app can send it a command action. Each shortcut intent therefore carries one random per-install launcher token, stored in `noBackupFilesDir`. The app runs a command only when the intent carries that token; an intent without it is an ordinary launch. The token names no vault, identity, or session. Only the launcher that holds the shortcut can read it, so another app cannot force Lock and hide or open a private route. A restored installation makes a new token and republishes its shortcuts. No assistant integration, private system indexing, or programmatic pinning prompt is added.

## Validation boundary

Controller tests use the real vault runtime for authentication, cancellation, identity/session isolation, command delivery, and panic preservation of saved data. Android native/UI tests cover published shortcut metadata and route behavior. Swift tests cover the native allowlist, dispatch lifecycle, and system registration. Whole-app compilation checks the shared/native integration. These checks do not replace supported physical-device, OEM-launcher, signing, or release acceptance.

The [research and original implementation plan](../research/APP_ICON_ACTIONS_2026-10-18.md) records official platform sources and the reasons for different registration methods.
