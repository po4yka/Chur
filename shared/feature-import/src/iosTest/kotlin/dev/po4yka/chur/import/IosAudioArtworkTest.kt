@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.po4yka.chur.imports

import dev.po4yka.chur.ffi.StreamKind
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDictionaryContainsKey
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFGetTypeID
import platform.CoreFoundation.CFNumberGetTypeID
import platform.CoreFoundation.CFNumberGetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFNumberSInt64Type
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.ImageIO.CGImageSourceCopyPropertiesAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.kCGImagePropertyGPSDictionary
import platform.ImageIO.kCGImagePropertyOrientation
import platform.UIKit.UIImage
import platform.posix.memcmp
import platform.posix.unlink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AVFoundation reads actual MP3 APIC and M4A covr fixtures; ImageIO produces new JPEGs. */
class IosAudioArtworkTest {
    @Test
    fun embedded_MP3_and_M4A_artwork_generates_sanitized_JPEGs_without_changing_audio_metadata_or_original_bytes() {
        for ((encoded, extension) in listOf(IosAudioArtFixtures.WITH_ART_MP3 to "mp3", IosAudioArtFixtures.WITH_ART_M4A to "m4a")) {
            fixture(encoded, extension) { codec, source, probe ->
                assertEquals(MediaBounds.CLASS_AUDIO, probe.mediaClass)
                assertEquals(0 to 0, probe.width to probe.height)
                assertTrue(probe.durationMs > 0)
                for (kind in ART_KINDS) {
                    val art = assertNotNull(codec.derive(source, probe, kind))
                    try {
                        assertEquals(kind, art.kind)
                        assertEquals(96 to 48, art.width to art.height, "a small cover must not be enlarged")
                        assertSanitizedJPEG(art)
                    } finally { art.bytes.fill(0) }
                }
                val waveform = assertNotNull(codec.derive(source, probe, StreamKind.AUDIO_WAVEFORM))
                try {
                    assertEquals(0 to 0, waveform.width to waveform.height)
                    assertTrue(waveform.bytes.isNotEmpty())
                } finally { waveform.bytes.fill(0) }
            }
        }
    }

    @Test
    fun absent_or_corrupt_artwork_preserves_audio_and_waveform_without_visual_derivatives() {
        val cases = listOf(IosAudioArtFixtures.NO_ART_MP3 to "mp3", IosAudioArtFixtures.CORRUPT_ART_MP3 to "mp3",
            IosAudioArtFixtures.NO_ART_M4A to "m4a", IosAudioArtFixtures.CORRUPT_ART_M4A to "m4a")
        for ((encoded, extension) in cases) fixture(encoded, extension) { codec, source, probe ->
            assertEquals(MediaBounds.CLASS_AUDIO, probe.mediaClass)
            assertTrue(probe.durationMs > 0)
            ART_KINDS.forEach { assertNull(codec.derive(source, probe, it)) }
            val waveform = assertNotNull(codec.derive(source, probe, StreamKind.AUDIO_WAVEFORM))
            waveform.bytes.fill(0)
        }
    }

    @Test
    fun embedded_orientation_is_normalized_before_encoding_and_large_art_is_downsampled_per_kind() {
        fixture(IosAudioArtFixtures.ORIENTED_ART_MP3, "mp3") { codec, source, probe ->
            for (kind in ART_KINDS) {
                val art = assertNotNull(codec.derive(source, probe, kind))
                try { assertEquals(48 to 96, art.width to art.height); assertSanitizedJPEG(art) }
                finally { art.bytes.fill(0) }
            }
        }
        fixture(IosAudioArtFixtures.LARGE_ART_MP3, "mp3") { codec, source, probe ->
            val expected = mapOf(StreamKind.THUMBNAIL to (320 to 160), StreamKind.GRID_PREVIEW to (640 to 320),
                StreamKind.SCREEN_PREVIEW to (2048 to 1024))
            for (kind in ART_KINDS) {
                val art = assertNotNull(codec.derive(source, probe, kind))
                try { assertEquals(expected[kind], art.width to art.height); assertSanitizedJPEG(art) }
                finally { art.bytes.fill(0) }
            }
        }
    }

    @Test
    fun cancellation_and_encoded_size_limits_stop_optional_art_before_publication() {
        fixture(IosAudioArtFixtures.WITH_ART_MP3, "mp3") { codec, source, probe ->
            ART_KINDS.forEach { kind -> assertNull(codec.derive(source, probe, kind) { true }) }
            var checks = 0
            assertNull(codec.derive(source, probe, StreamKind.THUMBNAIL) { ++checks >= 3 })
            assertTrue(checks >= 3)
            assertNull(codec.derive(source, probe, StreamKind.AUDIO_WAVEFORM) { true })
        }
        val valid = assertNotNull(NSData.create(base64EncodedString = IosAudioArtFixtures.COVER_JPEG, options = 0u))
        var checks = 0
        assertNull(audioArtworkDerivative(valid, StreamKind.THUMBNAIL) { ++checks >= 4 })
        assertTrue(checks >= 4)
        val oversized = ByteArray(MediaBounds.AUDIO_ART_ENCODED_MAX_BYTES + 1)
        try { assertNull(audioArtworkDerivative(oversized.toData(), StreamKind.THUMBNAIL) { false }) }
        finally { oversized.fill(0) }
        assertNull(audioArtworkDerivative(byteArrayOf(1, 2, 3, 4).toData(), StreamKind.THUMBNAIL) { false })
    }

    private inline fun fixture(encoded: String, extension: String, block: (IosMediaCodec, PickedMedia, ProbedMedia) -> Unit) {
        val path = NSTemporaryDirectory() + "chur-audio-art-" + NSUUID().UUIDString + ".$extension"
        val original = assertNotNull(NSData.create(base64EncodedString = encoded, options = 0u))
        assertTrue(original.writeToFile(path, atomically = true))
        val codec = IosMediaCodec()
        val source = assertNotNull(codec.open(NSURL.fileURLWithPath(path), "Public fixture.$extension"))
        try {
            val probe = assertNotNull(codec.probe(source))
            block(codec, source, probe)
            assertEquals("Public fixture.$extension", source.originalFilename)
            val after = assertNotNull(NSFileManager.defaultManager.contentsAtPath(path))
            assertEquals(original.length, after.length,
                "optional extraction must leave the original audio size unchanged")
            if (original.length > 0uL) {
                assertEquals(0, memcmp(assertNotNull(original.bytes), assertNotNull(after.bytes), original.length),
                    "optional extraction must leave all original audio bytes unchanged")
            }
        } finally { source.close(); unlink(path) }
    }

    private fun assertSanitizedJPEG(art: Derivative) {
        assertTrue(art.bytes.size in 2..MediaBounds.AUDIO_ART_ENCODED_MAX_BYTES)
        assertEquals(0xff, art.bytes[0].toInt() and 255)
        assertEquals(0xd8, art.bytes[1].toInt() and 255)
        val data = art.bytes.toData()
        val decoded = assertNotNull(UIImage.imageWithData(data))
        assertEquals(art.width.toDouble() to art.height.toDouble(), decoded.size.useContents { width to height })
        val cfData = CFDataCreate(null, data.bytes?.reinterpret(), data.length.toLong())
        assertNotNull(cfData)
        try {
            val source = assertNotNull(CGImageSourceCreateWithData(cfData, null))
            try {
                val properties = assertNotNull(CGImageSourceCopyPropertiesAtIndex(source, 0u, null))
                try {
                    assertTrue(!CFDictionaryContainsKey(properties, kCGImagePropertyGPSDictionary))
                    // UIKit may write an explicit normal orientation into a
                    // new JPEG. No source rotation or mirror may remain.
                    val orientation = CFDictionaryGetValue(properties, kCGImagePropertyOrientation)
                    if (orientation != null) memScoped {
                        assertEquals(CFNumberGetTypeID(), CFGetTypeID(orientation))
                        val value = alloc<LongVar>()
                        assertTrue(CFNumberGetValue(orientation.reinterpret(), kCFNumberSInt64Type, value.ptr))
                        assertEquals(1L, value.value, "a re-encoded JPEG must have normal orientation")
                    }
                } finally { CFRelease(properties) }
            } finally { CFRelease(source) }
        } finally { CFRelease(cfData) }
    }
    private fun ByteArray.toData(): NSData = usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = size.toULong()) }
    private companion object {
        val ART_KINDS = listOf(StreamKind.THUMBNAIL, StreamKind.GRID_PREVIEW, StreamKind.SCREEN_PREVIEW)
    }
}
