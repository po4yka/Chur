package dev.po4yka.chur.app

import dev.po4yka.chur.app.vault.archivePasswordValid
import dev.po4yka.chur.ffi.PrivateNoteSelection
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelectiveExportRequestTest {
    @Test
    fun a_picker_request_owns_the_ids_captured_before_the_user_changes_the_selection() {
        val objectId = ByteArray(16) { 1 }
        val albumId = ByteArray(16) { 2 }
        val noteId = ByteArray(16) { 3 }
        val objects = mutableListOf(objectId)
        val notes = mutableListOf(PrivateNoteSelection(noteId, 17))
        val request = SelectiveExportRequest(9, objects, listOf(albumId), notes)
        objectId.fill(0); albumId.fill(0); noteId.fill(0)
        objects.clear(); notes.clear()
        assertContentEquals(ByteArray(16) { 1 }, request.objectIds.single())
        assertContentEquals(ByteArray(16) { 2 }, request.albumIds.single())
        assertContentEquals(ByteArray(16) { 3 }, request.notes.single().noteId)
        assertEquals(17L, request.notes.single().revision)
        assertEquals("1 file; 1 album, including subalbums; 1 private note", request.summary)
        assertEquals("SelectiveExportRequest(<redacted>)", request.toString())
    }

    @Test
    fun empty_oversized_or_invalid_reference_requests_are_rejected_before_a_picker_opens() {
        assertFailsWith<IllegalArgumentException> { SelectiveExportRequest(1) }
        assertFailsWith<IllegalArgumentException> { SelectiveExportRequest(1, objectIds = List(1_001) { ByteArray(16) }) }
        assertFailsWith<IllegalArgumentException> { SelectiveExportRequest(1, albumIds = listOf(byteArrayOf(1))) }
        assertFailsWith<IllegalArgumentException> { SelectiveExportRequest(1, notes = listOf(PrivateNoteSelection(ByteArray(16), 0))) }
        // An empty album is still a valid scope: native resolves its contents.
        assertEquals(1, SelectiveExportRequest(1, albumIds = listOf(ByteArray(16) { 1 })).albumIds.size)
    }

    @Test
    fun archive_passwords_use_the_exact_untrimmed_utf8_sequence_and_a_byte_bound() {
        assertFalse(archivePasswordValid(""))
        assertTrue(archivePasswordValid("  exact password  "))
        assertTrue(archivePasswordValid("🌍".repeat(256)))
        assertFalse(archivePasswordValid("🌍".repeat(256) + "x"))
        assertTrue(archivePasswordValid("e\u0301".repeat(341)))
    }
}
