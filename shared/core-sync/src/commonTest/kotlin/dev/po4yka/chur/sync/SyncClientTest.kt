package dev.po4yka.chur.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SyncClientTest {
    @Test
    fun membership_pages_are_bounded_binary_records_with_bearer_auth() = runTest {
        val record = byteArrayOf(1, 2, 3)
        val engine = MockEngine { request ->
            assertEquals("Bearer ${ByteArray(32) { 9 }.hex()}", request.headers[HttpHeaders.Authorization])
            assertEquals("https://sync.example/v1/vaults/${ByteArray(16) { 1 }.hex()}/memberships?after=7", request.url.toString())
            respond(
                content = frame(record),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/octet-stream"),
            )
        }
        val client = SyncClient("https://sync.example", { ByteArray(32) { 9 } }, HttpClient(engine))

        val records = client.memberships(ByteArray(16) { 1 }, 7u)

        assertEquals(1, records.size)
        assertContentEquals(record, records.single())
    }

    @Test
    fun stable_server_errors_are_typed() = runTest {
        val engine = MockEngine {
            respond(content = byteArrayOf(0, 0, 0, 100), status = HttpStatusCode.Unauthorized)
        }
        val client = SyncClient("https://sync.example", { ByteArray(32) }, HttpClient(engine))

        val failure = assertFailsWith<SyncTransportFailure> {
            client.memberships(ByteArray(16), 0u)
        }

        assertEquals(dev.po4yka.chur.core.model.ChurStatus.AUTHENTICATION_FAILED, failure.status)
    }

    @Test
    fun oversized_page_counts_are_rejected() = runTest {
        val engine = MockEngine { respond(byteArrayOf(0, 0, 1, 1)) }
        val client = SyncClient("http://127.0.0.1:8080", { ByteArray(32) }, HttpClient(engine))

        assertFailsWith<IllegalArgumentException> {
            client.memberships(ByteArray(16), 0u)
        }
    }
}

private fun frame(record: ByteArray): ByteArray =
    byteArrayOf(0, 0, 0, 1, 0, 0, 0, record.size.toByte()) + record

private fun ByteArray.hex(): String {
    val digits = "0123456789abcdef"
    return buildString(size * 2) {
        for (byte in this@hex) {
            append(digits[(byte.toInt() ushr 4) and 15])
            append(digits[byte.toInt() and 15])
        }
    }
}
