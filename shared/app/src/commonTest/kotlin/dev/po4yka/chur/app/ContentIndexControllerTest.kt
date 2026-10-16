package dev.po4yka.chur.app

import dev.po4yka.chur.app.vault.ContentTextExtractor
import dev.po4yka.chur.app.vault.ContentTextSource
import dev.po4yka.chur.app.vault.ExtractedContent
import dev.po4yka.chur.ffi.ContentIndexCandidate
import dev.po4yka.chur.imports.DecoderCleanupException
import dev.po4yka.chur.vault.VaultState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
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
        assertEquals(ContentIndexState(), controller.state.value)
        assertContentEquals(ByteArray(text.size), text)
        assertEquals(1, access.closed)
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
        assertEquals("Content indexing could not finish. Try again.", controller.state.value.message)
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

    private class Engine(
        val action: suspend (ContentTextSource, String, Int, () -> Boolean, (Int, Int) -> Unit) -> ExtractedContent,
    ) : ContentTextExtractor {
        override suspend fun extract(source: ContentTextSource, contentType: String, mediaClass: Int,
            cancelRequested: () -> Boolean, onPage: (Int, Int) -> Unit) =
            action(source, contentType, mediaClass, cancelRequested, onPage)
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
        override suspend fun candidates(generation: Long, after: ByteArray?, rebuild: Boolean) =
            sources.filter { it.objectId[0].toInt() > (after?.get(0)?.toInt() ?: 0) &&
                (rebuild || it.objectId[0].toInt() !in saved) }.take(1)
        override suspend fun open(generation: Long, candidate: ContentIndexCandidate): ContentIndexLease {
            opened += candidate.objectId[0].toInt()
            return ContentIndexLease(ContentTextSource(candidate.plaintextSize, { _, count -> ByteArray(count) },
                { (state.value as? VaultState.Unlocked)?.generation == generation })) { closed++ }
        }
        override suspend fun put(generation: Long, candidate: ContentIndexCandidate, text: ByteArray, partial: Boolean) {
            check(!refuseWrites)
            check((state.value as? VaultState.Unlocked)?.generation == generation)
            saved += candidate.objectId[0].toInt()
        }
        override suspend fun clear(generation: Long) { saved.clear(); clears++ }
    }
}
