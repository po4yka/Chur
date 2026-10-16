package dev.po4yka.chur.app.vault

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ThumbnailBudgetTest {
    @Test
    fun large_previews_are_evicted_by_decoded_bytes_before_the_entry_limit() = runTest {
        var reads = 0
        val cache = ThumbnailCache(256, StandardTestDispatcher(testScheduler), maxBytes = 64) { _, _ -> BudgetImage() }
        suspend fun load(id: String) = cache.load(1, id) { reads++; byteArrayOf(1) }
        load("first")
        load("second")
        load("third")
        load("first")
        assertEquals(4, reads, "only one 64-byte preview fits")
    }

    private class BudgetImage : ImageBitmap {
        override val width = 4
        override val height = 4
        override val colorSpace get() = ColorSpaces.Srgb
        override val hasAlpha = false
        override val config get() = ImageBitmapConfig.Argb8888
        override fun prepareToDraw() = Unit
        override fun readPixels(buffer: IntArray, startX: Int, startY: Int, width: Int, height: Int, bufferOffset: Int, stride: Int): Unit = error("no raster read is needed")
    }
}
