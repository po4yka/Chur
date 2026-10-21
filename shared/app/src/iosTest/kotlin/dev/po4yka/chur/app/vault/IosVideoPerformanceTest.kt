@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.app.vault

import kotlinx.cinterop.*
import kotlinx.coroutines.runBlocking
import platform.AVFoundation.*
import platform.CoreVideo.CVPixelBufferRelease
import platform.Foundation.*
import platform.darwin.*
import kotlin.test.*

/** Measurements are observations for this runner, not invented release limits. */
class IosVideoPerformanceTest {
    @Test fun records_4k60_native_startup_seek_drops_and_memory() = measure(ios4k60Video)
    @Test fun records_portrait_native_startup_seek_drops_and_memory() = measure(iosPortraitVideo)
    @Test fun records_sparse_gop_vfr_native_startup_seek_drops_and_memory() = measure(iosLongGopVfrVideo)

    private fun measure(case: IosVideoCase): Unit = runBlocking {
        val result = mutableMapOf<String, Any>("fixture" to case.name, "passed" to false,
            "measurement_scope" to "encrypted local playback using AVPlayerItemVideoOutput; not on-screen frame pacing",
            "threshold_policy" to "No device-independent performance threshold is asserted")
        try {
            withIosImportedVideo(case) { session, originalSha256 ->
                result["memory_before_player"] = memory()
                val start = uptime()
                val native = session.player()
                try {
                    native.ready()
                    result["ready_to_play_ms"] = (uptime() - start) * 1_000
                    native.player.play()
                    CVPixelBufferRelease(native.frame(case))
                    result["first_decoded_frame_ms"] = (uptime() - start) * 1_000
                    result["memory_after_first_frame"] = memory()
                    var observedFrames = 0
                    val sampleStart = uptime()
                    while (uptime() - sampleStart < 0.75) {
                        val time = native.player.currentTime()
                        if (native.output.hasNewPixelBufferForItemTime(time)) {
                            native.output.copyPixelBufferForItemTime(time, null)?.let { CVPixelBufferRelease(it); observedFrames += 1 }
                        }
                        NSRunLoop.currentRunLoop.runUntilDate(NSDate().dateByAddingTimeInterval(0.005))
                    }
                    result["decoded_frames_observed_during_sampling"] = observedFrames
                    result["decoded_frame_sampling_seconds"] = uptime() - sampleStart
                    assertNull(native.item.error)
                    result["seek_to_decoded_frame"] = case.seekSeconds.map { seconds ->
                        val seekStart = uptime()
                        native.seek(case, seconds)
                        mapOf("target_seconds" to seconds, "elapsed_ms" to (uptime() - seekStart) * 1_000)
                    }
                    val events = native.item.accessLog()?.events?.filterIsInstance<AVPlayerItemAccessLogEvent>().orEmpty()
                    val drops = events.map { it.numberOfDroppedVideoFrames }.filter { it >= 0 }
                    result["native_dropped_video_frames"] = if (drops.isEmpty()) NSNull() else drops.sum()
                    result["native_dropped_video_frames_reason"] = if (drops.isEmpty())
                        "AVPlayer supplied no nonnegative access-log drop counter for this custom local asset" else "AVPlayerItem access-log counters"
                    result["memory_after_playback_and_seeks"] = memory()
                } finally { native.close() }
                session.verifyExportAndLock(originalSha256)
                result["memory_after_player_release_and_lock"] = memory()
            }
            result["passed"] = true
        } finally { iosVideoReport("performance-${case.name}", result) }
    }

    private fun uptime(): Double = NSProcessInfo.processInfo.systemUptime

    private fun memory(): Map<String, Any> = memScoped {
        val info = alloc<mach_task_basic_info>()
        val count = alloc<UIntVar>()
        count.value = (sizeOf<mach_task_basic_info>() / sizeOf<IntVar>()).toUInt()
        val status = task_info(mach_task_self_, MACH_TASK_BASIC_INFO.toUInt(), info.ptr.reinterpret(), count.ptr)
        if (status != KERN_SUCCESS) return@memScoped mapOf("resident_bytes" to NSNull(), "process_peak_resident_bytes" to NSNull(),
            "reason" to "mach task_info failed with status $status")
        mapOf("resident_bytes" to info.resident_size.toLong(), "process_peak_resident_bytes" to info.resident_size_max.toLong(),
            "scope" to "Whole native test process; peak includes earlier tests and is not a decoder-only allocation")
    }
}
