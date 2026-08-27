//! The redacted error type every Chur crate returns.
//!
//! `docs/ERROR_MODEL.md` requires that an error carry a stable code and bounded
//! non-private metadata, and that no untrusted input reach a message, a log, or
//! a crash report. This type enforces the second half structurally: its context
//! is a `&'static str`, so a filename, a search query, or a decrypted field
//! cannot be formatted into it.

use core::fmt;

use crate::status::{ChurStatus, Retry};

/// A Chur error: one stable status plus a fixed description of where it arose.
///
/// The context is a compile-time constant. There is no constructor that accepts
/// an owned string, which is what keeps private values out of diagnostics.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct Error {
    status: ChurStatus,
    context: &'static str,
}

impl Error {
    /// Builds an error from a status and a constant context.
    #[must_use]
    pub const fn new(status: ChurStatus, context: &'static str) -> Self {
        Self { status, context }
    }

    /// The stable status.
    #[must_use]
    pub const fn status(&self) -> ChurStatus {
        self.status
    }

    /// The constant context.
    #[must_use]
    pub const fn context(&self) -> &'static str {
        self.context
    }

    /// The retry classification of the status.
    #[must_use]
    pub const fn retry(&self) -> Retry {
        self.status.retry()
    }

    /// The ABI value a caller across the FFI boundary receives.
    #[must_use]
    pub const fn as_i32(&self) -> i32 {
        self.status.as_i32()
    }
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}: {}", self.status.name(), self.context)
    }
}

impl core::error::Error for Error {}

/// The result type of every fallible Chur operation.
pub type Result<T> = core::result::Result<T, Error>;

/// Builds an [`Error`] from a [`ChurStatus`] variant name and a constant context.
///
/// It exists so a call site reads as one line and cannot accidentally format a
/// runtime value into the context.
///
/// ```
/// use chur_core::{err, ChurStatus};
///
/// let error = err!(InvalidInput, "chunk index exceeds the record count");
/// assert_eq!(error.status(), ChurStatus::InvalidInput);
/// ```
#[macro_export]
macro_rules! err {
    ($status:ident, $context:literal) => {
        $crate::Error::new($crate::ChurStatus::$status, $context)
    };
}

/// Returns early with an [`Error`] built from a status variant and a context.
///
/// ```
/// use chur_core::{bail, ChurStatus, Result};
///
/// fn reject() -> Result<()> {
///     bail!(ResourceLimitExceeded, "declared length exceeds the parser limit");
/// }
/// assert_eq!(reject().unwrap_err().status(), ChurStatus::ResourceLimitExceeded);
/// ```
#[macro_export]
macro_rules! bail {
    ($status:ident, $context:literal) => {
        return ::core::result::Result::Err($crate::err!($status, $context))
    };
}

/// Returns early with an [`Error`] unless a condition holds.
///
/// ```
/// use chur_core::{ensure, ChurStatus, Result};
///
/// fn check(length: usize) -> Result<()> {
///     ensure!(length == 28, InvalidInput, "preamble is not 28 bytes");
///     Ok(())
/// }
/// assert!(check(28).is_ok());
/// assert_eq!(check(27).unwrap_err().status(), ChurStatus::InvalidInput);
/// ```
#[macro_export]
macro_rules! ensure {
    ($condition:expr, $status:ident, $context:literal) => {
        if !$condition {
            $crate::bail!($status, $context);
        }
    };
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::panic, clippy::expect_used)]

    use super::*;

    #[test]
    fn display_carries_the_code_name_and_the_constant_context() {
        let error = Error::new(ChurStatus::ObjectCorrupt, "chunk tag did not verify");
        assert_eq!(
            error.to_string(),
            "OBJECT_CORRUPT: chunk tag did not verify"
        );
    }

    #[test]
    fn the_abi_value_is_the_status_value() {
        let error = err!(AuthenticationFailed, "candidate root did not authenticate");
        assert_eq!(error.as_i32(), 100);
        assert_eq!(error.retry(), Retry::Yes);
    }

    #[test]
    fn ensure_passes_and_fails_on_the_stated_condition() {
        fn probe(ok: bool) -> Result<u8> {
            ensure!(ok, InvalidInput, "probe rejected");
            Ok(7)
        }
        assert_eq!(probe(true).unwrap(), 7);
        assert_eq!(probe(false).unwrap_err().status(), ChurStatus::InvalidInput);
    }
}
