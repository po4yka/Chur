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

    /** Authenticated objects that still wait for their source device to send a key. */
    public var pendingObjectKeys: Int = 0
        private set

    /** Missing or corrupt containers deferred without blocking other objects. */
    public var unavailableObjects: Int = 0
        private set

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
        pendingObjectKeys = 0
        unavailableObjects = 0
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
            // Authenticate addressed grants only after their signed Commit is known.
            // ACK follows the native private-key erasure barrier, so a retry can
            // never require a prekey which the client has already destroyed.
            for (grant in client.objectKeyGrants(vaultId)) {
                require(grant.size == 346) { "object key grant length is invalid" }
                if (!grant.copyOfRange(18, 34).contentEquals(plan.collectionId)) continue
                if (!acceptObjectKeyGrant(client, vaultId, grant) {
                        boundary.objectKeyAccept(packageBytes, grant)
                    }
                ) return accepted
            }
            for (objectPlan in plan.downloads) {
                val request = try { boundary.objectKeyRequest(plan.collectionId, objectPlan.objectId) } catch (failure: ChurFailure) {
                    if (failure.status == ChurStatus.RESOURCE_LIMIT_EXCEEDED) {
                        pendingObjectKeys++
                        break
                    }
                    throw failure
                }
                if (request != null) {
                    pendingObjectKeys++
                    client.putObjectKeyRequest(vaultId, request)
                    continue
                }

                if (!downloadOrDefer(vaultId, boundary, plan, objectPlan.objectId, objectPlan.storeId, objectPlan.length, nowMs)) {
                    return accepted
                }
            }
            accepted++
        }
        return accepted
    }

    /** False means the vault locked; an unavailable object remains pending for retry. */
    internal suspend fun downloadOrDefer(
        recipientVaultId: ByteArray,
        vault: SyncVaultBoundary,
        plan: SharedReceivePlan,
        objectId: ByteArray,
        storeId: ByteArray,
        length: ULong,
        nowMs: Long,
    ): Boolean = try {
        download(recipientVaultId, vault, plan, objectId, storeId, length, nowMs)
    } catch (failure: SyncTransportFailure) {
        if (failure.status != ChurStatus.NOT_FOUND) throw failure
        unavailableObjects++
        true
    } catch (failure: ChurFailure) {
        if (failure.status !in setOf(ChurStatus.NOT_FOUND, ChurStatus.OBJECT_CORRUPT, ChurStatus.OBJECT_INCOMPLETE)) throw failure
        unavailableObjects++
        true
    }

    internal suspend fun download(
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
                val bytes = if (plan.sourceVaultId.contentEquals(recipientVaultId)) {
                    client.download(recipientVaultId, storeId, offset, amount)
                } else {
                    client.downloadSharedObject(
                        recipientVaultId, plan.sourceVaultId, plan.collectionId, storeId, offset, amount,
                    )
                }
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
