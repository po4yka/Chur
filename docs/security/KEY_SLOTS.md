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

Two device-slot policies exist and the product mode selects one:

- **convenient**, the default: the device slot accepts biometry or the device credential. The device unlock code is consequently a working vault credential, which [`THREAT_MODEL.md`](THREAT_MODEL.md) §4 records under A2 and A8;
- **strict**: the device slot accepts biometry only and invalidates when the biometric set changes. It is the only configuration that resists an adversary who knows the device unlock code.

The policy is a per-vault setting shown at device-slot creation, and neither mode removes the portable-slot requirement above. §4 and §5 give each platform's mechanism.

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
- user authentication required according to the configured policy: convenient mode accepts biometry or a device credential, so the device unlock code opens this slot; strict mode accepts biometry only and invalidates on biometric enrollment change;
- TEE-backed by default when available;
- StrongBox optional with explicit fallback;
- root wrapped with fresh 96-bit GCM nonce;
- slot AAD binds the §2 field set on every wrap and unwrap: slot type and version, `vault_id`, `slot_generation`, `wrap_suite_id`, and the public parameters;
- alias is opaque and does not reveal real/decoy identity.

Invalidation, missing key, or device restore must lead to portable recovery rather than silent vault deletion.

## 5. Apple Keychain slot

Preferred design:

- random `DeviceUnlockSecret` stored as a `ThisDeviceOnly` Keychain item;
- access controlled by `userPresence` in convenient mode, which biometry or the device passcode satisfies, so the device passcode opens this slot;
- `biometryCurrentSet` in strict mode, which excludes the passcode and invalidates when the biometric set changes;
- Rust derives `AppleDeviceKEK` from the Keychain secret under the label `chur/v1/slot/apple-device-kek`, registered in [`KEY_HIERARCHY.md`](KEY_HIERARCHY.md) §3, with the context registered for that label in [`KEY_HIERARCHY.md`](KEY_HIERARCHY.md) §3, `vault_id:bytes[16], slot_id:bytes[16], slot_generation:u64` in that order, so a copied or superseded slot derives a different KEK;
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

An unlock attempt that uses a password runs exactly two Argon2id derivations, whatever the device holds. Argon2id output is salt-bound and §3 gives every slot its own random salt, so one derivation can never be tried against a second slot; a constant candidate count, not a reused derivation, is what removes the cost signal.

- the candidate list holds the highest `slot_generation` of each `PasswordSlotV1` reachable from the descriptors present, in the registry enumeration order of [`../format/VAULT_DESCRIPTOR_V1.md`](../format/VAULT_DESCRIPTOR_V1.md) §11, which is ascending filename bytes;
- v1 provisions at most two password-unlockable vault identities on one device, a vault and the optional decoy of [`DECOY_VAULT.md`](DECOY_VAULT.md), and §11 admits at most one `PasswordSlotV1` identity per descriptor, so the list never holds more than two real entries;
- a list shorter than two is padded to two with dummy candidates. A dummy candidate runs the parameters of the first real candidate over a fresh random 16-byte salt and discards the output;
- candidates run one at a time and every candidate, real or dummy, runs to completion before any result is used, so peak Argon2 memory is one profile allocation and the attempt costs two derivations whether it succeeds, fails, or matches a sibling identity;
- the memory the profile requires is checked once, before the first candidate, under [`PASSWORD_PROFILE.md`](PASSWORD_PROFILE.md) §6; a device that cannot allocate it runs no candidate at all.

The constant equalizes the derivation cost of one attempt. It does not hide the number of descriptors on the device or the Argon2 parameters they publish; the residual signals are in [`DECOY_VAULT.md`](DECOY_VAULT.md) §5.

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

The parser must enforce, before any derivation runs:

- at most 16 slots in one descriptor, matching [`../format/VAULT_DESCRIPTOR_V1.md`](../format/VAULT_DESCRIPTOR_V1.md) §13;
- `slot_body` between 16 and 4096 bytes, and the sum of all slot bodies at most 16384 bytes;
- nonce exactly 24 bytes, and `wrapped_root_secret` exactly 48 bytes: a 32-byte root plus a 16-byte tag;
- Argon2id salt length, memory, iterations, parallelism, and output length exactly as bounded in [`../CRYPTOGRAPHY.md`](../CRYPTOGRAPHY.md) §18.3, which the parser checks before Argon2 starts; a value outside any bound is `RESOURCE_LIMIT_EXCEEDED` and no derivation runs;
- zero unknown extension records: v1 defines none and rejects any;
- duplicate `slot_id` values, and duplicate `(slot_id, slot_generation)` pairs;
- at most one `PasswordSlotV1` `slot_id` per descriptor, whatever its generations. A descriptor offering a second password-slot identity is `RESOURCE_LIMIT_EXCEEDED`, which is what keeps the §8 candidate set constant.

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
