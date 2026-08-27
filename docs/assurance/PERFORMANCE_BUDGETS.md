# Performance and Resource Budgets

> **Status:** Proposed measurement framework; numerical candidates require benchmark baselines

Performance is a security property when unbounded work, memory, lock latency, or FFI copying can expose plaintext or cause denial of service. Budgets never justify skipping authentication or completeness checks.

## 1. Measurement principles

- measure release-like builds on the physical floor, baseline, and high-end devices named in [ADR-0017](../adr/0017-freeze-the-supported-device-set.md);
- record toolchain, OS, device, thermal state, object profile, and sample size;
- report p50/p95/p99 where meaningful;
- separate platform provider/codec time from Rust crypto/storage time;
- track peak plaintext memory separately from total memory;
- compare against pinned baseline in CI/performance lab;
- proposals become gates only after evidence.

## 2. Candidate interactive budgets

| Operation | Initial candidate |
| --- | --- |
| password unlock | 350–750 ms per Argon2id derivation on the floor device of [ADR-0017](../adr/0017-freeze-the-supported-device-set.md), and [`../security/KEY_SLOTS.md`](../security/KEY_SLOTS.md) §8 runs two candidates per attempt, so the whole-attempt budget is twice that; Argon2 memory ≥ the floor of [`../security/PASSWORD_PROFILE.md`](../security/PASSWORD_PROFILE.md) §4 |
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
- sync ciphertext upload/download overhead;
- collection epoch rewrap in object-key envelopes per second, with an initial p95 completion candidate under 60 s for a 100,000-object collection on the baseline device; under [ADR-0017](../adr/0017-freeze-the-supported-device-set.md) it becomes a gate only when the floor device also meets it. The rewrap is bounded by [`../sync/REVOCATION.md`](../sync/REVOCATION.md) §3.1, and the exposure window it defines ends only when the pass completes, so a regression here is a security regression.

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
- scratch files stay inside the caps of [`../security/PLAINTEXT_LIFECYCLE.md`](../security/PLAINTEXT_LIFECYCLE.md) §5, which are enforced limits and not quantities to measure;
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

Benchmark approved memory/iteration candidates across the device set frozen in [ADR-0017](../adr/0017-freeze-the-supported-device-set.md); the floor device sets the ceiling, and a candidate that fits only the high-end device is not approved. Do not automatically reduce memory below the 64 MiB floor in [`../security/PASSWORD_PROFILE.md`](../security/PASSWORD_PROFILE.md) to meet latency. Capture OOM/thermal/background behavior and CLI interoperability.

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

The two Phase 0 measurements run through `chur-cli`, so an Android device, an iOS device, and a workstation execute the same code path:

```text
chur-cli bench chunk-sizes --object-bytes 16777216 --samples 8
chur-cli bench argon2 --samples 8
```

They are not a benchmark framework and add no dependency. They report p50, p95, and p99 with the sample size, per §1.

## 11. First recorded measurements

These are the first numbers the benchmarks produced. They rank candidates and approve none: §1 requires a release-like build on a device from [ADR-0017](../adr/0017-freeze-the-supported-device-set.md), and the host below is none of them.

- **Host:** Apple silicon workstation, macOS, release profile, Rust 1.97.0.
- **Sample size:** 5 per candidate.
- **Object:** 16 MiB of synthetic plaintext.

Chunk-size candidates, milliseconds, p50 unless stated:

| `chunk_size` | Chunks | Whole-object write | Complete verify | One-byte read | One-byte read p95 |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 65536 | 256 | 33.7 | 41.6 | 0.13 | 0.14 |
| 262144 | 64 | 33.5 | 40.9 | 0.52 | 0.53 |
| 1048576 | 16 | 34.0 | 41.1 | 2.10 | 2.18 |
| 4194304 | 4 | 33.2 | 40.8 | 8.47 | 8.79 |
| 8388608 | 2 | 34.4 | 41.2 | 16.85 | 17.34 |

Two things the table settles. Sequential cost is flat across the whole approved range, so a larger chunk buys nothing for import or for a complete verify. Seek cost is linear in `chunk_size`, because a one-byte read authenticates one whole chunk. The [§6](../format/OBJECT_CONTAINER_V1.md) candidates of the container specification, 256 KiB for photos and derived streams and 1 MiB for video and large audio, sit where seek cost is still under a frame at 60 Hz, and 8 MiB is a ceiling for the parser rather than a candidate for a writer.

Argon2id candidates, milliseconds per derivation, with the whole-attempt cost of two candidates:

| Memory KiB | Iterations | Lanes | p50 | p95 | Attempt p50 |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 65536 | 3 | 1 | 71.6 | 74.6 | 143.2 |
| 65536 | 4 | 1 | 96.1 | 96.9 | 192.2 |
| 65536 | 6 | 1 | 138.2 | 141.6 | 276.4 |
| 131072 | 3 | 1 | 151.1 | 153.8 | 302.2 |
| 131072 | 4 | 1 | 197.5 | 204.3 | 395.0 |
| 262144 | 3 | 1 | 321.5 | 333.8 | 643.1 |
| 524288 | 3 | 1 | 673.5 | 719.9 | 1347.0 |
| 65536 | 3 | 2 | 70.3 | 71.2 | 140.6 |
| 65536 | 3 | 4 | 72.8 | 82.8 | 145.7 |

The frozen floor costs 72 ms per derivation on this host, well under the 350 to 750 ms interactive target, which is the expected shape: the target is set on the floor device of ADR-0017, and a workstation is several times faster. Raising memory is the effective lever and raising lanes is not, which matches Argon2's cost model. Calibration under §6 may therefore raise memory on a device that measures far under the target, and may never lower it. The 524288 KiB parser ceiling already exceeds the target on this host, so it is a bound rather than a candidate.

A measurement on the ADR-0017 device set replaces this section; until then no candidate above the floor is approved.
