package dev.po4yka.chur.android

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.MediaImporter
import dev.po4yka.chur.app.vault.DOCUMENT_PDF_PREVIEW_MAX_BYTES
import dev.po4yka.chur.app.vault.PdfPreviewDocument
import dev.po4yka.chur.ffi.LockReason
import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.ffi.QueryScope
import dev.po4yka.chur.imports.AndroidMediaCodec
import dev.po4yka.chur.vault.VaultRepository
import dev.po4yka.chur.vault.VaultState
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Framework parsing plus the real Android JNI import/query/verified-export path. */
@RunWith(AndroidJUnit4::class)
class DocumentsAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun arbitraryAndMislabelledFilesRoundTripThroughNativeCatalogSearchAndExport() = runBlocking {
        val root = File(context.cacheDir, "documents-native-${System.nanoTime()}").apply { mkdirs() }
        val vault = VaultRepository(File(root, "vault").path, { System.currentTimeMillis() })
        try {
            vault.start()
            vault.create("documents-test-password".encodeToByteArray(), false)
            val generation = (vault.state.value as VaultState.Unlocked).generation
            val fixtures = listOf(
                Triple("private-report.bin", "application/octet-stream", ByteArray(81_923) { (it % 251).toByte() }),
                Triple("../mislabelled-image.jpg", "image/jpeg", "not an image\u0000binary".encodeToByteArray()),
                Triple("mime-report.dat", "not a valid MIME\u0000", byteArrayOf(0, -1, 13, 10)),
                Triple("empty-report.bin", "application/octet-stream", byteArrayOf()),
                Triple("nonseekable-report.dat", "application/x-unregistered", ByteArray(24_321) { (it % 239).toByte() }),
                Triple("two-pages.pdf", "application/pdf", pdfBytes()),
            )
            for ((name, mime, bytes) in fixtures) {
                val source = File(root, "source-${System.nanoTime()}").apply { writeBytes(bytes) }
                val nonseekable = name.startsWith("nonseekable")
                val codec = AndroidMediaCodec(provider(source, name, mime, nonseekable = nonseekable))
                val picked = requireNotNull(codec.open(sourceUri, asFile = true))
                if (nonseekable) {
                    assertFalse(picked.seekable)
                    assertEquals(null, picked.knownLength)
                }
                val result = MediaImporter(codec).import(vault, picked)
                assertTrue("generic files must import regardless of media-looking MIME", result is MediaImporter.Outcome.Imported)
                val objectId = (result as MediaImporter.Outcome.Imported).objectId
                assertEquals(0, result.derivatives)
                val detail = vault.detail(objectId)
                assertEquals(name, detail.filename)
                assertEquals(if (mime.startsWith("not ")) "application/octet-stream" else mime, detail.contentType)
                assertEquals(bytes.size.toLong(), detail.plaintextSize)
                val filePage = vault.page(ObjectQuery(kinds = 8))
                assertTrue(filePage.objects.any { it.objectId.contentEquals(objectId) && it.mediaKind == 4 })
                assertFalse(vault.page(ObjectQuery(kinds = 1)).objects.any { it.objectId.contentEquals(objectId) })
                val searched = vault.page(ObjectQuery(QueryScope.SEARCH, terms = name.substringAfterLast('/').substringBefore('.')))
                assertTrue(searched.objects.any { it.objectId.contentEquals(objectId) })

                val exported = File(root, "export-${System.nanoTime()}")
                ParcelFileDescriptor.open(exported, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE).use { output ->
                    val operation = vault.beginExport(objectId, output.fd)
                    try {
                        var progress = vault.poll(operation)
                        while (!progress.terminal) { delay(20); progress = vault.poll(operation) }
                        assertEquals(0, progress.status)
                    } finally { vault.closeOperation(operation) }
                }
                assertArrayEquals(bytes, exported.readBytes())
                if (mime == "application/pdf") {
                    val reader = vault.leaseReader(objectId)
                    PdfPreviewDocument.open(context, bytes.size.toLong(),
                        { offset, length -> vault.readLeased(reader, offset, length) },
                        { (vault.state.value as? VaultState.Unlocked)?.generation == generation },
                        { vault.releaseReader(reader) }).use { preview ->
                        assertEquals(2, preview.pageCount)
                        val bitmap = preview.render(1)
                        assertTrue(bitmap.width <= 2048 && bitmap.height <= 2048)
                        bitmap.eraseColor(Color.TRANSPARENT); bitmap.recycle()
                        vault.lock(LockReason.USER)
                        rejected { preview.render(0) }
                    }
                }
            }
        } finally { vault.shutdown(); root.deleteRecursively() }
    }

    @Test
    fun pdfProxyIsBoundedClearsTemporaryRangesAndRejectsInvalidation() {
        val bytes = pdfBytes()
        val slices = mutableListOf<ByteArray>()
        var maxRequested = 0
        val released = AtomicInteger()
        val preview = PdfPreviewDocument.open(context, bytes.size.toLong(), { offset, length ->
            maxRequested = maxOf(maxRequested, length)
            bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + length)).also { slices.add(it) }
        }, { true }, { released.incrementAndGet() })
        try {
            assertEquals(2, preview.pageCount)
            val bitmap = preview.render(0)
            assertTrue(bitmap.width <= 2048 && bitmap.height <= 2048)
            assertTrue(bitmap.width.toLong() * bitmap.height <= 4_000_000)
            assertTrue(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) != Color.TRANSPARENT)
            bitmap.eraseColor(Color.TRANSPARENT); bitmap.recycle()
            assertTrue(maxRequested in 1..65_536)
            assertTrue(slices.isNotEmpty() && slices.all { range -> range.all { it == 0.toByte() } })
            preview.invalidate()
            rejected { preview.render(1) }
        } finally { preview.close(); preview.close() }
        assertEquals(1, released.get())
    }

    @Test
    fun malformedAndOversizePdfsFailWithoutAPlaintextScratchFile() {
        val released = AtomicInteger()
        rejected {
            PdfPreviewDocument.open(context, 17, { _, length -> ByteArray(length) }, { true }, { released.incrementAndGet() })
        }
        assertEquals(1, released.get())
        var read = false
        rejected {
            PdfPreviewDocument.open(context, DOCUMENT_PDF_PREVIEW_MAX_BYTES + 1, { _, _ -> read = true; byteArrayOf() }, { true }, {})
        }
        assertFalse(read)
    }

    @Test
    fun aPasswordProtectedPdfReleasesItsSourceAndDoesNotOpen() {
        // Own one-page fixture, encrypted with qpdf AES-256. The public test
        // password is document-test-password; production supplies no password.
        val bytes = android.util.Base64.decode("JVBERi0xLjcKJb/3ov4KMSAwIG9iago8PCAvRXh0ZW5zaW9ucyA8PCAvQURCRSA8PCAvQmFzZVZlcnNpb24gLzEuNyAvRXh0ZW5zaW9uTGV2ZWwgOCA+PiA+PiAvUGFnZXMgMiAwIFIgL1R5cGUgL0NhdGFsb2cgPj4KZW5kb2JqCjIgMCBvYmoKPDwgL0NvdW50IDEgL0tpZHMgWyAzIDAgUiBdIC9UeXBlIC9QYWdlcyA+PgplbmRvYmoKMyAwIG9iago8PCAvQ29udGVudHMgNCAwIFIgL01lZGlhQm94IFsgMCAwIDYxMiA3OTIgXSAvUGFyZW50IDIgMCBSIC9SZXNvdXJjZXMgPDwgPj4gL1R5cGUgL1BhZ2UgPj4KZW5kb2JqCjQgMCBvYmoKPDwgL0xlbmd0aCAzMiA+PgpzdHJlYW0Kv3Q4iQKiRWaqkA8iaV01/I3uneDqtlt4vFOTvFUXQvJlbmRzdHJlYW0KZW5kb2JqCjUgMCBvYmoKPDwgL0NGIDw8IC9TdGRDRiA8PCAvQXV0aEV2ZW50IC9Eb2NPcGVuIC9DRk0gL0FFU1YzIC9MZW5ndGggMzIgPj4gPj4gL0ZpbHRlciAvU3RhbmRhcmQgL0xlbmd0aCAyNTYgL08gPDI3ZTE4YjUyNjJjMmY3N2RlMmE1NzkyZWY3ODI3MWEwNWI2NTlmYjk4MzY1MjFiOTViZjA1OGQ4MWViZDA5M2RiM2NmOWJmYTFkYTYzZTRlNGQzOTY5NGE5YmI4ZWFkND4gL09FIDwyMmM0NzE2MmRmMWRmOTVmODU3OGQ0M2U4OGY1NjRmNjEwMTVjZjk2ZGJkYjRhN2NmZjYxOTE5NzhkY2VjNDYyPiAvUCAtNCAvUGVybXMgPDc4ZDQ3ZGYxNzUyYjg4MDg0MDczYzNjZTVmYTliMWRlPiAvUiA2IC9TdG1GIC9TdGRDRiAvU3RyRiAvU3RkQ0YgL1UgPDE4OGU0ZGI2ODFhNjY1NzgyMGIzZDQ2ZDdkMjJiYTUyMGEyYWEyYjJlN2NiYzRiY2IyZjc0ZDg1NWRlMWEyNjdmNjhmNjcwMTllMTE5ZjU3ZjAwZWJjNDUwYWIzN2ZhND4gL1VFIDxlOWNlNThiNDlmNjE5MTE4MDE0MjY1ZDM3YzEzY2I3NGM4ZTAxMjY2MzYxOWEwYzRiYWFjZGE5NTA2ZWY1NWM0PiAvViA1ID4+CmVuZG9iagp4cmVmCjAgNgowMDAwMDAwMDAwIDY1NTM1IGYgCjAwMDAwMDAwMTUgMDAwMDAgbiAKMDAwMDAwMDEzMCAwMDAwMCBuIAowMDAwMDAwMTg5IDAwMDAwIG4gCjAwMDAwMDAyOTUgMDAwMDAgbiAKMDAwMDAwMDM3NiAwMDAwMCBuIAp0cmFpbGVyIDw8IC9Sb290IDEgMCBSIC9TaXplIDYgL0lEIFs8MjQ0NTM5MGJhYjM0NDEyZjBlMWJhN2QzZmY0YWRjNmQ+PDI0NDUzOTBiYWIzNDQxMmYwZTFiYTdkM2ZmNGFkYzZkPl0gL0VuY3J5cHQgNSAwIFIgPj4Kc3RhcnR4cmVmCjkyMwolJUVPRgo=", android.util.Base64.DEFAULT)
        val released = AtomicInteger()
        try {
            PdfPreviewDocument.open(context, bytes.size.toLong(), { offset, length ->
                bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + length))
            }, { true }, { released.incrementAndGet() })
            fail("a protected PDF must not open without its password")
        } catch (_: SecurityException) { }
        assertEquals(1, released.get())
    }

    @Test
    fun optionalProviderMetadataFailureDoesNotPreventOpeningTheOriginalBytes() {
        val source = File(context.cacheDir, "document-provider-${System.nanoTime()}").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            val codec = AndroidMediaCodec(provider(source, "unused", "text/plain", refuseMetadata = true))
            val picked = requireNotNull(codec.open(sourceUri, asFile = true))
            try {
                assertTrue(picked.importAsFile)
                ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.fromFd(picked.descriptor)).use {
                    assertArrayEquals(byteArrayOf(1, 2, 3), it.readBytes())
                }
                assertEquals("application/octet-stream", picked.contentTypeHint)
            } finally { picked.close() }
        } finally { source.delete() }
    }

    private fun provider(source: File, name: String, mime: String, refuseMetadata: Boolean = false,
        nonseekable: Boolean = false): ContentResolver {
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                if (!nonseekable) return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
                val pipe = ParcelFileDescriptor.createPipe()
                Thread {
                    runCatching {
                        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output -> source.inputStream().use { it.copyTo(output) } }
                    }
                }.apply { isDaemon = true; start() }
                return pipe[0]
            }
            override fun getType(uri: Uri): String {
                if (refuseMetadata) throw SecurityException("metadata is optional")
                return mime
            }
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
                if (refuseMetadata) throw SecurityException("metadata is optional")
                return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
                    addRow(arrayOf<Any?>(name, if (nonseekable) null else source.length()))
                }
            }
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        }
        provider.attachInfo(context, ProviderInfo().apply { authority = "dev.po4yka.chur.document-test" })
        return ContentResolver.wrap(provider)
    }

    private fun rejected(action: () -> Unit) {
        try { action(); fail("expected preview refusal") } catch (_: Exception) { }
    }

    private fun pdfBytes(): ByteArray {
        val document = PdfDocument()
        try {
            repeat(2) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(612, 792, index).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawText("Private page ${index + 1}", 32f, 80f, Paint().apply { color = Color.BLACK; textSize = 28f })
                document.finishPage(page)
            }
            val output = ByteArrayOutputStream()
            document.writeTo(output)
            return output.toByteArray()
        } finally { document.close() }
    }

    companion object { private val sourceUri = Uri.parse("content://dev.po4yka.chur.document-test/source") }
}
