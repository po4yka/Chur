# ADR-0041: The Android Keystore Slot Exchanges Root Bytes

- **Status:** Accepted
- **Date:** 2026-08-28
- **Decision owners:** @po4yka
- **Related:** [`0001`](0001-rust-owns-private-vault.md), [`0016`](0016-freeze-the-v1-c-abi.md), [`../security/KEY_SLOTS.md`](../security/KEY_SLOTS.md) §4, [`../format/KEY_SLOT_BODIES_V1.md`](../format/KEY_SLOT_BODIES_V1.md) §5, [`../interop/FFI_CONTRACT.md`](../interop/FFI_CONTRACT.md) §6.6 and §12

## Context

[ADR-0001](0001-rust-owns-private-vault.md) gives Rust every private byte, and [`../interop/FFI_CONTRACT.md`](../interop/FFI_CONTRACT.md) §12 keeps root, collection, and object keys away from application code. Three of the four v1 key-slot families obey that without an exception: the password, recovery, and Apple Keychain slots each hand Rust a secret, Rust derives a KEK from it, and Rust performs the AEAD. The root never leaves.

The Android Keystore family cannot work that way. Its wrapping key is generated inside the Keystore and is non-exportable by construction, which is the property that makes it worth having: no software copy exists to steal. The cipher therefore runs on the platform side, and [`../format/KEY_SLOT_BODIES_V1.md`](../format/KEY_SLOT_BODIES_V1.md) §5 records the consequence in the format itself. `AndroidKeystoreSlotBodyV1` stores a 12-byte GCM nonce and 48 wrapped bytes that Rust never produced and cannot open, and `wrap_suite_id` `0x0002` exists to say so.

An AEAD that runs on the platform side needs its plaintext on the platform side. So enrolling the slot means handing the root secret out, and unlocking with it means taking the root secret back.

This was specified in Phase 0 and not implemented, and the gap was recorded as a Phase 1 limitation. Implementing it forces the exception to be stated rather than implied.

## Decision

The Android Keystore family is the one place a Chur root secret crosses the FFI boundary. [`../interop/FFI_CONTRACT.md`](../interop/FFI_CONTRACT.md) §6.6 adds three exports for it at ABI 1.2:

- `chur_vault_keystore_begin` returns an opaque alias, the §4 AAD, and the root secret. It records a pending enrollment in the session and writes nothing to the descriptor;
- `chur_vault_keystore_commit` takes the nonce and the wrapped bytes the Keystore produced and writes the slot. An enrollment the platform abandons leaves the vault exactly as it was;
- `chur_vault_keystore_material` runs on a locked runtime and returns what an unwrap needs. Nothing it returns is secret: every field is already stored in the clear in the descriptor.

Unlock factor `4` carries the unwrapped root rather than a value a slot body opens. It is verified the way every other factor is: the descriptor authenticates under the root, so a wrong or substituted value is `AUTHENTICATION_FAILED` and not corruption. A descriptor with no Keystore slot is skipped, so a root obtained some other way opens no vault that was never enrolled on this device.

Three constraints bound the exposure, and each one is a rule rather than an intention:

1. **the slot identity is decided before the wrap.** The AAD binds the slot id and the slot generation, so Rust allocates both in `begin`. A wrap performed under one identity and stored under another would never open;
2. **every holder clears its copy.** `chur-ffi` zeroizes the encoded record, `chur-jni` passes a direct buffer the caller clears, and `VaultRepository.enrollKeystoreSlot` and `unlockWithKeystoreRoot` fill their arrays with zeroes in a `finally`. The window is one platform call wide;
3. **the exception is this family and no other.** No other export returns a root, and `chur_vault_add_device_slot` continues to return a `DeviceUnlockSecret` rather than a root, because the Apple design does not need one.

## Consequences

The Android device slot is as strong as the Keystore key that holds it and as weak as the process during one wrap or one unwrap. A host that leaks its heap during that window leaks the vault root, where the same leak on iOS would leak a `DeviceUnlockSecret` that is useless without the slot body. That is a real difference between the two platforms and it is stated here rather than smoothed over.

The alternative is in [`../security/KEY_SLOTS.md`](../security/KEY_SLOTS.md) §5's shape: store a random `DeviceUnlockSecret` wrapped by the Keystore key, and let Rust derive a KEK from it and wrap the root itself. Then the root never leaves and the Keystore holds a value that opens nothing on its own. It was rejected for v1 for one reason: `AndroidKeystoreSlotBodyV1` is a frozen v1 format whose field is `wrapped_root_secret`, and [`../assurance/MIGRATION_POLICY.md`](../assurance/MIGRATION_POLICY.md) does not permit reinterpreting a frozen field. A v2 body may make that change, and a later ADR should revisit it, because the Apple model is the better one.

Until then, a host that will not accept the exposure has a supported answer: do not enroll the slot. [`../security/PROVISIONING.md`](../security/PROVISIONING.md) §5 already makes a device slot never the only slot, so a vault with a password and a recovery phrase and no Keystore slot is a complete vault.

## Alternatives considered

### Wrap a device secret rather than the root

The Apple model, described above. Correct, and blocked on the frozen v1 body.

### Pass a callback into the Keystore through the FFI

Rejected. [`../interop/FFI_CONTRACT.md`](../interop/FFI_CONTRACT.md) §8 makes every native call synchronous and takes no function pointer from a host. A callback would also not reduce the exposure: the root would still be in the host process, just on a different stack.

### Keep the family unimplemented

This is what Phase 0 did, and it is honest. It was rejected for Phase 1 because "password, device, and recovery key slots" is Phase 1 scope, and an Android build in which the device slot is absent delivers two of three.
