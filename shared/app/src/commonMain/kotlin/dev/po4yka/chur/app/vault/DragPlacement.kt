package dev.po4yka.chur.app.vault

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.ffi.AlbumSummary
import kotlinx.coroutines.delay

/** A validated album drop, expressed in the catalog's parent/before contract. */
internal data class AlbumDrop(val parent: AlbumSummary?, val before: AlbumSummary?)

/** Resolve a drop without cycles, self anchors, or no-op catalog writes. */
internal fun albumDrop(
    source: AlbumSummary,
    target: AlbumSummary,
    placement: AlbumPlacement,
    albums: List<AlbumSummary>,
): AlbumDrop? {
    if (source.id == target.id || descendantOf(target, source, albums)) return null
    val parent = if (placement == AlbumPlacement.INSIDE) target else parentOf(target, albums)
    val remaining = siblingsUnder(parent, albums).filterNot { it.id == source.id }
    val at = when (placement) {
        AlbumPlacement.INSIDE -> remaining.size
        AlbumPlacement.BEFORE -> remaining.indexOfFirst { it.id == target.id }
        AlbumPlacement.AFTER -> remaining.indexOfFirst { it.id == target.id }
            .takeIf { it >= 0 }?.plus(1) ?: -1
    }
    if (at < 0) return null
    if (sameAlbumParent(source, parent) &&
        siblingsUnder(parent, albums).indexOfFirst { it.id == source.id } == at) return null
    return AlbumDrop(parent, remaining.getOrNull(at))
}

internal enum class AlbumPlacement { BEFORE, INSIDE, AFTER }

internal fun albumPlacement(point: Offset, bounds: Rect): AlbumPlacement {
    val fraction = (point.y - bounds.top) / bounds.height
    return when {
        fraction < 0.25f -> AlbumPlacement.BEFORE
        fraction > 0.75f -> AlbumPlacement.AFTER
        else -> AlbumPlacement.INSIDE
    }
}

internal fun rootAlbumDrop(source: AlbumSummary, albums: List<AlbumSummary>): AlbumDrop? {
    val roots = siblingsUnder(null, albums)
    return if (source.parentId == null && roots.lastOrNull()?.id == source.id) null
    else AlbumDrop(null, null)
}

private fun sameAlbumParent(source: AlbumSummary, parent: AlbumSummary?): Boolean =
    if (parent == null) source.parentId == null
    else source.parentId?.contentEquals(parent.albumId) == true

internal fun parentOf(album: AlbumSummary, albums: List<AlbumSummary>): AlbumSummary? =
    albums.firstOrNull { album.parentId?.contentEquals(it.albumId) == true }

internal fun siblingsUnder(parent: AlbumSummary?, albums: List<AlbumSummary>): List<AlbumSummary> =
    albums.filter { if (parent == null) it.parentId == null else it.parentId?.contentEquals(parent.albumId) == true }
        .sortedWith(compareBy<AlbumSummary> { it.position }.thenBy { it.id })

internal fun descendantOf(candidate: AlbumSummary, ancestor: AlbumSummary, albums: List<AlbumSummary>): Boolean {
    var parent = parentOf(candidate, albums)
    repeat(albums.size) {
        if (parent == null) return false
        if (parent.id == ancestor.id) return true
        parent = parentOf(parent, albums)
    }
    return true
}

/** A valid media drop; null [beforeId] means append to the fully loaded album. */
internal data class MemberDrop(val beforeId: String?)

internal fun memberDrop(
    ids: List<String>, sourceId: String, targetId: String, after: Boolean, hasMore: Boolean,
): MemberDrop? {
    val old = ids.indexOf(sourceId)
    if (old < 0 || sourceId == targetId) return null
    val remaining = ids.filterNot { it == sourceId }
    val targetIndex = remaining.indexOf(targetId)
    if (targetIndex < 0) return null
    val at = targetIndex + if (after) 1 else 0
    if (at == old || (at == remaining.size && hasMore)) return null
    return MemberDrop(remaining.getOrNull(at))
}

/** Signed pixels per frame while a dragged pointer stays near a scroll edge. */
internal fun edgeScrollDelta(point: Offset, viewport: Rect, margin: Float, speed: Float): Float {
    if (margin <= 0 || speed <= 0 || point.x !in viewport.left..viewport.right ||
        point.y !in viewport.top..viewport.bottom) return 0f
    return when {
        point.y < viewport.top + margin -> -speed
        point.y > viewport.bottom - margin -> speed
        else -> 0f
    }
}

/** Scrolls either lazy list or grid while the drag stays near its visible edge. */
@Composable
internal fun DragEdgeScroll(
    active: Boolean,
    point: Offset,
    viewport: Rect,
    scrollable: ScrollableState,
    excluded: Rect? = null,
) {
    val currentPoint by rememberUpdatedState(point)
    val currentViewport by rememberUpdatedState(viewport)
    val currentExcluded by rememberUpdatedState(excluded)
    val density = LocalDensity.current
    val margin = with(density) { 48.dp.toPx() }
    val speed = with(density) { 12.dp.toPx() }
    LaunchedEffect(active, scrollable) {
        if (!active) return@LaunchedEffect
        while (true) {
            val delta = if (currentExcluded?.contains(currentPoint) == true) 0f else
                edgeScrollDelta(currentPoint, currentViewport, margin, speed)
            if (delta != 0f) scrollable.scrollBy(delta)
            delay(16)
        }
    }
}
