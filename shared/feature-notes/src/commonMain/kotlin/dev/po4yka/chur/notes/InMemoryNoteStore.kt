package dev.po4yka.chur.notes

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The same retention and recovery rules as the durable store, without platform I/O. */
class InMemoryNoteStore(initial: List<Note> = emptyList()) : NoteStore {
    private val mutex = Mutex()
    private var state = NoteState(initial.associateBy { it.id }).also { it.validate() }
    private val blobs = mutableMapOf<String, ByteArray>()

    override suspend fun all(): List<Note> = mutex.withLock { Notes.ordered(state.notes.values.filter { it.trashedAtMs == null }) }
    override suspend fun trash(): List<Note> = mutex.withLock { Notes.ordered(state.notes.values.filter { it.trashedAtMs != null }) }
    override suspend fun put(note: Note): Note = mutex.withLock {
        commit(state.put(note))
        state.current(note.id)
    }
    override suspend fun remove(id: String): Unit = mutex.withLock {
        val note = state.notes[id] ?: return@withLock
        if (note.trashedAtMs == null) commit(state.replace(note.copy(trashedAtMs = nowMs(),
            revision = nextRevision(note), recoveryEpoch = nextEpoch(note))))
    }
    override suspend fun restore(id: String, updatedMs: Long): Note = mutex.withLock {
        val note = state.current(id, allowTrashed = true)
        check(note.trashedAtMs != null) { "note is not in Trash" }
        val restored = note.copy(trashedAtMs = null, updatedMs = updatedMs, revision = nextRevision(note), recoveryEpoch = nextEpoch(note))
        commit(state.replace(restored))
        restored
    }
    override suspend fun purge(id: String): Unit = mutex.withLock {
        check(state.current(id, allowTrashed = true).trashedAtMs != null) { "only trashed notes can be purged" }
        commit(state.copy(notes = state.notes - id, history = state.history - id))
    }
    override suspend fun updateOrganization(id: String, folder: String?, tags: List<String>): Note = mutex.withLock {
        val note = state.current(id)
        val organized = note.copy(folder = NoteOrganization.folder(folder), tags = NoteOrganization.tags(tags))
        if (organized == note) return@withLock note
        val next = organized.copy(revision = nextRevision(note), updatedMs = nowMs())
        commit(state.replace(next))
        next
    }
    override suspend fun history(id: String): List<NoteRevision> = mutex.withLock {
        state.current(id, allowTrashed = true)
        state.history[id].orEmpty()
    }
    override suspend fun restoreRevision(noteId: String, revisionId: String): Note = mutex.withLock {
        val note = state.current(noteId)
        val version = state.history[noteId].orEmpty().singleOrNull { it.id == revisionId } ?: error("revision not found")
        version.note.attachments.forEach { check(it.storageId in blobs) { "attachment content is missing" } }
        val restored = version.note.copy(updatedMs = nowMs(), trashedAtMs = null, revision = nextRevision(note), recoveryEpoch = nextEpoch(note))
        commit(state.replace(restored))
        restored
    }
    override suspend fun addAttachment(noteId: String, name: String, data: ByteArray): NoteAttachment = mutex.withLock {
        val note = state.current(noteId)
        val attachment = NoteAttachment(newAttachmentId(), name, NoteAttachments.validate(name, data), data.size)
        val next = state.replace(note.copy(attachments = note.attachments + attachment, revision = nextRevision(note), updatedMs = nowMs()))
        blobs[attachment.storageId] = data.copyOf()
        commit(next)
        attachment
    }
    override suspend fun readAttachment(noteId: String, attachmentId: String): ByteArray = mutex.withLock {
        val note = state.current(noteId, allowTrashed = true)
        val attachment = note.attachments.singleOrNull { it.id == attachmentId } ?: error("attachment not found")
        blobs[attachment.storageId]?.copyOf() ?: error("attachment content is missing")
    }
    override suspend fun removeAttachment(noteId: String, attachmentId: String): Unit = mutex.withLock {
        val note = state.current(noteId)
        val attachment = note.attachments.singleOrNull { it.id == attachmentId } ?: return@withLock
        commit(state.replace(note.copy(attachments = note.attachments - attachment, revision = nextRevision(note), updatedMs = nowMs())))
    }
    override suspend fun exportPackage(noteId: String): ByteArray = mutex.withLock {
        val note = state.current(noteId, allowTrashed = true)
        val contents = note.attachments.map {
            NotePackageAttachment(it.id, it.name, it.mimeType, blobs[it.storageId] ?: error("attachment content is missing"))
        }
        NotePackages.encode(NotePackage(note.title, note.body, note.format, contents))
    }
    override suspend fun importPackage(data: ByteArray, noteId: String, updatedMs: Long): Note = mutex.withLock {
        require(noteId !in state.notes) { "note already exists" }
        val imported = NotePackages.decode(data)
        try {
            val attachments = imported.attachments.map { NoteAttachment(it.id, it.name, it.mimeType, it.data.size, newAttachmentId()) }
            val note = Note(noteId, imported.title, imported.body, updatedMs, format = imported.format, attachments = attachments)
            val next = state.replace(note)
            attachments.zip(imported.attachments).forEach { (descriptor, content) -> blobs[descriptor.storageId] = content.data.copyOf() }
            commit(next)
            note
        } finally {
            imported.attachments.forEach { it.data.fill(0) }
        }
    }
    override suspend fun disclosureAcknowledged(): Boolean = mutex.withLock { state.acknowledged }
    override suspend fun acknowledgeDisclosure(): Unit = mutex.withLock { state = state.copy(acknowledged = true) }

    private fun commit(next: NoteState) {
        next.validate()
        val live = next.blobs().keys
        blobs.keys.filterNot { it in live }.forEach { blobs.remove(it)?.fill(0) }
        state = next
    }
}
