# ADR-0067: Signed peer transcript with X25519

- **Status:** Accepted
- **Date:** 2026-10-21
- **Decision owners:** @po4yka
- **Related:** [`0065`](0065-peer-only-sync-without-accounts.md), [`0009`](0009-one-hkdf-label-registry.md), [`../sync/SYNC_PROTOCOL_V1.md`](../sync/SYNC_PROTOCOL_V1.md), [`../sync/PEER_TRUST_MODEL.md`](../sync/PEER_TRUST_MODEL.md), [`../security/KEY_HIERARCHY.md`](../security/KEY_HIERARCHY.md)

## Context

Version 1 of the direct peer transport derived its session keys only from the one-use invitation secret and two random challenges. Two defects followed:

- the device identity was self-asserted. Each app sent its vault ID and device ID in a JSON message, and native code checked only that the claimed device was an active member. Any holder of the invitation could claim the identifier of an enrolled device and receive heads, checkpoints, and signed history metadata. Object keys stayed safe because grants are addressed and signed;
- there was no forward secrecy. Later disclosure of an invitation decrypted recorded traffic.

## Decision

The peer protocol moves to version 2 with the hello magic `CHURP2P2`:

- each hello carries a fresh X25519 ephemeral public key. The X25519 shared secret follows the invitation secret in the HKDF input, and the ephemeral private keys are erased after the handshake;
- the derivation uses two new registered labels, `chur/v1/peer/session` and `chur/v1/peer/transcript-binding`, under a new `peer` tier. The version-1 strings `CHUR\0P2P\0DIRECTION\0V1` and `CHUR\0P2P\0TRANSCRIPT\0V1` are not reused;
- the first frame in each direction is a record that the device signs with its enrolled Ed25519 device signing key. The signature covers the role, the vault ID, the claimed device ID, the transcript hash of both hellos, and the session binding. The binding derives from the invitation secret and the X25519 shared secret;
- Rust verifies the remote signature against the enrolled key of the claimed device before it sends or returns any caller frame or device history. The Kotlin hello message is removed, and Kotlin reads the authenticated device ID from native code;
- each native call that serves or accepts data checks the authenticated device again against the membership that is trusted at that time. For a restored device with a pending approval, that is the newer of the local membership and the approval authority. Object-key grants and ciphertext ranges are served through the peer call, and a grant is issued only to the authenticated device;
- a connector refuses a version-1 hello with `UNSUPPORTED_VERSION`. A listener counts it as a failed connection, because any host can send it.

## Alternatives considered

### Keep the PSK and add only a signature

Rejected. A signature fixes the identity defect but leaves recorded traffic exposed to later disclosure of an invitation. X25519 is already a reviewed workspace dependency with the required features.

### TLS or Noise from a library

Rejected. A TLS library adds certificates, native code, and configuration that the protocol does not use. A Noise library adds a new dependency for one handshake that the existing primitives express directly.

### Verify identity in Kotlin

Rejected. The device keys and membership are native state. A check in the app layer makes the platform code a trust boundary, which ADR-0065 assigns to Rust.

## Consequences

### Positive

- a holder of the invitation who is not an enrolled device, or who claims another member's identifier, fails before any synchronization data moves;
- later disclosure of an invitation does not decrypt a recorded session;
- a record from another session does not verify, because its transcript hash and binding differ.

### Tradeoffs

- version-1 and version-2 apps cannot synchronize; both devices need the update;
- the handshake has one more round trip, because the listener answers only after the initiator signature verifies.

## Security impact

Affected invariants: SEC-005 (key domain separation, by the new registered labels) and the peer trust model. The trust decision for a peer device is now a native signature check. An attacker who holds the invitation during a session can still delay or end it, but cannot read it or impersonate a device without that device's signing key.

## Compatibility impact

No stored byte changes. The peer wire protocol changes from version 1 to version 2. `chur_peer_call` keeps its C signature. Command 9 gains an empty-body form that returns the authenticated device; its earlier body still works. The KDF vectors gain two rows for the new labels.

## Validation

- transport tests for a wrong device key, a claimed member identifier, an unknown device, a replayed signed record, different keys for two sessions of one invitation, and a version-1 hello;
- unit tests for the X25519 agreement, a low-order key, and the key derivation;
- KDF vectors for both new labels;
- Kotlin host tests that pair two real vaults through JNI and TCP.

## Follow-up

- run a physical two-device peer session with version 2, which these local checks do not cover;
- include the version-2 handshake in the independent review of the peer replacement that ADR-0065 requires.
