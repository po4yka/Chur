package dev.po4yka.chur.sync

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SharingAvailabilityTest {
    @Test fun unavailableContainersDoNotBlockTheHealthyNeighbor() = runTest {
        val finished = mutableListOf<Int>()
        val boundary = Boundary(finished)
        val client = client { store ->
            if (store == 1) respond(code(ChurStatus.NOT_FOUND), HttpStatusCode.NotFound)
            else respond(byteArrayOf(42))
        }
        val puller = SharingPuller(client, boundary)
        assertEquals(1, puller.pullOnce(id(9)))
        assertEquals(listOf(3), finished)
        assertEquals(2, puller.unavailableObjects)
    }

    @Test fun transportAuthenticationFailureStillStopsTheRun() = runTest {
        var requests = 0
        val client = client {
            requests++
            respond(code(ChurStatus.AUTHENTICATION_FAILED), HttpStatusCode.Forbidden)
        }
        val failure = assertFailsWith<SyncTransportFailure> {
            SharingPuller(client, Boundary(mutableListOf())).pullOnce(id(9))
        }
        assertEquals(ChurStatus.AUTHENTICATION_FAILED, failure.status)
        assertEquals(1, requests)
    }

    private fun client(download: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(Int) -> io.ktor.client.request.HttpResponseData): SyncClient {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/sharing/packages") -> respond(frame(byteArrayOf(1)))
                request.url.encodedPath.contains("/sharing/operations/") -> respond(frame())
                request.url.encodedPath.endsWith("/sharing/object-key-grants") -> respond(frame())
                request.url.encodedPath.contains("/sharing/issuers/") -> {
                    val store = request.url.encodedPath.substringAfterLast('/').takeLast(2).toInt(16)
                    download(store)
                }
                else -> error(request.url.toString())
            }
        }
        return SyncClient("https://sync.example", { ByteArray(32) }, HttpClient(engine))
    }

    private class Boundary(private val finished: MutableList<Int>) : SyncVaultBoundary {
        override suspend fun identity(): SharingIdentity? = null
        override suspend fun stage(vaultId: ByteArray, kind: SyncRecordKind, stagedAtMs: Long, record: ByteArray) = Unit
        override suspend fun process(): SyncProcessReport? = null
        override suspend fun forkState(): SyncForkState? = null
        override suspend fun acknowledgeForks() = false
        override suspend fun acceptSharePackage(packageBytes: ByteArray) = true
        override suspend fun receiveSharedOperations(packageBytes: ByteArray, operations: List<ByteArray>) =
            SharedReceivePlan(id(8), id(7), id(6), 0, (1..3).map { SharedDownload(id(it), id(it), 1uL) })
        override suspend fun objectKeyRequest(collectionId: ByteArray, objectId: ByteArray): ByteArray? = null
        override suspend fun sharedDownloadOffset(collectionId: ByteArray, objectId: ByteArray) = 0uL
        override suspend fun appendSharedDownload(collectionId: ByteArray, objectId: ByteArray, offset: ULong, bytes: ByteArray): Boolean {
            assertContentEquals(byteArrayOf(42), bytes)
            return true
        }
        override suspend fun finishSharedDownload(collectionId: ByteArray, objectId: ByteArray, nowMs: Long): Boolean {
            val number = objectId.last().toInt()
            if (number == 2) throw ChurFailure(ChurStatus.OBJECT_CORRUPT, "corrupt container")
            finished += number
            return true
        }
        override suspend fun sourceCollectionId(): ByteArray? = null
        override suspend fun sourcePage(collectionId: ByteArray, afterObjectId: ByteArray): List<SharedSourceObject>? = null
        override suspend fun sourceRange(objectId: ByteArray, offset: ULong, maxBytes: Int): SharedSourceRange? = null
        override suspend fun sourceAuthor(source: SharedSourceObject): SharedSourceObject? = null
    }

    companion object {
        private fun id(value: Int) = ByteArray(16).apply { this[15] = value.toByte() }
        private fun code(status: ChurStatus) = ByteArray(4) { (status.value ushr (24 - it * 8)).toByte() }
        private fun frame(vararg records: ByteArray): ByteArray {
            fun int(value: Int) = ByteArray(4) { (value ushr (24 - it * 8)).toByte() }
            return int(records.size) + records.fold(byteArrayOf()) { bytes, record -> bytes + int(record.size) + record }
        }
    }
}
