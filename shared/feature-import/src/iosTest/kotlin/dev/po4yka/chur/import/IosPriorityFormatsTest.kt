@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.po4yka.chur.imports

import dev.po4yka.chur.ffi.StreamKind
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.addressOf
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberSInt64Type
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageRelease
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.kCGImageSourceCreateThumbnailFromImageAlways
import platform.ImageIO.kCGImageSourceCreateThumbnailWithTransform
import platform.ImageIO.kCGImageSourceThumbnailMaxPixelSize
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringGetCString
import platform.CoreFoundation.CFStringGetLength
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreServices.UTTypeCreatePreferredIdentifierForTag
import platform.CoreServices.UTTypeCopyPreferredTagWithClass
import platform.CoreServices.kUTTagClassFilenameExtension
import platform.CoreServices.kUTTagClassMIMEType
import platform.UniformTypeIdentifiers.UTType
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.isEqualToData
import platform.Foundation.writeToFile
import platform.UIKit.UIImage
import platform.posix.unlink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosPriorityFormatsTest {
    @Test
    fun native_registry_resolves_UTF8_tags_in_the_Kotlin_test_process() {
        fun text(value: CFStringRef?): String? = value?.let { memScoped {
            val length = CFStringGetLength(it)
            if (length !in 0..1024) return@memScoped null
            val capacity = length * 4 + 1
            val bytes = allocArray<ByteVar>(capacity.toInt())
            if (CFStringGetCString(it, bytes, capacity, kCFStringEncodingUTF8)) bytes.toKString() else null
        } }
        val extension = assertNotNull(CFStringCreateWithCString(null, "heic", kCFStringEncodingUTF8))
        val literalClass = assertNotNull(CFStringCreateWithCString(null, "public.filename-extension", kCFStringEncodingUTF8))
        try {
            val identifier = UTTypeCreatePreferredIdentifierForTag(kUTTagClassFilenameExtension, extension, null)
            val literalIdentifier = UTTypeCreatePreferredIdentifierForTag(literalClass, extension, null)
            val mime = identifier?.let { UTTypeCopyPreferredTagWithClass(it, kUTTagClassMIMEType) }
            try {
                assertEquals("heic", text(extension))
                assertEquals("public.filename-extension", text(kUTTagClassFilenameExtension))
                assertEquals("public.mime-type", text(kUTTagClassMIMEType))
                assertEquals("public.heic", text(identifier))
                assertEquals("public.heic", text(literalIdentifier))
                assertEquals("image/heic", text(mime))
            } finally {
                mime?.let { CFRelease(it) }
                identifier?.let { CFRelease(it) }
                literalIdentifier?.let { CFRelease(it) }
            }
        } finally { CFRelease(extension); CFRelease(literalClass) }
    }

    @Test
    fun native_ImageIO_identifies_real_TIFF_BMP_and_AVIF_contents_despite_an_unknown_extension() {
        for ((encoded, mime) in listOf(TIFF to "image/tiff", BMP to "image/bmp", AVIF to "image/avif")) {
            val path = NSTemporaryDirectory() + NSUUID().UUIDString + ".bin"
            val data = assertNotNull(NSData.create(base64EncodedString = encoded, options = 0u))
            assertTrue(data.writeToFile(path, atomically = true))
            try {
                val codec = IosMediaCodec()
                val source = assertNotNull(codec.open(NSURL.fileURLWithPath(path), "Provider item.bin"))
                try {
                    assertEquals(mime, source.contentTypeHint)
                    val probe = assertNotNull(codec.probe(source))
                    assertEquals(MediaBounds.CLASS_IMAGE, probe.mediaClass)
                    assertEquals(24 to 12, probe.width to probe.height)
                    val preview = assertNotNull(codec.derive(source, probe, StreamKind.SCREEN_PREVIEW))
                    try {
                        assertEquals(24 to 12, preview.width to preview.height)
                        val jpeg = preview.bytes.asData()
                        assertNotNull(UIImage.imageWithData(jpeg))
                        assertEquals(0xff.toByte(), preview.bytes[0])
                        assertEquals(0xd8.toByte(), preview.bytes[1])
                    } finally { preview.bytes.fill(0) }
                } finally { source.close() }
            } finally { unlink(path) }
        }
    }

    @Test
    fun a_real_camera_DNG_uses_the_native_RAW_thumbnail_decoder_and_preserves_original_bytes() {
        val path = NSTemporaryDirectory() + NSUUID().UUIDString + ".dng"
        val fixtureRoot = assertNotNull(
            platform.posix.getenv("CHUR_MEDIA_TEST_FIXTURES")?.toKString(),
            "The native test task must pass the shared media fixture directory",
        )
        val data = assertNotNull(NSData.dataWithContentsOfFile("$fixtureRoot/camera.dng"))
        assertTrue(data.writeToFile(path, atomically = true))
        try {
            val codec = IosMediaCodec()
            val source = assertNotNull(codec.open(NSURL.fileURLWithPath(path), "Camera.dng"))
            try {
                assertEquals("image/x-adobe-dng", source.contentTypeHint)
                val probe = assertNotNull(codec.probe(source))
                assertEquals(MediaBounds.CLASS_IMAGE, probe.mediaClass)
                assertEquals(600 to 337, probe.width to probe.height)
                val thumbnail = assertNotNull(codec.derive(source, probe, StreamKind.THUMBNAIL))
                try {
                    val nativeSize = nativeRawThumbnailSize(data)
                    // iOS can expose a 256 x 144 embedded preview when it cannot
                    // render this camera's full RAW. Never invent more pixels.
                    assertTrue(thumbnail.width in 1..minOf(320, nativeSize.first))
                    assertTrue(thumbnail.height in 1..minOf(179, nativeSize.second))
                    val scaleX = thumbnail.width.toDouble() / nativeSize.first
                    val scaleY = thumbnail.height.toDouble() / nativeSize.second
                    assertTrue(kotlin.math.abs(scaleX - scaleY) < 0.01)
                    assertNotNull(UIImage.imageWithData(thumbnail.bytes.asData()))
                } finally { thumbnail.bytes.fill(0) }
                assertTrue(data.isEqualToData(assertNotNull(NSData.dataWithContentsOfFile(path))))
            } finally { source.close() }
        } finally { unlink(path) }
    }

    @Test
    fun a_coordinated_audio_document_keeps_its_URL_and_closes_the_host_lease() {
        val path = NSTemporaryDirectory() + NSUUID().UUIDString + ".wav"
        assertTrue(wave(48_000, 2).asData().writeToFile(path, atomically = true))
        val fd = platform.posix.open(path, platform.posix.O_RDONLY)
        var releases = 0
        try {
            val codec = IosMediaCodec()
            val source = assertNotNull(codec.openMediaDocument(
                url = NSURL.fileURLWithPath(path), descriptor = fd, name = "Recording.wav",
                contentType = "audio/wav", knownLength = 192_044,
                release = { platform.posix.close(fd); releases++ },
            ))
            try {
                assertFalse(source.importAsFile)
                assertNotNull(source.platformHandle)
                val probe = assertNotNull(codec.probe(source))
                assertEquals(MediaBounds.CLASS_AUDIO, probe.mediaClass)
                assertEquals(1000L, probe.durationMs)
                assertNotNull(codec.derive(source, probe, StreamKind.AUDIO_WAVEFORM)).bytes.fill(0)
                assertEquals(0, releases)
            } finally { source.close() }
            assertEquals(1, releases)
        } finally { if (releases == 0) platform.posix.close(fd); unlink(path) }
    }

    @Test
    fun actual_mono_and_stereo_PCM_rates_place_late_audio_in_the_second_half_of_the_waveform() {
        for ((rate, channels) in listOf(16_000 to 1, 48_000 to 2)) {
            val path = NSTemporaryDirectory() + NSUUID().UUIDString + ".wav"
            assertTrue(wave(rate, channels).asData().writeToFile(path, atomically = true))
            try {
                val codec = IosMediaCodec()
                val source = assertNotNull(codec.open(NSURL.fileURLWithPath(path), "Recording.wav"))
                try {
                    val probe = assertNotNull(codec.probe(source))
                    val waveform = assertNotNull(codec.derive(source, probe, StreamKind.AUDIO_WAVEFORM))
                    try {
                        assertEquals(0, waveform.bytes[Waveform.HEAD_LEN + 128].toInt())
                        assertTrue(waveform.bytes[Waveform.HEAD_LEN + 384].toInt() != 0, "$rate Hz, $channels channels")
                    } finally { waveform.bytes.fill(0) }
                } finally { source.close() }
            } finally { unlink(path) }
        }
    }

    @Test
    fun waveform_sample_counts_validate_native_rate_and_channels() {
        assertEquals(96_000L, waveformExpectedSamples(1000, 48_000.0, 2))
        assertEquals(16_000L, waveformExpectedSamples(1000, 16_000.0, 1))
        assertNull(waveformExpectedSamples(1000, Double.NaN, 2))
        assertNull(waveformExpectedSamples(1000, 48_000.0, 0))
    }

    /** Read this runtime's actual RAW thumbnail capability independently of the codec. */
    private fun nativeRawThumbnailSize(data: NSData): Pair<Int, Int> = memScoped {
        val encoded = assertNotNull(CFDataCreate(null, data.bytes?.reinterpret(), data.length.toLong()))
        try {
            val source = assertNotNull(CGImageSourceCreateWithData(encoded, null))
            try {
                val size = alloc<LongVar> { value = 320 }
                val number = assertNotNull(CFNumberCreate(null, kCFNumberSInt64Type, size.ptr))
                val options = assertNotNull(CFDictionaryCreateMutable(null, 3, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr))
                val frame = try {
                    CFDictionarySetValue(options, kCGImageSourceCreateThumbnailFromImageAlways, kCFBooleanTrue)
                    CFDictionarySetValue(options, kCGImageSourceCreateThumbnailWithTransform, kCFBooleanTrue)
                    CFDictionarySetValue(options, kCGImageSourceThumbnailMaxPixelSize, number)
                    assertNotNull(CGImageSourceCreateThumbnailAtIndex(source, 0u, options))
                } finally { CFRelease(options); CFRelease(number) }
                try { CGImageGetWidth(frame).toInt() to CGImageGetHeight(frame).toInt() }
                finally { CGImageRelease(frame) }
            } finally { CFRelease(source) }
        } finally { CFRelease(encoded) }
    }

    /** One second of PCM: silence then equal constant samples in all channels. */
    private fun wave(rate: Int, channels: Int): ByteArray {
        val size = rate * channels * 2
        val bytes = ByteArray(44 + size)
        fun text(offset: Int, value: String) { value.forEachIndexed { index, char -> bytes[offset + index] = char.code.toByte() } }
        fun int(offset: Int, value: Int, length: Int = 4) { repeat(length) { bytes[offset + it] = (value ushr (8 * it)).toByte() } }
        text(0, "RIFF"); int(4, size + 36); text(8, "WAVEfmt "); int(16, 16)
        int(20, 1, 2); int(22, channels, 2); int(24, rate); int(28, rate * channels * 2)
        int(32, channels * 2, 2); int(34, 16, 2); text(36, "data"); int(40, size)
        for (frame in rate / 2 until rate) repeat(channels) { int(44 + (frame * channels + it) * 2, 10_000, 2) }
        return bytes
    }

    private fun ByteArray.asData(): NSData = usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

    private companion object {
        // Real 24 x 12 solid-color files encoded with Pillow; no renamed substitutes.
        const val TIFF =
            "SUkqAFwAAACANwPBSBQSBwWEQeFQaGQmGwuHRGIROHxWJRaKReNRmORiPRuPx2QSORSWQyeSSiTSmWSuXSqYS2Yy+ZTWaTeZzmbTqcTufT2gTy" +
            "hT+h0GiSCAgAAKAAABAwABAAAAGAAAAAEBAwABAAAADAAAAAIBAwADAAAA2gAAAAMBAwABAAAABQAAAAYBAwABAAAAAgAAABEBBAABAAAACAAA" +
            "ABUBAwABAAAAAwAAABYBAwABAAAADAAAABcBBAABAAAAUwAAABwBAwABAAAAAQAAAAAAAAAIAAgACAA="
        const val BMP =
            "Qk2WAwAAAAAAADYAAAAoAAAAGAAAAAwAAAABABgAAAAAAGADAADEDgAAxA4AAAAAAAAAAAAAFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB" +
            "7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7c" +
            "FB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB" +
            "7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7c" +
            "FB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB" +
            "7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7c" +
            "FB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB" +
            "7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7c" +
            "FB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB" +
            "7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7c" +
            "FB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB7cFB" +
            "7cFB7cFB7cFB7c"
        const val AVIF =
            "AAAAIGZ0eXBhdmlmAAAAAGF2aWZtaWYxbWlhZk1BMUIAAADrbWV0YQAAAAAAAAAhaGRscgAAAAAAAAAAcGljdAAAAAAAAAAAAAAAAAAAAAAOcG" +
            "l0bQAAAAAAAQAAAB5pbG9jAAAAAEQAAAEAAQAAAAEAAAETAAAAKgAAAChpaW5mAAAAAAABAAAAGmluZmUCAAAAAAEAAGF2MDFDb2xvcgAAAABq" +
            "aXBycAAAAEtpcGNvAAAAFGlzcGUAAAAAAAAAGAAAAAwAAAAQcGl4aQAAAAADCAgIAAAADGF2MUOBAAwAAAAAE2NvbHJuY2x4AAEADQAGgAAAAB" +
            "dpcG1hAAAAAAAAAAEAAQQBAoMEAAAAMm1kYXQSAAoJGBDvbRAQ0GhAMhsUx4eGZQIIIJ5QAAAAV98R8d8rTERV57XU4tA="
    }
}
