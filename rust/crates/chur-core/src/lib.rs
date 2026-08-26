//! Chur core domain types.
//!
//! This crate owns vault-lifecycle orchestration types and the stable error
//! taxonomy shared by every other crate. It deliberately holds no
//! cryptographic or storage logic.
//!
//! Normative sources:
//!
//! - `docs/ARCHITECTURE.md` (crate responsibilities, runtime model)
//! - `docs/ERROR_MODEL.md` (stable error codes, retry policy, redaction)
//! - `docs/security/SECURITY_INVARIANTS.md` (SEC-028, SEC-032)
//!
//! Modules land one milestone at a time.
