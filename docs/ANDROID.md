# Chur Android Platform Architecture

> **Status:** proposed platform architecture for implementation  
> **Audience:** Android, KMP, Rust, security, QA, and release-engineering contributors  
> **Last updated:** 2026-08-26  
> **Related:** [Project README](../README.md) · [System architecture](ARCHITECTURE.md) · [iOS architecture](IOS.md)

Chur is a local-first private media archive with a functional public shell and a Rust-owned encrypted vault. This document defines the Android-specific implementation boundary: application startup, platform services, Android Keystore integration, biometric authorization, storage placement, media import and playback, lifecycle locking, task privacy, background execution, native packaging, testing, and release requirements.

The system architecture remains authoritative for cryptographic formats, key hierarchy, object containers, integrity, private catalog ownership, migrations, real/decoy separation, and synchronization semantics. Android code MUST NOT reimplement those rules.

Chur is currently in architecture and protocol design. Nothing in this document is a completed audit or a production security guarantee.

---

## 1. Normative language

The key words **MUST**, **MUST NOT**, **REQUIRED**, **SHOULD**, **SHOULD NOT**, and **MAY** describe platform requirements.

Individual statements are classified as **Decision**, **Invariant**, **Proposal**, **Deferred**, or **Non-goal**, defined once in [`README.md`](README.md#statement-classification). An **Invariant** here is a property every Android implementation preserves. The status of this document as a whole is in its header and uses the document-status vocabulary of the same file.

Byte-exact cryptographic behavior belongs to dedicated Rust format specifications. Android owns platform policy and transport to the Rust boundary, not vault bytes.

---

## 2. Android responsibilities

The Android target owns:

- process and activity startup;
- Compose host creation and edge-to-edge window setup;
- lifecycle and device-lock signals;
- Android Keystore key operations;
- `BiometricPrompt` and device-credential UX;
- Photo Picker, Storage Access Framework, and content-provider integration;
- file-descriptor acquisition and ownership transfer;
- Media3 playback integration;
- task, screenshot, notification, and clipboard privacy policy;
- app-private filesystem placement and backup exclusions;
- WorkManager and foreground-execution policy;
- Rust native library packaging for Android ABIs;
- Android-specific tests, performance measurement, and release checks.

Android MUST NOT own:

- private catalog schema;
- key-slot parsing;
- Argon2id parameters or execution;
- vault, collection, object, content, metadata, or preview keys;
- object-container serialization;
- nonce construction or AAD construction;
- integrity decisions;
- real/decoy classification in ordinary feature code;
- sync-operation canonicalization;
- private metadata persistence in Room, DataStore, `Bundle`, `SavedStateHandle`, preferences, or analytics.

---

## 3. Planned Android baseline

| Area | Planned choice |
| --- | --- |
| Application ID | `dev.po4yka.chur` |
| Compile SDK | Android API 37 |
| Target SDK | Android API 37 |
| Minimum SDK | API 23, subject to a final support ADR |
| Kotlin | Kotlin 2.4.10 / K2 |
| UI | Compose Multiplatform 1.11.1 hosted by a thin Android shell |
| Navigation | Navigation 3 Multiplatform |
| State | AndroidX ViewModel, StateFlow, Flow, UDF/MVVM |
| Public persistence | Room 3.0.1 KMP and DataStore KMP |
| Images | Coil 3.5 with a separate private loader |
| Media | AndroidX Media3 |
| Background work | WorkManager for policy-approved jobs |
| Native core | Rust library through an isolated JNI/C-ABI adapter |
| Build | Gradle version catalog, convention plugins, KSP, pinned NDK/toolchains |

Version numbers are implementation targets rather than protocol identifiers. Upgrading Android, Kotlin, Compose, Media3, the NDK, or Rust dependencies MUST NOT silently change vault bytes or security policy.

### 3.1 ABI policy

The initial development matrix SHOULD include:

- `arm64-v8a` for physical devices;
- `x86_64` for emulator and CI coverage where supported.

Support for 32-bit ABIs requires a separate size, performance, and maintenance decision. Release artifacts MUST fail the build when a declared ABI is missing its Rust library.

### 3.2 Device capability tiers

Chur distinguishes capability from policy:

```text
Baseline
    Android Keystore available
    secure lock screen available
    app-private storage available

Enhanced
    hardware-backed Keystore key
    strong biometric available

Maximum
    StrongBox-backed wrapping key available
```

A higher tier MAY improve protection of the platform wrapping key, but it MUST NOT alter the canonical vault format or make data unrecoverable without an explicit user choice.

---

## 4. Runtime architecture

```text
┌──────────────────────────────────────────────────────────────┐
│ Android process                                              │
│ Application · MainActivity · platform services               │
├──────────────────────────────────────────────────────────────┤
│ Compose Multiplatform host                                   │
│ public shell · session gate · private vault UI               │
├──────────────────────────────────────────────────────────────┤
│ KMP application layer                                        │
│ ViewModels · UDF · navigation · coordinators                 │
├──────────────────────────────────────────────────────────────┤
│ Android platform adapters                                    │
│ Keystore · biometrics · pickers · FDs · Media3 · lifecycle   │
├──────────────────────────────────────────────────────────────┤
│ Rust bridge                                                  │
│ control plane · opaque handles · streaming data plane        │
├──────────────────────────────────────────────────────────────┤
│ Rust Vault Runtime                                           │
│ keys · catalog · objects · media · integrity · migrations    │
└──────────────────────────────────────────────────────────────┘
```

The Android shell stays deliberately thin. Shared application code owns user-visible state and navigation; Android adapters expose capabilities through narrow KMP interfaces.

---

## 5. Planned module boundaries

```text
apps/androidApp/
├── application startup
├── activity/window setup
├── manifest and resources
├── Android composition root
└── release configuration

shared/core-platform/
├── expect platform contracts
└── platform-neutral models

shared/core-platform-android/
├── KeystoreRootKeyProtector
├── AndroidUserAuthenticator
├── AndroidMediaPicker
├── AndroidReadHandleFactory
├── AndroidExportDestination
├── AndroidLifecycleSignals
├── AndroidTaskPrivacyController
└── AndroidBackgroundScheduler

shared/core-media/
├── private image-loader contracts
├── player coordination
└── opaque media-source models

rust/crates/chur-ffi/
└── stable Android-facing native ABI
```

Feature modules MUST depend on interfaces in shared code. They MUST NOT import `android.security.keystore`, `BiometricPrompt`, Media3 `DataSource`, JNI symbols, or filesystem APIs directly.

---

## 6. Application and activity startup

### 6.1 Application

The `Application` class SHOULD perform only non-secret initialization:

1. configure privacy-safe logging;
2. initialize the long-lived public and platform dependency graphs;
3. load the Rust library and negotiate the native API version;
4. register process lifecycle signals;
5. schedule ciphertext-only cleanup or reconciliation that is safe while locked;
6. expose a fatal startup state if native compatibility checks fail.

The `Application` class MUST NOT unlock a vault, open the private catalog, derive password keys, preload private thumbnails, or restore private navigation.

### 6.2 Main activity

`MainActivity` is a thin `ComponentActivity` that:

- configures edge-to-edge rendering;
- installs Compose content;
- forwards lifecycle and window privacy signals;
- hosts `BiometricPrompt`, picker, export, and permission launchers;
- provides the Android platform composition root;
- renders a neutral surface before private state can be snapshotted.

The activity MUST always be able to start in `PublicLocked`, even when the previous process died with an open viewer or active player.

### 6.3 Process restoration

Android state restoration MAY restore:

- the public shell route;
- public notes/calculator state through Room;
- theme, locale, and non-secret preferences;
- an indication that an interrupted encrypted transaction requires Rust reconciliation.

It MUST NOT restore:

- private back-stack entries;
- filenames, album names, search queries, or object IDs;
- active reader handles;
- unlock credentials;
- a boolean revealing real versus decoy identity;
- a viewer or player position that can identify private media.

---

## 7. Dependency graphs

```text
AndroidApplicationGraph
├── PublicGraph                    Koin classic DSL
│   ├── public repositories
│   ├── Room
│   ├── DataStore
│   ├── public navigation
│   └── non-sensitive schedulers
│
├── PlatformGraph                  long-lived explicit bindings
│   ├── user authentication
│   ├── Keystore operations
│   ├── media picker
│   ├── file-descriptor factory
│   ├── task privacy
│   └── lifecycle signals
│
└── SecureGraph                    manual, session scoped
    ├── VaultSessionHandle
    ├── VaultEngine
    ├── private repositories
    ├── private image loader
    ├── player coordinator
    └── session caches
```

The secure graph is constructed only after Rust returns an authenticated session handle. Its destruction order is part of locking and MUST remain explicit.

Koin MUST NOT own root, collection, object, stream, password, recovery, or platform-unwrapped secret values.

---

## 8. Platform contracts

Representative KMP-facing contracts:

```kotlin
interface PlatformUserAuthenticator {
    suspend fun authorize(request: AuthenticationRequest): AuthenticationResult
}

interface PlatformRootKeyProtector {
    suspend fun createSlot(request: PlatformSlotCreation): PlatformSlotResult
    suspend fun unwrapSlot(request: PlatformSlotUnlock): PlatformSlotResult
    suspend fun deleteSlot(reference: PlatformSlotReference)
}

interface PlatformMediaPicker {
    suspend fun pick(request: PickerRequest): List<PlatformMediaSelection>
}

interface PlatformReadHandleFactory {
    suspend fun open(selection: PlatformMediaSelection): PlatformReadHandle
}

interface PlatformTaskPrivacyController {
    fun coverPrivateContent()
    fun revealAllowedContent()
    fun setScreenCapturePolicy(policy: ScreenCapturePolicy)
}
```

Contracts expose capability and redacted errors. They do not expose raw Keystore aliases or filesystem paths to feature code.

---

## 9. Android Keystore model

Android Keystore protects a short device-bound wrapping capability. It does not encrypt media and does not replace the Rust key hierarchy.

### 9.1 Root-key flow

```text
Rust VaultRootSecret
        │
        ▼
Android Keystore AES wrapping operation
        │
        ▼
PlatformKeySlot envelope stored as opaque vault state
```

Unlock:

```text
user authorization
        ↓
Keystore permits AES unwrap/decrypt
        ↓
short VaultRootSecret buffer
        ↓
Rust accepts and copies into a zeroizing session secret
        ↓
Android buffer is cleared best effort
```

The non-exportable Keystore key remains inside the platform key service. The unwrapped `VaultRootSecret` necessarily exists in the Chur process after unlock and is not protected from a fully compromised unlocked OS.

### 9.2 Proposed key policy

The platform wrapping key SHOULD use:

- AES with GCM;
- encrypt and decrypt purposes only;
- randomized encryption required;
- no application-supplied IV reuse;
- explicit user-authentication policy;
- optional StrongBox request;
- an opaque alias unrelated to `real`, `decoy`, filenames, or account names.

Exact `KeyGenParameterSpec` settings require an ADR covering biometric-only, device-credential fallback, per-use authorization, and short validity windows.

### 9.3 Authentication modes

Chur may expose product modes such as:

| Mode | Platform behavior | Recovery requirement |
| --- | --- | --- |
| Convenient | biometric or device credential with a short validity window | independent password/recovery slot strongly recommended |
| Strict | per-use authentication for every platform unwrap | independent password/recovery slot required for recoverable vaults |
| Maximum | strict mode plus StrongBox when available | fallback path required |

A mode change creates or replaces the platform slot. It MUST NOT re-encrypt media.

### 9.4 Key invalidation

The platform key may become unavailable after:

- secure lock-screen removal or reset;
- biometric enrollment changes under an invalidating policy;
- OS or device security events;
- application data restoration without the original hardware key;
- Keystore failure.

Required behavior:

1. classify the internal error as platform-key unavailable or invalidated;
2. present a non-oracular recovery message;
3. request password or recovery-key unlock;
4. ask Rust to open the same vault through an independent key slot;
5. create a new device-bound platform slot after explicit user authorization;
6. remove the unusable slot only after the replacement commits.

Platform-key failure MUST NOT be reported as catalog corruption without evidence.

### 9.5 StrongBox

StrongBox is optional:

- attempt it only in the user-selected maximum-security mode or after policy review;
- catch `StrongBoxUnavailableException` and capability mismatches;
- fall back to a hardware-backed or ordinary Keystore key according to policy;
- record only a non-sensitive capability result;
- never create an unrecoverable vault merely because StrongBox was requested.

---

## 10. Biometric and credential UX

`BiometricPrompt` authorizes a Keystore operation; biometric data is never converted into a key.

The authentication coordinator MUST:

- bind a prompt to the exact pending platform operation;
- avoid retaining Activity or Fragment references beyond lifecycle;
- handle cancellation, lockout, negative action, process loss, and configuration change;
- avoid exposing whether a credential maps to a real vault, decoy vault, or no vault;
- return redacted, stable outcomes to KMP;
- clear any temporary unwrapped buffer after Rust accepts it.

The public UI SHOULD use equivalent timing and wording for failed real, failed decoy, and malformed unlock attempts where practical. Exact resistance to timing analysis is not guaranteed on a general-purpose mobile OS.

Biometric convenience MUST NOT be the sole recovery mechanism for a recoverable vault.

---

## 11. Real and decoy vault identities

Android platform state for real and decoy vaults is independent:

```text
VaultDescriptor A
├── random platform-key alias
├── random storage namespace
├── independent root slot
└── independent session generation

VaultDescriptor B
├── unrelated random platform-key alias
├── unrelated random storage namespace
├── independent root slot
└── independent session generation
```

Android code MUST NOT use aliases such as:

```text
chur_real_key
chur_decoy_key
private_vault
hidden_photos
```

Ordinary features receive only an opaque `VaultSessionHandle`. A diagnostic tool may distinguish identities only under an explicitly privileged, security-reviewed workflow.

Decoy mode is a coercion-resistant UX feature, not a cryptographic claim that the second vault is undetectable under filesystem or forensic analysis.

---

## 12. Storage layout

Suggested platform placement:

```text
filesDir/
├── public/                         public Room-owned state as designed
└── vaults/
    ├── <opaque-vault-id>/          encrypted catalog, objects, temporary import state
    └── <opaque-vault-id>/

noBackupFilesDir/
└── device/
    ├── opaque platform references
    ├── local nonportable identity state
    └── recovery-independent device metadata

cacheDir/
├── encrypted-transfer-cache/
└── plaintext-scratch/              allowed only by explicit policy
```

Directory names MUST NOT reveal real/decoy identity, media type, album, filename, account, or object count beyond unavoidable filesystem metadata.

### 12.1 Private catalog and objects

- Rust opens and migrates the private catalog.
- Media containers remain immutable after commit.
- Object-key envelopes are mutable catalog/key-domain state.
- Room never opens the private catalog.
- Android file APIs provide paths or file descriptors only to the Rust adapter and composition root.

### 12.2 Plaintext scratch

When a platform API requires a real file:

- create it under app-private `cacheDir`;
- use a random opaque filename;
- exclude it from backup;
- set restrictive permissions;
- expose it only for the minimum operation;
- revoke grants when possible;
- delete it immediately after completion;
- clean interrupted scratch on startup;
- never promise physical overwrite on flash storage.

---

## 13. Backup and device transfer

Android Auto Backup and device-to-device transfer rules MUST be explicit.

### 13.1 Device-bound state

The following MUST be excluded:

- Keystore-dependent aliases or envelopes that cannot work on another device;
- local platform-authentication state;
- nonportable device identity private state;
- plaintext scratch;
- decrypted caches;
- temporary import containers, so a restored vault finds no resumable import transaction and marks each open one dead per [`format/OBJECT_CONTAINER_V1.md`](format/OBJECT_CONTAINER_V1.md) §14.4.

### 13.2 Portable encrypted state

Encrypted catalog and object containers MAY be included only when the vault also has an independent portable recovery path, such as a password or recovery slot.

Restoring ciphertext without a usable key slot is not recovery.

### 13.3 Required restore behavior

After restore on a new device:

1. start locked;
2. ignore unusable device-bound platform slots;
3. require password or recovery material;
4. let Rust verify and migrate the vault;
5. enroll a new Android platform slot;
6. preserve old ciphertext until the new slot commits.

---

## 14. Media import

```text
Photo Picker / SAF / content provider
            ↓
Android picker result
            ↓
PlatformReadHandleFactory
            ↓
ParcelFileDescriptor or bounded stream
            ↓
Rust ImportTransaction
            ↓
encrypted temporary object
            ↓
final commit + fsync + atomic finalize
            ↓
Rust catalog commit
```

The Android layer acquires access. Rust owns object identity, encryption, chunking, integrity, commit ordering, and private metadata persistence.

### 14.1 Photo Picker

The system Photo Picker is the default for photos and videos because it grants access only to user-selected items.

Chur SHOULD avoid broad `READ_MEDIA_IMAGES` or `READ_MEDIA_VIDEO` permissions when picker-based flows meet the requirement.

Picker results are import sources, not durable vault identifiers. Chur copies the content into its own encrypted object store.

### 14.2 Documents and audio

Use Storage Access Framework selection for audio, documents, and providers not covered by Photo Picker.

The platform adapter records only transient properties required to open the source. Original URI strings MUST NOT enter private logs, analytics, saved state, or public Room tables.

### 14.3 File-descriptor bridge

Preferred path:

```text
ContentResolver.openFileDescriptor(...)
        ↓
ParcelFileDescriptor
        ↓
validated ownership handoff
        ↓
Rust reads sequentially or by range
```

Rules:

- define exactly which side owns and closes each descriptor;
- detect seekability instead of assuming it;
- bound every length before allocation;
- do not convert complete media into `ByteArray`;
- handle providers that return pipes or delayed cloud content;
- propagate cancellation;
- report only sanitized provider/I/O errors.

A non-seekable source can still be imported sequentially. It MUST NOT be copied into a plaintext temporary file solely to make it seekable.

### 14.4 Cloud-backed providers

Import UX must support:

- unknown or changing source length;
- delayed availability;
- progress that starts after provider preparation;
- transient network errors;
- cancellation;
- process interruption and restart from a clean transaction state.

Chur does not consider an import successful until Rust has committed the encrypted original and private catalog record.

### 14.5 Source deletion

Deleting the original is a separate explicit user operation after successful commit. Chur MUST NOT claim secure physical erasure of the source from shared storage.

---

## 15. Metadata and derived assets

Rust owns canonical metadata serialization and private persistence. Android platform APIs MAY perform media probing or decoding when system codecs are needed.

```text
Android media APIs
    probe/decode selected source
            ↓
transient normalized values
            ↓
Rust validates, canonicalizes, encrypts, and stores
```

Potential Android helpers include system decoders, media metadata retrievers, and image metadata readers, but exact dependencies require review.

Rules:

- no private metadata in public Room or DataStore;
- no unredacted metadata in exceptions;
- derived assets reference the parent content revision;
- thumbnail or waveform failure does not invalidate a committed original;
- every derived asset is encrypted independently;
- metadata updates use a new stream revision and fresh nonce prefix.

---

## 16. Private image pipeline

Private images use a dedicated Coil `ImageLoader`:

```text
Compose request
    ↓
private Coil model containing opaque session/object reference
    ↓
Chur private fetcher
    ↓
Rust authenticated thumbnail or preview reader
    ↓
platform decoder
    ↓
session-scoped bitmap cache
```

Requirements:

- no public/private shared disk cache;
- private disk cache disabled or ciphertext-only;
- bounded memory cache scoped to one session generation;
- cache key contains an opaque session-scoped reference, not a path or filename;
- grid loads encrypted thumbnails rather than originals;
- viewer loads an appropriate encrypted preview before full-resolution ranges;
- every private bitmap cache is cleared during lock;
- decode failures return redacted object errors.

Coil must never receive the filesystem path of an encrypted object as if it were ordinary image content.

---

## 17. Video and audio playback

Media3 integrates through a custom `DataSource` backed by a Rust `ObjectReader`.

```text
ExoPlayer
   ↓ DataSpec(position, length)
ChurDataSource
   ↓
ObjectReader.readAt(offset, direct buffer)
   ↓
Rust authenticates required chunks
   ↓
requested plaintext range
```

### 17.1 DataSource behavior

`ChurDataSource` MUST:

- bind to one `VaultSessionHandle` and generation;
- validate requested position and length;
- avoid unbounded arrays;
- translate `SESSION_EXPIRED`, integrity, cancellation, and I/O errors predictably;
- close native readers idempotently;
- stop returning data immediately after lock;
- avoid exposing private URIs or filenames to player analytics;
- use a bounded decrypted-chunk cache owned by Rust or the secure graph.

### 17.2 Seeking

A range request may return `VerifiedRange`: all touched chunks passed AEAD authentication. This does not prove the complete object is present.

Export, repair, backup, and migration apply `CompleteVerifiedObject` policy separately.

### 17.3 Player lifecycle

Before private UI removal, locking MUST:

1. stop playback;
2. detach the player from the view;
3. invalidate the DataSource and native reader;
4. clear buffered plaintext and decoder surfaces as far as platform APIs allow;
5. release audio focus and media-session state;
6. prevent private title, artwork, duration, or controls from appearing on lock screen or external surfaces.

Chur SHOULD avoid publishing a system media notification for private playback unless a separately reviewed product mode requires it.

---

## 18. Export and sharing

Export destinations include:

- user-selected SAF destination;
- MediaStore insertion;
- Android share sheet through a temporary content URI;
- a future encrypted backup destination.

### 18.1 Direct destination export

Preferred when the provider supports streaming and abort semantics:

```text
Rust verified/decrypting export
        ↓
ParcelFileDescriptor destination
        ↓
commit or delete partial destination
```

If integrity fails, Chur must close and remove or invalidate the partial destination where the provider permits it.

### 18.2 Share-sheet scratch

When sharing requires a file:

- create a protected app-private temporary file;
- expose it through a narrowly configured `FileProvider`;
- grant temporary read permission only;
- use an opaque generated filename where recipient UX permits;
- revoke grants and delete after completion/timeout;
- clean on startup after process death;
- exclude from backup.

The Android share sheet receives plaintext by user request. Chur cannot force a recipient application to protect or delete it.

### 18.3 Clipboard

Private filenames, captions, recovery material, keys, and media bytes MUST NOT be copied to the clipboard implicitly. Explicit copy actions require a separate UX and privacy review.

---

## 19. Locking and lifecycle

Locking is a security transaction.

```text
UnlockedSession
      ↓ trigger
Locking
      ├── cover task/window
      ├── reject new private work
      ├── stop player and decoders
      ├── cancel/settle operations
      ├── increment Rust generation
      ├── zeroize session secrets
      ├── invalidate native handles
      ├── close private catalog
      ├── clear caches
      ├── destroy private graph/back stack
      └── return to PublicLocked
```

### 19.1 Triggers

- explicit lock;
- panic lock;
- configured inactivity timeout;
- process/background policy;
- device lock signal;
- vault switch;
- platform-key failure;
- unrecoverable integrity policy;
- application termination.

### 19.2 Lifecycle signals

The platform adapter combines:

- activity lifecycle;
- process lifecycle;
- window focus;
- device-interactive/keyguard state where reliable;
- explicit user activity and timeout state.

No individual callback is treated as a perfect security oracle. The state machine must be conservative and idempotent.

### 19.3 Background transition

Before the app can be represented in recents:

1. cover private content;
2. apply configured lock policy;
3. suppress private notifications and player metadata;
4. ensure private navigation is not serialized.

---

## 20. Screen, task, and external-display privacy

### 20.1 `FLAG_SECURE`

Private windows SHOULD use `FLAG_SECURE` according to product policy. It reduces ordinary screenshots, screen recording, and non-secure display projection.

It is defense in depth and does not protect against:

- root;
- a compromised system compositor;
- external cameras;
- accessibility or instrumentation malware;
- OEM implementation flaws.

### 20.2 Recents/task snapshot

The activity must display a neutral public/privacy surface before background snapshot capture. The snapshot MUST NOT contain:

- thumbnails;
- filenames;
- viewer content;
- album names;
- search queries;
- real/decoy indicators;
- unlock failures.

### 20.3 External surfaces

Private content MUST NOT be published to:

- widgets;
- app shortcuts;
- launcher badges that reveal counts;
- notification previews;
- media controls;
- picture-in-picture;
- cast targets;
- assistant/search indexing;
- autofill or content-capture services;

unless a future capability receives its own threat model and explicit opt-in.

---

## 21. Public shell and discreet presentation

The public shell is a real utility surface backed by its own public data.

Android launcher presentation MAY support user-selected alternatives through documented launcher components or activity aliases, subject to prototype validation across launchers and store policy.

Requirements:

- the user explicitly initiates any label/icon presentation change;
- changes are reversible;
- the public shell remains functional;
- private features are disclosed to store review;
- Chur does not hide its package from system settings, device administration, or installed-app lists;
- launcher-alias state does not reveal real/decoy identity;
- no component is enabled to evade uninstall, parental controls, policy enforcement, or review.

Changing launcher components may have OEM-specific effects such as shortcut relocation or duplicate icons. This requires an ADR and device-matrix testing before release.

Discreet presentation is privacy from casual observation, not platform-level invisibility.

---

## 22. Notifications

Notifications are neutral by default.

They MUST NOT contain:

- private filenames or album names;
- thumbnails or artwork;
- object counts tied to private state;
- real/decoy identity;
- unlock failure details;
- recovery material;
- private deep-link arguments.

Examples of acceptable categories:

- user-requested encrypted import finished;
- encrypted backup transfer requires attention;
- generic operation failed;
- app update or public-shell reminder.

Notification actions MUST open the public locked shell unless an authenticated session is still valid under explicit policy.

Notification channel names and descriptions should remain truthful without exposing private content.

---

## 23. Background execution

### 23.1 Allowed while locked

- upload already encrypted immutable objects;
- download ciphertext into a quarantined/incoming area;
- retry opaque transfer jobs;
- remove expired ciphertext cache;
- reconcile non-secret transfer bookkeeping;
- schedule user-visible reminders unrelated to private content.

### 23.2 Not allowed in v1 while locked

- unwrap `VaultRootSecret` solely for background convenience;
- prompt biometrics from background;
- decrypt thumbnails;
- generate previews, waveforms, OCR, or semantic indexes;
- mutate private metadata;
- import plaintext from arbitrary background sources;
- expose private notifications.

### 23.3 WorkManager

WorkManager receives opaque transfer identifiers and ciphertext locations. It MUST NOT receive private names, keys, decrypted manifests, or private search data in `Data`.

Long user-initiated operations that require a foreground service need a separate policy covering notification neutrality, cancellation, time limits, and locked-state behavior.

### 23.4 Process death

Operations must be transaction-safe when Android kills the process. On restart, Rust reconciliation determines whether an encrypted object is incoming, committed, corrupt, or removable. Kotlin does not infer success from a previous WorkManager or lifecycle state.

---

## 24. Permissions

Chur follows least privilege.

Expected permissions depend on enabled features, but the baseline SHOULD avoid:

- broad media-library permissions when Photo Picker is sufficient;
- `MANAGE_EXTERNAL_STORAGE`;
- `QUERY_ALL_PACKAGES`;
- contacts, location, microphone, or camera without a concrete feature;
- accessibility-service privileges;
- overlay permissions;
- install/unknown-source privileges.

Likely platform declarations include only capabilities actually used, such as biometric authorization, network access for future sync, and notification permission when notifications are enabled.

Every permission must have:

- an explicit use case;
- an in-context request;
- denial handling;
- a privacy review;
- no effect on ability to unlock through independent recovery paths unless technically required.

---

## 25. Future networking and sync

Ktor handles transport above platform networking. Android networking code sees opaque ciphertext and protocol envelopes only.

Android-specific responsibilities may include:

- connectivity constraints;
- battery/storage constraints;
- resumable background transfer;
- TLS and network-security configuration;
- proxy/VPN compatibility;
- user-controlled cellular usage;
- certificate and endpoint policy.

It MUST NOT deserialize private metadata or choose cryptographic algorithms.

A server-provided Argon2 parameter, length, suite, or allocation request is untrusted and validated by Rust before use.

---

## 26. Logging, analytics, and crash reporting

Kermit is wrapped by a privacy-safe facade.

Forbidden values include:

- source URI;
- destination URI;
- filesystem path;
- filename;
- EXIF/GPS;
- object, collection, or vault secret identifiers;
- key aliases if they can correlate a vault;
- password/recovery material;
- plaintext sizes when unnecessary;
- private navigation route;
- real/decoy identity;
- unredacted native errors.

Preferred structured events:

```text
IMPORT_STARTED
IMPORT_COMMITTED
IMPORT_CANCELLED
IMPORT_FAILED_IO
SESSION_LOCKED_BACKGROUND
PLATFORM_KEY_INVALIDATED
OBJECT_INTEGRITY_FAILED
NATIVE_API_INCOMPATIBLE
```

Crash attachments MUST exclude app-private files, databases, screenshots, and native buffers. Native symbols may be uploaded for symbolication without uploading user data.

---

## 27. Error mapping

Android platform conditions map onto the stable codes registered in [`ERROR_MODEL.md`](ERROR_MODEL.md). This layer names conditions; it MUST NOT introduce a code of its own.

| Android condition | Stable code |
| --- | --- |
| user dismissed `BiometricPrompt`, or the caller cancelled | `CANCELLED` |
| no enrolled biometric, no secure lock screen, or biometric lockout | `PLATFORM_KEY_UNAVAILABLE` |
| `KeyPermanentlyInvalidatedException`, or an enrolment change invalidated the alias | `PLATFORM_KEY_INVALIDATED` |
| `SecurityException` from a provider, or a revoked URI grant | `PERMISSION_DENIED` |
| provider returned no descriptor, or the source disappeared | `NOT_FOUND` |
| descriptor is a pipe or socket and the operation needs random access | `SOURCE_NOT_SEEKABLE` |
| cloud-backed provider must download the item before it can be read | `SOURCE_DOWNLOAD_REQUIRED` |
| destination is unwritable, or the volume is full | `STORAGE_UNAVAILABLE` |
| direct-boot or device-locked file access denied | `PROTECTED_DATA_UNAVAILABLE` |
| handshake rejection per §28.3 | `ABI_INCOMPATIBLE` |
| any other `IOException` | `IO_FAILURE` |

`SOURCE_NOT_SEEKABLE` and `SOURCE_DOWNLOAD_REQUIRED` are both derived from the source capability model in [`interop/MEDIA_PIPELINE.md`](interop/MEDIA_PIPELINE.md) §3, so the shared import use case branches identically on Android and iOS.

Detailed platform exceptions remain in local debug diagnostics only when they contain no private values. User-visible messages MUST avoid revealing whether a real, decoy, absent, or damaged vault matched a credential.

---

## 28. Rust and Android interop

### 28.1 Packaging

The Rust core is built for approved Android targets and packaged into the application through a dedicated adapter module.

The build SHOULD:

- pin Rust toolchain and Android NDK versions;
- use Cargo lockfiles;
- produce deterministic ABI-specific libraries where practical;
- fail when symbols or expected ABIs are missing;
- restrict exported native symbols;
- generate and verify headers/bindings;
- archive native debug symbols for crash symbolication;
- scan licenses and dependency advisories;
- prevent debug Rust artifacts from entering release packages.

### 28.2 JNI and C ABI

The stable data plane uses opaque handles and direct/native buffers.

Rules:

- no raw pointer escapes into feature code;
- no panic unwinds across JNI;
- every pointer and length is validated;
- each handle is typed and session-generation checked;
- native methods remain coarse-grained;
- large media is never returned as a complete `ByteArray`;
- callbacks from arbitrary Rust threads are avoided in v1;
- cancellation is explicit;
- close is idempotent.

### 28.3 Native API handshake

At startup, Kotlin verifies:

```text
native API version
supported object-format range
supported key-slot range
build flavor compatibility
required feature flags
```

An incompatible native library fails closed before any vault opens.

### 28.4 R8 and packaging rules

Any generated bindings that rely on reflection, native method names, or callback classes require explicit keep rules. Release validation must inspect the final APK/AAB, not only debug builds.

---

## 29. Concurrency and memory

- KMP wraps blocking native calls on bounded I/O dispatchers.
- The UI thread never performs Argon2id, large I/O, or media decryption.
- Imports and readers use bounded buffers.
- Concurrent operations are limited per device capability.
- A single object reader serializes or safely coordinates overlapping requests according to Rust policy.
- Lock cancellation outranks ordinary operation completion.
- Android `ByteArray` clearing is best effort; secret buffers are short-lived and never converted to `String`.
- Direct buffers must not outlive their native operation.
- Player buffering is bounded and invalidated on session expiration.

The architecture MUST remain safe under races between:

- lock and playback read;
- lock and import commit;
- process backgrounding and biometric completion;
- source-provider cancellation and native I/O;
- WorkManager retry and vault deletion;
- activity recreation and prompt completion.

---

## 30. Performance and resource budgets

Benchmarks are required for:

- cold start before native initialization;
- time to render the public shell;
- biometric-to-library latency;
- Argon2id latency and memory pressure;
- photo import throughput;
- multi-gigabyte video import throughput;
- random seek latency;
- thumbnail grid scrolling;
- native/Kotlin copy count;
- battery use during encryption and playback;
- SQLCipher/private-catalog query latency;
- lock completion latency;
- package size per ABI.

Performance optimization MUST NOT bypass authentication, integrity verification, buffer bounds, or session invalidation.

Baseline Profiles and Macrobenchmark SHOULD cover public startup, unlock transition, library scroll, viewer open, and video seek once implementation exists.

---

## 31. Accessibility and localization

The public and private UIs must remain accessible without leaking private content to persistent external services.

Requirements:

- content descriptions avoid unnecessary filenames;
- lock and recovery flows work with TalkBack;
- authentication errors are understandable but non-oracular;
- large text and adaptive layouts do not reveal private UI behind overlays;
- locale-aware display formatting never alters canonical Rust serialization;
- accessibility-node exposure for private content is minimized to what the current screen requires;
- panic lock remains reachable under accessibility settings.

Chur does not claim protection from a malicious accessibility service on an unlocked device.

---

## 32. Testing strategy

### 32.1 Shared and unit tests

- UDF reducers and ViewModels;
- lock-state transitions;
- redacted error mapping;
- public/private navigation separation;
- backup-policy decisions;
- platform capability mapping;
- fake picker and file-descriptor adapters;
- player coordination;
- session invalidation.

### 32.2 Instrumented Android tests

- Keystore slot creation and unwrap;
- authentication cancellation and invalidation;
- rotation/re-enrollment after recovery;
- Photo Picker and SAF import paths;
- seekable and non-seekable providers;
- Media3 random seek and lock interruption;
- `FLAG_SECURE` and task-cover behavior where testable;
- backup-exclusion verification;
- process death during import/export;
- cache cleanup after crash;
- notification redaction;
- real/decoy platform-state separation.

### 32.3 Device matrix

At minimum:

- hardware-backed Keystore device;
- StrongBox-capable device;
- device without StrongBox;
- biometric enrollment and invalidation scenarios;
- low-memory device;
- multiple API levels within support range;
- OEM launchers if alternate presentation is enabled;
- large cloud-backed picker source;
- emulator for deterministic CI flows.

### 32.4 Security fault injection

- stale native handle after lock;
- invalid descriptor ownership;
- truncated provider stream;
- storage-full before final commit;
- process kill between fsync and catalog commit;
- corrupted platform slot;
- restored ciphertext without Keystore key;
- partial share destination;
- logging assertions that scan for known private test values.

---

## 33. CI and release gates

Android CI SHOULD include:

```text
Kotlin/KMP unit tests
Compose UI tests
Android lint
Detekt / formatting
Rust unit and property tests
cargo fmt / clippy
JNI binding verification
NDK builds for declared ABIs
instrumented smoke tests
APK/AAB content inspection
R8 release build
license and dependency audit
secret/log leakage tests
```

Release is blocked when:

- a declared ABI lacks the native library;
- native and Kotlin API versions disagree;
- debug symbols or debug logging ship incorrectly;
- backup rules include prohibited paths;
- private data appears in screenshots, logs, or saved state tests;
- platform slot recovery has not been exercised;
- Rust format vectors fail on Android;
- release minification breaks FFI;
- security invariants are not covered by required tests.

Production claims require independent review of the Rust core, key-slot integration, import/export paths, and Android privacy surfaces.

---

## 34. Implementation sequence

### Phase A — shell and native handshake

- Android application/module skeleton;
- Compose host and public shell;
- platform contracts;
- Rust library loading and version handshake;
- locked-only lifecycle and privacy overlay;
- Room/DataStore for public data.

### Phase B — recoverable local unlock

- password/recovery slot through Rust;
- Android Keystore platform slot;
- `BiometricPrompt` flow;
- invalidation and re-enrollment tests;
- secure graph creation/destruction.

### Phase C — photo vault

- Photo Picker import;
- file-descriptor bridge;
- encrypted thumbnails;
- private Coil loader;
- library and viewer;
- verified export;
- atomic lock and cache clearing.

### Phase D — video and audio

- Media3 `DataSource`;
- random seek;
- audio playback;
- waveform/poster derived assets;
- large-file fault injection and benchmarks.

### Phase E — decoy and discreet presentation

- independent decoy platform slots and namespaces;
- public utility completeness;
- task/notification privacy;
- optional launcher presentation after policy and OEM testing.

### Phase F — ciphertext sync

- WorkManager transfer jobs;
- resumable encrypted objects;
- opaque signed operation logs;
- restore and device enrollment;
- no background plaintext work by default.

---

## 35. Required Android ADRs

Before production implementation is frozen, record decisions for:

1. minimum supported API level;
2. Android Gradle Plugin, JDK, NDK, and Rust toolchain versions;
3. production ABI set;
4. Keystore authentication modes and timeout policy;
5. StrongBox user-facing behavior and fallback;
6. platform-slot binary representation;
7. backup and device-transfer inclusion rules;
8. SQLCipher build and packaging on Android;
9. JNI versus generated control-plane binding details;
10. direct-buffer ownership contract;
11. foreground-service policy for long user operations;
12. alternate launcher presentation and store-policy review;
13. media notification and lock-screen control policy;
14. screenshots and `FLAG_SECURE` defaults;
15. analytics/crash-reporting provider and redaction guarantees;
16. baseline performance budgets.

---

## 36. Android security checklist

Before an Android release candidate:

- [ ] App starts in `PublicLocked` after process death.
- [ ] Private navigation is never restored.
- [ ] Keystore contains only platform key material, not media keys.
- [ ] Password/recovery unlock works after platform-key invalidation.
- [ ] StrongBox failure has a tested safe fallback.
- [ ] Real and decoy platform aliases and namespaces are unrelated.
- [ ] Room/DataStore contain no private metadata.
- [ ] Photo/video imports do not require broad library permission where picker access is sufficient.
- [ ] Complete media never crosses Kotlin as one `ByteArray`.
- [ ] Media3 readers fail with `SESSION_EXPIRED` after lock.
- [ ] Lock clears private Coil, player, decoder, and Rust caches.
- [ ] `FLAG_SECURE` and task-cover policies match product settings.
- [ ] Notifications contain no private names, counts, thumbnails, or deep-link data.
- [ ] Auto Backup excludes device-bound and plaintext state.
- [ ] Restored ciphertext requires a valid portable recovery slot.
- [ ] Scratch files are backup-excluded and cleaned after interruption.
- [ ] Logs and crash reports pass private-value scanning tests.
- [ ] Release AAB contains all expected native ABIs and no debug native artifacts.
- [ ] Rust/Kotlin API handshake fails closed on incompatibility.
- [ ] Cross-platform golden vectors pass on Android.

---

## 37. References

- [Chur README](../README.md)
- [Chur system architecture](ARCHITECTURE.md)
- [Chur iOS platform architecture](IOS.md)
- [Android Keystore system](https://developer.android.com/privacy-and-security/keystore)
- [Biometric authentication](https://developer.android.com/identity/sign-in/biometric-auth)
- [Android Photo Picker](https://developer.android.com/training/data-storage/shared/photo-picker)
- [Storage Access Framework](https://developer.android.com/guide/topics/providers/document-provider)
- [Android Auto Backup](https://developer.android.com/identity/data/autobackup)
- [Media3 customization](https://developer.android.com/media/media3/exoplayer/customization)
- [`FLAG_SECURE`](https://developer.android.com/reference/android/view/WindowManager.LayoutParams)

---

## 38. Summary

The Android implementation is a thin, security-sensitive platform shell around shared KMP/CMP application code and a Rust-owned vault runtime.

Its central obligations are:

```text
Authorize platform key use without becoming the key hierarchy.
Provide bounded file-descriptor and media-player integration without owning vault bytes.
Protect Android lifecycle, task, notification, and backup surfaces.
Invalidate every private operation on lock.
Keep public-shell persistence completely separate from private-vault state.
```

When an Android convenience conflicts with Rust ownership, recoverability, integrity, or deterministic locking, the security invariant wins.