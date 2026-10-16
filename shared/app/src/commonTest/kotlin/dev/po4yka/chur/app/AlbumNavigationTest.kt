package dev.po4yka.chur.app

import dev.po4yka.chur.app.vault.AlbumOrder
import dev.po4yka.chur.app.vault.albumChildren
import dev.po4yka.chur.app.vault.albumParent
import dev.po4yka.chur.app.vault.albumPath
import dev.po4yka.chur.ffi.AlbumSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AlbumNavigationTest {
    @Test
    fun duplicate_names_use_ids_and_parent_bytes_use_content_equality() {
        val root = album(1, "Same")
        val other = album(2, "Same")
        val child = album(3, "Same", root.albumId.copyOf())
        val leaf = album(4, "Same", child.albumId.copyOf())
        val albums = listOf(leaf, other, child, root)

        assertEquals(listOf(root, child, leaf), albumPath(albums, leaf.id))
        assertSame(child, albumParent(albums, leaf.id))
        assertNull(albumParent(albums, root.id))
    }

    @Test
    fun deep_path_is_iterative_and_preserves_every_ancestor() {
        val albums = (1..1000).map { number ->
            album(number, "Album $number", if (number == 1) null else id(number - 1))
        }
        assertEquals(albums, albumPath(albums.asReversed(), albums.last().id))
        assertEquals(albums[998], albumParent(albums, albums.last().id))
    }

    @Test
    fun current_rows_replace_renamed_and_moved_snapshots() {
        val formerRoot = album(1, "Former root")
        val newRoot = album(2, "New root")
        val child = album(3, "Before", formerRoot.albumId)
        assertEquals(listOf(formerRoot, child), albumPath(listOf(formerRoot, newRoot, child), child.id))

        val moved = child.copy(name = "Renamed", parentId = newRoot.albumId.copyOf())
        val current = listOf(formerRoot, moved, newRoot)
        assertEquals(listOf(newRoot, moved), albumPath(current, child.id))
        assertSame(moved, albumPath(current, child.id).last())
        assertSame(newRoot, albumParent(current, child.id))

        val promoted = moved.copy(parentId = null)
        assertEquals(listOf(promoted), albumPath(listOf(formerRoot, newRoot, promoted), child.id))
        assertNull(albumParent(listOf(promoted), child.id))
    }

    @Test
    fun root_and_removed_current_resolve_to_root() {
        val root = album(1, "Root")
        assertTrue(albumPath(listOf(root), null).isEmpty())
        assertTrue(albumPath(listOf(root), album(2, "Removed").id).isEmpty())
        assertTrue(albumPath(emptyList(), root.id).isEmpty())
        assertNull(albumParent(listOf(root), null))
    }

    @Test
    fun missing_parent_does_not_return_a_partial_path() {
        val orphan = album(2, "Orphan", id(1))
        val leaf = album(3, "Leaf", orphan.albumId)
        val albums = listOf(orphan, leaf)
        assertTrue(albumPath(albums, leaf.id).isEmpty())
        assertNull(albumParent(albums, leaf.id))
        assertTrue(albumChildren(albums, orphan.id).isEmpty())
    }

    @Test
    fun self_cycle_and_multi_album_cycle_resolve_to_root() {
        val self = album(1, "Self", id(1))
        assertTrue(albumPath(listOf(self), self.id).isEmpty())

        val first = album(2, "First", id(3))
        val second = album(3, "Second", first.albumId)
        val leaf = album(4, "Leaf", first.albumId)
        val albums = listOf(first, second, leaf)
        assertTrue(albumPath(albums, leaf.id).isEmpty())
        assertNull(albumParent(albums, leaf.id))
        assertTrue(albumChildren(albums, first.id).isEmpty())
    }

    @Test
    fun ambiguous_duplicate_catalog_identity_does_not_choose_an_arbitrary_parent() {
        val first = album(1, "First")
        val duplicate = first.copy(name = "Other", albumId = first.albumId.copyOf())
        val child = album(2, "Child", first.albumId)
        assertTrue(albumPath(listOf(first, duplicate, child), child.id).isEmpty())
    }

    @Test
    fun children_are_immediate_and_keep_the_existing_sibling_orders() {
        val root = album(1, "Root")
        val other = album(2, "Other", position = 1)
        val zebra = album(3, "Zebra", root.albumId, position = 0, count = 1)
        val apple = album(4, "Apple", root.albumId, position = 1, count = 5)
        val grandchild = album(5, "Grandchild", zebra.albumId)
        val unrelated = album(6, "Unrelated", other.albumId)
        val albums = listOf(grandchild, apple, unrelated, other, zebra, root)

        assertEquals(listOf(root, other), albumChildren(albums, null))
        assertEquals(listOf(zebra, apple), albumChildren(albums, root.id))
        assertEquals(listOf(apple, zebra), albumChildren(albums, root.id, AlbumOrder.NAME_ASC))
        assertEquals(listOf(apple, zebra), albumChildren(albums, root.id, AlbumOrder.COUNT_DESC))
        assertEquals(listOf(grandchild), albumChildren(albums, zebra.id))
        assertTrue(albumChildren(albums, album(7, "Missing").id).isEmpty())
    }

    private fun album(
        number: Int,
        name: String,
        parent: ByteArray? = null,
        position: Long = 0,
        count: Long = 0,
    ) = AlbumSummary(id(number), count, name, parent, position)

    private fun id(number: Int): ByteArray = ByteArray(16).also {
        it[0] = (number ushr 24).toByte()
        it[1] = (number ushr 16).toByte()
        it[2] = (number ushr 8).toByte()
        it[3] = number.toByte()
    }
}
