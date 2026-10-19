package dev.po4yka.chur.notes

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

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

    @Test
    fun new_notes_use_markdown_but_legacy_json_remains_plain_text() {
        assertEquals(NoteFormat.MARKDOWN, note("new", "", "# heading", 1).format)
        val legacy = Json.decodeFromString(
            Note.serializer(),
            """{"id":"old","title":"","body":"# literal heading","updatedMs":1}""",
        )
        assertEquals(NoteFormat.PLAIN_TEXT, legacy.format)
        assertEquals("# literal heading", legacy.body)
    }

    @Test
    fun both_note_formats_round_trip_the_exact_source() {
        val source = "# Heading\r\n\r\n**bold**  \n- [ ] task\n\tcode \\ \"quoted\"\n"
        for (format in NoteFormat.entries) {
            val original = Note("note", "Title", source, 1, pinned = true, format = format)
            val encoded = Json.encodeToString(Note.serializer(), original)
            assertEquals(original, Json.decodeFromString(Note.serializer(), encoded))
        }
    }

    @Test
    fun memory_store_supports_organization_trash_history_and_epoch_fencing() = runTest {
        val store = InMemoryNoteStore()
        val original = store.put(note("a", "Original", "first", 1))
        store.updateOrganization("a", "Work", listOf("tag"))
        val attachment = store.addAttachment("a", "file.bin", byteArrayOf(3, 4))
        val withFile = store.all().single()
        store.removeAttachment("a", attachment.id)
        val revision = store.history("a").single { it.note.revision == withFile.revision }
        val restored = store.restoreRevision("a", revision.id)
        assertEquals(withFile.attachments, restored.attachments)
        assertEquals("Work", restored.folder)
        assertEquals(listOf(3.toByte(), 4.toByte()), store.readAttachment("a", attachment.id).toList())
        assertFailsWith<IllegalStateException> { store.put(original.copy(body = "late")) }
        store.remove("a")
        assertTrue(store.all().isEmpty())
        assertEquals(1, store.trash().size)
        store.restore("a", 10)
        assertEquals(1, store.all().size)
        store.remove("a")
        store.purge("a")
        assertTrue(store.trash().isEmpty())
    }

    @Test
    fun organization_uses_exact_labels_and_unsigned_utf8_order() {
        assertEquals(listOf("A", "a", "\uE000", "\uD83D\uDE00"), NoteOrganization.tags(listOf("\uD83D\uDE00", " a ", "A", "\uE000", "a")))
        val note = note("a", "Title", "Body", 1).copy(tags = listOf("A"))
        assertTrue(NoteOrganization.filter(listOf(note), tag = "a").isEmpty())
        assertEquals(listOf(note), NoteOrganization.filter(listOf(note), tag = "A"))
    }

    @Test
    fun timestamp_only_autosaves_do_not_displace_meaningful_history() = runTest {
        val store = InMemoryNoteStore()
        val note = store.put(note("a", "Title", "Body", 1))
        repeat(30) { store.put(note.copy(updatedMs = 100L + it)) }
        assertEquals(note, store.all().single())
        assertTrue(store.history("a").isEmpty())
    }

    @Test
    fun rapid_text_autosaves_keep_the_pre_burst_state_and_checkpoint_each_minute() {
        val original = note("a", "Original", "before typing", 1)
        var state = NoteState().put(original, now = 0)
        repeat(80) { index ->
            state = state.put(state.current("a").copy(body = "typed $index"), now = 1_000L + index * 500)
        }
        assertEquals("typed 79", state.current("a").body)
        assertEquals(listOf(original), state.history["a"].orEmpty().map { it.note })
        assertEquals(80L, state.current("a").revision, "current state advances on every autosave")
        state = state.put(state.current("a").copy(body = "last before checkpoint"), now = 60_999)
        assertEquals(1, state.history["a"].orEmpty().size)
        val beforeSecondWindow = state.current("a")
        state = state.put(beforeSecondWindow.copy(body = "new checkpoint"), now = 61_000)
        assertEquals(listOf(beforeSecondWindow, original), state.history["a"].orEmpty().map { it.note })
        state.validate()
    }

    @Test
    fun explicit_format_pin_organization_files_trash_and_restore_always_checkpoint() {
        var state = NoteState().put(note("a", "Original", "before typing", 1), now = 0)
        var time = 1_000L
        fun type(body: String) {
            state = state.put(state.current("a").copy(body = body), now = time++)
        }
        type("typed")
        state = state.put(state.current("a").copy(format = NoteFormat.PLAIN_TEXT), now = time++)
        assertEquals(2, state.history["a"].orEmpty().size)
        type("after format")
        assertEquals(3, state.history["a"].orEmpty().size, "explicit changes start a fresh text window")
        state = state.put(state.current("a").copy(pinned = true), now = time++)
        assertEquals(4, state.history["a"].orEmpty().size)
        fun explicit(change: (Note) -> Note) {
            val before = state.notes.getValue("a")
            val count = state.history["a"].orEmpty().size
            state = state.replace(change(before).copy(revision = nextRevision(before)), savedAtMs = time++)
            assertEquals(count + 1, state.history["a"].orEmpty().size)
            assertEquals(before, state.history["a"].orEmpty().first().note)
        }
        explicit { it.copy(folder = "Work", tags = listOf("tag")) }
        val attachment = NoteAttachment("0123456789abcdef0123456789abcdef", "file.bin", "application/octet-stream", 1)
        explicit { it.copy(attachments = listOf(attachment)) }
        explicit { it.copy(attachments = emptyList()) }
        explicit { it.copy(trashedAtMs = time, recoveryEpoch = nextEpoch(it)) }
        explicit { it.copy(trashedAtMs = null, recoveryEpoch = nextEpoch(it)) }
        explicit { state.history.getValue("a").last().note.copy(recoveryEpoch = nextEpoch(it)) }
        state.validate()
    }

    @Test
    fun history_prunes_oldest_unique_blobs_to_the_64_mib_budget() {
        var state = NoteState().replace(note("a", "", "", 1))
        fun descriptor(index: Int): NoteAttachment {
            val id = index.toString(16).padStart(32, '0')
            return NoteAttachment(id, "file.bin", "application/octet-stream", NoteAttachments.MAX_FILE_BYTES)
        }
        for (index in 1..10) {
            val current = state.current("a")
            state = state.replace(current.copy(attachments = listOf(descriptor(index)), revision = nextRevision(current)))
        }
        state.validate()
        val historical = state.history["a"].orEmpty()
        assertEquals(8, historical.size)
        assertEquals((2..9).map(::descriptor).map { it.storageId }.toSet(), historical.flatMap { it.note.attachments }.map { it.storageId }.toSet())
        assertEquals(9, state.blobs().size, "eight historical blobs plus the current blob")
    }

}
