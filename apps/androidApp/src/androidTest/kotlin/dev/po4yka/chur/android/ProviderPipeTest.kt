package dev.po4yka.chur.android

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.imports.AndroidProviderPipe
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.ImportRequest
import dev.po4yka.chur.ffi.LockReason
import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.vault.VaultRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Actual kernel pipes and FUSE proxy descriptors; no provider or native-I/O mocks. */
@RunWith(AndroidJUnit4::class)
class ProviderPipeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun seekableInputPreservesThePreviewCursorAndAllBytes() {
        val file = File.createTempFile("provider-input-", ".bin", context.cacheDir)
        val bytes = ByteArray(200_003) { (it * 17).toByte() }
        file.writeBytes(bytes)
        val source = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        Os.lseek(source.fileDescriptor, 19, OsConstants.SEEK_SET)
        val input = AndroidProviderPipe.input(source, seekable = true)
        try {
            val result = readPipe(input.descriptor)
            assertArrayEquals(bytes, result)
            assertEquals(19L, Os.lseek(source.fileDescriptor, 0, OsConstants.SEEK_CUR))
        } finally { input.close(); file.delete() }
    }

    @Test
    fun providerReadFailureNeverBecomesASuccessfulTruncatedEof() {
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val thread = HandlerThread("provider-read-failure-test").apply { start() }
        val source = proxy(ParcelFileDescriptor.MODE_READ_ONLY, thread, object : ProxyFileDescriptorCallback() {
            override fun onGetSize() = 1L
            override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                entered.countDown()
                throw ErrnoException("test read", OsConstants.EIO)
            }
            override fun onRelease() { closed.countDown() }
        })
        val input = AndroidProviderPipe.input(source, seekable = true)
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            ParcelFileDescriptor.fromFd(input.descriptor).use { native ->
                val poll = StructPollfd().apply { fd = native.fileDescriptor; events = OsConstants.POLLIN.toShort() }
                assertEquals("read failure must wait for native timeout, never signal EOF", 0, Os.poll(arrayOf(poll), 250))
            }
        } finally {
            input.close()
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            thread.quitSafely()
        }
    }

    @Test
    fun closingAnInputDoesNotWaitForAStalledFuseRead() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val thread = HandlerThread("provider-read-stall-test").apply { start() }
        val source = proxy(ParcelFileDescriptor.MODE_READ_ONLY, thread, object : ProxyFileDescriptorCallback() {
            override fun onGetSize() = 1L
            override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                entered.countDown()
                release.await()
                data[0] = 42
                return 1
            }
            override fun onRelease() { closed.countDown() }
        })
        val input = AndroidProviderPipe.input(source, seekable = true)
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            ParcelFileDescriptor.fromFd(input.descriptor).use {
                try {
                    Os.read(it.fileDescriptor, ByteArray(1), 0, 1)
                    fail("an empty input must be nonblocking before native receives it")
                } catch (failure: ErrnoException) { assertEquals(OsConstants.EAGAIN, failure.errno) }
            }
            val before = System.nanoTime()
            input.close()
            assertTrue("close waited for a foreign read", System.nanoTime() - before < TimeUnit.SECONDS.toNanos(1))
            assertEquals(1L, release.count)
        } finally {
            input.close()
            release.countDown()
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            thread.quitSafely()
        }
    }

    @Test
    fun fourStalledOutputsStayBoundedAndCancelWithoutJoiningProviders() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(4)
        val closed = CountDownLatch(4)
        val threads = mutableListOf<HandlerThread>()
        val outputs = mutableListOf<AndroidProviderPipe.Output>()
        val extraFile = File.createTempFile("provider-output-limit-", ".bin", context.cacheDir)
        try {
            repeat(4) { index ->
                val thread = HandlerThread("provider-write-stall-$index").apply { start() }
                threads += thread
                val destination = proxy(ParcelFileDescriptor.MODE_WRITE_ONLY, thread, object : ProxyFileDescriptorCallback() {
                    override fun onGetSize() = 0L
                    override fun onWrite(offset: Long, size: Int, data: ByteArray): Int {
                        entered.countDown()
                        release.await()
                        return size
                    }
                    override fun onRelease() { closed.countDown() }
                })
                val output = AndroidProviderPipe.output(destination)
                outputs += output
                ParcelFileDescriptor.fromFd(output.descriptor).use { Os.write(it.fileDescriptor, byteArrayOf(1), 0, 1) }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val before = System.nanoTime()
            outputs.forEach { it.cancel(); it.close() }
            assertTrue("cancel waited for a foreign write", System.nanoTime() - before < TimeUnit.SECONDS.toNanos(1))
            ParcelFileDescriptor.open(extraFile, ParcelFileDescriptor.MODE_WRITE_ONLY).use { extra ->
                try {
                    AndroidProviderPipe.output(extra).close()
                    fail("a fifth blocked output must be refused")
                } catch (_: IOException) { }
            }
            assertEquals(1L, release.count)
        } finally {
            outputs.forEach { it.close() }
            release.countDown()
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            threads.forEach { it.quitSafely() }
            extraFile.delete()
        }
    }

    @Test
    fun stalledCiphertextCopyTimesOutWithoutProviderRelease() = blockedCiphertextCopy(cancel = false)

    @Test
    fun cancelledCiphertextCopyDoesNotWaitForProviderRelease() = blockedCiphertextCopy(cancel = true)

    private fun blockedCiphertextCopy(cancel: Boolean) {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val thread = HandlerThread("provider-archive-write-stall").apply { start() }
        val source = File.createTempFile("provider-ciphertext-copy-", ".bin", context.cacheDir).apply {
            // Transport bytes only; package authentication belongs to native tests.
            writeBytes(ByteArray(512 * 1024) { (it * 17).toByte() })
        }
        val destination = proxy(ParcelFileDescriptor.MODE_WRITE_ONLY, thread, object : ProxyFileDescriptorCallback() {
            override fun onGetSize() = 0L
            override fun onWrite(offset: Long, size: Int, data: ByteArray): Int {
                entered.countDown()
                release.await()
                return size
            }
            override fun onRelease() { closed.countDown() }
        })
        val output = AndroidProviderPipe.output(destination)
        val copy = FutureTask<IOException?> {
            try {
                ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { output.copyCiphertext(it) }
                null
            } catch (failure: IOException) { failure }
        }
        val started = System.nanoTime()
        val copyThread = Thread(copy, "provider-archive-copy-test").apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            if (cancel) output.cancel()
            val failure = copy.get(if (cancel) 2 else 40, TimeUnit.SECONDS)
            if (cancel) assertTrue(failure?.message in setOf("Provider transfer cancelled", "Provider output cancelled"))
            else assertEquals("Provider pipe made no progress", failure?.message)
            if (!cancel) assertTrue("the real 30 second stall limit must be exercised", System.nanoTime() - started >= TimeUnit.SECONDS.toNanos(30))
            assertEquals("copy must return while the foreign write is still blocked", 1L, release.count)
            assertEquals(1L, closed.count)
        } finally {
            output.close()
            release.countDown()
            copyThread.join(5_000)
            assertFalse("copy thread did not stop", copyThread.isAlive)
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            thread.quitSafely()
            source.delete()
        }
    }

    @Test
    fun publicationWaitsUntilTheProviderHasAllBytes() {
        val file = File.createTempFile("provider-output-", ".bin", context.cacheDir)
        val bytes = ByteArray(200_003) { (it * 13).toByte() }
        val output = AndroidProviderPipe.output(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY))
        try {
            writePipe(output.descriptor, bytes)
            output.finish()
            assertArrayEquals(bytes, file.readBytes())
        } finally { output.close(); file.delete() }
    }

    @Test
    fun nativeAcceptsAnAppPipeAndAnAppPrivateSeekableBackup() = runBlocking {
        val root = File(context.cacheDir, "provider-native-${System.nanoTime()}")
        val file = File(context.cacheDir, "provider-native-source-${System.nanoTime()}").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val backup = File(context.cacheDir, "provider-native-backup-${System.nanoTime()}")
        val vault = VaultRepository(root.path, System::currentTimeMillis)
        try {
            vault.start()
            vault.create("provider native guard test password".encodeToByteArray(), false)
            val input = AndroidProviderPipe.input(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), true)
            try {
                val operation = vault.beginImport(input.descriptor, ImportRequest("application/octet-stream", false, 4, knownLength = 3))
                try { complete(vault, operation) } finally { vault.closeOperation(operation) }
            } finally { input.close() }
            ParcelFileDescriptor.open(backup, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE).use {
                val operation = vault.beginBackup(it.fd)
                try { complete(vault, operation) } finally { vault.closeOperation(operation) }
            }
            assertTrue(backup.length() > 3)
        } finally { vault.shutdown(); root.deleteRecursively(); file.delete(); backup.delete() }
    }

    @Test
    fun nativeRejectsRawFuseBeforeCallingProviderMetadata() = runBlocking {
        val root = File(context.cacheDir, "provider-raw-fuse-${System.nanoTime()}")
        val vault = VaultRepository(root.path, System::currentTimeMillis)
        val metadata = AtomicInteger()
        val thread = HandlerThread("provider-native-guard-test").apply { start() }
        val source = proxy(ParcelFileDescriptor.MODE_READ_ONLY, thread, object : ProxyFileDescriptorCallback() {
            override fun onGetSize(): Long { metadata.incrementAndGet(); return 3L }
            override fun onRead(offset: Long, size: Int, data: ByteArray): Int = throw ErrnoException("must not read", OsConstants.EIO)
            override fun onRelease() = Unit
        })
        try {
            vault.start()
            vault.create("raw fuse guard test password".encodeToByteArray(), false)
            val before = metadata.get()
            try {
                val operation = vault.beginImport(source.fd, ImportRequest("application/octet-stream", true, 4, knownLength = 3))
                vault.cancel(operation)
                vault.closeOperation(operation)
                fail("a raw provider descriptor must not enter native I/O")
            } catch (failure: ChurFailure) { assertEquals(ChurStatus.INVALID_INPUT, failure.status) }
            assertEquals("native must inspect procfs before fstat", before, metadata.get())
        } finally { source.close(); thread.quitSafely(); vault.shutdown(); root.deleteRecursively() }
    }

    @Test
    fun nativeLockFinishesWhileTheKeylessProviderReadIsStillBlocked() = runBlocking {
        val root = File(context.cacheDir, "provider-native-lock-${System.nanoTime()}")
        val vault = VaultRepository(root.path, System::currentTimeMillis)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val thread = HandlerThread("provider-native-lock-test").apply { start() }
        val source = proxy(ParcelFileDescriptor.MODE_READ_ONLY, thread, object : ProxyFileDescriptorCallback() {
            override fun onGetSize() = 1L
            override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
                data[0] = 7
                return 1
            }
            override fun onRelease() { closed.countDown() }
        })
        var input: AndroidProviderPipe.Input? = null
        var operation: Long? = null
        try {
            vault.start()
            vault.create("provider lock test password".encodeToByteArray(), false)
            val nativeSource = AndroidProviderPipe.input(source, true)
            input = nativeSource
            operation = vault.beginImport(nativeSource.descriptor, ImportRequest("application/octet-stream", false, 4))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val before = System.nanoTime()
            vault.lock(LockReason.USER)
            assertTrue("vault lock waited for foreign read", System.nanoTime() - before < TimeUnit.SECONDS.toNanos(2))
            assertEquals("provider must still be blocked when keys are released", 1L, release.count)
        } finally {
            operation?.let { runCatching { vault.closeOperation(it) } }
            input?.close() ?: source.close()
            release.countDown()
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            thread.quitSafely()
            vault.shutdown()
            root.deleteRecursively()
        }
    }

    private fun readPipe(descriptor: Int): ByteArray = ParcelFileDescriptor.fromFd(descriptor).use { input ->
        val result = ByteArrayOutputStream()
        val bytes = ByteArray(8192)
        while (true) {
            val count = try { Os.read(input.fileDescriptor, bytes, 0, bytes.size) }
            catch (failure: ErrnoException) {
                if (failure.errno != OsConstants.EAGAIN) throw failure
                awaitPipe(input, OsConstants.POLLIN)
                continue
            }
            if (count == 0) break
            result.write(bytes, 0, count)
        }
        result.toByteArray()
    }

    private fun writePipe(descriptor: Int, bytes: ByteArray) = ParcelFileDescriptor.fromFd(descriptor).use { output ->
        var offset = 0
        while (offset < bytes.size) {
            try { offset += Os.write(output.fileDescriptor, bytes, offset, bytes.size - offset) }
            catch (failure: ErrnoException) {
                if (failure.errno != OsConstants.EAGAIN) throw failure
                awaitPipe(output, OsConstants.POLLOUT)
            }
        }
    }

    private fun awaitPipe(handle: ParcelFileDescriptor, event: Int) {
        val poll = StructPollfd().apply { fd = handle.fileDescriptor; events = event.toShort() }
        assertTrue("pipe made no progress", Os.poll(arrayOf(poll), 5000) > 0)
    }

    private suspend fun complete(vault: VaultRepository, operation: Long) = withTimeout(30_000) {
        while (true) {
            val progress = vault.poll(operation)
            if (progress.terminal) { assertEquals(0, progress.status); break }
            delay(10)
        }
    }

    private fun proxy(mode: Int, thread: HandlerThread, callback: ProxyFileDescriptorCallback): ParcelFileDescriptor =
        requireNotNull(context.getSystemService(StorageManager::class.java)).openProxyFileDescriptor(mode, callback, Handler(thread.looper))
}
