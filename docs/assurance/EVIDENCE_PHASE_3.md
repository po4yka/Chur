# Phase 3 Evidence

> **Status:** Evidence record for the Phase 3 scope and exit criteria of [`../../ROADMAP.md`](../../ROADMAP.md). It records what is true; it approves nothing.

[`RELEASE_GATES.md`](RELEASE_GATES.md) requires an evidence package and a list of items with no enforcing job. This document records both for encrypted synchronization. Regenerate each result with the command in the same row.

## 1. Package

| Item | Value |
| --- | --- |
| Source commit | the commit this file is read at; `git rev-parse HEAD` |
| Sync protocol | v1 canonical records and HTTP API under `/v1` |
| FFI ABI | 1.4, capabilities `0x000000000000003F`; `CHUR_CAP_SYNC` is bit 5 and concurrent reads remain clear |
| Catalog | schema v2 stores membership, operation heads, checkpoints, rotations, and locked staging metadata |
| Vector-set digest | `70ca21ccb9beca9828108e8c26563dced68be8984552fd43de1ca07574fd6e92`; 94 vectors and two fixtures |
| Rust dependencies | exact `ed25519-dalek` 3.0.0, `x25519-dalek` 3.0.0, `axum` 0.8.9, and `tokio` 1.53.1 pins |
| Mobile transport | exact Ktor Client 3.5.2 pin, with OkHttp on Android and Darwin on iOS |
| Dependency review | [`../DEPENDENCY_POLICY.md`](../DEPENDENCY_POLICY.md) records purpose, features, trust boundary, license, maintenance, MSRV, and unsafe footprint |
| Deployment | reference self-hosted service only; the project operates no sync service |

## 2. What runs

| Check | Observed result | Command |
| --- | --- | --- |
| Rust workspace | 523 tests listed; complete suite passed with no failure | `cd rust && cargo test --locked --workspace` |
| Rust formatting | clean | `cd rust && cargo fmt --all -- --check` |
| Rust lints | clean at `-D warnings` | `cd rust && cargo clippy --locked --workspace --all-targets -- -D warnings` |
| Rust MSRV | workspace check passed on 1.85 | `cd rust && cargo +1.85 check --locked --workspace` |
| Dependency policy | advisories, bans, licenses, and sources passed; permitted duplicate-version warnings remain visible | `cd rust && cargo deny check` |
| Protocol vectors | 94 vectors and two fixtures rebuilt and compared byte for byte | `cd rust && cargo run --locked -p chur-cli -- vectors verify --dir ../test-vectors/v1` |
| Mobile workspace | full build passed; 531 tasks were evaluated | `./gradlew --no-daemon --no-configuration-cache -Pkotlin.incremental=false build -x lint` |
| Repository policy | two backup-rule files passed; 46 locked skills had no problem | `python3 scripts/check-backup-rules.py && python3 scripts/check-vendored-skills.py` |

## 3. Phase 3 scope

| Item | Evidence |
| --- | --- |
| self-controlled opaque server | `chur-sync-server` stores SQLite control state and ciphertext files, provides a runnable Axum service, and is documented by [`../sync/SERVER_OPERATOR.md`](../sync/SERVER_OPERATOR.md) |
| ciphertext transport | `:shared:core-sync` covers every reference route with bounded raw-byte requests; `LockedSyncPullerTest` proves that locked pulls stage bytes and advance no accepted cursor |
| device identity and recovery | `chur-sync-protocol::identity`, `chur-catalog::sync_keys`, and `chur-media` `tests/backup_flow.rs` separate Ed25519/X25519 keys, persist the root-wrapped portable identity, and restore its recovery form |
| authenticated logs and checkpoints | `chur-sync-protocol::operation_log` and `checkpoint`, `chur-catalog::sync_log`, and `chur-sync-server::relay` implement canonical signing, durable heads, checkpoint floors, paging, and idempotent relay |
| malicious server resistance | `chur-sync-protocol` `tests/malicious_server.rs` rejects replay conflicts, omission against a checkpoint, key substitution, rollback, and equivocation |
| convergence and materialization | `tests/convergence.rs`, `tests/materialization.rs`, and `chur-catalog` `tests/sync_receive.rs` cover causal maxima, observed-remove sets, deterministic presentation, atomic projection, and restart replay |
| tombstones and garbage collection | `materialization::MaterializedState::gc_candidates`, durable checkpoint coverage, and catalog deletion tests prevent collection before authenticated causal and retention conditions hold |
| revocation and rotation | signed membership revocation pins the final device head; the server revokes its transport token; catalog and protocol rotation tests cover epoch advance, bounded rewrap, restart, takeover, and completion |
| native and mobile boundary | ABI 1.4 exposes locked stage and unlocked process operations through C, JNI, Android, and iOS; `sync_surface.rs` proves the locked-to-unlocked path |
| reference HTTP interoperability | `chur-sync-server` `tests/http_api.rs` exercises bootstrap, transport authentication, canonical relay, and health; `tests/protocol_vectors.rs` consumes published sync vectors |

## 4. Exit criteria

| Criterion | State |
| --- | --- |
| server trust model and sync protocol finalized | met. The accepted documents define the untrusted server, canonical byte contracts, limits, client verification, locked behavior, signed deletion, and observable metadata |
| malicious-server test harness operational | met. It runs in the Rust workspace and covers all five adversarial behaviors named by the roadmap |
| protocol vectors published | met. The committed set has accepted and rejected sync records, is regenerated by the CLI, and is consumed by the reference server test |
| independent review of identity, log, and rollback design | **outstanding.** [`SECURITY_REVIEW_SCOPE.md`](SECURITY_REVIEW_SCOPE.md) defines the work, but no independent reviewer has been commissioned |

## 5. What has no enforcing job

- **protocol-focused independent review.** This is the remaining Phase 3 exit criterion and cannot be supplied by the implementation team;
- **two physical devices.** No job enrolls, revokes, recovers, and converges two supported phones through a deployed server;
- **operating-system background execution.** The locked transfer primitive builds and is tested with a mock transport, but no Android scheduler or iOS background session runs on a device;
- **an operator deployment.** No job tests reverse-proxy TLS, persistent-volume backup and restore, process supervision, disk exhaustion, or disaster recovery;
- **global omission detection.** A checkpoint can prove rollback relative to known authenticated state, but an untrusted server can keep devices in separate consistent views without a trusted witness. [`../sync/SERVER_TRUST_MODEL.md`](../sync/SERVER_TRUST_MODEL.md) §5 states this limit;
- **production approval.** Passing this package does not approve Gate 5 while independent review and the earlier release-gate gaps remain open.

## 6. Known limitations

- The repository provides the protocol, native state machine, transport, locked pull primitive, and reference server. It does not provide a user-facing server-enrollment screen or an OS background scheduler.
- The reference server terminates plain HTTP and expects a production operator to place authenticated TLS in front of it. Plain HTTP clients are accepted only for loopback development.
- Collection sharing, recipient grants, member revocation, and multi-recipient interoperability remain Phase 4 scope.
- A server delete receipt is not proof of physical erasure. Client-side crypto-erasure remains subject to copies already held by devices and backups.
