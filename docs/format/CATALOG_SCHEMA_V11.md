# Catalog schema v11: pending album additions

> **Status:** Accepted implementation contract; device and release gates remain separate

Version `0x000b` stores local album additions until the signed own-vault operation log can retain them. The table uses the existing Rust-owned SQLCipher database. It adds no object, container, backup, or sync wire format version.

## Stored state

`own_sync_pending_album_memberships` is a STRICT table with two non-null 16-byte BLOB fields: `album_id` and `object_id`. Their pair is the primary key. Foreign keys refer to `albums` and `objects`, both with `ON DELETE CASCADE`. The index `own_sync_pending_album_memberships_object(object_id, album_id)` supports bounded lookup when an original becomes ready for sync publication.

A row means that the user added this object to this album and that the addition must enter the signed local operation log. It is an addition intent, not a remote membership token or a second album tree. It contains no filename, album name, key, or public path. Database pages and WAL use the catalog key.

## Transactions and receive

A local membership addition and its pending row commit together. The sync writer removes the pending row only after it stores the signed local `AddAlbumMember` operation in the same transaction. A failed write retains the pending row. The row survives lock and restart, including when the original is not yet available to sync.

Incoming membership projection preserves a pair with a pending local addition. A remote removal must not erase that local addition before it has its own durable token. A local removal clears the pending addition in the same transaction as the membership change. It removes observed remote tokens; future additions that the device has not observed follow the existing observed-remove set rules. Album and object deletion remove pending rows through the foreign keys. Removing a membership does not delete its original or its memberships in other albums.

## Migration and restore

Fresh catalogs install v11. A v10 vault first installs an authenticated `MIGRATING` descriptor for `(10,11,migration_generation=10)`. One SQL transaction creates the table and index, copies each existing `album_memberships` pair as a pending local addition, and records version 11. The migration preserves the retained catalog snapshot. It does not decrypt or infer membership operations from the signed log. The next own-vault sync writer retains each addition durably before it clears the pending row.

The SQL migration preserves album names, tree positions, ordinary memberships, and catalog generation. A checkpoint precedes the final authenticated active descriptor. Unlock resumes before or after the SQL commit. Failed SQL rolls back the table, copied rows, migration marker, and version together. Earlier migration steps remain sequential.

Full backup includes the encrypted catalog and these rows. Restore completes the same sequential schema migration before it publishes the restored descriptor. Selective import creates new local memberships through the normal catalog writer. Earlier clients reject the unsupported catalog version; a current client does not downgrade a future catalog.
