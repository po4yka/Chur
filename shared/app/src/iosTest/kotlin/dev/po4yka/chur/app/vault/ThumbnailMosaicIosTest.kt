package dev.po4yka.chur.app.vault

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Executes the shared mosaic through the native Skia canvas. */
class ThumbnailMosaicIosTest {
    @Test
    fun square_portrait_and_panorama_use_only_the_center_square() {
        for ((width, height) in listOf(32 to 32, 16 to 48, 64 to 16)) {
            val image = ImageBitmap(width, height)
            val canvas = Canvas(image)
            val paint = Paint().apply { color = Color.Red; isAntiAlias = false }
            canvas.drawRect(Rect(0f, 0f, width.toFloat(), height.toFloat()), paint)
            val side = minOf(width, height)
            val left = (width - side) / 2f
            val top = (height - side) / 2f
            paint.color = Color.Green
            canvas.drawRect(Rect(left, top, left + side, top + side), paint)
            val mosaic = thumbnailMosaic(image)
            assertEquals(16 to 16, mosaic.width to mosaic.height)
            assertTrue(pixels(mosaic).all { it == 0xFF00FF00.toInt() }, "$width x $height crop")
        }
    }

    @Test
    fun transparent_input_is_gray_and_a_previous_source_cannot_show_through() {
        val red = ImageBitmap(8, 8)
        Canvas(red).drawRect(Rect(0f, 0f, 8f, 8f), Paint().apply { color = Color.Red })
        assertTrue(pixels(thumbnailMosaic(red)).all { it == 0xFFFF0000.toInt() })
        val transparent = ImageBitmap(8, 24)
        assertTrue(pixels(thumbnailMosaic(transparent)).all { it == 0xFF808080.toInt() })
    }

    @Test
    fun spatial_detail_is_reduced_then_interpolated_and_alpha_is_flattened() {
        val image = ImageBitmap(32, 32)
        val canvas = Canvas(image)
        val paint = Paint().apply { isAntiAlias = false }
        for (x in 0 until 16 step 2) {
            paint.color = if (x % 4 == 0) Color.White else Color.Black
            canvas.drawRect(Rect(x.toFloat(), 0f, x + 2f, 16f), paint)
        }
        paint.color = Color(0x800000FF)
        canvas.drawRect(Rect(16f, 0f, 32f, 16f), paint)
        paint.color = Color.Green
        canvas.drawRect(Rect(0f, 16f, 16f, 32f), paint)
        val output = pixels(thumbnailMosaic(image))
        // These stripes disappear only when reduced to 8 px before enlargement.
        // A direct 32 -> 16 resize keeps alternating white and black.
        for (x in listOf(1, 2, 5)) assertRgbNear(output[3 * 16 + x], 128, 128, 128)
        assertRgbNear(output[3 * 16 + 13], 64, 64, 192)
        assertRgbNear(output[13 * 16 + 2], 0, 255, 0)
        assertRgbNear(output[13 * 16 + 13], 128, 128, 128)
        // Low interpolation mixes 1/4 and 3/4 of the two adjacent cells here.
        assertRgbNear(output[3 * 16 + 7], 112, 112, 144)
        assertRgbNear(output[3 * 16 + 8], 80, 80, 176)
        assertTrue(output.all { (it ushr 24) == 255 })
    }

    private fun assertRgbNear(pixel: Int, red: Int, green: Int, blue: Int) {
        for ((actual, expected) in listOf(
            ((pixel ushr 16) and 255) to red,
            ((pixel ushr 8) and 255) to green,
            (pixel and 255) to blue,
        )) assertTrue(kotlin.math.abs(actual - expected) <= 2, "$actual near $expected")
    }

    private fun pixels(image: ImageBitmap) = IntArray(image.width * image.height).also { image.readPixels(it) }
}
