package dev.po4yka.chur.app

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.notes.Note
import dev.po4yka.chur.notes.NoteStore
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

    /** No export can start in these tests, and none is attempted. */
    private object NoExports : ExportSink {
        override fun create(displayName: String, contentType: String): ExportSink.Destination? = null
    }
}
