# Object Key Envelope v1

> **Status:** Proposed normative format

`ObjectKeyEnvelopeV1` wraps one random `ObjectKey` under a Security Collection key. It is mutable and stored separately from the immutable media container so collection changes and sharing can rewrap keys without re-encrypting media.

## 1. Structure

Conceptual canonical fields:

```text
format_version:u16
encoding_profile:u16
suite_id:u16
vault_id:bytes[16]
collection_id:bytes[16]
collection_epoch:u64
object_id:bytes[16]
envelope_generation:u64
nonce:bytes[24]
wrapped_object_key:bytes[48]   # 32-byte key + 16-byte tag for XChaCha20-Poly1305
```

Exact offsets are defined by canonical vectors. The AAD domain tag is `CHUR\x00OBJECT\x00KEY-ENVELOPE\x00V1`, allocated in [`CANONICAL_ENCODING_V1.md`](CANONICAL_ENCODING_V1.md) §15.5. V1 values for `format_version`, `encoding_profile`, and `suite_id` are allocated in [`CANONICAL_ENCODING_V1.md`](CANONICAL_ENCODING_V1.md) §15.

## 2. Wrapping key

```text
ObjectEnvelopeKey = HKDF-SHA-256(
    SecurityCollectionKey[epoch],
    info = canonical collection/object-envelope context
)
```

The collection key remains random; HKDF separates envelope use from other future collection-key purposes.

## 3. AAD

AAD binds:

```text
domain tag
format/encoding/suite
vault ID
collection ID and epoch
object ID
envelope generation
```

It does not include nonce or ciphertext twice unless the final construction explicitly requires it.

Substituting an envelope across vaults, collections, epochs, objects, or generations must fail authentication.

## 4. Multiple envelopes

One object may have multiple envelopes when:

- rewrapping from old to new collection epoch;
- accessible through more than one security collection;
- shared to different recipient collection grants;
- migration temporarily retains old and new suites.

The catalog must define which envelopes are active and prevent ambiguous duplicate `(collection_id, epoch, object_id, generation)` tuples.

## 5. Generation

`envelope_generation` increases when the envelope for the same object/collection/epoch is replaced. The highest authenticated active generation wins under catalog transaction rules; lower generations are stale and must not silently replace newer state.

## 6. Creating an envelope

1. obtain authenticated collection key for the epoch;
2. obtain the object's 32-byte key inside Rust;
3. derive envelope key with versioned context;
4. generate fresh 24-byte nonce;
5. encode canonical AAD;
6. seal object key;
7. zeroize derived wrapping key;
8. write envelope in a catalog transaction;
9. read back and verify before retiring prior envelope.

## 7. Moving an object

Moving between security collections:

```text
unwrap ObjectKey with old active envelope
create/verify envelope under destination collection key
commit destination membership/envelope
remove old membership/envelope if policy requires
```

No media container bytes change.

A UI album move does not imply cryptographic rewrap unless the album maps to a different Security Collection.

## 8. Collection rotation

During epoch rotation:

- new objects use the new epoch immediately;
- existing object keys are rewrapped incrementally;
- catalog records track migration state;
- old epoch remains available until every required envelope is migrated and verified;
- crash recovery can resume without duplicating active generations;
- old key destruction follows revocation/backup policy.

## 9. Deletion

Deleting the active envelope contributes to crypto-erasure only when no other reachable envelope or recipient copy can recover the object key. The catalog must enumerate envelope references before claiming erasure.

## 10. Parser limits

- identifiers exactly 16 bytes, nonce exactly 24 bytes, `wrapped_object_key` exactly 48 bytes, whole record exactly 142 bytes;
- supported suite/version only;
- generation and epoch arithmetic checked in `u64`, with `0xFFFFFFFFFFFFFFFF` rejected so an increment always exists;
- no trailing bytes;
- canonical encoding required;
- at most 64 envelopes per object, of which at most 4 are active at once, enforced by the catalog limits in [`CATALOG_SCHEMA_V1.md`](CATALOG_SCHEMA_V1.md) §21.

## 11. Failure behavior

- AEAD failure → `OBJECT_CORRUPT` or a more specific internal envelope error, externally redacted;
- unsupported suite/version → fail closed;
- missing envelope → object inaccessible, not proof that ciphertext is corrupt;
- duplicate active envelope → catalog integrity error;
- stale generation → reject or retain as historical according to migration policy.

## 12. Test vectors

- deterministic valid envelope;
- wrong vault/collection/epoch/object/generation AAD;
- wrong key/nonce/tag;
- all bit flips;
- duplicate/stale generations;
- collection rotation and move;
- multiple active access domains;
- cross-platform Rust/CLI verification;
- parser truncation at every boundary.
