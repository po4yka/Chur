@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.app.vault

import kotlinx.coroutines.runBlocking
import platform.AVFoundation.*
import platform.CoreVideo.CVPixelBufferRelease
import kotlin.test.*

class VideoAcceptanceIosTest {
    @Test fun main10_4k60_imports_plays_seeks_exports_and_invalidates_readers() = check(ios4k60Video)
    @Test fun portrait_rotation_keeps_upright_metadata_and_original_video() = check(iosPortraitVideo)
    @Test fun long_gop_vfr_supports_distant_exact_seeks() = check(iosLongGopVfrVideo)
    @Test fun licensed_camera_dolby_vision_initial_frame_and_original_survive() = check(iosCameraDolbyVision)

    private fun check(case: IosVideoCase): Unit = runBlocking {
        var passed = false
        try {
            withIosImportedVideo(case) { session, originalSha256 ->
                val native = session.player()
                try {
                    native.ready(); native.player.play()
                    CVPixelBufferRelease(native.frame(case))
                    case.seekSeconds.forEach { native.seek(case, it) }
                    assertFalse(native.player.allowsExternalPlayback)
                    assertNull(native.loader.problem.value)
                } finally { native.close() }
                session.verifyExportAndLock(originalSha256)
            }
            passed = true
        } finally {
            iosVideoReport("acceptance-${case.name}", mapOf("fixture" to case.name, "passed" to passed,
                "coded_width" to case.codedWidth, "coded_height" to case.codedHeight,
                "display_width" to case.displayWidth, "display_height" to case.displayHeight,
                "seek_targets_seconds" to case.seekSeconds, "original_export_sha256_verified" to passed,
                "reader_invalidated_after_lock" to passed))
        }
    }
}
