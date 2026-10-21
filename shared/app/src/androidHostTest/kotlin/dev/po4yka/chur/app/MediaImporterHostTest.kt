package dev.po4yka.chur.app

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.LockReason
import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.ffi.QueryScope
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.imports.Derivative
import dev.po4yka.chur.imports.DecoderCleanupException
import dev.po4yka.chur.imports.MediaBounds
import dev.po4yka.chur.imports.MediaCodec
import dev.po4yka.chur.imports.PickedMedia
import dev.po4yka.chur.imports.ProbedMedia
import dev.po4yka.chur.imports.Waveform
import dev.po4yka.chur.app.vault.importedOriginals
import dev.po4yka.chur.notes.InMemoryNoteStore
import dev.po4yka.chur.vault.VaultRepository
import dev.po4yka.chur.vault.VaultState
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)
class MediaImporterHostTest {
    /** The clock of the controller [withVault] makes, which a case may move. */
    @Volatile
    private var now = 1_700_000_000_000L

    @Test
    fun optional_audio_art_decoder_failures_keep_the_verified_original_and_waveform() = runBlocking {
        val root = File(System.getProperty("java.io.tmpdir"), "chur-audio-art-${System.nanoTime()}").apply { mkdirs() }
        val pcm = ByteArray(1_600)
        val wav = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".encodeToByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".encodeToByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000).putShort(2).putShort(16)
            .put("data".encodeToByteArray()).putInt(pcm.size).put(pcm).array()
        val file = File(root, "public.wav").apply { writeBytes(wav) }
        val vault = VaultRepository(File(root, "vault").absolutePath, { 1_700_000_000_000L })
        try {
            vault.start()
            vault.create("public audio import fixture password".encodeToByteArray(), offerRecovery = false)
            val waveform = Waveform.record(100, ByteArray(512))
            val encodedWaveform = waveform.copyOf()
            val attempted = mutableListOf<StreamKind>()
            val codec = object : MediaCodec {
                override fun probe(media: PickedMedia) = ProbedMedia(MediaBounds.CLASS_AUDIO, 0, 0, 100, "audio/wav")
                override fun derive(media: PickedMedia, probe: ProbedMedia, kind: StreamKind, cancelRequested: () -> Boolean): Derivative {
                    attempted += kind
                    if (kind == StreamKind.AUDIO_WAVEFORM) return Derivative(kind, encodedWaveform, 0, 0)
                    throw IllegalArgumentException("the optional embedded cover cannot be decoded")
                }
            }
            var closed = false
            RandomAccessFile(file, "r").use { input ->
                val fd = input.fd.javaClass.getDeclaredField("fd").apply { isAccessible = true }.getInt(input.fd)
                val outcome = MediaImporter(codec).import(vault, PickedMedia(fd, true, file.length(), "audio/wav",
                    "public.wav", null, null, { closed = true }))
                val imported = assertIs<MediaImporter.Outcome.Imported>(outcome)
                assertEquals(1, imported.derivatives)
                assertEquals(listOf(StreamKind.AUDIO_WAVEFORM, StreamKind.THUMBNAIL, StreamKind.GRID_PREVIEW,
                    StreamKind.SCREEN_PREVIEW), attempted)
                val projection = vault.page(ObjectQuery()).objects.single()
                assertEquals(MediaBounds.CLASS_AUDIO, projection.mediaKind)
                assertEquals(0, projection.width)
                assertEquals(0, projection.height)
                assertEquals(false, projection.thumbnailReady)
                assertContentEquals(wav, vault.readRange(imported.objectId, 0, wav.size))
                assertContentEquals(waveform, vault.readDerived(imported.objectId, StreamKind.AUDIO_WAVEFORM))
                assertTrue(encodedWaveform.all { it == 0.toByte() }, "transferred derivative buffer is cleared")
            }
            assertTrue(closed)
        } finally { vault.shutdown(); root.deleteRecursively() }
    }

    @Test
    fun a_refused_audio_artwork_write_keeps_the_original_waveform_and_clears_the_output() = withVault { controller, root ->
        val assets = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }.take(10)
            .map { File(it, "apps/androidApp/src/androidTest/assets/audio-art") }.first { it.isDirectory }
        val original = File(assets, "with-art.mp3").readBytes()
        val expectedCover = File(assets, "cover.jpg").readBytes()
        val coverOutput = expectedCover.copyOf()
        val waveform = Waveform.record(150, ByteArray(512))
        val file = File(root, "public.mp3").apply { writeBytes(original) }
        val attempted = mutableListOf<StreamKind>()
        val codec = object : MediaCodec {
            override fun probe(media: PickedMedia) = ProbedMedia(MediaBounds.CLASS_AUDIO, 0, 0, 150, "audio/mpeg")
            override fun derive(media: PickedMedia, probe: ProbedMedia, kind: StreamKind, cancelRequested: () -> Boolean): Derivative {
                attempted += kind
                return when (kind) {
                    StreamKind.AUDIO_WAVEFORM -> Derivative(kind, waveform.copyOf(), 0, 0)
                    // Exercise the real native size refusal after the original and waveform commit.
                    else -> Derivative(kind, coverOutput, 321, 160)
                }
            }
        }
        RandomAccessFile(file, "r").use { input ->
            val fd = input.fd.javaClass.getDeclaredField("fd").apply { isAccessible = true }.getInt(input.fd)
            val outcome = MediaImporter(codec).import(controller.vault,
                PickedMedia(fd, true, original.size.toLong(), "audio/mpeg", "public.mp3", null, null, {}))
            val imported = assertIs<MediaImporter.Outcome.Imported>(outcome)
            assertEquals(1, imported.derivatives)
            assertEquals(MediaImporter.PreviewWarning.STORAGE_FAILED, imported.previewWarning)
            assertEquals(false, imported.previewsSkipped)
            assertEquals(listOf(StreamKind.AUDIO_WAVEFORM, StreamKind.THUMBNAIL), attempted)
            assertEquals(1, controller.vault.page(ObjectQuery()).objects.size)
            assertEquals(false, controller.vault.page(ObjectQuery()).objects.single().thumbnailReady)
            assertContentEquals(original, controller.vault.readRange(imported.objectId, 0, original.size))
            assertContentEquals(waveform, controller.vault.readDerived(imported.objectId, StreamKind.AUDIO_WAVEFORM))
            assertTrue(coverOutput.all { it == 0.toByte() })
        }
    }

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
                assertEquals(MediaImporter.PreviewWarning.DECODING_FAILED, outcome.previewWarning)
                assertEquals(1, vault.page(ObjectQuery()).objects.size)
            }
            assertTrue(closed)
        } finally {
            vault.shutdown()
            root.deleteRecursively()
        }
    }

    @Test
    fun unconfirmed_decoder_cleanup_preserves_the_committed_identity_with_a_stop_warning() = runBlocking {
        val root = File(System.getProperty("java.io.tmpdir"), "chur-decoder-cleanup-${System.nanoTime()}").apply { mkdirs() }
        val bytes = ByteArray(2_048) { 37 }
        val source = File(root, "picked.jpg").apply { writeBytes(bytes) }
        val password = "decoder-cleanup-fixture-password".encodeToByteArray()
        val vault = VaultRepository(File(root, "vault").absolutePath, { 1_700_000_000_000L })
        try {
            vault.start()
            vault.create(password, offerRecovery = false)
            var closes = 0
            val failure = DecoderCleanupException("decoder shutdown was not confirmed")
            RandomAccessFile(source, "r").use { file ->
                val descriptor = file.fd.javaClass.getDeclaredField("fd").apply { isAccessible = true }.getInt(file.fd)
                val picked = PickedMedia(descriptor, true, source.length(), "image/jpeg", "picked.jpg", null, null, {
                    closes += 1
                    file.close()
                })
                val codec = object : MediaCodec {
                    override fun probe(media: PickedMedia) = ProbedMedia(MediaBounds.CLASS_IMAGE, 80, 40, 0, "image/jpeg")
                    override fun derive(media: PickedMedia, probe: ProbedMedia, kind: StreamKind,
                        cancelRequested: () -> Boolean): Derivative = error("the bounded decoder owns cleanup")
                    override suspend fun deriveBounded(media: PickedMedia, probe: ProbedMedia, kind: StreamKind,
                        cancelRequested: () -> Boolean): Derivative {
                        assertEquals(0, closes, "the importer retains its source until decoder cleanup returns")
                        assertTrue(file.fd.valid())
                        assertEquals(1, vault.page(ObjectQuery()).objects.size, "the original is already committed")
                        throw failure
                    }
                }
                val imported = assertIs<MediaImporter.Outcome.Imported>(MediaImporter(codec).import(vault, picked))
                assertEquals(MediaImporter.PreviewWarning.DECODER_CLEANUP_FAILED, imported.previewWarning)
                assertEquals(0, imported.derivatives)
                assertEquals(false, imported.previewsSkipped, "failed shutdown is not user cancellation")
                assertContentEquals(vault.page(ObjectQuery()).objects.single().objectId, imported.objectId)
                assertEquals(1, closes, "the importer releases its source once on failure")
                assertTrue(!file.fd.valid())
            }
            val original = vault.page(ObjectQuery()).objects.single().objectId
            assertContentEquals(bytes, vault.readRange(original, 0, bytes.size))
            vault.lock(LockReason.USER)
            vault.unlock(password)
            assertEquals(1, vault.page(ObjectQuery()).objects.size)
            assertContentEquals(bytes, vault.readRange(original, 0, bytes.size))
        } finally {
            vault.shutdown()
            password.fill(0)
            root.deleteRecursively()
        }
    }

    @Test
    fun a_decoder_cleanup_failure_stops_the_pick_and_places_every_committed_original_in_the_album() = withVault { controller, root ->
        val album = controller.vault.createAlbum("Destination")
        val opened = mutableListOf<Int>()
        val closed = mutableListOf<Int>()
        val decoded = mutableListOf<String?>()
        val codec = object : MediaCodec {
            override fun probe(media: PickedMedia) = ProbedMedia(MediaBounds.CLASS_IMAGE, 80, 40, 0, "image/jpeg")
            override fun derive(media: PickedMedia, probe: ProbedMedia, kind: StreamKind,
                cancelRequested: () -> Boolean): Derivative? = error("the bounded decoder owns cleanup")
            override suspend fun deriveBounded(media: PickedMedia, probe: ProbedMedia, kind: StreamKind,
                cancelRequested: () -> Boolean): Derivative? {
                decoded += media.originalFilename
                assertTrue(opened.last() !in closed, "source ownership lasts through decoder shutdown")
                if (media.originalFilename == "IMG_0001.jpg") {
                    throw DecoderCleanupException("private decoder diagnostic must not reach the notice")
                }
                return null
            }
        }
        val outcomes = controller.importAll(MediaImporter(codec), 3, album) { index, _ ->
            opened += index
            val source = picked(root, index)
            source.copy(close = { source.close(); closed += index })
        }

        assertEquals(listOf(0, 1), opened, "do not even open the rest after decoder custody fails")
        assertEquals(opened, closed, "each consumed source is released once")
        assertEquals(listOf<String?>("IMG_0000.jpg", "IMG_0001.jpg"), decoded)
        val imported = outcomes.map { assertIs<MediaImporter.Outcome.Imported>(it) }
        assertEquals(listOf(MediaImporter.PreviewWarning.DECODING_FAILED, MediaImporter.PreviewWarning.DECODER_CLEANUP_FAILED), imported.map { it.previewWarning })
        assertEquals(listOf(0, 1), importedOriginals(listOf(0, 1, 2), outcomes))
        assertEquals(
            "2 imported, 1 skipped; some previews could not be decoded. Preview processing could not stop safely. Restart Chur before importing more media.",
            withTimeout(10_000) { controller.notice.filterNotNull().first { "2 imported" in it.text } }.text,
        )
        assertEquals(null, controller.activeOperation.value)
        val members = controller.vault.page(ObjectQuery(QueryScope.ALBUM, scopeId = album)).objects
        assertEquals(2, members.size)
        assertEquals(imported.map { it.objectId.toList() }.toSet(), members.map { it.objectId.toList() }.toSet())
        imported.forEachIndexed { index, original ->
            assertContentEquals(ByteArray(2_048) { index.toByte() }, controller.vault.readRange(original.objectId, 0, 2_048))
        }
        controller.vault.lock(LockReason.USER)
        controller.vault.unlock("Batch-import-password".encodeToByteArray())
        assertEquals(2L, controller.vault.page(ObjectQuery()).totalCount)
        assertEquals(2L, controller.vault.page(ObjectQuery(QueryScope.ALBUM, scopeId = album)).totalCount)
    }

    @Test
    fun a_single_committed_import_reports_decoder_shutdown_failure_without_a_refusal_or_cancellation() = withVault { controller, root ->
        var attempts = 0
        var closed = 0
        val codec = object : MediaCodec by Previewless {
            override suspend fun deriveBounded(media: PickedMedia, probe: ProbedMedia, kind: StreamKind,
                cancelRequested: () -> Boolean): Derivative? {
                attempts++
                throw DecoderCleanupException("private decoder diagnostic must not reach the notice")
            }
        }
        val outcomes = controller.importAll(MediaImporter(codec), 1, null) { index, _ ->
            val source = picked(root, index)
            source.copy(close = { source.close(); closed++ })
        }
        val imported = assertIs<MediaImporter.Outcome.Imported>(outcomes.single())
        assertEquals(MediaImporter.PreviewWarning.DECODER_CLEANUP_FAILED, imported.previewWarning)
        assertEquals(1, attempts)
        assertEquals(1, closed)
        assertEquals(
            "Imported original; preview processing could not stop safely. Restart Chur before importing more media.",
            withTimeout(10_000) { controller.notice.filterNotNull().first { "Imported original" in it.text } }.text,
        )
        assertContentEquals(ByteArray(2_048), controller.vault.readRange(imported.objectId, 0, 2_048))
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
    fun a_derivative_storage_failure_keeps_the_committed_object_and_reports_a_preview_warning() = withVault { controller, root ->
        var attempts = 0
        val codec = object : MediaCodec {
            override fun probe(media: PickedMedia) = Previewless.probe(media)

            override fun derive(
                media: PickedMedia,
                probe: ProbedMedia,
                kind: StreamKind,
                cancelRequested: () -> Boolean,
            ): Derivative {
                attempts += 1
                // The original is already committed. Refuse creation of the
                // next temporary container, without touching its original.
                val incoming = root.walkTopDown().first { it.isDirectory && it.name == "incoming" }
                assertTrue(incoming.renameTo(File(incoming.parentFile, "saved-incoming")))
                incoming.writeText("the volume cannot create another container")
                return Derivative(kind, byteArrayOf(1, 2, 3), 320, 240)
            }
        }

        val imported = assertIs<MediaImporter.Outcome.Imported>(
            controller.importMedia(MediaImporter(codec)) { picked(root, 0) },
        )

        assertEquals(MediaImporter.PreviewWarning.STORAGE_FAILED, imported.previewWarning)
        assertEquals(0, imported.derivatives)
        assertEquals(1, attempts, "do not repeat writes after storage failed")
        assertEquals(1, controller.vault.page(ObjectQuery()).objects.size)
        assertContentEquals(ByteArray(2_048), controller.vault.readRange(imported.objectId, 0, 2_048))
    }

    @Test
    fun a_derivative_deadline_cancels_generation_discards_its_bytes_and_releases_the_source() = withVault { controller, root ->
        val time = TestTimeSource()
        var attempts = 0
        var closed = false
        var decoding = false
        val bytes = byteArrayOf(1, 2, 3)
        val codec = object : MediaCodec {
            override fun probe(media: PickedMedia) = Previewless.probe(media)

            override fun derive(
                media: PickedMedia,
                probe: ProbedMedia,
                kind: StreamKind,
                cancelRequested: () -> Boolean,
            ): Derivative = error("the importer must use the bounded codec entry point")

            override suspend fun deriveBounded(
                media: PickedMedia,
                probe: ProbedMedia,
                kind: StreamKind,
                cancelRequested: () -> Boolean,
            ): Derivative {
                attempts += 1
                decoding = true
                assertTrue(!cancelRequested())
                time += 30.seconds
                assertTrue(cancelRequested(), "the deadline reaches the platform decoder")
                decoding = false
                // A decoder can finish at its deadline. Its late output must
                // not be saved as a completed preview.
                return Derivative(kind, bytes, 320, 240)
            }
        }
        val source = picked(root, 0)
        val imported = assertIs<MediaImporter.Outcome.Imported>(
            MediaImporter(codec, derivativeTimeSource = time).import(
                controller.vault,
                source.copy(close = {
                    assertTrue(!decoding, "release the source only after decoding stops")
                    source.close()
                    closed = true
                }),
            ),
        )

        assertEquals(MediaImporter.PreviewWarning.TIMED_OUT, imported.previewWarning)
        assertEquals(0, imported.derivatives)
        assertEquals(1, attempts, "no new preview starts after the deadline")
        assertTrue(closed)
        assertContentEquals(ByteArray(3), bytes)
        assertContentEquals(ByteArray(2_048), controller.vault.readRange(imported.objectId, 0, 2_048))
    }

    @Test
    fun cancellation_during_a_derivative_keeps_the_original_and_discards_the_late_result() = withVault { controller, root ->
        var cancelled = false
        var attempts = 0
        val bytes = byteArrayOf(1, 2, 3)
        val codec = object : MediaCodec {
            override fun probe(media: PickedMedia) = Previewless.probe(media)

            override fun derive(
                media: PickedMedia,
                probe: ProbedMedia,
                kind: StreamKind,
                cancelRequested: () -> Boolean,
            ): Derivative {
                attempts += 1
                cancelled = true
                assertTrue(cancelRequested())
                return Derivative(kind, bytes, 320, 240)
            }
        }
        val imported = assertIs<MediaImporter.Outcome.Imported>(
            MediaImporter(codec).import(controller.vault, picked(root, 0), cancelRequested = { cancelled }),
        )

        assertTrue(imported.previewsSkipped)
        assertNull(imported.previewWarning)
        assertEquals(0, imported.derivatives)
        assertEquals(1, attempts)
        assertContentEquals(ByteArray(3), bytes)
        assertContentEquals(ByteArray(2_048), controller.vault.readRange(imported.objectId, 0, 2_048))
    }

    @Test
    fun an_unsupported_media_share_keeps_its_warning_after_the_adapter_falls_back_to_a_file() = withVault { controller, root ->
        val source = picked(root, 0).copy(importAsFile = true, mediaPreviewUnavailable = true)
        val codec = object : MediaCodec by Previewless {
            override fun probe(media: PickedMedia): ProbedMedia? = error("adapter already probed this source")
        }
        val stored = assertIs<MediaImporter.Outcome.Imported>(MediaImporter(codec).import(controller.vault, source))
        assertTrue(stored.storedAsFile)
        assertEquals(MediaImporter.PreviewWarning.DECODING_FAILED, stored.previewWarning)
        assertEquals(0, stored.derivatives)
        assertContentEquals(ByteArray(2_048), controller.vault.readRange(stored.objectId, 0, 2_048))
    }

    @Test
    fun an_explicit_file_import_has_no_unsupported_media_warning() = withVault { controller, root ->
        val source = picked(root, 0).copy(importAsFile = true)
        val stored = assertIs<MediaImporter.Outcome.Imported>(MediaImporter(Previewless).import(controller.vault, source))
        assertFalse(stored.storedAsFile)
        assertNull(stored.previewWarning)
    }

    @Test
    fun a_refusal_carries_its_status_rather_than_its_name() = withVault { controller, root ->
        // Import first binds a live session. The host then distinguishes a
        // cancellation from an unsupported source by its code, never by
        // text, `ERROR_MODEL.md` "Layer mapping".
        val unidentified = object : MediaCodec {
            override fun probe(media: PickedMedia): ProbedMedia? = null

            override fun derive(
                media: PickedMedia,
                probe: ProbedMedia,
                kind: StreamKind,
                cancelRequested: () -> Boolean,
            ): Derivative = error("a refused source has no derivatives")
        }
        assertEquals(
            MediaImporter.Outcome.Refused(ChurStatus.CANCELLED),
            MediaImporter(unidentified).import(controller.vault, picked(root, 0), cancelRequested = { true }),
        )
        val stored = assertIs<MediaImporter.Outcome.Imported>(
            MediaImporter(unidentified).import(controller.vault, picked(root, 0)),
        )
        assertTrue(stored.storedAsFile)
        assertEquals(MediaImporter.PreviewWarning.DECODING_FAILED, stored.previewWarning)
        assertEquals(MediaBounds.CLASS_OPAQUE, controller.vault.page(ObjectQuery()).objects.single().mediaKind)
        assertContentEquals(ByteArray(2_048), controller.vault.readRange(stored.objectId, 0, 2_048))
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
            "2 imported, 1 could not be opened; some previews could not be decoded.",
            withTimeout(10_000) { controller.notice.filterNotNull().first { "imported" in it.text } }.text,
        )
        assertEquals(2, controller.vault.page(ObjectQuery(QueryScope.ALBUM, scopeId = album.albumId)).objects.size)
    }

    @Test
    fun an_unsupported_item_in_a_pick_remains_exportable_and_the_rest_go_on() = withVault { controller, root ->
        // No codec identifies the second item, as with audio whose probe
        // finds no decoder.
        val codec = object : MediaCodec by Previewless {
            override fun probe(media: PickedMedia): ProbedMedia? =
                if (media.originalFilename == "IMG_0001.jpg") null else Previewless.probe(media)
        }

        val outcomes = controller.importAll(MediaImporter(codec), 3, null) { index, _ -> picked(root, index) }

        assertTrue(assertIs<MediaImporter.Outcome.Imported>(outcomes[1]).storedAsFile)
        assertEquals(listOf(true, true, true), outcomes.map { it is MediaImporter.Outcome.Imported })
        assertEquals(
            "3 imported; some originals were saved as files without media previews; some previews could not be decoded.",
            withTimeout(10_000) { controller.notice.filterNotNull().first { "imported" in it.text } }.text,
        )
    }

    @Test
    fun a_native_probe_exception_preserves_the_original_with_an_explicit_file_notice() = withVault { controller, root ->
        val codec = object : MediaCodec by Previewless {
            override fun probe(media: PickedMedia): ProbedMedia = throw IllegalArgumentException("no decoder")
        }
        val imported = assertIs<MediaImporter.Outcome.Imported>(
            controller.importAll(MediaImporter(codec), 1, null) { _, _ -> picked(root, 0) }.single(),
        )
        assertTrue(imported.storedAsFile)
        assertEquals(MediaBounds.CLASS_OPAQUE, controller.vault.page(ObjectQuery()).objects.single().mediaKind)
        assertContentEquals(ByteArray(2_048), controller.vault.readRange(imported.objectId, 0, 2_048))
        assertEquals("Saved original as a file; a media preview is unavailable on this device.",
            withTimeout(10_000) { controller.notice.filterNotNull().first { "Saved original" in it.text } }.text)
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
            "1 imported, 1 could not be imported, 2 skipped; some previews could not be decoded. ${userCopy(ChurStatus.STORAGE_UNAVAILABLE)}",
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
            "2 imported, 1 could not be imported; some previews could not be decoded. ${userCopy(ChurStatus.IO_FAILURE)}",
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
            "1 imported, 2 cancelled; some previews could not be decoded.",
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
            "1 imported, 2 cancelled; some previews could not be decoded.",
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

    @Test
    fun work_on_the_host_holds_the_auto_lock_off_until_the_pick_ends() = withVault { controller, root ->
        // Nothing in the iOS fetch of an iCloud original, or in the opening of
        // a picked item, calls the vault. Each fetch here outlasts the
        // two-minute limit, and it used to lock the vault in the middle of the
        // pick and drop the rest, `DESIGN.md` §14.4.
        val outcomes = controller.importAll(MediaImporter(Previewless), 2, null) { index, prepare ->
            now += 3 * 60_000
            assertTrue(prepare(50, 100))
            controller.checkIdle()
            assertIs<VaultState.Unlocked>(controller.vaultState.value, "the fetch of item ${index + 1} locked")
            picked(root, index)
        }

        assertEquals(listOf(true, true), outcomes.map { it is MediaImporter.Outcome.Imported })
        withTimeout(10_000) { controller.notice.filterNotNull().first { "imported" in it.text } }
        // The loads the summary starts refresh the clock too; let them end.
        delay(500)
        // The limit counts from the end of the work.
        now += 3 * 60_000
        controller.checkIdle()
        assertIs<VaultState.Locked>(controller.vaultState.value)
    }

    @Test
    fun a_cancelled_pick_no_longer_holds_the_auto_lock_off() = withVault { controller, root ->
        // A cancel is the user giving the work up, so a fetch that has not
        // stopped yet does not keep the vault open past the limit. The loads
        // the open started refresh the clock too; let them end first.
        delay(500)
        controller.importAll(MediaImporter(Previewless), 2, null) { index, prepare ->
            controller.cancelActiveOperation()
            now += 3 * 60_000
            controller.checkIdle()
            assertIs<VaultState.Locked>(controller.vaultState.value)
            if (prepare(50, 100)) picked(root, index) else null
        }
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
            clock = { now },
            notes = InMemoryNoteStore(),
        )
        try {
            controller.start()
            controller.create("Batch-import-password", offerRecovery = false)
            withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }
            test(controller, root)
        } finally {
            controller.shutdown()
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
