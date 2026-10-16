# Own-device pairing

> **Status:** Accepted implementation contract; security release gates remain separate

A restored sync backup has a recovery-only identity. It cannot enroll itself as an ordinary signer. An active ordinary device approves a fresh identity through this exchange. Root and device private keys remain in native code and the encrypted catalog.

## Request and approval

`PairingRequest` is exactly 210 bytes: `version:u16=1`, `vault_id:16`, `request_id:16`, `device_id:16`, `signing_public_key:32`, `hpke_public_key:32`, `root_proof:32`, and `signature:64`. Identifiers are canonical non-zero IDs. The identity body is the fields through the HPKE public key. The root proof is the keyed BLAKE3 commitment under `CHUR\x00PAIRING\x00ROOT-PROOF\x00V1` of that body. The new Ed25519 key signs `CHUR\x00PAIRING\x00REQUEST-SIGNATURE\x00V1` followed by the body and root proof. The request digest commits the full request under `CHUR\x00PAIRING\x00REQUEST-DIGEST\x00V1`.

The authorized device verifies the request signature and root proof. The user compares the enrolled-key fingerprint through a separate trusted channel before approval. The device publishes inherited local media into its ordinary signed log before it issues the bootstrap checkpoint, then creates a signed successor enrollment and its root-encrypted `AddDevice` operation.

`PairingApproval` contains `version:u16=1`, vault/request/recipient-device/issuer-device IDs, the 32-byte request digest, and a `u32` authority-record count. Each authority record is a variable byte string: kind `1` plus the canonical enrollment, or kind `2` plus the canonical revocation. These fields form the header. Next are the 32-byte HPKE encapsulation, variable ciphertext, and 64-byte issuer signature. The signature covers `CHUR\x00PAIRING\x00APPROVAL-SIGNATURE\x00V1` plus every preceding encoded field. HPKE uses Base mode X25519/HKDF-SHA256/ChaCha20Poly1305. Both info and AAD equal `CHUR\x00PAIRING\x00APPROVAL-HPKE\x00V1` followed by the header.

The encrypted plaintext is `version:u16=1`, request digest, recipient device ID, server URL (canonical UTF-8 string), transport token (32 bytes), and variable checkpoint, enrollment, and ordinary operation records, in that order. The complete approval is limited to 16 MiB; the authority proof is limited to 4096 records. The URL and token are authenticated by the issuer signature as well as HPKE. A relay cannot change the URL and cause credential disclosure.

## Durable installation

The new device checks that the authority chain extends its trusted membership, the issuer remains active, the request matches, and all bootstrap signatures and bindings agree. On first approval acceptance the checkpoint must cover the initial trusted local history. Approval and encrypted credentials persist before credentials reach the host network layer. The recovered checkpoint becomes remote evidence, with its floors retained.

The host downloads the signed ordinary prefixes. Native finalization requires the exact approved enrollment operation and every approved checkpoint ancestor. Newer accepted checkpoint evidence is permitted and retained. Exact retained checkpoints remain idempotent after membership advances; an unknown stale checkpoint still fails. Durable and in-memory checkpoint commitments agree, and older replays cannot replace newer signed floor evidence. Finalization atomically installs the new ordinary identity and authors its first `DeviceReady` operation, removes recovery envelopes and the pending private identity, and keeps the authenticated response for app-state save retries. A retry does not create another identity or token. Portable backup snapshots exclude pending identities and approval credential caches.

`DeviceReady` has root domain, kind `0x13`, an empty body, and device sequence one. It changes no content projection. It establishes the fresh signer's chain so an immediate signed checkpoint and backup are valid.

## Device management

The native overview derives IDs, fingerprints, status, and the local-device flag from authenticated membership. The local device cannot revoke itself. A peer revocation pins the latest accepted peer operation and commits the signed revocation, root operation, and retry artifacts together. For an enrolled device that never authored an accepted operation, the revocation point is exactly `(0, zero32)`. The local author rejects that sentinel if its accepted target head or trusted floor is non-zero. Receivers and the relay check that the signed outer operation does not observe the target. A concurrently relayed `DeviceReady` does not change the issuer's signed accepted prefix. The relay retains the signed record but does not deliver records above the revocation cutoff. Protected local history and checkpoint records remain intact on the revoked device and other devices. Only native reconstruction of already-authenticated catalog rows uses historical replay; untrusted receive and sharing proofs still enforce the cutoff. Mixed zero/non-zero points are invalid. The server removes the revoked device's transport token; first and later operations from the revoked device are rejected.
