# ADR-0065: Peer-only synchronization without accounts

- **Status:** Accepted
- **Date:** 2026-10-20
- **Decision owners:** @po4yka
- **Supersedes:** service, hosted storage, transport-token, and account assumptions in previous sync decisions.

## Decision

Chur has no account, registration, login service, first-party backend, user-operated backend, object-storage backend, relay, rendezvous service, or cloud synchronization. Local vault passwords and recovery factors unlock local encryption; device identities are cryptographic keys, not accounts.

Synchronization occurs only through an explicit direct connection between two unlocked foreground apps. A device may temporarily listen for its peer as part of that connection. It is not a separately deployed or persistent service. Locking or leaving the app closes its connection and clears its invitation. There is no locked/background transfer.

The first pairing starts with an encrypted portable backup on the new device. The approving device verifies its fingerprint and issues an encrypted approval. A direct session transfers signed history, verifies checkpoint floors, installs a fresh device identity, exchanges independently authorized object keys, and authenticates complete downloaded containers before activation.

The initial transport uses literal IPv4 device addresses and a one-use 256-bit invitation. Both devices need a direct network route, such as the same LAN. Automatic discovery and connections through NAT without a direct route are not provided. Future transports must remain strictly direct and account-free.

## Implementation

`chur-sync-server`, its HTTP routes, Ktor service clients, operator/bootstrap credentials, durable service configuration, remote deletion authorizations, and periodic workers are removed. Catalog v15 removes obsolete network enrollment state without deleting vault objects. Old service approvals must be regenerated; no legacy transport remains. Native ABI 7 rejects earlier bundled callers before exchanging changed pairing payloads.

`shared/core-sync` now coordinates ephemeral native direct connections. Rust owns encryption, signed membership, operations, key delivery, download verification, and session-owned socket lifetime. The protocol is specified in [SYNC_PROTOCOL_V1.md](../sync/SYNC_PROTOCOL_V1.md).
