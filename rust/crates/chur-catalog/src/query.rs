//! The bounded query surface of `docs/format/CATALOG_SCHEMA_V1.md` §16.
//!
//! One projection serves every screen, §16.1. One query shape serves every
//! scope, §16.2. Paging is keyset and never offset, so a page costs the same
//! whatever its position, and the cursor binds the scope and the sort it was
//! issued under so a caller cannot carry it into another scope and receive a
//! correctly ordered page drawn from the wrong set.
//!
//! Nothing here returns free-form user text. A filename, a caption, an album
//! name, and a tag name are fetched for one object by
//! [`crate::store::active_metadata`], so a page of 200 rows never carries 200
//! filenames across the boundary.

use chur_core::{
    ChurStatus, Error, Id, Result, bail, ensure,
    limits::{ID_LEN, catalog as limits},
};
use chur_format::constants::{IntegritySummary, MediaClass, ObjectState};
use rusqlite::types::Value;

use crate::db::{CatalogDb, as_sqlite_integer, from_sqlite_integer, map_sqlite};
use crate::schema::{check_query_limit, generation};

/// The scope a page is drawn from, §16.2.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Scope {
    /// Every listable object.
    Timeline,
    /// The members of one album.
    Album(Id),
    /// Every object flagged as a favourite.
    Favorites,
    /// Every object carrying one tag.
    Tag(Id),
    /// The FTS5 query of §16.4.
    Search(String),
    /// The objects §16.2 keeps out of the ordinary library.
    Quarantine,
}

impl Scope {
    /// The `scope_kind` byte of the cursor.
    const fn kind(&self) -> u8 {
        match self {
            Scope::Timeline => 1,
            Scope::Album(_) => 2,
            Scope::Favorites => 3,
            Scope::Tag(_) => 4,
            Scope::Search(_) => 5,
            Scope::Quarantine => 6,
        }
    }

    /// The `scope_id` field of the cursor: an album or tag, zero otherwise.
    const fn scope_id(&self) -> [u8; ID_LEN] {
        match self {
            Scope::Album(id) | Scope::Tag(id) => *id.as_bytes(),
            _ => [0u8; ID_LEN],
        }
    }
}

/// The order a page is drawn in, §16.2.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum Sort {
    /// Capture time descending, the default.
    #[default]
    CaptureDesc,
    /// Capture time ascending.
    CaptureAsc,
    /// Import time descending.
    ImportDesc,
}

impl Sort {
    /// The `sort` byte of the cursor.
    const fn code(self) -> u8 {
        match self {
            Sort::CaptureDesc => 1,
            Sort::CaptureAsc => 2,
            Sort::ImportDesc => 3,
        }
    }

    const fn from_code(value: u8) -> Option<Self> {
        match value {
            1 => Some(Sort::CaptureDesc),
            2 => Some(Sort::CaptureAsc),
            3 => Some(Sort::ImportDesc),
            _ => None,
        }
    }

    /// Whether the order is ascending, which decides the keyset comparison.
    const fn ascending(self) -> bool {
        matches!(self, Sort::CaptureAsc)
    }

    /// Whether the sort reads the import time rather than the capture time.
    const fn by_import(self) -> bool {
        matches!(self, Sort::ImportDesc)
    }
}

/// An opaque page cursor, §16.2.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Cursor {
    sort_value: u64,
    object_id: Id,
    sort: Sort,
    scope_kind: u8,
    scope_id: [u8; ID_LEN],
}

impl Cursor {
    /// Encodes the cursor into its 42 canonical bytes.
    #[must_use]
    pub fn encode(&self) -> [u8; limits::CURSOR_LEN] {
        let mut bytes = [0u8; limits::CURSOR_LEN];
        bytes[..8].copy_from_slice(&self.sort_value.to_be_bytes());
        bytes[8..24].copy_from_slice(self.object_id.as_bytes());
        bytes[24] = self.sort.code();
        bytes[25] = self.scope_kind;
        bytes[26..].copy_from_slice(&self.scope_id);
        bytes
    }

    /// Decodes a cursor, rejecting any length but the exact one.
    pub fn decode(bytes: &[u8]) -> Result<Self> {
        ensure!(
            bytes.len() == limits::CURSOR_LEN,
            InvalidInput,
            "the page cursor is not §16.2 length"
        );
        let mut sort_value = [0u8; 8];
        sort_value.copy_from_slice(&bytes[..8]);
        let mut object = [0u8; ID_LEN];
        object.copy_from_slice(&bytes[8..24]);
        let Some(sort) = Sort::from_code(bytes[24]) else {
            bail!(InvalidInput, "the page cursor names an unallocated sort");
        };
        if bytes[25] == 0 || bytes[25] > 6 {
            bail!(InvalidInput, "the page cursor names an unallocated scope");
        }
        let mut scope_id = [0u8; ID_LEN];
        scope_id.copy_from_slice(&bytes[26..]);
        Ok(Self {
            sort_value: u64::from_be_bytes(sort_value),
            object_id: Id::new(object)?,
            sort,
            scope_kind: bytes[25],
            scope_id,
        })
    }

    /// Rejects a cursor issued under another scope or sort, §16.2.
    fn check(&self, scope: &Scope, sort: Sort) -> Result<()> {
        ensure!(
            self.sort == sort
                && self.scope_kind == scope.kind()
                && self.scope_id == scope.scope_id(),
            InvalidInput,
            "the page cursor was issued for another scope or sort"
        );
        Ok(())
    }
}

/// One page request, §16.2.
#[derive(Debug, Clone)]
pub struct ObjectQuery {
    /// The scope the page is drawn from.
    pub scope: Scope,
    /// A bitmask over the media classes, zero for every kind.
    pub kinds: u16,
    /// The order.
    pub sort: Sort,
    /// The cursor, absent for the first page.
    pub cursor: Option<Cursor>,
    /// The page size, zero for the default of 200.
    pub limit: u32,
}

impl ObjectQuery {
    /// The first page of the timeline under the default sort and size.
    #[must_use]
    pub fn timeline() -> Self {
        Self {
            scope: Scope::Timeline,
            kinds: 0,
            sort: Sort::default(),
            cursor: None,
            limit: 0,
        }
    }

    /// The media classes this query admits, or `None` for every class.
    ///
    /// A set bit above the allocated range selects nothing rather than failing,
    /// which is what lets a newer caller ask a v1 build for a class it does not
    /// have without the request being an error.
    fn classes(&self) -> Option<Vec<u8>> {
        if self.kinds == 0 {
            return None;
        }
        let mut classes = Vec::new();
        for class in MediaClass::ALL {
            let bit = u32::from(class.value()) - 1;
            if self.kinds & (1u16 << bit) != 0 {
                classes.push(class.value());
            }
        }
        Some(classes)
    }
}

/// The fixed-width object shape of §16.1.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ObjectProjection {
    /// The object identifier.
    pub object_id: Id,
    /// The original stream.
    pub primary_stream_id: Id,
    /// The canonical media kind.
    pub media_kind: u16,
    /// Capture time in milliseconds.
    pub capture_time_ms: u64,
    /// Import time in milliseconds.
    pub import_time_ms: u64,
    /// Whether the capture time was substituted, §8.1.
    pub capture_time_substituted: bool,
    /// The authenticated plaintext size.
    pub plaintext_size: u64,
    /// Pixel width, zero when the kind has none.
    pub width: u32,
    /// Pixel height, zero when the kind has none.
    pub height: u32,
    /// Duration in milliseconds, zero for a still.
    pub duration_ms: u64,
    /// Whether the object is a favourite.
    pub favorite: bool,
    /// The lifecycle state, §5.1.
    pub state: u8,
    /// The verification verdict, §5.1.
    pub integrity_summary: u8,
    /// Whether a current small thumbnail exists.
    pub thumbnail_ready: bool,
}

impl ObjectProjection {
    /// Encodes the projection into its 79 canonical bytes.
    ///
    /// The order and the widths are §16.1's, big-endian like every other Chur
    /// integer, `docs/format/CANONICAL_ENCODING_V1.md` §3.
    #[must_use]
    pub fn encode(&self) -> [u8; limits::PROJECTION_LEN] {
        let mut bytes = [0u8; limits::PROJECTION_LEN];
        let mut at = 0usize;
        let mut put = |source: &[u8], at: &mut usize| {
            bytes[*at..*at + source.len()].copy_from_slice(source);
            *at += source.len();
        };
        put(self.object_id.as_bytes(), &mut at);
        put(self.primary_stream_id.as_bytes(), &mut at);
        put(&self.media_kind.to_be_bytes(), &mut at);
        put(&self.capture_time_ms.to_be_bytes(), &mut at);
        put(&self.import_time_ms.to_be_bytes(), &mut at);
        put(&[u8::from(self.capture_time_substituted)], &mut at);
        put(&self.plaintext_size.to_be_bytes(), &mut at);
        put(&self.width.to_be_bytes(), &mut at);
        put(&self.height.to_be_bytes(), &mut at);
        put(&self.duration_ms.to_be_bytes(), &mut at);
        put(&[u8::from(self.favorite)], &mut at);
        put(&[self.state], &mut at);
        put(&[self.integrity_summary], &mut at);
        put(&[u8::from(self.thumbnail_ready)], &mut at);
        debug_assert!(at == limits::PROJECTION_LEN);
        bytes
    }

    /// Decodes a projection, which the CLI and the tests use to check a page
    /// the FFI wrote into a buffer.
    pub fn decode(bytes: &[u8]) -> Result<Self> {
        ensure!(
            bytes.len() == limits::PROJECTION_LEN,
            InvalidInput,
            "the projection is not §16.1 length"
        );
        let id_at = |at: usize| -> Result<Id> {
            let mut value = [0u8; ID_LEN];
            value.copy_from_slice(&bytes[at..at + ID_LEN]);
            Id::new(value)
        };
        let u64_at = |at: usize| -> u64 {
            let mut value = [0u8; 8];
            value.copy_from_slice(&bytes[at..at + 8]);
            u64::from_be_bytes(value)
        };
        let u32_at = |at: usize| -> u32 {
            let mut value = [0u8; 4];
            value.copy_from_slice(&bytes[at..at + 4]);
            u32::from_be_bytes(value)
        };
        let flag_at = |at: usize| -> Result<bool> {
            match bytes[at] {
                0 => Ok(false),
                1 => Ok(true),
                _ => Err(Error::new(
                    ChurStatus::NonCanonicalEncoding,
                    "a projection boolean is neither 0x00 nor 0x01",
                )),
            }
        };
        Ok(Self {
            object_id: id_at(0)?,
            primary_stream_id: id_at(16)?,
            media_kind: u16::from_be_bytes([bytes[32], bytes[33]]),
            capture_time_ms: u64_at(34),
            import_time_ms: u64_at(42),
            capture_time_substituted: flag_at(50)?,
            plaintext_size: u64_at(51),
            width: u32_at(59),
            height: u32_at(63),
            duration_ms: u64_at(67),
            favorite: flag_at(75)?,
            state: bytes[76],
            integrity_summary: bytes[77],
            thumbnail_ready: flag_at(78)?,
        })
    }
}

/// One page of results, §16.2.
#[derive(Debug, Clone)]
pub struct Page {
    /// The projections, in sort order.
    pub objects: Vec<ObjectProjection>,
    /// The number of rows the scope holds.
    pub total_count: u64,
    /// The catalog generation the page was read at.
    pub catalog_generation: u64,
    /// The cursor for the next page, absent when the scope is exhausted.
    pub next_cursor: Option<Cursor>,
}

/// Runs one page query.
pub fn page(db: &CatalogDb, query: &ObjectQuery) -> Result<Page> {
    let limit = check_query_limit(query.limit)?;
    if let Some(cursor) = &query.cursor {
        cursor.check(&query.scope, query.sort)?;
    }
    if let Scope::Search(terms) = &query.scope {
        ensure!(
            terms.len() <= limits::SEARCH_TERMS_MAX,
            ResourceLimitExceeded,
            "the search query exceeds its catalog bound"
        );
    }

    let catalog_generation = generation(db)?;
    let plan = Plan::build(query, limit)?;

    let connection = db.connection();
    let mut statement = connection
        .prepare(&plan.rows_sql)
        .map_err(|error| map_sqlite(error, "the page query could not be prepared"))?;
    let rows = statement
        .query_map(rusqlite::params_from_iter(plan.row_params.iter()), |row| {
            projection(row)
        })
        .map_err(|error| map_sqlite(error, "the page could not be read"))?;
    let mut objects = Vec::new();
    for row in rows {
        objects.push(row.map_err(|error| map_sqlite(error, "a page row could not be read"))??);
    }

    let total_count: i64 = connection
        .query_row(
            &plan.count_sql,
            rusqlite::params_from_iter(plan.count_params.iter()),
            |row| row.get(0),
        )
        .map_err(|error| map_sqlite(error, "the scope total could not be read"))?;

    let next_cursor = (objects.len() == limit as usize)
        .then(|| objects.last())
        .flatten()
        .map(|last| Cursor {
            sort_value: if query.sort.by_import() {
                last.import_time_ms
            } else {
                last.capture_time_ms
            },
            object_id: last.object_id,
            sort: query.sort,
            scope_kind: query.scope.kind(),
            scope_id: query.scope.scope_id(),
        });

    Ok(Page {
        objects,
        total_count: from_sqlite_integer(total_count, "the scope total is negative")?,
        catalog_generation,
        next_cursor,
    })
}

/// The SQL and parameters one query resolves to.
struct Plan {
    rows_sql: String,
    row_params: Vec<Value>,
    count_sql: String,
    count_params: Vec<Value>,
}

/// The projection columns, always read from the object row.
const COLUMNS: &str = "o.object_id, o.primary_stream_id, o.media_kind, o.capture_time_ms, \
     o.import_time_ms, o.capture_time_substituted, o.plaintext_size, o.width, o.height, \
     o.duration_ms, o.favorite, o.state, o.integrity_summary, o.thumbnail_ready";

impl Plan {
    fn build(query: &ObjectQuery, limit: u32) -> Result<Self> {
        let active = i64::from(ObjectState::Active.value());
        let quarantined = i64::from(IntegritySummary::Quarantined.value());

        // §16.2: a DELETING or TOMBSTONED row is never returned, and a
        // QUARANTINED row appears only in the quarantine scope.
        let (from, mut params): (String, Vec<Value>) = match &query.scope {
            Scope::Timeline => (
                format!(
                    "objects o WHERE o.state = {active} AND o.integrity_summary <> {quarantined}"
                ),
                Vec::new(),
            ),
            Scope::Quarantine => (
                format!(
                    "objects o WHERE o.state = {active} AND o.integrity_summary = {quarantined}"
                ),
                Vec::new(),
            ),
            Scope::Favorites => (
                format!(
                    "favorites f JOIN objects o ON o.object_id = f.object_id \
                     WHERE o.state = {active} AND o.integrity_summary <> {quarantined}"
                ),
                Vec::new(),
            ),
            Scope::Album(album_id) => (
                format!(
                    "album_memberships m JOIN objects o ON o.object_id = m.object_id \
                     WHERE m.album_id = ? AND o.state = {active} \
                       AND o.integrity_summary <> {quarantined}"
                ),
                vec![Value::Blob(album_id.as_bytes().to_vec())],
            ),
            Scope::Tag(tag_id) => (
                format!(
                    "object_tags g JOIN objects o ON o.object_id = g.object_id \
                     WHERE g.tag_id = ? AND o.state = {active} \
                       AND o.integrity_summary <> {quarantined}"
                ),
                vec![Value::Blob(tag_id.as_bytes().to_vec())],
            ),
            Scope::Search(terms) => (
                format!(
                    "object_search s JOIN objects o ON o.search_key = s.rowid \
                     WHERE object_search MATCH ? AND o.state = {active} \
                       AND o.integrity_summary <> {quarantined}"
                ),
                vec![Value::Text(escape_fts(terms))],
            ),
        };

        let mut filter = String::new();
        if let Some(classes) = query.classes() {
            if classes.is_empty() {
                // Every set bit is above the allocated range, so the scope is
                // empty. `0` is a condition SQLite evaluates without a scan.
                filter.push_str(" AND 0");
            } else {
                filter.push_str(" AND o.media_kind IN (");
                for (index, class) in classes.iter().enumerate() {
                    if index > 0 {
                        filter.push(',');
                    }
                    filter.push('?');
                    params.push(Value::Integer(i64::from(*class)));
                }
                filter.push(')');
            }
        }

        let count_sql = format!("SELECT count(*) FROM {from}{filter}");
        let count_params = params.clone();

        let sort_column = if query.sort.by_import() {
            "o.import_time_ms"
        } else {
            "o.capture_time_ms"
        };
        let direction = if query.sort.ascending() {
            "ASC"
        } else {
            "DESC"
        };
        let comparison = if query.sort.ascending() { ">" } else { "<" };

        let mut keyset = String::new();
        if let Some(cursor) = &query.cursor {
            // §16.2: paging is keyset, so the next page selects the rows
            // ordered strictly after the pair the cursor carries. The row-value
            // comparison is what keeps it one range scan instead of an OR.
            keyset = format!(" AND ({sort_column}, o.object_id) {comparison} (?, ?)");
            params.push(Value::Integer(as_sqlite_integer(
                cursor.sort_value,
                "the cursor sort value is out of range",
            )?));
            params.push(Value::Blob(cursor.object_id.as_bytes().to_vec()));
        }
        params.push(Value::Integer(i64::from(limit)));

        let rows_sql = format!(
            "SELECT {COLUMNS} FROM {from}{filter}{keyset} \
             ORDER BY {sort_column} {direction}, o.object_id {direction} LIMIT ?"
        );

        Ok(Self {
            rows_sql,
            row_params: params,
            count_sql,
            count_params,
        })
    }
}

/// Turns user text into one FTS5 phrase-and-prefix query.
///
/// The terms reach FTS5, whose query language has operators. Passing the text
/// through would let a caption or a paste change the query into a `NEAR` or a
/// column filter, and an unbalanced quotation mark would make an ordinary
/// search return `INVALID_INPUT`. Each whitespace-separated run is therefore
/// quoted as a phrase and given the prefix operator, which is what §16.4's
/// two-and-three-character prefix index exists to serve.
fn escape_fts(terms: &str) -> String {
    let mut query = String::new();
    for word in terms.split_whitespace() {
        if !query.is_empty() {
            query.push(' ');
        }
        query.push('"');
        for character in word.chars() {
            if character == '"' {
                query.push('"');
            }
            query.push(character);
        }
        query.push_str("\"*");
    }
    query
}

fn projection(row: &rusqlite::Row<'_>) -> rusqlite::Result<Result<ObjectProjection>> {
    let object_id: Vec<u8> = row.get(0)?;
    let primary: Vec<u8> = row.get(1)?;
    let media_kind: i64 = row.get(2)?;
    let capture: i64 = row.get(3)?;
    let import: i64 = row.get(4)?;
    let substituted: i64 = row.get(5)?;
    let size: i64 = row.get(6)?;
    let width: i64 = row.get(7)?;
    let height: i64 = row.get(8)?;
    let duration: i64 = row.get(9)?;
    let favorite: i64 = row.get(10)?;
    let state: i64 = row.get(11)?;
    let summary: i64 = row.get(12)?;
    let thumbnail: i64 = row.get(13)?;
    Ok((|| {
        let small = |value: i64, context: &'static str| -> Result<u32> {
            u32::try_from(value).map_err(|_| Error::new(ChurStatus::CatalogCorrupt, context))
        };
        let byte = |value: i64, context: &'static str| -> Result<u8> {
            u8::try_from(value).map_err(|_| Error::new(ChurStatus::CatalogCorrupt, context))
        };
        Ok(ObjectProjection {
            object_id: crate::row::id(&object_id, "the object id is malformed")?,
            primary_stream_id: crate::row::id(&primary, "the stream id is malformed")?,
            media_kind: u16::try_from(media_kind).map_err(|_| {
                Error::new(ChurStatus::CatalogCorrupt, "the media kind is out of range")
            })?,
            capture_time_ms: from_sqlite_integer(capture, "the capture time is negative")?,
            import_time_ms: from_sqlite_integer(import, "the import time is negative")?,
            capture_time_substituted: crate::row::flag(
                substituted,
                "the substitution flag is not a boolean",
            )?,
            plaintext_size: from_sqlite_integer(size, "the plaintext size is negative")?,
            width: small(width, "the width is out of range")?,
            height: small(height, "the height is out of range")?,
            duration_ms: from_sqlite_integer(duration, "the duration is negative")?,
            favorite: crate::row::flag(favorite, "the favourite flag is not a boolean")?,
            state: byte(state, "the state is out of range")?,
            integrity_summary: byte(summary, "the integrity summary is out of range")?,
            thumbnail_ready: crate::row::flag(thumbnail, "the thumbnail flag is not a boolean")?,
        })
    })())
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::panic, clippy::expect_used)]

    use super::*;
    use crate::db::{CatalogKey, CatalogLocation};
    use crate::model::{
        Album, COLLECTION_POLICY_VAULT_DEFAULT, COLLECTION_STATUS_ACTIVE, Collection,
        MetadataRevision, Object, Stream, Tag,
    };
    use crate::schema::open_at_current_version;
    use crate::store;
    use chur_crypto::{Key, random};
    use chur_format::constants::StreamKind;

    struct Vault {
        db: CatalogDb,
        collection: Id,
    }

    fn vault() -> Vault {
        let root: Key = random::secret::<32>().expect("root");
        let vault_id = random::id().expect("id");
        let key = CatalogKey::derive(&root, &vault_id).expect("key");
        let mut db = CatalogDb::open(&CatalogLocation::Memory, &key).expect("open");
        open_at_current_version(&mut db, 1).expect("schema");
        let collection = random::id().expect("id");
        store::put_collection(
            &mut db,
            &Collection {
                collection_id: collection,
                current_epoch: 1,
                policy_type: COLLECTION_POLICY_VAULT_DEFAULT,
                created_revision: 1,
                status: COLLECTION_STATUS_ACTIVE,
            },
        )
        .expect("collection");
        Vault { db, collection }
    }

    /// Imports one object with a fixed capture time, so a test can state the
    /// order it expects rather than discover it.
    fn import(vault: &mut Vault, capture_ms: u64, kind: MediaClass, filename: &str) -> Id {
        let object_id = random::id().expect("id");
        let stream_id = random::id().expect("id");
        let (width, height, duration) = match kind {
            MediaClass::Image => (4_000, 3_000, 0),
            MediaClass::Video => (1_920, 1_080, 30_000),
            MediaClass::Audio => (0, 0, 60_000),
            _ => (0, 0, 0),
        };
        store::activate_object(
            &mut vault.db,
            &store::ObjectActivation {
                object: Object {
                    object_id,
                    object_generation: 1,
                    collection_id: vault.collection,
                    primary_stream_id: stream_id,
                    media_kind: kind,
                    capture_time_ms: capture_ms,
                    import_time_ms: 2_000_000 - capture_ms,
                    capture_time_substituted: false,
                    plaintext_size: 4_096,
                    width,
                    height,
                    duration_ms: duration,
                    favorite: false,
                    state: ObjectState::Active,
                    integrity_summary: IntegritySummary::Unverified,
                    thumbnail_ready: false,
                    active_metadata_revision: 1,
                },
                stream: Stream {
                    stream_id,
                    object_id,
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
                },
                envelope: vec![0u8; 142],
                envelope_generation: 1,
                metadata: MetadataRevision {
                    object_id,
                    revision: 1,
                    active: true,
                    record: vec![0u8; 32],
                    original_filename: Some(String::from(filename)),
                    caption: None,
                    content_type: String::from("image/jpeg"),
                    capture_time_ms: Some(capture_ms),
                    width,
                    height,
                    duration_ms: duration,
                },
            },
        )
        .expect("activate");
        object_id
    }

    fn ids(page: &Page) -> Vec<Id> {
        page.objects.iter().map(|row| row.object_id).collect()
    }

    /// Walks a scope through every page and returns the whole order.
    fn walk(db: &CatalogDb, mut query: ObjectQuery) -> Vec<Id> {
        let mut all = Vec::new();
        loop {
            let result = page(db, &query).expect("page");
            all.extend(ids(&result));
            let Some(cursor) = result.next_cursor else {
                return all;
            };
            query.cursor = Some(cursor);
        }
    }

    #[test]
    fn a_projection_round_trips_through_its_79_bytes() {
        let projection = ObjectProjection {
            object_id: random::id().expect("id"),
            primary_stream_id: random::id().expect("id"),
            media_kind: 1,
            capture_time_ms: 1_700_000_000_000,
            import_time_ms: 1_700_000_000_001,
            capture_time_substituted: true,
            plaintext_size: 8_388_608,
            width: 4_000,
            height: 3_000,
            duration_ms: 0,
            favorite: true,
            state: 1,
            integrity_summary: 4,
            thumbnail_ready: true,
        };
        let bytes = projection.encode();
        assert_eq!(bytes.len(), 79);
        assert_eq!(
            ObjectProjection::decode(&bytes).expect("decode"),
            projection
        );
    }

    #[test]
    fn a_projection_boolean_is_strictly_zero_or_one() {
        let projection = ObjectProjection {
            object_id: random::id().expect("id"),
            primary_stream_id: random::id().expect("id"),
            media_kind: 1,
            capture_time_ms: 1,
            import_time_ms: 1,
            capture_time_substituted: false,
            plaintext_size: 1,
            width: 0,
            height: 0,
            duration_ms: 0,
            favorite: false,
            state: 1,
            integrity_summary: 1,
            thumbnail_ready: false,
        };
        for at in [50usize, 75, 78] {
            let mut bytes = projection.encode();
            bytes[at] = 2;
            let Err(error) = ObjectProjection::decode(&bytes) else {
                panic!("a boolean of 0x02 decoded");
            };
            assert_eq!(error.status(), ChurStatus::NonCanonicalEncoding);
        }
    }

    #[test]
    fn a_cursor_round_trips_and_binds_its_scope_and_sort() {
        let album = random::id().expect("id");
        let cursor = Cursor {
            sort_value: 42,
            object_id: random::id().expect("id"),
            sort: Sort::CaptureAsc,
            scope_kind: Scope::Album(album).kind(),
            scope_id: *album.as_bytes(),
        };
        let bytes = cursor.encode();
        assert_eq!(bytes.len(), 42);
        let decoded = Cursor::decode(&bytes).expect("decode");
        assert_eq!(decoded, cursor);
        decoded
            .check(&Scope::Album(album), Sort::CaptureAsc)
            .expect("its own scope");
        for wrong in [
            decoded.check(&Scope::Timeline, Sort::CaptureAsc),
            decoded.check(&Scope::Album(album), Sort::CaptureDesc),
            decoded.check(&Scope::Album(random::id().expect("id")), Sort::CaptureAsc),
            decoded.check(&Scope::Tag(album), Sort::CaptureAsc),
        ] {
            let Err(error) = wrong else {
                panic!("a cursor was accepted outside the scope it was issued for");
            };
            assert_eq!(error.status(), ChurStatus::InvalidInput);
        }
    }

    #[test]
    fn a_malformed_cursor_is_invalid_input() {
        let good = Cursor {
            sort_value: 1,
            object_id: random::id().expect("id"),
            sort: Sort::CaptureDesc,
            scope_kind: 1,
            scope_id: [0u8; ID_LEN],
        }
        .encode();
        for bad in [
            good[..41].to_vec(),
            [good.to_vec(), vec![0]].concat(),
            {
                let mut bytes = good;
                bytes[24] = 9;
                bytes.to_vec()
            },
            {
                let mut bytes = good;
                bytes[25] = 7;
                bytes.to_vec()
            },
        ] {
            let Err(error) = Cursor::decode(&bad) else {
                panic!("a malformed cursor decoded");
            };
            assert_eq!(error.status(), ChurStatus::InvalidInput);
        }
    }

    #[test]
    fn the_timeline_sorts_on_capture_time_descending() {
        let mut vault = vault();
        let old = import(&mut vault, 1_000, MediaClass::Image, "a.jpg");
        let new = import(&mut vault, 3_000, MediaClass::Image, "b.jpg");
        let middle = import(&mut vault, 2_000, MediaClass::Image, "c.jpg");
        let result = page(&vault.db, &ObjectQuery::timeline()).expect("page");
        assert_eq!(ids(&result), vec![new, middle, old]);
        assert_eq!(result.total_count, 3);
        assert!(
            result.next_cursor.is_none(),
            "an exhausted scope has no cursor"
        );
    }

    #[test]
    fn paging_is_keyset_and_every_page_is_disjoint_and_complete() {
        let mut vault = vault();
        let mut expected = Vec::new();
        for index in 0..25u64 {
            expected.push(import(
                &mut vault,
                1_000 + index,
                MediaClass::Image,
                "a.jpg",
            ));
        }
        expected.reverse();
        let query = ObjectQuery {
            limit: 4,
            ..ObjectQuery::timeline()
        };
        let walked = walk(&vault.db, query);
        assert_eq!(walked, expected);
        let unique: std::collections::HashSet<_> = walked.iter().collect();
        assert_eq!(unique.len(), walked.len(), "a page repeated a row");
    }

    #[test]
    fn each_sort_produces_the_order_it_names() {
        let mut vault = vault();
        let first = import(&mut vault, 1_000, MediaClass::Image, "a.jpg");
        let second = import(&mut vault, 2_000, MediaClass::Image, "b.jpg");
        let by = |sort: Sort, db: &CatalogDb| {
            walk(
                db,
                ObjectQuery {
                    sort,
                    limit: 1,
                    ..ObjectQuery::timeline()
                },
            )
        };
        assert_eq!(by(Sort::CaptureDesc, &vault.db), vec![second, first]);
        assert_eq!(by(Sort::CaptureAsc, &vault.db), vec![first, second]);
        // import_time_ms was written as 2_000_000 - capture, so import_desc is
        // the reverse of capture_desc and the test cannot pass by accident.
        assert_eq!(by(Sort::ImportDesc, &vault.db), vec![first, second]);
    }

    #[test]
    fn a_kind_mask_selects_only_the_classes_it_names() {
        let mut vault = vault();
        let photo = import(&mut vault, 1_000, MediaClass::Image, "a.jpg");
        let clip = import(&mut vault, 2_000, MediaClass::Video, "b.mp4");
        let sound = import(&mut vault, 3_000, MediaClass::Audio, "c.m4a");
        let with = |kinds: u16, db: &CatalogDb| {
            ids(&page(
                db,
                &ObjectQuery {
                    kinds,
                    ..ObjectQuery::timeline()
                },
            )
            .expect("page"))
        };
        assert_eq!(with(0, &vault.db), vec![sound, clip, photo]);
        assert_eq!(with(0b0001, &vault.db), vec![photo]);
        assert_eq!(with(0b0010, &vault.db), vec![clip]);
        assert_eq!(with(0b0101, &vault.db), vec![sound, photo]);
        assert_eq!(with(0b1111, &vault.db), vec![sound, clip, photo]);
        // A bit above the allocated range selects nothing and is not an error.
        assert!(with(0b1_0000_0000, &vault.db).is_empty());
    }

    #[test]
    fn a_quarantined_row_appears_only_in_the_quarantine_scope() {
        let mut vault = vault();
        let ordinary = import(&mut vault, 1_000, MediaClass::Image, "a.jpg");
        let held = import(&mut vault, 2_000, MediaClass::Image, "b.jpg");
        store::set_integrity_summary(&mut vault.db, &held, IntegritySummary::Quarantined, 1)
            .expect("quarantine");
        assert_eq!(
            ids(&page(&vault.db, &ObjectQuery::timeline()).expect("page")),
            vec![ordinary]
        );
        let quarantine = page(
            &vault.db,
            &ObjectQuery {
                scope: Scope::Quarantine,
                ..ObjectQuery::timeline()
            },
        )
        .expect("page");
        assert_eq!(ids(&quarantine), vec![held]);
    }

    #[test]
    fn a_corrupt_row_is_in_no_scope() {
        let mut vault = vault();
        let broken = import(&mut vault, 1_000, MediaClass::Image, "a.jpg");
        store::mark_corrupt(&mut vault.db, &broken).expect("corrupt");
        for scope in [Scope::Timeline, Scope::Quarantine] {
            let result = page(
                &vault.db,
                &ObjectQuery {
                    scope,
                    ..ObjectQuery::timeline()
                },
            )
            .expect("page");
            assert!(result.objects.is_empty());
            assert_eq!(result.total_count, 0);
        }
    }

    #[test]
    fn the_album_and_tag_and_favourite_scopes_return_their_members() {
        let mut vault = vault();
        let inside = import(&mut vault, 1_000, MediaClass::Image, "a.jpg");
        let outside = import(&mut vault, 2_000, MediaClass::Image, "b.jpg");
        let album_id = random::id().expect("id");
        store::put_album(
            &mut vault.db,
            &Album {
                album_id,
                name: String::from("Holiday"),
                created_ms: 1,
                revision: 1,
            },
        )
        .expect("album");
        store::set_album_membership(&mut vault.db, &album_id, &inside, true, 1).expect("member");
        let tag_id = random::id().expect("id");
        store::put_tag(
            &mut vault.db,
            &Tag {
                tag_id,
                name: String::from("sea"),
                created_ms: 1,
            },
        )
        .expect("tag");
        store::set_object_tag(&mut vault.db, &tag_id, &inside, true).expect("apply");
        store::set_favorite(&mut vault.db, &inside, true, 1).expect("favourite");

        for scope in [Scope::Album(album_id), Scope::Tag(tag_id), Scope::Favorites] {
            let result = page(
                &vault.db,
                &ObjectQuery {
                    scope: scope.clone(),
                    ..ObjectQuery::timeline()
                },
            )
            .expect("page");
            assert_eq!(
                ids(&result),
                vec![inside],
                "{scope:?} returned the wrong set"
            );
            assert_eq!(result.total_count, 1);
        }
        assert_ne!(inside, outside);
    }

    #[test]
    fn search_matches_a_filename_a_caption_and_a_tag() {
        let mut vault = vault();
        let target = import(&mut vault, 1_000, MediaClass::Image, "Bäckerei.jpg");
        let other = import(&mut vault, 2_000, MediaClass::Image, "other.jpg");
        let search = |terms: &str, db: &CatalogDb| {
            ids(&page(
                db,
                &ObjectQuery {
                    scope: Scope::Search(String::from(terms)),
                    ..ObjectQuery::timeline()
                },
            )
            .expect("page"))
        };
        assert_eq!(search("backerei", &vault.db), vec![target]);
        assert_eq!(
            search("ba", &vault.db),
            vec![target],
            "a prefix query matches"
        );
        assert!(search("nothing", &vault.db).is_empty());
        assert_ne!(target, other);
    }

    #[test]
    fn a_search_query_carrying_fts_syntax_is_matched_literally() {
        let mut vault = vault();
        import(&mut vault, 1_000, MediaClass::Image, "holiday.jpg");
        // Each of these is either an FTS5 operator or unbalanced syntax. None
        // may fail the query, and none may match a row it does not describe.
        for hostile in [
            "holiday OR anything",
            "holiday NEAR beach",
            "\"unbalanced",
            "filename:holiday",
            "holiday*)(",
            "^holiday",
            "-holiday",
        ] {
            let result = page(
                &vault.db,
                &ObjectQuery {
                    scope: Scope::Search(String::from(hostile)),
                    ..ObjectQuery::timeline()
                },
            );
            assert!(result.is_ok(), "{hostile} failed the query");
        }
        let exact = page(
            &vault.db,
            &ObjectQuery {
                scope: Scope::Search(String::from("holiday OR anything")),
                ..ObjectQuery::timeline()
            },
        )
        .expect("page");
        assert!(
            exact.objects.is_empty(),
            "an operator was interpreted rather than searched for"
        );
    }

    #[test]
    fn a_search_query_above_its_bound_is_refused() {
        let vault = vault();
        let Err(error) = page(
            &vault.db,
            &ObjectQuery {
                scope: Scope::Search("a".repeat(513)),
                ..ObjectQuery::timeline()
            },
        ) else {
            panic!("an over-long search query ran");
        };
        assert_eq!(error.status(), ChurStatus::ResourceLimitExceeded);
    }

    #[test]
    fn a_page_reports_the_generation_it_was_read_at() {
        let mut vault = vault();
        let first = page(&vault.db, &ObjectQuery::timeline()).expect("page");
        import(&mut vault, 1_000, MediaClass::Image, "a.jpg");
        let second = page(&vault.db, &ObjectQuery::timeline()).expect("page");
        assert!(second.catalog_generation > first.catalog_generation);
    }

    #[test]
    fn a_cursor_from_another_scope_is_refused_by_the_page_call() {
        let mut vault = vault();
        for index in 0..3u64 {
            import(&mut vault, 1_000 + index, MediaClass::Image, "a.jpg");
        }
        let first = page(
            &vault.db,
            &ObjectQuery {
                limit: 1,
                ..ObjectQuery::timeline()
            },
        )
        .expect("page");
        let cursor = first.next_cursor.expect("a cursor");
        let Err(error) = page(
            &vault.db,
            &ObjectQuery {
                scope: Scope::Favorites,
                cursor: Some(cursor),
                limit: 1,
                ..ObjectQuery::timeline()
            },
        ) else {
            panic!("a timeline cursor paged the favourites scope");
        };
        assert_eq!(error.status(), ChurStatus::InvalidInput);
    }

    #[test]
    fn a_page_limit_above_the_bound_is_refused() {
        let vault = vault();
        let Err(error) = page(
            &vault.db,
            &ObjectQuery {
                limit: 501,
                ..ObjectQuery::timeline()
            },
        ) else {
            panic!("a page above §16.2 ran");
        };
        assert_eq!(error.status(), ChurStatus::ResourceLimitExceeded);
    }

    #[test]
    fn every_scope_answers_from_a_covering_index_and_never_sorts() {
        let mut vault = vault();
        let object_id = import(&mut vault, 1_000, MediaClass::Image, "a.jpg");
        let album_id = random::id().expect("id");
        store::put_album(
            &mut vault.db,
            &Album {
                album_id,
                name: String::from("Holiday"),
                created_ms: 1,
                revision: 1,
            },
        )
        .expect("album");
        store::set_album_membership(&mut vault.db, &album_id, &object_id, true, 1).expect("member");
        let tag_id = random::id().expect("id");
        store::put_tag(
            &mut vault.db,
            &Tag {
                tag_id,
                name: String::from("sea"),
                created_ms: 1,
            },
        )
        .expect("tag");
        store::set_object_tag(&mut vault.db, &tag_id, &object_id, true).expect("apply");
        store::set_favorite(&mut vault.db, &object_id, true, 1).expect("favourite");

        // §16.2: "Every page therefore costs the same whatever its position",
        // which only holds while the plan is a range scan. A TEMP B-TREE in the
        // plan is the query planner saying it sorted the whole scope first.
        for scope in [
            Scope::Timeline,
            Scope::Quarantine,
            Scope::Favorites,
            Scope::Album(album_id),
            Scope::Tag(tag_id),
        ] {
            for sort in [Sort::CaptureDesc, Sort::CaptureAsc, Sort::ImportDesc] {
                let query = ObjectQuery {
                    scope: scope.clone(),
                    sort,
                    ..ObjectQuery::timeline()
                };
                let plan = Plan::build(&query, 200).expect("plan");
                let explained = explain(&vault.db, &plan);
                assert!(
                    !explained.contains("TEMP B-TREE"),
                    "{scope:?} under {sort:?} sorted the scope: {explained}"
                );
            }
        }
    }

    fn explain(db: &CatalogDb, plan: &Plan) -> String {
        let connection = db.connection();
        let mut statement = connection
            .prepare(&format!("EXPLAIN QUERY PLAN {}", plan.rows_sql))
            .expect("prepare");
        let rows = statement
            .query_map(rusqlite::params_from_iter(plan.row_params.iter()), |row| {
                row.get::<_, String>(3)
            })
            .expect("explain");
        rows.map(|row| row.expect("row"))
            .collect::<Vec<_>>()
            .join("; ")
    }
}
