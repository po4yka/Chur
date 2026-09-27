//! The FFI query-input parser over arbitrary fields.
//!
//! §6.4 of `FFI_CONTRACT.md` fixes the allocated scopes and sorts, and
//! `records::query_from` must reject an unallocated value with the stable
//! `INVALID_INPUT` status. An unallocated scope is refused before any other
//! validation runs. An unallocated sort is refused after the scope's own
//! checks, and the only one of those with another status is the search-terms
//! bound, which is `RESOURCE_LIMIT_EXCEEDED`.

#![no_main]

use libfuzzer_sys::fuzz_target;

use chur_core::ChurStatus;
use chur_core::limits::catalog;

fuzz_target!(|data: &[u8]| {
    if data.len() > 65_536 || data.len() < 26 {
        return;
    }
    let scope = data[0];
    let sort = data[1];
    let kinds = u16::from_le_bytes([data[2], data[3]]);
    let limit = u32::from_le_bytes([data[4], data[5], data[6], data[7]]);
    let Ok(scope_id) = <[u8; 16]>::try_from(&data[8..24]) else {
        return;
    };
    let cursor_present = data[24] & 1 == 1;
    let terms_present = data[24] & 2 == 2;
    let (cursor_bytes, terms_bytes) = data[25..].split_at((data.len() - 25) / 2);
    let cursor = if cursor_present {
        Some(cursor_bytes)
    } else {
        None
    };
    let terms = if terms_present {
        Some(terms_bytes)
    } else {
        None
    };

    let outcome =
        chur_ffi::records::query_from(scope, sort, kinds, limit, &scope_id, cursor, terms);
    let status = outcome.err().map(|error| error.status());
    // Scopes 1..=7 (7 is trash) and sorts 0..=4 (0 and 1 are capture_desc,
    // 4 an album's manual order) are allocated.
    let oversized_search =
        scope == 5 && terms.is_some_and(|terms| terms.len() > catalog::SEARCH_TERMS_MAX);
    if scope == 0 || scope > 7 {
        assert_eq!(status, Some(ChurStatus::InvalidInput));
    } else if sort > 4 && !oversized_search {
        assert_eq!(status, Some(ChurStatus::InvalidInput));
    }
});
