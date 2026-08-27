# ADR-0036: Freeze the Four v1 Key-Slot Bodies

- **Status:** Accepted
- **Date:** 2026-08-27
- **Decision owners:** @po4yka
- **Related:** [`../format/KEY_SLOT_BODIES_V1.md`](../format/KEY_SLOT_BODIES_V1.md), [`../security/KEY_SLOTS.md`](../security/KEY_SLOTS.md), [`../format/VAULT_DESCRIPTOR_V1.md`](../format/VAULT_DESCRIPTOR_V1.md), [`0019`](0019-freeze-remaining-v1-record-layouts.md), [`0032`](0032-vault-creation-requires-a-password-slot.md)

## Context

`VAULT_DESCRIPTOR_V1.md` §7 froze the 34-byte key-slot header and one length-prefixed `slot_body`, and pointed at `KEY_SLOTS.md` for the body schema. `KEY_SLOTS.md` describes slot behaviour in prose and states in its own status line that byte-exact encoding "remains defined by the format specifications". No format specification defined it. The descriptor could therefore be parsed but no slot could be opened, which meant no vault could be unlocked.

Two consequences followed. [ADR-0032](0032-vault-creation-requires-a-password-slot.md) requires a verified password slot before a vault is created, and the record it requires had no bytes. `CRYPTOGRAPHY.md` §18.4 wrote the password slot AAD as a tuple whose last element was the phrase "Argon2 public parameters", which is a group of values and not one element, and whose second element was `identity_id`, a name that appears in no record layout.

## Decision

- `docs/format/KEY_SLOT_BODIES_V1.md` owns the `slot_body` of every family and its AAD tuple. `KEY_SLOTS.md` stays authoritative for behaviour, policy, and lifecycle.
- Four bodies are frozen: `PasswordSlotBodyV1` at `92 + salt_length` bytes, `RecoverySlotBodyV1` at exactly 74, `AndroidKeystoreSlotBodyV1` at `66 + alias_length`, `AppleKeychainSlotBodyV1` at exactly 90. Each ends with a 48-byte `wrapped_root_secret`.
- Every family AAD repeats the same six binding elements before its family-specific elements: `vault_id`, `slot_id`, `slot_type`, `slot_version`, `wrap_suite_id`, `slot_generation`. That is the `KEY_SLOTS.md` §2 requirement written as bytes.
- Each Argon2 parameter is a separate AAD element, so lowering `memory_kib` in a stored slot changes the AAD and the unwrap fails even if the parser bound were removed.
- `identity_id` is an element of no slot AAD, matching [ADR-0034](0034-freeze-the-hkdf-context-element-lists.md).
- Three domain tags are allocated: `CHUR\x00SLOT\x00RECOVERY\x00V1`, `CHUR\x00SLOT\x00ANDROID-KEYSTORE\x00V1`, `CHUR\x00SLOT\x00APPLE-KEYCHAIN\x00V1`. Four profile identifier namespaces are allocated in `CANONICAL_ENCODING_V1.md` §15.2.
- `PeerDeviceSlotV1` keeps its allocated `slot_type` and receives no v1 body.

## Alternatives considered

### One body schema shared by every family

Rejected. The families differ in what they must carry: a password slot needs Argon2 parameters and a salt, a recovery slot needs neither, and the Android slot carries a 12-byte GCM nonce because its AEAD runs in the platform Keystore rather than in Rust. A union type with unused fields would put attacker-controlled bytes in every slot that does not use them.

### Encode the body as a tagged extension record

Rejected. `CANONICAL_ENCODING_V1.md` §6 states that core v1 security records should prefer fixed schemas. A slot body is parsed before any credential exists, which is the worst place for tag ordering, duplicate-tag, and unknown-tag rules.

### Store the wrapped root as the Keychain secret on Apple

Not decided here. `KEY_SLOTS.md` §5 leaves the two models open and requires an ADR for the alternative. `keychain_profile_id` `0x0001` is the `DeviceUnlockSecret` model because it keeps the AEAD in Rust and therefore test-vectorable; the alternative would take `0x0002`.

## Consequences

### Positive

- a vault can be created, sealed, and unlocked from bytes that two implementations can agree on;
- the password slot vectors of `TEST_VECTORS.md` §4 and the every-family requirement of `KEY_SLOTS.md` §12 become writable;
- a downgraded Argon2 parameter is rejected twice: by the parser bound and by the AAD.

### Tradeoffs

- the Android family is frozen before its prototype exists, so a prototype that needs another public parameter takes `keystore_profile_id` `0x0002`;
- `wrap_suite_id` for the Android family denotes AES-256-GCM and has no allocated value yet, so a v1 descriptor carries no Android slot until the prototype lands.

## Security impact

Affected invariants: SEC-006, SEC-007, SEC-008, SEC-009, SEC-013.

No invariant changes. SEC-008 requires that slot parameters be validated before key-derivation work; §8 of the new document gives that check an ordered list to run. SEC-013 requires that a slot not authenticate against another vault, which the six common binding elements now enforce byte by byte.

## Compatibility impact

No persisted bytes change. No vault exists, and no slot was previously encodable.

## Validation

- one deterministic vector per family with its encoded AAD and its byte length;
- a negative vector per family truncated at every field boundary;
- a test that alters `memory_kib` after sealing and asserts the parser bound fires first and the AEAD fails second;
- a test that the same root wrapped by a password slot and a recovery slot unwraps to identical bytes.
