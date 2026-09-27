package dev.po4yka.chur.app

import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.imports.Derivative
import dev.po4yka.chur.imports.MediaBounds
import dev.po4yka.chur.imports.MediaCodec
import dev.po4yka.chur.imports.PickedMedia
import dev.po4yka.chur.imports.ProbedMedia
import dev.po4yka.chur.notes.InMemoryNoteStore
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CompletableDeferred
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

/**
 * A reload keeps the rows the grid has paged, `CATALOG_SCHEMA_V1.md` §16.2.
 *
 * A favourite set from the viewer reloads the query. The reload read only the
 * first page, so the viewer lost the item on screen from its pages once that
 * item lay past the first page, and a swipe did nothing, `DESIGN.md` §13.1.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryReloadHostTest {
    private val root = File(System.getProperty("java.io.tmpdir"), "chur-reload-${System.nanoTime()}")
    private lateinit var controller: ChurController

    @BeforeTest
    fun open(): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        root.mkdirs()
        controller = ChurController(
            storageRoot = root.absolutePath,
            privacy = NoPrivacyCover,
            exports = NoExports,
            clock = { 1_700_000_000_000L },
            notes = InMemoryNoteStore(),
        )
        controller.start()
        controller.create(PASSWORD, offerRecovery = false)
        withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }
    }

    @AfterTest
    fun close(): Unit = runBlocking {
        controller.vault.shutdown()
        Dispatchers.resetMain()
        root.deleteRecursively()
    }

    @Test
    fun a_reload_after_a_next_page_keeps_the_rows_already_loaded(): Unit = runBlocking {
        repeat(3) { importOne(it) }
        // One row a page, so the second row is the first past a page boundary.
        controller.load(ObjectQuery(limit = 1))
        withTimeout(10_000) { controller.page.first { it.objects.size == 1 } }
        controller.loadNextPage()
        val loaded = withTimeout(10_000) { controller.page.first { it.objects.size == 2 } }.objects

        val done = CompletableDeferred<Unit>()
        controller.setFavorite(loaded[1].objectId, favorite = true) { done.complete(Unit) }
        withTimeout(10_000) { done.await() }

        val reloaded = controller.page.value.objects
        assertEquals(loaded.map { it.id }, reloaded.map { it.id }, "the reload kept both rows, in order")
        assertTrue(reloaded[1].favorite, "the reloaded row carries the new favourite")
    }

    /** Imports one picture straight through the controller's repository. */
    private suspend fun importOne(n: Int): ByteArray {
        val source = File(root, "picked-$n.jpg").apply { writeBytes(ByteArray(2_048) { n.toByte() }) }
        return RandomAccessFile(source, "r").use { file ->
            val descriptor = file.fd.javaClass.getDeclaredField("fd").apply { isAccessible = true }
                .getInt(file.fd)
            val picked = PickedMedia(
                descriptor = descriptor,
                seekable = true,
                knownLength = source.length(),
                contentTypeHint = "image/jpeg",
                originalFilename = source.name,
                captureTimeMs = null,
                platformHandle = null,
                close = {},
            )
            val codec = object : MediaCodec {
                override fun probe(media: PickedMedia) =
                    ProbedMedia(MediaBounds.CLASS_IMAGE, 1_200, 900, 0, "image/jpeg")

                override fun derive(
                    media: PickedMedia,
                    probe: ProbedMedia,
                    kind: StreamKind,
                    cancelRequested: () -> Boolean,
                ): Derivative = error("no previews are needed here")
            }
            assertIs<MediaImporter.Outcome.Imported>(MediaImporter(codec).import(controller.vault, picked)).objectId
        }
    }

    private object NoExports : ExportSink {
        override fun cancelPending() = Unit
        override fun create(displayName: String, contentType: String): ExportSink.Destination? = null
        override fun create(
            displayName: String,
            contentType: String,
            target: ExportTarget,
            uri: String?,
        ): ExportSink.Destination? = null
    }

    private companion object {
        const val PASSWORD = "Library-reload-password"
    }
}
