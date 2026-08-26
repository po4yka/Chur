# Android Integration Contract

> **Status:** Proposed focused interop contract  
> **Relationship:** platform implementation detail; it does not define vault bytes

This document complements the broader Android architecture guide when present. Rust remains authoritative for keys, slots, catalog, containers, integrity, and migrations.

## 1. Application shell

The Android application is thin and owns:

- lifecycle and process/task integration;
- Keystore and `BiometricPrompt` authorization;
- Photo Picker/SAF/MediaStore integration;
- file descriptor opening and permissions;
- Media3 and platform codecs;
- protected directories and backup rules;
- `FLAG_SECURE`, recents, notifications, and public launcher surfaces;
- native library loading and JNI bridge.

## 2. Keystore slot

Preferred policy:

```text
AES-256-GCM wrapping key
PURPOSE_ENCRYPT | PURPOSE_DECRYPT
user authentication required
TEE-backed when available
StrongBox optional
random opaque alias
```

Requirements:

- probe capability; do not assume hardware backing;
- handle `StrongBoxUnavailableException` with explicit fallback;
- handle permanent invalidation through password/recovery;
- never use Keystore to stream media;
- independent aliases for real/decoy identities;
- exclude device-bound state from portable backup;
- verify replacement slot before deleting old alias.

## 3. BiometricPrompt

Biometric/credential authorizes a Keystore operation. It does not derive a key. UI must handle:

- user cancellation;
- temporary/permanent lockout;
- no enrolled biometric;
- device credential fallback policy;
- enrollment change invalidation;
- process/activity recreation;
- no disclosure of target real/decoy identity.

## 4. Storage placement

Proposed:

```text
filesDir/             encrypted catalog and committed object store
noBackupFilesDir/     device-only alias references/local identity state
cacheDir/             disposable ciphertext and controlled plaintext scratch
```

All paths are app-private and opaque. Backup/data-extraction rules explicitly include portable ciphertext only when password/recovery restoration is possible.

## 5. Import

Use Photo Picker for least-privilege photo/video selection. Use SAF for supported documents/audio when needed.

Flow:

1. receive selected `Uri`;
2. open `ParcelFileDescriptor`/`AssetFileDescriptor`;
3. determine seekability/known length without trusting metadata;
4. duplicate/pass descriptor under FFI ownership contract;
5. stream to Rust encryptor;
6. use platform probe/thumbnail APIs only through bounded transient results;
7. release persisted URI permissions unless explicitly needed;
8. report success only after encrypted commit.

Cloud-backed providers require progress, cancellation, and potentially unknown length.

## 6. Media3 playback

A custom `DataSource` delegates range reads to `ObjectReaderHandle`:

- map requested position/length to Rust `read_at`;
- authenticate affected chunks before returning bytes;
- reuse bounded direct buffers;
- propagate EOF only from authenticated size/final commit;
- stop and close on lock/session expiry;
- avoid disk plaintext cache;
- map corruption separately from transient I/O.

## 7. Images

Private images use a dedicated Coil `ImageLoader` with:

- Rust-backed thumbnail/preview fetcher;
- separate bounded memory cache;
- disk cache disabled or ciphertext-only;
- session-generation cache keys;
- complete invalidation on lock;
- no public-shell cache sharing.

## 8. Export

User-selected destinations use MediaStore, SAF, or share sheet. If a provider requires a plaintext file:

- use app-private protected scratch/FileProvider;
- random name;
- backup/index exclusion;
- delete after recipient completion when observable;
- startup cleanup journal;
- warn that external recipient may retain plaintext.

## 9. Lifecycle

Lock policy observes process/activity visibility without keeping keys alive for background convenience. Before background snapshot:

- cover private UI;
- stop private playback;
- initiate lock according to policy;
- clear navigation/cache after native invalidation.

Process death always restores public locked state.

## 10. Screen and external surfaces

- apply `FLAG_SECURE` to sensitive activities/windows according to product policy;
- no private content in notifications, widgets, shortcuts, app links, clipboard, or assist content;
- block insecure external display presentation where supported;
- accessibility tree cleared/covered on lock;
- public launcher/alternate icon behavior remains user-controlled and documented.

## 11. Background work

WorkManager may perform ciphertext-only transfer/reconciliation while locked. It must not unlock Keystore keys, open catalog plaintext, generate thumbnails, or create plaintext scratch.

## 12. Permissions

Request least privilege at point of use. Broad media-library permissions are not default when Photo Picker suffices. Storage, notification, biometric, network, and foreground-service permissions require documented product need.

## 13. JNI/native packaging

- pin NDK and Rust targets;
- package only supported ABIs;
- verify ELF architecture, symbols, and stripping policy;
- keep JNI adapter narrow;
- prevent exceptions/panics across boundary;
- run ABI handshake before opening vault;
- ensure R8 does not remove required bridge symbols/classes.

## 14. Tests

- hardware/TEE/StrongBox capability matrix;
- biometric enrollment and lock-screen changes;
- backup/restore and app reinstall;
- Photo Picker local/cloud/large/non-seekable inputs;
- Media3 seek, EOF, corruption, lock race;
- process death/background snapshots;
- scratch cleanup and backup exclusion;
- public/private cache and storage isolation;
- real/decoy alias/session isolation;
- supported Android API/ABI matrix.
