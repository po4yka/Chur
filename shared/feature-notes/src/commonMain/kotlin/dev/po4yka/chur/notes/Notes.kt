package dev.po4yka.chur.notes

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

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

/** The syntax of the body; legacy plain text is never implicitly reinterpreted. */
@Serializable
enum class NoteFormat { PLAIN_TEXT, MARKDOWN }

/** One public note. Nothing here is private, and nothing here is encrypted. */
@Serializable(with = NoteSerializer::class)
data class Note(
    val id: String,
    val title: String,
    val body: String,
    val updatedMs: Long,
    val pinned: Boolean = false,
    val format: NoteFormat = NoteFormat.MARKDOWN,
    val attachments: List<NoteAttachment> = emptyList(),
    val folder: String? = null,
    val tags: List<String> = emptyList(),
    val trashedAtMs: Long? = null,
    val revision: Long = 0,
    val recoveryEpoch: Long = 0,
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

/** Decoding an absent format must retain the meaning of pre-Markdown notes. */
internal object NoteSerializer : KSerializer<Note> {
    override val descriptor: SerialDescriptor = StoredNote.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Note) {
        encoder.encodeSerializableValue(
            StoredNote.serializer(),
            StoredNote(value.id, value.title, value.body, value.updatedMs, value.pinned, value.format, value.attachments, value.folder, value.tags, value.trashedAtMs, value.revision, value.recoveryEpoch),
        )
    }

    override fun deserialize(decoder: Decoder): Note {
        val stored = decoder.decodeSerializableValue(StoredNote.serializer())
        return Note(stored.id, stored.title, stored.body, stored.updatedMs, stored.pinned, stored.format, stored.attachments, stored.folder, stored.tags, stored.trashedAtMs, stored.revision, stored.recoveryEpoch)
    }
}

@Serializable
private data class StoredNote(
    val id: String,
    val title: String,
    val body: String,
    val updatedMs: Long,
    val pinned: Boolean = false,
    val format: NoteFormat = NoteFormat.PLAIN_TEXT,
    val attachments: List<NoteAttachment> = emptyList(),
    val folder: String? = null,
    val tags: List<String> = emptyList(),
    val trashedAtMs: Long? = null,
    val revision: Long = 0,
    val recoveryEpoch: Long = 0,
)

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
    suspend fun put(note: Note): Note

    suspend fun trash(): List<Note>
    suspend fun restore(id: String, updatedMs: Long): Note
    suspend fun purge(id: String)
    suspend fun updateOrganization(id: String, folder: String?, tags: List<String>): Note
    suspend fun history(id: String): List<NoteRevision>
    suspend fun restoreRevision(noteId: String, revisionId: String): Note

    /** Moves a note to durable Trash. */
    suspend fun remove(id: String)

    /** Adds content to the latest persisted note; never publishes metadata before bytes. */
    suspend fun addAttachment(noteId: String, name: String, data: ByteArray): NoteAttachment

    suspend fun readAttachment(noteId: String, attachmentId: String): ByteArray

    suspend fun removeAttachment(noteId: String, attachmentId: String)

    suspend fun exportPackage(noteId: String): ByteArray

    /** Imports a complete note, preserving source and attachment reference IDs. */
    suspend fun importPackage(data: ByteArray, noteId: String, updatedMs: Long): Note

    /**
     * Whether the public-shell disclosure has been shown.
     *
     * `docs/product/DISCREET_MODE.md` requires the statement on the first
     * public-shell write and once only, so the answer has to survive a process
     * restart. It lives with the notes because it is public-shell state:
     * putting it in the vault would make a disclosure about unprotected
     * content depend on unlocking the protected one.
     */
    suspend fun disclosureAcknowledged(): Boolean

    /** Records that the disclosure has been shown. */
    suspend fun acknowledgeDisclosure()
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
                    note.body.contains(needle, ignoreCase = true) ||
                    note.folder?.contains(needle, ignoreCase = true) == true ||
                    note.tags.any { it.contains(needle, ignoreCase = true) }
            },
        )
    }
}
