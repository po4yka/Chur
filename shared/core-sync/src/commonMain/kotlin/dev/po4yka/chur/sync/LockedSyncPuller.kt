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
        val next = ArrayList<DeviceCursor>(cursors.size)
        for (cursor in cursors) {
            var pulled = 0
            for (record in client.operations(vaultId, cursor.deviceId, cursor.after)) {
                stage(vaultId, SyncRecordKind.OPERATION, stagedAtMs, record)
                pulled++
                operationCount++
            }
            // §5: the caller's cursor for this chain advances by what this page
            // held, and only once every record of the page reached the stage.
            next += DeviceCursor(cursor.deviceId, cursor.after + pulled.toULong())
        }
        val checkpoints = client.checkpoints(vaultId)
        for (record in checkpoints) {
            stage(vaultId, SyncRecordKind.CHECKPOINT, stagedAtMs, record)
        }
        return LockedPullReport(operationCount, checkpoints.size, next)
    }
}

/** Last accepted sequence for one device, obtained while the vault was unlocked. */
public data class DeviceCursor(public val deviceId: ByteArray, public val after: ULong)

/** Opaque records handed to the bounded native inbox, with where they leave the cursors. */
public data class LockedPullReport(
    public val operations: Int,
    public val checkpoints: Int,
    /** The cursors the next run pulls from, one per input cursor, in order. */
    public val cursors: List<DeviceCursor> = emptyList(),
)

private fun ByteArray.hex(): String = buildString(size * 2) {
    for (byte in this@hex) {
        append(HEX[(byte.toInt() ushr 4) and 15])
        append(HEX[byte.toInt() and 15])
    }
}

private const val ID_BYTES = 16
private const val DEVICE_MAX = 32
private const val HEX = "0123456789abcdef"
