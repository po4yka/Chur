# ADR-0003: Separate Object-Key Envelopes from Media Containers

- **Status:** Accepted
- **Date:** 2026-08-26
- **Related:** [`../format/OBJECT_KEY_ENVELOPE_V1.md`](../format/OBJECT_KEY_ENVELOPE_V1.md), [`../format/OBJECT_CONTAINER_V1.md`](../format/OBJECT_CONTAINER_V1.md)

## Context

An object's random key must be recoverable through a collection key, while the media container's manifest/content keys derive from that object key. Placing the wrapped object key inside a manifest encrypted by an object-derived key creates a circular dependency. Embedding collection-specific wrapping in a large container also makes collection moves/rotation mutate media bytes.

## Decision

Store `ObjectKeyEnvelopeV1` separately from immutable `ChurObjectV1`.

```text
CollectionKey → ObjectKeyEnvelope → ObjectKey
ObjectKey → manifest/content/final-commit keys → immutable container
```

One object may have multiple envelopes for access domains, epochs, or migration.

## Alternatives considered

### Wrapped object key in encrypted manifest

Rejected: circular dependency if manifest key derives from object key.

### Manifest encrypted directly with collection key

Rejected: couples immutable media container to current collection and complicates multiple collections/sharing.

### Re-encrypt media on every collection change

Rejected: expensive and unnecessary for gigabyte objects.

## Consequences

### Positive

- no key-discovery cycle;
- collection move/rotation rewraps 32-byte key only;
- multiple access domains supported;
- media container remains content-immutable;
- simpler backup/sync object deduplication at ciphertext level within one vault policy.

### Tradeoffs

- catalog must maintain envelope/container referential integrity;
- missing envelope and corrupt container are distinct failure modes;
- crypto-erasure inventory must consider every envelope.

## Security impact

AAD binds envelope to vault, collection, epoch, object, and generation. Active duplicate/stale envelopes are rejected by catalog policy.

## Compatibility impact

Envelope and container versions evolve independently. Backup packages must include both.

## Validation

- cross-collection rewrap tests;
- multiple-envelope vectors;
- dangling/missing/duplicate envelope reconciliation;
- no media-byte changes during collection rotation.
