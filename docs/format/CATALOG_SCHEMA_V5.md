# Private Catalog Schema v5

> **Status:** Accepted normative physical schema extension

Catalog v5 extends [`CATALOG_SCHEMA_V4.md`](CATALOG_SCHEMA_V4.md) with one durable client-security column. All tables stay in the same SQLCipher database and transaction domain.

## 1. Grant-acceptance freeze

`sharing_collections` gains one column:

```text
grants_frozen:integer, 0 or 1, default 0
```

[`COLLECTION_MEMBERSHIP.md`](../sync/COLLECTION_MEMBERSHIP.md) §5 says that reusing one grant identifier with different bytes freezes grant acceptance for that collection as a security conflict. A freeze that one reopen forgets protects nobody, so the state is durable: the client records it in the same transaction that rejects the conflicting grant, and a reopened vault refuses every new grant for a frozen collection. Identical replay of an already-stored grant stays idempotent.

The column is client state about a detected conflict. It carries no canonical meaning, never enters an authenticated record, and is never set by any protocol rule other than the observed identifier reuse. Unfreezing is a user decision outside the protocol; no v1 surface offers it.

## 2. Migration

The only new step is `v4 -> v5`. It adds the column with its default and its `0`/`1` check, then changes `vault_state.catalog_format_version` to `0x0005` in the same transaction. Earlier catalogs complete every preceding migration in order. Every existing collection reads as unfrozen.

## 3. Invariants

- the freeze is recorded atomically with the rejection of the conflicting grant;
- a frozen collection accepts no new grant while the freeze stands;
- the freeze survives process death and reopen, because it lives in the collection row the grant projection already guards.

## 4. Tests

- a new catalog installs version five with the freeze column present;
- the `v4 -> v5` migration reaches version five and accepts only `0` or `1`;
- a grant identifier reused with different bytes records the freeze and rejects the grant;
- a frozen collection refuses a different new grant while identical replay stays `Duplicate`.
