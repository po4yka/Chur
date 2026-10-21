package dev.po4yka.chur.app.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoPlaybackTimelineTest {
    @Test fun resume_clamps_stale_positions_and_restarts_the_last_two_seconds() {
        assertEquals(0L, videoResumePosition(-1, 64_000))
        assertEquals(31_000L, videoResumePosition(31_000, 64_000))
        assertEquals(0L, videoResumePosition(62_000, 64_000))
        assertEquals(0L, videoResumePosition(90_000, 64_000))
        assertEquals(0L, videoResumePosition(100, 1_000))
        assertEquals(0L, videoResumePosition(100, 0))
    }

    @Test fun preview_slots_cover_each_cell_and_clamp_both_ends() {
        for (slot in 0..11) assertEquals(slot, videoTimelineSlot(slot * 1_000L + 500L, 12_000L))
        assertEquals(0, videoTimelineSlot(-1, 12_000))
        assertEquals(11, videoTimelineSlot(12_000, 12_000))
        assertEquals(11, videoTimelineSlot(Long.MAX_VALUE, 12_000))
        assertEquals(0, videoTimelineSlot(10, 0))
    }

    @Test fun jpeg_header_requires_the_exact_atlas_bounds_before_decode() {
        val header = byteArrayOf(-1, -40, -1, -64, 0, 8, 8, 1, 14, 2, -128, 0)
        assertTrue(timelineJpegHasExpectedSize(header))
        assertFalse(timelineJpegHasExpectedSize(header.copyOf(9)))
        assertFalse(timelineJpegHasExpectedSize(header.copyOf().also { it[9] = 127 }))
        assertFalse(timelineJpegHasExpectedSize(byteArrayOf(-1, -40, -1, -38, 0, 2)))
    }
}
