# ADR-0045: Freeze the v1 Device Membership Records

- **Status:** Accepted
- **Date:** 2026-08-29
- **Decision owners:** @po4yka
- **Related:** [`../sync/DEVICE_IDENTITY.md`](../sync/DEVICE_IDENTITY.md), [`../sync/ROLLBACK_PROTECTION.md`](../sync/ROLLBACK_PROTECTION.md), [`0023`](0023-define-signed-checkpoint-and-bootstrap-attestation.md), [`0024`](0024-freeze-revocation-point-and-eager-rewrap.md)

## Context

The accepted membership and checkpoint designs named their fields but did not freeze enrollment widths, suite values, signature inputs, membership commitments, or checkpoint commitments. Implementations could therefore sign different bytes or accept a generation without an authenticated predecessor.

## Decision

- enrollment and revocation use the fixed-width records in `DEVICE_IDENTITY.md` §4 and §9;
- signing and HPKE suite `0x0001` select the only Phase 3 profiles, and capability bit 0 selects sync v1;
- record-specific domain tags separate enrollment and revocation signatures;
- membership generation starts at 1 and advances by exactly one; each complete signed record commits to its predecessor through `previous_membership_commitment` and the membership-chain hash;
- generation-1 self-enrollment is the only membership record allowed to carry zero predecessor and checkpoint commitments;
- every later enrollment carries the commitment of the issuer's current signed checkpoint;
- revocation pins a non-zero sequence and digest and cannot be issued by the device it removes;
- the checkpoint commitment covers the complete signed canonical checkpoint under its own domain tag.

## Consequences

The parser, state machine, vectors, and recovery flow now share one byte contract. Adding a suite, capability, optional field, or recovery-authorized issuer requires a new protocol version instead of an ambiguous v1 extension.

## Validation

- exact enrollment, revocation, membership-chain, and checkpoint vectors;
- wrong domain, issuer, generation, predecessor, suite, capability, and signature failures;
- generation-1 self-enrollment and later-device bootstrap;
- revocation at, below, and above its pinned device head.
