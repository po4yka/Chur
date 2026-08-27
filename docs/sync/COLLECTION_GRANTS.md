# Collection Grants

> **Status:** Proposed future sharing protocol

A collection grant authorizes a recipient device/user to unwrap one Security Collection key epoch. Public-key cryptography wraps only the small collection key, never bulk media.

## 1. Cryptographic profile

Proposed RFC 9180 suite:

```text
KEM: DHKEM(X25519, HKDF-SHA-256)
KDF: HKDF-SHA-256
AEAD: ChaCha20-Poly1305
```

Sender/device authentication uses a separate Ed25519 signature over the canonical grant. HPKE Base mode alone does not prove sender identity.

## 2. Grant structure

Conceptual fields:

```text
grant_version
grant_id
vault/account binding
collection_id
collection_epoch
recipient_device_id
recipient_hpke_key_id
sender_device_id
sender_signing_key_id
permissions
membership_generation
created operation/sequence context
HPKE encapsulated key
HPKE ciphertext of CollectionKey and context
sender signature
```

The exact fields are minimized and canonically encoded. `membership_generation` is the vault device-membership counter of [`DEVICE_IDENTITY.md`](DEVICE_IDENTITY.md) §4, not a per-collection counter: it fixes which device-membership state the grant was issued under, which is what §9 compares.

## 3. HPKE plaintext/context

Encrypted plaintext includes:

```text
SecurityCollectionKey[epoch]
collection ID/epoch
recipient ID/key ID
sender ID
permissions/membership generation
grant ID
protocol version
```

HPKE `info` and AAD use unique domain tags and the same immutable context to prevent cross-protocol/key substitution.

## 4. Sender signature

Signature covers all canonical outer fields including HPKE encapsulation/ciphertext and identity context. Recipient verifies:

- known authorized sender device;
- signature/key validity at membership generation;
- recipient key match;
- grant not revoked/stale;
- collection epoch accepted;
- HPKE decrypt/context.

## 5. Recipient verification

Recipient key trust follows [`SERVER_TRUST_MODEL.md`](SERVER_TRUST_MODEL.md) §7: an external recipient's keys are pinned on the first grant, and out-of-band verification before that first grant is offered but not required. Server-provided names/avatars are not sufficient cryptographic proof. The fingerprint the user compares is constructed in [`DEVICE_IDENTITY.md`](DEVICE_IDENTITY.md) §5.

A change to a pinned recipient key blocks: no further grant is issued to that recipient until the user re-verifies the fingerprint, or until a signed identity rotation chaining to the pinned key authorizes the change. It is not a dismissible banner.

## 6. Permissions

Initial permission candidates:

```text
READ
CONTRIBUTE
MANAGE_MEMBERS
```

Permissions affect accepted signed operations, not ciphertext ability already granted. A recipient with collection key can decrypt all objects in that epoch available to it.

## 7. Multiple devices

Grant may be per recipient device to support independent revocation and key directory verification. User-level abstraction can issue one grant per verified active device.

## 8. Membership changes

Adding member:

- validate signed membership operation;
- issue grants for current epoch;
- no media re-encryption.

Removing member:

- sign revocation;
- create new collection epoch;
- rewrap active object keys under new epoch, eagerly and to completion per [`REVOCATION.md`](REVOCATION.md) §3.1;
- issue new grants to remaining members;
- prevent future operations/grants from removed device;
- accept that old epoch data previously downloaded remains accessible.

## 9. Replay and stale grants

Clients reject grants below accepted membership generation/epoch or already revoked. Identical grant replay is idempotent. Conflicting grant ID/content is a security error.

## 10. Recovery and device rotation

New recipient device requires new verified key and grant. Copying another device's private key is discouraged unless the identity recovery protocol explicitly permits wrapped portability.

## 11. Post-quantum extension

Grant records carry suite/recipient type so a future hybrid X25519+ML-KEM profile can be added without changing local media containers. It requires new vectors and audit; no server-chosen downgrade.

## 12. Tests

- valid sender/recipient grant;
- wrong recipient/key/context;
- modified encapsulation/ciphertext/permissions/epoch;
- invalid/unknown/revoked sender;
- replay/stale/conflicting grant;
- multi-device recipient;
- membership add/remove/epoch rotation;
- key-directory substitution;
- cross-platform HPKE/signature vectors;
- no bulk media passed to public-key encryption.
