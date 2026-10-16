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

    private fun pixels(image: ImageBitmap) = IntArray(image.width * image.height).also { image.readPixels(it) }
}
