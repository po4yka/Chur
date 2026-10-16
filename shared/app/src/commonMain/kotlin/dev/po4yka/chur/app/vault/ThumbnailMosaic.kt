package dev.po4yka.chur.app.vault

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * Reduces a decoded private derivative to an opaque square mosaic.
 *
 * This runs only on a thumbnail cache miss, on its worker dispatcher. The
 * caller displays the returned 16 px image with [FilterQuality.None]. Neither
 * the original file nor a full-size image enters this transformation.
 */
internal fun thumbnailMosaic(source: ImageBitmap): ImageBitmap {
    require(source.width > 0 && source.height > 0)
    val side = minOf(source.width, source.height)
    val coarse = ImageBitmap(8, 8)
    val coarseCanvas = Canvas(coarse)
    val paint = Paint().apply {
        color = Color(0xFF808080)
        isAntiAlias = false
        filterQuality = FilterQuality.Low
    }
    coarseCanvas.drawRect(Rect(0f, 0f, 8f, 8f), paint)
    coarseCanvas.drawImageRect(
        image = source,
        srcOffset = IntOffset((source.width - side) / 2, (source.height - side) / 2),
        srcSize = IntSize(side, side),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(8, 8),
        paint = paint,
    )
    val mosaic = ImageBitmap(16, 16)
    Canvas(mosaic).drawImageRect(
        image = coarse,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(8, 8),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(16, 16),
        paint = paint,
    )
    return mosaic
}
