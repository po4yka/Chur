package dev.po4yka.chur.imports

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The provider side has no vault, password, callback, or native operation handle.
 * A blocked provider retains one worker and at most one 64 KiB application buffer.
 * Native workers only see a pipe, whose waits are cancellable.
 */
object AndroidProviderPipe {
    // External archives accept at most 32 sources. There is no task queue.
    private val inputSlots = Semaphore(32)
    private val outputSlots = Semaphore(4)
    private const val BUFFER_SIZE = 64 * 1024

    class Input internal constructor(
        private val provider: ParcelFileDescriptor,
        private val seekable: Boolean,
    ) : Closeable {
        private val pipe = nonblockingPipe()
        private val stopped = AtomicBoolean()
        private val released = CountDownLatch(1)
        val descriptor: Int get() = pipe[0].fd

        internal fun start() {
            if (!inputSlots.tryAcquire()) {
                pipe.forEach { it.close() }
                throw IOException("Too many provider input workers")
            }
            Thread({
                val bytes = ByteArray(BUFFER_SIZE)
                try {
                    var offset = 0L
                    while (!stopped.get()) {
                        val count = if (seekable) Os.pread(provider.fileDescriptor, bytes, 0, bytes.size, offset)
                            else Os.read(provider.fileDescriptor, bytes, 0, bytes.size)
                        if (stopped.get()) break
                        if (count == 0) {
                            // Only a confirmed provider EOF may become a native EOF.
                            pipe[1].close()
                            break
                        }
                        writePipe(pipe[1], bytes, count, stopped)
                        offset += count
                        bytes.fill(0)
                    }
                } catch (_: Exception) {
                    // Keep the writer open on an I/O failure. Native's stalled-read
                    // timeout must fail and roll back, never commit a truncated import.
                } finally {
                    bytes.fill(0)
                    // Probes and preview readers still use the original descriptor.
                    // Their owner releases it only after native operation teardown.
                    released.await()
                    try { pipe[1].close() } finally {
                        try { provider.close() } finally { inputSlots.release() }
                    }
                }
            }, "chur-provider-input").apply { isDaemon = true }.start()
        }

        override fun close() {
            if (stopped.compareAndSet(false, true)) {
                try { pipe[0].close() } finally { released.countDown() }
            }
        }
    }

    class Output internal constructor(
        private val provider: ParcelFileDescriptor,
        private val pipe: Array<ParcelFileDescriptor>,
    ) : Closeable {
        private val stopped = AtomicBoolean()
        private val nativeClosed = AtomicBoolean()
        private val done = CountDownLatch(1)
        private val failure = AtomicReference<Exception?>()
        val descriptor: Int get() = pipe[1].fd

        internal fun start() {
            Thread({
                val bytes = ByteArray(BUFFER_SIZE)
                try {
                    while (!stopped.get()) {
                        val count = readPipe(pipe[0], bytes, stopped)
                        if (count == 0) break
                        var offset = 0
                        while (offset < count && !stopped.get()) {
                            val written = Os.write(provider.fileDescriptor, bytes, offset, count - offset)
                            if (written <= 0) throw IOException("Provider output made no progress")
                            offset += written
                        }
                        bytes.fill(0)
                    }
                } catch (caught: Exception) {
                    failure.set(caught)
                } finally {
                    bytes.fill(0)
                    try { pipe[0].close() } finally {
                        // A provider close may also block. It stays on this keyless worker.
                        try { provider.close() } catch (caught: Exception) { failure.compareAndSet(null, caught) }
                        finally { outputSlots.release(); done.countDown() }
                    }
                }
            }, "chur-provider-output").apply { isDaemon = true }.start()
        }

        /** Copies an already complete encrypted package from app-private storage. */
        fun copyCiphertext(source: ParcelFileDescriptor) {
            val bytes = ByteArray(BUFFER_SIZE)
            try {
                while (!stopped.get()) {
                    val count = Os.read(source.fileDescriptor, bytes, 0, bytes.size)
                    if (count == 0) return
                    writePipe(pipe[1], bytes, count, stopped)
                    bytes.fill(0)
                }
                throw IOException("Provider output cancelled")
            } finally { bytes.fill(0) }
        }

        /** Called only after the native operation has released its duplicate. */
        fun finish() {
            closeNative()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!done.await(50, TimeUnit.MILLISECONDS)) {
                if (stopped.get() || System.nanoTime() >= deadline) {
                    stopped.set(true)
                    throw IOException("Provider output did not finish")
                }
            }
            if (stopped.get()) throw IOException("Provider output cancelled")
            failure.get()?.let { throw IOException("Provider output failed", it) }
        }

        /** Lock only signals. It never joins a worker or closes a foreign descriptor. */
        fun cancel() { stopped.set(true) }

        override fun close() { cancel(); closeNative() }

        private fun closeNative() {
            if (nativeClosed.compareAndSet(false, true)) pipe[1].close()
        }
    }

    /** Takes provider ownership after a successful return. */
    fun input(provider: ParcelFileDescriptor, seekable: Boolean): Input = Input(provider, seekable).also { it.start() }

    /** Takes provider ownership after a successful return. */
    fun output(provider: ParcelFileDescriptor): Output = output { provider }

    /** Reserve a worker before the caller opens a foreign descriptor. The opener is not retained. */
    fun output(openProvider: () -> ParcelFileDescriptor): Output {
        if (!outputSlots.tryAcquire()) throw IOException("Too many provider output workers")
        // Allocate local descriptors before acquiring a foreign handle. An EMFILE
        // failure must not leave a provider FD with no worker to close it.
        val pipe = try { nonblockingPipe() }
        catch (failure: Throwable) { outputSlots.release(); throw failure }
        val provider = try { openProvider() } catch (failure: Throwable) {
            try { pipe.forEach { it.close() } } finally { outputSlots.release() }
            throw failure
        }
        return Output(provider, pipe).also { it.start() }
    }

    // Os.fcntlInt requires API 30. Reopen only our own FIFO endpoints with the
    // API 21 open call so both ends are nonblocking before they leave the factory.
    private fun nonblockingPipe(): Array<ParcelFileDescriptor> {
        val original = ParcelFileDescriptor.createPipe()
        var reader: ParcelFileDescriptor? = null
        try {
            reader = reopenPipe(original[0], OsConstants.O_RDONLY)
            val writer = reopenPipe(original[1], OsConstants.O_WRONLY)
            return arrayOf(reader, writer)
        } catch (failure: Throwable) {
            reader?.close()
            throw failure
        } finally { original.forEach { it.close() } }
    }

    private fun reopenPipe(handle: ParcelFileDescriptor, mode: Int): ParcelFileDescriptor {
        val raw = Os.open("/proc/self/fd/${handle.fd}", mode or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
        return try { ParcelFileDescriptor.dup(raw) } finally { Os.close(raw) }
    }

    private fun readPipe(handle: ParcelFileDescriptor, into: ByteArray, stopped: AtomicBoolean): Int {
        while (!stopped.get()) {
            try { return Os.read(handle.fileDescriptor, into, 0, into.size) }
            catch (failure: ErrnoException) { if (failure.errno != OsConstants.EAGAIN) throw failure }
            waitPipe(handle, OsConstants.POLLIN)
        }
        throw IOException("Provider transfer cancelled")
    }

    private fun writePipe(handle: ParcelFileDescriptor, bytes: ByteArray, count: Int, stopped: AtomicBoolean) {
        var offset = 0
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (offset < count && !stopped.get()) {
            try {
                val written = Os.write(handle.fileDescriptor, bytes, offset, count - offset)
                if (written <= 0) throw IOException("Provider pipe made no progress")
                offset += written
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            } catch (failure: ErrnoException) {
                if (failure.errno != OsConstants.EAGAIN) throw failure
                if (System.nanoTime() >= deadline) throw IOException("Provider pipe made no progress", failure)
                waitPipe(handle, OsConstants.POLLOUT)
            }
        }
        if (stopped.get()) throw IOException("Provider transfer cancelled")
    }

    private fun waitPipe(handle: ParcelFileDescriptor, event: Int) {
        val poll = StructPollfd().apply { fd = handle.fileDescriptor; events = event.toShort() }
        Os.poll(arrayOf(poll), 50)
    }
}
