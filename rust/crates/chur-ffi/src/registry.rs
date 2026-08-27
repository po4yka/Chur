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
    },
    /// A random-access reader, and the session that opened it.
    Reader {
        /// The session this reader belongs to.
        session: Handle,
        /// The reader.
        reader: Mutex<chur_media::reader::ObjectReader>,
    },
    /// A long-running import, export, or integrity scan.
    Operation {
        /// The session this operation belongs to.
        session: Handle,
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
            Entry::Session { runtime, .. } => Some(*runtime),
            Entry::Reader { session, .. } | Entry::Operation { session, .. } => Some(*session),
        }
    }
}

impl Entry {
    /// The name §3 uses for this handle type, for a mismatch diagnostic.
    const fn kind(&self) -> Kind {
        match self {
            Entry::Runtime(_) => Kind::Runtime,
            Entry::Session { .. } => Kind::Session,
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
    if let Some(index) = table.slots.iter().position(|slot| slot.entry.is_none()) {
        let slot = &mut table.slots[index];
        // The generation increments on every allocation, so the value this slot
        // issues has never been issued before.
        slot.generation = slot.generation.wrapping_add(1).max(1);
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
    Ok(slot.entry.take())
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
    let mut frontier = vec![owner];
    let mut taken = Vec::new();
    let mut table = table();
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
    taken
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
/// A poisoned mutex means a call panicked while holding it, which §11 already
/// contained. Refusing every later call on the handle would turn one contained
/// failure into a permanently unusable session, so the lock is recovered and
/// the handle's own state checks decide whether the value is still usable.
pub fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}
