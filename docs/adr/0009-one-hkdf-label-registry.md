# ADR-0009: One HKDF Label Registry

- **Status:** Accepted
- **Date:** 2026-08-27
- **Related:** [`../security/KEY_HIERARCHY.md`](../security/KEY_HIERARCHY.md), [`../CRYPTOGRAPHY.md`](../CRYPTOGRAPHY.md), [`../format/TEST_VECTORS.md`](../format/TEST_VECTORS.md)

## Context

Four documents listed HKDF domain labels for the same keys and no two lists agreed. The collection-envelope root key had three spellings (`chur/v1/root/collection-wrap`, `chur/v1/root/collection-envelope`, `chur/v1/collection/wrap-root`), `ARCHITECTURE.md` dropped the `/root/` segment from six labels, `catalog-record` and `identifier` differed from `catalog-records` and `identifiers` by one letter, the poster key was `poster-frame` in one document and `video-poster` in another, and three labels existed in exactly one document each (`ocr` and `embedding` only in `KEY_HIERARCHY.md`, `local-fingerprint` only in `CRYPTOGRAPHY.md`). The same root-derived key was named `CollectionEnvelopeRootKey`, `CollectionWrapRootKey`, and `CollectionWrapRoot`. A label is an HKDF input, not documentation: each spelling produces different key bytes, and vectors cannot be generated while one constant has three values. No vault bytes exist yet, so choosing now costs nothing and choosing after the first vault costs a re-encryption migration.

## Decision

`security/KEY_HIERARCHY.md` §3 is the sole registry: one table giving each label, the key it derives, its input key, and its output length, followed by the naming rules and the change rule. `CRYPTOGRAPHY.md`, `ARCHITECTURE.md`, and the root `README.md` keep the prose that explains why domain separation exists and point at the registry instead of restating the strings.

The registry holds the union of the four lists, with these resolutions:

- `chur/v1/root/collection-envelope`, matching the derivation already written in `CRYPTOGRAPHY.md` §25;
- every root-derived label keeps the `/root/` tier segment, and the tier segment names the input key;
- plural for a class of records (`catalog-records`, `identifiers`), singular for one named artifact (`catalog-database`, `backup-manifest`);
- `chur/v1/object/poster-frame`, because the sibling derived-asset labels `thumbnail`, `preview`, and `waveform` name the asset rather than the media type it comes from;
- `chur/v1/root/local-fingerprint`, moved from the object tier: a fingerprint derived under a per-object random key cannot match two objects with identical content, which is the only purpose the key has;
- one name per key, `CollectionEnvelopeKey` and `IdentityWrapKey`.

A label is never redefined. A change of purpose, tier, or spelling is a new label plus a migration, and the old label stays in the registry and in the vectors until no reachable data depends on it.

## Alternatives considered

### Keep four lists and synchronize them during review

Rejected. That process produced the four divergent lists, and every new label multiplies the copies.

### Make `CRYPTOGRAPHY.md` the registry

Rejected. The authority hierarchy in `docs/README.md` places focused security specifications above `CRYPTOGRAPHY.md`, and key derivation is the subject of `KEY_HIERARCHY.md`.

## Consequences

### Positive

- one constant per key, so vectors and independent implementations can agree;
- labels that existed in a single document are preserved instead of lost in a merge.

### Tradeoffs

- three documents now depend on a link into a section of a fourth;
- the registry mixes tiers in one table, which reads longer than a per-tier list.

## Security impact

Affected invariant: SEC-005. Dropping `ocr`, `embedding`, or `local-fingerprint` during reconciliation would have removed a key domain and moved its data onto a neighboring key, which is the reuse SEC-005 exists to prevent. Keeping `local-fingerprint` under a dedicated root-derived key preserves duplicate detection without giving an unkeyed plaintext hash a global identity.

## Compatibility impact

No vault bytes exist, so nothing migrates and the discarded spellings were never implemented. After the first vault is created, the table changes only by adding a label or by the migration rule above.

## Validation

- a positive vector for every registry row;
- a repository check that fails when a `chur/v1/` label string appears outside the registry and the format specifications that consume it.
