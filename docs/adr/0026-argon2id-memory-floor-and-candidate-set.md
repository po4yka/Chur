# ADR-0026: Argon2id Memory Floor and the Constant Password-Candidate Set

- **Status:** Accepted
- **Date:** 2026-08-27
- **Decision owners:** @po4yka
- **Related:** [`../security/PASSWORD_PROFILE.md`](../security/PASSWORD_PROFILE.md), [`../security/KEY_SLOTS.md`](../security/KEY_SLOTS.md), [`0017`](0017-freeze-the-supported-device-set.md), [`../security/DECOY_VAULT.md`](../security/DECOY_VAULT.md)

## Context

`PASSWORD_PROFILE.md` §6 asked for memory at or above the approved minimum "when possible" and for never reducing below the floor, which cannot both hold on a device that cannot allocate it; no document said whether such a device refuses, fails, or writes a weaker slot, and the "64 MiB floor in `PASSWORD_PROFILE.md`" that `PERFORMANCE_BUDGETS.md` §6 and [`0017`](0017-freeze-the-supported-device-set.md) both gate on was never written there as a number. Separately, `CRYPTOGRAPHY.md` §23 offered "a fixed candidate set after one derivation", which per-slot random salts make impossible: Argon2id output is salt-bound, so N candidate slots cost N derivations and unlock latency counts the identities present, the first signal `DECOY_VAULT.md` §5 lists.

## Decision

- the v1 Argon2id floor is 65536 KiB of memory, 3 iterations, parallelism 1, a 16-byte random salt, and 32 bytes of output, and the floor is also the v1 default for a newly created slot. Calibration may raise memory or iterations inside the `CRYPTOGRAPHY.md` §18.3 bounds and may never lower a parameter;
- v1 defines no reduced-memory profile. A device that cannot allocate the floor fails closed with the new `KDF_MEMORY_UNAVAILABLE`, value 107, on creation and on unlock, and never writes or accepts a below-floor slot;
- an unlock attempt that uses a password runs exactly two Argon2id derivations, one at a time, whatever the device holds. v1 provisions at most two password-unlockable identities per device, a vault and its optional decoy, and at most one `PasswordSlotV1` identity per descriptor; a shorter list is padded with dummy derivations over a fresh random salt under the first candidate's parameters;
- the allocation is checked once, before the first candidate, so a failure never leaves a partial candidate set attempted.

## Alternatives considered

- **A named reduced-memory profile for low-memory devices.** Rejected: the same password must derive the same key on every supported device, so a second profile needs its own identifier in every slot and hands an attacker who can induce memory pressure a weaker slot to attack. [`0017`](0017-freeze-the-supported-device-set.md) already makes a device below the 3 GB RAM floor unsupported rather than degraded.
- **One salt shared across candidate slots, so one derivation serves all.** Rejected: a shared salt cryptographically links the real and decoy slots, which is what SEC-036 forbids.
- **Attempt only the slots that exist.** Rejected: that is today's behavior, and it makes unlock latency a counter of the identities present.

## Consequences

### Positive

- `PERFORMANCE_BUDGETS.md` §6 and [`0017`](0017-freeze-the-supported-device-set.md) gate on a floor that now exists as a number, and unlock cost is a constant of the procedure rather than a function of what the device holds.

### Tradeoffs

- every password unlock pays two derivations, so the whole-attempt budget is twice the per-derivation budget, and a device that cannot allocate the floor is refused rather than served a weaker vault.

## Security impact

Affected invariants: SEC-007, SEC-036. The floor bounds offline guessing and the constant count removes the identity-count timing signal. The residual signals stay in `DECOY_VAULT.md` §5: slot Argon2id parameters are public descriptor bytes, so every identity on one device must be provisioned with one profile.

## Compatibility impact

No password slot bytes exist yet. `KDF_MEMORY_UNAVAILABLE` is an append-only addition to `ERROR_MODEL.md`; every existing value is unchanged.

## Validation

- Argon2id vectors at the floor and at one calibrated setting above it;
- creation and unlock under an induced allocation failure, both returning 107 with no slot written and no candidate run;
- an unlock cost measurement showing that one identity and two identities cost the same two derivations.

## Follow-up

- record the calibration run of `PERFORMANCE_BUDGETS.md` §6 on the floor device of [`0017`](0017-freeze-the-supported-device-set.md).
