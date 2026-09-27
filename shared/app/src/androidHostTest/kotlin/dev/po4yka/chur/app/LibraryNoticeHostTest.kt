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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a library action says when it is done, `DESIGN.md` §26.
 *
 * Moves to Trash, restores, album placement and permanent deletion used to
 * reload the grid and say nothing, so a user could not tell whether an action
 * worked, and a mis-tap on Move to Trash could be taken back only from Trash.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryNoticeHostTest {
    private val root = File(System.getProperty("java.io.tmpdir"), "chur-notice-${System.nanoTime()}")
    private lateinit var controller: ChurController
    private lateinit var objectId: ByteArray

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
        objectId = importOne()
        controller.load(ObjectQuery())
        withTimeout(10_000) { controller.page.first { it.objects.size == 1 } }
    }

    @AfterTest
    fun close(): Unit = runBlocking {
        controller.vault.shutdown()
        Dispatchers.resetMain()
        root.deleteRecursively()
    }

    @Test
    fun a_move_to_trash_is_counted_and_undone_from_its_notice(): Unit = runBlocking {
        controller.delete(objectId)
        val moved = next(after = null)

        assertEquals("Moved 1 item to Trash.", moved.text)
        assertTrue(controller.page.value.objects.isEmpty(), "the item left All media")
        val (label, undo) = assertNotNull(moved.action)
        assertEquals("Undo", label)

        undo()

        assertEquals("Restored 1 item.", next(after = moved).text)
        assertEquals(1, controller.page.value.objects.size, "the item is back in All media")
    }

    @Test
    fun emptying_trash_says_the_deletion_is_permanent(): Unit = runBlocking {
        controller.delete(objectId)
        val moved = next(after = null)

        controller.emptyTrash()

        assertEquals("Deleted permanently.", next(after = moved).text)
    }

    @Test
    fun an_album_placement_is_counted_without_the_album_name(): Unit = runBlocking {
        controller.createAlbum(ALBUM)
        val album = withTimeout(10_000) { controller.albums.first { it.isNotEmpty() } }.single()

        controller.placeObjectsInAlbum(album.albumId, listOf(objectId))
        val placed = next(after = null)

        // §26: a snackbar carries no private name that is not already needed.
        assertEquals("Added 1 item to the album.", placed.text)
        assertFalse(ALBUM in placed.text)
    }

    /** The first notice other than [after]. */
    private suspend fun next(after: Notice?): Notice =
        withTimeout(10_000) { controller.notice.filterNotNull().first { it.id != after?.id } }

    /** Imports one picture straight through the controller's repository. */
    private suspend fun importOne(): ByteArray {
        val source = File(root, "picked.jpg").apply { writeBytes(ByteArray(2_048) { 3 }) }
        return RandomAccessFile(source, "r").use { file ->
            val descriptor = file.fd.javaClass.getDeclaredField("fd").apply { isAccessible = true }
                .getInt(file.fd)
            val picked = PickedMedia(
                descriptor = descriptor,
                seekable = true,
                knownLength = source.length(),
                contentTypeHint = "image/jpeg",
                originalFilename = "picked.jpg",
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
        const val PASSWORD = "Library-notice-password"
        const val ALBUM = "Private album name"
    }
}
