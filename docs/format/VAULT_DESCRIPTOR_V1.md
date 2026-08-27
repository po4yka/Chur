# Vault Descriptor v1

> **Status:** Proposed normative logical and binary contract; the descriptor-authentication construction in §8 is frozen. The remaining field encoding, offsets, and deterministic vectors are outstanding.

`VaultDescriptorV1` is the small pre-unlock structure that identifies a vault format, lists bounded key-slot descriptors, locates encrypted catalog/object state, and records transaction/migration generation. It contains no private user metadata.

## 1. Goals

- allow safe parsing before expensive authentication;
- bind key slots to one vault identity;
- distinguish complete initialization from interrupted setup;
- locate opaque catalog/object roots;
- support atomic descriptor generations;
- expose only minimal unavoidable metadata;
- remain portable across Android, iOS, and CLI.

## 2. Conceptual structure

```text
VaultDescriptorV1
├── magic
├── descriptor_version
├── canonical_encoding_profile
├── crypto_policy_id
├── vault_id
├── descriptor_generation
├── state
├── catalog_descriptor
├── object_store_descriptor
├── key_slot_descriptors[]
├── migration_descriptor?
└── descriptor_authentication
```

Exact offsets/tags are frozen in vectors before production use.

## 3. Public fields

Permitted before unlock:

- fixed magic and version;
- suite/policy identifiers needed to select a supported reader;
- random vault identifier;
- opaque directory/file identifiers;
- bounded slot descriptors and KDF parameters;
- descriptor generation and transaction state;
- migration-required version numbers;
- authentication bytes that cannot be verified until a root candidate exists.

Forbidden:

- real/decoy label;
- filename, album, media type, count, date, location;
- user email/account name;
- plaintext catalog schema content;
- recovery secret or platform key bytes.

## 4. State machine

Proposed states:

```text
INITIALIZING
ACTIVE
MIGRATING
RECOVERING
DELETING
```

`ACTIVE` is the only ordinary openable state. Other states require a bounded recovery/migration flow and fail closed for normal feature access.

A state transition writes a new descriptor generation; it does not mutate authenticated fields in place.

## 5. Catalog descriptor

Conceptual fields:

```text
catalog_format_version
catalog_crypto_suite
opaque_catalog_path_id
catalog_generation
catalog_header_commitment
```

The catalog key is derived after root validation. The descriptor does not contain plaintext schema or SQLCipher passphrase.

## 6. Object-store descriptor

Conceptual fields:

```text
object_store_format_version
opaque_root_path_id
naming_profile_id
container_version_floor
container_version_ceiling
```

Physical paths are app-internal and resolved by the platform/storage adapter from opaque IDs.

## 7. Key-slot descriptors

Each entry declares only bounded information needed to attempt a slot:

```text
slot_id
slot_type
slot_version
slot_generation
public parameters/reference
wrapped-root payload or platform envelope reference
```

Slot count and total bytes are capped. Duplicate IDs/generations are rejected.

## 8. Descriptor authentication

After a candidate root is unwrapped, Rust derives a descriptor-authentication key and verifies the canonical descriptor body. This proves:

- the slot is bound to this vault;
- catalog/object descriptors were not substituted;
- generation/state fields are authentic;
- a wrong but structurally valid root is rejected.

The construction is a keyed authenticator. It encrypts nothing, so it has no separate AAD and no nonce: every field an AAD would carry is inside the authenticated input.

```text
DescriptorAuthKey = HKDF-SHA-256(
    IKM     = VaultRootSecret,
    label   = "chur/v1/root/descriptor-auth",
    context = vault_id,
    length  = 32
)
```

The label is registered in [`../security/KEY_HIERARCHY.md`](../security/KEY_HIERARCHY.md) §3; the extract and expand construction is [`../CRYPTOGRAPHY.md`](../CRYPTOGRAPHY.md) §13. The key is stable for the life of the root secret. A new descriptor generation reuses it and does not derive a new one.

`descriptor_authentication` is exactly the last 32 bytes of the encoded descriptor and holds `descriptor_auth_tag`. The descriptor body is every byte before it:

```text
descriptor_body     = encoded_descriptor[0 .. descriptor_length - 32]
descriptor_auth_tag = encoded_descriptor[descriptor_length - 32 .. descriptor_length]

descriptor_auth_tag = BLAKE3-256-keyed(
    key   = DescriptorAuthKey,
    input =    "CHUR\x00VAULT\x00DESCRIPTOR-AUTH\x00V1"
            || descriptor_body
)
```

The domain tag is a fixed ASCII byte constant with no length prefix, per [`CANONICAL_ENCODING_V1.md`](CANONICAL_ENCODING_V1.md) §3 and §7. The output is 32 bytes and nothing follows it, so the trailing-byte rule of §13 still applies.

Authenticating the bytes as written, rather than a re-encoded field tuple, binds every field of §2 under one rule: magic, `descriptor_version`, `canonical_encoding_profile`, `crypto_policy_id`, `vault_id`, `descriptor_generation`, `state`, the catalog and object-store descriptors, every key-slot descriptor with its framing, and the optional migration descriptor. A field added by a later encoding profile is authenticated without a change to this section, and two implementations cannot disagree about field order.

The tag proves authenticity, not freshness. An older but authentic descriptor generation is a §10 problem, not an authentication failure.

Verification order:

1. reject an encoded descriptor shorter than the smallest body a v1 parser accepts plus 32 bytes;
2. parse and bound the body under §13, before any credential is used;
3. unwrap a candidate root from one key slot;
4. derive `DescriptorAuthKey` and recompute the tag over `descriptor_body`;
5. compare the 32 bytes in constant time;
6. accept the root and open a session only on equality.

On inequality the candidate root and every key derived from it are zeroized, no session opens, and the caller receives `AUTHENTICATION_FAILED`. A mismatch is never reported as `VAULT_CORRUPT`: a damaged descriptor and a wrong credential share one external failure, as required by [`../security/KEY_SLOTS.md`](../security/KEY_SLOTS.md) §3. Steps 1 and 2 run before any credential is used and keep their own parser error codes.

Real and decoy identities hold independent root secrets, so a credential valid for the sibling vault fails here exactly as an invalid credential does. The two must stay indistinguishable:

- the tag comparison is constant time over all 32 bytes and never returns early;
- a failed slot unwrap is followed by the same derivation and tag computation over a random 32-byte substitute root, and the result is discarded, so the work performed does not depend on which step failed;
- the same candidate set is attempted in the same order for every attempt, whatever the outcome;
- every failure emits the same error code, the same safe metadata, and the same retry classification, and no log event names the failing step;
- retry pacing and lockout counters do not depend on which candidate failed.

Whole-device indistinguishability is not claimed; the residual signals are listed in [`../security/DECOY_VAULT.md`](../security/DECOY_VAULT.md) §5.

## 9. Initialization transaction

```text
create random vault ID/root
create encrypted catalog temp
create object-store directory temp
create at least one key slot temp
write descriptor generation 0 as INITIALIZING
fsync all components
verify slot and descriptor
write generation 1 ACTIVE
atomic install
```

A crash before `ACTIVE` must be recoverable or removable without exposing a partially trusted vault.

## 10. Generation and rollback

Local descriptor generations are strictly increasing. A lower generation discovered beside a newer authenticated catalog state is suspicious and must not be selected silently.

Future sync/backup rollback protection is separate; a copied old but authentic standalone vault may require an external trusted checkpoint to detect.

## 11. Real/decoy handling

Each identity has an independent descriptor and random path ID. The descriptor does not identify its role. A public registry may locate candidate descriptors through opaque entries, but ordinary failures must not reveal which candidate matched.

## 12. Backup behavior

Portable backup includes:

- descriptor fields required for restore;
- portable password/recovery slots;
- catalog and object inventory;
- excludes device-bound Keystore/Keychain material.

Restore writes a new local platform slot after authenticating a portable slot.

## 13. Parser limits

At minimum cap:

- descriptor total size;
- slot count and slot bytes;
- path/identifier lengths;
- migration extension count/size;
- supported version and suite ranges;
- generation arithmetic.

Reject trailing bytes and non-canonical encoding.

## 14. Required tests

- valid minimal recoverable descriptor;
- device-bound-only descriptor;
- maximum permitted slots;
- duplicate IDs/generations;
- wrong root/descriptor authentication;
- failed slot unwrap that still performs descriptor-authentication work;
- state transition crash at every step;
- stale generation;
- unsupported version/suite/profile;
- real/decoy external indistinguishability;
- backup restore without device slots;
- migration-required descriptor.
