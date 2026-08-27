# ADR-0037: Contain Panics in Exports That Have No Status Channel

- **Status:** Accepted
- **Date:** 2026-08-27
- **Decision owners:** @po4yka
- **Related:** [`../interop/FFI_CONTRACT.md`](../interop/FFI_CONTRACT.md), [`0016`](0016-freeze-the-v1-c-abi.md), [`../ERROR_MODEL.md`](../ERROR_MODEL.md)

## Context

`FFI_CONTRACT.md` §11 is unconditional: "Every exported symbol wraps its whole body in `catch_unwind` and converts a caught panic into `INTERNAL_FAILURE`. This is unconditional: every export, no 'where applicable' exemption."

The first exports to land were the §2 handshake, and they cannot obey the second half of that sentence. They return `uint32_t`, `uint64_t`, `uint16_t`, and `bool`; none has a `chur_status_t` channel, and §2 states that they "cannot fail". A rule that cannot be followed is followed by nobody: the handshake shipped with no `catch_unwind` at all, which is the outcome the "no exemption" wording exists to prevent.

Two further requirements of §11 also need a place to live. The panic payload must be dropped inside the boundary, and the default Rust panic hook prints that payload to standard error, where a host's log collector reads it. A payload can hold a value a caller passed in.

## Decision

- Every export wraps its body in `catch_unwind`, with no exemption. An export that returns `chur_status_t` converts a caught panic into `INTERNAL_FAILURE`, as §11 already says.
- An export with no status channel returns a fallback the host already refuses. The values are frozen:

  | Export | Fallback | Why the host refuses it |
  | --- | --- | --- |
  | `chur_abi_version_major` | `0` | not this ABI; the host reports `ABI_INCOMPATIBLE` |
  | `chur_abi_version_minor` | `0` | reported with the major value above |
  | `chur_object_format_min` | `0xFFFF` | with the maximum below, an inclusive range holding no version |
  | `chur_object_format_max` | `0` | as above |
  | `chur_key_slot_format_min` | `0xFFFF` | as above |
  | `chur_key_slot_format_max` | `0` | as above |
  | `chur_capabilities` | `0` | no capability is offered, so the host calls behind none |
  | `chur_build_flavor` | `0` | neither the release nor the debug bit, which is not a build a host accepts |
  | `chur_status_is_known` | `false` | an unrecognized code, which already fails closed |

- A redacting panic hook is installed once, on the first guarded call. It prints a fixed marker and the source location and never the payload. The location is a path inside this repository and carries no private value.
- The hook and the guards live in one module, so the first status-returning export cannot land without them.

## Alternatives considered

### Give the handshake exports a status channel

Rejected. It would change eight frozen signatures, and [ADR-0016](0016-freeze-the-v1-c-abi.md) froze them for a reason: a platform gate calls them before runtime initialization and needs a value, not an out-parameter it must allocate first.

### Let the handshake exports panic

Rejected. A panic across a `extern "C"` boundary is undefined behavior, and the whole point of `panic = "unwind"` over abort is that a contained failure stays contained.

### Return the correct value and log the panic

Rejected as impossible: a body that panicked produced no value. Something must be invented, and the only safe invention is one the host rejects.

### Abort on panic

Rejected by [ADR-0016](0016-freeze-the-v1-c-abi.md) already: abort converts a redactable failure into a process kill that skips session zeroization and removes the public shell along with the vault.

## Consequences

### Positive

- §11 applies to every export with no exemption, which is what it says;
- a panicking library fails the platform gate instead of reporting a version it did not compute;
- no panic payload reaches a host log.

### Tradeoffs

- the hook is process-wide. No other Rust runs in a Chur host process, and the alternative is the default hook printing what §11 forbids;
- nine fallback values are now ABI. A future export with no status channel allocates its own here.

## Security impact

Affected invariants: SEC-050.

No invariant changes. SEC-050 requires that Rust panics cannot unwind across FFI, and it had no implementation to point at. It now has one, plus panic injection over every guard and a test that a payload carrying a caller-supplied value does not reach the return value.

## Compatibility impact

No persisted or wire bytes change. Nine ABI values are allocated; none was previously defined, and each is a value a conforming host already refuses.

## Validation

- panic injection through each guard asserts the fallback for every return type;
- a test panics with a caller-supplied string and asserts it reaches neither the return value nor the caller;
- the C harness asserts the live values differ from the fallbacks, so a contained panic is visible from the host side;
- the header harness asserts the nine values agree between `chur.h` and the Rust side.
