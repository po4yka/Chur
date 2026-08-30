//! Phase 4 collection-sharing identity C ABI.

use chur_core::{ChurStatus, Error, Id, ensure};
use chur_crypto::Key;
use chur_format::codec::{Reader, Writer};
use chur_sync_protocol::{
    collection_membership::CollectionMembershipRecord,
    grant::{CollectionGrant, PermissionProfile},
    identity::fingerprint,
    membership::{EnrollmentRecord, RevocationRecord},
    operation::Operation,
};

use crate::api::{Status, borrow_bytes, borrow_bytes_mut, write_out};
use crate::panic::guard_status;
use crate::registry::{self, Entry, Handle, Kind};

const RECORD_VERSION_V1: u16 = 1;
const BUNDLE_BYTES_MAX: u32 = 16_777_216;
const BUNDLE_RECORDS_MAX: usize = 4_096;
const ISSUERS_MAX: usize = 257;

struct DecodedIssuer {
    membership: Vec<chur_catalog::sharing_service::IssuerMembershipRecord>,
    operations: Vec<Operation>,
}

fn permission_profile(value: u8) -> Result<PermissionProfile, Error> {
    match value {
        0x01 => Ok(PermissionProfile::Read),
        0x03 => Ok(PermissionProfile::Contribute),
        0x07 => Ok(PermissionProfile::ManageMembers),
        _ => Err(Error::new(
            ChurStatus::UnsupportedVersion,
            "sharing permission profile is not supported",
        )),
    }
}

/// Idempotently provisions or returns the ordinary local sharing identity.
///
/// # Safety
///
/// `destination` covers `capacity` writable bytes and `bytes_written` points
/// to one writable, aligned `size_t` for this call.
#[unsafe(no_mangle)]
#[expect(
    unsafe_code,
    reason = "FFI_CONTRACT.md section 6.9 fixes this exported symbol"
)]
pub unsafe extern "C" fn chur_sharing_identity(
    session: Handle,
    destination: *mut u8,
    capacity: usize,
    bytes_written: *mut usize,
) -> Status {
    guard_status(|| {
        // SAFETY: the caller guarantees the writable out-parameter above.
        let _ = unsafe { write_out(bytes_written, 0usize) };
        let entry = registry::get(session, Kind::Session)?;
        let Entry::Session { session, .. } = entry.as_ref() else {
            return Err(Error::new(
                ChurStatus::InvalidInput,
                "the handle is of another type",
            ));
        };
        let encoded = {
            let mut session = registry::lock(session);
            let vault_id = session.vault_id();
            let root = Key::new(*session.root_secret().expose());
            let (enrollment, operation) = chur_catalog::sync_receive::provision_local_identity(
                session.catalog()?,
                &root,
                vault_id,
            )?;
            let display = fingerprint(
                enrollment.vault_id(),
                enrollment.device_id(),
                enrollment.signing_public_key(),
                enrollment.hpke_public_key(),
            );
            let mut writer = Writer::new();
            writer
                .u16(RECORD_VERSION_V1)
                .id(enrollment.vault_id())
                .id(enrollment.device_id())
                .fixed(enrollment.signing_public_key())
                .fixed(enrollment.hpke_public_key());
            writer.variable(display.as_bytes())?;
            writer.variable(&enrollment.encode())?;
            writer.variable(&operation.encode())?;
            writer.finish()
        };
        // SAFETY: the caller guarantees the destination range above.
        let buffer = unsafe { borrow_bytes_mut(destination, capacity)? };
        ensure!(
            encoded.len() <= buffer.len(),
            ResourceLimitExceeded,
            "the destination buffer is smaller than the sharing identity record"
        );
        buffer[..encoded.len()].copy_from_slice(&encoded);
        // SAFETY: the caller guarantees the writable out-parameter above.
        unsafe { write_out(bytes_written, encoded.len()) }
    })
}

/// Prepares one recipient membership and HPKE collection-key grant.
///
/// # Safety
///
/// Fixed and variable input pointers cover their declared readable ranges.
/// `destination` covers `capacity` writable bytes and `bytes_written` points
/// to one writable, aligned `size_t` for this call.
#[unsafe(no_mangle)]
#[expect(
    unsafe_code,
    reason = "FFI_CONTRACT.md section 6.10 fixes this exported symbol"
)]
pub unsafe extern "C" fn chur_sharing_prepare(
    session: Handle,
    collection_id: *const u8,
    recipient_enrollment: *const u8,
    recipient_enrollment_length: u32,
    permissions: u8,
    fingerprint_verified: u8,
    destination: *mut u8,
    capacity: usize,
    bytes_written: *mut usize,
) -> Status {
    guard_status(|| {
        // SAFETY: the caller guarantees the writable out-parameter above.
        let _ = unsafe { write_out(bytes_written, 0usize) };
        // SAFETY: the caller guarantees the fixed input range above.
        let collection_id = Id::from_slice(unsafe { borrow_bytes(collection_id, 16)? })?;
        // SAFETY: the caller guarantees the variable input range above.
        let enrollment = EnrollmentRecord::decode(unsafe {
            borrow_bytes(recipient_enrollment, recipient_enrollment_length)?
        })?;
        let permissions = permission_profile(permissions)?;
        let fingerprint_verified = match fingerprint_verified {
            0 => false,
            1 => true,
            _ => {
                return Err(Error::new(
                    ChurStatus::InvalidInput,
                    "fingerprint verification must be zero or one",
                ));
            }
        };
        let entry = registry::get(session, Kind::Session)?;
        let Entry::Session { session, .. } = entry.as_ref() else {
            return Err(Error::new(
                ChurStatus::InvalidInput,
                "the handle is of another type",
            ));
        };
        let encoded = {
            let mut session = registry::lock(session);
            let source_vault_id = session.vault_id();
            let root = Key::new(*session.root_secret().expose());
            let prepared = chur_catalog::sharing_service::prepare_share(
                session.catalog()?,
                &root,
                source_vault_id,
                collection_id,
                &enrollment,
                permissions,
                fingerprint_verified,
            )?;
            encode_prepared_share(&prepared)?
        };
        // SAFETY: the caller guarantees the destination range above.
        let buffer = unsafe { borrow_bytes_mut(destination, capacity)? };
        ensure!(
            encoded.len() <= buffer.len(),
            ResourceLimitExceeded,
            "the destination buffer is smaller than the prepared share record"
        );
        buffer[..encoded.len()].copy_from_slice(&encoded);
        // SAFETY: the caller guarantees the writable out-parameter above.
        unsafe { write_out(bytes_written, encoded.len()) }
    })
}

/// Prepares a grant for one device in an authenticated recipient vault.
///
/// # Safety
///
/// Fixed and variable input pointers cover their declared readable ranges.
/// `destination` covers `capacity` writable bytes and `bytes_written` points
/// to one writable, aligned `size_t` for this call.
#[unsafe(no_mangle)]
#[expect(
    unsafe_code,
    reason = "FFI_CONTRACT.md section 6.13 fixes this exported symbol"
)]
pub unsafe extern "C" fn chur_sharing_prepare_device(
    session: Handle,
    collection_id: *const u8,
    recipient_evidence: *const u8,
    recipient_evidence_length: u32,
    recipient_device_id: *const u8,
    permissions: u8,
    fingerprint_verified: u8,
    destination: *mut u8,
    capacity: usize,
    bytes_written: *mut usize,
) -> Status {
    guard_status(|| {
        // SAFETY: the caller guarantees the writable out-parameter above.
        let _ = unsafe { write_out(bytes_written, 0usize) };
        ensure!(
            recipient_evidence_length <= BUNDLE_BYTES_MAX,
            ResourceLimitExceeded,
            "recipient evidence exceeds the ABI limit"
        );
        // SAFETY: the caller guarantees the fixed input range above.
        let collection_id = Id::from_slice(unsafe { borrow_bytes(collection_id, 16)? })?;
        // SAFETY: the caller guarantees the fixed input range above.
        let recipient_device_id =
            Id::from_slice(unsafe { borrow_bytes(recipient_device_id, 16)? })?;
        // SAFETY: the caller guarantees the variable input range above.
        let evidence = decode_recipient_evidence(unsafe {
            borrow_bytes(recipient_evidence, recipient_evidence_length)?
        })?;
        let permissions = permission_profile(permissions)?;
        let fingerprint_verified = match fingerprint_verified {
            0 => false,
            1 => true,
            _ => {
                return Err(Error::new(
                    ChurStatus::InvalidInput,
                    "fingerprint verification must be zero or one",
                ));
            }
        };
        let entry = registry::get(session, Kind::Session)?;
        let Entry::Session { session, .. } = entry.as_ref() else {
            return Err(Error::new(
                ChurStatus::InvalidInput,
                "the handle is of another type",
            ));
        };
        let encoded = {
            let mut session = registry::lock(session);
            let source_vault_id = session.vault_id();
            let root = Key::new(*session.root_secret().expose());
            let prepared = chur_catalog::sharing_service::prepare_share_for_device(
                session.catalog()?,
                &root,
                source_vault_id,
                collection_id,
                chur_catalog::sharing_service::IssuerEvidence {
                    membership: &evidence.membership,
                    operations: &evidence.operations,
                },
                recipient_device_id,
                permissions,
                fingerprint_verified,
            )?;
            encode_prepared_share(&prepared)?
        };
        // SAFETY: the caller guarantees the destination range above.
        let buffer = unsafe { borrow_bytes_mut(destination, capacity)? };
        ensure!(
            encoded.len() <= buffer.len(),
            ResourceLimitExceeded,
            "the destination buffer is smaller than the prepared share record"
        );
        buffer[..encoded.len()].copy_from_slice(&encoded);
        // SAFETY: the caller guarantees the writable out-parameter above.
        unsafe { write_out(bytes_written, encoded.len()) }
    })
}

/// Revokes one recipient and prepares the forward-only collection rotation.
///
/// # Safety
///
/// Each identifier points to 16 readable bytes. `destination` covers
/// `capacity` writable bytes and `bytes_written` points to one writable,
/// aligned `size_t` for this call.
#[unsafe(no_mangle)]
#[expect(
    unsafe_code,
    reason = "FFI_CONTRACT.md section 6.12 fixes this exported symbol"
)]
pub unsafe extern "C" fn chur_sharing_revoke(
    session: Handle,
    collection_id: *const u8,
    recipient_vault_id: *const u8,
    recipient_device_id: *const u8,
    accepted_at_ms: u64,
    destination: *mut u8,
    capacity: usize,
    bytes_written: *mut usize,
) -> Status {
    guard_status(|| {
        // SAFETY: the caller guarantees the writable out-parameter above.
        unsafe { write_out(bytes_written, 0usize)? };
        ensure!(
            capacity >= BUNDLE_BYTES_MAX as usize,
            ResourceLimitExceeded,
            "the revocation destination must cover the ABI response bound"
        );
        // SAFETY: the caller guarantees the destination range above.
        let buffer = unsafe { borrow_bytes_mut(destination, capacity)? };
        // SAFETY: the caller guarantees the fixed input ranges above.
        let collection_id = Id::from_slice(unsafe { borrow_bytes(collection_id, 16)? })?;
        // SAFETY: the caller guarantees the fixed input ranges above.
        let recipient_vault_id = Id::from_slice(unsafe { borrow_bytes(recipient_vault_id, 16)? })?;
        // SAFETY: the caller guarantees the fixed input ranges above.
        let recipient_device_id =
            Id::from_slice(unsafe { borrow_bytes(recipient_device_id, 16)? })?;
        let entry = registry::get(session, Kind::Session)?;
        let Entry::Session { session, .. } = entry.as_ref() else {
            return Err(Error::new(
                ChurStatus::InvalidInput,
                "the handle is of another type",
            ));
        };
        let encoded = {
            let mut session = registry::lock(session);
            let source_vault_id = session.vault_id();
            let root = Key::new(*session.root_secret().expose());
            let prepared = chur_catalog::sharing_service::prepare_share_revocation(
                session.catalog()?,
                &root,
                source_vault_id,
                collection_id,
                recipient_vault_id,
                recipient_device_id,
                accepted_at_ms,
                BUNDLE_RECORDS_MAX,
            )?;
            ensure!(
                prepared.rotation_operations().len() <= BUNDLE_RECORDS_MAX
                    && prepared.grants().len() <= BUNDLE_RECORDS_MAX,
                ResourceLimitExceeded,
                "the prepared revocation exceeds the ABI record limit"
            );
            let rotation_count =
                u32::try_from(prepared.rotation_operations().len()).map_err(|_| {
                    Error::new(
                        ChurStatus::ResourceLimitExceeded,
                        "the rotation operation count exceeds u32",
                    )
                })?;
            let grant_count = u32::try_from(prepared.grants().len()).map_err(|_| {
                Error::new(
                    ChurStatus::ResourceLimitExceeded,
                    "the current grant count exceeds u32",
                )
            })?;
            let mut writer = Writer::new();
            writer.u16(RECORD_VERSION_V1);
            writer.variable(&prepared.membership().encode())?;
            writer.variable(&prepared.membership_operation().encode())?;
            writer.u32(rotation_count);
            for operation in prepared.rotation_operations() {
                writer.variable(&operation.encode())?;
            }
            writer.u32(grant_count);
            for (grant, operation) in prepared.grants() {
                writer.variable(&grant.encode())?;
                writer.variable(&operation.encode())?;
            }
            writer.u8(u8::from(prepared.rotation_complete()));
            writer.finish()
        };
        ensure!(
            encoded.len() <= BUNDLE_BYTES_MAX as usize,
            ResourceLimitExceeded,
            "the prepared revocation exceeds the ABI byte limit"
        );
        buffer[..encoded.len()].copy_from_slice(&encoded);
        // SAFETY: the caller guarantees the writable out-parameter above.
        unsafe { write_out(bytes_written, encoded.len()) }
    })
}

/// Authenticates and installs one recipient share bundle.
///
/// # Safety
///
/// `bundle` covers `bundle_length` readable bytes for this call.
#[unsafe(no_mangle)]
#[expect(
    unsafe_code,
    reason = "FFI_CONTRACT.md section 6.11 fixes this exported symbol"
)]
pub unsafe extern "C" fn chur_sharing_accept(
    session: Handle,
    bundle: *const u8,
    bundle_length: u32,
) -> Status {
    guard_status(|| {
        ensure!(
            bundle_length <= BUNDLE_BYTES_MAX,
            ResourceLimitExceeded,
            "the sharing acceptance bundle exceeds the ABI limit"
        );
        // SAFETY: the caller guarantees the readable input range above.
        let bytes = unsafe { borrow_bytes(bundle, bundle_length)? };
        let (issuers, membership, grant, grant_operation) = decode_accept_bundle(bytes)?;
        let evidence = issuers
            .iter()
            .map(|issuer| chur_catalog::sharing_service::IssuerEvidence {
                membership: &issuer.membership,
                operations: &issuer.operations,
            })
            .collect::<Vec<_>>();
        let entry = registry::get(session, Kind::Session)?;
        let Entry::Session { session, .. } = entry.as_ref() else {
            return Err(Error::new(
                ChurStatus::InvalidInput,
                "the handle is of another type",
            ));
        };
        let mut session = registry::lock(session);
        let root = Key::new(*session.root_secret().expose());
        chur_catalog::sharing_service::accept_share(
            session.catalog()?,
            &root,
            &evidence,
            &membership,
            &grant,
            &grant_operation,
        )?;
        Ok(())
    })
}

type DecodedAcceptBundle = (
    Vec<DecodedIssuer>,
    Vec<(CollectionMembershipRecord, Operation)>,
    CollectionGrant,
    Operation,
);

fn decode_accept_bundle(bytes: &[u8]) -> Result<DecodedAcceptBundle, Error> {
    let mut reader = Reader::new(bytes, ChurStatus::NonCanonicalEncoding);
    ensure!(
        reader.u16()? == RECORD_VERSION_V1,
        UnsupportedVersion,
        "sharing acceptance bundle version is not supported"
    );
    let issuer_count = bounded_count(&mut reader, ISSUERS_MAX, "sharing issuer count")?;
    let mut issuers = Vec::with_capacity(issuer_count);
    for _ in 0..issuer_count {
        issuers.push(decode_issuer(&mut reader)?);
    }
    let membership_count = bounded_count(
        &mut reader,
        BUNDLE_RECORDS_MAX,
        "collection membership count",
    )?;
    let mut membership = Vec::with_capacity(membership_count);
    for _ in 0..membership_count {
        let record = CollectionMembershipRecord::decode(
            reader.variable(CollectionMembershipRecord::LEN as u32)?,
        )?;
        let operation = Operation::decode(reader.variable(BUNDLE_BYTES_MAX)?)?;
        membership.push((record, operation));
    }
    let grant = CollectionGrant::decode(reader.variable(CollectionGrant::LEN as u32)?)?;
    let grant_operation = Operation::decode(reader.variable(BUNDLE_BYTES_MAX)?)?;
    reader.finish()?;
    Ok((issuers, membership, grant, grant_operation))
}

fn decode_recipient_evidence(bytes: &[u8]) -> Result<DecodedIssuer, Error> {
    let mut reader = Reader::new(bytes, ChurStatus::NonCanonicalEncoding);
    ensure!(
        reader.u16()? == RECORD_VERSION_V1,
        UnsupportedVersion,
        "recipient evidence version is not supported"
    );
    let evidence = decode_issuer(&mut reader)?;
    reader.finish()?;
    Ok(evidence)
}

fn decode_issuer(reader: &mut Reader<'_>) -> Result<DecodedIssuer, Error> {
    let membership_count = bounded_count(reader, BUNDLE_RECORDS_MAX, "issuer membership count")?;
    let mut membership = Vec::with_capacity(membership_count);
    for _ in 0..membership_count {
        let bytes = reader.variable(EnrollmentRecord::LEN as u32)?;
        let record = match bytes.len() {
            EnrollmentRecord::LEN => {
                chur_catalog::sharing_service::IssuerMembershipRecord::Enrollment(
                    EnrollmentRecord::decode(bytes)?,
                )
            }
            RevocationRecord::LEN => {
                chur_catalog::sharing_service::IssuerMembershipRecord::Revocation(
                    RevocationRecord::decode(bytes)?,
                )
            }
            _ => {
                return Err(Error::new(
                    ChurStatus::NonCanonicalEncoding,
                    "issuer membership record has another length",
                ));
            }
        };
        membership.push(record);
    }
    let operation_count = bounded_count(reader, BUNDLE_RECORDS_MAX, "issuer operation count")?;
    let mut operations = Vec::with_capacity(operation_count);
    for _ in 0..operation_count {
        operations.push(Operation::decode(reader.variable(BUNDLE_BYTES_MAX)?)?);
    }
    Ok(DecodedIssuer {
        membership,
        operations,
    })
}

fn encode_prepared_share(
    prepared: &chur_catalog::sharing_service::PreparedShare,
) -> Result<Vec<u8>, Error> {
    let mut writer = Writer::new();
    writer.u16(RECORD_VERSION_V1);
    writer.variable(&prepared.membership().encode())?;
    writer.variable(&prepared.membership_operation().encode())?;
    writer.variable(&prepared.grant().encode())?;
    writer.variable(&prepared.grant_operation().encode())?;
    Ok(writer.finish())
}

fn bounded_count(
    reader: &mut Reader<'_>,
    maximum: usize,
    context: &'static str,
) -> Result<usize, Error> {
    let count = usize::try_from(reader.u32()?).map_err(|_| {
        Error::new(
            ChurStatus::ResourceLimitExceeded,
            "sharing record count exceeds the address space",
        )
    })?;
    if count > maximum {
        return Err(Error::new(ChurStatus::ResourceLimitExceeded, context));
    }
    Ok(count)
}
