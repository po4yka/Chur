# Direct peer synchronization

> **Status:** Accepted P2P transport contract; [ADR-0065](../adr/0065-peer-only-sync-without-accounts.md)

## 1. Scope

Two unlocked Chur apps synchronize their own vault over a direct, ephemeral TCP connection. There is no account, backend deployment, HTTP API, bootstrap credential, durable network token, cloud store, relay, or background worker. The current UI takes literal IPv4 addresses. Both devices must have a direct route; automatic discovery and NAT traversal are not implemented.

Signed operation, checkpoint, membership, and object-key formats retain their independently versioned canonical encodings. Rust alone validates them. This transport replaces all previous service-based synchronization behavior.

## 2. Pairing

Restore an encrypted portable backup on the new device. Prepare its fresh pairing request. Transfer the request to an ordinary device, compare the device fingerprint on both screens, and explicitly approve whole-vault access. The approval is HPKE-encrypted for the requesting identity and contains no network address or transport token. Open the approval file on the new device, then connect directly to the approving device. Native finalization requires the pinned checkpoint and every signed ancestor before installing the ordinary identity. Recovery credentials are not remote accounts.

## 3. Invitation and handshake

The ordinary device enters its reachable IPv4 address and creates a one-time invitation `IPv4:port#secret_hex`. Rust chooses a temporary port and a fresh 32-byte OS-random secret. Rust binds the listener only to the entered interface address. The other device enters the invitation and connects directly. A connection does not use the invitation until it proves the secret: its first encrypted frame must open under the derived keys. Until then, the listener sends only its hello. It closes a connection that sends a different hello, a frame that does not open, more than 64 KiB in its first frame, or nothing within ten seconds, and it continues to listen. At most four unproven connections are examined at the same time. After 32 failed connections, or two minutes without a proven connection, the invitation closes with a failure. The first proven connection closes the listener. There is no DNS lookup, discovery registry, or external infrastructure.

Each endpoint writes `CHURP2P1 || random_challenge[32]`. The salt is SHA-256 over `CHUR\0P2P\0TRANSCRIPT\0V1 || initiator_hello || responder_hello`. HKDF-SHA-256 extracts the invitation secret using that salt and expands under `CHUR\0P2P\0DIRECTION\0V1 || direction_byte`. Direction 1 is initiator to listener; direction 0 is listener to initiator. Keys are 32 bytes. No invitation or connection keys are saved.

## 4. Frames

A frame is `sealed_length:u32_be || XChaCha20Poly1305(plaintext, nonce, aad)`. Plaintext is bounded at 4 MiB and ciphertext at 4 MiB + 16 bytes. Each direction starts at sequence zero. Its nonce is sixteen zero bytes followed by its 64-bit big-endian sequence, and its AAD is that same sequence. Direction keys differ and are fresh for every handshake. Sequence exhaustion closes the session. Unauthenticated plaintext is never exposed. Native IO pumps are bounded and nonblocking; connection establishment has a three-second timeout, and idle IO has a two-minute timeout.

## 5. Exchange

Encrypted application frames contain bounded JSON `PeerMessage` control records. Binary canonical records remain opaque byte arrays. Both endpoints first exchange the vault ID and active device ID. Native membership checks reject unknown and revoked devices before any history, key, or object is served.

The connector pulls from the listener, then the endpoints exchange roles. A source prepares signed ordinary media and album operations from native-verified committed local containers. A receiver requests device heads and bounded per-device signed history pages, stages records, and applies them through native causal validation. Wire pagination positions never advance durable accepted heads. Every advertised head must be reached, and a native-verified source checkpoint must have no pending dependencies.

If initial pairing is pending, native finalization follows that signed-history verification. Missing originals are requested from native receive plans. Disposable prekey requests and addressed signed grants deliver object keys directly. Ciphertext transfers in ranges of at most 256 KiB, resumes from the locally retained prefix, and activates an object only after complete container verification. The existing deterministic conflict and tombstone rules remain in force. Private notes, album parent/order/deletion, and other local-only data remain outside the existing media/album operation contract.

The connector sends `turn` after its pull; the listener pulls while the connector handles requests. The listener then sends `complete`, and the connector acknowledges `complete`. Only this two-way completion reports success. Missing data, unresolved dependencies, failed authentication, interruption, and resource limits report failure or cancellation and preserve already committed local work.

## 6. Lifetime

Connections run only on an explicit foreground action. Stop cancels the run and closes the native connection. Vault lock and session close destroy the socket and keys independently of app coroutine cleanup. No periodic Android work, iOS background task, locked transfer, persisted endpoint, or automatic unlock-time connection exists.

## 7. Limits

No third-device service supplies absent data. A missing original requires reconnecting to a peer that retains it. There is no delivery while a device is offline and no guarantee that a peer erased a copy. See [PEER_TRUST_MODEL.md](PEER_TRUST_MODEL.md).
