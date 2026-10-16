package dev.po4yka.chur.app.vault

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import dev.po4yka.chur.imports.AndroidDerivativeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Reads authenticated ranges through a proxy descriptor. No plaintext path or vault key reaches the child. */
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
                val owner = ContentSourceDescriptor(context, source, stopped)
                try {
                    val result = AndroidDerivativeClient(context).recognize(owner.descriptor, contentType) {
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
                } finally { owner.close() }
            }
            owned = null
            return result
        } catch (failure: Throwable) {
            owned?.bytes?.fill(0)
            throw failure
        }
    }
}

/** Invalidation waits for an authenticated range read to drain before its reader lease can end. */
private class ContentSourceDescriptor(context: Context, private val source: ContentTextSource, private val stopped: () -> Boolean) : AutoCloseable {
    private val gate = Any()
    private var open = true
    private val thread = HandlerThread("chur-ocr-ranges").apply { start() }
    val descriptor: ParcelFileDescriptor
    init {
        try {
            val storage = requireNotNull(context.getSystemService(StorageManager::class.java))
            descriptor = storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, object : ProxyFileDescriptorCallback() {
                private fun checkOpen() {
                    val readable = try { open && source.canRead() && !stopped() } catch (_: Exception) { false }
                    if (!readable) throw ErrnoException("read", OsConstants.EIO)
                }
                override fun onGetSize(): Long = synchronized(gate) { checkOpen(); source.size }
                override fun onRead(offset: Long, size: Int, into: ByteArray): Int = synchronized(gate) {
                    checkOpen()
                    if (offset !in 0..source.size || size < 0 || size > into.size) throw ErrnoException("read", OsConstants.EINVAL)
                    val wanted = minOf(size.toLong(), source.size - offset).toInt()
                    var copied = 0
                    try {
                        while (copied < wanted) {
                            checkOpen()
                            val count = minOf(64 * 1024, wanted - copied)
                            val bytes = source.read(offset + copied, count)
                            try {
                                if (bytes.isEmpty() || bytes.size > count) throw ErrnoException("read", OsConstants.EIO)
                                bytes.copyInto(into, copied)
                                copied += bytes.size
                            } finally { bytes.fill(0) }
                        }
                        checkOpen()
                        copied
                    } catch (_: Exception) { into.fill(0, 0, wanted); throw ErrnoException("read", OsConstants.EIO) }
                }
                override fun onRelease() {}
            }, Handler(thread.looper))
        } catch (failure: Throwable) { thread.quitSafely(); throw failure }
    }
    override fun close() {
        synchronized(gate) { open = false }
        try { descriptor.close() } finally { thread.quitSafely() }
    }
}
