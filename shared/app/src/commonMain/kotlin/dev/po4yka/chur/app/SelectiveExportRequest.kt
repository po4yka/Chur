package dev.po4yka.chur.app

import dev.po4yka.chur.ffi.PrivateNoteSelection

/** An unlocked-session selection. It contains no password, title, body, or destination. */
class SelectiveExportRequest(
    val generation: Long,
    objectIds: List<ByteArray> = emptyList(),
    albumIds: List<ByteArray> = emptyList(),
    notes: List<PrivateNoteSelection> = emptyList(),
) {
    val objectIds = objectIds.map { it.copyOf() }
    val albumIds = albumIds.map { it.copyOf() }
    val notes = notes.map { PrivateNoteSelection(it.noteId.copyOf(), it.revision) }

    init {
        require(objectIds.isNotEmpty() || albumIds.isNotEmpty() || notes.isNotEmpty())
        require(objectIds.size <= 1_000 && albumIds.size <= 1_000 && notes.size <= 1_000)
        require(objectIds.all { it.size == 16 } && albumIds.all { it.size == 16 } && notes.all { it.noteId.size == 16 })
        require(notes.all { it.revision > 0 })
    }

    /** Counts only: the host can show this without exposing private names. */
    val summary: String get() = buildList {
        if (objectIds.isNotEmpty()) add("${objectIds.size} ${if (objectIds.size == 1) "file" else "files"}")
        if (albumIds.isNotEmpty()) add("${albumIds.size} ${if (albumIds.size == 1) "album" else "albums"}, including subalbums")
        if (notes.isNotEmpty()) add("${notes.size} private ${if (notes.size == 1) "note" else "notes"}")
    }.joinToString("; ")

    override fun toString(): String = "SelectiveExportRequest(<redacted>)"
}
