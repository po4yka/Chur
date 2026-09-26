package dev.po4yka.chur.app.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.app.theme.ChurSpacing
import dev.po4yka.chur.app.theme.IntegrityGlyph
import dev.po4yka.chur.app.theme.LocalChurColors
import dev.po4yka.chur.app.theme.PrivateBoundaryMark
import dev.po4yka.chur.app.theme.gridGeometry
import dev.po4yka.chur.ffi.ObjectProjection

/**
 * One row of the media grid, `DESIGN.md` §11.1.
 *
 * The thumbnail is decoded from a derivative Rust decrypted; a row whose
 * thumbnail is not ready or whose object is not intact shows a deliberate
 * placeholder rather than an empty tile, which §11.1 asks for by name.
 */
data class LibraryTile(
    /** The projection this tile shows. */
    val projection: ObjectProjection,
    /** The decoded thumbnail, absent while it loads or when it does not exist. */
    val thumbnail: ImageBitmap?,
    /** Whether the tile is selected, §11.4. */
    val selected: Boolean = false,
)

/**
 * The presented state of `CATALOG_SCHEMA_V1.md` §5.1.
 *
 * The pair of enums is never stored and never shown as two values; §5.1 gives
 * the table that derives one word from both, and this is that table.
 */
enum class PresentedState(val label: String) {
    /** Nothing to say; the tile shows its media. */
    ORDINARY(""),

    /** `UNVERIFIED` or `RANGE_VERIFIED`. */
    VERIFICATION_RECOMMENDED("Verification recommended"),

    /** `VERIFYING`. */
    VERIFYING("Verifying"),

    /** `COMPLETE_VERIFIED`. */
    VERIFIED("Verified"),

    /** `INCOMPLETE`. */
    INCOMPLETE("Incomplete"),

    /** `QUARANTINED`. */
    QUARANTINED("Quarantined"),

    /** `UNSUPPORTED`. */
    UNSUPPORTED("Unsupported format"),

    /** `MIGRATION_REQUIRED`. */
    MIGRATION_REQUIRED("Migration required"),

    /** `CORRUPT`, which is a lifecycle state. */
    CORRUPT("Corrupt"),
    ;

    companion object {
        /** The table of §5.1, applied to one projection. */
        fun of(projection: ObjectProjection): PresentedState = when (projection.state) {
            STATE_CORRUPT -> CORRUPT
            STATE_ACTIVE -> when (projection.integritySummary) {
                INTEGRITY_UNVERIFIED, INTEGRITY_RANGE_VERIFIED -> VERIFICATION_RECOMMENDED
                INTEGRITY_VERIFYING -> VERIFYING
                INTEGRITY_COMPLETE_VERIFIED -> ORDINARY
                INTEGRITY_INCOMPLETE -> INCOMPLETE
                INTEGRITY_QUARANTINED -> QUARANTINED
                INTEGRITY_UNSUPPORTED -> UNSUPPORTED
                INTEGRITY_MIGRATION_REQUIRED -> MIGRATION_REQUIRED
                else -> ORDINARY
            }
            // §5.1: a DELETING or TOMBSTONED row is never presented, and the
            // query surface never returns one, so reaching here is a defect
            // rather than a state.
            else -> CORRUPT
        }

        private const val STATE_ACTIVE = 1
        private const val STATE_CORRUPT = 4
        private const val INTEGRITY_UNVERIFIED = 1
        private const val INTEGRITY_VERIFYING = 2
        private const val INTEGRITY_RANGE_VERIFIED = 3
        private const val INTEGRITY_COMPLETE_VERIFIED = 4
        private const val INTEGRITY_INCOMPLETE = 5
        private const val INTEGRITY_QUARANTINED = 6
        private const val INTEGRITY_UNSUPPORTED = 7
        private const val INTEGRITY_MIGRATION_REQUIRED = 8
    }
}

/**
 * Whether a presented state is an uncertainty or a confirmed failure, §6.3.
 *
 * Uncertainty is warning; confirmed corruption is error. Nothing else is
 * coloured, because §6.3 pairs colour with text and geometry rather than using
 * it alone.
 */
enum class StateSeverity {
    /** No badge. */
    NONE,

    /** Integrity uncertainty. */
    WARNING,

    /** Confirmed corruption. */
    ERROR,
}

/** The severity of one presented state, §6.3. */
fun severityOf(state: PresentedState): StateSeverity = when (state) {
    PresentedState.ORDINARY -> StateSeverity.NONE
    PresentedState.CORRUPT -> StateSeverity.ERROR
    else -> StateSeverity.WARNING
}

/** The two ways to browse private media or albums during one unlocked session. */
enum class ContentView { GRID, LIST }

/** Visible, accessible mode selector; both colour and a checkmark mark the active mode. */
@Composable
fun ContentViewToggle(view: ContentView, onChange: (ContentView) -> Unit) {
    val colors = LocalChurColors.current
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = ChurSpacing.gutter)) {
        ContentView.entries.forEach { choice ->
            val active = view == choice
            TextButton(onClick = { onChange(choice) }, modifier = Modifier.semantics { selected = active },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (active) colors.accent else colors.inkMuted)) {
                Text("${if (active) "✓ " else ""}${if (choice == ContentView.GRID) "Grid" else "List"}")
            }
        }
    }
}

/** Media browser shared by Library, albums, search, favorites, tags, and Trash. */
@Composable
fun MediaBrowser(
    tiles: List<LibraryTile>,
    view: ContentView,
    onOpen: (ObjectProjection) -> Unit,
    onToggleSelection: (ObjectProjection) -> Unit,
    onMove: ((ObjectProjection, ObjectProjection?) -> Unit)? = null,
    onLoadMore: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val bounds = remember { mutableMapOf<String, Rect>() }
    var dragged by remember { mutableStateOf<String?>(null) }
    var dropPoint by remember { mutableStateOf(Offset.Zero) }
    fun dragModifier(tile: LibraryTile): Modifier = if (onMove == null) Modifier else Modifier
        .onGloballyPositioned { bounds[tile.projection.id] = it.boundsInWindow() }
        .pointerInput(tile.projection.id, tiles) {
            detectDragGesturesAfterLongPress(
                onDragStart = { offset ->
                    dragged = tile.projection.id
                    dropPoint = (bounds[tile.projection.id]?.topLeft ?: Offset.Zero) + offset
                },
                onDragEnd = {
                    val sourceIndex = tiles.indexOfFirst { it.projection.id == dragged }
                    val targetIndex = tiles.indexOfFirst {
                        it.projection.id != dragged && bounds[it.projection.id]?.contains(dropPoint) == true
                    }
                    if (sourceIndex >= 0 && targetIndex >= 0) {
                        val beforeIndex = if (sourceIndex < targetIndex) targetIndex + 1 else targetIndex
                        onMove(tiles[sourceIndex].projection, tiles.getOrNull(beforeIndex)?.projection)
                    }
                    dragged = null
                },
                onDragCancel = { dragged = null },
                onDrag = { change, amount -> change.consume(); dropPoint += amount },
            )
        }
    if (view == ContentView.GRID) {
        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val geometry = gridGeometry(maxWidth.value.toInt())
            LazyVerticalGrid(
                columns = GridCells.Fixed(geometry.columns),
                horizontalArrangement = Arrangement.spacedBy(geometry.gap),
                verticalArrangement = Arrangement.spacedBy(geometry.gap),
                contentPadding = PaddingValues(geometry.gap),
            ) {
                items(tiles, key = { it.projection.id }) { tile ->
                    DisposableEffect(tile.projection.id) { onDispose { bounds.remove(tile.projection.id) } }
                    MediaTile(tile, { onOpen(tile.projection) }, { onToggleSelection(tile.projection) },
                        dragModifier(tile))
                }
                if (onLoadMore != null) item(key = "load-more") {
                    LaunchedEffect(tiles.size) { onLoadMore() }
                    Box(modifier = Modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
                        Text("Loading…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(ChurSpacing.gutter),
            verticalArrangement = Arrangement.spacedBy(ChurSpacing.one),
        ) {
            listItems(tiles, key = { it.projection.id }) { tile ->
                DisposableEffect(tile.projection.id) { onDispose { bounds.remove(tile.projection.id) } }
                MediaRow(tile, { onOpen(tile.projection) }, { onToggleSelection(tile.projection) },
                    dragModifier(tile))
            }
            if (onLoadMore != null) item(key = "load-more") {
                LaunchedEffect(tiles.size) { onLoadMore() }
                Box(modifier = Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
                    Text("Loading…", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** A compact row exposes useful metadata without requiring the viewer. */
@Composable
private fun MediaRow(tile: LibraryTile, onOpen: () -> Unit, onToggleSelection: () -> Unit,
    modifier: Modifier = Modifier) {
    val colors = LocalChurColors.current
    val projection = tile.projection
    val state = PresentedState.of(projection)
    val kind = when (projection.mediaKind) {
        MEDIA_CLASS_IMAGE -> "Photo"
        MEDIA_CLASS_VIDEO -> "Video"
        MEDIA_CLASS_AUDIO -> "Audio"
        else -> "File"
    }
    Row(
        modifier = modifier.fillMaxWidth()
            .clip(RoundedCornerShape(ChurSpacing.one))
            .background(if (tile.selected) colors.accentSoft else colors.surfaceSunken)
            .border(if (tile.selected) ChurSpacing.hairline else 0.dp, colors.accent,
                RoundedCornerShape(ChurSpacing.one))
            .clickable(onClick = onOpen)
            .padding(ChurSpacing.two),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ChurSpacing.two),
    ) {
        Box(modifier = Modifier.size(64.dp).clip(RoundedCornerShape(ChurSpacing.one))
            .background(colors.surface), contentAlignment = Alignment.Center) {
            tile.thumbnail?.let {
                androidx.compose.foundation.Image(bitmap = it, contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } ?: Icon(IntegrityGlyph, contentDescription = null, tint = colors.inkMuted)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(kind, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(mediaDetails(projection), style = MaterialTheme.typography.bodySmall,
                color = colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (state != PresentedState.ORDINARY) {
                Text(state.label, style = MaterialTheme.typography.labelSmall,
                    color = if (severityOf(state) == StateSeverity.ERROR) colors.error else colors.warning)
            }
        }
        if (tile.selected) SelectionCheck(modifier = Modifier, onClick = onToggleSelection)
    }
}

internal fun mediaDetails(projection: ObjectProjection): String {
    val size = projection.plaintextSize
    val sizeLabel = when {
        size >= 1_048_576 -> "${size / 1_048_576} MB"
        size >= 1_024 -> "${size / 1_024} KB"
        else -> "$size B"
    }
    val details = buildList {
        if (projection.durationMs > 0) add(durationLabel(projection.durationMs))
        if (projection.width > 0 && projection.height > 0) {
            add("${projection.width}×${projection.height}")
        }
        add(sizeLabel)
        if (projection.favorite) add("Favorite")
    }
    return details.joinToString(" · ")
}

private fun durationLabel(durationMs: Long): String {
    val seconds = durationMs / 1_000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

@Composable
private fun MediaTile(
    tile: LibraryTile,
    onOpen: () -> Unit,
    onToggleSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChurColors.current
    val state = PresentedState.of(tile.projection)
    val severity = severityOf(state)
    // §11.4: selection is a 2dp outline plus a checkmark, never colour alone.
    val selectionBorder = if (tile.selected) ChurSpacing.hairline else 0.dp
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(ChurSpacing.one))
            .background(colors.surfaceSunken)
            .border(selectionBorder, colors.accent, RoundedCornerShape(ChurSpacing.one))
            .clickable(onClick = onOpen),
    ) {
        val bitmap = tile.thumbnail
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // §11.1: a deliberate placeholder with stable geometry, so the grid
            // does not jump when a thumbnail arrives.
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    IntegrityGlyph,
                    contentDescription = null,
                    tint = colors.inkMuted,
                )
            }
        }
        if (severity != StateSeverity.NONE) {
            StateBadge(state = state, severity = severity, modifier = Modifier.align(Alignment.TopStart))
        }
        if (tile.projection.durationMs > 0) {
            Text(
                durationLabel(tile.projection.durationMs),
                modifier = Modifier.align(Alignment.BottomEnd).padding(ChurSpacing.one)
                    .clip(RoundedCornerShape(ChurSpacing.one))
                    .background(colors.surface).padding(horizontal = ChurSpacing.one),
                color = colors.ink,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (tile.selected) {
            SelectionCheck(modifier = Modifier.align(Alignment.TopEnd), onClick = onToggleSelection)
        }
    }
}

@Composable
private fun StateBadge(state: PresentedState, severity: StateSeverity, modifier: Modifier) {
    val colors = LocalChurColors.current
    val tint = when (severity) {
        StateSeverity.ERROR -> colors.error
        else -> colors.warning
    }
    Row(
        modifier = modifier
            .padding(ChurSpacing.one)
            .clip(RoundedCornerShape(50))
            .background(colors.surface)
            .padding(ChurSpacing.one),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ChurSpacing.one),
    ) {
        Icon(IntegrityGlyph, contentDescription = state.label, tint = tint)
    }
}

@Composable
private fun SelectionCheck(modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalChurColors.current
    Box(
        modifier = modifier
            .padding(ChurSpacing.one)
            .clip(RoundedCornerShape(50))
            .background(colors.accent)
            .clickable(onClick = onClick)
            .padding(ChurSpacing.one),
    ) {
        Text("✓", color = colors.onInk, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * The empty library, §11.3.
 *
 * The copy avoids security marketing after onboarding, which §11.3 says
 * explicitly.
 */
@Composable
fun EmptyLibrary(modifier: Modifier = Modifier) {
    val colors = LocalChurColors.current
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ChurSpacing.two),
            modifier = Modifier.padding(ChurSpacing.gutterExpanded).fillMaxWidth(),
        ) {
            PrivateBoundaryMark(size = 72.dp, media = true)
            Text(
                "Your private library is empty",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                "Import photos, videos, or audio stored on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The timeline grouping of §11.2.
 *
 * The groups are ranges rather than timestamps, because §11.2 forbids a precise
 * time on a public surface and this label is one a screenshot can carry.
 */
enum class TimeGroup(val label: String) {
    /** Today. */
    TODAY("Today"),

    /** Yesterday. */
    YESTERDAY("Yesterday"),

    /** This month, named. */
    THIS_MONTH("This month"),

    /** Anything older. */
    EARLIER("Earlier"),
}

/**
 * The group one capture time falls into.
 *
 * The day boundary comes from the caller rather than from a clock here, so the
 * function is total and testable: a device whose clock is wrong produces a
 * wrong group and nothing worse, which is the same guarantee §8.1 of the
 * catalog gives the times themselves.
 */
fun timeGroupOf(captureMs: Long, todayStartMs: Long, dayMs: Long = 86_400_000L): TimeGroup = when {
    captureMs >= todayStartMs -> TimeGroup.TODAY
    captureMs >= todayStartMs - dayMs -> TimeGroup.YESTERDAY
    captureMs >= todayStartMs - 30 * dayMs -> TimeGroup.THIS_MONTH
    else -> TimeGroup.EARLIER
}
