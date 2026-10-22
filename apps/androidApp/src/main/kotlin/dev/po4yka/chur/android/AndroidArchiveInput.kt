package dev.po4yka.chur.android

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import dev.po4yka.chur.app.ArchiveSource
import dev.po4yka.chur.imports.AndroidProviderPipe
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Copies package bytes, without decrypting them, before native restore takes a credential. */
internal object AndroidArchiveInput {
    private val openSlots = Semaphore(4)
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stallNanos = TimeUnit.SECONDS.toNanos(30)

    suspend fun open(context: Context, uri: Uri): ArchiveSource {
        var completed: ArchiveSource? = null
        try {
            return withContext(Dispatchers.Default) {
                stage(context.applicationContext, uri).also { completed = it }
            }
        } catch (failure: Throwable) {
            // withContext can discard a ready result when its caller was cancelled.
            completed?.close?.invoke()
            throw failure
        }
    }

    private suspend fun stage(context: Context, uri: Uri): ArchiveSource {
        val pending = beginOpen(context, uri)
        var input: AndroidProviderPipe.Input? = null
        var pipe: ParcelFileDescriptor? = null
        var temporary: File? = null
        var local: ParcelFileDescriptor? = null
        val buffer = ByteArray(64 * 1024)
        try {
            val deadline = System.nanoTime() + stallNanos
            while (input == null) {
                currentCoroutineContext().ensureActive()
                input = pending.take()
                if (input == null) {
                    if (System.nanoTime() >= deadline) throw IOException("The archive provider did not open")
                    delay(50)
                }
            }
            val consumer = ParcelFileDescriptor.fromFd(input.descriptor)
            pipe = consumer
            // Input returns an app-owned nonblocking endpoint before its worker
            // starts. fromFd preserves that status without API-30 fcntl calls.
            var lastProgress = System.nanoTime()
            suspend fun read(offset: Int, length: Int): Int {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    try {
                        val count = Os.read(consumer.fileDescriptor, buffer, offset, length)
                        if (count > 0) lastProgress = System.nanoTime()
                        return count
                    } catch (failure: ErrnoException) {
                        if (failure.errno == OsConstants.EINTR) continue
                        if (failure.errno != OsConstants.EAGAIN) throw failure
                        if (System.nanoTime() - lastProgress >= stallNanos) throw IOException("The archive provider stalled")
                        val ready = StructPollfd().apply {
                            fd = consumer.fileDescriptor
                            events = OsConstants.POLLIN.toShort()
                        }
                        try { Os.poll(arrayOf(ready), 50) } catch (poll: ErrnoException) {
                            if (poll.errno != OsConstants.EINTR) throw poll
                        }
                    }
                }
            }
            // BACKUP_FORMAT_V1 §2.3 and native MAGIC_BACKUP. Reject ordinary
            // files before any disk write; this marker is not authentication.
            val magic = "CHURBAK1".encodeToByteArray()
            var prefix = 0
            while (prefix < magic.size) {
                val count = read(prefix, magic.size - prefix)
                if (count == 0) throw IOException("The file is shorter than an archive marker")
                prefix += count
            }
            if (magic.indices.any { buffer[it] != magic[it] }) throw IOException("The file is not a native archive")
            // Staged package bytes remain untrusted until native restore verifies them.
            val file = File.createTempFile("archive-input-", ".churbak", context.cacheDir)
            temporary = file
            FileOutputStream(file).use { output ->
                output.write(buffer, 0, prefix)
                buffer.fill(0)
                while (true) {
                    val count = read(0, buffer.size)
                    if (count == 0) break
                    output.write(buffer, 0, count)
                    buffer.fill(0)
                }
            }
            currentCoroutineContext().ensureActive()
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            local = descriptor
            val closed = AtomicBoolean()
            val result = ArchiveSource(descriptor.fd) {
                if (closed.compareAndSet(false, true)) cleanup.launch {
                    runCatching { descriptor.close() }
                    file.delete()
                }
            }
            local = null
            temporary = null
            return result
        } finally {
            buffer.fill(0)
            pending.close()
            runCatching { pipe?.close() }
            runCatching { input?.close() }
            runCatching { local?.close() }
            temporary?.delete()
        }
    }

    /** This cell never stores a credential, coroutine continuation, or UI callback. */
    private class PendingOpen : Closeable {
        private var closed = false
        private var result: AndroidProviderPipe.Input? = null
        private var failed = false

        @Synchronized fun deliver(input: AndroidProviderPipe.Input) {
            if (closed) input.close() else result = input
        }
        @Synchronized fun fail() { failed = true }
        @Synchronized fun take(): AndroidProviderPipe.Input? {
            if (failed) throw IOException("The archive provider could not be opened")
            return result.also { result = null }
        }
        @Synchronized override fun close() {
            closed = true
            result?.close()
            result = null
        }
    }

    private fun beginOpen(context: Context, uri: Uri): PendingOpen {
        if (!openSlots.tryAcquire()) throw IOException("Too many archive provider requests")
        val pending = PendingOpen()
        try {
            Thread({
                var provider: ParcelFileDescriptor? = null
                try {
                    provider = context.contentResolver.openFileDescriptor(uri, "r")
                        ?: throw IOException("The archive provider is unavailable")
                    val input = AndroidProviderPipe.input(provider, seekable = false)
                    provider = null // The broker now owns every foreign-descriptor operation.
                    pending.deliver(input)
                } catch (_: Exception) {
                    pending.fail()
                } finally {
                    // A failed open/bridge may block in close. Only this keyless worker waits.
                    try { provider?.close() } finally { openSlots.release() }
                }
            }, "chur-archive-open").apply { isDaemon = true }.start()
        } catch (failure: Throwable) {
            openSlots.release()
            throw failure
        }
        return pending
    }
}
