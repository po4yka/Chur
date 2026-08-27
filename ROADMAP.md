# Chur Roadmap

> **Status:** Proposed delivery plan; this document owns the phase definitions

Chur is developed in security-gated phases. Dates are intentionally omitted until implementation velocity and review capacity are known. Advancement depends on evidence, not feature count.

## Current status

**Architecture and protocol design.** The product overview, system architecture, and cryptographic design exist. Focused specifications, implementation scaffolding, vectors, and assurance infrastructure are being established.

## Phase 0 — specification and repository foundation

### Scope

- complete the normative documentation set;
- create architecture decision records;
- scaffold KMP/CMP and Rust workspaces;
- pin toolchains and dependencies;
- establish canonical encoding and byte-exact v1 formats;
- implement `chur-cli` foundations;
- publish deterministic positive and negative vectors;
- add fuzzing, corruption, migration, and FFI harnesses;
- prototype Android Keystore and iOS Keychain slots;
- benchmark candidate chunk sizes and Argon2id profiles.

### Exit criteria

- no unresolved circular key dependencies;
- parser limits specified and tested;
- Android, iOS, and CLI consume identical vectors;
- security invariants mapped to tests, through the per-invariant table in [`docs/assurance/SECURITY_TEST_PLAN.md`](docs/assurance/SECURITY_TEST_PLAN.md) §13, with every audit-only row named rather than implied;
- release gates and review scope approved.

## Phase 1 — local recoverable photo vault

### Scope

- functional Notes public shell;
- one private vault;
- vault creation and first run per [`docs/security/PROVISIONING.md`](docs/security/PROVISIONING.md);
- password, device, and recovery key slots;
- Rust-owned encrypted catalog;
- photo import through platform pickers;
- immutable encrypted originals;
- encrypted metadata, thumbnails, and previews;
- timeline, albums, favorites, viewer, and export;
- immediate, timed, background, and panic lock;
- app-switcher privacy handling;
- interrupted-import recovery and integrity inspection.

### Explicit exclusions

- cloud account;
- sync;
- sharing;
- decoy vault;
- local AI indexing;
- claims of production security before independent review.

### Exit criteria

- initialization, import, key-slot, and migration fault injection passes, matching Gate 2; the complete matrix, including media, large-file, and decoy paths, is a Phase 2 exit criterion;
- no private data persists in public storage or navigation state;
- platform-key invalidation and recovery work on supported devices;
- local format and Rust core receive independent review before production use.

## Phase 2 — video, audio, and decoy

### Scope

- Media3 and AVFoundation range readers;
- seekable video and audio playback;
- encrypted poster frames and waveforms;
- large-file import/export and cancellation;
- independent decoy vault identity;
- stronger discreet-mode policies;
- native portable encrypted backup;
- performance and energy tuning.

### Exit criteria

- multi-gigabyte objects remain bounded in memory;
- random seek and lock invalidation meet budgets;
- real/decoy isolation tests pass;
- backup restore succeeds across Android, iOS, and CLI.

## Phase 3 — encrypted synchronization

### Scope

- opaque object storage;
- ciphertext-only background transfers;
- device identities;
- signed per-device operation logs;
- replay, rollback, and fork detection;
- deterministic conflict resolution;
- tombstones and garbage collection;
- multi-device recovery;
- device revocation, and the collection-epoch rotation and rewrap it forces.

### Exit criteria

- server trust model and sync protocol finalized;
- malicious-server test harness operational;
- protocol vectors published;
- independent review of identity, log, and rollback design.

## Phase 4 — collection sharing

### Scope

- recipient verification;
- X25519 HPKE collection grants;
- Ed25519 sender/device authentication;
- permissions and membership changes;
- collection epochs and rewrapping;
- revocation semantics;
- multi-recipient and multi-device interoperability.

### Exit criteria

- separate sharing-protocol audit;
- clear forward-only revocation guarantees;
- recovery and device-loss behavior tested;
- no claim that previously authorized recipients can be forced to delete plaintext.

## Later exploration

- encrypted documents;
- local OCR and captions;
- encrypted semantic indexes and embeddings;
- private multimodal search;
- optional hybrid post-quantum recipients;
- shared family or team vaults;
- advanced padding and batching;
- additional functional public shells.

## Permanent non-goals

Unless the threat model changes explicitly, Chur does not promise:

- protection of plaintext from a compromised unlocked kernel;
- physical secure overwrite on flash storage;
- universal screenshot prevention on iOS;
- cryptographically undetectable hidden volumes;
- server-assisted password reset that can recover the root secret without a user-held recovery factor;
- global plaintext-hash deduplication.

## Roadmap governance

A phase may start experimentally before the previous phase ships, but production release gates remain ordered. Scope changes that affect security boundaries require an ADR, threat-model update, and revised assurance plan.
