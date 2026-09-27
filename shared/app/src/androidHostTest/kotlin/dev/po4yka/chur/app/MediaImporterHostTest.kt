package dev.po4yka.chur.app

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.imports.Derivative
import dev.po4yka.chur.imports.MediaBounds
import dev.po4yka.chur.imports.MediaCodec
import dev.po4yka.chur.imports.PickedMedia
import dev.po4yka.chur.imports.ProbedMedia
import dev.po4yka.chur.vault.VaultRepository
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
}
