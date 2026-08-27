//! The vault descriptor over arbitrary bytes.
//!
//! Steps 1 and 2 of `VAULT_DESCRIPTOR_V1.md` §8 run before any credential, so
//! this target reaches them with no key at all.

#![no_main]

use libfuzzer_sys::fuzz_target;

use chur_format::descriptor::VaultDescriptor;

fuzz_target!(|data: &[u8]| {
    if data.len() > 65_536 {
        return;
    }
    if let Ok(descriptor) = VaultDescriptor::parse(data) {
        assert!(!descriptor.key_slots.is_empty());
        assert!(descriptor.key_slots.len() <= 16);
        assert_ne!(descriptor.descriptor_generation, u64::MAX);
        let total: usize = descriptor
            .key_slots
            .iter()
            .map(|entry| entry.slot_body.len())
            .sum();
        assert!(total <= 16_384);
    }
});
