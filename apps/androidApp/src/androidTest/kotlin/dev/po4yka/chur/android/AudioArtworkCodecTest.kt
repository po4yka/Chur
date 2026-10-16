package dev.po4yka.chur.android

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.MediaImporter
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.imports.AndroidMediaCodec
import dev.po4yka.chur.imports.MediaBounds
import dev.po4yka.chur.imports.PickedMedia
import dev.po4yka.chur.imports.Waveform
import dev.po4yka.chur.vault.VaultRepository
import java.io.ByteArrayOutputStream
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

/** Platform extractors and decoders read original synthetic audio containers. */
@RunWith(AndroidJUnit4::class)
class AudioArtworkCodecTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val artKinds = listOf(StreamKind.THUMBNAIL, StreamKind.GRID_PREVIEW, StreamKind.SCREEN_PREVIEW)

    @Test
    fun mp3M4aAndFlacProduceJpegArtworkAndMp3ExifIsNormalized() {
        for (name in listOf("with-art.mp3", "with-art.m4a", "with-art.flac", "oriented-art.mp3")) {
            withSource(name) { codec, picked, _ ->
                val probe = requireNotNull(codec.probe(picked))
                assertEquals(MediaBounds.CLASS_AUDIO, probe.mediaClass)
                assertEquals(0, probe.width)
                assertEquals(0, probe.height)
                assertTrue(probe.durationMs > 0)
                val oriented = name.startsWith("oriented")
                for (kind in artKinds) {
                    val derivative = requireNotNull(codec.derive(picked, probe, kind)) { "$name has $kind artwork" }
                    try {
                        assertEquals(if (oriented) 48 else 96, derivative.width)
                        assertEquals(if (oriented) 96 else 48, derivative.height)
                        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(derivative.bytes, 0, derivative.bytes.size))
                        try {
                            assertEquals(derivative.width, bitmap.width)
                            assertEquals(derivative.height, bitmap.height)
                            assertRed(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
                        } finally { bitmap.recycle() }
                    } finally { derivative.bytes.fill(0) }
                }
            }
        }
    }

    @Test
    fun absentOrCorruptArtworkDoesNotPreventAudioProbeOrWaveform() {
        for (name in listOf("no-art.mp3", "no-art.m4a", "corrupt-art.mp3", "corrupt-art.m4a", "corrupt-art.flac")) {
            withSource(name) { codec, picked, _ ->
                val probe = requireNotNull(codec.probe(picked))
                assertEquals(MediaBounds.CLASS_AUDIO, probe.mediaClass)
                for (kind in artKinds) assertNull("$name must not claim $kind", codec.derive(picked, probe, kind))
                val waveform = requireNotNull(codec.derive(picked, probe, StreamKind.AUDIO_WAVEFORM))
                try {
                    assertNotNull(Waveform.peaksOf(waveform.bytes))
                    assertEquals(probe.durationMs, Waveform.durationMsOf(waveform.bytes))
                } finally { waveform.bytes.fill(0) }
            }
        }
    }

    @Test
    fun largeEmbeddedArtworkIsDownsampledToEachDerivativeBound() {
        withSource("large-art.mp3") { codec, picked, _ ->
            val probe = requireNotNull(codec.probe(picked))
            for ((kind, width) in listOf(StreamKind.THUMBNAIL to 320, StreamKind.GRID_PREVIEW to 640, StreamKind.SCREEN_PREVIEW to 2048)) {
                val derivative = requireNotNull(codec.derive(picked, probe, kind))
                try {
                    assertEquals(width, derivative.width)
                    assertEquals(width / 2, derivative.height)
                    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(derivative.bytes, 0, derivative.bytes.size))
                    try {
                        assertEquals(width, bitmap.width)
                        assertEquals(width / 2, bitmap.height)
                        assertRed(bitmap.getPixel(width / 2, width / 4))
                    } finally { bitmap.recycle() }
                } finally { derivative.bytes.fill(0) }
            }
        }
    }

    @Test
    fun unsafeArtworkAndCancellationNeverPublishAPictureOrChangeTheSource() {
        val cover = fixture("cover.jpg")
        val tooWide = cover.copyOf().also { bytes ->
            val marker = (0 until bytes.size - 8).first { bytes[it] == 0xff.toByte() && bytes[it + 1] == 0xc0.toByte() }
            bytes[marker + 7] = (20_000 ushr 8).toByte()
            bytes[marker + 8] = 20_000.toByte()
        }
        val tooLarge = cover.copyOf(MediaBounds.AUDIO_ART_ENCODED_MAX_BYTES + 1)
        for (art in listOf(tooWide, tooLarge)) {
            val source = mp3WithArtwork(art)
            withSource("unsafe.mp3", source) { codec, picked, file ->
                val probe = requireNotNull(codec.probe(picked))
                assertEquals(MediaBounds.CLASS_AUDIO, probe.mediaClass)
                for (kind in artKinds) assertNull(codec.derive(picked, probe, kind))
                assertArrayEquals(source, file.readBytes())
            }
            source.fill(0)
        }
        tooLarge.fill(0)
        withSource("with-art.mp3") { codec, picked, file ->
            val probe = requireNotNull(codec.probe(picked))
            val before = file.readBytes()
            for (cancelAt in 1..7) {
                var checks = 0
                assertNull("cancellation checkpoint $cancelAt", codec.derive(picked, probe, StreamKind.THUMBNAIL) {
                    ++checks >= cancelAt
                })
                assertTrue(checks >= cancelAt)
            }
            assertArrayEquals(before, file.readBytes())
            // A cancelled extraction releases its retriever and does not close
            // the picked source: a later normal derivation still succeeds.
            val retry = requireNotNull(codec.derive(picked, probe, StreamKind.THUMBNAIL))
            retry.bytes.fill(0)
            assertTrue(picked.descriptor >= 0)
        }
    }

    @Test
    fun productionImporterEncryptsArtworkAndExportsIdenticalAudioBytes() = runBlocking {
        val root = File(context.cacheDir, "audio-art-native-${System.nanoTime()}").apply { mkdirs() }
        val vault = VaultRepository(root.path, System::currentTimeMillis)
        try {
            vault.start()
            vault.create("audio artwork test password".encodeToByteArray(), false)
            for (name in listOf("with-art.mp3", "no-art.mp3", "corrupt-art.mp3")) {
                val bytes = fixture(name)
                val source = File(context.cacheDir, "export-scratch/audio-art-${System.nanoTime()}.mp3")
                source.parentFile?.mkdirs()
                source.writeBytes(bytes)
                try {
                    val codec = AndroidMediaCodec(context)
                    val picked = requireNotNull(codec.open(FileProvider.getUriForFile(context, "${context.packageName}.exports", source)))
                    val outcome = MediaImporter(codec).import(vault, picked)
                    assertTrue("$name imports: $outcome", outcome is MediaImporter.Outcome.Imported)
                    val id = (outcome as MediaImporter.Outcome.Imported).objectId
                    val hasArt = name == "with-art.mp3"
                    assertEquals(if (hasArt) 4 else 1, outcome.derivatives)
                    val projection = vault.page(dev.po4yka.chur.ffi.ObjectQuery()).objects.single { it.objectId.contentEquals(id) }
                    assertEquals(MediaBounds.CLASS_AUDIO, projection.mediaKind)
                    assertEquals(0, projection.width)
                    assertEquals(0, projection.height)
                    assertEquals(hasArt, projection.thumbnailReady)
                    val waveform = vault.readDerived(id, StreamKind.AUDIO_WAVEFORM)
                    try {
                        assertNotNull(Waveform.peaksOf(waveform))
                        assertEquals(projection.durationMs, Waveform.durationMsOf(waveform))
                    } finally { waveform.fill(0) }
                    if (hasArt) for (kind in artKinds) {
                        val jpeg = vault.readDerived(id, kind)
                        try {
                            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
                            try { assertRed(bitmap.getPixel(48, 24)) } finally { bitmap.recycle() }
                        } finally { jpeg.fill(0) }
                    }
                    val output = File(root, "roundtrip-$name")
                    ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE).use { fd ->
                        val operation = vault.beginExport(id, fd.fd)
                        try {
                            var progress = vault.poll(operation)
                            while (!progress.terminal) { delay(20); progress = vault.poll(operation) }
                            assertEquals(0, progress.status)
                        } finally { vault.closeOperation(operation) }
                    }
                    assertArrayEquals(bytes, output.readBytes())
                } finally { source.delete(); bytes.fill(0) }
            }
        } finally { vault.shutdown(); root.deleteRecursively() }
    }

    private fun fixture(name: String) = instrumentation.context.assets.open("audio-art/$name").use { it.readBytes() }

    private fun withSource(name: String, bytes: ByteArray = fixture(name), action: (AndroidMediaCodec, PickedMedia, File) -> Unit) {
        val file = File(context.cacheDir, "export-scratch/audio-art-${System.nanoTime()}-$name")
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        val codec = AndroidMediaCodec(context.contentResolver)
        val picked = requireNotNull(codec.open(FileProvider.getUriForFile(context, "${context.packageName}.exports", file)))
        try { action(codec, picked, file) } finally { picked.close(); file.delete() }
    }

    private fun mp3WithArtwork(art: ByteArray): ByteArray {
        val original = fixture("no-art.mp3")
        val oldTagSize = if (original.copyOfRange(0, 3).contentEquals("ID3".toByteArray()))
            10 + (6..9).fold(0) { size, index -> (size shl 7) or (original[index].toInt() and 127) } else 0
        val payload = byteArrayOf(0) + "image/jpeg".toByteArray() + byteArrayOf(0, 3, 0) + art
        val frame = "APIC".toByteArray() + byteArrayOf((payload.size ushr 24).toByte(), (payload.size ushr 16).toByte(),
            (payload.size ushr 8).toByte(), payload.size.toByte(), 0, 0) + payload
        return ByteArrayOutputStream().use {
            it.write(byteArrayOf(73, 68, 51, 3, 0, 0))
            for (shift in listOf(21, 14, 7, 0)) it.write((frame.size ushr shift) and 127)
            it.write(frame)
            it.write(original, oldTagSize, original.size - oldTagSize)
            it.toByteArray()
        }
    }

    private fun assertRed(pixel: Int) {
        assertTrue("the synthetic red cover is decoded", Color.red(pixel) > 180 && Color.green(pixel) < 90 && Color.blue(pixel) < 90)
    }
}
