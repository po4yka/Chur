# Vault Descriptor v1

> **Status:** Proposed normative logical and binary contract

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

The exact construction is defined in the cryptographic/profile vectors. It may use AEAD over an encrypted private descriptor extension or a keyed authenticator under a dedicated derived key. The final v1 construction requires an ADR and audit.

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
- state transition crash at every step;
- stale generation;
- unsupported version/suite/profile;
- real/decoy external indistinguishability;
- backup restore without device slots;
- migration-required descriptor.
