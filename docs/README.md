# Chur Documentation

> **Status:** Accepted documentation index, authority hierarchy, and change process

This directory contains the normative and supporting design documents for Chur.

## Authority hierarchy

When documents conflict, use this order until the conflict is resolved:

1. byte-exact versioned format or protocol specifications;
2. accepted ADRs that explicitly supersede earlier decisions;
3. focused normative security, interop, assurance, sync, and product specifications, and [`DESIGN.md`](../DESIGN.md) for visual and interaction contracts only;
4. [`CRYPTOGRAPHY.md`](CRYPTOGRAPHY.md);
5. [`ARCHITECTURE.md`](ARCHITECTURE.md);
6. [`ANDROID.md`](ANDROID.md) and [`IOS.md`](IOS.md), normative for platform behavior only; where either restates a rule owned by a document above, the higher document wins and the platform text is a defect;
7. root [`README.md`](../README.md), roadmap, and explanatory material.

Implementation behavior is not authoritative merely because it exists. A divergence from a normative specification is a defect unless a migration and specification change are approved.

## Normative language

The words must, must not, required, should, should not, and may are used as defined in [RFC 2119](https://www.rfc-editor.org/rfc/rfc2119.html).

Chur writes them in lowercase. In a normative document, lowercase must, must not, should, should not, and may carry their full RFC 2119 force and are not weaker than the same words in capitals. This is a deliberate departure from RFC 8174 §2, which reserves the meaning for the uppercase spelling: the focused format, security, interop, assurance, sync, and product specifications and the ADRs already state their requirements in lowercase, and converting them would change forty-one files to gain what this rule states once.

`CRYPTOGRAPHY.md`, `ARCHITECTURE.md`, `ANDROID.md`, and `IOS.md` capitalize the keywords. The capitals are typography, not extra strength. A new document should use lowercase.

## Document status

This is the project's only document-status vocabulary. Every document under `docs/` and under `test-vectors/`, and the root `README.md`, `ROADMAP.md`, `DESIGN.md`, `CONTRIBUTING.md`, `DEVELOPMENT.md`, and `SECURITY.md`, must state one of these labels in its header. Agent and tooling configuration such as `AGENTS.md` is out of scope.

- **Proposed** — direction under review; not compatibility-stable.
- **Accepted** — in force; changes follow the change process below and the versioning rules.
- **Experimental** — prototype used to collect evidence.
- **Deprecated** — readable and migratable but not used for new data.
- **Superseded** — replaced by a named document or ADR. An ADR spells this `Superseded by ADR-NNNN` so the replacement is named.
- **Rejected** — considered and not adopted; kept for the record.

Explanatory prose may follow the label, but the label itself comes from this list.

Byte-exact v1 documents remain proposed until constants, encoding, vectors, and cross-platform implementations are frozen.

## Statement classification

Document status describes a whole document. Individual statements inside a document are classified as:

- **Decision** — accepted direction for the implementation.
- **Invariant** — a property every implementation and migration preserves.
- **Proposal** — preferred direction that still requires a benchmark, prototype, or ADR.
- **Deferred** — intentionally excluded from the current phase.
- **Non-goal** — a guarantee Chur does not claim.

`CRYPTOGRAPHY.md`, `ARCHITECTURE.md`, `ANDROID.md`, and `IOS.md` use this classification. It is not a document status: a **Proposed** document may contain **Decision** statements.

## Root documents

- [`README.md`](../README.md) — product overview and explanatory material; rank 6.
- [`ROADMAP.md`](../ROADMAP.md) — owns the phase definitions, their scope, exclusions, and exit criteria; `ARCHITECTURE.md` §44 and the root README point at it.
- [`DESIGN.md`](../DESIGN.md) — visual and interaction contracts; rank 3 for presentation only. Privacy-sensitive transitions, lock behavior, and error semantics are owned by [`product/DISCREET_MODE.md`](product/DISCREET_MODE.md), [`security/PLAINTEXT_LIFECYCLE.md`](security/PLAINTEXT_LIFECYCLE.md), and [`ERROR_MODEL.md`](ERROR_MODEL.md).
- [`CONTRIBUTING.md`](../CONTRIBUTING.md) — contribution process, reading order, and format-change requirements.
- [`DEVELOPMENT.md`](../DEVELOPMENT.md) — development environment, pinned toolchains, and build workflow.
- [`SECURITY.md`](../SECURITY.md) — vulnerability reporting and supported versions.

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
- [`format/COLLECTION_KEY_ENVELOPE_V1.md`](format/COLLECTION_KEY_ENVELOPE_V1.md)
- [`format/OBJECT_KEY_ENVELOPE_V1.md`](format/OBJECT_KEY_ENVELOPE_V1.md)
- [`format/OBJECT_CONTAINER_V1.md`](format/OBJECT_CONTAINER_V1.md)
- [`format/CATALOG_SCHEMA_V1.md`](format/CATALOG_SCHEMA_V1.md)
- [`format/BACKUP_FORMAT_V1.md`](format/BACKUP_FORMAT_V1.md)
- [`format/TEST_VECTORS.md`](format/TEST_VECTORS.md)

## Interop

The complete platform documents are [`ANDROID.md`](ANDROID.md) and [`IOS.md`](IOS.md), at tier 6 of the hierarchy above; these focused contracts define the cross-platform boundary they implement, and the platform documents defer to them for the boundary itself.

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
- ADR-0001 through ADR-0017 record the foundational ownership, chunking, envelope, catalog, decoy, FFI, local-first, container-freeze, label-registry, canonical-tuple, descriptor-authentication, import-journal, constant-allocation, sync-causality, C-ABI-freeze, and supported-device decisions.

## Test vectors

The repository-level [`test-vectors/`](../test-vectors/README.md) directory will contain deterministic compatibility fixtures. Vector files, not examples embedded in prose, are the machine-readable interoperability authority.

It currently holds only the two scaffold READMEs. The `manifest.json` and the fixture groups diagrammed in [`format/TEST_VECTORS.md`](format/TEST_VECTORS.md) §1 land with the first `chur-cli` vector generator, which is a Phase 0 deliverable. Until then no document may cite a vector file as settled evidence.

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

## Generation counters

Ten counters are spelled "generation" and they are not one concept. The naming rule is: a counter that reaches persisted or wire bytes is named `<artifact>_generation`, is a `u64` under [`format/CANONICAL_ENCODING_V1.md`](format/CANONICAL_ENCODING_V1.md) §2, and is compared by a reader to reject stale state. The session counter is the only in-memory one; it is never encoded and never compared across processes.

| Counter | Defined in | Counts | Persisted | Compared for staleness |
| --- | --- | --- | --- | --- |
| `descriptor_generation` | [`format/VAULT_DESCRIPTOR_V1.md`](format/VAULT_DESCRIPTOR_V1.md) §2 | rewrites of one vault descriptor | yes | yes, §10 there |
| `catalog_generation` | [`format/VAULT_DESCRIPTOR_V1.md`](format/VAULT_DESCRIPTOR_V1.md) §5 and [`format/CATALOG_SCHEMA_V1.md`](format/CATALOG_SCHEMA_V1.md) §2 | committed catalog states | yes | yes |
| `slot_generation` | [`security/KEY_SLOTS.md`](security/KEY_SLOTS.md) §2 and [`format/VAULT_DESCRIPTOR_V1.md`](format/VAULT_DESCRIPTOR_V1.md) §7 | replacements of one key slot | yes | yes |
| `envelope_generation` | [`format/COLLECTION_KEY_ENVELOPE_V1.md`](format/COLLECTION_KEY_ENVELOPE_V1.md) §5 and [`format/OBJECT_KEY_ENVELOPE_V1.md`](format/OBJECT_KEY_ENVELOPE_V1.md) §5 | rewraps of one key envelope | yes | yes |
| `manifest_generation` | [`format/OBJECT_CONTAINER_V1.md`](format/OBJECT_CONTAINER_V1.md) §5 | sealed manifests of one stream revision | yes, sealed | yes |
| `commit_generation` | [`format/OBJECT_CONTAINER_V1.md`](format/OBJECT_CONTAINER_V1.md) §11 | final commits of one stream revision | yes, sealed | yes |
| `object_generation` | [`format/CATALOG_SCHEMA_V1.md`](format/CATALOG_SCHEMA_V1.md) §5 | catalog-row states of one media object | yes, catalog only | locally only |
| `membership_generation` | [`sync/COLLECTION_GRANTS.md`](sync/COLLECTION_GRANTS.md) §2 | accepted membership sets of one collection | yes, and on the wire | yes |
| grant generation | [`sync/ROLLBACK_PROTECTION.md`](sync/ROLLBACK_PROTECTION.md) §2 | grants issued to one recipient | yes, and on the wire | yes |
| session generation | [`interop/FFI_CONTRACT.md`](interop/FFI_CONTRACT.md) §4 | unlock-to-lock cycles inside one process | no, in-memory only | no, handle validity only |

"Handle generation" in prose is the session counter of the last row, and `session_generation` in [`format/OBJECT_CONTAINER_V1.md`](format/OBJECT_CONTAINER_V1.md) §12 is the same value. The "global/materialized state generation" of [`sync/ROLLBACK_PROTECTION.md`](sync/ROLLBACK_PROTECTION.md) §2 is not defined in v1 and takes a row here in the change that defines it. The generation digit of a file magic, [`format/CANONICAL_ENCODING_V1.md`](format/CANONICAL_ENCODING_V1.md) §15.1, is not a counter; it is the eighth byte of the magic.

A specification that adds a counter adds a row here in the same change.
