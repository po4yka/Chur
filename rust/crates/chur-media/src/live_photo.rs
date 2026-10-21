//! Bounded validation of the immutable Original defined by LIVE_PHOTO_BUNDLE_V1.

use chur_catalog::model::is_media_type;
use chur_core::{Error, Result, ensure, err};
use chur_crypto::sha256::ResourceSha256;
use zeroize::{Zeroize, Zeroizing};

pub(crate) const MIME: &str = "application/vnd.chur.live-photo";
const FIXED_HEADER: usize = 24;
const HEADER_MAX: usize = 65_536;
const BUNDLE_MAX: u64 = 2_147_483_648;

struct Resource {
    length: u64,
    digest: [u8; 32],
}

/// Non-bundle errors are deferred until the final MIME is known. Once the
/// bundle magic is present, malformed data and limits stop the source stream.
pub(crate) struct Validator {
    header: Zeroizing<Vec<u8>>,
    header_length: usize,
    resources: Vec<Resource>,
    resource_index: usize,
    resource_written: u64,
    hasher: ResourceSha256,
    total: u64,
    expected_total: u64,
    error: Option<Error>,
    bundle_magic: bool,
}

impl Validator {
    pub(crate) fn new() -> Self {
        Self {
            header: Zeroizing::new(Vec::with_capacity(FIXED_HEADER)),
            header_length: FIXED_HEADER,
            resources: Vec::new(),
            resource_index: 0,
            resource_written: 0,
            hasher: ResourceSha256::default(),
            total: 0,
            expected_total: 0,
            error: None,
            bundle_magic: false,
        }
    }

    pub(crate) fn update(&mut self, bytes: &[u8]) -> Result<()> {
        if self.error.is_none() {
            self.error = self.consume(bytes).err();
        }
        if self.bundle_magic
            && let Some(error) = self.error
        {
            return Err(error);
        }
        Ok(())
    }

    fn consume(&mut self, mut bytes: &[u8]) -> Result<()> {
        self.total = self
            .total
            .checked_add(bytes.len() as u64)
            .ok_or_else(|| err!(InvalidInput, "the Live Photo length overflows"))?;
        ensure!(
            self.total <= BUNDLE_MAX,
            ResourceLimitExceeded,
            "the Live Photo exceeds the 2 GiB bound"
        );
        while self.resources.is_empty() && !bytes.is_empty() {
            let take = bytes.len().min(self.header_length - self.header.len());
            self.header.extend_from_slice(&bytes[..take]);
            bytes = &bytes[take..];
            self.bundle_magic = self.header.starts_with(b"CHURLIVE");
            if self.header.len() == FIXED_HEADER && self.header_length == FIXED_HEADER {
                let mut at = 0;
                ensure!(
                    read::<8>(&self.header, &mut at)? == *b"CHURLIVE",
                    InvalidInput,
                    "the Live Photo magic is invalid"
                );
                ensure!(
                    u16::from_be_bytes(read(&self.header, &mut at)?) == 1,
                    InvalidInput,
                    "the Live Photo version is unsupported"
                );
                let count = u16::from_be_bytes(read(&self.header, &mut at)?);
                ensure!(
                    (2..=32).contains(&count),
                    InvalidInput,
                    "the Live Photo resource count is invalid"
                );
                self.header_length = u32::from_be_bytes(read(&self.header, &mut at)?) as usize;
                ensure!(
                    (FIXED_HEADER..=HEADER_MAX).contains(&self.header_length),
                    InvalidInput,
                    "the Live Photo header length is invalid"
                );
                // The full header is reserved in a new buffer and the fixed
                // part is moved over, so the old buffer is wiped on drop
                // rather than released by a regrowth.
                let mut grown = Zeroizing::new(Vec::with_capacity(self.header_length));
                grown.extend_from_slice(&self.header);
                self.header = grown;
                ensure!(
                    i64::from_be_bytes(read(&self.header, &mut at)?) >= -1,
                    InvalidInput,
                    "the Live Photo capture time is invalid"
                );
            }
            if self.header.len() == self.header_length {
                self.resources = parse_header(&self.header)?;
                self.expected_total = self.resources.iter().try_fold(
                    self.header_length as u64,
                    |offset, resource| {
                        offset.checked_add(resource.length).ok_or_else(|| {
                            err!(InvalidInput, "the Live Photo resource offset overflows")
                        })
                    },
                )?;
                ensure!(
                    self.expected_total <= BUNDLE_MAX,
                    ResourceLimitExceeded,
                    "the Live Photo exceeds the 2 GiB bound"
                );
                self.header.zeroize();
            }
        }
        while self.resource_index < self.resources.len() {
            let resource = &self.resources[self.resource_index];
            let remaining = resource.length - self.resource_written;
            let take = remaining.min(bytes.len() as u64) as usize;
            self.hasher.update(&bytes[..take]);
            self.resource_written += take as u64;
            bytes = &bytes[take..];
            if self.resource_written != resource.length {
                break;
            }
            let digest = std::mem::take(&mut self.hasher).finish();
            ensure!(
                digest == resource.digest,
                InvalidInput,
                "the Live Photo resource checksum does not match"
            );
            self.resource_index += 1;
            self.resource_written = 0;
        }
        ensure!(
            bytes.is_empty(),
            InvalidInput,
            "the Live Photo has trailing bytes"
        );
        Ok(())
    }

    pub(crate) fn finish(&self) -> Result<()> {
        if let Some(error) = self.error {
            return Err(error);
        }
        ensure!(
            !self.resources.is_empty()
                && self.resource_index == self.resources.len()
                && self.total == self.expected_total,
            InvalidInput,
            "the Live Photo is truncated"
        );
        Ok(())
    }

    pub(crate) fn has_bundle_magic(&self) -> bool {
        self.bundle_magic
    }
}

fn parse_header(header: &[u8]) -> Result<Vec<Resource>> {
    let count = u16::from_be_bytes(read(header, &mut 10)?);
    let mut at = FIXED_HEADER;
    let mut resources = Vec::with_capacity(usize::from(count));
    let mut photos = 0;
    let mut videos = 0;
    for _ in 0..count {
        let role = u32::from_be_bytes(read(header, &mut at)?);
        ensure!(
            (1..=2_147_483_647).contains(&role),
            InvalidInput,
            "the Live Photo resource role is invalid"
        );
        let length = u64::from_be_bytes(read(header, &mut at)?);
        let digest = read(header, &mut at)?;
        let name_length = usize::from(u16::from_be_bytes(read(header, &mut at)?));
        let uti_length = usize::from(u16::from_be_bytes(read(header, &mut at)?));
        let mime_length = usize::from(u16::from_be_bytes(read(header, &mut at)?));
        ensure!(
            (1..=1024).contains(&name_length)
                && (1..=255).contains(&uti_length)
                && (3..=63).contains(&mime_length),
            InvalidInput,
            "the Live Photo resource string length is invalid"
        );
        let _name = text(header, &mut at, name_length)?;
        let _uti = text(header, &mut at, uti_length)?;
        let mime = text(header, &mut at, mime_length)?;
        ensure!(
            is_media_type(mime),
            InvalidInput,
            "the Live Photo resource MIME is invalid"
        );
        if role == 1 || role == 9 {
            ensure!(
                length > 0,
                InvalidInput,
                "the Live Photo display resource is empty"
            );
            if role == 1 {
                photos += 1;
            } else {
                videos += 1;
            }
        }
        resources.push(Resource { length, digest });
    }
    ensure!(
        at == header.len(),
        InvalidInput,
        "the Live Photo header has extra bytes"
    );
    ensure!(
        photos == 1 && videos == 1,
        InvalidInput,
        "the Live Photo needs one original photo and one original paired video"
    );
    Ok(resources)
}

fn read<const N: usize>(bytes: &[u8], at: &mut usize) -> Result<[u8; N]> {
    let end = at
        .checked_add(N)
        .ok_or_else(|| err!(InvalidInput, "the Live Photo header offset overflows"))?;
    let value = bytes
        .get(*at..end)
        .and_then(|part| part.try_into().ok())
        .ok_or_else(|| err!(InvalidInput, "the Live Photo resource table is truncated"))?;
    *at = end;
    Ok(value)
}

fn text<'a>(bytes: &'a [u8], at: &mut usize, length: usize) -> Result<&'a str> {
    let end = at
        .checked_add(length)
        .ok_or_else(|| err!(InvalidInput, "the Live Photo string offset overflows"))?;
    let value = bytes
        .get(*at..end)
        .and_then(|part| std::str::from_utf8(part).ok())
        .filter(|value| !value.contains('\0'))
        .ok_or_else(|| err!(InvalidInput, "the Live Photo resource string is invalid"))?;
    *at = end;
    Ok(value)
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::panic, clippy::expect_used)]

    use super::*;

    #[test]
    fn the_header_buffer_is_reserved_once_at_its_declared_length() {
        // A regrowth would release the smaller buffer without a wipe, so the
        // declared length is reserved as soon as the fixed part is read.
        let mut fixed = b"CHURLIVE".to_vec();
        fixed.extend_from_slice(&1u16.to_be_bytes());
        fixed.extend_from_slice(&2u16.to_be_bytes());
        fixed.extend_from_slice(&200u32.to_be_bytes());
        fixed.extend_from_slice(&(-1i64).to_be_bytes());
        let mut validator = Validator::new();
        assert_eq!(validator.header.capacity(), FIXED_HEADER);
        validator.update(&fixed).unwrap();
        assert!(validator.error.is_none());
        assert_eq!(validator.header.len(), FIXED_HEADER);
        assert_eq!(validator.header.capacity(), 200);
        validator.update(&[0u8; 100]).unwrap();
        assert_eq!(validator.header.capacity(), 200);
    }
}
