# Sync Operation Payload v1

> **Status:** Accepted normative byte-level specification

This document defines the plaintext authenticated and encrypted by `OperationV1`. The server never receives these fields in plaintext. Encoding follows [`../format/CANONICAL_ENCODING_V1.md`](../format/CANONICAL_ENCODING_V1.md), with fields in the listed order and no padding, compression, or trailing bytes.

[`COLLECTION_OPERATION_LOG.md`](COLLECTION_OPERATION_LOG.md) reuses the same payload encoding for shared content kinds `0x01` through `0x0C` and `0x10`. It uses its own outer AAD and signature contract.

## 1. Common header

```text
payload_version:u16        = 0x0001
operation_kind:u8
collection_id:bytes[16]
collection_epoch:u64
kind_body:bytes[remaining]
```

`collection_id` and `collection_epoch` must resolve to the opaque outer `key_selector`. Kinds `0x0D` and `0x0E` are root-domain operations: `collection_id` equals the outer `vault_id` and `collection_epoch` is zero. Every other kind requires a non-zero collection epoch. Unknown versions, kinds, selectors, or cross-field mismatches fail closed.

## 2. Kind registry and bodies

| Value | Kind | Exact body |
| --- | --- | --- |
| `0x01` | `CreateObject` | `object_id:bytes[16]`, `object_generation:u64`, `store_id:bytes[16]`, `stream_id:bytes[16]`, `metadata_fields:MetadataFieldsV1` |
| `0x02` | `CommitObject` | `object_id:bytes[16]`, `object_generation:u64`, `store_id:bytes[16]`, `container_length:u64`, `container_commitment:bytes[32]`, `object_key_envelope:bytes[142]` |
| `0x03` | `UpdateMetadata` | `object_id:bytes[16]`, `object_generation:u64`, `field:MetadataFieldV1` |
| `0x04` | `CreateAlbum` | `album_id:bytes[16]`, `name:utf8` |
| `0x05` | `RenameAlbum` | `album_id:bytes[16]`, `name:utf8` |
| `0x06` | `AddAlbumMembership` | `album_id:bytes[16]`, `object_id:bytes[16]` |
| `0x07` | `RemoveAlbumMembership` | `album_id:bytes[16]`, `object_id:bytes[16]`, `removed_tokens:TokenSetV1` |
| `0x08` | `SetFavorite` | `object_id:bytes[16]`, `favorite:boolean`, `removed_tokens:TokenSetV1` |
| `0x09` | `AddTag` | `tag_id:bytes[16]`, `object_id:bytes[16]`, `name:utf8` |
| `0x0A` | `RemoveTag` | `tag_id:bytes[16]`, `object_id:bytes[16]`, `removed_tokens:TokenSetV1` |
| `0x0B` | `DeleteObject` | `object_id:bytes[16]`, `object_generation:u64`, `authored_at_ms:u64` |
| `0x0C` | `RestoreObject` | `object_id:bytes[16]`, `tombstone_operation_id:bytes[16]`, `new_object_generation:u64` |
| `0x0D` | `AddDevice` | `enrollment_record:bytes[270]` |
| `0x0E` | `RevokeDevice` | `revocation_record:bytes[194]` |
| `0x0F` | `CreateCollectionEpoch` | `previous_collection_epoch:u64`, `membership_generation:u64`, `collection_key_envelope:bytes[126]` |
| `0x10` | `RewrapObjectKey` | `object_id:bytes[16]`, `object_key_envelope:bytes[142]` |
| `0x11` | `ChangeCollectionMembership` | `membership_record:bytes[292]` |
| `0x12` | `IssueCollectionGrant` | `collection_grant:bytes[309]` |

All identifiers are non-zero random identifiers. `stream_id` names the primary original stream and is required to derive the manifest key and AAD before its sealed manifest can be opened. Every generation and every non-root epoch is non-zero and must have a successor, so `u64::MAX` is invalid. `container_length` is non-zero; the transfer service applies its configured object-size quota before reserving storage.

`CommitObject` activates a server object only after the downloaded ciphertext validates against its container and object-key envelope. `CreateObject` alone never makes media presentable.

`AddDevice` and `RevokeDevice` carry the complete signed membership records of [`DEVICE_IDENTITY.md`](DEVICE_IDENTITY.md) §4 and §9. Their `created_sequence` or issuer, membership generation, and vault binding must agree with the containing operation. `CreateCollectionEpoch` and `RewrapObjectKey` carry the complete canonical envelope records of the format specifications; all repeated collection, epoch, object, and vault values must agree.

`ChangeCollectionMembership` carries the complete record of [`COLLECTION_MEMBERSHIP.md`](COLLECTION_MEMBERSHIP.md). Its collection, issuer identity vault, issuer device, creation sequence, and pre-change epoch agree with the containing operation. The source vault can differ from the operation vault when an external `MANAGE_MEMBERS` recipient authors the change. `IssueCollectionGrant` carries the complete grant of [`COLLECTION_GRANTS.md`](COLLECTION_GRANTS.md). Its grant identifier equals the containing operation identifier; its collection, sender device, creation sequence, and collection epoch agree with the operation and selected key domain. The outer vault identifies the sender identity. The grant source vault is checked against accepted collection membership, so an external `MANAGE_MEMBERS` recipient can issue a grant without being misidentified as the collection owner.

## 3. Metadata fields

`MetadataFieldsV1` is a `u32` count followed by `MetadataFieldV1` values sorted by ascending `field_id`, with no duplicate. It contains at most 32 fields and at most 262,144 encoded value bytes in total.

```text
MetadataFieldV1 =
    field_id:u16
    value:variable-bytes
```

| `field_id` | Meaning | Canonical value bytes | Maximum |
| --- | --- | --- | --- |
| `0x0001` | original filename | strict UTF-8, no inner length | 4096 bytes |
| `0x0002` | media type | lowercase RFC token, `/`, lowercase RFC token | 255 bytes |
| `0x0003` | capture time | one big-endian `u64` milliseconds value | 8 bytes |
| `0x0004` | caption | strict UTF-8, no inner length | 65,536 bytes |
| `0x0005` | rating | one byte `0x00` through `0x05` | 1 byte |

An empty string clears a string field. Unknown field identifiers, invalid UTF-8/ASCII, a MIME string without exactly one `/`, wrong fixed length, and a value over its bound fail closed. Import time is local receipt state and is not synchronized. Additional normalized media properties need allocated field identifiers and exact value schemas before use.

`UpdateMetadata` carries exactly one field. Causally later values supersede earlier values; concurrent values retain conflict evidence and display the operation with the greater operation digest.

## 4. Observed-remove tokens

`TokenSetV1` is a `u32` count followed by 16-byte operation identifiers sorted in ascending byte order, with no duplicate and at most 256 entries.

The add token for album membership, favorite `true`, or a tag is the containing add operation's outer `operation_id`; it is not repeated in the payload. A remove lists exactly the add tokens the author observed. `SetFavorite(true)` requires an empty token set. `SetFavorite(false)` requires a non-empty token set. A concurrent add not listed by a remove survives.

## 5. Delete and restore

`DeleteObject` creates the tombstone named by the outer `operation_id`. Its `authored_at_ms` is a signed retention hint only. It never participates in happens-before, tie-break, authorization, expiry of keys, or checkpoint acceptance. A receiver clamps negative apparent age to zero and does not compact before its own clock reaches the signed time plus the required retention interval.

`RestoreObject` must name the currently visible tombstone and increment the last accepted object generation by exactly one. A metadata edit, membership add, favorite, or tag operation never restores an object. Delete wins visibility when concurrent with any of them, while their state remains available to an explicit later restore.

## 6. Parser limits

- common header: exactly 27 bytes;
- album and tag names: strict UTF-8 from 1 through 4096 encoded bytes;
- metadata field count: at most 32;
- metadata encoded value bytes: at most 262,144 in total;
- removed-token count: at most 256;
- nested records: exact lengths in §2;
- whole plaintext: at most 1,048,576 bytes, inherited from [`OPERATION_LOG.md`](OPERATION_LOG.md) §12.

A parser validates every count and length before allocation, rejects trailing bytes, and validates every nested canonical record before application.
