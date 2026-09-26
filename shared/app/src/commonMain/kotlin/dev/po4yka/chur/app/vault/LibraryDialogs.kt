package dev.po4yka.chur.app.vault

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.app.theme.churOutlinedTextFieldColors
import dev.po4yka.chur.ffi.AlbumSummary
import dev.po4yka.chur.ffi.TagSummary

@Composable
fun NewAlbumDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New album") },
        text = {
            OutlinedTextField(
                colors = churOutlinedTextFieldColors(),
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Album name") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.trim()) }, enabled = name.isNotBlank()) {
                Text("Create")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun AlbumPickerDialog(
    albums: List<AlbumSummary>,
    currentAlbum: AlbumSummary?,
    move: Boolean,
    onChoose: (AlbumSummary) -> Unit,
    onCreate: (String, AlbumSummary?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var parent by remember(currentAlbum?.id) { mutableStateOf(currentAlbum) }
    var parentExpanded by remember { mutableStateOf(false) }
    val verb = if (move) "Move" else "Add"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$verb to album") },
        text = {
            Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                albumRows(albums).filterNot { it.first.id == currentAlbum?.id }.forEach { (album, depth) ->
                    TextButton(
                        onClick = { onChoose(album) },
                        modifier = Modifier.padding(start = (depth * 16).coerceAtMost(96).dp),
                    ) { Text(album.name) }
                }
                Text("Create a new destination")
                OutlinedTextField(
                    colors = churOutlinedTextFieldColors(),
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("New album name") },
                )
                Box {
                    TextButton(onClick = { parentExpanded = true }) {
                        Text("Inside: ${parent?.name ?: "Top level"}")
                    }
                    DropdownMenu(expanded = parentExpanded, onDismissRequest = { parentExpanded = false }) {
                        DropdownMenuItem(text = { Text("Top level") }, onClick = {
                            parent = null; parentExpanded = false
                        })
                        albumRows(albums).forEach { (album, depth) ->
                            DropdownMenuItem(
                                text = { Text("${"  ".repeat(depth.coerceAtMost(6))}${album.name}") },
                                onClick = { parent = album; parentExpanded = false },
                            )
                        }
                    }
                }
                TextButton(onClick = { onCreate(name.trim(), parent) }, enabled = name.isNotBlank()) {
                    Text("Create and ${verb.lowercase()}")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun TagPickerDialog(
    tags: List<TagSummary>,
    onAdd: (TagSummary) -> Unit,
    onRemove: (TagSummary) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    val existing = tags.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tags") },
        text = {
            Column(modifier = Modifier.heightIn(max = 360.dp)) {
                OutlinedTextField(colors = churOutlinedTextFieldColors(), value = filter,
                    onValueChange = { filter = it }, singleLine = true, label = { Text("Find tag") })
                LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                    items(tags.filter { it.name.contains(filter, ignoreCase = true) }, key = { it.id }) { tag ->
                    Column {
                        TextButton(onClick = { onAdd(tag) }) { Text("Add ${tag.name}") }
                        TextButton(onClick = { onRemove(tag) }) { Text("Remove") }
                    }
                    }
                }
                OutlinedTextField(
                    colors = churOutlinedTextFieldColors(),
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("New tag name") },
                )
                TextButton(
                    onClick = { if (existing == null) onCreate(name.trim()) else onAdd(existing) },
                    enabled = name.isNotBlank(),
                ) {
                    Text(if (existing == null) "Create and add" else "Add existing tag")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Browses tag scopes and manages their private names and memberships. */
@Composable
fun TagBrowserDialog(
    tags: List<TagSummary>,
    onOpen: (TagSummary) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (TagSummary, String) -> Unit,
    onDelete: (TagSummary) -> Unit,
    onDismiss: () -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<TagSummary?>(null) }
    var deleting by remember { mutableStateOf<TagSummary?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tags") },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp)) {
                OutlinedTextField(colors = churOutlinedTextFieldColors(), value = filter,
                    onValueChange = { filter = it }, singleLine = true, label = { Text("Find tag") })
                LazyColumn(modifier = Modifier.heightIn(max = 270.dp)) {
                    items(tags.filter { it.name.contains(filter, ignoreCase = true) }, key = { it.id }) { tag ->
                        Column {
                            TextButton(onClick = { onOpen(tag) }) { Text(tag.name) }
                            TextButton(onClick = { editing = tag; name = tag.name }) { Text("Rename ${tag.name}") }
                            TextButton(onClick = { deleting = tag }) { Text("Delete ${tag.name}") }
                        }
                    }
                }
                OutlinedTextField(colors = churOutlinedTextFieldColors(), value = name,
                    onValueChange = { name = it }, singleLine = true,
                    label = { Text(if (editing == null) "New tag" else "Rename ${editing?.name}") })
                TextButton(onClick = {
                    val value = name.trim()
                    val tag = editing
                    if (tag == null) onCreate(value) else onRename(tag, value)
                    editing = null
                    name = ""
                }, enabled = name.isNotBlank()) {
                    Text(if (editing == null) "Create tag" else "Save name")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
    deleting?.let { tag ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete ${tag.name}?") },
            text = { Text("This removes the tag from its media. The media stays in the vault.") },
            confirmButton = { TextButton(onClick = {
                deleting = null
                onDelete(tag)
            }) { Text("Delete tag") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
}

@Composable
fun DeleteSelectionDialog(count: Int, onDelete: () -> Unit, onDismiss: () -> Unit,
    permanent: Boolean = false, emptyTrash: Boolean = false) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (permanent) "Delete permanently?" else "Move to Trash?") },
        text = { Text(when {
            emptyTrash -> "Everything in Trash will be permanently deleted. This cannot be undone."
            permanent -> "Permanently delete $count selected ${if (count == 1) "item" else "items"}? This cannot be undone."
            else -> "Move $count selected ${if (count == 1) "item" else "items"} to Trash? They are kept for 30 days."
        }) },
        confirmButton = { TextButton(onClick = onDelete) {
            Text(if (emptyTrash) "Empty trash" else if (permanent) "Delete permanently" else "Move to Trash")
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
