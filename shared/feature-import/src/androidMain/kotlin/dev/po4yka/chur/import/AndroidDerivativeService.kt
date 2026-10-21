package dev.po4yka.chur.imports

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.Handler
import android.os.HandlerThread
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import dev.po4yka.chur.ffi.StreamKind
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.sqrt

/**
 * The Binder endpoint of a disposable decoder child.
 *
 * A child receives no vault handle, key, storage path, or descriptor for app
 * data. It reads source bytes through the parent's byte-range reader and
 * returns one bounded result in anonymous shared memory. The parent unbinds
 * the child after one operation, and the child then ends.
 */
abstract class DerivativeChildService : Service() {
    internal abstract val operations: Set<Int>

    private val endpoint = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags)
            // Only the app's own UID can bind this unexported service. An isolated
            // child has another UID, so this compares with the application UID.
            if (getCallingUid() != applicationInfo.uid) return false
            data.enforceInterface(DERIVATIVE_INTERFACE)
            if (code == DERIVATIVE_IDENTITY) {
                identity(data.readString().orEmpty(), data.readStrongBinder() ?: return false, requireNotNull(reply))
                return true
            }
            if (code !in operations) return false
            val sink = data.readStrongBinder() ?: return false
            val sent = try { serve(code, data, sink) } catch (_: Exception) { false } catch (_: OutOfMemoryError) { false }
            // EOF of a result carries an unavailable derivative. The parent ends
            // this process before it returns any result.
            if (!sent) runCatching { sendUnavailable(sink) }
            return true
        }
    }

    /**
     * The parent learns this process's UID and PID from Binder through [callback].
     * It refuses to send a source to an isolated child that can open [name] in filesDir.
     */
    private fun identity(name: String, callback: IBinder, reply: Parcel) {
        val call = Parcel.obtain()
        try {
            call.writeInterfaceToken(DERIVATIVE_INTERFACE)
            callback.transact(IDENTITY_CALLBACK, call, null, 0)
        } finally { call.recycle() }
        val target = File(filesDir, name)
        val opened = try {
            if (name.isEmpty()) target.list() != null else FileInputStream(target).use { true }
        } catch (_: Exception) { false }
        reply.writeInt(if (Process.isIsolated()) 1 else 0)
        reply.writeInt(if (opened) 1 else 0)
    }

    private fun serve(code: Int, data: Parcel, sink: IBinder): Boolean = when (code) {
        DERIVATIVE_DECODE -> decode(RemoteSource(data.readStrongBinder() ?: return false), data, sink)
        DERIVATIVE_RENDER -> render(RemoteSource(data.readStrongBinder() ?: return false), data, sink)
        DERIVATIVE_EXTRACT_ARTWORK -> extractArtwork(RemoteSource(data.readStrongBinder() ?: return false), sink)
        DERIVATIVE_DECODE_ARTWORK -> decodeArtwork(data, sink)
        DERIVATIVE_OCR -> recognize(data, sink)
        else -> false
    }

    private fun decode(source: RemoteSource, data: Parcel, sink: IBinder): Boolean {
        val probe = ProbedMedia(data.readInt(), data.readInt(), data.readInt(), data.readLong(), data.readString() ?: return false)
        val kindCode = data.readInt()
        val kind = StreamKind.entries.firstOrNull { it.code == kindCode } ?: return false
        val result = AndroidMediaCodec(contentResolver, source).derive(remoteMedia(source, probe.contentType), probe, kind) { false }
            ?: return false
        try {
            if (result.bytes.size > DERIVATIVE_RESULT_MAX) return false
            sendDerivativeResult(sink, result.width, result.height, 0, result.bytes)
        } finally { result.bytes.fill(0) }
        return true
    }

    private fun extractArtwork(source: RemoteSource, sink: IBinder): Boolean {
        val bytes = AndroidMediaCodec(contentResolver, source).extractArtwork(Uri.EMPTY) { false } ?: return false
        try { sendDerivativeResult(sink, 0, 0, 0, bytes) } finally { bytes.fill(0) }
        return true
    }

    private fun decodeArtwork(data: Parcel, sink: IBinder): Boolean {
        val region = data.readTypedObject(ParcelFileDescriptor.CREATOR) ?: return false
        val bytes = try {
            val length = data.readInt()
            if (length < 1) return false
            readRegion(region, length, MediaBounds.AUDIO_ART_ENCODED_MAX_BYTES) ?: return false
        } finally { region.close() }
        val kindCode = data.readInt()
        try {
            val kind = StreamKind.entries.firstOrNull { it.code == kindCode } ?: return false
            val result = AndroidMediaCodec(contentResolver).decodeArtworkSafely(bytes, kind) { false } ?: return false
            try { sendDerivativeResult(sink, result.width, result.height, 0, result.bytes) } finally { result.bytes.fill(0) }
            return true
        } finally { bytes.fill(0) }
    }

    // One child serves one recognition, so the open document and engine end with it.
    private var document: PdfRenderer? = null
    private var engine: AndroidOfflineOcr? = null
    private var proxyThread: HandlerThread? = null

    /** Rasterizes one image or PDF page for recognition, inside the bounds the parent checks again. */
    private fun render(source: RemoteSource, data: Parcel, sink: IBinder): Boolean {
        val contentType = data.readString() ?: return false
        val page = data.readInt()
        return if (contentType == "application/pdf") renderPdf(source, page, sink) else renderImage(source, contentType, sink)
    }

    private fun renderImage(source: RemoteSource, contentType: String, sink: IBinder): Boolean {
        val codec = AndroidMediaCodec(contentResolver, source)
        val media = remoteMedia(source, contentType)
        val probe = codec.probe(media) ?: return false
        if (probe.mediaClass != MediaBounds.CLASS_IMAGE || MediaBounds.check(probe) != null) return false
        val preview = codec.derive(media, probe, StreamKind.SCREEN_PREVIEW) { false } ?: return false
        val bitmap = try {
            BitmapFactory.decodeByteArray(preview.bytes, 0, preview.bytes.size,
                BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return false
        } finally { preview.bytes.fill(0) }
        return sendPixels(bitmap, 1, sink)
    }

    private fun renderPdf(source: RemoteSource, number: Int, sink: IBinder): Boolean {
        val renderer = document ?: (openPdf(source) ?: return false).also { document = it }
        if (renderer.pageCount <= 0 || number !in 0 until minOf(renderer.pageCount, OCR_PAGE_MAX)) return false
        renderer.openPage(number).use { page ->
            if (page.width <= 0 || page.height <= 0) return false
            val scale = minOf(OCR_RASTER_EDGE_MAX.toDouble() / maxOf(page.width, page.height),
                sqrt(4_000_000.0 / (page.width.toDouble() * page.height)))
            val width = (page.width * scale).toInt().coerceIn(1, OCR_RASTER_EDGE_MAX)
            val height = (page.height * scale).toInt().coerceIn(1, OCR_RASTER_EDGE_MAX)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            } catch (failure: Throwable) { bitmap.recycle(); throw failure }
            return sendPixels(bitmap, renderer.pageCount, sink)
        }
    }

    /**
     * `PdfRenderer` needs a seekable descriptor with a size. The isolated
     * decoder copies the source into a `memfd` (API 30 and later). An app-UID
     * child on API 29 has no `memfd`; it serves the Binder reader through a
     * proxy descriptor of its own. Neither opens a file. The cap is
     * [OCR_PDF_MAX_BYTES].
     */
    private fun openPdf(source: RemoteSource): PdfRenderer? {
        val size = source.size ?: return null
        if (size !in 1..OCR_PDF_MAX_BYTES.toLong()) return null
        if (Process.isIsolated()) {
            if (Build.VERSION.SDK_INT < 30) return null
            val chunk = ByteArray(READ_CHUNK_MAX)
            val descriptor = try {
                anonymousRegion(size.toInt()) { write ->
                    var copied = 0L
                    while (copied < size) {
                        val count = source.readAt(copied, chunk, 0, minOf(chunk.size.toLong(), size - copied).toInt())
                        if (count <= 0) throw IOException("PDF source ended early")
                        write(chunk, 0, count, copied)
                        copied += count
                    }
                }
            } finally { chunk.fill(0) }
            return try { PdfRenderer(descriptor) } catch (failure: Throwable) { descriptor.close(); throw failure }
        }
        val thread = HandlerThread("chur-pdf-source").apply { start() }
        proxyThread = thread
        val storage = requireNotNull(getSystemService(StorageManager::class.java))
        val descriptor = storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, object : ProxyFileDescriptorCallback() {
            override fun onGetSize(): Long = size
            override fun onRead(offset: Long, length: Int, into: ByteArray): Int {
                if (offset !in 0..size || length < 0 || length > into.size) throw ErrnoException("read", OsConstants.EINVAL)
                val wanted = minOf(length.toLong(), size - offset).toInt()
                var copied = 0
                try {
                    while (copied < wanted) {
                        val count = source.readAt(offset + copied, into, copied, minOf(READ_CHUNK_MAX, wanted - copied))
                        if (count <= 0) break
                        copied += count
                    }
                    return copied
                } catch (_: Exception) { into.fill(0, 0, wanted); throw ErrnoException("read", OsConstants.EIO) }
            }
            override fun onRelease() {}
        }, Handler(thread.looper))
        return try { PdfRenderer(descriptor) } catch (failure: Throwable) { descriptor.close(); throw failure }
    }

    /** Sends ARGB_8888 pixels in shared memory and clears and recycles [bitmap]. */
    private fun sendPixels(bitmap: Bitmap, pages: Int, sink: IBinder): Boolean {
        try {
            if (bitmap.config != Bitmap.Config.ARGB_8888 || bitmap.width !in 1..OCR_RASTER_EDGE_MAX ||
                bitmap.height !in 1..OCR_RASTER_EDGE_MAX || bitmap.width.toLong() * bitmap.height > OCR_RASTER_AREA_MAX) return false
            val length = bitmap.byteCount
            val pixels = ByteArray(length)
            try {
                bitmap.copyPixelsToBuffer(ByteBuffer.wrap(pixels))
                val region = anonymousRegion(length) { write -> write(pixels, 0, length, 0) }
                sendDerivativeRegion(sink, bitmap.width, bitmap.height, pages, length, region)
            } finally { pixels.fill(0) }
            return true
        } finally {
            if (bitmap.isMutable) bitmap.eraseColor(Color.TRANSPARENT)
            bitmap.recycle()
        }
    }

    /** Receives only decoded pixels. No source bytes, descriptor, or vault path reach this child. */
    private fun recognize(data: Parcel, sink: IBinder): Boolean {
        val region = data.readTypedObject(ParcelFileDescriptor.CREATOR) ?: return false
        val bitmap = try {
            val width = data.readInt()
            val height = data.readInt()
            if (width !in 1..OCR_RASTER_EDGE_MAX || height !in 1..OCR_RASTER_EDGE_MAX ||
                width.toLong() * height > OCR_RASTER_AREA_MAX) return false
            // The decoder made this region. It is read, never mapped, so a short region fails here.
            val pixels = readRegion(region, width * height * 4, (OCR_RASTER_AREA_MAX * 4).toInt()) ?: return false
            try {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { it.copyPixelsFromBuffer(ByteBuffer.wrap(pixels)) }
            } finally { pixels.fill(0) }
        } finally { region.close() }
        try {
            val ocr = engine ?: AndroidOfflineOcr(this).also { engine = it }
            val text = ocr.recognize(bitmap) ?: return false
            try {
                val length = utf8End(text, OCR_RESULT_MAX)
                val prefix = text.copyOf(length)
                try { sendDerivativeResult(sink, 0, 0, if (length < text.size) 1 else 0, prefix) } finally { prefix.fill(0) }
            } finally { text.fill(0) }
            return true
        } finally {
            bitmap.eraseColor(Color.TRANSPARENT)
            bitmap.recycle()
        }
    }

    private fun remoteMedia(source: RemoteSource, contentType: String): PickedMedia =
        PickedMedia(-1, true, source.size, contentType, null, null, Uri.EMPTY, {})

    override fun onBind(intent: Intent): IBinder = endpoint

    override fun onDestroy() {
        runCatching { document?.close() }
        proxyThread?.quitSafely()
        engine?.close()
        super.onDestroy()
    }
}

/**
 * The isolated decoder. It runs as its own UID with no app storage, network
 * permission, or system media services. In-process image codecs, the JPEG XL
 * decoder, PDF rasterization, and embedded artwork decoding run here.
 */
class AndroidDerivativeService : DerivativeChildService() {
    override val operations: Set<Int> = setOf(DERIVATIVE_DECODE, DERIVATIVE_RENDER, DERIVATIVE_DECODE_ARTWORK)
}

/**
 * Video frames, audio waveforms, embedded artwork extraction, and HEIF/AVIF
 * stills. Their platform decoders call the system media services, which an
 * isolated process cannot reach. The services parse and decode in their own
 * sandboxed system processes; this child receives frames, samples, and the
 * still-encoded artwork, which it returns for the isolated decoder. On API 29
 * it also rasterizes PDF pages for recognition, because an isolated process
 * there has no anonymous descriptor that `PdfRenderer` accepts.
 */
class AndroidPlatformMediaService : DerivativeChildService() {
    override val operations: Set<Int> = setOf(DERIVATIVE_DECODE, DERIVATIVE_RENDER, DERIVATIVE_EXTRACT_ARTWORK)
}

/**
 * Tesseract needs its models at a file path, and an isolated process has no
 * readable or writable app directory. Recognition therefore runs in this
 * separate app-UID process. It receives only decoded pixels in shared memory,
 * and it never opens a network connection.
 */
class AndroidOcrService : DerivativeChildService() {
    override val operations: Set<Int> = setOf(DERIVATIVE_OCR)
}
