package dev.po4yka.chur.app.effects

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Real Skia canvas tests. Android host stubs cannot verify these pixel results. */
class PixelateRasterIosTest {
    @Test
    fun an_opaque_frame_has_no_feather_and_a_replacement_frame_clears_old_pixels() {
        val raster = raster(feather = false)
        val first = render(raster) {
            assertEquals(Size(64f, 64f), size, "content must retain its logical layout size")
            drawRect(Color.Red)
        }
        assertEquals(0xFFFF0000.toInt(), pixel(first, 0, 0))
        assertEquals(0xFFFF0000.toInt(), pixel(first, 32, 32))
        val replacement = render(raster) {
            drawRect(Color.Blue, size = Size(16f, 16f))
        }
        assertEquals(0xFF0000FF.toInt(), pixel(replacement, 4, 4))
        assertEquals(0, pixel(replacement, 60, 60))
        val empty = render(raster) {}
        assertTrue(IntArray(64 * 64).also { empty.readPixels(it) }.all { it == 0 })
    }

    @Test
    fun feather_reduces_edge_alpha_without_removing_the_centre() {
        val image = render(raster(feather = true)) { drawRect(Color.Green) }
        assertEquals(255, pixel(image, 32, 32) ushr 24)
        assertTrue(pixel(image, 0, 32) ushr 24 in 1..254)
        assertTrue(pixel(image, 0, 0) ushr 24 < pixel(image, 0, 32) ushr 24)
    }

    @Test
    fun a_resize_uses_the_new_grid_without_pixels_from_the_previous_buffer() {
        render(raster(feather = false)) { drawRect(Color.Red) }
        val resized = render(raster(feather = false, size = Size(32f, 16f))) {
            drawRect(Color.Blue)
        }
        assertEquals(32 to 16, resized.width to resized.height)
        assertEquals(0xFF0000FF.toInt(), pixel(resized, 31, 15))
    }

    @Test
    fun a_content_failure_restores_the_outer_canvas_before_the_next_draw() {
        val raster = raster(feather = true)
        val image = ImageBitmap(64, 64)
        val canvas = Canvas(image)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(64f, 64f)) {
            assertFailsWith<IllegalStateException> {
                drawPixelated(raster) { error("software content failed") }
            }
            assertEquals(canvas, drawContext.canvas)
            assertEquals(Size(64f, 64f), size)
            drawRect(Color.Blue, topLeft = Offset.Zero, blendMode = BlendMode.Src)
        }
        assertEquals(0xFF0000FF.toInt(), pixel(image, 32, 32))
    }

    private fun raster(feather: Boolean, size: Size = Size(64f, 64f)) =
        PixelateRaster(pixelateGeometry(size, 1f, DefaultBlockRatio, 8.dp, 8.dp)!!, feather)

    private fun render(raster: PixelateRaster, content: DrawScope.() -> Unit): ImageBitmap {
        val image = ImageBitmap(raster.geometry.size.width.toInt(), raster.geometry.size.height.toInt())
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), raster.geometry.size) {
            drawPixelated(raster, content)
        }
        return image
    }

    private fun pixel(image: ImageBitmap, x: Int, y: Int): Int =
        IntArray(1).also { image.readPixels(it, x, y, 1, 1) }[0]
}
