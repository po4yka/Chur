package dev.po4yka.chur.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaverScope
import dev.po4yka.chur.app.notes.DRAFT_STATE_LIMIT
import dev.po4yka.chur.app.notes.DraftSaver
import dev.po4yka.chur.app.notes.OpenNote
import dev.po4yka.chur.app.notes.OpenNoteSaver
import dev.po4yka.chur.notes.Note
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the note editor puts into saved state, which on Android is a Bundle
 * with a size ceiling that a note does not have.
 */
class NoteSavedStateTest {
    private val scope = SaverScope { true }

    @Test
    fun the_text_is_saved_once_and_only_up_to_the_limit() {
        // The open note keeps no text: the editor's draft is the one copy. It
        // comes back marked as restored, so the editor never reads the blank
        // text in its place.
        val note = Note(id = "n", title = "Title", body = "Body", updatedMs = 5L, pinned = true)
        val savedNote = with(OpenNoteSaver) { scope.save(OpenNote(note, isNew = true)) }!!
        assertEquals(
            OpenNote(note.copy(title = "", body = ""), isNew = true, restored = true),
            OpenNoteSaver.restore(savedNote),
        )

        val kept = "t" to "b".repeat(DRAFT_STATE_LIMIT - 1)
        val savedDraft = with(DraftSaver) { scope.save(mutableStateOf<Pair<String, String>?>(kept)) }!!
        assertEquals(kept, DraftSaver.restore(savedDraft)?.value)

        // One character more is left to the store, and restores as nothing.
        val tooLong = with(DraftSaver) { scope.save(mutableStateOf<Pair<String, String>?>("t" to "b".repeat(DRAFT_STATE_LIMIT))) }!!
        assertNull(DraftSaver.restore(tooLong)?.value)
        assertEquals(false, tooLong)
    }
}
