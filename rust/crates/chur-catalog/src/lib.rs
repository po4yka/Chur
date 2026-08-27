//! Chur private catalog.
//!
//! The catalog stores queryable private metadata, object and collection
//! relationships, key envelopes, journals, integrity state, and future sync
//! projections inside a Rust-owned encrypted database. Room and DataStore never
//! open or mirror it.
//!
//! Normative sources:
//!
//! - `docs/format/CATALOG_SCHEMA_V1.md` (logical entities, transactions)
//! - ADR-0004 (SQLCipher accessed directly from Rust)
//!
//! Two representations exist and no third: the physical SQLCipher schema of
//! [`schema`], and the canonical serialization of
//! `docs/format/CANONICAL_ENCODING_V1.md` when a portable backup exports it.

pub mod db;
pub mod schema;

pub use db::{CatalogDb, CatalogKey, CatalogLocation};
