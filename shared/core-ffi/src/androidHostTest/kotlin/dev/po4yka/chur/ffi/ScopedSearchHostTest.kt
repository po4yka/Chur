package dev.po4yka.chur.ffi

import dev.po4yka.chur.core.model.ChurStatus
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real JNI pagination must filter before paging and keep each cursor bound to its query. */
class ScopedSearchHostTest {
    @Test
    fun album_terms_filter_before_paging_and_cursors_reject_other_terms_or_albums() {
        val root = File(System.getProperty("java.io.tmpdir"), "chur-scoped-search-${System.nanoTime()}")
        check(root.mkdirs())
        val runtime = ChurVault.openRuntime(root.absolutePath)
        var session = 0L
        try {
            session = ChurVault.activateCreation(ChurVault.beginCreation(runtime, "Scoped-search-host-password".encodeToByteArray()))
            val first = import(session, root, "needle-first.bin", 1)
            val second = import(session, root, "needle-second.bin", 2)
            val other = import(session, root, "different.bin", 3)
            val outside = import(session, root, "needle-outside.bin", 4)
            val album = ChurVault.createAlbum(session, "Scoped album")
            val another = ChurVault.createAlbum(session, "Another album")
            for (id in listOf(first, second, other)) ChurVault.setAlbumMembership(session, album, id, true)
            ChurVault.setAlbumMembership(session, another, outside, true)
            val query = ObjectQuery(QueryScope.ALBUM, sort = QuerySort.ALBUM_MANUAL,
                scopeId = album, terms = "needle", limit = 1)
            val page = ChurVault.query(session, query)
            assertEquals(2L, page.totalCount)
            assertEquals(1, page.objects.size)
            val cursor = assertNotNull(page.nextCursor)
            val next = ChurVault.query(session, query.copy(cursor = cursor))
            assertEquals(2L, next.totalCount)
            assertEquals(1, next.objects.size)
            val actual = (page.objects + next.objects).map { it.objectId.toList() }.toSet()
            assertEquals(setOf(first.toList(), second.toList()), actual)
            assertTrue(outside.toList() !in actual)
            assertEquals(3L, ChurVault.query(session, query.copy(terms = null, limit = 0)).totalCount)
            assertEquals(3L, ChurVault.query(session, ObjectQuery(terms = "needle")).totalCount)
            for (mismatched in listOf(query.copy(terms = "different"), query.copy(scopeId = another), query.copy(kinds = 1))) {
                val failure = assertFailsWith<ChurFailure> {
                    ChurVault.query(session, mismatched.copy(cursor = cursor))
                }
                assertEquals(ChurStatus.INVALID_INPUT, failure.status)
            }
            assertContentEquals(album, ChurVault.albums(session).single { it.name == "Scoped album" }.albumId)
        } finally {
            if (session != 0L) ChurVault.closeSession(session)
            ChurVault.closeRuntime(runtime)
            root.deleteRecursively()
        }
    }

    private fun import(session: Long, root: File, filename: String, seed: Int): ByteArray {
        val source = File(root, filename)
        source.writeBytes(ByteArray(128) { (seed + it).toByte() })
        RandomAccessFile(source, "r").use { handle ->
            val descriptor = handle.fd.javaClass.getDeclaredField("fd").also { it.isAccessible = true }.getInt(handle.fd)
            val operation = ChurVault.beginImport(session, descriptor, ImportRequest(
                contentType = "application/octet-stream", seekable = true, mediaClass = 4,
                knownLength = source.length(), originalFilename = filename,
            ))
            try {
                val deadline = System.nanoTime() + 10_000_000_000L
                while (true) {
                    val progress = ChurVault.poll(operation)
                    if (progress.terminal) {
                        assertEquals(ChurStatus.OK, progress.status)
                        break
                    }
                    check(System.nanoTime() < deadline) { "native synthetic import did not finish" }
                    Thread.yield()
                }
            } finally { ChurVault.closeOperation(operation) }
        }
        return ChurVault.query(session, ObjectQuery(QueryScope.SEARCH, terms = filename)).objects.single().objectId
    }
}
