package dev.po4yka.chur.app.effects

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MosaicGeometryTest {
    @Test
    fun invalid_parameters_fail_before_drawing() {
        for (ratio in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { Modifier.mosaic(blockRatio = ratio) }
        }
        for (value in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { Modifier.mosaic(minBlockSize = Dp(value)) }
            assertFailsWith<IllegalArgumentException> { Modifier.mosaic(maxBlockSize = Dp(value)) }
        }
        assertFailsWith<IllegalArgumentException> { Modifier.mosaic(minBlockSize = 15.dp) }
    }

    @Test
    fun default_parameters_preserve_the_original_grid_and_two_times_intermediate() {
        assertEquals(0.22f, DefaultBlockRatio)
        assertEquals(0.18f, MinimumSafeBlockRatio)
        val geometry = geometry(Size(100f, 50f))!!
        assertEquals(11f, geometry.nominalCellSizePx)
        assertEquals(IntSize(9, 5), geometry.coarseSize)
        assertEquals(IntSize(18, 10), geometry.intermediateSizePx)
        assertEquals(IntSize(14, 7), geometry(Size(200f, 100f))!!.coarseSize)
    }

    @Test
    fun a_cell_cannot_be_smaller_than_one_physical_pixel() {
        val geometry = mosaicGeometry(Size(20f, 10f), 0.1f, 0.001f, 0.01.dp, 0.1.dp)!!
        assertEquals(1f, geometry.nominalCellSizePx)
        assertEquals(IntSize(20, 10), geometry.coarseSize)
        assertNull(geometry(Size.Zero))
        assertNull(geometry(Size(0.5f, 20f)))
    }

    @Test
    fun extreme_inputs_do_not_overflow_or_allocate_an_unbounded_bitmap() {
        for (size in listOf(Size(2_000_000_000f, 2_000_000_000f), Size(2_000_000_000f, 1f))) {
            val geometry = mosaicGeometry(size, 1f, Float.MIN_VALUE, Float.MIN_VALUE.dp, 1.dp)!!
            val bytes = (geometry.coarseSize.width.toLong() * geometry.coarseSize.height +
                geometry.intermediateSizePx.width.toLong() * geometry.intermediateSizePx.height) * 4L
            assertTrue(bytes <= MosaicBufferByteLimit)
            assertTrue(geometry.coarseSize.width > 0 && geometry.coarseSize.height > 0)
        }
        // Finite dp and density products can exceed Float.MAX_VALUE; Double arithmetic keeps them valid.
        assertEquals(
            IntSize(1, 1),
            mosaicGeometry(Size(20f, 20f), Float.MAX_VALUE, Float.MAX_VALUE, 4.dp, Float.MAX_VALUE.dp)!!.coarseSize,
        )
        assertFailsWith<IllegalArgumentException> { geometry(Size(Float.MAX_VALUE, 10f)) }
        assertFailsWith<IllegalArgumentException> { geometry(Size(Float.NaN, 10f)) }
    }

    private fun geometry(size: Size) = mosaicGeometry(size, 1f, DefaultBlockRatio, 4.dp, 14.dp)
}
