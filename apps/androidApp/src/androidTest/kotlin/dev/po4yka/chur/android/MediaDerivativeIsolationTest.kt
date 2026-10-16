package dev.po4yka.chur.android

import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.imports.AndroidMediaCodec
import dev.po4yka.chur.imports.MediaBounds
import dev.po4yka.chur.imports.PickedMedia
import dev.po4yka.chur.imports.ProbedMedia
import java.io.File
import java.io.IOException
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
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
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
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(ActivityManager::class.java)
        val livePids = manager.runningAppProcesses.orEmpty()
            .filter { it.processName == "${context.packageName}:media_decoder" }
            .filter { kernelProcessIsAlive(it.pid) }
            .map { it.pid }
        assertTrue("no child can retain source data after derive returns: $livePids", livePids.isEmpty())
    }

    private fun kernelProcessIsAlive(pid: Int): Boolean {
        fun exists(): Boolean = try {
            Os.kill(pid, 0)
            true
        } catch (failure: ErrnoException) {
            if (failure.errno != OsConstants.ESRCH) throw failure
            false
        }
        if (!exists()) return false
        // ActivityManager can retain a record after Binder death and SIGKILL.
        // Check the kernel task instead. A zombie has exited and released all
        // file descriptors; it cannot retain or decode source bytes.
        val stat = try { File("/proc/$pid/stat").readText() }
        catch (failure: IOException) {
            // A task can exit after /proc opens but before the first read.
            if ((failure.cause as? ErrnoException)?.errno == OsConstants.ESRCH || !exists()) return false
            throw failure
        }
        val state = stat.substringAfterLast(')').trimStart().first()
        return state != 'Z' && state != 'X'
    }

    private val imageProbe = ProbedMedia(MediaBounds.CLASS_IMAGE, 80, 40, 0, "image/jpeg")
}
