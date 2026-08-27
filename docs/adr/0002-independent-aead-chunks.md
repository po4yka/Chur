# ADR-0002: Use Independent AEAD Chunks for Media

- **Status:** Accepted
- **Date:** 2026-08-26
- **Decision owners:** @po4yka
- **Related:** [`../format/OBJECT_CONTAINER_V1.md`](../format/OBJECT_CONTAINER_V1.md)

## Context

Chur must encrypt multi-gigabyte videos and support random seek, partial download, bounded memory, resumable transfer, and localized corruption. One whole-file AEAD or sequential-only stream makes these requirements impractical.

## Decision

Each immutable media stream is divided into independently authenticated XChaCha20-Poly1305 chunks under a per-object derived content key.

Nonce proposal:

```text
random 128-bit prefix per stream revision || u64 big-endian chunk index
```

Canonical AAD binds object, stream, revision, manifest commitment, index, and plaintext length. An authenticated final commit separately proves object completeness and ordered commitment.

## Alternatives considered

### Whole-file AEAD

Rejected: unbounded/large operation, no efficient random access, poor resumability.

### Sequential secretstream only

Rejected for live media store: seeking and independent range retrieval are awkward. It remains useful for some outer export streams.

### Encrypted filesystem abstraction only

Rejected as the primary media format: weaker media semantics and cross-platform catalog integration.

## Consequences

### Positive

- bounded memory;
- random seek;
- independent transfer/repair granularity;
- corruption localization;
- playback before complete full-file scan with authenticated ranges.

### Tradeoffs

- per-chunk tag/framing overhead;
- nonce/journal/state complexity;
- range authentication is not completeness;
- chunk size requires benchmark and format policy.

## Security impact

Affected invariants: SEC-011, SEC-014, SEC-016, SEC-017.

Nonce uniqueness and canonical AAD are mandatory. The API must distinguish `VerifiedRange` from `CompleteVerifiedObject`; a missing final commit cannot be accepted as complete.

## Compatibility impact

Chunk framing, size range, nonce construction, and final commit are versioned format fields. Default chunk-size change does not reinterpret existing containers.

## Validation

- substitution/reorder/truncation vectors;
- nonce-uniqueness property tests;
- random-seek tests across boundaries;
- interrupted import/resume without prefix reuse;
- mobile performance/energy benchmarks.

## Follow-up

- the approved chunk-size range was set by [`0020`](0020-set-the-v1-parser-limits.md) in `format/OBJECT_CONTAINER_V1.md` §16; the default inside that range waits on the benchmark that [`0020`](0020-set-the-v1-parser-limits.md) §Follow-up requires over the candidates of §6 there;
- publish the substitution, reorder, and truncation vectors listed under Validation;
- record the mobile performance and energy benchmarks that justify the chosen default before Gate 2.
