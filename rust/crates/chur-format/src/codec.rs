//! The canonical encoder and decoder.
//!
//! `docs/format/CANONICAL_ENCODING_V1.md` §13 asks for explicit read and write
//! functions, bounded cursor operations, checked arithmetic, and structured
//! non-secret errors, with no generic serializer as the authority. This module
//! is that: a [`Reader`] over a byte slice and a [`Writer`] over a growing
//! buffer, both with one function per primitive of §2.
//!
//! A [`Reader`] carries the status its owning format uses for a structural
//! failure, so a truncated container reports `OBJECT_CORRUPT` and a truncated
//! descriptor reports its own code, without either parser rewriting errors the
//! codec produced.

use chur_core::status::ChurStatus;
use chur_core::{Error, Id, Result};

/// A bounded cursor over encoded bytes.
///
/// Every read is checked against the remaining input, and the reader never
/// allocates on behalf of a declared length: [`Reader::variable`] borrows from
/// the input it already holds.
pub struct Reader<'a> {
    bytes: &'a [u8],
    position: usize,
    truncation: ChurStatus,
}

impl<'a> Reader<'a> {
    /// Starts a reader.
    ///
    /// `truncation` is the status a short read, an unsupported length, or
    /// trailing bytes produce for the format that owns these bytes.
    #[must_use]
    pub const fn new(bytes: &'a [u8], truncation: ChurStatus) -> Self {
        Self {
            bytes,
            position: 0,
            truncation,
        }
    }

    fn short(&self) -> Error {
        Error::new(
            self.truncation,
            "encoded record ended before a declared field",
        )
    }

    /// The offset of the next unread byte.
    #[must_use]
    pub const fn position(&self) -> usize {
        self.position
    }

    /// The number of unread bytes.
    #[must_use]
    pub const fn remaining(&self) -> usize {
        self.bytes.len() - self.position
    }

    /// Whether every byte has been read.
    #[must_use]
    pub const fn is_empty(&self) -> bool {
        self.remaining() == 0
    }

    /// Reads exactly `length` bytes.
    ///
    /// # Errors
    ///
    /// Returns the reader's truncation status when fewer bytes remain.
    pub fn slice(&mut self, length: usize) -> Result<&'a [u8]> {
        let end = self
            .position
            .checked_add(length)
            .ok_or_else(|| self.short())?;
        if end > self.bytes.len() {
            return Err(self.short());
        }
        let value = &self.bytes[self.position..end];
        self.position = end;
        Ok(value)
    }

    /// Reads a fixed-width byte element.
    ///
    /// # Errors
    ///
    /// Returns the reader's truncation status when fewer than `N` bytes remain.
    pub fn fixed<const N: usize>(&mut self) -> Result<[u8; N]> {
        let slice = self.slice(N)?;
        let mut value = [0u8; N];
        value.copy_from_slice(slice);
        Ok(value)
    }

    /// Reads a `u8`.
    ///
    /// # Errors
    ///
    /// Returns the reader's truncation status when no byte remains.
    pub fn u8(&mut self) -> Result<u8> {
        Ok(self.fixed::<1>()?[0])
    }

    /// Reads a big-endian `u16`.
    ///
    /// # Errors
    ///
    /// Returns the reader's truncation status when fewer than 2 bytes remain.
    pub fn u16(&mut self) -> Result<u16> {
        Ok(u16::from_be_bytes(self.fixed::<2>()?))
    }

    /// Reads a big-endian `u32`.
    ///
    /// # Errors
    ///
    /// Returns the reader's truncation status when fewer than 4 bytes remain.
    pub fn u32(&mut self) -> Result<u32> {
        Ok(u32::from_be_bytes(self.fixed::<4>()?))
    }

    /// Reads a big-endian `u64`.
    ///
    /// # Errors
    ///
    /// Returns the reader's truncation status when fewer than 8 bytes remain.
    pub fn u64(&mut self) -> Result<u64> {
        Ok(u64::from_be_bytes(self.fixed::<8>()?))
    }

    /// Reads a boolean.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::NonCanonicalEncoding`] for any byte other than
    /// `0x00` or `0x01`, per §11.
    pub fn bool(&mut self) -> Result<bool> {
        match self.u8()? {
            0x00 => Ok(false),
            0x01 => Ok(true),
            _ => Err(Error::new(
                ChurStatus::NonCanonicalEncoding,
                "boolean byte is neither 0x00 nor 0x01",
            )),
        }
    }

    /// Reads an optional presence byte.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::NonCanonicalEncoding`] for any byte other than
    /// `0x00` or `0x01`, per §11.
    pub fn presence(&mut self) -> Result<bool> {
        match self.u8()? {
            0x00 => Ok(false),
            0x01 => Ok(true),
            _ => Err(Error::new(
                ChurStatus::NonCanonicalEncoding,
                "optional presence byte is neither 0x00 nor 0x01",
            )),
        }
    }

    /// Reads a 16-byte identifier and rejects the reserved all-zero value.
    ///
    /// # Errors
    ///
    /// Returns the reader's truncation status when fewer than 16 bytes remain,
    /// and [`ChurStatus::InvalidInput`] for the reserved value.
    pub fn id(&mut self) -> Result<Id> {
        Id::from_slice(self.slice(chur_core::limits::ID_LEN)?)
    }

    /// Reads a variable-length byte element: a `u32` length then the bytes.
    ///
    /// `maximum` is the bound the owning specification sets. The length is
    /// checked against both the bound and the remaining input before any byte
    /// is taken, so a forged length allocates nothing.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::ResourceLimitExceeded`] when the declared length
    /// exceeds `maximum`, and the reader's truncation status when it exceeds
    /// the remaining input.
    pub fn variable(&mut self, maximum: u32) -> Result<&'a [u8]> {
        let declared = self.u32()?;
        if declared > maximum {
            return Err(Error::new(
                ChurStatus::ResourceLimitExceeded,
                "declared element length exceeds the parser limit",
            ));
        }
        let length = usize::try_from(declared).map_err(|_| self.short())?;
        self.slice(length)
    }

    /// Reads a UTF-8 string element.
    ///
    /// # Errors
    ///
    /// As [`Reader::variable`], plus [`ChurStatus::NonCanonicalEncoding`] for
    /// invalid UTF-8, per §3.
    pub fn string(&mut self, maximum: u32) -> Result<&'a str> {
        let bytes = self.variable(maximum)?;
        core::str::from_utf8(bytes).map_err(|_| {
            Error::new(
                ChurStatus::NonCanonicalEncoding,
                "string element is not valid UTF-8",
            )
        })
    }

    /// Asserts that the input is exhausted.
    ///
    /// §11 requires a decoder for authenticated bytes to reject trailing bytes.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::NonCanonicalEncoding`] when bytes remain.
    pub fn finish(self) -> Result<()> {
        if self.remaining() == 0 {
            return Ok(());
        }
        Err(Error::new(
            ChurStatus::NonCanonicalEncoding,
            "encoded record carries trailing bytes",
        ))
    }

    /// Reads a fixed constant and rejects any other value.
    ///
    /// # Errors
    ///
    /// Returns `mismatch` when the bytes differ, and the reader's truncation
    /// status when fewer than `N` bytes remain.
    pub fn constant<const N: usize>(
        &mut self,
        expected: &[u8; N],
        mismatch: ChurStatus,
        context: &'static str,
    ) -> Result<()> {
        let found = self.fixed::<N>()?;
        if &found == expected {
            return Ok(());
        }
        Err(Error::new(mismatch, context))
    }
}

/// A growing buffer that writes canonical bytes.
///
/// Every method mirrors one [`Reader`] method, so a round trip is written and
/// read by the same field order and a property test can compare the two.
#[derive(Debug, Default, Clone)]
pub struct Writer {
    bytes: Vec<u8>,
}

impl Writer {
    /// An empty writer.
    #[must_use]
    pub const fn new() -> Self {
        Self { bytes: Vec::new() }
    }

    /// An empty writer with room for `capacity` bytes.
    #[must_use]
    pub fn with_capacity(capacity: usize) -> Self {
        Self {
            bytes: Vec::with_capacity(capacity),
        }
    }

    /// Appends a fixed-width byte element, with no length prefix.
    pub fn fixed(&mut self, value: &[u8]) -> &mut Self {
        self.bytes.extend_from_slice(value);
        self
    }

    /// Appends a `u8`.
    pub fn u8(&mut self, value: u8) -> &mut Self {
        self.bytes.push(value);
        self
    }

    /// Appends a big-endian `u16`.
    pub fn u16(&mut self, value: u16) -> &mut Self {
        self.fixed(&value.to_be_bytes())
    }

    /// Appends a big-endian `u32`.
    pub fn u32(&mut self, value: u32) -> &mut Self {
        self.fixed(&value.to_be_bytes())
    }

    /// Appends a big-endian `u64`.
    pub fn u64(&mut self, value: u64) -> &mut Self {
        self.fixed(&value.to_be_bytes())
    }

    /// Appends a boolean as `0x00` or `0x01`.
    pub fn bool(&mut self, value: bool) -> &mut Self {
        self.u8(u8::from(value))
    }

    /// Appends an optional presence byte.
    pub fn presence(&mut self, present: bool) -> &mut Self {
        self.u8(u8::from(present))
    }

    /// Appends a 16-byte identifier.
    pub fn id(&mut self, value: &Id) -> &mut Self {
        self.fixed(value.as_bytes())
    }

    /// Appends a variable-length byte element: a `u32` length then the bytes.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::ResourceLimitExceeded`] when the value is longer
    /// than a `u32` can express.
    pub fn variable(&mut self, value: &[u8]) -> Result<&mut Self> {
        let length = u32::try_from(value.len()).map_err(|_| {
            Error::new(
                ChurStatus::ResourceLimitExceeded,
                "variable element exceeds the u32 length prefix",
            )
        })?;
        Ok(self.u32(length).fixed(value))
    }

    /// Appends a UTF-8 string element.
    ///
    /// # Errors
    ///
    /// As [`Writer::variable`].
    pub fn string(&mut self, value: &str) -> Result<&mut Self> {
        self.variable(value.as_bytes())
    }

    /// The bytes written so far.
    #[must_use]
    pub fn as_slice(&self) -> &[u8] {
        &self.bytes
    }

    /// The number of bytes written.
    #[must_use]
    pub fn len(&self) -> usize {
        self.bytes.len()
    }

    /// Whether nothing has been written.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.bytes.is_empty()
    }

    /// The encoded bytes.
    #[must_use]
    pub fn finish(self) -> Vec<u8> {
        self.bytes
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::panic, clippy::expect_used)]

    use super::*;

    const TRUNCATION: ChurStatus = ChurStatus::ObjectCorrupt;

    #[test]
    fn integers_are_big_endian() {
        let mut writer = Writer::new();
        writer
            .u16(0x0102)
            .u32(0x0304_0506)
            .u64(0x0708_090a_0b0c_0d0e);
        assert_eq!(
            writer.as_slice(),
            &[1, 2, 3, 4, 5, 6, 7, 8, 9, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e]
        );
        let encoded = writer.finish();
        let mut reader = Reader::new(&encoded, TRUNCATION);
        assert_eq!(reader.u16().unwrap(), 0x0102);
        assert_eq!(reader.u32().unwrap(), 0x0304_0506);
        assert_eq!(reader.u64().unwrap(), 0x0708_090a_0b0c_0d0e);
        reader.finish().unwrap();
    }

    #[test]
    fn every_boundary_value_round_trips() {
        let mut writer = Writer::new();
        writer
            .u8(0)
            .u8(u8::MAX)
            .u16(0)
            .u16(u16::MAX)
            .u32(0)
            .u32(u32::MAX)
            .u64(0)
            .u64(u64::MAX);
        let encoded = writer.finish();
        let mut reader = Reader::new(&encoded, TRUNCATION);
        assert_eq!(reader.u8().unwrap(), 0);
        assert_eq!(reader.u8().unwrap(), u8::MAX);
        assert_eq!(reader.u16().unwrap(), 0);
        assert_eq!(reader.u16().unwrap(), u16::MAX);
        assert_eq!(reader.u32().unwrap(), 0);
        assert_eq!(reader.u32().unwrap(), u32::MAX);
        assert_eq!(reader.u64().unwrap(), 0);
        assert_eq!(reader.u64().unwrap(), u64::MAX);
        reader.finish().unwrap();
    }

    #[test]
    fn a_boolean_other_than_zero_or_one_is_non_canonical() {
        for byte in 2..=255u8 {
            let bytes = [byte];
            let mut reader = Reader::new(&bytes, TRUNCATION);
            assert_eq!(
                reader.bool().unwrap_err().status(),
                ChurStatus::NonCanonicalEncoding
            );
            let mut reader = Reader::new(&bytes, TRUNCATION);
            assert_eq!(
                reader.presence().unwrap_err().status(),
                ChurStatus::NonCanonicalEncoding
            );
        }
    }

    #[test]
    fn truncation_at_every_boundary_uses_the_owning_status() {
        let mut writer = Writer::new();
        writer.u64(1).u32(2).u16(3).u8(4);
        let encoded = writer.finish();
        for cut in 0..encoded.len() {
            let mut reader = Reader::new(&encoded[..cut], TRUNCATION);
            let outcome = (|| {
                reader.u64()?;
                reader.u32()?;
                reader.u16()?;
                reader.u8()
            })();
            assert_eq!(outcome.unwrap_err().status(), TRUNCATION, "cut at {cut}");
        }
    }

    #[test]
    fn trailing_bytes_are_rejected() {
        let bytes = [0u8; 3];
        let mut reader = Reader::new(&bytes, TRUNCATION);
        reader.u16().unwrap();
        assert_eq!(
            reader.finish().unwrap_err().status(),
            ChurStatus::NonCanonicalEncoding
        );
    }

    #[test]
    fn a_declared_length_over_the_limit_allocates_nothing() {
        let mut writer = Writer::new();
        writer.u32(4_000_000_000).fixed(&[0u8; 4]);
        let encoded = writer.finish();
        let mut reader = Reader::new(&encoded, TRUNCATION);
        assert_eq!(
            reader.variable(64).unwrap_err().status(),
            ChurStatus::ResourceLimitExceeded
        );
    }

    #[test]
    fn a_declared_length_over_the_remaining_input_is_truncation() {
        let mut writer = Writer::new();
        writer.u32(64).fixed(&[0u8; 4]);
        let encoded = writer.finish();
        let mut reader = Reader::new(&encoded, TRUNCATION);
        assert_eq!(reader.variable(64).unwrap_err().status(), TRUNCATION);
    }

    #[test]
    fn an_empty_variable_element_round_trips() {
        let mut writer = Writer::new();
        writer.variable(b"").unwrap();
        let encoded = writer.finish();
        assert_eq!(encoded, vec![0, 0, 0, 0]);
        let mut reader = Reader::new(&encoded, TRUNCATION);
        assert_eq!(reader.variable(16).unwrap(), b"");
        reader.finish().unwrap();
    }

    #[test]
    fn invalid_utf8_is_rejected() {
        let mut writer = Writer::new();
        writer.variable(&[0xff, 0xfe]).unwrap();
        let encoded = writer.finish();
        let mut reader = Reader::new(&encoded, TRUNCATION);
        assert_eq!(
            reader.string(16).unwrap_err().status(),
            ChurStatus::NonCanonicalEncoding
        );
    }

    #[test]
    fn the_reserved_identifier_is_rejected_on_read() {
        let bytes = [0u8; 16];
        let mut reader = Reader::new(&bytes, TRUNCATION);
        assert_eq!(reader.id().unwrap_err().status(), ChurStatus::InvalidInput);
    }

    #[test]
    fn a_constant_mismatch_reports_the_status_the_caller_chose() {
        let bytes = *b"CHURXXX1";
        let mut reader = Reader::new(&bytes, TRUNCATION);
        let error = reader
            .constant(b"CHUROBJ1", ChurStatus::ObjectCorrupt, "wrong magic")
            .unwrap_err();
        assert_eq!(error.status(), ChurStatus::ObjectCorrupt);
    }
}
