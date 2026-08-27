//! The whole container record sequence over arbitrary bytes.
//!
//! `Layout::parse` validates every chunk and final-commit header with no key,
//! which is the structural half of `OBJECT_CONTAINER_V1.md` §8 and §11.

#![no_main]

use libfuzzer_sys::fuzz_target;

use chur_format::container::Layout;

fuzz_target!(|data: &[u8]| {
    if data.len() > 65_536 {
        return;
    }
    if let Ok(layout) = Layout::parse(data) {
        assert!(layout.chunk_count() <= 1_048_576);
        assert!(layout.first_chunk_offset() <= data.len() as u64);
        // Recomputing the ordered commitment must not fail on a layout the
        // parser already accepted.
        assert!(layout.ordered_chunk_commitment(data).is_ok());
    }
});
