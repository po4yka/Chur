package dev.po4yka.chur.notes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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

    override suspend fun put(note: Note): Unit = mutate {
        val state = load()
        require(note.attachments == state.notes[note.id]?.attachments.orEmpty()) {
            "use attachment operations to change attachments"
        }
        save(state.copy(notes = state.notes + (note.id to note)))
    }

    override suspend fun remove(id: String): Unit = mutate {
        val state = load()
        val removed = state.notes[id]
        if (removed != null) {
            save(state.copy(notes = state.notes - id))
            removed.attachments.forEach { deleteBlob(it.storageId) }
        }
    }

    override suspend fun addAttachment(noteId: String, name: String, data: ByteArray): NoteAttachment = mutate {
        val state = load()
        val note = state.notes[noteId] ?: error("note not found")
        val attachment = NoteAttachment(newAttachmentId(), name, NoteAttachments.validate(name, data), data.size)
        NoteAttachments.validateList(note.attachments + attachment)
        writeBlob(attachment.storageId, data)
        try {
            save(state.copy(notes = state.notes + (noteId to note.copy(attachments = note.attachments + attachment))))
        } catch (cause: Throwable) {
            deleteBlob(attachment.storageId)
            throw cause
        }
        attachment
    }

    override suspend fun readAttachment(noteId: String, attachmentId: String): ByteArray = mutex.withLock {
        val note = load().notes[noteId] ?: error("note not found")
        val attachment = note.attachments.singleOrNull { it.id == attachmentId } ?: error("attachment not found")
        readBlob(attachment)
    }

    override suspend fun removeAttachment(noteId: String, attachmentId: String): Unit = mutate {
        val state = load()
        val note = state.notes[noteId] ?: error("note not found")
        val attachment = note.attachments.singleOrNull { it.id == attachmentId } ?: return@mutate
        save(state.copy(notes = state.notes + (noteId to note.copy(attachments = note.attachments - attachment))))
        deleteBlob(attachment.storageId)
    }

    override suspend fun exportPackage(noteId: String): ByteArray = mutex.withLock {
        val note = load().notes[noteId] ?: error("note not found")
        val contents = mutableListOf<NotePackageAttachment>()
        try {
            note.attachments.forEach {
                contents += NotePackageAttachment(it.id, it.name, it.mimeType, readBlob(it))
            }
            NotePackages.encode(NotePackage(note.title, note.body, note.format, contents))
        } finally {
            contents.forEach { it.data.fill(0) }
        }
    }

    override suspend fun importPackage(data: ByteArray, noteId: String, updatedMs: Long): Note = mutate {
        val state = load()
        require(noteId !in state.notes) { "note already exists" }
        val imported = NotePackages.decode(data)
        try {
            val attachments = imported.attachments.map {
                NoteAttachment(it.id, it.name, it.mimeType, it.data.size, storageId = newAttachmentId())
            }
            val note = Note(noteId, imported.title, imported.body, updatedMs, format = imported.format, attachments = attachments)
            val written = mutableListOf<String>()
            try {
                attachments.zip(imported.attachments).forEach { (descriptor, content) ->
                    writeBlob(descriptor.storageId, content.data)
                    written += descriptor.storageId
                }
                save(state.copy(notes = state.notes + (noteId to note)))
            } catch (cause: Throwable) {
                written.forEach { deleteBlob(it) }
                throw cause
            }
            note
        } finally {
            imported.attachments.forEach { it.data.fill(0) }
        }
    }

    // Once a durable mutation starts, finish its metadata/cache publication and cleanup even
    // when the composing screen goes away. Waiting for another writer remains cancellable.
    private suspend fun <T> mutate(block: suspend () -> T): T = mutex.withLock {
        withContext(NonCancellable) { block() }
    }

    private fun blobPath(storageId: String): String {
        require(NoteAttachments.validId(storageId)) { "invalid attachment identifier" }
        return "$path.attachments/$storageId"
    }

    private suspend fun writeBlob(storageId: String, data: ByteArray) = withContext(Dispatchers.Default) {
        writeNoteBlob(blobPath(storageId), data)
    }

    private suspend fun readBlob(attachment: NoteAttachment): ByteArray {
        var owned: ByteArray? = null
        try {
            // Assign before switching back: prompt cancellation must not drop an unowned array.
            withContext(Dispatchers.Default) {
                owned = readNoteBlob(blobPath(attachment.storageId), NoteAttachments.MAX_FILE_BYTES)
                    ?: error("attachment content is missing")
                val data = requireNotNull(owned)
                check(data.size == attachment.sizeBytes && NoteAttachments.validate(attachment.name, data) == attachment.mimeType) {
                    "attachment content is invalid"
                }
            }
            return requireNotNull(owned).also { owned = null }
        } finally {
            owned?.fill(0)
        }
    }

    // A failed cleanup leaves an unreachable public blob, never a broken saved note.
    private suspend fun deleteBlob(storageId: String) = withContext(Dispatchers.Default) {
        runCatching { deleteNoteBlob(blobPath(storageId)) }
        Unit
    }

    override suspend fun disclosureAcknowledged(): Boolean = mutex.withLock {
        load().acknowledged
    }

    override suspend fun acknowledgeDisclosure(): Unit = mutate {
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
                check(version in 1..3) { "unsupported note file version $version" }
                json.decodeFromJsonElement(NoteFile.serializer(), document)
            } catch (cause: Exception) {
                throw IllegalStateException("the note file at $path is not readable", cause)
            }
            check(file.notes.map { it.id }.toSet().size == file.notes.size) { "duplicate note identifier" }
            file.notes.forEach { NoteAttachments.validateList(it.attachments) }
            check(file.notes.flatMap { it.attachments }.map { it.storageId }.let { it.size == it.toSet().size }) {
                "duplicate attachment storage identifier"
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
            NoteFile(version = 3, notes = state.notes.values.toList(), disclosureAcknowledged = state.acknowledged),
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

/** Binary content is bounded before allocation and atomically replaced in the app-owned directory. */
internal expect fun readNoteBlob(path: String, maxBytes: Int): ByteArray?
internal expect fun writeNoteBlob(path: String, bytes: ByteArray)
internal expect fun deleteNoteBlob(path: String)
