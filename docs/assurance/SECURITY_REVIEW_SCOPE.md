# Security Review Scope

> **Status:** Proposed template for independent reviews

This document defines the artifacts and boundaries expected for Chur security reviews. Each engagement creates a versioned scope appendix naming exact commits and deliverables.

## 1. Review objectives

- validate cryptographic constructions and misuse resistance;
- validate key hierarchy, slots, recovery, rotation, and real/decoy separation;
- validate canonical formats, parsers, completeness, and transactions;
- validate private catalog and migration behavior;
- validate FFI ownership, cancellation, panic containment, and stale handles;
- validate Android/iOS platform key and plaintext lifecycle integration;
- validate sync identities, operation logs, rollback, and device revocation;
- later validate collection grants and sharing revocation.

## 2. Local-vault code scope

Expected Rust crates/modules:

```text
chur-crypto
chur-format
chur-catalog
chur-media
chur-core/session runtime
chur-ffi
chur-cli vector/repair paths
```

Expected platform/KMP scope:

- session gate and secure graph;
- public/private persistence boundary;
- Android Keystore/BiometricPrompt adapter;
- iOS Keychain/LocalAuthentication adapter;
- file descriptor/buffer adapters;
- player/image/scratch/lifecycle code;
- error/logging redaction.

## 3. Specification scope

Reviewers receive:

- `ARCHITECTURE.md` and `CRYPTOGRAPHY.md`;
- threat model and invariant registry;
- canonical encoding and all relevant format specs;
- FFI/platform/plaintext contracts;
- migration, test, fuzz, performance, and release policies;
- accepted ADRs;
- deterministic vectors.

Ambiguity between code and spec is itself a finding.

## 4. Build and provenance

Provide:

- exact source commit;
- Rust/Kotlin/Gradle/Xcode/NDK versions;
- lockfiles and dependency review output;
- build scripts and CI workflow;
- generated-binding source/version;
- release artifact architecture/symbol inventory;
- reproducible or repeatable build instructions.

## 5. Test evidence

- unit/property/KAT results;
- negative/corruption matrix;
- fuzz target list, corpus, duration, crashes;
- fault-injection results;
- cross-platform vector results;
- platform invalidation/backup/lifecycle matrix;
- log/storage leakage tests;
- performance/resource limits.

## 6. Review phases

### Phase A — design/specification

Before stable production bytes: construction, threat, invariant, versioning, recovery, and migration review.

### Phase B — local implementation

Rust core, catalog, containers, FFI, platform integration, and release build.

### Phase C — backup/sync

Backup completeness, device identity, operation log, malicious server, conflict, rollback.

### Phase D — sharing

HPKE/signatures, recipient verification, grants, epochs, revocation.

Each phase has its own commit and report.

## 7. Explicit out-of-scope items

Unless engagement states otherwise:

- full operating-system/kernel compromise after unlock;
- undisclosed third-party platform implementation vulnerabilities;
- external camera observation;
- social engineering and password strength beyond documented assumptions;
- cryptographically undetectable hidden volumes;
- forcing recipients to delete already obtained plaintext;
- server availability and traffic-analysis resistance.

Out-of-scope does not mean undocumented; residual risk remains in threat model.

## 8. Finding format

Each finding should include:

```text
ID and severity
component/commit/location
violated threat/invariant/spec
preconditions
technical impact
reproduction/vector
recommended remediation
compatibility/migration impact
verification status
```

## 9. Severity guidance

- **Critical:** practical root/key/plaintext compromise at broad scale or unrecoverable systemic corruption.
- **High:** significant confidentiality/integrity bypass under expected attacker model.
- **Medium:** constrained bypass, dangerous misuse edge, resource attack, or defense-in-depth failure with material impact.
- **Low:** limited issue unlikely to expose keys/plaintext directly.
- **Informational:** hardening, clarity, maintainability, or future-risk note.

Invariant violations may raise severity regardless of exploit complexity.

## 10. Remediation

For every accepted finding:

- create deterministic regression test/vector;
- fix and document behavior;
- define format/migration effect;
- request reviewer verification;
- publish remediation summary when disclosure permits;
- preserve historical report and reviewed commit.

## 11. Deliverables

- full report and executive summary;
- exact reviewed commit/artifacts;
- unresolved assumptions/questions;
- finding severity and remediation state;
- retest letter/report;
- public version with sensitive exploit details coordinated under disclosure policy.
