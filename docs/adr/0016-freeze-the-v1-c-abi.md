# ADR-0016: Freeze the v1 C ABI

- **Status:** Accepted
- **Date:** 2026-08-27
- **Decision owners:** @po4yka
- **Related:** [`../interop/FFI_CONTRACT.md`](../interop/FFI_CONTRACT.md), [`../ERROR_MODEL.md`](../ERROR_MODEL.md), [`0006`](0006-control-and-data-plane-ffi.md), [`0031`](0031-continuous-integration-owns-gate-enforcement.md)

## Context

`FFI_CONTRACT.md` named three exports, sketched five more under "Conceptual API:", and left the handle representation, the capability bits, the status type, and the binding generator open. Four documents defined four error vocabularies and none assigned a number, so SEC-051, "unknown native error codes map to a safe generic failure", could not be implemented against anything. `ANDROID.md` §28.3 and `IOS.md` §30.5 both required five facts to be verified before a vault opens, and four of them had no accessor. Neither platform team could start, and `chur-ffi` had no signature to write.

## Decision

Freeze the v1 boundary:

- `chur_status_t` is `int32_t`, `0` is success, defined values are positive, and `ERROR_MODEL.md` is the sole registry with domain blocks of 100, reserved ranges, and append-only allocation;
- `chur_handle_t` is `uint64_t`: a 32-bit registry slot index plus a 32-bit per-slot generation, so a value is never reissued and a stale value cannot alias a live handle;
- every export is `chur_` followed by lower snake case, and nothing else is exported, enforced by a version script on Android and an exported-symbols list on Apple; a handle operation takes the shape `chur_<subject>_<verb>`, and each handshake accessor of `FFI_CONTRACT.md` §2 is named `chur_<fact>` for the value it returns;
- the Phase-1 export list in `FFI_CONTRACT.md` §6.2 is complete: adding an export raises the minor version, changing or removing one raises the major version;
- the handshake exports the version pair, the object-format range, the key-slot range, the build flavor, and a `uint64_t` capability bitmask, so all five gate facts are retrievable before a vault opens;
- v1 has no foreign callbacks: progress is polled from the operation handle;
- the FFI artifacts build with `panic = "unwind"`, and every export wraps its body in `catch_unwind` and returns `INTERNAL_FAILURE`;
- the control plane uses the same C ABI through a KMP `expect`/`actual` adapter. No UniFFI, no Gobley, no generated boundary. `chur.h`, checked in with the first `chur-ffi` export, is the deliverable.

## Alternatives considered

### A generated control plane (UniFFI or Gobley)

Rejected. The surface is about twenty-five coarse functions and the data plane cannot use generated types at all, so a generator would buy little while adding a runtime dependency, a second version handshake with its own checksum failure mode, and a second definition of the error enum.

### `panic = "abort"` for the cdylib and staticlib

Rejected. Abort converts a contained, redactable failure into a process kill that skips session zeroization and removes the public shell along with the vault, and it makes the `catch_unwind` the contract requires inert.

### Callbacks for progress

Rejected for v1. Callbacks need a delivery-thread contract, a re-entrancy rule, and a release race against a disappearing consumer; polling removes all three, and no v1 requirement needs sub-poll-interval latency. Callbacks remain addable behind a capability bit.

## Consequences

### Positive

- `chur-ffi`, the Android JNI adapter, and the iOS resource loader can all be written against one header;
- SEC-050 and SEC-051 become testable: panic injection at an export and an unknown status value both have defined outcomes;
- the handshake gate in both platform documents is implementable as written.

### Tradeoffs

- hand-written bindings on both platforms must be kept in step with `chur.h`. The ABI version check of `FFI_CONTRACT.md` §2 detects drift at run time, and a header-diff job joins the workflow of [`0031`](0031-continuous-integration-owns-gate-enforcement.md) when the header lands;
- polling costs one extra call per progress tick compared with a callback.

## Security impact

Affected invariants: SEC-050, SEC-051. Panic containment is now unqualified at every export rather than "where applicable", and the unknown-status rule has a numeric space to be unknown against. Removing foreign callbacks removes every path on which Rust runs foreign code while holding a secret lock.

## Compatibility impact

No shipped binary exists, so nothing migrates. The major ABI version is the compatibility unit, and no vault byte is affected because no error value or handle is persisted.

## Validation

- a symbol-table check that the artifact exports exactly the frozen set;
- panic injection at every export, asserting `INTERNAL_FAILURE` and no unwind;
- an unknown status value and an unknown capability bit, asserting fail-closed behavior;
- double close, closed-handle reuse, and a fabricated handle value;
- Android and iOS built against the same `chur.h`.

## Follow-up

- publish the checked-in `chur.h` and the header check that keeps both hand-written binding sets in step with it; neither exists yet, and both land with the first `chur-ffi` implementation;
- allocate the value of the first `(major, minor)` ABI version pair, which `interop/FFI_CONTRACT.md` §2 exports and no document sets;
- the exact `ChurQueryV1` and page encoding of [`0028`](0028-freeze-the-catalog-query-surface.md) enter the frozen export list with the first `chur-catalog` implementation.
