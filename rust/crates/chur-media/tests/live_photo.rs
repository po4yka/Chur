//! Byte-exact Live Photo validation through real encrypted import and backup.
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

use std::io::Cursor;

use chur_catalog::paths::VaultRoot;
use chur_catalog::query::{ObjectQuery, page};
use chur_catalog::vault::{self, Session};
use chur_catalog::{journal, store};
use chur_core::{ChurStatus, Id};
use chur_crypto::{random, sha256::ResourceSha256};
use chur_format::constants::{MediaClass, StreamKind};
use chur_media::import::{CanonicalMedia, SourceCapability};
use chur_media::progress::{Progress, Uninterrupted};
use chur_media::{backup, export, import};
use zeroize::Zeroizing;

const MIME: &str = "application/vnd.chur.live-photo";
const PASSWORD: &[u8] = b"correct horse battery staple";
const NOW: u64 = 1_700_000_000_000;

#[test]
fn the_shared_format_vector_imports_and_exports_all_original_bytes() {
    let (_root, mut session) = new_vault();
    let mut bytes = include_bytes!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../../../tests/media/live-photo-v1/header.bin"
    ))
    .to_vec();
    bytes.extend_from_slice(include_bytes!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../../../tests/media/live-photo-v1/payload.bin"
    )));
    assert_eq!(bytes.len(), 358);
    let id = imported(&mut session, &bytes, image(), MIME).unwrap();
    assert_eq!(exported(&session, &id), bytes);
}

struct Resource {
    role: u32,
    bytes: Vec<u8>,
    name: String,
    uti: String,
    mime: String,
}

impl Resource {
    fn new(role: u32, bytes: Vec<u8>) -> Self {
        Self {
            role,
            bytes,
            name: String::from("фото.heic"),
            uti: String::from("public.heic"),
            mime: String::from("image/heic"),
        }
    }
}

struct Bundle {
    bytes: Vec<u8>,
    entries: Vec<usize>,
}

fn bundle(resources: &[Resource]) -> Bundle {
    let mut bytes = b"CHURLIVE".to_vec();
    bytes.extend_from_slice(&1u16.to_be_bytes());
    bytes.extend_from_slice(&(resources.len() as u16).to_be_bytes());
    bytes.extend_from_slice(&0u32.to_be_bytes());
    bytes.extend_from_slice(&(-1i64).to_be_bytes());
    let mut entries = Vec::new();
    for resource in resources {
        entries.push(bytes.len());
        bytes.extend_from_slice(&resource.role.to_be_bytes());
        bytes.extend_from_slice(&(resource.bytes.len() as u64).to_be_bytes());
        let mut sha = ResourceSha256::default();
        sha.update(&resource.bytes);
        bytes.extend_from_slice(&sha.finish());
        for text in [&resource.name, &resource.uti, &resource.mime] {
            bytes.extend_from_slice(&(text.len() as u16).to_be_bytes());
        }
        for text in [&resource.name, &resource.uti, &resource.mime] {
            bytes.extend_from_slice(text.as_bytes());
        }
    }
    let header_length = bytes.len() as u32;
    bytes[12..16].copy_from_slice(&header_length.to_be_bytes());
    for resource in resources {
        bytes.extend_from_slice(&resource.bytes);
    }
    Bundle { bytes, entries }
}

fn pair() -> Vec<Resource> {
    vec![
        Resource::new(1, vec![0xff, 0, 0x81, 17, 3]),
        Resource::new(9, vec![7; 262_144 + 3]),
    ]
}

fn scratch_root() -> VaultRoot {
    let path = std::env::temp_dir().join(format!("chur-live-{}", random::id().unwrap().to_hex()));
    std::fs::create_dir_all(&path).unwrap();
    VaultRoot::new(path)
}

fn new_vault() -> (VaultRoot, Session) {
    let root = scratch_root();
    let session = vault::create(&root, PASSWORD, NOW)
        .unwrap()
        .activate()
        .unwrap();
    (root, session)
}

fn source(length: usize) -> SourceCapability {
    SourceCapability {
        seekable: true,
        known_length: Some(length as u64),
        // The final content type, rather than this untrusted hint, controls validation.
        content_type_hint: String::from("image/jpeg"),
        original_filename: Some(String::from("capture.churlive")),
        capture_time_ms: None,
    }
}

fn image() -> CanonicalMedia {
    CanonicalMedia {
        media_class: MediaClass::Image,
        width: 2,
        height: 3,
        duration_ms: 0,
    }
}

fn imported(
    session: &mut Session,
    bytes: &[u8],
    media: CanonicalMedia,
    mime: &str,
) -> chur_core::Result<Id> {
    import::import_bytes(
        session,
        source(bytes.len()),
        media,
        mime,
        &Zeroizing::new(bytes.to_vec()),
        NOW,
    )
}

fn exported(session: &Session, id: &Id) -> Vec<u8> {
    let mut bytes = Vec::new();
    export::export_stream(
        session,
        id,
        StreamKind::Original,
        &mut bytes,
        &mut Uninterrupted,
    )
    .unwrap();
    bytes
}

fn assert_no_partial(root: &VaultRoot, session: &Session) {
    assert_eq!(
        page(session.catalog_ref().unwrap(), &ObjectQuery::timeline())
            .unwrap()
            .total_count,
        0
    );
    assert!(
        journal::live(session.catalog_ref().unwrap())
            .unwrap()
            .is_empty()
    );
    assert!(
        journal::dead(session.catalog_ref().unwrap())
            .unwrap()
            .is_empty()
    );
    assert_eq!(
        std::fs::read_dir(root.incoming(&session.object_store_id()))
            .unwrap()
            .count(),
        0
    );
}

#[test]
fn the_complete_bundle_with_unknown_and_edited_resources_round_trips_after_backup_and_lock() {
    let (_root, mut session) = new_vault();
    let mut resources = pair();
    resources.extend([
        Resource::new(5, vec![17, 18]),
        Resource::new(10, vec![91, 92]),
        Resource::new(2_147_483_647, vec![0, 0xff, 0, 0xfe]),
        Resource::new(2_147_483_647, Vec::new()),
    ]);
    let bytes = bundle(&resources).bytes;
    let id = imported(&mut session, &bytes, image(), MIME).unwrap();
    assert_eq!(exported(&session, &id), bytes);
    assert_eq!(
        store::active_metadata(session.catalog_ref().unwrap(), &id)
            .unwrap()
            .content_type,
        MIME
    );
    let mut package = Cursor::new(Vec::new());
    backup::create(&mut session, &mut package, NOW, &mut Uninterrupted).unwrap();
    session.lock().unwrap();
    let destination = scratch_root();
    backup::restore(&destination, &mut package, PASSWORD, &mut Uninterrupted).unwrap();
    let restored = vault::unlock_with_password(&destination, PASSWORD, NOW + 1).unwrap();
    assert_eq!(exported(&restored, &id), bytes);
}

#[test]
fn malformed_packages_never_activate_an_object_or_leave_partial_output() {
    let (root, mut session) = new_vault();
    let fixture = bundle(&pair());
    let first = fixture.entries[0];
    let second = fixture.entries[1];
    let mut cases: Vec<(&str, Vec<u8>)> = Vec::new();
    let mutations: &[(&str, usize, &[u8])] = &[
        ("magic", 0, b"X"),
        ("version", 8, &2u16.to_be_bytes()),
        ("capture time", 16, &(-2i64).to_be_bytes()),
        ("small count", 10, &1u16.to_be_bytes()),
        ("large count", 10, &33u16.to_be_bytes()),
        ("short header", 12, &23u32.to_be_bytes()),
        ("large header", 12, &65_537u32.to_be_bytes()),
        ("zero role", first, &0u32.to_be_bytes()),
        ("negative role", first, &0x8000_0000u32.to_be_bytes()),
        ("missing photo", first, &5u32.to_be_bytes()),
        ("duplicate photo", second, &1u32.to_be_bytes()),
        ("missing paired video", second, &10u32.to_be_bytes()),
        ("empty photo", first + 4, &0u64.to_be_bytes()),
        ("empty movie", second + 4, &0u64.to_be_bytes()),
        ("offset overflow", first + 4, &u64::MAX.to_be_bytes()),
        ("bundle bound", first + 4, &2_147_483_648u64.to_be_bytes()),
        ("empty name", first + 44, &0u16.to_be_bytes()),
        ("long name", first + 44, &1025u16.to_be_bytes()),
        ("empty UTI", first + 46, &0u16.to_be_bytes()),
        ("long UTI", first + 46, &256u16.to_be_bytes()),
        ("short MIME", first + 48, &2u16.to_be_bytes()),
        ("long MIME", first + 48, &64u16.to_be_bytes()),
        ("invalid UTF8", first + 50, &[0xff]),
        ("NUL", first + 50, &[0]),
    ];
    for (label, offset, replacement) in mutations {
        let mut bytes = fixture.bytes.clone();
        bytes[*offset..offset + replacement.len()].copy_from_slice(replacement);
        cases.push((label, bytes));
    }
    let mut bytes = fixture.bytes.clone();
    bytes[first + 12] ^= 1;
    cases.push(("digest", bytes));
    let mut bytes = fixture.bytes.clone();
    *bytes.last_mut().unwrap() ^= 1;
    cases.push(("payload digest", bytes));
    let mut bytes = fixture.bytes.clone();
    bytes.push(0);
    cases.push(("trailing payload", bytes));
    let mut bytes = fixture.bytes.clone();
    bytes.pop();
    cases.push(("truncated payload", bytes));
    cases.push(("truncated table", fixture.bytes[..second + 12].to_vec()));
    cases.push(("truncated fixed header", b"CHURLIVE".to_vec()));
    for mime in [
        "IMAGE/heic",
        "image/heic; charset=x",
        "image/",
        "image/фото",
    ] {
        let mut resources = pair();
        resources[0].mime = mime.to_owned();
        cases.push(("MIME grammar", bundle(&resources).bytes));
    }
    let mut resources = pair();
    resources[0].uti = String::from("public\0heic");
    cases.push(("UTI NUL", bundle(&resources).bytes));
    for (label, bytes) in cases {
        assert!(
            imported(&mut session, &bytes, image(), MIME).is_err(),
            "accepted {label}"
        );
        assert_no_partial(&root, &session);
    }
}

#[test]
fn a_malformed_or_oversized_table_stops_source_reads_after_one_chunk() {
    let (root, mut session) = new_vault();
    let fixture = bundle(&pair());
    for (offset, replacement, status) in [
        (10, 0u16.to_be_bytes().to_vec(), ChurStatus::InvalidInput),
        (
            fixture.entries[0] + 4,
            2_147_483_648u64.to_be_bytes().to_vec(),
            ChurStatus::ResourceLimitExceeded,
        ),
    ] {
        let mut bytes = fixture.bytes.clone();
        bytes[offset..offset + replacement.len()].copy_from_slice(&replacement);
        bytes.resize(4 * import::DEFAULT_CHUNK_SIZE as usize, 0);
        let running = import::begin(&mut session, source(bytes.len()), image(), NOW).unwrap();
        let mut input = Cursor::new(bytes);
        let error = import::stream_into(
            running,
            &mut session,
            &mut input,
            MIME,
            NOW,
            &mut Uninterrupted,
        )
        .unwrap_err();
        assert_eq!(error.status(), status);
        assert_eq!(input.position(), u64::from(import::DEFAULT_CHUNK_SIZE));
        assert!(input.position() < input.get_ref().len() as u64);
        assert_no_partial(&root, &session);
    }
}

#[test]
fn generic_image_and_opaque_imports_still_consume_their_complete_sources() {
    let (_root, mut session) = new_vault();
    for (media, mime, prefix) in [
        (image(), "image/jpeg", b"ordinary encoded image".as_slice()),
        (
            CanonicalMedia::opaque(),
            MIME,
            b"CHURLIVE broken file".as_slice(),
        ),
    ] {
        let mut bytes = vec![0x91; 2 * import::DEFAULT_CHUNK_SIZE as usize + 19];
        bytes[..prefix.len()].copy_from_slice(prefix);
        let running = import::begin(&mut session, source(bytes.len()), media, NOW).unwrap();
        let mut input = Cursor::new(&bytes);
        let id = import::stream_into(
            running,
            &mut session,
            &mut input,
            mime,
            NOW,
            &mut Uninterrupted,
        )
        .unwrap();
        assert_eq!(input.position(), bytes.len() as u64);
        assert_eq!(exported(&session, &id), bytes);
    }
}

#[test]
fn maximum_resource_count_preserves_repeated_unknown_roles_and_empty_optional_resources() {
    let (_root, mut session) = new_vault();
    let mut resources = pair();
    resources.extend((2..32).map(|_| Resource::new(77, Vec::new())));
    for resource in &mut resources {
        resource.name = "x".repeat(1024);
        resource.uti = "u".repeat(255);
        resource.mime = format!("image/{}", "a".repeat(57));
    }
    let bytes = bundle(&resources).bytes;
    let id = imported(&mut session, &bytes, image(), MIME).unwrap();
    assert_eq!(exported(&session, &id), bytes);
}

#[test]
fn an_opaque_file_keeps_arbitrary_bytes_even_with_the_live_photo_mime() {
    let (_root, mut session) = new_vault();
    for bytes in [
        b"CHURLIVE broken".as_slice(),
        b"arbitrary".as_slice(),
        b"".as_slice(),
    ] {
        let id = imported(&mut session, bytes, CanonicalMedia::opaque(), MIME).unwrap();
        assert_eq!(exported(&session, &id), bytes);
    }
}

#[test]
fn a_live_photo_image_cannot_be_stored_as_jpeg() {
    let (root, mut session) = new_vault();
    assert!(imported(&mut session, &bundle(&pair()).bytes, image(), "image/jpeg").is_err());
    assert_no_partial(&root, &session);
}

struct CancelAfterChunk(bool);
impl Progress for CancelAfterChunk {
    fn cancelled(&self) -> bool {
        self.0
    }
    fn advance(&mut self, _processed: u64) {
        self.0 = true;
    }
}

#[test]
fn cancelling_mid_resource_abandons_the_entire_live_photo() {
    let (root, mut session) = new_vault();
    let bytes = bundle(&pair()).bytes;
    let running = import::begin(&mut session, source(bytes.len()), image(), NOW).unwrap();
    let result = import::stream_into(
        running,
        &mut session,
        &mut Cursor::new(bytes),
        MIME,
        NOW,
        &mut CancelAfterChunk(false),
    );
    assert_eq!(result.unwrap_err().status(), ChurStatus::Cancelled);
    assert_no_partial(&root, &session);
}

#[test]
fn locking_during_a_resource_never_activates_a_partial_live_photo() {
    let (root, mut session) = new_vault();
    let bytes = bundle(&pair()).bytes;
    let mut running = import::begin(&mut session, source(bytes.len()), image(), NOW).unwrap();
    let chunk = running.chunk_size() as usize;
    running.write(&mut session, &bytes[..chunk]).unwrap();
    drop(running);
    session.lock().unwrap();
    let mut reopened = vault::unlock_with_password(&root, PASSWORD, NOW + 1).unwrap();
    assert_eq!(import::reconcile(&mut reopened, NOW + 1).unwrap(), 1);
    assert_no_partial(&root, &reopened);
}
