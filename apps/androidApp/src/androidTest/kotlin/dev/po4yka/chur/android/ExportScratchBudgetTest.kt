package dev.po4yka.chur.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.ExportSink
import dev.po4yka.chur.app.ExportTarget
import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Reservations include unpublished files and are made before Rust receives a descriptor. */
@RunWith(AndroidJUnit4::class)
class ExportScratchBudgetTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sink = ExportDestinations(context, {}, {})
    private val gib = 1024L * 1024 * 1024

    @Test
    fun oversizedSourcesAreRejectedBeforeCreatingAFile() {
        try {
            limited { reserve(4 * gib + 1) }
            assertTrue(files().isEmpty())
        } finally { sink.cancelPending() }
    }

    @Test
    fun aFifthUnpublishedEntryIsRejectedAndDiscardReleasesItsReservation() {
        val open = mutableListOf<ExportSink.Destination>()
        try {
            repeat(4) { open.add(reserve(100)) }
            limited { reserve(100) }
            assertEquals(4, files().size)
            open.removeAt(0).discard()
            open.add(reserve(100))
            assertEquals(4, files().size)
        } finally { open.forEach { it.discard() }; sink.cancelPending() }
    }

    @Test
    fun aggregateReservationsIncludeBytesNotWrittenYet() {
        val open = mutableListOf<ExportSink.Destination>()
        try {
            open.add(reserve(4 * gib))
            open.add(reserve(4 * gib))
            limited { reserve(1) }
            assertEquals(2, files().size)
            assertTrue("the check must precede every plaintext write", files().all { it.length() == 0L })
            open.removeAt(0).discard()
            open.add(reserve(4 * gib))
            assertEquals(2, files().size)
        } finally { open.forEach { it.discard() }; sink.cancelPending() }
    }

    @Test
    fun anUnknownOrNegativeLengthCannotBypassTheBudget() {
        try {
            limited { sink.create("unknown.bin", "application/octet-stream", ExportTarget.SHARE, null) }
            limited { reserve(-1) }
            assertTrue(files().isEmpty())
        } finally { sink.cancelPending() }
    }

    @Test
    fun concurrentCreatesCannotOverbookTheEntryLimit() {
        val open = java.util.Collections.synchronizedList(mutableListOf<ExportSink.Destination>())
        val refused = java.util.concurrent.atomic.AtomicInteger()
        val start = java.util.concurrent.CountDownLatch(1)
        try {
            val workers = (0 until 16).map {
                Thread {
                    start.await()
                    try { open.add(reserve(1)) } catch (failure: ChurFailure) {
                        assertEquals(ChurStatus.RESOURCE_LIMIT_EXCEEDED, failure.status)
                        refused.incrementAndGet()
                    }
                }.apply { start() }
            }
            start.countDown()
            workers.forEach { it.join(10_000) }
            assertEquals(4, open.size)
            assertEquals(12, refused.get())
            assertEquals(4, files().size)
        } finally { open.forEach { it.discard() }; sink.cancelPending() }
    }

    private fun reserve(size: Long): ExportSink.Destination {
        val destination = sink.create("original.bin", "application/octet-stream", ExportTarget.SHARE, null, size)
        assertNotNull(destination)
        return destination!!
    }

    private fun files(): List<File> = File(context.cacheDir, "export-scratch").listFiles().orEmpty().toList()

    private fun limited(action: () -> Unit) {
        try {
            action()
            fail("the scratch budget must reject the export")
        } catch (failure: ChurFailure) {
            assertEquals(ChurStatus.RESOURCE_LIMIT_EXCEEDED, failure.status)
        }
    }
}
