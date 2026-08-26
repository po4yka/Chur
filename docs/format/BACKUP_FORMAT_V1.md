# Chur Backup Format v1

> **Status:** Proposed portable backup contract

The backup format packages a complete or incremental encrypted vault for user-controlled storage and cross-device restore. It is distinct from the live vault layout and from future sync transport.

## 1. Goals

- portable across Android, iOS, and CLI;
- no device-bound Keystore/Keychain dependency;
- authenticate inventory and completeness;
- preserve immutable object containers without decrypting them;
- include a recoverable root envelope;
- support streaming creation and restore;
- fail safely under truncation, reordering, or stale manifests.

## 2. Package model

```text
BackupPackageV1
├── PublicBackupPreamble
├── EncryptedBackupManifest
├── PortableVaultDescriptor
├── EncryptedCatalogSnapshot / canonical catalog export
├── ObjectContainer entries
├── ObjectKey/Collection envelopes
├── Optional incremental operation segment
└── AuthenticatedFinalBackupCommit
```

The physical outer framing may be a Chur-native archive. An optional `age` envelope may wrap the native package but does not replace its internal inventory/completeness semantics.

## 3. Portable slots

Included:

- password slot and parameters when configured;
- recovery slot ciphertext;
- portable recipient envelope explicitly selected by user.

Excluded:

- Android Keystore aliases/keys;
- iOS `ThisDeviceOnly` Keychain items;
- local session handles;
- local device-only caches and transfer state;
- private identity key unless the multi-device recovery design explicitly includes a wrapped portable copy.

## 4. Backup manifest

Encrypted manifest includes:

```text
backup_id
backup_version
source_vault_id or privacy-preserving bound identity
created_time metadata
base_backup_id for incremental
catalog generation/schema
object inventory: IDs, versions, ciphertext lengths, commitments
slot inventory
operation-log heads when applicable
required free-space/restore policy metadata
```

The manifest is encrypted/authenticated under a dedicated root-derived backup key or portable backup content key.

## 5. Full backup

A full backup includes every active object/container/envelope required to reconstruct the chosen vault identity and its catalog state. Completeness verification checks every inventory entry and final commit before activation.

## 6. Incremental backup

Incremental support is future/proposed. It must define:

- authenticated base backup ID/commitment;
- changed/new object inventory;
- tombstones/deletions;
- catalog operation segment;
- maximum chain length and compaction;
- restore ordering and missing-base failure;
- rollback/stale incremental handling.

An incremental must never silently restore without its exact authenticated base.

## 7. Streaming creation

```text
open consistent catalog snapshot
write temp package
write encrypted manifest placeholder or streaming manifest records
copy ciphertext containers without plaintext
compute ordered inventory commitment
write authenticated final backup commit
fsync destination
verify structural completeness
atomic finalize where destination supports it
```

When writing to a non-atomic external provider, incomplete state remains explicitly marked and is never advertised as complete.

## 8. Restore transaction

1. parse public preamble and enforce limits;
2. obtain password/recovery/recipient factor;
3. authenticate manifest and root context;
4. verify package/inventory completeness;
5. restore to temporary app-private namespace;
6. validate catalog and object-key references;
7. verify object final commits at the required level;
8. create new local platform key slot;
9. commit local vault descriptor atomically;
10. clean temporary state.

## 9. Confidentiality and leakage

The outer package may reveal:

- total backup size;
- creation/modification time from filesystem/provider;
- number/size of outer records if not fully wrapped;
- format version.

A single encrypted outer stream or `age` wrapper can reduce record-level leakage but does not hide total size or access timing.

## 10. Rollback

A backup can be authentic but old. The package carries generation/log heads, but detecting restoration of a deliberately old offline backup requires user awareness or an external trusted checkpoint. Restore UI must show safe, decrypted backup metadata only after authentication.

## 11. Real and decoy

Default: one package contains one vault identity. Combining real and decoy in one manifest leaks the sibling relationship and is forbidden without an explicit alternate design.

## 12. Recovery rotation

Old backups retain old portable slots. Rotating a password or recovery secret does not revoke old backup packages. Users must replace/delete old backups if that guarantee matters.

## 13. Limits

- package and manifest version policy;
- maximum inventory entries and manifest bytes;
- maximum object/container size and record count;
- checked aggregate sizes;
- nesting/extension limits;
- no decompression bombs;
- KDF limits before work;
- temporary disk-space checks.

## 14. Test vectors

- minimal empty vault;
- mixed photo/video/audio objects;
- complete and truncated package;
- reordered/duplicated/missing object entries;
- wrong password/recovery;
- incremental with correct/wrong/missing base;
- old catalog/object versions requiring migration;
- Android→iOS→CLI restore;
- no device-bound slot imported;
- real/decoy package isolation;
- interrupted external-provider write.
