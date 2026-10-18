@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.app

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.ImportRequest
import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.ffi.QueryScope
import dev.po4yka.chur.vault.VaultRepository
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.open
import platform.posix.write
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Actual iOS C ABI and SQLCipher, with disposable public fixture files. */
class AlbumOrganizationIosTest {
    @Test
    fun removing_a_selection_is_atomic_and_preserves_other_albums_and_originals_after_restart(): Unit = runBlocking {
        val root = NSTemporaryDirectory() + "chur-album-ios-" + NSUUID().UUIDString
        assertTrue(NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null))
        val password = "Album-organization-iOS-fixture".encodeToByteArray()
        val vault = VaultRepository("$root/vault", { 1_700_000_000_000L })
        var reopened: VaultRepository? = null
        try {
            vault.start()
            vault.create(password, false)
            val firstBytes = "First public fixture".encodeToByteArray()
            val secondBytes = "Second public fixture".encodeToByteArray()
            val first = importFile(vault, "$root/first.txt", firstBytes)
            val second = importFile(vault, "$root/second.txt", secondBytes)
            val source = vault.createAlbum("Source")
            val retained = vault.createAlbum("Other album")
            vault.placeAlbumObjects(source, "", null, null, listOf(first, second), false)
            vault.placeAlbumObjects(retained, "", null, null, listOf(first, second), false)
            val query = ObjectQuery(QueryScope.ALBUM, scopeId = source)
            val before = vault.page(query)
            assertEquals(2L, before.totalCount)
            val missing = ByteArray(16) { 99 }
            assertEquals(ChurStatus.CONFLICT, assertFailsWith<ChurFailure> {
                vault.removeAlbumObjects(source, listOf(first, missing))
            }.status)
            assertEquals(before.catalogGeneration, vault.page(query).catalogGeneration)
            assertEquals(2L, vault.page(query).totalCount)
            vault.removeAlbumObjects(source, listOf(first, second))
            assertEquals(0L, vault.page(query).totalCount)
            assertEquals(2L, vault.page(ObjectQuery(QueryScope.ALBUM, scopeId = retained)).totalCount)
            assertEquals(2L, vault.page(ObjectQuery()).totalCount)
            assertContentEquals(firstBytes, vault.readRange(first, 0, firstBytes.size))
            assertContentEquals(secondBytes, vault.readRange(second, 0, secondBytes.size))
            vault.shutdown()
            val next = VaultRepository("$root/vault", { 1_700_000_000_001L }).also { reopened = it }
            next.start()
            next.unlock(password)
            assertEquals(0L, next.page(query).totalCount)
            assertEquals(2L, next.page(ObjectQuery(QueryScope.ALBUM, scopeId = retained)).totalCount)
            assertContentEquals(firstBytes, next.readRange(first, 0, firstBytes.size))
        } finally {
            runCatching { vault.shutdown() }
            runCatching { reopened?.shutdown() }
            NSFileManager.defaultManager.removeItemAtPath(root, null)
        }
    }

    private suspend fun importFile(vault: VaultRepository, path: String, bytes: ByteArray): ByteArray {
        val output = open(path, O_WRONLY or O_CREAT or O_TRUNC, 384)
        assertTrue(output >= 0)
        try {
            bytes.usePinned { assertEquals(bytes.size.toLong(), write(output, it.addressOf(0), bytes.size.convert())) }
        } finally { close(output) }
        val input = open(path, O_RDONLY)
        assertTrue(input >= 0)
        try {
            val operation = vault.beginImport(input, ImportRequest("text/plain", true, 4,
                knownLength = bytes.size.toLong(), originalFilename = path.substringAfterLast('/')))
            try {
                val result = withTimeout(60_000) {
                    var progress = vault.poll(operation)
                    while (!progress.terminal) { delay(10); progress = vault.poll(operation) }
                    progress
                }
                assertEquals(0, result.status)
                return assertNotNull(result.importedObjectId())
            } finally { vault.closeOperation(operation) }
        } finally { close(input) }
    }
}
