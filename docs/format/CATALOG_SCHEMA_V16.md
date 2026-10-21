# Catalog schema v16

> **Status:** Accepted

Catalog format `0x0010` adds the capability to store encrypted compatible-video
derivative streams. It changes the set of stream kinds that a reader must
understand. It adds no SQL table or column and does not change existing object,
metadata, original, derivative, key or history bytes. The original remains the
source for export and for each derived stream's content-revision binding.

The authenticated v15→v16 transition uses the existing descriptor `MIGRATING`
state, records the migration target in one SQLCipher transaction, then records
version 16. It checkpoints the catalog and completes the authenticated descriptor
transition before unlock returns. A failed transaction retains v15. A process
interruption before or after the SQL commit resumes the same one-step migration.
Backups retain existing objects and run this step before a restored catalog
becomes active. No media rewrite or extra media-size space reservation is needed.

New catalogs install v16 directly. Readers that support only v15 must reject
the v16 descriptor as `UNSUPPORTED_VERSION`; opening the catalog with an older
reader is not a supported downgrade. Retain the previous encrypted backup until
the upgrade is accepted. No compatibility exception permits an older reader to
ignore an unknown authenticated stream kind.
