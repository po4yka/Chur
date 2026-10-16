package dev.po4yka.chur.app

import dev.po4yka.chur.app.vault.DocumentPreviewKind
import dev.po4yka.chur.app.vault.decodeDocumentText
import dev.po4yka.chur.app.vault.documentPreviewKind
import dev.po4yka.chur.app.vault.documentTextChunks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentPreviewTest {
    @Test
    fun only_pdf_and_literal_text_formats_have_an_internal_preview() {
        assertEquals(DocumentPreviewKind.PDF, documentPreviewKind("Application/PDF"))
        for (type in listOf("text/plain; charset=UTF-8", "text/html", "application/json", "application/xml")) {
            assertEquals(DocumentPreviewKind.TEXT, documentPreviewKind(type))
        }
        for (type in listOf("", "invalid type", "image/jpeg", "application/octet-stream", "application/zip")) {
            assertEquals(DocumentPreviewKind.NONE, documentPreviewKind(type))
        }
    }

    @Test
    fun empty_text_and_utf8_bom_have_explicit_valid_results() {
        assertEquals("", assertNotNull(decodeDocumentText(byteArrayOf(), false)).text)
        val text = assertNotNull(decodeDocumentText("\uFEFFПривет, мир 🌍".encodeToByteArray(), false))
        assertEquals("Привет, мир 🌍", text.text)
        assertFalse(text.truncated)
    }

    @Test
    fun truncation_drops_only_an_incomplete_final_codepoint() {
        val utf8 = "abc🌍".encodeToByteArray()
        for (size in 4..6) {
            val preview = assertNotNull(decodeDocumentText(utf8.copyOf(size), true))
            assertEquals("abc", preview.text)
            assertTrue(preview.truncated)
            assertNull(decodeDocumentText(utf8.copyOf(size), false))
        }
        assertEquals("abc🌍", assertNotNull(decodeDocumentText(utf8, false)).text)
    }

    @Test
    fun malformed_or_binary_content_is_never_silently_replaced_with_text() {
        for (bytes in listOf(byteArrayOf(0xc0.toByte()), byteArrayOf(0xe0.toByte(), 0x80.toByte()),
            byteArrayOf(0xf4.toByte(), 0x90.toByte()), byteArrayOf(65, 0, 66), byteArrayOf(0xff.toByte(), 65))) {
            assertNull(decodeDocumentText(bytes, false))
            assertNull(decodeDocumentText(bytes, true))
        }
    }

    @Test
    fun markup_remains_literal_and_layout_chunks_keep_surrogate_pairs_intact() {
        val literal = "<script>alert('private')</script>\n{\"name\": \"value\"}"
        assertEquals(literal, assertNotNull(decodeDocumentText(literal.encodeToByteArray(), false)).text)
        val text = "a".repeat(4_095) + "🌍" + "tail"
        val chunks = documentTextChunks(text)
        assertEquals(text, chunks.joinToString(""))
        assertEquals(4_095, chunks.first().length)
        assertFalse(chunks.any { it.last().isHighSurrogate() || it.first().isLowSurrogate() })
    }
}
