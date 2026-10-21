package dev.po4yka.chur.android

import android.content.Context
import android.content.ContextWrapper
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.imports.AndroidDerivativeService
import dev.po4yka.chur.imports.AndroidMediaCodec
import dev.po4yka.chur.imports.AndroidOcrService
import dev.po4yka.chur.imports.MediaBounds
import dev.po4yka.chur.imports.PickedMedia
import dev.po4yka.chur.imports.ProbedMedia
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses a real blocking FIFO, so the child is inside a native source read. */
@RunWith(AndroidJUnit4::class)
class MediaDerivativeIsolationTest {
    @Test
    fun theThirtySecondDeadlineKillsABlockedDecoderAndReleasesItsDescriptors() = runBlocking {
        withBlockedImage { codec, source ->
            val started = System.nanoTime()
            assertNull(codec.deriveBounded(source, imageProbe, StreamKind.THUMBNAIL) { false })
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("deadline elapsed $elapsedMs ms", elapsedMs in 29_000..35_000)
            assertNoDecoderProcess()
        }
        assertHealthyNextImport()
    }

    @Test
    fun userCancellationKillsABlockedDecoderBeforeReturning() = runBlocking {
        withBlockedImage { codec, source ->
            val started = System.nanoTime()
            assertNull(codec.deriveBounded(source, imageProbe, StreamKind.THUMBNAIL) {
                System.nanoTime() - started >= 1_000_000_000
            })
            assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
            assertNoDecoderProcess()
        }
        assertHealthyNextImport()
    }

    @Test
    fun coroutineCancellationWaitsForChildDeathBeforeTheSourceCloses() = runBlocking {
        withBlockedImage { codec, source ->
            val work = async(start = CoroutineStart.UNDISPATCHED) {
                codec.deriveBounded(source, imageProbe, StreamKind.THUMBNAIL) { false }
            }
            delay(1_000)
            work.cancelAndJoin()
            assertNoDecoderProcess()
        }
        assertHealthyNextImport()
    }

    @Test
    fun cancellationBeforeThePidHandshakeStillOwnsAndStopsTheRealChild() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val delayed = DelayedConnectionContext(instrumentation.targetContext)
        withBlockedImage(delayed) { codec, source ->
            val work = async(start = CoroutineStart.UNDISPATCHED) {
                codec.deriveBounded(source, imageProbe, StreamKind.THUMBNAIL) { false }
            }
            try {
                assertTrue("the actual service must bind before cancellation", withContext(Dispatchers.IO) {
                    delayed.connected.await(5, TimeUnit.SECONDS)
                })
                work.cancel()
                // Deliver the real Binder only after cancellation is already requested.
                instrumentation.runOnMainSync { delayed.deliverConnection() }
                work.join()
                assertNoDecoderProcess()
            } finally {
                instrumentation.runOnMainSync { delayed.deliverConnection() }
                work.cancelAndJoin()
            }
        }
        assertHealthyNextImport()
    }

    @Test
    fun theDecoderIsAnIsolatedProcessThatCannotOpenAppFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val canary = File(context.filesDir, "decoder-isolation-canary-${System.nanoTime()}")
        canary.writeText("private")
        try {
            // The app-UID OCR child opens the same file, so the probe itself works.
            var appChild = 0
            withChild(context, AndroidOcrService::class.java) { identity ->
                val (uid, pid, _, opened) = identity(canary.name)
                assertEquals(Process.myUid(), uid)
                assertEquals(1, opened)
                appChild = pid
            }
            // An app-UID child stays cached after unbind; the client kills it the same way.
            Process.killProcess(appChild)
            withChild(context, AndroidDerivativeService::class.java) { identity ->
                val (uid, pid, isolated, opened) = identity(canary.name)
                assertEquals("the decoder must be an isolated process", 1, isolated)
                assertNotEquals("the decoder must not run as the app UID", Process.myUid(), uid)
                assertEquals("the decoder must not open a file in filesDir", 0, opened)
                assertEquals("the decoder must not list filesDir", 0, identity("")[3])
                val user = decoderProcesses().single { it.first == pid }.second
                assertTrue("decoder user $user", user.matches(Regex("u\\d+_i\\d+")))
            }
            val until = System.nanoTime() + 5_000_000_000L
            while (decoderProcesses().isNotEmpty() && System.nanoTime() < until) Thread.sleep(50)
            assertNoDecoderProcess()
        } finally { canary.delete() }
    }

    /** Binds one child directly and asks it which UID it has and whether it opens [name]. */
    private fun withChild(context: Context, service: Class<*>, body: ((String) -> List<Int>) -> Unit) {
        val connected = CountDownLatch(1)
        var endpoint: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { endpoint = binder; connected.countDown() }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        assertTrue(context.bindService(Intent(context, service), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue(connected.await(10, TimeUnit.SECONDS))
            body { name ->
                val request = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    // The child calls this back, so Binder, not the child, names its UID and PID.
                    var caller = -1 to -1
                    val callback = object : Binder() {
                        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                            if (code != IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
                            caller = getCallingUid() to getCallingPid()
                            return true
                        }
                    }
                    request.writeInterfaceToken("dev.po4yka.chur.imports.Derivative")
                    request.writeString(name)
                    request.writeStrongBinder(callback)
                    assertTrue(requireNotNull(endpoint).transact(IBinder.FIRST_CALL_TRANSACTION, request, reply, 0))
                    listOf(caller.first, caller.second, reply.readInt(), reply.readInt())
                } finally { request.recycle(); reply.recycle() }
            }
        } finally { context.unbindService(connection) }
    }

    private class DelayedConnectionContext(base: Context) : ContextWrapper(base) {
        val connected = CountDownLatch(1)
        private var actual: ServiceConnection? = null
        private var deliver: (() -> Unit)? = null
        override fun getApplicationContext(): Context = this
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            val forwarding = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    deliver = { connection.onServiceConnected(name, service) }
                    connected.countDown()
                }
                override fun onServiceDisconnected(name: ComponentName) = connection.onServiceDisconnected(name)
                override fun onBindingDied(name: ComponentName) = connection.onBindingDied(name)
                override fun onNullBinding(name: ComponentName) = connection.onNullBinding(name)
            }
            actual = forwarding
            return super.bindService(intent, forwarding, flags)
        }
        override fun unbindService(connection: ServiceConnection) {
            super.unbindService(requireNotNull(actual))
        }
        fun deliverConnection() {
            val callback = deliver
            deliver = null
            callback?.invoke()
        }
    }

    private suspend fun withBlockedImage(
        context: Context = InstrumentationRegistry.getInstrumentation().targetContext,
        body: suspend (AndroidMediaCodec, PickedMedia) -> Unit,
    ) {
        val file = File(context.cacheDir, "blocked-codec-${System.nanoTime()}.fifo")
        Os.mkfifo(file.absolutePath, 384)
        // Holding both ends makes reopening the FIFO succeed, but no pixels
        // ever arrive. Native EXIF/decode must be stopped by process death.
        val descriptor = Os.open(file.absolutePath, OsConstants.O_RDWR or OsConstants.O_NONBLOCK, 0)
        val owner = try { ParcelFileDescriptor.dup(descriptor) } finally { Os.close(descriptor) }
        val source = PickedMedia(owner.fd, false, null, "image/jpeg", null, null,
            Uri.fromFile(file), { owner.close() })
        try {
            body(AndroidMediaCodec(context), source)
            assertTrue("the codec does not close its caller's source", owner.fileDescriptor.valid())
        } finally { source.close(); file.delete() }
    }

    private suspend fun assertHealthyNextImport() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "export-scratch/healthy-codec-${System.nanoTime()}.jpg")
        requireNotNull(file.parentFile).mkdirs()
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        bitmap.recycle()
        val codec = AndroidMediaCodec(context)
        val uri = leasedExportUri(context, file)
        val source = requireNotNull(codec.open(uri))
        try {
            assertEquals("image/jpeg", source.contentTypeHint)
            val probe = requireNotNull(codec.probe(source))
            assertEquals(MediaBounds.CLASS_IMAGE, probe.mediaClass)
            val result = requireNotNull(codec.deriveBounded(source, probe, StreamKind.THUMBNAIL) { false })
            val decoded = requireNotNull(BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size))
            try { assertEquals(80, decoded.width); assertEquals(40, decoded.height) }
            finally { decoded.recycle() }
            assertNoDecoderProcess()
        } finally { source.close(); file.delete() }
    }

    private fun assertNoDecoderProcess() {
        val livePids = decoderProcesses().map { it.first }
        assertTrue("no child can retain source data after derive returns: $livePids", livePids.isEmpty())
    }

    /**
     * The isolated decoder has its own UID, which this app cannot see in
     * `/proc`; the shell sees every process. A zombie has exited and released
     * all file descriptors and memory, so it cannot retain source bytes.
     */
    private fun decoderProcesses(): List<Pair<Int, String>> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val names = listOf("media_decoder", "media_platform", "ocr").map { "${context.packageName}:$it" }
        return shell("ps -A -o PID,USER,S,NAME").lines().drop(1)
            .map { it.trim().split(Regex("\\s+")) }
            .filter { it.size == 4 && names.any { name -> it[3] == name || it[3].startsWith("$name:") } && it[2] != "Z" }
            .map { it[0].toInt() to it[1] }
    }

    private fun shell(command: String): String {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes().decodeToString() }
    }

    private val imageProbe = ProbedMedia(MediaBounds.CLASS_IMAGE, 80, 40, 0, "image/jpeg")
}
