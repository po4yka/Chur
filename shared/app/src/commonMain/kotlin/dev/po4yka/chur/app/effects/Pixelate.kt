package dev.po4yka.chur.app.effects

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

public const val DefaultBlockRatio: Float = 0.22f

/** Historical text-rendering reference. This value is not a photo privacy guarantee. */
public const val MinimumSafeBlockRatio: Float = 0.18f

/**
 * Draws content through software bitmap canvases as a mosaic.
 *
 * Android hardware bitmaps and content that requires hardware graphics layers are not supported.
 * Decode images to software bitmaps before use. Rendering errors propagate; the effect does not
 * display the original content as a fallback. Semantics and the source data remain accessible.
 * Use the thumbnail rendition cache for the gallery, rather than this per-frame effect.
 *
 * Buffer storage is limited to 16 MiB. Extreme layouts use a coarser grid to meet this limit.
 */
public fun Modifier.pixelate(
    blockRatio: Float = DefaultBlockRatio,
    minBlockSize: Dp = 4.dp,
    maxBlockSize: Dp = 14.dp,
    featherEdges: Boolean = true,
): Modifier {
    validatePixelateParameters(blockRatio, minBlockSize, maxBlockSize)
    return drawWithCache {
        val geometry = pixelateGeometry(size, density, blockRatio, minBlockSize, maxBlockSize)
        val raster = geometry?.let { PixelateRaster(it, featherEdges) }
        onDrawWithContent {
            if (raster != null) {
                val contentScope = this
                drawPixelated(raster) { contentScope.drawContent() }
            }
        }
    }
}

/**
 * Draws [content] as one mosaic, with the same parameters as [Modifier.pixelate].
 * [modifier] is outside the effect: its padding and background are not pixelated.
 * This changes pixels only. It does not remove content from accessibility or protect source data.
 * See [Modifier.pixelate] for the software canvas restriction and buffer limit.
 */
@Composable
public fun Pixelate(
    modifier: Modifier = Modifier,
    blockRatio: Float = DefaultBlockRatio,
    minBlockSize: Dp = 4.dp,
    maxBlockSize: Dp = 14.dp,
    featherEdges: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(modifier.pixelate(blockRatio, minBlockSize, maxBlockSize, featherEdges)) {
        content()
    }
}

internal fun validatePixelateParameters(blockRatio: Float, minBlockSize: Dp, maxBlockSize: Dp) {
    require(blockRatio.isFinite() && blockRatio > 0f) { "blockRatio must be finite and positive" }
    require(minBlockSize.value.isFinite() && minBlockSize.value > 0f) {
        "minBlockSize must be finite and positive"
    }
    require(maxBlockSize.value.isFinite() && maxBlockSize.value > 0f) {
        "maxBlockSize must be finite and positive"
    }
    require(minBlockSize <= maxBlockSize) { "minBlockSize must not exceed maxBlockSize" }
}

// An ARGB pixel uses four bytes. The fine image has four times as many pixels as the coarse one.
internal const val PixelateBufferByteLimit: Long = 16L * 1024L * 1024L
private const val PixelateMaxCoarsePixels: Long = PixelateBufferByteLimit / (4L * 5L)

internal data class PixelateGeometry(
    val size: Size,
    val coarseSize: IntSize,
    val fineSize: IntSize,
    val cell: Float,
)

internal fun pixelateGeometry(
    size: Size,
    density: Float,
    blockRatio: Float,
    minBlockSize: Dp,
    maxBlockSize: Dp,
): PixelateGeometry? {
    validatePixelateParameters(blockRatio, minBlockSize, maxBlockSize)
    require(density.isFinite() && density > 0f) { "density must be finite and positive" }
    require(size.width.isFinite() && size.height.isFinite()) { "layout size must be finite" }
    require(size.width.toDouble() <= Int.MAX_VALUE && size.height.toDouble() <= Int.MAX_VALUE) {
        "layout size exceeds bitmap destination coordinates"
    }
    if (size.width < 1f || size.height < 1f) return null

    // Double arithmetic also handles finite Float parameters whose product exceeds Float.MAX_VALUE.
    val cell = (size.height.toDouble() * blockRatio)
        .coerceIn(minBlockSize.value.toDouble() * density, maxBlockSize.value.toDouble() * density)
        .coerceAtLeast(1.0)
    val width = floor(size.width / cell + 0.5).coerceAtLeast(1.0)
    val height = floor(size.height / cell + 0.5).coerceAtLeast(1.0)
    val reduction = max(
        1.0,
        max(
            sqrt(width * height / PixelateMaxCoarsePixels),
            max(width / PixelateMaxCoarsePixels, height / PixelateMaxCoarsePixels),
        ),
    )
    val coarseWidth = floor(width / reduction).toInt().coerceAtLeast(1)
    val coarseHeight = floor(height / reduction).toInt().coerceAtLeast(1)
    check(coarseWidth.toLong() * coarseHeight <= PixelateMaxCoarsePixels)
    return PixelateGeometry(
        size,
        IntSize(coarseWidth, coarseHeight),
        IntSize(coarseWidth * 2, coarseHeight * 2),
        (cell * reduction).coerceAtMost(Float.MAX_VALUE.toDouble()).toFloat(),
    )
}

/** All native buffers and masks belong to one drawWithCache instance, never a global cache. */
internal class PixelateRaster(val geometry: PixelateGeometry, featherEdges: Boolean) {
    val coarse = ImageBitmap(geometry.coarseSize.width, geometry.coarseSize.height)
    val fine = ImageBitmap(geometry.fineSize.width, geometry.fineSize.height)
    val coarseCanvas = Canvas(coarse)
    val fineCanvas = Canvas(fine)
    val layerPaint = Paint()
    val horizontalFeather = geometry.cell.coerceAtMost(geometry.size.width / 2f)
    val verticalFeather = geometry.cell.coerceAtMost(geometry.size.height / 2f)
    val masks = if (featherEdges) {
        listOf(
            Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), 0f, horizontalFeather),
            Brush.horizontalGradient(
                listOf(Color.Black, Color.Transparent),
                geometry.size.width - horizontalFeather,
                geometry.size.width,
            ),
            Brush.verticalGradient(listOf(Color.Transparent, Color.Black), 0f, verticalFeather),
            Brush.verticalGradient(
                listOf(Color.Black, Color.Transparent),
                geometry.size.height - verticalFeather,
                geometry.size.height,
            ),
        )
    } else {
        emptyList()
    }
}

internal fun DrawScope.drawPixelated(raster: PixelateRaster, content: DrawScope.() -> Unit) {
    val geometry = raster.geometry
    val coarseSize = Size(geometry.coarseSize.width.toFloat(), geometry.coarseSize.height.toFloat())
    val fineSize = Size(geometry.fineSize.width.toFloat(), geometry.fineSize.height.toFloat())
    // Content keeps its logical layout size; only the target bitmap and transform are coarse.
    // Using coarseSize here would scale size-relative draw commands twice.
    draw(this, layoutDirection, raster.coarseCanvas, geometry.size) {
        drawRect(Color.Transparent, size = coarseSize, blendMode = BlendMode.Src)
        scale(
            geometry.coarseSize.width / geometry.size.width,
            geometry.coarseSize.height / geometry.size.height,
            Offset.Zero,
        ) {
            content()
        }
    }
    draw(this, layoutDirection, raster.fineCanvas, fineSize) {
        drawRect(Color.Transparent, blendMode = BlendMode.Src)
        drawImage(
            raster.coarse,
            srcSize = geometry.coarseSize,
            dstSize = geometry.fineSize,
            filterQuality = FilterQuality.Low,
            blendMode = BlendMode.Src,
        )
    }
    val canvas = drawContext.canvas
    if (raster.masks.isNotEmpty()) {
        canvas.saveLayer(Rect(Offset.Zero, geometry.size), raster.layerPaint)
    }
    try {
        drawImage(
            raster.fine,
            srcSize = geometry.fineSize,
            dstSize = IntSize(geometry.size.width.roundToInt(), geometry.size.height.roundToInt()),
            filterQuality = FilterQuality.None,
        )
        if (raster.masks.isNotEmpty()) {
            val width = geometry.size.width
            val height = geometry.size.height
            val horizontal = raster.horizontalFeather
            val vertical = raster.verticalFeather
            drawRect(raster.masks[0], size = Size(horizontal, height), blendMode = BlendMode.DstIn)
            drawRect(
                raster.masks[1], Offset(width - horizontal, 0f), Size(horizontal, height),
                blendMode = BlendMode.DstIn,
            )
            drawRect(raster.masks[2], size = Size(width, vertical), blendMode = BlendMode.DstIn)
            drawRect(
                raster.masks[3], Offset(0f, height - vertical), Size(width, vertical),
                blendMode = BlendMode.DstIn,
            )
        }
    } finally {
        if (raster.masks.isNotEmpty()) canvas.restore()
    }
}
