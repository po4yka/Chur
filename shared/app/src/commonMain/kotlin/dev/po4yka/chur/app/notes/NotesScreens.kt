package dev.po4yka.chur.app.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.app.theme.BackGlyph
import dev.po4yka.chur.app.theme.ChurSpacing
import dev.po4yka.chur.app.theme.PlusGlyph
import dev.po4yka.chur.app.theme.SettingsGlyph
import dev.po4yka.chur.app.theme.LocalChurColors
import dev.po4yka.chur.notes.Note
import dev.po4yka.chur.notes.Notes

/**
 * The public Notes shell, `DESIGN.md` §19.
 *
 * It is a real application, because a shell nobody would use announces what it
 * hides. It reaches nothing private: this file imports `:shared:feature-notes`
 * and no vault type at all, which the module graph also enforces.
 *
 * The route to the vault is the visible settings entry `PROVISIONING.md` §2
 * requires and that v1 cannot remove. It is a plain row, not a secret.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    notes: List<Note>,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpen: (Note) -> Unit,
    onCreate: () -> Unit,
    onOpenSettings: () -> Unit,
    showFirstWriteDisclosure: Boolean = false,
    onAcknowledgeDisclosure: () -> Unit = {},
) {
    val colors = LocalChurColors.current
    Scaffold(
        containerColor = colors.canvas,
        topBar = {
            TopAppBar(
                title = { Text("Notes") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(SettingsGlyph, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreate) {
                Icon(PlusGlyph, contentDescription = "New note")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (showFirstWriteDisclosure) {
                FirstWriteDisclosure(onAcknowledge = onAcknowledgeDisclosure)
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                label = { Text("Search notes") },
                modifier = Modifier.fillMaxWidth().padding(ChurSpacing.gutter),
            )
            val visible = Notes.search(notes, query)
            if (visible.isEmpty()) {
                EmptyNotes(hasQuery = query.isNotBlank())
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = ChurSpacing.gutter,
                        vertical = ChurSpacing.two,
                    ),
                    verticalArrangement = Arrangement.spacedBy(ChurSpacing.two),
                ) {
                    items(visible, key = { it.id }) { note ->
                        NoteRow(note = note, onClick = { onOpen(note) })
                    }
                }
            }
        }
    }
}

/**
 * The statement `DISCREET_MODE.md` requires on the first public-shell write.
 *
 * It is shown once and dismissed by acknowledgement rather than by time, so a
 * user who writes a note and closes the application has still been told. The
 * copy names the vault as the protected alternative and claims nothing for the
 * shell, which that section forbids: a disclosure presented as a feature is not
 * a disclosure.
 */
@Composable
private fun FirstWriteDisclosure(onAcknowledge: () -> Unit) {
    val colors = LocalChurColors.current
    Card(modifier = Modifier.fillMaxWidth().padding(ChurSpacing.gutter)) {
        Column(
            modifier = Modifier.padding(ChurSpacing.three),
            verticalArrangement = Arrangement.spacedBy(ChurSpacing.two),
        ) {
            Text(
                text = Disclosure.FIRST_WRITE,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink,
            )
            TextButton(
                onClick = onAcknowledge,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Got it")
            }
        }
    }
}

@Composable
private fun NoteRow(note: Note, onClick: () -> Unit) {
    val colors = LocalChurColors.current
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(ChurSpacing.three)) {
            Text(
                text = note.displayTitle.ifBlank { "Untitled" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (note.preview.isNotBlank()) {
                Text(
                    text = note.preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The empty state.
 *
 * §21 of `DESIGN.md` keeps an empty state neutral, and §6.3 keeps it out of the
 * semantic colours: nothing has gone wrong.
 */
@Composable
private fun EmptyNotes(hasQuery: Boolean) {
    val colors = LocalChurColors.current
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ChurSpacing.two),
            modifier = Modifier.padding(ChurSpacing.gutterExpanded),
        ) {
            Text(
                text = if (hasQuery) "No notes match" else "No notes yet",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = if (hasQuery) {
                    "Try a different word."
                } else {
                    // "Notes stay on this device" read as a privacy
                    // assurance for content `DISCREET_MODE.md` requires be
                    // disclosed as unprotected and platform-backed-up.
                    Disclosure.EMPTY_STATE
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkMuted,
            )
        }
    }
}

/** The note editor, §10.2's "folders/list/editor". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(note: Note, onSave: (Note) -> Unit, onDelete: () -> Unit, onBack: () -> Unit) {
    var title by remember(note.id) { mutableStateOf(note.title) }
    var body by remember(note.id) { mutableStateOf(note.body) }
    val colors = LocalChurColors.current
    Scaffold(
        containerColor = colors.canvas,
        topBar = {
            TopAppBar(
                title = { Text("Note") },
                navigationIcon = {
                    IconButton(onClick = {
                        onSave(note.copy(title = title, body = body))
                        onBack()
                    }) {
                        Icon(BackGlyph, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = onDelete) { Text("Delete") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(ChurSpacing.gutter),
            verticalArrangement = Arrangement.spacedBy(ChurSpacing.two),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text("Note") },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}
