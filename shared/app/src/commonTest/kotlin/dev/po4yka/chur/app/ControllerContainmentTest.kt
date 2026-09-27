package dev.po4yka.chur.app

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.notes.InMemoryNoteStore
import dev.po4yka.chur.notes.Note
import dev.po4yka.chur.notes.NoteStore
import dev.po4yka.chur.sync.SyncTransportFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a failing action does to the process.
 *
 * `docs/ERROR_MODEL.md` requires a platform layer to normalize before a feature
 * sees it, and every surface action runs inside the controller's one guard. The
 * guard used to catch [ChurFailure] alone, so an adapter that raised anything
 * else left an uncaught exception in a coroutine and ended the process — a
 * crash rather than the orderly lock `PLAINTEXT_LIFECYCLE.md` §8 describes, and
 * one a user reached by tapping an ordinary settings row.
 *
 * These tests drive the guard through the one seam that needs no vault: the
 * note store, which the constructor takes.
 */
class ControllerContainmentTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun installMain() {
        // The controller builds its scope in its constructor, so the dispatcher
        // has to exist before one is made.
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun removeMain() {
        Dispatchers.resetMain()
    }

    @Test
    fun an_unnormalized_platform_failure_becomes_a_message_rather_than_a_crash() = runTest(
        dispatcher,
    ) {
        val controller = controllerOver(FailingNotes { error("the platform adapter raised its own") })

        controller.putNote(note())
        advanceUntilIdle()

        // Folded the way `ChurStatus.fromValue` folds an unrecognized code: not
        // success, not benign, and not a reason to end the process.
        assertEquals(userCopy(ChurStatus.INTERNAL_FAILURE), controller.notice.value?.text)
    }

    @Test
    fun a_boundary_failure_still_reports_its_own_status() = runTest(dispatcher) {
        val controller = controllerOver(
            FailingNotes { throw ChurFailure(ChurStatus.NOT_FOUND, "notes") },
        )

        controller.putNote(note())
        advanceUntilIdle()

        // The backstop must not swallow the status a normalized failure carries.
        assertEquals(userCopy(ChurStatus.NOT_FOUND), controller.notice.value?.text)
    }

    @Test
    fun a_sync_transport_failure_reports_its_own_status() = runTest(dispatcher) {
        // Sync setup rethrows the transport's failure, which is not a
        // `ChurFailure`. The note store stands in for that seam here: the
        // guard must read the status it carries, not fold it into the backstop.
        // A wrong bootstrap secret is `AUTHENTICATION_FAILED` on the server
        // (`SERVER_OPERATOR.md`), and it must not read as a failed unlock.
        val controller = controllerOver(
            FailingNotes {
                throw SyncTransportFailure(ChurStatus.AUTHENTICATION_FAILED, "sync server rejected bootstrap")
            },
        )

        controller.putNote(note())
        advanceUntilIdle()

        assertEquals(syncCopy(ChurStatus.AUTHENTICATION_FAILED), controller.notice.value?.text)
        assertNotEquals(userCopy(ChurStatus.AUTHENTICATION_FAILED), controller.notice.value?.text)
    }

    @Test
    fun a_throwable_the_guard_does_not_catch_still_reaches_a_message() = runTest(dispatcher) {
        // The guard catches `Exception`, so an `Error` walks past it, and a
        // coroutine the guard did not start walks past it as well. Both used to
        // reach the platform default handler, which ends the process. The
        // scope's own handler is the net under both.
        val controller = controllerOver(
            FailingNotes { throw AssertionError("the platform layer raised an Error") },
        )

        controller.putNote(note())
        advanceUntilIdle()

        assertEquals(userCopy(ChurStatus.INTERNAL_FAILURE), controller.notice.value?.text)
    }

    @Test
    fun a_host_import_report_survives_the_guard_that_now_carries_it() = runTest(dispatcher) {
        // `reportImport` runs inside the guard, and the guard clears the
        // message before the body. The message the host passed has to outlive
        // that, or every import outcome disappears from the surface.
        val controller = controllerOver(InertNotes)

        controller.reportImport("Imported 3 items.")
        advanceUntilIdle()

        assertEquals("Imported 3 items.", controller.notice.value?.text)
    }

    @Test
    fun a_refused_restore_still_returns_the_source_the_host_opened() = runTest(dispatcher) {
        // `BACKUP_FORMAT_V1.md` §8 reads a file the user picked, and the host
        // opened its descriptor. The controller returns it on a refusal exactly
        // as on a success: a descriptor the application keeps is a file the
        // platform cannot reclaim, and the picker of a second attempt would be
        // opening the same file again. This controller has no open runtime, so
        // the repository refuses before the boundary is reached.
        val controller = controllerOver(InertNotes)
        val returned = CompletableDeferred<Boolean>()

        controller.goTo(AppRoute.RestoreBackup)
        controller.restoreBackup(sourceFd = -1, password = "not this package's") { cancelled ->
            returned.complete(cancelled)
        }
        // The body suspends on `Dispatchers.Default`, which this scheduler does
        // not drive, so the wait is on the lambda rather than on the queue.
        val cancelled = returned.await()
        advanceUntilIdle()

        assertEquals(userCopy(ChurStatus.INTERNAL_FAILURE), controller.formError.value)
        // A refusal is a failure, not a cancellation: the route must not show
        // it as the calm cancelled state.
        assertFalse(cancelled)
    }

    @Test
    fun a_notice_does_not_outlive_the_route_it_was_shown_on() = runTest(dispatcher) {
        // `DESIGN.md` §26: an outcome is shown once, on the screen of the
        // action. It used to stay in one untyped message across routes, so a
        // second vault set up from Settings opened its creation form with the
        // vault's last outcome in error red.
        val controller = controllerOver(InertNotes)

        controller.goTo(AppRoute.Vault)
        controller.report("Imported into vault.")
        val shown = assertNotNull(controller.notice.value)
        assertEquals("Imported into vault.", shown.text)
        // A snackbar that ends marks its own notice, never a newer one.
        controller.report("Imported into album.")
        controller.consume(shown.id)
        assertEquals("Imported into album.", controller.notice.value?.text)

        controller.createSecondIdentity()
        assertEquals(null, controller.notice.value)
        assertEquals(null, controller.formError.value)

        controller.goTo(AppRoute.Vault)
        controller.report("Imported into vault.")
        controller.goTo(AppRoute.PublicSettings)
        assertEquals(null, controller.notice.value)

        controller.goTo(AppRoute.Vault)
        controller.report("Imported into vault.")
        controller.lock()
        controller.route.first { it == AppRoute.PublicShell }
        assertEquals(null, controller.notice.value)
    }

    @Test
    fun a_vault_failure_is_a_notice_and_never_a_form_error() = runTest(dispatcher) {
        val controller = controllerOver(
            FailingNotes { throw ChurFailure(ChurStatus.NOT_FOUND, "notes") },
        )
        controller.goTo(AppRoute.Vault)

        controller.putNote(note())
        advanceUntilIdle()

        assertEquals(userCopy(ChurStatus.NOT_FOUND), controller.notice.value?.text)
        assertEquals(null, controller.formError.value)
    }

    @Test
    fun a_vault_action_that_ends_after_the_route_changed_reports_nowhere() = runTest(dispatcher) {
        // The unlock screens read any refusal as a failed attempt, so an
        // outcome that arrived after a lock or a route change used to show a
        // credential the user had not entered yet as refused.
        val release = CompletableDeferred<Unit>()
        val controller = controllerOver(
            FailingNotes {
                release.await()
                throw ChurFailure(ChurStatus.NOT_FOUND, "notes")
            },
        )
        controller.goTo(AppRoute.Vault)
        controller.putNote(note())
        advanceUntilIdle()

        controller.createSecondIdentity()
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(AppRoute.CreateVault, controller.route.value)
        assertEquals(null, controller.formError.value)
        assertEquals(null, controller.notice.value)
    }

    @Test
    fun a_credential_form_shows_its_own_refusal() = runTest(dispatcher) {
        val controller = controllerOver(InertNotes)
        controller.goTo(AppRoute.CreateVault)

        controller.create("123", offerRecovery = false)
        advanceUntilIdle()

        assertEquals("Use at least 12 digits for a vault PIN.", controller.formError.value)
        assertEquals(null, controller.notice.value)
    }

    @Test
    fun locking_cancels_pending_app_owned_exports() = runTest(dispatcher) {
        val exports = PendingExports()
        val controller = controllerOver(InertNotes, exports)

        controller.lock()
        advanceUntilIdle()
        exports.cancelled.await()

        assertFalse(exports.pending)
    }

    @Test
    fun a_note_is_written_only_when_its_text_changed_and_carries_the_write_time() = runTest(
        dispatcher,
    ) {
        val store = InMemoryNoteStore(
            listOf(Note(id = "kept", title = "Kept", body = "", updatedMs = 5L)),
        )
        val controller = controllerOver(store, clock = { 9L })

        // The editor saves as it closes whatever happened, so a note opened
        // and left as it was reaches the controller too. That is not a write,
        // and nor is a new note with nothing in it; neither is the first
        // public-shell write `DISCREET_MODE.md` discloses.
        controller.putNote(Note(id = "new", title = "", body = "", updatedMs = 1L))
        controller.putNote(Note(id = "kept", title = "Kept", body = "", updatedMs = 5L))
        advanceUntilIdle()

        assertEquals(listOf("kept" to 5L), store.all().map { it.id to it.updatedMs })
        assertFalse(controller.disclosureDue.value)

        // A real edit is stamped with the time it was written rather than the
        // time the note was opened, so it sorts to the top of the list.
        controller.putNote(Note(id = "new", title = "", body = "milk", updatedMs = 1L))
        advanceUntilIdle()

        assertEquals(
            listOf("new" to 9L, "kept" to 5L),
            controller.notesState.value.map { it.id to it.updatedMs },
        )
        assertTrue(controller.disclosureDue.value)
    }

    private fun controllerOver(
        notes: NoteStore,
        exports: ExportSink = NoExports,
        clock: () -> Long = { 0L },
    ) = ChurController(
        storageRoot = "/nonexistent",
        privacy = NoPrivacyCover,
        exports = exports,
        clock = clock,
        notes = notes,
    )

    private fun note() = Note(id = "n", title = "t", body = "b", updatedMs = 0L)

    /** A store whose every read and write fails the way [raise] says. */
    private class FailingNotes(private val raise: suspend () -> Nothing) : NoteStore {
        override suspend fun all(): List<Note> = raise()

        override suspend fun put(note: Note) = raise()

        override suspend fun remove(id: String) = raise()

        override suspend fun disclosureAcknowledged(): Boolean = raise()

        override suspend fun acknowledgeDisclosure() = raise()
    }

    /** A store that answers rather than fails, for the tests that need no failure. */
    private object InertNotes : NoteStore {
        override suspend fun all(): List<Note> = emptyList()

        override suspend fun put(note: Note) = Unit

        override suspend fun remove(id: String) = Unit

        override suspend fun disclosureAcknowledged(): Boolean = true

        override suspend fun acknowledgeDisclosure() = Unit
    }

    /** No export can start in these tests, and none is attempted. */
    private object NoExports : ExportSink {
        override fun cancelPending() = Unit
        override fun create(displayName: String, contentType: String): ExportSink.Destination? = null
        override fun create(
            displayName: String,
            contentType: String,
            target: ExportTarget,
            uri: String?,
        ): ExportSink.Destination? = null
    }

    private class PendingExports : ExportSink {
        var pending = true
        val cancelled = CompletableDeferred<Unit>()

        override fun cancelPending() {
            pending = false
            cancelled.complete(Unit)
        }

        override fun create(displayName: String, contentType: String): ExportSink.Destination? = null
        override fun create(
            displayName: String,
            contentType: String,
            target: ExportTarget,
            uri: String?,
        ): ExportSink.Destination? = null
    }
}
