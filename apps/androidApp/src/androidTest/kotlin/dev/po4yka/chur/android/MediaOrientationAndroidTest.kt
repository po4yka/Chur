package dev.po4yka.chur.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.imports.AndroidMediaCodec
import dev.po4yka.chur.imports.MediaBounds
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual JPEG EXIF, sampled Android pixels, and re-encoded derivative pixels. */
@RunWith(AndroidJUnit4::class)
class MediaOrientationAndroidTest {
    @Test
    fun allEightExifOrientationsNormalizeBoundsAndPixels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "export-scratch/orientation-${System.nanoTime()}.jpg")
        requireNotNull(file.parentFile).mkdirs()
        val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        val bitmap = Bitmap.createBitmap(2400, 1600, Bitmap.Config.ARGB_8888)
        val paint = Paint()
        val canvas = Canvas(bitmap)
        colors.forEachIndexed { index, color ->
            paint.color = color
            val x = index % 2 * 1200f
            val y = index / 2 * 800f
            canvas.drawRect(x, y, x + 1200f, y + 800f, paint)
        }
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        bitmap.recycle()
        val quadrants = listOf(
            listOf(0, 1, 2, 3), listOf(1, 0, 3, 2), listOf(3, 2, 1, 0), listOf(2, 3, 0, 1),
            listOf(0, 2, 1, 3), listOf(2, 0, 3, 1), listOf(3, 1, 2, 0), listOf(1, 3, 0, 2),
        )
        try {
            for (orientation in 1..8) {
                ExifInterface(file).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                    saveAttributes()
                }
                val codec = AndroidMediaCodec(context.contentResolver)
                val uri = leasedExportUri(context, file)
                val media = requireNotNull(codec.open(uri))
                try {
                    assertEquals("image/jpeg", media.contentTypeHint)
                    val probe = requireNotNull(codec.probe(media))
                    assertEquals(MediaBounds.CLASS_IMAGE, probe.mediaClass)
                    val swapped = orientation >= 5
                    assertEquals(if (swapped) 1600 else 2400, probe.width)
                    assertEquals(if (swapped) 2400 else 1600, probe.height)
                    for (kind in listOf(StreamKind.THUMBNAIL, StreamKind.GRID_PREVIEW, StreamKind.SCREEN_PREVIEW)) {
                        val derivative = requireNotNull(codec.derive(media, probe, kind))
                        val target = requireNotNull(MediaBounds.targetSize(kind, probe.width, probe.height))
                        assertEquals(target.first, derivative.width)
                        assertEquals(target.second, derivative.height)
                        val decoded = requireNotNull(BitmapFactory.decodeByteArray(derivative.bytes, 0, derivative.bytes.size))
                        try {
                            assertEquals(derivative.width, decoded.width)
                            assertEquals(derivative.height, decoded.height)
                            quadrants[orientation - 1].forEachIndexed { index, original ->
                                val pixel = decoded.getPixel(
                                    decoded.width * (1 + 2 * (index % 2)) / 4,
                                    decoded.height * (1 + 2 * (index / 2)) / 4,
                                )
                                val expected = colors[original]
                                assertTrue("orientation $orientation, $kind, quadrant $index",
                                    kotlin.math.abs(Color.red(pixel) - Color.red(expected)) < 35 &&
                                        kotlin.math.abs(Color.green(pixel) - Color.green(expected)) < 35 &&
                                        kotlin.math.abs(Color.blue(pixel) - Color.blue(expected)) < 35)
                            }
                        } finally { decoded.recycle() }
                    }
                } finally { media.close() }
            }
        } finally { file.delete() }
    }
}
