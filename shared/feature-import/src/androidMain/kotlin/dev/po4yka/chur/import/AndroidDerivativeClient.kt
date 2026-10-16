package dev.po4yka.chur.imports

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import dev.po4yka.chur.ffi.StreamKind
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.DataInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Owns the child and all IPC descriptors until the child is dead. */
internal class AndroidDerivativeClient(private val context: Context) {
    suspend fun derive(
        media: PickedMedia,
        probe: ProbedMedia,
        kind: StreamKind,
        cancelRequested: () -> Boolean,
    ): Derivative? {
        var resultBytes: ByteArray? = null
        return try {
            val result = withTimeoutOrNull(30_000) {
                withContext(Dispatchers.IO) {
                    // A child belongs to exactly one operation. A concurrent import must
                    // never stop another operation's decoder.
                    decoderLock.withLock {
                        failedDecoder?.let { throw DecoderCleanupException("Previous decoder shutdown is unconfirmed", it.failure) }
                        val coroutine = currentCoroutineContext()
                        val started = System.nanoTime()
                        fun stopped(): Boolean {
                            coroutine.ensureActive()
                            return cancelRequested() || System.nanoTime() - started >= 30_000_000_000L
                        }
                        val connected = CountDownLatch(1)
                        val endpoint = AtomicReference<IBinder?>()
                        val connection = object : ServiceConnection {
                            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                                endpoint.set(service)
                                connected.countDown()
                            }
                            override fun onServiceDisconnected(name: ComponentName) {}
                            override fun onNullBinding(name: ComponentName) { connected.countDown() }
                            override fun onBindingDied(name: ComponentName) { connected.countDown() }
                        }
                        var bound = false
                        var child: ChildIdentity? = null
                        var originalFailure: Throwable? = null
                        var source: ParcelFileDescriptor? = null
                        var pipe: Array<ParcelFileDescriptor>? = null
                        try {
                            if (stopped()) return@withLock null
                            bound = context.bindService(
                                Intent(context, AndroidDerivativeService::class.java), connection, Context.BIND_AUTO_CREATE,
                            )
                            if (!bound) return@withLock null
                            while (!connected.await(50, TimeUnit.MILLISECONDS)) {
                                if (stopped()) return@withLock null
                            }
                            val service = endpoint.get() ?: return@withLock null
                            child = identify(service)
                            if (child == null || child.hasExited()) return@withLock null
                            if (stopped()) return@withLock null
                            // The original import is committed before this duplicate is
                            // made. Its reader no longer uses the source position.
                            source = ParcelFileDescriptor.fromFd(media.descriptor)
                            pipe = ParcelFileDescriptor.createPipe()
                            val request = Parcel.obtain()
                            try {
                                request.writeInterfaceToken(DERIVATIVE_INTERFACE)
                                request.writeTypedObject(source, 0)
                                request.writeTypedObject(pipe[1], 0)
                                request.writeString(media.platformHandle.toString())
                                request.writeInt(probe.mediaClass)
                                request.writeInt(probe.width)
                                request.writeInt(probe.height)
                                request.writeLong(probe.durationMs)
                                request.writeString(probe.contentType)
                                request.writeInt(kind.code)
                                if (!service.transact(DERIVATIVE_DECODE, request, null, IBinder.FLAG_ONEWAY)) {
                                    return@withLock null
                                }
                            } finally {
                                request.recycle()
                            }
                            pipe[1].close()
                            val descriptor = pipe[0].fileDescriptor
                            val flags = Os.fcntlInt(descriptor, OsConstants.F_GETFL, 0)
                            Os.fcntlInt(descriptor, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
                            val poll = StructPollfd().apply {
                                fd = descriptor
                                events = (OsConstants.POLLIN or OsConstants.POLLHUP).toShort()
                            }
                            ClearingByteArrayOutputStream().use { bytes ->
                                val buffer = ByteArray(64 * 1024)
                                try {
                                    while (!stopped()) {
                                        Os.poll(arrayOf(poll), 50)
                                        val count = try { Os.read(descriptor, buffer, 0, buffer.size) }
                                        catch (failure: ErrnoException) {
                                            if (failure.errno == OsConstants.EAGAIN) continue else throw failure
                                        }
                                        if (count == 0) {
                                            val received = bytes.toByteArray()
                                            try {
                                                if (received.size < 12) return@withLock null
                                                val input = DataInputStream(ByteArrayInputStream(received))
                                                val width = input.readInt()
                                                val height = input.readInt()
                                                val length = input.readInt()
                                                if (length < 0 || length != received.size - 12) return@withLock null
                                                val encoded = ByteArray(length)
                                                var transferred = false
                                                try {
                                                    input.readFully(encoded)
                                                    val result = Derivative(kind, encoded, width, height)
                                                    resultBytes = encoded
                                                    transferred = true
                                                    return@withLock result
                                                } finally { if (!transferred) encoded.fill(0) }
                                            } finally { received.fill(0) }
                                        }
                                        if (bytes.size() + count > DERIVATIVE_RESULT_MAX + 12) return@withLock null
                                        bytes.write(buffer, 0, count)
                                    }
                                    null
                                } finally { buffer.fill(0) }
                            }
                        } catch (failure: Throwable) {
                            originalFailure = failure
                            throw failure
                        } finally {
                            // Cleanup is blocking IO and does not consult coroutine cancellation.
                            // A cancelled bind still owns its late connection and PID handshake.
                            val deadline = System.nanoTime() + 5_000_000_000L
                            var cleanupFailure: Throwable? = null
                            fun cleanupStep(action: () -> Unit) {
                                try { action() } catch (failure: Throwable) {
                                    cleanupFailure?.addSuppressed(failure) ?: run { cleanupFailure = failure }
                                }
                            }
                            cleanupStep {
                                if (bound && child == null) {
                                    if (!connected.await(maxOf(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                                        throw IOException("Decoder connection did not complete during shutdown")
                                    }
                                    endpoint.get()?.let { child = identify(it) }
                                }
                            }
                            // An unbind failure must not skip killing the already owned child.
                            cleanupStep { if (bound) context.unbindService(connection) }
                            var exited = child == null && source == null && cleanupFailure == null
                            cleanupStep {
                                child?.let { it.terminate(deadline); exited = true }
                            }
                            if (exited) {
                                pipe?.forEach { descriptor -> cleanupStep { descriptor.close() } }
                                cleanupStep { source?.close() }
                            }
                            cleanupFailure?.let { failure ->
                                resultBytes?.fill(0)
                                val fatal = DecoderCleanupException("Decoder shutdown could not be confirmed", failure)
                                // Keep at most one unresolved owner. No later operation may bind
                                // to this process or release its duplicated source descriptors.
                                failedDecoder = FailedDecoder(fatal,
                                    listOfNotNull<Any>(context, connection, endpoint.get(),
                                        source.takeUnless { exited }, pipe.takeUnless { exited }))
                                val original = originalFailure
                                if (original is CancellationException && original !is TimeoutCancellationException) {
                                    original.addSuppressed(fatal)
                                } else {
                                    // Our own withTimeoutOrNull must not convert failed shutdown
                                    // into an ordinary missing preview after its deadline.
                                    original?.let { fatal.addSuppressed(it) }
                                    throw fatal
                                }
                            }
                        }
                    }
                }
            }
            // Dispatch back from IO is cancellable. A timeout can discard a
            // decoded result before the importer can take ownership of it.
            if (result == null) resultBytes?.fill(0)
            result
        } catch (failure: Throwable) {
            resultBytes?.fill(0)
            throw failure
        }
    }

    private fun identify(service: IBinder): ChildIdentity? {
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            request.writeInterfaceToken(DERIVATIVE_INTERFACE)
            if (!service.transact(DERIVATIVE_PID, request, reply, 0)) throw IOException("Decoder identity was unavailable")
            val pid = reply.readInt()
            check(pid > 0 && pid != Process.myPid())
            return kernelTask(pid)?.let { ChildIdentity(pid, it.started) }
        } finally { request.recycle(); reply.recycle() }
    }

    private data class KernelTask(val state: Char, val started: Long)

    private data class ChildIdentity(val pid: Int, val started: Long) {
        fun hasExited(): Boolean {
            val task = kernelTask(pid) ?: return true
            return task.started != started || task.state == 'Z' || task.state == 'X'
        }

        fun terminate(deadline: Long) {
            if (hasExited()) return
            // This app has only its main process and one private decoder process.
            // decoderLock excludes another operation until cleanup completes; a
            // failed unbind can restart only this operation's owned service.
            // starttime detects stale PIDs, but is not an atomic process handle.
            // Revisit numeric signalling if another app-UID process path is added.
            try { Os.kill(pid, OsConstants.SIGKILL) } catch (failure: ErrnoException) {
                if (failure.errno != OsConstants.ESRCH) throw failure
            }
            while (!hasExited()) {
                if (System.nanoTime() >= deadline) throw IOException("Decoder task did not exit after SIGKILL")
                Thread.sleep(10)
            }
        }
    }

    private class FailedDecoder(val failure: DecoderCleanupException, val retainedOwner: List<Any>)

    companion object {
        private val decoderLock = Mutex()
        // Accessed only while decoderLock is held. An uncertain owner forbids reuse.
        private var failedDecoder: FailedDecoder? = null

        private fun kernelTask(pid: Int): KernelTask? {
            val stat = try { File("/proc/$pid/stat").readText() } catch (failure: IOException) {
                try { Os.kill(pid, 0) } catch (probe: ErrnoException) {
                    if (probe.errno == OsConstants.ESRCH) return null
                    failure.addSuppressed(probe)
                }
                throw failure
            }
            // comm can contain spaces and parentheses. starttime is field 22;
            // the fields after the final ')' start with state (field 3).
            val fields = stat.substring(stat.lastIndexOf(')') + 1).trim().split(Regex("\\s+"))
            return KernelTask(fields[0].single(), fields[19].toLong())
        }
    }
}
