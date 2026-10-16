package dev.po4yka.chur.app

import dev.po4yka.chur.app.vault.VaultDestination
import dev.po4yka.chur.app.vault.browseQuery
import dev.po4yka.chur.ffi.ObjectProjection
import dev.po4yka.chur.ffi.QuerySort
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.imports.Derivative
import dev.po4yka.chur.imports.MediaBounds
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The production Recent query against real encrypted catalog imports and keyset pages. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RecentlyAddedHostTest {
    private val root = File(System.getProperty("java.io.tmpdir"), "chur-recent-${System.nanoTime()}")
    private lateinit var controller: ChurController

    @BeforeTest
    fun open(): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        root.mkdirs()
        controller = ChurController(root.absolutePath, NoPrivacyCover, NoExports,
            clock = { 1_700_000_000_000L }, notes = InMemoryNoteStore())
        controller.start()
        controller.create("Recently-added-password", offerRecovery = false)
        withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }
    }

    @AfterTest
    fun close(): Unit = runBlocking {
        controller.vault.shutdown()
        Dispatchers.resetMain()
        root.deleteRecursively()
    }

    @Test
    fun import_order_is_independent_of_capture_date_and_pages_without_gaps(): Unit = runBlocking {
        // Deliberately reverse capture dates: the last import is an old photograph.
        importOne(1, captureTime = 4_000_000)
        importOne(2, captureTime = 3_000_000)
        importOne(3, captureTime = 2_000_000)
        importOne(4, captureTime = 1_000_000)
        val expected = controller.vault.page(recent()).objects.sortedWith(
            compareByDescending<ObjectProjection> { it.importTimeMs }.thenByDescending { it.id })
        assertEquals(4, expected.size)
        controller.load(recent().copy(limit = 1))
        withTimeout(10_000) { controller.page.first { it.objects.size == 1 } }
        repeat(3) { index ->
            controller.loadNextPage()
            withTimeout(10_000) { controller.page.first { it.objects.size == index + 2 } }
        }
        assertEquals(expected.map { it.id }, controller.page.value.objects.map { it.id })
        assertEquals(4, controller.page.value.objects.distinctBy { it.id }.size)
        assertTrue(controller.page.value.objects.zipWithNext().all { (a, b) ->
            a.importTimeMs > b.importTimeMs || a.importTimeMs == b.importTimeMs && a.id > b.id
        })
        val captureOrder = controller.vault.page(recent().copy(sort = QuerySort.CAPTURE_DESC)).objects
        assertEquals(listOf(4_000_000L, 3_000_000L, 2_000_000L, 1_000_000L), captureOrder.map { it.captureTimeMs })
        assertTrue(expected.first().captureTimeMs < captureOrder.first().captureTimeMs)
    }

    @Test
    fun filters_and_reload_keep_recent_order_exclude_trash_and_lock_clears_rows(): Unit = runBlocking {
        val photo = importOne(1, captureTime = 2_000_000)
        val removed = importOne(2, captureTime = 3_000_000)
        val document = importOne(3, captureTime = 1_000_000, asFile = true)
        controller.vault.delete(removed)
        val all = controller.vault.page(recent()).objects
        assertEquals(setOf(photo.hex(), document.hex()), all.map { it.id }.toSet())
        assertEquals(listOf(photo.hex()), controller.vault.page(recent(1)).objects.map { it.id })
        assertEquals(listOf(document.hex()), controller.vault.page(recent(8)).objects.map { it.id })
        controller.load(recent().copy(limit = 1))
        withTimeout(10_000) { controller.page.first { it.objects.size == 1 } }
        controller.loadNextPage()
        withTimeout(10_000) { controller.page.first { it.objects.size == 2 } }
        val newPhoto = importOne(4, captureTime = 500_000)
        controller.reportImport(null)
        val expectedPage = controller.vault.page(recent())
        val expected = expectedPage.objects
        withTimeout(10_000) { controller.page.first { it.catalogGeneration == expectedPage.catalogGeneration } }
        assertEquals(expected.take(2).map { it.id }, controller.page.value.objects.map { it.id }, "reload keeps the loaded row budget")
        controller.loadNextPage()
        withTimeout(10_000) { controller.page.first { it.objects.size == 3 } }
        assertEquals(expected.map { it.id }, controller.page.value.objects.map { it.id })
        assertTrue(controller.page.value.objects.any { it.id == newPhoto.hex() })
        controller.lock()
        withTimeout(10_000) { controller.route.first { it == AppRoute.PublicShell } }
        assertTrue(controller.page.value.objects.isEmpty(), "a lock drops private catalog rows")
    }

    private fun recent(kinds: Int = 0) = browseQuery(VaultDestination.LIBRARY, null, null,
        false, false, "", QuerySort.CAPTURE_ASC, QuerySort.ALBUM_MANUAL, kinds, recentlyAdded = true)!!

    private suspend fun importOne(n: Int, captureTime: Long, asFile: Boolean = false): ByteArray {
        val source = File(root, "picked-$n.${if (asFile) "txt" else "jpg"}")
            .apply { writeBytes(ByteArray(2_048) { n.toByte() }) }
        return RandomAccessFile(source, "r").use { file ->
            val descriptor = file.fd.javaClass.getDeclaredField("fd").apply { isAccessible = true }.getInt(file.fd)
            val media = PickedMedia(descriptor, true, source.length(), if (asFile) "text/plain" else "image/jpeg",
                source.name, captureTime, null, {}, importAsFile = asFile)
            val codec = object : MediaCodec {
                override fun probe(media: PickedMedia) = ProbedMedia(MediaBounds.CLASS_IMAGE, 1200, 900, 0, "image/jpeg")
                override fun derive(media: PickedMedia, probe: ProbedMedia, kind: StreamKind,
                    cancelRequested: () -> Boolean): Derivative = error("no previews needed for catalog ordering")
            }
            assertIs<MediaImporter.Outcome.Imported>(MediaImporter(codec).import(controller.vault, media)).objectId
        }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    private object NoExports : ExportSink {
        override fun cancelPending() = Unit
        override fun create(displayName: String, contentType: String): ExportSink.Destination? = null
        override fun create(displayName: String, contentType: String, target: ExportTarget, uri: String?): ExportSink.Destination? = null
    }
}
