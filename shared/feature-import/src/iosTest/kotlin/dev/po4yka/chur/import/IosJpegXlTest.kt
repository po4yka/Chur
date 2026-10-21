@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.po4yka.chur.imports

import dev.po4yka.chur.ffi.StreamKind
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.toKString
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.isEqualToData
import platform.Foundation.writeToFile
import platform.UIKit.UIImage
import platform.posix.lseek
import platform.posix.SEEK_CUR
import platform.posix.SEEK_SET
import platform.posix.unlink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosJpegXlTest {
    @Test
    fun real_codestream_container_alpha_oriented_and_animated_images_import_without_filename_hints() {
        for ((name, size) in listOf(
            "still-codestream.jxl" to (96 to 48), "still-container.jxl" to (96 to 48),
            "alpha.jxl" to (32 to 16), "oriented.jxl" to (96 to 48), "animated.jxl" to (48 to 24),
        )) withFixture(name) { path, original ->
            val codec = IosMediaCodec()
            val media = assertNotNull(codec.open(NSURL.fileURLWithPath(path), "Provider copy.bin"))
            try {
                assertEquals("image/jxl", media.contentTypeHint, name)
                val probe = assertNotNull(codec.probe(media), name)
                assertEquals(MediaBounds.CLASS_IMAGE, probe.mediaClass)
                assertEquals(size, probe.width to probe.height, name)
                for (kind in listOf(StreamKind.THUMBNAIL, StreamKind.GRID_PREVIEW, StreamKind.SCREEN_PREVIEW)) {
                    val preview = assertNotNull(codec.derive(media, probe, kind), "$name $kind")
                    try {
                        assertEquals(size, preview.width to preview.height)
                        assertNotNull(UIImage.imageWithData(preview.bytes.toData()))
                        assertEquals(0xff.toByte(), preview.bytes[0])
                        assertEquals(0xd8.toByte(), preview.bytes[1])
                    } finally { preview.bytes.fill(0) }
                }
                assertTrue(original.isEqualToData(assertNotNull(NSData.dataWithContentsOfFile(path))))
            } finally { media.close() }
        }
    }

    @Test
    fun large_image_previews_are_bounded_and_decoding_keeps_the_borrowed_descriptor_position() =
        withFixture("large.jxl") { path, _ ->
            val codec = IosMediaCodec()
            val media = assertNotNull(codec.open(NSURL.fileURLWithPath(path), "Large.jxl"))
            try {
                assertEquals(7L, lseek(media.descriptor, 7, SEEK_SET))
                assertTrue(jpegXlSignature(media.descriptor))
                val probe = assertNotNull(codec.probe(media))
                assertEquals(2050 to 1025, probe.width to probe.height)
                for ((kind, size) in listOf(
                    StreamKind.THUMBNAIL to (320 to 160), StreamKind.GRID_PREVIEW to (640 to 320),
                    StreamKind.SCREEN_PREVIEW to (2048 to 1024),
                )) {
                    val preview = assertNotNull(codec.derive(media, probe, kind))
                    try { assertEquals(size, preview.width to preview.height) } finally { preview.bytes.fill(0) }
                }
                assertEquals(7L, lseek(media.descriptor, 0, SEEK_CUR))
                assertNull(codec.derive(media, probe, StreamKind.THUMBNAIL) { true })
            } finally { media.close() }
        }

    @Test
    fun corrupt_or_truncated_images_keep_the_original_but_have_no_invented_preview() {
        for (name in listOf("malformed.jxl", "truncated.jxl", "oversized.jxl")) withFixture(name) { path, original ->
            val codec = IosMediaCodec()
            val media = assertNotNull(codec.open(NSURL.fileURLWithPath(path), name))
            try {
                assertEquals("image/jxl", media.contentTypeHint)
                assertNull(codec.probe(media))
                assertTrue(original.isEqualToData(assertNotNull(NSData.dataWithContentsOfFile(path))))
            } finally { media.close() }
        }
    }

    private fun ByteArray.toData(): NSData = usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

    private fun withFixture(name: String, block: (String, NSData) -> Unit) {
        val root = assertNotNull(platform.posix.getenv("CHUR_JPEG_XL_FIXTURES")?.toKString())
        val data = assertNotNull(NSData.dataWithContentsOfFile("$root/$name"))
        val path = NSTemporaryDirectory() + NSUUID().UUIDString + ".bin"
        assertTrue(data.writeToFile(path, atomically = true))
        try { block(path, data) } finally { unlink(path) }
    }
}
