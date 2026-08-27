# ADR-0006: Split FFI into Control and Data Planes

- **Status:** Accepted
- **Date:** 2026-08-26
- **Decision owners:** @po4yka
- **Related:** [`../interop/FFI_CONTRACT.md`](../interop/FFI_CONTRACT.md)

## Context

Generated bindings are convenient for commands and records but copying multi-gigabyte media through `ByteArray`/`NSData` is inefficient, increases plaintext copies, and complicates cancellation/ownership. A handwritten low-level API for every UI record would add unnecessary maintenance.

## Decision

Use two interop planes:

```text
Control plane
  bounded commands, records, states, errors, handles
  generated bindings may be used

Data plane
  stable C ABI, opaque handles, file descriptors/direct buffers, read_at
```

Rust-native APIs remain independent of both adapters.

## Alternatives considered

### UniFFI/Gobley for everything

Rejected for media data path: large copying and generated API/runtime constraints.

### Handwritten C ABI for everything

Viable but excessive boilerplate for evolving control models.

### JNI on Android and direct Swift API on iOS with separate semantics

Rejected: inconsistent ownership and harder common validation.

## Consequences

### Positive

- bounded copies and stable streaming behavior;
- generated-control convenience remains replaceable;
- clear allocator/handle/cancellation contracts;
- common Rust core and data API.

### Tradeoffs

- two adapters/tooling paths;
- ABI/version packaging complexity;
- direct-buffer/file-descriptor platform testing;
- callback and concurrency discipline required.

## Security impact

Session generation invalidates all native handles on lock. Secrets cross FFI only when unavoidable for slot operations. Panics cannot unwind across boundary.

## Compatibility impact

FFI ABI versions separately from persisted formats. Binding generator changes do not change vault bytes.

## Validation

- ABI handshake tests;
- invalid buffer/handle/fd fuzzing;
- lock/cancel races;
- performance/copy-count benchmarks;
- Android/iOS packaging and symbol verification.

## Follow-up

- decide UniFFI or Gobley versus a handwritten C ABI for the control plane; the choice is still in the ADR backlog and blocks the generated-binding tests;
- assign the first FFI ABI version and the handshake constant, versioned independently of the vault formats;
- publish the invalid buffer, handle, and file-descriptor fuzz corpora listed under Validation.
