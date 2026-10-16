package dev.po4yka.chur.app

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.PrivateNote
import dev.po4yka.chur.vault.VaultState
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real JNI notes and controller operations; only the host destination is supplied here. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PrivateNoteExportHostTest {
    @Test
    fun a_saved_note_exports_exact_utf8_and_publishes_before_closing_its_destination() = scenario {
        controller.exportPrivateNote(request, ExportTarget.SHARE)
        val output = sink.closedOutput()
        val notice = withTimeout(10_000) { controller.notice.first { it?.text?.startsWith("Note exported.") == true } }

        assertNotNull(notice)
        assertEquals(Creation(request.filename, "text/plain", ExportTarget.SHARE, null, expected.size.toLong()), output.creation)
        assertEquals(note.exportSize, expected.size.toLong())
        assertContentEquals(expected, output.file.readBytes())
        assertContentEquals(expected, output.bytesAtPublication)
        assertEquals(listOf("publishChecked", "close"), output.events)
        assertNull(controller.activeOperation.value)
        assertEquals(1, sink.created)
    }

    @Test
    fun a_changed_revision_discards_the_document_that_the_files_picker_already_created() = scenario {
        val changed = controller.vault.savePrivateNote(generation, note.noteId, note.revision,
            note.title, "A later saved body", note.pinned)
        assertTrue(changed.revision > request.revision)
        sink.returnedDocument = File(root, "picker-returned.txt").apply { createNewFile() }

        controller.exportPrivateNote(request, ExportTarget.FILES, RETURNED_URI)
        assertRejectedReturnedDocument(ChurStatus.CONFLICT)
    }

    @Test
    fun a_lock_and_new_unlock_discard_a_returned_document_from_the_old_session() = scenario {
        controller.lock()
        withTimeout(10_000) { controller.route.first { it == AppRoute.PublicShell } }
        controller.unlock(PASSWORD)
        val current = withTimeout(10_000) {
            controller.vault.state.first { it is VaultState.Unlocked && it.generation != generation }
        } as VaultState.Unlocked
        withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }
        assertTrue(current.generation != request.generation)
        sink.returnedDocument = File(root, "old-session-picker-returned.txt").apply { createNewFile() }

        controller.exportPrivateNote(request, ExportTarget.FILES, RETURNED_URI)
        assertRejectedReturnedDocument(ChurStatus.SESSION_EXPIRED)
    }

    @Test
    fun a_provider_refusing_publication_discards_the_completed_plaintext_and_reports_failure() = scenario {
        sink.allowPublication = false
        controller.exportPrivateNote(request, ExportTarget.SHARE)
        val output = sink.closedOutput()
        val notice = withTimeout(10_000) { controller.notice.first { it != null } }!!

        assertContentEquals(expected, output.bytesAtPublication)
        assertEquals(listOf("publishChecked", "discard", "close"), output.events)
        assertFalse(output.file.exists())
        assertEquals(userCopy(ChurStatus.IO_FAILURE), notice.text)
        assertFalse("Note exported." in notice.text)
        assertNull(controller.activeOperation.value)
    }

    @Test
    fun another_active_operation_still_discards_a_late_files_picker_document() = scenario {
        // A real album query reaches directory creation under tracked(). The
        // host lease waits here, so the competing operation cannot finish by chance.
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val rootClosed = CompletableDeferred<Unit>()
        val childCloses = AtomicInteger()
        val child = object : ExportDirectory {
            override fun createDirectory(displayName: String): ExportDirectory? = error("the album has no children")
            override fun createFile(displayName: String, contentType: String, plaintextSize: Long): ExportSink.Destination? =
                error("the empty album has no files")
            override fun close() { childCloses.incrementAndGet() }
        }
        sink.directory = object : ExportDirectory {
            override fun createDirectory(displayName: String): ExportDirectory {
                entered.complete(Unit)
                check(release.await(10, TimeUnit.SECONDS)) { "the test did not release its directory lease" }
                return child
            }
            override fun createFile(displayName: String, contentType: String, plaintextSize: Long): ExportSink.Destination? =
                error("the selected root contains only the album folder")
            override fun close() { rootClosed.complete(Unit) }
        }
        val albumId = controller.vault.createAlbum("Empty public test album")
        controller.exportAlbum(albumId, "transient-directory-token")
        try {
            withTimeout(10_000) { entered.await() }
            val operation = assertNotNull(controller.activeOperation.value)
            assertEquals("album-export", operation.name)
            sink.returnedDocument = File(root, "busy-picker-returned.txt").apply { createNewFile() }

            controller.exportPrivateNote(request, ExportTarget.FILES, RETURNED_URI)
            val output = sink.closedOutput()
            val notice = withTimeout(10_000) {
                controller.notice.first { it?.text == "Finish the current operation first." }
            }!!
            assertEquals("Finish the current operation first.", notice.text)
            assertReturnedDocumentWasDiscarded(output)
            assertEquals(operation.id, controller.activeOperation.value?.id)
        } finally {
            release.countDown()
            withTimeout(10_000) { rootClosed.await() }
            withTimeout(10_000) { controller.activeOperation.first { it == null } }
        }
        assertEquals(1, childCloses.get())
    }

    private fun scenario(test: suspend Fixture.() -> Unit) = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val root = File(System.getProperty("java.io.tmpdir"), "chur-private-note-export-${System.nanoTime()}").apply { mkdirs() }
        val sink = RecordingSink(root)
        val controller = ChurController(root.absolutePath, NoPrivacyCover, sink, clock = { 1_700_000_000_000L })
        try {
            controller.start()
            controller.create(PASSWORD, offerRecovery = false)
            withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }
            val generation = (controller.vault.state.value as VaultState.Unlocked).generation
            val note = controller.vault.savePrivateNote(generation, title = "План 🌍", body = "Literal <script>text</script>\nΚαλημέρα\n日本語 🔐")
            Fixture(root, sink, controller, generation, note).test()
        } finally {
            controller.vault.shutdown()
            Dispatchers.resetMain()
            root.deleteRecursively()
        }
    }

    private class Fixture(
        val root: File,
        val sink: RecordingSink,
        val controller: ChurController,
        val generation: Long,
        val note: PrivateNote,
    ) {
        val request = PrivateNoteExportRequest.from(note, generation)
        val expected = (note.title + "\n\n" + note.body).encodeToByteArray()

        suspend fun assertRejectedReturnedDocument(status: ChurStatus) {
            val output = sink.closedOutput()
            val notice = withTimeout(10_000) { controller.notice.first { it != null } }!!
            assertReturnedDocumentWasDiscarded(output)
            assertEquals(userCopy(status), notice.text)
            assertFalse("Note exported." in notice.text)
            assertNull(controller.activeOperation.value)
        }

        fun assertReturnedDocumentWasDiscarded(output: Output) {
            assertEquals(Creation("", "", ExportTarget.FILES, RETURNED_URI, null), output.creation)
            assertEquals(listOf("discard", "close"), output.events)
            assertNull(output.bytesAtPublication)
            assertFalse(output.file.exists())
            assertEquals(1, sink.created)
        }
    }

    private data class Creation(
        val displayName: String,
        val contentType: String,
        val target: ExportTarget,
        val uri: String?,
        val plaintextSize: Long?,
    )

    private class RecordingSink(private val root: File) : ExportSink {
        var returnedDocument: File? = null
        var directory: ExportDirectory? = null
        var allowPublication = true
        var created = 0
            private set
        private val ready = CompletableDeferred<Output>()
        override fun cancelPending() = Unit
        override fun openDirectory(uri: String): ExportDirectory? = directory
        override fun create(displayName: String, contentType: String): ExportSink.Destination? =
            error("the controller must supply its export target")
        override fun create(displayName: String, contentType: String, target: ExportTarget, uri: String?): ExportSink.Destination =
            createOutput(Creation(displayName, contentType, target, uri, null))
        override fun create(displayName: String, contentType: String, target: ExportTarget, uri: String?, plaintextSize: Long): ExportSink.Destination =
            createOutput(Creation(displayName, contentType, target, uri, plaintextSize))
        private fun createOutput(creation: Creation): Output {
            created++
            val file = if (creation.target == ExportTarget.FILES) requireNotNull(returnedDocument) else File(root, "note-export.txt")
            return Output(file, creation, allowPublication).also { ready.complete(it) }
        }
        suspend fun closedOutput(): Output = withTimeout(10_000) {
            ready.await().also { it.closed.await() }
        }
    }

    private class Output(val file: File, val creation: Creation, private val allowPublication: Boolean) : ExportSink.Destination {
        private val output = RandomAccessFile(file, "rw")
        override val descriptor = output.fd.javaClass.getDeclaredField("fd").apply { isAccessible = true }.getInt(output.fd)
        val events = mutableListOf<String>()
        var bytesAtPublication: ByteArray? = null
            private set
        val closed = CompletableDeferred<Unit>()
        override fun publish(): Unit = error("the controller must check publication acceptance")
        override fun publishChecked(): Boolean {
            events += "publishChecked"
            bytesAtPublication = file.readBytes()
            return allowPublication
        }
        override fun discard() { events += "discard"; file.delete() }
        override fun close() {
            events += "close"
            output.close()
            closed.complete(Unit)
        }
    }

    private companion object {
        const val PASSWORD = "private-note-export-test-password"
        const val RETURNED_URI = "content://test-provider/returned-note-document"
    }
}
