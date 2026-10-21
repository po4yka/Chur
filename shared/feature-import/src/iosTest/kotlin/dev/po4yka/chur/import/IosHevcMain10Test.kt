@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.po4yka.chur.imports

import dev.po4yka.chur.ffi.StreamKind
import kotlinx.cinterop.*
import platform.AVFoundation.*
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringGetCString
import platform.CoreFoundation.CFStringGetLength
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreGraphics.*
import platform.Foundation.*
import platform.CoreMedia.CMTimeMake
import platform.UIKit.UIImage
import platform.posix.getenv
import kotlin.test.*

/** These checked fixtures are real HEVC Main 10 streams, not H.264 in an MP4 container. */
class IosHevcMain10Test {
    @Test fun sdr10_hvc1_produces_srgb_previews() = checkPreviews("sdr10-hvc1.mp4")
    @Test fun sdr10_hev1_is_explicitly_unplayable_without_previews() = checkUnsupportedPreviews("sdr10-hev1.mp4")
    @Test fun hdr10_hvc1_produces_srgb_previews() = checkPreviews("hdr10-hvc1.mp4")
    @Test fun hdr10_hev1_is_explicitly_unplayable_without_previews() = checkUnsupportedPreviews("hdr10-hev1.mp4")
    @Test fun hlg_hvc1_produces_srgb_previews() = checkPreviews("hlg-hvc1.mp4")
    @Test fun hlg_hev1_is_explicitly_unplayable_without_previews() = checkUnsupportedPreviews("hlg-hev1.mp4")

    private fun checkUnsupportedPreviews(name: String) = memScoped {
        val fixtures = assertNotNull(getenv("CHUR_HEVC_MAIN10_FIXTURES")?.toKString(), "HEVC fixture directory is required")
        val url = NSURL.fileURLWithPath("$fixtures/$name")
        val asset = AVURLAsset(url, null)
        assertFalse(asset.playable, "$name must be explicitly rejected by native asset capability")
        val generator = AVAssetImageGenerator(asset)
        val error = alloc<ObjCObjectVar<NSError?>>()
        error.value = null
        val frame = generator.copyCGImageAtTime(CMTimeMake(0, 1), null, error.ptr)
        try { assertNull(frame, "$name must not invent a preview for an unsupported sample entry") }
        finally { if (frame != null) CGImageRelease(frame) }
        val failure = assertNotNull(error.value)
        assertEquals(AVFoundationErrorDomain, failure.domain)
        assertEquals(AVErrorNoSourceTrack.toInt(), failure.code.toInt())
        val underlying = assertIs<NSError>(failure.userInfo[NSUnderlyingErrorKey])
        assertEquals(NSOSStatusErrorDomain, underlying.domain)
        assertEquals(-12430, underlying.code.toInt())
        val codec = IosMediaCodec()
        val source = assertNotNull(codec.open(url, name))
        try {
            val probe = assertNotNull(codec.probe(source))
            assertEquals(MediaBounds.CLASS_VIDEO, probe.mediaClass)
            assertEquals(192 to 96, probe.width to probe.height)
            for (kind in listOf(StreamKind.THUMBNAIL, StreamKind.VIDEO_POSTER)) {
                assertNull(codec.derive(source, probe, kind), "$name $kind must report unavailable")
            }
        } finally { source.close() }
    }

    private fun checkPreviews(name: String) = autoreleasepool {
        val fixtures = assertNotNull(getenv("CHUR_HEVC_MAIN10_FIXTURES")?.toKString(), "HEVC fixture directory is required")
        val codec = IosMediaCodec()
        val source = assertNotNull(codec.open(NSURL.fileURLWithPath("$fixtures/$name"), name), name)
        try {
            assertEquals("video/mp4", source.contentTypeHint, name)
            val probe = assertNotNull(codec.probe(source), name)
            assertEquals(MediaBounds.CLASS_VIDEO, probe.mediaClass, name)
            assertEquals(192 to 96, probe.width to probe.height, name)
            assertTrue(probe.durationMs in 1_200..1_300, "$name: ${probe.durationMs} ms")
            assertNull(MediaBounds.check(probe), name)
            for (kind in listOf(StreamKind.THUMBNAIL, StreamKind.VIDEO_POSTER)) {
                assertNull(codec.derive(source, probe, kind) { true }, "$name cancelled $kind")
                val preview = codec.derive(source, probe, kind) { false }
                    ?: fail("$name $kind must decode: ${posterDiagnostics(NSURL.fileURLWithPath("$fixtures/$name"))}")
                try {
                    assertEquals(0xff.toByte(), preview.bytes[0])
                    assertEquals(0xd8.toByte(), preview.bytes[1])
                    val data = preview.bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = preview.bytes.size.toULong()) }
                    val image = assertNotNull(UIImage.imageWithData(data)?.CGImage, "$name $kind JPEG")
                    assertEquals(192, CGImageGetWidth(image).toInt())
                    assertEquals(96, CGImageGetHeight(image).toInt())
                    assertEquals(8, CGImageGetBitsPerComponent(image).toInt(), "$name $kind is SDR 8-bit")
                    val space = assertNotNull(CGImageGetColorSpace(image))
                    val colorName = assertNotNull(CGColorSpaceCopyName(space), "$name $kind must retain sRGB")
                    try { assertEquals(utf8(assertNotNull(kCGColorSpaceSRGB)), utf8(colorName), "$name $kind must be sRGB") }
                    finally { CFRelease(colorName) }
                    checkGrayStrip(image, "$name $kind")
                } finally { preview.bytes.fill(0) }
            }
        } finally { source.close() }
    }

    private fun posterDiagnostics(url: NSURL): String = memScoped {
        val asset = AVURLAsset(url, null)
        val generator = AVAssetImageGenerator(asset)
        generator.dynamicRangePolicy = AVAssetImageGeneratorDynamicRangePolicyForceSDR
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSizeMake(192.0, 192.0)
        val error = alloc<ObjCObjectVar<NSError?>>()
        error.value = null
        val image = generator.copyCGImageAtTime(CMTimeMake(0, 1), null, error.ptr)
        if (image != null) CGImageRelease(image)
        val native = error.value
        "playable=${asset.playable}, videoTracks=${asset.tracksWithMediaType(AVMediaTypeVideo).size}, " +
            "image=${image != null}, error=${native?.domain}:${native?.code} ${native?.localizedDescription}, " +
            "underlying=${native?.userInfo?.get(NSUnderlyingErrorKey)}"
    }

    private fun utf8(value: CFStringRef): String = memScoped {
        val capacity = CFStringGetLength(value) * 4 + 1
        val bytes = allocArray<ByteVar>(capacity.toInt())
        assertTrue(CFStringGetCString(value, bytes, capacity, kCFStringEncodingUTF8))
        bytes.toKString()
    }

    private fun checkGrayStrip(image: CGImageRef, label: String) {
        val pixels = ByteArray(192 * 96 * 4)
        pixels.usePinned { pinned ->
            val space = assertNotNull(CGColorSpaceCreateWithName(kCGColorSpaceSRGB))
            val context = try {
                assertNotNull(CGBitmapContextCreate(pinned.addressOf(0), 192uL, 96uL, 8uL, 192uL * 4uL, space,
                    CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big))
            } finally { CGColorSpaceRelease(space) }
            try { CGContextDrawImage(context, CGRectMake(0.0, 0.0, 192.0, 96.0), image) }
            finally { CGContextRelease(context) }
        }
        // The fixture has neutral gray bars across its top 32 rows. PQ/HLG
        // steps can compress during native tone mapping; no calibrated luminance is claimed.
        val gray = (0..5).map { bar ->
            val offset = (16 * 192 + 16 + bar * 32) * 4
            val rgb = (0..2).map { pixels[offset + it].toInt() and 255 }
            assertTrue(rgb.max() - rgb.min() <= 12, "$label gray bar $bar must stay neutral: $rgb")
            rgb.sum() / 3
        }
        assertTrue(gray.zipWithNext().all { (first, second) -> second >= first - 2 }, "$label gray bars must remain ordered: $gray")
        assertTrue(gray.last() - gray.first() > 96, "$label must retain black-to-white separation: $gray")
    }
}
