# Chur Threat Model

> **Status:** Proposed normative threat model  
> **Scope:** local vault first; future backup, sync, and sharing are identified but not production-approved

## 1. Security objective

Chur protects the confidentiality and integrity of private photos, videos, audio, metadata, derivatives, and access keys while a vault is locked. It also reduces accidental disclosure while the application is in use. The strongest guarantee is **data-at-rest protection under a locked-vault model**.

Chur does not turn a compromised unlocked operating system into a trusted execution environment.

## 2. Protected assets

| Asset | Confidentiality | Integrity | Availability |
| --- | ---: | ---: | ---: |
| `VaultRootSecret` and derived keys | Critical | Critical | Critical |
| Password and recovery inputs | Critical | Critical | Critical |
| Collection and object keys | Critical | Critical | Critical |
| Original media plaintext | Critical | Critical | High |
| Metadata, EXIF, GPS, names, tags | Critical | High | High |
| Thumbnails, previews, waveforms | Critical | High | Medium |
| Private catalog and search indexes | Critical | Critical | High |
| Device identity private keys | Critical | Critical | High |
| Sync operation log and tombstones | High | Critical | High |
| Public-shell data | User-defined | Medium | Medium |
| Ciphertext and opaque identifiers | Medium | Critical | High |
| Security configuration and audit state | High | Critical | High |

## 3. Trust boundaries

```text
User
  ↓ credentials / consent
Platform UI and key services
  ↓ bounded bytes and authorization
KMP application orchestration
  ↓ versioned FFI
Rust vault runtime
  ↓ encrypted catalog and object store
Application filesystem / optional untrusted server
```

Trusted for their stated role:

- Rust cryptographic and storage core;
- correctly configured Android Keystore and iOS Keychain services;
- platform sandbox and data-protection mechanisms while the OS is not compromised;
- user-controlled password/recovery secret;
- deterministic specifications and tests used to build the release.

Not trusted with plaintext or root keys:

- public Room/DataStore storage;
- media backup/sync server;
- analytics, crash reporting, and log collection;
- public-shell UI;
- other applications and content providers;
- network intermediaries;
- future sharing recipients beyond granted access.

## 4. Attacker profiles

### A1 — Casual observer

Can see the launcher, notifications, recents, public shell, and unlocked screen from nearby. Cannot bypass OS access controls.

### A2 — Person holding an unlocked device

Can open applications and interact with Chur but does not know the private credential. May attempt screenshots, search, deep links, accessibility exploration, or public-shell inspection.

### A3 — Thief with a locked device

Controls the physical device and can attempt offline extraction, reboot, backup recovery, or OS authentication attacks without a valid credential.

### A4 — Sandbox-extraction attacker

Obtains application files, databases, backups, or filesystem snapshots but not an unlocked in-process root secret.

### A5 — Malicious or compromised server

Controls stored ciphertext, responses, ordering, replay, omission, timing, and availability. Does not know user secrets.

### A6 — Rooted/jailbroken or instrumented-device attacker

Can inspect process memory, intercept calls, modify code, or observe plaintext after unlock. This exceeds the primary guarantee.

### A7 — Malicious input provider

Supplies malformed media, containers, backups, catalogs, key slots, sync records, lengths, KDF parameters, or codec inputs.

### A8 — Coercive UI inspector

Can demand that the user unlock the application and inspect visible content. Decoy Vault reduces disclosure but does not provide an undetectable hidden volume.

### A9 — Previously authorized recipient

Possesses collection keys or plaintext obtained legitimately and may retain it after revocation.

### A10 — Supply-chain attacker

Attempts to compromise dependencies, build scripts, CI actions, signing, generated bindings, or release artifacts.

## 5. Security assumptions

- the user chooses a password with sufficient entropy or safely stores a recovery secret;
- OS random generation is functioning;
- approved cryptographic libraries implement their documented algorithms correctly;
- the locked device's platform security is not fully bypassed;
- release artifacts correspond to reviewed source and pinned dependencies;
- the application can obtain an app-private filesystem location;
- platform media APIs may see transient plaintext when needed for decoding or export;
- ciphertext size and access timing are not fully hidden.

## 6. Threats and controls

| ID | Threat | Primary controls | Residual risk |
| --- | --- | --- | --- |
| T-001 | offline password guessing | Argon2id, random salt, bounded/upgradeable parameters | weak user password remains weak |
| T-002 | sandbox copy reveals media | encrypted catalog, object containers, encrypted derivatives | sizes and object count may leak |
| T-003 | one key exposes all objects | independent random object keys | root/collection compromise has wider impact |
| T-004 | nonce reuse | per-revision random prefix, monotonic chunk index, tests | RNG or state-machine failure |
| T-005 | chunk substitution/reorder | canonical AAD binds object/stream/revision/index | implementation bug |
| T-006 | object truncation | authenticated final commit and complete verification | playback may intentionally verify only ranges |
| T-007 | malicious lengths/KDF parameters | hard limits before allocation/work | bounded denial of service remains possible |
| T-008 | interrupted import creates visible partial object | journal, temp ciphertext, fsync, atomic rename, catalog transaction | device/storage failure may require repair |
| T-009 | password change corrupts data | rewrap root only, transactional slot update | loss if every valid slot is destroyed |
| T-010 | platform key invalidation causes lockout | independent password/recovery slot | user may lose every factor |
| T-011 | logs/crashes leak private data | deny-by-default logging, redacted errors, tests | OS/runtime may inspect memory directly |
| T-012 | public shell reveals private state | separate DB/graph/navigation/cache | storage volume may remain observable |
| T-013 | real and decoy share state | separate roots/catalogs/namespaces/aliases/sessions | filesystem forensics may infer extra data |
| T-014 | stale media reader survives lock | session generation, in-place zeroization, cancellation | compromised process can bypass application logic |
| T-015 | server replays old valid data | signed operation chains, local accepted heads | server omission across all devices is difficult to prove |
| T-016 | recipient impersonation | verified identity keys, signed grants, human verification | user may verify wrong identity |
| T-017 | revoked recipient retains access | collection epoch rotation and rewrap | old ciphertext/plaintext already obtained remains available |
| T-018 | malicious codec input | platform sandboxing, minimal parsing, bounded resources | platform codec vulnerabilities |
| T-019 | dependency compromise | pinning, review, SBOM, reproducible release evidence | ecosystem compromise can still occur |
| T-020 | backup omits objects | authenticated backup manifest and completeness check | stale but authentic backup remains possible without trusted checkpoint |

## 7. Locked-state guarantee

When a vault is locked:

- no root, collection, object, stream, or catalog key remains intentionally available to feature code;
- private catalog connections are closed;
- native session handles are invalid;
- private navigation and decoded caches are cleared;
- persisted private media and metadata are ciphertext;
- platform device-bound factors may remain available only through Keystore/Keychain policy;
- public shell remains usable without access to private state.

Best-effort memory clearing cannot prove that a hostile OS did not copy earlier plaintext.

## 8. Unlocked-state exposure

While unlocked, Chur must minimize but cannot eliminate exposure. Plaintext may exist in:

- Rust buffers;
- direct/native FFI buffers;
- image and media decoder surfaces;
- Media3/AVFoundation buffers;
- protected scratch files for operations requiring a URL;
- visible UI and accessibility semantics.

The controls are bounded lifetimes, session-scoped caches, protected files, capture/snapshot policy, and immediate lock invalidation.

## 9. Metadata leakage

Even with full encryption, observers may learn:

- approximate ciphertext sizes;
- number of physical objects;
- creation/modification times unless normalized;
- network timing and transfer volume;
- existence of a large encrypted application data set;
- device/account activity.

Padding, batching, and metadata normalization may reduce leakage later. Oblivious storage is out of scope.

## 10. Real and decoy limitations

Decoy Vault protects against ordinary and coercive interface inspection by presenting an independently encrypted plausible data set. It does not guarantee that forensic analysis cannot infer another vault from storage size, aliases, backups, or I/O patterns.

Marketing and UI must not use “undetectable,” “hidden volume,” or equivalent claims without a separate proven construction.

## 11. Future server and sharing model

The server is assumed malicious for confidentiality and content integrity. Clients authenticate and encrypt canonical operations. Availability, global omission, traffic analysis, and recipient deletion cannot be guaranteed cryptographically by the local client alone.

## 12. Verification mapping

Every accepted threat control must map to at least one of:

- unit or known-answer test;
- property test;
- corruption/fault-injection test;
- fuzz target;
- Android/iOS integration test;
- cross-platform vector;
- independent review item;
- release gate.

The authoritative mapping lives in [`SECURITY_INVARIANTS.md`](SECURITY_INVARIANTS.md) and [`../assurance/SECURITY_TEST_PLAN.md`](../assurance/SECURITY_TEST_PLAN.md).

## 13. Review triggers

Update this threat model when adding:

- a new public shell or launcher disguise;
- background plaintext access;
- extensions, widgets, app groups, or shared processes;
- sync, sharing, web, desktop, or server-side recovery;
- new crypto suite or KDF profile;
- new catalog or container format;
- third-party telemetry or codec stack;
- enterprise/FIPS mode;
- post-quantum recipient support.
