//! The waveform record over arbitrary bytes.
//!
//! `Waveform::decode` is a canonical decoder: on success the record re-encodes
//! to exactly the input bytes, and every failure is a bounded rejection before
//! any allocation tracks the decoded sample count.

#![no_main]

use libfuzzer_sys::fuzz_target;

use chur_format::waveform::Waveform;

fuzz_target!(|data: &[u8]| {
    if data.len() > 4096 {
        return;
    }
    if let Ok(waveform) = Waveform::decode(data) {
        assert_eq!(waveform.encode(), data);
    }
});
