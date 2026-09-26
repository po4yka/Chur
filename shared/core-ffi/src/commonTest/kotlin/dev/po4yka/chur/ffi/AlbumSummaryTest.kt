package dev.po4yka.chur.ffi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AlbumSummaryTest {
    @Test
    fun membership_count_changes_the_summary_value() {
        val id = ByteArray(16) { it.toByte() }
        val empty = AlbumSummary(id, 0, "Trip")
        val populated = AlbumSummary(id.copyOf(), 1, "Trip")

        assertNotEquals(empty, populated)
        assertEquals(populated, AlbumSummary(id.copyOf(), 1, "Trip"))
        assertEquals(populated.hashCode(), AlbumSummary(id.copyOf(), 1, "Trip").hashCode())
    }
}
