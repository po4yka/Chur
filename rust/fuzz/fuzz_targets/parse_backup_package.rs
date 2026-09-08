//! The portable backup package over arbitrary bytes.
//!
//! `BACKUP_FORMAT_V1.md` §2.3 rejects a file that is none of the three
//! framings before any further parsing, and every package record is a bounded
//! canonical decode (§13), so the target exercises the framing classifier and
//! every public package-record decoder with no key material at all.

#![no_main]

use libfuzzer_sys::fuzz_target;

use chur_core::ChurStatus;
use chur_format::backup::{
    BackupManifest, FinalBackupCommit, PublicPreamble, RecordHeader, StreamInventoryEntry,
    framing_of,
};

fuzz_target!(|data: &[u8]| {
    if data.len() > 65_536 {
        return;
    }
    if let Err(error) = framing_of(data) {
        assert_eq!(error.status(), ChurStatus::VaultCorrupt);
    }
    // The preamble, header, and inventory-entry decodes name exactly the
    // statuses their `# Errors` sections list; the manifest and final commit
    // are parsed for panic-freedom and bounded rejection.
    if let Err(error) = PublicPreamble::decode(data) {
        assert!(matches!(
            error.status(),
            ChurStatus::VaultCorrupt
                | ChurStatus::UnsupportedVersion
                | ChurStatus::UnsupportedSuite
                | ChurStatus::ResourceLimitExceeded
        ));
    }
    if let Err(error) = RecordHeader::decode(data) {
        assert_eq!(error.status(), ChurStatus::VaultCorrupt);
    }
    if let Err(error) = StreamInventoryEntry::decode(data) {
        assert_eq!(error.status(), ChurStatus::VaultCorrupt);
    }
    let _ = BackupManifest::decode(data);
    let _ = FinalBackupCommit::decode(data);
});
