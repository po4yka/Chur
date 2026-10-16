package dev.po4yka.chur.sync

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.CollectionOutbound
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CollectionOutboxPusherTest {
    @Test
    fun a_partial_http_failure_preserves_the_last_ack_and_retries_only_the_remainder() = runTest {
        val records = listOf(
            CollectionOutbound(7uL, ByteArray(16) { 1 }, byteArrayOf(10, 11)),
            CollectionOutbound(12uL, ByteArray(16) { 2 }, byteArrayOf(20, 21)),
            CollectionOutbound(19uL, ByteArray(16) { 1 }, byteArrayOf(30, 31)),
        )
        val initial = SyncState("https://sync.example", ByteArray(16) { 3 }, ByteArray(16) { 4 },
            ByteArray(32) { 5 }, emptyList(), ownPushCursor = 31uL)
        val store = Store(initial)
        val sent = mutableListOf<ByteArray>()
        var failSecond = true
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertTrue(request.url.encodedPath.endsWith("/sharing/operations"))
            val bytes = request.body.toByteArray()
            sent += bytes
            if (bytes.contentEquals(records[1].record) && failSecond) {
                failSecond = false
                respond(byteArrayOf(), HttpStatusCode.ServiceUnavailable)
            } else respond(byteArrayOf(), HttpStatusCode.Created)
        }
        val readAfter = mutableListOf<ULong>()
        val read: suspend (ULong) -> List<CollectionOutbound> = { after ->
            readAfter += after
            records.filter { it.cursor > after }
        }
        val client = SyncClient(initial.serverUrl, { initial.transportToken }, HttpClient(engine))
        try {
            val failure = assertFailsWith<SyncTransportFailure> {
                CollectionOutboxPusher(client, store).flush(initial, read)
            }
            assertEquals(ChurStatus.INTERNAL_FAILURE, failure.status)
            assertEquals(7uL, store.state.collectionPushCursor)
            assertEquals(listOf(7uL), store.savedCursors)
            assertEquals(2, sent.size)
            assertContentEquals(records[0].record, sent[0])
            assertContentEquals(records[1].record, sent[1])

            // A new pusher resumes from persisted state, as after an app restart.
            val completed = CollectionOutboxPusher(client, store).flush(store.load(), read)
            assertEquals(19uL, completed.collectionPushCursor)
            assertEquals(31uL, completed.ownPushCursor)
            assertEquals(listOf(7uL, 12uL, 19uL), store.savedCursors)
            assertEquals(listOf(0uL, 7uL, 19uL), readAfter)
            assertEquals(4, sent.size)
            assertContentEquals(records[1].record, sent[2])
            assertContentEquals(records[2].record, sent[3])
        } finally { client.close() }
    }

    private class Store(var state: SyncState) : SyncStateStore {
        val savedCursors = mutableListOf<ULong>()
        override suspend fun load(): SyncState = state
        override suspend fun save(state: SyncState) { this.state = state; savedCursors += state.collectionPushCursor }
        override suspend fun clear() = error("an upload must not clear configuration")
    }
}
