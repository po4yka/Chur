package dev.po4yka.chur.app.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.app.theme.AlbumsGlyph
import dev.po4yka.chur.app.theme.ChurSpacing
import dev.po4yka.chur.app.theme.LocalChurColors
import dev.po4yka.chur.ffi.AlbumSummary

/** Full names stay available to accessibility and horizontal scrolling, even for deep paths. */
@Composable
fun AlbumBreadcrumbs(
    path: List<AlbumSummary>,
    current: AlbumSummary,
    onRoot: () -> Unit,
    onOpen: (AlbumSummary) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // A host may still hold an album projection while a catalog refresh runs.
    // Show only that leaf when the path is invalid; never invent its ancestors.
    val shownPath = path.ifEmpty { listOf(current) }
    val pathIds = shownPath.map { it.id }
    // Scroll positions belong to this composition, not Android saved state.
    val scroll = remember(pathIds) { LazyListState(firstVisibleItemIndex = shownPath.lastIndex) }
    val colors = LocalChurColors.current
    Row(modifier.semantics { isTraversalGroup = true }, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onRoot, enabled = enabled, modifier = Modifier.testTag("album-breadcrumb-root")) { Text("Albums") }
        LazyRow(state = scroll, modifier = Modifier.weight(1f).testTag("album-breadcrumb-path"),
            verticalAlignment = Alignment.CenterVertically) {
            itemsIndexed(shownPath, key = { _, album -> album.id }) { index, album ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("›", color = colors.inkMuted, modifier = Modifier.clearAndSetSemantics { })
                    if (index == shownPath.lastIndex) {
                        Box(Modifier.heightIn(min = 48.dp).padding(horizontal = ChurSpacing.one)
                            .testTag("album-breadcrumb-current-${album.id}").semantics(mergeDescendants = true) {
                                contentDescription = album.name
                                stateDescription = "Current album"
                                selected = true
                            }, contentAlignment = Alignment.CenterStart) {
                            Text(album.name, maxLines = 1, color = colors.ink, style = MaterialTheme.typography.labelLarge)
                        }
                    } else {
                        TextButton(onClick = { onOpen(album) }, enabled = enabled,
                            modifier = Modifier.testTag("album-breadcrumb-${album.id}")) {
                            Text(album.name, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** Only immediate children are shown; the media grid keeps the remaining vertical space. */
@Composable
fun AlbumChildren(
    children: List<AlbumSummary>,
    onOpen: (AlbumSummary) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (children.isEmpty()) return
    val colors = LocalChurColors.current
    val scroll = remember(children.map { it.id }) { LazyListState() }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("Subalbums", style = MaterialTheme.typography.labelMedium, color = colors.inkMuted,
            modifier = Modifier.padding(start = ChurSpacing.two, end = ChurSpacing.one))
        LazyRow(state = scroll, modifier = Modifier.weight(1f).testTag("album-children"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ChurSpacing.one)) {
            itemsIndexed(children, key = { _, album -> album.id }) { _, album ->
                TextButton(onClick = { onOpen(album) }, enabled = enabled,
                    modifier = Modifier.testTag("album-child-${album.id}").semantics {
                        contentDescription = "${album.name}, ${album.memberCount} items"
                    }) {
                    Icon(AlbumsGlyph, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(album.name, maxLines = 1, modifier = Modifier.padding(start = ChurSpacing.one))
                }
            }
        }
    }
}
