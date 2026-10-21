# Catalog schema v17

> **Status:** Accepted

Catalog format `0x0011` adds local video resume positions and the encrypted
`VideoStoryboard` stream capability (`0x0b`). The storyboard is a 12-cell SDR
JPEG grid. Format v16 retains the compatible-video capability (`0x0a`). Neither
migration rewrites originals, encrypted media, keys, metadata or history.

`playback_positions` is a strict table in the existing SQLCipher catalog:

| Column | Contract |
| --- | --- |
| `object_id` | Object primary key and foreign key; `ON DELETE CASCADE` |
| `object_generation` | Positive source object generation |
| `source_content_revision` | Positive primary original stream revision |
| `position_ms` | Integer from zero through the duration |
| `duration_ms` | Canonical catalog duration, from 1 through 14,400,000 ms |

Only active video objects with a primary original stream can read or write a
position. An absent position returns zero. A position also returns zero when
its object generation, original revision or canonical duration no longer
matches. Zero clears the row. An unchanged write does not advance the catalog
generation. Each changed write advances it in the same immediate transaction.
The native observed duration must be within the same four-hour bound, and the
position must fit both that duration and the canonical duration. Native duration
rounding does not require equality with the catalog duration. Storage preserves
near-end positions; the player applies its restart-at-zero policy.

Positions are local state. They do not enter sync records or plaintext platform
preferences. Object IDs and positions use the catalog's existing SQLCipher key,
WAL, secure-delete and lifecycle rules. A vault backup includes catalog state.
The repository checks the captured session generation under its session mutex
before each call. Lock or re-unlock prevents a queued call from using a new
session. No caller pointer survives the synchronous C ABI call.

The authenticated v16→v17 transition uses the descriptor `MIGRATING` state.
One SQLCipher transaction creates the table and records version 17. A failed
transaction retains both the v16 version and schema. A process interruption on
either side of the SQL commit resumes that step before unlock returns. Restore
runs each earlier migration in order and then this step. New catalogs install
v17 directly. Readers that support only v16 reject the authenticated descriptor
as `UNSUPPORTED_VERSION`; there is no downgrade exception. Retain an encrypted
backup until the upgrade is accepted.
