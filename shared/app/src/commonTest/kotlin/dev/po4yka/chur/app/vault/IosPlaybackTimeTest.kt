package dev.po4yka.chur.app.vault

import kotlin.test.Test
import kotlin.test.assertEquals

class IosPlaybackTimeTest {
    @Test
    fun time_uses_minutes_and_hours_without_raw_second_counts() {
        assertEquals("0:00", iosPlaybackTime(0f))
        assertEquals("1:05", iosPlaybackTime(65f))
        assertEquals("1:02:03", iosPlaybackTime(3_723f))
        assertEquals("0:00", iosPlaybackTime(Float.NaN))
        assertEquals("0:00", iosPlaybackTime(-3f))
    }

    @Test
    fun scrubber_announces_elapsed_and_remaining_with_safe_boundaries() {
        assertEquals("1:05 elapsed, 0:55 remaining", iosPlaybackTimeDescription(65f, 120f))
        assertEquals("2:00 elapsed, 0:00 remaining", iosPlaybackTimeDescription(140f, 120f))
        assertEquals("0:00 elapsed, 2:00 remaining", iosPlaybackTimeDescription(-2f, 120f))
        assertEquals("0:00 elapsed, duration unavailable", iosPlaybackTimeDescription(0f, Float.NaN))
    }
}
