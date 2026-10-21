package dev.po4yka.chur.android

import android.media.MediaDataSource
import android.media.MediaCodec
import android.media.MediaCodecList
import android.graphics.SurfaceTexture
import android.view.Surface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.MediaImporter
import dev.po4yka.chur.app.vault.AndroidVideoCompatibilityEncoder
import dev.po4yka.chur.app.vault.PlaybackSource
import dev.po4yka.chur.app.vault.createCompatibleVideoCopy
import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.LockReason
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.imports.AndroidMediaCodec
import dev.po4yka.chur.vault.VaultRepository
import dev.po4yka.chur.vault.VaultState
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith

/** Real platform transcode, RAM-backed muxing, and encrypted derived-video storage. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class VideoCompatibilityAndroidTest {
    private var screen: VideoTestWindow? = null
    @Before fun keepNativeAcceptanceAwake() { screen = VideoTestWindow.open(blank = true) }
    @After fun restoreTestWindow() { screen?.close(); screen = null }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val password = "Compatible video native test password"

    @Test
    fun sdrPqAndHlgBecomeEncryptedAvcSdrCopiesWhileOriginalExportsStayExact() = runBlocking {
        withVault { vault, directory ->
            val encoder = AndroidVideoCompatibilityEncoder(context)
            val fixtures = listOf("sdr10", "hdr10", "hlg").flatMap { prefix ->
                listOf("hvc1", "hev1").map { "hevc-main10/$prefix-$it.mp4" }
            } + listOf("main10-4k60", "main10-long-gop-vfr", "main10-portrait-rotation").map { "video-acceptance/$it.mp4" }
            for (path in fixtures) {
                val name = path.substringAfterLast('/').removeSuffix(".mp4")
                val (original, originalBytes) = importFixture(vault, directory, name, path)
                if (name.startsWith("hdr10-") || name.startsWith("hlg-")) reportToneMapCandidates(originalBytes, name)
                val plaintextBefore = plaintextMp4Files(directory)
                val copy = createCompatibleVideoCopy(original, encoder)
                assertEquals("$name copy MIME", "video/mp4", copy.contentType)
                assertTrue("$name copy size must respect the encrypted derivative boundary", copy.plaintextSize in 1..32L * 1024 * 1024)
                assertTrue("$name transcode must not create a plaintext MP4 file", plaintextBefore == plaintextMp4Files(directory))
                val bytes = readCompatibleVideoForTest(vault, original.objectIdForTest())
                try {
                    assertAvcSdrWithAac(bytes, name)
                    assertDecodedCopy(bytes, name)
                    assertFalse("$name viewing copy must be independently encoded", originalBytes.contentEquals(bytes))
                } finally { bytes.fill(0) }
                val exported = File(directory, "$name-original-export.mp4")
                ParcelFileDescriptor.open(exported, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE).use { target ->
                    val operation = vault.beginExport(original.objectIdForTest(), target.fd)
                    try {
                        var progress = vault.poll(operation)
                        while (!progress.terminal) { delay(20); progress = vault.poll(operation) }
                        assertEquals(name, 0, progress.status)
                    } finally { vault.closeOperation(operation) }
                }
                assertArrayEquals("$name original export must keep every source byte", originalBytes, exported.readBytes())
                originalBytes.fill(0)
                report("COMPATIBLE_VIDEO name=$name encryptedCopyBytes=${copy.plaintextSize} codec=avc transfer=sdr audio=aac originalExport=exact plaintextDiskOutput=absent")
            }
        }
    }

    @Test
    fun cancellationAndLockDuringRealEncodingDoNotSavePartialCopiesOrRetainWorkerThreads() = runBlocking {
        withVault { vault, directory ->
            val encoder = AndroidVideoCompatibilityEncoder(context)
            val (source, originalBytes) = importFixture(vault, directory, "sdr10-hvc1")
            val before = encoderThreads()
            val cancel = AtomicBoolean(false)
            try {
                createCompatibleVideoCopy(source, encoder, cancel::get) { if (it > 0) cancel.set(true) }
                fail("cancelled encoding saved a viewing copy")
            } catch (_: CancellationException) { }
            assertNoCopy(vault, source)
            assertTrue("cancel must close the operation's callback worker", encoderThreads().subtract(before).isEmpty())
            var locked = false
            try {
                createCompatibleVideoCopy(source, encoder) {
                    if (!locked && it > 0) {
                        locked = true
                        runBlocking { vault.lock(LockReason.USER) }
                    }
                }
                fail("locked encoding saved a viewing copy")
            } catch (failure: ChurFailure) { assertEquals(ChurStatus.SESSION_EXPIRED, failure.status) }
            assertTrue("lock must occur after real decoder progress", locked)
            assertNull((vault.state.value as VaultState.Locked).lastFailure)
            assertTrue("lock must close the operation's callback worker", encoderThreads().subtract(before).isEmpty())
            vault.unlock(password.encodeToByteArray())
            assertNoCopy(vault, source)
            // The raw encoder must reject lock/cancel from its completion
            // callback too, before its caller can receive plaintext bytes.
            val completionCancelled = AtomicBoolean(false)
            try {
                val returned = encoder.encode(source, completionCancelled::get) { if (it == 1.0) completionCancelled.set(true) }
                try { fail("Completion callback cancellation returned a completed copy") }
                finally { returned.bytes.fill(0) }
            } catch (_: CancellationException) { }
            assertTrue("Cancellation must occur in the actual completion callback", completionCancelled.get())
            assertNoCopy(vault, source)
            assertTrue("Completion cancellation must close its callback worker", encoderThreads().subtract(before).isEmpty())
            var completionLocked = false
            try {
                val returned = encoder.encode(source, { false }) {
                    if (it == 1.0 && !completionLocked) {
                        completionLocked = true
                        runBlocking { vault.lock(LockReason.USER) }
                    }
                }
                try { fail("Completion callback lock returned plaintext from the old generation") }
                finally { returned.bytes.fill(0) }
            } catch (failure: ChurFailure) { assertEquals(ChurStatus.SESSION_EXPIRED, failure.status) }
            assertTrue("Lock must occur in the actual completion callback", completionLocked)
            assertNull((vault.state.value as VaultState.Locked).lastFailure)
            assertTrue("Completion lock must close its callback worker", encoderThreads().subtract(before).isEmpty())
            vault.unlock(password.encodeToByteArray())
            assertNoCopy(vault, source)
            assertArrayEquals(originalBytes, vault.readRange(source.objectIdForTest(), 0, originalBytes.size))
            originalBytes.fill(0)
        }
    }

    @Test
    fun seekableProxyOutputRejectsAWritePastItsHardLimit() {
        val failure = AtomicReference<Throwable?>(null)
        val outputClass = Class.forName("dev.po4yka.chur.app.vault.BoundedVideoOutput")
        val constructor = outputClass.getDeclaredConstructor(Int::class.javaPrimitiveType,
            AtomicReference::class.java, Function0::class.java).apply { isAccessible = true }
        val callback = constructor.newInstance(1024, failure, {}) as ProxyFileDescriptorCallback
        val worker = HandlerThread("compatible-video-bound-test").apply { start() }
        try {
            requireNotNull(context.getSystemService(StorageManager::class.java)).openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_WRITE, callback, Handler(worker.looper),
            ).use { output ->
                assertEquals(1024, Os.pwrite(output.fileDescriptor, ByteArray(1024) { 42 }, 0, 1024, 0))
                try {
                    Os.pwrite(output.fileDescriptor, byteArrayOf(1), 0, 1, 1024)
                    fail("proxy descriptor allowed growth past its hard limit")
                } catch (problem: ErrnoException) { assertEquals(OsConstants.ENOSPC, problem.errno) }
                assertEquals(1024, Os.fstat(output.fileDescriptor).st_size)
                assertEquals(ChurStatus.RESOURCE_LIMIT_EXCEEDED, (failure.get() as ChurFailure).status)
            }
        } finally {
            outputClass.getDeclaredMethod("clear").apply { isAccessible = true }.invoke(callback)
            worker.quitSafely()
            worker.join(2_000)
            assertFalse("bound-test callback thread must stop", worker.isAlive)
        }
    }

    private fun assertAvcSdrWithAac(bytes: ByteArray, name: String) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(object : MediaDataSource() {
                override fun getSize() = bytes.size.toLong()
                override fun close() = Unit
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    if (position >= bytes.size) return -1
                    val count = minOf(size, bytes.size - position.toInt())
                    bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
                    return count
                }
            })
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val video = formats.single { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            assertEquals(name, "video/avc", video.getString(MediaFormat.KEY_MIME))
            val sequence = requireNotNull(video.getByteBuffer("csd-0")).duplicate()
            val sequenceBytes = ByteArray(sequence.remaining()).also { sequence.get(it) }
            val nalOffset = if (sequenceBytes[2] == 1.toByte()) 3 else 4
            assertEquals("$name AVC sequence parameter set", 7, sequenceBytes[nalOffset].toInt() and 31)
            val sps = NalUnitUtil.parseSpsNalUnit(sequenceBytes, nalOffset, sequenceBytes.size)
            assertEquals("$name encoded luma must be 8-bit", 0, sps.bitDepthLumaMinus8)
            assertEquals("$name encoded chroma must be 8-bit", 0, sps.bitDepthChromaMinus8)
            sequenceBytes.fill(0)
            val width = video.getInteger(MediaFormat.KEY_WIDTH)
            val height = video.getInteger(MediaFormat.KEY_HEIGHT)
            assertTrue(name, maxOf(width, height) <= 1280 && minOf(width, height) <= 720)
            assertEquals(name, MediaFormat.COLOR_TRANSFER_SDR_VIDEO, video.getInteger(MediaFormat.KEY_COLOR_TRANSFER))
            assertEquals(name, MediaFormat.COLOR_STANDARD_BT709, video.getInteger(MediaFormat.KEY_COLOR_STANDARD))
            if (video.containsKey(MediaFormat.KEY_FRAME_RATE)) assertTrue("$name frame rate cap", video.getInteger(MediaFormat.KEY_FRAME_RATE) <= 30)
            extractor.selectTrack(formats.indexOf(video))
            var previousUs = -1L
            var frameCount = 0
            while (extractor.sampleTrackIndex >= 0) {
                val timeUs = extractor.sampleTime
                if (previousUs >= 0) assertTrue("$name frame spacing must cap 30fps: ${timeUs - previousUs}", timeUs - previousUs >= 33_332)
                previousUs = timeUs
                frameCount++
                extractor.advance()
            }
            assertTrue("$name must have encoded frames", frameCount > 0)
            assertTrue("$name must retain the AAC track", formats.any { it.getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" })
            assertFalse("$name must not contain HEVC or HDR input metadata", formats.any {
                it.getString(MediaFormat.KEY_MIME) == "video/hevc" ||
                    (it.containsKey(MediaFormat.KEY_COLOR_TRANSFER) && it.getInteger(MediaFormat.KEY_COLOR_TRANSFER) in
                        listOf(MediaFormat.COLOR_TRANSFER_ST2084, MediaFormat.COLOR_TRANSFER_HLG))
            })
        } finally { extractor.release() }
    }

    private fun reportToneMapCandidates(bytes: ByteArray, name: String) {
        val extractor = MediaExtractor()
        val texture = SurfaceTexture(false)
        val surface = Surface(texture)
        try {
            val input = object : MediaDataSource() {
                override fun getSize() = bytes.size.toLong()
                override fun close() = Unit
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    if (position >= bytes.size) return -1
                    val count = minOf(size, bytes.size - position.toInt())
                    bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
                    return count
                }
            }
            extractor.setDataSource(input)
            val format = (0 until extractor.trackCount).map(extractor::getTrackFormat).first {
                it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            format.setInteger(MediaFormat.KEY_ROTATION, 0)
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {
                !it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, ignoreCase = true) }
            }) {
                var codec: MediaCodec? = null
                val result = try {
                    codec = MediaCodec.createByCodecName(info.name)
                    codec.configure(format, surface, null, 0)
                    val input = codec.inputFormat
                    "configure=accepted transferRequestAck=${if (input.containsKey(MediaFormat.KEY_COLOR_TRANSFER_REQUEST)) input.getInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST) else -1}"
                } catch (_: MediaCodec.CodecException) { "configure=codec-rejected" }
                catch (_: IllegalArgumentException) { "configure=format-rejected" }
                finally { codec?.release() }
                report("COMPATIBLE_VIDEO_CANDIDATE name=$name component=${info.name.take(128).filter { it.isLetterOrDigit() || it in "._-" }} $result")
            }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(input)
                for ((width, height) in listOf(192 to 96, 160 to 90)) {
                    val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
                    val frame = requireNotNull(retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST, width, height, params))
                    try {
                        report("COMPATIBLE_VIDEO_NATIVE_FRAME name=$name requested=${width}x$height config=${frame.config} actualConfig=${params.actualConfig} color=${frame.colorSpace?.name?.take(128)} srgb=${frame.colorSpace?.isSrgb} wideGamut=${frame.colorSpace?.isWideGamut}")
                    } finally { frame.recycle() }
                }
            } finally { retriever.release() }
        } finally { surface.release(); texture.release(); extractor.release() }
    }

    private fun assertDecodedCopy(bytes: ByteArray, name: String) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(object : MediaDataSource() {
                override fun getSize() = bytes.size.toLong()
                override fun close() = Unit
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    if (position >= bytes.size) return -1
                    val count = minOf(size, bytes.size - position.toInt())
                    bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
                    return count
                }
            })
            val duration = requireNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
            val expected = when (name) {
                "main10-long-gop-vfr" -> 64_000L
                "main10-4k60" -> 2_500L
                "main10-portrait-rotation" -> 2_500L
                else -> 1_250L
            }
            assertTrue("$name duration must keep A/V timeline: $duration", kotlin.math.abs(duration - expected) <= 100)
            val seekUs = if (name == "main10-long-gop-vfr") listOf(500_000L, 31_000_000L, 62_000_000L) else listOf(500_000L)
            val frames = seekUs.map { time ->
                requireNotNull(retriever.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST)) { "$name decoded AVC frame at $time" }
            }
            try {
                if (name == "main10-long-gop-vfr") {
                    val hashes = frames.map { frame ->
                        val pixels = IntArray(frame.width * frame.height)
                        frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
                        try { pixels.contentHashCode() } finally { pixels.fill(0) }
                    }
                    assertEquals("VFR distant seeks must decode different frames", frames.size, hashes.distinct().size)
                } else if (name == "main10-portrait-rotation") {
                    assertTrue("Copy must keep portrait display orientation once", frames.single().height > frames.single().width)
                } else if (!name.startsWith("main10-")) {
                    val frame = frames.single()
                    val gray = (0..5).map { bar ->
                        val pixel = frame.getPixel(16 + 32 * bar, 16)
                        val rgb = listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                        assertTrue("$name decoded SDR gray must remain neutral: $rgb", rgb.max() - rgb.min() <= 12)
                        rgb.sum() / 3
                    }
                    assertTrue("$name gray order: $gray", gray.zipWithNext().all { (a, b) -> b >= a - 2 })
                    assertTrue("$name black/white separation: $gray", gray.last() - gray.first() > 96)
                }
            } finally { frames.forEach { it.recycle() } }
        } finally { retriever.release() }
    }

    private suspend fun importFixture(vault: VaultRepository, directory: File, name: String,
        path: String = "hevc-main10/$name.mp4"): Pair<PlaybackSource, ByteArray> {
        val bytes = instrumentation.context.assets.open(path).use { it.readBytes() }
        val file = File(directory, "$name.mp4").apply { writeBytes(bytes) }
        val codec = AndroidMediaCodec(context)
        val result = MediaImporter(codec).import(vault, requireNotNull(codec.open(
            leasedExportUri(context, file),
        ))) as MediaImporter.Outcome.Imported
        return PlaybackSource(vault, result.objectId, vault.detail(result.objectId).contentType, bytes.size.toLong()) to bytes
    }
    // Keep platform operation state internal to the shared module's public API.
    private fun PlaybackSource.objectIdForTest(): ByteArray = javaClass.getDeclaredField("objectId").apply { isAccessible = true }.get(this) as ByteArray
    private suspend fun assertNoCopy(vault: VaultRepository, source: PlaybackSource) {
        try { vault.readDerived(source.objectIdForTest(), StreamKind.COMPATIBLE_VIDEO); fail("partial copy became readable") }
        catch (failure: ChurFailure) { assertEquals(ChurStatus.NOT_FOUND, failure.status) }
    }
    private fun plaintextMp4Files(directory: File) = directory.walkTopDown().filter { it.isFile }.filter {
        it.inputStream().use { input ->
            val header = ByteArray(12)
            input.read(header) >= 8 && header.copyOfRange(4, 8).contentEquals("ftyp".encodeToByteArray())
        }
    }.map { it.relativeTo(directory).path }.toSet()
    private fun encoderThreads() = Thread.getAllStackTraces().keys.filter {
        it.isAlive && it.name == "chur-compatible-video-io"
    }.map { it.id }.toSet()
    private suspend fun withVault(block: suspend (VaultRepository, File) -> Unit) {
        val directory = File(context.cacheDir, "export-scratch/video-compatible-${System.nanoTime()}").apply { mkdirs() }
        val vault = VaultRepository(File(directory, "vault").path, System::currentTimeMillis)
        try { vault.start(); vault.create(password.encodeToByteArray(), false); block(vault, directory) }
        finally { vault.shutdown(); directory.deleteRecursively() }
    }
    private fun report(message: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
}
