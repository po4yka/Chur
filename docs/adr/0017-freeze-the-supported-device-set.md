# ADR-0017: Freeze the Supported Device Set

- **Status:** Accepted
- **Date:** 2026-08-27
- **Decision owners:** @po4yka
- **Related:** [`../assurance/PERFORMANCE_BUDGETS.md`](../assurance/PERFORMANCE_BUDGETS.md), [`../security/PASSWORD_PROFILE.md`](../security/PASSWORD_PROFILE.md), [`../ANDROID.md`](../ANDROID.md), [`../IOS.md`](../IOS.md)

## Context

`PERFORMANCE_BUDGETS.md` §6 required Argon2id candidates to be benchmarked "across the minimum supported device set" and §1 to measure on "baseline and high-end devices". No document named a device, a RAM floor, or an API level. `ANDROID.md` recorded "API 23, subject to a final support ADR" and `IOS.md` "exact deployment target requires an ADR", and neither ADR existed. The Argon2id memory parameter, the chunk-size choice, and every latency budget therefore had no measurable target, and the Phase 2 exit criterion "random seek and lock invalidation meet budgets" could not be evaluated.

## Decision

Freeze the support matrix:

- Android `minSdk` is 29 and `targetSdk` stays 37;
- the iOS deployment target is 18.0, with iOS 26 remaining the design baseline;
- the RAM floor is 3 GB; a device below it is unsupported rather than degraded;
- the benchmark set, against which every frozen constant is measured, is:

| Role | Android | iOS |
| --- | --- | --- |
| floor | 4 GB, arm64, API 29 (Pixel 3a class) | iPhone XR class, 3 GB, iOS 18 |
| baseline | 6 GB, arm64, current Android with a 16 KiB page size (Pixel 6a class) | iPhone SE 3rd generation or iPhone 13 class |
| high end | current Pixel with StrongBox | current iPhone Pro |

- a candidate constant is approved only when it meets its budget on the floor device; a result from the baseline or high-end device alone does not approve it;
- Argon2id memory candidates are benchmarked at and above the 64 MiB floor in `PASSWORD_PROFILE.md`, and the floor device decides the ceiling.

## Alternatives considered

### Keep API 23 for reach

Rejected. API 23 to 28 needs a second storage model, a second biometric path, and a Keystore without StrongBox, which triples the platform matrix for devices that cannot run the Argon2id floor comfortably anyway.

### Set the deployment target to the iOS 26 design baseline

Rejected. It would drop several device generations for no API the implementation uses; iOS 18 already provides every Data Protection, PhotosPicker, and Swift 6 concurrency API in the design.

### Benchmark on the high-end device and scale down

Rejected. Argon2id memory and seek latency do not scale predictably across thermal envelopes, and a scaled estimate cannot detect the low-memory kill that the floor device actually suffers.

## Consequences

### Positive

- the Argon2id profile, the chunk size, and the latency budgets acquire a pass or fail criterion;
- the Phase 2 budget exit criteria become measurable rather than blocked;
- the Android storage model has one behaviour, because scoped storage is mandatory from API 29.

### Tradeoffs

- devices below API 29, below iOS 18, or below 3 GB of RAM are excluded;
- the CI performance lab needs five physical devices rather than two.

## Security impact

Affected invariants: SEC-007.

The KDF cost parameter is a security parameter set by measurement. Naming the floor device prevents the usual failure where the parameter is calibrated on a fast device and then quietly reduced in the field to keep unlock latency acceptable, which weakens the password profile for exactly the users on the weakest hardware.

## Compatibility impact

No vault byte changes. `minSdk` and the deployment target are packaging metadata, and the vault format stays identical across every supported device and the CLI.

## Validation

- the Argon2id calibration run recorded on all five devices, including thermal state and any low-memory kill;
- chunk-size candidates compared on the floor device for seek amplification and import throughput;
- unlock, first-grid, and lock-invalidation budgets measured on the floor device before any value becomes a gate.

## Follow-up

- record the Argon2id calibration run of `assurance/PERFORMANCE_BUDGETS.md` §6 on the five devices above, which [`0026`](0026-argon2id-memory-floor-and-candidate-set.md) also requires;
- compare the chunk-size candidates of `format/OBJECT_CONTAINER_V1.md` §6 on the floor device, which [`0020`](0020-set-the-v1-parser-limits.md) leaves open.
