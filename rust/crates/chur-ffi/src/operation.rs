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

use chur_core::Result;

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
/// album, object identifier, or real-or-decoy identity appears in it.
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
    /// caller already saw.
    pub fn finish(&self, status: i32) {
        let mut progress = crate::registry::lock(&self.progress);
        if progress.terminal {
            return;
        }
        progress.stage = Stage::Terminal;
        progress.terminal = true;
        progress.status = status;
    }

    /// The current snapshot.
    #[must_use]
    pub fn snapshot(&self) -> Progress {
        *crate::registry::lock(&self.progress)
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
    /// cancellation, and its result becomes the one terminal status.
    pub fn spawn(
        kind: OperationKind,
        total: u64,
        body: impl FnOnce(&Shared) -> Result<()> + Send + 'static,
    ) -> Result<Self> {
        let shared = Arc::new(Shared {
            progress: Mutex::new(Progress::starting(kind, total)),
            cancelled: AtomicBool::new(false),
        });
        let worker_state = Arc::clone(&shared);
        let worker = std::thread::Builder::new()
            .name(String::from("chur-operation"))
            .spawn(move || {
                // Success is `0`, which is not a member of `ChurStatus`, so the
                // ABI value is carried rather than the enum.
                let status = match body(&worker_state) {
                    Ok(()) => chur_core::CHUR_OK,
                    Err(error) => error.as_i32(),
                };
                worker_state.finish(status);
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
