# Chur Key Slots

> **Status:** Proposed normative slot model; byte-exact encoding remains defined by the format specifications

A key slot is an authenticated envelope that allows one factor to recover the same random `VaultRootSecret`. Slots protect a short root secret; they never encrypt media directly.

## 1. Slot families

```text
PasswordSlotV1
AndroidKeystoreSlotV1
AppleKeychainSlotV1
RecoverySlotV1
PeerDeviceSlotV1      future
```

A recoverable consumer vault should have at least one portable slot (password or recovery) and may have one or more device-bound slots.

## 2. Common fields

Conceptual common fields:

```text
slot_id
slot_type
slot_version
vault_id
slot_generation
wrap_suite_id
kdf/profile_id when applicable
bounded parameters
nonce or platform reference
wrapped_root_secret
authentication tag or platform-authenticated result
created_at policy metadata when allowed
```

AAD must bind slot type/version, vault identity, generation, suite, and public parameters. Unknown slot types are preserved only when the enclosing format explicitly supports safe forwarding; they are never treated as valid unlock methods.

## 3. Password slot

```text
password bytes
    ↓ Argon2id(salt, bounded parameters)
PasswordKEK
    ↓ XChaCha20-Poly1305 wrap
VaultRootSecret
```

Requirements:

- fresh random salt and nonce;
- versioned password encoding profile;
- parameters validated before KDF work;
- no password verifier unless separately justified;
- wrong credential and damaged ciphertext share the same external failure;
- parameter upgrades occur by creating and verifying a replacement slot;
- the old slot is removed only after the replacement commits.

## 4. Android Keystore slot

Preferred design:

- non-exportable AES-256-GCM wrapping key in Android Keystore;
- user authentication required according to configured policy;
- TEE-backed by default when available;
- StrongBox optional with explicit fallback;
- root wrapped with fresh 96-bit GCM nonce;
- slot AAD binds vault and slot generation;
- alias is opaque and does not reveal real/decoy identity.

Invalidation, missing key, or device restore must lead to portable recovery rather than silent vault deletion.

## 5. Apple Keychain slot

Preferred design:

- random `DeviceUnlockSecret` stored as a `ThisDeviceOnly` Keychain item;
- access controlled by `userPresence` by default;
- optional stricter `biometryCurrentSet` mode;
- Rust derives a device KEK using a versioned HKDF context;
- Rust wraps/unwraps the root using the approved local AEAD;
- Keychain item identifier is opaque and separate for each vault identity.

An implementation may instead store wrapped root bytes directly as the Keychain secret if an ADR demonstrates equivalent lifecycle, portability, and Rust ownership. The chosen model must be test-vectorable at the Rust envelope layer.

## 6. Recovery slot

```text
RecoverySecret (random 32 bytes)
    ↓ HKDF recovery context
RecoveryKEK
    ↓ AEAD wrap
VaultRootSecret
```

The mnemonic or QR is a presentation encoding of canonical recovery bytes. It is not a low-entropy password and does not use Argon2id by default.

## 7. Peer-device slot

Future device enrollment may wrap the root or a device-specific root envelope to an authenticated device public key. It requires:

- verified device identity;
- signed enrollment operation;
- replay and revocation behavior;
- recovery if every enrolled device is lost;
- separate protocol review.

It is not part of local Vault v1 release scope.

## 8. Slot selection and failure behavior

Unlock flow:

1. parse and bound all candidate slot descriptors;
2. obtain user/platform factor;
3. derive or request the slot KEK operation;
4. unwrap a candidate root into protected Rust memory;
5. authenticate the vault descriptor under [`../format/VAULT_DESCRIPTOR_V1.md`](../format/VAULT_DESCRIPTOR_V1.md) §8, then the catalog root;
6. open an opaque session only after validation;
7. clear candidate credentials and failed roots.

External errors must not reveal:

- whether a real, decoy, or no vault slot exists;
- whether password bytes were close to correct;
- which AEAD or descriptor check failed;
- whether a hidden sibling identity exists.

A failed unwrap at step 4 still performs the step 5 derivation and tag computation over a random substitute root, so an invalid credential and a credential valid for a sibling vault cost the same work and return the same error. The exact rule is in [`../format/VAULT_DESCRIPTOR_V1.md`](../format/VAULT_DESCRIPTOR_V1.md) §8.

## 9. Transactions

Creating/replacing a slot uses:

```text
write new slot temp
fsync
read and verify with intended factor
commit descriptor generation
fsync
remove old slot when policy allows
```

At least one verified recovery path must remain throughout a recoverable-vault update.

## 10. Backup policy

| Slot | Portable | Included in portable backup |
| --- | ---: | ---: |
| Password | Yes | Yes, wrapped bytes and parameters |
| Recovery | Yes | Yes, wrapped bytes; not plaintext secret |
| Android Keystore | No | No; descriptor may record that re-enrollment is needed |
| Apple Keychain `ThisDeviceOnly` | No | No |
| Peer device | Protocol-specific | only under explicit device-recovery design |

## 11. Limits

The parser must cap:

- total slot count;
- parameter byte lengths;
- salt and nonce lengths;
- wrapped payload size;
- Argon2 memory, iterations, and parallelism;
- unknown extension count;
- duplicate slot IDs/generations.

## 12. Test requirements

- correct and incorrect password;
- changed Unicode encoding profile;
- minimum/maximum KDF parameters;
- corrupted AAD, nonce, tag, and wrapped root;
- platform key missing/invalidated;
- crash at every replacement step;
- backup/restore without device slots;
- real/decoy external failure equivalence;
- stale generation and duplicate slot rejection.
