package dev.po4yka.chur.imports

import android.media.MediaDataSource
import android.os.Binder
import android.os.Build
import android.os.CancellationSignal
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch

/**
 * Bytes that the parent serves to a decoder child.
 *
 * A child never receives a descriptor for app data. It asks for bounded
 * ranges over Binder, and the parent reads them from its own source.
 */
interface DerivativeInput {
    /** The exact length, or null when the source cannot tell it. */
    val size: Long?

    /**
     * Reads at most [count] bytes at [offset] into [into] and returns the count,
     * or -1 at the end. A read that cannot progress returns when [stopped] is true.
     */
    fun read(offset: Long, into: ByteArray, count: Int, stopped: () -> Boolean): Int

    /** Interrupts a running provider call. It can run while [read] runs on another thread. */
    fun cancel() {}

    /** Releases resources that the input opened itself. It never closes a caller's source. */
    fun close() {}
}

internal const val DERIVATIVE_INTERFACE = "dev.po4yka.chur.imports.Derivative"

/**
 * Data: entry name, parent callback. The child calls the callback once, so the
 * parent learns the child's UID and PID from Binder. Reply: isolated flag, and
 * whether the named entry in filesDir opened.
 */
internal const val DERIVATIVE_IDENTITY = IBinder.FIRST_CALL_TRANSACTION
/** One-way: sink, reader, probe, kind. The result is one encoded derivative. */
internal const val DERIVATIVE_DECODE = IBinder.FIRST_CALL_TRANSACTION + 1
/** One-way: sink, reader, content type, page. The result is ARGB_8888 pixels of one page. */
internal const val DERIVATIVE_RENDER = IBinder.FIRST_CALL_TRANSACTION + 2
/** One-way: sink, reader. The result is the encoded embedded picture of an audio file. */
internal const val DERIVATIVE_EXTRACT_ARTWORK = IBinder.FIRST_CALL_TRANSACTION + 3
/** One-way: sink, encoded artwork region, its length, kind. The result is one encoded derivative. */
internal const val DERIVATIVE_DECODE_ARTWORK = IBinder.FIRST_CALL_TRANSACTION + 4
/** One-way: sink, pixel region, width, height. The result is UTF-8 text. */
internal const val DERIVATIVE_OCR = IBinder.FIRST_CALL_TRANSACTION + 5

internal const val READER_SIZE = IBinder.FIRST_CALL_TRANSACTION
internal const val READER_READ = IBinder.FIRST_CALL_TRANSACTION + 1
internal const val SINK_RESULT = IBinder.FIRST_CALL_TRANSACTION
internal const val IDENTITY_CALLBACK = IBinder.FIRST_CALL_TRANSACTION

/** One Binder read stays well below the 1 MiB transaction buffer of a process. */
internal const val READ_CHUNK_MAX = 256 * 1024
internal const val DERIVATIVE_RESULT_MAX = 32 * 1024 * 1024
internal const val OCR_RESULT_MAX = 256 * 1024

/** A PDF for recognition is held in anonymous memory or a proxy descriptor; this is its cap. */
internal const val OCR_PDF_MAX_BYTES = 32 * 1024 * 1024
internal const val OCR_RASTER_EDGE_MAX = 2048
internal const val OCR_RASTER_AREA_MAX = 4_194_304L
internal const val OCR_PAGE_MAX = 100

/** A non-seekable source is opened again for a pass that goes back. This bounds re-reads. */
internal const val SOURCE_PASSES_MAX = 16

/**
 * Still-image types whose Android decoder uses the platform media services.
 * An isolated process cannot reach those services, so they decode in the
 * platform media process.
 */
internal val PLATFORM_IMAGE_TYPES = setOf(
    "image/heic", "image/heif", "image/heic-sequence", "image/heif-sequence", "image/avif", "image/avif-sequence",
)

/** Isolated and app-zygote UIDs, `Process.FIRST_APP_ZYGOTE_ISOLATED_UID` to `LAST_ISOLATED_UID`. */
internal fun isIsolatedUid(uid: Int): Boolean = uid % 100_000 in 90_000..99_999

/** The parent end of a child's byte-range reads. Only [allowedUid] can call it. */
internal class DerivativeReader(private val input: DerivativeInput, private val stopped: () -> Boolean) : Binder() {
    private val gate = Object()
    private var closed = false
    private var active = 0

    @Volatile
    var allowedUid: Int = -1

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags)
        if (getCallingUid() != allowedUid) return false
        data.enforceInterface(DERIVATIVE_INTERFACE)
        val out = requireNotNull(reply)
        synchronized(gate) { if (closed) return false; active++ }
        try {
            when (code) {
                READER_SIZE -> out.writeLong(input.size ?: -1)
                READER_READ -> {
                    val offset = data.readLong()
                    val count = data.readInt()
                    if (offset < 0 || count !in 1..READ_CHUNK_MAX) return false
                    val buffer = ByteArray(count)
                    try {
                        val read = input.read(offset, buffer, count) { isClosed() || stopped() }
                        if (read > count || read < -1 || isClosed()) return false
                        out.writeInt(read)
                        // Binder recycles this Parcel without clearing it; see PLAINTEXT_LIFECYCLE.md.
                        if (read > 0) out.writeByteArray(buffer, 0, read)
                    } finally { buffer.fill(0) }
                }
                else -> return false
            }
            return true
        } catch (_: Exception) {
            return false
        } finally {
            synchronized(gate) { active--; gate.notifyAll() }
        }
    }

    private fun isClosed(): Boolean = synchronized(gate) { closed }

    /**
     * Refuses later reads, interrupts a provider call, and waits until [deadline]
     * for a running read to return. Only then does the input close. A read that
     * does not return keeps the input open and makes the cleanup fail.
     */
    fun close(deadline: Long) {
        synchronized(gate) { closed = true }
        input.cancel()
        synchronized(gate) {
            while (active > 0) {
                val waitMs = (deadline - System.nanoTime()) / 1_000_000
                if (waitMs <= 0) throw IOException("A source read did not stop")
                gate.wait(waitMs)
            }
        }
        input.close()
    }
}

/** A child's result: [length] bytes of encoded data or pixels in [region], and three small integers. */
internal class DerivativeResult(val width: Int, val height: Int, val extra: Int, val length: Int, val region: ParcelFileDescriptor)

/** Receives one result from [allowedUid]. The receiver owns and later closes the region. */
internal class DerivativeSink : Binder() {
    val done = CountDownLatch(1)

    @Volatile
    var allowedUid: Int = -1

    private var result: DerivativeResult? = null

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags)
        if (getCallingUid() != allowedUid || code != SINK_RESULT) return false
        data.enforceInterface(DERIVATIVE_INTERFACE)
        val available = data.readInt() == 1
        val width = data.readInt()
        val height = data.readInt()
        val extra = data.readInt()
        val length = data.readInt()
        val region = try { data.readTypedObject(ParcelFileDescriptor.CREATOR) } catch (_: RuntimeException) { null }
        synchronized(this) {
            if (done.count == 0L) { region?.close(); return true }
            result = if (available && region != null && length >= 0) DerivativeResult(width, height, extra, length, region)
                else { region?.close(); null }
            done.countDown()
        }
        return true
    }

    /** Takes the result, or closes a result that arrives after the parent stopped waiting. */
    fun take(): DerivativeResult? = synchronized(this) {
        done.countDown()
        result.also { result = null }
    }
}

/** Records the UID and PID of the process that calls it, as Binder reports them. */
internal class IdentityCallback : Binder() {
    @Volatile
    var caller: Pair<Int, Int>? = null
        private set

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags)
        if (code != IDENTITY_CALLBACK || caller != null) return false
        data.enforceInterface(DERIVATIVE_INTERFACE)
        caller = getCallingUid() to getCallingPid()
        return true
    }
}

/** The child end of [DerivativeReader]. */
internal class RemoteSource(private val reader: IBinder) {
    val size: Long? by lazy {
        call(READER_SIZE, {}) { it.readLong() }.takeIf { it >= 0 }
    }

    fun readAt(position: Long, into: ByteArray, offset: Int, count: Int): Int {
        if (count == 0) return 0
        return call(READER_READ, { it.writeLong(position); it.writeInt(minOf(count, READ_CHUNK_MAX)) }) { reply ->
            val read = reply.readInt()
            if (read > 0) {
                val bytes = reply.createByteArray() ?: throw IOException("Source read failed")
                try {
                    if (bytes.size != read || read > count) throw IOException("Source read failed")
                    bytes.copyInto(into, offset)
                } finally { bytes.fill(0) }
            }
            read
        }
    }

    private fun <T> call(code: Int, write: (Parcel) -> Unit, read: (Parcel) -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DERIVATIVE_INTERFACE)
            write(data)
            if (!reader.transact(code, data, reply, 0)) throw IOException("Source read failed")
            return read(reply)
        } finally { data.recycle(); reply.recycle() }
    }

    /** A sequential stream from the first byte. Each decoder pass opens its own. */
    fun stream(): InputStream = object : InputStream() {
        private var position = 0L
        private val single = ByteArray(1)
        override fun read(): Int = if (read(single, 0, 1) <= 0) -1 else single[0].toInt() and 255
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val count = readAt(position, bytes, offset, length)
            if (count > 0) position += count
            return if (count <= 0) -1 else count
        }
        override fun skip(count: Long): Long {
            if (count <= 0) return 0
            val remaining = size?.let { maxOf(0, it - position) } ?: count
            return minOf(count, remaining).also { position += it }
        }
    }

    /** Media framework classes read through this; the bytes still come over Binder. */
    fun mediaDataSource(): MediaDataSource = object : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
            this@RemoteSource.readAt(position, buffer, offset, size)
        override fun getSize(): Long = this@RemoteSource.size ?: -1
        override fun close() {}
    }
}

/**
 * A read-only descriptor of anonymous memory that holds [length] bytes, which
 * [fill] writes. API 30 and later use a `memfd`; API 29 uses `SharedMemory`,
 * whose Parcel form is one descriptor.
 */
internal fun anonymousRegion(length: Int, fill: (write: (ByteArray, Int, Int, Long) -> Unit) -> Unit): ParcelFileDescriptor {
    if (Build.VERSION.SDK_INT >= 30) {
        val descriptor = Os.memfd_create("chur-derivative", OsConstants.MFD_CLOEXEC)
        val owned = try { ParcelFileDescriptor.dup(descriptor) } finally { Os.close(descriptor) }
        try {
            Os.ftruncate(owned.fileDescriptor, length.toLong())
            fill { bytes, offset, count, position ->
                var written = 0
                while (written < count) {
                    written += Os.pwrite(owned.fileDescriptor, bytes, offset + written, count - written, position + written)
                }
            }
            return owned
        } catch (failure: Throwable) { owned.close(); throw failure }
    }
    val region = SharedMemory.create("chur-derivative", maxOf(1, length))
    try {
        val mapping = region.mapReadWrite()
        try {
            fill { bytes, offset, count, position ->
                mapping.position(position.toInt())
                mapping.put(bytes, offset, count)
            }
        } finally { SharedMemory.unmap(mapping) }
        val parcel = Parcel.obtain()
        try {
            region.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            return parcel.readFileDescriptor() ?: throw IOException("Shared memory has no descriptor")
        } finally { parcel.recycle() }
    } finally { region.close() }
}

/** Sends encoded bytes to the parent's sink in a new region. */
internal fun sendDerivativeResult(sink: IBinder, width: Int, height: Int, extra: Int, bytes: ByteArray) {
    val region = anonymousRegion(bytes.size) { write -> write(bytes, 0, bytes.size, 0) }
    sendDerivativeRegion(sink, width, height, extra, bytes.size, region)
}

/** Sends one region and closes this process's handle. Null [region] means no result. */
internal fun sendDerivativeRegion(sink: IBinder, width: Int, height: Int, extra: Int, length: Int, region: ParcelFileDescriptor?) {
    val data = Parcel.obtain()
    try {
        data.writeInterfaceToken(DERIVATIVE_INTERFACE)
        data.writeInt(if (region != null) 1 else 0)
        data.writeInt(width)
        data.writeInt(height)
        data.writeInt(extra)
        data.writeInt(length)
        data.writeTypedObject(region, 0)
        sink.transact(SINK_RESULT, data, null, 0)
    } finally {
        data.recycle()
        region?.close()
    }
}

internal fun sendUnavailable(sink: IBinder) = sendDerivativeRegion(sink, 0, 0, 0, 0, null)

/**
 * Reads exactly [length] bytes from a region that another process made.
 *
 * The region is never mapped. A region larger than [limit] is refused before
 * any read, and a region that ends early, or that its creator shrinks during
 * the read, gives a short read and no result. Nothing here can fault.
 */
internal fun readRegion(region: ParcelFileDescriptor, length: Int, limit: Int): ByteArray? {
    if (!regionFits(region, length, limit)) return null
    val bytes = ByteArray(length)
    var read = 0
    try {
        while (read < length) {
            val count = Os.pread(region.fileDescriptor, bytes, read, length - read, read.toLong())
            if (count <= 0) { bytes.fill(0); return null }
            read += count
        }
        return bytes
    } catch (_: ErrnoException) {
        bytes.fill(0)
        return null
    } catch (_: java.io.InterruptedIOException) {
        bytes.fill(0)
        return null
    }
}

/**
 * Whether a region can hold [length] bytes within [limit]. A memfd is a regular
 * file with its true size. An API 29 ashmem region reports none, so only the
 * bounded reads of its consumer apply to it.
 */
internal fun regionFits(region: ParcelFileDescriptor, length: Int, limit: Int): Boolean {
    if (length !in 0..limit) return false
    val status = try { Os.fstat(region.fileDescriptor) } catch (_: ErrnoException) { return false }
    return !OsConstants.S_ISREG(status.st_mode) || status.st_size in length.toLong()..limit.toLong()
}

/** Copies the bytes of a result and closes its region. */
internal fun DerivativeResult.takeBytes(limit: Int): ByteArray? =
    try { readRegion(region, length, limit) } finally { region.close() }

/** The end of a UTF-8 prefix of at most [limit] bytes that does not split a code point. */
internal fun utf8End(bytes: ByteArray, limit: Int): Int {
    if (bytes.size <= limit) return bytes.size
    var end = maxOf(0, limit)
    while (end > 0 && bytes[end].toInt() and 0xc0 == 0x80) end--
    return end
}

/**
 * Reads an app-process descriptor for a child. A seekable source uses positional
 * reads, so the importer's own position does not move. A pipe is reopened from
 * its URI for a pass that starts again from an earlier offset, at most
 * [SOURCE_PASSES_MAX] times; [cancel] interrupts that provider call.
 */
internal class DescriptorInput(
    descriptor: Int,
    seekable: Boolean,
    private val reopen: ((CancellationSignal) -> ParcelFileDescriptor?)?,
    private val base: Long = 0,
    private val length: Long? = null,
) : DerivativeInput {
    private val owner: ParcelFileDescriptor? = if (seekable) ParcelFileDescriptor.fromFd(descriptor) else null
    private val cancellation = CancellationSignal()
    private var stream: ParcelFileDescriptor? = null
    private var position = 0L
    private var passes = 0

    override val size: Long? = length ?: owner?.let { source ->
        runCatching { Os.fstat(source.fileDescriptor) }.getOrNull()
            ?.takeIf { OsConstants.S_ISREG(it.st_mode) }?.st_size?.let { maxOf(0, it - base) }
    }

    @Synchronized
    override fun read(offset: Long, into: ByteArray, count: Int, stopped: () -> Boolean): Int {
        val limit = size?.let { minOf(count.toLong(), it - offset) } ?: count.toLong()
        if (limit <= 0) return -1
        val source = owner
        if (source != null) {
            val read = Os.pread(source.fileDescriptor, into, 0, limit.toInt(), base + offset)
            return if (read == 0) -1 else read
        }
        if (stream == null || offset < position) {
            stream?.close()
            stream = null
            if (++passes > SOURCE_PASSES_MAX || stopped()) throw IOException("Source passes exhausted")
            val opened = reopen?.invoke(cancellation) ?: return -1
            stream = opened
            position = 0
            val flags = Os.fcntlInt(opened.fileDescriptor, OsConstants.F_GETFL, 0)
            Os.fcntlInt(opened.fileDescriptor, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
        }
        val opened = requireNotNull(stream)
        while (position < offset) {
            val skipped = readSome(opened, into, minOf(into.size.toLong(), offset - position).toInt(), stopped)
            into.fill(0)
            if (skipped <= 0) return -1
            position += skipped
        }
        val read = readSome(opened, into, limit.toInt(), stopped)
        if (read > 0) position += read
        return if (read <= 0) -1 else read
    }

    private fun readSome(source: ParcelFileDescriptor, into: ByteArray, count: Int, stopped: () -> Boolean): Int {
        val poll = StructPollfd().apply {
            fd = source.fileDescriptor
            events = (OsConstants.POLLIN or OsConstants.POLLHUP).toShort()
        }
        while (true) {
            if (stopped()) throw IOException("Source read stopped")
            Os.poll(arrayOf(poll), 50)
            try {
                return Os.read(source.fileDescriptor, into, 0, count)
            } catch (failure: ErrnoException) {
                if (failure.errno != OsConstants.EAGAIN) throw failure
            }
        }
    }

    override fun cancel() = cancellation.cancel()

    @Synchronized
    override fun close() {
        stream?.close()
        stream = null
        // fromFd duplicated the caller's descriptor; the caller keeps its own.
        owner?.close()
    }
}

