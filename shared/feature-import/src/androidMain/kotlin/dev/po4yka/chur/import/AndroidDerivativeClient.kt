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
import java.io.DataInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
                        val coroutine = currentCoroutineContext()
                        val started = System.nanoTime()
                        fun stopped(): Boolean {
                            coroutine.ensureActive()
                            return cancelRequested() || System.nanoTime() - started >= 30_000_000_000L
                        }
                        val connected = CountDownLatch(1)
                        val dead = CountDownLatch(1)
                        val endpoint = AtomicReference<IBinder?>()
                        val connection = object : ServiceConnection {
                            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                                endpoint.set(service)
                                runCatching { service.linkToDeath({ dead.countDown() }, 0) }
                                    .onFailure { dead.countDown() }
                                connected.countDown()
                            }
                            override fun onServiceDisconnected(name: ComponentName) { dead.countDown() }
                            override fun onNullBinding(name: ComponentName) { connected.countDown() }
                            override fun onBindingDied(name: ComponentName) { dead.countDown(); connected.countDown() }
                        }
                        var bound = false
                        var childPid = 0
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
                            if (stopped()) return@withLock null
                            val identity = Parcel.obtain()
                            val identityReply = Parcel.obtain()
                            try {
                                identity.writeInterfaceToken(DERIVATIVE_INTERFACE)
                                if (!service.transact(DERIVATIVE_PID, identity, identityReply, 0)) return@withLock null
                                childPid = identityReply.readInt()
                                check(childPid > 0 && childPid != Process.myPid())
                            } finally { identity.recycle(); identityReply.recycle() }
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
                        } finally {
                            // The private process owns the native operation. Kill it even
                            // after success so no codec/source/plaintext outlives this call.
                            // Remove the binding first: the system must not restart a
                            // killed codec service while an auto-create binding remains.
                            try {
                                if (bound) context.unbindService(connection)
                                endpoint.get()?.let { service ->
                                    if (childPid > 0 && childPid != Process.myPid() && service.isBinderAlive) {
                                        // A parent-side SIGKILL cannot wait behind the native
                                        // decode or the child's Binder transaction queue.
                                        Process.killProcess(childPid)
                                    }
                                    while (childPid > 0 && service.isBinderAlive && !dead.await(50, TimeUnit.MILLISECONDS)) {
                                        // Await actual child death before closing source FDs.
                                    }
                                }
                                pipe?.forEach { runCatching { it.close() } }
                                source?.let { runCatching { it.close() } }
                            } catch (failure: Throwable) {
                                // A cleanup failure prevents delivery to the importer,
                                // so no caller can take ownership of this final array.
                                resultBytes?.fill(0)
                                throw failure
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

    companion object {
        private val decoderLock = Mutex()
    }
}
