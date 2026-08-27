# Key Slot Bodies v1

> **Status:** Proposed normative format; the four `slot_body` layouts of §3 to §6 and their AAD tuples are frozen by [ADR-0036](../adr/0036-freeze-the-v1-key-slot-bodies.md). Deterministic vectors are outstanding.

[`VAULT_DESCRIPTOR_V1.md`](VAULT_DESCRIPTOR_V1.md) §7 gives every key slot a fixed 34-byte header and one length-prefixed `slot_body`, and states that the body schema is selected by `slot_type` and owned elsewhere. This document is that owner. [`../security/KEY_SLOTS.md`](../security/KEY_SLOTS.md) stays authoritative for slot behaviour, policy, and lifecycle; this document fixes the bytes.

A slot protects one `VaultRootSecret`. It never encrypts media, and it carries no private user metadata.

## 1. Common rules

Integers are unsigned big-endian per [`CANONICAL_ENCODING_V1.md`](CANONICAL_ENCODING_V1.md) §2. Each body is a structure under §4 there, encoded in the field order listed, with no padding between fields and no trailing bytes inside the declared `slot_body_length`.

Every v1 body ends with `wrapped_root_secret`, exactly 48 bytes: the 32-byte root secret plus a 16-byte AEAD tag, as [`../security/KEY_SLOTS.md`](../security/KEY_SLOTS.md) §11 requires. A body whose declared length does not match the length its own fields imply is `NON_CANONICAL_ENCODING`, checked before any key-derivation work runs.

Every slot AAD begins with the family's domain tag and then repeats the same six binding elements, in this order, before any family-specific element:

```text
vault_id:bytes[16]
slot_id:bytes[16]
slot_type:u8
slot_version:u16
wrap_suite_id:u16
slot_generation:u64
```

Those six are the descriptor header fields of [`VAULT_DESCRIPTOR_V1.md`](VAULT_DESCRIPTOR_V1.md) §7 plus the `vault_id` of §2.1 there. They are 61 bytes and satisfy the rule in [`../security/KEY_SLOTS.md`](../security/KEY_SLOTS.md) §2 that AAD binds slot type, version, vault identity, generation, and suite. The family-specific elements that follow are the public parameters of that family: everything in its body except the nonce or platform reference and the wrapped root.

The nonce and `wrapped_root_secret` are never AAD elements: the nonce is an AEAD input and the wrapped bytes are what the tag already covers. `slot_body_length` is not an element either, because §1 fixes it as a function of the declared fields.

`identity_id` is not an element of any v1 slot AAD. Real and decoy identities hold independent `vault_id` values under [`../security/KEY_HIERARCHY.md`](../security/KEY_HIERARCHY.md) §11, so `vault_id` already names the identity, per [ADR-0034](../adr/0034-freeze-the-hkdf-context-element-lists.md).

## 2. Constants

`password_profile_id`, `recovery_profile_id`, `keystore_profile_id`, and `keychain_profile_id` are allocated in [`CANONICAL_ENCODING_V1.md`](CANONICAL_ENCODING_V1.md) §15.2; the registry records the allocation and this document is the authority for these body bytes. `slot_type` values are §15.4 there.

`argon2_type` `0x02` and `argon2_version` `0x13` are the Argon2id variant and the Argon2 version 1.3 constant of RFC 9106. They are external constants carried in Chur bytes, not values of a Chur namespace, and §15.6 does not allocate them.

## 3. `PasswordSlotBodyV1`

`slot_type` `0x01`.

```text
offset  size         field                    v1 value
0x00     2           password_profile_id:u16  0x0001
0x02     1           argon2_type:u8           0x02
0x03     1           argon2_version:u8        0x13
0x04     4           memory_kib:u32           65536 for a newly created slot
0x08     4           iterations:u32           3 for a newly created slot
0x0C     4           parallelism:u32          1
0x10     4           salt_length:u32          16 to 32
0x14     salt_length salt                     fresh CSPRNG bytes per slot generation
...      24          slot_nonce               fresh 24 random bytes per seal
...      48          wrapped_root_secret      32-byte root plus 16-byte tag
```

The body is `92 + salt_length` bytes, so 108 bytes at the 16-byte salt a v1 writer produces.

`memory_kib`, `iterations`, `parallelism`, and `salt_length` are validated against the parser bounds of [`../CRYPTOGRAPHY.md`](../CRYPTOGRAPHY.md) §18.3 before Argon2 allocates anything. A value outside any bound is `RESOURCE_LIMIT_EXCEEDED` and no derivation runs. `password_profile_id` selects the canonical password bytes of [`../security/PASSWORD_PROFILE.md`](../security/PASSWORD_PROFILE.md) §3; `0x0001` is the no-normalization strict-UTF-8 profile.

```text
PasswordKEK = Argon2id(password_bytes, salt, memory_kib, iterations, parallelism, out = 32)

slot_aad = CanonicalTuple(
    "CHUR\x00SLOT\x00PASSWORD\x00V1",
    vault_id:bytes[16],
    slot_id:bytes[16],
    slot_type:u8,
    slot_version:u16,
    wrap_suite_id:u16,
    slot_generation:u64,
    password_profile_id:u16,
    argon2_type:u8,
    argon2_version:u8,
    memory_kib:u32,
    iterations:u32,
    parallelism:u32,
    salt:bytes
)

wrapped_root_secret = XChaCha20Poly1305.Encrypt(
    key       = PasswordKEK,
    nonce     = slot_nonce,
    plaintext = VaultRootSecret,
    aad       = slot_aad
)
```

`salt` is a variable-bytes element, so it carries its own `u32` length inside the tuple. The tag is 21 bytes and the elements add `65 + salt_length`, so the AAD is `86 + salt_length` bytes, 102 at a 16-byte salt.

Every Argon2 parameter is inside the AAD, so an attacker who lowers `memory_kib` in the body to make the slot cheap to attack changes the AAD and the unwrap fails. The parser bound of §18.3 rejects such a value first; the AAD is what makes the rejection unnecessary for correctness.

## 4. `RecoverySlotBodyV1`

`slot_type` `0x04`.

```text
offset  size  field                     v1 value
0x00     2    recovery_profile_id:u16   0x0001
0x02    24    slot_nonce                fresh 24 random bytes per seal
0x1A    48    wrapped_root_secret       32-byte root plus 16-byte tag
0x4A          end of body
```

The body is exactly 74 bytes and carries no variable field. `recovery_profile_id` `0x0001` is a 32-byte `RecoverySecret` presented as 24 BIP-39 English words, frozen by [ADR-0029](../adr/0029-freeze-the-recovery-secret-encoding.md). The words are a presentation encoding; the slot holds no part of them.

The recovery secret is high-entropy, so no password KDF runs and no salt is stored:

```text
RecoveryKEK = HKDF-SHA-256(
    IKM     = RecoverySecret,
    label   = "chur/v1/recovery/root-envelope",
    context = vault_id:bytes[16], slot_id:bytes[16], slot_generation:u64,
    length  = 32
)

slot_aad = CanonicalTuple(
    "CHUR\x00SLOT\x00RECOVERY\x00V1",
    vault_id:bytes[16],
    slot_id:bytes[16],
    slot_type:u8,
    slot_version:u16,
    wrap_suite_id:u16,
    slot_generation:u64,
    recovery_profile_id:u16
)

wrapped_root_secret = XChaCha20Poly1305.Encrypt(
    key       = RecoveryKEK,
    nonce     = slot_nonce,
    plaintext = VaultRootSecret,
    aad       = slot_aad
)
```

The tag is 21 bytes and the elements add 47, so the AAD is exactly 68 bytes.

## 5. `AndroidKeystoreSlotBodyV1`

`slot_type` `0x02`.

```text
offset  size          field                    v1 value
0x00     2            keystore_profile_id:u16  0x0001
0x02     4            alias_length:u32         16 to 64
0x06     alias_length alias                    opaque CSPRNG bytes, no identity in the name
...      12           gcm_nonce                fresh 96-bit nonce per seal
...      48           wrapped_root_secret      32-byte root plus 16-byte tag
```

The body is `66 + alias_length` bytes, so 82 bytes at the 16-byte alias a v1 writer produces. `keystore_profile_id` `0x0001` is a non-exportable AES-256-GCM Keystore key under the policy of [`../security/KEY_SLOTS.md`](../security/KEY_SLOTS.md) §4; the convenient and strict device policies of §1 there select the authentication requirement and do not change these bytes.

This is the one family whose AEAD runs outside Rust. The Keystore cipher performs it, so the nonce is the 12-byte GCM nonce rather than the 24-byte XChaCha nonce of the other three families, and Rust supplies the AAD and receives the wrapped bytes.

```text
slot_aad = CanonicalTuple(
    "CHUR\x00SLOT\x00ANDROID-KEYSTORE\x00V1",
    vault_id:bytes[16],
    slot_id:bytes[16],
    slot_type:u8,
    slot_version:u16,
    wrap_suite_id:u16,
    slot_generation:u64,
    keystore_profile_id:u16,
    alias:bytes
)

wrapped_root_secret = AES-256-GCM.Encrypt(
    key       = the Keystore key named by alias,
    nonce     = gcm_nonce,
    plaintext = VaultRootSecret,
    aad       = slot_aad
)
```

The tag is 29 bytes and the elements add `49 + alias_length`, so the AAD is `78 + alias_length` bytes, 94 at a 16-byte alias.

`wrap_suite_id` for this family is `0x0002`, allocated in [`CANONICAL_ENCODING_V1.md`](CANONICAL_ENCODING_V1.md) §15.2. It denotes AES-256-GCM performed by the platform keystore rather than the XChaCha20-Poly1305 of suite `0x0001`, and it is valid in this field only. Every other v1 family carries `0x0001`, and a descriptor rejects any other pairing of `slot_type` and `wrap_suite_id`.

## 6. `AppleKeychainSlotBodyV1`

`slot_type` `0x03`.

```text
offset  size  field                     v1 value
0x00     2    keychain_profile_id:u16   0x0001
0x02    16    keychain_item_id          opaque CSPRNG bytes naming the Keychain item
0x12    24    slot_nonce                fresh 24 random bytes per seal
0x2A    48    wrapped_root_secret       32-byte root plus 16-byte tag
0x5A          end of body
```

The body is exactly 90 bytes and carries no variable field. `keychain_profile_id` `0x0001` is the `DeviceUnlockSecret` model of [`../security/KEY_SLOTS.md`](../security/KEY_SLOTS.md) §5: the Keychain holds a random secret as a `ThisDeviceOnly` item and Rust performs the AEAD, which is what keeps this family test-vectorable at the Rust envelope layer. The alternative model that section leaves open, wrapped root bytes held directly as the Keychain secret, would take `0x0002` and its own ADR.

```text
AppleDeviceKEK = HKDF-SHA-256(
    IKM     = DeviceUnlockSecret,
    label   = "chur/v1/slot/apple-device-kek",
    context = vault_id:bytes[16], slot_id:bytes[16], slot_generation:u64,
    length  = 32
)

slot_aad = CanonicalTuple(
    "CHUR\x00SLOT\x00APPLE-KEYCHAIN\x00V1",
    vault_id:bytes[16],
    slot_id:bytes[16],
    slot_type:u8,
    slot_version:u16,
    wrap_suite_id:u16,
    slot_generation:u64,
    keychain_profile_id:u16
)

wrapped_root_secret = XChaCha20Poly1305.Encrypt(
    key       = AppleDeviceKEK,
    nonce     = slot_nonce,
    plaintext = VaultRootSecret,
    aad       = slot_aad
)
```

The tag is 27 bytes and the elements add 47, so the AAD is exactly 74 bytes. `keychain_item_id` is not an AAD element: it names where the secret is stored and never selects a construction. The `slot_id` it accompanies is already bound.

## 7. `PeerDeviceSlotV1`

`slot_type` `0x05` is allocated and has no v1 body. [`../security/KEY_SLOTS.md`](../security/KEY_SLOTS.md) §7 leaves the family to a separate protocol review, and §7 of [`VAULT_DESCRIPTOR_V1.md`](VAULT_DESCRIPTOR_V1.md) already states that the value parses as an allocated family and is never attempted as an unlock method in v1. A parser bounds and steps over such a body; it does not interpret it.

## 8. Parser limits

Checked in this order, before any derivation and before any allocation sized by the input:

- `slot_body_length` between 16 and 4096, and the sum of all bodies at most 16384, per [`VAULT_DESCRIPTOR_V1.md`](VAULT_DESCRIPTOR_V1.md) §13;
- `slot_type` is one of the five allocated values; an unallocated value is rejected and never forwarded;
- the body length equals the length the family's fields imply, so no trailing bytes exist inside a body;
- `salt_length` between 16 and 32, `alias_length` between 16 and 64;
- `slot_nonce` exactly 24 bytes, `gcm_nonce` exactly 12 bytes, `wrapped_root_secret` exactly 48 bytes;
- every profile identifier is a supported value; an unknown one is `UNSUPPORTED_VERSION`;
- Argon2 parameters inside the bounds of [`../CRYPTOGRAPHY.md`](../CRYPTOGRAPHY.md) §18.3;
- `slot_generation` is not `0xFFFFFFFFFFFFFFFF`, so an increment always exists;
- nesting depth is 1: a body contains no nested record.

A failure of any check is a parser error with its own code and is attributed to no credential, because every one of them runs before a credential is used.

## 9. Test vectors

- one deterministic valid body of each family, with its encoded AAD;
- an unlock that succeeds and one that fails on a one-bit password change;
- a body whose `memory_kib` is altered after sealing, which must fail the parser bound and, with the bound removed, the AEAD;
- `salt_length` and `alias_length` at both bounds and one past each;
- truncation at every field boundary of every family;
- trailing bytes inside `slot_body_length`;
- an unallocated `slot_type` and an unknown profile identifier;
- duplicate `slot_id`, and duplicate `(slot_id, slot_generation)`;
- a body sealed for one vault presented under another `vault_id`;
- the same root wrapped by a password slot and a recovery slot, both unwrapping to identical bytes.
