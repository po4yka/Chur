package dev.po4yka.chur.app

import dev.po4yka.chur.app.vault.ContentTextExtractor
import dev.po4yka.chur.app.vault.ContentTextSource
import dev.po4yka.chur.app.vault.ExtractedContent
import dev.po4yka.chur.ffi.ContentIndexCandidate
import dev.po4yka.chur.imports.DecoderCleanupException
import dev.po4yka.chur.vault.VaultState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ContentIndexControllerTest {
    @Test
    fun keyset_scan_keeps_successes_counts_partial_and_wipes_each_result() = runTest {
        val access = Access(3)
        val results = mutableListOf<ByteArray>()
        val engine = Engine { source, _, _, _, onPage ->
            onPage(1, 2)
            ExtractedContent("секрет".encodeToByteArray().also { results += it }, partial = source.size == 2L)
        }
        var refreshes = 0
        val controller = ContentIndexController(access.state, access, engine, this,
            { refreshes++ }, StandardTestDispatcher(testScheduler))
        controller.start()
        advanceUntilIdle()
        assertEquals(3, access.saved.size)
        assertEquals(listOf(1, 2, 3), access.opened)
        assertEquals(3, access.closed)
        assertEquals(3, controller.state.value.indexed)
        assertEquals(1, controller.state.value.partial)
        assertEquals(1, refreshes)
        assertTrue(results.all { bytes -> bytes.all { it == 0.toByte() } })
        assertFalse(controller.state.value.running)
        controller.start()
        advanceUntilIdle()
        assertEquals(3, access.opened.size, "successful items are skipped")
        controller.start(rebuild = true)
        advanceUntilIdle()
        assertEquals(6, access.opened.size, "rebuild processes existing index entries")
    }

    @Test
    fun a_failed_write_wipes_text_releases_reader_and_is_retried() = runTest {
        val access = Access(1).apply { refuseWrites = true }
        val text = "secret".encodeToByteArray()
        val controller = ContentIndexController(access.state, access,
            Engine { _, _, _, _, _ -> ExtractedContent(text, false) }, this, {}, StandardTestDispatcher(testScheduler))
        controller.start(); advanceUntilIdle()
        assertEquals(1, controller.state.value.failed)
        assertEquals(1, access.closed)
        assertContentEquals(ByteArray(text.size), text)
        assertTrue(access.saved.isEmpty())
        access.refuseWrites = false
        controller.start(); advanceUntilIdle()
        assertEquals(1, access.saved.size)
        assertEquals(0, controller.state.value.failed)
    }

    @Test
    fun cancel_waits_for_native_custody_and_never_publishes_unfinished_text() = runTest {
        val access = Access(2)
        val drain = CompletableDeferred<Unit>()
        val text = "unfinished".encodeToByteArray()
        val controller = ContentIndexController(access.state, access,
            Engine { _, _, _, _, _ ->
                withContext(NonCancellable) { drain.await() }
                ExtractedContent(text, false)
            }, this, {}, StandardTestDispatcher(testScheduler))
        controller.start(); runCurrent()
        controller.cancel(); runCurrent()
        assertTrue(controller.state.value.stopping)
        assertEquals(0, access.closed, "reader must remain owned until native extraction drains")
        controller.start()
        assertEquals(1, access.opened.size)
        drain.complete(Unit); advanceUntilIdle()
        assertTrue(access.saved.isEmpty())
        assertEquals(1, access.closed)
        assertContentEquals(ByteArray(text.size), text)
        assertFalse(controller.state.value.running)
        assertEquals("Indexing stopped. Saved results remain searchable.", controller.state.value.message)
    }

    @Test
    fun session_replacement_discards_late_text_and_progress() = runTest {
        val access = Access(1)
        val drain = CompletableDeferred<Unit>()
        val text = "old identity text".encodeToByteArray()
        val controller = ContentIndexController(access.state, access,
            Engine { _, _, _, _, page ->
                withContext(NonCancellable) { drain.await() }
                page(8, 8)
                ExtractedContent(text, false)
            }, this, {}, StandardTestDispatcher(testScheduler))
        controller.start(); runCurrent()
        access.state.value = VaultState.Locked()
        controller.reset()
        access.state.value = VaultState.Unlocked(2)
        drain.complete(Unit); advanceUntilIdle()
        assertTrue(access.saved.isEmpty())
        assertEquals(ContentIndexState(), controller.state.value.copy(runEpoch = 0))
        assertContentEquals(ByteArray(text.size), text)
        assertEquals(1, access.closed)
    }

    @Test
    fun reset_between_progress_validation_and_publication_rejects_the_old_run() = runTest {
        val access = Access(1)
        val vault = InterleavingState(access.state)
        val text = "old identity text".encodeToByteArray()
        lateinit var controller: ContentIndexController
        var resetState: ContentIndexState? = null
        controller = ContentIndexController(vault, access,
            Engine { _, _, _, _, onPage ->
                vault.afterSnapshot = {
                    controller.reset()
                    access.state.value = VaultState.Unlocked(2)
                    resetState = controller.state.value
                }
                onPage(8, 9)
                ExtractedContent(text, false)
            }, this, {}, StandardTestDispatcher(testScheduler))
        controller.start(); advanceUntilIdle()
        assertEquals(resetState, controller.state.value, "a validated old callback must not repaint a reset projection")
        assertTrue(access.saved.isEmpty())
        assertEquals(1, access.closed)
        assertContentEquals(ByteArray(text.size), text)
    }

    @Test
    fun reset_between_write_validation_and_count_publication_rejects_the_old_run() = runTest {
        val access = Access(1)
        val vault = InterleavingState(access.state)
        lateinit var controller: ContentIndexController
        var resetState: ContentIndexState? = null
        access.afterPut = {
            vault.afterSnapshot = {
                controller.reset()
                access.state.value = VaultState.Unlocked(2)
                resetState = controller.state.value
            }
        }
        controller = ContentIndexController(vault, access,
            Engine { _, _, _, _, _ -> ExtractedContent("old result".encodeToByteArray(), true) },
            this, {}, StandardTestDispatcher(testScheduler))
        controller.start(); advanceUntilIdle()
        assertEquals(resetState, controller.state.value, "saved old results must not add counts to a reset projection")
        assertEquals(1, access.saved.size, "the completed write belongs to the old session")
        assertEquals(1, access.closed)
    }

    @Test
    fun replacement_run_with_the_same_visible_counts_cannot_accept_the_old_write_count() = runTest {
        val access = Access(1)
        val vault = InterleavingState(access.state)
        lateinit var controller: ContentIndexController
        access.afterPut = {
            vault.afterSnapshot = {
                controller.reset()
                access.state.value = VaultState.Unlocked(2)
                controller.start()
                assertTrue(controller.state.value.running)
                assertEquals(0, controller.state.value.indexed)
            }
        }
        controller = ContentIndexController(vault, access,
            Engine { _, _, _, _, _ -> ExtractedContent("old result".encodeToByteArray(), true) },
            this, {}, StandardTestDispatcher(testScheduler))
        controller.start(); advanceUntilIdle()
        assertEquals(0, controller.state.value.indexed, "identical visible run states must not allow an ABA update")
        assertEquals(0, controller.state.value.partial)
        assertFalse(controller.state.value.running)
        assertEquals(1, access.closed)
    }

    @Test
    fun reset_between_failure_validation_and_count_publication_rejects_the_old_run() = runTest {
        val access = Access(1)
        val vault = InterleavingState(access.state)
        lateinit var controller: ContentIndexController
        var resetState: ContentIndexState? = null
        controller = ContentIndexController(vault, access,
            Engine { _, _, _, _, _ ->
                vault.afterSnapshot = {
                    controller.reset()
                    access.state.value = VaultState.Unlocked(2)
                    resetState = controller.state.value
                }
                error("private parser details")
            }, this, {}, StandardTestDispatcher(testScheduler))
        controller.start(); advanceUntilIdle()
        assertEquals(resetState, controller.state.value, "old failures must not enter the next projection")
        assertEquals(1, access.closed)
    }

    @Test
    fun cancel_keeps_clear_out_until_an_in_flight_write_and_reader_have_drained() = runTest {
        val access = Access(1)
        val entered = CompletableDeferred<Unit>()
        val drain = CompletableDeferred<Unit>()
        val text = "committed before cancellation".encodeToByteArray()
        access.beforePut = { entered.complete(Unit); drain.await() }
        val controller = ContentIndexController(access.state, access,
            Engine { _, _, _, _, _ -> ExtractedContent(text, false) },
            this, {}, StandardTestDispatcher(testScheduler))
        controller.start(); runCurrent()
        assertTrue(entered.isCompleted)
        controller.cancel(); controller.clear(); runCurrent()
        assertTrue(controller.state.value.stopping)
        assertEquals(0, access.clears)
        assertEquals(0, access.closed)
        drain.complete(Unit); advanceUntilIdle()
        assertEquals(setOf(1), access.saved)
        assertEquals(1, access.closed)
        assertContentEquals(ByteArray(text.size), text)
        controller.clear(); advanceUntilIdle()
        assertEquals(1, access.clears)
        assertTrue(access.saved.isEmpty(), "no old write may follow the completed clear")
    }

    @Test
    fun unconfirmed_decoder_shutdown_stops_the_scan_before_another_source_opens() = runTest {
        val access = Access(3)
        val controller = ContentIndexController(access.state, access,
            Engine { _, _, _, _, _ -> throw DecoderCleanupException("private SDK details") },
            this, {}, StandardTestDispatcher(testScheduler))
        controller.start(); advanceUntilIdle()
        assertEquals(listOf(1), access.opened)
        assertEquals(1, access.closed)
        assertTrue(access.saved.isEmpty())
        assertFalse(controller.state.value.running)
        val recovery = "Content indexing could not stop safely. Restart Chur before indexing more files."
        assertEquals(recovery, controller.state.value.message)
        controller.start(); advanceUntilIdle()
        assertEquals(listOf(1, 1), access.opened, "the persistent decoder refusal must stop each attempt at its first source")
        assertEquals(2, access.closed)
        assertTrue(access.saved.isEmpty())
        assertEquals(recovery, controller.state.value.message)
    }

    @Test
    fun cancellation_with_suppressed_decoder_shutdown_failure_requires_restart() = runTest {
        val access = Access(3)
        val fatal = DecoderCleanupException("private child diagnostics")
        val cancellation = CancellationException("private cancellation details").apply { addSuppressed(fatal) }
        var attempts = 0
        // The real withContext boundary in the controller also exercises JVM
        // stack-trace recovery, which can wrap this cancellation as its cause.
        val controller = ContentIndexController(access.state, access,
            Engine { _, _, _, _, _ ->
                attempts++
                // Android retains cancellation and attaches its unconfirmed
                // shutdown as a direct suppressed failure at the boundary.
                if (attempts == 1) throw cancellation else throw fatal
            }, this, {}, StandardTestDispatcher(testScheduler))
        controller.start(); advanceUntilIdle()
        val recovery = "Content indexing could not stop safely. Restart Chur before indexing more files."
        assertEquals(recovery, controller.state.value.message)
        assertEquals(listOf(1), access.opened)
        assertEquals(1, access.closed)
        assertTrue(access.saved.isEmpty())
        assertFalse(controller.state.value.running)
        assertEquals(listOf(fatal), cancellation.suppressedExceptions, "reporting must preserve the original failure")
        controller.start(); advanceUntilIdle()
        assertEquals(recovery, controller.state.value.message)
        assertEquals(listOf(1, 1), access.opened, "the persistent refusal must not open another candidate")
        assertEquals(2, access.closed)
        assertTrue(access.saved.isEmpty())
    }

    @Test
    fun clear_removes_only_index_entries_and_cannot_race_an_active_scan() = runTest {
        val access = Access(1)
        val controller = ContentIndexController(access.state, access,
            Engine { _, _, _, _, _ -> ExtractedContent("content".encodeToByteArray(), false) },
            this, {}, StandardTestDispatcher(testScheduler))
        controller.start(); controller.clear(); advanceUntilIdle()
        assertEquals(1, access.saved.size)
        assertEquals(0, access.clears)
        controller.clear(); controller.cancel(); advanceUntilIdle()
        assertTrue(access.saved.isEmpty())
        assertEquals(1, access.clears)
        assertEquals(1, access.sources.size)
        assertFalse(controller.state.value.running)
    }

    @Test
    fun a_failed_rebuild_keeps_saved_results_and_explains_which_action_retries_it() = runTest {
        val access = Access(1)
        var failExtraction = false
        var extractions = 0
        val controller = ContentIndexController(access.state, access,
            Engine { _, _, _, _, _ ->
                extractions++
                check(!failExtraction)
                ExtractedContent("saved original result".encodeToByteArray(), false)
            }, this, {}, StandardTestDispatcher(testScheduler))
        controller.start(); advanceUntilIdle()
        assertEquals(setOf(1), access.saved)
        failExtraction = true
        controller.start(rebuild = true); advanceUntilIdle()
        assertEquals(setOf(1), access.saved, "a failed rebuild must retain its saved index record")
        assertEquals(1, controller.state.value.failed)
        assertEquals("Content indexing finished. Use Rebuild all supported items to retry failed rebuilds. Saved results remain searchable.",
            controller.state.value.message)
        controller.start(); advanceUntilIdle()
        assertEquals(2, extractions, "the normal retry skips items that still have a saved index record")
        assertEquals(setOf(1), access.saved)
        failExtraction = false
        controller.start(rebuild = true); advanceUntilIdle()
        assertEquals(3, extractions)
        assertEquals(1, controller.state.value.indexed)
        assertEquals(0, controller.state.value.failed)
        assertEquals(setOf(1), access.saved)
    }

    private class Engine(
        val action: suspend (ContentTextSource, String, Int, () -> Boolean, (Int, Int) -> Unit) -> ExtractedContent,
    ) : ContentTextExtractor {
        override suspend fun extract(source: ContentTextSource, contentType: String, mediaClass: Int,
            cancelRequested: () -> Boolean, onPage: (Int, Int) -> Unit) =
            action(source, contentType, mediaClass, cancelRequested, onPage)
    }

    /** Models a worker paused after it reads a generation while Main resets the run. */
    private class InterleavingState(private val backing: StateFlow<VaultState>) : StateFlow<VaultState> by backing {
        var afterSnapshot: (() -> Unit)? = null
        override val value: VaultState
            get() {
                val snapshot = backing.value
                val action = afterSnapshot
                afterSnapshot = null
                action?.invoke()
                return snapshot
            }
    }

    private class Access(count: Int) : ContentIndexAccess {
        val state = MutableStateFlow<VaultState>(VaultState.Unlocked(1))
        val sources = List(count) { n -> ContentIndexCandidate(ByteArray(16) { (n + 1).toByte() },
            ByteArray(16) { (n + 2).toByte() }, 4, (n + 1).toLong(), "text/plain") }
        val saved = mutableSetOf<Int>()
        val opened = mutableListOf<Int>()
        var closed = 0
        var clears = 0
        var refuseWrites = false
        var beforePut: suspend () -> Unit = {}
        var afterPut: () -> Unit = {}
        override suspend fun candidates(generation: Long, after: ByteArray?, rebuild: Boolean) =
            sources.filter { it.objectId[0].toInt() > (after?.get(0)?.toInt() ?: 0) &&
                (rebuild || it.objectId[0].toInt() !in saved) }.take(1)
        override suspend fun open(generation: Long, candidate: ContentIndexCandidate): ContentIndexLease {
            opened += candidate.objectId[0].toInt()
            return ContentIndexLease(ContentTextSource(candidate.plaintextSize, { _, count -> ByteArray(count) },
                { (state.value as? VaultState.Unlocked)?.generation == generation })) { closed++ }
        }
        override suspend fun put(generation: Long, candidate: ContentIndexCandidate, text: ByteArray, partial: Boolean) {
            // The native commit is synchronous: cancellation cannot interrupt it.
            withContext(NonCancellable) {
                beforePut()
                check(!refuseWrites)
                check((state.value as? VaultState.Unlocked)?.generation == generation)
                saved += candidate.objectId[0].toInt()
                afterPut()
            }
        }
        override suspend fun clear(generation: Long) { saved.clear(); clears++ }
    }
}
