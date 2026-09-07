package dev.po4yka.chur.app

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.notes.Note
import dev.po4yka.chur.notes.NoteStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
        assertEquals(ChurStatus.INTERNAL_FAILURE.name, controller.message.value)
    }

    @Test
    fun a_boundary_failure_still_reports_its_own_status() = runTest(dispatcher) {
        val controller = controllerOver(
            FailingNotes { throw ChurFailure(ChurStatus.NOT_FOUND, "notes") },
        )

        controller.putNote(note())
        advanceUntilIdle()

        // The backstop must not swallow the status a normalized failure carries.
        assertEquals(ChurStatus.NOT_FOUND.name, controller.message.value)
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

        assertEquals(ChurStatus.INTERNAL_FAILURE.name, controller.message.value)
    }

    @Test
    fun a_host_import_report_survives_the_guard_that_now_carries_it() = runTest(dispatcher) {
        // `reportImport` runs inside the guard, and the guard clears the
        // message before the body. The message the host passed has to outlive
        // that, or every import outcome disappears from the surface.
        val controller = controllerOver(InertNotes)

        controller.reportImport("Imported 3 items.")
        advanceUntilIdle()

        assertEquals("Imported 3 items.", controller.message.value)
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
        val returned = CompletableDeferred<Unit>()

        controller.restoreBackup(sourceFd = -1, password = "not this package's") {
            returned.complete(Unit)
        }
        // The body suspends on `Dispatchers.Default`, which this scheduler does
        // not drive, so the wait is on the lambda rather than on the queue.
        returned.await()
        advanceUntilIdle()

        assertEquals(ChurStatus.INTERNAL_FAILURE.name, controller.message.value)
    }

    private fun controllerOver(notes: NoteStore) = ChurController(
        storageRoot = "/nonexistent",
        privacy = NoPrivacyCover,
        exports = NoExports,
        clock = { 0L },
        notes = notes,
    )

    private fun note() = Note(id = "n", title = "t", body = "b", updatedMs = 0L)

    /** A store whose every read and write fails the way [raise] says. */
    private class FailingNotes(private val raise: () -> Nothing) : NoteStore {
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
        override fun create(displayName: String, contentType: String): ExportSink.Destination? = null
    }
}
