package dev.po4yka.chur.app.vault

import android.content.Context
import dev.po4yka.chur.imports.AndroidDerivativeClient
import dev.po4yka.chur.imports.DerivativeInput
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Serves authenticated ranges over Binder. No plaintext path, descriptor, or vault key reaches a child. */
class AndroidContentTextExtractor(context: Context) : ContentTextExtractor {
    private val context = context.applicationContext

    override suspend fun extract(
        source: ContentTextSource,
        contentType: String,
        mediaClass: Int,
        cancelRequested: () -> Boolean,
        onPage: (Int, Int) -> Unit,
    ): ExtractedContent {
        var owned: ExtractedContent? = null
        try {
            val result = withContext(Dispatchers.IO) {
                val coroutine = currentCoroutineContext()
                val stopped = { !coroutine.isActive || cancelRequested() }
                checkContentSource(source, stopped)
                if (documentPreviewKind(contentType) == DocumentPreviewKind.TEXT) return@withContext extractPlainContent(source, stopped).also { owned = it }
                if (contentType != "application/pdf" && !contentType.startsWith("image/")) {
                    throw ContentExtractionException(ContentExtractionFailure.UNSUPPORTED)
                }
                if (source.size !in 1..CONTENT_SOURCE_MAX_BYTES) throw ContentExtractionException(ContentExtractionFailure.LIMIT)
                val result = AndroidDerivativeClient(context).recognize(ContentSourceInput(source, stopped), contentType) {
                    cancelRequested() || !source.canRead()
                }
                if (result == null) {
                    checkContentSource(source, stopped)
                    throw ContentExtractionException(ContentExtractionFailure.UNAVAILABLE)
                }
                var transferred = false
                try {
                    checkContentSource(source, stopped)
                    onPage(result.pages, result.pages)
                    ExtractedContent(result.bytes, result.partial, result.pages).also { transferred = true; owned = it }
                } finally { if (!transferred) result.bytes.fill(0) }
            }
            owned = null
            return result
        } catch (failure: Throwable) {
            owned?.bytes?.fill(0)
            throw failure
        }
    }
}

/**
 * Serves authenticated ranges to the decoder child. The child's reader closes
 * this input and waits for a running read before the reader lease can end.
 */
private class ContentSourceInput(private val source: ContentTextSource, private val stopped: () -> Boolean) : DerivativeInput {
    override val size: Long = source.size

    override fun read(offset: Long, into: ByteArray, count: Int, stopped: () -> Boolean): Int {
        fun checkOpen() {
            val readable = try { source.canRead() && !this.stopped() && !stopped() } catch (_: Exception) { false }
            if (!readable) throw IOException("Content source ended")
        }
        checkOpen()
        if (offset !in 0..size || count < 0 || count > into.size) throw IOException("Content range is invalid")
        if (offset == size) return -1
        val wanted = minOf(count.toLong(), size - offset).toInt()
        var copied = 0
        try {
            while (copied < wanted) {
                checkOpen()
                val length = minOf(64 * 1024, wanted - copied)
                val bytes = source.read(offset + copied, length)
                try {
                    if (bytes.isEmpty() || bytes.size > length) throw IOException("Content range is unavailable")
                    bytes.copyInto(into, copied)
                    copied += bytes.size
                } finally { bytes.fill(0) }
            }
            checkOpen()
            return copied
        } catch (failure: Exception) { into.fill(0, 0, wanted); throw failure }
    }
}
