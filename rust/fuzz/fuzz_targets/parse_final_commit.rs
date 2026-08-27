//! `CanonicalFinalCommit` over arbitrary bytes.

#![no_main]

use libfuzzer_sys::fuzz_target;

use chur_format::container::CanonicalFinalCommit;

fuzz_target!(|data: &[u8]| {
    if data.len() > 256 {
        return;
    }
    if let Ok(commit) = CanonicalFinalCommit::decode(data) {
        assert_eq!(commit.encode(), data);
        assert_eq!(commit.len(), 128);
        assert!(commit.chunk_count() <= 1_048_576);
        assert!(commit.total_plaintext_length() <= 1_099_511_627_776);
    }
});
