package dev.po4yka.chur.ffi

/** A session-local expected note revision. Never serialize this selection as saved state. */
data class PrivateNoteSelection(val noteId: ByteArray, val revision: Long) {
    override fun toString(): String = "PrivateNoteSelection(<redacted>)"
}
