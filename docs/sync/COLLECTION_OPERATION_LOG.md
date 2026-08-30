# Collection Operation Log v1

> **Status:** Accepted normative Phase 4 protocol

This log carries encrypted content changes for one shared collection epoch. It does not replace the identity-vault log in [`OPERATION_LOG.md`](OPERATION_LOG.md).

## 1. Canonical record

```text
CollectionOperationV1 =
    protocol_version:u16 = 0x0001
    operation_id:bytes[16]
    issuer_identity_vault_id:bytes[16]
    issuer_device_id:bytes[16]
    device_sequence:u64
    previous_operation_hash:bytes[32]
    observed_heads:list of CollectionObservedHeadV1
    key_selector:bytes[16]
    encrypted_payload:variable-bytes
    issuer_signature:bytes[64]

CollectionObservedHeadV1 =
    issuer_identity_vault_id:bytes[16]
    issuer_device_id:bytes[16]
    device_sequence:u64
```

Identifiers and sequences are non-zero. An observed-head list has at most 287 entries. It is sorted by the 32-byte `(issuer_identity_vault_id, issuer_device_id)` pair and has no duplicate or self entry. The payload bound is the same 1,048,576-byte plaintext bound as `OperationV1`.

## 2. Encryption and signature

The payload is one [`OPERATION_PAYLOAD_V1.md`](OPERATION_PAYLOAD_V1.md) value encrypted with the collection operation key selected by `key_selector`. The allowed kinds are `0x01` through `0x0C` and `0x10`. Every other kind fails closed.

The AAD is `CHUR\x00SHARING\x00COLLECTION-OPERATION-AAD\x00V1` followed by the exact record bytes from `protocol_version` through `key_selector`. The encrypted field contains a 24-byte XChaCha20-Poly1305 nonce and ciphertext with its 16-byte tag.

The Ed25519 signature input is `CHUR\x00SHARING\x00COLLECTION-OPERATION\x00V1` followed by every field through the length-prefixed encrypted payload. The signature authenticates the issuer identity, device, chain, causal heads, selector, and ciphertext.

## 3. Epoch-scoped chains

For each `(key_selector, issuer_identity_vault_id, issuer_device_id)`:

```text
device_sequence = previous device_sequence + 1

operation_digest = BLAKE3-256(
      "CHUR\x00SHARING\x00COLLECTION-OPERATION-CHAIN\x00V1"
   || complete canonical record
)
```

Sequence one has an all-zero predecessor. A later sequence names the previous digest. A duplicate is idempotent only when all bytes match. Different bytes at one position freeze that issuer stream as a fork. Reusing one operation identifier at another position is a security conflict.

A new collection epoch derives a new selector and starts fresh sequence-one chains. Clients retain old epoch records as evidence but do not accept them as current content changes.

## 4. Cross-vault causality

An observed head names the highest accepted sequence for another active collection participant in this epoch. A receiver holds an operation until every named head is present. This gives the conflict rules in [`CONFLICT_RESOLUTION.md`](CONFLICT_RESOLUTION.md) an unambiguous cross-vault causal position.

The issuer pair in a head must identify an active source device or an active collection recipient. `READ` recipients cannot author. `CONTRIBUTE` and `MANAGE_MEMBERS` recipients can author the allowed content kinds. Only a source-vault device can author `RewrapObjectKey`.

## 5. Server routing

The server stores the record under its clear `key_selector`. It authenticates the transport caller as the issuer vault and checks current public membership state. A recipient can fetch a selector only while it has a current grant for that epoch. The server does not decrypt the payload and cannot choose a conflict winner.

The selector reveals that records use one key epoch. It does not reveal the collection identifier or action kind. Grant and membership inboxes remain the authorization source for mapping a recipient to the selector.

## 6. Recovery and revocation

A client restores collection membership, issuer identity memberships, grants, selector keys, accepted collection-operation records, and heads before it accepts new records. A missing cause remains pending. A stored gap, digest mismatch, selector mismatch, or unauthorized issuer fails closed.

Revocation advances the collection epoch and rewraps active object keys. Remaining devices receive new grants and use new selector streams. A removed recipient retains any old keys, ciphertext, records, or plaintext that it already obtained.

## 7. Tests

- canonical round trip and malformed bounds;
- wrong key, selector, payload context, signature, and issuer;
- replay, gap, predecessor mismatch, identifier reuse, and fork;
- cross-vault observed cause and deterministic convergence;
- permission, revocation, epoch reset, restart, and malicious relay behavior;
- Android, iOS, and CLI consumption of the same vectors.
