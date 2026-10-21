package dev.po4yka.chur.android

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.imports.AndroidDerivativeClient
import dev.po4yka.chur.imports.DerivativeInput
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A child that breaks the result protocol must cost only its own operation. */
@RunWith(AndroidJUnit4::class)
class MediaDerivativeHostileChildTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val hostile = ComponentName(instrumentation.context.packageName, HostileDecoderService::class.java.name)

    @Test
    fun anOversizedPageRegionFailsClosedWithoutMapping() = runBlocking {
        assertHostileRegionFailsClosed(HostileDecoderService.OVERSIZED)
    }

    @Test
    fun aRegionShorterThanItsPageFailsClosedWithoutAFault() = runBlocking {
        assertHostileRegionFailsClosed(HostileDecoderService.SHORT)
    }

    /** API 29 has no memfd; its PDF pages render in the platform child through a proxy descriptor. */
    @Test
    fun theApi29PdfPathRecognizesThroughThePlatformChild() = runBlocking {
        val result = requireNotNull(AndroidDerivativeClient(context, null, true)
            .recognize(ByteInput(textPdf()), "application/pdf") { false })
        try {
            val text = result.bytes.decodeToString().lowercase()
            assertTrue("PDF text must be recognized: $text", "alpha" in text && "4827" in text)
            assertEquals(1, result.pages)
        } finally { result.bytes.fill(0) }
    }

    private suspend fun assertHostileRegionFailsClosed(type: String) {
        val started = System.nanoTime()
        assertNull(AndroidDerivativeClient(context, hostile).recognize(ByteInput(byteArrayOf(1, 2, 3)), type) { false })
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("the hostile result must fail at once: $elapsedMs ms", elapsedMs < 20_000)
        // The parent survived and did not mark decoding failed: the next recognition works.
        val result = requireNotNull(AndroidDerivativeClient(context).recognize(ByteInput(textPng()), "image/png") { false })
        try {
            val text = result.bytes.decodeToString().lowercase()
            assertTrue("image text must be recognized: $text", "alpha" in text && "4827" in text)
        } finally { result.bytes.fill(0) }
    }

    private class ByteInput(private val bytes: ByteArray) : DerivativeInput {
        override val size: Long = bytes.size.toLong()
        override fun read(offset: Long, into: ByteArray, count: Int, stopped: () -> Boolean): Int {
            if (offset >= bytes.size) return -1
            val length = minOf(count.toLong(), bytes.size - offset).toInt()
            bytes.copyInto(into, 0, offset.toInt(), offset.toInt() + length)
            return length
        }
    }

    private fun textPaint() = Paint().apply { color = Color.BLACK; textSize = 64f; isAntiAlias = true }

    private fun textPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(900, 200, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            android.graphics.Canvas(bitmap).drawText("alpha 4827 beta", 40f, 120f, textPaint())
            return ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    private fun textPdf(): ByteArray {
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(612, 200, 1).create())
            page.canvas.drawColor(Color.WHITE)
            page.canvas.drawText("alpha 4827 beta", 40f, 110f, textPaint().apply { textSize = 48f })
            document.finishPage(page)
            return ByteArrayOutputStream().use { output -> document.writeTo(output); output.toByteArray() }
        } finally { document.close() }
    }
}
