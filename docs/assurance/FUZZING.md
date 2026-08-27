# Fuzzing Strategy

> **Status:** Proposed normative assurance plan

Fuzzing targets every parser, decoder boundary, state machine, and FFI entry that accepts untrusted bytes, lengths, ordering, or resource parameters.

## 1. Goals

- no panic, abort, undefined behavior, or out-of-bounds access from untrusted input;
- bounded memory/CPU before expensive work;
- deterministic stable error classification;
- no plaintext release before authentication;
- no persistent partial state without a recoverable journal;
- corpus coverage of every supported version and negative construction.

## 2. Initial Rust targets

```text
parse_canonical_value
parse_vault_descriptor
parse_key_slot
password_parameter_validation
parse_object_key_envelope
parse_object_preamble
parse_manifest_record
parse_chunk_record
parse_final_commit
read_plaintext_range
parse_catalog_snapshot
apply_catalog_migration
parse_backup_package
validate_ffi_input
```

Future:

```text
parse_device_identity
parse_signed_operation
apply_operation_log
parse_collection_grant
sync_state_machine
```

## 3. Harness rules

- apply hard input-size cap before target;
- use deterministic no-network/no-platform environment;
- substitute bounded cheap KDF for parser-only fuzz or fuzz parameter validation separately;
- use synthetic keys;
- assert allocations/work remain within target budget;
- treat unexpected successful parse as interesting when input is non-canonical;
- run under sanitizers/Miri where compatible.

## 4. Structured and mutation fuzzing

Combine:

- raw byte mutation for parser robustness;
- structure-aware generation for deep states;
- state-machine sequences for slot replacement/import/migration/sync;
- differential testing between encoder and decoder;
- corpus derived from positive/negative vectors.

## 5. Corruption dictionary

Include tokens for:

- magic/version/suite/domain tags;
- record types;
- integer boundary encodings;
- canonical field tags;
- common ciphertext/tag lengths;
- max/min chunk sizes;
- Argon2 parameter boundaries;
- final-commit markers.

## 6. Resource assertions

Targets should track or constrain:

- maximum allocation;
- nesting/count;
- KDF work;
- chunk iterations;
- path/temp-file creation;
- database transaction count;
- callback count.

A clean rejection after bounded work is success.

## 7. Persistent-state fuzzing

Use temporary directories and fault injection to fuzz:

- interrupted import at every stage;
- catalog/object commit ordering;
- slot replacement;
- collection rotation;
- migration checkpoints;
- backup restore;
- orphan reconciliation.

After each sequence, assert invariants and reopen from disk.

## 8. FFI fuzzing

Exercise:

- invalid handles and generations;
- null/misaligned/zero/oversized buffers;
- offset/length overflow;
- double close;
- concurrent close/read;
- lock/cancel during callbacks;
- panic containment;
- unknown ABI/error codes.

Language-level integration tests supplement native fuzz harnesses.

## 9. Corpus management

- seed with every stable test vector;
- retain minimized regressions;
- label corpus by spec/version/target;
- never include real media or vaults;
- store large codec corpora outside the core crypto corpus with license/source metadata;
- remove duplicates without discarding semantic edge cases.

## 10. CI cadence

The enforcing workflow, its owner, and the rule that applies until it exists are stated once in [`RELEASE_GATES.md`](RELEASE_GATES.md#enforcement). The cadence below is unenforced until a fuzz job joins that workflow, which happens with the first fuzz target.

- short deterministic smoke fuzz on every PR touching target code;
- longer scheduled runs on default branch;
- release-candidate campaign with recorded duration/configuration;
- continuous external fuzzing when eligible;
- failures block release until triaged.

## 11. Triage

For each finding:

1. preserve minimized input privately if security-sensitive;
2. classify crash, hang, resource exhaustion, invariant breach, or unexpected acceptance;
3. identify affected versions/formats;
4. add deterministic regression test;
5. fix without weakening canonical validation;
6. update spec/invariant if behavior was ambiguous;
7. coordinate disclosure under `SECURITY.md`.

## 12. Exit criteria

Before local production vault:

- all v1 parsers have targets;
- vector corpus loaded;
- no known reproducible crash/invariant breach;
- parser allocation limits exercised;
- migration/import fault targets operational;
- campaign evidence included in release gate.
