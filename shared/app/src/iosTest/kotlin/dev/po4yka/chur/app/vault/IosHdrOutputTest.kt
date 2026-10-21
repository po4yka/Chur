@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.app.vault

import kotlinx.cinterop.*
import kotlinx.coroutines.runBlocking
import platform.AVFoundation.*
import platform.CoreFoundation.*
import platform.CoreGraphics.CGRectMake
import platform.CoreVideo.*
import platform.Foundation.NSNumber
import platform.UIKit.UIView
import platform.posix.getenv
import kotlin.test.*

/** Decoder color attachments and the native layer are separate from measured HDR display output. */
class IosHdrOutputTest {
    @Test fun pq_encrypted_video_retains_10bit_pq_on_the_native_output_path() = check(
        IosVideoCase("hdr10-hvc1.mp4", 192, 96, durationMs = 1_200L..1_300L, seekSeconds = emptyList(), fixtureEnvironment = "CHUR_HEVC_MAIN10_FIXTURES"),
        assertNotNull(kCVImageBufferTransferFunction_SMPTE_ST_2084_PQ),
    )

    @Test fun hlg_encrypted_video_retains_10bit_hlg_on_the_native_output_path() = check(
        IosVideoCase("hlg-hvc1.mp4", 192, 96, durationMs = 1_200L..1_300L, seekSeconds = emptyList(), fixtureEnvironment = "CHUR_HEVC_MAIN10_FIXTURES"),
        assertNotNull(kCVImageBufferTransferFunction_ITU_R_2100_HLG),
    )

    @Test fun licensed_camera_dolby_vision_keeps_its_10bit_hlg_base_layer() = check(iosCameraDolbyVision,
        assertNotNull(kCVImageBufferTransferFunction_ITU_R_2100_HLG))

    private fun check(case: IosVideoCase, transfer: CFStringRef): Unit = runBlocking {
        var passed = false
        var layerReady = false
        var actualTransfer = "unobserved"
        var actualPixelFormat = 0
        try {
            withIosImportedVideo(case) { session, originalSha256 ->
                val attributes = mapOf<Any?, Any>(coreString(assertNotNull(kCVPixelBufferPixelFormatTypeKey)) to
                    NSNumber(unsignedInt = kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange))
                val native = session.player(attributes)
                val host = UIView(frame = CGRectMake(0.0, 0.0, 320.0, 180.0))
                val layer = AVPlayerLayer.playerLayerWithPlayer(native.player)
                host.layer.addSublayer(layer)
                layer.setFrame(host.bounds)
                try {
                    native.ready(); native.player.play()
                    val frame = native.frame(case)
                    try {
                        actualPixelFormat = CVPixelBufferGetPixelFormatType(frame).toInt()
                        assertEquals(kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange.toInt(), actualPixelFormat, case.name)
                        actualTransfer = attachment(frame, assertNotNull(kCVImageBufferTransferFunctionKey))
                        assertEquals(coreString(transfer), actualTransfer, case.name)
                        assertEquals(coreString(assertNotNull(kCVImageBufferColorPrimaries_ITU_R_2020)),
                            attachment(frame, assertNotNull(kCVImageBufferColorPrimariesKey)), case.name)
                    } finally { CVPixelBufferRelease(frame) }
                    iosVideoWait(2.0) { layer.readyForDisplay || native.item.error != null }
                    assertNull(native.item.error)
                    layerReady = layer.readyForDisplay
                    assertFalse(native.player.allowsExternalPlayback)
                } finally { layer.setPlayer(null); layer.removeFromSuperlayer(); native.close() }
                session.verifyExportAndLock(originalSha256)
            }
            passed = true
        } finally {
            iosVideoReport("hdr-${case.name}", mapOf("fixture" to case.name, "passed" to passed,
                "eligible_for_hdr_playback" to AVPlayer.eligibleForHDRPlayback,
                "native_hdr_modes" to AVPlayer.availableHDRModes.toLong(),
                "decoded_pixel_format" to actualPixelFormat, "decoded_transfer_function" to actualTransfer,
                "decoded_hdr_buffer_verified" to passed, "native_player_layer_first_frame_ready" to layerReady,
                "layer_attachment" to "offscreen UIView layer in native test process",
                "physical_hdr_luminance_verified" to false,
                "display_verification_reason" to if (getenv("SIMULATOR_MODEL_IDENTIFIER") != null)
                    "Simulator does not establish physical HDR display output" else "No luminance or external display measurement was performed",
                "dolby_vision_limitation" to "HLG base-layer decode does not prove Dolby Vision RPU rendering"))
        }
    }

    private fun attachment(frame: CVPixelBufferRef, key: CFStringRef): String {
        val value = assertNotNull(CVBufferCopyAttachment(frame, key, null), "HDR color attachment is required")
        try { return coreString(value.reinterpret()) } finally { CFRelease(value) }
    }

    private fun coreString(value: CFStringRef): String = memScoped {
        val capacity = CFStringGetLength(value) * 4 + 1
        val bytes = allocArray<ByteVar>(capacity.toInt())
        assertTrue(CFStringGetCString(value, bytes, capacity, kCFStringEncodingUTF8))
        bytes.toKString()
    }
}
