//! Chur cryptographic primitives.
//!
//! Rust is the only owner of key material, derivation, wrapping, and AEAD use
//! (ADR-0001). Every construction here must stay testable from a CLI without
//! any Android or iOS dependency.
//!
//! Normative sources:
//!
//! - `docs/CRYPTOGRAPHY.md` (profile v1, primitives, HKDF labels, secret type model)
//! - `docs/security/KEY_HIERARCHY.md` (key classes, lifetimes, rotation)
//! - `docs/security/PASSWORD_PROFILE.md` (canonical password bytes, Argon2id profile)
//! - `docs/security/SECURITY_INVARIANTS.md` (SEC-001..SEC-010)
//!
//! Open items tracked by `docs/CRYPTOGRAPHY.md` section 74 remain marked as
//! provisional in this crate until frozen vectors exist.
//!
//! Modules land one construction at a time, each with tests.
