# ADR-0010: Define the Canonical Tuple and Freeze the HKDF Extract Salt

- **Status:** Accepted
- **Date:** 2026-08-27
- **Related:** [`../format/CANONICAL_ENCODING_V1.md`](../format/CANONICAL_ENCODING_V1.md), [`../CRYPTOGRAPHY.md`](../CRYPTOGRAPHY.md), [`0008`](0008-freeze-object-container-v1-layout.md)

## Context

`CRYPTOGRAPHY.md` used `CanonicalTuple(...)` normatively for every password-slot AAD, key envelope, manifest AAD, chunk AAD, final-commit AAD, and HKDF `info`, but `CANONICAL_ENCODING_V1.md` never defined the construct. It defined ordered fixed structures in §4 and nothing else, so whether the leading literal was a length-prefixed UTF-8 string under §2 or a bare ASCII constant under §3 and §7 was undecided. Two implementations could produce different AAD bytes for the same record and neither would be wrong.

Two tag styles were in use: `OBJECT_CONTAINER_V1.md` froze `CHUR\x00OBJECT\x00MANIFEST-COMMITMENT\x00V1`, while `CRYPTOGRAPHY.md` wrote `chur/object-chunk/v1`. Separately, the HKDF extract salt read "fixed profile salt or all-zero SHA-256-length salt" and was deferred to vectors that cannot be generated without it, while every derived key in the hierarchy depends on it.

## Decision

- a domain tag is a bare ASCII byte constant, written as its exact registered bytes with no length prefix and no terminator, consistent with the frozen commitments in `OBJECT_CONTAINER_V1.md` §5 and §10;
- `CanonicalTuple` is not a distinct construct: it is a §4 structure whose first field is the domain tag, with no element count, no separate schema-version field, no separators, and no terminator. The version suffix in the tag is the schema version;
- each element after the tag is one §2 primitive. A fixed-width or fixed-length element carries no prefix, a variable-length element carries its `u32` length, and a group such as the Argon2 public parameters is written as one element per value;
- no registered domain tag may be a byte prefix of another. With the tag-selected element list this makes tuple encoding injective;
- the tuple tags in `CRYPTOGRAPHY.md` are normalized to the frozen `CHUR\x00AREA\x00RECORD\x00V1` style;
- the HKDF-SHA-256 extract salt is 32 bytes of `0x00`, the RFC 5869 default, identical for every vault, platform, profile, and derivation. Domain separation stays entirely in `info`.

## Alternatives considered

- **Length-prefix the domain tag.** Rejected: `OBJECT_CONTAINER_V1.md` §5 and §10 already hash bare tags in frozen constructions, so a prefix would either contradict them or split tag encoding into two rules.
- **Encode an element count in every tuple.** Rejected: the tag already selects exactly one element list, so a count buys no injectivity, and it would need a resolved count for the password-slot tuple whose Argon2 parameter expansion is still open.
- **Use a random fixed profile salt.** Rejected: every input keying material here is already a high-entropy secret, so an extract salt adds nothing that `info` does not, and it would be one more constant to distribute, version, and get wrong.

## Consequences

### Positive

- AAD and `info` bytes are computable, so the first cryptographic vectors can be generated;
- one tag style across every document, and prefix-freeness is a unit test over the registry rather than a review habit.

### Tradeoffs

- adding an element to a v1 tuple is a new tag and a new version, never an in-place edit;
- tag constants are longer than the slash form, at most 4 extra bytes per authenticated record; three of the six converted tags are the same length.

## Security impact

Affected invariants: SEC-005, SEC-014, SEC-048.

Injectivity is the property that matters. Prefix-free tags plus a tag-selected element list mean no two distinct authenticated contexts can share AAD bytes, which is what prevents cross-context substitution between slots, envelopes, streams, and revisions. The zero salt is not a weakening: HKDF-Extract with a zero salt is the RFC 5869 default, and every derivation binds a purpose label and explicit context fields through `info`.

## Compatibility impact

No vault bytes exist yet, so nothing migrates. A change to any tag, any element list, or the salt changes every dependent key and AEAD tag, and requires a new tag version and a dual-reader policy, never a redefinition of v1.

## Validation

- vectors for a tuple carrying a fixed-length and a variable-length element, including empty and maximum-length variable elements;
- a registry test that no tag is a byte prefix of another;
- HKDF vectors from a known parent secret to each label under the frozen salt, consumed by Rust, Android, iOS, and the CLI.

## Follow-up

- publish vectors for every tag allocated in `format/CANONICAL_ENCODING_V1.md` §15.5, giving the exact bytes of each tuple per record;
- freeze the element list of every tuple, including the Argon2 parameter expansion in the password-slot AAD.
