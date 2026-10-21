package dev.po4yka.chur.android

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import dev.po4yka.chur.app.IncomingShareSource
import dev.po4yka.chur.imports.AndroidMediaCodec
import dev.po4yka.chur.imports.PickedMedia
import dev.po4yka.chur.imports.normalizedFileContentType

/** Read grants and shared text stay in this process until one confirmed import or discard. */
internal class AndroidIncomingShareSource(
    private val context: Context,
    private val payload: IncomingSharePayload,
) : IncomingShareSource {
    override val count: Int = payload.count
    private val uriCount = payload.uris.size
    private val codec = AndroidMediaCodec(context)
    private val lock = Any()
    private var closed = false
    private var textWorker: HandlerThread? = null
    private val textDescriptors = mutableSetOf<ParcelFileDescriptor>()

    // ChurController invokes open on its import worker. No dispatcher handoff
    // may lose a newly opened descriptor to prompt coroutine cancellation.
    override suspend fun open(index: Int, prepare: (Long, Long) -> Boolean): PickedMedia? {
        if (synchronized(lock) { closed } || !prepare(0, 0)) return null
        val uri = synchronized(lock) { payload.uris.getOrNull(index) }
        return if (index in 0 until uriCount && uri != null) {
            val type = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
            val normalized = normalizedFileContentType(type)
            val asFile = !(normalized.startsWith("image/") || normalized.startsWith("video/") || normalized.startsWith("audio/"))
            val media = codec.open(uri, asFile = asFile) ?: return null
            if (synchronized(lock) { closed } || !prepare(0, 0)) {
                media.close()
                return null
            }
            // Preserve the original as a file when its media hint cannot be
            // decoded. Receiving an arbitrary file must not require a codec.
            val opaque = asFile || runCatching { codec.probe(media) }.getOrNull() == null
            media.copy(originalFilename = safeIncomingFilename(media.originalFilename),
                contentTypeHint = normalized, importAsFile = opaque,
                mediaPreviewUnavailable = !asFile && opaque)
        } else if (index == uriCount) {
            openText()
        } else null
    }

    private fun openText(): PickedMedia? = synchronized(lock) {
        val bytes = payload.text ?: return null
        if (closed) return null
        val worker = textWorker ?: HandlerThread("chur-incoming-text").also {
            it.start()
            textWorker = it
        }
        val storage = context.getSystemService(StorageManager::class.java)
        val descriptor = storage.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY,
            object : ProxyFileDescriptorCallback() {
                override fun onGetSize(): Long = bytes.size.toLong()
                override fun onRead(offset: Long, size: Int, data: ByteArray): Int = synchronized(lock) {
                    if (closed) throw ErrnoException("read", OsConstants.EIO)
                    if (offset < 0 || offset > bytes.size || size < 0) throw ErrnoException("read", OsConstants.EINVAL)
                    val length = minOf(size, bytes.size - offset.toInt(), data.size)
                    bytes.copyInto(data, 0, offset.toInt(), offset.toInt() + length)
                    length
                }
                override fun onRelease() = Unit
            },
            Handler(worker.looper),
        )
        textDescriptors += descriptor
        PickedMedia(
            descriptor = descriptor.fd,
            seekable = true,
            knownLength = bytes.size.toLong(),
            contentTypeHint = "text/plain",
            originalFilename = "shared-text.txt",
            captureTimeMs = null,
            platformHandle = null,
            close = {
                synchronized(lock) {
                    if (textDescriptors.remove(descriptor)) descriptor.close()
                }
            },
            importAsFile = true,
        )
    }

    override fun close() = synchronized(lock) {
        if (closed) return
        closed = true
        textDescriptors.forEach { runCatching { it.close() } }
        textDescriptors.clear()
        payload.clear()
        textWorker?.quitSafely()
        textWorker = null
    }

    override fun toString(): String = "AndroidIncomingShareSource(items=$count)"
}

/** Display metadata must not contain paths or invisible control characters. */
internal fun safeIncomingFilename(value: String?): String? = value
    ?.substringAfterLast('/')?.substringAfterLast('\\')
    ?.filterNot { it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' }
    ?.take(255)?.trim()?.takeIf { it.isNotEmpty() && it != "." && it != ".." }
