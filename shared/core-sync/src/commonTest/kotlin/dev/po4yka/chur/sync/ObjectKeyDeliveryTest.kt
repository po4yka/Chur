package dev.po4yka.chur.sync

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class ObjectKeyDeliveryTest {
    @Test
    fun contradictory_signed_requests_are_acknowledged_before_the_next_valid_request() = runTest {
        val requests = List(65) { number -> ByteArray(226).apply { this[17] = number.toByte() } }
        var acknowledged = 0
        var granted = 0
        var pages = 0
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/object-key-requests") -> respond(frame(if (pages++ == 0) requests.take(64) else requests.drop(64)))
                request.url.encodedPath.endsWith("/object-key-requests/ack") -> { acknowledged++; respond(ByteArray(0)) }
                request.url.encodedPath.endsWith("/object-key-grants") -> { granted++; respond(ByteArray(0)) }
                else -> error(request.url.toString())
            }
        }
        val client = SyncClient("https://sync.example", { ByteArray(32) }, HttpClient(engine))
        suspend fun answerPage() = respondToObjectKeyRequests(client, ByteArray(16)) { request ->
            if (request[17].toInt() < 64) throw ChurFailure(ChurStatus.AUTHENTICATION_FAILED, "contradictory object binding")
            ByteArray(346)
        }
        answerPage()
        assertEquals(64, acknowledged)
        assertEquals(0, granted)
        answerPage()
        assertEquals(1, granted)
    }

    @Test
    fun invalid_grants_are_rejected_without_success_receipts_and_a_healthy_source_can_follow() = runTest {
        val responses = mutableListOf<String>()
        val engine = MockEngine { request ->
            responses += request.url.encodedPath.substringAfterLast('/')
            respond(ByteArray(0))
        }
        val client = SyncClient("https://sync.example", { ByteArray(32) }, HttpClient(engine))
        val grant = ByteArray(346)
        for (status in listOf(ChurStatus.AUTHENTICATION_FAILED, ChurStatus.INVALID_INPUT,
                ChurStatus.NON_CANONICAL_ENCODING)) {
            assertTrue(acceptObjectKeyGrant(client, ByteArray(16), grant) {
                throw ChurFailure(status, "invalid source response")
            })
        }
        assertEquals(List(3) { "reject" }, responses)
        assertTrue(acceptObjectKeyGrant(client, ByteArray(16), grant) {
            throw ChurFailure(ChurStatus.NOT_FOUND, "local object was deleted")
        })
        assertEquals("discard", responses.last())
        assertTrue(acceptObjectKeyGrant(client, ByteArray(16), grant) { true })
        assertEquals("ack", responses.last())
    }

    @Test
    fun locked_and_failed_durable_acceptance_do_not_retire_the_grant() = runTest {
        var requests = 0
        val client = SyncClient("https://sync.example", { ByteArray(32) }, HttpClient(MockEngine {
            requests++
            respond(ByteArray(0))
        }))
        assertFalse(acceptObjectKeyGrant(client, ByteArray(16), ByteArray(346)) { false })
        assertFailsWith<ChurFailure> {
            acceptObjectKeyGrant(client, ByteArray(16), ByteArray(346)) {
                throw ChurFailure(ChurStatus.IO_FAILURE, "durability barrier failed")
            }
        }
        assertEquals(0, requests)
    }

    private fun frame(records: List<ByteArray>): ByteArray {
        fun number(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(),
            (value ushr 8).toByte(), value.toByte())
        return number(records.size) + records.fold(ByteArray(0)) { bytes, record -> bytes + number(record.size) + record }
    }
}
