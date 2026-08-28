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
  app/               Compose Multiplatform shell, currently the ABI gate
  core-model/        error taxonomy and vector contract
  core-platform-keys/ Android Keystore and Apple Keychain slots
  feature-*/         planned
rust/
  crates/
  fuzz/
  Cargo.toml
scripts/             native cross-compilation and vendored-skill checks
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
./gradlew linkDebugFrameworkIosSimulatorArm64

scripts/build-native-targets.sh all
python3 scripts/check-vendored-skills.py
python3 scripts/check-backup-rules.py

cargo fmt --all --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
cargo deny check
cargo run -p chur-cli -- vectors verify --dir ../test-vectors/v1
cargo run -p chur-cli -- vectors digest --dir ../test-vectors/v1
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

The build supports these four, and the `native-targets` job of the enforcing workflow builds every one on every pull request:

```text
Android:
  aarch64-linux-android
  x86_64-linux-android for emulator and testing

iOS:
  aarch64-apple-ios
  aarch64-apple-ios-sim
```

```text
scripts/build-native-targets.sh android
scripts/build-native-targets.sh apple
scripts/build-native-targets.sh all
```

The script builds `chur-ffi` for each target and then checks that the archive exports the nine handshake symbols of [`docs/interop/FFI_CONTRACT.md`](docs/interop/FFI_CONTRACT.md) §2. An archive with no `chur_` symbol is one a host loads and then fails to call, so the symbol check is the point rather than an extra.

Android needs an NDK. The script reads `ANDROID_NDK_HOME`, then `ANDROID_NDK_ROOT`, then the newest NDK under `ANDROID_HOME/ndk`, and passes the linker to Cargo through a per-target environment variable, so no machine-specific path enters a checked-in `.cargo/config.toml`. The API level is 29, the floor [ADR-0017](docs/adr/0017-freeze-the-supported-device-set.md) freezes. The Apple targets need Xcode and build on macOS only.

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

## Driving a vault from the command line

`chur-cli vault` is the whole Phase 1 flow without a device
([`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) §9). A password reaches the
process through `CHUR_PASSWORD` or `--password-file`, never through an
argument: an argument is in `/proc`, in the shell history, and in `ps` output
for every user on the machine.

```sh
export CHUR_PASSWORD='...'
chur-cli vault --root ./v create --recovery   # prints the phrase once
chur-cli vault --root ./v import photo.jpg --content-type image/jpeg
chur-cli vault --root ./v list
chur-cli vault --root ./v show <object>
chur-cli vault --root ./v export <object> out.jpg
chur-cli vault --root ./v verify
CHUR_RECOVERY_PHRASE='...' chur-cli vault --root ./v recover
```

Every command but `create` and `status` unlocks first, and an unlock runs the
journal reconciliation of
[`docs/format/OBJECT_CONTAINER_V1.md`](docs/format/OBJECT_CONTAINER_V1.md) §14.4
and the garbage collection of
[`docs/format/CATALOG_SCHEMA_V1.md`](docs/format/CATALOG_SCHEMA_V1.md) §14.1,
exactly as a session on a device does.

`chur-cli backup` is the portable package of
[`docs/format/BACKUP_FORMAT_V1.md`](docs/format/BACKUP_FORMAT_V1.md), and the
same implementation both hosts call:

```sh
chur-cli backup --root ./v create vault.churbak
chur-cli backup inspect vault.churbak          # no credential, public bytes only
chur-cli backup --root ./restored restore vault.churbak
```

`restore` does not unlock the destination first. A restore installs an identity
rather than operating one, and §8 there obtains the credential from the
package's own portable descriptor, so restoring into an empty root is the
ordinary case. `inspect` reads the 32-byte public preamble and stops: §10
requires decrypted metadata to appear only after authentication, so it prints
the record count and the length and nothing a credential would have unsealed.

## Measuring

Four benchmarks run through the same binary Android and iOS build, so the
device measurement [`docs/assurance/PERFORMANCE_BUDGETS.md`](docs/assurance/PERFORMANCE_BUDGETS.md)
§1 requires needs a device and no new code:

```sh
chur-cli bench chunk-sizes --object-bytes 16777216 --samples 8
chur-cli bench argon2 --samples 8
chur-cli bench random-seek --object-bytes 16777216 --samples 32
chur-cli bench lock-invalidation --samples 8
```

Each one closes by stating what its numbers do and do not settle. That is a
convention rather than decoration: §1 of the budgets forbids treating a host
number as a gate.

## Native catalog build

`chur-catalog` compiles vendored SQLCipher and OpenSSL through `rusqlite`
([ADR-0038](docs/adr/0038-adopt-sqlcipher-as-the-v1-catalog-engine.md)). Two
environment facts change whether that build succeeds.

- **A compiler cache breaks the OpenSSL build.** The `cc` crate treats a
  `RUSTC_WRAPPER` naming `sccache` as a C compiler wrapper as well, and
  `openssl-sys` probes its headers with `cc -E`, which `sccache` refuses.
  Setting `CC` explicitly, for example `CC=/usr/bin/cc cargo test`, keeps the
  wrapper off the probe. The Rust side still caches.
- **Android needs an NDK and Apple needs Xcode**, exactly as
  [Native targets](#native-targets) already requires, and now for every build of
  the catalog crate rather than only for the mobile artifacts.
- **The Android build needs the NDK's binutils on `PATH`.** The vendored
  OpenSSL configures itself for Android and then invokes `${CROSS_COMPILE}ranlib`
  through `make`, which resolves on `PATH` rather than through an environment
  variable. `scripts/build-native-targets.sh` sets it; a hand-run `cargo build`
  for an Android target needs it too, or the build fails at `install_dev` with
  "ranlib: command not found".
- **The Apple build needs `IPHONEOS_DEPLOYMENT_TARGET` pinned** to the version
  [ADR-0017](docs/adr/0017-freeze-the-supported-device-set.md) supports. The
  vendored C is compiled against the installed SDK, whose objects reference
  symbols Rust's default iOS 10 link target does not provide, and the link fails
  on `___chkstk_darwin`. The script and the Gradle tasks both set it.

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
