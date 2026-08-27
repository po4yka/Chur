package dev.po4yka.chur.app.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The media grid geometry of `DESIGN.md` §11.1.
 *
 * The column count is `floor(available_width / target_tile)` clamped to 3
 * through 8, with the target tile 112dp compact, 148dp medium, and 180dp
 * expanded, and gaps 2dp compact and 4dp above. §11.1 says the result is
 * deterministic for a given width so a test can pin it, which is why this is a
 * pure function rather than a layout that measures.
 */
data class GridGeometry(val columns: Int, val gap: Dp)

/** The width classes of §8. */
enum class WidthClass {
    /** A phone in portrait. */
    COMPACT,

    /** A tablet in portrait or a phone in landscape. */
    MEDIUM,

    /** A tablet in landscape or a desktop window. */
    EXPANDED,
    ;

    companion object {
        /** The class a width falls into, by the Material breakpoints §8 uses. */
        fun of(widthDp: Int): WidthClass = when {
            widthDp < 600 -> COMPACT
            widthDp < 840 -> MEDIUM
            else -> EXPANDED
        }
    }
}

/** The grid for one available width, §11.1. */
fun gridGeometry(widthDp: Int): GridGeometry {
    val widthClass = WidthClass.of(widthDp)
    val target = when (widthClass) {
        WidthClass.COMPACT -> 112
        WidthClass.MEDIUM -> 148
        WidthClass.EXPANDED -> 180
    }
    val columns = (widthDp / target).coerceIn(MIN_COLUMNS, MAX_COLUMNS)
    val gap = if (widthClass == WidthClass.COMPACT) 2.dp else 4.dp
    return GridGeometry(columns, gap)
}

/** The gutter for one width class, §8.1. */
fun gutterFor(widthDp: Int): Dp = when (WidthClass.of(widthDp)) {
    WidthClass.COMPACT -> ChurSpacing.gutter
    WidthClass.MEDIUM -> ChurSpacing.gutterMedium
    WidthClass.EXPANDED -> ChurSpacing.gutterExpanded
}

private const val MIN_COLUMNS = 3
private const val MAX_COLUMNS = 8
