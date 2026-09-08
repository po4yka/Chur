//! The signed device operation over arbitrary bytes.
//!
//! `OPERATION_LOG.md` §2 fixes the cleartext record and §4 the obligations a
//! receiver enforces before a record is accepted; `Operation::decode` is the
//! first of those steps and the one every attacker-reachable path crosses.
//! The target is parsed for panic-freedom and bounded rejection: the decode
//! declares its statuses in its `# Errors` section, and the fuzz harness
//! asserts none of them is a panic rather than restating the list.

#![no_main]

use libfuzzer_sys::fuzz_target;

use chur_sync_protocol::operation::Operation;

fuzz_target!(|data: &[u8]| {
    if data.len() > 65_536 {
        return;
    }
    let _ = Operation::decode(data);
});
