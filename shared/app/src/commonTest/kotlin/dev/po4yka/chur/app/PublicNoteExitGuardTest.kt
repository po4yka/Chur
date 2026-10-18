package dev.po4yka.chur.app

import dev.po4yka.chur.app.notes.PublicNoteExitGuard
import dev.po4yka.chur.notes.Note
import dev.po4yka.chur.notes.NoteFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PublicNoteExitGuardTest {
    @Test
    fun failed_save_keeps_the_editor_and_retry_closes_only_after_the_matching_acknowledgement() {
        val saved = Note("note", "Title", "old source", 1, format = NoteFormat.PLAIN_TEXT)
        val draft = saved.copy(body = "# exact new source  \n", pinned = true, format = NoteFormat.MARKDOWN)
        val guard = PublicNoteExitGuard()
        val writes = mutableListOf<Note>()
        var closes = 0
        guard.request(draft, saved, writes::add) { closes++ }
        // A failed writer leaves the persisted projection unchanged.
        guard.acknowledge(draft, saved) { closes++ }
        assertTrue(guard.requested)
        assertEquals(0, closes)
        guard.request(draft, saved, writes::add) { closes++ }
        guard.acknowledge(draft, draft.copy(updatedMs = 99)) { closes++ }
        assertEquals(listOf(draft, draft), writes)
        assertEquals(1, closes)
        assertFalse(guard.requested)
    }

    @Test
    fun acknowledgement_includes_identity_title_body_pin_and_format() {
        val draft = Note("note", "Title", "# body", 7, pinned = true, format = NoteFormat.MARKDOWN)
        val guard = PublicNoteExitGuard()
        var closes = 0
        guard.request(draft, null, {}) { closes++ }
        for (mismatch in listOf(draft.copy(id = "other"), draft.copy(title = "Other"), draft.copy(body = "old"),
            draft.copy(pinned = false), draft.copy(format = NoteFormat.PLAIN_TEXT))) {
            guard.acknowledge(draft, mismatch) { closes++ }
        }
        assertTrue(guard.requested)
        assertEquals(0, closes)
        guard.acknowledge(draft, draft) { closes++ }
        assertEquals(1, closes)
    }

    @Test
    fun discard_and_keep_editing_cancel_the_pending_close_without_another_write() {
        val draft = Note("note", "Title", "body", 1)
        val guard = PublicNoteExitGuard()
        var writes = 0
        var closes = 0
        guard.request(draft, null, { writes++ }) { closes++ }
        guard.keepEditing()
        guard.acknowledge(draft, draft) { closes++ }
        assertEquals(0, closes)
        guard.request(draft, null, { writes++ }) { closes++ }
        guard.discard { closes++ }
        assertEquals(2, writes)
        assertEquals(1, closes)
        assertFalse(guard.requested)
    }
}
