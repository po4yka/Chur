# ADR-0040: Add a Rust JNI Adapter Crate

- **Status:** Accepted
- **Date:** 2026-08-27
- **Decision owners:** @po4yka
- **Related:** [`0016`](0016-freeze-the-v1-c-abi.md), [`../interop/FFI_CONTRACT.md`](../interop/FFI_CONTRACT.md) §14, [`../DEPENDENCY_POLICY.md`](../DEPENDENCY_POLICY.md) "Unsafe code"

## Context

[`../interop/FFI_CONTRACT.md`](../interop/FFI_CONTRACT.md) §1 has the KMP side reach the C ABI through an `expect`/`actual` adapter, and §14 says "Android loads ABI-specific native libraries through the application shell/JNI adapter". Kotlin/Native reaches a C ABI directly through cinterop, so iOS needs no adapter. Kotlin/JVM on Android cannot: the JVM calls native code through JNI, and JNI requires a native function whose symbol name encodes the Java class and method.

§6.2 forbids such a symbol in the Chur library: "Every exported symbol is `chur_` followed by lower snake case ... Nothing else leaves the artifact". A `Java_dev_po4yka_...` symbol is not in that set, and the version script that enforces the rule would strip it.

So the adapter is a second artifact. The remaining question is what builds it.

[`../DEPENDENCY_POLICY.md`](../DEPENDENCY_POLICY.md) "Unsafe code" makes `chur-ffi` the only crate exempt from `unsafe_code = "forbid"` and says adding a second requires an ADR. This is that ADR.

## Decision

`rust/crates/chur-jni` is a ninth workspace crate. It builds a `cdylib` named `libchur_jni.so`, exports one JNI function per `chur_*` export, and calls `chur-ffi` in process. It is the second and, unless a later ADR says otherwise, last crate with `unsafe_code = "deny"` rather than `forbid`.

The alternative was a C source compiled by the Android Gradle plugin through CMake. Rust was chosen for three reasons, in order of weight:

1. **one toolchain.** The Android build already cross-compiles the Rust static library for four ABIs, so producing a `cdylib` beside it adds a target, not a build system. A CMake path would add a second compiler, a second dependency graph, and a second place where a flag can differ between debug and release;
2. **the same lints.** A Rust adapter inherits the workspace's `panic`, `unwrap_used`, and `expect_used` denials, so a panic inside the adapter is a compile error rather than a JVM abort. C has no equivalent;
3. **no second boundary to review.** The adapter converts JVM types to the C ABI's types and nothing else. In Rust that conversion is checked; in C it is a cast.

The adapter is a translation layer and holds no logic. Every function does exactly three things: read the JVM arguments, call one `chur_*` export, and write the result back. `chur-jni` therefore has no tests of its own beyond the argument conversions, because there is nothing else in it to test: the behaviour is `chur-ffi`'s and is tested there.

## Alternatives considered

### A C source built by CMake in the Android Gradle plugin

Rejected above. It also depends on the reduced DSL of `com.android.kotlin.multiplatform.library` supporting `externalNativeBuild`, which is a constraint on a plugin rather than on this design.

### One JNI entry point that dispatches on an opcode

Rejected. It would replace a checked one-to-one mapping with a second encoding whose vocabulary is defined nowhere, and it would hide which export a call reaches from every tool that reads symbols.

### JNI functions inside `chur-ffi`

Rejected: §6.2 forbids the symbol in that artifact, and the version script that enforces the rule would remove it.

## Consequences

### Positive

- the Android build produces one extra `.so` per ABI from the toolchain it already runs;
- a panic in the adapter is contained by the same guard as a panic in an export, because the adapter calls exports and does nothing else;
- iOS is unaffected: cinterop reaches `chur.h` directly and never loads this library.

### Tradeoffs

- a second crate carries `unsafe`, which is what this ADR exists to record. It is bounded: every block converts a JVM pointer and length into a slice, and each states its invariant;
- the `jni` crate enters the dependency graph for the Android artifacts only. It is pure Rust, has no build script, and reaches no network;
- the adapter must track the export surface. A new export needs a new JNI function, which the ABI minor bump of §6.2 already makes a deliberate change.

## Security impact

Affected invariants: SEC-050, SEC-051.

The adapter adds no cryptography, no key handling, and no persistence. It widens the boundary by one translation, and the redaction rule survives it: every JNI function returns the `int32_t` status the export returned and never a message.

## Compatibility impact

None to any persisted format or to the C ABI. The adapter is additive and is not loaded on iOS.

## Measured

`aarch64-linux-android`, release, NDK 28: `libchur_jni.so` is 6.6 MiB stripped and exports 47 `Java_dev_po4yka_chur_ffi_ChurNative_*` symbols. The size is the whole vault, not the adapter: the shared library statically links `chur-ffi` and everything under it, including the vendored SQLCipher and OpenSSL of [ADR-0038](0038-adopt-sqlcipher-as-the-v1-catalog-engine.md).

## Follow-up

- the release symbol check of §6.2 applies to `libchur_ffi`, not to `libchur_jni`; the adapter's own symbol set is checked against the export list it wraps, by `rust/crates/chur-jni/tests/surface.rs`, in both directions.
