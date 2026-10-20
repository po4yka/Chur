# Own-device pairing

> **Status:** Accepted; direct peer transport, [ADR-0065](../adr/0065-peer-only-sync-without-accounts.md)

A restored backup carries a recovery-only identity. An ordinary active device must approve a fresh identity after an explicit comparison of the new device fingerprint. The app has no account or service login.

`PairingRequest` retains its canonical signed request format and root proof. `PairingApproval` retains its signed HPKE outer format; the encrypted credential payload is version 2 and contains the request digest, recipient device ID, checkpoint, enrollment, and enrollment operation. There is no address, bootstrap credential, or transport token. FFI pairing control records are version 2. Old payloads are rejected rather than decoded through a legacy path.

The approval is exchanged as an encrypted file. Opening it authenticates the request, authority records, current issuer membership, signature, HPKE body, and checkpoint floor. It does not complete setup. The new device connects directly to the approving device and receives every required signed history prefix. Native finalization installs the fresh ordinary identity only when the approved enrollment and checkpoint ancestry are satisfied. A failed transfer can retry the same approval. After a lock or app restart, native code revalidates the unfinished approval retained in the encrypted catalog; the coordinator resumes it without reopening the file. No network endpoint or transport secret is restored.

Catalog v15 discards pending approvals from the removed network enrollment model and removes network credential fields. Existing encrypted objects and authenticated device membership remain intact. Generate a fresh approval for unfinished old setup.

Settings derives device IDs, fingerprints, and active status from native membership. The local device cannot revoke itself. Revocation is signed and stored locally, then propagates during subsequent direct sessions. Already received copies cannot be recalled. No remote service receives deletion or credential-revocation requests.
