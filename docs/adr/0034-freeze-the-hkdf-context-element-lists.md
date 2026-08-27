# ADR-0034: Freeze the HKDF Context Element List of Every v1 Label

- **Status:** Accepted
- **Date:** 2026-08-27
- **Decision owners:** @po4yka
- **Related:** [`../security/KEY_HIERARCHY.md`](../security/KEY_HIERARCHY.md), [`../CRYPTOGRAPHY.md`](../CRYPTOGRAPHY.md), [`0018`](0018-freeze-the-hkdf-label-registry.md)

## Context

`CRYPTOGRAPHY.md` §13 builds the HKDF `info` value as `CanonicalTuple("CHUR\x00KDF\x00INFO\x00V1", purpose_label, context_fields)` and delegated `context_fields` to "the specification that owns the derivation". Only four of the twenty-five registered labels had such an owner: `collection-envelope`, `object-envelope`, `descriptor-auth`, and `apple-device-kek`. The remaining twenty-one had a label and an output length but no element list, so two implementations could derive different bytes from the same registered label and both could claim to follow the specification.

`TEST_VECTORS.md` §4 requires a positive vector for every label in the registry. A vector cannot exist for a derivation whose input is undefined, so the gap blocked the vector set and, with it, the Phase 0 exit criterion that Android, iOS, and CLI consume identical vectors.

`CRYPTOGRAPHY.md` §21 also wrote the recovery context as `vault_id || identity_id || slot_id`. `identity_id` appears in no record layout in the repository, and §23 already gives every vault identity its own random `vault_id`.

## Decision

- `KEY_HIERARCHY.md` §3 gains a context registry that lists, for all twenty-five labels, the exact elements in exact order with their canonical-encoding types. It is the only definition of a context element list; `CRYPTOGRAPHY.md` §13 and §29 point at it.
- A root label carries `vault_id`. A collection label carries `collection_id` and `collection_epoch`. An object label carries `object_id` and the revision of the stream it protects. A slot label carries `vault_id`, `slot_id`, and `slot_generation`.
- The three container labels `manifest`, `content`, and `final-commit` additionally carry `stream_id`, so one container's record keys never open another stream of the same object and kind.
- The eight derived-asset labels carry `stream_kind`, `source_content_revision`, and `stream_revision`. They key a derived asset stored as one AEAD record. A derived asset stored as a container instead derives its record keys from `ObjectKey` under the three container labels with that stream's kind and revision.
- `identity_id` is removed from the recovery context and is a context element of no v1 label. The recovery context becomes `vault_id`, `slot_id`, `slot_generation`, which matches the Apple device slot and binds the KEK to one slot generation.
- Changing an element list is a new label plus a migration, under the change rule already in `KEY_HIERARCHY.md` §3.

## Alternatives considered

### Leave the lists to each owning specification

Rejected. Twenty-one labels had no owning specification willing to claim them, which is how the gap arose. A registry that holds the label but not its input is half a registry, and the half it omits is the half that selects key bytes.

### Bind every element the record carries

Rejected. A context that repeats fields the AAD already binds enlarges the input without adding separation, and each added element is one more value an implementation can order differently. The rule adopted binds the scope over which the key must be unique and stops there.

### Keep `identity_id` and allocate it a width

Rejected. It would need a source, and the only candidate value is `vault_id` itself. A second name for one value is how the object-key envelope acquired `object_key_version`, which [`0025`](0025-freeze-the-object-key-envelope-aad.md) removed for the same reason.

## Consequences

### Positive

- every registered label now derives one defined key from one defined input;
- the per-label vectors required by `TEST_VECTORS.md` §4 become writable;
- a derived asset cannot be lifted between objects, kinds, or revisions whichever storage form it takes.

### Tradeoffs

- twenty-five element lists are frozen before most of them have a caller, so a purpose that turns out to need another element takes a new label rather than an edit.

## Security impact

Affected invariants: SEC-002, SEC-003, SEC-004, SEC-005.

No invariant changes. The decision closes an under-specification: SEC-004 requires domain separation between derived keys, and a label with no context list could not demonstrate it. Removing `identity_id` removes an element that carried no entropy of its own.

## Compatibility impact

No shipped bytes change; no vault, container, or vector exists. The recovery-slot KEK of `CRYPTOGRAPHY.md` §21 is defined for the first time rather than redefined.

## Validation

- a derivation test asserts the exact `info` bytes for one label of each tier against a checked-in vector;
- a test asserts that the same label with a different context yields a different key;
- the vector generator refuses a label absent from the registry.
