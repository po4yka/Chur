# ADR-0025: Freeze the Object-Key Envelope AAD

- **Status:** Accepted
- **Date:** 2026-08-27
- **Decision owners:** @po4yka
- **Related:** [`../format/OBJECT_KEY_ENVELOPE_V1.md`](../format/OBJECT_KEY_ENVELOPE_V1.md), [`0003`](0003-separate-object-key-envelope.md), [`../format/COLLECTION_KEY_ENVELOPE_V1.md`](../format/COLLECTION_KEY_ENVELOPE_V1.md)

## Context

One authenticated tuple was written three ways. `CRYPTOGRAPHY.md` §28 bound `object_id`, `collection_id`, `epoch`, `object_key_version`, `envelope_generation`, and `suite_id`. `OBJECT_KEY_ENVELOPE_V1.md` §3 named a different set in prose that included the vault ID. `CANONICAL_ENCODING_V1.md` §4 carried a third order under the name `ObjectEnvelopeAADV1`. `object_key_version` appears in no record layout, and the version an implementer was most likely to copy omitted `vault_id`, so an envelope lifted from a sibling vault with the same collection, epoch, and object identifiers would still authenticate.

## Decision

[`../format/OBJECT_KEY_ENVELOPE_V1.md`](../format/OBJECT_KEY_ENVELOPE_V1.md) §3 owns the tuple and it is:

```text
CanonicalTuple(
    "CHUR\x00OBJECT\x00KEY-ENVELOPE\x00V1",
    vault_id:bytes[16],
    collection_id:bytes[16],
    collection_epoch:u64,
    object_id:bytes[16],
    suite_id:u16,
    envelope_generation:u64
)
```

Exactly 93 bytes: a 27-byte tag, then 16, 16, 8, 16, 2, and 8. `vault_id` is inside, as the sibling record of `COLLECTION_KEY_ENVELOPE_V1.md` §3 already requires. `format_version` and `encoding_profile` are outside, because §1 compares both as constants before the AEAD runs. `object_key_version` does not exist: a rewrap of the same key is an `envelope_generation` increase and a new key is a new object. `CRYPTOGRAPHY.md` §28 and `CANONICAL_ENCODING_V1.md` §4 now point at §3 instead of restating it.

## Alternatives considered

- **Put `encoding_profile` inside the AAD.** Rejected: the profile selects the encoding of the AAD itself, so a reader that mis-parsed it could not compute the tag under either choice, and §1 compares it before the AEAD runs. Leaving it out keeps the two envelope tuples parallel.
- **Keep `object_key_version` and add it to the record layout.** Rejected: `envelope_generation` already versions the envelope, and a second counter with no reader is one more field two implementations can disagree about.

## Consequences

### Positive

- one element list with widths and order, so the §12 vectors are generatable, and an envelope is bound to its vault, so cross-vault substitution fails authentication.

### Tradeoffs

- the record stays 142 bytes and its AAD 93; binding `vault_id` costs 16 of them on every object envelope.

## Security impact

Affected invariants: SEC-014, SEC-036. Binding `vault_id` is what makes a real and a decoy envelope store non-interchangeable, which [`0005`](0005-real-and-decoy-vault-isolation.md) assumes and [`0003`](0003-separate-object-key-envelope.md) promises.

## Compatibility impact

No envelope bytes exist yet, so nothing migrates. A later change to the element list is a new `format_version`, never a redefinition of v1.

## Validation

- a deterministic valid envelope with its exact 93 AAD bytes, and negative vectors for a wrong vault, collection, epoch, object, suite, and generation;
- a vector proving a v1 AAD is unchanged by `encoding_profile`.

## Follow-up

- generate the vectors above with the first `chur-cli` vector generator.
