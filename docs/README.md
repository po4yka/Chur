# Chur Documentation

This directory contains the normative and supporting design documents for Chur.

## Authority hierarchy

When documents conflict, use this order until the conflict is resolved:

1. byte-exact versioned format or protocol specifications;
2. accepted ADRs that explicitly supersede earlier decisions;
3. focused normative security, interop, assurance, and sync specifications;
4. [`CRYPTOGRAPHY.md`](CRYPTOGRAPHY.md);
5. [`ARCHITECTURE.md`](ARCHITECTURE.md);
6. root [`README.md`](../README.md), roadmap, and explanatory material.

Implementation behavior is not authoritative merely because it exists. A divergence from a normative specification is a defect unless a migration and specification change are approved.

## Document status

Each document should state one of:

- **Proposed** — direction under review; not compatibility-stable.
- **Accepted** — implementation requirement, subject to versioning rules.
- **Experimental** — prototype used to collect evidence.
- **Deprecated** — readable/migratable but not used for new data.
- **Superseded** — replaced by a named document or ADR.

Byte-exact v1 documents remain proposed until constants, encoding, vectors, and cross-platform implementations are frozen.

## Core documents

- [`ARCHITECTURE.md`](ARCHITECTURE.md) — system components, trust boundaries, runtime states, ownership, and implementation constraints.
- [`CRYPTOGRAPHY.md`](CRYPTOGRAPHY.md) — key hierarchy, primitives, wrapping, media encryption, integrity, recovery, and future sharing.
- [`ANDROID.md`](ANDROID.md) — complete Android platform architecture.
- [`IOS.md`](IOS.md) — complete iOS platform architecture.
- [`ERROR_MODEL.md`](ERROR_MODEL.md) — stable errors, redaction, retry behavior, and layer mappings.
- [`DEPENDENCY_POLICY.md`](DEPENDENCY_POLICY.md) — dependency, supply-chain, native-code, license, and update requirements.

## Product

- [`product/DISCREET_MODE.md`](product/DISCREET_MODE.md) — public shell, launcher, notifications, recents, external surfaces, and product claims.

## Security

- [`security/THREAT_MODEL.md`](security/THREAT_MODEL.md)
- [`security/SECURITY_INVARIANTS.md`](security/SECURITY_INVARIANTS.md)
- [`security/KEY_HIERARCHY.md`](security/KEY_HIERARCHY.md)
- [`security/KEY_SLOTS.md`](security/KEY_SLOTS.md)
- [`security/PASSWORD_PROFILE.md`](security/PASSWORD_PROFILE.md)
- [`security/PLAINTEXT_LIFECYCLE.md`](security/PLAINTEXT_LIFECYCLE.md)
- [`security/DECOY_VAULT.md`](security/DECOY_VAULT.md)
- [`security/RECOVERY.md`](security/RECOVERY.md)

## Formats

- [`format/CANONICAL_ENCODING_V1.md`](format/CANONICAL_ENCODING_V1.md) — encoding rules and the [v1 constant registry](format/CANONICAL_ENCODING_V1.md#15-constant-registry) of magics, versions, profiles, suites, record types, and discriminants
- [`format/VAULT_DESCRIPTOR_V1.md`](format/VAULT_DESCRIPTOR_V1.md)
- [`format/OBJECT_KEY_ENVELOPE_V1.md`](format/OBJECT_KEY_ENVELOPE_V1.md)
- [`format/OBJECT_CONTAINER_V1.md`](format/OBJECT_CONTAINER_V1.md)
- [`format/CATALOG_SCHEMA_V1.md`](format/CATALOG_SCHEMA_V1.md)
- [`format/BACKUP_FORMAT_V1.md`](format/BACKUP_FORMAT_V1.md)
- [`format/TEST_VECTORS.md`](format/TEST_VECTORS.md)

## Interop

The complete platform documents are `ANDROID.md` and `IOS.md`; these focused contracts define the cross-platform boundary expected by the shared Rust/KMP architecture.

- [`interop/FFI_CONTRACT.md`](interop/FFI_CONTRACT.md)
- [`interop/ANDROID_INTEGRATION.md`](interop/ANDROID_INTEGRATION.md)
- [`interop/IOS_INTEGRATION.md`](interop/IOS_INTEGRATION.md)
- [`interop/MEDIA_PIPELINE.md`](interop/MEDIA_PIPELINE.md)

## Assurance

- [`assurance/FUZZING.md`](assurance/FUZZING.md)
- [`assurance/SECURITY_TEST_PLAN.md`](assurance/SECURITY_TEST_PLAN.md)
- [`assurance/MIGRATION_POLICY.md`](assurance/MIGRATION_POLICY.md)
- [`assurance/PERFORMANCE_BUDGETS.md`](assurance/PERFORMANCE_BUDGETS.md)
- [`assurance/RELEASE_GATES.md`](assurance/RELEASE_GATES.md)
- [`assurance/SECURITY_REVIEW_SCOPE.md`](assurance/SECURITY_REVIEW_SCOPE.md)

## Sync and sharing

- [`sync/SERVER_TRUST_MODEL.md`](sync/SERVER_TRUST_MODEL.md)
- [`sync/DEVICE_IDENTITY.md`](sync/DEVICE_IDENTITY.md)
- [`sync/OPERATION_LOG.md`](sync/OPERATION_LOG.md)
- [`sync/SYNC_PROTOCOL_V1.md`](sync/SYNC_PROTOCOL_V1.md)
- [`sync/ROLLBACK_PROTECTION.md`](sync/ROLLBACK_PROTECTION.md)
- [`sync/CONFLICT_RESOLUTION.md`](sync/CONFLICT_RESOLUTION.md)
- [`sync/COLLECTION_GRANTS.md`](sync/COLLECTION_GRANTS.md)
- [`sync/REVOCATION.md`](sync/REVOCATION.md)

## Decisions

- [`adr/README.md`](adr/README.md) — ADR format and index.
- ADR-0001 through ADR-0009 record the foundational ownership, chunking, envelope, catalog, decoy, FFI, local-first, container-freeze, and HKDF-label-registry decisions.

## Test vectors

The repository-level [`test-vectors/`](../test-vectors/README.md) directory contains deterministic compatibility fixtures. Vector files, not examples embedded in prose, are the machine-readable interoperability authority.

## Change process

A normative change should:

1. identify affected security invariants;
2. update or add an ADR;
3. update byte-exact specifications and allocate any new constant in the registry;
4. add vectors and negative tests;
5. define migration and downgrade behavior;
6. update platform and FFI contracts;
7. pass the applicable release gates.

## Writing rules

- define terms before using them normatively;
- distinguish user-facing names from cryptographic identifiers;
- distinguish accepted requirements from proposals;
- avoid security marketing language;
- link requirements to tests and owners;
- never include real secrets or private user data.
