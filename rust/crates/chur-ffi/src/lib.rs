//! Chur FFI boundary.
//!
//! Splits into a structured control plane (generated bindings allowed) and a
//! bounded streaming data plane on a stable C ABI (ADR-0006). Generated
//! bindings never become the canonical protocol definition.
//!
//! Normative sources:
//!
//! - `docs/interop/FFI_CONTRACT.md` (ABI handshake, handles, buffer ownership)
//! - `docs/security/SECURITY_INVARIANTS.md` (SEC-050, SEC-051)
//!
//! The data-plane functions land with the media runtime; this crate starts
//! with the ABI version handshake only.
