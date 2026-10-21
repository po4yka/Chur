//! JPEG XL original-only sync through real pairing, signed operations, grants, and activation.
#![allow(clippy::unwrap_used)]

use chur_catalog::{
    device_pairing, own_sync,
    paths::VaultRoot,
    sharing_publication, sync_engine, sync_log, sync_receive,
    sync_staging::LockedStaging,
    vault::{self, Session},
};
use chur_core::Id;
use chur_crypto::{Key, random};
use chur_format::constants::{MediaClass, StreamKind};
use chur_media::{
    backup, export,
    import::{self, CanonicalMedia, SourceCapability},
    jpeg_xl::Decoder,
    keys,
    progress::Uninterrupted,
    reader, store, sync_download,
};
use std::io::Cursor;
use zeroize::Zeroizing;

const PASSWORD: &[u8] = b"jpeg xl paired transport password";
const NOW: u64 = 1_700_000_000_000;

fn root() -> VaultRoot {
    let path = std::env::temp_dir().join(format!(
        "chur-jpeg-xl-sync-{}",
        random::id().unwrap().to_hex()
    ));
    std::fs::create_dir_all(&path).unwrap();
    VaultRoot::new(path)
}

fn relay(source: &Session, target: &mut Session, secret: &Key, device: &Id) {
    let vault = target.vault_id();
    for record in sync_log::records_after(source.catalog_ref().unwrap(), device, 0).unwrap() {
        sync_engine::stage_inbound(
            target.root_dir(),
            vault,
            sync_engine::StagedKind::Operation,
            NOW + 10,
            &record,
        )
        .unwrap();
    }
    let mut staging = LockedStaging::open(target.root_dir().sync_inbox(&vault)).unwrap();
    let report = sync_engine::process_staged(
        target.catalog().unwrap(),
        secret,
        vault,
        &mut staging,
        NOW + 10,
    )
    .unwrap();
    assert_eq!(report.rejected, 0);
    assert_eq!(report.pending, 0);
}

fn pair(source: &mut Session, secret: &Key, device: &Id) -> Session {
    let vault = source.vault_id();
    let mut package = Cursor::new(Vec::new());
    backup::create(source, &mut package, NOW, &mut Uninterrupted).unwrap();
    let target_root = root();
    backup::restore(&target_root, &mut package, PASSWORD, &mut Uninterrupted).unwrap();
    let mut target = vault::unlock_with_password(&target_root, PASSWORD, NOW + 1).unwrap();
    let request =
        device_pairing::prepare_request(target.catalog().unwrap(), secret, vault).unwrap();
    let approval =
        device_pairing::approve(source.catalog().unwrap(), secret, vault, &request, NOW + 2)
            .unwrap();
    device_pairing::accept_start(target.catalog().unwrap(), secret, vault, &approval.approval)
        .unwrap();
    relay(source, &mut target, secret, device);
    device_pairing::finalize(target.catalog().unwrap(), secret, vault, NOW + 4).unwrap();
    target
}

#[test]
fn paired_peers_preserve_jpeg_xl_original_metadata_and_decode_without_synced_derivatives() {
    let source_root = root();
    let mut source = vault::create(&source_root, PASSWORD, NOW)
        .unwrap()
        .activate()
        .unwrap();
    let collection = keys::ensure_default_collection(&mut source).unwrap();
    let secret = source.root_secret().unwrap().duplicate();
    let vault = source.vault_id();
    let (enrollment, _) =
        sync_receive::provision_local_identity(source.catalog().unwrap(), &secret, vault).unwrap();
    let device = *enrollment.device_id();
    // Pair from a baseline that has no media. Every fixture below must use peer transport.
    let mut target = pair(&mut source, &secret, &device);
    let fixtures: &[(&str, &[u8])] = &[
        (
            "still-codestream.jxl",
            include_bytes!("../../../../tests/media/jpeg-xl/still-codestream.jxl"),
        ),
        (
            "still-container.jxl",
            include_bytes!("../../../../tests/media/jpeg-xl/still-container.jxl"),
        ),
        (
            "alpha.jxl",
            include_bytes!("../../../../tests/media/jpeg-xl/alpha.jxl"),
        ),
        (
            "oriented.jxl",
            include_bytes!("../../../../tests/media/jpeg-xl/oriented.jxl"),
        ),
        (
            "animated.jxl",
            include_bytes!("../../../../tests/media/jpeg-xl/animated.jxl"),
        ),
    ];
    for (index, (name, bytes)) in fixtures.iter().enumerate() {
        let decoded = Decoder::open(bytes).unwrap();
        let dimensions = (decoded.width(), decoded.height());
        let object_id = import::import_bytes(
            &mut source,
            SourceCapability {
                requested_object_id: None,
                seekable: true,
                known_length: Some(bytes.len() as u64),
                content_type_hint: "image/jxl".into(),
                original_filename: Some((*name).into()),
                capture_time_ms: Some(NOW - 1_000),
            },
            CanonicalMedia {
                media_class: MediaClass::Image,
                width: dimensions.0,
                height: dimensions.1,
                duration_ms: 0,
            },
            "image/jxl",
            &Zeroizing::new(bytes.to_vec()),
            NOW + 5,
        )
        .unwrap();
        let candidate =
            sharing_publication::candidate(source.catalog_ref().unwrap(), &collection, &object_id)
                .unwrap();
        own_sync::publish(
            source.catalog().unwrap(),
            &secret,
            vault,
            collection,
            &[candidate],
        )
        .unwrap();
        relay(&source, &mut target, &secret, &device);
        let plan =
            own_sync::pending_for_collection(target.catalog().unwrap(), &secret, vault, collection)
                .unwrap();
        assert_eq!(plan.pending_operations, 0);
        assert_eq!(plan.objects.len(), 1);
        let pending = &plan.objects[0];
        assert_eq!(pending.object_id, object_id);
        assert!(
            !chur_catalog::store::object_present(target.catalog_ref().unwrap(), &object_id)
                .unwrap()
        );
        let request = chur_catalog::object_grants::request(
            target.catalog().unwrap(),
            &secret,
            vault,
            collection,
            object_id,
        )
        .unwrap();
        let grant =
            chur_catalog::object_grants::issue(source.catalog().unwrap(), &secret, vault, &request)
                .unwrap();
        chur_catalog::object_grants::accept(target.catalog().unwrap(), &secret, vault, &[], &grant)
            .unwrap();
        let ciphertext = store::read_container(
            source.root_dir(),
            &source.object_store_id(),
            &pending.store_id,
        )
        .unwrap();
        let expected = sync_download::Expectation::new(
            object_id,
            pending.stream_id,
            pending.container_length,
            pending.container_commitment,
        )
        .unwrap();
        if index == 0 {
            let mut corrupt = ciphertext.clone();
            let middle = corrupt.len() / 2;
            corrupt[middle] ^= 1;
            sync_download::append_range(
                target.root_dir(),
                &target.object_store_id(),
                &object_id,
                0,
                &corrupt,
                &expected,
            )
            .unwrap();
            assert!(sync_download::activate_shared(&mut target, pending, NOW + 11).is_err());
            assert!(
                !chur_catalog::store::object_present(target.catalog_ref().unwrap(), &object_id)
                    .unwrap()
            );
            let mut rejected_export = Vec::new();
            assert!(
                export::export_stream(
                    &target,
                    &object_id,
                    StreamKind::Original,
                    &mut rejected_export,
                    &mut Uninterrupted
                )
                .is_err()
            );
            assert!(rejected_export.is_empty());
        }
        // Offset zero replaces a failed staged transfer with the authentic container.
        sync_download::append_range(
            target.root_dir(),
            &target.object_store_id(),
            &object_id,
            0,
            &ciphertext,
            &expected,
        )
        .unwrap();
        sync_download::activate_shared(&mut target, pending, NOW + 12).unwrap();
        let object =
            chur_catalog::store::object(target.catalog_ref().unwrap(), &object_id).unwrap();
        assert_eq!(object.media_kind, MediaClass::Image);
        assert_eq!((object.width, object.height), dimensions);
        assert_eq!(object.capture_time_ms, NOW - 1_000);
        assert!(!object.thumbnail_ready);
        let metadata =
            chur_catalog::store::active_metadata(target.catalog_ref().unwrap(), &object_id)
                .unwrap();
        assert_eq!(metadata.content_type, "image/jxl");
        assert_eq!(metadata.original_filename.as_deref(), Some(*name));
        assert_eq!((metadata.width, metadata.height), dimensions);
        let streams =
            chur_catalog::store::streams(target.catalog_ref().unwrap(), &object_id).unwrap();
        assert_eq!(streams.len(), 1);
        assert_eq!(streams[0].stream_kind, StreamKind::Original);
        let original = reader::open(&target, &object_id, StreamKind::Original).unwrap();
        assert_eq!(original.dimensions(), dimensions);
        assert_eq!(original.content_info().unwrap().content_type, "image/jxl");
        let mut exported = Vec::new();
        export::export_stream(
            &target,
            &object_id,
            StreamKind::Original,
            &mut exported,
            &mut Uninterrupted,
        )
        .unwrap();
        assert_eq!(exported.as_slice(), *bytes);
        let received = Decoder::open(&exported).unwrap();
        assert_eq!((received.width(), received.height()), dimensions);
        assert_eq!(received.frame_count(), decoded.frame_count());
        assert_eq!(received.loop_count(), decoded.loop_count());
        for frame in 0..received.frame_count() {
            let mut pixels = Zeroizing::new(vec![0; (dimensions.0 * dimensions.1 * 4) as usize]);
            let mut source_pixels = Zeroizing::new(vec![0; pixels.len()]);
            assert_eq!(
                received.render(frame, 2048, &mut pixels).unwrap(),
                decoded.render(frame, 2048, &mut source_pixels).unwrap()
            );
            assert_eq!(pixels.as_slice(), source_pixels.as_slice());
        }
    }
}
