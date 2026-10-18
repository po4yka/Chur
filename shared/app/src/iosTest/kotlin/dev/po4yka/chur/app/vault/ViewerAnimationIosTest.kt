@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.app.vault

import dev.po4yka.chur.ffi.withChurBuffer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGDataProviderCreateWithData
import platform.CoreGraphics.CGDataProviderRelease
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGRectMake
import platform.ImageIO.CGImageSourceCreateWithDataProvider
import platform.UIKit.UIImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ViewerAnimationIosTest {
    @Test fun native_gif_opens_uses_frame_timing_and_releases_before_the_buffer_is_wiped() {
        withChurBuffer(animatedGif.size) { original ->
            original.copyIn(animatedGif)
            val decoder = assertNotNull(openViewerAnimation(original))
            decoder.close()
            decoder.close()
        }
    }

    @Test fun indexed_frames_preserve_delay_and_composite_optimized_gif_disposal() {
        withChurBuffer(animatedGif.size) { original ->
            original.copyIn(animatedGif)
            val provider = assertNotNull(CGDataProviderCreateWithData(null, original.pointer, original.capacityBytes.toULong(), null))
            val source = CGImageSourceCreateWithDataProvider(provider, null)
            CGDataProviderRelease(provider)
            assertNotNull(source)
            try {
                assertEquals(80L, animationDelayMillis(source, 0))
                assertEquals(120L, animationDelayMillis(source, 1))
                assertEquals(160L, animationDelayMillis(source, 2))
                assertEquals(200L, animationDelayMillis(source, 3))
                val images = (0..3).map { assertNotNull(animationFrame(source, it)) }
                // ImageIO must return full composited canvases for delta frames.
                images.forEach { assertEquals(8uL, CGImageGetWidth(it.CGImage)) }
                assertEquals(listOf(255, 0, 0), rgb(images[0], 1, 1))
                assertEquals(listOf(255, 255, 255), rgb(images[1], 1, 1))
                assertEquals(listOf(0, 0, 255), rgb(images[1], 5, 1))
                assertEquals(listOf(0, 128, 0), rgb(images[2], 1, 5))
                assertEquals(listOf(255, 255, 255), rgb(images[2], 5, 1))
                assertEquals(listOf(255, 255, 255), rgb(images[3], 1, 5))
                assertEquals(listOf(255, 255, 0), rgb(images[3], 5, 5))
            } finally { CFRelease(source) }
        }
    }

    @Test fun native_apng_and_webp_open_as_animations() {
        for (bytes in listOf(animatedPng, animatedWebp)) {
            withChurBuffer(bytes.size) { original ->
                original.copyIn(bytes)
                val decoder = assertNotNull(openViewerAnimation(original))
                decoder.close()
            }
        }
    }

    @Test fun native_animation_bounds_decode_size_and_rejects_oversized_canvases() {
        withChurBuffer(largeAnimatedPng.size) { original ->
            original.copyIn(largeAnimatedPng)
            val provider = assertNotNull(CGDataProviderCreateWithData(null, original.pointer, original.capacityBytes.toULong(), null))
            val source = CGImageSourceCreateWithDataProvider(provider, null)
            CGDataProviderRelease(provider)
            assertNotNull(source)
            try {
                val frame = assertNotNull(animationFrame(source, 0))
                assertEquals(1_024uL, CGImageGetWidth(frame.CGImage))
            } finally { CFRelease(source) }
        }
        withChurBuffer(oversizedAnimatedPng.size) { original ->
            original.copyIn(oversizedAnimatedPng)
            assertNull(openViewerAnimation(original))
        }
    }

    @Test fun corrupt_bytes_keep_the_static_fallback() {
        withChurBuffer(8) { original -> original.clear(); assertNull(openViewerAnimation(original)) }
    }

    private fun rgb(image: UIImage, x: Int, y: Int): List<Int> {
        val pixel = ByteArray(4)
        pixel.usePinned { pinned ->
            val space = platform.CoreGraphics.CGColorSpaceCreateDeviceRGB()
            val context = platform.CoreGraphics.CGBitmapContextCreate(pinned.addressOf(0), 1u, 1u, 8u, 4u, space, 1u)
            platform.CoreGraphics.CGColorSpaceRelease(space)
            assertNotNull(context)
            try {
                platform.CoreGraphics.CGContextTranslateCTM(context, -x.toDouble(), -(7 - y).toDouble())
                platform.CoreGraphics.CGContextDrawImage(context, CGRectMake(0.0, 0.0, 8.0, 8.0), image.CGImage)
            } finally { platform.CoreGraphics.CGContextRelease(context) }
        }
        return pixel.take(3).map { it.toInt() and 255 }
    }

    private val animatedGif = byteArrayOf(
        71, 73, 70, 56, 57, 97, 8, 0, 8, 0, -127, 0, 0, -1, -1, -1, -1, 0,
        0, 0, 0, 0, 0, 0, 0, 33, -1, 11, 78, 69, 84, 83, 67, 65, 80, 69,
        50, 46, 48, 3, 1, 0, 0, 0, 33, -7, 4, 4, 8, 0, 0, 0, 44, 0,
        0, 0, 0, 8, 0, 8, 0, 0, 8, 24, 0, 3, 8, 12, 0, -96, 32, -128,
        -127, 4, 13, 34, 52, 120, 112, 32, -61, -121, 16, 35, 74, -124, 24, 16, 0, 33,
        -7, 4, 9, 12, 0, 2, 0, 44, 0, 0, 0, 0, 8, 0, 4, 0, -127, -1,
        -1, -1, 0, 0, -1, 0, 0, 0, 0, 0, 0, 8, 18, 0, 1, 8, 4, 16,
        -96, 96, -128, -127, 4, 13, 34, 52, 120, 112, 32, -61, -128, 0, 33, -7, 4, 12,
        16, 0, 0, 0, 44, 0, 0, 0, 0, 8, 0, 8, 0, -127, -1, -1, -1, 0,
        -128, 0, 0, 0, 0, 0, 0, 0, 8, 23, 0, 1, 8, 28, 72, -80, -96, -63,
        -125, 4, 3, 40, 12, -112, 112, 97, 67, -123, 15, 25, 14, 12, 8, 0, 33, -7,
        4, 5, 20, 0, 2, 0, 44, 0, 0, 4, 0, 8, 0, 4, 0, -127, -1, -1,
        -1, -1, -1, 0, 0, 0, 0, 0, 0, 0, 8, 18, 0, 1, 8, 4, 16, -96,
        96, -128, -127, 4, 13, 34, 52, 120, 112, 32, -61, -128, 0, 59,
    )
    private val animatedPng = byteArrayOf(
        -119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0,
        0, 8, 0, 0, 0, 8, 8, 2, 0, 0, 0, 75, 109, 41, -36, 0, 0, 0,
        8, 97, 99, 84, 76, 0, 0, 0, 2, 0, 0, 0, 0, -13, -115, -109, 112, 0,
        0, 0, 26, 102, 99, 84, 76, 0, 0, 0, 0, 0, 0, 0, 8, 0, 0, 0,
        8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 10, 0, 0, -14, 78, -70,
        98, 0, 0, 0, 18, 73, 68, 65, 84, 120, -100, 99, -4, -49, -128, 29, 48, -31,
        16, 31, -92, 18, 0, -51, 65, 1, 15, -24, 65, -30, 111, 0, 0, 0, 26, 102,
        99, 84, 76, 0, 0, 0, 1, 0, 0, 0, 8, 0, 0, 0, 8, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 1, 0, 10, 0, 0, 105, 61, 80, -74, 0, 0, 0,
        24, 102, 100, 65, 84, 0, 0, 0, 2, 120, -100, 99, 100, 96, -8, -49, -128, 13,
        48, 97, 21, 29, -76, 18, 0, -53, 67, 1, 15, -117, -98, 124, -84, 0, 0, 0,
        0, 73, 69, 78, 68, -82, 66, 96, -126,
    )
    private val animatedWebp = byteArrayOf(
        82, 73, 70, 70, -124, 0, 0, 0, 87, 69, 66, 80, 86, 80, 56, 88, 10, 0,
        0, 0, 2, 0, 0, 0, 7, 0, 0, 7, 0, 0, 65, 78, 73, 77, 6, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 65, 78, 77, 70, 40, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 7, 0, 0, 7, 0, 0, 100, 0, 0, 2, 86, 80, 56, 76,
        15, 0, 0, 0, 47, 7, -64, 1, 0, 7, 16, -3, -113, -2, 7, 34, -94, -1,
        1, 0, 65, 78, 77, 70, 40, 0, 0, 0, 0, 0, 0, 0, 0, 0, 7, 0,
        0, 7, 0, 0, 100, 0, 0, 0, 86, 80, 56, 76, 15, 0, 0, 0, 47, 7,
        -64, 1, 0, 7, 16, -47, -1, -2, 7, 34, -94, -1, 1, 0,
    )
    private val largeAnimatedPng = byteArrayOf(
        -119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0,
        4, -80, 0, 0, 0, 2, 8, 2, 0, 0, 0, -57, 14, -78, -11, 0, 0, 0,
        8, 97, 99, 84, 76, 0, 0, 0, 2, 0, 0, 0, 0, -13, -115, -109, 112, 0,
        0, 0, 26, 102, 99, 84, 76, 0, 0, 0, 0, 0, 0, 4, -80, 0, 0, 0,
        2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 10, 0, 0, 125, -38, -92,
        -26, 0, 0, 0, 36, 73, 68, 65, 84, 120, -100, -19, -50, 65, 1, 0, 0, 4,
        4, 48, -12, -17, 76, -116, 123, -40, 18, -84, -73, 0, 0, 0, -8, 104, -46, 1,
        0, 0, 0, 50, 14, 90, -23, 1, 3, 98, 102, -14, 0, 0, 0, 0, 26, 102,
        99, 84, 76, 0, 0, 0, 1, 0, 0, 4, -80, 0, 0, 0, 2, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 1, 0, 10, 0, 0, -26, -87, 78, 50, 0, 0, 0,
        41, 102, 100, 65, 84, 0, 0, 0, 2, 120, -100, -19, -50, 65, 1, 0, 0, 4,
        4, -80, -93, 127, 103, 98, 120, -40, 18, -84, -110, 9, 0, 0, 0, -1, -12, 117,
        0, 0, 0, -128, 27, 11, 88, -21, 1, 3, 127, -90, -15, 64, 0, 0, 0, 0,
        73, 69, 78, 68, -82, 66, 96, -126,
    )
    private val oversizedAnimatedPng = byteArrayOf(
        -119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0,
        8, 52, 0, 0, 0, 2, 8, 2, 0, 0, 0, 34, -6, -119, 73, 0, 0, 0,
        8, 97, 99, 84, 76, 0, 0, 0, 2, 0, 0, 0, 0, -13, -115, -109, 112, 0,
        0, 0, 26, 102, 99, 84, 76, 0, 0, 0, 0, 0, 0, 8, 52, 0, 0, 0,
        2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 10, 0, 0, -35, -107, 76,
        -65, 0, 0, 0, 41, 73, 68, 65, 84, 120, -100, -19, -50, 49, 1, 0, 0, 4,
        0, 48, -12, -17, 76, 11, 14, 91, -126, 101, 7, 0, 0, 0, 0, 0, -64, -98,
        -70, 14, 0, 0, 0, 0, 0, 0, -65, 12, -98, 84, 1, 3, 51, 3, -36, 3,
        0, 0, 0, 26, 102, 99, 84, 76, 0, 0, 0, 1, 0, 0, 8, 52, 0, 0,
        0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 10, 0, 0, 70, -26,
        -90, 107, 0, 0, 0, 47, 102, 100, 65, 84, 0, 0, 0, 2, 120, -100, -19, -50,
        65, 1, 0, 48, 8, 4, -96, 115, -3, 59, 107, 11, -9, 16, 18, 80, 73, 7,
        0, 0, 0, 0, 0, 96, -53, -5, 29, 0, 0, 0, 0, 0, 0, 110, 25, -100,
        86, 1, 3, -29, 90, -26, 90, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96,
        -126,
    )
}
