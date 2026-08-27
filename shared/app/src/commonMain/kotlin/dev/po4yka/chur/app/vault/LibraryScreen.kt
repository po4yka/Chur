package dev.po4yka.chur.app.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.app.theme.ChurSpacing
import dev.po4yka.chur.app.theme.IntegrityGlyph
import dev.po4yka.chur.app.theme.LocalChurColors
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

/**
 * The media grid.
 *
 * The column count comes from [gridGeometry], which §11.1 makes a deterministic
 * function of the available width so a screenshot test can pin it.
 */
@Composable
fun MediaGrid(
    tiles: List<LibraryTile>,
    widthDp: Int,
    onOpen: (ObjectProjection) -> Unit,
    onToggleSelection: (ObjectProjection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val geometry = gridGeometry(widthDp)
    LazyVerticalGrid(
        columns = GridCells.Fixed(geometry.columns),
        horizontalArrangement = Arrangement.spacedBy(geometry.gap),
        verticalArrangement = Arrangement.spacedBy(geometry.gap),
        contentPadding = PaddingValues(geometry.gap),
        modifier = modifier.fillMaxSize(),
    ) {
        items(tiles, key = { it.projection.id }) { tile ->
            MediaTile(
                tile = tile,
                onOpen = { onOpen(tile.projection) },
                onToggleSelection = { onToggleSelection(tile.projection) },
            )
        }
    }
}

@Composable
private fun MediaTile(tile: LibraryTile, onOpen: () -> Unit, onToggleSelection: () -> Unit) {
    val colors = LocalChurColors.current
    val state = PresentedState.of(tile.projection)
    val severity = severityOf(state)
    // §11.4: selection is a 2dp outline plus a checkmark, never colour alone.
    val selectionBorder = if (tile.selected) ChurSpacing.hairline else 0.dp
    Box(
        modifier = Modifier
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
                    tint = colors.outline,
                )
            }
        }
        if (severity != StateSeverity.NONE) {
            StateBadge(state = state, severity = severity, modifier = Modifier.align(Alignment.TopStart))
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
        modifier = modifier.padding(ChurSpacing.one),
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
            Icon(IntegrityGlyph, contentDescription = null, tint = colors.outline)
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
