package dev.po4yka.chur.android

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.MediaImporter
import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.imports.AndroidMediaCodec
import dev.po4yka.chur.imports.MediaBounds
import dev.po4yka.chur.imports.PickedMedia
import dev.po4yka.chur.imports.Waveform
import dev.po4yka.chur.vault.VaultRepository
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Native decoders read generated image, container, and interleaved PCM bytes. */
@RunWith(AndroidJUnit4::class)
class MediaFormatsAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun imageDecodersConfirmActualBytesWhenProviderMimeIsWrong() {
        val names = listOf("still.png", "animated.gif", "animated.webp", "animated.png", "camera.dng") +
            if (Build.VERSION.SDK_INT >= 34) listOf("still.avif") else emptyList()
        for (name in names) withSource(name) { codec, picked ->
            val probe = requireNotNull(codec.probe(picked.copy(contentTypeHint = "application/octet-stream"))) { name }
            assertEquals(name, MediaBounds.CLASS_IMAGE, probe.mediaClass)
            assertEquals(name, if (name == "camera.dng") 600 else 96, probe.width)
            assertEquals(name, if (name == "camera.dng") 337 else 48, probe.height)
            val derivative = requireNotNull(codec.derive(picked, probe, StreamKind.THUMBNAIL)) { name }
            try {
                val bitmap = requireNotNull(BitmapFactory.decodeByteArray(derivative.bytes, 0, derivative.bytes.size))
                val target = requireNotNull(MediaBounds.targetSize(StreamKind.THUMBNAIL, probe.width, probe.height))
                try { assertEquals(target.first, bitmap.width); assertEquals(target.second, bitmap.height) } finally { bitmap.recycle() }
            } finally { derivative.bytes.fill(0) }
        }
    }

    @Test
    fun unnamedImagesAndContainersAreDetectedFromBytes() {
        for ((name, kind) in listOf("still.png" to MediaBounds.CLASS_IMAGE, "stereo.opus" to MediaBounds.CLASS_AUDIO,
            "clip.webm" to MediaBounds.CLASS_VIDEO, "clip.mkv" to MediaBounds.CLASS_VIDEO)) {
            withSource(name, "source.bin") { codec, picked ->
                assertEquals("application/octet-stream", picked.contentTypeHint)
                assertEquals(name, kind, requireNotNull(codec.probe(picked)).mediaClass)
            }
        }
    }

    @Test
    fun rotatedVideoBoundsAndPostersKeepPortraitAspectRatio() {
        withSource("rotated.mp4") { codec, picked ->
            val probe = requireNotNull(codec.probe(picked))
            assertEquals(MediaBounds.CLASS_VIDEO, probe.mediaClass)
            assertEquals(96, probe.width)
            assertEquals(192, probe.height)
            for (kind in listOf(StreamKind.THUMBNAIL, StreamKind.GRID_PREVIEW, StreamKind.VIDEO_POSTER)) {
                val derivative = requireNotNull(codec.derive(picked, probe, kind))
                try {
                    assertEquals(96, derivative.width)
                    assertEquals(192, derivative.height)
                    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(derivative.bytes, 0, derivative.bytes.size))
                    try {
                        assertEquals(bitmap.width * 2, bitmap.height)
                        val white = (0 until bitmap.width).filter { x ->
                            Color.green(bitmap.getPixel(x, bitmap.height / 2)) > 180
                        }
                        val tall = (0 until bitmap.height).filter { y ->
                            Color.green(bitmap.getPixel(bitmap.width / 2, y)) > 180
                        }
                        assertTrue("The circle must stay round", kotlin.math.abs(white.size - tall.size) <= 4)
                        assertTrue("The video circle is visible", white.size > 50)
                    } finally { bitmap.recycle() }
                } finally { derivative.bytes.fill(0) }
            }
        }
    }

    @Test
    fun stereoWaveformIncludesTheEndAndKeepsSilentHalfSilent() {
        for (name in listOf("stereo.wav", "stereo-8bit.wav", "stereo.flac", "stereo.opus")) {
            withSource(name) { codec, picked ->
                val probe = requireNotNull(codec.probe(picked))
                assertEquals(name, MediaBounds.CLASS_AUDIO, probe.mediaClass)
                val waveform = requireNotNull(codec.derive(picked, probe, StreamKind.AUDIO_WAVEFORM)) { name }
                try {
                    val peaks = requireNotNull(Waveform.peaksOf(waveform.bytes)).map { it.toInt() and 255 }
                    assertTrue("$name starts silent", peaks.take(100).all { it < 5 })
                    assertTrue("$name retains the audible ending", peaks.takeLast(100).all { it > 150 })
                    assertEquals(probe.durationMs, Waveform.durationMsOf(waveform.bytes))
                } finally { waveform.bytes.fill(0) }
                var checks = 0
                assertNull(codec.derive(picked, probe, StreamKind.AUDIO_WAVEFORM) { ++checks >= 2 })
            }
        }
    }

    @Test
    fun priorityFormatsKeepIdenticalOriginalBytesThroughEncryptedImportAndExport() = runBlocking {
        val root = File(context.cacheDir, "format-vault-${System.nanoTime()}").apply { mkdirs() }
        val vault = VaultRepository(root.path, System::currentTimeMillis)
        try {
            vault.start()
            vault.create("media format test password".encodeToByteArray(), false)
            val names = listOf("still.tiff", "synthetic.dng", "camera.dng", "animated.gif", "animated.webp", "animated.png",
                "rotated.mp4", "clip.webm", "clip.mkv", "stereo.wav", "stereo-8bit.wav", "stereo.flac", "stereo.opus") +
                if (Build.VERSION.SDK_INT >= 34) listOf("still.avif") else emptyList()
            for (name in names) {
                val bytes = fixture(name)
                val file = sourceFile(name, bytes)
                try {
                    val codec = AndroidMediaCodec(context)
                    val picked = requireNotNull(codec.open(uri(file)))
                    val nativeProbe = codec.probe(picked)
                    val imported = MediaImporter(codec).import(vault, picked)
                    assertTrue("$name: $imported", imported is MediaImporter.Outcome.Imported)
                    val outcome = imported as MediaImporter.Outcome.Imported
                    val projection = vault.page(ObjectQuery()).objects.single { it.objectId.contentEquals(outcome.objectId) }
                    assertEquals(name, nativeProbe?.mediaClass ?: MediaBounds.CLASS_OPAQUE, projection.mediaKind)
                    if (nativeProbe != null) {
                        assertTrue("$name has a native preview", outcome.derivatives > 0)
                    } else {
                        assertEquals(0, outcome.derivatives)
                        assertNotNull("Unavailable native decoder is reported", outcome.previewWarning)
                    }
                    val output = File(root, "export-$name")
                    ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE).use { descriptor ->
                        val operation = vault.beginExport(outcome.objectId, descriptor.fd)
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

    private fun fixture(name: String) = instrumentation.context.assets.open("media-formats/$name").use { it.readBytes() }
    private fun uri(file: File) = leasedExportUri(context, file)
    private fun sourceFile(name: String, bytes: ByteArray) =
        File(context.cacheDir, "export-scratch/format-${System.nanoTime()}-$name").apply {
            parentFile?.mkdirs(); writeBytes(bytes)
        }

    private fun withSource(name: String, storedName: String = name, action: (AndroidMediaCodec, PickedMedia) -> Unit) {
        val bytes = fixture(name)
        val file = sourceFile(storedName, bytes)
        val codec = AndroidMediaCodec(context.contentResolver)
        val picked = requireNotNull(codec.open(uri(file)))
        try { action(codec, picked) } finally { picked.close(); bytes.fill(0); file.delete() }
    }
}
