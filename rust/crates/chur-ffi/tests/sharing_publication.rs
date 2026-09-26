//! Source publication of objects imported before sharing was provisioned.

#![allow(clippy::expect_used)]
#![expect(unsafe_code, reason = "the test drives the C ABI pointer contract")]

use chur_ffi::api::{chur_runtime_close, chur_runtime_open, chur_session_close, chur_vault_unlock};
use chur_ffi::records::{ChurRuntimeConfigV1, ChurUnlockRequestV1};
use chur_ffi::sharing_publish::{
    chur_sharing_author, chur_sharing_object_read, chur_sharing_publication,
};
use chur_format::codec::Reader;
use chur_format::constants::MediaClass;
use chur_media::import::{CanonicalMedia, SourceCapability, import_bytes};
use chur_sync_protocol::collection_operation::CollectionOperation;
use sha2::{Digest, Sha256};

const PASSWORD: &[u8] = b"correct horse battery staple";

#[test]
fn preexisting_object_publication_is_complete_and_stable_after_reopen() {
    let path = std::env::temp_dir().join(format!(
        "chur-source-publication-{}",
        chur_crypto::random::id().expect("id").to_hex()
    ));
    let root = chur_catalog::paths::VaultRoot::new(&path);
    let mut direct = chur_catalog::vault::create(&root, PASSWORD, 1)
        .expect("create")
        .activate()
        .expect("activate");
    let plaintext = zeroize::Zeroizing::new(vec![7; 4_096]);
    let object_id = import_bytes(
        &mut direct,
        SourceCapability {
            seekable: true,
            known_length: Some(4_096),
            content_type_hint: String::from("image/jpeg"),
            original_filename: Some(String::from("photo.jpg")),
            capture_time_ms: Some(1),
        },
        CanonicalMedia {
            media_class: MediaClass::Image,
            width: 10,
            height: 10,
            duration_ms: 0,
        },
        "image/jpeg",
        &plaintext,
        1,
    )
    .expect("import before sync/share");
    let object = chur_catalog::store::object(direct.catalog_ref().expect("catalog"), &object_id)
        .expect("object");
    let collection_id = object.collection_id;
    let stream = chur_catalog::store::streams(direct.catalog_ref().expect("catalog"), &object_id)
        .expect("streams")
        .into_iter()
        .find(|stream| stream.stream_kind == chur_format::constants::StreamKind::Original)
        .expect("original");
    let container_path = root.container(&direct.object_store_id(), &stream.container_path_id);
    let container = std::fs::read(&container_path).expect("ciphertext");
    let vault_id = direct.vault_id();
    let root_key = direct.root_secret().expect("root").duplicate();
    chur_catalog::sync_receive::provision_local_identity(
        direct.catalog().expect("catalog"),
        &root_key,
        vault_id,
    )
    .expect("sync identity");
    chur_catalog::sharing::provision(
        direct.catalog().expect("catalog"),
        vault_id,
        collection_id,
        1,
    )
    .expect("share state");
    direct.lock().expect("lock");

    let path_bytes = path.to_str().expect("UTF-8 path").as_bytes();
    let config = ChurRuntimeConfigV1 {
        root_path: path_bytes.as_ptr(),
        root_path_length: path_bytes.len() as u32,
    };
    let unlock = ChurUnlockRequestV1 {
        factor: 1,
        reserved: [0; 3],
        secret: PASSWORD.as_ptr(),
        secret_length: PASSWORD.len() as u32,
    };
    let mut runtime = 0;
    let mut session = 0;
    assert_eq!(unsafe { chur_runtime_open(&config, &mut runtime) }, 0);
    assert_eq!(
        unsafe { chur_vault_unlock(runtime, &unlock, &mut session) },
        0
    );

    let mut output = vec![0u8; 1_048_576];
    let mut written = 0;
    let zero = [0u8; 16];
    let publication = |session, output: &mut Vec<u8>| {
        let mut written = 0;
        assert_eq!(
            unsafe {
                chur_sharing_publication(
                    session,
                    collection_id.as_bytes().as_ptr(),
                    zero.as_ptr(),
                    output.as_mut_ptr(),
                    output.len(),
                    &mut written,
                )
            },
            0
        );
        output[..written].to_vec()
    };
    let first = publication(session, &mut output);
    let mut reader = Reader::new(&first, chur_core::ChurStatus::NonCanonicalEncoding);
    assert_eq!(reader.u16().expect("version"), 1);
    assert_eq!(reader.u32().expect("count"), 1);
    assert!(
        reader
            .variable(1_048_576)
            .expect("pending create")
            .is_empty()
    );
    assert!(
        reader
            .variable(1_048_576)
            .expect("pending commit")
            .is_empty()
    );
    assert_eq!(reader.id().expect("object"), object_id);
    assert_eq!(reader.id().expect("store"), stream.container_path_id);
    assert_eq!(reader.u64().expect("length"), container.len() as u64);
    let full_sha256: [u8; 32] = Sha256::digest(&container).into();
    assert_eq!(reader.fixed::<32>().expect("SHA-256"), full_sha256);
    reader.finish().expect("complete page");
    assert_eq!(first, publication(session, &mut output));

    let mut wrong_hash = full_sha256;
    wrong_hash[0] ^= 1;
    assert_ne!(
        unsafe {
            chur_sharing_author(
                session,
                collection_id.as_bytes().as_ptr(),
                object_id.as_bytes().as_ptr(),
                stream.container_path_id.as_bytes().as_ptr(),
                container.len() as u64,
                wrong_hash.as_ptr(),
                output.as_mut_ptr(),
                output.len(),
                &mut written,
            )
        },
        0
    );
    assert_eq!(written, 0);
    let author = |session, output: &mut Vec<u8>| {
        let mut written = 0;
        assert_eq!(
            unsafe {
                chur_sharing_author(
                    session,
                    collection_id.as_bytes().as_ptr(),
                    object_id.as_bytes().as_ptr(),
                    stream.container_path_id.as_bytes().as_ptr(),
                    container.len() as u64,
                    full_sha256.as_ptr(),
                    output.as_mut_ptr(),
                    output.len(),
                    &mut written,
                )
            },
            0
        );
        output[..written].to_vec()
    };
    let authored = author(session, &mut output);
    let mut signed = Reader::new(&authored, chur_core::ChurStatus::NonCanonicalEncoding);
    assert_eq!(signed.u16().expect("version"), 1);
    assert_eq!(signed.u32().expect("count"), 1);
    let create = CollectionOperation::decode(signed.variable(1_048_576).expect("create"))
        .expect("signed create");
    let commit = CollectionOperation::decode(signed.variable(1_048_576).expect("commit"))
        .expect("signed commit");
    assert_eq!(signed.id().expect("object"), object_id);
    assert_eq!(signed.id().expect("store"), stream.container_path_id);
    assert_eq!(signed.u64().expect("length"), container.len() as u64);
    assert_eq!(signed.fixed::<32>().expect("hash"), full_sha256);
    signed.finish().expect("complete authored record");
    assert_eq!(commit.device_sequence(), create.device_sequence() + 1);
    assert_eq!(authored, author(session, &mut output));

    let mut range = [0u8; 1_024];
    let mut range_hash = [0u8; 32];
    assert_eq!(
        unsafe {
            chur_sharing_object_read(
                session,
                object_id.as_bytes().as_ptr(),
                0,
                1_024,
                range.as_mut_ptr(),
                range.len(),
                &mut written,
                range_hash.as_mut_ptr(),
            )
        },
        0
    );
    assert_eq!(written, 1_024);
    assert_eq!(&range, &container[..1_024]);
    let expected_range_hash: [u8; 32] = Sha256::digest(range).into();
    assert_eq!(range_hash, expected_range_hash);
    assert_eq!(
        unsafe {
            chur_sharing_object_read(
                session,
                object_id.as_bytes().as_ptr(),
                0,
                1_048_577,
                range.as_mut_ptr(),
                range.len(),
                &mut written,
                range_hash.as_mut_ptr(),
            )
        },
        chur_core::ChurStatus::InvalidInput.as_i32()
    );
    assert_eq!(written, 0);

    assert_eq!(unsafe { chur_session_close(session) }, 0);
    assert_eq!(unsafe { chur_runtime_close(runtime) }, 0);
    runtime = 0;
    session = 0;
    assert_eq!(unsafe { chur_runtime_open(&config, &mut runtime) }, 0);
    assert_eq!(
        unsafe { chur_vault_unlock(runtime, &unlock, &mut session) },
        0
    );
    assert_eq!(first, publication(session, &mut output));
    assert_eq!(authored, author(session, &mut output));
    let mut after = [0u8; 16];
    after.copy_from_slice(object_id.as_bytes());
    assert_eq!(
        unsafe {
            chur_sharing_publication(
                session,
                collection_id.as_bytes().as_ptr(),
                after.as_ptr(),
                output.as_mut_ptr(),
                output.len(),
                &mut written,
            )
        },
        0
    );
    let mut empty = Reader::new(
        &output[..written],
        chur_core::ChurStatus::NonCanonicalEncoding,
    );
    assert_eq!(empty.u16().expect("version"), 1);
    assert_eq!(empty.u32().expect("count"), 0);
    empty.finish().expect("empty page");

    let mut damaged = container.clone();
    *damaged.last_mut().expect("final commit byte") ^= 1;
    std::fs::write(&container_path, damaged).expect("damage ciphertext");
    assert_ne!(
        unsafe {
            chur_sharing_publication(
                session,
                collection_id.as_bytes().as_ptr(),
                zero.as_ptr(),
                output.as_mut_ptr(),
                output.len(),
                &mut written,
            )
        },
        0
    );
    assert_eq!(written, 0);
    assert_ne!(
        unsafe {
            chur_sharing_object_read(
                session,
                object_id.as_bytes().as_ptr(),
                0,
                1_024,
                range.as_mut_ptr(),
                range.len(),
                &mut written,
                range_hash.as_mut_ptr(),
            )
        },
        0
    );
    assert_eq!(written, 0);
    assert_eq!(unsafe { chur_session_close(session) }, 0);
    assert_eq!(unsafe { chur_runtime_close(runtime) }, 0);
}
