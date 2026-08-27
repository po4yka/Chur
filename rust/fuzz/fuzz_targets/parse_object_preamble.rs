//! The 28-byte container preamble over arbitrary bytes.

#![no_main]

use libfuzzer_sys::fuzz_target;

use chur_core::ChurStatus;
use chur_format::container::PublicPreamble;

fuzz_target!(|data: &[u8]| {
    if data.len() > 64 {
        return;
    }
    if let Ok(preamble) = PublicPreamble::decode(data) {
        // An accepted preamble re-encodes to exactly the bytes it came from,
        // which is what CANONICAL_ENCODING_V1.md §11 means by canonical.
        assert_eq!(preamble.encode(), data);
        let length = preamble.manifest_record_length();
        assert!((40..=65_536).contains(&length));
    } else if data.len() == PublicPreamble::LEN {
        // A rejection of a full-length preamble carries a classified status.
        let status = PublicPreamble::decode(data)
            .err()
            .map(|error| error.status())
            .unwrap_or(ChurStatus::InternalFailure);
        assert!(matches!(
            status,
            ChurStatus::ObjectCorrupt
                | ChurStatus::UnsupportedVersion
                | ChurStatus::UnsupportedSuite
        ));
    }
});
