package dev.po4yka.chur.app

import dev.po4yka.chur.app.theme.ChurDarkColors
import dev.po4yka.chur.app.theme.ChurLightColors
import dev.po4yka.chur.app.theme.WidthClass
import dev.po4yka.chur.app.theme.gridGeometry
import dev.po4yka.chur.app.theme.gutterFor
import dev.po4yka.chur.app.vault.PresentedState
import dev.po4yka.chur.app.vault.StateSeverity
import dev.po4yka.chur.app.vault.TimeGroup
import dev.po4yka.chur.app.vault.VaultDestination
import dev.po4yka.chur.app.vault.humanSize
import dev.po4yka.chur.app.vault.severityOf
import dev.po4yka.chur.app.vault.timeGroupOf
import dev.po4yka.chur.ffi.ObjectProjection
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The rules of `DESIGN.md` that are decisions rather than pictures.
 *
 * A screenshot test would prove the layout; these prove the rules the layout is
 * derived from, which is what a reviewer checking the document against the code
 * actually needs.
 */
class DesignRulesTest {

    @Test
    fun the_grid_geometry_is_the_formula_of_section_11_1() {
        // floor(width / target), clamped to 3..8, with the compact target 112dp
        // and a 2dp gap, and 148dp with a 4dp gap above.
        assertEquals(3, gridGeometry(360).columns)
        assertEquals(2.dp, gridGeometry(360).gap)
        assertEquals(4, gridGeometry(480).columns)
        // Medium starts at 600dp and the target grows to 148dp, so the column
        // count drops rather than climbing with the width.
        assertEquals(4, gridGeometry(640).columns)
        assertEquals(4.dp, gridGeometry(640).gap)
        assertEquals(5, gridGeometry(900).columns)
    }

    @Test
    fun the_column_count_is_clamped_to_three_through_eight() {
        assertEquals(3, gridGeometry(200).columns, "a narrow window still shows three")
        assertEquals(8, gridGeometry(4_000).columns, "a wide window stops at eight")
    }

    @Test
    fun the_grid_is_deterministic_for_a_given_width() {
        // §11.1 says the result is deterministic so a screenshot test can pin
        // it. Two calls at one width therefore agree, and neighbouring widths
        // never disagree by more than one column.
        for (width in 320..1_200 step 7) {
            assertEquals(gridGeometry(width), gridGeometry(width))
            val step = abs(gridGeometry(width).columns - gridGeometry(width + 1).columns)
            assertTrue(step <= 1, "the column count jumped at $width")
        }
    }

    @Test
    fun the_gutters_are_the_table_of_section_8_1() {
        assertEquals(16.dp, gutterFor(360))
        assertEquals(24.dp, gutterFor(700))
        assertEquals(32.dp, gutterFor(1_200))
    }

    @Test
    fun the_width_classes_are_the_material_breakpoints() {
        assertEquals(WidthClass.COMPACT, WidthClass.of(599))
        assertEquals(WidthClass.MEDIUM, WidthClass.of(600))
        assertEquals(WidthClass.MEDIUM, WidthClass.of(839))
        assertEquals(WidthClass.EXPANDED, WidthClass.of(840))
    }

    @Test
    fun the_compact_destination_set_is_the_four_of_section_10_1() {
        // §10.1 calls the set decided: a fifth destination is a change to that
        // section rather than a product option, so the count is asserted.
        assertEquals(4, VaultDestination.entries.size)
        assertEquals(
            listOf("Library", "Albums", "Search", "Settings"),
            VaultDestination.entries.map { it.label },
        )
    }

    @Test
    fun the_presented_state_table_is_the_one_of_section_5_1() {
        fun row(state: Int, summary: Int) = projection(state = state, integrity = summary)
        assertEquals(PresentedState.VERIFICATION_RECOMMENDED, PresentedState.of(row(1, 1)))
        assertEquals(PresentedState.VERIFYING, PresentedState.of(row(1, 2)))
        assertEquals(PresentedState.VERIFICATION_RECOMMENDED, PresentedState.of(row(1, 3)))
        assertEquals(PresentedState.ORDINARY, PresentedState.of(row(1, 4)))
        assertEquals(PresentedState.INCOMPLETE, PresentedState.of(row(1, 5)))
        assertEquals(PresentedState.QUARANTINED, PresentedState.of(row(1, 6)))
        assertEquals(PresentedState.UNSUPPORTED, PresentedState.of(row(1, 7)))
        assertEquals(PresentedState.MIGRATION_REQUIRED, PresentedState.of(row(1, 8)))
        // §5.1: proven corruption is a lifecycle change, so it wins whatever
        // the verification verdict says.
        assertEquals(PresentedState.CORRUPT, PresentedState.of(row(4, 4)))
    }

    @Test
    fun uncertainty_is_warning_and_confirmed_corruption_is_error() {
        // §6.3, and nothing else is coloured.
        assertEquals(StateSeverity.NONE, severityOf(PresentedState.ORDINARY))
        assertEquals(StateSeverity.ERROR, severityOf(PresentedState.CORRUPT))
        for (state in listOf(
            PresentedState.VERIFICATION_RECOMMENDED,
            PresentedState.VERIFYING,
            PresentedState.INCOMPLETE,
            PresentedState.QUARANTINED,
            PresentedState.UNSUPPORTED,
            PresentedState.MIGRATION_REQUIRED,
        )) {
            assertEquals(StateSeverity.WARNING, severityOf(state), "$state")
        }
    }

    @Test
    fun the_locked_state_is_not_red() {
        // §6.3 says so directly. The error colour is reserved for confirmed
        // corruption, so nothing in the surface ladder equals it.
        for (colors in listOf(ChurLightColors, ChurDarkColors)) {
            for (surface in listOf(colors.canvas, colors.surface, colors.surfaceSubtle)) {
                assertNotEquals(colors.error, surface)
            }
        }
    }

    @Test
    fun the_accent_is_not_the_primary_action_colour() {
        // §6.1: primary actions are near-black on light and near-white on dark,
        // and blue is reserved for focus, selection, progress, and links.
        assertNotEquals(ChurLightColors.accent, ChurLightColors.ink)
        assertNotEquals(ChurDarkColors.accent, ChurDarkColors.ink)
        assertEquals(Color(0xFF315EF7), ChurLightColors.accent)
        assertEquals(Color(0xFF7D98FF), ChurDarkColors.accent)
    }

    @Test
    fun the_two_ladders_are_the_hexadecimal_values_of_section_6_2() {
        assertEquals(Color(0xFFFAFAF9), ChurLightColors.canvas)
        assertEquals(Color(0xFFFFFFFF), ChurLightColors.surface)
        assertEquals(Color(0xFF171717), ChurLightColors.ink)
        assertEquals(Color(0xFF0A0A0A), ChurDarkColors.canvas)
        assertEquals(Color(0xFF111111), ChurDarkColors.surface)
        assertEquals(Color(0xFFF5F5F3), ChurDarkColors.ink)
    }

    @Test
    fun the_timeline_groups_are_ranges_and_never_a_timestamp() {
        val today = 1_700_000_000_000L
        val day = 86_400_000L
        assertEquals(TimeGroup.TODAY, timeGroupOf(today, today))
        assertEquals(TimeGroup.TODAY, timeGroupOf(today + day - 1, today))
        assertEquals(TimeGroup.YESTERDAY, timeGroupOf(today - 1, today))
        assertEquals(TimeGroup.THIS_MONTH, timeGroupOf(today - 5 * day, today))
        assertEquals(TimeGroup.EARLIER, timeGroupOf(today - 40 * day, today))
        // §11.2: no label carries a precise time.
        for (group in TimeGroup.entries) {
            assertTrue(group.label.none { it.isDigit() }, "${group.label} carries a number")
        }
    }

    @Test
    fun a_size_is_coarse_rather_than_a_byte_count() {
        assertEquals("512 B", humanSize(512))
        assertEquals("1.0 kB", humanSize(1_000))
        assertEquals("1.5 MB", humanSize(1_500_000))
        assertEquals("2.0 GB", humanSize(2_000_000_000))
    }

    private fun projection(state: Int, integrity: Int) = ObjectProjection(
        objectId = ByteArray(16) { 1 },
        primaryStreamId = ByteArray(16) { 2 },
        mediaKind = 1,
        captureTimeMs = 0,
        importTimeMs = 0,
        captureTimeSubstituted = false,
        plaintextSize = 0,
        width = 0,
        height = 0,
        durationMs = 0,
        favorite = false,
        state = state,
        integritySummary = integrity,
        thumbnailReady = false,
    )
}
