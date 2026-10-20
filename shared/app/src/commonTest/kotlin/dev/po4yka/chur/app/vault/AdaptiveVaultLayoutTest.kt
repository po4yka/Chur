package dev.po4yka.chur.app.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdaptiveVaultLayoutTest {
    @Test
    fun compact_and_short_windows_keep_one_navigation_stack() {
        assertNull(adaptiveVaultLayout(719.0, 900.0).viewer)
        assertNull(adaptiveVaultLayout(900.0, 399.0).viewer)
        assertEquals(AdaptiveRegion(0.0, 0.0, 390.0, 844.0), adaptiveVaultLayout(390.0, 844.0).library)
    }

    @Test
    fun expanded_panes_have_readable_widths_and_fill_the_window() {
        val layout = adaptiveVaultLayout(1000.0, 800.0)
        val viewer = assertNotNull(layout.viewer)
        assertEquals(400.0, layout.library.width)
        assertEquals(401.0, viewer.x)
        assertEquals(1000.0, viewer.right)
        assertEquals(420.0, adaptiveVaultLayout(1600.0, 900.0).library.width)
        assertTrue(assertNotNull(adaptiveVaultLayout(720.0, 400.0).viewer).width >= 360)
    }

    @Test
    fun a_vertical_occlusion_separates_the_existing_library_and_viewer() {
        val fold = AdaptiveRegion(390.0, 0.0, 24.0, 900.0)
        val layout = adaptiveVaultLayout(830.0, 900.0, occlusions = listOf(fold))
        assertEquals(390.0, layout.library.right)
        assertEquals(414.0, assertNotNull(layout.viewer).x)
        assertEquals(830.0, layout.viewer.right)
    }

    @Test
    fun a_zero_width_active_division_is_still_a_boundary() {
        val layout = adaptiveVaultLayout(820.0, 900.0,
            divisions = listOf(AdaptiveRegion(400.0, 0.0, 0.0, 900.0)))
        assertEquals(400.0, layout.library.right)
        assertEquals(400.0, assertNotNull(layout.viewer).x)
    }

    @Test
    fun a_horizontal_fold_keeps_controls_in_the_larger_usable_region() {
        val layout = adaptiveVaultLayout(800.0, 900.0,
            occlusions = listOf(AdaptiveRegion(0.0, 400.0, 800.0, 24.0)))
        assertEquals(424.0, layout.library.y)
        assertEquals(476.0, layout.library.height)
        assertEquals(424.0, assertNotNull(layout.viewer).y)
    }

    @Test
    fun insufficient_fold_sides_fall_back_without_crossing_the_hinge() {
        val layout = adaptiveVaultLayout(700.0, 900.0,
            divisions = listOf(AdaptiveRegion(300.0, 0.0, 0.0, 900.0)))
        assertNull(layout.viewer)
        assertEquals(AdaptiveRegion(300.0, 0.0, 400.0, 900.0), layout.library)
    }

    @Test
    fun small_camera_cutout_preserves_the_full_width_below_it() {
        val camera = AdaptiveRegion(400.0, 0.0, 80.0, 40.0)
        val usable = adaptiveContentRegion(900.0, 700.0, occlusions = listOf(camera))
        assertEquals(AdaptiveRegion(0.0, 40.0, 900.0, 660.0), usable)
        val layout = adaptiveVaultLayout(900.0, 700.0, occlusions = listOf(camera))
        assertEquals(40.0, layout.library.y)
        assertEquals(40.0, assertNotNull(layout.viewer).y)
        assertEquals(900.0, layout.viewer.right)
    }

    @Test
    fun no_viewer_uses_all_contiguous_space_and_public_forms_never_cross_divisions() {
        assertEquals(AdaptiveRegion(0.0, 0.0, 1000.0, 800.0),
            adaptiveVaultLayout(1000.0, 800.0, allowSplit = false).library)
        assertNull(adaptiveVaultLayout(1000.0, 800.0, allowSplit = false).viewer)
        assertEquals(AdaptiveRegion(400.0, 0.0, 420.0, 900.0),
            adaptiveContentRegion(820.0, 900.0,
                divisions = listOf(AdaptiveRegion(400.0, 0.0, 0.0, 900.0))))
        assertEquals(AdaptiveRegion(0.0, 450.0, 820.0, 500.0),
            adaptiveContentRegion(820.0, 950.0,
                divisions = listOf(AdaptiveRegion(0.0, 450.0, 820.0, 0.0))))
    }

    @Test
    fun out_of_bounds_and_invalid_geometry_cannot_create_negative_frames() {
        assertEquals(adaptiveVaultLayout(800.0, 900.0), adaptiveVaultLayout(800.0, 900.0,
            occlusions = listOf(AdaptiveRegion(900.0, 0.0, 10.0, 900.0), AdaptiveRegion(Double.NaN, 0.0, 20.0, 20.0))))
        assertEquals(AdaptiveRegion(0.0, 0.0, 0.0, 0.0), adaptiveVaultLayout(Double.NaN, -1.0).library)
        assertEquals(AdaptiveRegion(0.0, 0.0, 0.0, 0.0), adaptiveVaultLayout(800.0, 900.0,
            occlusions = listOf(AdaptiveRegion(-1.0, -1.0, 802.0, 902.0))).library)
    }
}
