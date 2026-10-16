package dev.po4yka.chur.app.vault

import kotlinx.coroutines.CancellationException
import kotlin.test.*

class ContentTextExtractorTest {
    @Test fun authenticated_ranges_are_bounded_and_cleared() {
        val returned = mutableListOf<ByteArray>()
        val original = "Private invoice Приватный документ".encodeToByteArray()
        val result = extractPlainContent(ContentTextSource(original.size.toLong(), { offset, size ->
            assertTrue(size <= 64 * 1024)
            original.copyOfRange(offset.toInt(), offset.toInt() + size).also { returned += it }
        }, { true }), { false })
        try {
            assertEquals("Private invoice Приватный документ", result.bytes.decodeToString())
            assertEquals("ExtractedContent(<redacted>)", result.toString())
            assertFalse(result.partial)
            assertTrue(returned.all { it.all { byte -> byte == 0.toByte() } })
        } finally { result.bytes.fill(0); original.fill(0) }
    }

    @Test fun utf8_boundaries_and_large_files_are_explicitly_partial() {
        val text = "я".repeat(CONTENT_TEXT_MAX_BYTES)
        val original = text.encodeToByteArray()
        val result = extractPlainContent(source(original), { false })
        try {
            assertEquals(CONTENT_TEXT_MAX_BYTES, result.bytes.size)
            assertTrue(result.partial)
            assertEquals("я".repeat(CONTENT_TEXT_MAX_BYTES / 2), result.bytes.decodeToString(throwOnInvalidSequence = true))
            assertEquals("a", boundedContentText("aя", 2).decodeToString())
        } finally { original.fill(0); result.bytes.fill(0) }
        val cut = ByteArray(DOCUMENT_TEXT_PREVIEW_MAX_BYTES + 4) { 'a'.code.toByte() }
        "😀".encodeToByteArray().copyInto(cut, DOCUMENT_TEXT_PREVIEW_MAX_BYTES - 1)
        val limited = extractPlainContent(source(cut), { false })
        try { assertTrue(limited.partial); limited.bytes.decodeToString(throwOnInvalidSequence = true) }
        finally { cut.fill(0); limited.bytes.fill(0) }
    }

    @Test fun revoked_cancelled_corrupt_and_short_sources_do_not_return_content() {
        var reads = 0
        val noRead = ContentTextSource(10, { _, _ -> reads++; byteArrayOf(1) }, { false })
        assertFailsWith<CancellationException> { extractPlainContent(noRead, { false }) }
        assertEquals(0, reads)
        assertFailsWith<CancellationException> { extractPlainContent(source(byteArrayOf(1)), { true }) }
        assertEquals(ContentExtractionFailure.UNSUPPORTED,
            assertFailsWith<ContentExtractionException> { extractPlainContent(source(byteArrayOf(0xff.toByte())), { false }) }.reason)
        assertFailsWith<ContentExtractionException> { extractPlainContent(ContentTextSource(1, { _, _ -> byteArrayOf() }, { true }), { false }) }
        val held = "private".encodeToByteArray()
        var live = true
        val invalidating = ContentTextSource(held.size.toLong(), { _, _ -> live = false; held }, { live })
        assertFailsWith<CancellationException> { extractPlainContent(invalidating, { false }) }
        assertTrue(held.all { it == 0.toByte() })
    }

    private fun source(bytes: ByteArray) = ContentTextSource(bytes.size.toLong(), { offset, size ->
        bytes.copyOfRange(offset.toInt(), offset.toInt() + size)
    }, { true })
}
