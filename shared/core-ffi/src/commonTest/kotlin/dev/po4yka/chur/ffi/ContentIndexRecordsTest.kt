package dev.po4yka.chur.ffi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse

class ContentIndexRecordsTest {
    @Test
    fun candidate_records_are_bounded_and_redact_source_metadata() {
        val bytes = record()
        val candidate = decodeContentIndexCandidates(bytes).single()
        assertEquals(4, candidate.mediaClass)
        assertEquals(123L, candidate.plaintextSize)
        assertEquals("text/plain", candidate.contentType)
        assertFalse(candidate.toString().contains("text/plain"))
        assertEquals(emptyList(), decodeContentIndexCandidates(byteArrayOf(1, 0, 0, 0, 0)))
    }

    @Test
    fun malformed_records_reject_unknown_versions_truncation_trailing_data_and_bad_fields() {
        val bytes = record()
        assertFails { decodeContentIndexCandidates(bytes.copyOf(bytes.size - 1)) }
        assertFails { decodeContentIndexCandidates(bytes + 0) }
        assertFails { decodeContentIndexCandidates(bytes.copyOf().apply { this[0] = 2 }) }
        assertFails { decodeContentIndexCandidates(bytes.copyOf().apply { this[4] = 101 }) }
        assertFails { decodeContentIndexCandidates(bytes.copyOf().apply { fill(0, 5, 21) }) }
        assertFails { decodeContentIndexCandidates(bytes.copyOf().apply { fill(0, 21, 37) }) }
        assertFails { decodeContentIndexCandidates(bytes.copyOf().apply { this[37] = 3 }) }
        assertFails { decodeContentIndexCandidates(bytes.copyOf().apply { this[38] = 0xff.toByte() }) }
        assertFails { decodeContentIndexCandidates(bytes.copyOf().apply { this[lastIndex] = 0xff.toByte() }) }
    }

    private fun record(): ByteArray = PrivateNoteWriter(60).apply {
        byte(1); count(1); raw(ByteArray(16) { 1 }); raw(ByteArray(16) { 2 }); byte(4); number(123); sized("text/plain".encodeToByteArray())
    }.bytes
}
