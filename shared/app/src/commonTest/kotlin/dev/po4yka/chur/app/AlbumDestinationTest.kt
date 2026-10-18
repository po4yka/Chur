package dev.po4yka.chur.app

import dev.po4yka.chur.app.vault.albumDestinations
import dev.po4yka.chur.app.vault.loadedObjectSelection
import dev.po4yka.chur.ffi.AlbumSummary
import dev.po4yka.chur.ffi.ObjectPage
import dev.po4yka.chur.ffi.ObjectProjection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlbumDestinationTest {
    @Test
    fun duplicate_names_keep_complete_paths_and_the_correct_identity() {
        val family = album(1, "Family")
        val work = album(2, "Work")
        val familyYear = album(3, "2026", family)
        val workYear = album(4, "2026", work)
        val leaf = album(5, "Photos", familyYear)
        val destinations = albumDestinations(listOf(leaf, workYear, work, familyYear, family))
        assertEquals(listOf("Family", "Family / 2026", "Family / 2026 / Photos", "Work", "Work / 2026"),
            destinations.map { it.path })
        assertEquals(workYear.id, destinations.single { it.path == "Work / 2026" }.album.id)
        assertEquals(listOf("Family / 2026", "Family / 2026 / Photos"),
            destinations.filter { it.path.contains("Family / 2026") }.map { it.path })
    }

    @Test
    fun move_excludes_the_entire_source_tree_but_keeps_its_ancestors_and_other_branches() {
        val root = album(1, "Root")
        val source = album(2, "Source", root)
        val child = album(3, "Child", source)
        val leaf = album(4, "Leaf", child)
        val sibling = album(5, "Sibling", root)
        assertEquals(listOf(root.id, sibling.id),
            albumDestinations(listOf(leaf, sibling, child, source, root), source.id).map { it.album.id })
    }

    @Test
    fun identical_full_paths_have_distinct_destination_labels() {
        val first = album(1, "Same")
        val second = album(2, "Same")
        val destinations = albumDestinations(listOf(first, second))
        assertEquals(2, destinations.map { it.path }.toSet().size)
        assertTrue(destinations.single { it.album.id == first.id }.path.contains(first.id))
        assertTrue(destinations.single { it.album.id == second.id }.path.contains(second.id))
    }

    @Test
    fun broken_or_ambiguous_paths_are_not_offered_as_destinations() {
        val root = album(1, "Root")
        val missing = album(2, "Missing")
        val orphan = album(3, "Orphan", missing)
        val cycleA = album(4, "Cycle A").copy(parentId = ByteArray(16) { 5 })
        val cycleB = album(5, "Cycle B", cycleA)
        assertEquals(listOf(root.id), albumDestinations(listOf(root, orphan, cycleA, cycleB)).map { it.album.id })
        assertTrue(albumDestinations(listOf(root, root.copy(name = "Conflicting root"))).isEmpty())
    }

    @Test
    fun select_loaded_items_does_not_claim_unfetched_results_and_includes_later_loaded_pages() {
        val all = (1..300).map(::projection)
        val first = ObjectPage(all.take(128), 300, 1, byteArrayOf(1))
        val second = first.copy(objects = all.take(256), nextCursor = byteArrayOf(2))
        val final = second.copy(objects = all, nextCursor = null)
        val firstSelection = loadedObjectSelection(first)
        assertEquals(128, firstSelection.size)
        assertEquals(all.take(128).map { it.id }.toSet(), firstSelection)
        assertTrue(all[128].id !in firstSelection)
        assertEquals(all.take(256).map { it.id }.toSet(), loadedObjectSelection(second))
        assertEquals(all.map { it.id }.toSet(), loadedObjectSelection(final))
        assertTrue(loadedObjectSelection(first.copy(objects = emptyList())).isEmpty())
    }

    private fun album(id: Int, name: String, parent: AlbumSummary? = null) =
        AlbumSummary(ByteArray(16) { id.toByte() }, 0, name, parent?.albumId)

    private fun projection(id: Int) = ObjectProjection(
        byteArrayOf((id ushr 8).toByte(), id.toByte()), byteArrayOf(), 1, 0, 0,
        false, 1, 1, 1, 0, false, 0, 0, false,
    )
}
