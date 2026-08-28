//! The audio waveform record, `docs/interop/MEDIA_PIPELINE.md` §6.1.
//!
//! §6 lists the waveform beside the OCR text layer and the face and embedding
//! records rather than beside the four image derivatives, and §12 gives it
//! neither a long edge nor a JPEG quality. It is a data record, not a picture,
//! and this module is its bytes.
//!
//! One format rather than two matters here: a waveform is drawn by shared
//! Compose code that runs on both hosts, so a record Android wrote must be one
//! iOS reads. §11 permits derivative output to differ across platforms and
//! still requires a declared generator profile; the peak values below may
//! differ between two decoders of the same recording, and the record that
//! carries them may not.

use chur_core::{Result, ensure, limits::media as bounds};

/// The `record_version` of a v1 waveform.
pub const WAVEFORM_VERSION_V1: u8 = 0x01;

/// The fixed head of the record, before the peaks.
pub const HEAD_LEN: usize = 8;

/// One audio waveform: a peak envelope over equal slices of the recording.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Waveform {
    duration_ms: u32,
    peaks: Vec<u8>,
}

impl Waveform {
    /// Builds a waveform from its duration and its peak envelope.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::InvalidInput`] for an empty envelope,
    /// [`ChurStatus::ResourceLimitExceeded`] above the §12 bucket bound or the
    /// four-hour duration bound.
    pub fn new(duration_ms: u32, peaks: Vec<u8>) -> Result<Self> {
        ensure!(
            !peaks.is_empty(),
            InvalidInput,
            "a waveform carries at least one bucket"
        );
        ensure!(
            peaks.len() <= bounds::WAVEFORM_BUCKETS_MAX,
            ResourceLimitExceeded,
            "the waveform exceeds the §12 bucket bound"
        );
        ensure!(
            u64::from(duration_ms) <= bounds::DURATION_MS_MAX,
            ResourceLimitExceeded,
            "the waveform claims a duration above the §12 four-hour bound"
        );
        Ok(Self { duration_ms, peaks })
    }

    /// The recording's duration in milliseconds.
    #[must_use]
    pub const fn duration_ms(&self) -> u32 {
        self.duration_ms
    }

    /// The peak envelope, one linear amplitude per equal slice.
    #[must_use]
    pub fn peaks(&self) -> &[u8] {
        &self.peaks
    }

    /// The canonical bytes, big-endian per `CANONICAL_ENCODING_V1.md` §2.
    #[must_use]
    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(HEAD_LEN + self.peaks.len());
        out.push(WAVEFORM_VERSION_V1);
        out.push(0x00);
        #[expect(
            clippy::cast_possible_truncation,
            reason = "new() bounds the length at WAVEFORM_BUCKETS_MAX, which is below u16::MAX"
        )]
        out.extend_from_slice(&(self.peaks.len() as u16).to_be_bytes());
        out.extend_from_slice(&self.duration_ms.to_be_bytes());
        out.extend_from_slice(&self.peaks);
        out
    }

    /// Parses canonical bytes.
    ///
    /// Every fixed field must hold its v1 value and the length must match the
    /// declared bucket count exactly. A record with trailing bytes is rejected
    /// rather than truncated, as §8 of `CANONICAL_ENCODING_V1.md` requires of
    /// every canonical decoder.
    ///
    /// # Errors
    ///
    /// Returns [`ChurStatus::NonCanonicalEncoding`] for a wrong fixed field or
    /// a length that contradicts the count,
    /// [`ChurStatus::UnsupportedVersion`] for another `record_version`, and the
    /// bounds errors of [`Waveform::new`].
    pub fn decode(bytes: &[u8]) -> Result<Self> {
        ensure!(
            bytes.len() >= HEAD_LEN,
            NonCanonicalEncoding,
            "the waveform record is shorter than its head"
        );
        ensure!(
            bytes[0] == WAVEFORM_VERSION_V1,
            UnsupportedVersion,
            "the waveform record carries an unsupported version"
        );
        ensure!(
            bytes[1] == 0x00,
            NonCanonicalEncoding,
            "the waveform reserved byte is not zero"
        );
        let count = usize::from(u16::from_be_bytes([bytes[2], bytes[3]]));
        let duration_ms = u32::from_be_bytes([bytes[4], bytes[5], bytes[6], bytes[7]]);
        ensure!(
            bytes.len() == HEAD_LEN + count,
            NonCanonicalEncoding,
            "the waveform length contradicts its bucket count"
        );
        Self::new(duration_ms, bytes[HEAD_LEN..].to_vec())
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

    use super::*;
    use chur_core::ChurStatus;

    fn status<T>(outcome: Result<T>) -> ChurStatus {
        let Err(error) = outcome else {
            panic!("the decoder accepted a record it must refuse");
        };
        error.status()
    }

    #[test]
    fn a_waveform_round_trips_through_its_canonical_bytes() {
        let waveform = Waveform::new(90_000, vec![0, 17, 200, 255, 3]).expect("build");
        let bytes = waveform.encode();
        assert_eq!(bytes.len(), HEAD_LEN + 5);
        assert_eq!(Waveform::decode(&bytes).expect("decode"), waveform);
    }

    #[test]
    fn every_fixed_field_is_checked_rather_than_ignored() {
        let bytes = Waveform::new(1_000, vec![9; 4]).expect("build").encode();

        let mut version = bytes.clone();
        version[0] = 0x02;
        assert_eq!(
            status(Waveform::decode(&version)),
            ChurStatus::UnsupportedVersion
        );

        let mut reserved = bytes.clone();
        reserved[1] = 0x01;
        assert_eq!(
            status(Waveform::decode(&reserved)),
            ChurStatus::NonCanonicalEncoding
        );

        let mut trailing = bytes.clone();
        trailing.push(0);
        assert_eq!(
            status(Waveform::decode(&trailing)),
            ChurStatus::NonCanonicalEncoding
        );

        let short = &bytes[..bytes.len() - 1];
        assert_eq!(
            status(Waveform::decode(short)),
            ChurStatus::NonCanonicalEncoding
        );
    }

    #[test]
    fn the_bucket_and_duration_bounds_are_enforced_at_both_ends() {
        assert_eq!(
            status(Waveform::new(0, Vec::new())),
            ChurStatus::InvalidInput
        );
        assert_eq!(
            status(Waveform::new(0, vec![0; bounds::WAVEFORM_BUCKETS_MAX + 1])),
            ChurStatus::ResourceLimitExceeded
        );
        assert_eq!(
            status(Waveform::new(14_400_001, vec![0; 8])),
            ChurStatus::ResourceLimitExceeded
        );
        // The exact bounds are accepted, so the check is a bound and not an
        // off-by-one that narrows the format.
        assert!(Waveform::new(14_400_000, vec![0; bounds::WAVEFORM_BUCKETS_MAX]).is_ok());
    }

    #[test]
    fn the_encoded_length_never_exceeds_the_record_bound() {
        let largest = Waveform::new(14_400_000, vec![255; bounds::WAVEFORM_BUCKETS_MAX])
            .expect("build")
            .encode();
        assert_eq!(largest.len(), bounds::WAVEFORM_BYTES_MAX);
    }
}
