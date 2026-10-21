//! Bounded foreground direct peer transport and signed history export.
use crate::api::{Status, borrow_bytes_mut, borrow_large, write_out};
use crate::panic::guard_status_for;
use crate::registry::{self, Entry, Handle, Kind};
use chur_core::{ChurStatus, Error, Result, ensure};
use chur_crypto::Key;
use chur_format::codec::{Reader, Writer};
use chur_media::peer_transport::{FRAME_MAX, Peer};

/// Operates one ephemeral connection owned by an unlocked vault session.
/// Commands: listen=1, connect=2, poll=3, send=4, receive=5, close=6, flush=7,
/// and signed device history=8. Nothing is persisted by the transport.
/// # Safety
/// Input/output cover their declared non-overlapping ranges; written is writable and aligned.
#[unsafe(no_mangle)]
#[expect(unsafe_code, reason = "the peer C ABI borrows checked caller buffers")]
pub unsafe extern "C" fn chur_peer_call(
    owner: Handle,
    input: *const u8,
    length: u32,
    output: *mut u8,
    capacity: usize,
    written: *mut usize,
) -> Status {
    guard_status_for(owner, || {
        ensure!(
            length as usize <= FRAME_MAX + 256,
            ResourceLimitExceeded,
            "peer input exceeds limit"
        );
        // SAFETY: buffers cover their declared lengths, validated before access.
        let input = unsafe { borrow_large(input, length)? };
        ensure!(
            capacity
                >= match input.first() {
                    Some(1) => 34,
                    Some(3 | 7) => 1,
                    Some(5) => FRAME_MAX + 1,
                    Some(8) => FRAME_MAX / 8,
                    Some(10) => 8192,
                    _ => 0,
                },
            ResourceLimitExceeded,
            "peer output buffer is too small"
        );
        let destination = unsafe { borrow_bytes_mut(output, capacity)? };
        let entry = registry::get(owner, Kind::Session)?;
        let Entry::Session { session, peer, .. } = entry.as_ref() else {
            return Err(Error::new(
                ChurStatus::InvalidInput,
                "peer owner is not a session",
            ));
        };
        let mut guard = registry::lock(session);
        guard.root_secret()?;
        let result = dispatch(&mut guard, &mut registry::lock(peer), input);
        if result.is_err() {
            registry::lock(peer).take();
        }
        let bytes = zeroize::Zeroizing::new(result?);
        ensure!(
            bytes.len() <= destination.len(),
            ResourceLimitExceeded,
            "peer response exceeds limit"
        );
        destination[..bytes.len()].copy_from_slice(&bytes);
        // SAFETY: written points to the caller's writable out-parameter.
        unsafe { write_out(written, bytes.len()) }
    })
}
fn dispatch(
    session: &mut chur_catalog::vault::Session,
    state: &mut Option<Box<Peer>>,
    input: &[u8],
) -> Result<Vec<u8>> {
    let mut reader = Reader::new(input, ChurStatus::InvalidInput);
    let command = reader.u8()?;
    let mut writer = Writer::new();
    match command {
        1 => {
            // The literal interface address is required; the listener never binds all interfaces.
            let address = reader.string(128)?.parse().map_err(|_| {
                Error::new(ChurStatus::InvalidInput, "enter this device's IP address")
            })?;
            reader.finish()?;
            ensure!(
                state.is_none(),
                Conflict,
                "a peer invitation already exists"
            );
            let (peer, port, secret) = Peer::listen(address)?;
            writer.u16(port).fixed(secret.expose());
            *state = Some(Box::new(peer));
        }
        2 => {
            let address = reader
                .string(128)?
                .parse()
                .map_err(|_| Error::new(ChurStatus::InvalidInput, "enter a device IP address"))?;
            let port = reader.u16()?;
            let secret = Key::new(reader.fixed()?);
            reader.finish()?;
            ensure!(state.is_none(), Conflict, "a peer session already exists");
            *state = Some(Box::new(Peer::connect(address, port, secret)?));
        }
        3 | 5 | 7 => {
            reader.finish()?;
            let peer = state
                .as_mut()
                .ok_or_else(|| Error::new(ChurStatus::NotFound, "no peer connection"))?;
            if command == 3 {
                writer.u8(u8::from(peer.poll()?));
            }
            if command == 7 {
                writer.u8(u8::from(peer.flush()?));
            }
            if command == 5 {
                match peer.receive()? {
                    None => {
                        writer.u8(0);
                    }
                    Some(bytes) => {
                        writer.u8(1).fixed(&bytes);
                    }
                }
            }
        }
        4 => {
            let bytes = reader.variable(FRAME_MAX as u32)?;
            reader.finish()?;
            state
                .as_mut()
                .ok_or_else(|| Error::new(ChurStatus::NotFound, "no peer connection"))?
                .send(bytes)?;
        }
        6 => {
            reader.finish()?;
            *state = None;
        }
        8 => {
            let device = reader.id()?;
            let after = reader.u64()?;
            let until = reader.u64()?;
            reader.finish()?;
            ensure!(
                until >= after,
                InvalidInput,
                "peer history range is invalid"
            );
            let records =
                chur_catalog::sync_log::records_after(session.catalog_ref()?, &device, after)?;
            let mut size = 4;
            let mut page = Vec::new();
            for record in records {
                if page.len() == 256 {
                    break;
                }
                let sequence =
                    chur_sync_protocol::operation::Operation::decode(&record)?.device_sequence();
                if sequence > until {
                    break;
                }
                if size + 12 + record.len() > FRAME_MAX / 8 {
                    ensure!(
                        !page.is_empty(),
                        ResourceLimitExceeded,
                        "peer operation exceeds frame size"
                    );
                    break;
                }
                size += 12 + record.len();
                page.push((sequence, record));
            }
            writer.u32(page.len() as u32);
            for (sequence, record) in page {
                writer.u64(sequence).variable(&record)?;
            }
        }
        9 => {
            let device = reader.id()?;
            reader.finish()?;
            let membership = chur_catalog::sync_membership::load(session.catalog_ref()?)?
                .ok_or_else(|| {
                    Error::new(ChurStatus::AuthenticationFailed, "peer membership absent")
                })?;
            ensure!(
                membership.is_active(&device),
                AuthenticationFailed,
                "peer device is revoked or unknown"
            );
        }
        10 => {
            reader.finish()?;
            let root = session.root_secret()?.duplicate();
            let vault = session.vault_id();
            if let Some(credentials) =
                chur_catalog::device_pairing::resume(session.catalog()?, &root, vault)?
            {
                writer
                    .u8(1)
                    .fixed(&crate::device_pairing::credentials_record(
                        vault,
                        &credentials,
                    )?);
            } else {
                writer.u8(0);
            }
        }
        _ => return Err(Error::new(ChurStatus::InvalidInput, "unknown peer command")),
    }
    Ok(writer.finish())
}
