@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.po4yka.chur.app.vault

import dev.po4yka.chur.ffi.ChurBuffer
import dev.po4yka.chur.ffi.withChurBuffer
import kotlinx.cinterop.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.CoreFoundation.*
import platform.CoreGraphics.*
import platform.ImageIO.*
import platform.Vision.*
import platform.posix.memcpy
import platform.posix.memcmp

/** Observes the real native request; it cannot supply results or replace Vision execution. */
internal interface IosVisionObserver {
    fun progress(fraction: Double)
    fun finished(errorCode: Long?)
}

/**
 * Vision receives cancellation while its synchronous request drains before image release.
 * Core Graphics decode and page rendering have no cancellation API and drain before release.
 */
class IosContentTextExtractor internal constructor(private val observer: IosVisionObserver?) : ContentTextExtractor {
    constructor() : this(null)
    override suspend fun extract(
        source: ContentTextSource,
        contentType: String,
        mediaClass: Int,
        cancelRequested: () -> Boolean,
        onPage: (Int, Int) -> Unit,
    ): ExtractedContent {
        var owned: ExtractedContent? = null
        try {
            val result = withContext(Dispatchers.Default) {
                val coroutine = currentCoroutineContext()
                val stopped = { !coroutine.isActive || cancelRequested() }
                checkContentSource(source, stopped)
                val extracted = when {
                    documentPreviewKind(contentType) == DocumentPreviewKind.TEXT -> extractPlainContent(source, stopped)
                    contentType == "application/pdf" || contentType.startsWith("image/") -> {
                        if (source.size !in 1..CONTENT_SOURCE_MAX_BYTES) throw ContentExtractionException(ContentExtractionFailure.LIMIT)
                        withChurBuffer(source.size.toInt()) { original ->
                            fillOriginal(source, original, stopped)
                            if (contentType == "application/pdf") extractPdf(original, source, stopped, onPage)
                            else {
                                val image = decodeImage(original) ?: throw ContentExtractionException(ContentExtractionFailure.UNAVAILABLE)
                                try {
                                    checkContentSource(source, stopped)
                                    val text = recognize(image, cancelled = { stopped() || !source.canRead() })
                                    checkContentSource(source, stopped)
                                    val bytes = boundedContentText(text)
                                    try {
                                        onPage(1, 1)
                                        ExtractedContent(bytes, contentTextTruncated(text, bytes.size))
                                    } catch (failure: Throwable) { bytes.fill(0); throw failure }
                                } finally { CGImageRelease(image) }
                            }
                        }
                    }
                    else -> throw ContentExtractionException(ContentExtractionFailure.UNSUPPORTED)
                }
                owned = extracted
                extracted
            }
            owned = null
            return result
        } catch (failure: Throwable) {
            owned?.bytes?.fill(0)
            throw failure
        }
    }

    private fun fillOriginal(source: ContentTextSource, original: ChurBuffer, stopped: () -> Boolean) {
        var offset = 0
        while (offset < original.capacityBytes) {
            checkContentSource(source, stopped)
            val count = minOf(64 * 1024, original.capacityBytes - offset)
            val bytes = source.read(offset.toLong(), count)
            try {
                if (bytes.isEmpty() || bytes.size > count) throw ContentExtractionException(ContentExtractionFailure.UNAVAILABLE)
                bytes.usePinned { memcpy(original.pointer + offset, it.addressOf(0), bytes.size.toULong()) }
                offset += bytes.size
            } finally { bytes.fill(0) }
        }
        checkContentSource(source, stopped)
    }

    private suspend fun extractPdf(original: ChurBuffer, source: ContentTextSource, stopped: () -> Boolean, onPage: (Int, Int) -> Unit): ExtractedContent {
        val first = renderPdfPage(original, 1) ?: throw ContentExtractionException(ContentExtractionFailure.UNAVAILABLE)
        val count = first.pages
        val pages = minOf(count, CONTENT_PDF_MAX_PAGES)
        val output = ByteArray(CONTENT_TEXT_MAX_BYTES)
        var used = 0
        var partial = count > pages
        var completed = 0
        try {
            for (number in 1..pages) {
                checkContentSource(source, stopped)
                val raster = if (number == 1) first else renderPdfPage(original, number)
                    ?: throw ContentExtractionException(ContentExtractionFailure.UNAVAILABLE)
                val text = try { recognizeRaster(raster, cancelled = { stopped() || !source.canRead() }) } finally { raster.close() }
                checkContentSource(source, stopped)
                val bytes = boundedContentText(text, CONTENT_TEXT_MAX_BYTES - used)
                partial = partial || contentTextTruncated(text, bytes.size)
                try {
                    bytes.copyInto(output, used)
                    used += bytes.size
                } finally { bytes.fill(0) }
                completed = number
                onPage(number, pages)
                if (used >= CONTENT_TEXT_MAX_BYTES - 4) { partial = true; break }
                output[used++] = '\n'.code.toByte()
            }
            return ExtractedContent(output.copyOf(used), partial, completed)
        } finally { first.close(); output.fill(0) }
    }

    private suspend fun recognizeRaster(raster: PdfPageRaster, cancelled: () -> Boolean): String {
        val bitmap = raster.bitmap
        val pixels = bitmap.peekPixels() ?: throw ContentExtractionException(ContentExtractionFailure.UNAVAILABLE)
        try {
            val coroutine = currentCoroutineContext()
            coroutine.ensureActive()
            if (cancelled()) throw CancellationException("Content extraction ended")
            // Rendering produces RGBA8888. Compare visible rows without stride padding;
            // every nonwhite pixel continues through Vision, including faint text.
            val visibleRowBytes = bitmap.width * 4
            val whiteRow = ByteArray(visibleRowBytes) { 0xff.toByte() }
            var opaqueWhite = true
            whiteRow.usePinned { reference ->
                for (row in 0 until bitmap.height) {
                    coroutine.ensureActive()
                    if (cancelled()) throw CancellationException("Content extraction ended")
                    val address = interpretCPointer<ByteVar>(pixels.addr + row.toLong() * bitmap.rowBytes)
                        ?: throw ContentExtractionException(ContentExtractionFailure.UNAVAILABLE)
                    if (memcmp(address, reference.addressOf(0), visibleRowBytes.toULong()) != 0) {
                        opaqueWhite = false
                        break
                    }
                }
            }
            coroutine.ensureActive()
            if (cancelled()) throw CancellationException("Content extraction ended")
            if (opaqueWhite) return ""

            val space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB)
            val context = CGBitmapContextCreate(interpretCPointer<ByteVar>(pixels.addr), bitmap.width.toULong(), bitmap.height.toULong(),
                8u, bitmap.rowBytes.toULong(), space,
                CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big)
            CGColorSpaceRelease(space)
            try {
                if (context == null) throw ContentExtractionException(ContentExtractionFailure.UNAVAILABLE)
                val image = CGBitmapContextCreateImage(context) ?: throw ContentExtractionException(ContentExtractionFailure.UNAVAILABLE)
                // The viewer raster is in Skia's top-left coordinates. Tell Vision about
                // its vertical mirror instead of changing the shared preview pixels.
                try { return recognize(image, kCGImagePropertyOrientationDownMirrored, cancelled) } finally { CGImageRelease(image) }
            } finally { if (context != null) CGContextRelease(context) }
        } finally { pixels.close() }
    }

    private suspend fun recognize(
        image: CGImageRef,
        orientation: CGImagePropertyOrientation = kCGImagePropertyOrientationUp,
        cancelled: () -> Boolean,
    ): String = coroutineScope {
        val request = VNRecognizeTextRequest { _, error -> observer?.finished(error?.code) }
        request.revision = VNRecognizeTextRequestRevision3
        request.recognitionLevel = VNRequestTextRecognitionLevelAccurate
        request.usesLanguageCorrection = true
        request.usesCPUOnly = true
        val supported = request.supportedRecognitionLanguagesAndReturnError(null).orEmpty().filterIsInstance<String>()
        val languages = listOf("en-US", "ru-RU").filter { it in supported }
        if (languages.isEmpty()) throw ContentExtractionException(ContentExtractionFailure.UNAVAILABLE)
        request.recognitionLanguages = languages
        // Vision may deliver progress on another queue. No Kotlin exception may cross this callback.
        request.progressHandler = { _, fraction, _ ->
            if (runCatching { observer?.progress(fraction); cancelled() }.getOrDefault(true)) request.cancel()
        }
        // A child starts its cancellation wait before performRequests enters native code.
        // Job cancellation resumes it even while this worker is inside synchronous Vision.
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { request.cancel() }
        }
        try {
            autoreleasepool {
                if (cancelled()) throw CancellationException("Content extraction ended")
                val handler = VNImageRequestHandler(cGImage = image, orientation = orientation, options = emptyMap<Any?, Any?>())
                val succeeded = handler.performRequests(listOf(request), null)
                if (cancelled()) throw CancellationException("Content extraction ended")
                if (!succeeded) throw ContentExtractionException(ContentExtractionFailure.UNAVAILABLE)
                request.results.orEmpty().filterIsInstance<VNRecognizedTextObservation>().joinToString("\n") {
                    (it.topCandidates(1u).firstOrNull() as? VNRecognizedText)?.string.orEmpty()
                }
            }
        } finally {
            // Keep the request and image alive until both native execution and cancellation drain.
            cancellation.cancel()
            withContext(NonCancellable) { cancellation.join() }
            request.progressHandler = null
        }
    }

    private fun decodeImage(original: ChurBuffer): CGImageRef? {
        val data = CFDataCreateWithBytesNoCopy(null, original.pointer.reinterpret(), original.capacityBytes.toLong(), kCFAllocatorNull) ?: return null
        try {
            val source = CGImageSourceCreateWithData(data, null) ?: return null
            try {
                return memScoped {
                    val size = alloc<LongVar> { value = 2000 }
                    val number = CFNumberCreate(null, kCFNumberSInt64Type, size.ptr) ?: return@memScoped null
                    val options = CFDictionaryCreateMutable(null, 4, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
                    if (options == null) { CFRelease(number); return@memScoped null }
                    try {
                        CFDictionarySetValue(options, kCGImageSourceCreateThumbnailFromImageAlways, kCFBooleanTrue)
                        CFDictionarySetValue(options, kCGImageSourceCreateThumbnailWithTransform, kCFBooleanTrue)
                        CFDictionarySetValue(options, kCGImageSourceShouldCacheImmediately, kCFBooleanTrue)
                        CFDictionarySetValue(options, kCGImageSourceThumbnailMaxPixelSize, number)
                        CGImageSourceCreateThumbnailAtIndex(source, 0u, options)
                    } finally { CFRelease(options); CFRelease(number) }
                }
            } finally { CFRelease(source) }
        } finally { CFRelease(data) }
    }
}
