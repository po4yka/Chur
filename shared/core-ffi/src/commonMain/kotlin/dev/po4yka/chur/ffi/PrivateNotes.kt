package dev.po4yka.chur.ffi

/** Private plaintext exists only in the unlocked session. Never log or save these records. */
data class PrivateNote(
    val noteId: ByteArray,
    val revision: Long,
    val title: String,
    val body: String,
    val createdMs: Long,
    val updatedMs: Long,
    val pinned: Boolean,
    val trashedAtMs: Long?,
    val exportSize: Long,
) {
    val id: String get() = noteId.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    override fun toString(): String = "PrivateNote(<redacted>)"
}

data class PrivateNoteSummary(
    val noteId: ByteArray,
    val revision: Long,
    val title: String,
    val preview: String,
    val createdMs: Long,
    val updatedMs: Long,
    val pinned: Boolean,
    val trashedAtMs: Long?,
    val exportSize: Long,
) {
    val id: String get() = noteId.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    override fun toString(): String = "PrivateNoteSummary(<redacted>)"
}

data class PrivateNotePage(val notes: List<PrivateNoteSummary>, val nextCursor: ByteArray?) {
    override fun toString(): String = "PrivateNotePage(<redacted>)"
}

enum class PrivateNoteAction { PIN, UNPIN, TRASH, RESTORE, PURGE }

internal class PrivateNoteWriter(size: Int) {
    val bytes = ByteArray(size)
    private var at = 0
    fun byte(value: Int) { bytes[at++] = value.toByte() }
    fun number(value: Long) { for (shift in 56 downTo 0 step 8) byte((value ushr shift).toInt()) }
    fun raw(value: ByteArray) { value.copyInto(bytes, at); at += value.size }
    fun count(value: Int) { for (shift in 24 downTo 0 step 8) byte(value ushr shift) }
    fun sized(value: ByteArray) { for (shift in 24 downTo 0 step 8) byte(value.size ushr shift); raw(value) }
}
internal class PrivateNoteReader(private val bytes: ByteArray) {
    private var at = 0
    fun byte(): Int { require(at < bytes.size); return bytes[at++].toInt() and 255 }
    fun number(): Long { var out = 0L; repeat(8) { out = (out shl 8) or byte().toLong() }; return out }
    fun raw(size: Int): ByteArray { require(size >= 0 && size <= bytes.size - at); return bytes.copyOfRange(at, at + size).also { at += size } }
    fun sized(max: Int): ByteArray { var n = 0L; repeat(4) { n = (n shl 8) or byte().toLong() }; require(n <= max); return raw(n.toInt()) }
    fun text(max: Int): String { val b = sized(max); return try { b.decodeToString(throwOnInvalidSequence = true) } finally { b.fill(0) } }
    fun note(): PrivateNote {
        val id = raw(16); require(id.any { it != 0.toByte() })
        val revision = number(); val created = number(); val updated = number(); val deleted = number()
        val pin = byte(); val size = number()
        require(revision > 0 && created >= 0 && updated >= 0 && (deleted == -1L || deleted >= 0) && pin <= 1 && size in 2..262402)
        return PrivateNote(id, revision, text(256), text(262144), created, updated, pin == 1, if (deleted == -1L) null else deleted, size)
    }
    fun end() { require(at == bytes.size) }
}

internal fun decodePrivateNote(bytes: ByteArray): PrivateNote = PrivateNoteReader(bytes).let { r -> r.note().also { r.end() } }
internal fun decodePrivateNotePage(bytes: ByteArray): PrivateNotePage {
    val r = PrivateNoteReader(bytes)
    val countBytes = r.raw(4)
    val count = countBytes.fold(0) { a, b -> (a shl 8) or (b.toInt() and 255) }
    require(count in 0..100)
    val notes = List(count) {
        val n = r.note()
        PrivateNoteSummary(n.noteId, n.revision, n.title, n.body, n.createdMs, n.updatedMs, n.pinned, n.trashedAtMs, n.exportSize)
    }
    val cursor = r.sized(65).takeIf { it.isNotEmpty() }
    require(cursor == null || cursor.size == 65); r.end()
    return PrivateNotePage(notes, cursor)
}
