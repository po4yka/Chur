//! Chur sync and sharing protocol records.
//!
//! Canonical, signed, encrypted operation-log records, device identities,
//! collection grants, and rollback state. Gated by ADR-0007: nothing here is
//! production-approved until the local vault formats are stable and the
//! protocol passes its dedicated review (`docs/assurance/RELEASE_GATES.md`
//! gates 5 and 6).
//!
//! Normative sources:
//!
//! - `docs/sync/SYNC_PROTOCOL_V1.md` (proposed future protocol outline)
//! - `docs/sync/OPERATION_LOG.md`, `DEVICE_IDENTITY.md`,
//!   `COLLECTION_GRANTS.md`, `REVOCATION.md`, `ROLLBACK_PROTECTION.md`
//!
//! The v1 operation record starts the implementation. Later modules add the
//! device membership, checkpoint, convergence, and transport state machines.

pub mod checkpoint;
pub mod collection_membership;
pub mod collection_operation;
pub mod collection_operation_log;
pub mod convergence;
pub mod deletion;
pub mod grant;
pub mod identity;
mod key_domain;
pub mod materialization;
pub mod membership;
pub mod operation;
pub mod operation_log;
pub mod payload;
pub mod rotation;
pub mod state;

pub use key_domain::{KeyDirectory, KeyDomain};
