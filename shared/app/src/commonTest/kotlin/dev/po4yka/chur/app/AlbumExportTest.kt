package dev.po4yka.chur.app

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.AlbumSummary
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.ObjectPage
import dev.po4yka.chur.ffi.ObjectProjection
import dev.po4yka.chur.ffi.QueryScope
import dev.po4yka.chur.ffi.QuerySort
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlbumExportTest {
    @Test
    fun every_page_and_descendant_membership_is_exported_without_a_ui_filter() = runTest {
        val rootAlbum = AlbumSummary(byteArrayOf(1), 130, "Root")
        val child = AlbumSummary(byteArrayOf(2), 1, "Child", rootAlbum.albumId)
        val unrelated = AlbumSummary(byteArrayOf(3), 20, "Other")
        val root = Directory("selected")
        val written = mutableListOf<Pair<String, Int>>()
        val result = exportAlbumTree(rootAlbum.albumId, listOf(child, unrelated, rootAlbum), root,
            page = { query ->
                assertEquals(QueryScope.ALBUM, query.scope)
                assertEquals(QuerySort.IMPORT_DESC, query.sort)
                assertEquals(0, query.kinds)
                assertEquals(null, query.terms)
                assertEquals(128, query.limit)
                if (query.scopeId!!.contentEquals(child.albumId)) page(listOf(0), 1)
                else if (query.cursor == null) page((0 until 128).toList(), 130, byteArrayOf(1))
                else page(listOf(128, 129), 130)
            }, cancelled = { false }, onItem = { item, total -> assertTrue(item in 1..total) },
            export = { directory, objectId -> written += (directory as Directory).path to objectId[0].toUByte().toInt() },
        )
        assertEquals(AlbumExportOutcome(131, 131, 0), result)
        assertEquals(130, written.count { it.first == "selected/Root" })
        assertEquals("selected/Root/Child" to 0, written.last())
        assertTrue(root.children.single().closed)
        assertTrue(root.children.single().children.single().closed)
    }

    @Test
    fun cancellation_keeps_completed_memberships_and_never_starts_the_next_one() = runTest {
        val root = Directory("selected")
        var cancelled = false
        var writes = 0
        val result = exportAlbumTree(byteArrayOf(1), listOf(album()), root,
            page = { page(listOf(1, 2), 2) }, cancelled = { cancelled }, onItem = { _, _ -> },
            export = { _, _ -> writes++; cancelled = true },
        )
        assertEquals(1, writes)
        assertEquals(AlbumExportOutcome(2, 1, 0, ChurStatus.CANCELLED), result)
        assertTrue("1 cancelled" in result.summary())
        assertTrue(root.children.single().closed)
    }

    @Test
    fun the_first_failed_file_stops_the_batch_with_explicit_partial_counts() = runTest {
        var writes = 0
        val result = exportAlbumTree(byteArrayOf(1), listOf(album()), Directory("selected"),
            page = { page(listOf(1, 2, 3), 3) }, cancelled = { false }, onItem = { _, _ -> },
            export = { _, _ ->
                writes++
                if (writes == 2) throw ChurFailure(ChurStatus.IO_FAILURE, "the export")
            },
        )
        assertEquals(2, writes)
        assertEquals(AlbumExportOutcome(3, 1, 1, ChurStatus.IO_FAILURE), result)
        assertTrue("1 exported, 1 failed, 1 skipped" in result.summary())
    }

    @Test
    fun a_changed_catalog_never_claims_that_a_partial_tree_is_complete() = runTest {
        var calls = 0
        var writes = 0
        val result = exportAlbumTree(byteArrayOf(1), listOf(album()), Directory("selected"),
            page = { page(listOf(1, 2), 2).copy(catalogGeneration = (++calls).toLong()) },
            cancelled = { false }, onItem = { _, _ -> }, export = { _, _ -> writes++ },
        )
        assertEquals(0, writes)
        assertEquals(AlbumExportOutcome(2, 0, 0, ChurStatus.CONFLICT), result)
    }

    @Test
    fun a_tree_change_before_the_first_membership_page_stops_before_creating_folders() = runTest {
        val root = Directory("selected")
        val result = exportAlbumTree(byteArrayOf(1), listOf(album()), root,
            page = { page(listOf(1, 2), 2).copy(catalogGeneration = 11) },
            expectedGeneration = 10,
            cancelled = { false }, onItem = { _, _ -> error("stale tree") },
            export = { _, _ -> error("stale tree") },
        )
        assertEquals(ChurStatus.CONFLICT, result.status)
        assertEquals(0, result.exported)
        assertTrue(root.children.isEmpty())
    }

    @Test
    fun an_empty_album_still_creates_and_closes_its_folder() = runTest {
        val root = Directory("selected")
        val result = exportAlbumTree(byteArrayOf(1), listOf(album()), root,
            page = { page(emptyList(), 0) }, cancelled = { false }, onItem = { _, _ -> error("empty") },
            export = { _, _ -> error("empty") },
        )
        assertEquals(AlbumExportOutcome(0, 0, 0), result)
        assertEquals("selected/Root", root.children.single().path)
        assertTrue(root.children.single().closed)
        assertTrue("0 exported" in result.summary())
    }

    @Test
    fun private_names_cannot_be_paths_or_dot_directories() {
        assertEquals("_a_b_", safeExportName("/a\\b:", "Album"))
        assertEquals("Album", safeExportName(" .. ", "Album"))
        assertEquals("a_b", safeExportName("a\nb", "Album"))
    }

    private fun album() = AlbumSummary(byteArrayOf(1), 0, "Root")
    private fun page(ids: List<Int>, total: Long, cursor: ByteArray? = null) = ObjectPage(
        ids.map { id -> ObjectProjection(byteArrayOf(id.toByte()), byteArrayOf(), 1, 0, 0,
            false, 1, 1, 1, 0, false, 0, 0, false) }, total, 1, cursor,
    )
    private class Directory(val path: String) : ExportDirectory {
        var closed = false
        val children = mutableListOf<Directory>()
        override fun createDirectory(displayName: String): ExportDirectory = Directory("$path/$displayName").also { children += it }
        override fun createFile(displayName: String, contentType: String, plaintextSize: Long): ExportSink.Destination? = null
        override fun close() { closed = true }
    }
}
