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
 * [blockRatio] sets the nominal cell size as a fraction of the content height, before the
 * [minBlockSize] and [maxBlockSize] limits apply. [featherEdges] reduces alpha at the edges;
 * it does not blur the mosaic or blend it with the original content.
 *
 * Android hardware bitmaps and content that requires hardware graphics layers are not supported.
 * Decode images to software bitmaps before use. Rendering errors propagate; the effect does not
 * display the original content as a fallback. Semantics and the source data remain accessible.
 * Use the thumbnail rendition cache for the gallery, rather than this per-frame effect.
 *
 * Buffer storage is limited to 16 MiB. Extreme layouts use a coarser grid to meet this limit.
 */
public fun Modifier.mosaic(
    blockRatio: Float = DefaultBlockRatio,
    minBlockSize: Dp = 4.dp,
    maxBlockSize: Dp = 14.dp,
    featherEdges: Boolean = true,
): Modifier {
    validateMosaicParameters(blockRatio, minBlockSize, maxBlockSize)
    return drawWithCache {
        val geometry = mosaicGeometry(size, density, blockRatio, minBlockSize, maxBlockSize)
        val raster = geometry?.let { MosaicRaster(it, featherEdges) }
        onDrawWithContent {
            if (raster != null) {
                val contentScope = this
                drawMosaic(raster) { contentScope.drawContent() }
            }
        }
    }
}

/**
 * Draws [content] as one mosaic, with the same parameters as [Modifier.mosaic].
 * [modifier] applies to the outer container; its padding and background stay outside the mosaic.
 * This changes pixels only. It does not remove content from accessibility or protect source data.
 * See [Modifier.mosaic] for the software canvas restriction and buffer limit.
 */
@Composable
public fun Mosaic(
    modifier: Modifier = Modifier,
    blockRatio: Float = DefaultBlockRatio,
    minBlockSize: Dp = 4.dp,
    maxBlockSize: Dp = 14.dp,
    featherEdges: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(modifier.mosaic(blockRatio, minBlockSize, maxBlockSize, featherEdges)) {
        content()
    }
}

internal fun validateMosaicParameters(blockRatio: Float, minBlockSize: Dp, maxBlockSize: Dp) {
    require(blockRatio.isFinite() && blockRatio > 0f) { "blockRatio must be finite and positive" }
    require(minBlockSize.value.isFinite() && minBlockSize.value > 0f) {
        "minBlockSize must be finite and positive"
    }
    require(maxBlockSize.value.isFinite() && maxBlockSize.value > 0f) {
        "maxBlockSize must be finite and positive"
    }
    require(minBlockSize <= maxBlockSize) { "minBlockSize must not exceed maxBlockSize" }
}

// An ARGB pixel uses four bytes. The intermediate bitmap has four times the coarse pixel count.
internal const val MosaicBufferByteLimit: Long = 16L * 1024L * 1024L
private const val MosaicMaxCoarsePixels: Long = MosaicBufferByteLimit / (4L * 5L)

internal data class MosaicGeometry(
    val contentSizePx: Size,
    val coarseSize: IntSize,
    val intermediateSizePx: IntSize,
    val nominalCellSizePx: Float,
)

internal fun mosaicGeometry(
    size: Size,
    density: Float,
    blockRatio: Float,
    minBlockSize: Dp,
    maxBlockSize: Dp,
): MosaicGeometry? {
    validateMosaicParameters(blockRatio, minBlockSize, maxBlockSize)
    require(density.isFinite() && density > 0f) { "density must be finite and positive" }
    require(size.width.isFinite() && size.height.isFinite()) { "layout size must be finite" }
    require(size.width.toDouble() <= Int.MAX_VALUE && size.height.toDouble() <= Int.MAX_VALUE) {
        "layout size exceeds bitmap destination coordinates"
    }
    if (size.width < 1f || size.height < 1f) return null

    // Double arithmetic also handles finite Float parameters whose product exceeds Float.MAX_VALUE.
    val nominalCellSizePx = (size.height.toDouble() * blockRatio)
        .coerceIn(minBlockSize.value.toDouble() * density, maxBlockSize.value.toDouble() * density)
        .coerceAtLeast(1.0)
    val width = floor(size.width / nominalCellSizePx + 0.5).coerceAtLeast(1.0)
    val height = floor(size.height / nominalCellSizePx + 0.5).coerceAtLeast(1.0)
    val reduction = max(
        1.0,
        max(
            sqrt(width * height / MosaicMaxCoarsePixels),
            max(width / MosaicMaxCoarsePixels, height / MosaicMaxCoarsePixels),
        ),
    )
    val coarseWidth = floor(width / reduction).toInt().coerceAtLeast(1)
    val coarseHeight = floor(height / reduction).toInt().coerceAtLeast(1)
    check(coarseWidth.toLong() * coarseHeight <= MosaicMaxCoarsePixels)
    return MosaicGeometry(
        size,
        IntSize(coarseWidth, coarseHeight),
        IntSize(coarseWidth * 2, coarseHeight * 2),
        (nominalCellSizePx * reduction).coerceAtMost(Float.MAX_VALUE.toDouble()).toFloat(),
    )
}

/** All native buffers and masks belong to one drawWithCache instance, never a global cache. */
internal class MosaicRaster(val geometry: MosaicGeometry, featherEdges: Boolean) {
    val coarseBitmap = ImageBitmap(geometry.coarseSize.width, geometry.coarseSize.height)
    val intermediateBitmap = ImageBitmap(
        geometry.intermediateSizePx.width, geometry.intermediateSizePx.height,
    )
    val coarseCanvas = Canvas(coarseBitmap)
    val intermediateCanvas = Canvas(intermediateBitmap)
    val layerPaint = Paint()
    val horizontalFeather = geometry.nominalCellSizePx.coerceAtMost(geometry.contentSizePx.width / 2f)
    val verticalFeather = geometry.nominalCellSizePx.coerceAtMost(geometry.contentSizePx.height / 2f)
    val masks = if (featherEdges) {
        listOf(
            Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), 0f, horizontalFeather),
            Brush.horizontalGradient(
                listOf(Color.Black, Color.Transparent),
                geometry.contentSizePx.width - horizontalFeather,
                geometry.contentSizePx.width,
            ),
            Brush.verticalGradient(listOf(Color.Transparent, Color.Black), 0f, verticalFeather),
            Brush.verticalGradient(
                listOf(Color.Black, Color.Transparent),
                geometry.contentSizePx.height - verticalFeather,
                geometry.contentSizePx.height,
            ),
        )
    } else {
        emptyList()
    }
}

internal fun DrawScope.drawMosaic(raster: MosaicRaster, content: DrawScope.() -> Unit) {
    val geometry = raster.geometry
    val coarseSize = Size(geometry.coarseSize.width.toFloat(), geometry.coarseSize.height.toFloat())
    val intermediateSizePx = Size(
        geometry.intermediateSizePx.width.toFloat(), geometry.intermediateSizePx.height.toFloat(),
    )
    // Content keeps its logical layout size; only the target bitmap and transform are coarse.
    // Using coarseSize here would scale size-relative draw commands twice.
    draw(this, layoutDirection, raster.coarseCanvas, geometry.contentSizePx) {
        drawRect(Color.Transparent, size = coarseSize, blendMode = BlendMode.Src)
        scale(
            geometry.coarseSize.width / geometry.contentSizePx.width,
            geometry.coarseSize.height / geometry.contentSizePx.height,
            Offset.Zero,
        ) {
            content()
        }
    }
    draw(this, layoutDirection, raster.intermediateCanvas, intermediateSizePx) {
        drawRect(Color.Transparent, blendMode = BlendMode.Src)
        drawImage(
            raster.coarseBitmap,
            srcSize = geometry.coarseSize,
            dstSize = geometry.intermediateSizePx,
            filterQuality = FilterQuality.Low,
            blendMode = BlendMode.Src,
        )
    }
    val canvas = drawContext.canvas
    if (raster.masks.isNotEmpty()) {
        canvas.saveLayer(Rect(Offset.Zero, geometry.contentSizePx), raster.layerPaint)
    }
    try {
        drawImage(
            raster.intermediateBitmap,
            srcSize = geometry.intermediateSizePx,
            dstSize = IntSize(geometry.contentSizePx.width.roundToInt(), geometry.contentSizePx.height.roundToInt()),
            filterQuality = FilterQuality.None,
        )
        if (raster.masks.isNotEmpty()) {
            val width = geometry.contentSizePx.width
            val height = geometry.contentSizePx.height
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
