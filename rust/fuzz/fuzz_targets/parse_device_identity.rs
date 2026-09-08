//! The device identity envelope over arbitrary bytes.
//!
//! `DEVICE_IDENTITY.md` §9 fixes the fixed-width identity record a vault
//! parses when it reads another device's enrollment material, so a byte one
//! short or one suite tag off must reject without ever reading past its
//! bounds. The target is parsed for panic-freedom and bounded rejection.

#![no_main]

use libfuzzer_sys::fuzz_target;

use chur_sync_protocol::identity::DeviceIdentityEnvelope;

fuzz_target!(|data: &[u8]| {
    if data.len() > 65_536 {
        return;
    }
    let _ = DeviceIdentityEnvelope::decode(data);
});
