# Performance and Resource Budgets

> **Status:** Proposed measurement framework; numerical candidates require benchmark baselines

Performance is a security property when unbounded work, memory, lock latency, or FFI copying can expose plaintext or cause denial of service. Budgets never justify skipping authentication or completeness checks.

## 1. Measurement principles

- measure release-like builds on physical Android/iOS baseline and high-end devices;
- record toolchain, OS, device, thermal state, object profile, and sample size;
- report p50/p95/p99 where meaningful;
- separate platform provider/codec time from Rust crypto/storage time;
- track peak plaintext memory separately from total memory;
- compare against pinned baseline in CI/performance lab;
- proposals become gates only after evidence.

## 2. Candidate interactive budgets

| Operation | Initial candidate |
| --- | --- |
| password unlock | 350–750 ms target on baseline; Argon2 memory ≥ approved floor |
| platform unlock after prompt authorization | <250 ms core unwrap/open target |
| thumbnail decrypt/read | p95 <50 ms for warm local storage |
| first private grid content | p95 <500 ms after session open for local catalog |
| random video seek crypto/data-source overhead | p95 <150 ms excluding codec/network |
| panic/explicit lock native invalidation | p95 <100 ms; UI cover immediate |
| ordinary background lock completion | p95 <250 ms |

These are starting hypotheses, not release promises.

## 3. Throughput budgets

Measure:

- photo import MB/s and latency distribution;
- 4K/large video encrypt/decrypt throughput;
- range-read throughput at 256 KiB/1 MiB chunk candidates;
- backup copy throughput without plaintext;
- catalog migration rows/s;
- sync ciphertext upload/download overhead.

Target sustained crypto throughput should exceed the media player/source consumption rate with headroom on the supported baseline.

## 4. Memory budgets

Track:

```text
Argon2 peak memory
Rust session secret/key cache
import buffers
range-reader buffers
image decode surfaces
player buffers
catalog page cache
FFI copies
scratch-file size
```

Requirements:

- multi-gigabyte objects do not scale memory with object size;
- parser allocations obey hard limits;
- plaintext buffer pools are bounded;
- lock releases session-scoped caches;
- low-memory handling cancels safely without committing incomplete state.

## 5. Chunk-size benchmark

Compare at least 256 KiB, 512 KiB, 1 MiB, and selected alternatives for:

- import throughput;
- random seek amplification;
- tag/storage overhead;
- FFI call frequency;
- memory pressure;
- resumable transfer granularity;
- energy/thermal behavior.

Format records selected chunk size within an approved range; v1 default is chosen by ADR.

## 6. Argon2 calibration

Benchmark approved memory/iteration candidates across the minimum supported device set. Do not automatically reduce memory below the security floor to meet latency. Capture OOM/thermal/background behavior and CLI interoperability.

## 7. Storage budgets

Measure overhead from:

- AEAD tags and record framing;
- encrypted manifest/final commit;
- thumbnails/previews/waveforms;
- SQLCipher pages/WAL;
- import/migration temp copies;
- backup manifests;
- optional padding.

Preflight large imports/migrations when temporary space may approach source size.

## 8. Energy

Long video import, backup, integrity scan, and migration measure battery/thermal impact. Work should be cancellable and schedulable, but keys must not remain unlocked in background solely to improve throughput.

## 9. Regression policy

A security-critical performance regression is investigated when it:

- exceeds approved p95 threshold;
- increases peak plaintext memory materially;
- increases lock invalidation latency;
- causes more copies across FFI;
- forces weaker KDF or larger plaintext cache;
- creates repeated retries/partial transactions.

Waivers state owner, reason, affected devices, mitigation, and expiry.

## 10. Benchmark artifacts

Store scripts/configuration and anonymized aggregate results, never private media. Synthetic corpora should include small photos, large photos, long audio, short/long 4K video, random seeks, and pathological metadata within approved limits.
