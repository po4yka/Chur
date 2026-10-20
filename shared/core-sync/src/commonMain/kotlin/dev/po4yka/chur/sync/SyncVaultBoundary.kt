package dev.po4yka.chur.sync

import dev.po4yka.chur.ffi.DevicePairingCredentials
import dev.po4yka.chur.ffi.DevicePairingPreview
import dev.po4yka.chur.ffi.DevicePairingRequest
import dev.po4yka.chur.ffi.OwnSyncOperation
import dev.po4yka.chur.ffi.PairedDevice
import dev.po4yka.chur.ffi.PreparedDevicePairing
import dev.po4yka.chur.ffi.PreparedDeviceRevocation
import dev.po4yka.chur.ffi.SharedReceivePlan
import dev.po4yka.chur.ffi.SharedSourceObject
import dev.po4yka.chur.ffi.SharedSourceRange
import dev.po4yka.chur.ffi.SharingIdentity
import dev.po4yka.chur.ffi.SyncForkState
import dev.po4yka.chur.ffi.SyncProcessReport
import dev.po4yka.chur.ffi.SyncRecordKind

/** Native vault operations used by the direct peer coordinator; null means the session locked. */
public interface SyncVaultBoundary {
    public suspend fun peerCall(input: ByteArray): ByteArray
    public suspend fun identity(): SharingIdentity?
    public suspend fun acceptedHeads(): List<DeviceCursor>?
    public suspend fun ownSyncCollections(): List<ByteArray>?
    public suspend fun ownSyncOperations(after: ULong): List<OwnSyncOperation>?
    public suspend fun ownSyncPlan(collectionId: ByteArray): SharedReceivePlan?
    public suspend fun ownSyncAuthor(source: SharedSourceObject): SharedSourceObject?
    public suspend fun prepareDevicePairing(): DevicePairingRequest?
    public suspend fun inspectDevicePairing(request: ByteArray): DevicePairingPreview?
    public suspend fun approveDevicePairing(request: ByteArray, nowMs: Long): PreparedDevicePairing?
    public suspend fun startDevicePairing(approval: ByteArray): DevicePairingCredentials?
    public suspend fun finishDevicePairing(nowMs: Long): ByteArray?
    public suspend fun currentDeviceCheckpoint(nowMs: Long): ByteArray?
    public suspend fun pairedDevices(): List<PairedDevice>?
    public suspend fun prepareDeviceRevocation(deviceId: ByteArray, nowMs: Long): PreparedDeviceRevocation?
    public suspend fun stage(vaultId: ByteArray, kind: SyncRecordKind, stagedAtMs: Long, record: ByteArray)
    public suspend fun process(): SyncProcessReport?
    public suspend fun forkState(): SyncForkState?
    public suspend fun objectKeyRequest(collectionId: ByteArray, objectId: ByteArray): ByteArray?
    public suspend fun objectKeyIssue(prekey: ByteArray): ByteArray?
    public suspend fun objectKeyAccept(packageBytes: ByteArray, grant: ByteArray): Boolean
    public suspend fun sourcePage(collectionId: ByteArray, afterObjectId: ByteArray): List<SharedSourceObject>?
    public suspend fun sourceRange(objectId: ByteArray, offset: ULong, maxBytes: Int): SharedSourceRange?
    public suspend fun sharedDownloadOffset(collectionId: ByteArray, objectId: ByteArray): ULong?
    public suspend fun appendSharedDownload(collectionId: ByteArray, objectId: ByteArray, offset: ULong, bytes: ByteArray): Boolean
    public suspend fun finishSharedDownload(collectionId: ByteArray, objectId: ByteArray, nowMs: Long): Boolean
}

/** A durable position accepted by native signature and causal validation, never a wire cursor. */
public data class DeviceCursor(public val deviceId: ByteArray, public val after: ULong)
