# Security Release Gates

> **Status:** Proposed normative advancement criteria

Features advance only when the evidence for their threat surface is complete. A successful build or feature demo is not a security release gate.

## Gate 0 — design prototype

Permitted:

- local developer experiments;
- synthetic data only;
- unstable formats;
- no security claims.

Required:

- ownership boundaries documented;
- no committed production secrets;
- major design decisions tracked by ADR.

## Gate 1 — local alpha

Permitted:

- test/user-generated non-critical local vaults;
- no sync/sharing;
- explicit pre-audit warning.

Required:

- canonical encoding and v1 draft formats;
- key-slot/password profile;
- deterministic positive/negative vectors;
- Rust unit/property/corruption tests;
- FFI contract and platform prototype tests;
- recovery and process-death flows;
- security invariants mapped to tests.

## Gate 2 — local beta

Required:

- complete local threat model;
- all local parsers fuzzed;
- fault injection for initialization/import/slot/migration;
- Android/iOS device matrix;
- real/public storage leakage tests;
- plaintext lifecycle inspection;
- backup/restore if offered;
- no unresolved critical/high internal findings;
- published known limitations.

## Gate 3 — production local vault

Required:

- byte-frozen local formats and stable vectors;
- independent review of Rust crypto/format/catalog/FFI and platform key-slot integration;
- remediation and regression tests for findings;
- signed/repeatable release process and SBOM;
- `SECURITY.md` private reporting configured;
- migration/recovery evidence;
- support policy and production warning removed only when justified;
- the compliance record above is complete, with the classification, storefronts, jurisdictions, exclusions, and filings named.

## Gate 4 — portable backup

Additional requirements:

- backup format and vectors;
- complete/truncated/stale package tests;
- cross-platform restore;
- device-slot exclusion proof;
- old password/recovery rotation semantics documented;
- external review of backup manifest/completeness construction.

## Gate 5 — synchronization

Additional requirements:

- server trust model;
- device identity and signed operation log;
- device revocation, including the revocation point recorded in the operation log and the collection-epoch rotation that follows it;
- sync protocol/conflict/tombstone specs;
- checkpoint format and trust rule, with new-device bootstrap attested against a checkpoint commitment;
- replay/rollback/fork malicious-server tests;
- ciphertext-only background verification;
- recovery across multiple devices;
- protocol-focused independent review.

## Gate 6 — sharing

Additional requirements:

- collection grant and recipient verification specs;
- HPKE/signature vectors;
- membership/epoch/rewrap/revocation tests;
- multi-device/multi-recipient interoperability;
- explicit recipient-retention limitation;
- separate sharing-protocol audit.

## Compliance

Two things are decided here and are not release paperwork.

Chur ships only the standard published algorithms of [`../CRYPTOGRAPHY.md`](../CRYPTOGRAPHY.md), adds no proprietary construction, and performs no cryptanalytic function, so the classification path is mass-market self-classification for both stores. No build variant may shorten a key, substitute an algorithm, disable a slot type, or remove the discreet presentation in order to enter a market; a market that cannot accept the shipped design is a market Chur does not enter. This rule exists before the formats freeze, because the alternative is a per-market format and a second set of vectors.

The remainder is a determination rather than a design choice, and the repository maintainer owns it. Before Gate 3 the evidence package records the export classification and its basis, the storefronts and jurisdictions the release targets, any market excluded and the reason, and any filing or notification made. Gate 1 and Gate 2 builds are not publicly distributed, so the record blocks Gate 3 only. `IOS.md` §37, `ANDROID.md` §37, and [`../interop/IOS_INTEGRATION.md`](../interop/IOS_INTEGRATION.md) answer store questions from that record and must not answer independently.

## Blocking findings

- Critical/High: block applicable gate until fixed and independently verified.
- Medium: block unless a documented risk acceptance with bounded scope, mitigation, owner, and expiry is approved.
- Low/Informational: tracked; may ship when no invariant is violated.

Severity labels do not override direct violation of a mandatory invariant.

## Evidence package

Each gated release records:

```text
source commit/tag
format/protocol versions
vector-set digest
toolchains/dependency locks
CI/test matrix
fuzz campaign summary
performance/resource results
migration/backup evidence
audit/review reports and remediation
known limitations/waivers
artifact checksums/SBOM/signing evidence
```

## Emergency fixes

An emergency security release may narrow ordinary process but must still include a regression test, impact analysis, safe migration/compatibility decision, and follow-up review. It cannot silently change persisted bytes.
