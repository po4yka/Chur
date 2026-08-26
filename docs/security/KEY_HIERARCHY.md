# Chur Key Hierarchy

> **Status:** Proposed normative key-ownership and lifecycle model

## 1. Overview

```text
Password / Device protector / Recovery / Peer device
                       ↓ key slot
               VaultRootSecret (32 bytes)
                       ↓ HKDF root domains
       ┌───────────────┼───────────────────────┐
       ↓               ↓                       ↓
CollectionWrapRoot  CatalogDatabaseKey   Other root-domain keys
       ↓
SecurityCollectionKey[epoch] (random)
       ↓ wrap
ObjectKey (random per object)
       ↓ HKDF object domains
Manifest / Content / Metadata / Thumbnail / Preview / Commit keys
```

A password is not a root key. A platform biometric is not a key. Android Keystore and iOS Keychain gate a key-slot operation that releases or unwraps `VaultRootSecret`.

## 2. Key classes

| Key | Size | Creation | Persistence |
| --- | ---: | --- | --- |
| `VaultRootSecret` | 32 bytes | Rust CSPRNG | only wrapped in key slots |
| `PasswordKEK` | 32 bytes | Argon2id | never persisted |
| platform KEK/wrapping key | platform-specific | Keystore/Keychain flow | platform service / device slot |
| `RecoverySecret` | 32 bytes | Rust CSPRNG | user-controlled representation only |
| `SecurityCollectionKey` | 32 bytes | Rust CSPRNG per epoch | wrapped under root-derived key or recipient grant |
| `ObjectKey` | 32 bytes | Rust CSPRNG per object | wrapped in one or more object envelopes |
| stream/domain key | 32 bytes | HKDF from parent | never persisted unless format explicitly requires |
| device identity private key | algorithm-specific | Rust/platform CSPRNG | wrapped under identity-domain key/platform policy |

## 3. Root-domain derivation

The root secret must not be used directly for unrelated AEAD operations. HKDF-SHA-256 derives versioned domains.

Proposed labels:

```text
chur/v1/root/collection-envelope
chur/v1/root/catalog-database
chur/v1/root/catalog-record
chur/v1/root/search
chur/v1/root/identifier
chur/v1/root/private-settings
chur/v1/root/device-identity-wrap
chur/v1/root/backup-manifest
```

Labels are ASCII protocol constants included in test vectors. Changing a label changes the derived key and therefore requires a format/protocol decision.

## 4. Security collection keys

A Security Collection is an access-control/key domain, not necessarily a UI album. The key is random and has an explicit epoch.

```text
Collection A epoch 1 → objects created before rotation
Collection A epoch 2 → new objects and rewrapped envelopes
```

Rotation may rewrap object keys incrementally. Media bytes need not be re-encrypted unless the object key itself is rotated.

## 5. Object keys

Every object receives a fresh random `ObjectKey`, even when content is identical. An object may have multiple envelopes when it belongs to multiple access domains or is shared to multiple authorized contexts.

Compromise of one object key must not reveal:

- other object keys;
- collection keys;
- catalog/search keys;
- root secret;
- real or decoy sibling vaults.

## 6. Object-domain derivation

Proposed labels:

```text
chur/v1/object/manifest
chur/v1/object/content
chur/v1/object/metadata
chur/v1/object/thumbnail
chur/v1/object/preview
chur/v1/object/poster-frame
chur/v1/object/waveform
chur/v1/object/ocr
chur/v1/object/embedding
chur/v1/object/final-commit
```

A stream revision also receives a fresh random nonce prefix. Domain separation does not replace nonce uniqueness.

## 7. Identifier keys

User-facing or server-visible identifiers should be random or derived through a dedicated identifier domain. An unkeyed plaintext hash must not become a global object identifier.

A keyed local fingerprint may support duplicate detection only when:

- derived under a dedicated key;
- never exposed globally by default;
- collision and deletion behavior are specified;
- users can disable or rebuild it.

## 8. Key lifetimes

| State | Root | Collection | Object/stream | Catalog key |
| --- | --- | --- | --- | --- |
| Locked | unavailable | unavailable | unavailable | unavailable |
| Unlocking | temporary candidate | none until root validated | none | none |
| Unlocked | Rust session secret | loaded/on demand | loaded/on demand | active in Rust catalog connection |
| Locking | zeroized in place | zeroized/evicted | readers invalidated and zeroized | DB closed then zeroized |
| Background ciphertext sync | unavailable | unavailable | unavailable | unavailable |

Caches must not extend a key lifetime beyond the session that created them.

## 9. Rotation

### Password change

Derive a new `PasswordKEK` with fresh salt/parameters and rewrap the unchanged root.

### Device re-enrollment

Use a valid password/recovery/root session to create a new platform slot. Remove the old slot only after the new one verifies.

### Collection rotation

Create `SecurityCollectionKey[epoch+1]`, write/verify its root envelope, then rewrap object keys. Old epochs remain readable only as long as policy requires.

### Object-key rotation

Required when an object key is suspected compromised or a construction changes in a way that cannot be solved by envelope rewrap. This re-encrypts object streams.

### Root rotation

Rare and expensive at the key-management layer but does not require media re-encryption: rewrap all collection/catalog/identity domains transactionally. It requires complete inventory and recovery planning.

## 10. Deletion and crypto-erasure

An object becomes cryptographically inaccessible only after every reachable envelope for its object key is destroyed, including:

- active catalog rows;
- WAL/journal/snapshots after compaction policy;
- backups;
- synced devices;
- collection grants;
- exported packages.

Chur cannot force an authorized recipient to erase previously obtained keys or plaintext.

## 11. Real and decoy hierarchy

Real and decoy vaults start with separate random root secrets. No derived label, collection key, object key, platform alias, recovery secret, or identity key is shared.

```text
RealRoot   ── independent tree
DecoyRoot  ── independent tree
```

## 12. Implementation requirements

- secret types use fixed-size buffers where practical;
- randomness failures abort creation;
- secret material never enters general serialization;
- no default `Debug` for secret-bearing structs;
- key handles are scoped to a session generation;
- all derivation and wrapping operations have deterministic vectors;
- unsupported key sizes or labels fail closed.
