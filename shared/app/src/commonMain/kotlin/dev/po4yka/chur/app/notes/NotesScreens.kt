package dev.po4yka.chur.app.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.app.theme.BackGlyph
import dev.po4yka.chur.app.theme.ChurSpacing
import dev.po4yka.chur.app.theme.DestructiveButton
import dev.po4yka.chur.app.theme.PlusGlyph
import dev.po4yka.chur.app.theme.SettingsGlyph
import dev.po4yka.chur.app.theme.LocalChurColors
import dev.po4yka.chur.app.theme.churOutlinedTextFieldColors
import dev.po4yka.chur.notes.Note
import dev.po4yka.chur.notes.Notes
import kotlinx.coroutines.delay

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
            if (notes.isNotEmpty()) {
                FloatingActionButton(onClick = onCreate) {
                    Icon(PlusGlyph, contentDescription = "New note")
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (showFirstWriteDisclosure) {
                FirstWriteDisclosure(onAcknowledge = onAcknowledgeDisclosure)
            }
            if (notes.isEmpty()) {
                EmptyNotes(hasQuery = false, onCreate = {
                    onQueryChange("")
                    onCreate()
                })
            } else {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    label = { Text("Search notes") },
                    modifier = Modifier.fillMaxWidth().padding(ChurSpacing.gutter),
                    colors = churOutlinedTextFieldColors(),
                )
                val visible = Notes.search(notes, query)
                if (visible.isEmpty()) {
                    EmptyNotes(hasQuery = true)
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
private fun EmptyNotes(hasQuery: Boolean, onCreate: (() -> Unit)? = null) {
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
            onCreate?.let { Button(onClick = it) { Text("Create note") } }
        }
    }
}

/**
 * The note editor, §10.2's "folders/list/editor".
 *
 * It saves as the user writes rather than on the way out. `DISCREET_MODE.md`
 * requires a public shell that is "functional rather than a static decoy
 * screen" and "usable after process restart without opening a vault", and a
 * notes editor that drops what was typed is neither. A save on the arrow and on
 * system Back alone missed every other way out: the whole-app gate taking the
 * route on a background, an activity recreated for a font or locale change,
 * and a process the platform reclaimed each dropped what had been typed. The
 * draft is now written after a pause in typing and again when the editor
 * leaves composition, whatever took it away, and the text is saved state, so a
 * recreation brings the editor back as it was; [rememberOpenNote] says how the
 * two are restored together. [rememberNoteDraft] holds the text and makes the
 * writes.
 *
 * Which of those writes is a change is the controller's to decide against the
 * store, not this screen's against the note it opened with: text typed and then
 * typed back to what it was has still been saved once in between. The one
 * exception is a note that "Create note" made, [OpenNote.isNew], and that is
 * blank when the editor leaves. The controller cannot tell it from a stored
 * note the user emptied, which stays, so this screen removes it through
 * [onRemove]: "Create note", a word, and the word erased leave no empty
 * "Untitled" row. Only leaving removes it, never the autosave, and never a
 * note the store held when the editor opened, so opening a note the user
 * emptied earlier leaves it where it is.
 *
 * Delete asks first, because a public note has no Trash to come back from:
 * `DESIGN.md` §26 puts a short, high-consequence decision in a dialog, and §27
 * has its copy name the consequence. The question is saved state, so a
 * rotation keeps it open. Confirming clears the draft, so the save on leaving
 * has nothing to write back into the note it has just removed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(open: OpenNote, onSave: (Note) -> Unit, onRemove: (String) -> Unit, onBack: () -> Unit) {
    var draft by rememberNoteDraft(open, onSave, onRemove)
    val (title, body) = draft ?: run {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete this note?") },
            text = { Text("It is removed from this device. You cannot undo this.") },
            confirmButton = {
                DestructiveButton(onClick = {
                    confirmingDelete = false
                    draft = null
                    onRemove(open.note.id)
                    onBack()
                }) { Text("Delete note") }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
        )
    }

    val colors = LocalChurColors.current
    Scaffold(
        containerColor = colors.canvas,
        topBar = {
            TopAppBar(
                title = { Text("Note") },
                navigationIcon = {
                    // Leaving is the save: the draft's dispose writes it.
                    IconButton(onClick = onBack) {
                        Icon(BackGlyph, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { confirmingDelete = true }) { Text("Delete") }
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
                onValueChange = { draft = it to body },
                singleLine = true,
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
                colors = churOutlinedTextFieldColors(),
            )
            OutlinedTextField(
                value = body,
                onValueChange = { draft = title to it },
                label = { Text("Note") },
                modifier = Modifier.fillMaxWidth().weight(1f),
                colors = churOutlinedTextFieldColors(),
            )
        }
    }
}

/**
 * [NoteEditorScreen]'s text and the writes it makes, apart from its layout, so
 * a test can compose them without a window.
 *
 * The text is the note's title and body. It is null, and the editor closes
 * without a write, when there is no text to show: the draft was too long to
 * keep as saved state, [DRAFT_STATE_LIMIT]; saved state brought [open] back
 * without the draft, whose text [OpenNoteSaver] does not keep; or Delete has
 * removed the note. In each case the store has what was last written, and the
 * list opens the note again from there.
 *
 * The autosave writes the text [AUTOSAVE_DELAY_MS] after it last changed. When
 * this leaves composition it writes the text again, or, for a new note that is
 * blank, removes it. The removal waits for that moment: a pause while the
 * fields are empty is not the user leaving the note.
 */
@Composable
internal fun rememberNoteDraft(
    open: OpenNote,
    onSave: (Note) -> Unit,
    onRemove: (String) -> Unit,
): MutableState<Pair<String, String>?> {
    val note = open.note
    val draft = rememberSaveable(note.id, saver = DraftSaver) {
        // A restore that reaches this has lost the draft, and the restored
        // note's blank text is a placeholder, not the note's.
        mutableStateOf<Pair<String, String>?>(if (open.restored) null else note.title to note.body)
    }
    val save by rememberUpdatedState(onSave)
    val remove by rememberUpdatedState(onRemove)
    val text = draft.value ?: return draft

    LaunchedEffect(text) {
        delay(AUTOSAVE_DELAY_MS)
        save(note.copy(title = text.first, body = text.second))
    }
    DisposableEffect(note.id) {
        onDispose {
            // Read now, so what is on screen as the editor leaves is written.
            val (title, body) = draft.value ?: return@onDispose
            if (open.isNew && title.isBlank() && body.isBlank()) {
                remove(note.id)
            } else {
                save(note.copy(title = title, body = body))
            }
        }
    }
    return draft
}

/**
 * How long the editor waits after the last keystroke before it writes.
 *
 * Long enough that a word is one write rather than one per letter, because
 * `FileNoteStore` rewrites its whole file on every change; short enough that a
 * process the platform reclaims soon after the user stops typing has already
 * written.
 */
private const val AUTOSAVE_DELAY_MS = 500L

/**
 * The most text, title and body together, the editor keeps as saved state.
 *
 * ponytail: a longer draft is not saved state, because the Android saved-state
 * Bundle has a size ceiling that a note does not: past the binder's 1 MB
 * transaction limit, onStop throws TransactionTooLargeException, and Android's
 * guidance for saved state is under 50 KB, which 20,000 UTF-16 characters keep
 * to. Above it a recreation or a process restart closes the editor, and the
 * note opens again from the list, that is from the store, which has what the
 * autosave and the save on leaving last wrote. Keep drafts in the store by id
 * if long notes must reopen in the editor.
 */
internal const val DRAFT_STATE_LIMIT = 20_000

/**
 * [NoteEditorScreen]'s text as saved state: the title and body, or, over
 * [DRAFT_STATE_LIMIT], only that the draft was too long, which restores as
 * null.
 */
internal val DraftSaver: Saver<MutableState<Pair<String, String>?>, Any> = Saver(
    save = { draft ->
        draft.value?.takeIf { (title, body) -> title.length + body.length <= DRAFT_STATE_LIMIT }?.toList() ?: false
    },
    restore = { saved -> mutableStateOf((saved as? List<*>)?.let { it[0] as String to it[1] as String }) },
)

/**
 * A note open in [NoteEditorScreen].
 *
 * [isNew] marks a note that "Create note" made, which the store did not hold
 * when the editor opened. It stays set for the editor's whole session, also
 * after the autosave has stored the note, because such a note is the only one
 * the editor removes when it leaves it blank.
 *
 * [restored] marks a note that saved state brought back, [rememberOpenNote].
 * [OpenNoteSaver] keeps no text, so its title and body are blank placeholders
 * that the editor never reads: it takes its own saved draft, or closes.
 */
data class OpenNote(val note: Note, val isNew: Boolean, val restored: Boolean = false)

/**
 * The note the editor has open, kept across an activity recreation and a
 * process restart.
 *
 * `ANDROID.md` §6.3 and `IOS.md` §6.3 allow the public-shell route and public
 * notes state to be restored, and this is both: one public note, nothing from
 * the vault. Only the note's identity and [OpenNote.isNew] are saved, not its
 * text: [NoteEditorScreen] saves the draft, which is the text worth restoring,
 * and a second copy would double what the Bundle carries for nothing. The
 * restored note carries no text; the editor restores its own draft in its
 * place, or closes when that is missing or was too long to keep.
 *
 * On iOS the composition's registry is in memory and the host does not persist
 * it, so there it lasts as long as the view controller and a relaunch opens the
 * list.
 */
@Composable
fun rememberOpenNote(): MutableState<OpenNote?> =
    rememberSaveable(stateSaver = OpenNoteSaver) { mutableStateOf(null) }

internal val OpenNoteSaver: Saver<OpenNote?, Any> = Saver(
    save = { open -> open?.run { listOf(note.id, note.updatedMs, note.pinned, isNew) } },
    restore = { saved ->
        val fields = saved as List<*>
        OpenNote(
            note = Note(
                id = fields[0] as String,
                title = "",
                body = "",
                updatedMs = fields[1] as Long,
                pinned = fields[2] as Boolean,
            ),
            isNew = fields[3] as Boolean,
            restored = true,
        )
    },
)
