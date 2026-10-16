package dev.po4yka.chur.ffi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PrivateNoteRecordsTest {
    @Test
    fun unicode_records_decode_with_redacted_diagnostics() {
        val bytes = record("秘密 🔐", "строка\nbody")
        val note = decodePrivateNote(bytes)
        assertEquals("秘密 🔐", note.title)
        assertEquals("строка\nbody", note.body)
        assertEquals(null, note.trashedAtMs)
        assertFalse(note.toString().contains(note.title))
        assertFalse(note.toString().contains(note.body))
        bytes.fill(0)
    }

    @Test
    fun malformed_records_refuse_truncation_trailing_bytes_and_bad_flags() {
        val bytes = record("title", "body")
        assertFailsWith<IllegalArgumentException> { decodePrivateNote(bytes.copyOf(bytes.size - 1)) }
        assertFailsWith<IllegalArgumentException> { decodePrivateNote(bytes + byteArrayOf(1)) }
        val invalidPin = bytes.copyOf().also { it[48] = 2 }
        assertFailsWith<IllegalArgumentException> { decodePrivateNote(invalidPin) }
        val zeroId = bytes.copyOf().also { it.fill(0, 0, 16) }
        assertFailsWith<IllegalArgumentException> { decodePrivateNote(zeroId) }
        val invalidUtf8 = record("title", "x").also { it[it.lastIndex] = 0xff.toByte() }
        assertFails { decodePrivateNote(invalidUtf8) }
    }

    private fun record(title: String, body: String): ByteArray {
        val titleBytes = title.encodeToByteArray(); val bodyBytes = body.encodeToByteArray()
        return PrivateNoteWriter(65 + titleBytes.size + bodyBytes.size).apply {
            raw(ByteArray(16) { 1 }); number(1); number(2); number(3); number(-1); byte(0)
            number((titleBytes.size + 2 + bodyBytes.size).toLong()); sized(titleBytes); sized(bodyBytes)
        }.bytes
    }
}
