# Chur Security Invariants

> **Status:** Proposed normative invariant registry

Security invariants are properties that implementation, migrations, platform adapters, and future protocols must preserve. IDs are stable and should be referenced by code comments, tests, ADRs, pull requests, and audit findings.

## Key and credential invariants

| ID | Invariant | Primary owner | Required evidence |
| --- | --- | --- | --- |
| SEC-001 | Passwords derive KEKs only and are never media, root, collection, or object keys. | `chur-crypto` | KAT, API review |
| SEC-002 | `VaultRootSecret` is generated randomly and never persisted in plaintext. | `chur-crypto` | vector, storage inspection |
| SEC-003 | Every security collection has an independent random key per epoch. | `chur-crypto` | property tests |
| SEC-004 | Every media object has an independent random `ObjectKey`. | `chur-crypto` | property tests |
| SEC-005 | Semantic key purposes use explicit, versioned HKDF domain separation. | `chur-crypto` | label registry tests |
| SEC-006 | Password, platform, recovery, and peer slots wrap the same root without becoming equivalent credentials. | `chur-core` | slot interoperability tests |
| SEC-007 | Untrusted KDF parameters are bounded before allocation or expensive work. | `chur-crypto` | negative vectors, fuzzing |
| SEC-008 | Password changes rewrap the root and do not re-encrypt media. | `chur-core` | integration test |
| SEC-009 | Deleting or replacing one slot cannot invalidate every other valid slot in the same transaction. | `chur-core` | fault injection |
| SEC-010 | Secret-bearing types never expose unredacted `Debug`, display, serialization, or error output. | Rust core | compile/review/log tests |
| SEC-053 | Platform key services protect short root and KEK material only, and never media streams. | `chur-core`, platform adapters | key-use audit, platform tests |
| SEC-054 | Logical albums are not automatically cryptographic security collections. | `chur-catalog` | key-domain review |
| SEC-055 | Device-bound and portable recovery state are explicitly distinguished and never interchanged. | `chur-core` | recovery matrix tests |

## Nonce and AEAD invariants

| ID | Invariant | Primary owner | Required evidence |
| --- | --- | --- | --- |
| SEC-011 | No key-and-nonce pair is reused. | `chur-format` | uniqueness/property tests |
| SEC-012 | Every stream revision receives a fresh random nonce prefix. | `chur-format` | revision tests |
| SEC-013 | Chunk indexes are monotonic within a revision and cannot overflow. | `chur-format` | boundary tests |
| SEC-014 | AAD canonically binds suite, object, stream, revision, index, manifest commitment, and plaintext length. | `chur-format` | vectors, substitution tests |
| SEC-015 | Plaintext is not released before the corresponding AEAD tag verifies. | `chur-media` | tamper tests |
| SEC-016 | Verified chunks do not imply a complete object. | `chur-media` | truncated-final tests |
| SEC-017 | Complete verification requires a valid authenticated final commit and ordered commitment. | `chur-format` | negative vectors |
| SEC-018 | Unknown or disallowed suites fail closed. | parser/policy | fuzz and compatibility tests |
| SEC-056 | Algorithm agility is policy-controlled and never selectable by a remote party or by input bytes alone. | parser/policy | policy review, negative vectors |

## Storage and transaction invariants

| ID | Invariant | Primary owner | Required evidence |
| --- | --- | --- | --- |
| SEC-019 | Rust is the only canonical owner of private vault bytes and migrations. | architecture | dependency checks/review |
| SEC-020 | Public Room/DataStore storage contains no private-vault metadata or keys. | KMP/platform | storage inspection tests |
| SEC-021 | Object-key envelopes are separate from immutable media containers. | `chur-format` | format vectors |
| SEC-022 | Source media is not deleted before encrypted import is durably committed. | import coordinator | crash/fault tests |
| SEC-023 | A catalog entry never points to an uncommitted object. | `chur-catalog` | transaction tests |
| SEC-024 | Interrupted writes are recoverable, resumable, or removable without exposing partial plaintext. | store/catalog | fault injection |
| SEC-025 | Private catalog and object migrations are versioned and atomic. | migration runtime | migration matrix |
| SEC-026 | Crypto-erasure claims require destruction of every accessible key envelope, not only ciphertext deletion. | deletion flow | backup/sync tests |
| SEC-027 | Physical filenames and paths reveal no user filename or unkeyed plaintext hash. | object store | filesystem inspection |
| SEC-057 | Original media containers are immutable after commit. | `chur-format` | write-path and rewrap tests |
| SEC-058 | Derived assets are bound to their parent content revision and asset kind. | `chur-media` | derivation vectors |

## Session and runtime invariants

| ID | Invariant | Primary owner | Required evidence |
| --- | --- | --- | --- |
| SEC-028 | Lock invalidates native handles independently of UI cleanup. | `chur-core` | stale-handle tests |
| SEC-029 | Private navigation state is destroyed on lock and not restored after process death. | KMP navigation | lifecycle tests |
| SEC-030 | Private decoded caches are session-scoped and cleared on lock. | media/platform | cache inspection |
| SEC-031 | Long-running import/export/playback observes cancellation caused by lock. | FFI/media | race tests |
| SEC-032 | Root and session secrets are zeroized in place to the extent supported by the runtime. | Rust core | review/observable tests |
| SEC-033 | Private data does not enter logs, analytics, crash reports, notifications, widgets, or public deep links. | all layers | leakage tests |
| SEC-034 | Scratch plaintext is app-private, backup-excluded, protected, random-named, cleaned, and inside the caps of [`PLAINTEXT_LIFECYCLE.md`](PLAINTEXT_LIFECYCLE.md) §5. | platform adapters | platform tests asserting each cap |
| SEC-059 | Background work performed while locked does not require private root-key access unless explicitly designed and consented. | platform adapters | background-execution tests |

## Real/decoy invariants

| ID | Invariant | Primary owner | Required evidence |
| --- | --- | --- | --- |
| SEC-035 | Real and decoy vaults use independent root secrets. | vault provisioning | vector/integration tests |
| SEC-036 | Real and decoy vaults do not share private catalogs, object namespaces, caches, platform aliases, recovery secrets, or sessions. | core/platform | isolation tests |
| SEC-037 | Ordinary feature code receives an opaque session, not a durable `isDecoy` discriminator. | KMP/core | API review |
| SEC-038 | External authentication failure does not reveal whether real, decoy, or no credential matched. | session gate | UI/error tests |
| SEC-039 | Discreet and decoy behavior is not represented as cryptographically undetectable storage. | product/docs | copy review |

## Sync and sharing invariants

| ID | Invariant | Primary owner | Required evidence |
| --- | --- | --- | --- |
| SEC-040 | The server receives no plaintext media, private metadata, root keys, or unwrapped collection keys. | sync protocol | protocol tests |
| SEC-041 | Sync operations are canonically encoded, authenticated, and bound to device identity. | `chur-sync-protocol` | signature vectors |
| SEC-042 | Per-device sequence and hash-chain validation rejects replay and simple rollback. | sync client | malicious-server tests |
| SEC-043 | Collection grants encrypt only collection keys, never bulk media with public-key encryption. | sharing protocol | HPKE vectors |
| SEC-044 | Sender/device identity is authenticated separately from HPKE confidentiality. | sharing protocol | signature tests |
| SEC-045 | Revocation is forward-looking and never claims to erase keys/plaintext already obtained by a recipient. | product/protocol | documentation and tests |
| SEC-046 | Global deduplication does not use an unkeyed plaintext content hash. | storage/sync | design review |

## Parser and resource invariants

| ID | Invariant | Primary owner | Required evidence |
| --- | --- | --- | --- |
| SEC-047 | Every untrusted length/count is checked against a hard limit before allocation. | all parsers | fuzzing |
| SEC-048 | Non-canonical encodings are rejected where bytes are authenticated or signed. | encoding layer | duplicate-encoding vectors |
| SEC-049 | Integer arithmetic for offsets, sizes, and counts is checked. | format/media | boundary/property tests |
| SEC-050 | Rust panics and foreign exceptions cannot unwind across FFI. | FFI | panic-injection tests |
| SEC-051 | Unknown native error codes map to a safe generic failure. | adapters | compatibility tests |
| SEC-052 | Deprecated formats are read only under explicit migration policy and are never silently written as new data. | migration/policy | version tests |

## Governance

Adding, removing, or weakening an invariant requires:

1. a threat-model update;
2. an ADR explaining the tradeoff;
3. implementation and migration impact;
4. updated test mapping;
5. security review;
6. release-note disclosure when users are affected.

An invariant may be marked “not yet implemented,” but it must not be silently ignored.
