# Canonical Encoding v1

> **Status:** Proposed normative binary profile; exact numeric tags remain provisional until test vectors freeze

Canonical encoding ensures that authenticated, signed, hashed, or key-derived structures have exactly one byte representation. General serializer defaults are not protocol definitions.

## 1. Scope

This profile applies to:

- key-slot AAD and descriptors;
- vault descriptors;
- collection and object-key envelopes;
- object manifests, chunk AAD, and final commits;
- backup manifests;
- sync operations, signatures, and collection grants;
- deterministic test vectors.

It does not require UI/domain models to use the same in-memory representation.

## 2. Primitive rules

| Type | Encoding |
| --- | --- |
| `u8` | one byte |
| `u16` | unsigned, fixed-width, big-endian |
| `u32` | unsigned, fixed-width, big-endian |
| `u64` | unsigned, fixed-width, big-endian |
| boolean | one byte: `0x00` false, `0x01` true |
| fixed bytes | exact declared length, no prefix |
| variable bytes | `u32` length followed by bytes |
| UTF-8 string | `u32` byte length followed by strict UTF-8 |
| enum | fixed-width numeric discriminant defined by owning spec |
| optional | one presence byte followed by value when present |
| list | `u32` count followed by elements in order |

Signed integers and floating-point values are forbidden in v1 cryptographic records unless a focused specification defines their canonical representation.

## 3. Strings

- strict UTF-8 only;
- no implicit Unicode normalization;
- no NUL termination;
- length counts encoded bytes, not characters;
- invalid UTF-8 is rejected;
- application text fields may define separate normalization/search behavior after decryption;
- protocol labels are fixed ASCII byte constants.

## 4. Structures

A structure is encoded as fields in the exact order listed by its owning versioned specification. Field names are not encoded unless the specification explicitly defines tagged extensibility.

Example conceptual encoding:

```text
ObjectEnvelopeAADV1 =
    domain_tag[fixed]
    format_version:u16
    suite_id:u16
    vault_id:bytes[16]
    collection_id:bytes[16]
    collection_epoch:u64
    object_id:bytes[16]
    envelope_generation:u64
```

Concatenation without a schema is forbidden. The decoder must know the exact structure/version before parsing.

## 5. Maps and unordered collections

Maps are forbidden in signed/AAD structures by default. A specification that requires a map must define:

- allowed key type;
- canonical key-byte ordering;
- duplicate-key rejection;
- maximum count;
- whether unknown keys are rejected.

Sets are encoded as sorted unique lists under a defined comparator. Duplicate elements are rejected as non-canonical.

## 6. Tagged extension records

If a future structure uses tagged fields:

```text
field_tag:u16
field_length:u32
field_value:bytes
```

Rules:

- fields sorted by strictly increasing tag;
- duplicate tags rejected;
- required tags explicitly listed;
- unknown critical tags rejected;
- unknown non-critical tags may be preserved only if the owning spec permits forwarding;
- length must fit parser limits before allocation;
- canonical re-encoding must reproduce the same bytes.

Core v1 security records should prefer fixed schemas over extensible tagged maps.

## 7. Domain tags and canonical tuples

Every authenticated or signed record begins logically with a unique fixed domain tag, for example:

```text
CHUR\x00SLOT\x00PASSWORD\x00V1
CHUR\x00OBJECT\x00CHUNK-AAD\x00V1
CHUR\x00SYNC\x00OPERATION\x00V1
```

A domain tag is a bare ASCII byte constant. It is encoded as its exact registered bytes, with no length prefix, no terminator, and no trailing NUL. It is a fixed-bytes value under §2, not a UTF-8 string under §3; the `\x00` bytes shown above are separators inside the constant itself.

Exact tags are registry-controlled and included in test vectors. A tag must never be reused for a different structure, and no registered tag may be a byte prefix of another registered tag. A version suffix past `V9` must not extend an existing tag, because `V1` is a byte prefix of `V10`.

### 7.1 Canonical tuples

`CanonicalTuple(tag, element, ...)`, as written in [`../CRYPTOGRAPHY.md`](../CRYPTOGRAPHY.md), is not a separate construct. It names a §4 structure whose first field is a domain tag, and it adds no framing of its own: no element count, no separate schema-version field, no separator between elements, and no terminator. The version suffix in the tag is the tuple's schema version.

Each element after the tag is one primitive from §2, encoded by the rule for its declared type. The specification that owns the record declares the element list in order, with the type and width of every element. A group of related values, such as the Argon2 public parameters of a password slot, is not one element; it is written as one element per value.

For an illustrative tuple, not a registered one:

```text
CanonicalTuple(
    "CHUR\x00EXAMPLE\x00TUPLE\x00V1",
    suite_id:u16,
    object_id:bytes[16],
    label:string
)
```

encodes as:

```text
43 48 55 52 00 45 58 41 4D 50 4C 45 00 54 55 50 4C 45 00 56 31  tag, 21 bytes, no prefix
2 bytes                                                          suite_id, big-endian
16 bytes                                                         object_id, no length prefix
4 bytes                                                          label byte length, u32 big-endian
label byte length bytes                                          label, strict UTF-8
```

A fixed-length element carries no prefix and a variable-length element carries its `u32` length, so the two are never confusable: the tag selects exactly one element list, and that list fixes the width of every fixed-length element and the position of every length prefix. Because no registered tag is a byte prefix of another, the tag is recoverable from the leading bytes, so two distinct tuples never encode to the same bytes.

Tuple bytes are produced, not parsed. They are AEAD additional authenticated data, HKDF `info`, or hash input, so a mismatch surfaces as an authentication failure rather than as a decode error. Nested tuples and nested structures are forbidden in v1 tuples.

A hash input that a byte-exact specification defines directly, such as the commitments in [`OBJECT_CONTAINER_V1.md`](OBJECT_CONTAINER_V1.md) §5 and §10, is a domain tag followed by declared record bytes. It is not a canonical tuple and this subsection does not apply to it.

## 8. Identifiers

V1 identifiers are proposed as 16 random bytes, encoded exactly as bytes rather than textual UUID. Text rendering is presentation only and must not re-enter authenticated bytes.

Identifier all-zero value is reserved as invalid unless a focused spec says otherwise.

## 9. Time

Cryptographic records should avoid wall-clock time when monotonic counters or revisions suffice. When required, v1 uses:

```text
u64 whole milliseconds since Unix epoch UTC
```

Values are metadata, not trusted ordering proof. Negative times and timezone offsets are forbidden in canonical records.

## 10. Length and allocation limits

Each focused specification defines maximums. General decoder requirements:

- use checked arithmetic;
- validate count × element-size before allocation;
- reject trailing bytes unless explicitly permitted;
- reject truncated fields;
- reject lengths larger than remaining input;
- limit nesting depth;
- limit unknown extension bytes;
- never run Argon2 or allocate media buffers based on unchecked values.

## 11. Canonicality

A decoder for authenticated bytes must reject:

- alternate integer widths;
- leading padding;
- boolean values other than 0 or 1;
- non-minimal or duplicate optional fields;
- unordered or duplicate tagged fields;
- invalid UTF-8;
- trailing bytes;
- unknown version/suite where policy disallows it;
- any representation that re-encodes differently.

## 12. Versioning

Encoding profile ID is carried by the containing artifact. V1 bytes never change. A new rule requires a new profile/version and migration or dual-reader policy.

Do not add a field to a fixed v1 structure while retaining its version number.

## 13. Rust implementation

The canonical encoder/decoder should be a small Rust-owned crate or module with:

- no generic `serde` format as the authority;
- explicit read/write functions;
- bounded cursor operations;
- checked arithmetic;
- structured non-secret errors;
- property tests that decode→encode is identity for accepted bytes;
- fuzz tests that rejected bytes do not allocate beyond limits.

Kotlin and Swift consume Rust-produced records or vectors; they do not define alternate canonical encoders for private formats.

## 14. Required vectors

- each primitive boundary value;
- empty and maximum-length byte/string/list values;
- invalid UTF-8;
- truncated lengths and trailing bytes;
- duplicate/out-of-order tagged fields;
- all-zero/maximum identifiers;
- cross-platform examples for every owning format;
- non-canonical encodings that must be rejected.
