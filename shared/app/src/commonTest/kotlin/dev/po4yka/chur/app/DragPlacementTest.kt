package dev.po4yka.chur.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.po4yka.chur.app.vault.AlbumPlacement
import dev.po4yka.chur.app.vault.albumDrop
import dev.po4yka.chur.app.vault.albumPlacement
import dev.po4yka.chur.app.vault.edgeScrollDelta
import dev.po4yka.chur.app.vault.memberDrop
import dev.po4yka.chur.app.vault.rootAlbumDrop
import dev.po4yka.chur.ffi.AlbumSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DragPlacementTest {
    @Test
    fun album_drop_never_uses_the_source_as_its_own_anchor_or_creates_a_cycle() {
        val a = album(1, "A", 0)
        val b = album(2, "B", 1)
        val c = album(3, "C", 2)
        val child = album(4, "Child", 0, a.albumId)
        val albums = listOf(a, b, c, child)

        assertNull(albumDrop(b, a, AlbumPlacement.AFTER, albums)) // already after A
        assertEquals(null, albumDrop(a, c, AlbumPlacement.AFTER, albums)?.before)
        assertEquals(a.id, albumDrop(c, a, AlbumPlacement.BEFORE, albums)?.before?.id)
        assertNull(albumDrop(a, child, AlbumPlacement.INSIDE, albums))
        assertEquals(b.id, albumDrop(child, b, AlbumPlacement.INSIDE, albums)?.parent?.id)
        assertNull(rootAlbumDrop(c, albums)) // already the last root
        assertEquals(null, rootAlbumDrop(child, albums)?.parent)
        val card = Rect(0f, 0f, 100f, 100f)
        assertEquals(AlbumPlacement.BEFORE, albumPlacement(Offset(50f, 10f), card))
        assertEquals(AlbumPlacement.INSIDE, albumPlacement(Offset(50f, 50f), card))
        assertEquals(AlbumPlacement.AFTER, albumPlacement(Offset(50f, 90f), card))
    }

    @Test
    fun member_drop_keeps_page_boundaries_and_skips_no_op_moves() {
        val ids = listOf("a", "b", "c", "d")
        assertNull(memberDrop(ids, "b", "a", after = true, hasMore = false))
        assertEquals("d", memberDrop(ids, "a", "c", after = true, hasMore = false)?.beforeId)
        assertNull(memberDrop(ids, "a", "d", after = true, hasMore = true))
        assertEquals(null, memberDrop(ids, "a", "d", after = true, hasMore = false)?.beforeId)
        assertEquals("a", memberDrop(ids, "d", "a", after = false, hasMore = true)?.beforeId)
    }

    @Test
    fun edge_scroll_only_runs_inside_the_visible_viewport() {
        val viewport = Rect(0f, 0f, 100f, 100f)
        assertEquals(-4f, edgeScrollDelta(Offset(50f, 5f), viewport, 20f, 4f))
        assertEquals(4f, edgeScrollDelta(Offset(50f, 95f), viewport, 20f, 4f))
        assertEquals(0f, edgeScrollDelta(Offset(50f, 50f), viewport, 20f, 4f))
        assertEquals(0f, edgeScrollDelta(Offset(150f, 95f), viewport, 20f, 4f))
    }

    private fun album(id: Byte, name: String, position: Long, parent: ByteArray? = null) =
        AlbumSummary(ByteArray(16) { id }, 0, name, parent, position)

    private fun album(id: Int, name: String, position: Long, parent: ByteArray? = null) =
        album(id.toByte(), name, position, parent)
}
