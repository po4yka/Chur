# Peer trust model

> **Status:** Accepted; [ADR-0065](../adr/0065-peer-only-sync-without-accounts.md)

Chur has no backend, account, cloud store, relay, or operator. Peers communicate directly while both vaults are open. Availability depends on both devices and a direct route; there is no remote copy or offline mailbox to recover from.

## Connection authentication

A temporary listener creates a one-use 256-bit random invitation secret. Share it only with the intended device through a trusted channel. Both endpoints contribute fresh 32-byte challenges and fresh X25519 ephemeral keys. HKDF-SHA-256 binds the invitation secret and the X25519 shared secret to the ordered handshake transcript and derives separate keys for each direction. Each bounded frame uses XChaCha20-Poly1305 with a monotonic direction-specific sequence nonce and authenticated sequence number. Wrong invitations, tampering, and replay are rejected. Because the ephemeral keys are erased after the handshake, later disclosure of an invitation does not decrypt recorded traffic. The listener binds only the entered interface address. A host on the network cannot take the invitation by connecting first: a connection that does not prove the secret is closed and the invitation stays open. The invitation closes with a failure after 32 failed connections or two minutes, so a host that sends 32 bad connections can end it. Connection attempts time out and close; invitation secrets are not persisted.

A party that holds the invitation secret but is not an enrolled device can do only these things:

- connect to the listener, prove the secret, and so use up the invitation. Its authentication record does not verify, and that one bad record ends the invitation with `AUTHENTICATION_FAILED`. It learns the listener hello, but not the listener vault ID or device ID, because the listener sends its record only after the connector record verifies;
- answer at the listener address in place of the listener, when it can receive that traffic. The connector sends its signed record first, so this party learns the connector vault ID and device ID, and a signature that is valid only for that one transcript. The connector then fails, because no valid listener record arrives;
- forward or drop the encrypted bytes between the two devices. It cannot decrypt them. If it replaces a hello or runs two separate handshakes, the transcripts differ and the device signatures do not verify.

It cannot read heads, checkpoints, history, keys, or objects, cannot sign as a device, and cannot replay a record into another session. A version-1 hello from an unproven connection is refused as a failed connection. Because any host can send it, `UNSUPPORTED_VERSION` is reported only when the invitation closes with no proven connection.

Possession of an invitation is not a vault enrollment. Each device signs the complete handshake transcript with its enrolled Ed25519 device key. Rust verifies the signature against the enrolled key of the claimed device, the same vault, and active membership before any heads, checkpoint, history, key, or object is served. Each native call that serves or accepts data checks the authenticated device again against the membership that is trusted at that time, so a device revoked during the session is refused at its next frame. Object-key grants are issued only to the authenticated device. Kotlin and Swift code never supply or trust a device identifier. A holder of the invitation who is not an enrolled device, or who claims the identifier of another member, fails the handshake. Native verification still governs signatures, causal dependencies, checkpoint floors, device revocation, disposable object-key prekeys, and full container authentication. An approval fingerprint must be compared on both devices before initial enrollment. A restored recovery identity cannot sign as an ordinary device until the pinned signed history has been verified.

## Untrusted peer behavior

Assume a peer can withhold records, replay old valid records, fork signed chains it owns, provide invalid frames, interrupt downloads, and retain data it previously received. Local signatures, accepted heads, rollback floors, native membership, resource limits, and complete object verification remain mandatory. A partial or causally incomplete transfer is never reported as up to date. Resumable ciphertext prefixes are stored locally and verified before activation.

Revocation propagates only when authorized peers reconnect. It prevents future key release once learned; it cannot recall plaintext or keys a recipient already copied. Peers can observe one another's IP addresses, transfer timing, sizes, and opaque device/object identifiers. Network observers can observe endpoints, timing, and sizes. There is no global completeness, online presence, delivery, or deletion guarantee.

## Lifetime and privacy

The invitation and transport keys remain in memory for the current foreground session. Lock closes the socket independently of coroutine cancellation, clears private projections, and prevents IO through retained native handles. Real and decoy vaults share neither invitations nor active transport state. Local passwords and recovery phrases are never sent to a peer.
