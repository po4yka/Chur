package dev.po4yka.chur.sync

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.SharedDownload
import dev.po4yka.chur.ffi.SharedReceivePlan
import dev.po4yka.chur.ffi.SharedSourceObject
import dev.po4yka.chur.ffi.SharedSourceRange
import dev.po4yka.chur.ffi.SharingIdentity
import dev.po4yka.chur.ffi.SyncProcessReport
import dev.po4yka.chur.ffi.SyncRecordKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

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

    @Test
    fun a_later_create_unblocks_an_earlier_commit_before_download() = runTest {
        val commit = ByteArray(58).apply { this[49] = 1; this[57] = 1 }
        val create = ByteArray(58).apply { this[49] = 2; this[57] = 1 }
        val collection = ByteArray(16) { 3 }
        val objectId = ByteArray(16) { 4 }
        val storeId = ByteArray(16) { 5 }
        val source = ByteArray(16) { 6 }
        var downloadRequests = 0
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/sharing/packages") -> respond(frame(byteArrayOf(1)))
                request.url.encodedPath.contains("/sharing/operations/") -> {
                    val cursor = request.url.parameters["after_vault"]
                    val device = request.url.parameters["after_device"]
                    when {
                        cursor == null -> respond(frame(commit))
                        device == ByteArray(16).apply { this[15] = 1 }.toHex() -> respond(frame(create))
                        else -> respond(frame())
                    }
                }
                request.url.encodedPath.contains("/sharing/issuers/") -> {
                    downloadRequests++
                    respond(byteArrayOf(7, 8, 9))
                }
                else -> error(request.url.toString())
            }
        }
        val boundary = object : SyncVaultBoundary {
            var createSeen = false
            var commitSeen = false
            var appended = false
            var finished = false
            var finishAttempts = 0

            override suspend fun identity(): SharingIdentity? = null
            override suspend fun stage(vaultId: ByteArray, kind: SyncRecordKind, stagedAtMs: Long, record: ByteArray) = Unit
            override suspend fun process(): SyncProcessReport? = null
            override suspend fun acceptSharePackage(packageBytes: ByteArray): Boolean = true
            override suspend fun receiveSharedOperations(packageBytes: ByteArray, operations: List<ByteArray>): SharedReceivePlan {
                val record = operations.singleOrNull()
                if (record?.contentEquals(create) == true) createSeen = true
                if (record?.contentEquals(commit) == true && createSeen) commitSeen = true
                val pending = if (record?.contentEquals(commit) == true && !createSeen) 1 else 0
                return SharedReceivePlan(
                    source, collection, ByteArray(16), pending,
                    if (commitSeen) listOf(SharedDownload(objectId, storeId, 3uL)) else emptyList(),
                )
            }
            override suspend fun sharedDownloadOffset(collectionId: ByteArray, objectId: ByteArray): ULong? = 3uL
            override suspend fun appendSharedDownload(collectionId: ByteArray, objectId: ByteArray, offset: ULong, bytes: ByteArray): Boolean {
                appended = bytes.contentEquals(byteArrayOf(7, 8, 9))
                return appended
            }
            override suspend fun finishSharedDownload(collectionId: ByteArray, objectId: ByteArray, nowMs: Long): Boolean {
                if (finishAttempts++ == 0) throw ChurFailure(ChurStatus.OBJECT_CORRUPT, "stale prefix")
                finished = true
                return true
            }
            override suspend fun sourceCollectionId(): ByteArray? = null
            override suspend fun sourcePage(collectionId: ByteArray, afterObjectId: ByteArray): List<SharedSourceObject>? = null
            override suspend fun sourceRange(objectId: ByteArray, offset: ULong, maxBytes: Int): SharedSourceRange? = null
            override suspend fun sourceAuthor(source: SharedSourceObject): SharedSourceObject? = null
        }
        val client = SyncClient("https://sync.example", { ByteArray(32) }, HttpClient(engine))

        assertEquals(1, SharingPuller(client, boundary).pullOnce(ByteArray(16)))
        assertEquals(1, downloadRequests)
        assertTrue(boundary.appended)
        assertTrue(boundary.finished)
        assertEquals(2, boundary.finishAttempts)
    }

    @Test
    fun network_retry_resumes_from_durable_native_offset() = runTest {
        val length = 1_048_579uL
        val collection = ByteArray(16) { 3 }
        val objectId = ByteArray(16) { 4 }
        val storeId = ByteArray(16) { 5 }
        val source = ByteArray(16) { 6 }
        val requested = mutableListOf<ULong>()
        var failOnce = true
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/sharing/packages") -> respond(frame(byteArrayOf(1)))
                request.url.encodedPath.contains("/sharing/operations/") -> respond(frame())
                request.url.encodedPath.contains("/sharing/issuers/") -> {
                    val offset = requireNotNull(request.url.parameters["offset"]).toULong()
                    requested += offset
                    if (offset == 1_048_576uL && failOnce) {
                        failOnce = false
                        error("network unavailable")
                    }
                    respond(ByteArray(requireNotNull(request.url.parameters["length"]).toInt()))
                }
                else -> error(request.url.toString())
            }
        }
        val boundary = object : SyncVaultBoundary {
            var durableLength = 0uL
            var finished = false
            override suspend fun identity(): SharingIdentity? = null
            override suspend fun stage(vaultId: ByteArray, kind: SyncRecordKind, stagedAtMs: Long, record: ByteArray) = Unit
            override suspend fun process(): SyncProcessReport? = null
            override suspend fun acceptSharePackage(packageBytes: ByteArray): Boolean = true
            override suspend fun receiveSharedOperations(packageBytes: ByteArray, operations: List<ByteArray>): SharedReceivePlan =
                SharedReceivePlan(source, collection, ByteArray(16), 0, listOf(SharedDownload(objectId, storeId, length)))
            override suspend fun sharedDownloadOffset(collectionId: ByteArray, objectId: ByteArray): ULong? = durableLength
            override suspend fun appendSharedDownload(collectionId: ByteArray, objectId: ByteArray, offset: ULong, bytes: ByteArray): Boolean {
                assertEquals(durableLength, offset)
                durableLength += bytes.size.toULong()
                return true
            }
            override suspend fun finishSharedDownload(collectionId: ByteArray, objectId: ByteArray, nowMs: Long): Boolean {
                assertEquals(length, durableLength)
                finished = true
                return true
            }
            override suspend fun sourceCollectionId(): ByteArray? = null
            override suspend fun sourcePage(collectionId: ByteArray, afterObjectId: ByteArray): List<SharedSourceObject>? = null
            override suspend fun sourceRange(objectId: ByteArray, offset: ULong, maxBytes: Int): SharedSourceRange? = null
            override suspend fun sourceAuthor(source: SharedSourceObject): SharedSourceObject? = null
        }
        val client = SyncClient("https://sync.example", { ByteArray(32) }, HttpClient(engine))

        assertFails { SharingPuller(client, boundary).pullOnce(ByteArray(16)) }
        assertEquals(1_048_576uL, boundary.durableLength)
        assertEquals(1, SharingPuller(client, boundary).pullOnce(ByteArray(16)))
        assertEquals(listOf(0uL, 1_048_576uL, 1_048_576uL), requested)
        assertTrue(boundary.finished)
    }
}

private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

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
