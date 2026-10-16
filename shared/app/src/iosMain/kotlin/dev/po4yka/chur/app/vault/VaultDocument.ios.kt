@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.app.vault

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.app.theme.ViewerColors
import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurBuffer
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.ObjectDetail
import dev.po4yka.chur.ffi.ObjectProjection
import dev.po4yka.chur.ffi.withChurBuffer
import dev.po4yka.chur.vault.VaultRepository
import dev.po4yka.chur.vault.VaultState
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextConcatCTM
import platform.CoreGraphics.CGContextDrawPDFPage
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGDataProviderCreateWithData
import platform.CoreGraphics.CGDataProviderRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.kCGPDFCropBox
import platform.CoreGraphics.CGPDFDocumentCreateWithProvider
import platform.CoreGraphics.CGPDFDocumentGetNumberOfPages
import platform.CoreGraphics.CGPDFDocumentGetPage
import platform.CoreGraphics.CGPDFDocumentIsEncrypted
import platform.CoreGraphics.CGPDFDocumentRelease
import platform.CoreGraphics.CGPDFPageGetBoxRect
import platform.CoreGraphics.CGPDFPageGetDrawingTransform
import platform.CoreGraphics.CGPDFPageGetRotationAngle
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.CoreGraphics.kCGColorSpaceSRGB
import platform.posix.memset
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

@Composable
actual fun VaultDocument(
    vault: VaultRepository,
    generation: Long,
    projection: ObjectProjection,
    detail: ObjectDetail?,
    modifier: Modifier,
    contentPadding: PaddingValues,
) {
    val state by vault.state.collectAsState()
    if ((state as? VaultState.Unlocked)?.generation != generation) return
    if (PresentedState.of(projection) == PresentedState.QUARANTINED) {
        DocumentFallback(projection, detail, "This file is quarantined. Check its integrity before opening a preview.", modifier, contentPadding)
        return
    }
    if (detail == null) {
        DocumentFallback(projection, null, "Loading document…", modifier, contentPadding)
        return
    }
    when (documentPreviewKind(detail.contentType)) {
        DocumentPreviewKind.TEXT -> ReadOnlyTextDocument(vault, generation, projection, detail, modifier, contentPadding)
        DocumentPreviewKind.NONE -> DocumentFallback(projection, detail,
            "No internal preview is available for this file type.", modifier, contentPadding)
        DocumentPreviewKind.PDF -> if (detail.plaintextSize in 1..DOCUMENT_PDF_PREVIEW_MAX_BYTES) {
            PdfDocument(vault, generation, projection, detail, modifier, contentPadding)
        } else {
            DocumentFallback(projection, detail, "This PDF is outside the 32 MiB preview limit. Its complete original can still be exported.", modifier, contentPadding)
        }
    }
}

@Composable
private fun PdfDocument(
    vault: VaultRepository,
    generation: Long,
    projection: ObjectProjection,
    detail: ObjectDetail?,
    modifier: Modifier,
    contentPadding: PaddingValues,
) {
    var page by remember(generation, projection.id) { mutableStateOf(1) }
    var zoom by remember(generation, projection.id, page) { mutableStateOf(1f) }
    var offset by remember(generation, projection.id, page) { mutableStateOf(Offset.Zero) }
    var raster by remember(generation, projection.id) { mutableStateOf<PdfPageRaster?>(null) }
    var loading by remember(generation, projection.id) { mutableStateOf(true) }
    DisposableEffect(generation, projection.id) {
        onDispose { raster?.close(); raster = null }
    }
    DisposableEffect(raster) {
        val displayed = raster
        onDispose { displayed?.close() }
    }
    LaunchedEffect(generation, projection.id, page) {
        raster = null; loading = true
        var prepared: PdfPageRaster? = null
        try {
            withContext(Dispatchers.Default) { prepared = readPdfPage(vault, generation, projection, page) }
            currentCoroutineContext().ensureActive()
            if ((vault.state.value as? VaultState.Unlocked)?.generation == generation) {
                raster = prepared
                prepared = null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ChurFailure) {
            // Reader failures use the same private-data-free fallback.
        } finally {
            prepared?.close()
            loading = false
        }
    }
    val shown = raster
    if (shown == null) {
        DocumentFallback(projection, detail, if (loading) "Loading PDF preview…" else
            "This PDF cannot be previewed. It may be protected, damaged, or larger than the 32 MiB preview limit.",
            modifier, contentPadding)
    } else {
        Column(modifier.fillMaxSize().padding(contentPadding).padding(16.dp)) {
            DocumentHeader(projection, detail)
            Text("Page $page of ${shown.pages}", color = ViewerColors.content)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { page -= 1 }, enabled = page > 1) { Text("Previous") }
                TextButton(onClick = { page += 1 }, enabled = page < shown.pages) { Text("Next") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { zoom = (zoom * 1.5f).coerceAtMost(4f) }) { Text("Zoom") }
                TextButton(onClick = { zoom = 1f; offset = Offset.Zero }) { Text("Fit") }
            }
            val transform = rememberTransformableState { factor, pan, _ ->
                zoom = (zoom * factor).coerceIn(1f, 4f)
                offset = if (zoom == 1f) Offset.Zero else offset + pan
            }
            Box(Modifier.fillMaxWidth().weight(1f).clipToBounds().transformable(transform)) {
                Image(shown.image, contentDescription = "PDF page $page of ${shown.pages}",
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = zoom; scaleY = zoom; translationX = offset.x; translationY = offset.y
                    })
            }
        }
    }
}

/** The encoded PDF stays in a bounded native buffer and is cleared after each page. */
private suspend fun readPdfPage(vault: VaultRepository, generation: Long, projection: ObjectProjection, page: Int): PdfPageRaster? {
    fun requireSession() {
        if ((vault.state.value as? VaultState.Unlocked)?.generation != generation) {
            throw ChurFailure(ChurStatus.SESSION_EXPIRED, "the document preview")
        }
    }
    requireSession()
    val reader = vault.leaseReader(projection.objectId)
    try {
        val info = vault.readerContentInfo(reader)
        if (!info.complete || info.plaintextSize !in 1..DOCUMENT_PDF_PREVIEW_MAX_BYTES) return null
        return withChurBuffer(info.plaintextSize.toInt()) { buffer ->
            vault.readLeasedInto(reader, buffer)
            currentCoroutineContext().ensureActive()
            requireSession()
            renderPdfPage(buffer, page)
        }
    } finally { vault.releaseReader(reader) }
}

/** One page raster, explicitly wiped when replaced or removed from the scene. */
internal class PdfPageRaster(private val bitmap: Bitmap, val pages: Int) {
    val image: ImageBitmap = bitmap.asComposeImageBitmap()
    private var closed = false
    fun close() {
        if (closed) return
        closed = true
        bitmap.peekPixels()?.let { pixels ->
            memset(interpretCPointer<ByteVar>(pixels.addr), 0, (bitmap.rowBytes.toLong() * bitmap.height).toULong())
            pixels.close()
        }
        bitmap.close()
    }
}

/** Core Graphics reads the native allocation directly and retains no document after return. */
internal fun renderPdfPage(original: ChurBuffer, number: Int): PdfPageRaster? {
    if (original.capacityBytes <= 0 || original.capacityBytes > DOCUMENT_PDF_PREVIEW_MAX_BYTES || number < 1) return null
    val provider = CGDataProviderCreateWithData(null, original.pointer, original.capacityBytes.toULong(), null) ?: return null
    val document = CGPDFDocumentCreateWithProvider(provider)
    CGDataProviderRelease(provider)
    if (document == null) return null
    try {
        if (CGPDFDocumentIsEncrypted(document)) return null
        val count = CGPDFDocumentGetNumberOfPages(document)
        if (number.toULong() > count || count > Int.MAX_VALUE.toULong()) return null
        val page = CGPDFDocumentGetPage(document, number.toULong()) ?: return null
        val box = CGPDFPageGetBoxRect(page, kCGPDFCropBox)
        var width = box.useContents { size.width }
        var height = box.useContents { size.height }
        if (!width.isFinite() || !height.isFinite() || width <= 0 || height <= 0) return null
        if (CGPDFPageGetRotationAngle(page) % 180 != 0) { val swap = width; width = height; height = swap }
        val (rasterWidth, rasterHeight) = pdfRasterSize(width, height) ?: return null
        val bitmap = Bitmap()
        if (!bitmap.allocPixels(ImageInfo(rasterWidth, rasterHeight, ColorType.RGBA_8888, ColorAlphaType.PREMUL))) {
            bitmap.close(); return null
        }
        val pixels = bitmap.peekPixels()
        if (pixels == null) { bitmap.close(); return null }
        val space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB)
        val context = CGBitmapContextCreate(interpretCPointer<ByteVar>(pixels.addr), rasterWidth.toULong(), rasterHeight.toULong(),
            8u, bitmap.rowBytes.toULong(), space,
            CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big)
        CGColorSpaceRelease(space)
        pixels.close()
        if (context == null) { bitmap.close(); return null }
        val bounds = CGRectMake(0.0, 0.0, rasterWidth.toDouble(), rasterHeight.toDouble())
        try {
            CGContextSetRGBFillColor(context, 1.0, 1.0, 1.0, 1.0)
            CGContextFillRect(context, bounds)
            CGContextTranslateCTM(context, 0.0, rasterHeight.toDouble())
            CGContextScaleCTM(context, 1.0, -1.0)
            CGContextConcatCTM(context, CGPDFPageGetDrawingTransform(page, kCGPDFCropBox, bounds, 0, true))
            CGContextDrawPDFPage(context, page)
        } finally { CGContextRelease(context) }
        return PdfPageRaster(bitmap, count.toInt())
    } finally { CGPDFDocumentRelease(document) }
}

/** Both the edge and decimal four-million-pixel area are hard allocation bounds. */
internal fun pdfRasterSize(width: Double, height: Double): Pair<Int, Int>? {
    val area = width * height
    if (!width.isFinite() || !height.isFinite() || !area.isFinite() || width <= 0 || height <= 0) return null
    val scale = minOf(1.0, DOCUMENT_PDF_RASTER_EDGE_MAX / max(width, height), sqrt(4_000_000.0 / area))
    val rasterWidth = max(1, floor(width * scale).toInt())
    val rasterHeight = max(1, floor(height * scale).toInt())
    return (rasterWidth to rasterHeight).takeIf { rasterWidth.toLong() * rasterHeight <= 4_000_000L }
}
