# Chur Test Vectors

This directory will contain deterministic, machine-readable compatibility fixtures for Chur formats and protocols.

Read [`docs/format/TEST_VECTORS.md`](../docs/format/TEST_VECTORS.md) for governance, required cases, and stability rules.

## Rules

- all keys, nonces, passwords, recovery secrets, and media are synthetic and marked test-only;
- accepted-version positive bytes never change;
- negative fixtures include expected stable error/state;
- generator commit and specification version are recorded;
- Android, iOS, Rust, and CLI consume the same fixtures;
- no real user data or production vault material.

## Versions

- [`v1/`](v1/README.md) — scaffold for the proposed v1 formats; bytes are not yet frozen.
