# Rust–KMP FFI Contract

> **Status:** Proposed normative interop contract

The FFI boundary exposes coarse-grained vault operations without making Kotlin, Swift, JNI, Objective-C, UniFFI, or Gobley part of the private storage protocol.

## 1. Layers

```text
chur-core / crypto / format / catalog / media
    ↓ Rust-native APIs
chur-ffi
    ├── stable C ABI data plane
    └── optional generated control plane
        ↓
KMP expect/actual adapter
        ↓
features and platform shells
```

Generated bindings are replaceable. The secure core has no dependency on generated-language types.

## 2. ABI versioning

The native library exports an ABI handshake:

```text
chur_abi_version_major()
chur_abi_version_minor()
chur_capabilities()
```

Major mismatch fails loading. Minor/capability differences are negotiated only within explicitly compatible behavior; they never select cryptographic suites from untrusted input.

## 3. Handles

Opaque handles represent:

```text
RuntimeHandle
VaultSessionHandle
ObjectReaderHandle
ImportHandle
ExportHandle
IntegrityScanHandle
```

Requirements:

- random or generation-tagged opaque values, not raw pointers exposed as business IDs;
- explicit owner runtime/session;
- thread-safety documented per handle type;
- idempotent close where practical;
- stale generation returns `SESSION_EXPIRED`;
- no handle revives after lock;
- handle registry bounded against leaks/DoS.

## 4. Session generation

Every opened vault session receives a monotonically changing in-process generation. Handles capture it. Locking:

1. marks session cancelling;
2. increments/invalidates generation;
3. zeroizes session secrets in place;
4. closes catalog;
5. cancels operations;
6. makes every old handle fail.

UI cleanup is not required for native invalidation.

## 5. Control plane

Suitable values:

- commands and bounded query parameters;
- opaque references;
- small projections;
- stable error codes;
- progress summaries;
- migration/integrity states;
- capability flags.

Control records must not contain keys, decrypted manifests, private paths, or arbitrarily large media bytes.

## 6. Data plane

Large data uses:

- platform file descriptors/seekable handles when safe;
- caller-provided direct/native buffers;
- `read_at(offset, destination)`;
- bounded sequential import/export;
- explicit byte counts;
- no whole-file `ByteArray`, `NSData`, or generated-binding list.

Conceptual API:

```text
object_reader_open(session, object_ref, stream_kind) -> reader
object_reader_size(reader) -> u64
object_reader_read_at(reader, offset, ptr, capacity) -> bytes_written
object_reader_verify_complete(reader) -> integrity_state
object_reader_close(reader)
```

## 7. Buffer ownership

Each function specifies:

- allocating side;
- writable/readable range;
- alignment;
- maximum capacity;
- whether bytes remain valid after return;
- whether zeroization is required;
- whether concurrent reuse is allowed.

Default data-plane policy: caller allocates a bounded mutable buffer, Rust writes authenticated plaintext, validity ends when caller reuses/frees it. Rust never retains the pointer after return.

## 8. Threads and blocking

Native FFI calls are synchronous unless explicitly callback-based. KMP wraps blocking work on a dedicated I/O dispatcher. Rust may use internal workers but must not call arbitrary Kotlin/Swift code while holding secret locks.

A handle documents whether concurrent operations are serialized or rejected. Readers may support independent concurrent reads only after benchmarks and correctness tests.

## 9. Cancellation

Long operations accept a cancellation handle/token or expose cancel functions. Lock cancellation has higher priority than ordinary caller cancellation.

Cancellation guarantees:

- no new plaintext after cancellation observed;
- partial ciphertext remains temp/journaled, not active;
- callbacks cease after terminal completion;
- exactly one terminal result;
- cancellation maps to `CANCELLED`, not corruption.

## 10. Progress callbacks

Progress contains only bounded non-private numbers:

```text
operation kind
encrypted/plain bytes processed when safe
total bytes if known
stage code
terminal flag
```

No filename, path, album, object ID, or real/decoy identity.

Callbacks must not block Rust critical sections and must tolerate consumer disappearance.

## 11. Errors

Native result uses stable numeric category plus bounded safe metadata. Error strings are diagnostic-only and redacted. See [`../ERROR_MODEL.md`](../ERROR_MODEL.md).

Unknown codes map to `INTERNAL_FAILURE`; panics are contained with `catch_unwind` at safe boundaries where applicable and never unwind through foreign code.

## 12. Secrets across FFI

Allowed only when unavoidable for a key-slot operation:

- bounded mutable byte buffers;
- exact length validation;
- no string conversion;
- no JSON/serialization;
- immediate best-effort clearing on foreign side;
- Rust secret wrapper on receipt;
- no callback echo.

Object/collection/root keys never return to application feature code.

## 13. File descriptor ownership

For each import/export call define whether Rust duplicates or consumes the descriptor. Preferred:

- platform opens descriptor;
- adapter passes it with explicit ownership flag;
- Rust duplicates when needed for asynchronous lifetime;
- original closes deterministically;
- non-seekable capability communicated explicitly;
- no integer descriptor persisted after operation.

## 14. Packaging

Android loads ABI-specific native libraries through the application shell/JNI adapter. iOS links one Rust static library/XCFramework instance. Duplicate Rust runtimes in one process are forbidden unless an ADR proves safety.

## 15. Tests

- ABI mismatch and unknown capabilities;
- invalid/null/misaligned/oversized buffers;
- double close and leaked handle cleanup;
- lock during read/import/export/verify/migrate;
- panic injection;
- callback disappearance/reentrancy;
- file descriptor closed early/non-seekable;
- cancellation at every stage;
- no secret values in errors/logs;
- Android/iOS byte-equivalent behavior.
