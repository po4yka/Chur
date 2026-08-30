//! Sharing identity provisioning through the real C ABI.

#![allow(clippy::expect_used)]
#![expect(unsafe_code, reason = "the test drives the C ABI pointer contract")]

use chur_ffi::api::{chur_runtime_close, chur_runtime_open, chur_session_close, chur_vault_unlock};
use chur_ffi::records::{ChurRuntimeConfigV1, ChurUnlockRequestV1};
use chur_ffi::sharing::{chur_sharing_identity, chur_sharing_prepare};
use chur_format::codec::Reader;
use chur_format::envelope::CollectionKeyEnvelope;
use chur_sync_protocol::{
    collection_membership::CollectionMembershipRecord,
    grant::{CollectionGrant, PermissionProfile},
    identity::{DeviceIdentity, fingerprint},
    membership::EnrollmentRecord,
    operation::Operation,
};

const PASSWORD: &[u8] = b"correct horse battery staple";

#[test]
fn identity_provisioning_is_private_atomic_and_idempotent() {
    let path = std::env::temp_dir().join(format!(
        "chur-ffi-sharing-{}",
        chur_crypto::random::id().expect("random id").to_hex()
    ));
    let root = chur_catalog::paths::VaultRoot::new(&path);
    drop(
        chur_catalog::vault::create(&root, PASSWORD, 1)
            .expect("create")
            .activate()
            .expect("activate"),
    );
    let mut direct = chur_catalog::vault::unlock_with_password(&root, PASSWORD, 1).expect("unlock");
    let source_vault_id = direct.vault_id();
    let root_key = chur_crypto::Key::new(*direct.root_secret().expose());
    let collection_id = chur_crypto::random::id().expect("collection");
    let collection_key = chur_crypto::Key::new([31; 32]);
    let envelope = CollectionKeyEnvelope::seal(
        &root_key,
        source_vault_id,
        collection_id,
        1,
        1,
        chur_crypto::Nonce::new([32; 24]),
        &collection_key,
    )
    .expect("collection envelope");
    chur_catalog::store::put_collection_with_envelope(
        direct.catalog().expect("catalog"),
        &chur_catalog::model::Collection {
            collection_id,
            current_epoch: 1,
            policy_type: chur_catalog::model::COLLECTION_POLICY_VAULT_DEFAULT,
            created_revision: 1,
            status: chur_catalog::model::COLLECTION_STATUS_ACTIVE,
        },
        1,
        &envelope.encode(),
    )
    .expect("collection");
    drop(direct);
    let path_bytes = path.to_str().expect("UTF-8 path").as_bytes();
    let config = ChurRuntimeConfigV1 {
        root_path: path_bytes.as_ptr(),
        root_path_length: path_bytes.len() as u32,
    };
    let mut runtime = 0;
    assert_eq!(unsafe { chur_runtime_open(&config, &mut runtime) }, 0);
    let unlock = ChurUnlockRequestV1 {
        factor: 1,
        reserved: [0; 3],
        secret: PASSWORD.as_ptr(),
        secret_length: PASSWORD.len() as u32,
    };
    let mut session = 0;
    assert_eq!(
        unsafe { chur_vault_unlock(runtime, &unlock, &mut session) },
        0
    );

    let mut bytes = vec![0u8; 4096];
    let mut written = 0;
    assert_eq!(
        unsafe { chur_sharing_identity(session, bytes.as_mut_ptr(), bytes.len(), &mut written) },
        0
    );
    bytes.truncate(written);
    let mut reader = Reader::new(&bytes, chur_core::ChurStatus::NonCanonicalEncoding);
    assert_eq!(reader.u16().expect("version"), 1);
    let vault_id = reader.id().expect("vault");
    let device_id = reader.id().expect("device");
    let signing = reader.fixed::<32>().expect("signing key");
    let hpke = reader.fixed::<32>().expect("HPKE key");
    let display = reader.variable(49).expect("fingerprint");
    let enrollment = EnrollmentRecord::decode(reader.variable(270).expect("enrollment"))
        .expect("valid enrollment");
    let operation = Operation::decode(reader.variable(16_777_216).expect("operation"))
        .expect("valid operation");
    reader.finish().expect("complete record");
    assert_eq!(enrollment.vault_id(), &vault_id);
    assert_eq!(enrollment.device_id(), &device_id);
    assert_eq!(enrollment.signing_public_key(), &signing);
    assert_eq!(enrollment.hpke_public_key(), &hpke);
    assert_eq!(
        display,
        fingerprint(&vault_id, &device_id, &signing, &hpke).as_bytes()
    );
    assert_eq!(operation.device_id(), &device_id);
    assert_eq!(operation.device_sequence(), 1);

    let recipient_vault_id = chur_crypto::random::id().expect("recipient vault");
    let recipient_device_id = chur_crypto::random::id().expect("recipient device");
    let recipient = DeviceIdentity::from_seeds([41; 32], [42; 32]);
    let recipient_enrollment = EnrollmentRecord::initial(
        recipient_vault_id,
        recipient_device_id,
        recipient.signing_public_key(),
        recipient.hpke_public_key(),
    )
    .expect("recipient enrollment")
    .sign(recipient.signing_key())
    .encode();
    let mut short = [0xa5];
    written = usize::MAX;
    assert_eq!(
        unsafe {
            chur_sharing_prepare(
                session,
                collection_id.as_bytes().as_ptr(),
                recipient_enrollment.as_ptr(),
                recipient_enrollment.len() as u32,
                PermissionProfile::Contribute as u8,
                1,
                short.as_mut_ptr(),
                short.len(),
                &mut written,
            )
        },
        chur_core::ChurStatus::ResourceLimitExceeded.as_i32()
    );
    assert_eq!(written, 0);
    assert_eq!(short, [0xa5]);

    let mut share = vec![0u8; 4096];
    assert_eq!(
        unsafe {
            chur_sharing_prepare(
                session,
                collection_id.as_bytes().as_ptr(),
                recipient_enrollment.as_ptr(),
                recipient_enrollment.len() as u32,
                PermissionProfile::Contribute as u8,
                1,
                share.as_mut_ptr(),
                share.len(),
                &mut written,
            )
        },
        0
    );
    share.truncate(written);
    let mut reader = Reader::new(&share, chur_core::ChurStatus::NonCanonicalEncoding);
    assert_eq!(reader.u16().expect("share version"), 1);
    let membership = CollectionMembershipRecord::decode(
        reader
            .variable(CollectionMembershipRecord::LEN as u32)
            .expect("membership"),
    )
    .expect("valid membership");
    let membership_operation =
        Operation::decode(reader.variable(16_777_216).expect("membership operation"))
            .expect("valid membership operation");
    let grant =
        CollectionGrant::decode(reader.variable(CollectionGrant::LEN as u32).expect("grant"))
            .expect("valid grant");
    let grant_operation = Operation::decode(reader.variable(16_777_216).expect("grant operation"))
        .expect("valid grant operation");
    reader.finish().expect("complete share record");
    assert_eq!(membership.collection_id(), &collection_id);
    assert_eq!(
        membership.recipient_identity_vault_id(),
        &recipient_vault_id
    );
    assert_eq!(membership.recipient_device_id(), &recipient_device_id);
    assert_eq!(grant.collection_id(), &collection_id);
    assert_eq!(grant.recipient_identity_vault_id(), &recipient_vault_id);
    assert_eq!(grant.recipient_device_id(), &recipient_device_id);
    assert!(grant.permissions() == PermissionProfile::Contribute);
    assert!(membership_operation.device_sequence() < grant_operation.device_sequence());

    let mut share_replay = vec![0u8; 4096];
    let mut share_replay_written = 0;
    assert_eq!(
        unsafe {
            chur_sharing_prepare(
                session,
                collection_id.as_bytes().as_ptr(),
                recipient_enrollment.as_ptr(),
                recipient_enrollment.len() as u32,
                PermissionProfile::Contribute as u8,
                1,
                share_replay.as_mut_ptr(),
                share_replay.len(),
                &mut share_replay_written,
            )
        },
        0
    );
    assert_eq!(&share_replay[..share_replay_written], share.as_slice());

    let mut replay = vec![0u8; 4096];
    let mut replay_written = 0;
    assert_eq!(
        unsafe {
            chur_sharing_identity(
                session,
                replay.as_mut_ptr(),
                replay.len(),
                &mut replay_written,
            )
        },
        0
    );
    assert_eq!(&replay[..replay_written], bytes.as_slice());
    assert_eq!(unsafe { chur_session_close(session) }, 0);
    assert_eq!(unsafe { chur_runtime_close(runtime) }, 0);
    std::fs::remove_dir_all(path).expect("cleanup");
}
