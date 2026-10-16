# Chur for iOS

`Chur.xcodeproj` is the UIKit host for the shared Compose application. The
checked-in project builds without XcodeGen; `project.yml` is its source for
future project changes. Regenerate it with:

```sh
xcodegen generate --spec apps/iosApp/project.yml --project apps/iosApp
```

## Build

Use Xcode 27, the Rust targets `aarch64-apple-ios-sim` and
`aarch64-apple-ios`, and the repository's Gradle wrapper. Open
`apps/iosApp/Chur.xcodeproj` and select the `Chur` scheme, or run:

```sh
xcodebuild -project apps/iosApp/Chur.xcodeproj -scheme Chur \
  -configuration Debug -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO build

xcodebuild -project apps/iosApp/Chur.xcodeproj -scheme Chur \
  -configuration Debug -destination 'generic/platform=iOS' \
  CODE_SIGNING_ALLOWED=NO build
```

The first build phase selects the matching Gradle framework and Rust static
library for the Xcode platform and configuration. The application links one
Rust archive. Its deployment target is iOS 18.0, matching
`gradle/libs.versions.toml`. Local builds do not request signing or change an
Apple Developer account. Device installation needs the user's existing signing
setup supplied to Xcode at build time.

The unsigned commands check compilation. To run the incoming-share flow in
Simulator, replace `CODE_SIGNING_ALLOWED=NO` with
`CODE_SIGNING_ALLOWED=YES CODE_SIGNING_REQUIRED=YES CODE_SIGN_IDENTITY=-`.
Xcode embeds the App Group entitlements for Simulator with ad-hoc signing.
This does not register capabilities or change an Apple Developer account.

## Host bindings

`AppDelegate` checks the native ABI before any runtime opens, creates one
controller, excludes the vault and sync-state directories from backup, and
registers the background refresh task. `SceneDelegate` presents
`ChurViewController`, covers the app-switcher snapshot before resigning active,
locks on backgrounding, and schedules refresh. A background launch can open the
runtime without opening a vault session; signed ciphertext is staged until
unlock.

The host presents PhotosUI and Files pickers. Each selected file is copied
before the provider callback ends. A Files pick is answered once. A PhotosUI
pick is loaded and answered one item at a time: the host reports the fetch of
each item and stops it on a cancel, and Kotlin deletes the copy after its
import, before it loads the next item. Export uses a protected
temporary file for the Files picker, the share sheet, or Photos. Completion and
the next launch remove abandoned plaintext copies. The host requests add-only
Photos access when the user saves an export there; PhotosUI import needs no
photo-library permission because it grants access only to selected items.
After an import, the host requests read-write Photos access only if the user
chooses to review the deletion of the originals, `IOS.md` §15.4. iOS asks for
local network access by itself when sync first connects to a server on the
local network, usually at setup, and `NSLocalNetworkUsageDescription` is the
reason it shows. Every sync run to such a server with the vault open (setup,
Sync now, and the pull after an unlock) runs as a system prompt, so the alert
does not lock the vault under it, `IOS.md` §21.2 and §27. A sharing publish or
revocation does not run as a prompt, so the vault locks when one of them is the
first connection to a local address, `IOS.md` §27.

`Info.plist` contains the background task identifier, `fetch` mode, scene
configuration, and Compose's required frame-duration setting. Keep these in
`project.yml` so project regeneration preserves them.

## App icon

`Assets.xcassets` is a target source in `project.yml`, and `ASSETCATALOG_COMPILER_APPICON_NAME` selects `AppIcon`. The checked-in `AppIcon.appiconset` contains Default, Dark, and Tinted appearances of the selected Chur boundary marker.

## Incoming system shares

`ChurShare.appex` is embedded in the application. Its Share extension accepts
up to 64 files, images, movies, audio items, text items, or URLs per request.
The user selects **Save to Chur**, then opens Chur to unlock and choose a vault
and album. The extension returns to the source app. It does not use private
APIs to launch Chur. Links are stored as UTF-8 text and are never fetched.
Source files are never removed.

The application and extension declare only the App Group
`group.dev.po4yka.chur.incoming`. A signed device or distribution build must
register this group for both bundle IDs (`dev.po4yka.chur` and
`dev.po4yka.chur.share`) in the existing Apple signing setup. Local unsigned
builds do not register capabilities or change an Apple Developer account.
Open Chur once after installation to prepare the inbox. An unavailable group
or locked Keychain fails closed; the extension explains how to retry.

The app creates a separate RSA-2048 ingress private key in its own Keychain,
with `WhenUnlockedThisDeviceOnly` accessibility. The extension receives only
its public key. No vault key, password, session, or catalog is shared. Each
item has a fresh AES-256-GCM key, wrapped with RSA-OAEP-SHA256. Encrypted,
bounded metadata contains the original name, MIME type, and byte length.
One MiB authenticated chunks bind the envelope header and chunk position.
Random batch names and numbered ciphertext filenames reveal no source names.
Only `.ready` batches are consumed; publication is an atomic directory rename
after every item is encrypted and synchronized. A POSIX lock coordinates
writers and readers. Completed ciphertext entries are immutable by contract.
The app authenticates every frame, exact byte length, order, and end of file
before exposing a source to Rust; a pipe end cannot commit a malformed prefix.

Inbox files have Complete file protection and are excluded from backup.
The encrypted queue has at most eight batches, 64 items per batch, 4 GiB per
item, and 8 GiB in total, including envelope overhead. Text and URL payloads
are limited to 1 MiB. Abandoned and completed encrypted batches expire after
24 hours and are removed when the inbox is next used. Storage exhaustion,
provider failure, cancellation, or timeout does not publish a partial batch.

No document or text import creates plaintext scratch: authenticated bytes
flow through a pipe to the existing file importer. ImageIO and AVFoundation
require file URLs for media probes and derivatives, so only images, movies,
and audio may use `chur-incoming-media` protected, random-named,
backup-excluded scratch. This directory shares the existing 4-entry, 4 GiB
per-entry, 8 GiB total budget with import and export scratch. A file outside
that budget is imported as an opaque file through the pipe. The existing
importer owns every descriptor and media scratch lease until native custody
drains; cancellation and lock stop production and remove plaintext. Launch
removes abandoned media scratch. Unsupported media retains its original bytes
as a file. Queued ciphertext may remain while locked; it contains no plaintext.

The public prompt contains only a count. Names and destination choices appear
only after unlock. Closing the source after an import attempt or selecting
Discard removes the encrypted batch, including failed items. A failed item
must be shared again; successful items are not silently duplicated by a retry.
No payload, URL, or filename is written to public Notes, preferences, saved UI
state, diagnostics, or notifications. Flash deletion and managed text buffers
do not provide a physical-erasure guarantee. An App Group peer belongs to the
same trusted signing team; this design does not defend against a malicious
replacement extension signed with that authority.
