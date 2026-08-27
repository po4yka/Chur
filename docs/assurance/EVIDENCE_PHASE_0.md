# Phase 0 Evidence

> **Status:** Evidence record for the Gate 0 and Gate 1 items of [`RELEASE_GATES.md`](RELEASE_GATES.md). It records what is true; it approves nothing.

[`RELEASE_GATES.md`](RELEASE_GATES.md) requires every gated release to record its evidence and, explicitly, "which of its items had no enforcing job". This document is that record for Phase 0. The two approvals Phase 0 still owes — the release gates and the review scope — are decisions for the repository maintainer, and this exists so that decision is a reading rather than an investigation.

Regenerate every number below with the commands each row names. Nothing here is transcribed from memory.

## 1. Package

| Item | Value |
| --- | --- |
| Source commit | the commit this file is read at; `git rev-parse HEAD` |
| Canonical encoding profile | `0x0001` |
| Container, descriptor, envelope, slot, backup, catalog versions | `0x0001` each, [`../format/CANONICAL_ENCODING_V1.md`](../format/CANONICAL_ENCODING_V1.md) §15.2 |
| Suite | `0x0001`; `0x0002` allocated for the Android Keystore wrap only |
| FFI ABI | 1.0, capabilities `0x0000000000000000` |
| Vector-set digest | `chur-cli vectors digest --dir ../test-vectors/v1` |
| Rust toolchain | `rust/rust-toolchain.toml`, exact |
| Gradle toolchain | `gradle/libs.versions.toml` and `gradle/wrapper/gradle-wrapper.properties`, with the distribution SHA-256 |
| Dependency locks | `rust/Cargo.lock`; the Gradle build resolves through the version catalog |
| SBOM, checksums, signing | **absent.** No artifact is produced or distributed in Phase 0 |

## 2. Gate 0 — design prototype

| Required item | State | Evidence |
| --- | --- | --- |
| ownership boundaries documented | met | [`../ARCHITECTURE.md`](../ARCHITECTURE.md), [ADR-0001](../adr/0001-rust-owns-private-vault.md), [ADR-0006](../adr/0006-control-and-data-plane-ffi.md) |
| no committed production secrets | met | every key, salt, nonce, password, and recovery secret in the repository is a fixed constant under `test-vectors/`, marked `TEST-ONLY — NEVER USE FOR REAL VAULTS`; no production build can select deterministic randomness |
| major design decisions tracked by ADR | met | 37 ADRs, indexed in [`../adr/README.md`](../adr/README.md) |

Gate 0 is satisfiable on this evidence.

## 3. Gate 1 — local alpha

| Required item | State | Enforcing job |
| --- | --- | --- |
| canonical encoding and v1 draft formats | met | `test` — the codec, the container, both envelopes, the descriptor, and the four slot bodies encode, decode, and round-trip |
| key-slot and password profile | met | `test` — all four families, the frozen Argon2id floor, and the no-normalization rule |
| deterministic positive and negative vectors | met | `vectors` — 62 vectors, 44 accepted and 18 rejected, rebuilt and compared byte for byte |
| Rust unit, property, and corruption tests | **partly met** | `test` — unit and corruption tests run; there is no property-based test framework, and the round-trip properties are asserted per format rather than over generated inputs |
| FFI contract and platform prototype tests | met | `abi`, `gradle`, `kotlin-native` — the C harness links the real static library; the Keystore and Keychain prototypes compile for Android and both iOS targets |
| recovery and process-death flows | **not met** | recovery is implemented to the BIP-39 round trip and the slot; the import journal, the descriptor transaction, and process-death recovery are specified and not implemented, because the catalog is Phase 1 |
| security invariants mapped to tests | met | 19 of 59 rows of [`SECURITY_TEST_PLAN.md`](SECURITY_TEST_PLAN.md) §13 name a running target; every other row names a procedure no job executes, and 6 are audit-only |

Gate 1 is **not** satisfiable yet. Two items above are the reason, and both wait on Phase 1 rather than on a decision.

## 4. Enforcing jobs

`.github/workflows/rust.yml` runs all of these on every pull request and on the default branch. Its file name predates the Gradle build, which [ADR-0031](../adr/0031-continuous-integration-owns-gate-enforcement.md) named.

| Job | What it enforces |
| --- | --- |
| `fmt` | `cargo fmt --all --check` |
| `clippy` | `cargo clippy --workspace --all-targets -- -D warnings` |
| `test` | `cargo test --workspace` |
| `deny` | `cargo deny check`: advisories, licences, bans, sources |
| `vectors` | the vector set rebuilds byte for byte; records the digest |
| `abi` | the C harness links the static library and runs the handshake gate |
| `fuzz` | a deterministic smoke pass over all ten fuzz targets |
| `vendored-skills` | every vendored skill matches its recorded content hash |
| `native-targets` | the four mobile targets build and export the handshake |
| `gradle` | the JVM and Android host tests |
| `kotlin-native` | the iOS simulator tests, the device compile, and the framework link |

## 5. Gate items with no enforcing job

This is the list [`RELEASE_GATES.md`](RELEASE_GATES.md) requires by name.

- **the six audit-only invariants** of [`SECURITY_TEST_PLAN.md`](SECURITY_TEST_PLAN.md) §13: SEC-019, SEC-032, SEC-037, SEC-046, SEC-054, and the claim half of SEC-045;
- **the forty invariant rows** of that section that still name a procedure of the plan rather than a test target;
- **every Gate 2 and later item**, none of which has a subject yet: the media pipeline, the decoy vault, backup, sync, and sharing;
- **the scheduled, release-candidate, and external fuzz cadences** of [`FUZZING.md`](FUZZING.md) §10. Only the per-pull-request smoke pass runs;
- **performance budgets.** [`PERFORMANCE_BUDGETS.md`](PERFORMANCE_BUDGETS.md) §11 records a first measurement on a workstation. No budget is a gate, because §1 requires a device from [ADR-0017](../adr/0017-freeze-the-supported-device-set.md) and none has been measured;
- **migration evidence.** [`MIGRATION_POLICY.md`](MIGRATION_POLICY.md) has ten version domains and v1 is the only version of each, so the harness proves that every domain fails closed on an unknown version and nothing more;
- **backup evidence.** [`../format/BACKUP_FORMAT_V1.md`](../format/BACKUP_FORMAT_V1.md) is specified and not implemented;
- **SBOM, artifact checksums, and signing.** No artifact is produced;
- **export classification.** [`RELEASE_GATES.md`](RELEASE_GATES.md) blocks Gate 3 on it, not Gate 1.

## 6. Fuzz campaign summary

Ten targets, listed in [`FUZZING.md`](FUZZING.md) §2. The `fuzz` job runs each for 20000 executions or 30 seconds, whichever comes first, on every pull request. No crash, no timeout, and no leak has been recorded. No long campaign has run, so the absence of a finding is weak evidence and is not offered as strong evidence.

## 7. Known limitations

- **six crates are documentation only.** `chur-catalog`, `chur-media`, and `chur-sync-protocol` hold no code, and `chur-core`, `chur-crypto`, and `chur-format` hold no session, no import transaction, and no catalog. There is no vault to open;
- **`chur_capabilities` returns zero.** No data-plane surface exists, so a host may call nothing behind it;
- **the vector suite crosses platforms at the index level.** Android, iOS, and the CLI read one `manifest.json` and one fixture set. Decoding a private record on a platform is out of scope: [`../format/CANONICAL_ENCODING_V1.md`](../format/CANONICAL_ENCODING_V1.md) §13 reserves that for Rust;
- **the Argon2id and chunk-size candidates are ranked, not approved.** See §5;
- **`computedHash` in `skills-lock.json` covers a skill's `SKILL.md` and not its `references/` files.** `contentHash` beside it covers the whole directory and is what the `vendored-skills` job checks;
- **no independent review has run.** [`SECURITY_REVIEW_SCOPE.md`](SECURITY_REVIEW_SCOPE.md) defines one and none has been commissioned.

## 8. What the two approvals are

Both are the repository maintainer's, and neither is blocked on work:

1. **Release gates approved.** [`RELEASE_GATES.md`](RELEASE_GATES.md) defines Gates 0 to 6 and their blocking-finding policy. Approval means accepting that definition as the release contract. §5 above is the honest cost of accepting it today.
2. **Review scope approved.** [`SECURITY_REVIEW_SCOPE.md`](SECURITY_REVIEW_SCOPE.md) defines the objectives, the code and specification scope, the phases, the finding format, and the deliverables of the independent review. Approval means accepting that scope before a reviewer is engaged.

Recording either approval is an edit to this section naming the approver and the date. Neither approval makes Gate 1 declarable: §3 blocks that on the two Phase 1 items.
