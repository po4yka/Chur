# Phase 4 Evidence

> **Status:** Evidence record for the Phase 4 scope and exit criteria of [`../../ROADMAP.md`](../../ROADMAP.md). It records what is true; it approves nothing.

[`RELEASE_GATES.md`](RELEASE_GATES.md) requires an evidence package and a list of items with no enforcing job. This document records both for collection sharing. Regenerate each result with the command in the same row.

## 1. Package

| Item | Value |
| --- | --- |
| Source commit | the commit this file is read at; `git rev-parse HEAD` |
| Sharing protocol | v1 canonical collection grant, membership, and operation records; grants are 309 bytes and membership records are 292 bytes |
| Cryptography | X25519 HPKE with HKDF-SHA-256 and ChaCha20-Poly1305; Ed25519 signatures bind the complete canonical records |
| FFI ABI | 1.9, capabilities `0x00000000000000BF`; `CHUR_CAP_COLLECTION_SHARING` is bit 7 and concurrent reads remain clear |
| Catalog | schema v4 stores recipient pins, membership history, grants, shared operation heads, and resumable rewrap state |
| Vector-set digest | `14029a3e8b1e7c60cbba1550818b2e879d4ab6655f53fff908bcf7e42fc5413e`; 99 vectors and two fixtures, including five sharing vectors |
| Rust dependencies | exact `hpke` 0.14.0, `ed25519-dalek` 3.0.0, and `x25519-dalek` 3.0.0 pins |
| Mobile transport | exact Ktor Client 3.5.2 pin, with OkHttp on Android and Darwin on iOS |
| Dependency review | [`../DEPENDENCY_POLICY.md`](../DEPENDENCY_POLICY.md) records purpose, features, trust boundary, license, maintenance, MSRV, and unsafe footprint |
| Deployment | reference self-hosted service only; the project operates no sharing service |

## 2. What runs

| Check | Observed result | Command |
| --- | --- | --- |
| Rust workspace | complete suite passed with no failure | `cd rust && cargo test --locked --workspace` |
| Rust formatting | clean | `cd rust && cargo fmt --all -- --check` |
| Rust lints | clean at `-D warnings` | `cd rust && cargo clippy --locked --workspace --all-targets -- -D warnings` |
| Rust MSRV | workspace check passed on 1.85 | `cd rust && cargo +1.85 check --locked --workspace` |
| Dependency policy | advisories, bans, licenses, and sources passed; permitted duplicate-version warnings remain visible | `cd rust && cargo deny check` |
| Protocol vectors | 99 vectors and two fixtures rebuilt and compared byte for byte | `cd rust && cargo run --locked -p chur-cli -- vectors verify --dir ../test-vectors/v1` |
| Mobile workspace | full build passed; 535 tasks were evaluated | `./gradlew --no-daemon --no-configuration-cache --no-build-cache --refresh-dependencies -Pkotlin.incremental=false build -x lint` |
| Repository policy | two backup-rule files passed; 46 locked skills had no problem | `python3 scripts/check-backup-rules.py && python3 scripts/check-vendored-skills.py` |

## 3. Phase 4 scope

| Item | Evidence |
| --- | --- |
| recipient verification | [`../sync/COLLECTION_MEMBERSHIP.md`](../sync/COLLECTION_MEMBERSHIP.md) defines explicit fingerprint verification; `collection_membership` and catalog sharing tests pin recipient signing and HPKE keys, reject substitution, and require an explicit verified replacement |
| authenticated grants | [`../sync/COLLECTION_GRANTS.md`](../sync/COLLECTION_GRANTS.md), `chur-sync-protocol::grant`, and the sharing vectors fix the HPKE suite, full-record Ed25519 signature, context, additional authenticated data, and 309-byte encoding |
| permissions and membership | canonical cumulative `READ`, `CONTRIBUTE`, and `MANAGE_MEMBERS` profiles fail closed; signed membership records authorize grants and shared operations against the current recipient-device state |
| epochs, rewrap, and revocation | [`../sync/REVOCATION.md`](../sync/REVOCATION.md), `chur-catalog::sharing_service`, and rotation tests advance the epoch before replacement grants, resume bounded rewrap work, omit the revoked device, and reject stale grants |
| recovery and device loss | `sharing_service::multi_recipient_device_loss_rotates_forward_and_replays` covers two recipient vaults, multiple recipient devices, loss of one device, forward rotation, retained object access, and idempotent retry |
| durable recipient state | catalog schema v4 and the sharing, sharing-log, sync-log, and recovery tests restore pins, membership, grants, heads, epochs, and rewrap progress after reopen |
| native and mobile boundary | ABI 1.9 prepares, accepts, and revokes shares through panic-contained C and JNI exports; Android and iOS use the same bounded record codecs and Kotlin surface |
| reference HTTP interoperability | `chur-sync-server` relays bounded issuer evidence and ready-to-accept packages; `SharingPusher` publishes dependencies before grants and `SharingPuller` accepts opaque packages in order |

## 4. Exit criteria

| Criterion | State |
| --- | --- |
| separate sharing-protocol audit | **outstanding.** [`SECURITY_REVIEW_SCOPE.md`](SECURITY_REVIEW_SCOPE.md) defines the engagement, but no independent reviewer has been commissioned |
| clear forward-only revocation guarantees | met. Revocation removes the recipient device from the current membership, advances the collection epoch, rewraps retained object keys, and issues replacement grants only to current devices; old ciphertext and plaintext already obtained remain outside this guarantee |
| recovery and device-loss behavior tested | met in deterministic protocol, catalog, FFI, server, and mobile tests; no job has repeated the flow on physical supported devices |
| no claim that a previously authorized recipient can be forced to delete plaintext | met. The protocol and product text state that recipients can retain key material, ciphertext, or plaintext obtained while authorized |

## 5. What has no enforcing job

- **independent sharing-protocol audit.** This is the remaining Phase 4 exit criterion and cannot be supplied by the implementation team;
- **physical multi-device interoperability.** No job verifies sender and recipient flows across supported Android and iOS devices through a deployed service;
- **operating-system background execution.** The transport primitives build and are tested, but no Android scheduler or iOS background session runs on a device;
- **an operator deployment.** No job tests reverse-proxy TLS, persistent-volume backup and restore, process supervision, disk exhaustion, or disaster recovery;
- **recipient erasure.** No protocol can prove deletion of plaintext, keys, screenshots, exports, or backups already controlled by a recipient;
- **production approval.** Passing this package does not approve Gate 6 while the separate audit and earlier release-gate gaps remain open.

## 6. Known limitations

- The repository provides canonical records, native durable state, bounded mobile transport orchestration, and a reference server. It does not provide a user-facing sharing screen or an operating-system background scheduler.
- The reference server terminates plain HTTP and expects a production operator to place authenticated TLS in front of it. Plain HTTP clients are accepted only for loopback development.
- Revocation is forward-only. It protects new collection epochs and does not retract data that an authorized recipient already received.
- A malicious server can omit current records or keep devices in separate consistent views until clients compare authenticated state through another channel.
