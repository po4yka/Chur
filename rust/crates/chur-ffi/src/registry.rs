//! The process-global handle registry.
//!
//! `docs/interop/FFI_CONTRACT.md` §3 fixes every property this module has to
//! provide, and each one is a defence rather than a convenience:
//!
//! - a handle is `uint64_t`, the low 32 bits a slot index and the high 32 bits
//!   that slot's generation. It is never a pointer, so a host cannot forge one
//!   into a dereference, and never a business identifier, so it discloses
//!   nothing;
//! - `0` is the null handle;
//! - a value is never reissued: the generation increments on every allocation,
//!   so a stale value cannot alias a live handle for the life of the process;
//! - a stale generation is `SESSION_EXPIRED`;
//! - close is idempotent for every handle type. Closing a value this process
//!   never issued is `INVALID_INPUT`, which the generation makes
//!   distinguishable from a re-close;
//! - the registry is bounded against leaks and denial of service.
//!
//! §8 adds one more: the registry lock is per slot and is never held across
//! user work, so a reader may be driven from a thread that did not create it
//! while another thread closes it. The registry lock here is therefore held
//! only long enough to clone an `Arc`, never across an operation.
//!
//! The slots are one table holding a typed entry rather than one table per
//! type. §3 calls a slot typed, and this is the form of that which makes
//! passing a session handle to a reader function impossible to confuse: the
//! entry names its own kind, so the mismatch is `INVALID_INPUT` rather than a
//! live handle of the wrong kind.

use std::sync::{Arc, Mutex, MutexGuard, OnceLock, PoisonError};

use chur_core::{ChurStatus, Error, Result};

/// The `chur_handle_t` of the C ABI.
pub type Handle = u64;

/// The null handle, §3.
pub const NULL_HANDLE: Handle = 0;

/// The largest number of live handles in one process.
///
/// §3 requires the registry to be bounded against leaks and denial of service.
/// The number is well above any real usage: the concurrent import bound of
/// `docs/format/CATALOG_SCHEMA_V1.md` §21 is 128, a session opens one runtime,
/// and a library screen holds one reader per visible row.
pub const MAX_HANDLES: usize = 4_096;

/// What a slot holds.
///
/// Each variant is behind its own `Mutex` because §8 serializes calls per
/// handle rather than per registry.
pub enum Entry {
    /// The one runtime of §14.
    Runtime(Mutex<crate::runtime::Runtime>),
    /// An unlocked vault session, and the runtime that opened it.
    Session {
        /// The runtime this session belongs to.
        runtime: Handle,
        /// The session.
        session: Mutex<chur_catalog::vault::Session>,
        /// Bounded public ciphertext verification results for this session only.
        publication_cache: Mutex<crate::sharing_publish::VerificationCache>,
    },
    /// A random-access reader, and the session that opened it.
    Reader {
        /// The session this reader belongs to.
        session: Handle,
        /// Physical vault scope, distinct from an independent restored copy.
        root: std::path::PathBuf,
        /// Physical encrypted object store.
        store_id: chur_core::Id,
        /// The object whose keys this reader retains.
        object_id: chur_core::Id,
        /// None after permanent erasure, including for an in-flight Arc.
        reader: Mutex<Option<chur_media::reader::ObjectReader>>,
    },
    /// A vault being created, `PROVISIONING.md` §3.
    ///
    /// The value is taken out on activation or abandonment, so a second call on
    /// the same handle is `SESSION_EXPIRED` rather than a second activation.
    Creation {
        /// The runtime this creation belongs to.
        runtime: Handle,
        /// The creation in progress.
        creation: Mutex<Option<chur_catalog::vault::VaultCreation>>,
    },
    /// A long-running import, export, integrity scan, backup, or restore.
    Operation {
        /// The handle this operation belongs to.
        ///
        /// It is a session for every operation that reads or writes vault
        /// content, and the runtime for a restore, which runs before any
        /// session exists. §4 invalidates a session's handles on lock and §14
        /// makes closing the runtime stronger still, so a restore is torn down
        /// by the second and not by the first.
        owner: Handle,
        /// The operation.
        operation: crate::operation::Operation,
    },
}

impl Entry {
    /// The handle this entry belongs to, when it belongs to one.
    ///
    /// §4 makes locking a session invalidate that session's handles, and §14
    /// makes closing the runtime a stronger event still. Neither may reach a
    /// handle of another owner: one process opens one vault, but a test process
    /// opens several, and an invalidation that ignored ownership would be
    /// correct only by accident.
    #[must_use]
    pub const fn owner(&self) -> Option<Handle> {
        match self {
            Entry::Runtime(_) => None,
            Entry::Session { runtime, .. } | Entry::Creation { runtime, .. } => Some(*runtime),
            Entry::Reader { session, .. } => Some(*session),
            Entry::Operation { owner, .. } => Some(*owner),
        }
    }
}

impl Entry {
    /// The name §3 uses for this handle type, for a mismatch diagnostic.
    const fn kind(&self) -> Kind {
        match self {
            Entry::Runtime(_) => Kind::Runtime,
            Entry::Session { .. } => Kind::Session,
            Entry::Creation { .. } => Kind::Creation,
            Entry::Reader { .. } => Kind::Reader,
            Entry::Operation { .. } => Kind::Operation,
        }
    }
}

/// The handle types of §3.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    /// `RuntimeHandle`.
    Runtime,
    /// `VaultSessionHandle`.
    Session,
    /// A vault creation in progress, §6.5.
    Creation,
    /// `ObjectReaderHandle`.
    Reader,
    /// `ImportHandle`, `ExportHandle`, or `IntegrityScanHandle`.
    Operation,
}

struct Slot {
    /// The generation of the value this slot last issued.
    ///
    /// It starts at 1 rather than 0 so that slot 0 issues no handle equal to
    /// [`NULL_HANDLE`], and it only increases.
    generation: u32,
    entry: Option<Arc<Entry>>,
    // Prevents a child prepared before teardown from publishing after its owner drains.
    closing: bool,
}

struct Table {
    slots: Vec<Slot>,
}

fn table() -> MutexGuard<'static, Table> {
    static TABLE: OnceLock<Mutex<Table>> = OnceLock::new();
    TABLE
        .get_or_init(|| Mutex::new(Table { slots: Vec::new() }))
        .lock()
        .unwrap_or_else(PoisonError::into_inner)
}

/// Splits a handle into its slot index and generation, §3.
///
/// The two halves are 32 bits each by definition, so the truncation is the
/// decoding rather than a loss.
fn split(handle: Handle) -> (usize, u32) {
    let index = u32::try_from(handle & 0xffff_ffff).unwrap_or(u32::MAX) as usize;
    let generation = u32::try_from(handle >> 32).unwrap_or(u32::MAX);
    (index, generation)
}

fn join(index: usize, generation: u32) -> Result<Handle> {
    let index = u32::try_from(index)
        .map_err(|_| Error::new(ChurStatus::InternalFailure, "the slot index exceeds a u32"))?;
    Ok((u64::from(generation) << 32) | u64::from(index))
}

/// Registers a value and returns its handle.
///
/// # Errors
///
/// Returns [`ChurStatus::ResourceLimitExceeded`] when the registry is full.
pub fn insert(entry: Entry) -> Result<Handle> {
    let mut table = table();
    if let Some(owner) = entry.owner() {
        let (index, generation) = split(owner);
        let live = table.slots.get(index).is_some_and(|slot| {
            slot.generation == generation && slot.entry.is_some() && !slot.closing
        });
        if !live {
            drop(table);
            return Err(Error::new(
                ChurStatus::SessionExpired,
                "the owning handle is closing",
            ));
        }
    }
    if let Some(index) = table.slots.iter().position(|slot| slot.entry.is_none()) {
        let slot = &mut table.slots[index];
        // The generation increments on every allocation, so the value this slot
        // issues has never been issued before.
        slot.generation = slot.generation.wrapping_add(1).max(1);
        slot.closing = false;
        slot.entry = Some(Arc::new(entry));
        return join(index, slot.generation);
    }
    if table.slots.len() >= MAX_HANDLES {
        return Err(Error::new(
            ChurStatus::ResourceLimitExceeded,
            "the handle registry is full",
        ));
    }
    table.slots.push(Slot {
        generation: 1,
        closing: false,
        entry: Some(Arc::new(entry)),
    });
    let index = table.slots.len() - 1;
    join(index, 1)
}

/// Looks up a handle of the expected kind.
///
/// The registry lock is released before the caller does anything with the
/// value, which §8 requires: a per-slot lock is never held across user work.
///
/// # Errors
///
/// Returns [`ChurStatus::InvalidInput`] for a null handle, a handle this
/// process never issued, or one of another kind, and
/// [`ChurStatus::SessionExpired`] for a handle whose slot has moved on.
pub fn get(handle: Handle, expected: Kind) -> Result<Arc<Entry>> {
    if handle == NULL_HANDLE {
        return Err(Error::new(
            ChurStatus::InvalidInput,
            "the null handle names nothing",
        ));
    }
    let (index, generation) = split(handle);
    let table = table();
    let Some(slot) = table.slots.get(index) else {
        return Err(Error::new(
            ChurStatus::InvalidInput,
            "the handle was never issued by this process",
        ));
    };
    if generation > slot.generation || generation == 0 {
        return Err(Error::new(
            ChurStatus::InvalidInput,
            "the handle was never issued by this process",
        ));
    }
    if generation < slot.generation {
        return Err(Error::new(
            ChurStatus::SessionExpired,
            "the handle belongs to an older generation of its slot",
        ));
    }
    if slot.closing && expected != Kind::Session {
        return Err(Error::new(
            ChurStatus::SessionExpired,
            "the handle is closing",
        ));
    }
    let Some(entry) = slot.entry.as_ref() else {
        return Err(Error::new(
            ChurStatus::SessionExpired,
            "the handle was closed",
        ));
    };
    if entry.kind() != expected {
        return Err(Error::new(
            ChurStatus::InvalidInput,
            "the handle is of another type",
        ));
    }
    Ok(Arc::clone(entry))
}

/// Releases a handle, §3.
///
/// Close is idempotent without exception: the first close releases the
/// resources and every later close of the same value returns success and does
/// nothing. It never returns `NOT_FOUND` or `SESSION_EXPIRED`.
///
/// # Errors
///
/// Returns [`ChurStatus::InvalidInput`] for the null handle or one this process
/// never issued.
pub fn close(handle: Handle) -> Result<Option<Arc<Entry>>> {
    let _lifecycle = reader_lifecycle();
    if handle == NULL_HANDLE {
        return Err(Error::new(
            ChurStatus::InvalidInput,
            "the null handle names nothing",
        ));
    }
    let (index, generation) = split(handle);
    let mut table = table();
    let Some(slot) = table.slots.get_mut(index) else {
        return Err(Error::new(
            ChurStatus::InvalidInput,
            "the handle was never issued by this process",
        ));
    };
    if generation == 0 || generation > slot.generation {
        return Err(Error::new(
            ChurStatus::InvalidInput,
            "the handle was never issued by this process",
        ));
    }
    if generation < slot.generation {
        // The slot has moved on, so this value was issued and is already
        // released. A re-close is success.
        return Ok(None);
    }
    let taken = slot.entry.take();
    drop(table);
    if let Some(entry) = taken.as_ref() {
        revoke_reader(entry);
    }
    Ok(taken)
}

/// Closes every handle `owner` transitively owns, and returns them.
///
/// It returns the entries so the caller drops them outside the registry lock:
/// dropping a session closes a database and joining an operation waits for a
/// worker, and §8 forbids holding the registry lock across either.
///
/// The walk is transitive because ownership is: a runtime owns its sessions and
/// a session owns its readers and operations, so closing a runtime reaches a
/// reader two levels down.
pub fn drain_owned_by(owner: Handle) -> Vec<Arc<Entry>> {
    let _lifecycle = reader_lifecycle();
    let mut frontier = vec![owner];
    let mut taken = Vec::new();
    let mut table = table();
    let (index, generation) = split(owner);
    if let Some(slot) = table.slots.get_mut(index)
        && slot.generation == generation
    {
        slot.closing = true;
    }
    while let Some(current) = frontier.pop() {
        // The handles are collected before the entries are taken, because a
        // taken entry no longer names its slot and its own children would then
        // be unreachable.
        let owned: Vec<(usize, Handle)> = table
            .slots
            .iter()
            .enumerate()
            .filter(|(_, slot)| {
                slot.entry
                    .as_ref()
                    .is_some_and(|entry| entry.owner() == Some(current))
            })
            .filter_map(|(index, slot)| {
                join(index, slot.generation)
                    .ok()
                    .map(|handle| (index, handle))
            })
            .collect();
        for (index, handle) in owned {
            if let Some(entry) = table.slots[index].entry.take() {
                taken.push(entry);
                frontier.push(handle);
            }
        }
    }
    drop(table);
    for entry in &taken {
        revoke_reader(entry);
    }
    taken
}

fn revoke_reader(entry: &Entry) {
    if let Entry::Reader { reader, .. } = entry {
        drop(lock(reader).take());
    }
}

/// Revokes object readers before permanent erasure can report success.
///
/// The session lock serializes this with reader creation. Remove public handles
/// under the table lock, then wait for any in-flight read and drop both its
/// derived keys and cached plaintext without holding the registry lock.
pub fn invalidate_object_readers(
    session: &chur_catalog::vault::Session,
    object: &chur_core::Id,
) -> Result<()> {
    let scope = std::fs::canonicalize(session.root_dir().base()).map_err(|_| {
        Error::new(
            ChurStatus::IoFailure,
            "the reader storage root could not be resolved",
        )
    })?;
    let entries = {
        let mut table = table();
        table
            .slots
            .iter_mut()
            .filter_map(|slot| {
                let matches = slot.entry.as_ref().is_some_and(|entry| {
                    matches!(entry.as_ref(), Entry::Reader {
                root, store_id, object_id, ..
            } if root == &scope && store_id == &session.object_store_id() && object_id == object)
                });
                if matches { slot.entry.take() } else { None }
            })
            .collect::<Vec<_>>()
    };
    for entry in entries {
        revoke_reader(&entry);
    }
    Ok(())
}

/// Serializes key acquisition and reader publication with permanent erasure.
/// Acquire after the session lock. Ordinary reads do not take this guard.
/// One process-wide guard also covers separate handles for the same physical vault.
pub fn reader_lifecycle() -> MutexGuard<'static, ()> {
    static LIFECYCLE: Mutex<()> = Mutex::new(());
    lock(&LIFECYCLE)
}

/// The number of live handles, for the leak test §15 asks for.
#[must_use]
pub fn live() -> usize {
    table()
        .slots
        .iter()
        .filter(|slot| slot.entry.is_some())
        .count()
}

/// Locks a mutex, recovering a poisoned one.
///
/// A poisoned mutex means a call panicked while holding it. §11 invalidates
/// the handle that owned that call, and the boundary can only perform that
/// invalidation while the registry and the entry's mutexes are still usable,
/// so the lock is recovered rather than refused. Whether the value itself is
/// still usable is decided by the handle's own state checks, and a handle
/// whose call panicked is gone from the registry before any of that matters.
pub fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}

/// Removes the entry a panicked call owned, §11 of the interop contract.
///
/// The boundary caught the panic and reports `INTERNAL_FAILURE`; the handle
/// that owned the call is invalidated so a later call on it also fails. The
/// teardown is the walk a close uses, because a session that died mid-call
/// must leave no reader or operation holding catalog state behind it, and it
/// runs before the entry itself is taken so the ownership walk still sees it.
///
/// Every failure path is ignored by construction: the value may already be
/// closed, and the boundary is reporting a panic rather than a status, so
/// there is nothing to propagate into.
pub fn invalidate(handle: Handle) {
    let owned = drain_owned_by(handle);
    drop(owned);
    let taken = close(handle);
    drop(taken);
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used)]
#[expect(unsafe_code, reason = "the regression tests drive the real C ABI")]
mod reader_teardown_tests {
    use super::*;
    use crate::{api, records::*};
    use chur_catalog::{paths::VaultRoot, vault};
    use chur_media::import::{self, CanonicalMedia, SourceCapability};
    use zeroize::Zeroizing;

    #[test]
    fn close_and_owner_drain_erase_a_reader_retained_by_an_in_flight_arc() {
        for owner_drain in [false, true] {
            let path = std::env::temp_dir().join(format!(
                "chur-reader-drain-{}",
                chur_crypto::random::id().unwrap().to_hex()
            ));
            std::fs::create_dir_all(&path).unwrap();
            let password = b"synthetic reader teardown password";
            let mut native =
                vault::create(&VaultRoot::new(path.clone()), password, 1_700_000_000_000)
                    .unwrap()
                    .activate()
                    .unwrap();
            let object = import::import_bytes(
                &mut native,
                SourceCapability {
                    seekable: true,
                    known_length: Some(4096),
                    content_type_hint: "application/octet-stream".into(),
                    original_filename: None,
                    capture_time_ms: None,
                },
                CanonicalMedia::opaque(),
                "application/octet-stream",
                &Zeroizing::new(vec![17; 4096]),
                1_700_000_000_001,
            )
            .unwrap();
            drop(native);
            let bytes = path.to_str().unwrap().as_bytes();
            let config = ChurRuntimeConfigV1 {
                root_path: bytes.as_ptr(),
                root_path_length: bytes.len() as u32,
            };
            let mut runtime = 0;
            assert_eq!(unsafe { api::chur_runtime_open(&config, &mut runtime) }, 0);
            let request = ChurUnlockRequestV1 {
                factor: 1,
                reserved: [0; 3],
                secret: password.as_ptr(),
                secret_length: password.len() as u32,
            };
            let mut session = 0;
            assert_eq!(
                unsafe { api::chur_vault_unlock(runtime, &request, &mut session) },
                0
            );
            let mut reader = 0;
            assert_eq!(
                unsafe {
                    api::chur_object_reader_open(
                        session,
                        &ChurObjectRefV1 {
                            object_id: *object.as_bytes(),
                        },
                        1,
                        &mut reader,
                    )
                },
                0
            );
            let mut buffer = [0; 32];
            let mut written = 0;
            assert_eq!(
                unsafe {
                    api::chur_object_reader_read_at(
                        reader,
                        0,
                        buffer.as_mut_ptr(),
                        buffer.len(),
                        &mut written,
                    )
                },
                0
            );
            assert_eq!(buffer, [17; 32]);
            let unpublished = if owner_drain {
                let entry = get(session, Kind::Session).unwrap();
                let Entry::Session {
                    session: guarded, ..
                } = entry.as_ref()
                else {
                    unreachable!()
                };
                let native = lock(guarded);
                Some((
                    chur_media::reader::open(
                        &native,
                        &object,
                        chur_format::constants::StreamKind::Original,
                    )
                    .unwrap(),
                    native.object_store_id(),
                ))
            } else {
                None
            };
            // A concurrent read can retain this reference before close detaches its slot.
            let retained = get(reader, Kind::Reader).unwrap();
            if owner_drain {
                assert_eq!(unsafe { api::chur_vault_lock(session, 0) }, 0);
            } else {
                assert_eq!(unsafe { api::chur_object_reader_close(reader) }, 0);
            }
            let Entry::Reader {
                reader: retained_reader,
                ..
            } = retained.as_ref()
            else {
                unreachable!()
            };
            assert!(
                lock(retained_reader).is_none(),
                "a detached Arc must not retain keys or cached plaintext"
            );
            assert_eq!(unsafe { api::chur_object_reader_close(reader) }, 0);
            if let Some((opened, store_id)) = unpublished {
                let result = insert(Entry::Reader {
                    session,
                    root: std::fs::canonicalize(&path).unwrap(),
                    store_id,
                    object_id: object,
                    reader: Mutex::new(Some(opened)),
                });
                assert_eq!(
                    result.err().map(|error| error.status()),
                    Some(ChurStatus::SessionExpired),
                    "a prepared reader cannot publish after its owner was closed"
                );
            }
            drop(retained);
            assert_eq!(unsafe { api::chur_runtime_close(runtime) }, 0);
            std::fs::remove_dir_all(path).unwrap();
        }
    }
}
