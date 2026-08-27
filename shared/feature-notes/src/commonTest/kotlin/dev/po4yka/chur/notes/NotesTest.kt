package dev.po4yka.chur.notes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class NotesTest {
    private fun note(id: String, title: String, body: String, updated: Long, pinned: Boolean = false) =
        Note(id, title, body, updated, pinned)

    @Test
    fun pinned_notes_come_first_then_the_most_recently_updated() {
        val ordered = Notes.ordered(
            listOf(
                note("a", "A", "", 300),
                note("b", "B", "", 100, pinned = true),
                note("c", "C", "", 200),
            ),
        )
        assertEquals(listOf("b", "a", "c"), ordered.map { it.id })
    }

    @Test
    fun the_order_is_total_so_two_notes_updated_together_do_not_swap() {
        val first = Notes.ordered(listOf(note("b", "B", "", 100), note("a", "A", "", 100)))
        val again = Notes.ordered(listOf(note("a", "A", "", 100), note("b", "B", "", 100)))
        assertEquals(first.map { it.id }, again.map { it.id })
    }

    @Test
    fun search_matches_the_title_and_the_body_case_insensitively() {
        val notes = listOf(
            note("a", "Groceries", "milk and bread", 1),
            note("b", "Ideas", "a note about MILK", 2),
            note("c", "Other", "nothing here", 3),
        )
        assertEquals(setOf("a", "b"), Notes.search(notes, "milk").map { it.id }.toSet())
        assertTrue(Notes.search(notes, "nothing").map { it.id } == listOf("c"))
        assertEquals(3, Notes.search(notes, "   ").size, "an empty query is every note")
    }

    @Test
    fun a_note_with_no_title_shows_its_first_line_and_previews_the_second() {
        val note = note("a", "", "First line\n\nSecond line", 1)
        assertEquals("First line", note.displayTitle)
        assertEquals("Second line", note.preview)
    }

    @Test
    fun a_note_with_a_title_previews_its_first_body_line() {
        val note = note("a", "Title", "Body line\nmore", 1)
        assertEquals("Title", note.displayTitle)
        assertEquals("Body line", note.preview)
    }

    @Test
    fun the_in_memory_store_replaces_a_note_with_the_same_identifier() = runTest {
        val store = InMemoryNoteStore()
        store.put(note("a", "One", "", 1))
        store.put(note("a", "Two", "", 2))
        val all = store.all()
        assertEquals(1, all.size)
        assertEquals("Two", all.first().title)
        store.remove("a")
        assertTrue(store.all().isEmpty())
    }
}
