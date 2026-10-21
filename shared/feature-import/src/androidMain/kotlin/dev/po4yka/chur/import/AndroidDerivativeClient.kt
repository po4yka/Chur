package dev.po4yka.chur.imports

import android.app.ActivityManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.RemoteException
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import dev.po4yka.chur.ffi.StreamKind
import java.io.File
import java.io.IOException
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

/**
 * Owns the children, their source readers, and their results until every child is dead.
 *
 * The isolated decoder parses images, PDF pages, and embedded artwork. The
 * platform media child reaches the system media services for video, audio,
 * and HEIF/AVIF stills. The OCR child sees only decoded pixels. No child
 * receives a descriptor: each reads bounded ranges from the parent over Binder.
 */
class AndroidDerivativeClient(
    private val context: Context,
    /** Device tests only: a component that is bound in place of the isolated decoder. */
    private val decoderComponent: ComponentName? = null,
    /** PDF pages render in the platform media child. API 29 needs this; device tests can select it. */
    private val documentsInPlatform: Boolean = Build.VERSION.SDK_INT < 30,
) {
    internal suspend fun derive(
        media: PickedMedia,
        probe: ProbedMedia,
        kind: StreamKind,
        cancelRequested: () -> Boolean,
    ): Derivative? = custody(DERIVE_TIMEOUT_MS, cancelRequested, { it.bytes.fill(0) }) { session ->
        // A Live Photo child sees only the display photo's byte range.
        val photo = media.livePhoto?.displayPhoto
        val uri = media.platformHandle as? Uri
        val reader = session.reader(DescriptorInput(media.descriptor, media.seekable,
            uri?.let { source -> { cancel -> context.contentResolver.openFileDescriptor(source, "r", cancel) } },
            photo?.offset ?: 0, photo?.byteLength))
        val platform = kind == StreamKind.AUDIO_WAVEFORM || probe.mediaClass == MediaBounds.CLASS_VIDEO ||
            probe.mediaClass == MediaBounds.CLASS_AUDIO ||
            (probe.mediaClass == MediaBounds.CLASS_IMAGE && probe.contentType in PLATFORM_IMAGE_TYPES)
        if (probe.mediaClass != MediaBounds.CLASS_IMAGE && !platform) return@custody null
        if (probe.mediaClass == MediaBounds.CLASS_AUDIO && kind != StreamKind.AUDIO_WAVEFORM) {
            // The platform child extracts the encoded picture; only the isolated
            // decoder decodes it.
            val artwork = session.child(ChildKind.PLATFORM)?.request(DERIVATIVE_EXTRACT_ARTWORK, reader) {} ?: return@custody null
            try {
                if (artwork.length !in 1..MediaBounds.AUDIO_ART_ENCODED_MAX_BYTES) return@custody null
                session.child(ChildKind.DECODER)?.request(DERIVATIVE_DECODE_ARTWORK, null) { data ->
                    data.writeTypedObject(artwork.region, 0)
                    data.writeInt(artwork.length)
                    data.writeInt(kind.code)
                }?.toDerivative(kind)
            } finally { artwork.region.close() }
        } else {
            session.child(if (platform) ChildKind.PLATFORM else ChildKind.DECODER)?.request(DERIVATIVE_DECODE, reader) { data ->
                data.writeInt(probe.mediaClass)
                data.writeInt(probe.width)
                data.writeInt(probe.height)
                data.writeLong(probe.durationMs)
                data.writeString(probe.contentType)
                data.writeInt(kind.code)
            }?.toDerivative(kind)
        }
    }

    /**
     * Recognizes text in one image or PDF. The decoder rasterizes each page into
     * shared memory, and the parent forwards only those pixels to the OCR child.
     */
    suspend fun recognize(input: DerivativeInput, contentType: String, cancelRequested: () -> Boolean): AndroidRecognizedText? =
        custody(OCR_TIMEOUT_MS, cancelRequested, { it.bytes.fill(0) }) { session -> recognizePages(session, input, contentType) }

    private fun recognizePages(session: Session, input: DerivativeInput, contentType: String): AndroidRecognizedText? {
        val reader = session.reader(input)
        val pdf = contentType == "application/pdf"
        val platform = contentType in PLATFORM_IMAGE_TYPES || (pdf && documentsInPlatform)
        val decoder = session.child(if (platform) ChildKind.PLATFORM else ChildKind.DECODER) ?: return null
        val ocr = session.child(ChildKind.OCR) ?: return null
        val output = ByteArray(OCR_RESULT_MAX)
        var used = 0
        var completed = 0
        var partial = false
        var pages = 1
        try {
            var number = 0
            while (number < pages) {
                val page = decoder.request(DERIVATIVE_RENDER, reader) { data ->
                    data.writeString(contentType)
                    data.writeInt(number)
                } ?: return null
                val text = try {
                    if (page.width !in 1..OCR_RASTER_EDGE_MAX || page.height !in 1..OCR_RASTER_EDGE_MAX ||
                        page.width.toLong() * page.height > OCR_RASTER_AREA_MAX ||
                        page.length != page.width * page.height * 4 ||
                        !regionFits(page.region, page.length, (OCR_RASTER_AREA_MAX * 4).toInt())) return null
                    if (number == 0) {
                        if (pdf) {
                            if (page.extra <= 0) return null
                            pages = minOf(page.extra, OCR_PAGE_MAX)
                            partial = page.extra > pages
                        } else if (page.extra != 1) return null
                    }
                    val recognized = ocr.request(DERIVATIVE_OCR, null) { data ->
                        // The OCR child reads exactly width * height * 4 bytes and never maps the region.
                        data.writeTypedObject(page.region, 0)
                        data.writeInt(page.width)
                        data.writeInt(page.height)
                    } ?: return null
                    partial = partial || recognized.extra == 1
                    recognized.takeBytes(OCR_RESULT_MAX) ?: return null
                } finally { page.region.close() }
                try {
                    val length = utf8End(text, OCR_RESULT_MAX - used)
                    text.copyInto(output, used, 0, length)
                    used += length
                    partial = partial || length < text.size
                } finally { text.fill(0) }
                completed = number + 1
                number++
                if (!pdf) break
                if (used >= OCR_RESULT_MAX - 4) { partial = true; break }
                output[used++] = '\n'.code.toByte()
            }
            return AndroidRecognizedText(output.copyOf(used), partial, if (pdf) completed else 1)
        } finally { output.fill(0) }
    }

    private fun DerivativeResult.toDerivative(kind: StreamKind): Derivative? {
        val bytes = takeBytes(DERIVATIVE_RESULT_MAX) ?: return null
        return Derivative(kind, bytes, width, height)
    }

    private suspend fun <T : Any> custody(
        timeoutMs: Long,
        cancelRequested: () -> Boolean,
        discard: (T) -> Unit,
        body: (Session) -> T?,
    ): T? {
        var owned: T? = null
        return try {
            val result = withTimeoutOrNull(timeoutMs) {
                withContext(Dispatchers.IO) {
                    // A child belongs to exactly one operation. A concurrent import must
                    // never stop another operation's decoder.
                    decoderLock.withLock {
                        failedDecoder?.let { throw DecoderCleanupException("Previous decoder shutdown is unconfirmed", it.failure) }
                        val coroutine = currentCoroutineContext()
                        val started = System.nanoTime()
                        val session = Session(context, decoderComponent) {
                            coroutine.ensureActive()
                            cancelRequested() || System.nanoTime() - started >= timeoutMs * 1_000_000L
                        }
                        var originalFailure: Throwable? = null
                        try {
                            if (session.stopped()) null else body(session)?.also { owned = it }
                        } catch (failure: Throwable) {
                            originalFailure = failure
                            throw failure
                        } finally {
                            session.close()?.let { failure ->
                                owned?.let(discard)
                                owned = null
                                val fatal = DecoderCleanupException("Decoder shutdown could not be confirmed", failure)
                                // Keep at most one unresolved owner. No later operation may bind
                                // to these processes or release their source readers.
                                failedDecoder = FailedDecoder(fatal, listOf(session))
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
            // decoded result before the caller can take ownership of it.
            if (result == null) owned?.let(discard)
            result
        } catch (failure: Throwable) {
            owned?.let(discard)
            throw failure
        }
    }

    private enum class ChildKind(val service: Class<out Service>, val isolated: Boolean, val process: String) {
        DECODER(AndroidDerivativeService::class.java, true, ":media_decoder"),
        PLATFORM(AndroidPlatformMediaService::class.java, false, ":media_platform"),
        OCR(AndroidOcrService::class.java, false, ":ocr"),
    }

    /** Everything one operation owns. [close] stops the readers first, then ends every child. */
    private class Session(private val context: Context, private val decoder: ComponentName?, private val stop: () -> Boolean) {
        private val readers = mutableListOf<DerivativeReader>()
        private val children = mutableMapOf<ChildKind, Child>()

        fun stopped(): Boolean = stop()

        /** A non-throwing view for Binder threads, which cannot see coroutine cancellation. */
        private val quietStop: () -> Boolean = { try { stop() } catch (_: Exception) { true } }

        fun reader(input: DerivativeInput): DerivativeReader =
            DerivativeReader(input, quietStop).also { readers += it }

        /** Binds and checks one child, or returns null when it is unavailable or the operation stopped. */
        fun child(kind: ChildKind): Child? {
            children[kind]?.let { return it.takeIf { child -> child.ready } }
            val component = if (kind == ChildKind.DECODER && decoder != null) decoder else ComponentName(context, kind.service)
            val child = Child(context, kind, component, ::stopped)
            children[kind] = child
            return child.takeIf { it.start() }
        }

        /** Cleanup is blocking IO and does not consult coroutine cancellation. */
        fun close(): Throwable? {
            val deadline = System.nanoTime() + 5_000_000_000L
            var cleanupFailure: Throwable? = null
            fun cleanupStep(action: () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    cleanupFailure?.addSuppressed(failure) ?: run { cleanupFailure = failure }
                }
            }
            // A closed reader refuses every later range, so no child reads the
            // source after this point, even one whose exit is not yet confirmed.
            readers.forEach { reader -> cleanupStep { reader.close(deadline) } }
            children.values.forEach { child -> cleanupStep { child.terminate(deadline) } }
            return cleanupFailure
        }
    }

    private class Child(
        private val context: Context,
        private val kind: ChildKind,
        private val component: ComponentName,
        private val stopped: () -> Boolean,
    ) {
        private val connected = CountDownLatch(1)
        private val endpoint = AtomicReference<IBinder?>()
        private val died = CountDownLatch(1)
        private val death = IBinder.DeathRecipient { died.countDown() }
        private var bound = false
        private var linked = false
        private var identity: ChildIdentity? = null
        var ready = false
            private set
        private var uid = -1

        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                endpoint.set(service)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {}
            override fun onNullBinding(name: ComponentName) { connected.countDown() }
            override fun onBindingDied(name: ComponentName) { connected.countDown() }
        }

        fun start(): Boolean {
            if (stopped()) return false
            bound = context.bindService(Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE)
            if (!bound) return false
            while (!connected.await(50, TimeUnit.MILLISECONDS)) {
                if (stopped()) return false
            }
            val service = endpoint.get() ?: return false
            ready = identify(service)
            return ready && !stopped()
        }

        /**
         * Links death notice and learns the child's UID and PID from Binder. The
         * isolated decoder must have an isolated UID, report itself isolated, and
         * fail to open the app's filesDir; otherwise no source reaches it. A
         * child that misbehaves fails this operation only.
         */
        private fun identify(service: IBinder): Boolean {
            if (!link(service)) return false
            val (childUid, pid, isolated, opensAppFiles) = ask(service) ?: return false.also { claim(service) }
            if (kind.isolated) {
                if (!isolated || !isIsolatedUid(childUid) || opensAppFiles) return false
            } else {
                if (childUid != Process.myUid() || pid <= 0 || pid == Process.myPid()) return false
                val task = kernelTask(pid) ?: return false
                identity = ChildIdentity(pid, task.started)
            }
            uid = childUid
            return true
        }

        private data class Answer(val uid: Int, val pid: Int, val isolated: Boolean, val opensAppFiles: Boolean)

        /** Null when the child is dead, refuses, or never calls back. */
        private fun ask(service: IBinder): Answer? {
            val callback = IdentityCallback()
            val request = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                request.writeInterfaceToken(DERIVATIVE_INTERFACE)
                request.writeString("")
                request.writeStrongBinder(callback)
                if (!service.transact(DERIVATIVE_IDENTITY, request, reply, 0)) return null
                val (uid, pid) = callback.caller ?: return null
                return Answer(uid, pid, reply.readInt() == 1, reply.readInt() == 1)
            } catch (_: RemoteException) {
                return null
            } catch (_: RuntimeException) {
                return null
            } finally { request.recycle(); reply.recycle() }
        }

        /** A child that is already dead counts as exited. */
        private fun link(service: IBinder): Boolean {
            if (linked) return true
            return try {
                service.linkToDeath(death, 0)
                linked = true
                true
            } catch (_: RemoteException) {
                died.countDown()
                false
            }
        }

        /** Sends one request and waits for its result, the child's death, or a stop. */
        fun request(code: Int, reader: DerivativeReader?, write: (Parcel) -> Unit): DerivativeResult? {
            val service = endpoint.get() ?: return null
            if (!ready || died.count == 0L) return null
            val sink = DerivativeSink().also { it.allowedUid = uid }
            reader?.allowedUid = uid
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken(DERIVATIVE_INTERFACE)
                data.writeStrongBinder(sink)
                reader?.let { data.writeStrongBinder(it) }
                write(data)
                if (!service.transact(code, data, null, IBinder.FLAG_ONEWAY)) return null
            } catch (_: RemoteException) {
                return null
            } finally { data.recycle() }
            return sink.await()
        }

        /** A stop can throw cancellation; a result that arrived is then cleared. */
        private fun DerivativeSink.await(): DerivativeResult? {
            try {
                while (!done.await(50, TimeUnit.MILLISECONDS)) {
                    if (died.count == 0L || stopped()) break
                }
            } catch (failure: Throwable) {
                take()?.region?.close()
                throw failure
            }
            val result = take()
            val stop = try { stopped() } catch (failure: Throwable) { result?.region?.close(); throw failure }
            if (stop) { result?.region?.close(); return null }
            return result
        }

        /** Cleanup needs Binder death for every child and a PID for an app-UID child. */
        private fun claim(service: IBinder) {
            if (!link(service) || kind.isolated || identity != null) return
            // A child that does not answer is still an app-UID process this app can find.
            val pid = ask(service)?.takeIf { it.uid == Process.myUid() }?.pid
                ?: context.getSystemService(ActivityManager::class.java)?.runningAppProcesses.orEmpty()
                    .firstOrNull { it.uid == Process.myUid() && it.processName == context.packageName + kind.process }?.pid
                ?: return
            if (pid <= 0 || pid == Process.myPid()) return
            identity = kernelTask(pid)?.let { ChildIdentity(pid, it.started) }
        }

        fun terminate(deadline: Long) {
            if (bound && endpoint.get() == null) {
                // A cancelled bind still owns its late connection.
                if (!connected.await(maxOf(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                    throw IOException("Decoder connection did not complete during shutdown")
                }
                endpoint.get()?.let(::claim)
            } else endpoint.get()?.let(::claim)
            var failure: Throwable? = null
            // An unbind failure must not skip waiting for the owned child.
            try { if (bound) context.unbindService(connection) } catch (unbind: Throwable) { failure = unbind }
            try {
                if (kind.isolated) {
                    // The system ends an isolated process when its last binding ends.
                    // The app cannot signal another UID, so it waits for Binder death.
                    if (linked && !died.await(maxOf(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                        throw IOException("Decoder did not exit after unbind")
                    }
                } else {
                    val owned = identity
                    if (owned != null) owned.terminate(deadline)
                    else if (linked && !died.await(maxOf(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                        throw IOException("Decoder exit could not be confirmed")
                    }
                }
            } catch (exit: Throwable) {
                failure?.let(exit::addSuppressed)
                throw exit
            }
            failure?.let { throw it }
        }
    }

    private data class KernelTask(val state: Char, val started: Long)

    private data class ChildIdentity(val pid: Int, val started: Long) {
        fun hasExited(): Boolean {
            val task = kernelTask(pid) ?: return true
            return task.started != started || task.state == 'Z' || task.state == 'X'
        }

        fun terminate(deadline: Long) {
            if (hasExited()) return
            // The app-UID children are this app's private platform media and OCR
            // processes. decoderLock excludes another operation until cleanup
            // completes. starttime detects stale PIDs, but is not an atomic
            // process handle. Revisit numeric signalling if another app-UID
            // process path is added.
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
        private const val DERIVE_TIMEOUT_MS = 30_000L
        private const val OCR_TIMEOUT_MS = 120_000L
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

/** Caller clears [bytes] after its encrypted index write. */
data class AndroidRecognizedText(val bytes: ByteArray, val partial: Boolean, val pages: Int)
