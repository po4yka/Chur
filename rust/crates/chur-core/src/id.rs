//! The 16-byte opaque identifier every v1 record carries.
//!
//! `docs/format/CANONICAL_ENCODING_V1.md` §8 makes a v1 identifier 16 random
//! bytes encoded exactly as bytes, reserves the all-zero value as invalid, and
//! forbids a textual rendering from re-entering authenticated bytes.

use core::fmt;

use crate::error::{Error, Result};
use crate::limits::ID_LEN;
use crate::status::ChurStatus;

/// A 16-byte opaque identifier.
///
/// The type refuses the all-zero value at construction, so a record that holds
/// an [`Id`] cannot carry the reserved invalid identifier.
///
/// [`fmt::Debug`] deliberately prints no bytes. `DEVELOPMENT.md` forbids logging
/// a stable private object identifier, and a derived `Debug` would put one in
/// every panic message and every log line that formats a record. Use
/// [`Id::to_hex`] where a rendering is intended.
#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct Id([u8; ID_LEN]);

impl Id {
    /// Builds an identifier from 16 bytes.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::InvalidInput`] when every byte is zero.
    pub const fn new(bytes: [u8; ID_LEN]) -> Result<Self> {
        let mut index = 0;
        while index < ID_LEN {
            if bytes[index] != 0 {
                return Ok(Self(bytes));
            }
            index += 1;
        }
        Err(Error::new(
            ChurStatus::InvalidInput,
            "identifier is the reserved all-zero value",
        ))
    }

    /// Builds an identifier from a slice.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::InvalidInput`] when the slice is not 16 bytes or
    /// holds the reserved all-zero value.
    pub fn from_slice(bytes: &[u8]) -> Result<Self> {
        let array: [u8; ID_LEN] = bytes
            .try_into()
            .map_err(|_| Error::new(ChurStatus::InvalidInput, "identifier is not 16 bytes"))?;
        Self::new(array)
    }

    /// The exact bytes, as they appear in an encoded record.
    #[must_use]
    pub const fn as_bytes(&self) -> &[u8; ID_LEN] {
        &self.0
    }

    /// A lowercase hexadecimal rendering, for tooling and vector manifests.
    ///
    /// The result is presentation only and must not re-enter authenticated
    /// bytes, per `CANONICAL_ENCODING_V1.md` §8.
    #[must_use]
    pub fn to_hex(&self) -> String {
        let mut out = String::with_capacity(ID_LEN * 2);
        for byte in self.0 {
            out.push(nibble(byte >> 4));
            out.push(nibble(byte & 0x0f));
        }
        out
    }
}

const fn nibble(value: u8) -> char {
    match value {
        0..=9 => (b'0' + value) as char,
        _ => (b'a' + value - 10) as char,
    }
}

impl fmt::Debug for Id {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("Id(<redacted>)")
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::panic, clippy::expect_used)]

    use super::*;

    #[test]
    fn the_all_zero_identifier_is_rejected() {
        assert_eq!(
            Id::new([0; ID_LEN]).unwrap_err().status(),
            ChurStatus::InvalidInput
        );
    }

    #[test]
    fn one_non_zero_byte_is_enough() {
        let mut bytes = [0u8; ID_LEN];
        bytes[ID_LEN - 1] = 1;
        assert_eq!(Id::new(bytes).unwrap().as_bytes(), &bytes);
    }

    #[test]
    fn a_slice_of_the_wrong_length_is_rejected() {
        assert!(Id::from_slice(&[1u8; 15]).is_err());
        assert!(Id::from_slice(&[1u8; 17]).is_err());
        assert!(Id::from_slice(&[1u8; 16]).is_ok());
    }

    #[test]
    fn hex_is_lowercase_and_full_width() {
        let id = Id::new([
            0x00, 0x0f, 0xa0, 0xff, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
        ])
        .unwrap();
        assert_eq!(id.to_hex(), "000fa0ff0102030405060708090a0b0c");
    }

    #[test]
    fn debug_reveals_no_bytes() {
        let id = Id::new([0xab; ID_LEN]).unwrap();
        assert_eq!(format!("{id:?}"), "Id(<redacted>)");
        assert!(!format!("{id:?}").contains("ab"));
    }
}
