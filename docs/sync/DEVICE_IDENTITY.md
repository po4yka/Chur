# Device Identity

> **Status:** Proposed future protocol

Device identity authenticates sync operations, enrollment, and collection grants. It is separate from transport authentication and from local vault-unlock credentials.

## 1. Key separation

Proposed per-device keys:

```text
Ed25519 signing key pair
X25519 HPKE/key-agreement key pair
```

Do not reuse one key pair across signature and KEM roles. Public keys are server-visible; private keys are wrapped under a root-derived identity key and optionally additionally gated by platform protection.

## 2. Device identifier

`device_id` is random and bound to public keys through a signed enrollment record. User-facing device names are encrypted private metadata and are not cryptographic identifiers.

## 3. Enrollment

Initial device:

1. create vault/account and identity keys locally;
2. sign self-enrollment/root device record;
3. publish public record;
4. store private keys encrypted under root identity domain.

Additional device:

1. authenticate account transport;
2. generate keys on new device;
3. existing authorized device or recovery flow verifies new keys;
4. authorized device signs enrollment including capabilities and sequence;
5. server relays signed record;
6. new device receives required encrypted root/collection state;
7. all devices update accepted membership.

Email/server login alone must not authorize a new decryption identity.

## 4. Enrollment record

Conceptual fields:

```text
protocol_version
account/vault identity binding
device_id
signing_public_key
hpke_public_key
key_versions
capabilities
created_sequence
issuer_device_id
previous_membership_commitment
issuer_signature
```

Canonical encoding and signature domain are versioned.

## 5. Verification

Users may verify devices through:

- QR scan;
- short authentication string/fingerprint;
- comparison on existing device;
- recovery-mediated approval.

Server-displayed names alone are not proof.

## 6. Private-key protection

- encrypted at rest under dedicated root-derived domain;
- loaded only in authenticated session for signing/decryption operations;
- zeroized/evicted on lock;
- not stored in public Room/DataStore;
- portable recovery behavior explicitly chosen;
- no export through general FFI/application API.

Optional Secure Enclave/Android hardware identity keys require separate suite and interoperability ADR; default portable Rust keys simplify recovery and protocol consistency.

## 7. Sequence and signing

Every device maintains a strictly increasing operation sequence. Signatures bind:

```text
device_id
sequence
previous operation hash
operation bytes
protocol version
```

Private key use must not sign arbitrary server-provided bytes without domain/context validation.

## 8. Rotation

Key rotation creates a signed replacement record:

- new key generated locally;
- old signing key authorizes transition when available;
- recovery/quorum procedure used if old key unavailable;
- sequence and previous membership commitment prevent silent replacement;
- old key retained only for historical signature verification;
- compromise event may require collection epoch rotation.

## 9. Revocation

Revocation is a signed membership operation. After acceptance:

- future root/collection envelopes are not issued to device;
- collection keys rotate according to policy;
- server tokens are revoked;
- old signatures remain verifiable;
- previously downloaded keys/plaintext remain outside enforceable deletion.

## 10. Device loss and recovery

A user can remove a lost device from another authorized device or recovery session. If every identity device is lost, recovery must bootstrap a new root membership state without trusting server key substitution. This flow requires explicit operation/log rules.

## 11. Multi-vault/decoy

Real and decoy identities do not share device private keys or membership by default. Correlating them through the same sync account would weaken decoy goals and requires a separate product decision.

## 12. Tests

- enrollment happy path and key substitution;
- duplicate/stale device ID;
- wrong issuer/signature/domain;
- sequence rollback/fork;
- key rotation with/without old device;
- revocation and stale operation rejection;
- recovery bootstrap;
- private-key storage/lock lifecycle;
- cross-platform signature/HPKE vectors.
