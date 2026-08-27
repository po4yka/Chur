# ADR-0039: Freeze the Catalog Header Commitment

- **Status:** Accepted
- **Date:** 2026-08-27
- **Decision owners:** @po4yka
- **Related:** [`../format/VAULT_DESCRIPTOR_V1.md`](../format/VAULT_DESCRIPTOR_V1.md) §5, [`../format/CATALOG_SCHEMA_V1.md`](../format/CATALOG_SCHEMA_V1.md) §15, [`0038`](0038-adopt-sqlcipher-as-the-v1-catalog-engine.md)

## Context

[`../format/VAULT_DESCRIPTOR_V1.md`](../format/VAULT_DESCRIPTOR_V1.md) §5 gives the catalog sub-descriptor a 32-byte `catalog_header_commitment` and describes it as "BLAKE3-256". It names no input. The field is 32 bytes of a frozen 60-byte structure, so its width is decided and its meaning is not, and an implementation cannot write it without choosing an input.

The field is worth having. Without it the descriptor and the catalog are joined only by a path: a catalog file copied in from another vault, or an older copy of this vault's catalog restored underneath the current descriptor, fails at the first query that reads a row it does not recognise, or does not fail at all. With it the substitution fails at unlock.

An engine was needed before the input could be chosen, and [ADR-0038](0038-adopt-sqlcipher-as-the-v1-catalog-engine.md) chose one.

## Decision

`catalog_header_commitment` commits to the first 16 bytes of the catalog database file:

```text
catalog_header_commitment = BLAKE3-256(
    "CHUR\x00CATALOG\x00HEADER-COMMITMENT\x00V1" || catalog_file[0..16]
)
```

The tag is allocated in [`../format/CANONICAL_ENCODING_V1.md`](../format/CANONICAL_ENCODING_V1.md) §15.5 in the same change, as §15.6 requires.

Those 16 bytes are SQLCipher's per-database salt. SQLCipher writes it when the database is created, derives the page HMAC key from it, and never rewrites it, so it is stable for the life of the file and is not secret: it is the one part of the file that is plaintext by construction.

The commitment is computed when the catalog is created and checked at every unlock, before the connection is opened.

## Alternatives considered

### Commit to the whole first page

Rejected. The first page changes on every schema write, so the descriptor would need rewriting on every catalog transaction, which is a descriptor generation per write and a rollback surface [`../format/VAULT_DESCRIPTOR_V1.md`](../format/VAULT_DESCRIPTOR_V1.md) §10 does not want.

### Commit to a hash of the whole file

Rejected for the same reason, and worse: it would make unlock read the entire catalog.

### Store nothing and leave the field zero

Rejected. §8 of the descriptor specification authenticates the descriptor body, so a zero field would still be authenticated; it would simply authenticate the absence of a binding. A field that is present, frozen in width, and meaningless is worse than one with a defined input, because a later reader cannot tell the two apart.

### Derive the commitment from the catalog key

Rejected. The catalog key is a secret and the descriptor is readable before any credential exists, so a commitment over it would put a key-dependent value in a file an attacker holds. Committing to the salt commits to the file's identity without committing to anything secret.

## Consequences

### Positive

- a catalog file substituted from another vault, or an older copy restored underneath the descriptor, fails at unlock rather than at the first query that trips over it;
- the value is stable, so an ordinary catalog write does not touch the descriptor;
- the input is 16 bytes, so the check costs one open and one small read.

### Tradeoffs

- the commitment is engine-specific. A future engine that does not begin its file with 16 stable bytes needs a new `catalog_format_version` and a new input definition, which is the same change that would replace [ADR-0038](0038-adopt-sqlcipher-as-the-v1-catalog-engine.md);
- it proves the file's identity, not its contents. A catalog that was tampered with in place, keeping the salt, is caught by SQLCipher's per-page HMAC rather than here;
- restoring an older backup of the same catalog file keeps the salt, so it passes this check. Rollback of the catalog is [`../format/VAULT_DESCRIPTOR_V1.md`](../format/VAULT_DESCRIPTOR_V1.md) §10's problem through `catalog_generation`, not this field's.

## Security impact

Affected invariants: SEC-020, SEC-032.

The commitment is over public bytes and reveals nothing: the salt is already plaintext at the front of a file an attacker who has the file already holds.

## Compatibility impact

No v1 vault exists, so no descriptor with a differently defined value can be encountered. The field's width and position are unchanged.

## Follow-up

- the check is part of unlock, so it is exercised by every unlock test; a dedicated negative test substitutes a catalog file from a second vault and asserts `VAULT_CORRUPT`.
