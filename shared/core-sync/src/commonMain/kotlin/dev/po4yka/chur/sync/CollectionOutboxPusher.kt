package dev.po4yka.chur.sync

import dev.po4yka.chur.ffi.CollectionOutbound

/** Sends durable records even after their media rows have been deleted. */
internal class CollectionOutboxPusher(private val client: SyncClient, private val store: SyncStateStore) {
    suspend fun flush(initial: SyncState, read: suspend (ULong) -> List<CollectionOutbound>?): SyncState {
        var state = initial
        while (true) {
            val page = read(state.collectionPushCursor) ?: return state
            require(page.size <= 64) { "collection outbox page exceeds its bound" }
            if (page.isEmpty()) return state
            for (record in page) {
                require(record.cursor > state.collectionPushCursor) { "collection outbox did not advance" }
                client.putCollectionOperation(state.vaultId, record.record)
                state = state.copy(collectionPushCursor = record.cursor)
                store.save(state)
            }
        }
    }
}
