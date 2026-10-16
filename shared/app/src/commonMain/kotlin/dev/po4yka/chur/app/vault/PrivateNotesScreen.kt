package dev.po4yka.chur.app.vault

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import dev.po4yka.chur.app.ActiveOperation
import dev.po4yka.chur.app.Notice
import dev.po4yka.chur.app.PrivateNoteConfirmation
import dev.po4yka.chur.app.PrivateNoteEditorState
import dev.po4yka.chur.app.PrivateNotesActions
import dev.po4yka.chur.app.PrivateNoteInputPolicy
import dev.po4yka.chur.app.PrivateNotesUiState
import dev.po4yka.chur.app.privateKeyboardOptions
import dev.po4yka.chur.app.privateNoteTextFieldPolicy
import dev.po4yka.chur.app.theme.BackGlyph
import dev.po4yka.chur.app.theme.ChurSpacing
import dev.po4yka.chur.app.theme.LocalChurColors
import dev.po4yka.chur.app.theme.PlusGlyph
import dev.po4yka.chur.app.theme.churOutlinedTextFieldColors

/** The renderer has no persistence and never writes when it leaves composition. */
@Composable
fun PrivateNotesScreen(
    state: PrivateNotesUiState,
    actions: PrivateNotesActions,
    onLock: () -> Unit,
    onPanic: () -> Unit,
    operation: ActiveOperation? = null,
    onCancelOperation: () -> Unit = {},
    notice: Notice? = null,
    onNoticeShown: (Long) -> Unit = {},
) {
    CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
        PrivateNoteInputPolicy {
            PrivateNotesContent(state, actions, onLock, onPanic, operation, onCancelOperation, notice, onNoticeShown)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrivateNotesContent(
    state: PrivateNotesUiState,
    actions: PrivateNotesActions,
    onLock: () -> Unit,
    onPanic: () -> Unit,
    operation: ActiveOperation?,
    onCancelOperation: () -> Unit,
    notice: Notice?,
    onNoticeShown: (Long) -> Unit,
) {
    val colors = LocalChurColors.current
    val editor = state.editor
    val busy = state.busy || operation != null
    Scaffold(
        containerColor = colors.canvas,
        topBar = {
            TopAppBar(
                title = { Text(if (editor == null) "Private notes" else if (editor.trashed) "Trashed note" else "Private note",
                    maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(enabled = !state.busy, onClick = actions::back) {
                    Icon(BackGlyph, contentDescription = "Back")
                } },
                actions = {
                    if (editor != null && !editor.trashed) TextButton(onClick = actions::save,
                        enabled = editor.canSave && !busy, modifier = Modifier.testTag("private-note-save")) { Text("Save") }
                    LockControl(onLock, onPanic)
                },
            )
        },
        snackbarHost = { NoticeHost(notice, onNoticeShown) },
        floatingActionButton = {
            if (editor == null && !state.trash && !busy) FloatingActionButton(onClick = actions::create) {
                Icon(PlusGlyph, contentDescription = "New private note")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (editor == null) {
                PrivateNotesList(state, actions, busy, Modifier.weight(1f))
            } else {
                PrivateNoteEditor(editor, actions, busy, Modifier.weight(1f))
            }
            state.error?.let {
                Text(it, modifier = Modifier.padding(ChurSpacing.gutter).semantics { liveRegion = LiveRegionMode.Polite }, color = colors.error,
                    style = MaterialTheme.typography.bodyMedium)
            }
            operation?.let { OperationProgressCard(it, onCancelOperation,
                Modifier.fillMaxWidth().padding(horizontal = ChurSpacing.gutter)) }
            if (state.busy) Text("Saving or loading note…", modifier = Modifier.padding(ChurSpacing.gutter),
                color = colors.inkMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
    state.confirmation?.let { confirmation ->
        val leave = confirmation == PrivateNoteConfirmation.LEAVE
        val export = confirmation == PrivateNoteConfirmation.EXPORT
        AlertDialog(
            onDismissRequest = actions::dismissConfirmation,
            title = { Text(when (confirmation) {
                PrivateNoteConfirmation.LEAVE -> "Save changes before leaving?"
                PrivateNoteConfirmation.TRASH -> "Move note to Trash?"
                PrivateNoteConfirmation.PURGE -> "Delete this note permanently?"
                PrivateNoteConfirmation.EXPORT -> "Save changes before exporting?"
            }) },
            text = { Text(when (confirmation) {
                PrivateNoteConfirmation.LEAVE -> "Keep editing, save your changes, or discard this unsaved text."
                PrivateNoteConfirmation.TRASH -> if (editor?.dirty == true) "The saved note moves to Trash. Unsaved changes are discarded."
                    else "You can restore this note from Trash."
                PrivateNoteConfirmation.PURGE -> "This removes the note from this vault. You cannot undo this."
                PrivateNoteConfirmation.EXPORT -> "Export uses the saved note. Save these changes first, then choose a destination."
            }) },
            confirmButton = {
                when {
                    leave -> TextButton(enabled = editor?.canSave == true && !busy, onClick = actions::saveAndBack) { Text("Save and leave") }
                    export -> TextButton(enabled = editor?.canSave == true && !busy, onClick = actions::saveAndExport) { Text("Save and export") }
                    else -> Button(enabled = !busy, colors = ButtonDefaults.buttonColors(
                        containerColor = colors.error, contentColor = colors.onInk), onClick = {
                        if (confirmation == PrivateNoteConfirmation.TRASH) actions.trash() else actions.purge()
                    }) { Text(if (confirmation == PrivateNoteConfirmation.TRASH) "Move to Trash" else "Delete permanently") }
                }
            },
            dismissButton = {
                FlowRow {
                    if (leave) TextButton(enabled = !busy, onClick = actions::discardAndBack) { Text("Discard") }
                    TextButton(onClick = actions::dismissConfirmation) { Text(if (leave) "Keep editing" else "Cancel") }
                }
            },
        )
    }
}

@Composable
private fun PrivateNotesList(state: PrivateNotesUiState, actions: PrivateNotesActions, busy: Boolean, modifier: Modifier) {
    val colors = LocalChurColors.current
    Column(modifier.padding(horizontal = ChurSpacing.gutter), verticalArrangement = Arrangement.spacedBy(ChurSpacing.one)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(enabled = !busy, onClick = { actions.showTrash(!state.trash) }) {
                Text(if (state.trash) "Active notes" else "Trash")
            }
            TextButton(enabled = !busy && !state.loading, onClick = actions::refresh) { Text("Refresh") }
        }
        OutlinedTextField(value = state.terms, onValueChange = actions::search, enabled = !busy, singleLine = true,
            label = { Text(if (state.trash) "Search Trash" else "Search private notes") },
            keyboardOptions = privateKeyboardOptions().copy(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().privateNoteTextFieldPolicy().testTag("private-note-search"),
            colors = churOutlinedTextFieldColors())
        when {
            state.loading -> Text("Loading private notes…", color = colors.inkMuted)
            state.notes.isEmpty() && state.error == null -> Text(if (state.terms.isNotBlank()) "No matching private notes."
                else if (state.trash) "Trash is empty." else "No private notes yet. Create one to keep text in this vault.",
                color = colors.inkMuted)
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ChurSpacing.one)) {
            items(state.notes, key = { it.id }) { note ->
                Surface(color = colors.surfaceSunken, modifier = Modifier.fillMaxWidth()
                    .clickable(enabled = !busy, onClickLabel = "Open private note") { actions.open(note) }) {
                    Row(Modifier.padding(ChurSpacing.two), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(note.title.ifBlank { "Untitled note" }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                color = colors.ink, style = MaterialTheme.typography.titleSmall)
                            if (note.preview.isNotBlank()) Text(note.preview, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                color = colors.inkMuted, style = MaterialTheme.typography.bodyMedium)
                            if (note.pinned) Text("Pinned", color = colors.inkMuted, style = MaterialTheme.typography.labelSmall)
                        }
                        if (!state.trash) TextButton(enabled = !busy, onClick = { actions.pin(note) }) {
                            Text(if (note.pinned) "Unpin" else "Pin")
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = state.hasPrevious && !state.loading && !busy, onClick = actions::previousPage) { Text("Previous") }
            Text("Page ${state.page}", color = colors.inkMuted, style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = state.hasNext && !state.loading && !busy, onClick = actions::nextPage) { Text("Next") }
        }
    }
}

@Composable
private fun PrivateNoteEditor(editor: PrivateNoteEditorState, actions: PrivateNotesActions, busy: Boolean, modifier: Modifier) {
    val colors = LocalChurColors.current
    Column(modifier.padding(ChurSpacing.gutter), verticalArrangement = Arrangement.spacedBy(ChurSpacing.one)) {
        if (editor.trashed) {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ChurSpacing.two)) {
                item { Text(editor.title.ifBlank { "Untitled note" }, style = MaterialTheme.typography.titleLarge, color = colors.ink) }
                items(documentTextChunks(editor.body)) { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.ink) }
            }
        } else {
            OutlinedTextField(value = editor.title, onValueChange = actions::title, singleLine = true, enabled = !busy,
                keyboardOptions = privateKeyboardOptions().copy(imeAction = ImeAction.Next), label = { Text("Title") },
                modifier = Modifier.fillMaxWidth().privateNoteTextFieldPolicy().testTag("private-note-title"),
                colors = churOutlinedTextFieldColors())
            OutlinedTextField(value = editor.body, onValueChange = actions::body, enabled = !busy,
                keyboardOptions = privateKeyboardOptions(), label = { Text("Note") },
                modifier = Modifier.fillMaxWidth().weight(1f).privateNoteTextFieldPolicy().testTag("private-note-body"),
                colors = churOutlinedTextFieldColors())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = editor.pinned, onCheckedChange = actions::pinned, enabled = !busy)
                Text("Pinned", color = colors.ink)
            }
            Text(if (editor.dirty) "Unsaved changes are cleared when the vault locks."
                else if (editor.saved != null) "Saved in this vault." else "Save to keep this note in the vault.",
                style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (editor.trashed) {
                TextButton(enabled = !busy, onClick = actions::restore) { Text("Restore") }
                TextButton(enabled = !busy, onClick = actions::requestPurge) { Text("Delete permanently") }
            } else if (editor.saved != null) {
                TextButton(enabled = !busy, onClick = actions::requestTrash) { Text("Move to Trash") }
            }
            TextButton(enabled = !busy && (editor.saved != null || editor.canSave), onClick = actions::export) { Text("Export") }
        }
    }
}
