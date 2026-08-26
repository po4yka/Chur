//! Chur media pipeline.
//!
//! Streaming bounded-memory import and export plus random-access plaintext
//! ranges over immutable encrypted containers. Platform codecs see transient
//! plaintext only through the contracts in `docs/interop/MEDIA_PIPELINE.md`;
//! identity, encryption, persistence, and integrity stay here.
//!
//! Normative sources:
//!
//! - `docs/interop/MEDIA_PIPELINE.md`
//! - `docs/format/OBJECT_CONTAINER_V1.md` (random access, verification states)
//! - `docs/security/PLAINTEXT_LIFECYCLE.md` (import, viewing, scratch policy)

//! Modules land with their owning specifications; none exist yet.
