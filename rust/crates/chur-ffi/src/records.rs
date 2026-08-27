//! The C structures the control plane passes and the canonical page it writes.
//!
//! `docs/interop/FFI_CONTRACT.md` §5 keeps a control record to commands,
//! bounded query parameters, opaque references, small projections, stable error
//! codes, progress summaries, and capability flags. None of these carries a
//! key, a decrypted manifest, a private path, or media bytes.
//!
//! A request is a C structure because the caller builds it. A page is canonical
//! bytes because Rust builds it and §6.4 requires one definition rather than
//! whichever padding the host's compiler chose.

use chur_catalog::query::{Cursor, ObjectProjection, ObjectQuery, Page, Scope, Sort};
use chur_core::{ChurStatus, Error, Id, Result, ensure, limits::catalog as limits};

/// The exact length of the page header of §6.4.
pub const PAGE_HEADER_LEN: usize = 8 + 8 + 4 + 1 + limits::CURSOR_LEN;

const _: () = assert!(PAGE_HEADER_LEN == 63);

/// The configuration `chur_runtime_open` takes.
#[repr(C)]
pub struct ChurRuntimeConfigV1 {
    /// The storage root, UTF-8, not NUL-terminated.
    pub root_path: *const u8,
    /// The length of `root_path` in bytes.
    pub root_path_length: u32,
}

/// The credential `chur_vault_unlock` uses.
#[repr(C)]
pub struct ChurUnlockRequestV1 {
    /// `1` password, `2` recovery phrase, `3` Apple Keychain secret.
    pub factor: u8,
    /// Reserved, zero.
    pub reserved: [u8; 3],
    /// The credential bytes: a password, a recovery phrase, or a 32-byte secret.
    pub secret: *const u8,
    /// The length of `secret` in bytes.
    pub secret_length: u32,
}

/// One page request, §6.4.
#[repr(C)]
pub struct ChurQueryV1 {
    /// The scope.
    pub scope: u8,
    /// The sort.
    pub sort: u8,
    /// The media-kind mask.
    pub kinds: u16,
    /// The page size, zero for the default.
    pub limit: u32,
    /// The album or tag, zero bytes for every other scope.
    pub scope_id: [u8; 16],
    /// Whether `cursor` carries a value.
    pub cursor_present: u8,
    /// The page cursor.
    pub cursor: [u8; limits::CURSOR_LEN],
    /// The search terms, UTF-8, not NUL-terminated.
    pub terms: *const u8,
    /// The length of `terms` in bytes.
    pub terms_length: u32,
}

/// The password and profile a new vault is created with, §6.5.
#[repr(C)]
pub struct ChurCreateRequestV1 {
    /// The password bytes, UTF-8, not NUL-terminated.
    pub password: *const u8,
    /// The length of `password` in bytes.
    pub password_length: u32,
    /// Argon2id memory cost in KiB, zero for the frozen v1 floor.
    pub memory_kib: u32,
    /// Argon2id iterations, zero for the frozen v1 default.
    pub iterations: u32,
    /// Argon2id parallelism, zero for the frozen v1 default.
    pub parallelism: u32,
}

/// An opaque object reference.
#[repr(C)]
pub struct ChurObjectRefV1 {
    /// The object identifier.
    pub object_id: [u8; 16],
}

/// What `chur_import_begin` is told about its source.
#[repr(C)]
pub struct ChurImportRequestV1 {
    /// `1` seekable, `0` not.
    pub seekable: u8,
    /// Whether `known_length` carries a value.
    pub known_length_present: u8,
    /// The canonical media class, `docs/format/CANONICAL_ENCODING_V1.md` §15.4.
    pub media_class: u8,
    /// Reserved, zero.
    pub reserved: u8,
    /// Pixel width, zero when the class has none.
    pub width: u32,
    /// Pixel height, zero when the class has none.
    pub height: u32,
    /// Duration in milliseconds, zero when the class has none.
    pub duration_ms: u64,
    /// The provider's length hint.
    pub known_length: u64,
    /// The capture time the provider reported.
    pub capture_time_ms: u64,
    /// Whether `capture_time_ms` carries a value.
    pub capture_time_present: u8,
    /// Reserved, zero.
    pub reserved_two: [u8; 7],
    /// The validated IANA media type, UTF-8, not NUL-terminated.
    pub content_type: *const u8,
    /// The length of `content_type` in bytes.
    pub content_type_length: u32,
    /// The provider's filename, UTF-8, not NUL-terminated, may be null.
    pub original_filename: *const u8,
    /// The length of `original_filename` in bytes.
    pub original_filename_length: u32,
}

/// What `chur_integrity_scan_begin` is told.
#[repr(C)]
pub struct ChurScanRequestV1 {
    /// `1` to scan one object named by `object_id`, `0` to scan every object.
    pub single_object: u8,
    /// Reserved, zero.
    pub reserved: [u8; 7],
    /// The object to scan when `single_object` is `1`.
    pub object_id: [u8; 16],
}

/// The progress snapshot `chur_operation_poll` writes.
#[repr(C)]
pub struct ChurProgressV1 {
    /// The operation kind.
    pub kind: u32,
    /// The stage.
    pub stage: u32,
    /// Plaintext bytes processed.
    pub processed: u64,
    /// The total when known, zero otherwise.
    pub total: u64,
    /// `1` once the terminal result is set.
    pub terminal: u8,
    /// Reserved, zero.
    pub reserved: [u8; 3],
    /// The terminal status, meaningful only once `terminal` is `1`.
    pub status: i32,
}

/// The content information of §6.1.
#[repr(C)]
pub struct ChurContentInfoV1 {
    /// The authenticated plaintext size.
    pub plaintext_size: u64,
    /// A NUL-terminated lowercase IANA media type.
    pub content_type: [u8; 64],
    /// The canonical media-kind value.
    pub media_kind: u16,
    /// `1` for a committed immutable object.
    pub byte_range_supported: u8,
    /// `1` only after final-commit validation.
    pub complete: u8,
    /// Reserved, zero.
    pub reserved: [u8; 4],
}

/// Builds an [`ObjectQuery`] from a request, rejecting an unallocated value.
pub fn query_from(
    scope: u8,
    sort: u8,
    kinds: u16,
    limit: u32,
    scope_id: &[u8; 16],
    cursor: Option<&[u8]>,
    terms: Option<&[u8]>,
) -> Result<ObjectQuery> {
    let scope = match scope {
        1 => Scope::Timeline,
        2 => Scope::Album(Id::from_slice(scope_id)?),
        3 => Scope::Favorites,
        4 => Scope::Tag(Id::from_slice(scope_id)?),
        5 => {
            let bytes = terms.unwrap_or(&[]);
            ensure!(
                bytes.len() <= limits::SEARCH_TERMS_MAX,
                ResourceLimitExceeded,
                "the search query exceeds its catalog bound"
            );
            let text = core::str::from_utf8(bytes).map_err(|_| {
                Error::new(ChurStatus::InvalidInput, "the search query is not UTF-8")
            })?;
            Scope::Search(text.to_owned())
        }
        6 => Scope::Quarantine,
        _ => {
            return Err(Error::new(
                ChurStatus::InvalidInput,
                "the query names an unallocated scope",
            ));
        }
    };
    let sort = match sort {
        0 | 1 => Sort::CaptureDesc,
        2 => Sort::CaptureAsc,
        3 => Sort::ImportDesc,
        _ => {
            return Err(Error::new(
                ChurStatus::InvalidInput,
                "the query names an unallocated sort",
            ));
        }
    };
    let cursor = cursor.map(Cursor::decode).transpose()?;
    Ok(ObjectQuery {
        scope,
        kinds,
        sort,
        cursor,
        limit,
    })
}

/// The exact byte length one page occupies, §6.4.
#[must_use]
pub fn page_length(objects: usize) -> usize {
    PAGE_HEADER_LEN + objects * limits::PROJECTION_LEN
}

/// Encodes a page into `destination`, §6.4.
///
/// A buffer smaller than the page is `RESOURCE_LIMIT_EXCEEDED` and writes
/// nothing: a truncated page would be indistinguishable from a short one, and
/// the caller would treat the scope as exhausted.
pub fn encode_page(page: &Page, destination: &mut [u8]) -> Result<usize> {
    let length = page_length(page.objects.len());
    ensure!(
        destination.len() >= length,
        ResourceLimitExceeded,
        "the destination buffer is smaller than the page"
    );
    let count = u32::try_from(page.objects.len())
        .map_err(|_| Error::new(ChurStatus::InternalFailure, "the page count exceeds a u32"))?;
    destination[..8].copy_from_slice(&page.total_count.to_be_bytes());
    destination[8..16].copy_from_slice(&page.catalog_generation.to_be_bytes());
    destination[16..20].copy_from_slice(&count.to_be_bytes());
    match &page.next_cursor {
        Some(cursor) => {
            destination[20] = 1;
            destination[21..PAGE_HEADER_LEN].copy_from_slice(&cursor.encode());
        }
        None => {
            destination[20] = 0;
            destination[21..PAGE_HEADER_LEN].fill(0);
        }
    }
    let mut at = PAGE_HEADER_LEN;
    for object in &page.objects {
        destination[at..at + limits::PROJECTION_LEN].copy_from_slice(&object.encode());
        at += limits::PROJECTION_LEN;
    }
    Ok(length)
}

/// One decoded page, §6.4.
#[derive(Debug, Clone)]
pub struct DecodedPage {
    /// The rows the scope holds.
    pub total_count: u64,
    /// The generation the page was read at.
    pub catalog_generation: u64,
    /// The cursor for the next page, absent when the scope is exhausted.
    pub next_cursor: Option<[u8; limits::CURSOR_LEN]>,
    /// The projections.
    pub objects: Vec<ObjectProjection>,
}

/// Decodes a page, which the CLI and the tests use to check what was written.
pub fn decode_page(bytes: &[u8]) -> Result<DecodedPage> {
    ensure!(
        bytes.len() >= PAGE_HEADER_LEN,
        InvalidInput,
        "the page is shorter than its header"
    );
    let mut eight = [0u8; 8];
    eight.copy_from_slice(&bytes[..8]);
    let total = u64::from_be_bytes(eight);
    eight.copy_from_slice(&bytes[8..16]);
    let generation = u64::from_be_bytes(eight);
    let mut four = [0u8; 4];
    four.copy_from_slice(&bytes[16..20]);
    let count = u32::from_be_bytes(four) as usize;
    let cursor = match bytes[20] {
        0 => None,
        1 => {
            let mut value = [0u8; limits::CURSOR_LEN];
            value.copy_from_slice(&bytes[21..PAGE_HEADER_LEN]);
            Some(value)
        }
        _ => {
            return Err(Error::new(
                ChurStatus::NonCanonicalEncoding,
                "the cursor presence byte is neither 0x00 nor 0x01",
            ));
        }
    };
    ensure!(
        bytes.len() >= page_length(count),
        InvalidInput,
        "the page is shorter than the rows it declares"
    );
    let mut objects = Vec::with_capacity(count);
    for index in 0..count {
        let at = PAGE_HEADER_LEN + index * limits::PROJECTION_LEN;
        objects.push(ObjectProjection::decode(
            &bytes[at..at + limits::PROJECTION_LEN],
        )?);
    }
    Ok(DecodedPage {
        total_count: total,
        catalog_generation: generation,
        next_cursor: cursor,
        objects,
    })
}

// ---------------------------------------------------------------------------
// The §6.5 list records
// ---------------------------------------------------------------------------

/// Appends a `u16`-prefixed string, refusing one longer than the prefix holds.
fn put_text(out: &mut Vec<u8>, text: &str) {
    // Every string reaching here is already bounded by the catalog: an album or
    // tag name by §21's label bounds, a content type by §6.1, and a filename or
    // caption by §12 of the media pipeline. The saturating conversion is a
    // belt on top of that, and it truncates the length rather than the bytes,
    // which a decoder then rejects as short.
    let length = u16::try_from(text.len()).unwrap_or(u16::MAX);
    out.extend_from_slice(&length.to_be_bytes());
    out.extend_from_slice(&text.as_bytes()[..length as usize]);
}

/// Encodes `ChurSlotListV1` of §6.5.
#[must_use]
pub fn encode_slot_list(slots: Vec<(Id, chur_format::constants::SlotType, u64)>) -> Vec<u8> {
    let mut out = Vec::with_capacity(4 + slots.len() * 25);
    out.extend_from_slice(&u32::try_from(slots.len()).unwrap_or(u32::MAX).to_be_bytes());
    for (slot_id, slot_type, generation) in slots {
        out.extend_from_slice(slot_id.as_bytes());
        out.push(slot_type.value());
        out.extend_from_slice(&generation.to_be_bytes());
    }
    out
}

/// Encodes `ChurAlbumListV1` of §6.5.
#[must_use]
pub fn encode_album_list(albums: &[(chur_catalog::model::Album, u64)]) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(
        &u32::try_from(albums.len())
            .unwrap_or(u32::MAX)
            .to_be_bytes(),
    );
    for (album, members) in albums {
        out.extend_from_slice(album.album_id.as_bytes());
        out.extend_from_slice(&members.to_be_bytes());
        put_text(&mut out, &album.name);
    }
    out
}

/// Encodes `ChurObjectMetadataV1` of §6.5.
#[must_use]
pub fn encode_object_metadata(
    object: &chur_catalog::model::Object,
    metadata: &chur_catalog::model::MetadataRevision,
    tags: &[chur_catalog::model::Tag],
) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(&object.capture_time_ms.to_be_bytes());
    out.extend_from_slice(&object.import_time_ms.to_be_bytes());
    out.push(u8::from(object.capture_time_substituted));
    out.extend_from_slice(&object.width.to_be_bytes());
    out.extend_from_slice(&object.height.to_be_bytes());
    out.extend_from_slice(&object.duration_ms.to_be_bytes());
    out.extend_from_slice(&object.plaintext_size.to_be_bytes());
    put_text(&mut out, &metadata.content_type);
    put_text(
        &mut out,
        metadata.original_filename.as_deref().unwrap_or(""),
    );
    put_text(&mut out, metadata.caption.as_deref().unwrap_or(""));
    out.extend_from_slice(&u16::try_from(tags.len()).unwrap_or(u16::MAX).to_be_bytes());
    for tag in tags.iter().take(usize::from(u16::MAX)) {
        out.extend_from_slice(tag.tag_id.as_bytes());
        put_text(&mut out, &tag.name);
    }
    out
}

/// One decoded album, for the CLI and the tests.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DecodedAlbum {
    /// The album identifier.
    pub album_id: Id,
    /// The number of listable members.
    pub member_count: u64,
    /// The album name.
    pub name: String,
}

/// Decodes `ChurAlbumListV1`.
pub fn decode_album_list(bytes: &[u8]) -> Result<Vec<DecodedAlbum>> {
    let mut reader = RecordReader::new(bytes);
    let count = reader.u32()?;
    let mut albums = Vec::new();
    for _ in 0..count {
        albums.push(DecodedAlbum {
            album_id: Id::from_slice(reader.take(16)?)?,
            member_count: reader.u64()?,
            name: reader.text()?,
        });
    }
    reader.finish()?;
    Ok(albums)
}

/// One decoded metadata record, for the CLI and the tests.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DecodedMetadata {
    /// Capture time in milliseconds.
    pub capture_time_ms: u64,
    /// Import time in milliseconds.
    pub import_time_ms: u64,
    /// Whether the capture time was substituted.
    pub capture_time_substituted: bool,
    /// Pixel width.
    pub width: u32,
    /// Pixel height.
    pub height: u32,
    /// Duration in milliseconds.
    pub duration_ms: u64,
    /// The authenticated plaintext size.
    pub plaintext_size: u64,
    /// The IANA media type.
    pub content_type: String,
    /// The provider's filename, empty when there was none.
    pub filename: String,
    /// The caption, empty when there is none.
    pub caption: String,
    /// The tags, in name order.
    pub tags: Vec<(Id, String)>,
}

/// Decodes `ChurObjectMetadataV1`.
pub fn decode_object_metadata(bytes: &[u8]) -> Result<DecodedMetadata> {
    let mut reader = RecordReader::new(bytes);
    let record = DecodedMetadata {
        capture_time_ms: reader.u64()?,
        import_time_ms: reader.u64()?,
        capture_time_substituted: match reader.u8()? {
            0 => false,
            1 => true,
            _ => {
                return Err(Error::new(
                    ChurStatus::NonCanonicalEncoding,
                    "a boolean is neither 0x00 nor 0x01",
                ));
            }
        },
        width: reader.u32()?,
        height: reader.u32()?,
        duration_ms: reader.u64()?,
        plaintext_size: reader.u64()?,
        content_type: reader.text()?,
        filename: reader.text()?,
        caption: reader.text()?,
        tags: {
            let count = reader.u16()?;
            let mut tags = Vec::new();
            for _ in 0..count {
                let id = Id::from_slice(reader.take(16)?)?;
                tags.push((id, reader.text()?));
            }
            tags
        },
    };
    reader.finish()?;
    Ok(record)
}

/// A big-endian reader for the §6.5 records.
///
/// It is separate from `chur_format::codec::Reader`, which carries the
/// truncation status of the format that owns it; these records are boundary
/// bytes and their truncation is `INVALID_INPUT`.
struct RecordReader<'a> {
    bytes: &'a [u8],
    at: usize,
}

impl<'a> RecordReader<'a> {
    const fn new(bytes: &'a [u8]) -> Self {
        Self { bytes, at: 0 }
    }

    fn take(&mut self, length: usize) -> Result<&'a [u8]> {
        let end = self.at.checked_add(length).ok_or_else(short)?;
        let slice = self.bytes.get(self.at..end).ok_or_else(short)?;
        self.at = end;
        Ok(slice)
    }

    fn u8(&mut self) -> Result<u8> {
        Ok(self.take(1)?[0])
    }

    fn u16(&mut self) -> Result<u16> {
        let bytes = self.take(2)?;
        Ok(u16::from_be_bytes([bytes[0], bytes[1]]))
    }

    fn u32(&mut self) -> Result<u32> {
        let bytes = self.take(4)?;
        Ok(u32::from_be_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
    }

    fn u64(&mut self) -> Result<u64> {
        let bytes = self.take(8)?;
        let mut value = [0u8; 8];
        value.copy_from_slice(bytes);
        Ok(u64::from_be_bytes(value))
    }

    fn text(&mut self) -> Result<String> {
        let length = usize::from(self.u16()?);
        let bytes = self.take(length)?;
        core::str::from_utf8(bytes).map(str::to_owned).map_err(|_| {
            Error::new(
                ChurStatus::NonCanonicalEncoding,
                "a record string is not UTF-8",
            )
        })
    }

    /// Rejects trailing bytes, which `CANONICAL_ENCODING_V1.md` §3 requires of
    /// every Chur decoder.
    fn finish(&self) -> Result<()> {
        ensure!(
            self.at == self.bytes.len(),
            NonCanonicalEncoding,
            "the record carries trailing bytes"
        );
        Ok(())
    }
}

fn short() -> Error {
    Error::new(ChurStatus::InvalidInput, "the record ends inside a field")
}
