package dev.po4yka.chur.app.vault

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.app.theme.ChurSpacing
import dev.po4yka.chur.app.theme.LocalChurColors
import dev.po4yka.chur.ffi.QuerySort
import dev.po4yka.chur.ffi.QueryScope
import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.ffi.AlbumSummary
import dev.po4yka.chur.ffi.TagSummary

/** The catalog's four allocated media classes, encoded as ObjectQuery.kinds bits. */
enum class MediaKindFilter(val label: String, mediaClass: Int) {
    PHOTOS("Photos", MEDIA_CLASS_IMAGE),
    VIDEOS("Videos", MEDIA_CLASS_VIDEO),
    AUDIO("Audio", MEDIA_CLASS_AUDIO),
    FILES("Files", 4),
    ;

    val bit: Int = 1 shl (mediaClass - 1)
}

private val allKindsMask = MediaKindFilter.entries.fold(0) { mask, kind -> mask or kind.bit }

/** Zero means all classes. Toggling the last selected kind returns to all. */
fun toggleKindFilter(current: Int, kind: MediaKindFilter): Int {
    val next = if (current == 0) kind.bit else current xor kind.bit
    return if (next == 0 || next == allKindsMask) 0 else next
}

fun sortChoices(album: Boolean): List<QuerySort> =
    if (album) listOf(QuerySort.ALBUM_MANUAL, QuerySort.CAPTURE_DESC,
        QuerySort.CAPTURE_ASC, QuerySort.IMPORT_DESC)
    else listOf(QuerySort.CAPTURE_DESC, QuerySort.CAPTURE_ASC, QuerySort.IMPORT_DESC)

fun sortLabel(sort: QuerySort, trash: Boolean): String = when (sort) {
    QuerySort.ALBUM_MANUAL -> "Manual order"
    QuerySort.CAPTURE_DESC -> if (trash) "Recently deleted" else "Newest capture"
    QuerySort.CAPTURE_ASC -> if (trash) "Oldest deleted" else "Oldest capture"
    QuerySort.IMPORT_DESC -> "Recently imported"
}

/** One query builder for both hosts, so a filter always runs before paging. */
fun browseQuery(
    destination: VaultDestination,
    album: AlbumSummary?,
    tag: TagSummary?,
    favorites: Boolean,
    trash: Boolean,
    terms: String,
    mediaSort: QuerySort,
    albumSort: QuerySort,
    kinds: Int,
): ObjectQuery? = when {
    album != null -> ObjectQuery(QueryScope.ALBUM, sort = albumSort,
        kinds = kinds, scopeId = album.albumId)
    destination == VaultDestination.LIBRARY && trash ->
        ObjectQuery(QueryScope.TRASH, sort = mediaSort, kinds = kinds)
    destination == VaultDestination.LIBRARY && tag != null ->
        ObjectQuery(QueryScope.TAG, sort = mediaSort, kinds = kinds, scopeId = tag.tagId)
    destination == VaultDestination.LIBRARY && favorites ->
        ObjectQuery(QueryScope.FAVORITES, sort = mediaSort, kinds = kinds)
    destination == VaultDestination.LIBRARY ->
        ObjectQuery(QueryScope.TIMELINE, sort = mediaSort, kinds = kinds)
    destination == VaultDestination.SEARCH && terms.isNotBlank() ->
        ObjectQuery(QueryScope.SEARCH, sort = mediaSort, kinds = kinds, terms = terms.trim())
    else -> null
}

/** Controls query-backed ordering and class filtering before pagination occurs. */
@Composable
fun MediaSortFilterControls(
    sort: QuerySort,
    kinds: Int,
    album: Boolean,
    trash: Boolean,
    onSort: (QuerySort) -> Unit,
    onKinds: (Int) -> Unit,
) {
    val colors = LocalChurColors.current
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(modifier = Modifier.padding(horizontal = ChurSpacing.gutter)) {
            Box {
                TextButton(onClick = { expanded = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Sort: ${sortLabel(sort, trash)} ▾", color = colors.accent)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    sortChoices(album).forEach { choice ->
                        DropdownMenuItem(
                            text = { Text("${if (sort == choice) "✓ " else ""}${sortLabel(choice, trash)}") },
                            onClick = { expanded = false; onSort(choice) },
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState())
                .padding(horizontal = ChurSpacing.gutter),
            horizontalArrangement = Arrangement.spacedBy(ChurSpacing.one),
        ) {
            FilterChip(
                selected = kinds == 0,
                onClick = { onKinds(0) },
                label = { Text("All") },
                leadingIcon = if (kinds == 0) ({ Text("✓") }) else null,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = colors.accentSoft,
                    selectedLabelColor = colors.accent,
                    selectedLeadingIconColor = colors.accent,
                ),
            )
            MediaKindFilter.entries.forEach { kind ->
                val selected = kinds and kind.bit != 0
                FilterChip(
                    selected = selected,
                    onClick = { onKinds(toggleKindFilter(kinds, kind)) },
                    label = { Text(kind.label) },
                    leadingIcon = if (selected) ({ Text("✓") }) else null,
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = colors.accentSoft,
                        selectedLabelColor = colors.accent,
                        selectedLeadingIconColor = colors.accent,
                    ),
                )
            }
        }
    }
}
