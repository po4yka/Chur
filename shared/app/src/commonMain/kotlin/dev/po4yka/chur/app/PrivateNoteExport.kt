package dev.po4yka.chur.app

import dev.po4yka.chur.ffi.PrivateNote

/** A session-bound export reference. It holds no note body or plaintext file path. */
class PrivateNoteExportRequest private constructor(
    val noteId: ByteArray,
    val revision: Long,
    val generation: Long,
    val filename: String,
    val exportSize: Long,
) {
    override fun toString(): String = "PrivateNoteExportRequest"

    companion object {
        fun from(note: PrivateNote, generation: Long): PrivateNoteExportRequest = PrivateNoteExportRequest(
            note.noteId.copyOf(), note.revision, generation,
            safeExportName(note.title, "private-note") + ".txt", note.exportSize,
        )
    }
}
