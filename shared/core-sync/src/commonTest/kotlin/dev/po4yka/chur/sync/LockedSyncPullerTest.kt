package dev.po4yka.chur.sync

import dev.po4yka.chur.ffi.SyncRecordKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class LockedSyncPullerTest {
    @Test
    fun opaque_records_are_staged_without_advancing_accepted_cursors() = runTest {
        val operation = byteArrayOf(1, 2)
        val checkpoint = byteArrayOf(3, 4)
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.contains("/operations/") -> respond(frame(operation))
                request.url.encodedPath.endsWith("/checkpoints") -> respond(frame(checkpoint))
                else -> error("unexpected URL ${request.url}")
            }
        }
        val client = SyncClient("https://sync.example", { ByteArray(32) }, HttpClient(engine))
        val staged = mutableListOf<Pair<SyncRecordKind, ByteArray>>()
        val puller = LockedSyncPuller(client) { _, kind, time, bytes ->
            assertEquals(9, time)
            staged += kind to bytes
        }

        val report = puller.pullOnce(
            ByteArray(16) { 1 },
            listOf(DeviceCursor(ByteArray(16) { 2 }, 7u)),
            9,
        )

        assertEquals(LockedPullReport(1, 1), report)
        assertEquals(listOf(SyncRecordKind.OPERATION, SyncRecordKind.CHECKPOINT), staged.map { it.first })
        assertContentEquals(operation, staged[0].second)
        assertContentEquals(checkpoint, staged[1].second)
    }
}

private fun frame(record: ByteArray): ByteArray =
    byteArrayOf(0, 0, 0, 1, 0, 0, 0, record.size.toByte()) + record
