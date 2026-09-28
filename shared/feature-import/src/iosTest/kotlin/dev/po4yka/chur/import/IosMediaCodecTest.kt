package dev.po4yka.chur.imports

import kotlinx.cinterop.BetaInteropApi
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSTimeZone
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.timeZoneForSecondsFromGMT
import platform.Foundation.timeZoneWithName
import platform.Foundation.writeToFile
import platform.posix.unlink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The photo picker's copy has a random name, `IOS.md` §12, so the catalog
 * must record the name the picker gave the item and not the copy's. The
 * picker gives no capture time, so the copy's own is read,
 * `MEDIA_PIPELINE.md` §4.
 */
class IosMediaCodecTest {
    @Test
    fun a_picked_copy_keeps_the_items_own_name_and_type() {
        val path = NSTemporaryDirectory() + NSUUID().UUIDString + ".heic"
        assertTrue(NSFileManager.defaultManager.createFileAtPath(path, null, null))
        try {
            val named = assertNotNull(IosMediaCodec().open(NSURL.fileURLWithPath(path), "IMG_0001.heic"))
            named.close()
            assertEquals("IMG_0001.heic", named.originalFilename)
            assertEquals("image/heic", named.contentTypeHint)

            val unnamed = assertNotNull(IosMediaCodec().open(NSURL.fileURLWithPath(path), null))
            unnamed.close()
            assertNull(unnamed.originalFilename)
        } finally {
            unlink(path)
        }
    }

    @Test
    @OptIn(BetaInteropApi::class)
    fun a_picked_photo_keeps_the_capture_time_its_exif_records() {
        val path = NSTemporaryDirectory() + NSUUID().UUIDString + ".jpg"
        val photo = assertNotNull(NSData.create(base64EncodedString = EXIF_JPEG, options = 0u))
        assertTrue(photo.writeToFile(path, atomically = true))
        try {
            val picked = assertNotNull(IosMediaCodec().open(NSURL.fileURLWithPath(path), "IMG_0002.jpg"))
            picked.close()
            // 2019:05:01 12:00:00 at +02:00.
            assertEquals(1_556_704_800_000, picked.captureTimeMs)
        } finally {
            unlink(path)
        }
    }

    @Test
    fun an_exif_time_is_read_in_its_recorded_offset_or_else_the_device_zone() {
        val anyZone = assertNotNull(NSTimeZone.timeZoneWithName("America/New_York"))
        assertEquals(1_556_704_800_000, exifCaptureTimeMs("2019:05:01 12:00:00", "+02:00", anyZone))
        val utc = NSTimeZone.timeZoneForSecondsFromGMT(0)
        assertEquals(1_556_712_000_000, exifCaptureTimeMs("2019:05:01 12:00:00", null, utc))
        val berlin = assertNotNull(NSTimeZone.timeZoneWithName("Europe/Berlin"))
        assertEquals(1_556_704_800_000, exifCaptureTimeMs("2019:05:01 12:00:00", "", berlin))
    }

    @Test
    fun an_unreadable_exif_time_is_no_capture_time() {
        val utc = NSTimeZone.timeZoneForSecondsFromGMT(0)
        listOf(null, "", "0000:00:00 00:00:00", "2019:02:30 12:00:00", "yesterday", "    :  :     :  :  ")
            .plus("1969:12:31 23:59:59")
            .forEach { assertNull(exifCaptureTimeMs(it, "+00:00", utc), it) }
    }

    private companion object {
        /** An 8 by 8 JPEG whose EXIF says 2019:05:01 12:00:00, offset +02:00. */
        const val EXIF_JPEG =
            "/9j/4AAQSkZJRgABAQAAAQABAAD/4QBcRXhpZgAATU0AKgAAAAgAAYdpAAQAAAABAAAAGgAAAAAAApADAAIAAAAU" +
            "AAAAOJARAAIAAAAHAAAATAAAAAAyMDE5OjA1OjAxIDEyOjAwOjAwACswMjowMAAA/9sAQwAQCwwODAoQDg0OEhEQ" +
            "ExgoGhgWFhgxIyUdKDozPTw5Mzg3QEhcTkBEV0U3OFBtUVdfYmdoZz5NcXlwZHhcZWdj/8AACwgACAAIAQERAP/E" +
            "ABQAAQAAAAAAAAAAAAAAAAAAAAD/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oACAEBAAA/AD//2Q=="
    }
}
