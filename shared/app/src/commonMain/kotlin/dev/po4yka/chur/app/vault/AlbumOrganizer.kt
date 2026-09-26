package dev.po4yka.chur.app.vault

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.app.theme.ChurSpacing
import dev.po4yka.chur.app.theme.LocalChurColors
import dev.po4yka.chur.app.theme.churOutlinedTextFieldColors
import dev.po4yka.chur.ffi.AlbumSummary
import dev.po4yka.chur.ffi.toHex

enum class AlbumOrder(val label: String) {
    MANUAL("Manual order"),
    NAME_ASC("Name A–Z"),
    NAME_DESC("Name Z–A"),
    COUNT_DESC("Most items"),
    COUNT_ASC("Fewest items"),
}

/** Keep the catalog hierarchy while ordering siblings for presentation. */
internal fun albumRows(albums: List<AlbumSummary>, order: AlbumOrder = AlbumOrder.MANUAL,
    filter: String = ""): List<Pair<AlbumSummary, Int>> {
    val result = ArrayList<Pair<AlbumSummary, Int>>(albums.size)
    val visited = HashSet<String>()
    val comparator = when (order) {
        AlbumOrder.MANUAL -> compareBy<AlbumSummary> { it.position }.thenBy { it.id }
        AlbumOrder.NAME_ASC -> compareBy<AlbumSummary> { it.name.lowercase() }.thenBy { it.id }
        AlbumOrder.NAME_DESC -> compareByDescending<AlbumSummary> { it.name.lowercase() }.thenBy { it.id }
        AlbumOrder.COUNT_DESC -> compareByDescending<AlbumSummary> { it.memberCount }.thenBy { it.id }
        AlbumOrder.COUNT_ASC -> compareBy<AlbumSummary> { it.memberCount }.thenBy { it.id }
    }
    val children = albums.groupBy { it.parentId?.toHex() }
        .mapValues { (_, siblings) -> siblings.sortedWith(comparator) }
    val pending = ArrayDeque<Pair<AlbumSummary, Int>>()
    children[null].orEmpty().asReversed().forEach { pending.addLast(it to 0) }
    while (pending.isNotEmpty()) {
        val (album, depth) = pending.removeLast()
        if (visited.add(album.id)) {
            result += album to depth
            children[album.id].orEmpty().asReversed().forEach { pending.addLast(it to depth + 1) }
        }
    }
    albums.sortedWith(comparator)
        .filterNot { it.id in visited }
        .forEach { result += it to 0 }
    if (filter.isBlank()) return result
    val byId = albums.associateBy { it.id }
    val visible = HashSet<String>()
    result.filter { (album, _) -> album.name.contains(filter.trim(), ignoreCase = true) }
        .forEach { (album, _) ->
            var current: AlbumSummary? = album
            var steps = 0
            while (current != null && steps++ < albums.size) {
                if (!visible.add(current.id)) break
                current = current.parentId?.toHex()?.let(byId::get)
            }
        }
    return result.filter { (album, _) -> album.id in visible }
}

private fun siblingsOf(album: AlbumSummary, albums: List<AlbumSummary>): List<AlbumSummary> =
    albums.filter { child ->
        if (album.parentId == null) child.parentId == null
        else child.parentId?.contentEquals(album.parentId) == true
    }.sortedWith(compareBy<AlbumSummary> { it.position }.thenBy { it.id })

/** Album navigation, editing, nesting, and drag placement for both hosts. */
@Composable
fun AlbumOrganizer(albums: List<AlbumSummary>, actions: VaultActions,
    view: ContentView, onViewChange: (ContentView) -> Unit,
    order: AlbumOrder, onOrderChange: (AlbumOrder) -> Unit,
    filter: String, onFilterChange: (String) -> Unit) {
    val colors = LocalChurColors.current
    var renaming by remember { mutableStateOf<AlbumSummary?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<AlbumSummary?>(null) }
    var moving by remember { mutableStateOf<AlbumSummary?>(null) }
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    var dragged by remember { mutableStateOf<AlbumSummary?>(null) }
    var dropPoint by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(Rect.Zero) }
    var rootDropBounds by remember { mutableStateOf(Rect.Zero) }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val rows = remember(albums, order, filter) { albumRows(albums, order, filter) }
    val canReorder = order == AlbumOrder.MANUAL && filter.isBlank()
    val hovered = dragged?.takeUnless { rootDropBounds.contains(dropPoint) }?.let { source -> rows.firstOrNull { (album, _) ->
        album.id != source.id && bounds[album.id]?.contains(dropPoint) == true
    }?.first }
    val hoverPlacement = hovered?.let { albumPlacement(dropPoint, bounds[it.id]!!) }
    val hoverMove = if (dragged != null && hovered != null && hoverPlacement != null)
        albumDrop(dragged!!, hovered, hoverPlacement, albums) else null
    DragEdgeScroll(dragged != null, dropPoint, viewport,
        if (view == ContentView.GRID) gridState else listState,
        if (dragged != null) rootDropBounds else null)
    val dragGestureModifier = if (!canReorder) Modifier else Modifier.pointerInput(albums, view) {
        detectDragGesturesAfterLongPress(
            onDragStart = { offset ->
                val point = viewport.topLeft + offset
                val source = rows.firstOrNull { (album, _) ->
                    bounds[album.id]?.contains(point) == true
                }?.first
                if (source != null) {
                    dragged = source
                    dropPoint = point
                    rootDropBounds = Rect.Zero
                }
            },
            onDragEnd = {
                val source = dragged
                if (source != null) {
                    val move = if (rootAlbumDrop(source, albums) != null &&
                        rootDropBounds.contains(dropPoint)) rootAlbumDrop(source, albums)
                    else rows.firstOrNull { (candidate, _) ->
                        candidate.id != source.id && bounds[candidate.id]?.contains(dropPoint) == true
                    }?.first?.let { target ->
                        albumDrop(source, target, albumPlacement(dropPoint, bounds[target.id]!!), albums)
                    }
                    if (move != null) actions.onMoveAlbum(source, move.parent, move.before)
                }
                dragged = null
                rootDropBounds = Rect.Zero
            },
            onDragCancel = { dragged = null; rootDropBounds = Rect.Zero },
            onDrag = { change, amount ->
                if (dragged != null) { change.consume(); dropPoint += amount }
            },
        )
    }

    val grid = view == ContentView.GRID
    val albumCard: @Composable (AlbumSummary, Int) -> Unit = { album, depth ->
        DisposableEffect(album.id) { onDispose { bounds.remove(album.id) } }
        val siblings = siblingsOf(album, albums)
        val index = siblings.indexOfFirst { it.id == album.id }
        val parent = parentOf(album, albums)
        var menu by remember(album.id) { mutableStateOf(false) }
        Card(
            onClick = { actions.onOpenAlbum(album) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = if (grid) 0.dp else (depth * 16).coerceAtMost(96).dp)
                .zIndex(if (dragged?.id == album.id) 1f else 0f)
                .graphicsLayer {
                    if (dragged?.id == album.id) { scaleX = 1.03f; scaleY = 1.03f; alpha = 0.8f }
                }
                .border(if (hovered?.id == album.id && hoverMove != null) 2.dp else 0.dp,
                    colors.accent, RoundedCornerShape(12.dp))
                .onGloballyPositioned { bounds[album.id] = it.boundsInWindow() },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(ChurSpacing.three),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(album.name, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (grid && parent != null) {
                        Text("In ${parent.name}", style = MaterialTheme.typography.bodySmall,
                            color = colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        "${album.memberCount} item${if (album.memberCount == 1L) "" else "s"}" +
                            if (canReorder) " · Hold to drag" else "",
                        style = MaterialTheme.typography.bodySmall, color = colors.inkMuted,
                    )
                    if (hovered?.id == album.id && hoverMove != null) {
                        Text(when (hoverPlacement) {
                            AlbumPlacement.BEFORE -> "Place before"
                            AlbumPlacement.AFTER -> "Place after"
                            else -> "Move inside"
                        }, color = colors.accent, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Box {
                    TextButton(onClick = { menu = true }) { Text("More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = {
                            menu = false; renameText = album.name; renaming = album
                        })
                        DropdownMenuItem(text = { Text("Move to…") }, onClick = {
                            menu = false; moving = album
                        })
                        if (canReorder && index > 0) {
                            DropdownMenuItem(text = { Text("Move up") }, onClick = {
                                menu = false
                                actions.onMoveAlbum(album, parent, siblings[index - 1])
                            })
                        }
                        if (canReorder && index in 0 until siblings.lastIndex) {
                            DropdownMenuItem(text = { Text("Move down") }, onClick = {
                                menu = false
                                actions.onMoveAlbum(album, parent, siblings.getOrNull(index + 2))
                            })
                        }
                        DropdownMenuItem(text = { Text("Delete album") }, onClick = {
                            menu = false; deleting = album
                        })
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ContentViewToggle(view, onViewChange)
        var sortExpanded by remember { mutableStateOf(false) }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = ChurSpacing.gutter)) {
            Box {
                TextButton(onClick = { sortExpanded = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Sort: ${order.label} ▾")
                }
                DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                    AlbumOrder.entries.forEach { choice ->
                        DropdownMenuItem(text = { Text("${if (choice == order) "✓ " else ""}${choice.label}") },
                            onClick = { sortExpanded = false; onOrderChange(choice) })
                    }
                }
            }
        }
        OutlinedTextField(
            value = filter, onValueChange = onFilterChange,
            label = { Text("Find album") }, singleLine = true,
            trailingIcon = if (filter.isNotEmpty()) {
                { TextButton(onClick = { onFilterChange("") }) { Text("Clear") } }
            } else null,
            modifier = Modifier.fillMaxWidth().padding(horizontal = ChurSpacing.gutter),
            colors = churOutlinedTextFieldColors(),
        )
        if (albums.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No albums yet", style = MaterialTheme.typography.titleMedium)
                    Text("Group objects you want to find together.", color = colors.inkMuted)
                }
            }
        } else if (rows.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No matching albums", color = colors.inkMuted)
            }
        } else {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()
                .onGloballyPositioned { viewport = it.boundsInWindow() }
                .then(dragGestureModifier)) {
            if (grid) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(ChurSpacing.gutter),
                    horizontalArrangement = Arrangement.spacedBy(ChurSpacing.two),
                    verticalArrangement = Arrangement.spacedBy(ChurSpacing.two),
                ) {
                    gridItems(rows, key = { it.first.id }) { (album, depth) ->
                        albumCard(album, depth)
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(ChurSpacing.gutter),
                    verticalArrangement = Arrangement.spacedBy(ChurSpacing.two),
                ) {
                    items(rows, key = { it.first.id }) { (album, depth) ->
                        albumCard(album, depth)
                    }
                }
            }
            if (dragged?.let { rootAlbumDrop(it, albums) } != null) {
                Box(
                    modifier = Modifier.align(Alignment.BottomEnd).fillMaxWidth(0.55f)
                        .padding(end = ChurSpacing.gutter, bottom = ChurSpacing.one)
                        .onGloballyPositioned { rootDropBounds = it.boundsInWindow() }
                        .background(if (rootDropBounds.contains(dropPoint)) colors.accentSoft else colors.surface)
                        .border(2.dp, colors.accent, RoundedCornerShape(12.dp))
                        .heightIn(min = 64.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Drop: top level", color = colors.accent)
                }
            }
            }
        }
    }

    renaming?.let { album ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename album") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Album name") },
                    colors = churOutlinedTextFieldColors(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    actions.onRenameAlbum(album, renameText.trim())
                    renaming = null
                }, enabled = renameText.isNotBlank()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    moving?.let { album ->
        AlertDialog(
            onDismissRequest = { moving = null },
            title = { Text("Move album") },
            text = {
                Column(modifier = Modifier.heightIn(max = 360.dp)) {
                    TextButton(onClick = {
                        actions.onMoveAlbum(album, null, null); moving = null
                    }) { Text("Top level") }
                    LazyColumn {
                        items(albums.filter { it.id != album.id && !descendantOf(it, album, albums) },
                            key = { it.id }) { parent ->
                            TextButton(onClick = {
                                actions.onMoveAlbum(album, parent, null); moving = null
                            }) { Text(parent.name) }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { moving = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { album ->
        val descendants = albums.count { descendantOf(it, album, albums) }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete album?") },
            text = {
                Text(
                    "Delete ${album.name} and $descendants nested album${if (descendants == 1) "" else "s"}? " +
                        "Their media will remain in the library.",
                )
            },
            confirmButton = {
                TextButton(onClick = { actions.onDeleteAlbum(album); deleting = null }) { Text("Delete albums") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
