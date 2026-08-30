package dev.po4yka.chur.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

    @Test
    fun sharing_records_and_collection_cursor_use_the_frozen_routes() = runTest {
        val requests = mutableListOf<Pair<String, ByteArray>>()
        val engine = MockEngine { request ->
            requests += request.url.toString() to (request.body.toByteArray())
            respond(frame(byteArrayOf(7)), status = HttpStatusCode.OK)
        }
        val client = SyncClient("https://sync.example", { ByteArray(32) }, HttpClient(engine))
        val vault = ByteArray(16) { 1 }
        val selector = ByteArray(16) { 2 }
        val issuer = ByteArray(16) { 3 }
        val device = ByteArray(16) { 4 }

        client.putSharingMembership(vault, byteArrayOf(5, 6), byteArrayOf(7, 8, 9))
        val page = client.collectionOperations(
            vault,
            selector,
            CollectionOperationCursor(issuer, device, 11u),
        )
        client.sharingIssuerMemberships(vault, issuer, 12u)
        client.sharingIssuerOperations(vault, issuer, device, 13u)

        assertContentEquals(byteArrayOf(0, 0, 0, 2, 5, 6, 7, 8, 9), requests[0].second)
        assertTrue(requests[1].first.endsWith(
            "/sharing/operations/${selector.hex()}?after_vault=${issuer.hex()}&after_device=${device.hex()}&after=11",
        ))
        assertContentEquals(byteArrayOf(7), page.single())
        assertTrue(requests[2].first.endsWith(
            "/sharing/issuers/${issuer.hex()}/memberships?after=12",
        ))
        assertTrue(requests[3].first.endsWith(
            "/sharing/issuers/${issuer.hex()}/operations/${device.hex()}?after=13",
        ))
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
