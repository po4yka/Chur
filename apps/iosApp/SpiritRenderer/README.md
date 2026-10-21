# Chur Metal renderer

The standard `Chur.xcodeproj` and `project.yml` include this renderer. No alternate project or feature compilation flag is required from the caller. The public Notes UI still starts with the companion off; choose **Show Chur**.

`ChurSpiritRendererInstaller` installs the narrow Kotlin host factory before Compose starts. The exported symbols `IosSpiritRenderer` and `IosSpiritRendererHost` are part of the existing framework export gate. Native rendering receives only public art/presentation values. UIKit owns a transparent CAMetalLayer; Compose owns the gesture and accessibility target.

## Build

Use the repository's Apple/Rust/Kotlin prerequisites from DEVELOPMENT.md. The normal app prebuild runs `stage-filament.sh`, which invokes `prepare-filament.sh`, verifies the official Filament 1.77.3 SDK SHA-256 and stages the selected device/Simulator archives in DERIVED_FILE_DIR. There are no credentials, developer-account changes or newly generated signing identities.

    xcodebuild -project apps/iosApp/Chur.xcodeproj -scheme Chur \
      -configuration Debug -destination 'generic/platform=iOS Simulator' \
      CODE_SIGNING_ALLOWED=NO build

An already-downloaded archive can be supplied with `FILAMENT_ARCHIVE=/path/to/filament-v1.77.3-ios.tgz`; the same checksum is mandatory. `.vendor/` is ignored. Project source and checked-in PBX settings must stay in sync. Do not commit downloaded libraries or build outputs.

## Runtime and validation

The adapter requests 30fps and caps drawable density at 2×/1024px. The shared native visual contract uses no shadows/bloom/postprocessing. Visibility/lifecycle gates stop rendering and disposal releases native resources. An active, attached but hierarchy-hidden view uses a weak-owner 200ms visibility timer solely for resumption; the timer is canceled on inactivity, detach and disposal.

A missing asset or native initialization failure shows the PNG fallback. It is not animated 3D. The final GLB is the same asset as Android; manifest and exact per-joint axes are bundled.

C++ header/syntax and cross-language math checks are only partial Linux validation. Swift/Objective-C++/Kotlin-Native linking, Metal appearance, actual UIKit/Compose touch delivery, privacy covers and device performance require the Apple CI/native-device gates described in `docs/INTERACTIVE_SPIRIT.md`.
