package dev.po4yka.chur.sync

import dev.po4yka.chur.ffi.ChurVault
import dev.po4yka.chur.ffi.SyncRecordKind

/** Downloads only opaque records and writes them to the native locked inbox. */
public class LockedSyncPuller internal constructor(
    private val client: SyncClient,
    private val stage: suspend (ByteArray, SyncRecordKind, Long, ByteArray) -> Unit,
) {
    /** Uses the process runtime that owns the locked inbox. */
    public constructor(client: SyncClient, runtime: Long) : this(
        client,
        { vaultId, kind, stagedAtMs, record ->
            ChurVault.stageSync(runtime, vaultId, kind, stagedAtMs, record)
        },
    )

    /** Pulls one server page for each accepted device head and all checkpoints. */
    public suspend fun pullOnce(
        vaultId: ByteArray,
        cursors: List<DeviceCursor>,
        stagedAtMs: Long,
    ): LockedPullReport {
        require(vaultId.size == ID_BYTES) { "vault ID must be $ID_BYTES bytes" }
        require(stagedAtMs >= 0) { "staging time must not be negative" }
        require(cursors.size <= DEVICE_MAX) { "sync cursor list exceeds the device limit" }
        require(cursors.all { it.deviceId.size == ID_BYTES }) { "device ID must be $ID_BYTES bytes" }
        require(cursors.map { it.deviceId.hex() }.distinct().size == cursors.size) {
            "sync cursor list repeats a device"
        }

        var operationCount = 0
        var hasMoreOperations = false
        for (cursor in cursors) {
            val page = client.operations(vaultId, cursor.deviceId, cursor.after)
            // A full page cannot prove that this device history is exhausted.
            hasMoreOperations = hasMoreOperations || page.size >= OPERATION_PAGE_MAX
            for (record in page) {
                stage(vaultId, SyncRecordKind.OPERATION, stagedAtMs, record)
                operationCount++
            }
        }
        val checkpoints = client.checkpoints(vaultId)
        for (record in checkpoints) {
            stage(vaultId, SyncRecordKind.CHECKPOINT, stagedAtMs, record)
        }
        // Opaque staging can evict or expire records. Only unlocked native
        // signature and sequence validation can advance an accepted cursor.
        return LockedPullReport(operationCount, checkpoints.size, cursors, hasMoreOperations)
    }
}

/** Last accepted sequence for one device, obtained while the vault was unlocked. */
public data class DeviceCursor(public val deviceId: ByteArray, public val after: ULong)

/** Opaque records handed to the bounded native inbox; accepted cursors stay unchanged. */
public data class LockedPullReport(
    public val operations: Int,
    public val checkpoints: Int,
    /** The unchanged accepted cursors, one per input cursor, in order. */
    public val cursors: List<DeviceCursor> = emptyList(),
    /** Another bounded cycle is needed to prove the operation history is exhausted. */
    public val hasMoreOperations: Boolean = false,
)

private fun ByteArray.hex(): String = buildString(size * 2) {
    for (byte in this@hex) {
        append(HEX[(byte.toInt() ushr 4) and 15])
        append(HEX[byte.toInt() and 15])
    }
}

private const val ID_BYTES = 16
private const val DEVICE_MAX = 32
private const val OPERATION_PAGE_MAX = 256
private const val HEX = "0123456789abcdef"
