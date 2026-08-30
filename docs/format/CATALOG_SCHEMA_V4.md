# Private Catalog Schema v4

> **Status:** Accepted normative physical schema extension

Catalog v4 extends [`CATALOG_SCHEMA_V3.md`](CATALOG_SCHEMA_V3.md) with durable collection-operation streams. All tables stay in the same SQLCipher database and transaction domain.

## 1. Stream domains

`sharing_operation_streams` binds one opaque epoch selector to one security collection:

```text
key_selector:bytes[16] primary key
collection_id:bytes[16]
collection_epoch:u64
```

The collection and epoch pair is unique. A selector collision with another pair fails closed.

## 2. Accepted operations

`sharing_operations` stores one complete canonical `CollectionOperationV1` and checked projections:

```text
key_selector:bytes[16]
issuer_identity_vault_id:bytes[16]
issuer_device_id:bytes[16]
device_sequence:u64
operation_id:bytes[16]
digest:bytes[32]
record:variable-bytes
```

The primary key is `(key_selector, issuer_identity_vault_id, issuer_device_id, device_sequence)`. `operation_id` is unique in one selector stream. Exact replay is idempotent. Different bytes at one position or identifier reuse fail closed.

## 3. Fork evidence

`sharing_operation_forks` is keyed by selector and issuer pair. It stores state `1` for detected or `2` for acknowledged, the accepted signed record, and the conflicting signed record. A stored fork keeps that participant stream frozen after restart.

## 4. Migration

The only new step is `v3 -> v4`. It creates empty stream, operation, fork, and index structures, then changes `vault_state.catalog_format_version` to `0x0004` in the same transaction. Earlier catalogs complete every preceding migration in order.

## 5. Invariants

- protocol records are decoded, signature-checked, authorized, and causally accepted before insertion;
- catalog load decodes each record and checks every projection and digest;
- accepted records replay in selector, issuer-pair, and sequence order;
- one transaction stores a newly accepted record and its materialized content effect;
- historical epochs remain evidence and never authorize access to a current selector;
- collection keys and private identity keys do not enter the new tables.
