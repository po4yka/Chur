package dev.po4yka.chur.app.vault

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.app.ActiveOperation
import dev.po4yka.chur.app.Notice
import dev.po4yka.chur.app.PrivateNoteConfirmation
import dev.po4yka.chur.app.PrivateNoteEditorState
import dev.po4yka.chur.app.PrivateNotesActions
import dev.po4yka.chur.app.PrivateNoteInputPolicy
import dev.po4yka.chur.app.PrivateNotesUiState
import dev.po4yka.chur.app.privateKeyboardOptions
import dev.po4yka.chur.app.privateNoteTextFieldPolicy
import dev.po4yka.chur.app.notes.MarkdownEditor
import dev.po4yka.chur.app.notes.NoteImportAction
import dev.po4yka.chur.ffi.PrivateNoteFormat
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
    onImportActivity: (Boolean) -> Unit = {},
) {
    CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
        PrivateNoteInputPolicy {
            PrivateNotesContent(state, actions, onLock, onPanic, operation, onCancelOperation, notice, onNoticeShown, onImportActivity)
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
    onImportActivity: (Boolean) -> Unit,
) {
    val colors = LocalChurColors.current
    val editor = state.editor
    val selecting = editor == null && state.selection.isNotEmpty()
    val busy = state.busy || operation != null
    Scaffold(
        containerColor = colors.canvas,
        topBar = {
            TopAppBar(
                title = { Text(if (selecting) "${state.selection.size} selected" else if (editor == null) "Private notes"
                    else if (editor.trashed) "Trashed note" else "Private note",
                    maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(enabled = !state.busy, onClick = actions::back) {
                    Icon(BackGlyph, contentDescription = if (selecting) "Clear selection" else "Back")
                } },
                actions = {
                    if (selecting) TextButton(onClick = actions::exportEncryptedSelection, enabled = !busy,
                        modifier = Modifier.semantics { contentDescription = "Create encrypted archive" }) { Text("Archive") }
                    if (editor != null && !editor.trashed) TextButton(onClick = actions::save,
                        enabled = editor.canSave && !busy, modifier = Modifier.testTag("private-note-save")) { Text("Save") }
                    LockControl(onLock, onPanic)
                },
            )
        },
        snackbarHost = { NoticeHost(notice, onNoticeShown) },
        floatingActionButton = {
            if (editor == null && !state.trash && !busy && !selecting) FloatingActionButton(onClick = actions::create) {
                Icon(PlusGlyph, contentDescription = "New private note")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (editor == null) {
                PrivateNotesList(state, actions, busy, Modifier.weight(1f), onImportActivity)
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
        val export = confirmation == PrivateNoteConfirmation.EXPORT || confirmation == PrivateNoteConfirmation.ENCRYPTED_EXPORT
        PrivateAlertDialog(
            onDismissRequest = actions::dismissConfirmation,
            title = { Text(when (confirmation) {
                PrivateNoteConfirmation.LEAVE -> "Save changes before leaving?"
                PrivateNoteConfirmation.TRASH -> "Move note to Trash?"
                PrivateNoteConfirmation.PURGE -> "Delete this note permanently?"
                PrivateNoteConfirmation.EXPORT, PrivateNoteConfirmation.ENCRYPTED_EXPORT -> "Save changes before exporting?"
            }) },
            text = { Text(when (confirmation) {
                PrivateNoteConfirmation.LEAVE -> "Keep editing, save your changes, or discard this unsaved text."
                PrivateNoteConfirmation.TRASH -> if (editor?.dirty == true) "The saved note moves to Trash. Unsaved changes are discarded."
                    else "You can restore this note from Trash."
                PrivateNoteConfirmation.PURGE -> "This removes the note from this vault. You cannot undo this."
                PrivateNoteConfirmation.EXPORT -> "Export uses the saved note. Save these changes first, then choose a destination."
                PrivateNoteConfirmation.ENCRYPTED_EXPORT -> "The encrypted archive uses the saved note. Save these changes first, then set an archive password."
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PrivateNotesList(state: PrivateNotesUiState, actions: PrivateNotesActions, busy: Boolean, modifier: Modifier, onImportActivity: (Boolean) -> Unit) {
    var importFailed by remember { mutableStateOf(false) }
    val colors = LocalChurColors.current
    val selecting = state.selection.isNotEmpty()
    Column(modifier.padding(horizontal = ChurSpacing.gutter), verticalArrangement = Arrangement.spacedBy(ChurSpacing.one)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (!state.trash && !selecting) NoteImportAction(enabled = !busy,
                onImported = actions::importMarkdown, onFailure = { importFailed = true }, onPickerActive = onImportActivity)
            if (!selecting) TextButton(enabled = !busy, onClick = { actions.showTrash(!state.trash) }) {
                Text(if (state.trash) "Active notes" else "Trash")
            }
            if (!selecting) TextButton(enabled = !busy && !state.loading, onClick = actions::refresh) { Text("Refresh") }
            if (!state.trash) TextButton(enabled = !busy && !state.loading && state.notes.isNotEmpty(),
                onClick = actions::selectPage) { Text("Select page") }
        }
        if (importFailed) Text("The Markdown file could not be imported. Use UTF-8 text up to 256 KiB.", color = colors.error)
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
                val selectedNote = note.id in state.selection
                Surface(color = if (selectedNote) colors.accentSoft else colors.surfaceSunken, modifier = Modifier.fillMaxWidth()
                    .semantics { selected = selectedNote }
                    .combinedClickable(enabled = !busy, onClickLabel = if (selecting) "Select private note" else "Open private note",
                        onClick = { if (selecting) actions.toggleSelection(note) else actions.open(note) },
                        onLongClickLabel = if (state.trash) null else "Select private note",
                        onLongClick = if (state.trash) null else ({ actions.toggleSelection(note) }))) {
                    Row(Modifier.padding(ChurSpacing.two), verticalAlignment = Alignment.CenterVertically) {
                        if (selecting) Checkbox(checked = selectedNote,
                            onCheckedChange = { actions.toggleSelection(note) }, enabled = !busy,
                            modifier = Modifier.semantics { contentDescription = "Select private note" })
                        Column(Modifier.weight(1f)) {
                            Text(note.title.ifBlank { "Untitled note" }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                color = colors.ink, style = MaterialTheme.typography.titleSmall)
                            if (note.preview.isNotBlank()) Text(note.preview, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                color = colors.inkMuted, style = MaterialTheme.typography.bodyMedium)
                            if (note.pinned) Text("Pinned", color = colors.inkMuted, style = MaterialTheme.typography.labelSmall)
                        }
                        if (!state.trash && !selecting) TextButton(enabled = !busy, onClick = { actions.pin(note) }) {
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
    BoxWithConstraints(modifier) {
        // Body has its own bounded viewport; metadata never competes for its height.
        // The surrounding form scrolls below the sticky Save/Lock app bar.
        val editorHeight = maxOf(360.dp, maxHeight * 0.7f)
        Column(Modifier.fillMaxWidth().testTag("private-note-editor-scroll")
            .verticalScroll(rememberScrollState()).padding(ChurSpacing.gutter),
            verticalArrangement = Arrangement.spacedBy(ChurSpacing.one)) {
            if (editor.trashed && editor.format == PrivateNoteFormat.MARKDOWN) {
                Text(editor.title.ifBlank { "Untitled note" }, style = MaterialTheme.typography.titleLarge, color = colors.ink)
                dev.po4yka.chur.app.notes.MarkdownPreview(editor.body, Modifier.height(editorHeight).fillMaxWidth())
            } else if (editor.trashed) {
                LazyColumn(Modifier.height(editorHeight), verticalArrangement = Arrangement.spacedBy(ChurSpacing.two)) {
                    item { Text(editor.title.ifBlank { "Untitled note" }, style = MaterialTheme.typography.titleLarge, color = colors.ink) }
                    items(documentTextChunks(editor.body)) { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.ink) }
                }
            } else {
                OutlinedTextField(value = editor.title, onValueChange = actions::title, singleLine = true, enabled = !busy,
                    keyboardOptions = privateKeyboardOptions().copy(imeAction = ImeAction.Next), label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth().privateNoteTextFieldPolicy().testTag("private-note-title"),
                    colors = churOutlinedTextFieldColors())
                MarkdownEditor(editor.body, actions::body, editor.format == PrivateNoteFormat.MARKDOWN,
                    { actions.format(if (it) PrivateNoteFormat.MARKDOWN else PrivateNoteFormat.PLAIN_TEXT) },
                    Modifier.fillMaxWidth().height(editorHeight), private = true, enabled = !busy)
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
                if (!editor.trashed) TextButton(enabled = !busy && (editor.saved != null || editor.canSave),
                    onClick = actions::exportEncrypted) { Text("Encrypted archive") }
            }
        }
    }
}
