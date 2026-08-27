//! Chur private storage formats.
//!
//! Byte-exact, versioned formats for everything that persists across
//! platforms. Kotlin and Swift never define alternate encoders; they consume
//! records produced here (CANONICAL_ENCODING_V1.md section 13).
//!
//! Normative sources:
//!
//! - `docs/format/CANONICAL_ENCODING_V1.md`
//! - `docs/format/OBJECT_KEY_ENVELOPE_V1.md`
//! - `docs/format/OBJECT_CONTAINER_V1.md`
//! - `docs/format/CATALOG_SCHEMA_V1.md`
//! - `docs/format/BACKUP_FORMAT_V1.md`
//!
//! Byte-exact codecs land one artifact at a time, each with positive and
//! negative tests.

pub mod codec;
pub mod constants;
pub mod container;
pub mod descriptor;
pub mod envelope;
pub mod slot;
