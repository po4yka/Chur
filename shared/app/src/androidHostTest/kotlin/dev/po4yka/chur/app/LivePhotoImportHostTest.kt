package dev.po4yka.chur.app

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.app.vault.livePhotoPlaybackFor
import dev.po4yka.chur.app.vault.readVaultLivePhoto
import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.imports.Derivative
import dev.po4yka.chur.imports.LivePhotoBundle
import dev.po4yka.chur.imports.MediaBounds
import dev.po4yka.chur.imports.MediaCodec
import dev.po4yka.chur.imports.PickedMedia
import dev.po4yka.chur.imports.ProbedMedia
import dev.po4yka.chur.vault.VaultRepository
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The actual platform descriptor validator, SHA checks, and Rust encrypted persistence. */
class LivePhotoImportHostTest {
    private val fixture: File get() = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { File(it, "tests/media/live-photo-v1") }.first { File(it, "header.bin").isFile }
    private val unavailableCodec = object : MediaCodec {
        override fun probe(media: PickedMedia): ProbedMedia? = null
        override fun derive(media: PickedMedia, probe: ProbedMedia, kind: StreamKind, cancelRequested: () -> Boolean): Derivative? =
            error("an opaque package must not attempt a preview")
    }

    @Test
    fun unavailablePhotoDecoderKeepsCompleteValidatedBundleWithFileWarning() = runBlocking {
        withVault { vault, input ->
            var closes = 0
            val picked = picked(input) { closes++; input.close() }
            val result = importer().import(vault, picked)
            val imported = assertIs<MediaImporter.Outcome.Imported>(result)
            assertTrue(imported.storedAsFile)
            assertEquals(0, imported.derivatives)
            assertEquals(MediaImporter.PreviewWarning.DECODING_FAILED, imported.previewWarning)
            assertEquals(1, closes)
            val detail = vault.detail(imported.objectId)
            assertEquals(LivePhotoBundle.CONTENT_TYPE, detail.contentType)
            assertEquals(0, detail.width)
            assertEquals(0, detail.height)
            assertEquals(MediaBounds.CLASS_OPAQUE, vault.page(ObjectQuery()).objects.single().mediaKind)
            val expected = formatBundleBytes()
            val actual = vault.readRange(imported.objectId, 0, expected.size)
            try { assertContentEquals(expected, actual) } finally { expected.fill(0); actual.fill(0) }
        }
    }

    @Test
    fun compoundMovieRetainsItsBoundsAndExplicitLibraryVerification() = runBlocking {
        withVault { vault, input ->
            val imported = assertIs<MediaImporter.Outcome.Imported>(
                importer().import(vault, picked(input) { input.close() }),
            )
            val bundle = assertNotNull(readVaultLivePhoto(vault, imported.objectId))
            var verifications = 0
            val source = assertNotNull(livePhotoPlaybackFor(vault, imported.objectId) { verifications++ })
            assertEquals(bundle.displayVideo.offset, source.offset)
            assertEquals(bundle.displayVideo.byteLength, source.length)
            assertEquals(0, verifications)
            assertNotNull(source.onVerify).invoke()
            assertEquals(1, verifications)
        }
    }

    @Test
    fun cancellationDuringFullResourceValidationClosesSourceOnceAndCommitsNothing() = runBlocking {
        withVault { vault, input ->
            var closes = 0
            var checks = 0
            val outcome = importer().import(vault, picked(input) { closes++; input.close() }, cancelRequested = { ++checks >= 5 })
            assertEquals(MediaImporter.Outcome.Refused(ChurStatus.CANCELLED), outcome)
            assertEquals(1, closes)
            assertTrue(vault.page(ObjectQuery()).objects.isEmpty())
        }
    }

    private fun importer() = MediaImporter(unavailableCodec)
    private fun formatBundleBytes(): ByteArray = File(fixture, "header.bin").readBytes() + File(fixture, "payload.bin").readBytes()
    private fun picked(input: RandomAccessFile, close: () -> Unit): PickedMedia {
        val fd = input.fd.javaClass.getDeclaredField("fd").apply { isAccessible = true }.getInt(input.fd)
        return PickedMedia(fd, true, input.length(), LivePhotoBundle.CONTENT_TYPE, "native.churlive", null, null, close)
    }
    private suspend fun withVault(block: suspend (VaultRepository, RandomAccessFile) -> Unit) {
        val root = File(System.getProperty("java.io.tmpdir"), "chur-live-import-${System.nanoTime()}").apply { mkdirs() }
        val vault = VaultRepository(root.path, { 1_700_000_000_000L })
        try {
            vault.start(); vault.create("live photo unavailable codec test".encodeToByteArray(), false)
            val source = File(root, "source.churlive").apply { writeBytes(formatBundleBytes()) }
            RandomAccessFile(source, "r").use { block(vault, it) }
        } finally { vault.shutdown(); root.deleteRecursively() }
    }
}
