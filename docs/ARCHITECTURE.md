# Chur Architecture

> **Status:** Proposed architecture for implementation  
> **Audience:** application, mobile-platform, Rust, security, and infrastructure contributors  
> **Last updated:** 2026-08-26  
> **Related:** [README](../README.md)

Chur is a local-first private media archive for Android and iOS. Kotlin Multiplatform and Compose Multiplatform own the application experience; a Rust runtime owns the private vault format, key hierarchy, encrypted catalog, media containers, integrity decisions, and migrations.

This document is the normative system-architecture description. The README explains the product and its intended capabilities; this file defines component boundaries, trust boundaries, runtime states, storage rules, cryptographic responsibilities, cross-platform integration, and implementation constraints.

Chur is currently in the architecture and protocol-design stage. Nothing in this document should be interpreted as a completed independent audit or a production security guarantee.

---

## 1. Document conventions

The key words **MUST**, **MUST NOT**, **REQUIRED**, **SHOULD**, **SHOULD NOT**, and **MAY** describe architectural requirements.

Individual statements are classified as **Decision**, **Invariant**, **Proposal**, **Deferred**, or **Non-goal**, defined once in [`README.md`](README.md#statement-classification). The status of this document as a whole is in its header and uses the document-status vocabulary of the same file.

The architecture is intentionally split from the future binary-format specification. This document defines the system model and required properties; byte-exact layouts, constants, encodings, and test vectors must be finalized in dedicated specifications before production data is stored.

---

## 2. Architecture drivers

Chur is driven by the following requirements, in priority order:

1. Protect private media and metadata when the vault is locked and application storage is copied.
2. Preserve integrity under corruption, truncation, chunk substitution, interrupted writes, and failed migrations.
3. Keep the private storage format independent from Kotlin, Compose, Android, iOS, JNI, Objective-C, and Swift APIs.
4. Support multi-gigabyte videos without whole-file decryption or unbounded memory use.
5. Support efficient random access for image decoding, video seeking, and audio playback.
6. Keep password changes and collection-key rotation independent from media re-encryption.
7. Isolate public-shell data from private-vault data.
8. Support an independent decoy vault without representing it as cryptographically undetectable storage.
9. Remain local-first until the object format, catalog model, migrations, and recovery paths are stable.
10. Permit future ciphertext-only backup, synchronization, and collection sharing without redesigning local media encryption.
11. Make security-critical behavior testable from a Rust CLI without Android or iOS UI code.
12. Fail closed on unsupported formats, algorithm suites, invalid resource parameters, and stale sessions.

---

## 3. Core architectural decisions

The decision records are the files in [`adr/`](adr/) and the specifications they freeze. This section explains how those decisions fit together and states the architectural rules that no ADR carries. It is not a decision register: it assigns no identifiers and no statuses of its own, and where it disagrees with an ADR the [authority hierarchy](README.md#authority-hierarchy) gives the ADR precedence.

Recorded as ADRs:

- Rust is the canonical owner of all private-vault formats and cryptographic state transitions — [`ADR-0001`](adr/0001-rust-owns-private-vault.md).
- Large media uses independent XChaCha20-Poly1305 chunks and an authenticated final commit — [`ADR-0002`](adr/0002-independent-aead-chunks.md), with the public container layout frozen by [`ADR-0008`](adr/0008-freeze-object-container-v1-layout.md).
- The immutable encrypted media container is separate from the mutable object-key envelope — [`ADR-0003`](adr/0003-separate-object-key-envelope.md).
- A Rust-owned SQLCipher catalog is the preferred private-catalog implementation — [`ADR-0004`](adr/0004-rust-owned-private-catalog.md), which remains Proposed until the build, linkage, WAL, migration, performance, and backup validation required by [`format/CATALOG_SCHEMA_V1.md`](format/CATALOG_SCHEMA_V1.md) §15 completes.
- Real and decoy vaults have independent roots, catalogs, object namespaces, caches, and sessions — [`ADR-0005`](adr/0005-real-and-decoy-vault-isolation.md).
- FFI is split into a structured control plane and a bounded streaming data plane — [`ADR-0006`](adr/0006-control-and-data-plane-ffi.md). Both planes use the hand-written C ABI frozen by [`ADR-0016`](adr/0016-freeze-the-v1-c-abi.md); v1 has no generated binding layer.
- The initial product is a local recoverable vault without cloud sharing — [`ADR-0007`](adr/0007-local-first-before-sync.md).
- A portable backup complements, and never replaces, the Chur vault format; its framing and its single optional `age` layer are frozen by [`ADR-0018`](adr/0018-freeze-backup-package-framing.md).

Architectural rules this document owns, because no ADR states them:

- KMP owns use cases, UDF state, navigation, and platform orchestration, but not private persistence formats.
- Android Keystore and iOS Keychain protect release or unwrapping of a short vault root secret; they do not encrypt media streams. [`security/KEY_SLOTS.md`](security/KEY_SLOTS.md) owns the slot behavior.
- Media uses envelope encryption with random vault, security-collection, and per-object keys, derived as [`security/KEY_HIERARCHY.md`](security/KEY_HIERARCHY.md) specifies.
- Public-shell persistence uses Room/DataStore; private persistence is Rust-owned.
- Locking invalidates native handles independently of UI cleanup, under [`security/PLAINTEXT_LIFECYCLE.md`](security/PLAINTEXT_LIFECYCLE.md).

Rejected alternatives include:

- deriving media keys directly from a password;
- using one global media key for every object;
- encrypting an entire multi-gigabyte file with one AEAD invocation;
- treating a UI filter as a decoy vault;
- storing private metadata in Room, DataStore, `SavedStateHandle`, bundles, or user defaults;
- passing complete media through FFI as `ByteArray` or `NSData`;
- global deduplication through an unkeyed plaintext hash;
- trusting a server to select cryptographic algorithms or KDF resource parameters.

---

## 4. System context

```text
                         ┌───────────────────────┐
                         │        User           │
                         └───────────┬───────────┘
                                     │
                  public utility UI  │  authenticated vault UI
                                     ▼
┌─────────────────────────────────────────────────────────────────────┐
│                             Chur App                                │
│                                                                     │
│  Public shell ─ Session gate ─ Private vault UI ─ Platform adapters │
│                                      │                              │
│                                      ▼                              │
│                              Rust Vault Runtime                     │
└──────────┬──────────────────┬──────────────────────┬────────────────┘
           │                  │                      │
           ▼                  ▼                      ▼
  Android/iOS media    Keystore / Keychain    Encrypted app storage
  pickers and players  and user presence       catalog + objects
                                                      │
                                                      ▼
                                            Optional opaque server
                                            backup / sync / sharing
```

External actors and systems:

- **User** — creates a public workspace, unlocks a real or decoy vault, imports and consumes media.
- **Android/iOS operating system** — supplies sandboxing, file protection, media pickers, decoders, players, lifecycle, and authentication UX.
- **Platform key service** — controls use or release of device-bound key material.
- **Application filesystem** — stores public data, encrypted catalogs, encrypted objects, journals, and temporary ciphertext.
- **Optional remote server** — future untrusted storage and synchronization relay; it must not require plaintext media, private metadata, or user root keys.
- **Recovery medium** — optional high-entropy recovery secret or encrypted portable backup controlled by the user.

---

## 5. Trust boundaries

### 5.1 Rust vault boundary

The Rust runtime is the canonical private-data boundary. It MUST own:

- vault and object identifiers used by the private format;
- key-slot parsing and validation;
- root, collection, object, stream, and metadata keys;
- key wrapping and unwrapping;
- Argon2id execution and parameter validation;
- private-catalog schema and migrations;
- encrypted-object container parsing and serialization;
- chunk nonce construction and AAD construction;
- object completeness and corruption decisions;
- import, export, repair, and migration transactions;
- sync-operation canonicalization and encryption when sync exists.

KMP MUST NOT reimplement these rules.

### 5.2 KMP application boundary

KMP is trusted to orchestrate the user experience, but it is not a second canonical storage implementation. It MAY hold:

- opaque session and object handles;
- short-lived screen projections returned by Rust;
- progress and lifecycle state;
- public-shell models;
- non-secret settings;
- platform capability information.

It MUST NOT persist private filenames, album names, EXIF, coordinates, object keys, collection keys, root keys, decrypted manifests, search queries, or private navigation state outside the active vault session.

### 5.3 Platform key boundary

Android Keystore and iOS Keychain protect device-bound key use. They do not create a trusted runtime after unlock.

After successful release or unwrapping, `VaultRootSecret` exists in the Chur process and is vulnerable to a fully compromised unlocked OS, runtime instrumentation, or malicious memory inspection. The platform key service primarily protects data at rest and gates access to the root secret.

### 5.4 Server boundary

The future server is untrusted for confidentiality and content integrity. It MAY store and relay:

- opaque object identifiers;
- encrypted object containers;
- wrapped keys;
- encrypted catalog operations;
- signed device-log entries;
- ciphertext revisions and upload state.

It MUST NOT be required to process plaintext filenames, metadata, thumbnails, album titles, media types, or root secrets.

### 5.5 Public-shell boundary

The public shell is a privacy surface, not the cryptographic boundary. Compromise of its Room database MUST NOT expose private-vault metadata or keys.

---

## 6. Data classification

| Class | Examples | Allowed persistence |
| --- | --- | --- |
| Public | functional notes/calculator data, theme, locale | Room/DataStore, platform backup according to public policy |
| Opaque sensitive | random vault directory IDs, ciphertext sizes, opaque operation IDs | application-private storage; never treated as harmless telemetry |
| Private plaintext | filenames, EXIF, GPS, album names, tags, decoded thumbnails | only inside an unlocked session; encrypted at rest |
| Secret | root, collection, object, stream, recovery, identity private keys | Rust secret types or platform key services only |
| Device-bound secret state | Keystore alias, Keychain item reference, local device key envelope | backup excluded unless explicitly portable |
| Portable encrypted state | password/recovery key slot, encrypted catalog snapshot, object containers | backup allowed under explicit recovery policy |

Data classification is independent of whether a value appears harmless in isolation. Object counts, dimensions, timestamps, and access patterns may leak user activity and therefore require privacy review.

---

## 7. Top-level runtime architecture

```text
┌─────────────────────────────────────────────────────────────┐
│ Android application / iOS application shell                │
│ lifecycle, permissions, key services, pickers, players     │
├─────────────────────────────────────────────────────────────┤
│ Compose Multiplatform UI                                   │
│ public shell, unlock, library, viewer, import, settings     │
├─────────────────────────────────────────────────────────────┤
│ KMP application layer                                      │
│ UDF, ViewModels, navigation, use cases, coordinators        │
├─────────────────────────────────────────────────────────────┤
│ KMP Rust adapter                                           │
│ expect/actual, errors, opaque handles, buffers              │
├─────────────────────────────────────────────────────────────┤
│ Rust Vault Runtime                                         │
│ sessions, keys, catalog, objects, media, integrity          │
├─────────────────────────────────────────────────────────────┤
│ Encrypted storage                                          │
│ key slots, catalog, object containers, journals             │
└─────────────────────────────────────────────────────────────┘
```

The application is composed from three dependency graphs:

```text
ApplicationGraph
├── PublicGraph             long-lived; Koin classic DSL
├── PlatformGraph           long-lived; expect/actual services
└── SecureGraph             session-scoped; manual construction
```

The secure graph MUST be created only after Rust has opened an authenticated vault session and MUST be destroyed on every lock transition.

---

## 8. Runtime states

The root runtime follows a closed state machine.

```text
             ┌──────────────┐
             │ Cold / Start │
             └──────┬───────┘
                    ▼
             ┌──────────────┐
             │ PublicLocked │◄──────────────────────┐
             └──────┬───────┘                       │
                    │ unlock                        │ lock / timeout /
                    ▼                               │ background policy /
             ┌──────────────┐                       │ panic lock
             │  Unlocking   │                       │
             └───┬──────┬───┘                       │
        failure  │      │ success                   │
                 │      ▼                           │
                 │  ┌──────────────────┐             │
                 └─►│ UnlockedSession  │─────────────┘
                    │ opaque identity  │
                    └───────┬──────────┘
                            │ migration required
                            ▼
                    ┌──────────────────┐
                    │   Migrating      │
                    └──────────────────┘
```

`UnlockedSession` does not expose `isReal` or `isDecoy` to ordinary feature code. The selected cryptographic identity is represented by an opaque `VaultSessionHandle` and session-scoped policy.

### 8.1 Required transitions

- A failed unlock MUST return to `PublicLocked` without exposing whether the credential matched no slot, a decoy slot, or a damaged slot.
- A background transition MUST apply the configured lock policy before private UI can be snapshotted.
- `Locking` MUST stop media, invalidate Rust handles, clear private navigation, close the private catalog, and clear session caches.
- Process restoration MUST start in `PublicLocked`; private back stacks and viewer state MUST NOT be restored.
- A migration failure MUST fail closed and retain a recoverable encrypted checkpoint where possible.

---

## 9. Repository structure

The tree below is the repository, not a plan. Phase 1 built fewer Kotlin modules
than the original plan listed, and the difference is deliberate: a module that
holds one screen and no rule of its own is a directory rather than a boundary.
The boundaries that exist are the ones that enforce something — the public shell
cannot see the vault, the design system cannot see the boundary, and the FFI
adapter is the only module that names a handle.

```text
Chur/
├── apps/
│   ├── androidApp/          composition root, JNI packaging, pickers, exports
│   └── iosApp/              the Xcode project's specification, README.md
│
├── shared/
│   ├── app/                 design system, screens, and the shared controller
│   ├── core-model/          the status vocabulary and the vector index
│   ├── core-ffi/            the expect/actual C ABI adapter and its records
│   ├── core-vault/          the session state machine and the lock policy
│   ├── core-platform-keys/  the Keystore and Keychain slot prototypes
│   ├── feature-import/      the platform half of the media pipeline
│   └── feature-notes/       the public shell's own logic
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
│       ├── chur-jni/
│       └── chur-cli/
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

Module boundaries MUST prevent feature modules from importing platform-native key implementations or Rust FFI symbols directly. Features depend on KMP interfaces; only the composition root and adapter modules bind implementations.

---

## 10. Kotlin Multiplatform application architecture

### 10.1 Stack

| Area | Planned choice |
| --- | --- |
| Language | Kotlin 2.4.10 / K2 |
| UI | Compose Multiplatform 1.11.1 |
| Navigation | Navigation 3 Multiplatform |
| Architecture | AndroidX ViewModel with UDF/MVVM |
| State | StateFlow / Flow |
| Concurrency | Coroutines 1.11 |
| Dependency injection | Koin 4.2.2 classic DSL plus manual secure graph |
| Network | Ktor Client 3.5.2 |
| Serialization | kotlinx.serialization 1.11 |
| Public database | Room 3.0.1 KMP |
| Settings | DataStore KMP |
| Images | Coil 3.5 |
| Time | `kotlin.time` and kotlinx-datetime 0.8 |
| Logging | Kermit with privacy-safe facade |
| Build | version catalog, convention plugins, KSP |
| Apple interop | Objective-C framework interop initially; selective Swift Export |

Versions in this table are implementation targets, not protocol identifiers. Dependency upgrades MUST NOT silently change vault bytes or cryptographic behavior.

### 10.2 UDF contract

Each feature uses a unidirectional contract:

```kotlin
interface FeatureContract<State, Intent, Effect> {
    val state: StateFlow<State>
    val effects: Flow<Effect>
    fun accept(intent: Intent)
}
```

Private feature state MUST contain only the projection needed by the visible screen. It SHOULD prefer opaque `ObjectRef` values over filesystem paths or stable identifiers.

One-shot effects include:

- platform picker requests;
- permission requests;
- share/save presentation;
- transient error presentation;
- navigation commands that cannot be represented as durable state.

Private effects MUST NOT be replayed after process death.

### 10.3 Navigation

Navigation is split into independent graphs:

```text
RootNavigation
├── PublicShellGraph
│   ├── Notes
│   ├── Journal
│   ├── Calculator
│   └── PublicSettings
│
├── UnlockGraph
│   ├── CredentialEntry
│   ├── PlatformAuthentication
│   └── Recovery
│
└── VaultGraph
    ├── Library
    ├── Albums
    ├── Viewer
    ├── Import
    ├── Audio
    └── VaultSettings
```

Private navigation keys MUST NOT contain:

- original filenames;
- album or tag names;
- filesystem paths;
- search queries;
- EXIF or GPS;
- stable remote IDs;
- `isDecoy` flags.

A suitable key contains a session-scoped opaque reference:

```kotlin
@Serializable
data class ViewerKey(
    val objectHandle: String,
) : NavKey
```

Locking destroys the complete `VaultGraph` back stack.

### 10.4 Dependency injection

Koin classic DSL MAY manage long-lived application and public-feature dependencies. The secure graph MUST use explicit construction:

```kotlin
class SecureGraph private constructor(
    val session: VaultSession,
    val mediaSourceFactory: PrivateMediaSourceFactory,
    val privateImageLoader: ImageLoader,
    val repositories: PrivateRepositories,
) : AutoCloseable {
    override fun close() {
        // stop clients, clear caches, close session facade
    }
}
```

This explicit graph makes session lifetime auditable and prevents accidental process-wide singletons retaining private state.

### 10.5 Public persistence

Room stores only genuinely public-shell data and non-secret orchestration state:

```text
public.db
├── notes
├── calculator_history
├── journal_entries
├── onboarding_state
└── opaque_background_jobs
```

DataStore stores only settings whose disclosure is acceptable, such as locale, theme, public-shell selection, and non-secret UI preferences.

Neither Room nor DataStore may store private-vault item counts, names, timestamps, identifiers, key slots, unlock history, or secret triggers.

---

## 11. Platform abstraction layer

KMP declares narrow interfaces:

```kotlin
interface PlatformRootKeyProtector {
    suspend fun seal(
        vaultReference: VaultReference,
        rootSecret: SecureBytes,
        policy: PlatformKeyPolicy,
    ): PlatformEnvelope

    suspend fun unseal(
        envelope: PlatformEnvelope,
        reason: AuthenticationReason,
    ): SecureBytes

    suspend fun delete(envelope: PlatformEnvelope)
}

interface PlatformMediaPicker {
    suspend fun pickMedia(request: MediaPickRequest): List<PickedMedia>
}

interface PlatformFileAccess {
    suspend fun openForRead(reference: PlatformFileReference): ReadHandle
    suspend fun openForWrite(reference: PlatformFileReference): WriteHandle
}

interface PlatformPrivacyController {
    fun setPrivateContentVisible(visible: Boolean)
    fun setScreenCapturePolicy(policy: ScreenCapturePolicy)
}
```

Platform implementations MUST NOT define vault-format rules. They supply capabilities, handles, and policy enforcement to the KMP application and Rust adapter.

---

## 12. Rust crate responsibilities

### `chur-core`

Owns:

- runtime state machine;
- vault discovery and opening;
- session generation and invalidation;
- orchestration of catalog, object store, and key slots;
- import/export/migration transactions;
- policy checks and stable domain errors.

It MUST NOT depend on Kotlin, JNI, Swift, Objective-C, Compose, or platform UI types.

### `chur-crypto`

Owns:

- XChaCha20-Poly1305 operations;
- Argon2id key derivation;
- HKDF-SHA-256 domain separation;
- random key and nonce-prefix generation;
- key wrapping primitives;
- secret types and zeroization;
- future HPKE and signature primitives behind explicit protocol versions.

The crate SHOULD expose purpose-specific operations rather than a broad generic cryptography toolbox.

### `chur-format`

Owns:

- fixed preamble parsing;
- manifest and final-commit encoding;
- object-key envelope encoding;
- version and suite validation;
- allocation limits;
- canonical byte representations;
- migration readers for supported historical formats.

### `chur-catalog`

Owns:

- private schema;
- catalog connection lifecycle;
- transactions and indexes;
- collection, album, tag, object, derived-asset, and tombstone records;
- materialized sync state;
- catalog migrations and consistency checks.

### `chur-media`

Owns:

- streaming encryption import;
- random-access authenticated reads;
- sequential export;
- chunk cache policy;
- final-completeness verification;
- bounded buffer management;
- derived-asset object handling.

### `chur-sync-protocol`

Initially contains versioned types and test scaffolding only. Later it owns:

- canonical encrypted operations;
- signed per-device logs;
- collection grants;
- replay and rollback checks;
- protocol version negotiation.

### `chur-ffi`

Owns:

- the stable C ABI frozen by [`ADR-0016`](adr/0016-freeze-the-v1-c-abi.md);
- opaque handle table;
- input length validation;
- panic containment;
- stable redacted error codes;
- direct-buffer and descriptor bridges.

It MUST contain minimal business logic.

### `chur-jni`

The Android JNI adapter of [`interop/FFI_CONTRACT.md`](interop/FFI_CONTRACT.md) §14, decided by [`ADR-0040`](adr/0040-add-a-rust-jni-adapter-crate.md). It builds a separate shared library because §6.2 forbids a `Java_*` symbol in the Chur artifact, and it holds no logic: every function reads the JVM arguments, calls one `chur_*` export, and writes the result back.

iOS does not load it. Kotlin/Native reaches `chur.h` through cinterop directly.

### `chur-cli`

Supports:

- creation and verification of golden vectors;
- integrity inspection;
- migration dry runs;
- recovery and repair experiments;
- fuzz corpus generation;
- deterministic compatibility tests;
- offline backup verification.

The CLI MUST use the same Rust core as mobile clients.

---

## 13. Rust runtime model

The runtime uses opaque handles rather than exposing pointers across FFI.

```text
VaultRuntime
├── RuntimeId
├── SessionGeneration
├── LockedVaultRegistry
├── ActiveSession? 
│   ├── VaultIdentity
│   ├── Zeroizing<SessionSecrets>
│   ├── CatalogConnection
│   ├── HandleRegistry
│   ├── CancellationToken
│   └── SessionCaches
└── ResourceLimits
```

Every session-bound handle contains or is associated with:

- runtime ID;
- session generation;
- object/operation kind;
- lifecycle state.

After lock, an old handle MUST return `SESSION_EXPIRED`, even if Kotlin, Swift, Media3, or AVFoundation failed to close it.

### 13.1 Concurrency model

Initial Rust APIs SHOULD remain synchronous. KMP dispatches blocking native work onto bounded I/O dispatchers.

The core SHOULD avoid embedding a permanent async runtime across FFI until required. This reduces lifecycle complexity and foreign-thread callbacks.

Per-vault concurrency is owned by [`interop/FFI_CONTRACT.md`](interop/FFI_CONTRACT.md) §8 and §8.1, which fix one process per vault, one runtime per process, one writer mutex per session, reads serialized per handle, at most one unlock in flight, and a process-wide Argon2id semaphore of 1. This document MUST NOT restate them.

Lock establishes a barrier that cancels new work, waits for or aborts active operations according to policy, zeroizes secrets, and closes the catalog.

No operation may outlive the session whose generation authorized it.

---

## 14. Private storage architecture

The private store consists of three independent concerns:

```text
PrivateVaultStorage
├── Key-slot and vault descriptor store
├── Rust-owned private catalog
└── Immutable encrypted object store
```

### 14.1 Vault descriptors

A descriptor contains only information required before unlock:

- descriptor format version;
- random opaque vault identifier;
- supported key slots;
- KDF and wrapping suite identifiers;
- bounded KDF parameters and salts;
- platform envelope references;
- minimum reader/writer versions;
- optional recovery metadata that reveals no secret.

Descriptors MUST NOT contain private media metadata or distinguish real and decoy vaults through human-readable names.

### 14.2 Private catalog

The current preferred design is a Rust-owned SQLCipher database. This remains subject to prototype validation for:

- Android and iOS build integration;
- binary-size impact;
- encryption and WAL configuration;
- migration reliability;
- memory behavior;
- license and dependency policy;
- backup and corruption recovery.

The catalog is opened only after `CatalogDatabaseKey` is derived from an authenticated `VaultRootSecret`.

Suggested logical schema:

```text
vault_meta
security_collections
collection_key_envelopes
objects
object_key_envelopes
media_streams
derived_assets
logical_albums
album_memberships
tags
object_tags
private_settings
tombstones
migration_state
sync_device_heads        later
sync_operations          later
collection_grants        later
```

SQLCipher provides database-at-rest encryption but does not replace object-level envelope encryption. `ObjectKey` values remain wrapped under security-collection keys even inside the encrypted catalog.

The v1 design SHOULD avoid additional field-level encryption unless it addresses a distinct threat. Extra field encryption reduces queryability and multiplies nonce, migration, and index complexity.

### 14.3 Immutable object store

Large media is stored outside the catalog as immutable encrypted containers. Object filenames are random and unrelated to plaintext names or unkeyed content hashes.

```text
objects/
├── 2f/
│   └── 2f0f...opaque-object-id.chur
├── a1/
│   └── a18b...opaque-object-id.chur
└── f9/
    └── f9cc...opaque-object-id.chur
```

The catalog maps opaque object IDs to:

- key envelopes;
- object state;
- encrypted/private metadata;
- collection membership;
- derived assets;
- integrity status;
- revisions and tombstones.

### 14.4 Proposed physical layout

Actual platform roots differ, but the internal shape is consistent:

```text
chur/
├── registry/
│   └── opaque vault descriptors and key slots
├── vaults/
│   ├── <random-vault-dir-A>/
│   │   ├── catalog.db
│   │   ├── catalog.db-wal
│   │   ├── catalog.db-shm
│   │   ├── objects/
│   │   ├── incoming/
│   │   ├── quarantine/
│   │   └── sync-staging/          bounded opaque records fetched while locked
│   └── <random-vault-dir-B>/
│       └── ...
└── public/
    └── public Room database and public files
```

The `registry/` directory is not illustrative: its entry naming, its cap of two entries, and the order in which candidates are enumerated before unlock are normative in [`format/VAULT_DESCRIPTOR_V1.md`](format/VAULT_DESCRIPTOR_V1.md) §11 ([`ADR-0030`](adr/0030-freeze-the-vault-registry-and-discovery.md)). The per-attempt password-derivation count is fixed at two in [`security/KEY_SLOTS.md`](security/KEY_SLOTS.md) §8 ([`ADR-0026`](adr/0026-argon2id-memory-floor-and-candidate-set.md)) and does not follow the entry count.

Directories MUST NOT be named `real`, `private`, `decoy`, `secret`, or `vault` in a way exposed outside the app sandbox. The remaining illustrative names above describe responsibilities, not necessarily final on-disk labels.

---

## 15. Cryptographic architecture

### 15.1 Suite v1

The proposed local suite is:

| Purpose | Primitive |
| --- | --- |
| Media and structured-record AEAD | XChaCha20-Poly1305 |
| Password KDF | Argon2id |
| Key derivation and domain separation | HKDF-SHA-256 |
| Randomness | OS CSPRNG through Rust `getrandom` |
| Ordered object commitment | BLAKE3, authenticated inside the final commit |
| Secret cleanup | zeroizing secret wrappers and bounded mutable buffers |
| Future sharing | RFC 9180 HPKE with X25519/HKDF-SHA-256/ChaCha20-Poly1305 |
| Future identity | Ed25519 signatures |

Production v1 MUST support exactly the approved mandatory local suite. Format fields permit future suites, but runtime policy MUST reject unapproved algorithms and prevent downgrade selection.

### 15.2 Key hierarchy

```text
VaultRootSecret                         random 256-bit secret
    │
    └─ HKDF root domains, registered in security/KEY_HIERARCHY.md §3
           ├─ CatalogDatabaseKey
           ├─ CollectionEnvelopeKey
           ├─ IdentifierKey
           ├─ SearchKey
           ├─ PrivateSettingsKey
           └─ IdentityWrapKey

SecurityCollectionKey[epoch]           random 256-bit key
    │
    └─ wraps ObjectKey                 random 256-bit key per object
           │
           └─ HKDF object domains
                  ├─ ManifestKey
                  ├─ ContentKey
                  ├─ MetadataKey
                  ├─ PreviewKey
                  ├─ ThumbnailKey
                  └─ FinalCommitKey
```

Security-collection and object keys are random rather than derived. This permits independent rotation, sharing, rewrapping, and deletion.

The label for each derivation, its input key, and its output length are registered in [`security/KEY_HIERARCHY.md`](security/KEY_HIERARCHY.md) §3. Salt use, context encoding, and the canonical vectors that fix them are locked by the format specifications.

### 15.3 Album versus security collection

A logical album is a UI grouping. A security collection is a key domain and sharing boundary.

```text
SecurityCollection
├── Paris album
├── Favorites
├── Documents album
└── ungrouped objects
```

Adding an item to a logical album MUST NOT automatically rotate or duplicate object cryptography. New security collections are created for distinct access-control or sharing domains, not ordinary UI organization.

### 15.4 Object keys

Every media original and every independently stored derived asset receives a random object key. Derived assets MAY either:

- use independent object keys and envelopes; or
- derive strictly domain-separated keys from a parent object key when lifecycle and deletion semantics are identical.

The preferred initial implementation uses independently addressable encrypted derived objects, because previews can then be regenerated, replaced, deleted, and synchronized without mutating an original media container.

---

## 16. Key slots and recovery

`VaultRootSecret` is wrapped through one or more versioned key slots.

```text
VaultKeySlots
├── PasswordSlot
├── AndroidKeystoreSlot
├── AppleKeychainSlot
├── RecoverySlot
└── PeerDeviceSlot       future
```

Every slot has:

```text
slot_id
slot_type
slot_version
kdf_or_platform_suite
wrap_suite
validated parameters
nonce or platform envelope
wrapped root secret
```

### 16.1 Password slot

```text
password input
    ↓ canonical password-byte procedure
Argon2id(password, random salt, bounded parameters)
    ↓
PasswordKEK
    ↓
XChaCha20-Poly1305 wrap(VaultRootSecret)
```

Requirements:

- the password MUST NOT directly encrypt media;
- salts are random and unique per slot;
- Argon2 memory, iterations, parallelism, password length, salt length, and output length are validated before allocation or work;
- both minimum and maximum resource bounds are enforced;
- parameters are stored with the slot and versioned;
- successful unlock with obsolete parameters MAY trigger background rewrap under stronger parameters;
- error behavior MUST NOT reveal which part of the slot failed;
- password bytes MUST follow one cross-platform procedure and golden vectors.

The canonical password-byte procedure is defined in [`security/PASSWORD_PROFILE.md`](security/PASSWORD_PROFILE.md): the exact Unicode scalar sequence entered by the user, encoded as strict UTF-8, with no normalization, no trimming, and no case folding. Implementations MUST NOT apply NFC, NFKC, NFD, or NFKD, because normalization changes the Argon2id input bytes for an unchanged password and surfaces as `AUTHENTICATION_FAILED`, indistinguishable from a wrong password. A later normalization decision requires a new password-encoding profile identifier and MUST NOT reinterpret existing slots.

### 16.2 Android platform slot

Android uses a non-exportable Keystore wrapping key where supported:

```text
Keystore AES key
    + user-authentication policy
    ↓
wrap / unwrap VaultRootSecret
```

StrongBox MAY be offered as a stricter optional mode with fallback to a TEE-backed key. Permanent invalidation must be recoverable through a password or recovery slot.

### 16.3 Apple platform slot

iOS uses Keychain access control to gate short secret material. The implementation MAY:

- store a device KEK protected by Keychain ACL and use it to wrap the root secret; or
- store the root secret directly as a protected Keychain item.

The final choice must preserve a uniform Rust slot model and a reliable recovery path. `userPresence` is the practical default; `biometryCurrentSet` is an optional stricter policy with explicit invalidation consequences.

### 16.4 Recovery slot

A recovery secret is random high-entropy key material, not a human-selected secondary password.

It MAY be represented as a mnemonic or QR code, but the canonical binary secret and encoding rules are fixed by the protocol.

### 16.5 Device-bound and recoverable modes

- **Device-bound mode:** only a platform slot exists; loss or invalidation can make the vault unrecoverable.
- **Recoverable mode:** a platform slot plus password and/or recovery slot exists. This is the intended consumer default.
- **Synced mode:** peer-device envelopes and device identities are added without replacing local slots.

---

## 17. Real and decoy vault architecture

Real and decoy vaults are separate cryptographic identities.

```text
Credential A
    ↓
VaultRootSecret A
    ↓
Catalog A + object namespace A + caches A

Credential B
    ↓
VaultRootSecret B
    ↓
Catalog B + object namespace B + caches B
```

They MUST NOT share:

- root or collection keys;
- object-key envelopes;
- catalog databases;
- object namespaces;
- thumbnails or previews;
- search indexes;
- native sessions;
- navigation history;
- media caches;
- integrity or sync state;
- platform key aliases where separation is required.

The KMP feature layer receives an opaque session and presentation policy, not a durable `isDecoy` flag.

The implementation MAY reveal to local forensic analysis that multiple encrypted datasets exist. Chur therefore describes this feature as **Decoy Vault** or **coercion-resistant UX**, not as an undetectable hidden volume or guaranteed plausible deniability.

---

## 18. Encrypted object model

A user-visible media item is a logical aggregate:

```text
MediaObject
├── immutable encrypted original
├── revisioned encrypted metadata
├── encrypted grid thumbnail
├── encrypted screen preview
├── encrypted video poster frame
├── encrypted audio waveform
└── future encrypted OCR / embeddings
```

The original container is immutable after commit. Metadata and derived assets are independently revisioned so that favorites, captions, tags, thumbnails, and semantic indexes do not require rewriting a multi-gigabyte original.

Every derived asset MUST be bound to:

- parent object ID;
- parent content revision;
- asset kind;
- asset revision.

This prevents stale or cross-object previews from being accepted as current derivatives.

---

## 19. Object-key envelope

The object key envelope is stored separately from the immutable object container.

```text
ObjectKeyEnvelopeV1
├── envelope_version
├── object_id
├── security_collection_id
├── collection_epoch
├── wrap_suite_id
├── wrap_nonce
└── wrapped_object_key
```

Opening an object:

```text
CollectionKey[epoch]
    ↓ unwrap
ObjectKey
    ↓ HKDF
ManifestKey / ContentKey / FinalCommitKey / other purpose keys
```

Moving an object between security collections rewraps `ObjectKey`; it does not rewrite media ciphertext.

Portable backup packages must include the required object-key envelopes alongside containers and encrypted catalog state.

---

## 20. Encrypted object container v1

The record sequence is:

```text
ChurObjectV1
├── PublicPreambleV1
├── EncryptedManifestRecordV1
├── ChunkRecordV1[0..N-1]
└── FinalCommitRecordV1
```

Byte offsets, field widths, v1 constants, and record framing are frozen in [`format/OBJECT_CONTAINER_V1.md`](format/OBJECT_CONTAINER_V1.md) §3, §5, §8, and §11. That specification governs container bytes, and this section MUST NOT restate its fields.

The preamble carries no object identifier; object and stream identifiers live in the encrypted manifest and the final commit. The preamble exposes only values required to parse and locate encrypted records. It MUST NOT expose plaintext filename, MIME type, dimensions, duration, EXIF, capture time, GPS, album membership, or human-readable vault identity.

### 20.1 Serialization requirements

The byte-level specification MUST define:

- fixed endianness;
- exact integer widths;
- exact tag and nonce lengths;
- canonical field ordering;
- maximum encoded lengths;
- unknown-field behavior;
- reader and writer compatibility rules;
- authentication coverage;
- deterministic golden vectors.

Default `serde`, `bincode`, Kotlin serialization, JSON, or unspecified CBOR encoding MUST NOT define persistent protocol bytes.

A fixed preamble plus a deterministic bounded structured encoding for encrypted records is required. The chosen encoding is the custom binary profile of [`format/CANONICAL_ENCODING_V1.md`](format/CANONICAL_ENCODING_V1.md), recorded by [`adr/0010-define-canonical-tuple-and-freeze-hkdf-salt.md`](adr/0010-define-canonical-tuple-and-freeze-hkdf-salt.md) and [`adr/0013-allocate-v1-format-constants.md`](adr/0013-allocate-v1-format-constants.md). Deterministic CBOR was considered and not chosen.

### 20.2 Chunk nonce

For XChaCha20-Poly1305:

```text
nonce = random_stream_prefix_128bit || chunk_index_u64_be
```

A fresh random stream prefix is generated for every stream revision. Rewriting metadata or a preview under the same object key MUST NOT reuse a previous prefix.

The architecture invariant is uniqueness of every `(key, nonce)` pair. The implementation MUST include tests and debug assertions that make accidental reuse detectable during development.

### 20.3 Chunk AAD

Canonical AAD binds a chunk to its complete context. The bound items are frozen in [`format/OBJECT_CONTAINER_V1.md`](format/OBJECT_CONTAINER_V1.md) §9 and MUST NOT be restated here; the tuple encoding is in [`CRYPTOGRAPHY.md`](CRYPTOGRAPHY.md) §35. The binding includes the manifest commitment, so a chunk cannot be substituted against a different manifest, as SEC-014 in [`security/SECURITY_INVARIANTS.md`](security/SECURITY_INVARIANTS.md) requires.

Total length and total chunk count are not required in each chunk AAD, because some import sources do not provide a trustworthy length before streaming begins. Completeness is authenticated by the final commit.

### 20.4 Chunk sizes

Initial benchmark candidates:

- 256 KiB for photos and small objects;
- 1 MiB for video and large audio;
- smaller independent objects for thumbnails and waveforms.

Chunk size is stored in the encrypted manifest and is not a cryptographic constant. Final values require benchmarks for seek latency, throughput, FFI overhead, memory pressure, battery use, and resumable-transfer granularity.

---

## 21. Integrity model

Chur distinguishes range authenticity from object completeness.

```text
VerifiedRange
    all returned chunks passed AEAD authentication

CompleteVerifiedObject
    manifest, every expected chunk, total lengths,
    ordered commitment, and final commit are valid
```

A video player MAY consume a `VerifiedRange` without verifying a two-hour file from beginning to end. Export, backup, repair, and migration operations use `CompleteVerifiedObject` according to policy.

### 21.1 Final commit

The encrypted final commit authenticates the object and stream identity, the manifest commitment, the expected chunk count, the exact total plaintext length, the final chunk length, and the ordered commitment over canonical ciphertext records. Its record framing and sealed contents are defined in [`format/OBJECT_CONTAINER_V1.md`](format/OBJECT_CONTAINER_V1.md) §11.

BLAKE3-256 is the commitment hash. Its result gains authenticity only by being stored inside the AEAD-protected final commit.

### 21.2 Object states

The catalog persists two values per object and this document defines neither: the lifecycle `state` and the `integrity_summary` of [`format/CATALOG_SCHEMA_V1.md`](format/CATALOG_SCHEMA_V1.md) §5.1, which also derives the presentation names of [`../DESIGN.md`](../DESIGN.md) §20.1 from the pair. Names used earlier in this document are not values of either enum: `Incoming` is the `stage` of an `ImportTransaction` row per §22.1, and a purged object is the absence of the row after garbage collection per [`format/CATALOG_SCHEMA_V1.md`](format/CATALOG_SCHEMA_V1.md) §14.1.

A catalog entry MUST NOT set an object's `state` to `ACTIVE` before its encrypted container and final commit are durably written.

### 21.3 Startup reconciliation

After a crash, Rust scans journals and incoming objects:

- an open import transaction is resumed or declared dead under [`format/OBJECT_CONTAINER_V1.md`](format/OBJECT_CONTAINER_V1.md) §14.3 and §14.4; a temporary object with no journal record is always dead;
- finalized orphan objects can be reconciled into the catalog;
- an object row whose container is absent or uncommitted keeps `state` `ACTIVE` and takes `integrity_summary` `QUARANTINED`;
- no unverified object becomes visible as successfully imported.

---

## 22. Import architecture

```text
System picker / file provider
           │
           ▼
Platform read handle or file descriptor
           │
           ▼
KMP ImportCoordinator
           │
           ▼
Rust ImportTransaction
  ├── validate source capabilities
  ├── generate ObjectKey and stream prefix
  ├── stream plaintext through bounded buffers
  ├── write authenticated encrypted chunks
  ├── build encrypted manifest and final commit
  ├── fsync temporary ciphertext
  ├── atomically finalize object
  └── commit catalog state
           │
           ▼
Optional source deletion after explicit user action
```

### 22.1 Transaction ordering

Required ordering:

1. Create a journaled `Incoming` transaction.
2. Generate random object and stream key material in Rust.
3. Write encrypted content to a temporary object.
4. Write the encrypted final commit.
5. Flush and synchronize the temporary object according to platform guarantees.
6. Re-read the temporary object and run the default structural verification of [`CRYPTOGRAPHY.md`](CRYPTOGRAPHY.md) §45, or paranoid verification when the user has enabled it.
7. Atomically rename into the immutable object store.
8. Commit the catalog record and object-key envelope.
9. Mark derived assets pending or committed.
10. Report success to UI.
11. Offer deletion of the source only after step 6 passed and step 8 committed.

Within step 3, each chunk index MUST be reserved durably in the import journal before it is encrypted, and a resumed or abandoned transaction MUST follow the ordering, resume, and abandonment rules in [`format/OBJECT_CONTAINER_V1.md`](format/OBJECT_CONTAINER_V1.md) §14.2 to §14.4. The journal is a private-catalog table per [`format/CATALOG_SCHEMA_V1.md`](format/CATALOG_SCHEMA_V1.md) §11; there is no separate journal directory.

### 22.2 Essential and derived work

The original ciphertext, base metadata required to identify it, key envelope, and catalog entry are essential.

Thumbnail, waveform, OCR, poster-frame, or semantic-index generation MAY be retried after the original commits. A derived-asset failure MUST NOT silently destroy an otherwise valid imported original.

The UI may delay visibility until a grid thumbnail exists, but catalog state must distinguish `original committed` from `all derivatives ready`.

### 22.3 Progress and cancellation

Progress is coarse-grained and privacy-safe:

- bytes read and written for the active user-requested operation;
- current phase;
- cancellable state;
- sanitized failure code.

Rust SHOULD expose polled operation state or structured control-plane events rather than callbacks for every chunk.

Cancellation leaves either:

- no visible object and a recoverable/removable temporary transaction; or
- a fully committed object.

There is no half-visible success state.

---

## 23. Export architecture

```text
Encrypted object
      ↓
Rust reader and completeness policy
      ↓
Authenticated plaintext ranges
      ↓
Protected destination stream or scratch file
      ↓
Platform share/save flow
      ↓
Cleanup and audit-safe completion result
```

### 23.1 Export modes

- **Verified export:** verify complete-object state before or while creating a temporary protected destination, then expose only the completed output.
- **Direct destination export:** stream to a revocable platform destination and delete/abort partial output on integrity failure where the provider supports it.
- **Playback range access:** return only independently authenticated requested ranges; not equivalent to complete export.

### 23.2 Scratch policy

When a platform decoder, editor, or share provider requires a file URL, scratch plaintext MUST be:

- app-private;
- randomly named;
- excluded from backup;
- protected by the strongest compatible platform file-protection class;
- deleted immediately after use;
- recovered and cleaned on the next launch after interruption;
- absent from logs and analytics.

Chur MUST NOT claim physical overwrite on flash storage. Wear levelling, snapshots, backup, and copy-on-write prevent reliable application-level secure erase.

### 23.3 Crypto-erasure

Local crypto-erasure requires removal of every locally accessible envelope for an object key, including current catalog state, WAL/journal copies, and queued sync operations. A backup package written before the erasure commit is outside this boundary: it carries its own envelope and portable slot and stays openable until the package itself is destroyed, per [`security/KEY_HIERARCHY.md`](security/KEY_HIERARCHY.md) §10. The ordering that achieves this, the transaction that is the erasure moment, the garbage-collection trigger, and the recovery of a half-deleted object are normative in [`format/CATALOG_SCHEMA_V1.md`](format/CATALOG_SCHEMA_V1.md) §14.1 and are not restated here ([`ADR-0027`](adr/0027-freeze-the-deletion-transaction.md)).

It cannot force already-authorized recipients, remote devices, old backups, or previously exported plaintext to forget data.

---

## 24. Plaintext lifecycle

Plaintext is treated as a toxic, bounded, temporary resource.

Rules:

- decrypt only required ranges;
- avoid persistent plaintext files;
- avoid `String` for key material;
- keep private metadata projections minimal;
- use bounded buffers and clear them after use;
- clear image, video, audio, and query caches on lock;
- do not place private values in coroutine names, exceptions, logs, analytics, crash attachments, or saved state;
- close platform decoders and players during locking;
- exclude scratch files from backups;
- schedule startup cleanup for interrupted scratch operations.

Rust secrets use zeroizing wrappers and MUST NOT implement unredacted `Debug`. JVM, Kotlin/Native, and Swift buffer clearing is best effort and does not create a guarantee against a compromised runtime.

---

## 25. Private image pipeline

Private images use a separate Coil `ImageLoader` and cache namespace.

```text
Compose image request
       ↓
Private Coil fetcher
       ↓
Opaque object/asset handle
       ↓
Rust authenticated range or complete thumbnail read
       ↓
Platform decoder / Compose bitmap
```

Requirements:

- no shared public/private disk cache;
- disk cache disabled or ciphertext-only;
- bounded session-scoped memory cache;
- cache keys contain session-scoped opaque references, not filenames;
- all private bitmap caches are invalidated on lock;
- grid requests encrypted thumbnails, not full-resolution originals;
- thumbnails are linked to the parent content revision.

---

## 26. Video and audio playback

### 26.1 Android

```text
Media3 / ExoPlayer
       ↓
Chur DataSource
       ↓
object_reader_read_at(handle, offset, buffer)
       ↓
Rust authenticates affected chunks
       ↓
requested plaintext slice
```

### 26.2 iOS

```text
AVPlayer
       ↓
AVAssetResourceLoaderDelegate
       ↓
object_reader_read_at(handle, offset, buffer)
       ↓
Rust authenticates affected chunks
       ↓
requested plaintext slice
```

### 26.3 Reader behavior

`ObjectReader`:

- is bound to one session generation;
- returns only bytes from authenticated chunks;
- validates offset and length before allocation;
- supports cancellation;
- uses a bounded decrypted-chunk cache;
- clears cache on close or lock;
- returns `SESSION_EXPIRED` after lock;
- separates range verification from complete-object verification.

Audio MAY additionally expose a sequential reader for long recordings. Cover art and waveforms are encrypted derived assets.

### 26.4 Codec boundary

Rust owns canonical identity, metadata storage, encryption, and persistence. It does not need to bundle every platform media codec in the first release.

```text
Platform media APIs
    probe / decode / transcode where necessary
          ↓ transient normalized representation
Rust
    validates canonical metadata
    encrypts and persists it
```

This boundary keeps HEIF, Live Photos, HDR video, ProRes, RAW, and system-specific codecs practical. Any plaintext crossing the boundary remains session-scoped and must follow the logging and memory rules.

---

## 27. FFI architecture

Interop is split into a control plane and data plane.

### 27.1 Control plane

Small structured records:

- runtime initialization and API-version handshake;
- unlock and lock;
- object queries and paged projections;
- operation creation, progress, cancellation, and result;
- migration state;
- integrity status;
- redacted structured errors;
- opaque session, object, reader, and operation handles.

### 27.2 Data plane

Uses a stable C ABI:

- opaque integer handles rather than raw pointers;
- platform file descriptors or callbacks with strict contracts;
- direct/native buffers;
- `read_at(offset, destination)`;
- sequential import/export operations;
- explicit close and cancellation;
- bounded copies.

Large media MUST NOT repeatedly cross FFI as allocated Kotlin `ByteArray` or Swift `Data` values.

The exported symbol names, the handle representation, the capability bits, and the status type are frozen in [`interop/FFI_CONTRACT.md`](interop/FFI_CONTRACT.md) §2 and §6.2. The Kotlin interface below illustrates the adapter written above them; it is not the ABI.

### 27.3 Conceptual KMP API

```kotlin
interface VaultEngine {
    suspend fun unlock(request: UnlockRequest): UnlockResult
    suspend fun lock(reason: LockReason)

    suspend fun queryObjects(
        session: VaultSessionHandle,
        query: ObjectQuery,
    ): Page<ObjectProjection>

    suspend fun beginImport(
        session: VaultSessionHandle,
        source: PlatformReadHandle,
        request: ImportRequest,
    ): OperationHandle

    suspend fun openObject(
        session: VaultSessionHandle,
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

`ObjectQuery`, `ObjectProjection`, and `Page` are the Kotlin spellings of `ObjectQueryV1`, `ObjectProjectionV1`, and the page result of [`format/CATALOG_SCHEMA_V1.md`](format/CATALOG_SCHEMA_V1.md) §16.1 and §16.2, which own their fields, the sort keys, the keyset-cursor contract, and the page-size bound. This document MUST NOT define them.

### 27.4 Safety rules

- Rust panics MUST NOT unwind across FFI.
- Every input length is validated before pointer use or allocation.
- Handles are looked up in a typed registry and checked against runtime/session generation.
- Errors contain stable codes, retryability, and a diagnostic token, but no private inputs.
- Native calls remain coarse-grained.
- Kotlin coroutines wrap synchronous calls on bounded dispatchers.
- Rust never calls foreign code in v1: progress is polled from the operation handle per [`interop/FFI_CONTRACT.md`](interop/FFI_CONTRACT.md) §10, and the polling caller owns the delivery thread.
- The binding generator is replaceable; core crates do not expose binding-specific types.

### 27.5 Secret transfer

Password bytes and a platform-unwrapped root secret may cross the platform/Rust boundary during unlock. They MUST:

- use fixed or bounded mutable byte buffers;
- avoid JSON and generic serialization;
- be copied the minimum number of times;
- be cleared best effort immediately after Rust accepts them;
- never appear in errors, logs, crash reports, or coroutine state longer than necessary.

---

## 28. Android integration

The Android application is a thin native shell around shared Compose content and platform services.

### 28.1 Key protection

- Android Keystore holds an AES wrapping key, not media keys.
- User authentication is enforced with `BiometricPrompt` and Keystore policy.
- StrongBox is optional and must fall back safely.
- Key invalidation must route to password/recovery unlock and re-enrollment.
- The app must distinguish `platform key unavailable` from vault corruption internally while presenting non-oracular user errors.

### 28.2 Storage

Proposed placement:

```text
filesDir/
    encrypted catalog and object store

noBackupFilesDir/
    device-bound aliases, envelopes, and nonportable state

cacheDir/
    disposable ciphertext and policy-controlled plaintext scratch only
```

Auto Backup and device-transfer rules MUST be explicit. A device-bound key without a portable recovery slot cannot make restored ciphertext recoverable on another device.

### 28.3 Import

Use the system Photo Picker or explicit document picker to minimize broad library permissions. Pass file descriptors or seekable handles into the Rust data plane instead of loading media into Kotlin memory.

### 28.4 Playback

Media3 uses a custom `DataSource` backed by a Rust `ObjectReader`. The player is stopped and its data source invalidated before private UI is removed during lock.

### 28.5 Screen and task privacy

- private screens use `FLAG_SECURE` according to product policy;
- the task snapshot is replaced by a neutral public/privacy surface;
- notifications contain no vault names, media names, counts, or private intent data;
- screenshot protection is treated as defense in depth, not root-compromise protection.

### 28.6 Background execution

WorkManager MAY transfer already encrypted objects while locked. It MUST NOT obtain `VaultRootSecret` solely to perform ordinary ciphertext upload/download.

Background import, thumbnail generation, or metadata operations that require plaintext need an explicit security design and are not assumed by v1.

---

## 29. iOS integration

The iOS target uses a thin SwiftUI/UIKit shell with Compose content and native platform adapters.

### 29.1 Key protection

- Keychain protects short device-bound secret material.
- `userPresence` is the recoverable default policy.
- `biometryCurrentSet` is an optional strict mode with documented invalidation.
- password/recovery slots remain independent from biometric enrollment.
- Secure Enclave is not used as a bulk media cipher.

### 29.2 Data Protection

Private catalog, key-slot files, and plaintext scratch use the strongest compatible Data Protection class. Strict vault data targets `NSFileProtectionComplete`.

Ciphertext-only background transfer MAY use a policy compatible with operation after first unlock, provided root and catalog keys remain unavailable while locked.

### 29.3 Import

Use `PhotosPicker` and file representations. Imports must support:

- iCloud download progress;
- cancellation;
- large file representations;
- streaming or seekable access without loading complete video into Swift/Kotlin memory.

### 29.4 Playback

`AVAssetResourceLoaderDelegate` bridges AVFoundation byte-range requests to the Rust reader. Resource-loader objects are session-bound and invalidated on lock.

### 29.5 Scene privacy

- private content is covered before background snapshots;
- capture-state changes can trigger warning or obscuring policy;
- the architecture does not claim universal screenshot prevention on iOS;
- private navigation and player state are not restored after process death.

### 29.6 Background execution

Locked background work is limited to ciphertext operations unless the user explicitly enables a separately reviewed capability requiring unlocked material.

---

## 30. Locking and lifecycle

Locking is a security transaction, not a navigation event.

Required sequence:

1. Transition root state to `Locking`; reject new private operations.
2. Cover private UI and task/scene snapshots.
3. Stop Media3/AVPlayer and platform decoders.
4. Cancel imports, exports, queries, and derived-asset jobs according to transaction policy.
5. Increment Rust session generation.
6. Zeroize session secrets in place.
7. Invalidate all object readers and operation handles.
8. Close the private catalog connection.
9. Clear Rust, Coil, Compose, platform decoder, and feature caches.
10. Destroy the private navigation graph and secure dependency graph.
11. Return to the public shell.

Triggers include:

- explicit lock;
- panic lock;
- configured inactivity timeout;
- device lock;
- app backgrounding according to policy;
- account/vault switch;
- unrecoverable integrity or platform-key error.

Lock must be idempotent and safe during concurrent reader/import completion.

---

## 31. Error model

Rust exposes stable error codes rather than internal strings.

The codes, their numeric values, their retryability, and the rule for an unknown value are registered once in [`ERROR_MODEL.md`](ERROR_MODEL.md). This document MUST NOT restate them or introduce a parallel vocabulary.

Rules:

- unlock errors do not reveal credential-to-vault mapping;
- messages contain no secret or private input;
- logs use an opaque diagnostic token;
- retryability is explicit;
- corruption is not downgraded to ordinary I/O failure;
- authentication failure is not used to hide detected catalog corruption from repair tooling, although the user-facing unlock surface may remain non-oracular.

---

## 32. Logging and observability

Kermit is wrapped behind a privacy-safe logging facade. Rust uses an equivalent redacted event facade.

Allowed events resemble:

```text
VAULT_UNLOCK_STARTED
VAULT_UNLOCK_SUCCEEDED
VAULT_UNLOCK_FAILED
IMPORT_STARTED
IMPORT_COMMITTED
IMPORT_CANCELLED
OBJECT_INTEGRITY_FAILED
SESSION_LOCKED_BACKGROUND
MIGRATION_STARTED
MIGRATION_ROLLED_BACK
```

Forbidden values include:

- passwords and recovery secrets;
- salts, nonces, wrapped-key contents, or key bytes;
- filenames and filesystem source paths;
- album, tag, note, and caption text;
- EXIF and coordinates;
- private object IDs when a short-lived diagnostic alias is sufficient;
- private search queries;
- media thumbnails or attachments;
- real/decoy identity indicators.

Debug builds do not relax secret-redaction rules.

Crash reporting, if introduced, must be opt-in or strictly sanitized. Native tombstones and exception breadcrumbs require privacy review before upload.

---

## 33. Backup architecture

Backup and synchronization are different features.

### 33.1 Portable encrypted backup

A portable backup package needs:

- backup format version;
- portable password/recovery key slot;
- encrypted catalog snapshot and required deltas;
- immutable encrypted object containers;
- object-key and collection-key envelopes;
- authenticated package manifest;
- completeness and compatibility metadata.

Device-only platform slots are omitted or marked nonportable.

Restore MUST validate all lengths and format versions before allocation, authenticate the package manifest, verify required object/envelope presence, and stage the restored vault before making it active.

### 33.2 `age` export

An `age`-compatible stream may be used for an interoperable export or backup envelope. It does not replace:

- the media catalog;
- logical albums;
- object revisions;
- decoy topology;
- sync operations;
- Chur object containers.

---

## 34. Synchronization architecture

Sync was added after the local format and migrations became stable.

### 34.1 Opaque server model

The server stores:

```text
opaque account/device identifiers
encrypted immutable objects
encrypted catalog operations
wrapped collection keys
signed operation metadata
upload/download status
```

It does not need private filenames, EXIF, thumbnails, media types, album titles, root keys, or plaintext hashes.

### 34.2 Upload commit

```text
Client creates immutable encrypted object
    ↓
Upload chunks or complete container
    ↓
Server confirms durable ciphertext
    ↓
Upload authenticated object commit/catalog operation
    ↓
Object becomes visible to other devices
```

A server-side partial upload is not a committed object.

### 34.3 Authenticated operation log

Mutable state uses signed per-device operations. The record fields, including the `observed_heads` vector that orders operations across devices, are defined in [sync/OPERATION_LOG.md](sync/OPERATION_LOG.md) §2 and §4.

Clients track accepted sequence/head state. This detects many replay, rollback, and fork conditions. It does not by itself prove that a malicious server has not omitted an entire unseen branch; stronger transparency or device-gossip mechanisms may be added later.

### 34.4 Conflict semantics

Immutable media objects avoid content conflicts. Mutable metadata uses explicit revisions or operations with deterministic conflict rules.

Deletion uses tombstones retained long enough to prevent an offline device from resurrecting data accidentally.

### 34.5 Background synchronization

Ktor transports opaque ciphertext. Locked devices may upload/download ciphertext and store pending operations, but cannot apply private catalog updates requiring root or collection keys until unlock.

### 34.6 Deduplication

Global deduplication through plaintext hashes is forbidden. Allowed options:

- no deduplication;
- local-only deduplication;
- a user-specific keyed fingerprint under a dedicated derived key.

---

## 35. Future sharing architecture

Public-key cryptography wraps security-collection keys, not media files.

Proposed recipient suite:

```text
KEM  = DHKEM(X25519, HKDF-SHA-256)
KDF  = HKDF-SHA-256
AEAD = ChaCha20-Poly1305
```

Ed25519 provides persistent sender/device identity and operation signatures.

```text
CollectionGrant
├── protocol_version
├── collection_id
├── collection_epoch
├── recipient_key_id
├── sender_key_id
├── permissions
├── HPKE encapsulation
├── wrapped collection key
├── creation and expiry policy
└── sender signature
```

Requirements:

- recipients can verify human-readable identity fingerprints;
- membership changes create a new collection epoch;
- new objects use the new epoch;
- old object keys may be rewrapped progressively;
- revocation cannot erase plaintext or keys already retained by an authorized recipient;
- share operations are canonicalized and signed by Rust;
- sharing receives a separate protocol audit before production release.

Post-quantum recipient types may be added through versioned `kem_id` and recipient records without changing local symmetric media encryption.

---

## 36. Migration architecture

Rust owns all migrations affecting private state.

Migration categories:

- key-slot format;
- catalog schema;
- object-key envelope;
- encrypted manifest/final commit;
- algorithm suite;
- storage layout;
- sync operation protocol.

### 36.1 Version policy

Every vault records:

```text
format_version
catalog_schema_version
minimum_reader_version
minimum_writer_version
```

Unknown future versions fail closed. Deprecated versions may be opened read-only for migration when safe.

### 36.2 Migration transaction

```text
Preflight and resource check
    ↓
Encrypted checkpoint / journal
    ↓
Migrate in bounded batches
    ↓
Verify new representation
    ↓
Atomically commit version marker
    ↓
Retire old representation
```

Migrations MUST be restartable or rollback-safe. An interrupted migration cannot leave the only key envelope or only valid catalog representation half-written.

### 36.3 Compatibility testing

- every supported `N → N+1` migration has deterministic fixtures;
- old mobile builds and `chur-cli` share vectors;
- migrations are fault-injected at each durable write boundary;
- downgrade behavior is explicit;
- migration code remains available as long as supported backups can contain the old version.

---

## 37. Threat model

### 37.1 Mitigated threats

| Threat | Primary controls |
| --- | --- |
| Lost or stolen locked device | OS data protection, Keystore/Keychain gate, encrypted catalog and objects |
| Application sandbox copied | root secret unavailable, encrypted catalog, per-object AEAD containers |
| Single object-key compromise | independent random object keys and domain-separated stream keys |
| Chunk corruption/substitution | XChaCha20-Poly1305 with canonical AAD |
| Object truncation | encrypted final commit and complete-object verification |
| Password database extraction | Argon2id, random salt, bounded parameters |
| Password change | rewrap root secret without media re-encryption |
| Collection membership change | key epochs and object-key rewrapping |
| Cloud compromise | ciphertext-only server model |
| Casual UI observation | functional public shell, privacy snapshots, immediate lock |
| Coercive UI inspection | independent decoy vault with explicit limitations |
| Interrupted import | transaction journal, fsync, atomic rename, catalog commit |
| Replay of known sync entries | device sequence/hash chains, signatures, and authenticated checkpoints |

### 37.2 Threats not fully mitigated

| Threat | Limitation |
| --- | --- |
| Compromised OS/kernel | attacker may observe plaintext and keys after unlock |
| Root/jailbreak/runtime instrumentation | active memory and calls may be inspected or modified |
| Malicious keyboard/accessibility service | credentials or displayed content may be captured |
| External camera | visible content can be photographed |
| Universal screenshot prevention on iOS | no complete application-level guarantee |
| Physical secure erase on flash | wear levelling, snapshots, and copy-on-write prevent guarantees |
| Recipient deletion after sharing | recipients may retain keys or plaintext |
| Malicious server omission | signed logs detect many rollbacks but not every unseen omitted branch |
| Undetectable hidden volume | ciphertext volume, storage layout, backups, and I/O patterns may reveal data |
| Forgotten credentials without recovery | strong cryptography implies irreversible loss |

The primary guarantee is confidentiality and integrity of data at rest while the vault is locked. Runtime hardening reduces exposure but does not make a compromised device trustworthy.

---

## 38. Security invariants

[`security/SECURITY_INVARIANTS.md`](security/SECURITY_INVARIANTS.md) is the registry. The implementation and every migration MUST preserve every invariant recorded there. Each entry carries a stable `SEC-` identifier that this document, the ADRs, the test plan, and audit findings cite; this section does not restate them, so there is one list to keep correct.

---

## 39. Testing and assurance

### 39.1 Rust unit and property testing

- known-answer tests for every cryptographic construction;
- deterministic vectors for key slots, envelopes, manifests, chunks, final commits, catalogs, and grants;
- property-based serialization and state-machine tests;
- nonce uniqueness tests;
- key rotation and rewrapping tests;
- catalog/object reconciliation tests;
- import/export cancellation tests;
- migration round trips;
- redacted error and secret-debug tests where observable.

### 39.2 Corruption matrix

Every parser and reader is tested against:

- bit flips in each structured field;
- truncated preamble, manifest, chunk, tag, or final commit;
- missing middle/final chunks;
- duplicated or reordered chunks;
- chunk copied from another object;
- forged plaintext lengths or chunk counts;
- invalid stream revisions;
- unknown suites and versions;
- oversized encoded metadata;
- integer overflow and allocation attacks;
- malformed key slots;
- extreme Argon2 parameters;
- corrupted catalog WAL, journal, and migration state.

### 39.3 Fuzzing

Planned targets:

```text
parse_vault_descriptor
parse_key_slot
parse_object_key_envelope
parse_object_preamble
parse_manifest
parse_final_commit
decrypt_chunk
decode_private_metadata
open_catalog
import_backup
apply_catalog_migration
apply_sync_operation
validate_ffi_input
```

Parsers impose hard limits before allocation.

### 39.4 Fault injection

Durable operations are interrupted after every write boundary:

- before and after temporary object creation;
- between a durable chunk-index reservation and the chunk record write;
- between a chunk record write and the next reservation;
- before/after final commit;
- before/after fsync;
- before/after rename;
- before/after catalog commit;
- during key rewrap;
- during migration version change;
- during backup restore activation.

The expected result is either the old valid state or the new valid state, never an unreported ambiguous success.

### 39.5 Cross-platform vectors

```text
Encrypt on Android
Decrypt on iOS
Verify with chur-cli
```

and the inverse direction. Test vectors must be independent from platform locale, endianness, time zone, Unicode composition, or serialization defaults.

### 39.6 Platform-security tests

- biometric enrollment changes;
- device passcode changes;
- Keystore/Keychain invalidation;
- StrongBox unavailable/fallback;
- backup and restore to another device;
- process death during unlock/import/playback;
- background/foreground locking races;
- stale reader handles after lock;
- app-switcher privacy behavior;
- no private saved-state restoration;
- scratch cleanup after crash;
- encrypted-only background sync while locked.

### 39.7 UI tests

CMP UI tests and platform tests cover:

- public/private navigation separation;
- unlock error non-oracle behavior;
- lock during every private screen;
- panic lock;
- decoy/real session isolation;
- redacted notifications;
- screen/task privacy transitions;
- import cancellation and retry;
- corrupted-object presentation without leaking filenames.

### 39.8 Release gates

Before production storage claims:

1. finalize the byte-level vault and object specifications;
2. publish golden vectors;
3. fuzz parsers, catalog migrations, and FFI;
4. complete recovery and interrupted-write testing;
5. run dependency and supply-chain review;
6. complete an independent security review of Rust core and platform key integration;
7. resolve findings and publish a remediation summary;
8. establish `SECURITY.md` and private vulnerability reporting.

Sync and sharing require additional protocol review.

---

## 40. Performance and resource budgets

Values are provisional targets and MUST be benchmarked on the device set frozen in [ADR-0017](adr/0017-freeze-the-supported-device-set.md).

### 40.1 Memory

- media import and playback memory is bounded by a small number of chunks plus decoder buffers;
- Argon2id operations are serialized or tightly limited;
- grid loading uses encrypted thumbnails, never full originals;
- catalog queries are paged;
- no whole-file plaintext or ciphertext copy crosses FFI.

### 40.2 Latency

Targets to validate:

- platform-biometric unlock adds minimal work beyond root release and catalog open;
- password unlock intentionally costs approximately the calibrated Argon2 budget for each of the constant candidates of [`security/KEY_SLOTS.md`](security/KEY_SLOTS.md) §8;
- a video `readAt` supports sustained throughput above the highest supported media bitrate;
- random seek decrypts only required chunks;
- lock makes private UI unavailable immediately, even if cleanup continues briefly behind the covered surface.

### 40.3 Storage overhead

Overhead includes:

- per-chunk authentication tags;
- encrypted manifest and final commit;
- object-key envelopes;
- encrypted thumbnails/previews;
- catalog and WAL;
- optional padding.

Padding to hide exact sizes is not required for v1. If added, it must be an explicit policy with measurable storage cost.

### 40.4 Battery

Benchmarks cover:

- import encryption throughput and energy;
- thumbnail generation;
- video playback and seeking;
- locked ciphertext sync;
- large catalog indexing;
- background cleanup and integrity scans.

---

## 41. Build and release architecture

### 41.1 Kotlin/Gradle

- central version catalog;
- convention plugins;
- KSP only where required;
- configuration-cache-compatible tasks;
- reproducible toolchain versions;
- dependency verification and lock policy;
- separate build types for development diagnostics and production hardening.

### 41.2 Rust

- workspace-level dependency policy;
- locked toolchain and `Cargo.lock` for applications;
- `cargo fmt`, Clippy, tests, `cargo deny`, and fuzz jobs;
- Android targets built through NDK tooling;
- iOS static libraries/XCFramework packaging;
- exported symbols restricted to the frozen `chur_`-prefixed surface by a version script on Android and an exported-symbols list on Apple;
- `panic = "unwind"` for every FFI artifact, with mandatory `catch_unwind` at every export per [`interop/FFI_CONTRACT.md`](interop/FFI_CONTRACT.md) §11;
- no accidental export of internal Rust symbols beyond the FFI surface.

### 41.3 CI matrix

Planned CI stages:

```text
format and lint
KMP unit tests
Rust unit/property tests
format golden vectors
Android build and instrumentation subset
iOS build and simulator tests
cross-platform compatibility tests
fuzz smoke tests
migration/fault-injection suite
license and dependency checks
release artifact inspection
```

Security-sensitive release artifacts must be generated from reviewed commits, include provenance/SBOM where practical, and undergo native library inventory checks.

---

## 42. Privacy review checklist for new features

Every new feature must answer:

1. What private plaintext does it create?
2. Which process/language/runtime holds that plaintext?
3. Is it persisted, cached, logged, snapshotted, backed up, or transferred?
4. Which key domain protects it?
5. What invalidates it on lock?
6. Can it cross real/decoy boundaries?
7. Does it create a new side channel through counts, sizes, timestamps, notifications, or background tasks?
8. Does it require a format or migration change?
9. Can it run while locked using ciphertext only?
10. Which corruption, fuzz, lifecycle, and recovery tests are required?

A feature is not complete until its privacy and lifecycle behavior is specified.

---

## 43. Architecture decision backlog

[`adr/README.md`](adr/README.md) holds the backlog of decisions that still require an ADR, and [`CRYPTOGRAPHY.md`](CRYPTOGRAPHY.md) §74 holds the open cryptographic decisions. This section keeps no list of its own.

The decisions this section previously listed as open are now recorded: the canonical encrypted-record encoding in [`ADR-0010`](adr/0010-define-canonical-tuple-and-freeze-hkdf-salt.md) and [`ADR-0013`](adr/0013-allocate-v1-format-constants.md); the chunk-size policy in [`ADR-0008`](adr/0008-freeze-object-container-v1-layout.md) and [`ADR-0020`](adr/0020-set-the-v1-parser-limits.md); the control-plane binding generator in [`ADR-0016`](adr/0016-freeze-the-v1-c-abi.md); the portable backup format in [`ADR-0018`](adr/0018-freeze-backup-package-framing.md); the sync conflict model in [`ADR-0014`](adr/0014-observed-heads-causality-vector.md), [`ADR-0021`](adr/0021-freeze-conflict-tie-break-and-set-semantics.md), and [`ADR-0022`](adr/0022-freeze-operation-chain-hash-and-identifier.md); the metadata and derived-asset transaction boundary in §22.2 and [`format/CATALOG_SCHEMA_V1.md`](format/CATALOG_SCHEMA_V1.md) §10; and optional size padding in [`sync/SYNC_PROTOCOL_V1.md`](sync/SYNC_PROTOCOL_V1.md) §11, which defers it to a later versioned transport profile.

An ADR number identifies exactly one decision. A new ADR takes the next unused number in [`adr/`](adr/), and no number is reused for a second subject. No open decision may be resolved implicitly through a library default.

---

## 44. Delivery sequence

[`ROADMAP.md`](../ROADMAP.md) owns the phase definitions, their scope, exclusions, and exit criteria; this section does not restate them. The ordering constraint that this architecture depends on is fixed by [`ADR-0007`](adr/0007-local-first-before-sync.md): the local vault stabilizes before sync and sharing, and no phase may bypass the evidence its gate requires in [`assurance/RELEASE_GATES.md`](assurance/RELEASE_GATES.md).

---

## 45. Required follow-up specifications

The decomposition is complete. [`README.md`](README.md) indexes every normative document this file defers to, and the decisions that still require a specification are the backlog in [`adr/README.md`](adr/README.md).

---

## 46. Reference architecture and standards

Chur is an independent design. The following are reference points rather than drop-in specifications:

- [Ente](https://github.com/ente-io/ente) — media-oriented end-to-end encryption, master/collection/file key hierarchy, and sharing concepts.
- [Cryptomator vault cryptography](https://docs.cryptomator.org/security/vault/) — authenticated chunking and explicit threat-model documentation.
- [age format](https://age-encryption.org/v1) — modern encrypted export/backup and recipient design.
- [RFC 9106](https://www.rfc-editor.org/rfc/rfc9106.html) — Argon2 guidance.
- [RFC 9180](https://www.rfc-editor.org/rfc/rfc9180.html) — HPKE.
- [RFC 8452](https://www.rfc-editor.org/rfc/rfc8452.html) — AES-GCM-SIV as a nonce-misuse-resistant reference.
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore) — device key protection.
- [Android Photo Picker](https://developer.android.com/training/data-storage/shared/photo-picker) — least-privilege media import.
- [Android Media3 customization](https://developer.android.com/media/media3/exoplayer/customization) — custom encrypted data sources.
- [Apple Keychain data protection](https://support.apple.com/guide/security/keychain-data-protection-secb0694df1a/web) — Keychain protection model.
- [Apple Data Protection classes](https://support.apple.com/guide/security/data-protection-classes-secb010e978a/web) — file protection.
- [AVAssetResourceLoaderDelegate](https://developer.apple.com/documentation/avfoundation/avassetresourceloaderdelegate) — custom byte-range loading.
- [UniFFI](https://github.com/mozilla/uniffi-rs) and [Gobley](https://gobley.dev/) — binding generators evaluated for the control plane and rejected by [`ADR-0016`](adr/0016-freeze-the-v1-c-abi.md).

Licenses and protocol assumptions must be reviewed before reusing implementation code. Architectural similarity does not imply license compatibility or protocol interoperability.

---

## 47. Summary

The Chur architecture is defined by one ownership rule:

> **KMP owns the application experience. Rust owns private data and its cryptographic lifecycle. Platform key services control release of the short root secret.**

The resulting system uses:

```text
functional public shell
    + independent authenticated vault sessions
    + Rust-owned encrypted catalog
    + immutable chunked media objects
    + separate key envelopes
    + per-object random keys
    + Argon2id password KEKs
    + Keystore/Keychain device protection
    + bounded random-access playback
    + explicit integrity and migration states
    + future ciphertext-only sync and collection sharing
```

Local-first implementation, stable test vectors, recovery testing, fuzzing, and an independent review are prerequisites for treating Chur as a production vault.
