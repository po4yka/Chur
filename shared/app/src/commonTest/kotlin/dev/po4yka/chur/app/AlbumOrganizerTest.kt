package dev.po4yka.chur.app

import dev.po4yka.chur.app.vault.albumRows
import dev.po4yka.chur.ffi.AlbumSummary
import kotlin.test.Test
import kotlin.test.assertEquals

class AlbumOrganizerTest {
    @Test
    fun album_rows_follow_the_saved_tree_and_sibling_order() {
        val root = AlbumSummary(ByteArray(16) { 1 }, 1, "Root", position = 1)
        val earlier = AlbumSummary(ByteArray(16) { 2 }, 0, "Earlier", position = 0)
        val child = AlbumSummary(ByteArray(16) { 3 }, 1, "Child", root.albumId, 0)
        assertEquals(
            listOf("Earlier" to 0, "Root" to 0, "Child" to 1),
            albumRows(listOf(child, root, earlier)).map { (album, depth) -> album.name to depth },
        )
    }
}
