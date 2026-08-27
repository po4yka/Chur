//! Both key envelopes over arbitrary bytes.

#![no_main]

use libfuzzer_sys::fuzz_target;

use chur_format::envelope::{CollectionKeyEnvelope, ObjectKeyEnvelope};

fuzz_target!(|data: &[u8]| {
    if data.len() > 256 {
        return;
    }
    if let Ok(envelope) = ObjectKeyEnvelope::decode(data) {
        assert_eq!(envelope.encode(), data);
        assert_eq!(data.len(), ObjectKeyEnvelope::LEN);
        assert_ne!(envelope.collection_epoch(), 0);
        assert_ne!(envelope.envelope_generation(), u64::MAX);
    }
    if let Ok(envelope) = CollectionKeyEnvelope::decode(data) {
        assert_eq!(envelope.encode(), data);
        assert_eq!(data.len(), CollectionKeyEnvelope::LEN);
    }
});
