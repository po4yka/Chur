# The iOS host application

This directory holds the Xcode project that presents the Compose Multiplatform
framework. It is the composition root for iOS, in the sense
[`../../docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md) §9 gives the term:
the place that binds implementations, and the only place that may.

## What the Gradle build produces

```sh
./gradlew :shared:app:linkDebugFrameworkIosSimulatorArm64
```

The framework is `shared/app/build/bin/iosSimulatorArm64/debugFramework/ChurApp.framework`.
It is static and links `libchur_ffi.a`, which the same build compiles with Cargo
for the matching target. iOS loads no JNI adapter: Kotlin/Native reaches the C
ABI through cinterop, and [ADR-0040](../../docs/adr/0040-add-a-rust-jni-adapter-crate.md)
exists for Android only.

## What the project must do

Seven things, and each one is a requirement of a normative document rather than a
preference.

1. **Create one `ChurController`.** Its storage root is `churStorageRoot()` and
   its note store is `churNoteStore()`; both functions are
   exported by the framework so the two hosts agree on where each store lives.
   [`../../docs/product/DISCREET_MODE.md`](../../docs/product/DISCREET_MODE.md)
   requires a shell that keeps what a person writes in it, which is why the note
   store is bound here rather than left at its in-memory default. Its
   device-unlock binding is `NoDeviceUnlock.shared`: the Apple device slot needs
   no platform call during unlock, because Rust performs its AEAD, so
   [ADR-0041](../../docs/adr/0041-the-android-keystore-slot-exchanges-root-bytes.md)
   applies to Android alone.

2. **Present `ChurViewController`.** It is exported by the framework and takes
   the controller and the gate verdict of [`../../docs/interop/FFI_CONTRACT.md`](../../docs/interop/FFI_CONTRACT.md)
   §2. A library that fails the gate is not called again in the process, so the
   verdict is computed once at launch and the vault route is never composed
   after a refusal.

3. **Attach the privacy cover on `sceneWillResignActive` and remove it on
   `sceneDidBecomeActive`.** `IosPrivacyCover` is exported for this.
   [`../../docs/security/PLAINTEXT_LIFECYCLE.md`](../../docs/security/PLAINTEXT_LIFECYCLE.md)
   §1 puts the app-switcher snapshot in the forbidden column, and iOS takes that
   snapshot after `willResignActive`: a cover attached later is attached after
   the picture was taken.

4. **Lock on backgrounding.** The same transition calls the repository's
   background hook, which locks under the default policy of
   [`../../DESIGN.md`](../../DESIGN.md) §14.

5. **Present `PHPickerViewController` for import and write exports through the
   share sheet.** `IosMediaCodec` takes the resulting file URL. §8 of
   [`../../docs/interop/MEDIA_PIPELINE.md`](../../docs/interop/MEDIA_PIPELINE.md)
   keeps the decoded derivative only long enough to encrypt it, so the picker's
   temporary copy is closed as soon as the import commits.

6. **Register the background refresh task.** `IosSyncBackground` is exported
   for this. Call `IosSyncBackground.register(controller)` once in
   `application:didFinishLaunchingWithOptions:` — the system refuses a
   registration made later — and `IosSyncBackground.schedule()` each time the
   scene resigns active, beside the privacy cover of step 3. The task's launch
   runs one sync cycle through the shared controller, which
   [`../../docs/sync/SYNC_PROTOCOL_V1.md`](../../docs/sync/SYNC_PROTOCOL_V1.md)
   §7 permits while the vault is locked: only signed opaque bytes move, and
   the next unlock applies what the task staged. The task must always reach
   `setTaskCompleted`, and the exported handler does, on success, on failure,
   and on the expiration handler the system calls when the runtime it granted
   runs out.

   The controller also needs its sync engine bound, the way Android binds one:
   construct `SyncCoordinator(store = churSyncStateStore(), …)` — the exported
   `churSyncStateStore()` places the file beside the vault root, in the
   directory excluded from iCloud and iTunes backup, because the file holds
   the device's transport token — and pass it as the controller's `sync`
   parameter.

7. **Present `UIDocumentPickerViewController` for restore.** `IosBackupPicker`
   is the seam and the framework exports it. Install it once, beside the task
   registration of step 6:

   ```swift
   import UniformTypeIdentifiers

   IosBackupPicker.shared.present = { answer in
       let picker = UIDocumentPickerViewController(
           forOpeningContentTypes: [UTType.data], asCopy: true)
       // The delegate holds `answer` and calls it exactly once: with the first
       // URL's `path` on a choice, and with `nil` on a dismissal. A delegate
       // that answers twice restores twice; one that never answers leaves the
       // background lock suppressed for the life of the process.
       picker.delegate = self.backupPickerDelegate(answering: answer)
       self.present(picker, animated: true)
   }
   ```

   `asCopy: true` is what makes the path usable, and for two reasons. The copy
   is in this application's temporary directory, so it needs no security-scoped
   access held open across a restore whose end the host cannot see; and it is a
   local file, so it seeks. §8 of
   [`../../docs/format/BACKUP_FORMAT_V1.md`](../../docs/format/BACKUP_FORMAT_V1.md)
   reads the package from both ends - the preamble first and the length last -
   which a stream from a provider cannot serve.

   What the copy holds is the encrypted package, not plaintext, so
   [`../../docs/security/PLAINTEXT_LIFECYCLE.md`](../../docs/security/PLAINTEXT_LIFECYCLE.md)
   §1 is unaffected by it. Delete it after the restore reports its result, or
   leave it to the system's cleanup of `tmp`.

   Nothing else is needed. The framework opens the descriptor, takes the
   password from its own screen, and closes what it opened, so the password
   never crosses into the host.

## Info.plist

The project requests no photo-library permission. `PHPickerViewController` runs
out of process and returns only what the user chose, so
[`../../docs/security/PROVISIONING.md`](../../docs/security/PROVISIONING.md) §8's
"request a permission the flow does not use" is satisfied by requesting none.

Two background-sync entries belong here, because the refresh task of step 6
needs them and nothing else provides them:

```xml
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
    <string>dev.po4yka.chur.sync.refresh</string>
</array>
<key>UIBackgroundModes</key>
<array>
    <string>fetch</string>
</array>
```

The identifier must match `IosSyncBackground.TASK_IDENTIFIER` exactly, and the
`fetch` mode is what lets the system launch the host for the task at all.

The deployment target is the one [ADR-0017](../../docs/adr/0017-freeze-the-supported-device-set.md)
freezes, and it must match `iosDeploymentTarget` in `gradle/libs.versions.toml`:
the vendored C of [ADR-0038](../../docs/adr/0038-adopt-sqlcipher-as-the-v1-catalog-engine.md)
is compiled against it, and a mismatch fails the link on `___chkstk_darwin`
rather than at run time.

## What is not here

The `.xcodeproj` itself. It is generated by Xcode and is not checked in, so this
file is the specification a developer builds it from rather than a project that
would need regenerating whenever Xcode's format changes. Everything the project
consumes — the framework, the exported entry point, the privacy cover, and the
media codec — is built and linked by the Gradle build and is checked by the
`kotlin-native` job of the enforcing workflow.
