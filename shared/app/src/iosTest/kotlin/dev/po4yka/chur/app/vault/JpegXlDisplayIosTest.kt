@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.app.vault

import androidx.compose.ui.graphics.ImageBitmap
import dev.po4yka.chur.ffi.ChurBuffer
import dev.po4yka.chur.ffi.JpegXlDecoder
import dev.po4yka.chur.ffi.withChurBuffer
import kotlinx.cinterop.toKString
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JpegXlDisplayIosTest {
    @Test
    fun static_codestream_container_and_oriented_originals_decode_into_owned_upright_pixels() {
        for (name in listOf("still-codestream.jxl", "still-container.jxl", "oriented.jxl")) {
            val image = withFixture(name) { assertNotNull(decodeForDisplay(it, 2048)) }
            // The native source is cleared and freed before these pixels are read.
            assertEquals(96 to 48, image.width to image.height, name)
            val pixels = IntArray(image.width * image.height)
            image.readPixels(pixels)
            assertTrue(pixels.all { it ushr 24 == 255 })
        }
    }

    @Test
    fun alpha_survives_display_conversion_and_large_originals_obey_the_viewer_bound() {
        val alpha = withFixture("alpha.jxl") { assertNotNull(decodeForDisplay(it, 2048)) }
        val pixels = IntArray(alpha.width * alpha.height)
        alpha.readPixels(pixels)
        assertTrue(pixels.any { it ushr 24 < 255 })
        assertTrue(pixels.any { it ushr 24 > 0 })
        val large = withFixture("large.jxl") { assertNotNull(decodeForDisplay(it, 512)) }
        assertEquals(512 to 256, large.width to large.height)
    }

    @Test
    fun indexed_animation_frames_retain_srgb_pixels_timing_and_loop_metadata_after_native_close() {
        val frames = withFixture("animated.jxl") { original ->
            val decoder = JpegXlDecoder.open(original)
            try {
                assertEquals(3, decoder.frameCount)
                assertEquals(0, decoder.loopCount)
                (0 until decoder.frameCount).map { index ->
                    val frame = decoder.renderFrame(index, 1024)
                    try { frame.durationMs to assertNotNull(jpegXlFrameBitmap(frame)) } finally { frame.close() }
                }
            } finally { decoder.close() }
        }
        assertEquals(listOf(80, 120, 160), frames.map { it.first })
        frames.forEach { assertEquals(48 to 24, it.second.width to it.second.height) }
        assertEquals(listOf(0, 1, 2), frames.map { (_, bitmap) -> dominantChannel(bitmap) })
        withFixture("animated.jxl") { original ->
            val decoder = assertNotNull(openViewerAnimation(original))
            decoder.close()
            decoder.close()
        }
    }

    @Test
    fun viewer_frame_transitions_obey_timing_finite_loops_and_dispose_the_displayed_frame() =
        withFixture("finite-animation.jxl") { original ->
            val native = JpegXlDecoder.open(original)
            val loops = native.loopCount
            assertEquals(2, loops)
            val animation = JpegXlViewerAnimation(native)
            try {
                repeat(loops) { loop ->
                    assertEquals(0, animation.frameIndex)
                    assertEquals(80L, animation.frameDurationMs)
                    assertEquals(0, dominantChannel(assertNotNull(animation.current)))
                    assertTrue(animation.advanceFrame())
                    assertEquals(1, animation.frameIndex)
                    assertEquals(120L, animation.frameDurationMs)
                    assertEquals(1, dominantChannel(assertNotNull(animation.current)))
                    assertTrue(animation.advanceFrame())
                    assertEquals(2, animation.frameIndex)
                    assertEquals(160L, animation.frameDurationMs)
                    assertEquals(2, dominantChannel(assertNotNull(animation.current)))
                    assertEquals(loop + 1 < loops, animation.advanceFrame())
                    assertEquals(loop + 1, animation.completedLoops)
                }
                assertFalse(animation.advanceFrame())
                assertNotNull(animation.current)
                assertTrue(animation.restartPlayback())
                assertEquals(0, animation.frameIndex)
                assertEquals(0, animation.completedLoops)
                assertEquals(80L, animation.frameDurationMs)
                assertEquals(0, dominantChannel(assertNotNull(animation.current)))
                assertTrue(animation.advanceFrame())
                assertEquals(1, animation.frameIndex)
            } finally { animation.close() }
            assertNull(animation.current)
            assertFalse(animation.advanceFrame())
            animation.close()
        }

    @Test
    fun static_and_malformed_inputs_do_not_start_animation_and_invalid_jxl_has_no_display() {
        withFixture("still-container.jxl") { assertNull(openViewerAnimation(it)) }
        for (name in listOf("malformed.jxl", "truncated.jxl", "oversized.jxl")) withFixture(name) {
            assertNull(openViewerAnimation(it))
            assertNull(decodeForDisplay(it, 2048))
        }
    }

    private fun dominantChannel(image: ImageBitmap): Int {
        val pixel = IntArray(1)
        image.readPixels(pixel, 0, 0, 1, 1)
        val channels = listOf((pixel[0] ushr 16) and 255, (pixel[0] ushr 8) and 255, pixel[0] and 255)
        return channels.indexOf(channels.max())
    }

    private fun <T> withFixture(name: String, block: (ChurBuffer) -> T): T {
        val root = assertNotNull(platform.posix.getenv("CHUR_JPEG_XL_FIXTURES")?.toKString())
        val data = assertNotNull(NSData.dataWithContentsOfFile("$root/$name"))
        return withChurBuffer(data.length.toInt()) { original ->
            memcpy(original.pointer, data.bytes, data.length)
            block(original)
        }
    }
}
