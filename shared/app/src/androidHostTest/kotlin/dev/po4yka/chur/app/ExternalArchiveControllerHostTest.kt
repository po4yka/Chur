package dev.po4yka.chur.app

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.imports.PickedMedia
import dev.po4yka.chur.vault.VaultRepository
import dev.po4yka.chur.vault.VaultState
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real controller, provider descriptors, JNI, and authenticated archive restore. Public fixtures only. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExternalArchiveControllerHostTest {
    @Test
    fun public_export_without_a_vault_closes_sources_once_and_restores_existing_archive_format() = scenario {
        enter()
        assertTrue(needsPrivacyCover(VaultState.NoVault, AppRoute.EncryptFiles))
        assertTrue(needsPrivacyCover(VaultState.Locked(), AppRoute.EncryptFiles))
        val source = source("public-document.txt", "External public fixture 🔐".encodeToByteArray())
        val credential = ArchiveCredential(ARCHIVE_PASSWORD)
        controller.exportExternalArchive(controller.externalArchiveVisit, source.selection, credential, ExportTarget.SHARE)
        val output = sink.closedOutput()
        withTimeout(TIMEOUT) { controller.activeOperation.first { it == null } }
        assertEquals(listOf("publishChecked", "close"), output.events.toList())
        assertContentEquals("CHURBAK1".encodeToByteArray(), output.file.inputStream().use { it.readNBytes(8) })
        assertEquals(1, source.sourceCloses.get()); assertEquals(1, source.selectionCloses.get())
        assertCleared(credential)
        assertTrue(controller.vaultState.value is VaultState.NoVault)
        assertEquals(AppRoute.EncryptFiles, controller.route.value)
        assertContentEquals(source.bytes, source.file.readBytes())

        val restored = VaultRepository(File(root, "restored-vault").path, { 1_700_000_000_000L })
        try {
            restored.start()
            RandomAccessFile(output.file, "r").use { input ->
                val operation = restored.beginRestore(descriptor(input), ARCHIVE_PASSWORD.encodeToByteArray())
                try {
                    withTimeout(TIMEOUT) {
                        var progress = restored.poll(operation)
                        while (!progress.terminal) { delay(10); progress = restored.poll(operation) }
                        assertEquals(0, progress.status)
                    }
                } finally { restored.closeOperation(operation) }
            }
            restored.unlock(ARCHIVE_PASSWORD.encodeToByteArray())
            val item = restored.page(ObjectQuery()).objects.single()
            assertEquals("public-document.txt", restored.detail(item.objectId).filename)
            assertContentEquals(source.bytes, restored.readRange(item.objectId, 0, source.bytes.size))
        } finally { restored.shutdown() }
    }

    @Test
    fun export_from_public_settings_keeps_a_locked_existing_vault_and_catalog_unchanged() = scenario {
        createVault()
        val generation = (controller.vaultState.value as VaultState.Unlocked).generation
        val note = controller.vault.savePrivateNote(generation, title = "Existing fixture", body = "Keep this private fixture")
        val identity = assertNotNull(controller.vault.syncIdentity()).vaultId
        controller.lock()
        withTimeout(TIMEOUT) { controller.route.first { it == AppRoute.PublicShell } }
        enter()
        val source = source("external.bin", byteArrayOf(1, 2, 3))
        val credential = ArchiveCredential(ARCHIVE_PASSWORD)
        controller.exportExternalArchive(controller.externalArchiveVisit, source.selection, credential, ExportTarget.SHARE)
        val output = sink.closedOutput()
        withTimeout(TIMEOUT) { controller.activeOperation.first { it == null } }
        assertEquals(listOf("publishChecked", "close"), output.events.toList())
        assertTrue(controller.vaultState.value is VaultState.Locked)
        controller.closeExternalArchive()
        controller.unlock(VAULT_PASSWORD)
        withTimeout(TIMEOUT) { controller.route.first { it == AppRoute.Vault } }
        val current = (controller.vaultState.value as VaultState.Unlocked).generation
        assertContentEquals(identity, assertNotNull(controller.vault.syncIdentity()).vaultId)
        assertEquals("Keep this private fixture", controller.vault.privateNote(current, note.noteId).body)
        assertEquals(1, controller.vault.privateNotes(current).notes.size)
        assertEquals(0, controller.vault.page(ObjectQuery()).objects.size)
        assertEquals(1, source.sourceCloses.get()); assertEquals(1, source.selectionCloses.get())
        assertCleared(credential)
    }

    @Test
    fun output_capacity_failure_discards_the_partial_result_and_clears_all_owners() = scenario {
        enter()
        sink.capacity = 1
        val source = source("source.bin", byteArrayOf(5, 6, 7))
        val credential = ArchiveCredential(ARCHIVE_PASSWORD)
        controller.exportExternalArchive(controller.externalArchiveVisit, source.selection, credential, ExportTarget.SHARE)
        val output = sink.closedOutput()
        withTimeout(TIMEOUT) { controller.activeOperation.first { it == null } }
        assertEquals(listOf("discard", "close"), output.events.toList())
        assertFalse(output.file.exists())
        assertCleared(credential)
        assertEquals(1, source.sourceCloses.get()); assertEquals(1, source.selectionCloses.get())
        assertTrue(controller.vaultState.value is VaultState.NoVault)
    }

    @Test
    fun a_stale_route_visit_releases_selection_and_password_without_opening_any_source() = scenario {
        enter()
        val oldVisit = controller.externalArchiveVisit
        controller.closeExternalArchive()
        enter()
        val source = source("stale.bin", byteArrayOf(9))
        val credential = ArchiveCredential(ARCHIVE_PASSWORD)
        controller.exportExternalArchive(oldVisit, source.selection, credential, ExportTarget.SHARE)
        withTimeout(TIMEOUT) { while (source.selectionCloses.get() == 0) delay(10) }
        assertEquals(0, source.opens.get()); assertEquals(0, source.sourceCloses.get())
        assertEquals(1, source.selectionCloses.get())
        assertCleared(credential)
        assertEquals(0, sink.created.get())
        assertNull(controller.activeOperation.value)
    }

    @Test
    fun cancellation_while_the_provider_is_opening_releases_the_late_descriptor_without_publication() = scenario {
        enter()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val source = source("cancelled.bin", byteArrayOf(4, 3, 2)) { entered.complete(Unit); release.await() }
        val credential = ArchiveCredential(ARCHIVE_PASSWORD)
        controller.exportExternalArchive(controller.externalArchiveVisit, source.selection, credential, ExportTarget.SHARE)
        withTimeout(TIMEOUT) { entered.await() }
        controller.cancelActiveOperation()
        assertCleared(credential)
        assertEquals(0, source.opens.get(), "the provider is still blocked")
        release.complete(Unit)
        withTimeout(TIMEOUT) { while (source.selectionCloses.get() == 0) delay(10) }
        withTimeout(TIMEOUT) { controller.activeOperation.first { it == null } }
        assertEquals(1, source.sourceCloses.get()); assertEquals(1, source.selectionCloses.get())
        assertCleared(credential)
        assertTrue(sink.closedOutputs.all { "publishChecked" !in it.events })
        assertTrue(sink.closedOutputs.all { !it.file.exists() })
        assertTrue(controller.vaultState.value is VaultState.NoVault)
    }

    @Test
    fun disposing_a_pending_destination_picker_clears_credential_and_discards_a_late_document() = scenario {
        enter()
        val owner = ExternalArchiveOwner(controller)
        val source = source("pending.bin", byteArrayOf(7, 8))
        owner.choose { result -> result(source.selection) }
        val credential = ArchiveCredential(ARCHIVE_PASSWORD)
        var returnDestination: ((String?) -> Unit)? = null
        owner.export(credential, ExportTarget.FILES) { returnDestination = it }
        assertTrue(owner.pickerPending)
        owner.dispose()
        assertCleared(credential)
        assertEquals(1, source.selectionCloses.get())
        assertEquals(0, source.opens.get())
        assertNull(owner.selection)
        assertFalse(owner.pickerPending)
        assertNotNull(returnDestination).invoke("content://public-test-provider/late-document")
        val output = sink.closedOutput()
        assertEquals(listOf("discard", "close"), output.events.toList())
        assertFalse(output.file.exists())
        assertNull(controller.activeOperation.value)
        assertEquals(1, source.selectionCloses.get())
    }

    @Test
    fun disposing_a_source_picker_closes_late_and_duplicate_results_without_opening_them() = scenario {
        enter()
        val owner = ExternalArchiveOwner(controller)
        var returnSelection: ((ExternalArchiveSelection?) -> Unit)? = null
        owner.choose { returnSelection = it }
        assertTrue(owner.pickerPending)
        owner.dispose()
        owner.dispose()
        val source = source("late-input.bin", byteArrayOf(3))
        val callback = assertNotNull(returnSelection)
        callback(source.selection)
        callback(source.selection)
        assertEquals(1, source.selectionCloses.get())
        assertEquals(0, source.opens.get())
        assertEquals(0, source.sourceCloses.get())
        assertNull(owner.selection)
        assertFalse(owner.pickerPending)
        assertEquals(0, sink.created.get())
        assertNull(controller.activeOperation.value)
    }

    private fun scenario(test: suspend Fixture.() -> Unit) = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val root = File(System.getProperty("java.io.tmpdir"), "chur-external-controller-${System.nanoTime()}").apply { mkdirs() }
        val sink = Sink(root)
        val controller = ChurController(root.absolutePath, NoPrivacyCover, sink, clock = { 1_700_000_000_000L })
        try {
            controller.start()
            Fixture(root, sink, controller).test()
        } finally {
            controller.vault.shutdown()
            Dispatchers.resetMain()
            root.deleteRecursively()
        }
    }

    private class Fixture(val root: File, val sink: Sink, val controller: ChurController) {
        suspend fun enter() {
            controller.goTo(AppRoute.PublicSettings)
            controller.openExternalArchive()
            withTimeout(TIMEOUT) { controller.route.first { it == AppRoute.EncryptFiles } }
        }
        suspend fun createVault() {
            controller.create(VAULT_PASSWORD, offerRecovery = false)
            withTimeout(TIMEOUT) { controller.route.first { it == AppRoute.Vault } }
        }
        fun source(name: String, bytes: ByteArray, beforeOpen: suspend () -> Unit = {}): Source {
            val file = File(root, "input-${System.nanoTime()}").apply { writeBytes(bytes) }
            return Source(file, bytes, name, beforeOpen)
        }
    }

    private class Source(val file: File, val bytes: ByteArray, name: String, beforeOpen: suspend () -> Unit) {
        val opens = AtomicInteger()
        val sourceCloses = AtomicInteger()
        val selectionCloses = AtomicInteger()
        val selection = ExternalArchiveSelection(1, { _, _ ->
            beforeOpen()
            opens.incrementAndGet()
            val input = RandomAccessFile(file, "r")
            PickedMedia(descriptor(input), true, bytes.size.toLong(), "application/octet-stream", name, null, null,
                { sourceCloses.incrementAndGet(); input.close() }, importAsFile = true)
        }, { selectionCloses.incrementAndGet() })
    }

    private class Sink(private val root: File) : ExportSink {
        var capacity = 16L * 1024 * 1024
        val created = AtomicInteger()
        val closedOutputs = CopyOnWriteArrayList<Output>()
        private val outputs = Channel<Output>(Channel.UNLIMITED)
        override fun cancelPending() = Unit
        override fun archiveCapacity(target: ExportTarget) = capacity
        override fun create(displayName: String, contentType: String): ExportSink.Destination? = error("explicit target required")
        override fun create(displayName: String, contentType: String, target: ExportTarget, uri: String?): ExportSink.Destination =
            Output(File(root, "archive-${created.incrementAndGet()}.churbak")) { closedOutputs += it }.also {
                check(outputs.trySend(it).isSuccess)
            }
        override fun create(displayName: String, contentType: String, target: ExportTarget, uri: String?, plaintextSize: Long): ExportSink.Destination =
            create(displayName, contentType, target, uri)
        suspend fun closedOutput(): Output = withTimeout(TIMEOUT) { outputs.receive().also { it.closed.await() } }
    }

    private class Output(val file: File, val onClose: (Output) -> Unit) : ExportSink.Destination {
        private val output = RandomAccessFile(file, "rw")
        override val descriptor = descriptor(output)
        val events = CopyOnWriteArrayList<String>()
        val closed = CompletableDeferred<Unit>()
        override fun publish(): Unit = error("checked publication required")
        override fun publishChecked(): Boolean { events += "publishChecked"; return true }
        override fun discard() { events += "discard"; file.delete() }
        override fun close() { events += "close"; output.close(); onClose(this); closed.complete(Unit) }
    }

    private companion object {
        const val TIMEOUT = 60_000L
        const val VAULT_PASSWORD = "existing-vault-public-fixture-password"
        const val ARCHIVE_PASSWORD = "external-archive-public-fixture-password"
        fun descriptor(file: RandomAccessFile): Int = file.fd.javaClass.getDeclaredField("fd").apply { isAccessible = true }.getInt(file.fd)
        fun assertCleared(credential: ArchiveCredential) {
            assertEquals(ChurStatus.SESSION_EXPIRED, assertFailsWith<ChurFailure> { credential.copyBytes() }.status)
        }
    }
}
