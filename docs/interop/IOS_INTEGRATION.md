# iOS Integration Contract

> **Status:** Proposed focused interop contract  
> **Relationship:** platform implementation detail; it does not define vault bytes

The iOS shell owns Keychain authorization, Photos/Files integration, AVFoundation, Data Protection, scene lifecycle, and Rust/Kotlin/Swift packaging. Rust remains authoritative for private formats and cryptographic state.

## 1. Keychain slot

Preferred design:

```text
random DeviceUnlockSecret
Keychain accessibility: ThisDeviceOnly profile
SecAccessControl: userPresence by default
optional strict mode: biometryCurrentSet
Rust HKDF → AppleDeviceKEK
Rust AEAD wraps VaultRootSecret
```

Requirements:

- opaque item identifiers independent for real/decoy;
- `LAContext` scoped to one authorization flow;
- handle user cancellation, lockout, passcode/biometry changes, item loss, and restore;
- use password/recovery to re-enroll invalidated item;
- device-bound item excluded from portable backup;
- no Secure Enclave streaming of media.

## 2. Storage and Data Protection

Proposed placement:

```text
Application Support/   encrypted catalog and committed objects
Caches/                disposable ciphertext and controlled scratch
```

Sensitive local files use the strongest compatible Data Protection class, preferably complete protection for catalog, descriptors, and plaintext scratch. Ciphertext-only background-transfer files may use a separately reviewed class.

Explicitly set backup-exclusion attributes for device-only/temp/scratch data. Portable ciphertext enters backup only under recovery policy.

## 3. Protected-data availability

If protected data is unavailable:

- remain public/locked;
- do not open catalog or restore private navigation;
- defer cleanup that requires protected files safely;
- allow only policy-approved ciphertext transfer using already accessible files;
- resume after protected-data availability notification and explicit session flow.

## 4. Import from Photos

Use `PhotosPicker`/Transferable or Photos framework file representation:

- support iCloud-backed download progress and cancellation;
- prefer file representation over loading entire media into memory;
- stream through a bounded descriptor/file handle to Rust;
- treat reported size/type as untrusted metadata;
- handle Live Photos/compound assets through explicit media-pipeline policy;
- release temporary representations after encrypted commit.

## 5. Import from Files

Security-scoped URLs are acquired only for operation lifetime unless explicit persistent access is required. Coordinate reads when providers require it, detect non-seekable/cloud-backed behavior, and never persist user paths as physical vault names.

## 6. AVFoundation playback

`AVAssetResourceLoaderDelegate` or an equivalent custom resource path maps byte-range requests to Rust `read_at`:

- authenticate chunks before responding;
- accumulate short reads with `respond(with:)` and call `finishLoading()` only when the requested range is satisfied or `read_at` reports end of stream at `offset == size`; call `finishLoading(with:)` on any failure status, per [`FFI_CONTRACT.md`](FFI_CONTRACT.md) §6.3;
- provide correct content information after manifest/final-commit validation;
- use bounded buffers;
- cancel pending requests on lock;
- ensure stale handles return session expired;
- no plaintext disk cache unless scratch policy explicitly requires it.

## 7. Images and codecs

Compose/private image pipeline uses encrypted thumbnails/previews. Platform ImageIO/Core Image/AVFoundation may decode transient plaintext where needed. Rust receives normalized metadata/derivative bytes and remains the persistence owner.

Caches are session-scoped and cleared on lock/background policy.

## 8. Export and sharing

Use Photos, Files exporters, or `UIActivityViewController`. If an activity requires a URL:

- create protected app-private scratch;
- random opaque filename/required extension;
- backup excluded;
- delete after completion and on startup reconciliation;
- state clearly that the destination may retain plaintext.

Pasteboard export is disabled by default for media/private metadata.

## 9. Scene lifecycle

Before background scene snapshot:

- cover private UI with public/neutral view;
- stop private playback;
- apply lock policy;
- clear private restoration state;
- invalidate native session independently.

Multi-scene support requires a single coherent vault-session policy; no scene may retain a reader after another locks the vault.

## 10. Screenshots and capture

There is no universal public API guaranteeing screenshot prevention. Chur may:

- obscure app-switcher snapshots;
- react to screen-capture/mirroring state;
- hide sensitive content on external scenes;
- warn users according to policy.

It must not claim complete prevention.

## 11. Background work

`BGTaskScheduler` and background URL sessions are ciphertext-only while locked. They must not release Keychain secret, open private catalog, or generate plaintext derivatives.

Extensions/App Groups are deferred until a dedicated threat model; they widen the process/storage boundary.

## 12. Interop packaging

- Rust built once per target into a static library/XCFramework strategy;
- Kotlin/Native uses C interop or generated adapter for control plane;
- Swift Export is selective and does not expose secure-core internals;
- avoid duplicate Rust runtime/library instances;
- ABI handshake before use;
- symbols and architectures verified in release artifact.

## 13. Privacy/store requirements

- privacy manifest and required-reason APIs reviewed;
- App Store review notes explain vault/discreet access;
- export-compliance declarations match actual algorithms/distribution;
- no private content in notifications, Spotlight, Siri/App Intents, widgets, Handoff, logs, or analytics;
- alternate icons remain user-controlled and documented.

## 14. Tests

- Keychain accessibility/access-control profiles;
- passcode/biometric changes and item invalidation;
- device backup/restore/reinstall;
- protected-data unavailable/available transitions;
- PhotosPicker local/iCloud/large/compound assets;
- Files security scope and provider cancellation;
- AVPlayer seek/corruption/lock races;
- scene background/snapshot/multi-scene behavior;
- scratch cleanup and backup exclusion;
- real/decoy item/cache/session isolation;
- device/simulator architecture matrix.
