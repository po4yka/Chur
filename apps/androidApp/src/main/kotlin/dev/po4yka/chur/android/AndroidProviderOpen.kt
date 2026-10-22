package dev.po4yka.chur.android

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A foreign open must not retain a caller's credential or block the UI lock callback. */
internal object AndroidProviderOpen {
    private val slots = Semaphore(4)

    fun open(resolver: ContentResolver, uri: Uri, mode: String, document: Boolean = false, cancelled: () -> Boolean): ParcelFileDescriptor? {
        if (cancelled()) {
            discard(resolver, uri, document)
            throw ChurFailure(ChurStatus.CANCELLED, "the provider open")
        }
        if (!slots.tryAcquire()) throw ChurFailure(ChurStatus.RESOURCE_LIMIT_EXCEEDED, "too many provider opens")
        val pending = Pending()
        // This worker captures only the resolver, URI, mode, and handoff cell.
        // The cancellation callback and the caller's credentials stay outside it.
        Thread({ runOpen(resolver, uri, mode, document, pending) }, "chur-provider-open").apply { isDaemon = true }.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        try {
            while (!pending.ready.await(50, TimeUnit.MILLISECONDS)) {
                if (cancelled()) throw ChurFailure(ChurStatus.CANCELLED, "the provider open")
                if (System.nanoTime() >= deadline) throw ChurFailure(ChurStatus.IO_FAILURE, "the provider open timed out")
            }
            if (cancelled()) throw ChurFailure(ChurStatus.CANCELLED, "the provider open")
            pending.failure?.let { throw it }
            pending.accepted.set(pending.handle != null)
            return pending.handle
        } finally {
            // If open returns after cancellation, its own worker closes the foreign FD.
            pending.decision.countDown()
        }
    }

    /** Best effort, no queue: a stalled provider must not retain a failed caller. */
    fun discard(resolver: ContentResolver, uri: Uri, document: Boolean = false) {
        if (!slots.tryAcquire()) return
        try {
            Thread({
                try { delete(resolver, uri, document) } catch (_: Exception) { }
                finally { slots.release() }
            }, "chur-provider-discard").apply { isDaemon = true }.start()
        } catch (_: Exception) { slots.release() }
    }

    private fun delete(resolver: ContentResolver, uri: Uri, document: Boolean) {
        if (document) DocumentsContract.deleteDocument(resolver, uri)
        else resolver.delete(uri, null, null)
    }

    private fun runOpen(resolver: ContentResolver, uri: Uri, mode: String, document: Boolean, pending: Pending) {
        try {
            try { pending.handle = resolver.openFileDescriptor(uri, mode) }
            catch (failure: Exception) { pending.failure = failure }
            pending.ready.countDown()
            pending.decision.await()
            if (!pending.accepted.get()) {
                try { pending.handle?.close() } finally {
                    // This URI is a new, unpublished destination. Cleanup must not
                    // keep the cancelled caller or its credential alive.
                    delete(resolver, uri, document)
                }
            }
        } catch (_: Exception) {
            // Cleanup can fail or the provider can disappear. No caller waits for it.
        } finally { slots.release() }
    }

    private class Pending {
        val ready = CountDownLatch(1)
        val decision = CountDownLatch(1)
        val accepted = AtomicBoolean()
        var handle: ParcelFileDescriptor? = null
        var failure: Exception? = null
    }
}
