package dev.po4yka.chur.app

import dev.po4yka.chur.ffi.SharingIdentity
import dev.po4yka.chur.ffi.SyncProcessReport
import dev.po4yka.chur.ffi.SyncRecordKind
import dev.po4yka.chur.ffi.SharedReceivePlan
import dev.po4yka.chur.ffi.SharedSourceObject
import dev.po4yka.chur.ffi.SharedSourceRange
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

    override suspend fun acceptSharePackage(packageBytes: ByteArray): Boolean =
        repository.acceptSharePackage(packageBytes)

    override suspend fun receiveSharedOperations(
        packageBytes: ByteArray,
        operations: List<ByteArray>,
    ): SharedReceivePlan? = repository.receiveSharedOperations(packageBytes, operations)

    override suspend fun sharedDownloadOffset(collectionId: ByteArray, objectId: ByteArray): ULong? =
        repository.sharedDownloadOffset(collectionId, objectId)

    override suspend fun appendSharedDownload(
        collectionId: ByteArray,
        objectId: ByteArray,
        offset: ULong,
        bytes: ByteArray,
    ): Boolean = repository.appendSharedDownload(collectionId, objectId, offset, bytes)

    override suspend fun finishSharedDownload(
        collectionId: ByteArray,
        objectId: ByteArray,
        nowMs: Long,
    ): Boolean = repository.finishSharedDownload(collectionId, objectId, nowMs)

    override suspend fun sourceCollectionId(): ByteArray? = repository.sourceCollectionId()

    override suspend fun sourcePage(collectionId: ByteArray, afterObjectId: ByteArray): List<SharedSourceObject>? =
        repository.sourcePage(collectionId, afterObjectId)

    override suspend fun sourceRange(objectId: ByteArray, offset: ULong, maxBytes: Int): SharedSourceRange? =
        repository.sourceRange(objectId, offset, maxBytes)

    override suspend fun sourceAuthor(source: SharedSourceObject): SharedSourceObject? = repository.sourceAuthor(source)
}
