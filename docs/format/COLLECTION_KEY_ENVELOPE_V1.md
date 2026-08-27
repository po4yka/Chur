# Collection Key Envelope v1

> **Status:** Proposed normative format; the 126-byte record layout of §1 is frozen by [ADR-0019](../adr/0019-freeze-remaining-v1-record-layouts.md). Deterministic vectors are outstanding.

`CollectionKeyEnvelopeV1` wraps one random `SecurityCollectionKey[epoch]` under a root-derived envelope key. It is the second link of the wrapping chain root to `CollectionEnvelopeKey` to `SecurityCollectionKey` to `ObjectEnvelopeKey` to `ObjectKey`, so every object key in a vault is reachable only through one of these records.

It is a private catalog row, not a file, so it carries no magic; the catalog table of [`CATALOG_SCHEMA_V1.md`](CATALOG_SCHEMA_V1.md) §4 selects the record.

## 1. Structure

The record is exactly 126 bytes. Integers are unsigned big-endian per [`CANONICAL_ENCODING_V1.md`](CANONICAL_ENCODING_V1.md) §2.

```text
offset  size  field                      v1 value
0x00     2    format_version:u16         0x0001
0x02     2    encoding_profile:u16       0x0001
0x04     2    suite_id:u16               0x0001
0x06    16    vault_id                   random, never all zero
0x16    16    collection_id              random, never all zero
0x26     8    collection_epoch:u64       starts at 1
0x2E     8    envelope_generation:u64    starts at 1
0x36    24    nonce                      fresh 24 random bytes per seal
0x4E    48    wrapped_collection_key     32-byte key plus 16-byte tag
0x7E          end of record
```

V1 values for `format_version`, `encoding_profile`, and `suite_id` are allocated in [`CANONICAL_ENCODING_V1.md`](CANONICAL_ENCODING_V1.md) §15; the registry records the allocation and this section is the authority for these envelope bytes. A reader compares all three against the supported values before the AEAD runs, so a modified identifier fails as `UNSUPPORTED_VERSION` or `UNSUPPORTED_SUITE` and can never select a different construction. `suite_id` is additionally inside the AAD of §3.

## 2. Wrapping key

```text
CollectionEnvelopeKey = HKDF-SHA-256(
    IKM     = VaultRootSecret,
    label   = "chur/v1/root/collection-envelope",
    context = vault_id:bytes[16], collection_id:bytes[16], collection_epoch:u64,
    length  = 32
)
```

The label is registered in [`../security/KEY_HIERARCHY.md`](../security/KEY_HIERARCHY.md) §3; the extract and expand construction and the `info` tuple are [`../CRYPTOGRAPHY.md`](../CRYPTOGRAPHY.md) §13. The wrapping key is therefore per vault, collection, and epoch. It is derived on demand, used once per seal or open, and never persisted.

`SecurityCollectionKey` itself stays random. A collection key derived from the root could not be shared to a recipient or rotated without changing the root.

## 3. AAD

```text
aad = CanonicalTuple(
    "CHUR\x00COLLECTION\x00KEY-ENVELOPE\x00V1",
    vault_id:bytes[16],
    collection_id:bytes[16],
    collection_epoch:u64,
    suite_id:u16,
    envelope_generation:u64
)
```

This element list, in this order, is the only collection-key-envelope AAD, and [`../CRYPTOGRAPHY.md`](../CRYPTOGRAPHY.md) §25 defers to it. The tag is allocated in [`CANONICAL_ENCODING_V1.md`](CANONICAL_ENCODING_V1.md) §15.5 and the tuple encoding is §7.1 there, so the AAD is exactly 81 bytes: a 31-byte tag, then 16, 16, 8, 2, and 8.

`nonce` and `wrapped_collection_key` are not in the AAD: the nonce is an AEAD input and the ciphertext is what the tag already covers. `format_version` and `encoding_profile` are not in the AAD either, because §1 compares them as constants before the AEAD runs.

Substituting an envelope across vaults, collections, epochs, or generations must fail authentication.

## 4. Multiple envelopes

One collection may have more than one envelope when:

- a new epoch is created and the previous epoch's envelope is still needed;
- the envelope is being replaced under §5 and the previous generation has not yet been retired;
- migration temporarily retains an old and a new suite.

The catalog must define which envelope is active for each `(collection_id, collection_epoch)` pair and reject ambiguous duplicate `(collection_id, collection_epoch, envelope_generation)` tuples.

## 5. Generation

`envelope_generation` increases when the envelope for the same collection and epoch is replaced, for example after a root rewrap. The highest authenticated active generation wins under catalog transaction rules; a lower generation is stale and must not silently replace newer state. It is a persisted counter, listed in the generation glossary of [`../README.md`](../README.md).

## 6. Creating an envelope

1. obtain the authenticated `VaultRootSecret` of the open session;
2. generate a fresh random 32-byte `SecurityCollectionKey`, or take the existing one when rewrapping;
3. derive `CollectionEnvelopeKey` for the vault, collection, and epoch;
4. generate a fresh 24-byte nonce from the Rust CSPRNG;
5. encode the §3 AAD;
6. seal the collection key;
7. zeroize the derived wrapping key;
8. write the envelope in a catalog transaction;
9. read the envelope back and open it before retiring any prior envelope.

## 7. Epoch rotation

During rotation, per [`../CRYPTOGRAPHY.md`](../CRYPTOGRAPHY.md) §26:

- a new random collection key is generated and sealed under the new epoch;
- new object envelopes use the new epoch immediately;
- existing object keys are rewrapped incrementally under [`OBJECT_KEY_ENVELOPE_V1.md`](OBJECT_KEY_ENVELOPE_V1.md) §8;
- the previous epoch's envelope stays available until every required object envelope is migrated and verified;
- crash recovery resumes without creating a second active generation.

## 8. Deletion

Destroying the only reachable envelope for an epoch makes every object key wrapped under that epoch unrecoverable, so it is the crypto-erasure lever for a whole collection. The catalog must enumerate object envelopes still bound to the epoch before claiming erasure, and a recipient grant that carries the same collection key is outside this record's reach.

## 9. Parser limits

- record exactly 126 bytes, with no trailing bytes;
- identifiers exactly 16 bytes, nonce exactly 24 bytes, `wrapped_collection_key` exactly 48 bytes;
- only `format_version` `0x0001`, `encoding_profile` `0x0001`, and `suite_id` `0x0001` are accepted;
- `collection_epoch` and `envelope_generation` arithmetic checked in `u64`, with `0xFFFFFFFFFFFFFFFF` rejected so an increment always exists;
- at most 8 envelopes per collection epoch and at most 1024 collections per vault, enforced by the catalog limits in [`CATALOG_SCHEMA_V1.md`](CATALOG_SCHEMA_V1.md) §21;
- canonical encoding required; a re-encoding that differs in any byte is rejected.

## 10. Failure behavior

- AEAD failure → `OBJECT_CORRUPT` internally, externally redacted so it is not distinguishable from a wrong credential;
- unsupported version, profile, or suite → fail closed as `UNSUPPORTED_*`;
- missing envelope → the collection and every object inside it is inaccessible, which is not proof that any ciphertext is corrupt;
- duplicate active envelope for one epoch → `CATALOG_CORRUPT`;
- stale generation → reject, or retain as historical according to migration policy.

## 11. Test vectors

- deterministic valid envelope for epoch 1 generation 1;
- wrong vault, collection, epoch, or generation in the AAD;
- wrong wrapping key, nonce, or tag;
- every single-bit flip in the 126 bytes;
- duplicate and stale generations;
- epoch rotation with an old and a new envelope both present;
- unsupported version, profile, and suite;
- truncation at every field boundary;
- cross-platform Rust and CLI verification.
