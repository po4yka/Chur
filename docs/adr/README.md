# Architecture Decision Records

> **Status:** Accepted ADR process, template, and index

ADRs record durable architectural choices, alternatives, consequences, and security impact. They explain **why** a decision exists; normative format and behavior remain in focused specifications.

## Status values

An ADR uses the document-status vocabulary defined once in [`../README.md`](../README.md#document-status): **Proposed**, **Accepted**, **Experimental**, **Deprecated**, **Superseded**, or **Rejected**. An ADR spells the superseded label `Superseded by ADR-NNNN` so the metadata line names the replacement.

## Template

```markdown
# ADR-NNNN: Title

- Status: Proposed
- Date: YYYY-MM-DD
- Decision owners: ...
- Related: links

## Context

## Decision

## Alternatives considered

## Consequences

### Positive

### Tradeoffs

## Security impact

## Compatibility impact

## Validation

## Follow-up
```

`Security impact` covers privacy impact and names the affected `SEC-` identifiers from [`../security/SECURITY_INVARIANTS.md`](../security/SECURITY_INVARIANTS.md). `Compatibility impact` covers migration and downgrade behavior. A Proposed ADR may title its validation section `Validation required before acceptance`.

## Process

- create an ADR for a decision affecting ownership, trust boundary, persisted/wire bytes, key lifecycle, major dependency, platform security policy, or release gate;
- do not edit the historical decision into a different choice after acceptance; supersede it with a new ADR;
- update related normative docs and vectors in the same change or state the sequencing explicitly;
- accepted ADRs must identify unresolved proposals and evidence required;
- security-sensitive ADRs require a second reviewer when possible.

## Index

| ADR | Decision | Status |
| --- | --- | --- |
| [0001](0001-rust-owns-private-vault.md) | Rust owns the private vault | Accepted |
| [0002](0002-independent-aead-chunks.md) | Independent AEAD chunks for media | Accepted |
| [0003](0003-separate-object-key-envelope.md) | Separate object-key envelope from immutable container | Accepted |
| [0004](0004-rust-owned-private-catalog.md) | Rust-owned private catalog; SQLCipher preferred pending validation | Proposed |
| [0005](0005-real-and-decoy-vault-isolation.md) | Real and decoy vault cryptographic isolation | Accepted |
| [0006](0006-control-and-data-plane-ffi.md) | Split FFI control and data planes | Accepted |
| [0007](0007-local-first-before-sync.md) | Stabilize local vault before sync/sharing | Accepted |
| [0008](0008-freeze-object-container-v1-layout.md) | Freeze the object container v1 public layout | Accepted |
| [0009](0009-one-hkdf-label-registry.md) | One HKDF label registry | Accepted |
| [0010](0010-define-canonical-tuple-and-freeze-hkdf-salt.md) | Define the canonical tuple encoding and freeze the HKDF extract salt | Accepted |
| [0011](0011-freeze-vault-descriptor-authentication.md) | Freeze vault-descriptor authentication | Accepted |
| [0012](0012-import-journal-durability-ordering.md) | Reserve chunk indexes in the import journal before use | Accepted |
| [0013](0013-allocate-v1-format-constants.md) | Allocate the v1 format constants in one registry | Accepted |
| [0014](0014-observed-heads-causality-vector.md) | Observed-heads causality vector in the operation record | Accepted |
| [0016](0016-freeze-the-v1-c-abi.md) | Freeze the v1 C ABI: exports, handles, status type, and panic containment | Accepted |
| [0017](0017-freeze-the-supported-device-set.md) | Freeze the supported device set and the benchmark baseline | Accepted |
| [0018](0018-freeze-backup-package-framing.md) | Freeze the backup package framing and manifest key | Accepted |
| [0019](0019-freeze-remaining-v1-record-layouts.md) | Freeze the remaining v1 record layouts | Accepted |
| [0020](0020-set-the-v1-parser-limits.md) | Set the v1 parser limits | Accepted |
| [0023](0023-define-signed-checkpoint-and-bootstrap-attestation.md) | Signed checkpoint record and new-device bootstrap attestation | Accepted |
| [0024](0024-freeze-revocation-point-and-eager-rewrap.md) | Accepted revocation point and eager epoch rewrap | Accepted |
| [0022](0022-freeze-operation-chain-hash-and-identifier.md) | Freeze the operation chain hash, identifier, and cleartext field set | Accepted |
| [0021](0021-freeze-conflict-tie-break-and-set-semantics.md) | Freeze the conflict tie-break and set semantics | Accepted |
| [0025](0025-freeze-the-object-key-envelope-aad.md) | Freeze the object-key envelope AAD | Accepted |
| [0026](0026-argon2id-memory-floor-and-candidate-set.md) | Argon2id memory floor and the constant password-candidate set | Accepted |
| [0028](0028-freeze-the-catalog-query-surface.md) | Freeze the catalog query surface, index set, and v1 search | Accepted |
| [0029](0029-freeze-the-recovery-secret-encoding.md) | Freeze the recovery-secret human encoding as BIP-39 English | Accepted |
| [0030](0030-freeze-the-vault-registry-and-discovery.md) | Freeze the vault registry layout and discovery order | Accepted |
| [0032](0032-vault-creation-requires-a-password-slot.md) | Vault creation requires a verified password slot | Accepted |
| [0033](0033-chur-operates-no-sync-service.md) | Chur operates no sync service; deployments are user-controlled | Accepted |

## Future ADR backlog

- password Unicode profile; the Argon2id parameter floor and default are [0026](0026-argon2id-memory-floor-and-candidate-set.md);
- SQLCipher build/link/backup validation result;
- Android Keystore and iOS Keychain exact policies;
- device identity portability;
- post-quantum recipient profile.
