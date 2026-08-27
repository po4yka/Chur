//! Chur FFI boundary.
//!
//! Splits into a structured control plane and a bounded streaming data plane,
//! both on one stable C ABI (ADR-0006, frozen by ADR-0016). No binding
//! generator is part of the boundary.
//!
//! Normative sources:
//!
//! - `docs/interop/FFI_CONTRACT.md` (ABI handshake, handles, buffer ownership)
//! - `docs/security/SECURITY_INVARIANTS.md` (SEC-050, SEC-051)
//!
//! The data-plane functions land with the media runtime; this crate starts
//! with the ABI version handshake only.
