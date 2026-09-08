//! The collection grant record over arbitrary bytes.
//!
//! `COLLECTION_GRANTS.md` §2 fixes the canonical grant record a recipient
//! device parses before any of §9's acceptance rules run, so the decode is
//! the grant's attacker-reachable front door. The target is parsed for
//! panic-freedom and bounded rejection, as every target of this workspace
//! whose subject names its statuses in a `# Errors` section.

#![no_main]

use libfuzzer_sys::fuzz_target;

use chur_sync_protocol::grant::CollectionGrant;

fuzz_target!(|data: &[u8]| {
    if data.len() > 65_536 {
        return;
    }
    let _ = CollectionGrant::decode(data);
});
