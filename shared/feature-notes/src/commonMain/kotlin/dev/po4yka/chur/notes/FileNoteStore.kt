package dev.po4yka.chur.notes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The public store, which is one JSON file.
 *
 * `docs/product/DISCREET_MODE.md` requires a shell that is a real application,
 * and a notes application that forgets every note when the process ends is a
 * decoy with extra steps. So the shell persists, and it persists in the plain:
 * `PLAINTEXT_LIFECYCLE.md` §1 forbids private data in a public store, and the
 * converse is what makes this file safe to write unencrypted. Nothing here has
 * ever been inside the vault.
 *
 * One file rather than a database, because the scale is a person's notes. The
 * whole file is rewritten on every change, which is the same reasoning: at this
 * size the simplest durable write is also the fastest one.
 */
class FileNoteStore(private val path: String) : NoteStore {
    private val mutex = Mutex()
    private var cache: MutableMap<String, Note>? = null
    private var acknowledged = false

    override suspend fun all(): List<Note> = mutex.withLock {
        Notes.ordered(load().values.toList())
    }

    override suspend fun put(note: Note): Unit = mutex.withLock {
        val notes = load()
        notes[note.id] = note
        save(notes)
    }

    override suspend fun remove(id: String): Unit = mutex.withLock {
        val notes = load()
        if (notes.remove(id) != null) save(notes)
    }

    override suspend fun disclosureAcknowledged(): Boolean = mutex.withLock {
        load()
        acknowledged
    }

    override suspend fun acknowledgeDisclosure(): Unit = mutex.withLock {
        val notes = load()
        if (!acknowledged) {
            acknowledged = true
            save(notes)
        }
    }

    /**
     * Reads the file once and keeps it.
     *
     * A file that exists and does not parse is an error rather than an empty
     * store. The empty store is the more convenient answer and the wrong one:
     * the next write would replace the unreadable file, and whatever it held
     * would be gone.
     */
    private suspend fun load(): MutableMap<String, Note> {
        cache?.let { return it }
        val text = withContext(Dispatchers.Default) { readNoteFile(path) }
        val notes = LinkedHashMap<String, Note>()
        if (text != null && text.isNotBlank()) {
            val file = try {
                json.decodeFromString(NoteFileV1.serializer(), text)
            } catch (cause: Exception) {
                throw IllegalStateException("the note file at $path is not readable", cause)
            }
            file.notes.forEach { notes[it.id] = it }
            acknowledged = file.disclosureAcknowledged
        }
        cache = notes
        return notes
    }

    private suspend fun save(notes: MutableMap<String, Note>) {
        val text = json.encodeToString(
            NoteFileV1.serializer(),
            NoteFileV1(notes = notes.values.toList(), disclosureAcknowledged = acknowledged),
        )
        withContext(Dispatchers.Default) { writeNoteFile(path, text) }
    }

    private companion object {
        val json: Json = Json { prettyPrint = false }
    }
}

/**
 * The file's contents.
 *
 * The version field is what a later shell reads first. It costs one integer
 * now and is the difference between migrating the file and guessing at it.
 */
@Serializable
private data class NoteFileV1(
    val version: Int = 1,
    val notes: List<Note>,
    /**
     * Whether the first-write disclosure has been shown.
     *
     * It defaults to false, so a file written by the build before this field
     * existed shows the statement once. Showing it to a user who has seen it
     * costs a dismissal; not showing it to one who has not is the failure
     * `DISCREET_MODE.md` forbids.
     */
    val disclosureAcknowledged: Boolean = false,
)

/** The file's text, or `null` when no file is there yet. */
internal expect fun readNoteFile(path: String): String?

/**
 * Replaces the file's text.
 *
 * The replacement must be atomic: a half-written note file is a lost note
 * file, and the write happens while a person is typing.
 */
internal expect fun writeNoteFile(path: String, text: String)
