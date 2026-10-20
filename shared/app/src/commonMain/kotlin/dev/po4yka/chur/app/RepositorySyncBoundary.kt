package dev.po4yka.chur.app

import dev.po4yka.chur.ffi.SharingIdentity
import dev.po4yka.chur.ffi.SyncForkState
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
 * holding a `Long`. Peer networking requires the current unlocked session.
 * All canonical record verification and ciphertext activation remain native.
 */
class RepositorySyncBoundary(
    private val repository: VaultRepository,
) : SyncVaultBoundary {
    override suspend fun peerCall(input: ByteArray): ByteArray = repository.peerCall(input)
    override suspend fun identity(): SharingIdentity? = repository.syncIdentity()

    override suspend fun acceptedHeads(): List<dev.po4yka.chur.sync.DeviceCursor>? =
        repository.syncHeads()?.map { dev.po4yka.chur.sync.DeviceCursor(it.deviceId, it.sequence) }

    override suspend fun ownSyncCollections(): List<ByteArray>? = repository.ownSyncCollections()
    override suspend fun ownSyncOperations(after: ULong): List<dev.po4yka.chur.ffi.OwnSyncOperation>? = repository.ownSyncOperations(after)
    override suspend fun ownSyncPlan(collectionId: ByteArray): SharedReceivePlan? = repository.ownSyncPlan(collectionId)
    override suspend fun ownSyncAuthor(source: SharedSourceObject): SharedSourceObject? = repository.ownSyncAuthor(source)
    override suspend fun prepareDevicePairing(): dev.po4yka.chur.ffi.DevicePairingRequest? = repository.prepareDevicePairing()
    override suspend fun inspectDevicePairing(request: ByteArray): dev.po4yka.chur.ffi.DevicePairingPreview? = repository.inspectDevicePairing(request)
    override suspend fun approveDevicePairing(request: ByteArray, nowMs: Long): dev.po4yka.chur.ffi.PreparedDevicePairing? =
        repository.approveDevicePairing(request, nowMs)
    override suspend fun startDevicePairing(approval: ByteArray): dev.po4yka.chur.ffi.DevicePairingCredentials? = repository.startDevicePairing(approval)
    override suspend fun finishDevicePairing(nowMs: Long): ByteArray? = repository.finishDevicePairing(nowMs)
    override suspend fun currentDeviceCheckpoint(nowMs: Long): ByteArray? = repository.currentDeviceCheckpoint(nowMs)
    override suspend fun pairedDevices(): List<dev.po4yka.chur.ffi.PairedDevice>? = repository.pairedDevices()
    override suspend fun prepareDeviceRevocation(deviceId: ByteArray, nowMs: Long): dev.po4yka.chur.ffi.PreparedDeviceRevocation? = repository.prepareDeviceRevocation(deviceId, nowMs)

    override suspend fun stage(
        vaultId: ByteArray,
        kind: SyncRecordKind,
        stagedAtMs: Long,
        record: ByteArray,
    ) = repository.stageSyncRecord(vaultId, kind, stagedAtMs, record)

    override suspend fun process(): SyncProcessReport? = repository.processSync()

    override suspend fun forkState(): SyncForkState? = repository.syncForkState()

    override suspend fun objectKeyRequest(collectionId: ByteArray, objectId: ByteArray): ByteArray? =
        repository.objectKeyRequest(collectionId, objectId)

    override suspend fun objectKeyIssue(prekey: ByteArray): ByteArray? = repository.objectKeyIssue(prekey)

    override suspend fun objectKeyAccept(packageBytes: ByteArray, grant: ByteArray): Boolean =
        repository.objectKeyAccept(packageBytes, grant)

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

    override suspend fun sourcePage(collectionId: ByteArray, afterObjectId: ByteArray): List<SharedSourceObject>? =
        repository.sourcePage(collectionId, afterObjectId)

    override suspend fun sourceRange(objectId: ByteArray, offset: ULong, maxBytes: Int): SharedSourceRange? =
        repository.sourceRange(objectId, offset, maxBytes)

}
