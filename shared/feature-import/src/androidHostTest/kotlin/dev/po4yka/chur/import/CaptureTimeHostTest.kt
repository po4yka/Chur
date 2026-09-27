package dev.po4yka.chur.imports

import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The capture time an Android import reads from the file when the provider
 * publishes none, §8.1 of the catalog and `MEDIA_PIPELINE.md` §4.
 */
class CaptureTimeHostTest {
    @Test
    fun an_exif_time_is_read_in_its_recorded_offset() {
        val anyZone = ZoneId.of("America/New_York")
        assertEquals(1_556_704_800_000, exifCaptureTimeMs("2019:05:01 12:00:00", "+02:00", anyZone))
    }

    @Test
    fun an_exif_time_without_an_offset_is_read_in_the_device_zone() {
        assertEquals(1_556_712_000_000, exifCaptureTimeMs("2019:05:01 12:00:00", null, ZoneOffset.UTC))
        assertEquals(1_556_704_800_000, exifCaptureTimeMs("2019:05:01 12:00:00", "", ZoneId.of("Europe/Berlin")))
    }

    @Test
    fun an_unreadable_exif_time_is_no_capture_time() {
        listOf(null, "", "0000:00:00 00:00:00", "2019:02:30 12:00:00", "yesterday", "    :  :     :  :  ")
            .forEach { assertNull(exifCaptureTimeMs(it, "+02:00", ZoneOffset.UTC), it) }
    }

    @Test
    fun a_container_date_is_utc() {
        assertEquals(1_556_712_000_000, containerCaptureTimeMs("20190501T120000.000Z"))
        assertEquals(1_556_712_000_000, containerCaptureTimeMs("20190501T120000Z"))
    }

    @Test
    fun a_missing_or_unreadable_container_date_is_no_capture_time() {
        // A container with no date stores zero seconds since 1904.
        listOf(null, "", "19040101T000000.000Z", "2019", "garbage")
            .forEach { assertNull(containerCaptureTimeMs(it), it) }
    }
}
