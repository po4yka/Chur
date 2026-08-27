# Rust–KMP FFI Contract

> **Status:** Proposed normative interop contract

The FFI boundary exposes coarse-grained vault operations without making Kotlin, Swift, JNI, Objective-C, UniFFI, or Gobley part of the private storage protocol.

## 1. Layers

```text
chur-core / crypto / format / catalog / media
    ↓ Rust-native APIs
chur-ffi   one stable C ABI, one process-global handle registry
    ├──────────────────────────────┐
    ↓                              ↓
KMP expect/actual adapter      platform shell data-plane adapter
    ↓                              (iOS AVAssetResourceLoader in v1)
features and platform shells
```

The handle registry is process-global and language-agnostic. A handle created through the control plane in one language is usable from the other, and lock invalidates it for both in one step. A platform shell adapter may call the data plane directly when this avoids repeated copies, but it never creates or owns a session: sessions are created and closed through the shared application layer, and exactly one Rust runtime exists in the process (§14).

Bindings are replaceable. The secure core has no dependency on binding-language types.

## 2. ABI versioning

The native library exports a handshake that answers every fact a platform gate checks before a vault opens. These functions are callable from any thread before runtime initialization and cannot fail:

```text
chur_abi_version_major()   -> uint32_t
chur_abi_version_minor()   -> uint32_t
chur_capabilities()        -> uint64_t
chur_object_format_min()   -> uint16_t
chur_object_format_max()   -> uint16_t
chur_key_slot_format_min() -> uint16_t
chur_key_slot_format_max() -> uint16_t
chur_build_flavor()        -> uint32_t
```

- native API version is the (major, minor) pair. A different major value fails loading, reports `ABI_INCOMPATIBLE`, and the library is not called again in that process;
- the object-format range is the inclusive `container_version` interval this build reads, using the values registered in [`../format/CANONICAL_ENCODING_V1.md`](../format/CANONICAL_ENCODING_V1.md) §15;
- the key-slot range is the inclusive key-slot format interval;
- build flavor is a bitfield: bit 0 set means a release build, bit 1 set means debug assertions are compiled in, bit 2 set means test hooks are compiled in. A release application refuses a library with bit 1 or bit 2 set;
- required feature flags are capability bits.

`chur_capabilities()` returns a bitmask:

| Bit | Name | Meaning |
| ---: | --- | --- |
| 0 | `CHUR_CAP_DECOY_VAULT` | independent decoy identity supported |
| 1 | `CHUR_CAP_OBJECT_READER` | random-access authenticated reader available |
| 2 | `CHUR_CAP_SEQUENTIAL_READER` | sequential reader available |
| 3 | `CHUR_CAP_INTEGRITY_SCAN` | background integrity scan available |
| 4 | `CHUR_CAP_BACKUP_PACKAGE` | portable backup package import/export available |
| 5 | `CHUR_CAP_SYNC` | ciphertext sync available |
| 6 | `CHUR_CAP_CONCURRENT_READS` | one reader handle serves parallel reads (§8) |
| 7-63 | reserved | zero in v1 |

An unknown set bit is ignored and never enables behavior. Minor and capability differences are negotiated only within explicitly compatible behavior; they never select cryptographic suites from untrusted input.

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

- `chur_handle_t` is `uint64_t`: the low 32 bits index a typed registry slot, the high 32 bits carry that slot's generation counter. It is never a raw pointer and never a business ID; `0` is the null handle;
- explicit owner runtime/session;
- thread affinity and concurrency fixed per handle type by the table in §8, not per instance;
- close is idempotent for every handle type without exception: the first close releases the resources, and every later close of the same value returns success and does nothing. Close never returns `NOT_FOUND` or `SESSION_EXPIRED`; closing a value this process never issued returns `INVALID_INPUT`, which the generation counter makes distinguishable from a re-close;
- a handle value is never reissued: the generation counter of a slot increments on every allocation, so a stale value cannot alias a live handle for the life of the process;
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

### 6.2 Exported symbols

Every exported symbol is `chur_` followed by lower snake case, in the shape `chur_<subject>_<verb>`. Nothing else leaves the artifact: the Android link step applies a version script, the Apple link step an exported-symbols list, and a release check fails on any symbol outside this set. `chur_handle_t` is `uint64_t` with `0` as the null handle, and `chur_status_t` is the `int32_t` of [`../ERROR_MODEL.md`](../ERROR_MODEL.md).

The Phase-1 surface is frozen. Adding an export raises the minor ABI version; changing or removing one raises the major version. The checked-in `chur.h` is the deliverable both platform teams build against, and every binding derives from it.

```c
/* handshake: any thread, before initialization, cannot fail (§2) */
uint32_t chur_abi_version_major(void);
uint32_t chur_abi_version_minor(void);
uint64_t chur_capabilities(void);
uint16_t chur_object_format_min(void);
uint16_t chur_object_format_max(void);
uint16_t chur_key_slot_format_min(void);
uint16_t chur_key_slot_format_max(void);
uint32_t chur_build_flavor(void);

/* runtime and session */
chur_status_t chur_runtime_open(const ChurRuntimeConfigV1 *config, chur_handle_t *out_runtime);
chur_status_t chur_runtime_close(chur_handle_t runtime);
chur_status_t chur_vault_unlock(chur_handle_t runtime, const ChurUnlockRequestV1 *request,
                                chur_handle_t *out_session);
chur_status_t chur_vault_lock(chur_handle_t session, uint32_t reason);
chur_status_t chur_session_close(chur_handle_t session);

/* catalog queries: a bounded projection written into a caller buffer */
chur_status_t chur_catalog_query(chur_handle_t session, const ChurQueryV1 *query,
                                 uint8_t *destination, size_t capacity, size_t *bytes_written);

/* operations */
chur_status_t chur_import_begin(chur_handle_t session, int32_t source_fd,
                                const ChurImportRequestV1 *request, chur_handle_t *out_import);
chur_status_t chur_export_begin(chur_handle_t session, const ChurObjectRefV1 *object,
                                int32_t destination_fd, chur_handle_t *out_export);
chur_status_t chur_integrity_scan_begin(chur_handle_t session, const ChurScanRequestV1 *request,
                                        chur_handle_t *out_scan);
chur_status_t chur_operation_poll(chur_handle_t operation, ChurProgressV1 *out_progress);
chur_status_t chur_operation_cancel(chur_handle_t operation);
chur_status_t chur_operation_close(chur_handle_t operation);

/* object reader */
chur_status_t chur_object_reader_open(chur_handle_t session, const ChurObjectRefV1 *object,
                                      uint32_t stream_kind, chur_handle_t *out_reader);
chur_status_t chur_object_reader_size(chur_handle_t reader, uint64_t *out_size);
chur_status_t chur_object_reader_content_info(chur_handle_t reader, ChurContentInfoV1 *out_info);
chur_status_t chur_object_reader_read_at(chur_handle_t reader, uint64_t offset, uint8_t *destination,
                                         size_t capacity, size_t *bytes_written);
chur_status_t chur_object_reader_verify_complete(chur_handle_t reader, uint32_t *out_state);
chur_status_t chur_object_reader_close(chur_handle_t reader);
```

The control plane uses these same symbols through a thin KMP `expect`/`actual` adapter. No binding generator is part of the boundary ([ADR-0016](../adr/0016-freeze-the-v1-c-abi.md)).

### 6.3 Range reads

`chur_object_reader_read_at` never mixes an error with a byte count: the status is the return value, the count is written through `bytes_written`.

- `bytes_written` is set on every call, including every failure, where it is set to `0`;
- on success `*bytes_written <= capacity`. A short read is permitted at any offset, not only near the end: the reader returns at most the authenticated bytes it already holds, so the caller must loop until it has the range it needs or observes `*bytes_written == 0`;
- `*bytes_written == 0` with a success status means end of authenticated plaintext, and occurs only when `offset == size`;
- `offset == size` returns success with `0` bytes;
- `offset > size` returns `INVALID_INPUT`, never a zero-length success, so a seek past the end stays distinguishable from end of stream;
- `capacity == 0` returns success with `0` bytes and touches nothing;
- on any failure status the whole destination buffer holds unspecified bytes. The caller must not use any prefix of it, and must not treat bytes written by an earlier successful call into the same buffer as still valid;
- `size` is the authenticated plaintext size from the final commit record, not a file length.

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

Thread affinity is a property of the handle type, not of the creating thread. No handle is bound to the thread that created it:

| Handle | Callable from | Concurrent calls on one handle |
| --- | --- | --- |
| `RuntimeHandle` | any thread | serialized inside Rust |
| `VaultSessionHandle` | any thread | serialized per session |
| `ObjectReaderHandle` | any thread, explicitly including a thread other than its creator | serialized per reader in v1; parallel only when `CHUR_CAP_CONCURRENT_READS` is set, which requires benchmarks and correctness tests first |
| `ImportHandle` | any thread | one call at a time; a second concurrent call returns `CONFLICT` |
| `ExportHandle` | any thread | one call at a time; a second concurrent call returns `CONFLICT` |
| `IntegrityScanHandle` | any thread | one call at a time; a second concurrent call returns `CONFLICT` |

`chur_operation_cancel` and every `*_close` are exempt: they are callable from any thread at any time, including while another call on the same handle is in flight, and they never wait on that call. The registry lock is therefore per slot and is never held across user work, so a Media3 loader thread and an `AVAssetResourceLoader` queue may both drive a reader they did not create.

## 9. Cancellation

Long operations accept a cancellation handle/token or expose cancel functions. Lock cancellation has higher priority than ordinary caller cancellation.

Cancellation guarantees:

- no new plaintext after cancellation observed;
- partial ciphertext remains temp/journaled, not active;
- no progress snapshot advances after the terminal flag is set;
- exactly one terminal result;
- cancellation maps to `CANCELLED`, not corruption.

## 10. Progress reporting

v1 has no foreign callbacks. Rust never calls Kotlin, Swift, or Objective-C code, so there is no delivery thread, no re-entrancy rule, and no consumer-disappearance race to specify. The caller polls its own operation handle:

```text
chur_operation_poll(operation, out_progress) -> chur_status_t
```

- polling is synchronous and cheap: it takes the per-slot lock only long enough to copy a snapshot, and never waits on the operation;
- the caller polls on its own dispatcher or queue, at a rate it chooses, and republishes to the UI on the platform's main thread. The delivery thread is therefore the caller's, by construction;
- `ChurProgressV1` contains only bounded non-private numbers: operation kind, encrypted or plain bytes processed when safe, total bytes if known, stage code, terminal flag, and the terminal status;
- once the terminal flag is set the snapshot is frozen; every later poll returns the same terminal result until the handle is closed, so exactly one terminal result is observable;
- polling a stale-generation handle returns `SESSION_EXPIRED` rather than a partial snapshot;
- no filename, path, album, object ID, or real/decoy identity appears in progress.

A callback data plane would need a delivery-thread contract, a re-entrancy rule, and a release race against a disappearing consumer. Adding callbacks later is a minor-version addition behind a capability bit.

## 11. Errors

Every exported function that can fail returns `chur_status_t`, the `int32_t` status registered in [`../ERROR_MODEL.md`](../ERROR_MODEL.md), which owns every error name and value. `0` is success. Results never share the status channel: a byte count, a handle, or a projection is written through an out-parameter. Error strings are diagnostic-only and redacted, and this contract adds no code of its own.

An unrecognized value maps to `INTERNAL_FAILURE`.

The FFI artifacts build with `panic = "unwind"`; abort is not used. Every exported symbol wraps its whole body in `catch_unwind` and converts a caught panic into `INTERNAL_FAILURE`. This is unconditional: every export, no "where applicable" exemption, verified by panic injection at each symbol. The panic payload is dropped inside the boundary and no payload text crosses it, the handle that owned the call is invalidated so a later call on it also fails, and a panic hook records a synthetic-reproduction diagnostic with no private values. Abort is rejected because it converts a contained, redactable failure into a process kill that skips session zeroization and removes the public shell along with the vault ([ADR-0016](../adr/0016-freeze-the-v1-c-abi.md)).

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
- poll after the terminal result, after close, and after lock;
- file descriptor closed early/non-seekable;
- cancellation at every stage;
- no secret values in errors/logs;
- Android/iOS byte-equivalent behavior.
