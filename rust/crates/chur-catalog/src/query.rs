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
    Search,
    /// The objects §16.2 keeps out of the ordinary library.
    Quarantine,
    /// Recoverable objects awaiting purge.
    Trash,
}

impl Scope {
    /// The `scope_kind` byte of the cursor.
    const fn kind(&self) -> u8 {
        match self {
            Scope::Timeline => 1,
            Scope::Album(_) => 2,
            Scope::Favorites => 3,
            Scope::Tag(_) => 4,
            Scope::Search => 5,
            Scope::Quarantine => 6,
            Scope::Trash => 7,
        }
    }

    /// The scope identity included in the cursor context: album/tag, zero otherwise.
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
    /// The order explicitly arranged inside an album.
    AlbumManual,
}

impl Sort {
    /// The `sort` byte of the cursor.
    const fn code(self) -> u8 {
        match self {
            Sort::CaptureDesc => 1,
            Sort::CaptureAsc => 2,
            Sort::ImportDesc => 3,
            Sort::AlbumManual => 4,
        }
    }

    const fn from_code(value: u8) -> Option<Self> {
        match value {
            1 => Some(Sort::CaptureDesc),
            2 => Some(Sort::CaptureAsc),
            3 => Some(Sort::ImportDesc),
            4 => Some(Sort::AlbumManual),
            _ => None,
        }
    }

    /// Whether the order is ascending, which decides the keyset comparison.
    const fn ascending(self) -> bool {
        matches!(self, Sort::CaptureAsc | Sort::AlbumManual)
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
    query_key: [u8; ID_LEN],
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
        bytes[26..].copy_from_slice(&self.query_key);
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
        if bytes[25] == 0 || bytes[25] > 7 {
            bail!(InvalidInput, "the page cursor names an unallocated scope");
        }
        let mut query_key = [0u8; ID_LEN];
        query_key.copy_from_slice(&bytes[26..]);
        Ok(Self {
            sort_value: u64::from_be_bytes(sort_value),
            object_id: Id::new(object)?,
            sort,
            scope_kind: bytes[25],
            query_key,
        })
    }

    /// Rejects a cursor issued for another scope, sort or filter, §16.2.
    fn check(&self, query: &ObjectQuery) -> Result<()> {
        ensure!(
            self.sort == query.sort
                && self.scope_kind == query.scope.kind()
                && self.query_key == query.context_key(),
            InvalidInput,
            "the page cursor was issued for another query"
        );
        Ok(())
    }
}

/// One page request, §16.2.
#[derive(Debug, Clone)]
pub struct ObjectQuery {
    /// The scope the page is drawn from.
    pub scope: Scope,
    /// Optional literal FTS text intersected with this scope before paging.
    pub terms: Option<String>,
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
            terms: None,
            kinds: 0,
            sort: Sort::default(),
            cursor: None,
            limit: 0,
        }
    }

    fn search_terms(&self) -> Option<&str> {
        self.terms
            .as_deref()
            .map(str::trim)
            .filter(|text| !text.is_empty())
    }

    /// An ephemeral cursor binds the complete filter, without carrying its text.
    fn context_key(&self) -> [u8; ID_LEN] {
        let mut digest = chur_crypto::sha256::ResourceSha256::default();
        digest.update(b"chur-page-query-v1\0");
        digest.update(&[self.scope.kind()]);
        digest.update(&self.scope.scope_id());
        digest.update(&self.kinds.to_be_bytes());
        if let Some(terms) = self.search_terms() {
            digest.update(escape_fts(terms).as_bytes());
        }
        let mut key = [0; ID_LEN];
        key.copy_from_slice(&digest.finish()[..ID_LEN]);
        key
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
    ensure!(
        query.sort != Sort::AlbumManual || matches!(query.scope, Scope::Album(_)),
        InvalidInput,
        "manual order is available only inside an album"
    );
    if let Some(terms) = &query.terms {
        ensure!(
            terms.len() <= limits::SEARCH_TERMS_MAX,
            ResourceLimitExceeded,
            "the search query exceeds its catalog bound"
        );
    }
    ensure!(
        !matches!(query.scope, Scope::Search) || query.search_terms().is_some(),
        InvalidInput,
        "the search terms name nothing to match"
    );
    ensure!(
        !matches!(query.scope, Scope::Trash) || query.search_terms().is_none(),
        InvalidInput,
        "Trash has no retained text-search index"
    );
    if let Some(cursor) = &query.cursor {
        cursor.check(query)?;
    }

    let catalog_generation = generation(db)?;
    let plan = Plan::build(query, limit)?;

    let connection = db.connection();
    let mut statement = connection
        .prepare(&plan.rows_sql)
        .map_err(|error| map_sqlite(error, "the page query could not be prepared"))?;
    let rows = statement
        .query_map(rusqlite::params_from_iter(plan.row_params.iter()), |row| {
            Ok((projection(row)?, row.get::<_, i64>(14)?))
        })
        .map_err(|error| map_sqlite(error, "the page could not be read"))?;
    let mut objects = Vec::new();
    for row in rows {
        let (object, sort_value) =
            row.map_err(|error| map_sqlite(error, "a page row could not be read"))?;
        objects.push((
            object?,
            from_sqlite_integer(sort_value, "the sort value is negative")?,
        ));
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
        .map(|(last, sort_value)| Cursor {
            sort_value: *sort_value,
            object_id: last.object_id,
            sort: query.sort,
            scope_kind: query.scope.kind(),
            query_key: query.context_key(),
        });

    Ok(Page {
        objects: objects.into_iter().map(|(object, _)| object).collect(),
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
        let trashed = i64::from(ObjectState::Trashed.value());
        let quarantined = i64::from(IntegritySummary::Quarantined.value());

        // §16.2: a DELETING or TOMBSTONED row is never returned, and a
        // QUARANTINED row appears only in the quarantine scope.
        //
        // §16.3: under the capture sort the scope table drives the join, so
        // the duplicated-sort-key index serves the page as one range scan.
        // CROSS JOIN fixes that join order; a plain JOIN lets the planner
        // drive from the objects table and sort the scope instead. Under the
        // import sort the objects table must drive, so the order stays the
        // planner's choice.
        let scope_join = if query.sort.by_import() {
            "JOIN"
        } else {
            "CROSS JOIN"
        };
        let (from, mut params): (String, Vec<Value>) = match &query.scope {
            Scope::Timeline | Scope::Search => (
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
            Scope::Trash => (
                format!(
                    "trash_entries t CROSS JOIN objects o ON o.object_id = t.object_id \
                    WHERE o.state = {trashed}"
                ),
                Vec::new(),
            ),
            Scope::Favorites => (
                format!(
                    "favorites f {scope_join} objects o ON o.object_id = f.object_id \
                      WHERE o.state = {active} AND o.integrity_summary <> {quarantined}"
                ),
                Vec::new(),
            ),
            Scope::Album(album_id) => (
                format!(
                    "album_memberships m {scope_join} objects o ON o.object_id = m.object_id \
                      WHERE m.album_id = ? AND o.state = {active} \
                        AND o.integrity_summary <> {quarantined}"
                ),
                vec![Value::Blob(album_id.as_bytes().to_vec())],
            ),
            Scope::Tag(tag_id) => (
                format!(
                    "object_tags g {scope_join} objects o ON o.object_id = g.object_id \
                      WHERE g.tag_id = ? AND o.state = {active} \
                        AND o.integrity_summary <> {quarantined}"
                ),
                vec![Value::Blob(tag_id.as_bytes().to_vec())],
            ),
        };

        let mut filter = String::new();
        if let Some(terms) = query.search_terms() {
            filter.push_str(" AND o.search_key IN (SELECT rowid FROM object_search WHERE object_search MATCH ?)");
            params.push(Value::Text(escape_fts(terms)));
        }

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

        // §16.3: the album-membership, favourite, and tag rows duplicate
        // capture_time_ms so a scope page orders by its own table's columns,
        // and the duplicated-sort-key index serves the page as one range
        // scan. Ordering by the objects-table columns would sort the join
        // instead, so none of the three indexes could serve the query. The
        // tiebreak names the driving table's object_id, the index's last
        // column, which the join equates with the object row's. The import
        // sort has no duplicated scope column and keeps the objects ones.
        let (sort_column, tiebreak_column) = if query.sort == Sort::AlbumManual {
            ("m.sort_position", "m.object_id")
        } else if query.sort.by_import() {
            ("o.import_time_ms", "o.object_id")
        } else {
            match &query.scope {
                Scope::Album(_) => ("m.capture_time_ms", "m.object_id"),
                Scope::Favorites => ("f.capture_time_ms", "f.object_id"),
                Scope::Tag(_) => ("g.capture_time_ms", "g.object_id"),
                Scope::Trash => ("t.deleted_ms", "t.object_id"),
                Scope::Timeline | Scope::Quarantine | Scope::Search => {
                    ("o.capture_time_ms", "o.object_id")
                }
            }
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
            keyset = format!(" AND ({sort_column}, {tiebreak_column}) {comparison} (?, ?)");
            params.push(Value::Integer(as_sqlite_integer(
                cursor.sort_value,
                "the cursor sort value is out of range",
            )?));
            params.push(Value::Blob(cursor.object_id.as_bytes().to_vec()));
        }
        params.push(Value::Integer(i64::from(limit)));

        let rows_sql = format!(
            "SELECT {COLUMNS}, {sort_column} FROM {from}{filter}{keyset} \
             ORDER BY {sort_column} {direction}, {tiebreak_column} {direction} LIMIT ?"
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
    use chur_crypto::{Key, Nonce, random};
    use chur_format::constants::StreamKind;
    use chur_format::envelope::ObjectKeyEnvelope;

    struct Vault {
        db: CatalogDb,
        collection: Id,
    }

    fn envelope(collection_id: Id, object_id: Id) -> Vec<u8> {
        ObjectKeyEnvelope::seal(
            &random::secret::<32>().expect("collection key"),
            random::id().expect("vault id"),
            collection_id,
            1,
            object_id,
            1,
            Nonce::random().expect("nonce"),
            &random::secret::<32>().expect("object key"),
        )
        .expect("object envelope")
        .encode()
    }

    fn vault() -> Vault {
        vault_at(None)
    }

    fn vault_at(path: Option<&std::path::Path>) -> Vault {
        let root: Key = random::secret::<32>().expect("root");
        let vault_id = random::id().expect("id");
        let key = CatalogKey::derive(&root, &vault_id).expect("key");
        let location = path.map_or(CatalogLocation::Memory, CatalogLocation::File);
        let mut db = CatalogDb::open(&location, &key).expect("open");
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
                envelope: envelope(vault.collection, object_id),
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
        let query = ObjectQuery {
            scope: Scope::Album(album),
            sort: Sort::CaptureAsc,
            ..ObjectQuery::timeline()
        };
        let cursor = Cursor {
            sort_value: 42,
            object_id: random::id().expect("id"),
            sort: query.sort,
            scope_kind: query.scope.kind(),
            query_key: query.context_key(),
        };
        let bytes = cursor.encode();
        assert_eq!(bytes.len(), 42);
        let decoded = Cursor::decode(&bytes).expect("decode");
        assert_eq!(decoded, cursor);
        decoded.check(&query).expect("its own query");
        for wrong in [
            ObjectQuery {
                scope: Scope::Timeline,
                ..query.clone()
            },
            ObjectQuery {
                sort: Sort::CaptureDesc,
                ..query.clone()
            },
            ObjectQuery {
                scope: Scope::Album(random::id().expect("id")),
                ..query.clone()
            },
            ObjectQuery {
                scope: Scope::Tag(album),
                ..query.clone()
            },
            ObjectQuery {
                kinds: 1,
                ..query.clone()
            },
            ObjectQuery {
                terms: Some("different".into()),
                ..query.clone()
            },
        ] {
            let error = decoded.check(&wrong).expect_err("another query is refused");
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
            query_key: [0u8; ID_LEN],
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
                bytes[25] = 8;
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
    fn manual_album_order_pages_after_rearranging_members() {
        let mut vault = vault();
        let first = import(&mut vault, 1_000, MediaClass::Image, "a.jpg");
        let second = import(&mut vault, 2_000, MediaClass::Image, "b.jpg");
        let third = import(&mut vault, 3_000, MediaClass::Image, "c.jpg");
        let album = random::id().expect("album");
        store::put_album(
            &mut vault.db,
            &crate::model::Album {
                album_id: album,
                name: "Trip".to_owned(),
                created_ms: 1,
                revision: 1,
            },
        )
        .expect("album");
        for id in [first, second, third] {
            store::set_album_membership(&mut vault.db, &album, &id, true, 1).expect("member");
        }
        store::move_album_member(&mut vault.db, &album, &third, Some(&first)).expect("reorder");
        let query = ObjectQuery {
            scope: Scope::Album(album),
            sort: Sort::AlbumManual,
            terms: None,
            kinds: 0,
            cursor: None,
            limit: 1,
        };
        assert_eq!(walk(&vault.db, query), vec![third, first, second]);
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
    fn scoped_text_is_intersected_before_album_paging_and_counting() {
        let mut vault = vault();
        let first = import(&mut vault, 1_000, MediaClass::Image, "needle first.jpg");
        let hidden = import(&mut vault, 2_000, MediaClass::Image, "other.jpg");
        let last = import(&mut vault, 3_000, MediaClass::Image, "needle last.jpg");
        let outside = import(&mut vault, 4_000, MediaClass::Image, "needle outside.jpg");
        let album = random::id().expect("album");
        store::put_album(
            &mut vault.db,
            &Album {
                album_id: album,
                name: "Scoped".into(),
                created_ms: 1,
                revision: 1,
            },
        )
        .expect("album");
        for id in [first, hidden, last] {
            store::set_album_membership(&mut vault.db, &album, &id, true, 1).expect("member");
        }
        let request = ObjectQuery {
            scope: Scope::Album(album),
            terms: Some("needle".into()),
            sort: Sort::AlbumManual,
            limit: 1,
            ..ObjectQuery::timeline()
        };
        let first_page = page(&vault.db, &request).expect("first page");
        assert_eq!(first_page.total_count, 2);
        assert_eq!(ids(&first_page), vec![first]);
        assert_eq!(walk(&vault.db, request.clone()), vec![first, last]);
        assert!(!walk(&vault.db, request.clone()).contains(&outside));
        let cursor = first_page.next_cursor.expect("next page");
        for changed in [
            ObjectQuery {
                terms: Some("other".into()),
                ..request.clone()
            },
            ObjectQuery {
                terms: None,
                ..request.clone()
            },
            ObjectQuery {
                kinds: 1,
                ..request.clone()
            },
        ] {
            let error = page(
                &vault.db,
                &ObjectQuery {
                    cursor: Some(cursor),
                    ..changed
                },
            )
            .expect_err("a different text/filter cannot reuse a cursor");
            assert_eq!(error.status(), ChurStatus::InvalidInput);
        }
        assert_eq!(
            walk(
                &vault.db,
                ObjectQuery {
                    terms: None,
                    ..request
                }
            ),
            vec![first, hidden, last]
        );
        let global = page(
            &vault.db,
            &ObjectQuery {
                terms: Some("needle".into()),
                ..ObjectQuery::timeline()
            },
        )
        .expect("filtered library");
        assert_eq!(global.total_count, 3);
    }

    #[test]
    fn text_filters_keep_special_scope_lifecycle_boundaries() {
        let mut vault = vault();
        let member = import(&mut vault, 1_000, MediaClass::Image, "needle member.jpg");
        let ordinary = import(&mut vault, 2_000, MediaClass::Image, "needle ordinary.jpg");
        let held = import(
            &mut vault,
            3_000,
            MediaClass::Image,
            "needle quarantine.jpg",
        );
        let trashed = import(&mut vault, 4_000, MediaClass::Image, "needle trash.jpg");
        store::set_integrity_summary(&mut vault.db, &held, IntegritySummary::Quarantined, 1)
            .expect("quarantine");
        crate::trash::set(&mut vault.db, &[trashed], false, 1).expect("trash");
        store::set_favorite(&mut vault.db, &member, true, 1).expect("favorite");
        let tag = random::id().expect("tag");
        store::put_tag(
            &mut vault.db,
            &Tag {
                tag_id: tag,
                name: "Scope".into(),
                created_ms: 1,
            },
        )
        .expect("tag");
        store::set_object_tag(&mut vault.db, &tag, &member, true).expect("tagged");
        for (scope, expected) in [
            (Scope::Timeline, vec![ordinary, member]),
            (Scope::Favorites, vec![member]),
            (Scope::Tag(tag), vec![member]),
            (Scope::Quarantine, vec![held]),
        ] {
            let query = ObjectQuery {
                scope,
                terms: Some("needle".into()),
                ..ObjectQuery::timeline()
            };
            let result = page(&vault.db, &query).expect("scoped text");
            assert_eq!(ids(&result), expected, "{:?}", query.scope);
            assert_eq!(result.total_count, expected.len() as u64);
        }
        let trash = ObjectQuery {
            scope: Scope::Trash,
            ..ObjectQuery::timeline()
        };
        assert_eq!(
            ids(&page(&vault.db, &trash).expect("unfiltered trash")),
            vec![trashed]
        );
        let error = page(
            &vault.db,
            &ObjectQuery {
                terms: Some("needle".into()),
                ..trash
            },
        )
        .expect_err("Trash erased its text index instead of retaining private text for search");
        assert_eq!(error.status(), ChurStatus::InvalidInput);
    }

    #[test]
    fn scoped_text_validates_its_bound_and_treats_operators_literally() {
        let mut vault = vault();
        import(&mut vault, 1_000, MediaClass::Image, "needle.jpg");
        for text in ["needle OR anything", "\"unbalanced", "filename:needle"] {
            let result = page(
                &vault.db,
                &ObjectQuery {
                    terms: Some(text.into()),
                    ..ObjectQuery::timeline()
                },
            )
            .expect("literal FTS text");
            assert!(result.objects.is_empty());
        }
        let error = page(
            &vault.db,
            &ObjectQuery {
                terms: Some("a".repeat(513)),
                ..ObjectQuery::timeline()
            },
        )
        .expect_err("bounded scoped query");
        assert_eq!(error.status(), ChurStatus::ResourceLimitExceeded);
        assert_eq!(
            page(
                &vault.db,
                &ObjectQuery {
                    terms: Some(" \t".into()),
                    ..ObjectQuery::timeline()
                }
            )
            .expect("blank clears filter")
            .total_count,
            1
        );
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
                    scope: Scope::Search,
                    terms: Some(String::from(terms)),
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
                    scope: Scope::Search,
                    terms: Some(String::from(hostile)),
                    ..ObjectQuery::timeline()
                },
            );
            assert!(result.is_ok(), "{hostile} failed the query");
        }
        let exact = page(
            &vault.db,
            &ObjectQuery {
                scope: Scope::Search,
                terms: Some(String::from("holiday OR anything")),
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
                scope: Scope::Search,
                terms: Some("a".repeat(513)),
                ..ObjectQuery::timeline()
            },
        ) else {
            panic!("an over-long search query ran");
        };
        assert_eq!(error.status(), ChurStatus::ResourceLimitExceeded);
    }

    #[test]
    fn an_empty_search_term_is_refused_as_invalid_input() {
        let vault = vault();
        for terms in ["", "   ", " \t\n "] {
            let Err(error) = page(
                &vault.db,
                &ObjectQuery {
                    scope: Scope::Search,
                    terms: Some(String::from(terms)),
                    ..ObjectQuery::timeline()
                },
            ) else {
                panic!("an empty search term {terms:?} ran an FTS5 MATCH query");
            };
            assert_eq!(error.status(), ChurStatus::InvalidInput, "{terms:?}");
        }
    }

    #[test]
    fn a_capture_page_in_the_joined_scopes_orders_by_the_duplicated_column() {
        let vault = vault();
        let scope_id = Id::new([7; 16]).expect("id");
        // §16.3: the album-membership, favourite, and tag rows duplicate
        // capture_time_ms so a scope page is one range scan over the
        // duplicated-sort-key index. Ordering by the objects-table column
        // would sort the join instead, and none of the three indexes could
        // serve the page.
        for (query, column, table) in [
            (
                ObjectQuery {
                    scope: Scope::Album(scope_id),
                    ..ObjectQuery::timeline()
                },
                "m.capture_time_ms",
                "the album scope",
            ),
            (
                ObjectQuery {
                    scope: Scope::Favorites,
                    ..ObjectQuery::timeline()
                },
                "f.capture_time_ms",
                "the favourite scope",
            ),
            (
                ObjectQuery {
                    scope: Scope::Tag(scope_id),
                    ..ObjectQuery::timeline()
                },
                "g.capture_time_ms",
                "the tag scope",
            ),
        ] {
            let plan = Plan::build(&query, 60).expect("plan");
            assert!(
                plan.rows_sql.contains(&format!("ORDER BY {column} DESC")),
                "{} ordered by the objects column: {}",
                table,
                plan.rows_sql
            );
            let explained: Vec<String> = vault
                .db
                .connection()
                .prepare(&format!("EXPLAIN QUERY PLAN {}", plan.rows_sql))
                .and_then(|mut statement| {
                    statement
                        .query_map(rusqlite::params_from_iter(plan.row_params.iter()), |row| {
                            row.get::<_, String>(3)
                        })?
                        .collect()
                })
                .expect("explain");
            assert!(
                explained.iter().all(|line| !line.contains("TEMP B-TREE")),
                "{table} sorts instead of scanning its index: {explained:?}"
            );
        }
        // §16.3 duplicates only capture_time_ms into the scope rows, so the
        // import sort keeps the objects column.
        let plan = Plan::build(
            &ObjectQuery {
                sort: Sort::ImportDesc,
                scope: Scope::Favorites,
                ..ObjectQuery::timeline()
            },
            60,
        )
        .expect("plan");
        assert!(plan.rows_sql.contains("ORDER BY o.import_time_ms DESC"));
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
    fn content_search(db: &CatalogDb, terms: &str) -> Vec<Id> {
        ids(&page(
            db,
            &ObjectQuery {
                scope: Scope::Search,
                terms: Some(terms.into()),
                ..ObjectQuery::timeline()
            },
        )
        .expect("search"))
    }
    fn content_source(db: &CatalogDb, id: &Id) -> Id {
        store::object(db, id).expect("object").primary_stream_id
    }
    #[test]
    fn content_index_combines_metadata_unicode_and_survives_rename_trash_restore() {
        use crate::{content_index as ci, trash};
        let mut v = vault();
        let id = import(&mut v, 1, MediaClass::Image, "receipt.jpg");
        let stream = content_source(&v.db, &id);
        ci::put(
            &mut v.db,
            &id,
            &stream,
            1,
            1,
            "Секретный документ invoice Café",
        )
        .expect("put");
        assert_eq!(content_search(&v.db, "СЕКРЕТНЫЙ"), vec![id]);
        assert_eq!(content_search(&v.db, "receipt invoice cafe"), vec![id]);
        let mut metadata = store::active_metadata(&v.db, &id).expect("metadata");
        metadata.revision += 1;
        metadata.original_filename = Some("renamed.jpg".into());
        store::activate_metadata_revision(&mut v.db, &metadata).expect("rename");
        assert_eq!(content_search(&v.db, "renamed документ"), vec![id]);
        assert!(content_search(&v.db, "receipt").is_empty());
        trash::set(&mut v.db, &[id], false, 100).expect("trash");
        assert!(content_search(&v.db, "документ").is_empty());
        assert!(ci::put(&mut v.db, &id, &stream, 1, 1, "late").is_err());
        trash::set(&mut v.db, &[id], true, 101).expect("restore");
        assert_eq!(content_search(&v.db, "документ"), vec![id]);
        ci::clear(&mut v.db).expect("clear");
        assert!(content_search(&v.db, "документ").is_empty());
        assert_eq!(content_search(&v.db, "renamed"), vec![id]);
    }
    #[test]
    fn content_index_is_bounded_source_bound_replaceable_and_purged_before_erasure() {
        use crate::{content_index as ci, deletion};
        let mut v = vault();
        let id = import(&mut v, 1, MediaClass::Image, "source.jpg");
        let stream = content_source(&v.db, &id);
        for (text, status, version, source, want) in [
            (
                "x".repeat(ci::TEXT_MAX + 1),
                1,
                1,
                stream,
                ChurStatus::ResourceLimitExceeded,
            ),
            ("\0".into(), 1, 1, stream, ChurStatus::InvalidInput),
            ("text".into(), 3, 1, stream, ChurStatus::InvalidInput),
            ("text".into(), 1, 2, stream, ChurStatus::InvalidInput),
            (
                "text".into(),
                1,
                1,
                random::id().unwrap(),
                ChurStatus::Conflict,
            ),
        ] {
            assert_eq!(
                ci::put(&mut v.db, &id, &source, status, version, &text)
                    .expect_err("refuse")
                    .status(),
                want
            );
        }
        ci::put(&mut v.db, &id, &stream, 2, 1, &"я".repeat(ci::TEXT_MAX / 2))
            .expect("exact UTF8 byte bound");
        ci::put(&mut v.db, &id, &stream, 1, 1, "oldsecret").expect("replace");
        ci::put(&mut v.db, &id, &stream, 1, 1, "newsecret").expect("replace");
        assert!(content_search(&v.db, "oldsecret").is_empty());
        assert_eq!(content_search(&v.db, "newsecret"), vec![id]);
        deletion::begin(&mut v.db, &id).expect("begin");
        let n: i64 =
            v.db.connection()
                .query_row("SELECT count(*) FROM content_index", [], |r| r.get(0))
                .unwrap();
        assert_eq!(n, 0);
        deletion::erase(&mut v.db, &id, 100).expect("erase");
        assert!(content_search(&v.db, "newsecret").is_empty());
    }
    #[test]
    fn content_candidates_keyset_ignores_catalog_generation_and_filters_unsupported_kinds() {
        use crate::content_index as ci;
        let mut v = vault();
        let first = import(&mut v, 1, MediaClass::Image, "a.jpg");
        let second = import(&mut v, 2, MediaClass::Image, "b.jpg");
        import(&mut v, 3, MediaClass::Video, "movie.mp4");
        let all = ci::candidates(&v.db, None, 100, false).expect("all");
        assert_eq!(all.len(), 2);
        let a = ci::candidates(&v.db, None, 1, false)
            .expect("first")
            .remove(0);
        ci::put(&mut v.db, &a.object_id, &a.primary_stream_id, 2, 1, "")
            .expect("empty completed attempt");
        let b = ci::candidates(&v.db, Some(&a.object_id), 1, false).expect("next");
        assert_eq!(b.len(), 1);
        assert_ne!(b[0].object_id, a.object_id);
        assert_eq!(ci::candidates(&v.db, None, 100, false).unwrap().len(), 1);
        assert_eq!(ci::candidates(&v.db, None, 100, true).unwrap().len(), 2);
        assert!(ci::candidates(&v.db, None, 101, false).is_err());
        assert!(
            all.iter().any(|c| c.object_id == first) && all.iter().any(|c| c.object_id == second)
        );
    }

    #[test]
    fn content_text_and_fts_terms_never_appear_in_catalog_wal_or_shm() {
        use crate::content_index as ci;
        let directory = std::env::temp_dir().join(format!(
            "chur-content-index-{}",
            random::id().unwrap().to_hex()
        ));
        std::fs::create_dir(&directory).unwrap();
        let path = directory.join("catalog.db");
        let mut v = vault_at(Some(&path));
        let id = import(&mut v, 1, MediaClass::Image, "scan.jpg");
        let source = content_source(&v.db, &id);
        let text = "OCR_PRIVATE_CANARY_832718 Содержимое конфиденциального документа";
        ci::put(&mut v.db, &id, &source, 1, 1, text).expect("index");
        assert_eq!(content_search(&v.db, "конфиденциального"), vec![id]);
        for entry in std::fs::read_dir(&directory).unwrap() {
            let bytes = std::fs::read(entry.unwrap().path()).unwrap();
            for canary in [
                b"OCR_PRIVATE_CANARY_832718".as_slice(),
                "конфиденциального".as_bytes(),
            ] {
                assert!(
                    !bytes.windows(canary.len()).any(|w| w == canary),
                    "private text is visible outside SQLCipher"
                );
            }
        }
        v.db.checkpoint().unwrap();
        drop(v);
        let bytes = std::fs::read(&path).unwrap();
        assert!(!bytes.starts_with(b"SQLite format 3\0"));
        assert!(!bytes.windows(24).any(|w| w == b"OCR_PRIVATE_CANARY_832718"));
        std::fs::remove_dir_all(directory).unwrap();
    }
    #[test]
    fn content_candidates_support_opaque_image_pdf_and_utf8_files_and_refuse_quarantine_race() {
        use crate::content_index as ci;
        let mut v = vault();
        for mime in [
            "image/png",
            "application/pdf",
            "text/plain",
            "application/json",
            "application/xml",
        ] {
            let id = import(&mut v, 1, MediaClass::Opaque, "file.bin");
            let source = content_source(&v.db, &id);
            let mut metadata = store::active_metadata(&v.db, &id).unwrap();
            metadata.revision += 1;
            metadata.content_type = mime.into();
            store::activate_metadata_revision(&mut v.db, &metadata).unwrap();
            assert!(
                ci::candidates(&v.db, None, 100, false)
                    .unwrap()
                    .iter()
                    .any(|c| c.object_id == id)
            );
            ci::put(&mut v.db, &id, &source, 1, 1, "content").unwrap();
        }
        let id = import(&mut v, 1, MediaClass::Image, "quarantine.jpg");
        let source = content_source(&v.db, &id);
        v.db.transaction(|tx| {
            tx.execute(
                "UPDATE objects SET integrity_summary=6 WHERE object_id=?1",
                [id.as_bytes().as_slice()],
            )
            .unwrap();
            Ok(())
        })
        .unwrap();
        assert!(
            !ci::candidates(&v.db, None, 100, true)
                .unwrap()
                .iter()
                .any(|c| c.object_id == id)
        );
        assert_eq!(
            ci::put(&mut v.db, &id, &source, 1, 1, "late")
                .expect_err("quarantined after read")
                .status(),
            ChurStatus::Conflict
        );
    }
    #[test]
    fn content_migration_rebuilds_real_v9_metadata_search_without_losing_labels() {
        let mut v = vault();
        let id = import(&mut v, 1, MediaClass::Image, "migrationreceipt.jpg");
        let mut metadata = store::active_metadata(&v.db, &id).unwrap();
        metadata.revision += 1;
        metadata.caption = Some("importantcaption".into());
        store::activate_metadata_revision(&mut v.db, &metadata).unwrap();
        v.db.transaction(|tx|{tx.execute_batch("DROP TABLE content_index; DROP TABLE object_search; CREATE VIRTUAL TABLE object_search USING fts5(filename,caption,tag_names,content='',contentless_delete=1,tokenize='unicode61 remove_diacritics 2',prefix='2 3'); UPDATE vault_state SET catalog_format_version=9;").unwrap();Ok(())}).unwrap();
        crate::schema::migrate_v9_to_v10(&mut v.db).expect("real v9 migration");
        assert_eq!(
            content_search(&v.db, "migrationreceipt importantcaption"),
            vec![id]
        );
        assert_eq!(
            crate::content_index::candidates(&v.db, None, 100, false)
                .unwrap()
                .len(),
            1
        );
    }
}
