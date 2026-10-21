package dev.po4yka.chur.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.MediaImporter
import dev.po4yka.chur.app.vault.AndroidVideoTimelineGenerator
import dev.po4yka.chur.app.vault.PlaybackSource
import dev.po4yka.chur.app.vault.createVideoTimeline
import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.LockReason
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.imports.AndroidMediaCodec
import dev.po4yka.chur.vault.VaultRepository
import dev.po4yka.chur.vault.VaultState
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith

/** Real frame extraction from an encrypted source, including long GOPs and native HDR previews. */
@RunWith(AndroidJUnit4::class)
class VideoTimelineAndroidTest {
    private var screen: VideoTestWindow? = null
    @Before fun keepNativeAcceptanceAwake() { screen = VideoTestWindow.open(blank = true) }
    @After fun restoreTestWindow() { screen?.close(); screen = null }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val password = "Native video timeline test password"

    @Test
    fun twelveVfrSlotsAreDistinctEncryptedAndKeepTheOriginalExportExact() = runBlocking {
        withVault { vault, directory ->
            val (source, original) = importFixture(vault, directory, "video-acceptance/main10-long-gop-vfr.mp4")
            val filesBefore = directory.walkTopDown().filter { it.isFile }.map { it.relativeTo(directory).path }.toSet()
            val bytes = createVideoTimeline(source, AndroidVideoTimelineGenerator(context))
            try {
                assertTrue(bytes.size in 1..2 * 1024 * 1024)
                assertArrayEquals(bytes, vault.readDerived(source.id(), StreamKind.VIDEO_STORYBOARD))
                val image = decode(bytes)
                try {
                    val cells = (0 until 12).map { slot ->
                        val pixels = IntArray(160 * 90)
                        image.getPixels(pixels, 0, 160, slot % 4 * 160, slot / 4 * 90, 160, 90)
                        try { pixels.contentHashCode() } finally { pixels.fill(0) }
                    }
                    assertTrue("Different VFR slot times must not all show one keyframe", cells.distinct().size > 1)
                } finally { image.recycle() }
                val newFiles = directory.walkTopDown().filter { it.isFile }.filter {
                    it.relativeTo(directory).path !in filesBefore
                }
                assertFalse("Timeline generation must not save a plaintext JPEG", newFiles.any { file ->
                    file.inputStream().use { it.read() == 0xff && it.read() == 0xd8 }
                })
                verifyExport(vault, source, directory, original)
                vault.lock(LockReason.USER)
                assertNull((vault.state.value as VaultState.Locked).lastFailure)
                vault.unlock(password.encodeToByteArray())
                assertArrayEquals(bytes, vault.readDerived(source.id(), StreamKind.VIDEO_STORYBOARD))
                report("VIDEO_TIMELINE name=main10-long-gop-vfr slots=12 sourceDurationMs=64000 jpeg=${bytes.size} encrypted=verified originalExport=exact")
            } finally { bytes.fill(0); original.fill(0) }
        }
    }

    @Test
    fun pqHlgSlotsHaveNeutralOrderedGrayAndPortraitSlotsKeepBlackSides() = runBlocking {
        withVault { vault, directory ->
            val generator = AndroidVideoTimelineGenerator(context)
            for (name in listOf("hdr10-hvc1", "hlg-hvc1")) {
                val (source, original) = importFixture(vault, directory, "hevc-main10/$name.mp4")
                val atlas = generator.generate(source) { false }
                try {
                    val image = decode(atlas.bytes)
                    try {
                        // 192x96 -> 160x80 centered vertically in the 160x90 cell.
                        val gray = (0..5).map { bar ->
                            val pixel = image.getPixel(((16 + bar * 32) * 160.0 / 192).toInt(), 18)
                            val rgb = listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                            assertTrue("$name native SDR gray must remain neutral: $rgb", rgb.max() - rgb.min() <= 12)
                            rgb.sum() / 3
                        }
                        assertTrue("$name gray order: $gray", gray.zipWithNext().all { (a, b) -> b >= a - 2 })
                        assertTrue("$name black/white separation: $gray", gray.last() - gray.first() > 96)
                    } finally { image.recycle() }
                    report("VIDEO_TIMELINE name=$name nativeSdrGray=verified")
                } finally { atlas.bytes.fill(0); original.fill(0) }
            }
            val (source, original) = importFixture(vault, directory, "video-acceptance/main10-portrait-rotation.mp4")
            val atlas = generator.generate(source) { false }
            try {
                val image = decode(atlas.bytes)
                try {
                    for (x in listOf(5, 155)) {
                        val pixel = image.getPixel(x, 45)
                        assertTrue("Portrait sides must be black", maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) <= 8)
                    }
                    assertTrue("Portrait content must appear in the center", (10 until 80).any { y ->
                        val pixel = image.getPixel(80, y)
                        maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) > 32
                    })
                } finally { image.recycle() }
            } finally { atlas.bytes.fill(0); original.fill(0) }
        }
    }

    @Test
    fun cancellationAndLockDuringNativeReadsNeverSavePartialAtlases() = runBlocking {
        withVault { vault, directory ->
            val (source, original) = importFixture(vault, directory, "video-acceptance/main10-long-gop-vfr.mp4")
            val generator = AndroidVideoTimelineGenerator(context)
            val checks = AtomicInteger()
            try {
                createVideoTimeline(source, generator) { checks.incrementAndGet() >= 10 }
                fail("Cancelled timeline became readable")
            } catch (_: CancellationException) { }
            assertTrue("Cancellation must be checked during native work", checks.get() >= 10)
            assertNoAtlas(vault, source)
            checks.set(0)
            var locked = false
            try {
                createVideoTimeline(source, generator) {
                    if (checks.incrementAndGet() == 10) { runBlocking { vault.lock(LockReason.USER) }; locked = true }
                    false
                }
                fail("Locked timeline became readable")
            } catch (failure: ChurFailure) { assertEquals(ChurStatus.SESSION_EXPIRED, failure.status) }
            assertTrue("Lock must occur during generation", locked)
            assertNull((vault.state.value as VaultState.Locked).lastFailure)
            vault.unlock(password.encodeToByteArray())
            assertNoAtlas(vault, source)
            verifyExport(vault, source, directory, original)
            original.fill(0)
        }
    }

    private fun decode(bytes: ByteArray): Bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)).also {
        assertEquals(640, it.width); assertEquals(270, it.height)
    }
    private suspend fun importFixture(vault: VaultRepository, directory: File, path: String): Pair<PlaybackSource, ByteArray> {
        val bytes = instrumentation.context.assets.open(path).use { it.readBytes() }
        val file = File(directory, path.substringAfterLast('/')).apply { writeBytes(bytes) }
        val codec = AndroidMediaCodec(context)
        val result = MediaImporter(codec).import(vault, requireNotNull(codec.open(
            leasedExportUri(context, file),
        ))) as MediaImporter.Outcome.Imported
        return PlaybackSource(vault, result.objectId, vault.detail(result.objectId).contentType, bytes.size.toLong()) to bytes
    }
    private fun PlaybackSource.id(): ByteArray = javaClass.getDeclaredField("objectId").apply { isAccessible = true }.get(this) as ByteArray
    private suspend fun assertNoAtlas(vault: VaultRepository, source: PlaybackSource) {
        try { vault.readDerived(source.id(), StreamKind.VIDEO_STORYBOARD); fail("Partial atlas was saved") }
        catch (failure: ChurFailure) { assertEquals(ChurStatus.NOT_FOUND, failure.status) }
    }
    private suspend fun verifyExport(vault: VaultRepository, source: PlaybackSource, directory: File, original: ByteArray) {
        val file = File(directory, "original-export.mp4")
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE).use { target ->
            val operation = vault.beginExport(source.id(), target.fd)
            try {
                var state = vault.poll(operation)
                while (!state.terminal) { delay(20); state = vault.poll(operation) }
                assertEquals(0, state.status)
            } finally { vault.closeOperation(operation) }
        }
        assertArrayEquals("Original export must keep every input byte", original, file.readBytes())
    }
    private suspend fun withVault(block: suspend (VaultRepository, File) -> Unit) {
        val directory = File(context.cacheDir, "export-scratch/video-timeline-${System.nanoTime()}").apply { mkdirs() }
        val vault = VaultRepository(File(directory, "vault").path, System::currentTimeMillis)
        try { vault.start(); vault.create(password.encodeToByteArray(), false); block(vault, directory) }
        finally { vault.shutdown(); directory.deleteRecursively() }
    }
    private fun report(message: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
}
