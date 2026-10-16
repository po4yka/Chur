package dev.po4yka.chur.android

import android.app.ActivityManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.vault.AndroidContentTextExtractor
import dev.po4yka.chur.app.vault.CONTENT_SOURCE_MAX_BYTES
import dev.po4yka.chur.app.vault.ContentExtractionException
import dev.po4yka.chur.app.vault.ContentExtractionFailure
import dev.po4yka.chur.app.vault.ContentTextSource
import dev.po4yka.chur.ffi.ImportRequest
import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.ffi.QueryScope
import dev.po4yka.chur.vault.VaultRepository
import dev.po4yka.chur.vault.VaultState
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real bundled Tesseract recognition and real child-process custody, with no network or plaintext file. */
@RunWith(AndroidJUnit4::class)
class ContentOcrAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun englishAndRussianImageTextIsRecognizedLocally() = runBlocking {
        val bytes = image()
        val ranges = mutableListOf<ByteArray>()
        val result = AndroidContentTextExtractor(context).extract(source(bytes, ranges), "image/png", 1, { false })
        try {
            val text = result.bytes.decodeToString().lowercase()
            assertTrue("English image text must be recognized", "alpha" in text && "4827" in text)
            assertTrue("Russian image text must be recognized", "приватный" in text && "документ" in text)
            assertFalse(result.partial)
            assertEquals(1, result.pages)
            assertTrue(ranges.all { it.all { byte -> byte == 0.toByte() } })
            assertNoDecoder()
        } finally { result.bytes.fill(0); bytes.fill(0) }
    }

    @Test fun bothPdfPagesBecomeSearchableText() = runBlocking {
        val bytes = pdf()
        val result = AndroidContentTextExtractor(context).extract(source(bytes), "application/pdf", 4, { false })
        try {
            val text = result.bytes.decodeToString().lowercase()
            assertTrue("both real PDF pages must be recognized", "alpha" in text && "beta" in text && "4827" in text)
            assertEquals(2, result.pages)
            assertFalse(result.partial)
            assertNoDecoder()
        } finally { result.bytes.fill(0); bytes.fill(0) }
    }

    @Test fun malformedCancelledAndOversizedInputsDrainTheChildAndDoNotReturnText() = runBlocking {
        val extractor = AndroidContentTextExtractor(context)
        try {
            extractor.extract(source("not a PDF".encodeToByteArray()), "application/pdf", 4, { false })
            fail("a malformed PDF must fail")
        } catch (failure: ContentExtractionException) { assertEquals(ContentExtractionFailure.UNAVAILABLE, failure.reason) }
        assertNoDecoder()
        val cancel = AtomicBoolean(false)
        val ranges = mutableListOf<ByteArray>()
        val bytes = image()
        val cancelling = ContentTextSource(bytes.size.toLong(), { offset, count ->
            bytes.copyOfRange(offset.toInt(), offset.toInt() + count).also { ranges += it; cancel.set(true) }
        }, { true })
        try {
            extractor.extract(cancelling, "image/png", 1, { cancel.get() })
            fail("cancelled recognition must not return text")
        } catch (_: CancellationException) { /* Expected after the owned child has exited. */ }
        finally { bytes.fill(0) }
        assertTrue(ranges.all { it.all { byte -> byte == 0.toByte() } })
        assertNoDecoder()
        try {
            extractor.extract(ContentTextSource(CONTENT_SOURCE_MAX_BYTES + 1, { _, _ -> error("must not read") }, { true }), "image/png", 1, { false })
            fail("oversized parser input must fail before reading")
        } catch (failure: ContentExtractionException) { assertEquals(ContentExtractionFailure.LIMIT, failure.reason) }
        // Cancellation and parser failure must not poison the shared media decoder owner.
        val healthy = image()
        val result = extractor.extract(source(healthy), "image/png", 1, { false })
        try { assertTrue("alpha" in result.bytes.decodeToString().lowercase()); assertNoDecoder() }
        finally { result.bytes.fill(0); healthy.fill(0) }
    }

    @Test fun actualImagePdfAndUtf8ExtractionSearchEncryptedIndexAcrossReopenAndDeletion() = runBlocking {
        val before = context.cacheDir.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toSet()
        val root = File(context.cacheDir, "ocr-native-${System.nanoTime()}").apply { mkdirs() }
        var vault = repository(root)
        try {
            vault.start(); vault.create(PASSWORD, false)
            val bytes = listOf(image(), pdf(), TEXT_CANARY.encodeToByteArray())
            val types = listOf("image/png", "application/pdf", "text/plain")
            val ids = bytes.mapIndexed { index, original ->
                importObject(vault, root, original, "item$index.${listOf("png", "pdf", "txt")[index]}", types[index], if (index == 0) 1 else 4)
            }
            bytes.forEach { it.fill(0) }
            val generation = (vault.state.value as VaultState.Unlocked).generation
            val candidates = vault.contentIndexCandidates(generation, rebuild = true)
            assertEquals(3, candidates.size)
            val pages = mutableListOf<Pair<Int, Int>>()
            for (candidate in candidates) {
                val ranges = mutableListOf<ByteArray>()
                val reader = vault.leaseContentIndexReader(generation, candidate.objectId)
                try {
                    val source = ContentTextSource(candidate.plaintextSize, { offset, length ->
                        vault.readLeased(reader, offset, length).also { ranges += it }
                    }, { (vault.state.value as? VaultState.Unlocked)?.generation == generation })
                    val result = AndroidContentTextExtractor(context).extract(source, candidate.contentType, candidate.mediaClass, { false }) { completed, total -> pages += completed to total }
                    try { vault.putContentIndex(generation, candidate, result.bytes, result.partial) }
                    finally { result.bytes.fill(0) }
                } finally { vault.releaseReader(reader) }
                assertTrue("authenticated range copies must be erased", ranges.isNotEmpty() && ranges.all { it.all { byte -> byte == 0.toByte() } })
                assertNoDecoder()
            }
            assertTrue(pages.contains(2 to 2))
            assertEquals(setOf(ids[0].toList(), ids[1].toList()), search(vault, "alpha"))
            assertEquals(setOf(ids[0].toList(), ids[2].toList()), search(vault, "приватный"))
            assertEquals(setOf(ids[2].toList()), search(vault, "orchidocr"))
            assertTrue(vault.contentIndexCandidates(generation).isEmpty())
            vault.shutdown()
            assertNoPersistentCanary(root, TEXT_CANARY)
            assertNoPersistentCanary(root, "Приватный документ")
            vault = repository(root)
            vault.start(); vault.unlock(PASSWORD)
            assertEquals(setOf(ids[0].toList(), ids[1].toList()), search(vault, "alpha"))
            assertEquals(setOf(ids[2].toList()), search(vault, "orchidocr"))
            vault.delete(ids[0])
            assertEquals(setOf(ids[1].toList()), search(vault, "alpha"))
            vault.restoreAll(listOf(ids[0]))
            assertEquals(2, search(vault, "alpha").size)
            vault.deleteAll(listOf(ids[0], ids[1]))
            vault.permanentlyDelete(ids[0]); vault.permanentlyDelete(ids[1])
            assertTrue(search(vault, "alpha").isEmpty())
            vault.clearContentIndex((vault.state.value as VaultState.Unlocked).generation)
            assertTrue(search(vault, "orchidocr").isEmpty())
            assertEquals(setOf(ids[2].toList()), search(vault, "item2"))
            // Check only newly created test files and model assets, never existing app data.
            context.cacheDir.walkTopDown().filter { it.isFile && it.absolutePath !in before }.forEach {
                assertNoPersistentCanary(it, TEXT_CANARY)
                assertNoPersistentCanary(it, "Приватный документ")
            }
        } finally { vault.shutdown(); root.deleteRecursively() }
    }

    @Test fun protectedPdfRevokedSourceAndCoroutineCancellationReturnNoTextAndDrainChild() = runBlocking {
        val extractor = AndroidContentTextExtractor(context)
        val protected = InstrumentationRegistry.getInstrumentation().context.assets.open("ocr/protected.pdf").use { it.readBytes() }
        try {
            extractor.extract(source(protected), "application/pdf", 4, { false })
            fail("a protected PDF must not produce recognized text without its password")
        } catch (failure: ContentExtractionException) { assertEquals(ContentExtractionFailure.UNAVAILABLE, failure.reason) }
        finally { protected.fill(0) }
        assertNoDecoder()

        val imageBytes = image()
        val ranges = mutableListOf<ByteArray>()
        val allowed = AtomicBoolean(true)
        try {
            extractor.extract(ContentTextSource(imageBytes.size.toLong(), { offset, length ->
                imageBytes.copyOfRange(offset.toInt(), offset.toInt() + length).also { ranges += it; allowed.set(false) }
            }, { allowed.get() }), "image/png", 1, { false })
            fail("a revoked source must not return OCR text")
        } catch (_: CancellationException) { }
        assertTrue(ranges.isNotEmpty() && ranges.all { it.all { byte -> byte == 0.toByte() } })
        assertNoDecoder()

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val job = launch(Dispatchers.Default) {
            val result = extractor.extract(ContentTextSource(imageBytes.size.toLong(), { offset, length ->
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                imageBytes.copyOfRange(offset.toInt(), offset.toInt() + length).also { synchronized(ranges) { ranges += it } }
            }, { true }), "image/png", 1, { false })
            result.bytes.fill(0)
        }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
            job.cancel()
        } finally { release.countDown(); withTimeout(30_000) { job.cancelAndJoin() }; imageBytes.fill(0) }
        assertTrue(job.isCancelled)
        assertTrue(ranges.all { it.all { byte -> byte == 0.toByte() } })
        assertNoDecoder()
        val healthy = image()
        val result = extractor.extract(source(healthy), "image/png", 1, { false })
        try { assertTrue("alpha" in result.bytes.decodeToString().lowercase()); assertNoDecoder() }
        finally { result.bytes.fill(0); healthy.fill(0) }
    }

    private fun repository(root: File) = VaultRepository(File(root, "vault").path, System::currentTimeMillis)
    private suspend fun importObject(vault: VaultRepository, root: File, bytes: ByteArray, filename: String, mime: String, mediaClass: Int): ByteArray {
        val original = File(root, "input-${System.nanoTime()}").apply { writeBytes(bytes) }
        try {
            return ParcelFileDescriptor.open(original, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
                val operation = vault.beginImport(input.fd, ImportRequest(mime, true, mediaClass, width = if (mediaClass == 1) 1100 else 0,
                    height = if (mediaClass == 1) 270 else 0, knownLength = bytes.size.toLong(), originalFilename = filename))
                try {
                    withTimeout(60_000) {
                        var progress = vault.poll(operation)
                        while (!progress.terminal) { delay(20); progress = vault.poll(operation) }
                        assertEquals(0, progress.status)
                        requireNotNull(progress.importedObjectId())
                    }
                } finally { vault.closeOperation(operation) }
            }
        } finally { original.delete() }
    }
    private suspend fun search(vault: VaultRepository, term: String): Set<List<Byte>> =
        vault.page(ObjectQuery(QueryScope.SEARCH, terms = term)).objects.map { it.objectId.toList() }.toSet()
    private fun assertNoPersistentCanary(root: File, text: String) {
        val needle = text.encodeToByteArray()
        val files = if (root.isDirectory) root.walkTopDown().filter { it.isFile }.toList() else listOf(root)
        files.forEach { file ->
            val bytes = file.readBytes()
            assertFalse("OCR plaintext must not appear in persistent files", (0..bytes.size - needle.size).any { offset -> needle.indices.all { bytes[offset + it] == needle[it] } })
        }
    }

    private fun source(bytes: ByteArray, ranges: MutableList<ByteArray> = mutableListOf()) =
        ContentTextSource(bytes.size.toLong(), { offset, length -> bytes.copyOfRange(offset.toInt(), offset.toInt() + length).also { ranges += it } }, { true })

    private fun image(): ByteArray {
        val bitmap = Bitmap.createBitmap(1100, 270, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 54f }
            canvas.drawText("Invoice Alpha 4827", 35f, 90f, paint)
            canvas.drawText("Приватный документ", 35f, 190f, paint)
            return ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() }
        } finally { bitmap.eraseColor(Color.TRANSPARENT); bitmap.recycle() }
    }

    private fun pdf(): ByteArray {
        val document = PdfDocument()
        val scans = mutableListOf<Bitmap>()
        try {
            listOf("Alpha", "Beta").forEachIndexed { number, text ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(700, 250, number + 1).create())
                page.canvas.drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 44f }
                if (number == 0) {
                    page.canvas.drawText("Invoice $text 4827", 35f, 135f, paint)
                } else {
                    // The second page contains pixels only, like a scanned document.
                    val scan = Bitmap.createBitmap(700, 250, Bitmap.Config.ARGB_8888).also { scans += it }
                    Canvas(scan).apply { drawColor(Color.WHITE); drawText("Invoice $text 4827", 35f, 135f, paint) }
                    page.canvas.drawBitmap(scan, 0f, 0f, null)
                }
                document.finishPage(page)
            }
            return ByteArrayOutputStream().use { output -> document.writeTo(output); output.toByteArray() }
        } finally {
            try { document.close() }
            finally { scans.forEach { it.eraseColor(Color.TRANSPARENT); it.recycle() } }
        }
    }

    private companion object {
        val PASSWORD = "local-ocr-integration-password".encodeToByteArray()
        const val TEXT_CANARY = "Orchidocr private text canary 6ea092d8 Приватный текст"
    }

    private fun assertNoDecoder() {
        val processes = context.getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty()
        val live = processes.filter { it.processName == "${context.packageName}:media_decoder" }.filter {
            val stat = runCatching { File("/proc/${it.pid}/stat").readText() }.getOrNull()
            stat != null && stat.substringAfterLast(')').trim().firstOrNull() !in listOf('Z', 'X')
        }
        assertTrue("the decoder must exit before OCR returns", live.isEmpty())
    }
}
