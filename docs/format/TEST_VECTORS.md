# Chur Test Vectors

> **Status:** Proposed vector governance and machine-readable layout

Deterministic vectors are the interoperability authority for canonical bytes and cryptographic constructions. Prose examples are explanatory only.

## 1. Repository layout

```text
test-vectors/
├── README.md
└── v1/
    ├── README.md
    ├── manifest.json
    ├── canonical-encoding/
    ├── key-derivations/
    ├── password-slots/
    ├── recovery-slots/
    ├── keystore-slots/
    ├── keychain-slots/
    ├── vault-descriptors/
    ├── collection-envelopes/
    ├── object-key-envelopes/
    ├── object-containers/
    ├── backup-packages/
    ├── sync-operations/
    ├── collection-grants/
    └── negative/
```

Binary fixtures use exact bytes. JSON manifests describe inputs/expected results but are not themselves canonical protocol encodings.

One vector is one entry in `manifest.json` plus zero or more binary fixture files. A fixture file lives in the group directory of its format and is named `<vector_id>.bin` when the vector has one fixture, or `<vector_id>.<role>.bin` when it has several, where `<role>` is a lowercase key that also appears in that vector's `inputs` or `expected` object. A vector whose `outcome` is `reject` keeps its format's `vector_id` and stores its fixtures under `negative/`.

`manifest.json` is the only index. A fixture file that no entry references, and an entry that names a missing file, both fail the vector suite.

## 2. Vector metadata

`manifest.json` is a UTF-8 JSON object:

| Key | Type | Required | Meaning |
| --- | --- | --- | --- |
| `manifest_version` | number | yes | `1` for this layout |
| `spec_commit` | string | yes | repository commit of the specifications the vectors were generated from |
| `generator` | object | yes | `name`, `version`, `commit`, and `toolchain` strings |
| `vectors` | array | yes | one entry per vector, sorted by `vector_id` |

Each element of `vectors` is:

| Key | Type | Required | Meaning |
| --- | --- | --- | --- |
| `vector_id` | string | yes | unique, matching the grammar in §9 |
| `spec` | string | yes | repository-relative path of the owning specification |
| `spec_section` | string | yes | the section that defines the case, such as `5` or `15.4` |
| `purpose` | string | yes | one sentence |
| `outcome` | string | yes | `accept` or `reject` |
| `inputs` | object | yes | field name to byte value or file reference, including the test-only keys, nonces, salts, and password bytes of §3 |
| `expected` | object | when `outcome` is `accept` | field name to byte value or file reference: encoded bytes, ciphertext, and commitments |
| `decoded` | object | optional | expected semantic fields as JSON numbers, strings, and booleans |
| `error_code` | string | when `outcome` is `reject` | one stable code from [`../ERROR_MODEL.md`](../ERROR_MODEL.md) |
| `notes` | string | optional | explanatory text only |

A byte value is a lowercase hexadecimal string matching `[0-9a-f]*`, of even length, with no `0x` prefix and no separator. The empty string is a zero-length byte value. Uppercase hexadecimal, base64, and JSON arrays of numbers are rejected by the loader, so one byte string has one representation.

A file reference is the object `{"file": "<path>"}` whose path is relative to `test-vectors/v1/`. Use a file for any value above 4096 bytes and a hexadecimal string below it, so a reviewer reads short values in the diff.

An integer that is a semantic value rather than encoded bytes is a JSON number when it is at most 9007199254740991 and a decimal string otherwise, so a `u64` never loses precision.

## 3. Test-only secrets

Deterministic keys, salts, nonces, passwords, and recovery secrets are permitted only in `test-vectors/` and must be clearly marked:

```text
TEST-ONLY — NEVER USE FOR REAL VAULTS
```

Production code must not expose APIs that select deterministic randomness outside test builds.

## 4. Positive vectors

Required:

- canonical primitive/structure encodings;
- Unicode password profile cases;
- Argon2id derivations;
- every key-slot family, under its own `format` word: `password-slot`, `recovery-slot`, `keystore-slot`, and `keychain-slot`. The Android family's AEAD runs in the platform Keystore, so its vector carries the body and the AAD and no wrapped bytes a Rust implementation could reproduce;
- every HKDF label in the registry in [`../security/KEY_HIERARCHY.md`](../security/KEY_HIERARCHY.md) §3, one `key-derivation` vector each, carrying the encoded `info` tuple as well as the derived key so a mismatch names the element that differs;
- valid object-key envelope;
- zero-byte, one-chunk, multi-chunk, and partial-final object;
- recovery-secret round trip: 32 bytes to 24 BIP-39 English words and back, including the checksum bits and one denormalized re-entry that must normalize to the same words;
- catalog logical fixtures/migrations;
- full backup and later incremental backup;
- signed operation and collection grant when protocols exist.

## 5. Negative vectors

Each parser/construction includes:

- bit flips;
- truncation at every field/record boundary;
- trailing bytes;
- non-canonical integer/boolean/field ordering;
- duplicate fields/IDs/generations;
- unsupported version/suite/profile;
- wrong key, nonce, AAD, tag, signature;
- oversized lengths/counts/KDF parameters;
- missing/reordered/duplicated/substituted chunks;
- absent/invalid final commit;
- stale descriptor/envelope/log generation;
- malformed UTF-8 and password-length boundary.

## 6. Generation

A Rust vector generator in `chur-cli` should:

- use explicit fixture inputs;
- use deterministic test RNG only behind test tooling;
- emit binaries and manifest atomically;
- refuse to overwrite without explicit command;
- print no production secrets;
- record generator commit/toolchain;
- validate its own output using an independent read path where possible.

## 7. Consumption

The same vector suite must run in:

```text
Rust unit/integration tests
chur-cli compatibility tests
Android instrumentation/unit tests
Kotlin common tests for error/API mapping
Swift/iOS integration tests
future server protocol tests
```

Kotlin/Swift may invoke Rust to parse private formats; platform tests still verify packaging, byte transport, and expected results.

## 8. Stability

Once a format/protocol version is accepted:

- existing positive vector bytes never change;
- correcting a mistaken spec requires new version/vector IDs;
- negative vectors may expand but not silently change expected classification;
- generator updates must reproduce historical vectors byte-for-byte;
- release CI archives the vector-set digest, which `chur-cli vectors digest` computes: SHA-256 over every file under `test-vectors/v1`, in ascending order of the file's path relative to that directory, feeding for each file the relative path as UTF-8 with `/` separators and then the file bytes. The formula is fixed in the tool rather than in a shell pipeline, so the value does not depend on how a platform orders a directory listing or formats a checksum line.

## 9. Naming

A vector ID matches:

```text
vector_id = format "-v" version "-" case
format    = one or more lowercase ASCII words joined by "-", naming the owning format
version   = one or more decimal digits, the format version under test
case      = one or more lowercase ASCII words joined by "-", describing the input
```

The `case` describes the input, never the expectation, because `outcome` already carries that. Allocated `format` words map to the §1 directories:

| `format` | Directory |
| --- | --- |
| `canonical-encoding` | `canonical-encoding/` |
| `key-derivation` | `key-derivations/` |
| `password-slot` | `password-slots/` |
| `recovery-slot` | `recovery-slots/` |
| `keystore-slot` | `keystore-slots/` |
| `keychain-slot` | `keychain-slots/` |
| `vault-descriptor` | `vault-descriptors/` |
| `collection-envelope` | `collection-envelopes/` |
| `object-key-envelope` | `object-key-envelopes/` |
| `object` | `object-containers/` |
| `backup` | `backup-packages/` |
| `operation` | `sync-operations/` |
| `collection-grant` | `collection-grants/` |

Examples:

```text
object-v1-zero-byte
object-v1-three-chunks-partial-final
password-slot-v1-unicode-combining-no-normalization
object-v1-missing-final-commit
operation-v1-replayed-sequence
```

IDs are stable under §8: a corrected case takes a new ID rather than reusing one.

## 10. Review

A vector PR must include:

- linked normative requirement/invariant;
- human-readable byte layout or decoder output;
- generator change;
- cross-platform result where applicable;
- security review for new cryptographic construction;
- no real user data.
