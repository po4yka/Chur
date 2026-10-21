package dev.po4yka.chur.android

import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaCodecList
import android.os.Build
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.LockReason
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.imports.AndroidMediaCodec
import dev.po4yka.chur.imports.MediaBounds
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Extended bytes are checked independently of file names and provider MIME. */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class VideoAcceptanceAndroidTest {
    @Test
    fun nativeMetadataConfirms4k60VfrLongGopAndPortraitRotation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "export-scratch/video-metadata-${System.nanoTime()}")
        assertTrue(directory.mkdirs())
        try {
            val manifest = videoFixtureManifest().getJSONArray("files")
            assertTrue("The extended matrix must contain at least three files", manifest.length() >= 3)
            for (index in 0 until manifest.length()) {
                val entry = manifest.getJSONObject(index)
                val name = entry.getString("filename")
                val file = copyVideoFixture(directory, name)
                assertEquals(entry.getLong("length"), file.length())
                assertEquals(entry.getString("sha256"), fileDigest(file).joinToString("") { "%02x".format(it) })
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.path)
                    val track = (0 until extractor.trackCount).first {
                        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                    }
                    val format = extractor.getTrackFormat(track)
                    extractor.selectTrack(track)
                    val times = mutableListOf<Long>()
                    val sync = mutableListOf<Long>()
                    while (extractor.sampleTime >= 0) {
                        times += extractor.sampleTime
                        if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) sync += extractor.sampleTime
                        assertTrue("Bound fixture sample count", times.size <= 20_000)
                        if (!extractor.advance()) break
                    }
                    assertTrue("$name must have coded video samples", times.isNotEmpty())
                    when (name) {
                        "main10-4k60.mp4" -> {
                            assertEquals(3840, format.getInteger(MediaFormat.KEY_WIDTH))
                            assertEquals(2160, format.getInteger(MediaFormat.KEY_HEIGHT))
                            val steps = times.sorted().zipWithNext { a, b -> b - a }
                            assertTrue("$name actual sample cadence must be 60 fps", steps.all { it in 16_000..17_500 })
                            assertTrue("$name must contain at least two seconds of video", times.size >= 120)
                        }
                        "main10-long-gop-vfr.mp4" -> {
                            assertTrue("Long video must cover one minute", times.max() >= 60_000_000)
                            assertTrue("VFR must change the actual presentation intervals",
                                times.sorted().zipWithNext { a, b -> b - a }.toSet().size >= 2)
                            assertTrue("The fixture must have a long GOP", sync.sorted().zipWithNext { a, b -> b - a }.any { it >= 8_000_000 })
                        }
                        "main10-portrait-rotation.mp4" -> {
                            assertTrue("Portrait fixture must carry a rotation matrix", format.getInteger(MediaFormat.KEY_ROTATION) in setOf(90, 270))
                            val codec = AndroidMediaCodec(instrumentation.targetContext)
                            val uri = leasedExportUri(instrumentation.targetContext, file)
                            val picked = requireNotNull(codec.open(uri))
                            try {
                                val probe = requireNotNull(codec.probe(picked))
                                assertEquals(MediaBounds.CLASS_VIDEO, probe.mediaClass)
                                assertTrue("Rotation must produce portrait display bounds", probe.height > probe.width)
                                val poster = codec.derive(picked, probe, StreamKind.VIDEO_POSTER)
                                if (MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format) != null) {
                                    val image = requireNotNull(poster) { "Advertised decoder must produce an upright poster" }
                                    try {
                                        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size))
                                        try { assertTrue("Native poster must be upright", bitmap.height > bitmap.width) }
                                        finally { bitmap.recycle() }
                                    } finally { image.bytes.fill(0) }
                                } else assertNull("No decoder means an explicit unavailable preview", poster)
                            } finally { picked.close() }
                        }
                    }
                } finally { extractor.release(); file.delete() }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun encryptedExtendedVideosRenderSeekExportAndInvalidateAtLock() = runBlocking {
        val files = videoFixtureManifest().getJSONArray("files")
        val results = JSONArray()
        val requireDecode = InstrumentationRegistry.getArguments().getString("chur.video.requireDecode") == "true"
        for (index in 0 until files.length()) {
            val name = files.getJSONObject(index).getString("filename")
            val session = VideoAcceptanceSession.create()
            try {
                session.importFixture(name)
                val start = SystemClock.elapsedRealtime()
                val player = session.mount()
                assertTrue("$name must finish preparation", awaitVideo { onVideoMain { videoReady(player) } })
                val supported = onVideoMain { player.currentTracks.isTypeSupported(C.TRACK_TYPE_VIDEO, false) }
                if (requireDecode) assertTrue("Connected-device acceptance requires support for $name", supported)
                val result = JSONObject().put("fixture", name).put("strictVideoSupported", supported).put("sdk", Build.VERSION.SDK_INT)
                if (supported) {
                    assertNull("$name decoder initialization", onVideoMain { player.playerError })
                    onVideoMain { player.play() }
                    assertTrue("$name must produce a real video frame", awaitVideo {
                        onVideoMain { player.videoDecoderCounters?.let { it.ensureUpdated(); it.renderedOutputBufferCount > 0 } == true }
                    })
                    assertNull("$name decoded frame", onVideoMain { player.playerError })
                    result.put("firstFrameMs", SystemClock.elapsedRealtime() - start)
                    val duration = onVideoMain { player.duration }
                    if (duration > 1_000) {
                        val target = duration / 2
                        val before = onVideoMain { requireNotNull(player.videoDecoderCounters).renderedOutputBufferCount }
                        val framePts = AtomicLong(Long.MIN_VALUE)
                        onVideoMain {
                            player.setVideoFrameMetadataListener { pts, _, _, _ -> framePts.set(pts) }
                            player.pause(); player.seekTo(target); player.play()
                        }
                        assertTrue("$name seek must decode a new frame", awaitVideo {
                            onVideoMain { player.videoDecoderCounters?.let {
                                it.ensureUpdated()
                                it.renderedOutputBufferCount > before && player.currentPosition >= target - 100 &&
                                    framePts.get() in (target - 100) * 1_000..(target + 1_000) * 1_000
                            } == true }
                        })
                        assertNull("$name seek", onVideoMain { player.playerError })
                        result.put("seekTargetMs", target)
                        result.put("seekFramePtsUs", framePts.get())
                    }
                    result.put("result", "decoded")
                } else {
                    // Metadata acceptance and exact-byte export remain required
                    // on devices with no decoder. No decoder case is skipped.
                    assertTrue("$name must expose its unsupported video track", onVideoMain {
                        player.currentTracks.containsType(C.TRACK_TYPE_VIDEO)
                    })
                    result.put("result", "unsupported by device")
                }
                session.unmount()
                session.assertExport()
                val reader = session.vault.leaseReader(session.imported.objectId)
                try {
                    session.vault.lock(LockReason.USER)
                    try { session.vault.readLeased(reader, 0, 16); fail("$name lock returned plaintext") }
                    catch (failure: ChurFailure) { assertEquals(ChurStatus.SESSION_EXPIRED, failure.status) }
                } finally { session.vault.releaseReader(reader) }
                result.put("originalExportIdentical", true).put("lockedReaderRejected", true)
                results.put(result)
            } finally { session.close() }
        }
        videoReport("extended-videos", JSONObject().put("cases", results))
    }
}
