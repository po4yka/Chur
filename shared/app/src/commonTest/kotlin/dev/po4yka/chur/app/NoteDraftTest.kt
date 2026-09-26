package dev.po4yka.chur.app

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import dev.po4yka.chur.app.notes.OpenNote
import dev.po4yka.chur.app.notes.rememberNoteDraft
import dev.po4yka.chur.notes.Note
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the note editor writes and removes, and when.
 *
 * [rememberNoteDraft] draws nothing, so it is composed here without a window,
 * on the test's virtual clock: the autosave's pause passes only when a case
 * lets it, and leaving is the composition's end, as every way out of the
 * editor is.
 */
class NoteDraftTest {
    @Test
    fun only_leaving_removes_a_blank_note_and_only_one_the_editor_created() = runTest {
        // "Create note", a word, a pause, the word erased, and a pause.
        val created = Editor(this, OpenNote(Note(id = "new", title = "", body = "", updatedMs = 1L), isNew = true))
        created.type("milk")
        advanceUntilIdle()
        created.type("")
        advanceUntilIdle()

        assertEquals(listOf("milk", ""), created.saved.map { it.body })
        assertEquals(emptyList(), created.removed, "a pause with the fields empty is not leaving")
        created.leave()
        assertEquals(listOf("new"), created.removed)

        // A stored note the user emptied earlier, opened, left for a pause,
        // and closed.
        val emptied = Editor(this, OpenNote(Note(id = "kept", title = "", body = "", updatedMs = 1L), isNew = false))
        advanceUntilIdle()
        emptied.leave()

        assertEquals(emptyList(), emptied.removed, "opening a stored note must not remove it")
    }

    @Test
    fun leaving_writes_the_draft_the_autosave_has_not() = runTest {
        val editor = Editor(this, OpenNote(Note(id = "n", title = "", body = "", updatedMs = 1L), isNew = true))
        editor.type("milk")

        editor.leave()
        advanceUntilIdle()

        assertEquals(listOf("milk"), editor.saved.map { it.body })
    }

    @Test
    fun a_restore_without_the_draft_closes_and_writes_nothing() = runTest {
        // Saved state brought the open note back but not the editor's own
        // draft; the note's text is the saver's blank placeholder.
        val placeholder = Note(id = "kept", title = "", body = "", updatedMs = 1L)
        val editor = Editor(this, OpenNote(placeholder, isNew = false, restored = true))

        assertNull(editor.draft.value, "the editor closes")
        advanceUntilIdle()
        editor.leave()

        assertEquals(emptyList(), editor.saved, "the placeholder must not blank the stored note")
        assertEquals(emptyList(), editor.removed)
    }

    /** [rememberNoteDraft] for [open], composed on [test]'s scheduler. */
    private class Editor(private val test: TestScope, open: OpenNote) {
        val saved = mutableListOf<Note>()
        val removed = mutableListOf<String>()
        lateinit var draft: MutableState<Pair<String, String>?>
        private val clock = BroadcastFrameClock()
        private val recomposer = Recomposer(test.coroutineContext + clock)
        private val composition = Composition(NoNodes(), recomposer)

        init {
            test.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
            composition.setContent {
                draft = rememberNoteDraft(open, onSave = { saved += it }, onRemove = { removed += it })
            }
            test.runCurrent()
        }

        /** Replaces the body, as typing does, and runs the frame that composes it. */
        fun type(body: String) {
            draft.value = "" to body
            Snapshot.sendApplyNotifications()
            test.runCurrent()
            clock.sendFrame(0L)
            test.runCurrent()
        }

        /** Takes the editor out of composition. */
        fun leave() {
            composition.dispose()
            recomposer.cancel()
            test.runCurrent()
        }
    }

    /** An applier for a composition that emits no nodes. */
    private class NoNodes : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit

        override fun insertBottomUp(index: Int, instance: Unit) = Unit

        override fun remove(index: Int, count: Int) = Unit

        override fun move(from: Int, to: Int, count: Int) = Unit

        override fun onClear() = Unit
    }
}
