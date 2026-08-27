# Chur Development Guide

> **Status:** Proposed development environment and workflow

This document describes the intended development environment and workflow. The repository is still being scaffolded; commands marked **planned** become normative only after the corresponding build files exist.

## Target toolchain

| Area | Target |
| --- | --- |
| JDK | 21, pinned for Gradle builds |
| Kotlin | 2.4.10 / K2 |
| Compose Multiplatform | 1.11.1 |
| Gradle | version pinned by wrapper |
| Android | compile/target API 37; NDK pinned in version catalog |
| Xcode | version supporting the selected iOS deployment target |
| Swift | toolchain shipped with pinned Xcode |
| Rust | exact version pinned by `rust/rust-toolchain.toml`; supported floor in `rust-version` |
| Cargo tools | pinned or locked where reproducibility matters |

Do not rely on globally mutable defaults for JDK, NDK, Rust target, Xcode, or code-generation versions.

Only the Rust row is enforced today. The Gradle build and `gradle/libs.versions.toml` do not exist yet, so the JDK, Kotlin, Compose Multiplatform, Gradle, Android, Xcode, and Swift rows are planned targets; they become normative when the version catalog lands.

## Planned repository layout

```text
apps/
  androidApp/
  iosApp/
shared/
  app/
  core-*/
  feature-*/
rust/
  crates/
  Cargo.toml
build-logic/
  convention/
docs/
test-vectors/
```

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for module ownership.

## Initial setup

1. Install the pinned JDK and set `JAVA_HOME`.
2. Install Android Studio and the pinned SDK/NDK components.
3. Install Xcode and select it with `xcode-select`.
4. Install rustup and the repository-pinned toolchain.
5. Install required Rust targets for Android and iOS.
6. Clone the repository without copying local credentials into it.
7. Verify that generated/native artifact directories are ignored.

The project must eventually provide bootstrap scripts that validate versions instead of silently accepting incompatible tools.

## Planned build commands

Once the project scaffold exists, the following task families should be available:

```text
./gradlew build
./gradlew check
./gradlew :apps:androidApp:assembleDebug
./gradlew :shared:allTests

cargo build --workspace
cargo test --workspace
cargo clippy --workspace --all-targets --all-features
cargo fmt --all --check
```

Exact module paths may change during scaffolding. The workflow named in [`docs/assurance/RELEASE_GATES.md`](docs/assurance/RELEASE_GATES.md#enforcement) becomes the source of truth for supported commands when it lands; until then this list is, and no check is enforced.

## Native targets

The build must support, at minimum:

```text
Android:
  aarch64-linux-android
  x86_64-linux-android for emulator/testing when supported

iOS:
  aarch64-apple-ios
  aarch64-apple-ios-sim
```

Additional ABIs require an explicit support and testing decision. Release artifacts must contain only intended architectures.

## Local development workflow

1. Pull the current default branch.
2. Read the applicable normative documents.
3. Create or update an ADR for a design decision.
4. Implement the smallest coherent change.
5. Add tests before changing stable bytes.
6. Run common, Rust, Android, and iOS checks relevant to the change.
7. Inspect logs and artifacts for private data.
8. Open a focused pull request using `CONTRIBUTING.md`.

## Test data

Use generated synthetic media and deterministic test keys. Never commit:

- real user media;
- production vaults;
- passwords or recovery phrases;
- platform signing identities;
- provisioning profiles;
- Keystore/Keychain exports;
- unredacted crash reports.

Deterministic keys are permitted only inside clearly marked test-vector material.

## Rust workflow

Security-critical crates should expose testable, platform-neutral APIs. Recommended checks include:

```text
unit tests
property tests
known-answer tests
corruption tests
fuzz targets
Miri where applicable
sanitizers for native/FFI paths
cargo-deny or equivalent policy checks
```

`chur-cli` should be able to create, inspect structurally, verify, migrate, and repair synthetic vaults without Android or iOS UI code. It must never print plaintext secrets by default.

## KMP workflow

Common code tests should cover:

- UDF reducers and effect routing;
- session state transitions;
- navigation graph isolation;
- error mapping;
- cancellation and process-restoration policy;
- public/private persistence boundaries.

Platform tests cover key invalidation, file descriptors, media players, backup policy, lifecycle races, and scratch cleanup.

## FFI development

The FFI contract is versioned in [`docs/interop/FFI_CONTRACT.md`](docs/interop/FFI_CONTRACT.md). During development:

- keep control-plane values small and structured;
- use handles and bounded buffers for media;
- define allocator ownership explicitly;
- contain panics inside Rust;
- test lock during every long-running operation;
- verify that stale handles return `SessionExpired`.

## Logging

Development builds may emit additional diagnostics, but private values remain forbidden. Do not log filenames, EXIF, GPS, keys, salts, wrapped keys, passwords, recovery secrets, private search queries, or stable private object identifiers.

Use stable event codes such as:

```text
IMPORT_STARTED
IMPORT_COMMITTED
OBJECT_INTEGRITY_FAILED
SESSION_LOCKED
PLATFORM_KEY_INVALIDATED
```

## Code generation

Generated code must be reproducible from checked-in schemas/configuration. A pull request changing generated output must also change its source and state the generator version. Generated FFI bindings must not become the canonical protocol definition.

## Troubleshooting principles

- fail rather than silently downgrade cryptographic behavior;
- verify exact tool versions before diagnosing compiler/linker errors;
- inspect native architectures and exported symbols;
- reproduce format failures with `chur-cli` and synthetic data;
- preserve ciphertext and journals when investigating crash recovery;
- never request a user's password or recovery secret for debugging.

## Security-sensitive debugging

If temporarily instrumenting key or plaintext paths:

1. use synthetic data;
2. keep instrumentation local and uncommitted;
3. disable crash upload and analytics;
4. clear simulator/emulator/device test storage afterward;
5. verify that no diagnostic artifact entered Git history.
