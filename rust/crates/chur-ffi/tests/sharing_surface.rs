//! Sharing identity provisioning through the real C ABI.

#![allow(clippy::expect_used)]
#![expect(unsafe_code, reason = "the test drives the C ABI pointer contract")]

use chur_ffi::api::{chur_runtime_close, chur_runtime_open, chur_session_close, chur_vault_unlock};
use chur_ffi::records::{ChurRuntimeConfigV1, ChurUnlockRequestV1};
use chur_ffi::sharing::{
    chur_sharing_accept, chur_sharing_identity, chur_sharing_prepare, chur_sharing_prepare_device,
    chur_sharing_revoke,
};
use chur_format::codec::{Reader, Writer};
use chur_format::envelope::CollectionKeyEnvelope;
use chur_sync_protocol::{
    collection_membership::{CollectionMembershipAction, CollectionMembershipRecord},
    grant::{CollectionGrant, PermissionProfile},
    identity::fingerprint,
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

    let recipient_path = std::env::temp_dir().join(format!(
        "chur-ffi-sharing-recipient-{}",
        chur_crypto::random::id().expect("random id").to_hex()
    ));
    let recipient_root = chur_catalog::paths::VaultRoot::new(&recipient_path);
    drop(
        chur_catalog::vault::create(&recipient_root, PASSWORD, 1)
            .expect("create recipient")
            .activate()
            .expect("activate recipient"),
    );
    let recipient_path_bytes = recipient_path.to_str().expect("UTF-8 path").as_bytes();
    let recipient_config = ChurRuntimeConfigV1 {
        root_path: recipient_path_bytes.as_ptr(),
        root_path_length: recipient_path_bytes.len() as u32,
    };
    let mut recipient_runtime = 0;
    assert_eq!(
        unsafe { chur_runtime_open(&recipient_config, &mut recipient_runtime) },
        0
    );
    let mut recipient_session = 0;
    assert_eq!(
        unsafe { chur_vault_unlock(recipient_runtime, &unlock, &mut recipient_session) },
        0
    );
    let mut recipient_identity = vec![0u8; 4096];
    let mut recipient_identity_length = 0;
    assert_eq!(
        unsafe {
            chur_sharing_identity(
                recipient_session,
                recipient_identity.as_mut_ptr(),
                recipient_identity.len(),
                &mut recipient_identity_length,
            )
        },
        0
    );
    recipient_identity.truncate(recipient_identity_length);
    let mut recipient_reader = Reader::new(
        &recipient_identity,
        chur_core::ChurStatus::NonCanonicalEncoding,
    );
    assert_eq!(recipient_reader.u16().expect("version"), 1);
    let recipient_vault_id = recipient_reader.id().expect("recipient vault");
    let recipient_device_id = recipient_reader.id().expect("recipient device");
    recipient_reader.slice(64).expect("recipient public keys");
    recipient_reader
        .variable(49)
        .expect("recipient fingerprint");
    let recipient_enrollment = recipient_reader
        .variable(EnrollmentRecord::LEN as u32)
        .expect("recipient enrollment")
        .to_vec();
    let recipient_initial_operation = recipient_reader
        .variable(16_777_216)
        .expect("recipient initial operation")
        .to_vec();
    recipient_reader
        .finish()
        .expect("recipient identity record");
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

    let mut recipient_evidence = Writer::new();
    recipient_evidence.u16(1).u32(1);
    recipient_evidence
        .variable(&recipient_enrollment)
        .expect("recipient membership");
    recipient_evidence.u32(1);
    recipient_evidence
        .variable(&recipient_initial_operation)
        .expect("recipient operation");
    let recipient_evidence = recipient_evidence.finish();
    let mut device_share = vec![0u8; 4096];
    let mut device_share_written = 0;
    assert_eq!(
        unsafe {
            chur_sharing_prepare_device(
                session,
                collection_id.as_bytes().as_ptr(),
                recipient_evidence.as_ptr(),
                recipient_evidence.len() as u32,
                recipient_device_id.as_bytes().as_ptr(),
                PermissionProfile::Contribute as u8,
                1,
                device_share.as_mut_ptr(),
                device_share.len(),
                &mut device_share_written,
            )
        },
        0
    );
    assert_eq!(&device_share[..device_share_written], share.as_slice());

    let mut bundle = Writer::new();
    bundle.u16(1).u32(1).u32(1);
    bundle
        .variable(&enrollment.encode())
        .expect("issuer enrollment");
    bundle.u32(3);
    bundle
        .variable(&operation.encode())
        .expect("issuer operation");
    bundle
        .variable(&membership_operation.encode())
        .expect("membership operation");
    bundle
        .variable(&grant_operation.encode())
        .expect("grant operation");
    bundle.u32(1);
    bundle
        .variable(&membership.encode())
        .expect("membership record");
    bundle
        .variable(&membership_operation.encode())
        .expect("membership operation");
    bundle.variable(&grant.encode()).expect("grant");
    bundle
        .variable(&grant_operation.encode())
        .expect("grant operation");
    let bundle = bundle.finish();
    assert_eq!(
        unsafe { chur_sharing_accept(recipient_session, bundle.as_ptr(), 1) },
        chur_core::ChurStatus::NonCanonicalEncoding.as_i32()
    );
    assert_eq!(
        unsafe { chur_sharing_accept(recipient_session, bundle.as_ptr(), bundle.len() as u32,) },
        0
    );
    assert_eq!(
        unsafe { chur_sharing_accept(recipient_session, bundle.as_ptr(), bundle.len() as u32,) },
        0
    );

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

    let mut revoke_short = [0xa5];
    let mut revoke_written = usize::MAX;
    assert_eq!(
        unsafe {
            chur_sharing_revoke(
                session,
                collection_id.as_bytes().as_ptr(),
                recipient_vault_id.as_bytes().as_ptr(),
                recipient_device_id.as_bytes().as_ptr(),
                1_000,
                revoke_short.as_mut_ptr(),
                revoke_short.len(),
                &mut revoke_written,
            )
        },
        chur_core::ChurStatus::ResourceLimitExceeded.as_i32()
    );
    assert_eq!(revoke_written, 0);
    assert_eq!(revoke_short, [0xa5]);

    let mut revoke = vec![0u8; 16_777_216];
    assert_eq!(
        unsafe {
            chur_sharing_revoke(
                session,
                collection_id.as_bytes().as_ptr(),
                recipient_vault_id.as_bytes().as_ptr(),
                recipient_device_id.as_bytes().as_ptr(),
                1_000,
                revoke.as_mut_ptr(),
                revoke.len(),
                &mut revoke_written,
            )
        },
        0
    );
    revoke.truncate(revoke_written);
    let mut revoke_reader = Reader::new(&revoke, chur_core::ChurStatus::NonCanonicalEncoding);
    assert_eq!(revoke_reader.u16().expect("revoke version"), 1);
    let revoked_membership = CollectionMembershipRecord::decode(
        revoke_reader
            .variable(CollectionMembershipRecord::LEN as u32)
            .expect("revoked membership"),
    )
    .expect("valid revoked membership");
    assert!(revoked_membership.action() == CollectionMembershipAction::Revoke);
    Operation::decode(
        revoke_reader
            .variable(16_777_216)
            .expect("revoked membership operation"),
    )
    .expect("valid revoked membership operation");
    assert_eq!(revoke_reader.u32().expect("rotation operation count"), 1);
    Operation::decode(
        revoke_reader
            .variable(16_777_216)
            .expect("rotation operation"),
    )
    .expect("valid rotation operation");
    assert_eq!(revoke_reader.u32().expect("grant count"), 0);
    assert_eq!(revoke_reader.u8().expect("rotation complete"), 1);
    revoke_reader.finish().expect("complete revocation record");

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
    assert_eq!(unsafe { chur_session_close(recipient_session) }, 0);
    assert_eq!(unsafe { chur_runtime_close(recipient_runtime) }, 0);
    let mut received =
        chur_catalog::vault::unlock_with_password(&recipient_root, PASSWORD, 1).expect("unlock");
    assert_eq!(
        chur_catalog::store::collection(received.catalog().expect("catalog"), &collection_id)
            .expect("shared collection")
            .policy_type,
        chur_catalog::model::COLLECTION_POLICY_SHARED
    );
    let received_root = chur_crypto::Key::new(*received.root_secret().expose());
    let received_envelope = CollectionKeyEnvelope::decode(
        &chur_catalog::store::active_collection_envelope(
            received.catalog().expect("catalog"),
            &collection_id,
            1,
        )
        .expect("received envelope"),
    )
    .expect("valid envelope");
    assert_eq!(received_envelope.vault_id(), &recipient_vault_id);
    assert_eq!(
        received_envelope
            .open(&received_root)
            .expect("collection key")
            .expose(),
        collection_key.expose()
    );
    drop(received);
    std::fs::remove_dir_all(path).expect("cleanup");
    std::fs::remove_dir_all(recipient_path).expect("cleanup recipient");
}
