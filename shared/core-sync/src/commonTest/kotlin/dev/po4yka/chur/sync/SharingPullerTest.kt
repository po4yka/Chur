package dev.po4yka.chur.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SharingPullerTest {
    @Test
    fun opaque_acceptance_packages_are_passed_to_the_native_core_in_order() = runTest {
        val first = byteArrayOf(1, 2)
        val second = byteArrayOf(3, 4, 5)
        val engine = MockEngine { request ->
            check(request.url.encodedPath.endsWith("/sharing/packages"))
            respond(frame(first, second))
        }
        val client = SyncClient("https://sync.example", { ByteArray(32) }, HttpClient(engine))
        val accepted = mutableListOf<ByteArray>()
        val puller = SharingPuller(client) {
            accepted += it
            true
        }

        val count = puller.pullOnce(ByteArray(16) { 1 })

        assertEquals(2, count)
        assertContentEquals(first, accepted[0])
        assertContentEquals(second, accepted[1])
    }

    @Test
    fun a_locked_vault_stops_before_later_packages() = runTest {
        val engine = MockEngine { respond(frame(byteArrayOf(1), byteArrayOf(2))) }
        val client = SyncClient("https://sync.example", { ByteArray(32) }, HttpClient(engine))
        var attempts = 0
        val count = SharingPuller(client) {
            attempts++
            false
        }.pullOnce(ByteArray(16))

        assertEquals(0, count)
        assertEquals(1, attempts)
    }
}

private fun frame(vararg records: ByteArray): ByteArray {
    val bytes = mutableListOf<Byte>()
    fun addU32(value: Int) {
        bytes += (value ushr 24).toByte()
        bytes += (value ushr 16).toByte()
        bytes += (value ushr 8).toByte()
        bytes += value.toByte()
    }
    addU32(records.size)
    records.forEach { record ->
        addU32(record.size)
        bytes += record.toList()
    }
    return bytes.toByteArray()
}
