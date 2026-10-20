# Catalog schema v15

> **Status:** Accepted

Catalog format `0x000f` removes persisted network enrollment data. The authenticated v14→v15 migration clears unfinished device-pairing state and recreates `device_pairing_approvals` with only `request_id`, `request`, `checkpoint`, `enrollment`, `operation`, and `approval`. No endpoint or transport credential is stored. Existing objects, encrypted keys, ordinary signed histories, device membership, albums, and notes are preserved.

The transition uses the existing authenticated descriptor migration and one SQLCipher transaction. A failed transaction leaves the previous catalog state intact. Approvals from the removed network model must be regenerated; there is no legacy decoder. New catalogs install v15 directly.
