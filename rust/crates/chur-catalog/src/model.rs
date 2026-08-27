//! The catalog row types.
//!
//! One type per logical entity of `docs/format/CATALOG_SCHEMA_V1.md` §1, in the
//! shape the schema stores. They carry no behaviour beyond validation, so a
//! reader of the module sees the entity model and nothing else.
//!
//! Private text lives here: `docs/format/CATALOG_SCHEMA_V1.md` §8 permits
//! queryable plaintext inside the unlocked database, and §16.4 needs the
//! filename, the caption, and the tag names in a form FTS5 can tokenize. None
//! of these types implements `Display`, and none appears in an [`Error`], which
//! is what keeps them out of a diagnostic.
//!
//! [`Error`]: chur_core::Error

use chur_core::{Id, Result, bail, limits::catalog as limits, limits::media};
use chur_format::constants::{IntegritySummary, MediaClass, ObjectState, StreamKind};

/// The v1 collection policy: every object of a single-vault install.
///
/// `docs/format/CATALOG_SCHEMA_V1.md` §3 allocates `0x01` and reserves the next
/// value for a per-album key domain.
pub const COLLECTION_POLICY_VAULT_DEFAULT: u8 = 0x01;

/// A collection that new objects may be sealed under, §3.
pub const COLLECTION_STATUS_ACTIVE: u8 = 0x01;

/// A collection that keeps its envelopes but takes no new object, §3.
pub const COLLECTION_STATUS_RETIRED: u8 = 0x02;

/// A key envelope a reader should use, §4 and §7.
pub const ENVELOPE_STATUS_ACTIVE: u8 = 0x01;

/// A key envelope kept for an older generation, §4 and §7.
pub const ENVELOPE_STATUS_SUPERSEDED: u8 = 0x02;

/// A security collection, `docs/format/CATALOG_SCHEMA_V1.md` §3.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Collection {
    /// The collection identifier.
    pub collection_id: Id,
    /// The epoch new objects are sealed under.
    pub current_epoch: u64,
    /// The policy this collection implements.
    pub policy_type: u8,
    /// The catalog generation the collection was created at.
    pub created_revision: u64,
    /// Whether the collection still accepts new objects.
    pub status: u8,
}

/// A media object, `docs/format/CATALOG_SCHEMA_V1.md` §5.
///
/// `state` and `integrity_summary` are the two independent enums of §5.1.
/// `integrity_summary` is meaningful only while `state` is
/// [`ObjectState::Active`], and [`Object::check`] enforces that rather than
/// leaving it to every caller.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Object {
    /// The object identifier.
    pub object_id: Id,
    /// The generation, advanced by a content replacement.
    pub object_generation: u64,
    /// The security collection whose key wraps this object's key.
    pub collection_id: Id,
    /// The original stream.
    pub primary_stream_id: Id,
    /// The canonical media kind.
    pub media_kind: MediaClass,
    /// Capture time in milliseconds, §8.1.
    pub capture_time_ms: u64,
    /// Import time in milliseconds, §8.1.
    pub import_time_ms: u64,
    /// Whether capture time was substituted from import time, §8.1.
    pub capture_time_substituted: bool,
    /// Authenticated plaintext size of the original stream.
    pub plaintext_size: u64,
    /// Pixel width, or zero when the kind has none.
    pub width: u32,
    /// Pixel height, or zero when the kind has none.
    pub height: u32,
    /// Duration in milliseconds, or zero for a still.
    pub duration_ms: u64,
    /// Whether the object is a favourite.
    pub favorite: bool,
    /// The lifecycle state.
    pub state: ObjectState,
    /// The verification verdict.
    pub integrity_summary: IntegritySummary,
    /// Whether a small thumbnail is committed and current.
    pub thumbnail_ready: bool,
    /// The metadata revision the projection columns were written from.
    pub active_metadata_revision: u32,
}

impl Object {
    /// Rejects a row the schema would accept but §5.1 and §12 forbid.
    ///
    /// The database enforces types and references; these are the rules that
    /// only this specification states, so they are checked before an insert
    /// rather than discovered by a reader later.
    pub fn check(&self) -> Result<()> {
        if self.state != ObjectState::Active
            && self.integrity_summary != IntegritySummary::Unverified
        {
            bail!(
                InvalidInput,
                "integrity_summary is meaningful only while state is ACTIVE"
            );
        }
        match self.media_kind {
            MediaClass::Image => {
                self.check_pixels()?;
                if self.duration_ms != 0 {
                    bail!(InvalidInput, "a still image carries no duration");
                }
            }
            MediaClass::Video => {
                if self.width > media::VIDEO_WIDTH_MAX || self.height > media::VIDEO_HEIGHT_MAX {
                    bail!(
                        ResourceLimitExceeded,
                        "the video exceeds the §12 track bound"
                    );
                }
                self.check_duration()?;
            }
            MediaClass::Audio => {
                if self.width != 0 || self.height != 0 {
                    bail!(InvalidInput, "an audio object carries no dimensions");
                }
                self.check_duration()?;
            }
            _ => {
                if self.width != 0 || self.height != 0 || self.duration_ms != 0 {
                    bail!(
                        InvalidInput,
                        "an opaque object carries no dimensions and no duration"
                    );
                }
            }
        }
        Ok(())
    }

    fn check_pixels(&self) -> Result<()> {
        if self.width > media::IMAGE_EDGE_MAX || self.height > media::IMAGE_EDGE_MAX {
            bail!(
                ResourceLimitExceeded,
                "the image exceeds the §12 edge bound"
            );
        }
        if u64::from(self.width) * u64::from(self.height) > media::IMAGE_AREA_MAX {
            bail!(
                ResourceLimitExceeded,
                "the image exceeds the §12 area bound"
            );
        }
        Ok(())
    }

    fn check_duration(&self) -> Result<()> {
        if self.duration_ms > media::DURATION_MS_MAX {
            bail!(
                ResourceLimitExceeded,
                "the duration exceeds the §12 four-hour bound"
            );
        }
        Ok(())
    }
}

/// One original or derived stream, `docs/format/CATALOG_SCHEMA_V1.md` §6.
///
/// No row here ever points at a temporary uncommitted container: the import
/// journal of §11 owns a transaction's temporary path until the rename, and
/// this row is written in the transaction that activates the object.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Stream {
    /// The stream identifier.
    pub stream_id: Id,
    /// The object this stream belongs to.
    pub object_id: Id,
    /// The stream kind.
    pub stream_kind: StreamKind,
    /// The stream revision.
    pub stream_revision: u32,
    /// The content revision a derived stream was generated from, zero for an
    /// original.
    pub source_content_revision: u32,
    /// The opaque store identifier of the committed container.
    pub container_path_id: Id,
    /// The container version.
    pub container_version: u16,
    /// The algorithm suite.
    pub suite_id: u16,
    /// The committed ciphertext size.
    pub ciphertext_size: u64,
    /// The authenticated plaintext size.
    pub plaintext_size: u64,
    /// The chunk size the container was written with.
    pub chunk_size: u32,
    /// When complete verification last succeeded, if it ever has.
    pub complete_verified_ms: Option<u64>,
    /// The final-commit commitment this stream is identified by.
    pub final_commitment: [u8; chur_core::limits::COMMITMENT_LEN],
}

impl Stream {
    /// Rejects a stream whose kind and revisions disagree, §15.4.
    ///
    /// `0x01` is the only kind whose `source_content_revision` is absent, and
    /// the encoding registry states that as a rule rather than a convention.
    pub fn check(&self) -> Result<()> {
        let original = self.stream_kind == StreamKind::Original;
        if original && self.source_content_revision != 0 {
            bail!(
                InvalidInput,
                "an original stream has no source content revision"
            );
        }
        if !original && self.source_content_revision == 0 {
            bail!(
                InvalidInput,
                "a derived stream names the content revision it was generated from"
            );
        }
        Ok(())
    }
}

/// One metadata revision, `docs/format/CATALOG_SCHEMA_V1.md` §8.
///
/// `record` is the sealed canonical revision and is what a backup exports. The
/// remaining fields are the queryable projection of the same bytes, written in
/// the transaction that activates the revision, which is what stops the two
/// from drifting.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MetadataRevision {
    /// The object this revision describes.
    pub object_id: Id,
    /// The revision number.
    pub revision: u32,
    /// Whether this revision is the active one.
    pub active: bool,
    /// The sealed canonical record.
    pub record: Vec<u8>,
    /// The filename the source reported, if any.
    pub original_filename: Option<String>,
    /// The user's caption, if any.
    pub caption: Option<String>,
    /// The validated IANA media type.
    pub content_type: String,
    /// Capture time from provider metadata, absent when it was not supplied or
    /// failed its range check, §8.1.
    pub capture_time_ms: Option<u64>,
    /// Pixel width, zero when the kind has none.
    pub width: u32,
    /// Pixel height, zero when the kind has none.
    pub height: u32,
    /// Duration in milliseconds, zero for a still.
    pub duration_ms: u64,
}

impl MetadataRevision {
    /// Rejects a revision outside the bounds of §12 and §21.
    pub fn check(&self) -> Result<()> {
        if self.revision == 0 {
            bail!(InvalidInput, "a metadata revision is numbered from one");
        }
        if self.revision > limits::METADATA_REVISIONS_MAX {
            bail!(
                ResourceLimitExceeded,
                "the metadata revision exceeds the §21 bound"
            );
        }
        if self.record.len() > media::METADATA_REVISION_MAX {
            bail!(
                ResourceLimitExceeded,
                "the metadata record exceeds the §12 bound"
            );
        }
        if self.content_type.len() > media::CONTENT_TYPE_MAX {
            bail!(
                ResourceLimitExceeded,
                "the content type exceeds the §6.1 bound"
            );
        }
        if !is_media_type(&self.content_type) {
            bail!(
                InvalidInput,
                "the content type is not a lowercase IANA type"
            );
        }
        for text in [&self.original_filename, &self.caption]
            .into_iter()
            .flatten()
        {
            if text.len() > media::METADATA_FIELD_VALUE_MAX {
                bail!(
                    ResourceLimitExceeded,
                    "a metadata field exceeds the §12 value bound"
                );
            }
        }
        Ok(())
    }

    /// The three columns §16.4 tokenizes, in the order the index declares them.
    #[must_use]
    pub fn search_columns<'a>(&'a self, tag_names: &'a str) -> [&'a str; 3] {
        [
            self.original_filename.as_deref().unwrap_or(""),
            self.caption.as_deref().unwrap_or(""),
            tag_names,
        ]
    }
}

/// Whether a string is a lowercase IANA media type of the shape §6.1 accepts.
///
/// The check is structural rather than a registry lookup: `docs/interop/
/// MEDIA_PIPELINE.md` §3 classifies the provider's type hint as untrusted, so
/// what matters is that the stored value cannot carry a path, a parameter, or a
/// control byte into a platform that will convert it to a UTType.
#[must_use]
pub fn is_media_type(value: &str) -> bool {
    let Some((kind, subtype)) = value.split_once('/') else {
        return false;
    };
    if kind.is_empty() || subtype.is_empty() {
        return false;
    }
    let token = |part: &str| {
        part.bytes().all(|byte| {
            byte.is_ascii_lowercase() || byte.is_ascii_digit() || b"!#$&^_.+-".contains(&byte)
        })
    };
    token(kind) && token(subtype)
}

/// One derived asset, `docs/format/CATALOG_SCHEMA_V1.md` §10.
///
/// The binding to `source_content_revision` is what stops a stale derivative
/// from being presented as current after the original is replaced.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DerivedAsset {
    /// The object this asset was generated from.
    pub object_id: Id,
    /// The asset kind.
    pub kind: StreamKind,
    /// The content revision the asset was generated from.
    pub source_content_revision: u32,
    /// The asset revision.
    pub asset_revision: u32,
    /// The generator profile, `docs/interop/MEDIA_PIPELINE.md` §11.
    pub generator_profile: u32,
    /// The stream holding the asset's container.
    pub stream_id: Id,
}

impl DerivedAsset {
    /// Rejects an asset whose kind is the original, §15.4.
    pub fn check(&self) -> Result<()> {
        if self.kind == StreamKind::Original {
            bail!(InvalidInput, "an original is a stream, not a derived asset");
        }
        if self.source_content_revision == 0 {
            bail!(
                InvalidInput,
                "a derived asset names the content revision it was generated from"
            );
        }
        Ok(())
    }
}

/// A logical album, `docs/format/CATALOG_SCHEMA_V1.md` §9.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Album {
    /// The album identifier.
    pub album_id: Id,
    /// The user's name for the album.
    pub name: String,
    /// When the album was created.
    pub created_ms: u64,
    /// The revision, advanced by a membership change.
    pub revision: u64,
}

impl Album {
    /// Rejects an album name outside the catalog bound.
    pub fn check(&self) -> Result<()> {
        check_label(&self.name, limits::ALBUM_NAME_MAX, "the album name")
    }
}

/// A tag, `docs/format/CATALOG_SCHEMA_V1.md` §9.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Tag {
    /// The tag identifier.
    pub tag_id: Id,
    /// The user's name for the tag.
    pub name: String,
    /// When the tag was created.
    pub created_ms: u64,
}

impl Tag {
    /// Rejects a tag name outside the catalog bound.
    pub fn check(&self) -> Result<()> {
        check_label(&self.name, limits::TAG_NAME_MAX, "the tag name")
    }
}

/// Rejects an empty label, one above its bound, or one carrying a control byte.
///
/// A control byte is refused because the label reaches a platform text view and
/// an FTS5 tokenizer, and neither has a reason to receive one.
fn check_label(value: &str, maximum: usize, subject: &'static str) -> Result<()> {
    if value.is_empty() {
        bail!(InvalidInput, "the label is empty");
    }
    if value.len() > maximum {
        let _ = subject;
        bail!(ResourceLimitExceeded, "the label exceeds its catalog bound");
    }
    if value.chars().any(char::is_control) {
        bail!(InvalidInput, "the label carries a control character");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::panic, clippy::expect_used)]

    use super::*;
    use chur_core::ChurStatus;
    use chur_crypto::random;

    fn object(kind: MediaClass) -> Object {
        Object {
            object_id: random::id().expect("id"),
            object_generation: 1,
            collection_id: random::id().expect("id"),
            primary_stream_id: random::id().expect("id"),
            media_kind: kind,
            capture_time_ms: 1_700_000_000_000,
            import_time_ms: 1_700_000_000_001,
            capture_time_substituted: false,
            plaintext_size: 4_096,
            width: 0,
            height: 0,
            duration_ms: 0,
            favorite: false,
            state: ObjectState::Active,
            integrity_summary: IntegritySummary::Unverified,
            thumbnail_ready: false,
            active_metadata_revision: 1,
        }
    }

    fn rejection(outcome: Result<()>) -> ChurStatus {
        let Err(error) = outcome else {
            panic!("a value the specification forbids was accepted");
        };
        error.status()
    }

    #[test]
    fn an_image_is_bounded_by_edge_and_area() {
        let mut row = object(MediaClass::Image);
        row.width = 4_000;
        row.height = 3_000;
        row.check().expect("an ordinary photograph");
        row.width = 16_385;
        assert_eq!(rejection(row.check()), ChurStatus::ResourceLimitExceeded);
        row.width = 16_384;
        row.height = 16_384;
        assert_eq!(rejection(row.check()), ChurStatus::ResourceLimitExceeded);
    }

    #[test]
    fn a_still_image_carries_no_duration() {
        let mut row = object(MediaClass::Image);
        row.duration_ms = 1;
        assert_eq!(rejection(row.check()), ChurStatus::InvalidInput);
    }

    #[test]
    fn a_video_is_bounded_by_track_size_and_duration() {
        let mut row = object(MediaClass::Video);
        row.width = 3_840;
        row.height = 2_160;
        row.duration_ms = 60_000;
        row.check().expect("an ordinary clip");
        row.width = 7_681;
        assert_eq!(rejection(row.check()), ChurStatus::ResourceLimitExceeded);
        row.width = 3_840;
        row.duration_ms = 14_400_001;
        assert_eq!(rejection(row.check()), ChurStatus::ResourceLimitExceeded);
    }

    #[test]
    fn an_integrity_summary_is_meaningful_only_while_active() {
        let mut row = object(MediaClass::Opaque);
        row.state = ObjectState::Deleting;
        row.integrity_summary = IntegritySummary::CompleteVerified;
        assert_eq!(rejection(row.check()), ChurStatus::InvalidInput);
        row.integrity_summary = IntegritySummary::Unverified;
        row.check().expect("a deleting row carries no verdict");
    }

    #[test]
    fn only_an_original_has_no_source_content_revision() {
        let base = Stream {
            stream_id: random::id().expect("id"),
            object_id: random::id().expect("id"),
            stream_kind: StreamKind::Original,
            stream_revision: 1,
            source_content_revision: 0,
            container_path_id: random::id().expect("id"),
            container_version: 1,
            suite_id: 1,
            ciphertext_size: 4_200,
            plaintext_size: 4_096,
            chunk_size: 262_144,
            complete_verified_ms: None,
            final_commitment: [0u8; 32],
        };
        base.check().expect("an original");
        let mut derived = base.clone();
        derived.stream_kind = StreamKind::ThumbnailSmall;
        assert_eq!(rejection(derived.check()), ChurStatus::InvalidInput);
        derived.source_content_revision = 1;
        derived.check().expect("a thumbnail of revision one");
        let mut original = base.clone();
        original.source_content_revision = 1;
        assert_eq!(rejection(original.check()), ChurStatus::InvalidInput);
    }

    #[test]
    fn a_media_type_is_a_lowercase_iana_type_and_nothing_else() {
        for good in ["image/jpeg", "video/mp4", "image/x-canon-cr2", "audio/aac"] {
            assert!(is_media_type(good), "{good} was refused");
        }
        for bad in [
            "Image/JPEG",
            "image",
            "image/",
            "/jpeg",
            "image/jpeg; charset=utf-8",
            "../../etc/passwd",
            "image/jpeg\n",
        ] {
            assert!(!is_media_type(bad), "{bad} was accepted");
        }
    }

    #[test]
    fn a_metadata_revision_is_bounded() {
        let base = MetadataRevision {
            object_id: random::id().expect("id"),
            revision: 1,
            active: true,
            record: vec![0u8; 128],
            original_filename: Some(String::from("holiday.jpg")),
            caption: None,
            content_type: String::from("image/jpeg"),
            capture_time_ms: Some(1_700_000_000_000),
            width: 4_000,
            height: 3_000,
            duration_ms: 0,
        };
        base.check().expect("an ordinary revision");
        let mut zero = base.clone();
        zero.revision = 0;
        assert_eq!(rejection(zero.check()), ChurStatus::InvalidInput);
        let mut large = base.clone();
        large.record = vec![0u8; 65_537];
        assert_eq!(rejection(large.check()), ChurStatus::ResourceLimitExceeded);
        let mut typed = base.clone();
        typed.content_type = String::from("image/jpeg; charset=utf-8");
        assert_eq!(rejection(typed.check()), ChurStatus::InvalidInput);
        let mut named = base;
        named.original_filename = Some("a".repeat(8_193));
        assert_eq!(rejection(named.check()), ChurStatus::ResourceLimitExceeded);
    }

    #[test]
    fn a_label_is_bounded_and_carries_no_control_character() {
        let album = |name: &str| Album {
            album_id: random::id().expect("id"),
            name: String::from(name),
            created_ms: 0,
            revision: 1,
        };
        album("Holiday").check().expect("an ordinary album");
        assert_eq!(rejection(album("").check()), ChurStatus::InvalidInput);
        assert_eq!(
            rejection(album("a\u{0}b").check()),
            ChurStatus::InvalidInput
        );
        assert_eq!(
            rejection(album(&"a".repeat(513)).check()),
            ChurStatus::ResourceLimitExceeded
        );
    }

    #[test]
    fn a_derived_asset_is_never_the_original() {
        let asset = DerivedAsset {
            object_id: random::id().expect("id"),
            kind: StreamKind::Original,
            source_content_revision: 1,
            asset_revision: 1,
            generator_profile: 1,
            stream_id: random::id().expect("id"),
        };
        assert_eq!(rejection(asset.check()), ChurStatus::InvalidInput);
        let mut thumbnail = asset;
        thumbnail.kind = StreamKind::ThumbnailSmall;
        thumbnail.check().expect("a thumbnail");
    }
}
