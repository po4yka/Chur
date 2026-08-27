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
    ├── password-slots/
    ├── recovery-slots/
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

## 2. Vector metadata

Each vector records:

```text
vector_id
specification and version
purpose
input fixture paths
explicit test-only keys/nonces/password bytes
expected encoded bytes/ciphertext/commitments
expected decoded semantic fields
expected success or stable error code
source generator version/commit
```

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
- every key-slot family;
- every HKDF label in the registry in [`../security/KEY_HIERARCHY.md`](../security/KEY_HIERARCHY.md) §3;
- valid object-key envelope;
- zero-byte, one-chunk, multi-chunk, and partial-final object;
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
- release CI archives the vector-set digest.

## 9. Naming

Use descriptive stable IDs, for example:

```text
object-v1-zero-byte
object-v1-three-chunks-partial-final
password-slot-v1-unicode-combining-no-normalization
object-v1-missing-final-commit
operation-v1-replayed-sequence
```

## 10. Review

A vector PR must include:

- linked normative requirement/invariant;
- human-readable byte layout or decoder output;
- generator change;
- cross-platform result where applicable;
- security review for new cryptographic construction;
- no real user data.
