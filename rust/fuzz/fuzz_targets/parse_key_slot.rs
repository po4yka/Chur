//! All four key-slot bodies over arbitrary bytes.
//!
//! No derivation runs here. `FUZZING.md` §3 fuzzes parameter validation
//! separately from the KDF, which `password_parameter_validation` does.

#![no_main]

use libfuzzer_sys::fuzz_target;

use chur_format::slot::{
    AndroidKeystoreSlotBody, AppleKeychainSlotBody, PasswordSlotBody, RecoverySlotBody,
};

fuzz_target!(|data: &[u8]| {
    if data.len() > 4096 {
        return;
    }
    if let Ok(body) = PasswordSlotBody::decode(data) {
        assert_eq!(body.encode(), data);
        assert!((16..=32).contains(&body.salt().len()));
        assert!((65_536..=524_288).contains(&body.params().memory_kib()));
        assert!((3..=10).contains(&body.params().iterations()));
        assert!((1..=4).contains(&body.params().parallelism()));
    }
    if let Ok(body) = RecoverySlotBody::decode(data) {
        assert_eq!(body.encode(), data);
        assert_eq!(data.len(), RecoverySlotBody::LEN);
    }
    if let Ok(body) = AppleKeychainSlotBody::decode(data) {
        assert_eq!(body.encode(), data);
        assert_eq!(data.len(), AppleKeychainSlotBody::LEN);
    }
    if let Ok(body) = AndroidKeystoreSlotBody::decode(data) {
        assert_eq!(body.encode(), data);
        assert!((16..=64).contains(&body.alias().len()));
    }
});
