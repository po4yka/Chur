package dev.po4yka.chur.notes

import kotlinx.serialization.Serializable

/**
 * The public Notes shell of `docs/product/DISCREET_MODE.md` and `DESIGN.md`
 * §19.
 *
 * It is a real application, not a decoration: §19 requires the shell to be
 * usable on its own merits, because a shell nobody would use is a shell that
 * announces what it hides. It is also entirely public. Nothing here reaches the
 * vault, and `docs/security/PLAINTEXT_LIFECYCLE.md` §1 forbids private data in
 * the public store, so the two share no type, no store, and no process state
 * beyond the composition root.
 *
 * The store is deliberately small. `PROVISIONING.md` §2 has first launch open
 * the shell with no account, no permission prompt, and no key generation, so
 * the shell must work before anything else exists.
 */

/** One public note. Nothing here is private, and nothing here is encrypted. */
@Serializable
data class Note(
    val id: String,
    val title: String,
    val body: String,
    val updatedMs: Long,
    val pinned: Boolean = false,
) {
    /** The first line, which the list shows when the title is empty. */
    val displayTitle: String
        get() = title.ifBlank { body.lineSequence().firstOrNull()?.take(60)?.ifBlank { null } ?: "" }

    /** A one-line preview, with the title's line removed when it supplied one. */
    val preview: String
        get() {
            val lines = body.lineSequence().filter { it.isNotBlank() }.toList()
            val skipFirst = title.isBlank() && lines.isNotEmpty()
            return lines.drop(if (skipFirst) 1 else 0).firstOrNull()?.take(120).orEmpty()
        }
}

/**
 * Where public notes live.
 *
 * It is an interface so the composition root binds a platform store and a test
 * binds an in-memory one, and so nothing in the shell can reach a private API
 * by accident: this is the only persistence the shell knows about.
 */
interface NoteStore {
    /** Every note, most recently updated first, pinned notes ahead of the rest. */
    suspend fun all(): List<Note>

    /** Writes a note, replacing one with the same identifier. */
    suspend fun put(note: Note)

    /** Removes a note. */
    suspend fun remove(id: String)
}

/**
 * The shell's own logic, which is the ordering and the search.
 *
 * It is separate from the store so both are testable without the other, and
 * because the ordering is the one rule the shell has that a reader would want
 * to check.
 */
object Notes {
    /** Pinned first, then most recently updated, then by identifier. */
    fun ordered(notes: List<Note>): List<Note> =
        notes.sortedWith(
            compareByDescending<Note> { it.pinned }
                .thenByDescending { it.updatedMs }
                .thenBy { it.id },
        )

    /**
     * A case-insensitive substring search over the title and the body.
     *
     * The public shell has no index and needs none: its scale is a person's
     * notes, and `CATALOG_SCHEMA_V1.md` §16.4's reasoning about `LIKE` scanning
     * applies to a million objects rather than to a few dozen notes.
     */
    fun search(notes: List<Note>, terms: String): List<Note> {
        val needle = terms.trim()
        if (needle.isEmpty()) return ordered(notes)
        return ordered(
            notes.filter { note ->
                note.title.contains(needle, ignoreCase = true) ||
                    note.body.contains(needle, ignoreCase = true)
            },
        )
    }
}

/** An in-memory store, for a test and for a first run before a platform store. */
class InMemoryNoteStore(initial: List<Note> = emptyList()) : NoteStore {
    private val notes = LinkedHashMap<String, Note>()

    init {
        initial.forEach { notes[it.id] = it }
    }

    override suspend fun all(): List<Note> = Notes.ordered(notes.values.toList())

    override suspend fun put(note: Note) {
        notes[note.id] = note
    }

    override suspend fun remove(id: String) {
        notes.remove(id)
    }
}
