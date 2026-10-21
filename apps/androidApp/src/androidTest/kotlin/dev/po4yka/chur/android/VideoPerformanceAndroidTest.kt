package dev.po4yka.chur.android

import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

/** Device measurements, not a universal speed budget or an optimization claim. */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class VideoPerformanceAndroidTest {
    @Test
    fun measureEncryptedLongGopStartupSeekDropsAndProcessMemory() = runBlocking {
        val session = VideoAcceptanceSession.create()
        val samples = JSONArray()
        val startupMs = mutableListOf<Long>()
        val seekMs = mutableListOf<Long>()
        val report = JSONObject().put("fixture", "main10-long-gop-vfr.mp4")
            .put("sdk", Build.VERSION.SDK_INT).put("model", Build.MODEL)
            .put("buildType", "debug instrumentation")
            .put("cacheState", "not flushed; repeated runs share process and filesystem caches")
            .put("firstFrameEndpoint", "first observed rendered video buffer after mounting the app player")
            .put("memoryScope", "sampled whole-app process PSS; excludes separate codec processes and unreported GPU memory")
        try {
            session.importFixture("main10-long-gop-vfr.mp4")
            repeat(3) { iteration ->
                val baselinePss = Debug.getPss()
                var peakPss = baselinePss
                val start = SystemClock.elapsedRealtime()
                val player = session.mount()
                assertTrue("Long-GOP player must finish preparation", awaitVideo { onVideoMain { videoReady(player) } })
                val supported = onVideoMain { player.currentTracks.isTypeSupported(C.TRACK_TYPE_VIDEO, false) }
                report.put("strictVideoSupported", supported)
                if (!supported) {
                    assertFalse("Connected-device run requires real video decoding",
                        InstrumentationRegistry.getArguments().getString("chur.video.requireDecode") == "true")
                    report.put("result", "unsupported by device").put("metrics", JSONObject.NULL)
                        .put("unavailableReason", "Native video track is not supported; no decoder timing is claimed")
                    videoReport("performance-android", report)
                    return@runBlocking
                }
                assertNull(onVideoMain { player.playerError })
                onVideoMain { player.play() }
                assertTrue("Startup must render video, not only advance audio", awaitVideo {
                    onVideoMain { player.videoDecoderCounters?.let { it.ensureUpdated(); it.renderedOutputBufferCount > 0 } == true }
                })
                startupMs += SystemClock.elapsedRealtime() - start
                peakPss = maxOf(peakPss, Debug.getPss())
                val presentationUs = AtomicLong(Long.MIN_VALUE)
                onVideoMain {
                    player.setVideoFrameMetadataListener { pts, _, _, _ -> presentationUs.set(pts) }
                }
                val seeks = JSONArray()
                for (targetMs in listOf(15_375L, 57_750L, 31_125L)) {
                    val before = onVideoMain {
                        player.pause()
                        requireNotNull(player.videoDecoderCounters).apply { ensureUpdated() }.renderedOutputBufferCount
                    }
                    presentationUs.set(Long.MIN_VALUE)
                    val seekStart = SystemClock.elapsedRealtime()
                    onVideoMain { player.seekTo(targetMs); player.play() }
                    assertTrue("Seek to $targetMs must produce a new frame at the requested time", awaitVideo {
                        val pts = presentationUs.get()
                        onVideoMain {
                            player.videoDecoderCounters?.let {
                                it.ensureUpdated()
                                it.renderedOutputBufferCount > before && pts in (targetMs - 100) * 1_000..(targetMs + 1_000) * 1_000 &&
                                    player.currentPosition in targetMs - 100..targetMs + 1_000
                            } == true
                        }
                    })
                    assertNull("Seek must not hide a native reader/decoder failure", onVideoMain { player.playerError })
                    val elapsed = SystemClock.elapsedRealtime() - seekStart
                    seekMs += elapsed
                    seeks.put(JSONObject().put("targetMs", targetMs).put("latencyMs", elapsed)
                        .put("observedFramePtsUs", presentationUs.get()))
                    peakPss = maxOf(peakPss, Debug.getPss())
                }
                val beforeWindow = onVideoMain {
                    requireNotNull(player.videoDecoderCounters).apply { ensureUpdated() }.let {
                        it.renderedOutputBufferCount to it.droppedBufferCount
                    }
                }
                val windowStart = SystemClock.elapsedRealtime()
                val cpuStart = Process.getElapsedCpuTime()
                repeat(5) {
                    SystemClock.sleep(1_000)
                    peakPss = maxOf(peakPss, Debug.getPss())
                }
                val windowMs = SystemClock.elapsedRealtime() - windowStart
                val cpuMs = Process.getElapsedCpuTime() - cpuStart
                val afterWindow = onVideoMain {
                    assertNull(player.playerError)
                    requireNotNull(player.videoDecoderCounters).apply { ensureUpdated() }.let {
                        it.renderedOutputBufferCount to it.droppedBufferCount
                    }
                }
                assertTrue("Steady playback must continue rendering video", afterWindow.first > beforeWindow.first)
                session.unmount()
                samples.put(JSONObject().put("iteration", iteration + 1).put("firstFrameMs", startupMs.last())
                    .put("seeks", seeks).put("steadyWindowMs", windowMs).put("steadyRenderedBuffers", afterWindow.first - beforeWindow.first)
                    .put("steadyDroppedBuffers", afterWindow.second - beforeWindow.second).put("appProcessCpuMs", cpuMs)
                    .put("baselineProcessPssKiB", baselinePss).put("maxObservedProcessPssKiB", peakPss)
                    .put("afterReleaseProcessPssKiB", Debug.getPss()))
            }
            session.assertExport()
            report.put("result", "measured").put("iterations", samples)
                .put("firstFrameP50Ms", percentile(startupMs, 0.50)).put("firstFrameP95Ms", percentile(startupMs, 0.95))
                .put("seekP50Ms", percentile(seekMs, 0.50)).put("seekP95Ms", percentile(seekMs, 0.95))
                .put("percentileMethod", "nearest rank; 3 startup samples and 9 seek samples, not a population estimate")
            videoReport("performance-android", report)
        } finally { session.close() }
    }

    private fun percentile(values: List<Long>, probability: Double): Long = values.sorted()[ceil(values.size * probability).toInt() - 1]
}
