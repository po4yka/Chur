# ADR-0019: Freeze the Remaining v1 Record Layouts

- **Status:** Accepted
- **Date:** 2026-08-27
- **Decision owners:** @po4yka
- **Related:** [`../format/OBJECT_CONTAINER_V1.md`](../format/OBJECT_CONTAINER_V1.md), [`../format/VAULT_DESCRIPTOR_V1.md`](../format/VAULT_DESCRIPTOR_V1.md), [`../format/COLLECTION_KEY_ENVELOPE_V1.md`](../format/COLLECTION_KEY_ENVELOPE_V1.md), [`0008`](0008-freeze-object-container-v1-layout.md), [`0011`](0011-freeze-vault-descriptor-authentication.md)

## Context

ADR-0008 froze the container's public bytes and left its two sealed plaintexts open; ADR-0011 froze the descriptor's authentication tag and left every descriptor field without a width. `COLLECTION_KEY_ENVELOPE_V1.md` did not exist at all, although the chain root to `CollectionEnvelopeKey` to `SecurityCollectionKey` to `ObjectEnvelopeKey` to `ObjectKey` passes through it. Three of the four records a Phase 1 import touches therefore had no encodable layout, and `CANONICAL_ENCODING_V1.md` §4 delegates order and width to exactly these owning specifications.

## Decision

- `CanonicalManifest` is a fixed field list of exactly 85 bytes for an original stream and 89 for a derived one; `source_content_revision` is an `optional<u32>` whose presence is a function of `stream_kind`;
- "immutable media properties permitted by policy" becomes `MediaPropertiesV1`, a closed 17-byte list of `media_class`, `pixel_width`, `pixel_height`, and `duration_ms`, and nothing mutable may appear there;
- `CanonicalFinalCommit` is a fixed field list of exactly 128 bytes with no optional;
- the vault descriptor gains a fixed 40-byte head, a typed body order, a 60-byte catalog sub-descriptor, a 24-byte object-store sub-descriptor, a 34-byte key-slot header with one length-prefixed body, and a 32-byte migration descriptor;
- `CollectionKeyEnvelopeV1` is a new byte-exact specification: a 126-byte record with the AAD tuple that `CRYPTOGRAPHY.md` §25 already stated;
- `stream_kind` and `media_class` discriminants are allocated in `CANONICAL_ENCODING_V1.md` §15.4.

## Alternatives considered

### A tagged extension block for the media properties

Rejected. `CANONICAL_ENCODING_V1.md` §6 already says core v1 security records should prefer fixed schemas, and a variable block would make the sealed manifest size unknown before decryption for no v1 use.

### Keep the key-slot public parameters and wrapped payload as separate fields

Rejected. Their shapes differ per family, so the descriptor parser would need every family schema to bound the descriptor. One length-prefixed body bounds them all.

## Consequences

### Positive

- every record a Phase 1 import writes now has one encoding, and both ends of the wrapping chain are specified at the same rigour;
- sealed record sizes are constants, so a reader validates a length before spending a decryption.

### Tradeoffs

- adding any media property later requires a new `container_version`;
- the descriptor head grows to 40 bytes, 12 more than the container preamble.

## Security impact

Affected invariants: SEC-007, SEC-013, SEC-018. The descriptor is parsed before any credential exists, so fixing its widths is what makes the bounds of `VAULT_DESCRIPTOR_V1.md` §13 checkable at all. Closing the media-property schema keeps mutable private metadata such as EXIF and GPS out of an immutable container that can never be rewritten.

## Compatibility impact

No persisted bytes exist. Version and profile identifiers remain the migration path.

## Validation

- byte-exact vectors for an original-stream manifest, a derived-stream manifest, and a final commit;
- descriptor vectors at the minimum and maximum slot counts, and collection-envelope vectors with wrong vault, collection, epoch, and generation AAD.

## Follow-up

- freeze the sealed field widths of `BackupManifestV1`;
- reconcile the manifest and final-commit AAD tuples of `CRYPTOGRAPHY.md` §32 and §38 with the container fields they now name.
