# ADR-0053: Freeze the v1 Collection Grant

- **Status:** Accepted
- **Date:** 2026-08-30
- **Decision owners:** @po4yka
- **Related:** [`../sync/COLLECTION_GRANTS.md`](../sync/COLLECTION_GRANTS.md), [`../sync/DEVICE_IDENTITY.md`](../sync/DEVICE_IDENTITY.md), [`../sync/REVOCATION.md`](../sync/REVOCATION.md), [`0045`](0045-freeze-device-membership-records.md)

## Context

The proposed sharing design selected RFC 9180 HPKE and a separate Ed25519 sender signature, but it left the grant fields conceptual, the permission values unallocated, and the recipient and sender key identifiers undefined. It also reused `membership_generation` for two different states: the sender vault's device membership and the shared collection's recipients.

Those gaps make a Phase 4 implementation unsafe. Two clients could seal different contexts, accept different permission combinations, or treat a sender-device change as a collection-member change. A variable grant would also add an attacker-controlled allocation where the v1 profile needs none.

## Decision

- `CollectionGrantV1` is the fixed 309-byte record in `COLLECTION_GRANTS.md` §2;
- one grant targets one recipient device. A recipient with several devices receives one independently revocable grant per device;
- the source vault identifier and recipient identity-vault identifier are separate fields. Phase 3 device enrollment remains the mechanism for another device of the same vault, so a v1 sharing grant cannot target its source vault;
- `sender_membership_generation` authenticates the sender device in the source vault. `collection_membership_generation` independently orders recipient and permission changes for one collection;
- the only v1 permission profiles are `READ` (`0x01`), `CONTRIBUTE` (`0x03`), and `MANAGE_MEMBERS` (`0x07`). The cumulative values make every stronger profile include the weaker capabilities. Every other byte fails closed;
- HPKE profile `0x0001` is RFC 9180 Base mode with DHKEM(X25519, HKDF-SHA-256), HKDF-SHA-256, and ChaCha20-Poly1305. It seals only the 32-byte `SecurityCollectionKey`;
- HPKE `info` and AAD use different domain tags followed by the same fixed 165-byte grant context. The Ed25519 signature uses a third domain tag and covers the complete record except the signature;
- signing and HPKE key identifiers are the leading 16 bytes of domain-separated BLAKE3-256 commitments over the identity-vault ID, device ID, suite, and public key;
- `grant_id` equals the identifier of the signed operation that issues the grant, and `created_sequence` equals that operation's device sequence;
- v1 has no expiry field. No trusted shared clock exists, and an expiry cannot revoke a collection key already obtained by a recipient. Revocation uses a signed membership generation and a new collection epoch.

## Alternatives considered

### One grant per user

Rejected. A user-level record needs another identity and key-distribution protocol. One grant per enrolled recipient device reuses the existing device identity and allows independent device loss and revocation.

### Arbitrary permission bit sets

Rejected. Values such as `MANAGE_MEMBERS` without `READ` have no useful v1 meaning and enlarge every authorization check. Three canonical cumulative profiles are enough.

### Put context inside the HPKE plaintext

Rejected as duplication. The fixed context is authenticated as HPKE AAD and by the sender signature. The plaintext is exactly the collection key, so public-key encryption can never receive a media payload or an unbounded value.

### Add expiry

Rejected. Device clocks are not authorization authorities, and expiry cannot erase a key already delivered. A future lease protocol would need an online trust model and a new grant version.

## Consequences

### Positive

- encoders, recipients, vectors, and audits share one bounded byte contract;
- sender-device membership and collection membership cannot be confused;
- multi-device recipients require no user-identity abstraction;
- malformed suites, permissions, key identifiers, and cross-vault self-grants fail before HPKE opens.

### Tradeoffs

- adding another permission profile or recipient type requires a new allocated value;
- one user with several devices produces several small grants;
- v1 grants do not express time-limited access.

## Security impact

Affected invariants: SEC-043, SEC-044, SEC-045. Only one fixed-size collection key enters HPKE, the sender signature is independent from HPKE Base mode, and revocation remains explicitly forward-only.

## Compatibility impact

No collection grant exists yet, so nothing migrates. A change to any field, width, domain tag, permission value, key-ID derivation, HPKE profile, or authenticated field list requires a new grant version.

## Validation

- byte-exact positive grant and key-ID vectors;
- wrong recipient, key, context, suite, permission, generation, signature, and ciphertext negatives;
- replay, stale generation, conflicting grant ID, and recipient key-substitution cases;
- multi-device recipient issuance and independent device revocation;
- an assertion that the HPKE plaintext is exactly 32 bytes.

## Follow-up

- implement the fixed codec, HPKE seal/open, and vectors in `chur-sync-protocol`;
- freeze the collection membership-change records and operation kinds;
- carry grants through catalog, server, FFI, and mobile boundaries.
