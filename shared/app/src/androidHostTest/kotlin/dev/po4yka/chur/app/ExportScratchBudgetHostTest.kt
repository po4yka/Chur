package dev.po4yka.chur.app

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.ImportRequest
import dev.po4yka.chur.imports.MediaBounds
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExportScratchBudgetHostTest {
    @Test
    fun the_host_can_refuse_the_full_plaintext_size_before_export_starts() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val root = File(System.getProperty("java.io.tmpdir"), "chur-export-budget-${System.nanoTime()}")
        root.mkdirs()
        var reservedSize: Long? = null
        val exports = object : ExportSink {
            override fun cancelPending() = Unit
            override fun create(displayName: String, contentType: String): ExportSink.Destination? = null
            override fun create(displayName: String, contentType: String, target: ExportTarget, uri: String?): ExportSink.Destination? = null
            override fun create(displayName: String, contentType: String, target: ExportTarget, uri: String?, plaintextSize: Long): ExportSink.Destination? {
                reservedSize = plaintextSize
                throw ChurFailure(ChurStatus.RESOURCE_LIMIT_EXCEEDED, "the scratch budget is full")
            }
        }
        val controller = ChurController(root.absolutePath, NoPrivacyCover, exports, clock = { 1_700_000_000_000L })
        try {
            controller.start()
            controller.create("export-budget-test-password", offerRecovery = false)
            withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }
            val source = File(root, "source.bin").apply { writeBytes(ByteArray(8_192) { 23 }) }
            val objectId = RandomAccessFile(source, "r").use { file ->
                val fd = file.fd.javaClass.getDeclaredField("fd").apply { isAccessible = true }.getInt(file.fd)
                val operation = controller.vault.beginImport(fd, ImportRequest(contentType = "application/octet-stream", seekable = true, mediaClass = MediaBounds.CLASS_OPAQUE, knownLength = source.length()))
                try {
                    var progress = controller.vault.poll(operation)
                    while (!progress.terminal) {
                        delay(50)
                        progress = controller.vault.poll(operation)
                    }
                    assertEquals(0, progress.status)
                    requireNotNull(progress.importedObjectId())
                } finally {
                    controller.vault.closeOperation(operation)
                }
            }
            controller.export(objectId, ExportTarget.SHARE)
            withTimeout(10_000) { controller.notice.first { it != null } }
            assertEquals(8_192L, reservedSize)
        } finally {
            controller.vault.shutdown()
            Dispatchers.resetMain()
            root.deleteRecursively()
        }
    }
}
