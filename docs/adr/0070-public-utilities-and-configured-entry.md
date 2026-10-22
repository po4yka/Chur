# ADR-0070: Public Utilities and Configured Entry

- **Status:** Accepted
- **Date:** 2026-10-22
- **Decision owners:** @po4yka
- **Related:** [Discreet Mode](../product/DISCREET_MODE.md), [Android](../ANDROID.md), [iOS](../IOS.md)

## Context

The public calculator, notes, and camera must support normal daily use. Branded controls, visible private settings, and external capture made those tools incomplete. The owner approved a configured transition code that replaces the visible private entry only after explicit setup.

## Decision

Use a neutral shared Compose theme. Keep existing Markdown notes and public JPEGs. Store calculator history in versioned public JSON. Unknown versions remain untouched.

An eight-digit code routes to existing authentication. A real public-app submission and successful authentication are required to enable the mode. Code input is transient; persisted configuration contains a digest and is excluded from backup. Holding About's version row provides a documented, accessible fallback to the same authentication boundary.

Android activity aliases select the public name and icon. Enable the new alias before disabling the old one. iOS alternate icons retain the installed app name. Shortcuts in the mode expose only public capabilities. System app identity and existing launcher records are outside the concealment claim.

Use pinned CameraX on Android and AVFoundation on iOS. Capture saves into the existing public camera directory. Pending files become gallery entries only after validation and atomic publication. Native adapters own permission, lifecycle, capture, playback, and export. The Swift bridge carries paths and metadata, never a complete video.

## Alternatives considered

Keep the visible private entry permanently: rejected because the approved product contract requires ordinary public Settings to contain public functions only after opt-in.

Continue to use system camera pickers: rejected because they cannot provide the integrated viewfinder and capture controls. Add another cross-platform camera framework: rejected because the platform adapters already provide the required boundary.

## Consequences

CameraX adds a maintained Apache-2.0 Android dependency, pinned in the version catalog and dependency locks. AVFoundation uses the platform SDK. Photos and videos remain unencrypted application data and participate in system backup. Explicit export can also enter system photo synchronization.

Changing aliases can relocate shortcuts or leave stale launcher labels. Alternate icons can show a system confirmation. Neither behavior removes package identity. Physical Android and iPhone camera acceptance is distinct from build, host, and simulator checks. For this implementation task the owner waived physical iPhone acceptance because no iPhone is available; that check has not been executed and remains required before a device-readiness claim.
