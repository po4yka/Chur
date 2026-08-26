# Chur iOS Platform Architecture

> **Status:** proposed platform architecture for implementation  
> **Audience:** iOS, KMP, Rust, security, QA, and release-engineering contributors  
> **Last updated:** 2026-08-26  
> **Related:** [Project README](../README.md) · [System architecture](ARCHITECTURE.md) · [Android architecture](ANDROID.md)

Chur is a local-first private media archive with a functional public shell and a Rust-owned encrypted vault. This document defines the iOS-specific implementation boundary: the SwiftUI/UIKit host, Kotlin/Native integration, Keychain authorization, Data Protection, Photos and Files import, AVFoundation playback, scene locking, discreet presentation, background execution, native packaging, testing, App Store review, and release requirements.

The system architecture remains authoritative for cryptographic formats, key hierarchy, object containers, integrity, private catalog ownership, migrations, real/decoy separation, and synchronization semantics. Swift, Objective-C, UIKit, SwiftUI, and Kotlin/Native MUST NOT independently reimplement those rules.

Chur is currently in architecture and protocol design. Nothing in this document is a completed audit or a production security guarantee.

---

## 1. Normative language

The key words **MUST**, **MUST NOT**, **REQUIRED**, **SHOULD**, **SHOULD NOT**, and **MAY** describe platform requirements.

Statements are classified as:

- **Decision** — accepted implementation direction.
- **Invariant** — property every iOS implementation must preserve.
- **Proposal** — preferred direction requiring a prototype, benchmark, or ADR.
- **Non-goal** — behavior or guarantee intentionally excluded.

Byte-exact cryptographic behavior belongs to dedicated Rust format specifications. iOS owns platform policy and transport to the Rust boundary, not vault bytes.

---

## 2. iOS responsibilities

The iOS target owns:

- application and scene startup;
- SwiftUI/UIKit hosting of Compose Multiplatform content;
- lifecycle, scene, protected-data, and capture-state signals;
- Keychain storage and `SecAccessControl` policy;
- LocalAuthentication authorization UX;
- PhotosPicker, Files, security-scoped URL, and file-representation integration;
- AVFoundation byte-range playback;
- scene snapshot, notification, clipboard, and alternate-icon privacy policy;
- app-private filesystem placement, Data Protection, and backup exclusions;
- background URLSession and BGTask policy;
- Kotlin framework and Rust library packaging;
- iOS-specific tests, performance measurement, privacy manifests, and release checks.

The iOS target MUST NOT own:

- private catalog schema;
- key-slot parsing;
- Argon2id execution or resource parameters;
- root, collection, object, content, metadata, or preview keys as durable Swift objects;
- object-container serialization;
- nonce or AAD construction;
- integrity decisions;
- real/decoy classification in ordinary feature code;
- sync-operation canonicalization;
- private metadata persistence in `UserDefaults`, SwiftData, Core Data, scene restoration, or analytics.

---

## 3. Planned iOS baseline

| Area | Planned choice |
| --- | --- |
| Bundle identifier | `dev.po4yka.chur` |
| Primary platform target | iOS 26+ design baseline; exact deployment target requires an ADR |
| Native language | Swift 6.2 target baseline |
| Shared language | Kotlin 2.4.10 / K2 |
| Shared UI | Compose Multiplatform 1.11.1 |
| Host UI | Thin SwiftUI/UIKit shell with Compose content |
| Navigation/state | Shared Navigation 3, AndroidX ViewModel, StateFlow, Flow, UDF/MVVM |
| Public persistence | Room 3.0.1 KMP and DataStore KMP |
| Images | Coil 3.5 with a separate private loader |
| Media | PhotosUI, AVFoundation, system image/media frameworks |
| Key protection | Keychain, LocalAuthentication, Data Protection |
| Background work | Background URLSession and BGTaskScheduler for policy-approved ciphertext work |
| Native core | Rust static library/XCFramework through isolated C interop |
| Apple interop | Objective-C framework interop initially; selective Swift Export where stable and beneficial |

Version numbers are implementation targets rather than protocol identifiers. Upgrading Swift, Xcode, Kotlin, Compose, AVFoundation, or Rust dependencies MUST NOT silently change vault bytes or security policy.

### 3.1 Device architecture policy

Development and CI SHOULD support:

- `arm64` physical devices;
- `arm64` simulator;
- `x86_64` simulator only if still required by the selected toolchain and CI fleet.

The release archive MUST contain exactly the approved device slices and must not embed duplicate or mismatched Rust runtimes.

### 3.2 Capability tiers

```text
Baseline
    passcode-capable device
    Keychain available
    protected app storage available

Enhanced
    Face ID or Touch ID available
    current passcode configured

Strict
    passcode-bound ThisDeviceOnly accessibility
    biometryCurrentSet policy explicitly selected
```

Capability detection informs UX. It MUST NOT change the canonical vault format or silently remove recovery.

---

## 4. Runtime architecture

```text
┌──────────────────────────────────────────────────────────────┐
│ iOS process                                                  │
│ SwiftUI App · scene host · UIKit platform services           │
├──────────────────────────────────────────────────────────────┤
│ Compose Multiplatform content                                │
│ public shell · session gate · private vault UI               │
├──────────────────────────────────────────────────────────────┤
│ KMP application layer                                        │
│ ViewModels · UDF · navigation · coordinators                 │
├──────────────────────────────────────────────────────────────┤
│ iOS platform adapters                                        │
│ Keychain · LocalAuthentication · pickers · files · AVPlayer  │
├──────────────────────────────────────────────────────────────┤
│ Rust bridge                                                  │
│ control plane · opaque handles · streaming data plane        │
├──────────────────────────────────────────────────────────────┤
│ Rust Vault Runtime                                           │
│ keys · catalog · objects · media · integrity · migrations    │
└──────────────────────────────────────────────────────────────┘
```

The iOS application is a native host, not a second application architecture. Shared KMP code owns user-visible state and navigation. Swift/UIKit adapters expose only platform capabilities.

---

## 5. Planned target structure

```text
apps/iosApp/
├── ChurApp.swift
├── scene and lifecycle bridge
├── Compose host
├── platform composition root
├── entitlements and privacy manifest
├── assets and alternate icons
└── release configuration

shared/core-platform/
├── expect platform contracts
└── platform-neutral models

shared/core-platform-ios/
├── KeychainRootKeyProtector
├── IOSUserAuthenticator
├── IOSMediaPicker
├── IOSReadHandleFactory
├── IOSExportDestination
├── IOSLifecycleSignals
├── IOSScenePrivacyController
└── IOSBackgroundScheduler

shared/core-media/
├── private image-loader contracts
├── player coordination
└── opaque media-source models

ios/native/
├── AVAsset resource-loader adapter
├── C-ABI/module-map integration
└── bounded native buffer helpers
```

Feature modules MUST depend on shared interfaces. They MUST NOT import Security, LocalAuthentication, PhotosUI, AVFoundation, or Rust C symbols directly.

A narrowly scoped AVFoundation data-plane adapter MAY call the stable Rust C ABI directly when this avoids repeated Swift → Kotlin/Native → C copies. It still uses session handles created and owned through the shared application layer.

---

## 6. App and scene startup

### 6.1 SwiftUI application shell

The native `App` entry point SHOULD:

1. install the platform composition root;
2. create the Compose host;
3. initialize privacy-safe logging;
4. load the Kotlin framework and verify the Rust/native API handshake;
5. observe scene lifecycle and protected-data availability;
6. render a neutral cover before private state can be snapshotted;
7. schedule ciphertext-only cleanup or transfer work that is safe while locked.

It MUST NOT unlock a vault, open the private catalog, derive password keys, or restore private navigation during startup.

### 6.2 Compose host

The preferred host may be either:

- a SwiftUI representable wrapping a Compose `UIViewController`; or
- a UIKit root controller embedded by the SwiftUI app shell.

The selected approach requires an ADR covering lifecycle propagation, keyboard/focus behavior, state restoration, accessibility, presentation of native sheets, and scene privacy.

### 6.3 Scene restoration

The app MAY restore:

- public-shell route and data;
- theme, locale, and non-secret settings;
- a public indication that encrypted recovery or reconciliation is needed.

It MUST NOT restore:

- private back stacks;
- filenames, albums, queries, or media identity;
- active readers or player positions;
- unlock credentials;
- a real/decoy flag;
- private sheet or share state;
- a snapshot of the private screen.

Every process launch begins logically in `PublicLocked` until a new authenticated Rust session is established.

---

## 7. Dependency graphs

```text
IOSApplicationGraph
├── PublicGraph                    Koin classic DSL
│   ├── public repositories
│   ├── Room
│   ├── DataStore
│   ├── public navigation
│   └── non-sensitive schedulers
│
├── PlatformGraph                  explicit native bindings
│   ├── LocalAuthentication
│   ├── Keychain
│   ├── media picker
│   ├── file-representation factory
│   ├── scene privacy
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

The secure graph exists only for one authenticated session generation. Swift property wrappers, environment objects, Koin scopes, and singleton containers MUST NOT retain secret-bearing session objects after lock.

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

interface PlatformScenePrivacyController {
    fun coverPrivateContent()
    fun revealAllowedContent()
    fun setCapturePolicy(policy: CapturePolicy)
}
```

Swift implementations return opaque references and stable error categories. Raw Keychain account names, security-scoped URLs, Photos identifiers, and filesystem paths do not enter feature state.

---

## 9. Keychain model

Keychain protects short device-bound secret material. It does not encrypt media and does not replace the Rust key hierarchy.

### 9.1 Preferred v1 platform slot

The preferred design is:

```text
Rust generates VaultRootSecret
        │
        ├── password/recovery slots remain Rust-owned
        │
        ▼
iOS creates random DeviceUnlockSecret
        │
        ▼
Keychain stores DeviceUnlockSecret
with ThisDeviceOnly + SecAccessControl
        │
        ▼
Rust uses DeviceUnlockSecret as a bounded KEK
for a Rust-owned platform key slot
```

This keeps the platform slot versioned and parsed by Rust while Keychain controls release of the device secret.

A direct `VaultRootSecret` Keychain item is simpler but makes platform-slot lifecycle and migration more platform-specific. It is not the preferred design without an ADR.

### 9.2 Unlock flow

```text
user initiates unlock
        ↓
LocalAuthentication / Keychain authorization
        ↓
Keychain releases DeviceUnlockSecret
        ↓ bounded mutable buffer
Rust unwraps VaultRootSecret from PlatformKeySlot
        ↓
Rust creates session and zeroizing secret state
        ↓
iOS/Kotlin buffers are cleared best effort
```

After release, the device secret and resulting root secret exist in the application process. Keychain and Secure Enclave protect data at rest and gate release; they do not protect an already unlocked process from a fully compromised OS.

### 9.3 Default and strict policies

Recoverable default proposal:

```text
accessibility: WhenUnlockedThisDeviceOnly
access control: userPresence
```

Strict proposal:

```text
accessibility: WhenPasscodeSetThisDeviceOnly
access control: biometryCurrentSet
```

Exact combinations require device testing and an ADR. `biometryCurrentSet` can invalidate access after biometric enrollment changes. `WhenPasscodeSetThisDeviceOnly` can become inaccessible when the passcode is removed.

A recoverable vault therefore keeps a password or high-entropy recovery slot independent from Keychain state.

### 9.4 Keychain identifiers

Keychain service/account labels MUST be opaque and unrelated to:

- real or decoy identity;
- filenames or media type;
- user-visible vault name;
- account email;
- collection title.

Access groups are not introduced unless an extension or multi-target design receives a separate security review.

### 9.5 Invalidation and recovery

Required flow when a Keychain item is unavailable or invalidated:

1. classify the internal error as platform-key unavailable or invalidated;
2. show a non-oracular recovery message;
3. request password or recovery-key unlock;
4. let Rust open and validate the same vault;
5. create a new Keychain item and platform slot;
6. delete the obsolete item only after the replacement commits.

Platform-key failure MUST NOT be reported as catalog corruption without evidence.

---

## 10. LocalAuthentication UX

LocalAuthentication authorizes access; biometric data is never converted into a key.

The authentication coordinator MUST:

- create an `LAContext` scoped to one pending operation;
- provide a precise localized reason without private filenames or vault identity;
- handle cancellation, lockout, fallback, interruption, and scene changes;
- avoid retaining contexts longer than required;
- prevent a completed callback from unlocking after the request was cancelled or the scene locked;
- map native errors to stable redacted outcomes;
- avoid revealing whether a credential selects a real, decoy, absent, or damaged vault.

Biometric convenience is not recovery. Recovery flows use a password or high-entropy recovery secret processed by Rust.

The app MUST NOT attempt interactive Keychain authorization from background execution.

---

## 11. Real and decoy vault identities

Real and decoy iOS platform state is independent:

```text
VaultDescriptor A
├── opaque Keychain item reference
├── opaque storage namespace
├── independent platform slot
└── independent session generation

VaultDescriptor B
├── unrelated Keychain item reference
├── unrelated storage namespace
├── independent platform slot
└── independent session generation
```

Ordinary feature code receives only an opaque `VaultSessionHandle`. It does not receive `isReal`, `isDecoy`, a Keychain service name, or storage path.

Keychain labels, directory names, crash diagnostics, and scene restoration MUST NOT reveal which vault is real.

Decoy mode is a coercion-resistant UX feature, not a claim that a second vault is cryptographically undetectable under device or filesystem analysis.

---

## 12. Filesystem placement

Suggested placement:

```text
Library/Application Support/Chur/
├── public/                         public shared data as designed
└── vaults/
    ├── <opaque-vault-id>/          encrypted catalog, objects, journals
    └── <opaque-vault-id>/

Library/Caches/Chur/
├── encrypted-transfer-cache/
└── plaintext-scratch/              explicit policy only

tmp/
└── short-lived system-operation files where unavoidable
```

Chur SHOULD NOT store private vault data in the user-visible Documents directory.

Directory and file names MUST NOT reveal real/decoy identity, media type, album, filename, account, or object count beyond unavoidable filesystem metadata.

### 12.1 Private catalog and objects

- Rust owns the private catalog connection and migrations.
- Media containers remain immutable after commit.
- Object-key envelopes are mutable catalog/key-domain state.
- SwiftData/Core Data/UserDefaults do not store private catalog data.
- Native file URLs are confined to the platform composition root and Rust adapter.

---

## 13. Data Protection policy

Data Protection complements Chur encryption; it does not replace it.

### 13.1 Strict local profile

Private key-slot files, private catalog, and plaintext scratch target the strongest compatible protection, with `NSFileProtectionComplete` as the strict default direction.

Strict profile behavior:

- protected files become inaccessible after device lock;
- private catalog is closed before background lock completes;
- no plaintext-requiring background work is expected;
- user must unlock the device and Chur session before private access resumes.

### 13.2 Ciphertext-transfer profile

Already encrypted immutable objects MAY use a protection class compatible with background transfer after first device unlock when the user enables ciphertext sync.

Even in this profile:

- `VaultRootSecret` remains unavailable while Chur is locked;
- private catalog keys remain unavailable;
- background workers operate only on opaque ciphertext and transfer metadata;
- downloaded ciphertext remains incoming/quarantined until Rust validates it in an authenticated session.

### 13.3 Protected-data availability

The application observes protected-data availability and fails closed when required files are inaccessible. It does not treat an I/O failure during device lock as corruption.

---

## 14. Backup, iCloud, and device transfer

Backup policy MUST be explicit.

### 14.1 Excluded state

The following MUST be excluded from backup:

- plaintext scratch;
- decrypted caches;
- active session state;
- nonportable device-only secret references;
- Keychain assumptions that cannot survive restore;
- incomplete operations unsafe to resume elsewhere.

### 14.2 Portable encrypted state

Encrypted catalog and object containers MAY be backed up only when the vault has an independent password or recovery slot that can open the restored data on another device.

A Keychain item marked `ThisDeviceOnly` does not migrate. Restored ciphertext without a portable slot is not recoverable.

### 14.3 Restore flow

After restore to a new device:

1. launch in `PublicLocked`;
2. ignore missing/unusable device Keychain slots;
3. request password or recovery material;
4. let Rust verify and migrate the vault;
5. create a new Keychain device slot;
6. retain old ciphertext until the new slot commits;
7. rebuild derived assets only after original objects validate.

Backup inclusion and exclusion must be tested against actual archive and device-transfer behavior, not inferred from directory names alone.

---

## 15. Photos import

```text
PhotosPicker
      ↓
Transferable / file representation
      ↓
iOS read handle
      ↓
Rust ImportTransaction
      ↓
encrypted temporary object
      ↓
final commit + durable finalize
      ↓
Rust catalog commit
```

The iOS layer acquires access. Rust owns object identity, encryption, chunking, integrity, commit ordering, and private metadata persistence.

### 15.1 PhotosPicker

PhotosPicker is the default photo/video import surface.

Requirements:

- support user-selected items without broad library access;
- request file representations rather than loading complete media into `Data`;
- account for iCloud download latency;
- surface progress and cancellation;
- treat picker identifiers as transient source references, not vault IDs;
- close and release representations after Rust import completes or cancels.

### 15.2 iCloud-backed assets

The selected representation may not exist locally when the user confirms the picker.

The import coordinator must handle:

- preparation/download progress;
- temporary provider URLs;
- cancellation before native import begins;
- changing or unknown size;
- transient network failure;
- process interruption;
- a source that disappears before retry.

No UI success is shown until Rust commits the encrypted original and private catalog record.

### 15.3 Live Photos, RAW, and paired resources

Live Photos, RAW+JPEG pairs, spatial media, and other compound assets require an explicit object-bundle model.

Until that model is specified, v1 SHOULD either:

- import the selected primary representation with a clear limitation; or
- exclude the unsupported compound type.

It MUST NOT silently drop paired resources while claiming lossless archival.

---

## 16. Files import and security-scoped URLs

Files selected through document pickers may provide security-scoped URLs.

The platform adapter MUST:

- start security-scoped access only for the minimum operation;
- stop access in all success, failure, and cancellation paths;
- avoid persisting the URL as a private identifier;
- pass a file descriptor or bounded read abstraction to the Rust data plane;
- detect seekability instead of assuming it;
- avoid copying complete content into Swift `Data`;
- avoid plaintext temporary files solely to gain seekability.

A non-seekable representation may be imported sequentially. Rust container completeness does not require a trusted total length before streaming begins.

---

## 17. Metadata and derived assets

Rust owns canonical metadata serialization and private persistence. Apple frameworks MAY perform probing, decoding, or transcoding when system codecs are required.

```text
Photos / ImageIO / AVFoundation
    probe or decode selected source
            ↓
transient normalized values
            ↓
Rust validates, canonicalizes, encrypts, and stores
```

Rules:

- private metadata never enters `UserDefaults`, SwiftData, Core Data, scene restoration, or analytics;
- derived assets reference the parent content revision;
- thumbnail, waveform, OCR, or poster-frame failure does not invalidate a committed original;
- each derived asset is encrypted independently;
- metadata updates use a new stream revision and fresh nonce prefix;
- native framework errors are redacted before entering shared state.

The first release does not need to bundle every HEIF, RAW, ProRes, HDR, or spatial codec in Rust.

---

## 18. Private image pipeline

Compose private images use a dedicated Coil `ImageLoader` and iOS-specific decoding path where required.

```text
Compose request
    ↓
opaque session/object model
    ↓
private Coil fetcher
    ↓
Rust authenticated thumbnail or preview reader
    ↓
Skia / ImageIO / platform decoder
    ↓
session-scoped bitmap cache
```

Requirements:

- no shared public/private disk cache;
- private disk cache disabled or ciphertext-only;
- bounded memory cache scoped to one session generation;
- cache key contains an opaque session reference, not a path or filename;
- grids use encrypted thumbnails rather than full originals;
- viewer requests a suitable preview before full-resolution ranges;
- all private images and decoded surfaces are invalidated during lock;
- the decoder does not receive the encrypted-object path as an ordinary image file.

Any native image cache created outside Coil must register with the secure graph and participate in lock cleanup.

---

## 19. Video and audio playback

AVFoundation integrates through `AVAssetResourceLoaderDelegate` backed by a Rust `ObjectReader`.

```text
AVPlayer
   ↓ custom asset URL/resource loading request
ChurAssetResourceLoader
   ↓ requested offset and length
ObjectReader.readAt(handle, offset, bounded buffer)
   ↓
Rust authenticates required chunks
   ↓
requested plaintext range
```

### 19.1 Resource-loader behavior

The adapter MUST:

- bind to one session generation;
- provide only the content information required by AVFoundation;
- validate offset and length before allocation;
- service requests on a dedicated serial executor/queue or otherwise preserve ordering rules;
- support request cancellation;
- close native readers idempotently;
- stop responding immediately after lock;
- translate session, integrity, and I/O failures without private strings;
- avoid publishing private title/artwork to Now Playing or external surfaces.

### 19.2 Seeking and verification

Playback consumes `VerifiedRange`: every returned chunk passed AEAD authentication. It does not prove the complete object exists.

Export, repair, backup, and migration apply `CompleteVerifiedObject` policy separately.

### 19.3 Player lifecycle

Before private UI is removed, locking MUST:

1. pause and stop playback;
2. detach the player layer/view;
3. cancel outstanding resource-loading requests;
4. invalidate Rust reader handles;
5. clear bounded decrypted buffers;
6. release audio session and Now Playing metadata where used;
7. prevent picture-in-picture or external playback from continuing.

Private picture-in-picture, AirPlay, route sharing, and lock-screen controls are disabled in v1 unless separately reviewed and explicitly enabled.

### 19.4 Audio

Audio may use AVPlayer over the same resource-loader design. Waveforms and cover art are encrypted derived assets. A sequential Rust reader MAY be added for long recordings when benchmarks justify it.

---

## 20. Export and sharing

Export destinations include:

- user-selected Files destination;
- save-to-Photos flow;
- `UIActivityViewController` through protected scratch;
- future encrypted backup.

### 20.1 Direct destination export

Preferred when the destination supports a file handle and failure cleanup:

```text
Rust verified/decrypting export
        ↓
platform destination handle
        ↓
commit or remove partial destination
```

Integrity failure must not leave a successful-looking partial export.

### 20.2 Activity-view-controller scratch

When a share provider requires a file URL:

- create an app-private randomly named temporary file;
- apply the strongest compatible file protection;
- exclude it from backup;
- present only after complete verification succeeds;
- delete it immediately after use or timeout;
- clean interrupted files on next launch;
- avoid private names in the temporary URL where recipient UX permits.

A recipient app receives plaintext by user request. Chur cannot force it to preserve confidentiality or delete the data.

### 20.3 Save to Photos

Saving to Photos is an explicit plaintext export. The user must understand that the resulting asset is governed by Photos/iCloud policy rather than Chur.

### 20.4 Pasteboard

Private filenames, captions, recovery material, keys, or media MUST NOT enter the pasteboard implicitly. Explicit copy actions require separate UX and privacy review.

---

## 21. Locking and scene lifecycle

Locking is a security transaction, not a view transition.

```text
UnlockedSession
      ↓ trigger
Locking
      ├── cover every active scene
      ├── reject new private work
      ├── stop AVPlayer and decoders
      ├── cancel/settle operations
      ├── increment Rust generation
      ├── zeroize session secrets
      ├── invalidate native handles
      ├── close private catalog
      ├── clear caches
      ├── destroy private graph/back stack
      └── return to PublicLocked
```

### 21.1 Triggers

- explicit lock;
- panic lock;
- inactivity timeout;
- scene backgrounding according to policy;
- protected data becoming unavailable;
- vault switch;
- platform-key failure;
- unrecoverable integrity policy;
- process termination.

### 21.2 Scene phases

The scene bridge treats active, inactive, and background transitions conservatively.

Required behavior:

- cover private content before a background snapshot can be useful;
- cancel an authentication callback whose originating request is no longer valid;
- do not restore a private route when a scene reconnects;
- coordinate multiple scenes explicitly or disable multi-window support until a secure design exists;
- keep locking idempotent under repeated lifecycle events.

### 21.3 Multi-scene policy

The initial release SHOULD support one active application scene only, or otherwise guarantee that all scenes share one session generation and lock atomically.

A second scene MUST NOT retain private content after the first scene locks.

---

## 22. Scene snapshot and capture privacy

### 22.1 App switcher snapshot

A neutral privacy/public surface is displayed before the scene enters background or becomes snapshot-eligible.

The snapshot MUST NOT contain:

- thumbnails;
- filenames;
- viewer content;
- album names;
- search queries;
- real/decoy indicators;
- authentication errors.

### 22.2 Screenshot limitations

iOS does not provide a universal public equivalent to Android `FLAG_SECURE` for arbitrary application content.

Chur MAY:

- observe platform capture-state changes;
- warn the user;
- obscure private content during detected recording or mirroring;
- cover the scene while inactive/backgrounded;
- disable product features that mirror private content.

Chur MUST NOT promise complete screenshot prevention. External cameras, compromised devices, and system behavior remain outside the guarantee.

Unsupported private hacks that rely on secure text-entry implementation details SHOULD NOT be used as a security boundary.

### 22.3 External surfaces

Private content is not exposed to:

- widgets;
- Spotlight indexing;
- Siri suggestions;
- App Intents;
- Live Activities;
- lock-screen controls;
- picture-in-picture;
- AirPlay;
- Handoff;
- pasteboard;
- notification previews;

unless a future capability receives its own threat model and explicit opt-in.

---

## 23. Public shell and discreet presentation

The public shell is a genuine functional utility surface with independent public persistence.

### 23.1 Alternate icons

iOS MAY offer user-selected alternate application icons through supported platform APIs.

Requirements:

- the user explicitly initiates the change;
- the change is reversible;
- icons accurately represent an allowed public-shell presentation;
- the private-vault capability remains disclosed to App Review;
- icon state does not reveal real/decoy identity;
- Chur does not claim to hide its identity from Settings, App Store purchase history, device management, backups, or forensic inspection.

The bundle display name is not treated as a freely dynamic disguise mechanism. Any product naming variation requires review and store-compliant packaging.

### 23.2 Review transparency

Discreet from casual observation does not mean hidden from platform review.

Review notes and test credentials must provide access to:

- the public shell;
- real vault behavior;
- decoy behavior if shipped;
- alternate icon/presentation settings;
- recovery and platform-authentication flows.

No dormant or reviewer-only behavior is allowed.

---

## 24. Notifications

User notifications are neutral by default.

They MUST NOT include:

- private filenames or album names;
- images, artwork, or waveforms;
- object counts tied to private state;
- real/decoy identity;
- unlock failure details;
- private deep-link parameters;
- recovery material.

Notification actions open the public locked shell unless an authenticated session remains valid under explicit policy.

Likely notification categories:

- user-requested encrypted import completed;
- encrypted backup transfer requires attention;
- generic operation failed;
- public-shell reminder.

Notification service extensions are not introduced in v1.

---

## 25. Background execution

### 25.1 Allowed while locked

- upload already encrypted immutable objects;
- download ciphertext into incoming/quarantined storage;
- resume opaque background URLSession transfers;
- delete expired ciphertext cache;
- reconcile non-secret transfer bookkeeping;
- run startup/background cleanup that requires no root secret.

### 25.2 Not allowed in v1 while locked

- interactive Keychain/biometric authorization;
- unwrap `VaultRootSecret` for convenience;
- decrypt thumbnails;
- create previews, OCR, waveforms, or semantic indexes;
- mutate private metadata;
- import arbitrary plaintext in the background;
- expose private notifications.

### 25.3 Background URLSession

Background transfer identifiers and task descriptions contain opaque transfer IDs only. They MUST NOT contain filenames, albums, object keys, decrypted manifests, or real/decoy identity.

Downloaded ciphertext is not committed into visible private state until Rust validates it in an authenticated session.

### 25.4 BGTaskScheduler

BG tasks are used for policy-approved maintenance and ciphertext operations. Expiration handlers cancel work and leave a recoverable transaction state.

No BG task assumes protected files or Keychain items are available merely because the process is running.

---

## 26. Extensions and App Groups

Widgets, Share Extensions, File Provider extensions, and other targets increase the secret and filesystem boundary.

They are excluded from v1 unless separately specified.

### 26.1 Share Extension considerations

A future Share Extension cannot simply open the main vault:

- interactive user presence may be unavailable or fragile;
- extending the Keychain access group broadens access;
- App Group storage broadens the filesystem boundary;
- the extension may be terminated while holding plaintext;
- private catalog concurrency becomes more complex.

A future design may use a dedicated encrypted staging protocol and main-app finalization. It requires its own threat model, key hierarchy, Data Protection policy, and crash-recovery tests.

### 26.2 Widgets and intents

Public-shell widgets MAY be possible later. Private-vault widgets, search intents, and shortcuts are disabled unless they can avoid private persistence and external indexing.

---

## 27. Future networking and sync

Ktor handles shared protocol transport. Native iOS networking provides platform background capabilities where needed.

The iOS layer may own:

- background session configuration;
- connectivity and discretionary-transfer policy;
- cellular/roaming settings;
- task restoration;
- TLS and endpoint configuration;
- proxy/VPN compatibility;
- user-visible transfer controls.

It MUST NOT:

- deserialize private metadata;
- select cryptographic suites;
- accept unbounded server-provided KDF/resource parameters;
- decide catalog conflict semantics;
- sign canonical operations outside Rust.

The server remains untrusted for confidentiality and content integrity.

---

## 28. Logging, analytics, and crash reporting

Kermit and native logging are wrapped by a privacy-safe facade.

Forbidden values include:

- Photos identifiers;
- security-scoped URL;
- file path or filename;
- EXIF/GPS;
- album, tag, or query;
- Keychain service/account identifier if correlating a vault;
- password or recovery material;
- root, collection, object, or device secret;
- private navigation route;
- real/decoy identity;
- unredacted Security/AVFoundation errors containing input details.

Preferred structured events:

```text
IMPORT_STARTED
IMPORT_COMMITTED
IMPORT_CANCELLED
IMPORT_FAILED_PROVIDER
SESSION_LOCKED_BACKGROUND
PLATFORM_KEY_INVALIDATED
PROTECTED_DATA_UNAVAILABLE
OBJECT_INTEGRITY_FAILED
NATIVE_API_INCOMPATIBLE
```

Crash reports MUST exclude screenshots, app-private attachments, databases, object containers, and memory snapshots. Native symbols can be uploaded separately for symbolication.

---

## 29. Error mapping

Native failures map to stable domain codes:

```text
AuthenticationCancelled
AuthenticationUnavailable
AuthenticationLockedOut
PlatformKeyUnavailable
PlatformKeyInvalidated
ProtectedDataUnavailable
SourcePermissionDenied
SourceUnavailable
SourceRequiresDownload
DestinationUnavailable
OperationCancelled
StorageUnavailable
NativeApiIncompatible
IoFailure
```

Internal details may appear in privacy-reviewed local diagnostics, but user-facing errors must not reveal whether a real, decoy, absent, or corrupted vault matched a credential.

---

## 30. Kotlin/Native and Swift interop

### 30.1 Shared framework

The KMP application is packaged as an Apple framework/XCFramework consumed by the iOS target.

Initial direction:

- Objective-C framework interop for broad stability;
- selective Swift Export for APIs where it materially improves Swift ergonomics;
- no secret-bearing public API types;
- generated headers checked into build artifacts or reproducibly generated;
- API compatibility verified in CI.

The Swift-facing surface SHOULD remain small:

```text
create application host
forward scene/lifecycle signals
present native picker/auth/share flow
bind platform adapters
receive redacted UI effects
```

### 30.2 Rust packaging

The Rust core is linked once through a dedicated native artifact.

The build SHOULD:

- pin Rust and Xcode toolchains;
- use Cargo lockfiles;
- build approved device/simulator slices;
- create a deterministic XCFramework or static-library package where practical;
- restrict exported symbols;
- generate/verify C headers and module maps;
- archive dSYM/native symbols;
- scan licenses and dependency advisories;
- prevent debug libraries and assertions from entering release archives.

### 30.3 Single-runtime rule

The app MUST NOT accidentally embed separate Rust runtimes through both the Kotlin framework and a second native framework.

If AVFoundation calls the Rust data plane directly, it links to the same runtime and handle registry used by KMP.

### 30.4 Control and data planes

Control-plane operations may use generated bindings:

- unlock/lock;
- queries;
- operation progress;
- migration state;
- stable errors;
- opaque handles.

The media data plane uses a stable C ABI:

- opaque integer handles;
- bounded native buffers;
- `read_at`;
- explicit close/cancel;
- no whole-file Swift `Data` values.

### 30.5 Native API handshake

At startup, Swift/Kotlin verifies:

```text
native API version
supported object-format range
supported key-slot range
build flavor compatibility
required feature flags
```

Mismatch fails closed before any vault opens.

---

## 31. Concurrency and memory

- SwiftUI and UIKit mutations occur on the main actor.
- Keychain and authentication callbacks are converted into cancellable structured operations.
- Rust I/O and Argon2id never block the main thread.
- AVAsset resource requests use a dedicated executor/queue.
- Repeated native callbacks use bounded buffers and autorelease scopes where necessary.
- Complete media never becomes one Swift `Data` or Kotlin `ByteArray`.
- Password and device-secret buffers are mutable, bounded, and cleared best effort.
- Secret bytes are never converted to `String`.
- Lock cancellation outranks ordinary operation completion.
- Swift tasks holding session handles are cancelled when generation changes.

The architecture must remain safe under races between:

- scene backgrounding and authentication completion;
- lock and AVFoundation range response;
- lock and import commit;
- protected-data loss and catalog I/O;
- background URLSession callback and vault deletion;
- picker cancellation and file-representation creation;
- app termination and scratch cleanup.

---

## 32. Deep links and URL handling

Deep links, universal links, notification routes, and URL schemes MUST default to the public locked shell.

They MUST NOT encode:

- private object IDs;
- filenames;
- album names;
- queries;
- real/decoy identity;
- active session handles;
- recovery material.

A future authenticated link may carry an opaque one-time action token, but it still requires local session authorization before resolving private content.

URL handlers must reject oversized and malformed inputs before allocation or navigation.

---

## 33. Accessibility and localization

Requirements:

- VoiceOver can complete public, unlock, recovery, and panic-lock flows;
- accessibility labels avoid unnecessary private filenames;
- privacy covers remain above private accessibility elements;
- authentication wording is understandable but non-oracular;
- Dynamic Type and display changes do not expose private content behind overlays;
- locale formatting never changes canonical Rust serialization;
- public shell and private vault remain separate in restoration and indexing;
- panic lock remains reachable without precision gestures.

Chur does not claim protection from malicious accessibility, keyboard, or screen-reading software on a compromised unlocked device.

---

## 34. Performance and resource budgets

Benchmarks are required for:

- cold launch before Kotlin/Rust initialization;
- time to render the public shell;
- LocalAuthentication-to-library latency;
- Argon2id latency and memory pressure;
- photo and multi-gigabyte video import;
- iCloud-backed source preparation;
- random AVPlayer seek latency;
- thumbnail-grid scrolling;
- Swift/Kotlin/Rust copy count;
- private-catalog query latency;
- lock completion latency;
- memory pressure and cache eviction;
- application archive size.

Use Instruments and signposts with privacy-safe identifiers. Profiling output MUST NOT include filenames, paths, metadata, keys, or private screenshots.

Optimization must not bypass authentication, integrity verification, buffer bounds, Data Protection, or session invalidation.

---

## 35. Testing strategy

### 35.1 Shared and unit tests

- UDF reducers and ViewModels;
- lock-state transitions;
- redacted error mapping;
- public/private navigation separation;
- backup-policy decisions;
- platform capability mapping;
- fake picker and file adapters;
- player coordination;
- session invalidation.

### 35.2 XCTest and integration tests

- Keychain item creation, lookup, deletion, and policy mapping;
- authentication cancellation and context invalidation;
- password/recovery re-enrollment after Keychain failure;
- protected-data unavailable behavior;
- PhotosPicker/file-representation import;
- security-scoped URL access cleanup;
- AVPlayer random seek and lock interruption;
- scene-cover behavior;
- backup-exclusion attributes;
- process termination during import/export;
- scratch cleanup after relaunch;
- notification redaction;
- real/decoy platform-state separation.

### 35.3 Physical-device matrix

Some properties require real devices:

- Face ID and Touch ID behavior;
- passcode removal/change;
- `biometryCurrentSet` invalidation;
- Data Protection after physical device lock;
- protected-data availability transitions;
- background URLSession restoration;
- iCloud-backed Photos imports;
- memory pressure;
- device performance for Argon2id and media.

Simulator tests remain useful for deterministic UI, interop, and corruption scenarios but cannot validate every hardware-backed behavior.

### 35.4 Security fault injection

- stale reader after lock;
- Keychain item missing or malformed;
- protected file inaccessible;
- storage full before final commit;
- process kill between object finalize and catalog commit;
- picker representation truncated;
- resource-loader request cancelled mid-chunk;
- backup restore without device slot;
- partial share scratch;
- log scanning with known private test canaries.

---

## 36. CI and release gates

iOS CI SHOULD include:

```text
Kotlin/KMP unit tests
Compose UI tests where supported
Swift formatting/lint policy
XCTest
Rust unit/property/fuzz tests
cargo fmt / clippy
Apple framework and XCFramework builds
header/module-map verification
device/simulator slice inspection
release archive build
dSYM/native symbol validation
privacy manifest validation
license and dependency audit
secret/log leakage tests
```

Release is blocked when:

- Kotlin/Swift/native API versions disagree;
- the archive embeds duplicate Rust runtimes;
- an approved slice is absent or an unexpected slice ships;
- backup exclusions or file-protection classes are wrong;
- scene snapshots reveal private test content;
- private data appears in logs, state restoration, or crash attachments;
- platform-slot recovery has not been exercised;
- Rust format vectors fail on iOS;
- AVFoundation readers remain usable after lock;
- privacy manifest or store declarations are incomplete;
- security invariants lack required tests.

Production claims require independent review of the Rust core, Keychain slot integration, import/export paths, AVFoundation data plane, and scene privacy.

---

## 37. App Store, privacy, and compliance

### 37.1 Review disclosure

App Review receives complete documentation and access to all significant behavior, including the private vault, decoy vault, discreet presentation, alternate icons, recovery, and authentication flows.

The public shell is not used to conceal dormant behavior from review.

### 37.2 Privacy manifest and data declarations

The application must maintain an accurate privacy manifest and required-reason API declarations for the APIs actually used by the final implementation.

Store privacy answers must reflect:

- whether sync/account features exist;
- whether diagnostics leave the device;
- whether any analytics are collected;
- whether Photos access is selection-only;
- whether user content is linked to an account;
- retention and deletion behavior.

Private media and metadata are not used for tracking or advertising.

### 37.3 Encryption export compliance

The release process must answer Apple encryption/export-compliance questions accurately. Legal classification and any required filings are release tasks, not cryptographic design decisions.

### 37.4 Security claims

Marketing MUST NOT claim:

- independent audit before one exists;
- universal screenshot prevention;
- physical secure erase;
- protection from a compromised unlocked OS;
- cryptographically undetectable plausible deniability;
- recoverability when the user selected device-bound-only storage.

---

## 38. Implementation sequence

### Phase A — shell and native handshake

- SwiftUI/UIKit app skeleton;
- Compose host;
- public shell;
- platform contracts;
- Kotlin/Rust API handshake;
- locked-only lifecycle and scene cover;
- public Room/DataStore persistence.

### Phase B — recoverable local unlock

- password/recovery slot through Rust;
- Keychain device slot;
- LocalAuthentication flow;
- invalidation and re-enrollment tests;
- secure graph creation/destruction;
- strict Data Protection setup.

### Phase C — photo vault

- PhotosPicker import;
- file-representation bridge;
- encrypted thumbnails;
- private Coil loader;
- library and viewer;
- verified export;
- atomic lock and cache clearing.

### Phase D — video and audio

- AVAsset resource loader;
- random seek;
- audio playback;
- waveform/poster derived assets;
- large-file fault injection and Instruments benchmarks.

### Phase E — decoy and discreet presentation

- independent decoy Keychain items and storage namespaces;
- public utility completeness;
- scene/notification privacy;
- alternate icons after review and testing.

### Phase F — ciphertext sync

- background URLSession transfers;
- resumable encrypted objects;
- opaque signed operation logs;
- restore and device enrollment;
- no background plaintext work by default.

---

## 39. Required iOS ADRs

Before production implementation is frozen, record decisions for:

1. minimum supported iOS version;
2. Xcode, Swift, Kotlin, and Rust toolchain versions;
3. SwiftUI representable versus UIKit root hosting model;
4. Objective-C interop versus selective Swift Export surface;
5. Rust static-library/XCFramework packaging and single-runtime enforcement;
6. exact Keychain accessibility and access-control combinations;
7. device-secret platform-slot representation;
8. LocalAuthentication reuse and fallback policy;
9. Data Protection classes by file category;
10. iCloud/device-backup inclusion and exclusion rules;
11. SQLCipher build and linkage on Apple targets;
12. AVAsset resource-loader queue and buffer ownership;
13. background URLSession and BGTask policy;
14. multi-scene support or explicit prohibition;
15. alternate icon and public-shell presentation policy;
16. screenshot/capture response policy;
17. analytics/crash provider and redaction guarantees;
18. privacy manifest and required-reason API inventory;
19. baseline performance budgets.

---

## 40. iOS security checklist

Before an iOS release candidate:

- [ ] Every launch and process restoration starts in `PublicLocked`.
- [ ] Private navigation, viewer, and player state are never restored.
- [ ] Keychain stores only short platform secret material, not media keys.
- [ ] Password/recovery unlock works after Keychain invalidation or device restore.
- [ ] Real and decoy Keychain identifiers and namespaces are unrelated.
- [ ] UserDefaults, Room public tables, SwiftData, and Core Data contain no private metadata.
- [ ] Key-slot, catalog, object, and scratch files have reviewed Data Protection classes.
- [ ] Backup excludes plaintext and nonportable state.
- [ ] Restored ciphertext requires a valid portable recovery slot.
- [ ] Photos and Files imports do not load complete videos into Swift/Kotlin memory.
- [ ] Security-scoped URL access is balanced in every path.
- [ ] AVFoundation readers fail with `SessionExpired` after lock.
- [ ] Scene snapshots contain only neutral public/privacy UI.
- [ ] Capture policy makes no unsupported prevention claim.
- [ ] Now Playing, AirPlay, PiP, notifications, Spotlight, widgets, and intents expose no private data.
- [ ] Scratch exports are protected, backup-excluded, and cleaned after interruption.
- [ ] Logs and crash reports pass private-canary scanning tests.
- [ ] The release archive contains one compatible Rust runtime and correct slices.
- [ ] Native API handshake fails closed on incompatibility.
- [ ] Cross-platform golden vectors pass on iOS.
- [ ] Privacy manifest, store privacy answers, and encryption compliance are complete.

---

## 41. References

- [Chur README](../README.md)
- [Chur system architecture](ARCHITECTURE.md)
- [Chur Android platform architecture](ANDROID.md)
- [Keychain Services](https://developer.apple.com/documentation/security/keychain-services)
- [LocalAuthentication](https://developer.apple.com/documentation/localauthentication)
- [Data Protection](https://support.apple.com/guide/security/data-protection-overview-secf6276da8a/web)
- [PhotosPicker](https://developer.apple.com/documentation/photosui/photospicker)
- [AVAssetResourceLoaderDelegate](https://developer.apple.com/documentation/avfoundation/avassetresourceloaderdelegate)
- [BackgroundTasks](https://developer.apple.com/documentation/backgroundtasks)
- [Background URLSession](https://developer.apple.com/documentation/foundation/urlsessionconfiguration/background(withidentifier:))

---

## 42. Summary

The iOS implementation is a thin, security-sensitive SwiftUI/UIKit host around shared KMP/CMP application code and a Rust-owned vault runtime.

Its central obligations are:

```text
Authorize Keychain secret release without becoming the key hierarchy.
Provide bounded Photos, Files, and AVFoundation integration without owning vault bytes.
Apply Data Protection, scene-cover, backup, and notification policy consistently.
Invalidate every private operation on lock.
Keep public-shell persistence completely separate from private-vault state.
```

When an iOS convenience conflicts with Rust ownership, recoverability, integrity, or deterministic locking, the security invariant wins.