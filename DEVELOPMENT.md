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

Every row except Xcode and Swift is enforced today by the workflow of [`docs/assurance/RELEASE_GATES.md`](docs/assurance/RELEASE_GATES.md#enforcement). The Xcode and Swift rows stay planned until an iOS application target exists; the Kotlin/Native iOS targets already build and test in that workflow. The version catalog is [`gradle/libs.versions.toml`](gradle/libs.versions.toml) and it pins the JDK, Kotlin, Compose Multiplatform, Gradle, and Android rows exactly. The Gradle wrapper records the distribution SHA-256, so the pinned Gradle version is the one that runs. The Xcode and Swift rows stay planned until an iOS application target exists.

## Repository layout

Modules marked *planned* do not exist yet and land with the phase that needs them.

```text
apps/                planned
  androidApp/        planned
  iosApp/            planned
shared/
  app/               planned
  core-model/        error taxonomy and vector contract
  core-platform-keys/ Android Keystore and Apple Keychain slots
  feature-*/         planned
rust/
  crates/
  fuzz/
  Cargo.toml
build-logic/         planned
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

## Build commands

The workflow named in [`docs/assurance/RELEASE_GATES.md`](docs/assurance/RELEASE_GATES.md#enforcement) is the source of truth. It runs exactly these, and each is runnable locally:

```text
./gradlew jvmTest
./gradlew testAndroidHostTest
./gradlew iosSimulatorArm64Test
./gradlew compileKotlinIosArm64

cargo fmt --all --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
cargo deny check
cargo run -p chur-cli -- vectors verify --dir ../test-vectors/v1
cargo +nightly fuzz run <target> -- -runs=20000
```

The `cargo` commands run in `rust/`; the `./gradlew` commands run at the repository root. The C ABI harness is built and run by the `abi` job and locally with:

```text
cargo build -p chur-ffi --release
cc -Wall -Wextra -Werror -I crates/chur-ffi/include \
   crates/chur-ffi/tests/handshake.c target/release/libchur_ffi.a -o handshake
./handshake
```

An application module, and with it a `./gradlew build` that assembles one, lands with the Phase 1 shell. Exact module paths may still change while the feature modules are added.

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
- verify that stale handles return `SESSION_EXPIRED`.

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
