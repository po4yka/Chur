//! Chur core domain types.
//!
//! This crate owns the stable error taxonomy, the v1 parser limits, and the
//! opaque identifier every other crate encodes. It deliberately holds no
//! cryptographic or storage logic: a crate that parses bytes needs the limits
//! before it needs a cipher, and a crate that returns an error needs the code
//! registry without depending on either.
//!
//! Normative sources:
//!
//! - `docs/ERROR_MODEL.md` (stable error codes, retry policy, redaction)
//! - `docs/adr/0020-set-the-v1-parser-limits.md` (limits and where they live)
//! - `docs/format/CANONICAL_ENCODING_V1.md` §8 (identifiers)
//! - `docs/security/SECURITY_INVARIANTS.md` (SEC-028, SEC-032)

pub mod error;
pub mod id;
pub mod limits;
pub mod status;

pub use error::{Error, Result};
pub use id::Id;
pub use status::{CHUR_OK, ChurStatus, Retry};
