package dev.po4yka.chur.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.MediaImporter
import dev.po4yka.chur.app.vault.ThumbnailCache
import dev.po4yka.chur.app.vault.ThumbnailRendition
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.imports.AndroidMediaCodec
import dev.po4yka.chur.vault.VaultRepository
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android decode/canvas checks through the public private-image cache. */
@RunWith(AndroidJUnit4::class)
class ThumbnailMosaicAndroidTest {
    @Test
    fun mosaic_center_crop_is_opaque_and_does_not_keep_previous_pixels() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "thumbnail-mosaic-${System.nanoTime()}").apply { mkdirs() }
        val source = File(context.cacheDir, "export-scratch/mosaic-${System.nanoTime()}.png")
        source.parentFile?.mkdirs()
        source.writeBytes(png(32, 32))
        val repository = VaultRepository(root.absolutePath, System::currentTimeMillis)
        val cache = ThumbnailCache()
        try {
            repository.start()
            repository.create("thumbnail mosaic test password".encodeToByteArray(), offerRecovery = false)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", source)
            val codec = AndroidMediaCodec(context.contentResolver)
            val outcome = MediaImporter(codec).import(repository, codec.open(uri))
            assertTrue("fixture imports through the production pipeline: $outcome", outcome is MediaImporter.Outcome.Imported)
            val id = (outcome as MediaImporter.Outcome.Imported).objectId

            for ((width, height) in listOf(32 to 32, 16 to 48, 64 to 16)) {
                repository.putDerived(id, StreamKind.THUMBNAIL, width, height, png(width, height))
                cache.clear()
                val image = cache.load(repository, 1, id, "photo", rendition = ThumbnailRendition.Mosaic)
                assertNotNull(image)
                requireNotNull(image)
                assertEquals(16, image.width)
                assertEquals(16, image.height)
                val pixels = IntArray(256).also { image.readPixels(it) }
                assertTrue("$width x $height uses its center square", pixels.all { it == Color.GREEN })
                val cached = cache.load(repository, 1, id, "photo", rendition = ThumbnailRendition.Mosaic)
                assertTrue("redraw reuses the raster", image === cached)
                val plain = cache.load(repository, 1, id, "photo")
                assertNotNull(plain)
                assertEquals(width, requireNotNull(plain).width)
                assertEquals(height, plain.height)
            }

            repository.putDerived(id, StreamKind.THUMBNAIL, 8, 24, png(8, 24, transparent = true))
            cache.clear()
            val transparent = requireNotNull(
                cache.load(repository, 1, id, "photo", rendition = ThumbnailRendition.Mosaic),
            )
            val pixels = IntArray(256).also { transparent.readPixels(it) }
            assertTrue("transparent pixels cover previous images with neutral gray", pixels.all { it == 0xFF808080.toInt() })
        } finally {
            cache.clear()
            repository.shutdown()
            source.delete()
            root.deleteRecursively()
        }
    }

    private fun png(width: Int, height: Int, transparent: Boolean = false): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            if (!transparent) {
                bitmap.eraseColor(Color.RED)
                val side = minOf(width, height)
                val left = (width - side) / 2f
                val top = (height - side) / 2f
                Canvas(bitmap).drawRect(left, top, left + side, top + side, Paint().apply { color = Color.GREEN })
            }
            return ByteArrayOutputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
