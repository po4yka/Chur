package dev.po4yka.chur.app

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.PrivateNote
import dev.po4yka.chur.ffi.PrivateNoteAction
import dev.po4yka.chur.ffi.PrivateNotePage
import dev.po4yka.chur.ffi.PrivateNoteSummary
import dev.po4yka.chur.vault.VaultRepository
import dev.po4yka.chur.vault.VaultState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val PRIVATE_NOTE_TITLE_MAX_BYTES = 256
const val PRIVATE_NOTE_BODY_MAX_BYTES = 262_144

/** No serializer or saver: this value exists only in its unlocked route. */
data class PrivateNoteEditorState(
    val saved: PrivateNote? = null,
    val title: String = saved?.title.orEmpty(),
    val body: String = saved?.body.orEmpty(),
    val pinned: Boolean = saved?.pinned ?: false,
) {
    val trashed: Boolean get() = saved?.trashedAtMs != null
    val dirty: Boolean get() = if (saved == null) title.isNotEmpty() || body.isNotEmpty() || pinned
        else title != saved.title || body != saved.body || pinned != saved.pinned
    val canSave: Boolean get() = !trashed && dirty && (saved != null || title.isNotBlank() || body.isNotBlank())
    override fun toString(): String = "PrivateNoteEditorState(<redacted>)"
}

enum class PrivateNoteConfirmation { LEAVE, TRASH, PURGE, EXPORT }

data class PrivateNotesUiState(
    val notes: List<PrivateNoteSummary> = emptyList(),
    val terms: String = "",
    val trash: Boolean = false,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val editor: PrivateNoteEditorState? = null,
    val confirmation: PrivateNoteConfirmation? = null,
    val error: String? = null,
    val page: Int = 1,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
) {
    override fun toString(): String = "PrivateNotesUiState(<redacted>)"
}

/** Every storage method must validate [generation] while holding the repository mutex. */
internal interface PrivateNotesAccess {
    suspend fun page(generation: Long, terms: String, trash: Boolean, cursor: ByteArray?): PrivateNotePage
    suspend fun load(generation: Long, id: ByteArray): PrivateNote
    suspend fun save(generation: Long, saved: PrivateNote?, title: String, body: String, pinned: Boolean): PrivateNote
    suspend fun change(generation: Long, id: ByteArray, revision: Long, action: PrivateNoteAction): PrivateNote?
}

private class VaultPrivateNotesAccess(private val vault: VaultRepository) : PrivateNotesAccess {
    override suspend fun page(generation: Long, terms: String, trash: Boolean, cursor: ByteArray?) =
        vault.privateNotes(generation, terms = terms, trashed = trash, limit = 50, cursor = cursor)
    override suspend fun load(generation: Long, id: ByteArray) = vault.privateNote(generation, id)
    override suspend fun save(generation: Long, saved: PrivateNote?, title: String, body: String, pinned: Boolean) =
        vault.savePrivateNote(generation, id = saved?.noteId, expectedRevision = saved?.revision ?: 0,
            title = title, body = body, pinned = pinned)
    override suspend fun change(generation: Long, id: ByteArray, revision: Long, action: PrivateNoteAction) =
        vault.changePrivateNote(generation, id, revision, action)
}

/**
 * Explicit saves only. Lock or disposal cancels work and drops UI text; it never
 * writes a draft. Native generation and revision guards are the write boundary.
 */
class PrivateNotesController internal constructor(
    private val generation: Long,
    private val vaultState: StateFlow<VaultState>,
    private val access: PrivateNotesAccess,
    parentScope: CoroutineScope,
    private val onLeave: () -> Unit,
    private val onExport: (PrivateNote) -> Unit,
    private val dispatcher: CoroutineDispatcher,
    private val onActivity: () -> Unit = {},
) : PrivateNotesActions {
    constructor(vault: VaultRepository, generation: Long, scope: CoroutineScope,
        onLeave: () -> Unit, onExport: (PrivateNote) -> Unit, onActivity: () -> Unit = {}) :
        this(generation, vault.state, VaultPrivateNotesAccess(vault), scope, onLeave, onExport, Dispatchers.Default, onActivity)

    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val mutableState = MutableStateFlow(PrivateNotesUiState())
    val state: StateFlow<PrivateNotesUiState> = mutableState.asStateFlow()
    private var closed = false
    private var request = 0L
    private var listing: Job? = null
    private var cursor: ByteArray? = null
    private var nextCursor: ByteArray? = null
    private val previous = mutableListOf<ByteArray?>()

    init {
        scope.launch {
            vaultState.collect { if (!current()) close() }
        }
        refresh()
    }

    private fun current(): Boolean = !closed && (vaultState.value as? VaultState.Unlocked)?.generation == generation
    private fun update(change: (PrivateNotesUiState) -> PrivateNotesUiState) {
        if (current()) mutableState.value = change(mutableState.value)
    }

    fun close() {
        if (closed) return
        closed = true
        request++
        cursor = null
        nextCursor = null
        previous.clear()
        mutableState.value = PrivateNotesUiState()
        job.cancel()
    }

    override fun refresh() = loadPage(reset = true)
    override fun search(terms: String) {
        if (!current() || state.value.editor != null || state.value.busy) return
        if (terms != state.value.terms) onActivity()
        if (utf8Size(terms) > 256 || terms.splitToSequence(Regex("\\s+")).count { it.isNotEmpty() } > 32) {
            update { it.copy(error = "Use at most 256 UTF-8 bytes and 32 words in the search.") }
            return
        }
        update { it.copy(terms = terms, error = null) }
        loadPage(reset = true, debounce = true)
    }
    override fun showTrash(trash: Boolean) {
        if (!current() || state.value.editor != null || state.value.busy) return
        update { it.copy(trash = trash, terms = "", error = null) }
        loadPage(reset = true)
    }
    override fun nextPage() {
        if (!current() || state.value.loading || nextCursor == null) return
        previous += cursor
        cursor = nextCursor?.copyOf()
        loadPage(reset = false)
    }
    override fun previousPage() {
        if (!current() || state.value.loading || previous.isEmpty()) return
        cursor = previous.removeAt(previous.lastIndex)
        loadPage(reset = false)
    }
    private fun loadPage(reset: Boolean, debounce: Boolean = false) {
        if (!current()) return
        listing?.cancel()
        if (reset) { cursor = null; nextCursor = null; previous.clear() }
        val revision = ++request
        val snapshot = state.value
        val pageCursor = cursor?.copyOf()
        update { it.copy(loading = true, error = null, notes = if (reset) emptyList() else it.notes,
            hasNext = false, hasPrevious = previous.isNotEmpty(), page = previous.size + 1) }
        listing = scope.launch {
            try {
                if (debounce) delay(200)
                val result = withContext(dispatcher) { access.page(generation, snapshot.terms, snapshot.trash, pageCursor) }
                if (current() && request == revision) {
                    nextCursor = result.nextCursor?.copyOf()
                    update { it.copy(notes = result.notes, loading = false, hasNext = nextCursor != null) }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                if (request == revision) update { it.copy(loading = false, error = errorCopy(failure)) }
            }
        }
    }

    override fun create() {
        if (!current() || state.value.busy || state.value.editor != null) return
        listing?.cancel()
        request++
        update { it.copy(editor = PrivateNoteEditorState(), confirmation = null, error = null, loading = false) }
    }
    override fun open(note: PrivateNoteSummary) = runAction {
        if (state.value.editor != null) return@runAction
        listing?.cancel()
        request++
        val loaded = withContext(dispatcher) { access.load(generation, note.noteId) }
        update { it.copy(editor = PrivateNoteEditorState(loaded), confirmation = null, loading = false) }
    }
    override fun title(value: String) = edit { copy(title = value) }
    override fun body(value: String) = edit { copy(body = value) }
    override fun pinned(value: Boolean) = edit { copy(pinned = value) }
    private fun edit(change: PrivateNoteEditorState.() -> PrivateNoteEditorState) {
        if (!current() || state.value.busy) return
        val editor = state.value.editor?.takeUnless { it.trashed } ?: return
        val changed = editor.change()
        if (changed != editor) onActivity()
        if (utf8Size(changed.title) > PRIVATE_NOTE_TITLE_MAX_BYTES || utf8Size(changed.body) > PRIVATE_NOTE_BODY_MAX_BYTES) {
            update { it.copy(error = "Use at most 256 UTF-8 bytes for the title and 256 KiB for the note.") }
            return
        }
        update { it.copy(editor = changed, error = null) }
    }

    override fun back() {
        if (!current() || state.value.busy) return
        val editor = state.value.editor
        when {
            editor == null -> onLeave()
            editor.dirty -> update { it.copy(confirmation = PrivateNoteConfirmation.LEAVE) }
            else -> leaveEditor()
        }
    }
    override fun dismissConfirmation() = update { it.copy(confirmation = null) }
    override fun discardAndBack() {
        if (state.value.confirmation == PrivateNoteConfirmation.LEAVE && !state.value.busy) leaveEditor()
    }
    private fun leaveEditor() {
        update { it.copy(editor = null, confirmation = null, error = null) }
        // A saved or trashed note changes the native cursor generation.
        loadPage(reset = true)
    }
    override fun save() = saveThen()
    override fun saveAndBack() = saveThen(leave = true)
    override fun saveAndExport() = saveThen(export = true)
    private fun saveThen(leave: Boolean = false, export: Boolean = false) {
        val editor = state.value.editor ?: return
        if (!editor.canSave) return
        update { it.copy(confirmation = null) }
        runAction {
            val saved = withContext(dispatcher) { access.save(generation, editor.saved, editor.title, editor.body, editor.pinned) }
            update { it.copy(editor = PrivateNoteEditorState(saved)) }
            when {
                export && current() -> onExport(saved)
                leave -> leaveEditor()
            }
        }
    }
    override fun export() {
        if (!current() || state.value.busy) return
        val editor = state.value.editor ?: return
        if (editor.dirty) update { it.copy(confirmation = PrivateNoteConfirmation.EXPORT) }
        else editor.saved?.let(onExport)
    }
    override fun requestTrash() {
        if (state.value.editor?.saved != null) update { it.copy(confirmation = PrivateNoteConfirmation.TRASH) }
    }
    override fun requestPurge() {
        if (state.value.editor?.trashed == true) update { it.copy(confirmation = PrivateNoteConfirmation.PURGE) }
    }
    override fun trash() = changeOpen(PrivateNoteAction.TRASH)
    override fun restore() = changeOpen(PrivateNoteAction.RESTORE)
    override fun purge() = changeOpen(PrivateNoteAction.PURGE)
    private fun changeOpen(action: PrivateNoteAction) {
        if (action == PrivateNoteAction.TRASH && state.value.confirmation != PrivateNoteConfirmation.TRASH) return
        if (action == PrivateNoteAction.PURGE && state.value.confirmation != PrivateNoteConfirmation.PURGE) return
        val saved = state.value.editor?.saved ?: return
        update { it.copy(confirmation = null) }
        runAction {
            withContext(dispatcher) { access.change(generation, saved.noteId, saved.revision, action) }
            leaveEditor()
        }
    }
    override fun pin(note: PrivateNoteSummary) = runAction {
        withContext(dispatcher) { access.change(generation, note.noteId, note.revision,
            if (note.pinned) PrivateNoteAction.UNPIN else PrivateNoteAction.PIN) }
        loadPage(reset = true)
    }

    private fun runAction(action: suspend () -> Unit) {
        if (!current() || state.value.busy) return
        update { it.copy(busy = true, error = null) }
        scope.launch {
            try {
                if (current()) action()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                update { it.copy(error = errorCopy(failure)) }
            } finally { update { it.copy(busy = false) } }
        }
    }
    private fun errorCopy(failure: Exception): String = when (failure) {
        is ChurFailure -> if (failure.status == ChurStatus.CONFLICT) {
            if (state.value.editor != null) "This note changed. Your unsaved text is still here. Reopen the note to load the saved version."
            else "This note changed. Refresh the list and try again."
        } else userCopy(failure.status)
        else -> if (state.value.editor != null) "The note operation could not finish. Your unsaved text is still here. Try again."
            else "The note operation could not finish. Try again."
    }
}

/** Counts without allocating another complete plaintext byte array on each key press. */
internal fun utf8Size(text: String): Int {
    var size = 0
    var index = 0
    while (index < text.length) {
        val c = text[index++]
        size += when {
            c.code <= 0x7f -> 1
            c.code <= 0x7ff -> 2
            c.isHighSurrogate() && index < text.length && text[index].isLowSurrogate() -> { index++; 4 }
            c.isSurrogate() -> 3 // A conservative replacement-character bound on every host.
            else -> 3
        }
    }
    return size
}
