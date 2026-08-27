# Contributing to Chur

Chur welcomes architecture review, security analysis, Rust and Kotlin implementation work, platform integration, testing, and documentation improvements. The project is pre-release; correctness and explicit design decisions take priority over API stability or feature breadth.

## Before contributing

Read, in order:

1. [`docs/README.md`](docs/README.md) — the authority hierarchy, the document index, the normative-language rule, and the status vocabulary
2. the focused specification for the area being changed, found in that index
3. [`docs/CRYPTOGRAPHY.md`](docs/CRYPTOGRAPHY.md) — the cryptographic responsibilities behind those specifications
4. [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — component, trust, and lifecycle boundaries
5. [`README.md`](README.md) — product context; it is the lowest rank and states nothing normatively
6. [`DESIGN.md`](DESIGN.md) for user-interface work, and [`SECURITY.md`](SECURITY.md) before reporting a suspected vulnerability

The order matches the authority hierarchy in `docs/README.md`: the specifications that bind bytes come first and the explanatory documents come last. A pull request must not silently contradict a more authoritative normative document.

## Contribution categories

### Product and UI

Product changes must preserve the separation between the functional public shell, the session gate, and the private vault. Discreet behavior must not be presented as cryptographically undetectable storage.

### KMP/CMP application code

KMP owns use cases, UDF state, navigation, and platform orchestration. It must not create a second private storage format or persist private metadata in Room, DataStore, saved state, logs, or analytics.

### Rust secure core

Rust changes affecting keys, formats, catalog state, integrity, migrations, or FFI require security-focused review. Avoid widening the public API; prefer explicit secret types, bounded inputs, deterministic errors, and coarse-grained operations.

### Platform integration

Android and iOS code may authorize platform key use, open file descriptors, run platform codecs, host media players, and enforce lifecycle policy. It must not independently define vault bytes or derive application data keys.

### Documentation and specifications

Requirement strength follows the rule in [`docs/README.md`](docs/README.md#normative-language): in a normative document, lowercase must, must not, should, should not, and may carry full RFC 2119 force, and the four documents that capitalize them gain no extra strength by doing so. Use these words only for requirements. Proposed constants and undecided mechanisms must be labelled as proposals rather than frozen requirements.

## Pull-request requirements

Every pull request should include:

- a concise problem statement;
- the architectural owner of the changed behavior;
- affected threat-model entries and security invariants;
- tests added or an explanation of why tests are not applicable;
- migration and compatibility impact;
- privacy/logging impact;
- platform-specific impact;
- documentation updates when a contract changes.

Keep unrelated refactors separate from format or security changes.

## Changes to persisted or wire formats

A format change requires all of the following before merge:

- an ADR or update to an existing accepted decision;
- a version bump or an explicit proof that bytes are unchanged;
- canonical encoding rules;
- positive and negative test vectors;
- parser limits and failure behavior;
- migration and rollback behavior;
- cross-platform compatibility tests;
- fuzz-corpus updates;
- security review.

Never reinterpret existing bytes under a new meaning without a version change.

## Cryptographic changes

Do not implement custom cryptographic primitives. New constructions or algorithms require:

- a standards or peer-reviewed reference;
- a documented threat addressed by the change;
- comparison with existing approved primitives;
- nonce, key-lifetime, domain-separation, and misuse analysis;
- compatible Rust implementation review;
- deterministic vectors;
- independent review before production use.

Passwords derive KEKs only. Every object uses an independent random key. Unsupported algorithms fail closed.

## Dependencies

Follow [`docs/DEPENDENCY_POLICY.md`](docs/DEPENDENCY_POLICY.md). Security-sensitive dependency additions must describe:

- why existing dependencies are insufficient;
- maintenance and audit status;
- license;
- transitive native code;
- supported Android/iOS targets;
- impact on binary size, build reproducibility, and supply-chain risk.

## Rust expectations

- keep `unsafe` isolated and justified;
- deny or document unchecked integer conversions;
- validate untrusted lengths before allocation;
- prevent panics from crossing FFI;
- use redacted error and `Debug` implementations for secret-bearing types;
- add unit, property, corruption, and fuzz tests as appropriate;
- run formatting, linting, tests, dependency policy, and target builds.

## Kotlin expectations

- use explicit UDF contracts;
- keep private state session-scoped and minimal;
- separate one-shot effects from durable state;
- avoid private data in exception strings and coroutine names;
- do not persist private navigation state;
- use bounded streaming rather than whole-file `ByteArray` values;
- add common and platform tests.

## Commit and branch conventions

Use focused branches and conventional, descriptive subjects, for example:

```text
docs: specify password key slots
feat(crypto): add object-key envelope parser
test(format): add truncated-final-commit vectors
fix(ffi): invalidate readers on lock
```

Do not include generated binaries, signing material, real vaults, real media, or secrets.

## Review checklist

Reviewers should verify:

- ownership boundaries remain intact;
- failure is closed and redacted;
- resource limits are enforced before expensive work;
- real and decoy vault state remains independent;
- process death and interrupted writes are handled;
- tests exercise adversarial as well as success paths;
- documentation and implementation agree.

## Licensing

Contributions are accepted under the repository's BSD 3-Clause License. Contributors must have the right to submit the code and must preserve third-party notices where required.
