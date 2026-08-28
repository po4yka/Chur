# Chur v1 Vectors

> **Status:** Generated. `manifest.json` and the fixtures beside it are produced by `chur-cli` and are the interoperability authority for v1 canonical bytes.

**TEST-ONLY — NEVER USE FOR REAL VAULTS.** Every key, salt, nonce, password, and recovery secret in this directory is a fixed constant chosen so the output is reproducible. None of them protects anything.

## Regenerating and checking

```text
cargo run -p chur-cli -- vectors generate --force
cargo run -p chur-cli -- vectors verify
```

`verify` rebuilds the whole set from the current library and compares it byte for byte with what is on disk, then rejects any fixture no entry references. It is what [`../../docs/format/TEST_VECTORS.md`](../../docs/format/TEST_VECTORS.md) §8 asks for: a generator update must reproduce historical vectors byte for byte, and a change that alters one is a change to shipped bytes rather than a refactor.

`spec_commit` and the `generator` object are provenance. They are the one part of the manifest `verify` accepts as recorded rather than rebuilt.

## Layout

Group directories follow the §9 format-word table. A vector whose `outcome` is `reject` keeps its format's `vector_id` and files its fixtures under `negative/`. A value of at most 4096 bytes is written inline as lowercase hexadecimal so a reviewer reads it in the diff; anything longer becomes a `{"file": ...}` reference, which is why only the multi-chunk containers have fixture files.

## What the set covers

| Group | Vectors | What they fix |
| --- | ---: | --- |
| `canonical-encoding` | 8 | primitive boundaries, the worked tuple example, and five rejections |
| `key-derivation` | 25 | one per registered HKDF label: the encoded `info` tuple and the derived key |
| `password-slot` | 4 | the frozen Argon2id floor, the no-normalization rule, and two rejections |
| `recovery-slot` | 4 | the slot body, the 24-word round trip with a denormalized re-entry, two rejections |
| `keystore-slot` | 1 | the Android body framing and the AAD the platform cipher receives |
| `keychain-slot` | 1 | `AppleDeviceKEK` and the wrapped root |
| `vault-descriptor` | 5 | the 220-byte minimum, `MIGRATING`, and three rejections |
| `collection-envelope` | 2 | the 126-byte record and a foreign vault identity |
| `object-key-envelope` | 2 | the 142-byte record and an unsupported suite |
| `object` | 10 | the three §13 shapes, a partial final chunk, and six rejections |
| `backup` | 16 | the public preamble, the record header, both inventory entries, the ordered commitment and its empty case, the sealed manifest and final commit, and eight rejections |

A whole backup package is not among them and cannot be: [`../../docs/format/BACKUP_FORMAT_V1.md`](../../docs/format/BACKUP_FORMAT_V1.md) §2 has it carry the encrypted catalog, which is a SQLCipher file with a random salt, so two runs over one vault produce two packages that differ in bytes and mean the same thing. What is deterministic is every structure the format defines itself, and that is what the sixteen fix. The round trips live in `chur-media` `tests/backup_flow.rs`.

The Android vector carries no wrapped bytes a Rust implementation could reproduce: that family's AEAD runs in the platform Keystore, so the vector fixes the body framing and the AAD and stops there.

## Consumption

[`../../docs/format/TEST_VECTORS.md`](../../docs/format/TEST_VECTORS.md) §7 requires the same set to run in Rust tests, `chur-cli`, Android, and iOS. The Rust side runs it through `cargo test -p chur-cli`; the platform sides consume the same `manifest.json` and the same fixtures rather than a copy.
