package dev.po4yka.chur.app

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.ffi.QueryScope
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.imports.Derivative
import dev.po4yka.chur.imports.MediaBounds
import dev.po4yka.chur.imports.MediaCodec
import dev.po4yka.chur.imports.PickedMedia
import dev.po4yka.chur.imports.ProbedMedia
import dev.po4yka.chur.notes.InMemoryNoteStore
import dev.po4yka.chur.vault.VaultRepository
import dev.po4yka.chur.vault.VaultState
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MediaImporterHostTest {
    @Test
    fun imported_original_survives_a_codec_failure_and_closes_the_picker_source() = runBlocking {
        val root = File(System.getProperty("java.io.tmpdir"), "chur-media-${System.nanoTime()}")
        root.mkdirs()
        val source = File(root, "picked.jpg")
        source.writeBytes(ByteArray(2_048) { 3 })
        val vault = VaultRepository(File(root, "vault").absolutePath, { 1_700_000_000_000L })
        try {
            vault.start()
            vault.create("correct horse battery staple".encodeToByteArray(), offerRecovery = false)
            var closed = false
            RandomAccessFile(source, "r").use { file ->
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
                    close = { closed = true },
                )
                val codec = object : MediaCodec {
                    override fun probe(media: PickedMedia) =
                        ProbedMedia(MediaBounds.CLASS_IMAGE, 1_200, 900, 0, "image/jpeg")

                    override fun derive(
                        media: PickedMedia,
                        probe: ProbedMedia,
                        kind: StreamKind,
                        cancelRequested: () -> Boolean,
                    ): Derivative = error("the platform decoder refused this derivative")
                }
                val outcome = MediaImporter(codec).import(vault, picked)
                assertIs<MediaImporter.Outcome.Imported>(outcome)
                assertEquals(0, outcome.derivatives)
                assertEquals(1, vault.page(ObjectQuery()).objects.size)
            }
            assertTrue(closed)
        } finally {
            vault.shutdown()
            root.deleteRecursively()
        }
    }

    @Test
    fun cancelling_after_commit_keeps_the_original_and_skips_previews() = runBlocking {
        val root = File(System.getProperty("java.io.tmpdir"), "chur-cancel-${System.nanoTime()}")
        root.mkdirs()
        val source = File(root, "picked.jpg").apply { writeBytes(ByteArray(2_048) { 3 }) }
        val vault = VaultRepository(File(root, "vault").absolutePath, { 1_700_000_000_000L })
        try {
            vault.start()
            vault.create("correct horse battery staple".encodeToByteArray(), offerRecovery = false)
            var closed = false
            var cancelled = false
            RandomAccessFile(source, "r").use { file ->
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
                    close = { closed = true },
                )
                val codec = object : MediaCodec {
                    override fun probe(media: PickedMedia) =
                        ProbedMedia(MediaBounds.CLASS_IMAGE, 1_200, 900, 0, "image/jpeg")

                    override fun derive(
                        media: PickedMedia,
                        probe: ProbedMedia,
                        kind: StreamKind,
                        cancelRequested: () -> Boolean,
                    ): Derivative = error("a cancelled preview must not start")
                }
                val outcome = MediaImporter(codec).import(
                    vault,
                    picked,
                    onProgress = { if (it.terminal) cancelled = true },
                    cancelRequested = { cancelled },
                )
                val imported = assertIs<MediaImporter.Outcome.Imported>(outcome)
                assertTrue(imported.previewsSkipped)
                assertEquals(1, vault.page(ObjectQuery()).objects.size)
            }
            assertTrue(closed)
        } finally {
            vault.shutdown()
            root.deleteRecursively()
        }
    }

    @Test
    fun a_refusal_carries_its_status_rather_than_its_name() = runBlocking {
        // Both refusals return before the repository is reached, so it is
        // never started. The host tells a cancellation from a failure by this
        // code, never by text, `ERROR_MODEL.md` "Layer mapping".
        val vault = VaultRepository(System.getProperty("java.io.tmpdir"), { 1_700_000_000_000L })
        val unidentified = object : MediaCodec {
            override fun probe(media: PickedMedia): ProbedMedia? = null

            override fun derive(
                media: PickedMedia,
                probe: ProbedMedia,
                kind: StreamKind,
                cancelRequested: () -> Boolean,
            ): Derivative = error("a refused source has no derivatives")
        }
        fun picked() = PickedMedia(
            descriptor = -1,
            seekable = true,
            knownLength = null,
            contentTypeHint = "image/jpeg",
            originalFilename = null,
            captureTimeMs = null,
            platformHandle = null,
            close = {},
        )

        assertEquals(
            MediaImporter.Outcome.Refused(ChurStatus.CANCELLED),
            MediaImporter(unidentified).import(vault, picked(), cancelRequested = { true }),
        )
        assertEquals(
            MediaImporter.Outcome.Refused(ChurStatus.UNSUPPORTED_VERSION),
            MediaImporter(unidentified).import(vault, picked()),
        )
    }

    @Test
    fun a_pick_of_many_imports_under_one_operation_into_the_album_with_one_summary() = withVault { controller, root ->
        controller.createAlbum("Album")
        val album = withTimeout(10_000) { controller.albums.first { it.isNotEmpty() } }.single()
        val seen = mutableListOf<ActiveOperation?>()

        // The second item cannot be opened, and the batch goes on past it.
        val outcomes = controller.importAll(MediaImporter(Previewless), 3, album.albumId) { index, _ ->
            seen += controller.activeOperation.value
            if (index == 1) null else picked(root, index)
        }

        assertEquals(3, outcomes.size)
        assertIs<MediaImporter.Outcome.Imported>(outcomes[0])
        assertEquals(MediaImporter.Outcome.Unreadable, outcomes[1])
        assertIs<MediaImporter.Outcome.Imported>(outcomes[2])
        // One operation carries the whole pick, and it counts the items.
        assertNotNull(seen.first())
        assertEquals(listOf(seen.first()?.id), seen.map { it?.id }.distinct())
        assertEquals(listOf(1, 2, 3), seen.map { it?.item })
        assertEquals(listOf(3), seen.map { it?.items }.distinct())
        assertEquals(
            "2 imported, 1 could not be opened.",
            withTimeout(10_000) { controller.notice.filterNotNull().first { "imported" in it.text } }.text,
        )
        assertEquals(2, controller.vault.page(ObjectQuery(QueryScope.ALBUM, scopeId = album.albumId)).objects.size)
    }

    @Test
    fun a_refused_item_in_a_pick_gives_its_reason_and_the_rest_go_on() = withVault { controller, root ->
        // No codec identifies the second item, as with audio whose probe
        // finds no decoder.
        val codec = object : MediaCodec by Previewless {
            override fun probe(media: PickedMedia): ProbedMedia? =
                if (media.originalFilename == "IMG_0001.jpg") null else Previewless.probe(media)
        }

        val outcomes = controller.importAll(MediaImporter(codec), 3, null) { index, _ -> picked(root, index) }

        assertEquals(MediaImporter.Outcome.Refused(ChurStatus.UNSUPPORTED_VERSION), outcomes[1])
        assertEquals(listOf(true, false, true), outcomes.map { it is MediaImporter.Outcome.Imported })
        assertEquals(
            "2 imported, 1 could not be imported. ${userCopy(ChurStatus.UNSUPPORTED_VERSION)}",
            withTimeout(10_000) { controller.notice.filterNotNull().first { "imported" in it.text } }.text,
        )
    }

    @Test
    fun a_full_disk_stops_the_pick_and_skips_the_rest() = withVault { controller, root ->
        val opened = mutableListOf<Int>()
        val codec = object : MediaCodec by Previewless {
            override fun probe(media: PickedMedia): ProbedMedia? =
                if (media.originalFilename == "IMG_0001.jpg") {
                    throw ChurFailure(ChurStatus.STORAGE_UNAVAILABLE, "test")
                } else {
                    Previewless.probe(media)
                }
        }

        val outcomes = controller.importAll(MediaImporter(codec), 4, null) { index, _ ->
            opened += index
            picked(root, index)
        }

        assertEquals(listOf(0, 1), opened)
        assertEquals(MediaImporter.Outcome.Refused(ChurStatus.STORAGE_UNAVAILABLE), outcomes.last())
        assertEquals(
            "1 imported, 1 could not be imported, 2 skipped. ${userCopy(ChurStatus.STORAGE_UNAVAILABLE)}",
            withTimeout(10_000) { controller.notice.filterNotNull().first { "imported" in it.text } }.text,
        )
        assertEquals(1, controller.vault.page(ObjectQuery()).objects.size)
    }

    @Test
    fun an_item_that_fails_to_read_does_not_stop_the_pick() = withVault { controller, root ->
        // `ANDROID.md` §27 maps a failed read of the picked item to
        // IO_FAILURE, as when a cloud-backed item loses its network. The
        // cause is that item, not the vault, so the rest of the pick goes on.
        val opened = mutableListOf<Int>()
        val codec = object : MediaCodec by Previewless {
            override fun probe(media: PickedMedia): ProbedMedia? =
                if (media.originalFilename == "IMG_0001.jpg") {
                    throw ChurFailure(ChurStatus.IO_FAILURE, "test")
                } else {
                    Previewless.probe(media)
                }
        }

        val outcomes = controller.importAll(MediaImporter(codec), 3, null) { index, _ ->
            opened += index
            picked(root, index)
        }

        assertEquals(listOf(0, 1, 2), opened)
        assertEquals(MediaImporter.Outcome.Refused(ChurStatus.IO_FAILURE), outcomes[1])
        assertEquals(listOf(true, false, true), outcomes.map { it is MediaImporter.Outcome.Imported })
        assertEquals(
            "2 imported, 1 could not be imported. ${userCopy(ChurStatus.IO_FAILURE)}",
            withTimeout(10_000) { controller.notice.filterNotNull().first { "imported" in it.text } }.text,
        )
        assertEquals(2, controller.vault.page(ObjectQuery()).objects.size)
    }

    @Test
    fun a_single_import_reads_in_phases_and_not_in_bytes() = withVault { controller, root ->
        // The computer-control import runs one item through importMedia, and
        // its card reads as a pick of one does, `DESIGN.md` §15.2.
        var seen: ActiveOperation? = null

        val outcome = controller.importMedia(MediaImporter(Previewless)) {
            seen = controller.activeOperation.value
            picked(root, 0)
        }

        assertIs<MediaImporter.Outcome.Imported>(outcome)
        assertEquals("Importing · Preparing", seen?.description)
    }

    @Test
    fun a_cancel_keeps_the_items_already_imported_and_opens_no_more() = withVault { controller, root ->
        val opened = mutableListOf<Int>()

        val outcomes = controller.importAll(MediaImporter(Previewless), 3, null) { index, _ ->
            opened += index
            if (index == 1) controller.cancelActiveOperation()
            picked(root, index)
        }

        assertEquals(listOf(0, 1), opened)
        assertIs<MediaImporter.Outcome.Imported>(outcomes[0])
        assertEquals(listOf(outcomes[0], MediaImporter.Outcome.Refused(ChurStatus.CANCELLED)), outcomes)
        assertEquals(
            "1 imported, 2 cancelled.",
            withTimeout(10_000) { controller.notice.filterNotNull().first { "imported" in it.text } }.text,
        )
        assertEquals(1, controller.vault.page(ObjectQuery()).objects.size)
    }

    @Test
    fun a_cancel_while_an_item_is_fetched_stops_before_its_import() = withVault { controller, root ->
        // iOS fetches an iCloud original before it can open it, and the card
        // shows the fetch as "Preparing" with a bar, `IOS.md` §15.2. A cancel
        // reaches the host through the fetch report, and the host stops the
        // fetch and opens nothing, so no native import of that item begins.
        val closed = mutableListOf<Int>()
        var fetching: ActiveOperation? = null

        val outcomes = controller.importAll(MediaImporter(Previewless), 3, null) { index, prepare ->
            assertTrue(prepare(40, 100))
            if (index == 1) {
                fetching = controller.activeOperation.value
                controller.cancelActiveOperation()
            }
            if (!prepare(100, 100)) return@importAll null
            val media = picked(root, index)
            media.copy(close = {
                media.close()
                closed += index
            })
        }

        assertEquals("Importing item 2 of 3 · Preparing", fetching?.description)
        assertEquals(0.4f, fetching?.fraction)
        assertIs<MediaImporter.Outcome.Imported>(outcomes[0])
        assertEquals(listOf(outcomes[0], MediaImporter.Outcome.Refused(ChurStatus.CANCELLED)), outcomes)
        // The first item's copy was released, and the second was never opened.
        assertEquals(listOf(0), closed)
        assertEquals(
            "1 imported, 2 cancelled.",
            withTimeout(10_000) { controller.notice.filterNotNull().first { "imported" in it.text } }.text,
        )
        assertEquals(1, controller.vault.page(ObjectQuery()).objects.size)
        // The name the host gave the item is what a search finds.
        assertEquals(1, controller.vault.page(ObjectQuery(QueryScope.SEARCH, terms = "IMG_0000")).objects.size)
    }

    @Test
    fun a_lock_in_the_middle_stops_the_pick_without_a_summary() = withVault { controller, root ->
        val opened = mutableListOf<Int>()

        val outcomes = controller.importAll(MediaImporter(Previewless), 3, null) { index, _ ->
            opened += index
            if (index == 1) controller.lock()
            picked(root, index)
        }

        assertEquals(listOf(0, 1), opened)
        assertEquals(listOf(true, false), outcomes.map { it is MediaImporter.Outcome.Imported })
        withTimeout(10_000) { controller.vaultState.first { it !is VaultState.Unlocked } }
        withTimeout(10_000) { controller.route.first { it != AppRoute.Vault } }
        assertNull(controller.notice.value)
    }

    /** Runs [test] against a controller whose new vault is open. */
    private fun withVault(test: suspend (ChurController, File) -> Unit): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val root = File(System.getProperty("java.io.tmpdir"), "chur-batch-${System.nanoTime()}")
        root.mkdirs()
        val controller = ChurController(
            storageRoot = root.absolutePath,
            privacy = NoPrivacyCover,
            exports = NoExports,
            clock = { 1_700_000_000_000L },
            notes = InMemoryNoteStore(),
        )
        try {
            controller.start()
            controller.create("Batch-import-password", offerRecovery = false)
            withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }
            test(controller, root)
        } finally {
            controller.vault.shutdown()
            Dispatchers.resetMain()
            root.deleteRecursively()
        }
    }

    /** A picture of its own bytes, whose descriptor stays open until the importer closes it. */
    private fun picked(root: File, index: Int): PickedMedia {
        val source = File(root, "picked-$index.jpg").apply { writeBytes(ByteArray(2_048) { index.toByte() }) }
        val file = RandomAccessFile(source, "r")
        return PickedMedia(
            descriptor = file.fd.javaClass.getDeclaredField("fd").apply { isAccessible = true }.getInt(file.fd),
            seekable = true,
            knownLength = source.length(),
            contentTypeHint = "image/jpeg",
            originalFilename = "IMG_000$index.jpg",
            captureTimeMs = null,
            platformHandle = null,
            close = { file.close() },
        )
    }

    /** A codec that identifies a picture and makes no previews of it. */
    private object Previewless : MediaCodec {
        override fun probe(media: PickedMedia) = ProbedMedia(MediaBounds.CLASS_IMAGE, 1_200, 900, 0, "image/jpeg")

        override fun derive(
            media: PickedMedia,
            probe: ProbedMedia,
            kind: StreamKind,
            cancelRequested: () -> Boolean,
        ): Derivative = error("no previews are needed here")
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
}
