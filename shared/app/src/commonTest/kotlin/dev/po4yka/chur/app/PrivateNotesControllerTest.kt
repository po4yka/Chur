package dev.po4yka.chur.app

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.PrivateNote
import dev.po4yka.chur.ffi.PrivateNoteAction
import dev.po4yka.chur.ffi.PrivateNotePage
import dev.po4yka.chur.ffi.PrivateNoteSummary
import dev.po4yka.chur.vault.VaultState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PrivateNotesControllerTest {
    @Test
    fun a_new_note_is_only_a_session_draft_until_explicit_save() = runTest {
        val access = Access()
        val controller = controller(access)
        runCurrent()
        controller.create()
        controller.title("Private title")
        controller.body("Private body")
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(0, access.saves)
        assertTrue(controller.state.value.editor!!.dirty)
        controller.save()
        advanceUntilIdle()
        assertEquals(1, access.saves)
        assertFalse(controller.state.value.editor!!.dirty)
        assertEquals("Private body", controller.state.value.editor!!.saved!!.body)
        controller.close()
    }

    @Test
    fun a_save_failure_keeps_the_draft_and_back_requires_an_explicit_choice() = runTest {
        val access = Access().apply { saveFailure = ChurStatus.STORAGE_UNAVAILABLE }
        val controller = controller(access)
        controller.create(); controller.body("keep this text")
        controller.back()
        assertEquals(PrivateNoteConfirmation.LEAVE, controller.state.value.confirmation)
        controller.dismissConfirmation()
        assertEquals("keep this text", controller.state.value.editor!!.body)
        controller.saveAndBack()
        advanceUntilIdle()
        assertNotNull(controller.state.value.error)
        assertEquals("keep this text", controller.state.value.editor!!.body)
        assertTrue(controller.state.value.editor!!.dirty)
        controller.back(); controller.discardAndBack()
        assertNull(controller.state.value.editor)
        assertEquals(1, access.saves)
        controller.close()
    }

    @Test
    fun lock_clears_text_and_a_late_save_result_cannot_restore_it_or_export_in_a_new_identity() = runTest {
        val state = MutableStateFlow<VaultState>(VaultState.Unlocked(1))
        val blocked = CompletableDeferred<Unit>()
        val access = Access().apply { saveGate = blocked }
        var exports = 0
        val controller = controller(access, state, onExport = { exports++ })
        controller.create(); controller.body("old identity draft")
        controller.saveAndExport()
        runCurrent()
        assertTrue(controller.state.value.busy)
        state.value = VaultState.Unlocked(2)
        runCurrent()
        assertNull(controller.state.value.editor)
        assertTrue(controller.state.value.notes.isEmpty())
        blocked.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, exports)
        assertNull(controller.state.value.editor)
        controller.body("late input")
        controller.save()
        assertNull(controller.state.value.editor)
    }

    @Test
    fun closing_a_dirty_editor_never_writes_from_disposal() = runTest {
        val access = Access()
        val controller = controller(access)
        controller.create(); controller.body("discard on lock")
        controller.close()
        advanceUntilIdle()
        assertEquals(0, access.saves)
        assertNull(controller.state.value.editor)
    }

    @Test
    fun search_is_debounced_and_only_the_latest_query_reaches_the_list() = runTest {
        val access = Access()
        val controller = controller(access)
        runCurrent()
        controller.search("first")
        advanceTimeBy(100)
        controller.search("second")
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf("", "second"), access.queries)
        assertEquals("second", controller.state.value.terms)
        controller.close()
    }

    @Test
    fun oversized_search_keeps_the_last_valid_query_and_does_not_call_native() = runTest {
        val access = Access()
        val controller = controller(access)
        advanceUntilIdle()
        controller.search("é".repeat(128))
        advanceUntilIdle()
        controller.search("é".repeat(129))
        advanceUntilIdle()
        assertEquals("é".repeat(128), controller.state.value.terms)
        assertEquals(2, access.queries.size)
        assertNotNull(controller.state.value.error)
        controller.search((1..33).joinToString(" ") { "x" })
        advanceUntilIdle()
        assertEquals(2, access.queries.size)
        controller.close()
    }

    @Test
    fun a_mutation_does_not_reuse_the_previous_catalog_cursor() = runTest {
        val access = Access().apply { paged = true }
        val controller = controller(access)
        advanceUntilIdle()
        controller.nextPage()
        advanceUntilIdle()
        assertEquals(2, controller.state.value.page)
        controller.pin(summary(access.record))
        advanceUntilIdle()
        assertNull(access.cursors.last())
        assertEquals(1, controller.state.value.page)
        controller.nextPage()
        advanceUntilIdle()
        controller.open(summary(access.record))
        advanceUntilIdle()
        controller.body("changed")
        controller.saveAndBack()
        advanceUntilIdle()
        assertNull(access.cursors.last())
        assertEquals(1, controller.state.value.page)
        controller.close()
    }

    @Test
    fun paging_uses_opaque_cursors_and_keeps_only_one_summary_page() = runTest {
        val access = Access().apply { paged = true }
        val controller = controller(access)
        advanceUntilIdle()
        assertTrue(controller.state.value.hasNext)
        assertEquals(1, controller.state.value.notes.size)
        controller.nextPage()
        advanceUntilIdle()
        assertEquals(2, controller.state.value.page)
        assertTrue(controller.state.value.hasPrevious)
        assertEquals(1, controller.state.value.notes.size)
        assertEquals(1, access.cursors.last()!!.single().toInt())
        controller.previousPage()
        advanceUntilIdle()
        assertEquals(1, controller.state.value.page)
        assertNull(access.cursors.last())
        controller.close()
    }

    @Test
    fun trash_restore_and_permanent_delete_use_the_loaded_revision_and_require_confirmation() = runTest {
        val access = Access()
        val controller = controller(access)
        advanceUntilIdle()
        controller.open(summary(access.record)); advanceUntilIdle()
        controller.trash() // No confirmation: do not remove anything.
        assertTrue(access.changes.isEmpty())
        controller.body("unsaved change")
        controller.requestTrash(); controller.trash(); advanceUntilIdle()
        assertEquals(PrivateNoteAction.TRASH, access.changes.last().first)
        assertEquals(1L, access.changes.last().second)
        assertNull(controller.state.value.editor)
        access.record = access.record.copy(trashedAtMs = 10)
        controller.open(summary(access.record)); advanceUntilIdle()
        controller.restore(); advanceUntilIdle()
        assertEquals(PrivateNoteAction.RESTORE, access.changes.last().first)
        access.record = access.record.copy(trashedAtMs = 10)
        controller.open(summary(access.record)); advanceUntilIdle()
        controller.purge()
        assertEquals(2, access.changes.size)
        controller.requestPurge(); controller.purge(); advanceUntilIdle()
        assertEquals(PrivateNoteAction.PURGE, access.changes.last().first)
        controller.close()
    }

    @Test
    fun export_of_dirty_text_requires_save_and_uses_only_the_committed_result() = runTest {
        val access = Access()
        val exported = mutableListOf<PrivateNote>()
        val controller = controller(access, onExport = { exported += it })
        advanceUntilIdle()
        controller.open(summary(access.record)); advanceUntilIdle()
        controller.body("new body")
        controller.export()
        assertEquals(PrivateNoteConfirmation.EXPORT, controller.state.value.confirmation)
        assertTrue(exported.isEmpty())
        controller.saveAndExport(); advanceUntilIdle()
        assertEquals("new body", exported.single().body)
        assertEquals(2L, exported.single().revision)
        controller.close()
    }

    @Test
    fun pin_toggles_the_committed_summary_and_trash_search_is_a_separate_query() = runTest {
        val access = Access()
        val controller = controller(access)
        advanceUntilIdle()
        controller.pin(summary(access.record)); advanceUntilIdle()
        assertEquals(PrivateNoteAction.PIN, access.changes.single().first)
        controller.showTrash(true); advanceUntilIdle()
        assertTrue(access.trashQueries.last())
        controller.close()
    }

    @Test
    fun byte_limits_accept_unicode_at_the_boundary_and_preserve_text_on_overflow() = runTest {
        val controller = controller(Access())
        controller.create()
        controller.title("é".repeat(128))
        assertEquals(256, utf8Size(controller.state.value.editor!!.title))
        controller.title("é".repeat(129))
        assertEquals("é".repeat(128), controller.state.value.editor!!.title)
        assertNotNull(controller.state.value.error)
        controller.body("🌍".repeat(PRIVATE_NOTE_BODY_MAX_BYTES / 4))
        assertEquals(PRIVATE_NOTE_BODY_MAX_BYTES, utf8Size(controller.state.value.editor!!.body))
        controller.body(controller.state.value.editor!!.body + "x")
        assertEquals(PRIVATE_NOTE_BODY_MAX_BYTES, utf8Size(controller.state.value.editor!!.body))
        controller.close()
    }

    @Test
    fun actual_edits_notify_activity_without_a_background_keepalive() = runTest {
        var touches = 0
        val controller = controller(Access(), onActivity = { touches++ })
        advanceUntilIdle()
        assertEquals(0, touches)
        controller.create()
        controller.title("Title")
        controller.body("Body")
        assertEquals(2, touches)
        controller.body("Body") // No actual edit.
        advanceTimeBy(200_000)
        runCurrent()
        assertEquals(2, touches)
        controller.close()
    }

    private fun kotlinx.coroutines.test.TestScope.controller(access: Access,
        state: MutableStateFlow<VaultState> = MutableStateFlow(VaultState.Unlocked(1)),
        onExport: (PrivateNote) -> Unit = {}, onActivity: () -> Unit = {}): PrivateNotesController =
        PrivateNotesController(1, state, access, this, {}, onExport, StandardTestDispatcher(testScheduler), onActivity)

    private class Access : PrivateNotesAccess {
        var record = note()
        var saves = 0
        var saveFailure: ChurStatus? = null
        var saveGate: CompletableDeferred<Unit>? = null
        var paged = false
        val queries = mutableListOf<String>()
        val trashQueries = mutableListOf<Boolean>()
        val cursors = mutableListOf<ByteArray?>()
        val changes = mutableListOf<Pair<PrivateNoteAction, Long>>()
        override suspend fun page(generation: Long, terms: String, trash: Boolean, cursor: ByteArray?): PrivateNotePage {
            queries += terms; trashQueries += trash; cursors += cursor
            return PrivateNotePage(listOf(summary(record)), if (paged && cursor == null) byteArrayOf(1) else null)
        }
        override suspend fun load(generation: Long, id: ByteArray) = record
        override suspend fun save(generation: Long, saved: PrivateNote?, title: String, body: String, pinned: Boolean): PrivateNote {
            saves++
            saveGate?.let { withContext(NonCancellable) { it.await() } }
            saveFailure?.let { throw ChurFailure(it, "private note") }
            record = record.copy(revision = (saved?.revision ?: 0) + 1, title = title, body = body, pinned = pinned)
            return record
        }
        override suspend fun change(generation: Long, id: ByteArray, revision: Long, action: PrivateNoteAction): PrivateNote? {
            changes += action to revision
            return record
        }
    }
    companion object {
        private fun note() = PrivateNote(byteArrayOf(1), 1, "Saved title", "Saved body", 1, 1, false, null, 23)
        private fun summary(note: PrivateNote) = PrivateNoteSummary(note.noteId, note.revision, note.title, note.body.take(160),
            note.createdMs, note.updatedMs, note.pinned, note.trashedAtMs, note.exportSize)
    }
}
