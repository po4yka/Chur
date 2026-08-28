# Chur Test Vectors

> **Status:** v1 is generated and checked in; `chur-cli` produces it and `chur-cli vectors verify` rebuilds and compares it

This directory holds deterministic, machine-readable compatibility fixtures for Chur formats and protocols.

Read [`docs/format/TEST_VECTORS.md`](../docs/format/TEST_VECTORS.md) for governance, required cases, and stability rules.

## Rules

- all keys, nonces, passwords, recovery secrets, and media are synthetic and marked test-only;
- accepted-version positive bytes never change;
- negative fixtures include expected stable error/state;
- generator commit and specification version are recorded;
- Android, iOS, Rust, and CLI consume the same fixtures;
- no real user data or production vault material.

## Versions

- [`v1/`](v1/README.md) — the frozen v1 formats: 78 vectors covering canonical encoding, every HKDF label, every key-slot family, both key envelopes, the vault descriptor, the object container, and the backup package.
