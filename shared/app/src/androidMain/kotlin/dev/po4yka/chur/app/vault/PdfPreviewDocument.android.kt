package dev.po4yka.chur.app.vault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.sqrt

/** One read-only platform PDF renderer. It owns no plaintext file or whole-file buffer. */
class PdfPreviewDocument private constructor(
    private val renderer: PdfRenderer,
    private val allowed: AtomicBoolean,
    private val canRead: () -> Boolean,
    private val release: () -> Unit,
    private val worker: HandlerThread,
) : AutoCloseable {
    private var closed = false
    val pageCount: Int = renderer.pageCount

    /** Safe from the UI thread: ongoing native calls drain before [close] releases resources. */
    fun invalidate() { allowed.set(false) }

    fun render(index: Int): Bitmap = synchronized(this) {
        check(!closed && allowed.get() && canRead()) { "the PDF preview has ended" }
        renderer.openPage(index).use { page ->
            require(page.width > 0 && page.height > 0)
            val scale = min(DOCUMENT_PDF_RASTER_EDGE_MAX.toDouble() / maxOf(page.width, page.height),
                sqrt(4_000_000.0 / (page.width.toDouble() * page.height)))
            val width = (page.width * scale).toInt().coerceIn(1, DOCUMENT_PDF_RASTER_EDGE_MAX)
            val height = (page.height * scale).toInt().coerceIn(1, DOCUMENT_PDF_RASTER_EDGE_MAX)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                check(allowed.get() && canRead()) { "the PDF preview has ended" }
                bitmap
            } catch (failure: Exception) {
                bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
                bitmap.recycle()
                throw failure
            }
        }
    }

    override fun close() = synchronized(this) {
        if (closed) return@synchronized
        closed = true
        invalidate()
        try { renderer.close() } finally { release(); worker.quitSafely() }
    }

    companion object {
        /**
         * The production caller supplies authenticated native ranges and a session check.
         * The size limit restricts this optional parser, never original import or export.
         */
        fun open(
            context: Context,
            size: Long,
            read: (offset: Long, length: Int) -> ByteArray,
            canRead: () -> Boolean,
            onRelease: () -> Unit,
        ): PdfPreviewDocument {
            require(size in 1..DOCUMENT_PDF_PREVIEW_MAX_BYTES) { "PDF preview size limit" }
            val worker = HandlerThread("chur-pdf-ranges").apply { start() }
            val allowed = AtomicBoolean(true)
            val released = AtomicBoolean(false)
            val release = { if (released.compareAndSet(false, true)) onRelease() }
            var descriptor: ParcelFileDescriptor? = null
            var renderer: PdfRenderer? = null
            try {
                val storage = requireNotNull(context.getSystemService(StorageManager::class.java))
                descriptor = storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY,
                    object : ProxyFileDescriptorCallback() {
                        private fun checkOpen() {
                            if (!allowed.get() || !canRead()) throw ErrnoException("read", OsConstants.EIO)
                        }
                        override fun onGetSize(): Long { checkOpen(); return size }
                        override fun onRead(offset: Long, count: Int, data: ByteArray): Int {
                            checkOpen()
                            if (offset < 0 || count < 0 || count > data.size || offset > size) {
                                throw ErrnoException("read", OsConstants.EINVAL)
                            }
                            val wanted = minOf(count.toLong(), size - offset).toInt()
                            var copied = 0
                            try {
                                while (copied < wanted) {
                                    checkOpen()
                                    val requested = minOf(wanted - copied, 64 * 1024)
                                    val bytes = read(offset + copied, requested)
                                    try {
                                        if (bytes.isEmpty() || bytes.size > requested) throw ErrnoException("read", OsConstants.EIO)
                                        bytes.copyInto(data, copied)
                                        copied += bytes.size
                                    } finally { bytes.fill(0) }
                                }
                                checkOpen()
                                return copied
                            } catch (_: Exception) {
                                data.fill(0, 0, wanted)
                                throw ErrnoException("read", OsConstants.EIO)
                            }
                        }
                        override fun onRelease() { release() }
                    }, Handler(worker.looper))
                renderer = PdfRenderer(descriptor)
                descriptor = null // The renderer owns it from now on.
                check(renderer.pageCount > 0)
                return PdfPreviewDocument(renderer, allowed, canRead, release, worker)
            } catch (failure: Exception) {
                allowed.set(false)
                runCatching { renderer?.close() }
                runCatching { descriptor?.close() }
                release()
                worker.quitSafely()
                throw failure
            }
        }
    }
}
