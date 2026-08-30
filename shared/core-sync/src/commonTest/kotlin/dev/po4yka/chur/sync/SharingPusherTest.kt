package dev.po4yka.chur.sync

import dev.po4yka.chur.ffi.PreparedGrant
import dev.po4yka.chur.ffi.PreparedShare
import dev.po4yka.chur.ffi.PreparedShareRevocation
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SharingPusherTest {
    @Test
    fun records_are_uploaded_in_dependency_order() = runTest {
        val requests = mutableListOf<Pair<String, ByteArray>>()
        val engine = MockEngine { request ->
            requests += request.url.encodedPath to request.body.toByteArray()
            respond(ByteArray(0), HttpStatusCode.OK)
        }
        val client = SyncClient("https://sync.example", { ByteArray(32) }, HttpClient(engine))
        val pusher = SharingPusher(client)
        val vault = ByteArray(16) { 1 }

        pusher.push(
            vault,
            PreparedShare(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3), byteArrayOf(4)),
        )
        pusher.pushRevocation(
            vault,
            PreparedShareRevocation(
                membership = byteArrayOf(5),
                membershipOperation = byteArrayOf(6),
                rotationOperations = listOf(byteArrayOf(7), byteArrayOf(8)),
                grants = listOf(PreparedGrant(byteArrayOf(9), byteArrayOf(10))),
                rotationComplete = true,
            ),
        )

        assertEquals(
            listOf(
                "/sharing/memberships",
                "/sharing/grants",
                "/sharing/memberships",
                "/sharing/operations",
                "/sharing/operations",
                "/sharing/grants",
            ),
            requests.map { it.first.substringAfter("/v1/vaults/${vault.hex()}") },
        )
        assertEquals(
            listOf(2, 4, 6, 7, 8, 10),
            requests.map { (_, body) -> body.last().toInt() },
        )
    }
}

private fun ByteArray.hex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}
