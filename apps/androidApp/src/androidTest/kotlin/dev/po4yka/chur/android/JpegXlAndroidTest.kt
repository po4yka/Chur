@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.po4yka.chur.android

import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.MediaImporter
import dev.po4yka.chur.app.vault.JpegXlViewerAnimation
import dev.po4yka.chur.app.vault.ThumbnailCache
import dev.po4yka.chur.app.vault.ThumbnailRendition
import dev.po4yka.chur.app.vault.viewerStill
import dev.po4yka.chur.app.vault.decodeForDisplay
import dev.po4yka.chur.app.vault.openViewerAnimation
import dev.po4yka.chur.ffi.ChurBuffer
import dev.po4yka.chur.ffi.JpegXlDecoder
import dev.po4yka.chur.ffi.ImportRequest
import dev.po4yka.chur.ffi.LockReason
import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.imports.AndroidMediaCodec
import dev.po4yka.chur.imports.MediaBounds
import dev.po4yka.chur.imports.PickedMedia
import dev.po4yka.chur.imports.jpegXlBitmap
import dev.po4yka.chur.vault.VaultRepository
import dev.po4yka.chur.vault.VaultState
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Rust JPEG XL decoding and encrypted application import, preview, view, and export. */
@RunWith(AndroidJUnit4::class)
class JpegXlAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val variants = listOf("still-codestream.jxl", "still-container.jxl", "alpha.jxl", "oriented.jxl", "animated.jxl", "large.jxl")
    private val kinds = listOf(StreamKind.THUMBNAIL, StreamKind.GRID_PREVIEW, StreamKind.SCREEN_PREVIEW)

    @Test fun signaturesOverrideMissingAndWrongMimeAndAllPreviewSizesDecode() {
        for (name in variants) withSource(name) { codec, picked, _ ->
            assertEquals("A .bin source is recognized by bytes", "image/jxl", picked.contentTypeHint)
            for (hint in listOf("application/octet-stream", "image/jpeg", "video/mp4")) {
                val probe = requireNotNull(codec.probe(picked.copy(contentTypeHint = hint))) { "$name / $hint" }
                assertEquals(MediaBounds.CLASS_IMAGE, probe.mediaClass)
                assertEquals("image/jxl", probe.contentType)
                val expected = when (name) {
                    "alpha.jxl" -> 32 to 16
                    "large.jxl" -> 2050 to 1025
                    "animated.jxl" -> 48 to 24
                    else -> 96 to 48
                }
                assertEquals("$name upright width", expected.first, probe.width)
                assertEquals("$name upright height", expected.second, probe.height)
                for (kind in kinds) {
                    val derived = requireNotNull(codec.derive(picked, probe, kind)) { "$name / $kind" }
                    try {
                        assertEquals(0xff, derived.bytes[0].toInt() and 255)
                        assertEquals(0xd8, derived.bytes[1].toInt() and 255)
                        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(derived.bytes, 0, derived.bytes.size))
                        try {
                            val target = requireNotNull(MediaBounds.targetSize(kind, probe.width, probe.height))
                            assertEquals(target.first, bitmap.width)
                            assertEquals(target.second, bitmap.height)
                            if (name == "alpha.jxl") {
                                assertTrue("Transparent image corners composite over white", Color.red(bitmap.getPixel(0, 0)) > 235)
                                assertTrue(Color.green(bitmap.getPixel(0, 0)) > 235)
                                assertTrue(Color.blue(bitmap.getPixel(0, 0)) > 235)
                            }
                        } finally { bitmap.recycle() }
                    } finally { derived.bytes.fill(0) }
                }
            }
        }
    }

    @Test fun cancellationAndMalformedSourcesDoNotPublishPixelsOrAlterOriginals() {
        for (name in listOf("malformed.jxl", "truncated.jxl", "oversized.jxl")) withSource(name) { codec, picked, file ->
            val original = file.readBytes()
            try {
                assertNull(name, codec.probe(picked.copy(contentTypeHint = "image/jxl")))
                withOriginal(original) { assertNull(name, decodeForDisplay(it, 128)) }
                assertArrayEquals(original, file.readBytes())
            } finally { original.fill(0) }
        }
        withSource("still-container.jxl") { codec, picked, file ->
            val probe = requireNotNull(codec.probe(picked))
            val original = file.readBytes()
            try {
                for (cancelAt in 1..5) {
                    var checks = 0
                    assertNull(codec.derive(picked, probe, StreamKind.THUMBNAIL) { ++checks >= cancelAt })
                }
                assertArrayEquals(original, file.readBytes())
                requireNotNull(codec.derive(picked, probe, StreamKind.THUMBNAIL)).bytes.fill(0)
            } finally { original.fill(0) }
        }
    }

    @Test fun productionImporterEncryptsAllPreviewsViewsAuthenticatedOriginalAndExportsExactBytes() = runBlocking {
        val root = File(context.cacheDir, "jxl-vault-${System.nanoTime()}").apply { mkdirs() }
        val vault = VaultRepository(root.path, System::currentTimeMillis)
        try {
            vault.start()
            vault.create("jpeg xl import test password".encodeToByteArray(), false)
            for (name in variants) {
                val bytes = fixture(name)
                val file = sourceFile(bytes)
                try {
                    val codec = AndroidMediaCodec(context)
                    val picked = requireNotNull(codec.open(uri(file)))
                    val result = MediaImporter(codec).import(vault, picked)
                    assertTrue("$name: $result", result is MediaImporter.Outcome.Imported)
                    val imported = result as MediaImporter.Outcome.Imported
                    val storedKinds = if (name == "large.jxl") kinds else listOf(StreamKind.THUMBNAIL)
                    assertEquals(name, storedKinds.size, imported.derivatives)
                    assertNull(name, imported.previewWarning)
                    val projection = vault.page(ObjectQuery()).objects.single { it.objectId.contentEquals(imported.objectId) }
                    assertEquals(MediaBounds.CLASS_IMAGE, projection.mediaKind)
                    assertTrue(projection.thumbnailReady)
                    for (kind in storedKinds) {
                        val jpeg = vault.readDerived(imported.objectId, kind)
                        try {
                            val preview = requireNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
                            try { assertTrue(maxOf(preview.width, preview.height) <= requireNotNull(MediaBounds.longEdge(kind))) }
                            finally { preview.recycle() }
                        } finally { jpeg.fill(0) }
                    }
                    val reader = vault.leaseReader(imported.objectId)
                    try {
                        val info = vault.readerContentInfo(reader)
                        val original = ChurBuffer(info.plaintextSize.toInt())
                        try {
                            vault.readLeasedInto(reader, original)
                            val display = requireNotNull(decodeForDisplay(original, 32)) { name }
                            assertTrue(maxOf(display.width, display.height) <= 32)
                            display.asAndroidBitmap().apply { eraseColor(Color.TRANSPARENT); recycle() }
                        } finally { original.clear(); original.release() }
                    } finally { vault.releaseReader(reader) }
                    val output = File(root, "export-$name")
                    ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE).use { fd ->
                        val operation = vault.beginExport(imported.objectId, fd.fd)
                        try {
                            var progress = vault.poll(operation)
                            while (!progress.terminal) { delay(20); progress = vault.poll(operation) }
                            assertEquals(name, 0, progress.status)
                        } finally { vault.closeOperation(operation) }
                    }
                    assertArrayEquals(name, bytes, output.readBytes())
                } finally { bytes.fill(0); file.delete() }
            }
        } finally { vault.shutdown(); root.deleteRecursively() }
    }

    @Test fun animationRendersDifferentFramesAndViewerClosesBeforeSourceIsCleared() {
        val bytes = fixture("animated.jxl")
        try { withOriginal(bytes) { original ->
            val decoder = JpegXlDecoder.open(original)
            try {
                assertTrue(decoder.frameCount > 1)
                val colors = (0..1).map { index ->
                    val frame = decoder.renderFrame(index, 128)
                    try {
                        assertTrue(frame.durationMs > 0)
                        val bitmap = jpegXlBitmap(frame)
                        try { bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) }
                        finally { bitmap.eraseColor(Color.TRANSPARENT); bitmap.recycle() }
                    } finally { frame.close() }
                }
                assertNotEquals(colors[0], colors[1])
            } finally { decoder.close(); decoder.close() }
            assertTrue("Closed native decoders reject frame reads", runCatching { decoder.renderFrame(0, 128) }.isFailure)
            instrumentation.runOnMainSync {
                val viewer = requireNotNull(openViewerAnimation(original)) as JpegXlViewerAnimation
                val first = requireNotNull(viewer.current).asAndroidBitmap().getPixel(24, 12)
                assertEquals(80L, viewer.frameDurationMs)
                assertTrue(viewer.advanceFrame())
                assertEquals(120L, viewer.frameDurationMs)
                assertNotEquals(first, requireNotNull(viewer.current).asAndroidBitmap().getPixel(24, 12))
                assertTrue(viewer.advanceFrame())
                assertEquals(160L, viewer.frameDurationMs)
                assertTrue(viewer.advanceFrame())
                assertEquals(1, viewer.completedLoops)
                assertTrue(viewer.restartPlayback())
                assertEquals(0, viewer.frameIndex)
                viewer.close()
                assertNull(viewer.current)
                assertFalse(viewer.advanceFrame())
                viewer.close()
            }
        } } finally { bytes.fill(0) }
        val still = fixture("still-codestream.jxl")
        try { withOriginal(still) { original ->
            instrumentation.runOnMainSync { assertNull(openViewerAnimation(original)) }
        } } finally { still.fill(0) }
    }

    @Test fun finiteAnimationStopsAfterItsLoopCountAndRestartsFromFirstFrame() {
        val bytes = fixture("finite-animation.jxl")
        try { withOriginal(bytes) { original ->
            instrumentation.runOnMainSync {
                val viewer = requireNotNull(openViewerAnimation(original)) as JpegXlViewerAnimation
                try {
                    repeat(5) { assertTrue(viewer.advanceFrame()) }
                    assertEquals(2, viewer.frameIndex)
                    assertFalse(viewer.advanceFrame())
                    assertEquals(2, viewer.completedLoops)
                    assertFalse(viewer.advanceFrame())
                    assertTrue(viewer.restartPlayback())
                    assertEquals(0, viewer.frameIndex)
                    assertEquals(0, viewer.completedLoops)
                    assertTrue(viewer.advanceFrame())
                } finally { viewer.close() }
            }
        } } finally { bytes.fill(0) }
    }

    @Test fun pipeBackedProviderWithWrongMimeImportsPreviewsAndExportsExactSource() = runBlocking {
        val sourceUri = Uri.parse("content://dev.po4yka.chur.test.incoming-share/jpeg-xl")
        val root = File(context.cacheDir, "jxl-pipe-vault-${System.nanoTime()}").apply { mkdirs() }
        val vault = VaultRepository(root.path, System::currentTimeMillis)
        val expected = fixture("still-container.jxl")
        try {
            vault.start()
            vault.create("jpeg xl pipe import password".encodeToByteArray(), false)
            val codec = AndroidMediaCodec(context)
            val picked = requireNotNull(codec.open(sourceUri))
            assertFalse(picked.seekable)
            assertEquals("image/jxl", picked.contentTypeHint)
            val probe = requireNotNull(codec.probe(picked))
            assertEquals(96, probe.width)
            assertEquals(48, probe.height)
            val imported = MediaImporter(codec).import(vault, picked)
            assertTrue("$imported", imported is MediaImporter.Outcome.Imported)
            val outcome = imported as MediaImporter.Outcome.Imported
            assertEquals(1, outcome.derivatives)
            assertNull(outcome.previewWarning)
            val preview = vault.readDerived(outcome.objectId, StreamKind.THUMBNAIL)
            try {
                val bitmap = requireNotNull(BitmapFactory.decodeByteArray(preview, 0, preview.size))
                try { assertEquals(96, bitmap.width); assertEquals(48, bitmap.height) } finally { bitmap.recycle() }
            } finally { preview.fill(0) }
            val output = File(root, "export.jxl")
            ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE).use { fd ->
                val operation = vault.beginExport(outcome.objectId, fd.fd)
                try {
                    var progress = vault.poll(operation)
                    while (!progress.terminal) { delay(20); progress = vault.poll(operation) }
                    assertEquals(0, progress.status)
                } finally { vault.closeOperation(operation) }
            }
            assertArrayEquals(expected, output.readBytes())
        } finally { expected.fill(0); vault.shutdown(); root.deleteRecursively() }
    }

    @Test fun originalOnlySyncedImageSupportsGridMosaicViewerAndLockInvalidatesCache() = runBlocking {
        val root = File(context.cacheDir, "jxl-original-only-${System.nanoTime()}").apply { mkdirs() }
        val vault = VaultRepository(root.path, System::currentTimeMillis)
        val source = sourceFile(fixture("large.jxl"))
        val cache = ThumbnailCache()
        try {
            vault.start()
            vault.create("jpeg xl original only password".encodeToByteArray(), false)
            val objectId = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                val operation = vault.beginImport(fd.fd, ImportRequest("image/jxl", true, MediaBounds.CLASS_IMAGE,
                    width = 2050, height = 1025, knownLength = source.length(), originalFilename = "large.jxl"))
                try {
                    var progress = vault.poll(operation)
                    while (!progress.terminal) { delay(20); progress = vault.poll(operation) }
                    assertEquals(0, progress.status)
                    requireNotNull(progress.importedObjectId())
                } finally { vault.closeOperation(operation) }
            }
            val projection = vault.page(ObjectQuery()).objects.single { it.objectId.contentEquals(objectId) }
            assertFalse(projection.thumbnailReady)
            assertTrue("No derivative was manufactured by this import", runCatching { vault.readDerived(objectId, StreamKind.THUMBNAIL) }.isFailure)
            val generation = (vault.state.value as VaultState.Unlocked).generation
            val thumbnail = requireNotNull(cache.loadOriginal(vault, generation, projection))
            assertEquals(320, thumbnail.width)
            assertEquals(160, thumbnail.height)
            val mosaic = requireNotNull(cache.loadOriginal(vault, generation, projection, ThumbnailRendition.Mosaic))
            assertEquals(16, mosaic.width)
            assertEquals(16, mosaic.height)
            val still = requireNotNull(viewerStill(vault, cache, generation, projection))
            try { assertEquals(2048, still.width); assertEquals(1024, still.height) }
            finally { still.asAndroidBitmap().apply { eraseColor(Color.TRANSPARENT); recycle() } }
            cache.invalidate()
            vault.lock(LockReason.USER)
            assertNull(cache.loadOriginal(vault, generation, projection))
            vault.unlock("jpeg xl original only password".encodeToByteArray())
            val nextGeneration = (vault.state.value as VaultState.Unlocked).generation
            assertNotEquals(generation, nextGeneration)
            assertNull(cache.loadOriginal(vault, generation, projection))
            val reloaded = requireNotNull(cache.loadOriginal(vault, nextGeneration, projection))
            assertNotSame(thumbnail, reloaded)
        } finally { cache.clear(); vault.shutdown(); source.delete(); root.deleteRecursively() }
    }

    private fun fixture(name: String) = instrumentation.context.assets.open("jpeg-xl/$name").use { it.readBytes() }
    private fun uri(file: File) = leasedExportUri(context, file)
    private fun sourceFile(bytes: ByteArray) = File(context.cacheDir, "export-scratch/jxl-${System.nanoTime()}.bin").apply {
        parentFile?.mkdirs(); writeBytes(bytes)
    }
    private fun withSource(name: String, action: (AndroidMediaCodec, PickedMedia, File) -> Unit) {
        val bytes = fixture(name)
        val file = sourceFile(bytes)
        val codec = AndroidMediaCodec(context.contentResolver)
        val picked = requireNotNull(codec.open(uri(file)))
        try { action(codec, picked, file) } finally { picked.close(); bytes.fill(0); file.delete() }
    }
    private fun withOriginal(bytes: ByteArray, action: (ChurBuffer) -> Unit) {
        val original = ChurBuffer(bytes.size)
        try { original.copyIn(bytes); action(original) } finally { original.clear(); original.release() }
    }
}
