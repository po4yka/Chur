# ADR-0011: Freeze Vault-Descriptor Authentication

- **Status:** Accepted
- **Date:** 2026-08-27
- **Decision owners:** @po4yka
- **Related:** [`../format/VAULT_DESCRIPTOR_V1.md`](../format/VAULT_DESCRIPTOR_V1.md), [`../security/KEY_SLOTS.md`](../security/KEY_SLOTS.md), [`../security/KEY_HIERARCHY.md`](../security/KEY_HIERARCHY.md), [`0005`](0005-real-and-decoy-vault-isolation.md)

## Context

`CRYPTOGRAPHY.md` §18.4 removes the stored password verifier and makes authenticated vault-descriptor validation the only test of a correct credential, but `VAULT_DESCRIPTOR_V1.md` §8 declined to say what that validation is and deferred the choice between an AEAD extension and a keyed authenticator to vectors that do not exist. Unlock is the first user-visible feature of Phase 1 and cannot be written without the construction, and `KEY_SLOTS.md` §8 requires that an invalid credential and a credential valid for the sibling identity fail identically, which is a property of this check's timing and error shape. No HKDF label for a descriptor-authentication key appeared in any label list.

## Decision

Authenticate the descriptor with a keyed BLAKE3-256 tag over its wire bytes:

- `DescriptorAuthKey` is HKDF-SHA-256 from `VaultRootSecret` under the new label `chur/v1/root/descriptor-auth`, scoped to `vault_id`, 32 bytes, stable across descriptor generations;
- `descriptor_authentication` is the last 32 bytes of the encoded descriptor, and the body is every preceding byte;
- the authenticated input is the fixed domain tag `CHUR\x00VAULT\x00DESCRIPTOR-AUTH\x00V1` followed by that body, so magic, versions, `vault_id`, `descriptor_generation`, `state`, both store descriptors, every slot descriptor with its framing, and the optional migration descriptor are bound by one rule with no field-order ambiguity, as ADR-0008 did for the ordered chunk commitment of `format/OBJECT_CONTAINER_V1.md` §10;
- there is no AAD and no nonce, because the construction encrypts nothing;
- the comparison is constant time, and a mismatch zeroizes the candidate root and returns `AUTHENTICATION_FAILED`, never `VAULT_CORRUPT`;
- a failed slot unwrap still performs the derivation and tag computation over a random substitute root, so the cost of a failure does not depend on which step failed.

## Alternatives considered

### AEAD over an encrypted private descriptor extension

Rejected. `VAULT_DESCRIPTOR_V1.md` §3 keeps no private payload in the descriptor, so the extension would exist only to carry a tag, and it would add a per-generation nonce to the record that is rewritten on every state change and slot edit.

### XChaCha20-Poly1305 with empty plaintext and the body as AAD

Rejected. It authenticates equally well but keeps the same nonce lifecycle, and a nonce reused under this long-lived key leaks the Poly1305 key and allows descriptor forgery. A deterministic authenticator carries no such state.

### Restore a stored password verifier

Rejected. A verifier is an offline oracle that confirms a guessed password without touching vault data, and it separates a wrong password from a wrong vault, which is the distinction `KEY_SLOTS.md` §8 forbids.

## Consequences

### Positive

- the unlock path is implementable, and its vectors are deterministic;
- one rule covers every descriptor field, including fields a later encoding profile adds;
- the most frequently rewritten record gains no nonce-reuse failure mode.

### Tradeoffs

- a 32-byte trailer where a Poly1305 tag would be 16 bytes;
- keyed BLAKE3 becomes a second use of a primitive `CRYPTOGRAPHY.md` §6 still lists as proposed for ordered commitments only;
- the equal-work rule spends one derivation and one hash on every failed unlock.

## Security impact

Affected invariants: SEC-002, SEC-005, SEC-035, SEC-038.

This tag is the only thing separating a correct credential from a structurally valid wrong one, so its failure shape is the vault's authentication oracle. Binding the wire bytes means a substituted catalog descriptor, an edited state field, or a rolled-back slot set fails as a credential error instead of opening a session on attacker-chosen state. Freshness is not proven: an older authentic generation is caught by the generation rules, not here.

## Compatibility impact

No descriptors exist yet, so nothing migrates. Tag length, domain tag, and label are frozen for `descriptor_version` 1; a change requires a new version and a dual-reader policy.

## Validation

- byte-exact vectors for a minimal descriptor and a maximum-slot descriptor;
- negative vectors for a flipped body byte, a truncated tag, and a valid tag computed under a sibling vault's root;
- an equal-work test that a failed slot unwrap and a failed tag comparison return the same code after the same recorded steps.

## Follow-up

- the descriptor field encoding and offsets were frozen by [`0019`](0019-freeze-remaining-v1-record-layouts.md) in `format/VAULT_DESCRIPTOR_V1.md` §2 and §5 to §7; the descriptor magic `CHURVLT1` is allocated by [`0013`](0013-allocate-v1-format-constants.md) in `format/CANONICAL_ENCODING_V1.md` §15.1;
- `CRYPTOGRAPHY.md` §74 item 15, real/decoy candidate discovery, which this ADR constrains but does not define, was resolved by [`0026`](0026-argon2id-memory-floor-and-candidate-set.md) in `security/KEY_SLOTS.md` §8.
