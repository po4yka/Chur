package dev.po4yka.chur.app

import dev.po4yka.chur.ffi.ImportRequest
import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.ffi.QueryScope
import dev.po4yka.chur.imports.MediaBounds
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AlbumExportHostTest {
    @Test
    fun a_verified_export_publishes_every_album_item_and_closes_the_directory() = scenario(Mode.SUCCESS)

    @Test
    fun a_native_destination_failure_discards_the_active_file_and_reports_partial_counts() = scenario(Mode.BAD_DESCRIPTOR)

    @Test
    fun a_provider_publication_failure_is_not_counted_as_exported() = scenario(Mode.PUBLISH_FAILURE)

    @Test
    fun cancellation_during_publication_discards_the_active_file_and_stops_the_next_one() = scenario(Mode.CANCEL_ON_PUBLISH)

    @Test
    fun a_root_cleanup_failure_keeps_the_exported_counts_and_reports_the_cleanup_problem() = scenario(Mode.ROOT_CLOSE_FAILURE)

    @Test
    fun cancellation_releases_a_provider_waiting_before_directory_creation() = scenario(Mode.CANCEL_BEFORE_DIRECTORY)

    @Test
    fun cancellation_releases_a_provider_waiting_before_file_creation() = scenario(Mode.CANCEL_BEFORE_FILE)

    private enum class Mode { SUCCESS, BAD_DESCRIPTOR, PUBLISH_FAILURE, CANCEL_ON_PUBLISH, ROOT_CLOSE_FAILURE, CANCEL_BEFORE_DIRECTORY, CANCEL_BEFORE_FILE }

    private fun scenario(mode: Mode) = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val root = File(System.getProperty("java.io.tmpdir"), "chur-album-export-${System.nanoTime()}").apply { mkdirs() }
        val files = mutableListOf<File>()
        var discarded = 0
        var closed = 0
        var rootClosed = false
        var childClosed = false
        val providerWaiting = CountDownLatch(1)
        val cancelProvider = CountDownLatch(1)
        var directoryCancellations = 0
        lateinit var controller: ChurController
        val child = object : ExportDirectory {
            override fun createDirectory(displayName: String): ExportDirectory? = null
            override fun close() { childClosed = true }
            override fun createFile(displayName: String, contentType: String, plaintextSize: Long): ExportSink.Destination? {
                if (mode == Mode.CANCEL_BEFORE_FILE) {
                    providerWaiting.countDown()
                    check(cancelProvider.await(5, TimeUnit.SECONDS))
                    return null
                }
                val file = File(root, "export-${files.size}").also { files += it }
                val output = RandomAccessFile(file, "rw")
                return object : ExportSink.Destination {
                    override val descriptor = if (mode == Mode.BAD_DESCRIPTOR) -1 else descriptor(output)
                    override fun publish() = Unit
                    override fun publishChecked(): Boolean {
                        if (mode == Mode.CANCEL_ON_PUBLISH) controller.cancelActiveOperation()
                        return mode != Mode.PUBLISH_FAILURE
                    }
                    override fun discard() { discarded++; file.delete() }
                    override fun close() { closed++; output.close() }
                }
            }
        }
        val exports = object : ExportSink {
            override fun cancelPending() = Unit
            override fun cancelDirectoryOperations() {
                directoryCancellations++
                cancelProvider.countDown()
            }
            override fun create(displayName: String, contentType: String): ExportSink.Destination? = null
            override fun create(displayName: String, contentType: String, target: ExportTarget, uri: String?): ExportSink.Destination? = null
            override fun openDirectory(uri: String): ExportDirectory = object : ExportDirectory {
                override fun createDirectory(displayName: String): ExportDirectory? {
                    if (mode == Mode.CANCEL_BEFORE_DIRECTORY) {
                        providerWaiting.countDown()
                        check(cancelProvider.await(5, TimeUnit.SECONDS))
                        return null
                    }
                    return child
                }
                override fun createFile(displayName: String, contentType: String, plaintextSize: Long): ExportSink.Destination? = null
                override fun close() {
                    rootClosed = true
                    if (mode == Mode.ROOT_CLOSE_FAILURE) error("the directory could not close")
                }
            }
        }
        controller = ChurController(root.absolutePath, NoPrivacyCover, exports, clock = { 1_700_000_000_000L })
        try {
            controller.start()
            controller.create("album-export-test-password", offerRecovery = false)
            withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }
            val bytes = ByteArray(8_192) { (it % 251).toByte() }
            val source = File(root, "source.bin").apply { writeBytes(bytes) }
            val albumId = controller.vault.createAlbum("Album")
            repeat(2) {
                val objectId = RandomAccessFile(source, "r").use { input ->
                    val operation = controller.vault.beginImport(descriptor(input), ImportRequest(
                        contentType = "application/octet-stream", seekable = true,
                        mediaClass = MediaBounds.CLASS_OPAQUE, knownLength = source.length(), originalFilename = "same.bin",
                    ))
                    try {
                        var progress = controller.vault.poll(operation)
                        while (!progress.terminal) { delay(50); progress = controller.vault.poll(operation) }
                        assertEquals(0, progress.status)
                        requireNotNull(progress.importedObjectId())
                    } finally { controller.vault.closeOperation(operation) }
                }
                controller.vault.setAlbumMembership(albumId, objectId, true)
            }
            // A native cursor remains valid across exports: exporting must
            // not itself invalidate the batch's catalog-generation snapshot.
            val query = ObjectQuery(QueryScope.ALBUM, limit = 1, scopeId = albumId)
            val beforeExport = controller.vault.page(query)
            assertTrue(beforeExport.nextCursor != null)
            controller.exportAlbum(albumId, "transient-token")
            if (mode == Mode.CANCEL_BEFORE_DIRECTORY || mode == Mode.CANCEL_BEFORE_FILE) {
                withContext(Dispatchers.IO) { assertTrue(providerWaiting.await(5, TimeUnit.SECONDS)) }
                controller.cancelActiveOperation()
            }
            val notice = withTimeout(10_000) { controller.notice.first { it?.text?.contains(" exported") == true } }!!
            val afterExport = controller.vault.page(query.copy(cursor = beforeExport.nextCursor))
            assertEquals(beforeExport.catalogGeneration, afterExport.catalogGeneration)
            assertEquals(1, afterExport.objects.size)
            assertTrue(rootClosed)
            assertEquals(mode != Mode.CANCEL_BEFORE_DIRECTORY, childClosed)
            assertEquals(files.size, closed)
            when (mode) {
                Mode.SUCCESS, Mode.ROOT_CLOSE_FAILURE -> {
                    assertEquals(2, files.size)
                    assertEquals(0, discarded)
                    assertTrue(notice.text.startsWith("2 exported."), notice.text)
                    files.forEach { assertContentEquals(bytes, it.readBytes()) }
                    if (mode == Mode.ROOT_CLOSE_FAILURE) assertTrue("cleanup could not finish" in notice.text)
                }
                Mode.CANCEL_ON_PUBLISH -> {
                    assertEquals(1, files.size)
                    assertEquals(1, discarded)
                    assertFalse(files.single().exists())
                    assertTrue(notice.text.startsWith("0 exported, 2 cancelled."), notice.text)
                }
                Mode.CANCEL_BEFORE_DIRECTORY, Mode.CANCEL_BEFORE_FILE -> {
                    assertEquals(1, directoryCancellations)
                    assertTrue(files.isEmpty())
                    assertEquals(0, discarded)
                    assertTrue(notice.text.startsWith("0 exported, 2 cancelled."), notice.text)
                    assertEquals(null, controller.activeOperation.value)
                }
                else -> {
                    assertEquals(1, files.size)
                    assertEquals(1, discarded)
                    assertFalse(files.single().exists())
                    assertTrue(notice.text.startsWith("0 exported, 1 failed, 1 skipped."), notice.text)
                }
            }
        } finally {
            cancelProvider.countDown()
            controller.vault.shutdown()
            Dispatchers.resetMain()
            root.deleteRecursively()
        }
    }

    private fun descriptor(file: RandomAccessFile): Int =
        file.fd.javaClass.getDeclaredField("fd").apply { isAccessible = true }.getInt(file.fd)
}
