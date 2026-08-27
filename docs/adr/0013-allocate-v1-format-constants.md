# ADR-0013: Allocate the v1 Format Constants in One Registry

- **Status:** Accepted
- **Date:** 2026-08-27
- **Decision owners:** @po4yka
- **Related:** [`../format/CANONICAL_ENCODING_V1.md`](../format/CANONICAL_ENCODING_V1.md) §15, [`0008`](0008-freeze-object-container-v1-layout.md), [`../format/VAULT_DESCRIPTOR_V1.md`](../format/VAULT_DESCRIPTOR_V1.md), [`../format/BACKUP_FORMAT_V1.md`](../format/BACKUP_FORMAT_V1.md)

## Context

ADR-0008 froze the object container preamble and assigned `CHUROBJ1` with the v1 values of `container_version`, `canonical_encoding_profile`, `suite_id`, and `chunk_record_profile`. Nothing else in the byte-exact tier held a value: the vault descriptor declared `magic` and `descriptor_version` with no bytes behind them, the backup package had no magic at all, the object-key envelope declared `format_version`, `encoding_profile`, and `suite_id` as typed fields and referred their values to vectors that do not exist, and `record_type` was defined only inside the container. The deferral was also unowned. `CANONICAL_ENCODING_V1.md` §7 called exact tags "registry-controlled" while no registry existed, so two formats could reach the same value independently and no document said which one held it.

## Decision

Allocate every remaining v1 constant, and record all of them, the container's frozen values included, in one registry: §15 of `CANONICAL_ENCODING_V1.md`.

- magics `CHURVLT1` for `VaultDescriptorV1` and `CHURBAK1` for `BackupPackageV1`, beside `CHUROBJ1`;
- `0x0001` for every remaining v1 version, encoding profile, suite, policy, and profile identifier across the descriptor, envelope, backup package, catalog schema, object store, and key slots;
- `record_type` scoped per format: the container keeps `0x01` and `0x02`, and the backup package takes `0x01` to `0x07` over the components of its package model;
- `u8` discriminants for the descriptor `state` values and the key-slot families, in the order their specifications already list;
- a pairwise-distinctness rule for magics, and an allocation rule: the change that freezes a record allocates its values, `0x0000` and `0x00` are invalid, the top of each namespace is reserved for local experiments, and an allocated value is never reused for a different meaning. The registry records allocation; the owning specification keeps layout and meaning.

## Alternatives considered

### A separate `format/REGISTRY_V1.md`

Rejected. `CANONICAL_ENCODING_V1.md` is the only format document that is not the specification of one artifact, its §7 already delegated exact values to a registry, and its status line already flagged numeric values as provisional. Closing the deferral inside the document that opened it adds no fourth place to look.

### Replace the container's value column with a pointer to the registry

Rejected. The preamble table is the frozen artifact a parser is written from. Moving those bytes behind a pointer would split layout from value and let a registry edit change frozen container bytes. The registry repeats the values and names `OBJECT_CONTAINER_V1.md` as the authority for them.

## Consequences

### Positive

- the descriptor, backup package, and envelope can be implemented and their vectors generated;
- one document answers whether a value is free, so two formats cannot collide;
- a reader that has read eight bytes has identified the file or rejected it.

### Tradeoffs

- the container's values now appear in two documents; the registry rows name the container specification as their authority so the copy does not become a second source of truth;
- backup record types are allocated over a package model whose framing is not frozen, so a component the frozen framing does not use keeps a reserved and unused number.

## Security impact

Affected invariants: SEC-005, SEC-014, SEC-018, SEC-025. Distinct magics keep a vault descriptor, a backup package, and a container from being parsed as one another, so a substituted file fails at eight bytes rather than inside a parser that expects different fields. Requiring rejection of every unallocated value keeps an unknown version, suite, record type, or discriminant a fail-closed outcome. The registry allocates numbers only; it does not authenticate them, and every constant that reaches an AAD stays bound by its owning construction.

## Compatibility impact

No bytes exist yet for any of these formats, so nothing migrates. The version and profile fields remain the versioning path; a change to a frozen value takes a new allocation and a dual-reader policy, never a redefinition.

## Validation

- negative vectors that present each magic to the parsers of the other two formats;
- unsupported version, suite, profile, record-type, and discriminant vectors for every format;
- a check that no value appears twice within one registry namespace.

## Follow-up

- allocate a domain tag for each authenticated record whose AAD is not yet frozen, starting with the sync operation record, in the change that freezes it;
- freeze the backup package framing and confirm or retire the reserved record types;
- allocate the password profile identifier with the Argon2id parameter profile.
