package dev.po4yka.chur.app.vault

/** Container-local logical points, shared by UIKit geometry and layout policy. */
data class AdaptiveRegion(val x: Double, val y: Double, val width: Double, val height: Double) {
    val right: Double get() = x + width
    val bottom: Double get() = y + height
    internal val area: Double get() = width * height
}

/** A null viewer keeps the existing compact navigation stack in [library]. */
data class AdaptiveVaultLayout(val library: AdaptiveRegion, val viewer: AdaptiveRegion?)

/**
 * DESIGN §8.4–8.5: add context when both panes fit, and never bridge a fold.
 * This changes frames only; the route, scene leases and media owners survive resizing.
 * Divisions are boundaries even when their width/height is zero. Occlusions have area.
 */
fun adaptiveVaultLayout(
    width: Double,
    height: Double,
    occlusions: List<AdaptiveRegion> = emptyList(),
    divisions: List<AdaptiveRegion> = emptyList(),
    allowSplit: Boolean = true,
): AdaptiveVaultLayout {
    val regions = adaptiveSafeRegions(width, height, occlusions, divisions)
    val region = regions.maxByOrNull { it.area } ?: AdaptiveRegion(0.0, 0.0, 0.0, 0.0)
    if (!allowSplit) return AdaptiveVaultLayout(region, null)

    // An actual vertical boundary takes precedence over an arbitrary proportional split.
    val paired = regions.flatMap { left ->
        regions.mapNotNull { right ->
            val top = maxOf(left.y, right.y)
            val bottom = minOf(left.bottom, right.bottom)
            if (left.right <= right.x && left.width >= 320 && right.width >= 360 && bottom - top >= 400) {
                AdaptiveVaultLayout(
                    AdaptiveRegion(left.x, top, left.width, bottom - top),
                    AdaptiveRegion(right.x, top, right.width, bottom - top),
                )
            } else null
        }
    }.maxByOrNull { it.library.area + checkNotNull(it.viewer).area }
    val proportional = regions.filter { it.width >= 720 && it.height >= 400 }.map { usable ->
        val libraryWidth = (usable.width * 0.4).coerceIn(320.0, 420.0)
        AdaptiveVaultLayout(
            usable.copy(width = libraryWidth),
            AdaptiveRegion(usable.x + libraryWidth + 1, usable.y, usable.width - libraryWidth - 1, usable.height),
        )
    }.maxByOrNull { it.library.area + checkNotNull(it.viewer).area }
    return listOfNotNull(paired, proportional)
        .maxByOrNull { it.library.area + checkNotNull(it.viewer).area }
        ?: AdaptiveVaultLayout(region, null)
}

/** A readable form/root stays inside the largest uninterrupted usable rectangle. */
fun adaptiveContentRegion(
    width: Double,
    height: Double,
    occlusions: List<AdaptiveRegion> = emptyList(),
    divisions: List<AdaptiveRegion> = emptyList(),
): AdaptiveRegion = adaptiveSafeRegions(width, height, occlusions, divisions).maxByOrNull { it.area }
    ?: AdaptiveRegion(0.0, 0.0, 0.0, 0.0)

private fun adaptiveSafeRegions(
    width: Double,
    height: Double,
    occlusions: List<AdaptiveRegion>,
    divisions: List<AdaptiveRegion>,
): List<AdaptiveRegion> {
    val bounds = AdaptiveRegion(0.0, 0.0, width.finiteLength(), height.finiteLength())
    val cuts = occlusions.filter { it.valid() && it.width > 0 && it.height > 0 } +
        divisions.filter { it.valid() && (it.width > 0 || it.height > 0) }
    return cuts.fold(listOf(bounds)) { remaining, cut ->
        val candidates = remaining.flatMap { it.excluding(cut) }.distinct()
        // Overlapping candidates preserve full-width space below a camera cutout.
        // Remove contained rectangles to bound the set across multiple native cuts.
        candidates.filter { candidate -> candidates.none { other ->
            other != candidate && other.x <= candidate.x && other.y <= candidate.y &&
                other.right >= candidate.right && other.bottom >= candidate.bottom
        } }
    }.filter { it.width > 0 && it.height > 0 }
}

private fun Double.finiteLength(): Double = if (isFinite()) coerceAtLeast(0.0) else 0.0

private fun AdaptiveRegion.valid(): Boolean = x.isFinite() && y.isFinite() && width.isFinite() &&
    height.isFinite() && right.isFinite() && bottom.isFinite() && width >= 0 && height >= 0

/** Maximal usable rectangles around an obstacle, including zero-area fold lines. */
private fun AdaptiveRegion.excluding(cut: AdaptiveRegion): List<AdaptiveRegion> {
    if (cut.right <= x || cut.x >= right || cut.bottom <= y || cut.y >= bottom) return listOf(this)
    val left = maxOf(x, cut.x)
    val top = maxOf(y, cut.y)
    val end = minOf(right, cut.right)
    val lower = minOf(bottom, cut.bottom)
    return listOf(
        AdaptiveRegion(x, y, left - x, height),
        AdaptiveRegion(end, y, right - end, height),
        AdaptiveRegion(x, y, width, top - y),
        AdaptiveRegion(x, lower, width, bottom - lower),
    ).filter { it.width > 0 && it.height > 0 }
}
