package dev.po4yka.chur.app.vault

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The photo zoom of the viewer, `DESIGN.md` §13.1: a 4:3 photo on a 1:2
 * screen is fitted to 1000 by 750 px, so it fills the width and not the
 * height.
 */
class ViewerZoomTest {
    private val still = Size(4_000f, 3_000f)
    private val viewer = Size(1_000f, 2_000f)
    private val centre = Offset(500f, 1_000f)

    @Test
    fun a_pinch_zooms_from_1x_to_5x_and_no_further() {
        assertEquals(5f, ViewerZoom().transformed(centre, 10f, Offset.Zero, still, viewer).scale)
        assertEquals(1f, ViewerZoom(scale = 2f).transformed(centre, 0.1f, Offset.Zero, still, viewer).scale)
        assertEquals(ViewerZoom(), ViewerZoom(3f, Offset(400f, 0f)).transformed(centre, 0.1f, Offset.Zero, still, viewer))
    }

    @Test
    fun a_double_tap_zooms_to_2_5x_at_the_tap_and_the_next_returns_to_1x() {
        val zoomed = ViewerZoom().doubleTapped(Offset(250f, 1_000f), still, viewer)
        // The tapped point, 250 px left of the centre, stays under the finger.
        assertEquals(ViewerZoom(2.5f, Offset(375f, 0f)), zoomed)
        assertEquals(ViewerZoom(), zoomed.doubleTapped(Offset(250f, 1_000f), still, viewer))
    }

    @Test
    fun a_pan_stops_at_the_edge_of_the_photo() {
        // At 2.5x the photo is 2500 by 1875 px: 750 px past each side of the
        // screen, and still shorter than the screen, so it stays centred.
        val panned = ViewerZoom(2.5f).transformed(centre, 1f, Offset(-5_000f, 300f), still, viewer)
        assertEquals(ViewerZoom(2.5f, Offset(-750f, 0f)), panned)
    }

    @Test
    fun only_a_zoomed_photo_stops_the_pager() {
        assertFalse(ViewerZoom().zoomed, "at 1x one finger swipes to the next item")
        assertTrue(ViewerZoom().doubleTapped(centre, still, viewer).zoomed, "zoomed, one finger pans the photo")
    }
}
