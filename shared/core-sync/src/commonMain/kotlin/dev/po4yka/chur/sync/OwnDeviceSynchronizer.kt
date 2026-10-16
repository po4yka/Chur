package dev.po4yka.chur.sync

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure

/** Transfers only native-authenticated own-vault records and committed ciphertext. */
internal class OwnDeviceSynchronizer(private val client: SyncClient, private val vault: SyncVaultBoundary) {
    var pendingObjectKeys: Int = 0
        private set

    var unavailableObjects: Int = 0
        private set

    var hasMoreDownloads: Boolean = false
        private set

    suspend fun publishOperations(vaultId: ByteArray, after: ULong, acknowledged: suspend (ULong) -> Unit): ULong {
        var cursor = after
        while (true) {
            val page = vault.ownSyncOperations(cursor) ?: return cursor
            if (page.isEmpty()) return cursor
            for (operation in page) {
                require(operation.sequence > cursor) { "the native own operation page did not advance" }
                client.putOperation(vaultId, operation.record)
                cursor = operation.sequence
                acknowledged(cursor)
            }
        }
    }

    suspend fun publishMedia(vaultId: ByteArray, flushOperations: suspend () -> Unit) {
        val collections = vault.ownSyncCollections() ?: return
        // Native authoring persists ordinary operations in the same device
        // chain as earlier edits. Flush that complete ordered outbox, rather
        // than skipping an intervening operation when an object is committed.
        val pusher = SourceObjectPusher(client, publishSharedMapping = false) { _, _ -> flushOperations() }
        for (collection in collections) {
            var after = ByteArray(16)
            while (true) {
                val page = vault.sourcePage(collection, after) ?: return
                if (page.isEmpty()) break
                for (source in page) {
                    pusher.push(
                        vaultId,
                        SourceObjectPublication(source.collectionId, source.objectId, source.storeId, source.length, source.fullSha256),
                        read = { objectId, offset, amount ->
                            val range = vault.sourceRange(objectId, offset, amount)
                                ?: throw ChurFailure(ChurStatus.VAULT_LOCKED, "the own source vault locked")
                            SourceObjectRange(range.bytes, range.sha256)
                        },
                        author = {
                            val published = vault.ownSyncAuthor(source)
                                ?: throw ChurFailure(ChurStatus.VAULT_LOCKED, "the own source vault locked")
                            SourceObjectOperations(published.createOperation, published.commitOperation)
                        },
                    )
                }
                require(page.last().objectId.hex() > after.hex()) { "the native own source page did not advance" }
                after = page.last().objectId
            }
        }
    }

    suspend fun receive(vaultId: ByteArray, nowMs: Long) {
        pendingObjectKeys = 0
        unavailableObjects = 0
        hasMoreDownloads = false
        val collections = vault.ownSyncCollections() ?: return
        if (collections.isEmpty()) return
        val grants = client.objectKeyGrants(vaultId)
        val downloader = SharingPuller(client, vault)
        for (collection in collections) {
            val plan = vault.ownSyncPlan(collection) ?: return
            // Native dispatch is bounded. A full plan can hide another page,
            // including when every download in this page succeeds.
            hasMoreDownloads = hasMoreDownloads || plan.downloads.size >= DOWNLOAD_PAGE_MAX
            for (grant in grants) {
                require(grant.size == 346) { "own object key grant length is invalid" }
                if (!grant.copyOfRange(18, 34).contentEquals(collection) ||
                    !grant.copyOfRange(50, 66).contentEquals(vaultId)
                ) continue
                if (!acceptObjectKeyGrant(client, vaultId, grant) {
                        vault.objectKeyAccept(byteArrayOf(), grant)
                    }
                ) return
            }
            for (objectPlan in plan.downloads) {
                val request = try { vault.objectKeyRequest(collection, objectPlan.objectId) } catch (failure: ChurFailure) {
                    if (failure.status == ChurStatus.RESOURCE_LIMIT_EXCEEDED) {
                        pendingObjectKeys++
                        break
                    }
                    throw failure
                }
                if (request != null) {
                    client.putObjectKeyRequest(vaultId, request)
                    pendingObjectKeys++
                    continue
                }
                val continued = downloader.downloadOrDefer(vaultId, vault, plan, objectPlan.objectId, objectPlan.storeId, objectPlan.length, nowMs)
                unavailableObjects = downloader.unavailableObjects
                if (!continued) return
            }
        }
    }
}

private fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

private const val DOWNLOAD_PAGE_MAX = 256
