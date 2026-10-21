package dev.po4yka.chur.app.vault

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackContainerBoundsTest {
    @Test
    fun oversized_moov_and_excessive_top_level_headers_stop_before_payload_reads() {
        val header = byteArrayOf(0, 0x10, 0, 9, 'm'.code.toByte(), 'o'.code.toByte(), 'o'.code.toByte(), 'v'.code.toByte())
        val returned = mutableListOf<ByteArray>()
        val facts = inspectPlaybackContainerRanges(1024L * 1024 + 9) { _, count ->
            assertEquals(8, count)
            header.copyOf().also { returned += it }
        }
        assertFalse(facts.complete)
        assertEquals(1, returned.size)
        assertTrue(returned.all { bytes -> bytes.all { it == 0.toByte() } })
        var reads = 0
        assertFalse(inspectPlaybackContainerRanges(129 * 8L) { _, _ ->
            reads++
            byteArrayOf(0, 0, 0, 8, 0x66, 0x72, 0x65, 0x65)
        }.complete)
        assertEquals(128, reads)
    }

    @Test
    fun oversized_extended_sizes_truncated_boxes_and_missing_moov_remain_unknown() {
        for (bytes in listOf(
            byteArrayOf(0, 0, 0, 1, 0x6d, 0x6f, 0x6f, 0x76, -1, 0, 0, 0, 0, 0, 0, 0),
            byteArrayOf(0, 0, 0, 99, 0x6d, 0x6f, 0x6f, 0x76),
            byteArrayOf(0, 0, 0, 8, 0x66, 0x72, 0x65, 0x65),
        )) {
            assertFalse(inspectPlaybackContainerRanges(bytes.size.toLong()) { at, size ->
                bytes.copyOfRange(at.toInt(), at.toInt() + size)
            }.complete)
        }
    }

    @Test
    fun typed_authenticated_failures_propagate_and_short_reads_are_not_called_corruption() {
        val integrity = assertFailsWith<ChurFailure> {
            inspectPlaybackContainerRanges(8) { _, _ -> throw ChurFailure(ChurStatus.OBJECT_CORRUPT, "redacted") }
        }
        assertEquals(ChurStatus.OBJECT_CORRUPT, integrity.status)
        val short = byteArrayOf(1, 2)
        val unavailable = assertFailsWith<ChurFailure> { inspectPlaybackContainerRanges(8) { _, _ -> short } }
        assertEquals(ChurStatus.IO_FAILURE, unavailable.status)
        assertTrue(short.all { it == 0.toByte() })
    }
}
