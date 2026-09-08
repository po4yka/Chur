//! Long-running operations and their progress snapshots.
//!
//! `docs/interop/FFI_CONTRACT.md` §10 has no foreign callbacks: Rust never
//! calls Kotlin, Swift, or Objective-C, so there is no delivery thread, no
//! re-entrancy rule, and no consumer-disappearance race. The caller polls its
//! own handle at a rate it chooses.
//!
//! §8 makes native calls synchronous and permits internal workers, so an
//! operation runs on one worker thread and the polling call reads a snapshot.
//! Taking the snapshot lock is the only thing a poll does, so it never waits on
//! the operation.
//!
//! §9's cancellation guarantees hold by construction: the worker observes the
//! flag between chunks, no plaintext is produced after it does, partial
//! ciphertext stays in the temporary namespace under the import journal, the
//! snapshot freezes once terminal, and cancellation maps to `CANCELLED`.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use chur_core::{Id, Result};

/// What kind of work an operation handle drives, for the progress snapshot.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u32)]
pub enum OperationKind {
    /// An import.
    Import = 1,
    /// An export.
    Export = 2,
    /// An integrity scan.
    IntegrityScan = 3,
    /// A backup package being written, `BACKUP_FORMAT_V1.md` §7.
    Backup = 4,
    /// A backup package being restored, §8.
    Restore = 5,
}

/// The stage an operation reports, a bounded non-private number.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u32)]
pub enum Stage {
    /// The operation is starting.
    Starting = 1,
    /// Bytes are moving.
    Running = 2,
    /// The final commit, rename, and catalog transaction are running.
    Committing = 3,
    /// The operation has produced its terminal result.
    Terminal = 4,
}

/// The snapshot `chur_operation_poll` copies.
///
/// §10: it contains only bounded non-private numbers. No filename, path,
/// album, or real-or-decoy identity appears in it. The one identifier §10
/// admits is the object a terminal successful import activated: the caller
/// that began the import needs it to attach the derivatives the media
/// pipeline produces next, and it is an opaque value the caller's own object
/// queries already return for that object.
#[derive(Debug, Clone, Copy)]
pub struct Progress {
    /// The kind of work.
    pub kind: OperationKind,
    /// Plaintext bytes processed.
    pub processed: u64,
    /// The total when it is known, zero otherwise.
    pub total: u64,
    /// The stage.
    pub stage: Stage,
    /// Whether the terminal result is set.
    pub terminal: bool,
    /// The terminal status as its ABI value, meaningful only once `terminal`
    /// is set.
    ///
    /// It is the `int32_t` rather than a [`ChurStatus`] because success is `0`
    /// and `0` is not a member of that enum: `docs/ERROR_MODEL.md` makes
    /// success the absence of an error code, and folding it into the enum turns
    /// every completed operation into `INTERNAL_FAILURE`.
    pub status: i32,
    /// The object a terminal successful import activated, `None` for every
    /// other kind, stage, and outcome.
    pub object_id: Option<Id>,
}

impl Progress {
    const fn starting(kind: OperationKind, total: u64) -> Self {
        Self {
            kind,
            processed: 0,
            total,
            stage: Stage::Starting,
            terminal: false,
            status: chur_core::CHUR_OK,
            object_id: None,
        }
    }
}

/// The shared state one operation's worker writes and its poller reads.
pub struct Shared {
    progress: Mutex<Progress>,
    cancelled: AtomicBool,
}

impl Shared {
    /// Whether the caller or a lock has asked the operation to stop.
    #[must_use]
    pub fn cancelled(&self) -> bool {
        self.cancelled.load(Ordering::Relaxed)
    }

    /// Records progress, unless the terminal result is already set.
    ///
    /// §9: no progress snapshot advances after the terminal flag is set.
    pub fn advance(&self, processed: u64, stage: Stage) {
        let mut progress = crate::registry::lock(&self.progress);
        if progress.terminal {
            return;
        }
        progress.processed = processed;
        progress.stage = stage;
    }

    /// Sets the one terminal result.
    ///
    /// §9: exactly one terminal result is observable. A second call is ignored,
    /// so a worker that fails while unwinding cannot overwrite the status the
    /// caller already saw. A successful import names the object it activated;
    /// every other terminal result carries no identifier.
    pub fn finish(&self, status: i32, object_id: Option<Id>) {
        let mut progress = crate::registry::lock(&self.progress);
        if progress.terminal {
            return;
        }
        progress.stage = Stage::Terminal;
        progress.terminal = true;
        progress.status = status;
        progress.object_id = object_id;
    }

    /// The current snapshot.
    #[must_use]
    pub fn snapshot(&self) -> Progress {
        *crate::registry::lock(&self.progress)
    }
}

/// [`Shared`] as the media crate's progress sink.
///
/// `chur-media` owns the loops that must observe cancellation, and it must not
/// depend on this crate to do it. The trait is the seam: the media crate names
/// what it needs, and this binds it to the atomic flag and the snapshot an
/// operation handle already owns.
pub struct SharedProgress<'a> {
    shared: &'a Shared,
    stage: Stage,
}

impl<'a> SharedProgress<'a> {
    /// Reports into `shared` under one stage.
    #[must_use]
    pub const fn new(shared: &'a Shared, stage: Stage) -> Self {
        Self { shared, stage }
    }
}

impl chur_media::progress::Progress for SharedProgress<'_> {
    fn cancelled(&self) -> bool {
        self.shared.cancelled()
    }

    fn advance(&mut self, processed: u64) {
        self.shared.advance(processed, self.stage);
    }
}

/// One operation handle: its shared state and its worker.
pub struct Operation {
    shared: Arc<Shared>,
    worker: Mutex<Option<std::thread::JoinHandle<()>>>,
}

impl Operation {
    /// Starts an operation on a worker thread.
    ///
    /// The body receives the shared state so it can report progress and observe
    /// cancellation, and its result becomes the one terminal status. A
    /// successful import returns the object it activated; every other body
    /// returns `Ok(None)`.
    pub fn spawn(
        kind: OperationKind,
        total: u64,
        body: impl FnOnce(&Shared) -> Result<Option<Id>> + Send + 'static,
    ) -> Result<Self> {
        let shared = Arc::new(Shared {
            progress: Mutex::new(Progress::starting(kind, total)),
            cancelled: AtomicBool::new(false),
        });
        let worker_state = Arc::clone(&shared);
        let worker = std::thread::Builder::new()
            .name(String::from("chur-operation"))
            .spawn(move || {
                // §11: a panic inside the worker body is caught here and
                // converted into INTERNAL_FAILURE, so a panicking body still
                // produces the one terminal result and `chur_operation_poll`
                // never waits forever. The payload is dropped inside the
                // boundary, never unwound across it. Success is `0`, which is
                // not a member of `ChurStatus`, so the ABI value is carried
                // rather than the enum.
                match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| body(&worker_state)))
                {
                    Ok(Ok(object_id)) => worker_state.finish(chur_core::CHUR_OK, object_id),
                    Ok(Err(error)) => worker_state.finish(error.as_i32(), None),
                    Err(_) => {
                        worker_state.finish(chur_core::ChurStatus::InternalFailure.as_i32(), None)
                    }
                };
            })
            .map_err(|_| chur_core::err!(InternalFailure, "an operation worker could not start"))?;
        Ok(Self {
            shared,
            worker: Mutex::new(Some(worker)),
        })
    }

    /// The progress snapshot.
    #[must_use]
    pub fn poll(&self) -> Progress {
        self.shared.snapshot()
    }

    /// Asks the operation to stop.
    ///
    /// §8 exempts cancel from the one-call-at-a-time rule: it is callable from
    /// any thread at any time, including while another call on the same handle
    /// is in flight, and it never waits on that call.
    pub fn cancel(&self) {
        self.shared.cancelled.store(true, Ordering::Relaxed);
    }

    /// Waits for the worker and releases it.
    ///
    /// Close is what joins: an operation whose worker is still running holds a
    /// session, and dropping the handle without joining would let the session
    /// close underneath it.
    pub fn join(&self) {
        let handle = crate::registry::lock(&self.worker).take();
        if let Some(handle) = handle {
            let _ = handle.join();
        }
    }
}

impl Drop for Operation {
    fn drop(&mut self) {
        self.cancel();
        self.join();
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::expect_used, clippy::panic)]

    use super::*;

    #[test]
    fn a_panicking_worker_still_produces_a_terminal_internal_failure() {
        // §11: the boundary converts a caught panic into INTERNAL_FAILURE, so
        // the handle reaches its one terminal result instead of leaving
        // `chur_operation_poll` waiting forever.
        let operation = Operation::spawn(OperationKind::Import, 100, |_shared| {
            panic!("the import body failed");
        })
        .expect("spawn");
        operation.join();
        let progress = operation.poll();
        assert!(progress.terminal);
        assert_eq!(
            progress.status,
            chur_core::ChurStatus::InternalFailure.as_i32()
        );
        assert_eq!(progress.object_id, None);
    }

    #[test]
    fn a_failing_worker_reports_the_error_status() {
        let operation = Operation::spawn(OperationKind::Import, 100, |_shared| {
            Err(chur_core::err!(InvalidInput, "the body refused its input"))
        })
        .expect("spawn");
        operation.join();
        let progress = operation.poll();
        assert!(progress.terminal);
        assert_eq!(
            progress.status,
            chur_core::ChurStatus::InvalidInput.as_i32()
        );
        assert_eq!(progress.object_id, None);
    }

    #[test]
    fn a_successful_import_reports_the_object_it_activated() {
        // §10: the terminal snapshot of an import names the object it
        // activated, which is what the caller attaches its derivatives to.
        let activated = chur_core::Id::new([7; 16]).expect("id");
        let operation = Operation::spawn(OperationKind::Import, 100, move |_shared| {
            Ok(Some(activated))
        })
        .expect("spawn");
        operation.join();
        let progress = operation.poll();
        assert!(progress.terminal);
        assert_eq!(progress.status, chur_core::CHUR_OK);
        assert_eq!(progress.object_id, Some(activated));
    }

    #[test]
    fn a_successful_non_import_operation_names_no_object() {
        let operation =
            Operation::spawn(OperationKind::Export, 100, |_shared| Ok(None)).expect("spawn");
        operation.join();
        let progress = operation.poll();
        assert!(progress.terminal);
        assert_eq!(progress.status, chur_core::CHUR_OK);
        assert_eq!(progress.object_id, None);
    }
}
