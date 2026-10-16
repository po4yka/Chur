package dev.po4yka.chur.app.vault

import kotlinx.coroutines.CancellationException

const val CONTENT_TEXT_MAX_BYTES = 256 * 1024
const val CONTENT_SOURCE_MAX_BYTES = 32L * 1024 * 1024
const val CONTENT_PDF_MAX_PAGES = 100

/** Authenticated original ranges. The caller keeps the reader lease until extraction drains. */
data class ContentTextSource(
    val size: Long,
    val read: (offset: Long, length: Int) -> ByteArray,
    val canRead: () -> Boolean,
)

/** Ownership of [bytes] passes to the caller, which clears it after the encrypted index write. */
data class ExtractedContent(val bytes: ByteArray, val partial: Boolean, val pages: Int = 1) {
    override fun toString(): String = "ExtractedContent(<redacted>)"
}

enum class ContentExtractionFailure { UNSUPPORTED, LIMIT, UNAVAILABLE }
class ContentExtractionException(val reason: ContentExtractionFailure) : Exception("Content text extraction is unavailable")

interface ContentTextExtractor {
    suspend fun extract(
        source: ContentTextSource,
        contentType: String,
        mediaClass: Int,
        cancelRequested: () -> Boolean,
        onPage: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): ExtractedContent
}

internal fun checkContentSource(source: ContentTextSource, cancelRequested: () -> Boolean) {
    if (cancelRequested() || !source.canRead()) throw CancellationException("Content extraction ended")
}

/** Text files use bounded authenticated bytes and never enter a native media parser. */
internal fun extractPlainContent(source: ContentTextSource, cancelRequested: () -> Boolean): ExtractedContent {
    checkContentSource(source, cancelRequested)
    if (source.size < 0) throw ContentExtractionException(ContentExtractionFailure.LIMIT)
    val wanted = minOf(source.size, DOCUMENT_TEXT_PREVIEW_MAX_BYTES.toLong()).toInt()
    val bytes = ByteArray(wanted)
    try {
        var offset = 0
        while (offset < wanted) {
            checkContentSource(source, cancelRequested)
            val count = minOf(64 * 1024, wanted - offset)
            val part = source.read(offset.toLong(), count)
            try {
                if (part.isEmpty() || part.size > count) throw ContentExtractionException(ContentExtractionFailure.UNAVAILABLE)
                part.copyInto(bytes, offset)
                offset += part.size
            } finally { part.fill(0) }
        }
        checkContentSource(source, cancelRequested)
        // Strict UTF-8 avoids turning arbitrary binary files into searchable garbage.
        var textEnd = bytes.size
        if (source.size > wanted && textEnd > 0) {
            var start = textEnd - 1
            while (start > 0 && bytes[start].toInt() and 0xc0 == 0x80) start--
            val leading = bytes[start].toInt() and 0xff
            val width = when { leading < 0x80 -> 1; leading in 0xc2..0xdf -> 2; leading in 0xe0..0xef -> 3; leading in 0xf0..0xf4 -> 4; else -> 1 }
            if (start + width > textEnd) textEnd = start
        }
        val text = try { bytes.decodeToString(endIndex = textEnd, throwOnInvalidSequence = true) }
        catch (_: CharacterCodingException) { throw ContentExtractionException(ContentExtractionFailure.UNSUPPORTED) }
        val result = boundedContentText(text)
        return ExtractedContent(result, source.size > wanted || result.size < bytes.size)
    } finally { bytes.fill(0) }
}

/** Cuts only between UTF-8 code points. The temporary full encoding is always cleared. */
internal fun boundedContentText(text: String, maxBytes: Int = CONTENT_TEXT_MAX_BYTES): ByteArray {
    val encoded = text.encodeToByteArray()
    if (encoded.size <= maxBytes) return encoded
    try {
        var end = maxBytes
        while (end > 0 && encoded[end].toInt() and 0xc0 == 0x80) end--
        return encoded.copyOf(end)
    } finally { encoded.fill(0) }
}

internal fun contentTextTruncated(text: String, byteCount: Int): Boolean {
    val encoded = text.encodeToByteArray()
    return try { encoded.size > byteCount } finally { encoded.fill(0) }
}
