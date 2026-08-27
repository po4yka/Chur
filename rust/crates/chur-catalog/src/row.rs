//! Decoding a catalog row back into a model type.
//!
//! Every function here fails closed. A column whose stored value is outside the
//! vocabulary the specification allocates is `CATALOG_CORRUPT` and never a
//! default, because a default would present a row the catalog cannot describe
//! as an ordinary one.
//!
//! The functions return `rusqlite::Result<Result<T>>`: the outer result is the
//! column read, the inner one is the validation. Flattening them would make a
//! validation failure look like a database failure at the call site.

use chur_core::{ChurStatus, Error, Id, Result, limits::ID_LEN};
use chur_format::constants::{IntegritySummary, MediaClass, ObjectState, StreamKind};
use rusqlite::Row;

use crate::db::from_sqlite_integer;
use crate::model::{MetadataRevision, Object, Stream};

/// Reads a 16-byte identifier from a column.
pub(crate) fn id(bytes: &[u8], context: &'static str) -> Result<Id> {
    let array: [u8; ID_LEN] = bytes
        .try_into()
        .map_err(|_| Error::new(ChurStatus::CatalogCorrupt, context))?;
    Id::new(array).map_err(|_| Error::new(ChurStatus::CatalogCorrupt, context))
}

/// Reads a boolean column, rejecting a value that is neither 0 nor 1.
pub(crate) fn flag(value: i64, context: &'static str) -> Result<bool> {
    match value {
        0 => Ok(false),
        1 => Ok(true),
        _ => Err(Error::new(ChurStatus::CatalogCorrupt, context)),
    }
}

fn small(value: i64, context: &'static str) -> Result<u32> {
    u32::try_from(value).map_err(|_| Error::new(ChurStatus::CatalogCorrupt, context))
}

fn discriminant(value: i64, context: &'static str) -> Result<u8> {
    u8::try_from(value).map_err(|_| Error::new(ChurStatus::CatalogCorrupt, context))
}

/// Decodes an object row.
pub(crate) fn object(object_id: &Id, row: &Row<'_>) -> rusqlite::Result<Result<Object>> {
    let generation: i64 = row.get(0)?;
    let collection: Vec<u8> = row.get(1)?;
    let primary: Vec<u8> = row.get(2)?;
    let kind: i64 = row.get(3)?;
    let capture: i64 = row.get(4)?;
    let import: i64 = row.get(5)?;
    let substituted: i64 = row.get(6)?;
    let size: i64 = row.get(7)?;
    let width: i64 = row.get(8)?;
    let height: i64 = row.get(9)?;
    let duration: i64 = row.get(10)?;
    let favorite: i64 = row.get(11)?;
    let state: i64 = row.get(12)?;
    let summary: i64 = row.get(13)?;
    let thumbnail: i64 = row.get(14)?;
    let revision: i64 = row.get(15)?;
    Ok((|| {
        Ok(Object {
            object_id: *object_id,
            object_generation: from_sqlite_integer(
                generation,
                "the object generation is negative",
            )?,
            collection_id: id(&collection, "the collection id is malformed")?,
            primary_stream_id: id(&primary, "the primary stream id is malformed")?,
            media_kind: MediaClass::from_value(discriminant(kind, "the media kind is malformed")?)
                .ok_or_else(|| {
                    Error::new(ChurStatus::CatalogCorrupt, "the media kind is unallocated")
                })?,
            capture_time_ms: from_sqlite_integer(capture, "the capture time is negative")?,
            import_time_ms: from_sqlite_integer(import, "the import time is negative")?,
            capture_time_substituted: flag(substituted, "the substitution flag is not a boolean")?,
            plaintext_size: from_sqlite_integer(size, "the plaintext size is negative")?,
            width: small(width, "the width is out of range")?,
            height: small(height, "the height is out of range")?,
            duration_ms: from_sqlite_integer(duration, "the duration is negative")?,
            favorite: flag(favorite, "the favourite flag is not a boolean")?,
            state: ObjectState::from_value(discriminant(state, "the state is malformed")?)
                .ok_or_else(|| {
                    Error::new(
                        ChurStatus::CatalogCorrupt,
                        "the object state is unallocated",
                    )
                })?,
            integrity_summary: IntegritySummary::from_value(discriminant(
                summary,
                "the integrity summary is malformed",
            )?)
            .ok_or_else(|| {
                Error::new(
                    ChurStatus::CatalogCorrupt,
                    "the integrity summary is unallocated",
                )
            })?,
            thumbnail_ready: flag(thumbnail, "the thumbnail flag is not a boolean")?,
            active_metadata_revision: small(revision, "the metadata revision is out of range")?,
        })
    })())
}

/// Decodes a stream row.
pub(crate) fn stream(object_id: &Id, row: &Row<'_>) -> rusqlite::Result<Result<Stream>> {
    let stream_id: Vec<u8> = row.get(0)?;
    let kind: i64 = row.get(1)?;
    let revision: i64 = row.get(2)?;
    let source: i64 = row.get(3)?;
    let path: Vec<u8> = row.get(4)?;
    let version: i64 = row.get(5)?;
    let suite: i64 = row.get(6)?;
    let ciphertext: i64 = row.get(7)?;
    let plaintext: i64 = row.get(8)?;
    let chunk: i64 = row.get(9)?;
    let verified: Option<i64> = row.get(10)?;
    let commitment: Vec<u8> = row.get(11)?;
    Ok((|| {
        let final_commitment: [u8; chur_core::limits::COMMITMENT_LEN] = commitment
            .as_slice()
            .try_into()
            .map_err(|_| Error::new(ChurStatus::CatalogCorrupt, "the commitment is malformed"))?;
        Ok(Stream {
            stream_id: id(&stream_id, "the stream id is malformed")?,
            object_id: *object_id,
            stream_kind: StreamKind::from_value(discriminant(
                kind,
                "the stream kind is malformed",
            )?)
            .ok_or_else(|| {
                Error::new(ChurStatus::CatalogCorrupt, "the stream kind is unallocated")
            })?,
            stream_revision: small(revision, "the stream revision is out of range")?,
            source_content_revision: small(source, "the source revision is out of range")?,
            container_path_id: id(&path, "the container path id is malformed")?,
            container_version: u16::try_from(version).map_err(|_| {
                Error::new(
                    ChurStatus::CatalogCorrupt,
                    "the container version is out of range",
                )
            })?,
            suite_id: u16::try_from(suite).map_err(|_| {
                Error::new(ChurStatus::CatalogCorrupt, "the suite id is out of range")
            })?,
            ciphertext_size: from_sqlite_integer(ciphertext, "the ciphertext size is negative")?,
            plaintext_size: from_sqlite_integer(plaintext, "the plaintext size is negative")?,
            chunk_size: small(chunk, "the chunk size is out of range")?,
            complete_verified_ms: verified
                .map(|value| from_sqlite_integer(value, "the verification time is negative"))
                .transpose()?,
            final_commitment,
        })
    })())
}

/// Decodes a metadata revision row.
pub(crate) fn metadata(
    object_id: &Id,
    row: &Row<'_>,
) -> rusqlite::Result<Result<MetadataRevision>> {
    let revision: i64 = row.get(0)?;
    let record: Vec<u8> = row.get(1)?;
    let filename: Option<String> = row.get(2)?;
    let caption: Option<String> = row.get(3)?;
    let content_type: String = row.get(4)?;
    let capture: Option<i64> = row.get(5)?;
    let width: i64 = row.get(6)?;
    let height: i64 = row.get(7)?;
    let duration: i64 = row.get(8)?;
    Ok((|| {
        Ok(MetadataRevision {
            object_id: *object_id,
            revision: small(revision, "the metadata revision is out of range")?,
            active: true,
            record,
            original_filename: filename,
            caption,
            content_type,
            capture_time_ms: capture
                .map(|value| from_sqlite_integer(value, "the capture time is negative"))
                .transpose()?,
            width: small(width, "the width is out of range")?,
            height: small(height, "the height is out of range")?,
            duration_ms: from_sqlite_integer(duration, "the duration is negative")?,
        })
    })())
}
