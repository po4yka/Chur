package dev.po4yka.chur.app

import dev.po4yka.chur.ffi.SharingIdentity
import dev.po4yka.chur.ffi.SyncProcessReport
import dev.po4yka.chur.ffi.SyncRecordKind
import dev.po4yka.chur.sync.SyncVaultBoundary
import dev.po4yka.chur.vault.VaultRepository

/**
 * The one [VaultRepository] as the sync engine sees it.
 *
 * `docs/ARCHITECTURE.md` §9 puts the handles in the repository and nothing
 * above it, so the engine's boundary is this adapter rather than the engine
 * holding a `Long`. Every call here is safe in either lock state by contract:
 * staging runs locked, and the two session calls answer `null` locked.
 */
class RepositorySyncBoundary(
    private val repository: VaultRepository,
) : SyncVaultBoundary {
    override suspend fun identity(): SharingIdentity? = repository.syncIdentity()

    override suspend fun stage(
        vaultId: ByteArray,
        kind: SyncRecordKind,
        stagedAtMs: Long,
        record: ByteArray,
    ) = repository.stageSyncRecord(vaultId, kind, stagedAtMs, record)

    override suspend fun process(): SyncProcessReport? = repository.processSync()
}
