# ADR-0048: Recover a Device from a Portable Identity Envelope

- **Status:** Accepted
- **Date:** 2026-08-29
- **Decision owners:** @po4yka
- **Related:** [`../sync/DEVICE_IDENTITY.md`](../sync/DEVICE_IDENTITY.md), [`../sync/ROLLBACK_PROTECTION.md`](../sync/ROLLBACK_PROTECTION.md), [`../format/BACKUP_FORMAT_V1.md`](../format/BACKUP_FORMAT_V1.md)

## Context

A recovery slot restores `VaultRootSecret`, but the backup format excluded device identity private keys. Restoring content therefore could not authorize a new sync device when every physical identity device was lost. Trusting an account reset or a server-supplied replacement key would make the server a cryptographic authority.

Creating a second permanent recovery signer would add another high-value private key and another membership record. The existing root-derived `IdentityWrapKey` already protects portable protocol identity material.

## Decision

- a portable backup of a sync-enabled vault includes one `DeviceIdentityEnvelopeV1` for the device that creates it;
- the envelope contains the device's Ed25519 signing seed and X25519 private key, sealed under the existing root-derived `IdentityWrapKey` and bound to `vault_id`, `device_id`, identity generation, and both suite identifiers;
- the encrypted catalog snapshot includes the latest checkpoint issued by that device. Restore sets the checkpoint as its freshness floor before it accepts server state;
- a restored identity is recovery-only. It does not author ordinary operations. After reconstructing every chain through the checkpoint floor, it generates a new device identity and uses the recovered active identity to sign the ordinary enrollment record for that new device;
- after the enrollment and a new portable backup commit, the recovered private identity is destroyed. The new device authors future operations under its own identifier;
- if current authenticated membership says the recovered identity is revoked, it cannot enroll a replacement. Another active device must enroll it, or the user restores the media into a new independent sync deployment;
- a backup with no identity envelope still restores local content, but it cannot re-enter the old membership without another active device;
- a stale backup with no surviving peer or witness retains the rollback limitation of `ROLLBACK_PROTECTION.md` §7. The product shows the checkpoint date after unlock and does not claim server freshness.

The envelope uses the existing backup `Envelope` record type. [`DEVICE_IDENTITY.md`](../sync/DEVICE_IDENTITY.md) §6.1 freezes its 153-byte inner encoding; no new outer record type or key-derivation label is added.

## Alternatives considered

### Server or account recovery replaces membership

Rejected. Account authentication controls storage and cannot authorize a decryption identity.

### Root-authenticated membership reset record

Rejected for v1. It adds a second membership authentication scheme and cannot remove rollback after total loss without a trusted checkpoint or witness.

### Keep using the recovered device identity

Rejected. The lost device and restored device could then share one signing key and sequence space, creating accidental forks.

## Consequences

### Positive

- loss of every physical device is recoverable from a current portable backup without server key authority;
- the wire membership chain keeps one authentication mechanism: signed enrollment;
- the recovered key has one bounded task and is removed afterward.

### Tradeoffs

- a portable backup becomes as sensitive as the vault root it already carries and must retain the existing password/recovery protection;
- a stale identity that was later revoked cannot rejoin the old membership;
- recovery requires one additional enrollment and a replacement backup.

## Security impact

Affected invariants: SEC-041, SEC-042, and SEC-055. A checkpoint floor limits rollback, and recovery never lets account authentication replace membership authorization. Copying the old identity into two active authors is prevented by the recovery-only rule.

## Compatibility impact

Local-only v1 backups remain valid and restore local content. A sync-enabled restore requires the new optional envelope; its absence is a recoverable product state, not a parse failure.

## Validation

- restore an active identity, reject state below its checkpoint, and enroll a replacement;
- reject ordinary operation signing by a recovery-only identity;
- reject replacement enrollment when authenticated membership revoked the recovered identity;
- restore a local-only backup with no identity envelope;
- simulate stale server state after all-device loss and verify the rollback warning remains visible.

## Follow-up

- persist the latest own checkpoint in the encrypted catalog before enabling recovery enrollment.
