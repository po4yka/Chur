package dev.po4yka.chur.notes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
class FileNoteStore internal constructor(
    private val path: String,
    private val readFile: (String) -> String?,
    private val writeFile: (String, String) -> Unit,
) : NoteStore {
    constructor(path: String) : this(path, ::readNoteFile, ::writeNoteFile)

    private val mutex = Mutex()
    private var cache: State? = null

    override suspend fun all(): List<Note> = mutex.withLock {
        Notes.ordered(load().notes.values.toList())
    }

    override suspend fun put(note: Note): Unit = mutex.withLock {
        val state = load()
        save(state.copy(notes = state.notes + (note.id to note)))
    }

    override suspend fun remove(id: String): Unit = mutex.withLock {
        val state = load()
        if (id in state.notes) save(state.copy(notes = state.notes - id))
    }

    override suspend fun disclosureAcknowledged(): Boolean = mutex.withLock {
        load().acknowledged
    }

    override suspend fun acknowledgeDisclosure(): Unit = mutex.withLock {
        val state = load()
        if (!state.acknowledged) save(state.copy(acknowledged = true))
    }

    /**
     * An unreadable or unsupported file is an error, never an empty store that
     * a subsequent write could overwrite. Failed reads do not populate cache.
     */
    private suspend fun load(): State {
        cache?.let { return it }
        val text = withContext(Dispatchers.Default) { readFile(path) }
        val state = if (text != null && text.isNotBlank()) {
            val file = try {
                val document = json.parseToJsonElement(text).jsonObject
                val version = document["version"]?.let {
                    it.jsonPrimitive.intOrNull ?: error("invalid note file version")
                } ?: 1
                check(version in 1..2) { "unsupported note file version $version" }
                json.decodeFromJsonElement(NoteFile.serializer(), document)
            } catch (cause: Exception) {
                throw IllegalStateException("the note file at $path is not readable", cause)
            }
            State(file.notes.associateBy { it.id }, file.disclosureAcknowledged)
        } else {
            State(emptyMap(), false)
        }
        cache = state
        return state
    }

    /** Publish the new in-memory state only after its atomic write succeeds. */
    private suspend fun save(state: State) {
        val text = json.encodeToString(
            NoteFile.serializer(),
            NoteFile(version = 2, notes = state.notes.values.toList(), disclosureAcknowledged = state.acknowledged),
        )
        withContext(Dispatchers.Default) { writeFile(path, text) }
        cache = state
    }

    private data class State(val notes: Map<String, Note>, val acknowledged: Boolean)

    private companion object {
        val json: Json = Json { encodeDefaults = true }
    }
}

/** Version 1 omitted note formats; the note serializer preserves those as plain text. */
@Serializable
private data class NoteFile(
    val version: Int = 1,
    val notes: List<Note>,
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
