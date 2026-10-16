package dev.po4yka.chur.app

import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.imports.Derivative
import dev.po4yka.chur.imports.MediaCodec
import dev.po4yka.chur.imports.PickedMedia
import dev.po4yka.chur.imports.ProbedMedia
import dev.po4yka.chur.notes.InMemoryNoteStore
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class IncomingShareHostTest {
    @Test
    fun pending_sources_are_not_opened_and_cannot_be_replaced_or_released_twice() {
        val inbox = IncomingShareInbox()
        val first = Source(2)
        val second = Source(1)
        assertTrue(inbox.offer(first))
        assertFalse(inbox.offer(second))
        assertEquals(1, second.closes)
        assertEquals(2, inbox.state.value?.count)
        assertEquals(0, first.opens)
        inbox.discard()
        inbox.discard()
        assertEquals(1, first.closes)
        assertNull(inbox.state.value)
        assertFalse(inbox.offer(Source(101)))
    }

    @Test
    fun locked_import_does_not_read_source_or_create_a_vault() = runBlocking {
        withController { controller, _ ->
            val source = Source(1)
            val inbox = IncomingShareInbox()
            assertTrue(inbox.offer(source))
            assertTrue(inbox.import(controller, MediaImporter(NoCodec), null).isEmpty())
            assertEquals(0, source.opens)
            assertEquals(0, source.closes)
            assertEquals(AppRoute.PublicShell, controller.route.value)
            assertEquals(1, inbox.state.value?.count)
            inbox.discard()
            assertEquals(1, source.closes)
        }
    }

    @Test
    fun confirmed_batch_keeps_successes_reports_unreadable_and_releases_every_source() = runBlocking {
        withController { controller, root ->
            controller.create("incoming-confirmation-password", offerRecovery = false)
            withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }
            val text = "A public shared-text fixture with an exact URL https://example.invalid/path?q=1".encodeToByteArray()
            val input = File(root, "shared.txt").apply { writeBytes(text) }
            var fdClosed = false
            val source = object : Source(2) {
                override suspend fun open(index: Int, prepare: (Long, Long) -> Boolean): PickedMedia? {
                    opens++
                    if (index == 1) return null
                    val stream = RandomAccessFile(input, "r")
                    val fd = stream.fd.javaClass.getDeclaredField("fd").apply { isAccessible = true }.getInt(stream.fd)
                    return PickedMedia(fd, true, text.size.toLong(), "text/plain", "shared.txt", null, null,
                        { fdClosed = true; stream.close() }, importAsFile = true)
                }
            }
            val inbox = IncomingShareInbox()
            inbox.offer(source)
            val outcomes = inbox.import(controller, MediaImporter(NoCodec), null)
            val saved = assertIs<MediaImporter.Outcome.Imported>(outcomes.first())
            assertEquals(MediaImporter.Outcome.Unreadable, outcomes.last())
            assertEquals(1, source.closes)
            assertTrue(fdClosed)
            assertNull(inbox.state.value)
            assertContentEquals(text, controller.vault.readRange(saved.objectId, 0, text.size))
            assertEquals(1, controller.vault.page(ObjectQuery()).objects.size)
            assertTrue(inbox.import(controller, MediaImporter(NoCodec), null).isEmpty())
            assertEquals(2, source.opens)
        }
    }

    private suspend fun withController(test: suspend (ChurController, File) -> Unit) {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val root = File(System.getProperty("java.io.tmpdir"), "chur-incoming-${System.nanoTime()}").apply { mkdirs() }
        val controller = ChurController(root.absolutePath, NoPrivacyCover, NoExports,
            clock = { 1_700_000_000_000L }, notes = InMemoryNoteStore())
        try { controller.start(); test(controller, root) }
        finally { controller.shutdown(); Dispatchers.resetMain(); root.deleteRecursively() }
    }

    private open class Source(override val count: Int) : IncomingShareSource {
        var opens = 0
        var closes = 0
        override suspend fun open(index: Int, prepare: (Long, Long) -> Boolean): PickedMedia? { opens++; error("not confirmed") }
        override fun close() { closes++ }
    }

    private object NoCodec : MediaCodec {
        override fun probe(media: PickedMedia): ProbedMedia? = error("opaque input needs no decoder")
        override fun derive(media: PickedMedia, probe: ProbedMedia, kind: StreamKind,
            cancelRequested: () -> Boolean): Derivative? = error("opaque input needs no derivatives")
    }

    private object NoExports : ExportSink {
        override fun cancelPending() = Unit
        override fun create(displayName: String, contentType: String): ExportSink.Destination? = null
        override fun create(displayName: String, contentType: String, target: ExportTarget, uri: String?): ExportSink.Destination? = null
    }
}
