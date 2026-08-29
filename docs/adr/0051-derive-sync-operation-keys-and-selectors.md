# ADR-0051: Derive Sync Operation Keys and Opaque Selectors

- **Status:** Accepted
- **Date:** 2026-08-29
- **Decision owners:** @po4yka
- **Related:** [`../sync/OPERATION_LOG.md`](../sync/OPERATION_LOG.md), [`../security/KEY_HIERARCHY.md`](../security/KEY_HIERARCHY.md), [`../format/CATALOG_SCHEMA_V2.md`](../format/CATALOG_SCHEMA_V2.md), [`0049`](0049-add-sync-state-in-catalog-v2.md)

## Context

`OperationV1` carries an opaque 16-byte `key_selector`, but the protocol did not define how devices obtain the same selector or the AEAD key. A random selector stored only on its creating device cannot be recovered after restart and cannot reach another device because `CreateCollectionEpoch` is encrypted under the previous epoch. Catalog v2 also has no selector directory. Encrypting payloads directly with a root or collection key would reuse one key across protocol domains.

## Decision

- add four HKDF labels to the only registry in `KEY_HIERARCHY.md`:
  - `chur/v1/root/sync-operations` derives `RootSyncOperationKey` from `VaultRootSecret` with context `vault_id`;
  - `chur/v1/root/sync-selector` derives `RootSyncSelectorMaterial` from `VaultRootSecret` with context `vault_id`;
  - `chur/v1/collection/sync-operations` derives `CollectionSyncOperationKey[epoch]` from `SecurityCollectionKey[epoch]` with context `collection_id, collection_epoch`;
  - `chur/v1/collection/sync-selector` derives `CollectionSyncSelectorMaterial[epoch]` from `SecurityCollectionKey[epoch]` with the same context;
- the applicable sync operation key is the complete 32-byte output of the operation-key derivation;
- the opaque selector is the first 16 bytes of the separate selector-material output. If those bytes are all zero, the last selector byte is set to `0x01`, so the value is always a valid protocol `Id`;
- root-domain operations use the root derivations. Every other operation uses the derivations for the collection and epoch inside its authenticated payload;
- after unlock, a client derives the root entry and one entry for every locally available collection-key epoch. It rejects any selector collision that maps to different domains;
- the selector directory and derived operation keys are session memory. Catalog v2 stores the wrapped source keys and accepted operation bytes, so it stores no duplicate selector or derived-key table;
- collection rotation changes both collection outputs because the input key and epoch change. Old entries remain available while their wrapped collection keys remain available for accepted history;
- no selector, operation key, root secret, or collection key is sent to the server.

## Alternatives considered

### Persist random selectors in catalog v2

Rejected. A new selector must also reach every peer, which would change the frozen collection-epoch payload. The catalog row would duplicate state that all authorized devices can derive from the key they already need.

### Use the operation key bytes as the selector

Rejected. Publishing half of an AEAD key is unnecessary key exposure and couples routing to encryption.

### Encrypt directly with root or collection keys

Rejected. The same parent key already owns other wrap and metadata domains. A dedicated HKDF label is the required domain boundary.

## Consequences

### Positive

- every authorized device reconstructs the same selector directory after restart without a new wire field;
- selector rotation follows collection-key rotation automatically;
- routing material and AEAD keys are domain-separated;
- catalog v2 remains the one durable sync authority.

### Tradeoffs

- unlock derives selector and operation-key entries for every retained collection epoch;
- a selector collision is a fatal local security error, although its probability is bounded by a 128-bit output;
- root-domain operation grouping remains visible for the life of one root secret, as the server leakage model already states.

## Security impact

Affected invariants: SEC-040 and SEC-042. The server sees only pseudorandom selectors. Separate labels prevent a selector from exposing operation-key bytes and prevent sync payload encryption from reusing root, envelope, or metadata keys.

## Compatibility impact

No released sync operation exists. The outer record and payload bytes do not change. Implementations that guessed a random or direct-key selector algorithm are incompatible and must adopt these derivations before they emit v1 operations.

## Validation

- byte-exact root and collection derivation vectors;
- different vault, collection, epoch, purpose, or parent key produces a different output;
- selector and operation key never share bytes from one HKDF output;
- the all-zero normalization produces a valid non-zero selector;
- two devices with the same wrapped source keys rebuild the same directory;
- unknown and colliding selectors fail closed.
