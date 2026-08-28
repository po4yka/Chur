//! What a long operation asks of its caller and reports to it.
//!
//! `docs/interop/FFI_CONTRACT.md` §9 fixes the guarantees: an operation
//! observes cancellation without waiting for its own completion, no plaintext
//! is produced after it does, and cancellation maps to `CANCELLED` rather than
//! to a corruption status. `docs/interop/MEDIA_PIPELINE.md` §2 adds that every
//! import stage is cancellable and that a lock cancels the transaction.
//!
//! §10 has no foreign callbacks, so this is not a callback boundary. The two
//! methods are read and written entirely inside Rust: `chur-ffi` implements
//! them over the atomic flag and the snapshot its own operation handle owns,
//! and `chur-cli` implements them over a counter it prints.

/// The caller's control of one long operation.
pub trait Progress {
    /// Whether the caller has asked the operation to stop.
    ///
    /// It is read once per chunk, which is the granularity at which the import
    /// journal already works, so an operation never stops in the middle of a
    /// record.
    fn cancelled(&self) -> bool;

    /// Reports the plaintext bytes the operation has handled so far.
    ///
    /// §10 permits only bounded non-private numbers, and this is one: a byte
    /// count carries no filename, no identifier, and no identity.
    fn advance(&mut self, processed: u64);
}

/// A caller that never cancels and reads no progress.
///
/// It is what a test, a vector generator, and a CLI command that prints one
/// line at the end pass. Both methods compile away.
#[derive(Debug, Clone, Copy, Default)]
pub struct Uninterrupted;

impl Progress for Uninterrupted {
    fn cancelled(&self) -> bool {
        false
    }

    fn advance(&mut self, _processed: u64) {}
}

/// The error a cancelled operation returns.
///
/// Every cancellation in this crate produces exactly this, so the status a
/// caller sees does not depend on which stage stopped.
#[must_use]
pub fn cancelled(what: &'static str) -> chur_core::Error {
    chur_core::Error::new(chur_core::ChurStatus::Cancelled, what)
}
