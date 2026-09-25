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

## Host bindings

`AppDelegate` checks the native ABI before any runtime opens, creates one
controller, excludes the vault and sync-state directories from backup, and
registers the background refresh task. `SceneDelegate` presents
`ChurViewController`, covers the app-switcher snapshot before resigning active,
locks on backgrounding, and schedules refresh. A background launch can open the
runtime without opening a vault session; signed ciphertext is staged until
unlock.

The host presents PhotosUI and Files pickers. Each selected file is copied
before the provider callback ends and answered once. Export uses a protected
temporary file and the system share sheet; completion and the next launch
remove abandoned plaintext copies. The host requests no photo-library
permission because PhotosUI grants access only to selected items.

`Info.plist` contains the background task identifier, `fetch` mode, scene
configuration, and Compose's required frame-duration setting. Keep these in
`project.yml` so project regeneration preserves them.
