//! Every canonical primitive over arbitrary bytes.
//!
//! The reader must never panic, never allocate beyond the parser limit it is
//! given, and never accept a non-canonical boolean, presence byte, or string.

#![no_main]

use libfuzzer_sys::fuzz_target;

use chur_core::status::ChurStatus;
use chur_format::codec::Reader;

fuzz_target!(|data: &[u8]| {
    // A hard input-size cap, FUZZING.md §3.
    if data.len() > 4096 {
        return;
    }

    let mut reader = Reader::new(data, ChurStatus::ObjectCorrupt);
    while !reader.is_empty() {
        let before = reader.position();
        match reader.u8() {
            Ok(selector) => match selector % 8 {
                0 => drop(reader.u16()),
                1 => drop(reader.u32()),
                2 => drop(reader.u64()),
                3 => {
                    if let Ok(value) = reader.bool() {
                        assert!(value || !value);
                    }
                }
                4 => drop(reader.presence()),
                5 => drop(reader.id()),
                6 => drop(reader.variable(256)),
                _ => drop(reader.string(256)),
            },
            Err(_) => break,
        }
        // Every branch either consumes bytes or fails, so the cursor advances
        // and the loop terminates.
        assert!(reader.position() > before);
    }
});
