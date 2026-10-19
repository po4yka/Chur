package dev.po4yka.chur.notes

import kotlinx.serialization.Serializable

/** A retained complete saved state. Attachment descriptors point to retained app-owned payloads. */
@Serializable
data class NoteRevision(
    val id: String,
    val savedAtMs: Long,
    val note: Note,
    internal val textCheckpoint: Boolean = false,
)

object NoteOrganization {
    const val MAX_LABEL_BYTES = 64
    const val MAX_TAGS = 16

    private fun label(value: String): String {
        val trimmed = value.trim()
        require(trimmed.isNotEmpty() && trimmed.none { it.isISOControl() }) { "invalid organization label" }
        require(trimmed.encodeToByteArray(throwOnInvalidSequence = true).size <= MAX_LABEL_BYTES) { "organization label is too long" }
        return trimmed
    }

    fun folder(value: String?): String? = value?.takeIf { it.isNotBlank() }?.let(::label)
    fun tags(values: List<String>): List<String> {
        require(values.size <= MAX_TAGS) { "too many tags" }
        return values.map(::label).distinct().sortedWith { left, right ->
            val a = left.encodeToByteArray()
            val b = right.encodeToByteArray()
            var result = 0
            for (index in 0 until minOf(a.size, b.size)) {
                result = (a[index].toInt() and 255).compareTo(b[index].toInt() and 255)
                if (result != 0) break
            }
            if (result != 0) result else a.size.compareTo(b.size)
        }
    }

    fun filter(notes: List<Note>, folder: String? = null, tag: String? = null, pinnedOnly: Boolean = false): List<Note> =
        Notes.ordered(notes.filter { note ->
            (folder == null || note.folder == folder) && (tag == null || tag in note.tags) &&
                (!pinnedOnly || note.pinned)
        })
}

/** Shared mutation rules keep the file-backed and in-memory stores behaviorally identical. */
@Serializable
internal data class NoteState(
    val notes: Map<String, Note> = emptyMap(),
    val acknowledged: Boolean = false,
    val history: Map<String, List<NoteRevision>> = emptyMap(),
) {
    fun current(id: String, allowTrashed: Boolean = false): Note = (notes[id] ?: error("note not found")).also {
        check(allowTrashed || it.trashedAtMs == null) { "note is in Trash" }
    }

    fun replace(
        note: Note,
        capturePrevious: Boolean = true,
        textCheckpoint: Boolean = false,
        savedAtMs: Long = nowMs(),
    ): NoteState {
        validateNote(note)
        val previous = notes[note.id]
        if (previous == note) return this
        check(previous != null || notes.size < MAX_NOTES) { "too many notes" }
        var retained = history[note.id].orEmpty()
        if (previous != null && capturePrevious) {
            require(savedAtMs >= 0) { "invalid checkpoint timestamp" }
            retained = listOf(NoteRevision(newAttachmentId(), savedAtMs, previous, textCheckpoint)) + retained
        }
        retained = retained.take(MAX_REVISIONS)
        // A payload referenced by several retained versions counts once, including one also current.
        fun historyBytes(): Long = retained.flatMap { it.note.attachments }.distinctBy { it.storageId }
            .sumOf { it.sizeBytes.toLong() }
        while (retained.isNotEmpty() && historyBytes() > MAX_HISTORY_BYTES) retained = retained.dropLast(1)
        return copy(notes = notes + (note.id to note), history = history + (note.id to retained))
    }

    fun put(note: Note, now: Long = nowMs()): NoteState {
        val previous = notes[note.id]
        if (previous == null) {
            require(note.revision == 0L && note.recoveryEpoch == 0L && note.trashedAtMs == null && note.attachments.isEmpty()) { "invalid new note" }
            return replace(note.copy(folder = NoteOrganization.folder(note.folder), tags = NoteOrganization.tags(note.tags)))
        }
        check(previous.trashedAtMs == null) { "note is in Trash" }
        check(note.recoveryEpoch == previous.recoveryEpoch) { "note was restored; reload before saving" }
        val content = previous.copy(title = note.title, body = note.body, format = note.format,
            pinned = note.pinned, updatedMs = note.updatedMs)
        if (content.copy(updatedMs = previous.updatedMs) == previous) return this
        val textOnly = content.format == previous.format && content.pinned == previous.pinned
        val checkpoint = history[note.id]?.firstOrNull()?.takeIf { it.textCheckpoint }
        // Anchor the window to its first write, not its latest keystroke, so a long editing
        // session still receives one recoverable checkpoint per minute. Current text is
        // written on every put even when the previous state is not appended to history.
        val capture = !textOnly || checkpoint == null || now < checkpoint.savedAtMs ||
            now - checkpoint.savedAtMs >= TEXT_CHECKPOINT_MS
        return replace(content.copy(revision = nextRevision(previous)), capturePrevious = capture,
            textCheckpoint = textOnly, savedAtMs = now)
    }

    fun blobs(): Map<String, NoteAttachment> = (notes.values.toList() + history.values.flatten().map { it.note })
        .flatMap { it.attachments }.associateBy { it.storageId }

    fun validate() {
        require(notes.size <= MAX_NOTES && history.keys.all { it in notes }) { "invalid note history owner" }
        val storageOwners = mutableMapOf<String, Pair<String, NoteAttachment>>()
        notes.forEach { (id, note) ->
            require(id == note.id) { "invalid note identifier" }
            validateNote(note)
            val versions = history[id].orEmpty()
            require(versions.size <= MAX_REVISIONS && versions.map { it.id }.toSet().size == versions.size &&
                versions.zipWithNext().all { (newer, older) -> newer.note.revision > older.note.revision }) { "invalid note history" }
            versions.forEach {
                require(NoteAttachments.validId(it.id) && it.savedAtMs >= 0 && it.note.id == id && it.note.revision < note.revision && it.note.recoveryEpoch <= note.recoveryEpoch) {
                    "invalid note revision"
                }
                validateNote(it.note)
            }
            require(versions.flatMap { it.note.attachments }.distinctBy { it.storageId }
                .sumOf { it.sizeBytes.toLong() } <= MAX_HISTORY_BYTES) { "note history is too large" }
            (listOf(note) + versions.map { it.note }).flatMap { it.attachments }.forEach { attachment ->
                val owner = id to attachment
                require(storageOwners.put(attachment.storageId, owner).let { it == null || it == owner }) {
                    "conflicting attachment storage identifier"
                }
            }
        }
    }

    companion object {
        const val TEXT_CHECKPOINT_MS = 60_000L
        const val MAX_REVISIONS = 20
        const val MAX_HISTORY_BYTES = 64L * 1024 * 1024
        const val MAX_NOTES = 1000
        const val MAX_STORE_BYTES = 32 * 1024 * 1024
    }
}

internal fun validateNote(note: Note) {
    require(note.id.isNotBlank() && note.id.encodeToByteArray(throwOnInvalidSequence = true).size <= 256 &&
        note.id.none { it.isISOControl() }) { "invalid note identifier" }
    require(note.revision >= 0 && note.recoveryEpoch >= 0 && note.updatedMs >= 0 && (note.trashedAtMs == null || note.trashedAtMs >= 0)) { "invalid note timestamp or revision" }
    require('\u0000' !in note.title && '\u0000' !in note.body) { "notes cannot contain NUL" }
    require(note.title.encodeToByteArray(throwOnInvalidSequence = true).size <= 256) { "note title is too long" }
    require(note.body.encodeToByteArray(throwOnInvalidSequence = true).size <= 256 * 1024) { "note body is too long" }
    require(NoteOrganization.folder(note.folder) == note.folder && NoteOrganization.tags(note.tags) == note.tags) { "non-canonical organization metadata" }
    NoteAttachments.validateList(note.attachments)
}

internal fun nextRevision(note: Note): Long {
    check(note.revision < Long.MAX_VALUE) { "note revision is exhausted" }
    return note.revision + 1
}

internal fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

internal fun nextEpoch(note: Note): Long {
    check(note.recoveryEpoch < Long.MAX_VALUE) { "note recovery epoch is exhausted" }
    return note.recoveryEpoch + 1
}
