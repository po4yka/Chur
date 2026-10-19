@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.po4yka.chur.app.vault

import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Vision.VNErrorRequestCancelled
import kotlinx.cinterop.*
import platform.Foundation.*
import platform.CoreGraphics.*
import platform.CoreFoundation.*
import platform.ImageIO.*
import platform.UIKit.*
import platform.posix.memcpy
import kotlin.math.PI
import kotlinx.coroutines.test.runTest

/** Real Vision requests over an image and a rasterized PDF. No mocked recognizer is used. */
class ContentTextExtractorIosTest {
    @Test fun local_vision_recognizes_english_and_russian_image_text() = runTest {
        val bytes = Base64.decode(IMAGE)
        val ranges = mutableListOf<ByteArray>()
        val result = IosContentTextExtractor().extract(source(bytes, ranges), "image/png", 1, { false })
        try {
            val text = result.bytes.decodeToString().lowercase()
            assertTrue("alpha" in text && "4827" in text, "English image text must be recognized: $text")
            assertTrue("приватный" in text && "документ" in text, "Russian image text must be recognized: $text")
            assertFalse(result.partial)
            assertEquals(1, result.pages)
            assertTrue(ranges.all { it.all { byte -> byte == 0.toByte() } })
        } finally { result.bytes.fill(0); bytes.fill(0) }
    }

    @Test fun local_vision_recognizes_each_pdf_page_and_revocation_stops_next_page() = runTest {
        val bytes = pdf()
        val source = source(bytes)
        val progress = mutableListOf<Int>()
        val result = IosContentTextExtractor().extract(source, "application/pdf", 4, { false }) { page, _ -> progress += page }
        try {
            val text = result.bytes.decodeToString().lowercase()
            assertTrue("alpha" in text && "beta" in text && "4827" in text, "PDF fixture text: $text")
            assertEquals(listOf(1, 2), progress)
            assertEquals(2, result.pages)
        } finally { result.bytes.fill(0) }
        var readable = true
        val invalidated = source.copy(canRead = { readable })
        assertFailsWith<CancellationException> {
            IosContentTextExtractor().extract(invalidated, "application/pdf", 4, { false }) { _, _ -> readable = false }
        }
        bytes.fill(0)
    }

    @Test fun malformed_oversized_and_cancelled_inputs_do_not_produce_index_text() = runTest {
        val extractor = IosContentTextExtractor()
        assertEquals(ContentExtractionFailure.UNAVAILABLE, assertFailsWith<ContentExtractionException> {
            extractor.extract(source("bad image".encodeToByteArray()), "image/png", 1, { false })
        }.reason)
        assertEquals(ContentExtractionFailure.LIMIT, assertFailsWith<ContentExtractionException> {
            extractor.extract(ContentTextSource(CONTENT_SOURCE_MAX_BYTES + 1, { _, _ -> error("must not read") }, { true }), "application/pdf", 4, { false })
        }.reason)
        assertFailsWith<CancellationException> {
            extractor.extract(ContentTextSource(10, { _, _ -> error("must not read") }, { true }), "image/png", 1, { true })
        }
    }

    @Test fun exif_orientation_is_applied_before_real_image_recognition() = runTest {
        val original = Base64.decode(IMAGE)
        val rotated = orientedJpeg(original)
        try {
            val result = IosContentTextExtractor().extract(source(rotated), "image/jpeg", 1, { false })
            try {
                val text = result.bytes.decodeToString().lowercase()
                assertTrue("alpha" in text && "4827" in text)
                assertTrue("приватный" in text && "документ" in text)
                assertFalse(result.partial)
            } finally { result.bytes.fill(0) }
        } finally { original.fill(0); rotated.fill(0) }
    }

    @Test fun password_protected_pdf_does_not_return_index_text() = runTest {
        val bytes = Base64.decode(PROTECTED_PDF)
        try {
            assertEquals(ContentExtractionFailure.UNAVAILABLE, assertFailsWith<ContentExtractionException> {
                IosContentTextExtractor().extract(source(bytes), "application/pdf", 4, { false })
            }.reason)
        } finally { bytes.fill(0) }
    }

    @Test fun coroutine_cancel_during_real_vision_progress_cancels_the_native_request_before_return() = runBlocking {
        val bytes = Base64.decode(IMAGE)
        val ranges = mutableListOf<ByteArray>()
        val entered = MutableStateFlow(false)
        val completed = MutableStateFlow(false)
        val nativeError = MutableStateFlow<Long?>(null)
        lateinit var running: Job
        val extractor = IosContentTextExtractor(object : IosVisionObserver {
            override fun progress(fraction: Double) {
                if (fraction < 1.0 && entered.compareAndSet(false, true)) running.cancel()
            }
            override fun finished(errorCode: Long?) { nativeError.value = errorCode; completed.value = true }
        })
        try {
            running = launch(start = CoroutineStart.LAZY) {
                extractor.extract(source(bytes, ranges), "image/png", 1, { false }).bytes.fill(0)
                fail("a cancelled native request must not return OCR text")
            }
            running.start()
            withTimeout(30_000) { running.join() }
            assertTrue(entered.value, "cancel must follow a real native progress callback")
            assertTrue(running.isCancelled)
            assertTrue(completed.value, "native completion must drain before extraction ends")
            assertEquals(VNErrorRequestCancelled, nativeError.value, "Vision itself must acknowledge cancellation")
            assertTrue(ranges.isNotEmpty() && ranges.all { it.all { byte -> byte == 0.toByte() } })
        } finally { running.cancel(); running.join(); bytes.fill(0) }
        val healthyBytes = Base64.decode(IMAGE)
        val healthy = IosContentTextExtractor().extract(source(healthyBytes), "image/png", 1, { false })
        try { assertTrue("alpha" in healthy.bytes.decodeToString().lowercase()) }
        finally { healthy.bytes.fill(0); healthyBytes.fill(0) }
    }

    @Test fun external_revocation_during_real_vision_progress_cancels_and_drains_native_recognition() = runTest {
        val bytes = Base64.decode(IMAGE)
        val revoked = MutableStateFlow(false)
        val completed = MutableStateFlow(false)
        val nativeError = MutableStateFlow<Long?>(null)
        val extractor = IosContentTextExtractor(object : IosVisionObserver {
            override fun progress(fraction: Double) { if (fraction < 1.0) revoked.value = true }
            override fun finished(errorCode: Long?) { nativeError.value = errorCode; completed.value = true }
        })
        try {
            assertFailsWith<CancellationException> {
                extractor.extract(source(bytes).copy(canRead = { !revoked.value }), "image/png", 1, { false })
            }
            assertTrue(revoked.value, "revocation must happen inside the real request")
            assertTrue(completed.value)
            assertEquals(VNErrorRequestCancelled, nativeError.value)
        } finally { bytes.fill(0) }
    }

    @Test fun page_limit_is_partial_and_blank_pages_do_not_invoke_vision() = runTest {
        val bytes = pdf(List(CONTENT_PDF_MAX_PAGES + 1) { if (it == 0) "Invoice Alpha 4827" else "" })
        val progress = mutableListOf<Int>()
        val requests = MutableStateFlow(0)
        val extractor = IosContentTextExtractor(object : IosVisionObserver {
            override fun progress(fraction: Double) = Unit
            override fun finished(errorCode: Long?) { requests.value += 1 }
        })
        val result = extractor.extract(source(bytes), "application/pdf", 4, { false }) { page, _ -> progress += page }
        try {
            val text = result.bytes.decodeToString().lowercase()
            assertTrue("alpha" in text && "4827" in text, "PDF fixture text: $text")
            assertEquals(1, requests.value, "only the nonblank page must invoke real Vision recognition")
            assertTrue(result.partial)
            assertEquals(CONTENT_PDF_MAX_PAGES, result.pages)
            assertEquals((1..CONTENT_PDF_MAX_PAGES).toList(), progress)
            assertTrue(result.bytes.size <= CONTENT_TEXT_MAX_BYTES)
        } finally { result.bytes.fill(0); bytes.fill(0) }
    }

    @Test fun near_white_pdf_page_still_invokes_real_vision() = runTest {
        val bytes = pdf(listOf(""), backgroundGray = 0.99)
        val requests = MutableStateFlow(0)
        val extractor = IosContentTextExtractor(object : IosVisionObserver {
            override fun progress(fraction: Double) = Unit
            override fun finished(errorCode: Long?) { requests.value += 1 }
        })
        val result = extractor.extract(source(bytes), "application/pdf", 4, { false })
        try {
            assertEquals(1, requests.value, "near-white pixels must reach real Vision recognition")
            assertEquals(1, result.pages)
            assertFalse(result.partial)
            assertTrue(result.bytes.isEmpty() || result.bytes.all { it == '\n'.code.toByte() })
        } finally { result.bytes.fill(0); bytes.fill(0) }
    }

    @Test fun extreme_page_bounds_stay_bounded() = runTest {
        val huge = pdf(listOf(""), "1000000000 1000000000")
        val bounded = IosContentTextExtractor().extract(source(huge), "application/pdf", 4, { false })
        try { assertEquals(1, bounded.pages); assertTrue(bounded.bytes.isEmpty() || bounded.bytes.all { it == '\n'.code.toByte() }) }
        finally { bounded.bytes.fill(0); huge.fill(0) }
    }

    /** Counterclockwise stored pixels plus EXIF Right must recover the original upright text. */
    private fun orientedJpeg(original: ByteArray): ByteArray = autoreleasepool {
        val data = original.usePinned { NSData.create(bytes = it.addressOf(0), length = original.size.toULong()) }
        val image = assertNotNull(UIImage.imageWithData(data))
        val (width, height) = image.size.useContents { width to height }
        UIGraphicsBeginImageContextWithOptions(CGSizeMake(height, width), true, 1.0)
        val rotated = try {
            val context = assertNotNull(UIGraphicsGetCurrentContext())
            CGContextTranslateCTM(context, 0.0, width)
            CGContextRotateCTM(context, -PI / 2)
            image.drawInRect(CGRectMake(0.0, 0.0, width, height))
            assertNotNull(UIGraphicsGetImageFromCurrentImageContext())
        } finally { UIGraphicsEndImageContext() }
        val output = assertNotNull(CFDataCreateMutable(null, 0))
        val type = assertNotNull(CFStringCreateWithCString(null, "public.jpeg", kCFStringEncodingUTF8))
        val destination = try { assertNotNull(CGImageDestinationCreateWithData(output, type, 1u, null)) }
        finally { CFRelease(type) }
        try {
            val properties = memScoped {
                val value = alloc<IntVar> { this.value = 6 }
                val orientation = assertNotNull(CFNumberCreate(null, kCFNumberIntType, value.ptr))
                val dictionary = assertNotNull(CFDictionaryCreateMutable(null, 1, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr))
                CFDictionarySetValue(dictionary, kCGImagePropertyOrientation, orientation)
                CFRelease(orientation)
                dictionary
            }
            try { CGImageDestinationAddImage(destination, assertNotNull(rotated.CGImage), properties) }
            finally { CFRelease(properties) }
            assertTrue(CGImageDestinationFinalize(destination))
            ByteArray(CFDataGetLength(output).toInt()).also { result ->
                result.usePinned { memcpy(it.addressOf(0), CFDataGetBytePtr(output), result.size.toULong()) }
            }
        } finally { CFRelease(destination); CFRelease(output) }
    }

    private fun source(bytes: ByteArray, ranges: MutableList<ByteArray> = mutableListOf()) =
        ContentTextSource(bytes.size.toLong(), { offset, length -> bytes.copyOfRange(offset.toInt(), offset.toInt() + length).also { ranges += it } }, { true })

    private fun pdf(texts: List<String> = listOf("Invoice Alpha 4827", "Invoice Beta 4827"), box: String = "700 250", backgroundGray: Double = 1.0): ByteArray {
        val font = texts.size + 3
        val objects = buildList {
            add("<< /Type /Catalog /Pages 2 0 R >>")
            add("<< /Type /Pages /Kids [${texts.indices.joinToString(" ") { "${it + 3} 0 R" }}] /Count ${texts.size} >>")
            texts.indices.forEach { index ->
                add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $box] /Resources << /Font << /F1 $font 0 R >> >> /Contents ${font + 1 + index} 0 R >>")
            }
            add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
            texts.forEach {
                val background = if (backgroundGray == 1.0) "" else "$backgroundGray g 0 0 $box re f 0 g "
                add(stream(background + if (it.isEmpty()) "" else "BT /F1 44 Tf 35 135 Td ($it) Tj ET"))
            }
        }
        val out = StringBuilder("%PDF-1.4\n")
        val offsets = mutableListOf(0)
        objects.forEachIndexed { index, value -> offsets += out.length; out.append("${index + 1} 0 obj\n$value\nendobj\n") }
        val xref = out.length
        out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.drop(1).forEach { out.append(it.toString().padStart(10, '0')).append(" 00000 n \n") }
        out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toString().encodeToByteArray()
    }
    private fun stream(text: String) = "<< /Length ${text.length} >>\nstream\n$text\nendstream"

    companion object {
        private const val PROTECTED_PDF =
            "JVBERi0xLjcKJb/3ov4KMSAwIG9iago8PCAvRXh0ZW5zaW9ucyA8PCAvQURCRSA8PCAvQmFzZVZlcnNpb24gLzEuNyAvRXh0ZW5zaW9uTGV2ZWwgOCA+PiA+" +
            "PiAvUGFnZXMgMiAwIFIgL1R5cGUgL0NhdGFsb2cgPj4KZW5kb2JqCjIgMCBvYmoKPDwgL0NvdW50IDEgL0tpZHMgWyAzIDAgUiBdIC9UeXBlIC9QYWdlcyA+" +
            "PgplbmRvYmoKMyAwIG9iago8PCAvQ29udGVudHMgNCAwIFIgL01lZGlhQm94IFsgMCAwIDYxMiA3OTIgXSAvUGFyZW50IDIgMCBSIC9SZXNvdXJjZXMgPDwg" +
            "Pj4gL1R5cGUgL1BhZ2UgPj4KZW5kb2JqCjQgMCBvYmoKPDwgL0xlbmd0aCAzMiA+PgpzdHJlYW0Kv3Q4iQKiRWaqkA8iaV01/I3uneDqtlt4vFOTvFUXQvJl" +
            "bmRzdHJlYW0KZW5kb2JqCjUgMCBvYmoKPDwgL0NGIDw8IC9TdGRDRiA8PCAvQXV0aEV2ZW50IC9Eb2NPcGVuIC9DRk0gL0FFU1YzIC9MZW5ndGggMzIgPj4g" +
            "Pj4gL0ZpbHRlciAvU3RhbmRhcmQgL0xlbmd0aCAyNTYgL08gPDI3ZTE4YjUyNjJjMmY3N2RlMmE1NzkyZWY3ODI3MWEwNWI2NTlmYjk4MzY1MjFiOTViZjA1" +
            "OGQ4MWViZDA5M2RiM2NmOWJmYTFkYTYzZTRlNGQzOTY5NGE5YmI4ZWFkND4gL09FIDwyMmM0NzE2MmRmMWRmOTVmODU3OGQ0M2U4OGY1NjRmNjEwMTVjZjk2" +
            "ZGJkYjRhN2NmZjYxOTE5NzhkY2VjNDYyPiAvUCAtNCAvUGVybXMgPDc4ZDQ3ZGYxNzUyYjg4MDg0MDczYzNjZTVmYTliMWRlPiAvUiA2IC9TdG1GIC9TdGRD" +
            "RiAvU3RyRiAvU3RkQ0YgL1UgPDE4OGU0ZGI2ODFhNjY1NzgyMGIzZDQ2ZDdkMjJiYTUyMGEyYWEyYjJlN2NiYzRiY2IyZjc0ZDg1NWRlMWEyNjdmNjhmNjcw" +
            "MTllMTE5ZjU3ZjAwZWJjNDUwYWIzN2ZhND4gL1VFIDxlOWNlNThiNDlmNjE5MTE4MDE0MjY1ZDM3YzEzY2I3NGM4ZTAxMjY2MzYxOWEwYzRiYWFjZGE5NTA2" +
            "ZWY1NWM0PiAvViA1ID4+CmVuZG9iagp4cmVmCjAgNgowMDAwMDAwMDAwIDY1NTM1IGYgCjAwMDAwMDAwMTUgMDAwMDAgbiAKMDAwMDAwMDEzMCAwMDAwMCBu" +
            "IAowMDAwMDAwMTg5IDAwMDAwIG4gCjAwMDAwMDAyOTUgMDAwMDAgbiAKMDAwMDAwMDM3NiAwMDAwMCBuIAp0cmFpbGVyIDw8IC9Sb290IDEgMCBSIC9TaXpl" +
            "IDYgL0lEIFs8MjQ0NTM5MGJhYjM0NDEyZjBlMWJhN2QzZmY0YWRjNmQ+PDI0NDUzOTBiYWIzNDQxMmYwZTFiYTdkM2ZmNGFkYzZkPl0gL0VuY3J5cHQgNSAw" +
            "IFIgPj4Kc3RhcnR4cmVmCjkyMwolJUVPRgo="
        private val IMAGE =
            "iVBORw0KGgoAAAANSUhEUgAABEwAAAEOCAIAAAAcyxUtAABFVklEQVR4nO3deVxU1f/48RkWRVHccEXJ3Ml9CUTNHRJxR81EtLJS01LLUrOP6cfMtNwyQ01z" +
            "RVMwcsMF11IRF9Rcw31DBQFB9m1+j2/ze8z3fO/AcGcB5PJ6/jXnzrnn3pm58Ljve855H7VGo1EBAAAAgFJYFfUJAAAAAIAlEeQAAAAAUBSCHAAAAACKQpAD" +
            "AAAAQFEIcgAAAAAoCkEOAAAAAEUhyAEAAACgKAQ5AAAAABSFIAcAAACAohDkAAAAAFAUghwAAAAAikKQAwAAAEBRCHIAAAAAKApBDgAAAABFIcgBAAAAoCgE" +
            "OQAAAAAUhSAHAAAAgKIQ5AAAAABQFIIcAAAAAIpCkAMAAABAUQhyAAAAACgKQQ4AAAAARSHIAQAAAKAoBDkAAAAAFIUgBwAAAICiEOQAAAAAUBSCHAAAAACK" +
            "QpADAAAAQFEIcgAAAAAoCkEOAAAAAEUhyAEAAACgKAQ5AAAAABSFIAcAAACAohDkAAAAAFAUghwAAAAAikKQAwAAAEBRCHIAAAAAKApBDgAAAABFIcgBAAAA" +
            "oCgEOQAAAAAUhSAHAAAAgKIQ5AAAAABQFIIcAAAAAIpCkAMAAABAUYosyLGzs1MLrl+/XlRn8vJbsWKF+F0FBQUV9Rm9RJYtWyZ+OWq1eu7cuaY1NWXKFLGd" +
            "s2fPqgrRkiVLxKP/8ccfqpLq8ePHjo6O5vwQGRkZgYGB48aNa926da1atUqXLl2+fPlXX321a9euM2fODAsLM/nc4uPj165d+8EHH7Ru3bp27dr29vZlypSp" +
            "Xbt269atP/jgg4CAgOfPnxv7W5tjwoQJJn8WAAAUzKaoTwAwy9q1ayVbfvnll+nTp1tZ0UtZLGk0mnfeeSc2Nta03XNycpYtW7ZgwYKoqChxe0ZGRlJS0t27" +
            "d48dOzZnzpwOHTrMnTu3a9eu8lt+/PjxrFmzNm3alJKSInnr0b8uXLiwevVqBweHcePGTZ06tVKlSqZ9BAAAYD5uBFGMXbx48fz585KN9+7d27t3bxGdEcy1" +
            "dOnSAwcOmLbvs2fPevXqNWnSJEmEo+/kyZM9evSYNWuWRqOR03JwcHDz5s1XrVqlH+FIJCYmzp8/v23bthEREcacOwAAsCSCHBRjv/76a67bV6xYUejnAgu4" +
            "fPny9OnTTds3KSmpV69eoaGhMuvn5OTMnj37s88+y7fm5s2bBw8ebFTn0p07dzp27Hjy5En5uwAAAAsiyEFxlZGRERAQkOtbe/fuffDgQaGfEcySnp7u6+ub" +
            "lpZm2u7vvffeuXPnjN1r8eLFq1evNlDh5MmTo0aNysnJMbbltLS0/v3737lzR1VgSpcu7evrW3DtAwBQfBHkFANjx47VCAYPHlzUZ/RS2Llzp/hwfeDAgbrX" +
            "2dnZv/zySxGdF0w0ffr0v//+27R9jxw5EhgYKNnYuXPnTZs23b17Ny0t7cWLF5cvX168eHG9evUk1aZNmxYfH59rs9nZ2R999FFWVpa40dbW1tfXd/v27ffv" +
            "309NTU1KSrp58+bGjRt79uwp2f3Zs2cTJ07Ub3bSpEka48XHxzdo0EBsZ82aNe7u7sZ8TwAAlBQEOVDCWDV7e/tly5bZ2trqtqxZs0Zyb4qX2aFDh5YsWWLy" +
            "7suWLROLNjY2q1evPnbsmK+v7yuvvFK6dOly5co1bdp00qRJV65c+fDDD8XKsbGxa9asybXZbdu2Xbx4UdzSvHnzixcvbtq0adCgQXXq1LGzs7O3t69fv/6I" +
            "ESNCQ0N3795duXJlsf6uXbtOnDihMltOTs6wYcNu3ryp2/L555/TjQMAQF4IclAsRUVFidPTu3bt6uTk5OXlJVbYuXNnEZ0djBMXFzdq1ChdDgC1Wm3U7unp" +
            "6fv37xe3/Pzzz6NHj861sp2d3cqVK9966y1xY3BwcK6VN23aJBYbNWp09OhRFxeXvM7E29t7//799vb24sbffvtNZbZZs2aJn9HV1dXkVOkAAJQEBDkoljZs" +
            "2JCdna0r9uvXT6VSjRw5UqxD+oHiYsyYMY8ePdK+rlat2ogRI4za/eHDh2LSswYNGnzwwQeGd1mwYIEYSl26dEm/zosXLyRpDFauXCnpqNHXrl27//znP+KW" +
            "kJAQlXlOnDjx7bff6ooODg5btmwR+y0BAIBygpxLly5NmzatQ4cOjo6Otra2jo6OzZs3Hz58eGBgYHJysuolkJmZuXv37smTJ7u5uTk7O5cpU8bBwaFBgwZv" +
            "vPHGV199FRYWZsJsZvmys7MPHjz4ySefdOjQoVq1atoRO3Xq1OnWrdv06dNPnz5tbIMajebMmTMzZ87s3Llzw4YNHRwc7O3t69Wr17Fjx+nTp584caJAP46B" +
            "5XFsbGwGDRqkUqn69u0r3oMePHjw1q1bhXZKBw8eHDFiRP369cv8q3bt2l5eXvPnz883nXG+4uLiFi1a1LNnz+rVq9va2pYrV65+/foDBw5ctWpVYmKiCQ3e" +
            "v3//559/9vPza9euXfXq1cuUKWNtbW1vb+/k5NS+ffuxY8cGBQWlpqaqCsW6devE9W3XrFlTrVo1o1qIjo4Wi+3bt893F2dnZ3FyzosXL/T/aVy5ciUzM1NX" +
            "bNGihcx1dcaMGSNGIHfv3jXnTyMpKWnEiBFiSD9//nz9mUUAAOD/0BSR0qVLi6dx7dq1XKstXrxYV6dp06bajVFRUdqb2rxUrlx58eLF2dnZ+g3eunVLfIL7" +
            "6quvGnXaffv2FQ90/vz5XKulp6cvXbq0Tp06Bk5SpVK5uLjs2LEj34P6+/uLewUGBhqun5WV5e/v/8orrxg+uqura3h4uMwPHhoa2qJFC8MNNm3aNCQkRFPw" +
            "jh8/Lh63V69eurfGjx8vvvXFF1/Ib1aSTfjMmTOGL0gnJyftxri4OG9v77y+Fmtr63HjxsXGxho+utiydgCVRqPJzs7+4YcfypUrl1fjFSpU+OGHH3K91HN1" +
            "7Ngx/fnxuXJ0dJw/f35GRoamIN26dat8+fK6g44bN07mDyGSJFV766235By6cePG4l5paWmSCpKsa0ZlC2jatKm479OnTzWm+uSTT8SmOnfunJOTY3JrAACU" +
            "EMUvyDl37lyNGjXk3KX169cvPT1dv81OnTqJ1cLCwmSec1xcXKlSpXQ7NmvWLNdqN27caNu2rUo2b2/vxMRESwU5t27dkvMkW8va2nrevHmGP3VaWtrbb78t" +
            "/+MMGzZM/37Rst577z3xiOvWrdO9debMGfGtqlWr5noNWDDIiYmJMTBJQ8fZ2fncuXNGBTmpqanaYXj56t+/f77feXp6er6DuPS5u7vHx8drCkZWVpaYHKxx" +
            "48bJyckmBDnPnz8Xn1zUqVMn3zAgJibGxsZGP14VZWRknD171t/ff/To0S1atFiyZIn8j+bm5iZ+hOfPn2tMcubMGWtra107pUuX/ueff0xrCgCAEqWYBTn/" +
            "/PNPlSpV5N+iTZ48Wb/NVatWiXUmTpwo85xXrlwp7rhgwQL9OsePH3dwcFAZqWXLlg8fPjQ/yLl69WrNmjWNPfo333yTV4Px8fGdO3c2tsE33nij4O6Mk5KS" +
            "xGf/FSpU0N4Z60h6nDZv3lxwQU5mZqYkZjbAwcHhwoULMoOcoKCg3r17m5xnXCIzM9NAX5Nh/fv31xSM2bNn645ia2t79uxZ7XZjgxyNRiMJ7P39/Q3Xl/T4" +
            "vffeexqLEv8M7e3tTWskJyenXbt24nlOnTrVsucJAIBSFacgp2HDhq1atTLq/szW1vbmzZuSNp8/f25nZ6erU7NmTZmjfbp06aLby8rKSj8suXbtmnj/bZQ2" +
            "bdrk9TBeZpDz/PnzV1991YRDW1lZHT16VL/BnJwcmUOb9PXt21dTMMTZOCqV6pNPPpFUkOQb6Ny5c8EFOXPmzDHqa6ldu3ZeD/UlQY6xl7pardYFCYbDCROE" +
            "hoZqLC08PFzsSxF7FE0IciQZzGxtbTds2JBrzaysLEliAGtrawPBpwkuXLggtu/m5mZaOxs2bBDbqVmzpuEuXwAAUCyDHIk+ffps3br1/v37aWlpjx8/PnLk" +
            "iK+vrzi0w0BHzdChQ8U6hw8fzveEHz58aGX1v3kaPDw8JBUyMzNznbXi4eGxbt26W7duJScnJyQkXL16denSpS1bttSvqZ2QYHKQIxnEpdWhQ4eVK1f+888/" +
            "iYmJqamp169f//HHH/VnLTdp0kR/hM/8+fMl1UqXLj18+PCgoKA7d+4kJyenpqY+fPhw9+7dH330kSRtrkqlMmp4j3ySniX9KycpKalixYpinatXrxZEkGNv" +
            "by9Gy66urmvXrtUuPfno0aPg4OA+ffro/yIffPCBsZd6t27dAgIC7t+/n5GRER8ff/LkyYkTJ4qH1nr//fdzbfnx48f6lbWX5aVLlxISErKyspKSku7duxcS" +
            "EjJhwoQKFSpIKg8fPlxjUUlJSeK6lp07dxYfNJgQ5Gg0mgEDBkhOu3v37ps3b9b+IqmpqZGRkatWrdL/05sxY4ZlP927774rtv/tt9+a0EhqaqpkUt/atWst" +
            "e54AAChYsQxyatWqdeTIkVzr79u3T9KXUr9+ff1qu3fvlnPfKfr+++/FXTZu3Cip8NNPP0nO09HRcd++fbm2lp2dvXz5cv0ksBEREaYFOdeuXZMEePb29lu2" +
            "bMn16KmpqZJsy9pVC8U69+/fl5xez54979y5k9f3ExMTI5lAYm9vb/FBazdv3hRnX3Tr1i3XapMmTTJhRKKxQY6OlZXVokWLcu0PDA4OloR/NjY2t2/fltly" +
            "5cqV80pNcebMGUk641q1auVa8+uvv5acQEBAgIHv4enTp927dxd3qVSpkmUnu4uL2FSoUOHu3bviu6YFOcnJyf3791cZaeLEiZb9aOfOnRP/EtVqdWRkpAnt" +
            "LFiwQDzP1157TX56CQAAUPyCnAoVKhieeqsfbDx+/FhSJzMzs3r16roKlStXzjeLVOvWrXX1y5Url5SUJL6bnZ0tGSpWrVq1GzduGG7zwIED4ogd7ax904Kc" +
            "jz76SKxTunTpU6dOGTh0Tk7Om2++Ke7y9ttvixW++OIL8d2hQ4dmZmYa/jj6vUm5Tlsyx4wZM8T2t23blmu1yMhIMRaqVKlSSkpKAQU5arXa8LSf48ePS8LF" +
            "XKeK6bdcrly5ixcvGmh5y5Yt+V7qGo2mUaNGYp3//ve/+X4VMTExkpRuUVFRGguRrLypH3GZFuRor+pff/21du3aKhkaN268c+dOjUUlJSVJslAMGTLEhHaS" +
            "k5MlebTzzakIAACKd5CzfPlywy1nZWWJAUxeN0mSh/27d+820Ob169fFyiNHjpRUOHz4sFhBrVbv379fxteg+e6778Qdra2tHz16ZGyQk5mZKcnHsGjRonwP" +
            "HRkZKT5yrl69uu6RdkpKithLUKdOnRcvXsj5OElJSWLuu1deeUVjOdnZ2eIAnho1ahgITSUhnJyhPqYFOR9//HG+LYsrOeY1DUy/5R9//NFws9nZ2ZJE4fqR" +
            "7YMHD8QK1apVkxPvaTQaSb/cpUuXNJYQFRXl6OiYV2htZpCjTYn2+++/5xvnuLi4yM+fLlNWVpZkyFyZMmVy7bXL18KFC8V2WrVqRdpoAACMUswWAy1fvrz+" +
            "OCsJa2trScKrXBdMlLQjmbgsERAQYGBf7Vgvsejh4eHp6amS4bPPPnNyctIVs7Ozjx49qjLS2bNnY2NjdUVHR0dJ8qhcNWzYsHfv3h06dJg8efLWrVvDw8N1" +
            "vR8nT56Mi4vT1Rw3bpyBpVpE9vb2vr6+uuK9e/du376tspDQ0FDxlv399983sOi75BuQZCOwFAcHB0kAk6vJkyeLQePjx4/Pnz9veJfy5cvnm/HZysqqW7du" +
            "4pYnT55I6jg5OV29enXt2rVjx45t3bq1r69vmTJl8j1h7RJSYjElJUVlNu18lWfPnmmLzs7OP//8s8pytmzZ0qhRo0GDBj18+NBwzWvXrrm5ub355puS5xcm" +
            "y8nJGT169B9//CFuXLhwoQm5QDIzMyVBzpdffin2TAIAgHwVsyDH3d1dzt22ZGJ9Wlqafp3WrVs3b95cV/zjjz8MLPEuhkC1a9eW3FlqF9sRi+PGjVPJY2Nj" +
            "M2bMGHHLX3/9pTKSZHFMX19fcT0fA3bu3HnixIlFixYNHTpU7BM4efKkWM2oLNKSCFPSlDnEvGrW1tYffvihgcre3t5169bVFcPDwy9evKiyND8/PzkXpJ2d" +
            "3fDhw436lbt3766fLUCfZHCU/jWsVqtdXFzeeecdf3//iIiIRYsWqeSRBJBZWVkqs/3444/79+/XvraystqwYYMkRYTJsrKy3n333eHDh9+9e1f+XgcOHGjb" +
            "tu327dvNP/rIkSPXr18vbvT19ZX/f0AUFBQUFRWlK9atW9fw2scAAKDYBzkyF9mUTMjO6/7Mz89P9zopKWnPnj25Vjt9+vSNGzd0RV9fXzHNmtalS5d0r9Vq" +
            "tZhsOl+SSd6S5SzluHr1qljs0KGDyjySmK1Tp05q2QYOHCjue/bsWZUlxMfHi4/Jvb29JbmnJKysrCS3mAXRmdOrVy/TYr/Lly8brp9r/j19kiAhPT1dZYaM" +
            "jIwzZ84sXbrU29t72bJl4ls5OTnmtKxSqa5cuTJt2jRd8fPPPzfqz8SwDz/8cN26deIWW1vbd955Z8eOHQ8fPkxLS0tISLh+/fqaNWskB01JSXnrrbfy+tuX" +
            "IyUlZcCAAZLO3s6dO69evdq0BiWzCidOnKifNBIAABj2f2a9v/xkTimWTPjJ6/5sxIgR06dPz87O1ha3bNkyePBg/WqbN282PFYtLi4uOTlZV6xfv36lSpVU" +
            "srVt29bKykp3kjExMSoj3blzRyyKORJMc+/ePZWFmPBxcrV582bxDl7OM/LRo0d//fXXun68gICA77//Xua4O5lcXV1l1pT8KPmO4jMcwulIuuz+Z5qdbFFR" +
            "UTdu3Lj1r5s3b169evWff/7JzMxUFYCMjAxfX1/db9GmTRtjlxgyIDAwULJ6kqur65YtW8Qe3dKlSzs4ODRu3Pi9994LDQ0dNWrU48ePtW9lZ2cPHz78n3/+" +
            "EaeTyfTkyZN+/fpJHky4ubnt3r1bTkecvr///lvs/LS3txcz0QEAAGUGOQ4ODnKqyRy/XrNmzR49ehw4cEBbDAkJSUxMlBwiJydn27ZtumKbNm1ee+01w/fx" +
            "xt4q2dnZOTg4PH/+XFsUJ8PIpJvkoCVO7DZNfHy8mS1YvKlff/1VLHp5eRnbwosXLwICAiSDA81hZ2cnSYFlQNWqVcWiOIfKnEvdWA8ePNi4cWNoaOj58+cT" +
            "EhJUhWX69Om64YJlypQJCAgwMJ/KKBqNRuwg0vai7N+/30CM4eHhcerUqU6dOummeCUmJs6cOXPVqlVGHfrChQv9+vWTpHbo2rXrrl27TI6lN27cKBaHDBli" +
            "8vrCAACUZMVsuJr+ipNmErtl0tLSJPOGteuE6p745tqNoz8RwoRpBmLPT1JSkq5zSSaxH8ki98cvW5Dz999/R0REmN/OypUrVZZj1A8t+VEkP5k+mbOq5IuK" +
            "ivLz86tbt+6MGTOOHj1amBHOoUOHxNxxP/zwQ5MmTSzV+JEjR8RusQoVKgQGBubbi+Ls7Lx161bxaUhAQIBR38nWrVs7duwoiXB8fHz27t1rcoSTk5MjSQue" +
            "6wq/AABAaUGOxVMMDRw4UHxQqp9jTRyrZmNj8/bbb+s3IlnrxgTiGCG1Wm3sEHyLfy0ZGRmql4lkMJLJzp8/f/r0aYs0pT8q0jDJmElL9WPI9NdffzVt2nTT" +
            "pk3mT60xVnx8/KhRo3Tj6Hr37i1Z08lMkuzt48ePl9m95u7u3qdPH10xJSVFZmLDnJycadOmDRs2TJJxbtKkSdu2bTNtlJrWsWPHHj16pCs2aNDgjTfeMLk1" +
            "AABKsmIW5Fhc2bJlxXk4oaGh4jii9PT033//XVd88803c71/kszA0Q08k0/cRWaGX1GFChXyTZltFLHboWLFihozSHIYmCAzM1Myq9scFkw/YNT3LOklsOzU" +
            "IMPOnTvn6ekp57J0dHTs0aPHl19+GRoaalpmsHxv3ENCQvLNXSFJoPz666+L70rm5UuS5olxS74ky9rISZKRmJjYt2/f+fPnixttbW1XrFixePFi/ZQkRtm5" +
            "c6dYHDJkiDmtAQBQkhWzOTkFYeTIkbqOgqysrKCgIN20jT179oj3pnkt0SMJcvTXKjHsxYsXSUlJeaWGM2HcVGxsrFGZD/RVrlxZd0/8/F+WSvVrgl27dlkq" +
            "e4F2lNGiRYss8nESExOzsrJk9uNJroqaNWuqCkV6evrIkSP1s6jb2dm1aNHCxcWlXr16r776ar169Ro1aiROHJLccL+0JLObJKujGla/fn2x+PTpU8P1Hz58" +
            "6OXlJcmMV6VKlaCgoK5du6rMJknyJklUCAAA5CvpPTkqlapLly7ijdHWrVt1r8Xx8RUqVJCsAa9TtmxZsevj1q1bRiUPkKRmMmH1QEn/kvkLwkgatFQaaIuk" +
            "HFi+fLlRXUnPnj0T57ekpKRs2LDBIieWnZ195coVmZXPnTsnFhs2bKgqFEFBQZIM46+//vru3bsTEhLCw8PXrVs3c+ZMPz+/jh07SlIjiIG3sXnbCpPkxAys" +
            "dqVPEqAaHqV5/fp1d3d3SYTTokWLM2fOWCTCuXnzppiqvnbt2u3atTO/WQAASiaCnP+Z0DJixAhd8a+//tLOlZesnDNkyBADo+1ff/113WuNRnPkyBH5JyCp" +
            "3KBBA5WRJDdD4eHhMnfcu3dvo0aN/Pz8li1bdvr0ad1NnqRBc1YRMdOTJ0/27dunK9rY2AwdOtSoFqpUqSIZwmTB9APyB+NJVv+UueKT+SQRnaen5/Hjx729" +
            "vfNNbHDz5k2xWPiTeUwLyMU4wdhU6QYm81y7dq1bt24PHz4UN/r4+Jw8edKEpxK5OnTokFjs06ePxefaAQBQchDkSMehZWVlae/pd+3aJT4VzmusWq7rb/r7" +
            "+8s8dGZm5po1awysGilH+/btxeLmzZtl5mc7ePDgjRs3Nm3a9Mknn7i5uemyLHTs2FGstn79evnzT2bNmuXi4uLn57d06dITJ05IJmcba/369eJn8fT0NCFB" +
            "9jvvvCMWr169Kgk5TCZZgDIvSUlJYiJybaJhVaGQdOvNnz9fTt62uLg4SahcQOvnWHztLLEnNl8hISFi0cnJKddqDx488PDwkAw4nDFjRmBgoAXzPUquScka" +
            "wQAAwCgEOf+jUaNGbm5uktkI4l3pq6++ajj26Nmzp1g8dOjQ3r175Rx64cKFYopq025u2rVrJ05kf/TokWS1jVzpD9zSpXLq2rWrmOEtPj5e5tKNUVFRS5Ys" +
            "uX79+qZNmyZNmtSpU6dly5apLBdFDB8+3IRGvLy8qlevXhDpB8LDw0NDQ/OtNn/+/BcvXuiK7du3r1WrlqrgZWVlRUdHm9BPOGPGDMnYLZMT7g0YMMDYZBWf" +
            "ffaZ2MKZM2fEdydMmCC+26NHD7G4devWu3fvyjmxa9euSSLPXP/0UlJSvL29xdwJtra269at++abbyzb0yIJcrp162bBxgEAKGkIcnLpqNm3b19CQoJukVCV" +
            "SjVixAjDNzSdO3du0aKFuGXUqFGRkZGGD3r48OGZM2eKW3r06FG3bl1jT97Ozm7UqFHilkmTJuU7bueLL74QVxFt0qSJbh52jRo1fHx8xMo//PBDvinOMjIy" +
            "/Pz8xFQNarV62LBhKlOFhYVdv35dVyxbtqwkHZZMNjY2vr6+4pbt27dLVlA12bvvvhsVFWWgQmho6Lx58wz0LBUcm3+JW06dOpXvXt99951+EGjUXJfC5OHh" +
            "IebyTk1NHThwYL79h/Hx8UOHDhW7p1555ZXmzZvr1/z4448vXbqkK9ra2m7fvl3y52a+p0+f3r9/X1esX7+++Uv6AgBQkhHk/H/Dhg3TDeN58eLFV199Jd4n" +
            "+fn55dvCpEmTxGJMTEyHDh3E+SQijUazatUqLy8vySigKVOmmHb+ksfbCQkJnTp1+vPPP3OtnJGRMWXKlOXLl4sbx48fLxY//fRTyV5+fn6zZs3K64l+dHR0" +
            "nz59JIuWDBgwwKhsV4ZTDvTv39/k0UHvvvuuWExPT7fU2juPHj3q2LFjXskeNm7c2LdvX3HEnZOTk8VvkQ1wdnYWi+PHjzcQkt2/f3/QoEHTp0/Xf0uSh+Dl" +
            "Ub58ecnCOxcuXHBzczt//nxeu5w/f75Dhw6SFAJTp07Vr3ngwAHJRbh69eq+ffta4sSlpyQW27RpY/FDAABQohDk/H+VK1f29vbOdVKNu7u7nFxYfn5+kpk5" +
            "sbGxXl5enp6e69evv337dmpq6osXL65fv/7TTz+1bdt2zJgxkoBhwIABvXr1Mu38mzRpMnbsWHFLdHR0ly5dBg4cGBgY+ODBg4yMjOTk5MuXLy9ZsqRZs2aS" +
            "pUjq1q0rCQPc3NwkHQ4ajWb27NmNGjWaN2/euXPn4uLisrKyYmNjjx49+umnnzZo0EAycMvGxmbu3LmmfRztMCHJaCLTxqppNWvWTHLjuGrVKktlDLt7927b" +
            "tm3ff//9Q4cOxcTEZGRkPHjwICAgoEuXLiNHjkxPTxcrz50715z1Io3l6ekpFiMjI5s1azZr1qzTp08/f/48Ozs7JSUlMjJyy5Ytw4YNa9CgQXBwcK7taLNx" +
            "vJxmzJghyZl++fJlV1fXvn37BgQEXL9+/cWLF6mpqXfv3t26dauPj0+7du3EHkKVSuXi4vLBBx9Ims3OzpY8uRgzZozhuXkmu3Dhglhs1apVQRwFAIASRFNE" +
            "JKvFX7t2LddqixcvFqsFBwfLady0vfK6vfv5559lfqh79+6ZvEBN/fr1Y2Njc21WksYgMDAw12opKSkuLi4mHFqtVh88eFC/waSkpCZNmpj2cVQq1aJFizRm" +
            "WL9+vdhalSpVMjIyzGnwxx9/lJxhaGio/KkguV5aeU1Vz8ugQYMse9FK+qPWrl0rqfD333+buUKl1pQpU2R/0+aS80NI7N+/X+aCRfoqVKhw5coV/TYtuATt" +
            "+fPnDZ+/5IHCtm3bzPj+AACAhp6c/+Xt7a0/Dr5UqVJvvfWWzBacnZ137txpwmqe9erV279/vwk7isqUKbNjxw4TEtr+/PPPktnbWvb29nv27GnUqJEJJzN2" +
            "7NjJkyerzCC5fR8yZIitra05DQ4fPlySWMz89APz5s1r3LixzMqdO3eWkxDCspo3by4ZyiiHo6Oj5FQlXQ0vG09PzxUrVojZMmSyt7ffvn37a6+9pv+WpbJT" +
            "yHHnzp0iWUYJAAClIsj5X7a2tvrxTJ8+fYyKPTp16hQWFmZUYNC7d++wsDDJ4uumadiw4cmTJ+WvIWhvb79u3TrJODdRvXr1wsLCjMp3bGNjM2fOHPlJtHN1" +
            "+/btY8eOWWqsWl4L5uzYsUOS2s5Y5cuX37Vrl5zA0s/PLyQkpGzZsqpCt3Dhwv79+8uv37dv34sXL44YMULsloyIiHhp1wPVGj169IEDByRLmhrWqFGj8PDw" +
            "XCP858+fnzx5UlVYJCv2mDOTDQAAEORI6Q+4l5NyQKJRo0aXLl1atGhRvvmRWrRoERgYuGfPHgOrEBqrRo0ap06dWrFihSRpsoRare7Xr9+FCxfynQRfuXLl" +
            "w4cPb9u2Ld8uC7Va7eHhERER8dVXX6nMs27dOvGW2tnZ2YTlg/RJBgVlZWVJFikyQcOGDcPDww3EgY0bNw4ODt6wYYMF11Qxio2NTXBw8HfffVe+fHnDNdu3" +
            "bx8SErJz505thmt3d3fdW3FxcXIysxWt7t27X758ecqUKWJG9VxVrVpVO7WsadOmuVY4d+6czMWmLCI2Nlb3ulSpUiaPegUAAFomjmJXKldXVxcXl2vXrmmL" +
            "VapUEbMRyFeqVKnJkyePHTv28OHDISEhp0+fjo6OjomJ0Wg0lSpVatKkiaur68CBA8XFeSzI2tp6zJgxo0aNOnz48J49e06fPv306dPo6GgbGxtHR8fGjRt3" +
            "6dJl8ODB8rub1Gr1kCFDfHx8wsPD9+3b9+eff0ZFRUVHRyclJZUvX75KlSrNmzdv37794MGD69WrZ/755+TkSCbkDBs2zCJrknh5edWoUUNc1fGXX3758ssv" +
            "zZy1UrVq1SNHjvzxxx/r168PDw+PiYkpW7ass7Ozu7u7j4+Ph4eHRWbFmEOtVk+dOnXcuHEBAQFHjx6NiIh49uxZUlJSxYoVq1at2qBBgx49enh6ekrmdPn4" +
            "+IjLZW7dulUMe15O1apV+/7777/88st9+/YdOnTo3LlzMTExsbGxVlZWjo6O1atXd3d37969u4eHh+FetcJMtJCTkyMmr7PgIw8AAEos9Us+BAV4qSxZskSc" +
            "axQcHGza0j0AAAAoOAxXAwAAAKAoBDkAAAAAFIUgBwAAAICiEOQAAAAAUBSCHAAAAACKQpADAAAAQFEIcgAAAAAoCkEOAAAAAEUhyAEAAACgKAQ5AAAAABRF" +
            "rdFoivocAAAAAMBi6MkBAAAAoCgEOQAAAAAUhSAHAAAAgKIQ5AAAAABQFIIcAAAAAIpCkAMAAABAUQhyAAAAACgKQQ4AAAAARSHIAQAAAKAoBDkAAAAAFIUg" +
            "BwAAAICiEOQAAAAAUBSCHAAAAACKQpADAAAAQFEIcgAAAAAoCkEOAAAAAEUhyAEAAACgKAQ5AAAAABSFIAcAAACAohDkAAAAAFAUghwAAAAAikKQAwAAAEBR" +
            "CHIAAAAAKApBDgAAAABFIcgBAAAAoCgEOQAAAAAUhSAHAAAAgKIQ5AAAAABQFIIcAAAAAIpCkAMAAABAUQhyAAAAACgKQQ4AAAAARSHIAQAAAKAoBDkAAAAA" +
            "FIUgBwAAAICiEOQAAAAAUBSCHAAAAACKQpADAAAAQFEIcgAAAAAoCkEOAAAAAEUhyAEAAACgKAQ5AAAAABSFIAcAAACAohDkAAAAAFAUghwAAAAAikKQAwAA" +
            "AEBRCHIAAAAAKApBDgAAAABFIcgBCtWZM2fU/7K2to6Pj8+1ztOnT7V11Gr1xYsXC/0cAQAAireiCXLat2+vLhjvvPNOkXwiQKbmzZuXLl1apVLl5ORs3749" +
            "1zq///679kXZsmVfe+21wj1BAACAYo+eHKBQ2dnZ9e7dW/t69uzZMTExkgrR0dFz587Vvu7Xr5+trW2hnyMAAEDxRpADFLapU6eq1WqVSvXw4cM2bdqsX7/+" +
            "7t27mZmZjx8/DggIcHd3f/TokUqlsra2njZtWlGfLAAAQPFjU9QnAJQ4bm5u06ZNmzdvnjbOyWuM5Zw5c1q2bFnoZwcAAFDs0ZMDFIFvv/12zpw51tbWub5b" +
            "qlSphQsXTp8+vdDPCwAAQAleiiAnMDBQY6oZM2YU9ekDpvjqq68iIyO/+OKLdu3aVaxY0cbGplKlSq6urtOnT79x48ann35a1CcIAABQXDFcDSgy9erVmz9/" +
            "flGfBQAAgNK8FD05AAAAAGApBDkAAAAAFIXhaqqDBw+uW7cuLCwsKipKpVJVqVKlefPmXbt29fPzq1WrlqoEuH///u7du8PCwq5du/bgwYPExMSMjAw7O7uK" +
            "FSvWqVOnVatWPXv29Pb2LlOmTFGfKYqZzMzM/fv3Hzp06OTJk48fP46JibG1ta1WrVrNmjW7dOni7e3t5uZmZcWjFtNpNJqzZ8/u2rXr6NGjjx8/fvr0aXZ2" +
            "dvXq1WvWrNm5c+c+ffq4u7vzDQMASiJNUXBzcyugxAOjRo3KtdrixYt1dZycnLQb4+LivL298/pmrK2tx40bFxsba/gExJZVKlVwcHC+55yVldWiRQtxr/Hj" +
            "x+tX8/f3z/fns7W1dXBwqFu3bqdOncaMGRMUFJSRkaGR7dixYz179pRznTg6Os6fPz+vxlVm69Kli4HPLvMKkVwMf/31l36dwMBAsY6/v7/Gchdz6dKlC/ki" +
            "MZluQVJjtWzZUk776enpS5curVOnjuHWXFxcduzYkW9r2nTbOnv37s2r5u3bt8eMGePi4uLg4KBNXqfRaDIyMqpWrSq2cOXKFfnf1apVq8R9N2/erHsrODg4" +
            "18+V67WXl48//li/hcWLF+e7Y2hoqOQi0de0adOQkJB8myqIvziVpf8tAAAgX8l9wvfs2bOOHTvu2bMnrwrZ2dn+/v6tW7eOiIiw7KF//vnnv//+2yJNZWZm" +
            "JiYm3r179/jx4ytXrhw8eHCDBg3279+f744ZGRkffvhhly5dDh48KOdAz549mzp1apcuXZ4/f26JE0fhXSS5ysnJKbjGb9682aFDh4kTJz548MBwzWvXrvXv" +
            "379Pnz4vXrww/7hhYWEtW7ZcuXLltWvXEhMTs7OztdttbW2HDx8u1ty4caP8Zn/77Tfd63LlyvXv3z/fXfIKfvRlZ2dv27ZNZaT09PThw4d7eHjke5FcuXKl" +
            "d+/eb7/9dnp6urFHAQCg+CqhQU5WVtbAgQOvXbuWb8379+9369bt4sWLljp0TEzMzJkzVQXm/v37vXv3Xr9+vYE6WVlZgwYN+uWXX4xtPCwsLK+VK2FBBX2R" +
            "FGiQc+LEibZt2547d07+Lnv27HnjjTcePXpkznETEhIGDhyYV7D07rvvisWAgACZXQ1Pnjw5evSorjho0KCyZctaMMg5dOjQ06dPVcZ4/vy5p6fnli1b5O/y" +
            "22+/eXh48IQCAFBylNAg57vvvjt+/LjMyomJiX369ElISLDIoadNm1bQtxo5OTnvv/++gbvMb7/91kAXlmE7duyQ2fmDl/kischoIn3Xr1/38vJKTEw0dseL" +
            "Fy/269fPnN6GH374wUC00LJly1atWumKDx48EEMXA7Zu3SoGhH5+fnL2unPnjswnI5s3b1YZQ6PRDBky5M8//zRqL+1YspEjRxq7FwAAxVRJDHKeP38+d+5c" +
            "XdHV1XXt2rV3795NS0t79OhRcHBwnz59JLs8fPjw888/N//Q4eHha9euVRW8rKysL774Ite3njx5IpnhoFKpPDw81q1bd+nSpYSEhKysrKSkpHv37oWEhEyY" +
            "MKFChQqSyoXzEUqswrlIMjMzLd5mVlbWW2+9pd+Xor26bt26lZycnJCQcPXq1aVLl7Zs2VJSLSIiYvLkyaYdOikp6aeffjJcR9IJKXPEmjhWrVatWt27dzdQ" +
            "2c7OzqjOnLS0NPl9Plrff/+95ClD6dKlhw8fHhQUdOfOneTk5NTU1IcPH+7evfujjz6yt7cXa+7atWvp0qVGHQ4AgGKqJAY5ycnJaWlp//PhrawWLVqkHYL1" +
            "yiuvlC5dulatWgMGDNi1a1dwcLDk/mDt2rV37twx57g5OTkTJkww+Qm6/lTg9PT0+Pj4yMjI3bt3T5kypWLFimL9w4cPx8TE6LezYsUK7cfXsrGxCQgIOHDg" +
            "wKhRo5o1a6adrm1vb+/s7Ozl5bVs2bLIyEjJjZ122re4xcCsL+3kb60ePXrkVU3mY3XFM/MikU8MRY4fP2546l716tXltLly5UrJFBFHR8d9+/Zpr6569eqV" +
            "LVvWwcHBxcXlk08+iYiIWL58ua2trVjf39///PnzJnycDRs2iH1fCxcu1OYZy8rK0m309fUVDxcUFJSammq42Tt37pw6dUpXfPvttw1nKvP09NS9/v333/M9" +
            "7d27d+t6vSRfRa4ePHjw1VdfiVt69ux5/fr1gIAAHx+funXrli1b1s7OzsnJydvbe/ny5Xfv3u3Xr59Yf8aMGYU2aI1/CwCAIlQSgxwttVq9adOmyZMn53rX" +
            "MmDAgP3794u3HVlZWcuWLTPniKtXrz579qzKckqVKlWxYsWGDRt6e3t///33Fy9edHR0FCtcvnxZfy/JUP6ZM2dK5mRLVKtWbevWreXKldNtiY+Pf/LkiSU+" +
            "AQr8IpET5Oh31pkgJydn4cKF4pZq1aqFhYW9+eabuda3srL66KOP9uzZY2PzfxLZL1iwwISjr1ixQvd60KBBn376abVq1aysrMSbaUdHRzGb4osXL3bs2CG/" +
            "G0fOWLW+ffvq/mlcunTp1q1b8seq9erVy3BllUr1008/iV1wQ4cO3bt3b926dfOq7+jouGPHjvfee0+3JTk52YTJeAAAFDslN8iZMGHC22+/baBCx44dZ8+e" +
            "LW757bffTJ6uHR8fL6ZbNTmBrwHOzs6DBw82PCTp4cOHkZGRumK1atWmTJmSb8uOjo6SzpzY2FizzxdFcJEUXJBz7NgxsatTrVZv3LixQYMGhvfy8PD45ptv" +
            "xC2BgYHaFavkO378+KVLl3RFSV5jA+kH8h2xJj4RaNasmf4QO4mKFSt269ZNVzQ8FC0hISEkJET7unTp0j4+PoYbT01NXb16ta5Yp06dNWvWSELEXP344481" +
            "atTQFZcvX57vLgAAFHclNMhxcHD49ttv8602efLkypUr64qPHz82bSyN9sbr2bNn2tdOTk7/+c9/VAUgJSVFLNauXVtSwcnJ6erVq2vXrh07dmzr1q19fX1l" +
            "LvH56quvGjgQLKJwLhLtOCIxTBUvcpPt2rVLLHp4eIhjtwz47LPPnJycdMXs7GxjBymJa7x06tSpTZs2edXs3bt3tWrVdMUDBw5ER0fnVfnq1ati7DRixAg5" +
            "JzNo0CCZQU5QUJAu0YK3t3e+oebJkyfj4uJ0xXHjxon9qwbY29v7+vrqivfu3bt9+7acHQEAKL5KaJDj5+cn5/7Azs5OMpTrr7/+MuFw58+fX7lypa64aNEi" +
            "mXcnRjl+/Li44Eb16tUbNmwoqaNWq11cXN555x1/f/+IiIhFixbJbFwyYUCc6gCLKJyLROvZs2cZGRna1w4ODpLpZ6YJCwsTi+PGjZO5o42NzZgxY0z+K3v4" +
            "8KG4wOv48eMNH0u83c/KyjKQiFl8y8rKStzRgAEDBuhGwIaFhRkY2CmOVZPT+MmTJ8Vi586dVbJ16tTJQFMAAChPCQ1y5Ax/z/XmINdZLoZpNJoJEyboxrl1" +
            "79596NChKkvIyclJSUmJior6888/J0yY0K1bNzGjwIcffihnKrMBGRkZZ86cWbp0qbe3t2Q+UoEuJVmYxo0bp/6/rK2tK1WqVLdu3Q4dOowfP379+vWWyh5e" +
            "JBdJrsTxYOJAJnOInR5qtbpLly7y95UMhjxz5oz8fRcsWKAbluns7JzvoC/5I9bECTldunTR7xfNVfXq1d3d3bWvNRrNH3/8kWu1x48f6zqsKlSoIE4WkhlG" +
            "durUSS3bwIEDxX0LZ94XAABFKP/x3Irk6uoqs2br1q3FognDPDZs2KB7bmpra5tvotu8DBkyRH5lV1dXSRamfEVFRd24cePWv27evHn16tV//vmnIBINF/Rn" +
            "N1NOTs7zf927dy8sLOznn38uU6bM4MGD586dW6dOnQI6qKUuEpkePnyoe+3s7Gx+g3FxccnJybpi/fr1K1WqJH/3tm3bWllZ6WK8XLMC5ury5ctiyoH//Oc/" +
            "+Qb2zZs3b9OmTUREhLZ47ty5a9euubi4SKqdOXPm5s2bxo5V0xo0aNCJEye0r4ODg8eOHatfR5zgN3jw4NKlS+fb7L1791QWku83XJh/cQAAFISS2JNjZ2cn" +
            "jss3rGrVquZMuE9MTJw6daquOHHiRP3bKYvr27fvvn37SpUqlW/NBw8efPvtt926datYsaKTk1PXrl1Hjx797bffbtu27fLlyy9JhFPkUlNTN27c+Nprr/36" +
            "668F0X7hXyTXr1/Xvc43N4AJN83G9g7Z2dk5ODjoiuLMk1xlZ2c/fvx406ZNvXr10l2lvXr1EtOImblgjtiNY2dnJ0npIX9azpEjR3JN2SyOVTOc3lAnPj5e" +
            "/jkUWlMAALycSmKQI1lPxjDx3kubgNWoY3399de6Vdhr1ao1c+ZMVcErVapUvkthREVF+fn51a1bd8aMGUePHi2EEVnFXVJS0ujRo81MI/6SXCRXr17Vvdaf" +
            "uGUCyYIzRv2JaYk9P0lJSdnZ2QYq9+nTp1atWn5+fo8ePdKOjvvggw927NhheBEbneHDh4uPADZt2qS/7tPWrVt1xX79+kn+DxhWt25dXQ9wZmbmnj17JBVu" +
            "3LihGzBWq1atrl27ymmWIAcAAPlKYpAjZ2RIXpNPjJrlcuXKFXHc0cKFC8uXL68qeNu3b2/VqtXx48fzqvDXX381bdp006ZNiplaU2gmTZoUHh5uwQaL5CK5" +
            "cuWK7nW+aZHlkJPI2DCx21A7M0rmjlZWVitWrFi1apWcrkutKlWq9O3bV1d88OCBJJ/bn3/+qQ2fTBirJifHWkBAgPwFRnV0uSIAAEC+SmKQo1tiXA5JF4dR" +
            "Ca8mTJigy0LWtWvXYcOGqQpLYmJi//7979+/r//WuXPnPD095ax67ujo2KNHjy+//DI0NFR+sqzixd/fX7LIekZGRnx8/O3btw8cODBr1ixJR0dOTs7HH39s" +
            "wRMo/IskLS1NzITeqlUr89uUzMCRc3VJiLvITGuulZOTM2bMGB8fH6OGkhoesSaOVXN0dJSfpyTXIGffvn2Sni4xb5vMpG2SXuWKFStqzCDJYQAAgPKU0CBH" +
            "fgZkSQbYmjVrytzxt99+0z0etrGxMX8qeWBgoOROJScnJz09PT4+PjIycseOHZJ8XHFxcZ9//rmkkfT09JEjR4pJ2LTs7OxcXV1HjRo1e/bsDRs2HD9+PDo6" +
            "OiYm5uDBg3Pnzu3Zs6f5j+ot+9lzZWAVSPlsbW0rVqz46quvenh4fP3115cvX37//ffFCmfOnBHntJjD4heJHKdOndL1CdSvX79KlSoWD3IM5E3O1YsXL5KS" +
            "kuSv2/P7779fvHhxwoQJup7V33//3d3dXex+MaxXr17ixKHt27frlqzJysoKCgrSvfXWW2+ZkKXwtddea9KkifZ1cnLygQMHdG+dPXtWtyCvi4uLJLWJAeLX" +
            "os2NoVLEXxwAAAWhJAY52dnZ4nAdw86dOycWZU5gSEpKmjJliq74ySefNG3aVGVparW6VKlSFStWbNiwYb9+/bZu3bpq1Sqxwvbt2yWLxwcFBYnzMVQq1euv" +
            "v7579+6EhITw8PB169bNnDnTz8+vY8eOkowL4j2odtKCqmQoVarUihUrJGO69u3bZ37LhXOR6Dty5Ijudbdu3SzSZtmyZcV+hlu3buWbPMBAzmjJyrP6ypQp" +
            "06JFi2XLlu3YsUMXft+4cePNN9988eKFnCPa2NiIg9ASExNDQkK0rw8cOKBbktW0sWpaYtZmccSascvj6EjSpZAGGgAAA0pikKO/4oQBknUJ27ZtK2evOXPm" +
            "6B4q16xZc9asWapC8cEHH/Tp00dXzM7OPnTokFhhw4YNYtHT0/P48ePe3t75zmcQ0+kqaZ0cOaytrSWrVeY6DtBYRXWRiN0UPXv2tFSzr7/+uu61RqMRQ6l8" +
            "SSrLT/jm5eU1f/58XfHKlSuSbjf5I9Z0Q9TEoWsNGjRo3769yiTiiLVdu3Zpe49zcnLElAYy86pptWvXTizq5zMAAAAlPchZt26dnGpJSUnbtm0Tt8jJgxQZ" +
            "GblkyRJd8YcffiicfANa/fr1k5yMWLx48aJYnD9/vpzp2nFxcZLZ9iUtu3S9evXMyST+8lwkV/+lfW1nZ9e7d29LtdyhQwex6O/vL3PHzMzMNWvWGFiB17DJ" +
            "kyeL9bdt2ybOeDGgadOmYtiwZ8+e9PT0hIQEcflOk7txtDGJbg2iuLi4P//8UxvO6TpX3d3d8+2zEnXs2FEsrl+/Xv70wlmzZrm4uPj5+S1duvTEiRMpKSny" +
            "jwsAQHFUQoOc8PDw0NDQfKvNnz9fHP3Svn37WrVq5bvXJ598opvz0LlzZ6Me1pqvbNmyYlGc8ZyVlRUdHW3CI/MZM2ZIMjuVtERPjx8/NjNF8ktykYixh5eX" +
            "lwUjK0mn0KFDh/bu3Stnx4ULF0q+3u7du8s/rlqt9vf3F+eMTZo0SeZ8FbEzJzk5+dixY7/99ps4Y82cIEelUg0YMED3+vfffzdnrJr2CYuYdC4+Pn7OnDly" +
            "doyKilqyZMn169c3bdo0adKkTp06FUQmdAAAXiolNMhRqVTvvvuuZL6KRGho6Lx58wyMb8lVcHDw/v37C3kquUiSOVpcz97mX+K7p06dyrfB7777TlxUXkuS" +
            "LUrZNBqNpOvvlVdeMafBorpI4uLixPVMx44da8HGO3fu3KJFC3HLqFGjJB2J+g4fPixZF6hHjx5169Y16tDNmjX76KOPdMXo6Oj//Oc/cnYcPny4mFB+z549" +
            "4vfj7u5ev359lRnEEWt//PFHWlqaNtTR/u6STCH5qlGjho+Pj7jlhx9+ELNR5yojI8PPz0/MEqlWqwsz0yMAAEWi5AY5jx496tixo2T4ls7GjRv79u0rrkjo" +
            "5OQ0atSofJsVB/RPmDChefPmqkJ0/Phx8S5Nm74pr5hHpVKNHz/eQKR3//79QYMGTZ8+Xf8tSR4CBUtLSxs3btyxY8fEjWZOZSmqi+Sbb77RjVNq2rSph4eH" +
            "ZdufNGmSWIyJienQoUNeSRo0Gs2qVau8vLwkQx/FZAzyzZ49W0yV4e/vn9eftqhSpUri8M6NGzeePn3aUt04KpXqjTfe0GULePTo0ezZs3VdTJ6enpLcHnJ8" +
            "+umnki1+fn6zZs3Kq2c1Ojq6T58+hw8fFjcOGDDAzCgdAICXX8kNclQq1d27d9u2bfv+++8fOnQoJiYmIyPjwYMHAQEBXbp0GTlypC6lrNbcuXPt7OzybVOX" +
            "dqxGjRqzZ89WFTDtoi5XrlzZunXrqFGjunfvLt7uODg4dOnSRazv6ekpFiMjI5s1azZr1qzTp08/f/48Ozs7JSUlMjJyy5Ytw4YNa9Cggf46hspeMV2j0aSl" +
            "pcXGxv79999BQUGTJk165ZVXVq5cKdZp2bKlpMvChKMU5kWiFRER8eOPP+qKc+fOVavVlj2En5+fZGZObGysl5eXp6fn+vXrb9++nZqa+uLFi+vXr//0009t" +
            "27YdM2aM5O58wIABJixKox1AOHfuXF0xOzt7woQJcnYUu2fFq9rW1vatt95SmcfKykoMor7//nvda9MGKLq5uUn6kzUazezZsxs1ajRv3rxz587FxcVlZWXF" +
            "xsYePXr0008/bdCggWRcro2NjfhFAQCgVEW5+EkRcnJy0ia2ys7OXvMvw/UHDRokpxtH9P3334tJdc03ZMgQY3d55513JHkFxo0bt3LlSjE3Wnx8/Ox/GdWy" +
            "/AVJXnLj/mXULha8R7T4RZKrXIMZcbqIUS5evKhtsHHjxpL1gmxsbLZs2dKqVStJDBz6r3xbrl+/fr5/iQaMHj165cqVupzvx48f37hxo5+fn+G93nzzzVq1" +
            "aun3Z3p5eVlkBaFBgwatXr1a+1rXM2xvb2/y9//TTz+dOnVK8s3fu3fvy3/lu/uCBQskvbsAAChSCe3JmTdvXuPGjWVW7ty5s2RB9Hx16tTJ/LEuZqpevbp+" +
            "VuLmzZvLfMItcnR0lHwDFy5cUJVI48eP9/b2tkhTL8NFYnHOzs47d+7MdzVPffXq1du/f78JO+pYWVn9+OOPYkT3xRdf5Jt/zNraOtdfwVI/TY8ePSpUqCDZ" +
            "2L9/f3t7e9MatLe337NnT6NGjUzYd+zYsZMnTzbtuAAAFC8lNMgpX778rl275ORv9fPzCwkJkaQsM8za2nr58uWqIlW5cuUDBw5I1qHXWrhwYf/+/eU31bdv" +
            "34sXL44YMUJsLSIiouSsB6rj5+e3ePFiizT1MlwkBaRTp05hYWFG3YX37t07LCzMzFn+2jTWYsqyJ0+eyFl9SD+hSIUKFfr27auyhFKlSulHxcbmVZOoV69e" +
            "WFiYnHT2OjY2NnPmzJGf1xsAgOKuhAY5KpWqYcOG4eHhBm4UGjduHBwcvGHDBmOfuY4fP97MORvmUKvVPj4+ly5dyuscbGxsgoODv/vuu3zTB7dv3z4kJGTn" +
            "zp3axNnu7u66t+Li4uRkZlOMunXr/vLLLxs2bLC1tbVIg0V7kRS0Ro0aXbp0adGiRY6OjoZrtmjRIjAwcM+ePboJ+mZasGBBuXLldMVly5ZduXLF8C4uLi6u" +
            "rq7ilsGDB8uZgGdCjjVtv6hkapwJKleufPjw4W3btuXbI61Wqz08PCIiIr766iszDwoAQDFSQufkaFWtWvXIkSN//PHH+vXrw8PDY2JiypYt6+zs7O7u7uPj" +
            "4+HhYWVldBBYrVq1//73v6pCZGNjU758+SpVqjRr1szNzW3YsGH5ZuBVq9VTp04dN25cQEDA0aNHIyIinj17lpSUVLFixapVqzZo0KBHjx6enp6Ssfs+Pj4h" +
            "ISG64tatW8WwR0nUanX58uUrVapUu3bt9u3bd+nSpXfv3uISJWYq/Iuk8JUqVWry5Mljx449fPhwSEjI6dOno6OjY2JiNBpNpUqVmjRp4urqOnDgQDc3N8se" +
            "t2bNml999dW0adO0xaysrAkTJhw5csTwXs2aNbNsXjWRl5dXmTJldFnXhw4dKsnkbhq1Wj1kyBAfH5/w8PB9+/b9+eefUVFR0dHRSUlJ2n8IzZs3b9++/eDB" +
            "gyVL2QIAUBKoS8igoyVLloiD0YODg02e+AsUL7ppKtWrV3/y5Ik5TWlvoLWv9RMPFFMZGRk1a9aMi4vTFp2dne/evWvx1HMAAKAwldzhagCgUqlCQkJ0EY42" +
            "SxsRDgAAxR1BDoASTVw/19raevTo0UV6OgAAwAIIcgCUXDdv3tyzZ4+u2L9/fycnpyI9IwAAYAEEOQBKqLS0tFGjRolr406cOLFIzwgAAFgGQQ6AEiEhIeHA" +
            "gQNPnz7NzMyMjo7evn27q6vryZMndRXc3Nw6d+5cpOcIAAAso0SnkAZQcmRlZb355psGKsyZM6cQTwcAABQgenIAlAhVqlQpU6ZMXu8OHTrUw8OjcM8IAAAU" +
            "FHpyAIWz4FpY5cqVK9Yra9WqVevWrVv625s0abJq1aqiOCMAAFAg6MkBUFLUrl1bf2P79u0PHz5coUKFojgjAABQIOjJAVBSTJw4sWzZsufPn3/27FnFihXb" +
            "tGkzYsSI4cOHW1tbF/WpAQAAS1IX68EnAAAAACDBcDUAAAAAikKQAwAAAEBRCHIAAAAAKApBDgAAAABFIcgBAAAAoCgEOQAAAAAUhSAHAAAAgKIQ5AAAAABQ" +
            "FIIcAAAAAIpCkAMAAABAUQhyAAAAACgKQQ4AAAAARSHIAQAAAKAoBDkAAAAAFIUgBwAAAICiEOQAAAAAUBSCHAAAAACKQpADAAAAQFEIcgAAAAAoCkEOAAAA" +
            "AEUhyAEAAACgKAQ5AAAAABSFIAcAAACAohDkAAAAAFAUghwAAAAAikKQAwAAAEBRCHIAAAAAKApBDgAAAABFIcgBAAAAoCgEOQAAAAAUhSAHAAAAgKIQ5AAA" +
            "AABQFIIcAAAAAIpCkAMAAABAUQhyAAAAACgKQQ4AAAAARSHIAQAAAKAoBDkAAAAAFIUgBwAAAICiEOQAAAAAUBSCHAAAAACKQpADAAAAQFEIcgAAAAAoCkEO" +
            "AAAAAEUhyAEAAACgKAQ5AAAAABSFIAcAAACAohDkAAAAAFAUghwAAAAAikKQAwAAAEBRCHIAAAAAKApBDgAAAABFIcgBAAAAoCgEOQAAAAAUhSAHAAAAgKIQ" +
            "5AAAAABQFIIcAAAAAIpCkAMAAABAUQhyAAAAACgKQQ4AAAAARSHIAQAAAKAoBDkAAAAAFIUgBwAAAICiEOQAAAAAUBSCHAAAAACKQpADAAAAQFEIcgAAAAAo" +
            "CkEOAAAAAEUhyAEAAACgKAQ5AAAAABSFIAcAAACAohDkAAAAAFAUghwAAAAAikKQAwAAAEClJP8P+87saqGFbL0AAAAASUVORK5CYII="
    }
}
