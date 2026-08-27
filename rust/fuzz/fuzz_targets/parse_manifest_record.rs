//! `CanonicalManifest` over arbitrary bytes.

#![no_main]

use libfuzzer_sys::fuzz_target;

use chur_format::container::CanonicalManifest;

fuzz_target!(|data: &[u8]| {
    if data.len() > 256 {
        return;
    }
    if let Ok(manifest) = CanonicalManifest::decode(data) {
        // Decode then encode is identity for accepted bytes.
        assert_eq!(manifest.encode(), data);
        assert!(matches!(manifest.len(), 85 | 89));
        assert_eq!(
            manifest.source_content_revision().is_some(),
            manifest.stream_kind().is_derived()
        );
        let chunk_size = manifest.chunk_size();
        assert!((65_536..=8_388_608).contains(&chunk_size));
        assert_eq!(chunk_size % 4096, 0);
    }
});
