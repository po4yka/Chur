# Peer trust model

> **Status:** Accepted; [ADR-0065](../adr/0065-peer-only-sync-without-accounts.md)

Chur has no backend, account, cloud store, relay, or operator. Peers communicate directly while both vaults are open. Availability depends on both devices and a direct route; there is no remote copy or offline mailbox to recover from.

## Connection authentication

A temporary listener creates a one-use 256-bit random invitation secret. Share it only with the intended device through a trusted channel. Both endpoints contribute fresh 32-byte challenges. HKDF-SHA-256 binds the secret to the ordered handshake transcript and derives separate keys for each direction. Each bounded frame uses XChaCha20-Poly1305 with a monotonic direction-specific sequence nonce and authenticated sequence number. Wrong invitations, tampering, and replay are rejected. The PSK transport does not provide forward secrecy if the invitation is later disclosed; inner operation and object encryption remain independent. The listener binds only the entered interface address. A host on the network cannot take the invitation by connecting first: a connection that does not prove the secret is closed and the invitation stays open, within a bound of 32 failed connections and two minutes. Connection attempts time out and close; invitation secrets are not persisted.

Possession of an invitation is not a vault enrollment. Both endpoints must name the same vault and an active enrolled device before exchanging records. Native verification still governs signatures, causal dependencies, checkpoint floors, device revocation, disposable object-key prekeys, and full container authentication. An approval fingerprint must be compared on both devices before initial enrollment. A restored recovery identity cannot sign as an ordinary device until the pinned signed history has been verified.

## Untrusted peer behavior

Assume a peer can withhold records, replay old valid records, fork signed chains it owns, provide invalid frames, interrupt downloads, and retain data it previously received. Local signatures, accepted heads, rollback floors, native membership, resource limits, and complete object verification remain mandatory. A partial or causally incomplete transfer is never reported as up to date. Resumable ciphertext prefixes are stored locally and verified before activation.

Revocation propagates only when authorized peers reconnect. It prevents future key release once learned; it cannot recall plaintext or keys a recipient already copied. Peers can observe one another's IP addresses, transfer timing, sizes, and opaque device/object identifiers. Network observers can observe endpoints, timing, and sizes. There is no global completeness, online presence, delivery, or deletion guarantee.

## Lifetime and privacy

The invitation and transport keys remain in memory for the current foreground session. Lock closes the socket independently of coroutine cancellation, clears private projections, and prevents IO through retained native handles. Real and decoy vaults share neither invitations nor active transport state. Local passwords and recovery phrases are never sent to a peer.
