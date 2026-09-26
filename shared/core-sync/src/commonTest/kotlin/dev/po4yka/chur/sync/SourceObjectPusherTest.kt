package dev.po4yka.chur.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SourceObjectPusherTest {
    @Test
    fun resumes_upload_before_publishing_mapping_and_signed_operations() = runTest {
        val calls = mutableListOf<String>()
        val operations = mutableListOf<ByteArray>()
        var complete = false
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            calls += path
            when {
                path.endsWith("/uploads/${id(3).hex()}") && request.method.value == "POST" ->
                    respond(progress(if (complete) 5 else 2, 5, complete))
                path.endsWith("/uploads/${id(3).hex()}") -> {
                    assertContentEquals(byteArrayOf(3, 4, 5), request.body.toByteArray())
                    assertEquals("2", request.url.parameters["offset"])
                    respond(progress(5, 5, false))
                }
                path.endsWith("/uploads/${id(3).hex()}/finish") -> {
                    complete = true
                    respond(progress(5, 5, true))
                }
                path.endsWith("/sharing/collections/${id(2).hex()}/objects/${id(3).hex()}") ->
                    respond(byteArrayOf(), status = HttpStatusCode.NoContent)
                path.endsWith("/sharing/operations") -> {
                    operations += request.body.toByteArray()
                    respond(byteArrayOf(), status = HttpStatusCode.Created)
                }
                else -> error("unexpected request: $path")
            }
        }
        val client = SyncClient("https://sync.example", { ByteArray(32) { 9 } }, HttpClient(engine))
        val publication = SourceObjectPublication(id(2), id(4), id(3), 5u, ByteArray(32) { 7 })
        var reads = 0
        val read: suspend (ByteArray, ULong, Int) -> SourceObjectRange = { objectId, offset, maxBytes ->
            assertContentEquals(id(4), objectId)
            assertEquals(2u, offset)
            assertEquals(3, maxBytes)
            reads++
            SourceObjectRange(byteArrayOf(3, 4, 5), ByteArray(32) { 8 })
        }
        val author: suspend (SourceObjectPublication) -> SourceObjectOperations = {
            assertContentEquals(publication.objectId, it.objectId)
            calls += "native-author"
            SourceObjectOperations(byteArrayOf(10), byteArrayOf(11))
        }

        SourceObjectPusher(client).push(id(1), publication, read, author)
        SourceObjectPusher(client).push(id(1), publication, read, author)

        assertEquals(1, reads)
        assertEquals(4, calls.count { it.endsWith("/sharing/operations") })
        assertContentEquals(byteArrayOf(10), operations[0])
        assertContentEquals(byteArrayOf(11), operations[1])
        assertContentEquals(byteArrayOf(10), operations[2])
        assertContentEquals(byteArrayOf(11), operations[3])
        assertTrue(calls.indexOfFirst { it.endsWith("/finish") } < calls.indexOfFirst { it.endsWith("/sharing/operations") })
        assertTrue(calls.indexOfFirst { it.contains("/sharing/collections/") } < calls.indexOfFirst { it == "native-author" })
        assertTrue(calls.indexOfFirst { it == "native-author" } < calls.indexOfFirst { it.endsWith("/sharing/operations") })
        assertTrue(calls.indexOfFirst { it.contains("/sharing/collections/") } < calls.indexOfFirst { it.endsWith("/sharing/operations") })
    }

    @Test
    fun missing_ciphertext_stops_before_publication() = runTest {
        val calls = mutableListOf<String>()
        val engine = MockEngine { request ->
            calls += request.url.encodedPath
            respond(progress(0, 5, false))
        }
        val client = SyncClient("https://sync.example", { ByteArray(32) { 9 } }, HttpClient(engine))
        val publication = SourceObjectPublication(id(2), id(4), id(3), 5u, ByteArray(32) { 7 })

        assertFailsWith<IllegalArgumentException> {
            SourceObjectPusher(client).push(
                id(1), publication,
                { _, _, _ -> SourceObjectRange(byteArrayOf(), ByteArray(32)) },
                { error("an incomplete upload must not author records") },
            )
        }
        assertEquals(1, calls.size)
    }
}

private fun progress(received: Int, expected: Int, complete: Boolean): ByteArray =
    ByteArray(8) { if (it == 7) received.toByte() else 0 } +
        ByteArray(8) { if (it == 7) expected.toByte() else 0 } +
        byteArrayOf(if (complete) 1 else 0)

private fun id(value: Byte): ByteArray = ByteArray(16) { value }

private fun ByteArray.hex(): String = joinToString("") { byte ->
    byte.toUByte().toString(16).padStart(2, '0')
}
