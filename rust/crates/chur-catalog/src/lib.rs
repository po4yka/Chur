//! Chur private catalog.
//!
//! The catalog stores queryable private metadata, key envelopes, journals,
//! integrity state, and future sync projections inside a Rust-owned encrypted
//! database. Room and DataStore never open or mirror it.
//!
//! Normative sources:
//!
//! - `docs/format/CATALOG_SCHEMA_V1.md` (logical entities, transactions)
//! - ADR-0004 (SQLCipher preferred physical engine, pending prototype
//!   validation of build size, WAL behavior, backup correctness)
//!
//! Physical implementation lands after the SQLCipher prototype required by
//! ADR-0004; this crate intentionally contains no database code yet.
