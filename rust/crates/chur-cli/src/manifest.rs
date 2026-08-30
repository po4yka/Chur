//! The vector manifest: `test-vectors/v1/manifest.json` and its fixtures.
//!
//! `docs/format/TEST_VECTORS.md` §1 and §2 fix the layout and the metadata.
//! `manifest.json` is the only index: a fixture no entry references, and an
//! entry that names a missing file, both fail the suite.
//!
//! The manifest is not itself a canonical protocol encoding. It is a UTF-8 JSON
//! index, so a serializer default here can never reach persisted bytes.

use std::collections::BTreeMap;
use std::fmt;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use serde_json::Value;

/// Largest byte value written inline as hexadecimal, §2.
///
/// Anything longer becomes a file reference, so a reviewer reads short values
/// in the diff and never a wall of hexadecimal.
pub const INLINE_MAX: usize = 4096;

/// Whether a vector must be accepted or rejected.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Outcome {
    /// The input is valid and produces the `expected` values.
    Accept,
    /// The input must be rejected with `error_code`.
    Reject,
}

impl fmt::Display for Outcome {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Outcome::Accept => "accept",
            Outcome::Reject => "reject",
        })
    }
}

/// The `generator` object of §2.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Generator {
    /// Generator name.
    pub name: String,
    /// Generator version.
    pub version: String,
    /// Repository commit the generator was built from.
    pub commit: String,
    /// Toolchain that built the generator.
    pub toolchain: String,
}

/// One manifest entry.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct VectorEntry {
    /// Unique identifier matching the §9 grammar.
    pub vector_id: String,
    /// Repository-relative path of the owning specification.
    pub spec: String,
    /// The section that defines the case.
    pub spec_section: String,
    /// One sentence.
    pub purpose: String,
    /// `accept` or `reject`.
    pub outcome: Outcome,
    /// Field name to byte value or file reference.
    pub inputs: BTreeMap<String, Value>,
    /// Present when `outcome` is `accept`.
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub expected: BTreeMap<String, Value>,
    /// Expected semantic fields.
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub decoded: BTreeMap<String, Value>,
    /// Present when `outcome` is `reject`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error_code: Option<String>,
    /// Explanatory text only.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub notes: Option<String>,
}

/// The whole manifest.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Manifest {
    /// `1` for this layout.
    pub manifest_version: u32,
    /// Repository commit of the specifications the vectors were generated from.
    pub spec_commit: String,
    /// What produced the vectors.
    pub generator: Generator,
    /// One entry per vector, sorted by `vector_id`.
    pub vectors: Vec<VectorEntry>,
}

/// A vector plus the fixture files it owns.
#[derive(Debug, Clone)]
pub struct Vector {
    /// The §9 format word this vector was declared under.
    pub format_word: String,
    /// The manifest entry.
    pub entry: VectorEntry,
    /// Fixture files, each a path relative to `test-vectors/v1/` and its bytes.
    pub files: Vec<(PathBuf, Vec<u8>)>,
}

/// Encodes bytes as the lowercase hexadecimal of §2.
#[must_use]
pub fn hex_of(bytes: &[u8]) -> String {
    hex::encode(bytes)
}

/// A JSON number for a value a `f64` holds exactly, a decimal string otherwise.
///
/// §2 requires this so a `u64` never loses precision in the manifest.
#[must_use]
pub fn number(value: u64) -> Value {
    if value <= 9_007_199_254_740_991 {
        Value::from(value)
    } else {
        Value::from(value.to_string())
    }
}

/// The §9 table: an allocated `format` word and the §1 directory it maps to.
///
/// A word absent from this table cannot name a vector, which is what keeps a
/// fixture from landing in a directory the layout does not define.
pub const FORMAT_DIRECTORIES: &[(&str, &str)] = &[
    ("canonical-encoding", "canonical-encoding"),
    ("key-derivation", "key-derivations"),
    ("password-slot", "password-slots"),
    ("recovery-slot", "recovery-slots"),
    ("keystore-slot", "keystore-slots"),
    ("keychain-slot", "keychain-slots"),
    ("vault-descriptor", "vault-descriptors"),
    ("collection-envelope", "collection-envelopes"),
    ("object-key-envelope", "object-key-envelopes"),
    ("object", "object-containers"),
    ("backup", "backup-packages"),
    ("operation", "sync-operations"),
    ("collection-grant", "collection-grants"),
    ("collection-membership", "collection-memberships"),
    ("collection-operation", "collection-operations"),
];

/// The directory an allocated `format` word maps to.
#[must_use]
pub fn format_directory(format_word: &str) -> Option<&'static str> {
    FORMAT_DIRECTORIES
        .iter()
        .find(|(word, _)| *word == format_word)
        .map(|(_, directory)| *directory)
}

/// The directory a vector's fixtures live in, §1.
///
/// A rejected vector keeps its format's `vector_id` and stores its fixtures
/// under `negative/`.
#[must_use]
pub fn fixture_dir(format_word: &str, outcome: Outcome) -> &'static str {
    match outcome {
        Outcome::Accept => format_directory(format_word).unwrap_or("unallocated"),
        Outcome::Reject => "negative",
    }
}

/// Builds one vector, filing long byte values as fixtures.
pub struct VectorBuilder {
    format_word: String,
    format_dir: String,
    entry: VectorEntry,
    files: Vec<(PathBuf, Vec<u8>)>,
}

impl VectorBuilder {
    /// Starts an `accept` vector.
    ///
    /// `format_word` is the §9 word, not a directory: the directory is derived
    /// from the table, so a vector cannot be filed outside the §1 layout.
    #[must_use]
    pub fn accept(
        format_word: &str,
        vector_id: &str,
        spec: &str,
        spec_section: &str,
        purpose: &str,
    ) -> Self {
        Self::new(
            format_word,
            vector_id,
            spec,
            spec_section,
            purpose,
            Outcome::Accept,
            None,
        )
    }

    /// Starts a `reject` vector with its stable error code.
    #[must_use]
    pub fn reject(
        format_word: &str,
        vector_id: &str,
        spec: &str,
        spec_section: &str,
        purpose: &str,
        error_code: &str,
    ) -> Self {
        Self::new(
            format_word,
            vector_id,
            spec,
            spec_section,
            purpose,
            Outcome::Reject,
            Some(error_code.to_owned()),
        )
    }

    fn new(
        format_word: &str,
        vector_id: &str,
        spec: &str,
        spec_section: &str,
        purpose: &str,
        outcome: Outcome,
        error_code: Option<String>,
    ) -> Self {
        Self {
            format_word: format_word.to_owned(),
            format_dir: fixture_dir(format_word, outcome).to_owned(),
            entry: VectorEntry {
                vector_id: vector_id.to_owned(),
                spec: spec.to_owned(),
                spec_section: spec_section.to_owned(),
                purpose: purpose.to_owned(),
                outcome,
                inputs: BTreeMap::new(),
                expected: BTreeMap::new(),
                decoded: BTreeMap::new(),
                error_code,
                notes: None,
            },
            files: Vec::new(),
        }
    }

    fn value_for(&mut self, role: &str, bytes: &[u8], single: bool) -> Value {
        if bytes.len() <= INLINE_MAX {
            return Value::from(hex_of(bytes));
        }
        let name = if single {
            format!("{}.bin", self.entry.vector_id)
        } else {
            format!("{}.{role}.bin", self.entry.vector_id)
        };
        let path = Path::new(&self.format_dir).join(&name);
        let reference = format!("{}/{name}", self.format_dir);
        self.files.push((path, bytes.to_vec()));
        serde_json::json!({ "file": reference })
    }

    /// Adds a byte-valued input.
    #[must_use]
    pub fn input_bytes(mut self, role: &str, bytes: &[u8]) -> Self {
        let value = self.value_for(role, bytes, false);
        self.entry.inputs.insert(role.to_owned(), value);
        self
    }

    /// Adds a semantic input.
    #[must_use]
    pub fn input(mut self, role: &str, value: Value) -> Self {
        self.entry.inputs.insert(role.to_owned(), value);
        self
    }

    /// Adds a byte-valued expectation, filed as the vector's single fixture.
    #[must_use]
    pub fn expect_single_fixture(mut self, role: &str, bytes: &[u8]) -> Self {
        let value = self.value_for(role, bytes, true);
        self.entry.expected.insert(role.to_owned(), value);
        self
    }

    /// Adds a byte-valued expectation.
    #[must_use]
    pub fn expect_bytes(mut self, role: &str, bytes: &[u8]) -> Self {
        let value = self.value_for(role, bytes, false);
        self.entry.expected.insert(role.to_owned(), value);
        self
    }

    /// Adds a semantic expectation.
    #[must_use]
    pub fn expect(mut self, role: &str, value: Value) -> Self {
        self.entry.expected.insert(role.to_owned(), value);
        self
    }

    /// Adds an expected decoded field.
    #[must_use]
    pub fn decoded(mut self, role: &str, value: Value) -> Self {
        self.entry.decoded.insert(role.to_owned(), value);
        self
    }

    /// Adds explanatory text.
    #[must_use]
    pub fn note(mut self, text: &str) -> Self {
        self.entry.notes = Some(text.to_owned());
        self
    }

    /// Finishes the vector.
    #[must_use]
    pub fn build(self) -> Vector {
        Vector {
            format_word: self.format_word,
            entry: self.entry,
            files: self.files,
        }
    }
}
