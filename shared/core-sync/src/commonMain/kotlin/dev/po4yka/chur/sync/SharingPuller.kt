package dev.po4yka.chur.sync

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurVault
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.SharedReceivePlan

/** Pulls signed collection records, then verifies missing source containers locally. */
public class SharingPuller internal constructor(
    private val client: SyncClient,
    private val accept: suspend (ByteArray) -> Boolean,
) {
    private var vault: SyncVaultBoundary? = null

    /** Uses the unlocked native session that owns the recipient catalog. */
    public constructor(client: SyncClient, session: Long) : this(
        client,
        { packageBytes ->
            ChurVault.acceptSharePackage(session, packageBytes)
            true
        },
    )

    /** Uses the repository-owned native boundary for content materialization. */
    public constructor(client: SyncClient, vault: SyncVaultBoundary) : this(
        client, { vault.acceptSharePackage(it) },
    ) {
        this.vault = vault
    }

    /** Accepts addressed packages and their original ciphertext while unlocked. */
    public suspend fun pullOnce(vaultId: ByteArray, nowMs: Long = 0L): Int {
        val packages = client.sharingPackages(vaultId)
        var accepted = 0
        for (packageBytes in packages) {
            val boundary = vault
            if (boundary == null) {
                if (!accept(packageBytes)) break
                accepted++
                continue
            }
            var plan = boundary.receiveSharedOperations(packageBytes, emptyList()) ?: break
            var hadPending = false
            // A record can precede its cause in server order across devices.
            // Replay one complete pass after all later predecessors are stored.
            repeat(2) { pass ->
                if (pass > 0 && !hadPending) return@repeat
                hadPending = false
                var cursor: CollectionOperationCursor? = null
                while (true) {
                    val page = client.collectionOperations(vaultId, plan.selector, cursor)
                    if (page.isEmpty()) break
                    val next = cursorOf(page.last())
                    require(cursor == null || after(next, cursor)) { "shared operation page did not advance" }
                    plan = boundary.receiveSharedOperations(packageBytes, page) ?: return accepted
                    hadPending = hadPending || plan.pendingOperations > 0
                    cursor = next
                }
            }
            // Longer cause chains retry on the next sync without exposing an
            // object whose signed history has not been accepted yet.
            if (hadPending) {
                accepted++
                continue
            }
            for (objectPlan in plan.downloads) {
                if (!download(vaultId, boundary, plan, objectPlan.objectId, objectPlan.storeId, objectPlan.length, nowMs)) {
                    return accepted
                }
            }
            accepted++
        }
        return accepted
    }

    private suspend fun download(
        recipientVaultId: ByteArray,
        vault: SyncVaultBoundary,
        plan: SharedReceivePlan,
        objectId: ByteArray,
        storeId: ByteArray,
        length: ULong,
        nowMs: Long,
    ): Boolean {
        val savedOffset = vault.sharedDownloadOffset(plan.collectionId, objectId) ?: return false
        require(savedOffset <= length) { "shared staged length exceeds signed length" }
        suspend fun transfer(start: ULong): Boolean {
            var offset = start
            while (offset < length) {
                val amount = minOf(1_048_576uL, length - offset)
                val bytes = client.downloadSharedObject(
                    recipientVaultId, plan.sourceVaultId, plan.collectionId, storeId, offset, amount,
                )
                require(bytes.size.toULong() == amount) { "shared ciphertext range is short" }
                if (!vault.appendSharedDownload(plan.collectionId, objectId, offset, bytes)) return false
                offset += amount
            }
            return true
        }
        if (!transfer(savedOffset)) return false
        try {
            return vault.finishSharedDownload(plan.collectionId, objectId, nowMs)
        } catch (failure: ChurFailure) {
            // A stale staged prefix can pass the length check. Restart once;
            // native finish authenticates every byte before catalog activation.
            if (savedOffset == 0uL || failure.status !in setOf(
                    ChurStatus.OBJECT_CORRUPT,
                    ChurStatus.OBJECT_INCOMPLETE,
                    ChurStatus.AUTHENTICATION_FAILED,
                    ChurStatus.NON_CANONICAL_ENCODING,
                )
            ) throw failure
            if (!transfer(0uL)) return false
            return vault.finishSharedDownload(plan.collectionId, objectId, nowMs)
        }
    }

    private fun cursorOf(record: ByteArray): CollectionOperationCursor {
        require(record.size >= 58) { "shared operation header is short" }
        var sequence = 0uL
        for (index in 50 until 58) sequence = (sequence shl 8) or record[index].toUByte().toULong()
        return CollectionOperationCursor(
            record.copyOfRange(18, 34), record.copyOfRange(34, 50), sequence,
        )
    }

    private fun after(next: CollectionOperationCursor, previous: CollectionOperationCursor): Boolean {
        fun compare(left: ByteArray, right: ByteArray): Int {
            for (index in left.indices) {
                val difference = left[index].toUByte().toInt() - right[index].toUByte().toInt()
                if (difference != 0) return difference
            }
            return 0
        }
        val vaultOrder = compare(next.issuerVaultId, previous.issuerVaultId)
        if (vaultOrder != 0) return vaultOrder > 0
        val deviceOrder = compare(next.issuerDeviceId, previous.issuerDeviceId)
        return if (deviceOrder != 0) deviceOrder > 0 else next.sequence > previous.sequence
    }
}
