# Private Catalog Schema v7

> **Status:** Accepted normative physical schema extension

Catalog v7 extends [v6](CATALOG_SCHEMA_V6.md) with local album hierarchy and manual order. All fields remain in the encrypted SQLCipher catalog.

## Album organization

`albums.parent_album_id` is a nullable 16-byte album identifier. Null denotes a root album. `albums.sort_position` is a nonnegative integer used to order siblings; `album_id` breaks ties. The application refuses a missing parent or a move that creates a cycle. Deleting an album deletes its complete descendant subtree and their memberships in one transaction. Media objects remain in the library.

`album_memberships.sort_position` is a nonnegative integer ordering an album's direct members; `object_id` breaks ties. New albums and memberships append. Moving one album or member rewrites the affected sibling positions transactionally and advances catalog generation. Album queries may select manual order with sort code `4`; that sort is valid only in album scope.

## Migration

The authenticated `v6 -> v7` migration adds the three columns and their indexes and records version `0x0007` in one SQLCipher transaction. Existing albums are ordered by name and identifier. Existing members are ordered by descending capture time and identifier. The migration leaves all existing album identifiers, names, and memberships intact.
