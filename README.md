# Chur

> A local-first, cross-platform private media vault for Android and iOS, built with Kotlin Multiplatform, Compose Multiplatform, and a Rust-owned cryptographic storage engine.

**Status:** Proposed — architecture and protocol design. Chur is not yet suitable for storing irreplaceable data, has not received an independent security audit, and does not currently make production security guarantees.

Chur is designed for protected storage of photos, videos, audio, and eventually documents. The application can present a real, functional public interface—such as notes, a journal, or a calculator—while keeping the encrypted archive behind a separate authenticated session.

The public shell is a privacy feature, not the security boundary. The actual boundary is the Rust vault runtime: it owns the private catalog, key hierarchy, encrypted object format, integrity rules, migrations, and every transition between ciphertext and plaintext.

The name **Chur** evokes a boundary around what belongs to the user: a private space with an explicit access boundary.

This README is explanatory and ranks last when documents disagree. The normative specifications are indexed in [`docs/README.md`](docs/README.md), which also defines the [authority hierarchy](docs/README.md#authority-hierarchy), the [normative-language rule](docs/README.md#normative-language), and the [document-status vocabulary](docs/README.md#document-status). Start there before implementing anything described below. Suspected vulnerabilities go through [`SECURITY.md`](SECURITY.md).

---

## Table of contents

- [Product model](#product-model)
- [Goals and non-goals](#goals-and-non-goals)
- [Architecture](#architecture)
- [Technology stack](#technology-stack)
- [Repository layout](#repository-layout)
- [Ownership and data boundaries](#ownership-and-data-boundaries)
- [Private storage model](#private-storage-model)
- [Key hierarchy](#key-hierarchy)
- [Key slots and recovery](#key-slots-and-recovery)
- [Real and decoy vaults](#real-and-decoy-vaults)
- [Encrypted object format](#encrypted-object-format)
- [Integrity and verification](#integrity-and-verification)
- [Import, export, and plaintext lifecycle](#import-export-and-plaintext-lifecycle)
- [Media playback](#media-playback)
- [Rust and KMP interop](#rust-and-kmp-interop)
- [Android integration](#android-integration)
- [iOS integration](#ios-integration)
- [Sync and sharing](#sync-and-sharing)
- [Threat model](#threat-model)
- [Security invariants](#security-invariants)
- [Testing and assurance](#testing-and-assurance)
- [Roadmap](#roadmap)
- [Design references](#design-references)
- [Security and contribution status](#security-and-contribution-status)
- [License](#license)

---

## Product model

Chur is not intended to be a superficial “calculator vault” that hides ordinary files behind a secret screen. It is a complete encrypted archive with optional discreet interfaces.

```text
Chur
├── Public shell
│   ├── Notes
│   ├── Journal
│   ├── Calculator
│   └── other genuinely functional utilities
│
├── Session gate
│   ├── password
│   ├── platform authentication
│   ├── recovery flow
│   └── optional decoy credential
│
├── Private vault
│   ├── photos
│   ├── videos
│   ├── audio
│   ├── albums
│   ├── metadata
│   └── encrypted search/indexes
│
└── Rust secure core
    ├── key hierarchy
    ├── encrypted object format
    ├── private catalog
    ├── integrity validation
    ├── migrations
    └── future sync protocol
```

### Public shell

The public shell must be a real application surface, not a static fake screen. A user should be able to open it, create notes, perform calculations, or use a journal without exposing the existence or current state of a private session through the UI.

Public-shell data is not mixed with private-vault data. It has its own Room database, navigation graph, dependency graph, and lifecycle.

### Private vault

The private vault is a media-first archive with a user experience closer to a private Photos/Files application than to a directory protected by a PIN.

Planned capabilities include:

- encrypted photo, video, and audio import;
- timeline, albums, favorites, and tags;
- encrypted thumbnails, previews, waveforms, and metadata;
- random-access media playback without decrypting complete files;
- protected export through the platform share/save flows;
- immediate and timed locking;
- local integrity validation and repair tooling;
- optional recovery and encrypted backup;
- later end-to-end encrypted sync and collection sharing.

### Discreet mode

The discreet layer can provide:

- neutral launcher presentation;
- neutral notifications;
- a real public workspace;
- app-switcher privacy overlays;
- immediate panic lock;
- an independent decoy vault;
- no private filenames, thumbnails, counts, or navigation state in public surfaces.

Discreet mode is not described as an undetectable hidden volume. Filesystem analysis, backups, ciphertext volume, I/O patterns, or a compromised operating system may reveal that additional encrypted data exists.

Store review and product documentation must disclose the vault functionality. Chur is intended to be discreet from casual observation, not hidden from platform review processes.

---

## Goals and non-goals

### Goals

1. **Strong data-at-rest protection.** Copying the application sandbox must not reveal media, filenames, EXIF, album names, thumbnails, or private indexes.
2. **Rust-owned private state.** Rust owns the storage format, key hierarchy, catalog schema, object lifecycle, migrations, and integrity decisions.
3. **Cross-platform behavior.** Android, iOS, and the future CLI must read the same canonical vault format and pass the same test vectors.
4. **Media-first random access.** Large videos and audio files must support bounded-memory import, playback, seek, and export.
5. **Recoverable by explicit policy.** A user can choose a device-bound vault or a recoverable vault with password/recovery envelopes.
6. **Independent object keys.** Compromise or deletion of one object key must not expose unrelated media.
7. **Local-first delivery.** The first useful version must not depend on a server.
8. **Auditable protocol.** Cryptographic structures must be versioned, documented, fuzzable, and testable outside the mobile UI.
9. **Minimal plaintext lifetime.** Plaintext is treated as a toxic, transient resource.
10. **Honest security claims.** Chur must clearly state what it can and cannot defend against.

### Non-goals

Chur does not claim to provide:

- protection from a fully compromised kernel or operating system;
- protection from malware that can inspect an already unlocked process;
- guaranteed prevention of screenshots on every platform;
- guaranteed physical secure erase on flash storage;
- cryptographically undetectable plausible deniability;
- global plaintext-content deduplication;
- end-to-end encrypted sharing in the first release;
- custom, unaudited cryptographic primitives;
- FIPS certification in the initial product scope.

---

## Architecture

```text
┌──────────────────────────────────────────────────────────────┐
│ Android application shell / iOS application shell           │
│                                                              │
│ Lifecycle │ Keystore/Keychain │ Pickers │ Native players     │
├──────────────────────────────────────────────────────────────┤
│ Compose Multiplatform UI                                     │
│                                                              │
│ Public shell │ Session gate │ Library │ Viewer │ Settings    │
├──────────────────────────────────────────────────────────────┤
│ KMP application layer                                        │
│                                                              │
│ Navigation │ ViewModels │ UDF │ Use cases │ Orchestration    │
├──────────────────────────────────────────────────────────────┤
│ KMP ↔ Rust adapter                                           │
│                                                              │
│ Control API │ Opaque handles │ Range I/O │ Error mapping      │
├──────────────────────────────────────────────────────────────┤
│ Rust vault runtime                                           │
│                                                              │
│ Crypto │ Key slots │ Catalog │ Object store │ Migrations     │
│ Import │ Export │ Integrity │ Session invalidation          │
├──────────────────────────────────────────────────────────────┤
│ Encrypted local storage                                      │
│                                                              │
│ Rust-owned catalog │ Immutable objects │ Journals │ Temp     │
└──────────────────────────────────────────────────────────────┘
```

The primary architectural invariant is:

> KMP owns the application experience. Rust owns private data and its cryptographic lifecycle. Platform security services only protect the ability to release or unwrap the vault root secret.

### Dependency graphs

Chur uses separate public and secure graphs:

```text
ApplicationGraph
├── PublicGraph                       Koin classic DSL
│   ├── public navigation
│   ├── public shell features
│   ├── Room
│   ├── DataStore
│   └── non-sensitive scheduling
│
└── SecureGraph                       manual DI
    ├── VaultSession
    ├── VaultEngine
    ├── PlatformRootKeyProtector
    ├── PrivateMediaSource
    ├── PrivateCatalogRepository
    └── SessionKeyLifetime
```

Manual construction is preferred for the secure graph because object ownership, destruction order, and session invalidation are security-relevant behavior. Koin remains useful for application and feature composition, but the cryptographic graph should not depend on a general-purpose service locator.

---

## Technology stack

The current planned baseline is:

| Area | Choice |
| --- | --- |
| Language | **Kotlin 2.4.10 / K2** |
| UI | **Compose Multiplatform 1.11.1** |
| Navigation | **Navigation 3 Multiplatform** |
| Architecture | **AndroidX ViewModel + UDF/MVVM** |
| State | **StateFlow / Flow** |
| Concurrency | **Kotlin Coroutines 1.11** |
| Dependency injection | **Koin 4.2.2 classic DSL** for application/features; **manual DI** for the secure graph |
| Network | **Ktor Client 3.5.2** |
| Serialization | **kotlinx.serialization 1.11** |
| Public database | **Room 3.0.1 KMP** |
| Public settings | **DataStore KMP** |
| Images | **Coil 3.5** with a separate private loader |
| Time | `kotlin.time` + **kotlinx-datetime 0.8** |
| Logging | **Kermit**, behind a privacy-safe logging facade |
| Testing | `kotlin.test`, Coroutines Test, Turbine, CMP UI tests |
| Android | Thin native application shell |
| iOS | Thin SwiftUI/UIKit shell with Compose content |
| Build | Gradle Version Catalog, convention plugins, KSP |
| Apple interop | Objective-C framework interop initially; selective Swift Export where stable and beneficial |
| Secure storage engine | **Rust** |
| Local media AEAD | **XChaCha20-Poly1305** |
| Password KDF | **Argon2id** |
| Key derivation | **HKDF-SHA-256** with explicit domain separation |
| Sharing | RFC 9180 HPKE with X25519, plus separate sender identity/signatures |
| Secret hygiene | `zeroize`, redacted secret wrappers, restricted logging |

Version numbers describe the intended project baseline and may be updated through normal dependency-management work before the first implementation release.

### Candidate Rust dependencies

The secure core should use narrow, well-reviewed libraries rather than a broad crypto framework:

```toml
chacha20poly1305 = "..." # XChaCha20-Poly1305
argon2           = "..." # Argon2id
hkdf             = "..."
sha2             = "..."
blake3           = "..." # ordered object commitments
getrandom        = "..." # platform CSPRNG
zeroize          = "..."
secrecy          = "..."
subtle           = "..."
thiserror        = "..."
```

Potential later dependencies include SQLCipher through `rusqlite`, HPKE/X25519, Ed25519, and an `age`-compatible backup layer. Dependency choice must remain subordinate to protocol tests and auditability.

---

## Repository layout

The repository is expected to evolve toward the following structure:

```text
Chur/
├── apps/
│   ├── androidApp/
│   └── iosApp/
│
├── shared/
│   ├── app/
│   ├── design/
│   ├── navigation/
│   ├── core-model/
│   ├── core-platform/
│   ├── core-rust-bridge/
│   ├── core-public-data/
│   ├── core-settings/
│   ├── core-network/
│   ├── core-media/
│   ├── feature-public-shell/
│   ├── feature-unlock/
│   ├── feature-library/
│   ├── feature-import/
│   ├── feature-viewer/
│   ├── feature-audio/
│   └── feature-settings/
│
├── rust/
│   ├── Cargo.toml
│   └── crates/
│       ├── chur-core/
│       ├── chur-crypto/
│       ├── chur-format/
│       ├── chur-catalog/
│       ├── chur-media/
│       ├── chur-sync-protocol/
│       ├── chur-ffi/
│       └── chur-cli/
│
├── build-logic/
│   └── convention/
│
├── docs/
│   ├── ARCHITECTURE.md
│   ├── adr/
│   ├── security/
│   ├── format/
│   ├── interop/
│   ├── sync/
│   ├── assurance/
│   └── product/
│
└── test-vectors/
```

### Rust crate responsibilities

| Crate | Responsibility |
| --- | --- |
| `chur-core` | Vault lifecycle, sessions, orchestration, common domain types |
| `chur-crypto` | AEAD, KDFs, key derivation, wrapping, secret types, zeroization |
| `chur-format` | Canonical object container, key envelopes, versioning, migrations |
| `chur-catalog` | Rust-owned private database, transactions, indexes, catalog migrations |
| `chur-media` | Streaming import/export, random-access readers, derived asset coordination |
| `chur-sync-protocol` | Canonical encrypted operations, device logs, grants, protocol versioning |
| `chur-ffi` | Stable C ABI, handle registry, and panic containment |
| `chur-cli` | Test vectors, inspection, validation, migration, recovery, fuzz corpora |

`chur-cli` is a first-class architecture component. The storage format must be testable and recoverable independently of Android and iOS UI code.

---

## Ownership and data boundaries

### KMP may own

- public-shell content;
- screen state and transient UI projections;
- navigation keys that contain only opaque session-scoped identifiers;
- operation progress;
- platform capability state;
- non-sensitive preferences;
- ciphertext upload/download orchestration;
- error categories that do not expose private input.

### KMP must not own

- `VaultRootSecret` or long-lived key material;
- canonical private metadata serialization;
- filenames, EXIF, private timestamps, album names, or search indexes in Room;
- object-key wrapping;
- container parsing or integrity decisions;
- vault migrations;
- sync-operation signing or canonical protocol encoding;
- plaintext media caches on disk;
- restorable private navigation state.

### Persistence split

```text
Public shell
    Room 3 KMP
    DataStore KMP

Private vault
    Rust-owned encrypted catalog
    Rust-owned encrypted object store
```

Room is intended for real public-shell data such as notes, journal entries, or calculator history. DataStore is intended for non-sensitive application settings.

The current private-catalog design direction is a **Rust-owned SQLCipher database**, subject to prototype and binary-size validation. Rust owns the connection, key, schema, queries, migrations, and close/zeroization lifecycle. Object keys remain individually wrapped even when stored inside SQLCipher; database encryption is defense in depth, not a replacement for envelope encryption.

The private catalog can contain queryable plaintext only while the database is open inside the unlocked Rust runtime. Its file, WAL, and related storage must remain encrypted at rest.

---

## Private storage model

Private data is split into:

```text
Vault
├── Catalog
│   ├── object records
│   ├── metadata revisions
│   ├── logical albums
│   ├── security collections/key domains
│   ├── wrapped object keys
│   ├── derived-asset relationships
│   ├── integrity state
│   └── future encrypted sync state
│
└── Object store
    ├── immutable encrypted originals
    ├── encrypted previews
    ├── encrypted thumbnails
    ├── encrypted poster frames
    └── encrypted audio waveforms
```

Physical filenames are random opaque identifiers. Original names, paths, MIME types, dimensions, duration, EXIF, GPS, timestamps, captions, favorites, OCR, face data, and search indexes are treated as private.

A UI album is not automatically a cryptographic collection.

- **Album** is a logical user grouping.
- **Security collection** or **key domain** is a unit of access control, sharing, key rotation, and revocation.

This distinction prevents ordinary album edits from forcing cryptographic rewrapping. A local private vault may initially use one key domain for many albums; new collection keys are created when a different access policy is required.

---

## Key hierarchy

Chur uses envelope encryption. Passwords and platform credentials do not directly encrypt media.

```text
                           VaultRootSecret
                                  │
              ┌───────────────────┼────────────────────┐
              │                   │                    │
              ▼                   ▼                    ▼
    CollectionEnvelopeKey   CatalogDatabaseKey   IdentifierKey
              │
              ▼
      SecurityCollectionKey[epoch]
              │
              ▼
             ObjectKey
              │
     ┌────────┼──────────┬─────────────┬───────────────┐
     ▼        ▼          ▼             ▼               ▼
 ContentKey MetadataKey PreviewKey ThumbnailKey FinalCommitKey
```

Root subkeys are derived with HKDF-SHA-256 and explicit versioned domain strings. Every label string, the key it derives, its input key, and its output length are registered in [`docs/security/KEY_HIERARCHY.md`](docs/security/KEY_HIERARCHY.md) §3, which is the only place a label is defined.

A single key must not be reused across different protocols or semantic purposes.

### Per-object keys

Every imported media object receives a random 256-bit `ObjectKey` generated by the Rust core through the operating-system CSPRNG.

Benefits include:

- a minimal blast radius;
- per-object crypto-erasure when all envelopes are removed;
- password changes without media re-encryption;
- collection-key rotation through key rewrapping;
- independent upload, repair, and verification;
- immutable object containers.

### Collection epochs

Security collection keys are versioned:

```text
CollectionKey(epoch = 1)
CollectionKey(epoch = 2)
CollectionKey(epoch = 3)
```

New objects use the current epoch. Older `ObjectKey` envelopes can be rewrapped gradually without re-encrypting the underlying media container.

---

## Key slots and recovery

`VaultRootSecret` is random. It is never derived from a password and is never stored as plaintext.

It can be wrapped into one or more versioned key slots:

```text
VaultKeySlots
├── PasswordSlot
├── AndroidKeystoreSlot
├── AppleKeychainSlot
├── RecoverySlot
└── PeerDeviceSlot          future
```

Each slot is a canonical record containing at least:

```text
slot_id
slot_type
slot_version
kdf_or_platform_suite
wrap_suite
validated_parameters
nonce
wrapped_vault_root_secret
```

### Password slot

```text
password
    ↓ UTF-8 canonicalization and validation
Argon2id(password, random salt, bounded parameters)
    ↓
PasswordKEK
    ↓ XChaCha20-Poly1305 key wrap
VaultRootSecret
```

Required rules:

- store the salt and Argon2 parameters with the slot;
- validate minimum and maximum parameters before allocation;
- reject unbounded memory, iteration, parallelism, and input lengths;
- use a versioned password encoding policy;
- never use a fast hash as the password KDF;
- upgrade outdated parameters after a successful unlock through root-key rewrapping;
- never log passwords, KEKs, salts plus user input, or decrypted root secrets;
- never expose secret-bearing types through ordinary `Debug` output.

Changing a password rewraps `VaultRootSecret`; it does not re-encrypt the archive.

### Platform slot

Android Keystore and iOS Keychain protect a short wrapping key or root-key envelope. They do not process media files.

Biometrics are authorization policy, not key material:

```text
BiometricPrompt / LAContext
        ↓ authorizes platform operation
Keystore / Keychain
        ↓ releases or unwraps a root-key envelope
Rust vault runtime
        ↓ owns the unlocked session secret temporarily
```

Platform-backed credentials may be invalidated by biometric enrollment changes, device-passcode changes, backup/restore behavior, or keychain/keystore policy. A recoverable vault must retain an independent password or recovery slot.

### Recovery slot

A recovery secret is high-entropy random data, not a second low-entropy password. It may be represented as a mnemonic or QR code, but the canonical binary representation must be fixed and versioned.

### Recovery modes

1. **Device-bound vault** — only a platform slot; maximum local binding, highest data-loss risk.
2. **Recoverable vault** — platform slot plus password and/or recovery slot; recommended consumer default.
3. **Synced vault** — additional device identities and encrypted device/collection grants; future scope.

---

## Real and decoy vaults

A decoy vault is not a filtered view of the real vault. It is a fully independent cryptographic identity.

```text
Real credential
    ↓
Real VaultRootSecret
    ↓
Real catalog
    ↓
Real object namespace
    ↓
Real caches and session

Decoy credential
    ↓
Decoy VaultRootSecret
    ↓
Decoy catalog
    ↓
Decoy object namespace
    ↓
Decoy caches and session
```

The two vaults must not share:

- root or collection keys;
- catalogs;
- object-key envelopes;
- object namespaces;
- thumbnails or playback caches;
- private navigation state;
- search indexes;
- integrity logs;
- sync identities;
- platform key aliases.

The KMP layer should receive an opaque `VaultSessionHandle`, not an `isDecoy` boolean that encourages branching over shared private data.

A decoy vault improves coercion resistance at the user-experience level. It does not make the existence of additional ciphertext cryptographically undetectable.

---

## Encrypted object format

The main media store uses immutable containers composed of independently authenticated XChaCha20-Poly1305 chunks.

The object-key envelope is stored separately from the media container. This avoids a circular dependency between the key required to decrypt the header and the wrapped object key, and it permits collection rewrapping without rewriting gigabytes of media.

### Object-key envelope

```text
ObjectKeyEnvelope
├── format_version
├── object_id
├── key_domain_id
├── collection_epoch
├── wrap_suite_id
├── wrap_nonce
└── wrapped_object_key
```

### Container v1

```text
ChurObjectV1
├── PublicPreambleV1
├── EncryptedManifestRecordV1
├── ChunkRecordV1[0..N-1]
└── FinalCommitRecordV1
```

Byte offsets, field widths, and v1 constants are frozen in [`docs/format/OBJECT_CONTAINER_V1.md`](docs/format/OBJECT_CONTAINER_V1.md), which is the authority for container bytes.

The public preamble carries no object identifier; object and stream identifiers live in the encrypted records. It contains only information required to parse the container, and it must not expose the original filename, media type, dimensions, duration, album, EXIF, GPS, or user-visible identifiers.

Ciphertext length still reveals an approximate object size. Optional padding may be added later, but Chur does not initially attempt oblivious storage.

### Chunk nonce

For XChaCha20-Poly1305:

```text
chunk_nonce = random_prefix_128bit || chunk_index_u64_be
```

Every encrypted stream revision receives a fresh random nonce prefix, even when the underlying object-derived key remains unchanged.

Mutable metadata, thumbnails, previews, and other derived assets therefore use explicit revisions:

```text
Original stream revision 1       immutable
Metadata revision 18             fresh nonce prefix
Thumbnail revision 4             fresh nonce prefix
Preview revision 2               fresh nonce prefix
```

Reusing the same key-and-nonce pair is forbidden.

### Chunk AAD

Each chunk is bound to its context through canonical associated data. The bound items are listed in [`docs/format/OBJECT_CONTAINER_V1.md`](docs/format/OBJECT_CONTAINER_V1.md) §9 and are not repeated here.

This prevents unnoticed chunk reordering, cross-object substitution, stream-kind confusion, substitution against a different manifest, and interpretation under an incompatible format revision.

The total length and expected chunk count are not required in every chunk AAD because an import source may not know its final length in advance. Whole-object completeness is committed separately in the final record.

### Chunk sizing

Initial benchmark candidates:

- approximately **1 MiB** for video and large audio;
- approximately **256 KiB** for photos and small objects;
- separate small encrypted objects for thumbnails and previews.

These are performance parameters, not protocol truths. They must be selected through Android/iOS benchmarks covering playback startup, seek latency, memory pressure, FFI overhead, throughput, battery use, and resumable transfer granularity.

---

## Integrity and verification

Independent AEAD chunks prove the authenticity of individual ranges. They do not, by themselves, prove that the complete object exists.

Chur distinguishes:

```text
VerifiedRange
    the requested chunk/range passed AEAD verification

CompleteVerifiedObject
    the manifest, all expected chunks, final commit, length,
    order, and commitment were validated as a complete object
```

The final commit authenticates the object and stream identity, the manifest commitment, the expected chunk count, the total plaintext size, the final chunk length, and an ordered commitment over all ciphertext records. Its sealed contents are listed in [`docs/format/OBJECT_CONTAINER_V1.md`](docs/format/OBJECT_CONTAINER_V1.md) §11.

The ordered commitment is BLAKE3-256 over a fixed domain tag followed by the exact wire bytes of every chunk record in ascending index order; the construction is frozen in [`docs/format/OBJECT_CONTAINER_V1.md`](docs/format/OBJECT_CONTAINER_V1.md) §10. The value gains authenticity only inside the AEAD-authenticated final commit.

### Playback verification

A player may consume `VerifiedRange` data without validating a multi-gigabyte object end to end before playback.

### Export and migration verification

Export, backup, migration, and explicit integrity checks require `CompleteVerifiedObject` semantics. A truncated stream must never be reported as a successful complete export merely because its prefix authenticated.

---

## Import, export, and plaintext lifecycle

Plaintext is treated as a short-lived resource that must be bounded, observable in ownership, and removed as soon as possible.

### Atomic import

```text
System picker / file provider
          ↓
Platform input descriptor or seekable stream
          ↓
Rust ImportTransaction
          ├── generate ObjectKey
          ├── derive stream keys
          ├── extract or accept validated metadata
          ├── encrypt chunks to a temporary object
          ├── create encrypted derived assets
          ├── build ordered commitment
          ├── write final commit
          ├── fsync temporary data
          ├── validate final structure
          ├── atomically rename object
          └── commit catalog transaction
```

The catalog record is committed only after the encrypted object has been durably finalized.

Crash recovery rules:

- an open import transaction is resumed from its journaled reserved index or declared dead, per [`docs/format/OBJECT_CONTAINER_V1.md`](docs/format/OBJECT_CONTAINER_V1.md) §14.3 and §14.4;
- finalized orphan objects can be reconciled into the catalog;
- a catalog entry must never point to an uncommitted object;
- source deletion is offered only after successful encrypted commit.

### Export

```text
Encrypted object
      ↓
Rust range or sequential reader
      ↓
AEAD verification
      ↓
Protected destination stream
      ↓
Platform share/save flow
      ↓
Explicit cleanup
```

Where a platform codec or editor requires a plaintext file URL, Chur uses a policy-controlled scratch location:

- app-private directory;
- random filename;
- backup excluded;
- strongest compatible platform file-protection class;
- immediate deletion after use;
- cleanup recovery on the next launch;
- no claim of physical overwrite on flash storage.

### Memory hygiene

- use bounded buffers;
- avoid Kotlin or Swift `String` for key material;
- zeroize Rust secrets and mutable buffers;
- best-effort overwrite temporary JVM/Kotlin buffers;
- keep secrets out of coroutine state longer than necessary;
- never include private inputs in exceptions, crash reports, analytics, or logs;
- clear decoded-image caches and media buffers when locking.

Crypto-erasure means destroying every accessible key envelope for the object. It does not guarantee deletion from remote recipients, old backups, filesystem snapshots, or already-decrypted copies.

---

## Media playback

### Images

Private images use a dedicated Coil `ImageLoader`:

```text
PrivateImageLoader
├── Rust-backed encrypted thumbnail fetcher
├── separate bounded memory cache
├── disk cache disabled or ciphertext-only
├── no public/private cache sharing
└── complete cache invalidation on lock
```

The grid loads encrypted thumbnails rather than decrypting full-resolution originals.

### Video on Android

```text
Media3 / ExoPlayer
      ↓
Custom DataSource
      ↓
VaultObjectReader.readAt(offset, destination)
      ↓
Rust identifies and authenticates affected chunks
      ↓
Requested plaintext range only
```

### Video on iOS

```text
AVPlayer
      ↓
AVAssetResourceLoaderDelegate
      ↓
VaultObjectReader.readAt(offset, destination)
      ↓
Rust identifies and authenticates affected chunks
      ↓
Requested plaintext range only
```

### Audio

Audio uses the same random-access model and may additionally expose a sequential reader for long recordings. Waveforms and cover art are separate encrypted derived assets.

### Codec boundary

Rust owns canonical metadata, identity, encryption, and persistence, but it does not necessarily need to bundle every media codec.

A practical boundary is:

```text
Platform media APIs
    decode/probe/transcode where required
        ↓ transient normalized result
Rust
    validates canonical metadata
    encrypts and persists it
```

This keeps support for HEIF, Live Photos, HDR video, ProRes, platform RAW formats, and system codecs practical without immediately embedding a large FFmpeg/libheif stack into the secure core.

---

## Rust and KMP interop

Interop is split into a small **control plane** and a streaming **data plane**.

### Control plane

Small structured records:

- unlock and lock commands;
- object queries;
- import/export progress;
- migration state;
- integrity results;
- opaque object/session handles;
- structured redacted errors.

### Data plane

Large media must not repeatedly cross FFI as copied `ByteArray` values.

The data plane uses:

- stable C ABI functions;
- opaque native handles;
- platform file descriptors where appropriate;
- direct/native buffers;
- `read_at(offset, destination)`;
- bounded streaming operations;
- explicit close and cancellation.

Conceptual KMP API:

```kotlin
interface VaultEngine {
    suspend fun unlock(request: UnlockRequest): UnlockResult
    suspend fun lock()

    suspend fun queryObjects(
        query: ObjectQuery,
    ): List<ObjectProjection>

    suspend fun openObject(
        objectRef: ObjectRef,
        stream: ObjectStream,
    ): ObjectReader
}

interface ObjectReader : AutoCloseable {
    val plaintextSize: Long

    suspend fun readAt(
        offset: Long,
        destination: PlatformBuffer,
    ): Int

    suspend fun verifyComplete(): IntegrityResult
}
```

Native calls remain synchronous and coarse-grained; coroutines wrap them on an I/O dispatcher.

### Binding strategy

The binding layer is replaceable:

```text
chur-core
    ↓
chur-ffi
    ├── stable C ABI data plane
    └── stable C ABI control plane
        ↓
KMP wrapper
```

The secure core must not depend on UniFFI-, JNI-, Kotlin-, or Swift-specific types, and no binding generator is part of the boundary: both planes cross one hand-written C ABI, frozen by ADR-0016.

### Session invalidation

A UI-level `close()` is not sufficient for security. Locking invalidates all native readers and active operations even if a player or Compose screen fails to release its handle.

The Rust runtime maintains:

```text
VaultRuntime
├── session generation
├── zeroizable SessionSecrets
├── cancellation state
└── active handle registry
```

On lock:

1. increment the generation;
2. cancel active operations;
3. zeroize session secrets in place;
4. invalidate every old handle with `SESSION_EXPIRED`;
5. close the private catalog;
6. destroy private projections and caches;
7. return the UI to the public graph.

---

## Android integration

### Application shell

The Android module remains thin and owns:

- application/activity lifecycle;
- Android Keystore;
- BiometricPrompt;
- Photo Picker and document providers;
- Media3 integration;
- backup rules;
- secure-window policy;
- native library packaging.

### Keystore

A hardware-backed AES key wraps a short root-key envelope. It does not encrypt media.

Preferred behavior:

- use hardware-backed Keystore where available;
- optionally request StrongBox in a “maximum security” mode;
- handle `StrongBoxUnavailableException` and capability differences;
- require per-use or short-window user authentication according to policy;
- retain an independent recovery path for recoverable vaults;
- treat biometric enrollment or lock-screen changes as possible key invalidation events.

The Keystore key can be non-exportable, but the unwrapped `VaultRootSecret` still exists temporarily inside the unlocked Chur process. Chur therefore protects data before unlock and minimizes runtime exposure; it does not claim immunity from a compromised unlocked process.

### Storage

```text
filesDir/
    encrypted catalogs
    encrypted object store

noBackupFilesDir/
    device-bound envelopes
    local device identity
    non-portable state

cacheDir/
    disposable ciphertext or explicitly controlled scratch data
```

Android Auto Backup rules must explicitly exclude device-bound key material and plaintext scratch locations. Ciphertext is portable only when the vault has a password/recovery envelope that can be used on the destination device.

### Privacy UI

Sensitive surfaces use `FLAG_SECURE` where appropriate. This reduces ordinary screenshots and non-secure display capture but is not treated as protection against root, instrumentation, or an external camera.

### Import

Use the system Photo Picker and file providers. Transfer open descriptors or seekable streams to Rust rather than loading large media into Kotlin memory.

---

## iOS integration

### Application shell

The iOS application remains thin and owns:

- SwiftUI/UIKit application and scene lifecycle;
- Keychain and LocalAuthentication;
- PhotosPicker and file import;
- AVFoundation playback integration;
- privacy overlays for app-switcher snapshots;
- file-protection attributes;
- XCFramework/static-library packaging.

### Keychain

A recoverable default can use a `ThisDeviceOnly` Keychain item protected through `SecAccessControl`, while retaining an independent password or recovery envelope.

Authentication policy and accessibility class are separate settings and are chosen separately.

Authentication policy controls which factor opens the item:

- `userPresence` accepts biometry or the device passcode, so under it the device passcode is a working vault credential;
- `biometryCurrentSet` accepts biometry only and invalidates access when the biometric set changes.

Accessibility class controls when the item is readable at all. A passcode-required class gives the strongest local protection and does not by itself restrict which factor authenticates the read.

Policy selection balances lockout risk, backup behavior, and recovery. [`docs/security/KEY_SLOTS.md`](docs/security/KEY_SLOTS.md) §1 owns the choice and names the two product modes; this section is explanatory.

Secure Enclave is not used as a streaming video cipher. It may later protect asymmetric device-identity keys, while symmetric root-key release remains a Keychain access-control operation.

### Data Protection

Private catalogs, wrapped-key blobs, and unavoidable scratch files use the strongest compatible file-protection class. Strict mode favors complete protection and does not promise background plaintext access while the device is locked.

Ciphertext-only background sync can be allowed without unlocking the vault root secret.

### Screenshots and snapshots

There is no general iOS equivalent to Android `FLAG_SECURE` for arbitrary application content. Chur can:

- cover private UI before scene background snapshots;
- hide private navigation state;
- detect active screen capture and apply policy;
- replace the private surface with the public shell when backgrounded or locked.

It cannot honestly guarantee screenshot prevention on every iOS version and device state.

---

## Sync and sharing

Sync and sharing are intentionally deferred until the local storage format, migrations, recovery, and integrity semantics are stable.

### Opaque server model

The server should receive only:

```text
opaque object identifiers
encrypted containers
encrypted catalog operations
wrapped collection keys
opaque revisions
signed device operations
```

It should not receive plaintext:

- filenames;
- MIME types;
- EXIF or GPS;
- album names;
- thumbnails;
- media hashes;
- search terms;
- object keys;
- collection keys;
- vault root secrets.

### Immutable media, mutable metadata

Media containers remain immutable. Favorites, captions, logical albums, tags, and other mutable state are independent encrypted revisions. Metadata changes do not rewrite a multi-gigabyte video.

### Authenticated operation log

AEAD alone does not prevent a malicious or broken server from replaying an older, valid ciphertext. The future sync protocol therefore requires signed, chained device operations:

The signed record's fields, the cleartext set, and the chain hash are defined in [`docs/sync/OPERATION_LOG.md`](docs/sync/OPERATION_LOG.md) §2, §4, and §6, and are not repeated here.

Clients track accepted device heads and detect replay, rollback, and log forks where possible. Server omission remains a separate consistency problem and must be documented honestly.

### Sharing keys

Large media is never encrypted directly with a recipient public key.

```text
ObjectKey
    wrapped by SecurityCollectionKey

SecurityCollectionKey
    wrapped separately for each authorized recipient/device
```

Planned standard suite:

```text
KEM  = DHKEM(X25519, HKDF-SHA256)
KDF  = HKDF-SHA256
AEAD = ChaCha20-Poly1305
```

XChaCha20-Poly1305 remains the local media-container AEAD; it is not substituted into an RFC 9180 suite that does not define it.

Sender identity is provided separately, preferably through Ed25519 signatures over canonical collection grants and sync operations. HPKE Base mode alone does not authenticate the sender or provide replay protection.

### Deduplication

Global deduplication by an unkeyed plaintext hash is out of scope because it reveals content equality. Acceptable options are:

- no deduplication;
- local-only deduplication;
- a user-specific keyed fingerprint under a dedicated derived key.

---

## Threat model

### Chur is designed to mitigate

| Threat | Primary controls |
| --- | --- |
| Lost or stolen locked device | OS data protection, Keystore/Keychain, authenticated root-key release |
| Application sandbox copied from storage | Encrypted catalog, object containers, encrypted metadata and derivatives |
| Single-object key disclosure | Independent random object keys and domain-separated stream keys |
| Chunk corruption or substitution | XChaCha20-Poly1305, canonical AAD, ordered final commitment |
| Object truncation | Authenticated final commit and complete-object verification |
| Password database extraction | Argon2id with random salt and bounded parameters |
| Password change | Rewrap root secret without media re-encryption |
| Collection access revocation | Collection epochs and object-key rewrapping |
| Cloud/server compromise | Client-side encryption and opaque server model |
| Casual observation of the app | Functional public shell, immediate lock, neutral UI surfaces |
| Coercive UI inspection | Independent decoy vault, with explicit limitations |
| Process death during import | Journaled temporary objects, fsync, atomic rename, catalog transaction |

### Chur cannot fully mitigate

| Threat | Limitation |
| --- | --- |
| Compromised OS/kernel | The attacker may inspect plaintext after unlock or intercept platform APIs |
| Runtime instrumentation/root/jailbreak | Open-session memory and calls may be observed or modified |
| Malicious accessibility/keyboard/input method | Passwords and visible content may be captured |
| External camera | Screen content can be photographed |
| All screenshot paths on iOS | The platform does not expose a universal prevention mechanism |
| Physical secure erase on flash | Wear levelling, snapshots, backups, and copy-on-write prevent guarantees |
| Recipient deletion after sharing | An authorized recipient may retain key material or plaintext |
| Undetectable hidden volume | Ciphertext size, storage layout, and backups may reveal additional data |
| Forgotten credentials without recovery | Correct cryptography implies irreversible data loss |

The primary guarantee is data-at-rest confidentiality and integrity under a locked-vault model. Runtime hardening reduces exposure but does not turn a compromised device into a trusted execution environment.

---

## Security invariants

[`docs/security/SECURITY_INVARIANTS.md`](docs/security/SECURITY_INVARIANTS.md) is the registry of the properties that the implementation, migrations, platform adapters, and future protocols must preserve. Each entry has a stable `SEC-` identifier that ADRs, tests, and audit findings cite. This README does not restate them.

---

## Testing and assurance

A cryptographic storage application requires more than ordinary unit tests.

### Rust tests

- known-answer tests for every cryptographic construction;
- deterministic golden vectors for key slots, envelopes, manifests, chunks, commits, catalogs, and grants;
- property-based tests for serialization and state transitions;
- format round trips across supported versions;
- nonce-uniqueness assertions;
- key-rotation and rewrapping tests;
- interrupted import and migration recovery;
- catalog/object reconciliation tests;
- explicit secret-zeroization and redacted-error tests where observable.

### Corruption matrix

Every container parser must be tested against:

- bit flips in every structured field;
- truncated preamble, manifest, chunk, tag, or final commit;
- missing middle and final chunks;
- duplicated or reordered chunks;
- a chunk copied from another object;
- forged lengths and chunk counts;
- invalid stream revisions;
- unknown algorithm suites;
- oversized allocation requests;
- malformed key slots;
- extreme Argon2 parameters;
- corrupted catalog journals and migrations.

### Fuzzing

[`docs/assurance/FUZZING.md`](docs/assurance/FUZZING.md) §2 lists the initial Rust fuzz targets and §1 the properties they establish. This README does not restate the target names.

All parsers must impose hard limits before allocating memory.

### Cross-platform compatibility

The same vector must support:

```text
Encrypt on Android
Decrypt on iOS
Verify with chur-cli
```

and the inverse direction.

### Platform security tests

- biometric enrollment changes;
- device-passcode changes;
- Keystore/Keychain invalidation;
- missing hardware-backed support;
- StrongBox fallback;
- backup and restore to another device;
- process death during unlock/import/playback;
- background/foreground locking races;
- stale native handles after lock;
- secure-window and app-switcher behavior;
- no private state restoration after process death.

### Release gates

Before production sync, sharing, or a public backup protocol:

1. publish the cryptographic specification;
2. publish stable test vectors;
3. document the threat model and invariants;
4. fuzz the container, catalog, migration, and FFI parsers;
5. complete an independent review of the Rust core and protocol;
6. resolve findings and publish a remediation summary;
7. define a vulnerability-reporting process and security policy.

No README wording should imply that a future audit has already occurred.

---

## Roadmap

Chur is developed in security-gated phases. [`ROADMAP.md`](ROADMAP.md) defines the phases, their scope, explicit exclusions, and exit criteria; [`docs/assurance/RELEASE_GATES.md`](docs/assurance/RELEASE_GATES.md) defines the evidence each gate requires. This README does not restate either list.

---

## Design references

Chur is an independent design. The following projects and standards are useful references, not drop-in specifications:

- [Ente](https://github.com/ente-io/ente) — E2EE media architecture, collection/file key hierarchy, sharing, and cross-client cryptography.
- [Cryptomator vault cryptography](https://docs.cryptomator.org/security/vault/) — chunked authenticated encryption and explicit threat-model documentation.
- [age format](https://age-encryption.org/v1) — modern interoperable encrypted export/backup and recipient design.
- [RFC 9106: Argon2](https://www.rfc-editor.org/rfc/rfc9106.html) — password KDF guidance.
- [RFC 9180: HPKE](https://www.rfc-editor.org/rfc/rfc9180.html) — standard hybrid public-key encryption.
- [RFC 8452: AES-GCM-SIV](https://www.rfc-editor.org/rfc/rfc8452.html) — optional nonce-misuse-resistant AEAD reference.
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore) — hardware-backed platform key protection.
- [Android Photo Picker](https://developer.android.com/training/data-storage/shared/photo-picker) — least-privilege media import.
- [Android Media3 customization](https://developer.android.com/media/media3/exoplayer/customization) — custom data sources for encrypted playback.
- [Apple Keychain data protection](https://support.apple.com/guide/security/keychain-data-protection-secb0694df1a/web) — Keychain access and Secure Enclave interaction.
- [Apple Data Protection classes](https://support.apple.com/guide/security/data-protection-classes-secb010e978a/web) — file protection policy.
- [AVAssetResourceLoaderDelegate](https://developer.apple.com/documentation/avfoundation/avassetresourceloaderdelegate) — custom byte-range media loading.
- [UniFFI](https://github.com/mozilla/uniffi-rs) and [Gobley](https://gobley.dev/) — generated interop layers evaluated for the control plane and rejected by ADR-0016.

Licenses of reference projects must be reviewed before reusing code. Architectural similarity does not grant permission to copy implementation.

---

## Security and contribution status

Chur is currently in its design stage. Architectural review, protocol critique, test-vector design, fuzzing strategy, and platform-security analysis are especially valuable.

Report a suspected vulnerability through [`SECURITY.md`](SECURITY.md), never in a public issue, discussion, pull request, or post. GitHub Private Vulnerability Reporting is not yet enabled for this repository; `SECURITY.md` gives the interim private-contact procedure and the report contents.

Do not use this repository as a production vault, and do not assume that an architectural proposal has been implemented or audited.

The expected application ID is:

```text
dev.po4yka.chur
```

---

## License

Chur is licensed under the [BSD 3-Clause License](LICENSE).
