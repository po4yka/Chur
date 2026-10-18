package dev.po4yka.chur.app

import dev.po4yka.chur.ffi.PrivateNote
import dev.po4yka.chur.ffi.PrivateNoteFormat
import dev.po4yka.chur.app.notes.noteExportFilename

/** A session-bound export reference. It holds no note body or plaintext file path. */
class PrivateNoteExportRequest private constructor(
    val noteId: ByteArray,
    val revision: Long,
    val generation: Long,
    val filename: String,
    val exportSize: Long,
    val contentType: String,
) {
    override fun toString(): String = "PrivateNoteExportRequest"

    companion object {
        fun from(note: PrivateNote, generation: Long): PrivateNoteExportRequest = PrivateNoteExportRequest(
            note.noteId.copyOf(), note.revision, generation,
            noteExportFilename(safeExportName(note.title, "private-note"), note.format == PrivateNoteFormat.MARKDOWN), note.exportSize,
            if (note.format == PrivateNoteFormat.MARKDOWN) "text/markdown" else "text/plain",
        )
    }
}
