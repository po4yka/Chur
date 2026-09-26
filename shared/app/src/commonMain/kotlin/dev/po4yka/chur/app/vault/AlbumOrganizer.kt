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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.app.theme.ChurSpacing
import dev.po4yka.chur.app.theme.LocalChurColors
import dev.po4yka.chur.ffi.AlbumSummary
import dev.po4yka.chur.ffi.toHex

/** Tree order is entirely supplied by the encrypted catalog. */
internal fun albumRows(albums: List<AlbumSummary>): List<Pair<AlbumSummary, Int>> {
    val result = ArrayList<Pair<AlbumSummary, Int>>(albums.size)
    val visited = HashSet<String>()
    val children = albums.groupBy { it.parentId?.toHex() }
        .mapValues { (_, siblings) -> siblings.sortedWith(compareBy<AlbumSummary> { it.position }.thenBy { it.id }) }
    val pending = ArrayDeque<Pair<AlbumSummary, Int>>()
    children[null].orEmpty().asReversed().forEach { pending.addLast(it to 0) }
    while (pending.isNotEmpty()) {
        val (album, depth) = pending.removeLast()
        if (visited.add(album.id)) {
            result += album to depth
            children[album.id].orEmpty().asReversed().forEach { pending.addLast(it to depth + 1) }
        }
    }
    albums.sortedWith(compareBy<AlbumSummary> { it.position }.thenBy { it.id })
        .filterNot { it.id in visited }
        .forEach { result += it to 0 }
    return result
}

private fun parentOf(album: AlbumSummary, albums: List<AlbumSummary>): AlbumSummary? =
    albums.firstOrNull { parent -> album.parentId?.contentEquals(parent.albumId) == true }

private fun siblingsOf(album: AlbumSummary, albums: List<AlbumSummary>): List<AlbumSummary> =
    albums.filter { child ->
        if (album.parentId == null) child.parentId == null
        else child.parentId?.contentEquals(album.parentId) == true
    }.sortedWith(compareBy<AlbumSummary> { it.position }.thenBy { it.id })

private fun descendantOf(candidate: AlbumSummary, ancestor: AlbumSummary, albums: List<AlbumSummary>): Boolean {
    var parent = parentOf(candidate, albums)
    repeat(albums.size) {
        if (parent == null) return false
        if (parent.id == ancestor.id) return true
        parent = parentOf(parent, albums)
    }
    return true
}

/** Album navigation, editing, nesting, and drag placement for both hosts. */
@Composable
fun AlbumOrganizer(albums: List<AlbumSummary>, actions: VaultActions) {
    val colors = LocalChurColors.current
    var renaming by remember { mutableStateOf<AlbumSummary?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<AlbumSummary?>(null) }
    var moving by remember { mutableStateOf<AlbumSummary?>(null) }
    val bounds = remember { mutableMapOf<String, Rect>() }
    var dragged by remember { mutableStateOf<AlbumSummary?>(null) }
    var dropPoint by remember { mutableStateOf(Offset.Zero) }
    val rows = remember(albums) { albumRows(albums) }

    var grid by remember { mutableStateOf(false) }
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
                .onGloballyPositioned { bounds[album.id] = it.boundsInWindow() }
                .pointerInput(album.id, albums) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            dragged = album
                            dropPoint = (bounds[album.id]?.topLeft ?: Offset.Zero) + offset
                        },
                        onDragEnd = {
                            val source = dragged
                            val target = rows.map { it.first }.firstOrNull {
                                it.id != source?.id && bounds[it.id]?.contains(dropPoint) == true
                            }
                            if (source != null && target != null &&
                                !descendantOf(target, source, albums)
                            ) {
                                val area = bounds[target.id]!!
                                val relativeY = (dropPoint.y - area.top) / area.height
                                if (relativeY in 0.25f..0.75f) {
                                    actions.onMoveAlbum(source, target, null)
                                } else {
                                    val targetSiblings = siblingsOf(target, albums)
                                    val before = if (relativeY < 0.25f) target else
                                        targetSiblings.getOrNull(targetSiblings.indexOfFirst { it.id == target.id } + 1)
                                    actions.onMoveAlbum(source, parentOf(target, albums), before)
                                }
                            }
                            dragged = null
                        },
                        onDragCancel = { dragged = null },
                        onDrag = { change, amount ->
                            change.consume()
                            dropPoint += amount
                        },
                    )
                },
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
                        "${album.memberCount} item${if (album.memberCount == 1L) "" else "s"} · Hold to drag",
                        style = MaterialTheme.typography.bodySmall, color = colors.inkMuted,
                    )
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
                        if (index > 0) {
                            DropdownMenuItem(text = { Text("Move up") }, onClick = {
                                menu = false
                                actions.onMoveAlbum(album, parent, siblings[index - 1])
                            })
                        }
                        if (index in 0 until siblings.lastIndex) {
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

    if (albums.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No albums yet", style = MaterialTheme.typography.titleMedium)
                Text("Group objects you want to find together.", color = colors.inkMuted)
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = ChurSpacing.gutter)) {
                TextButton(onClick = { grid = false }) { Text(if (grid) "List" else "✓ List") }
                TextButton(onClick = { grid = true }) { Text(if (grid) "✓ Grid" else "Grid") }
            }
            if (grid) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(240.dp),
                    modifier = Modifier.weight(1f),
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
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(ChurSpacing.gutter),
                    verticalArrangement = Arrangement.spacedBy(ChurSpacing.two),
                ) {
                    items(rows, key = { it.first.id }) { (album, depth) ->
                        albumCard(album, depth)
                    }
                }
            }
        }
    }

    renaming?.let { album ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename album") },
            text = { OutlinedTextField(renameText, { renameText = it }, label = { Text("Album name") }) },
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
