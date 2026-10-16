package dev.po4yka.chur.ffi

const val CONTENT_INDEX_TEXT_MAX_BYTES = 262_144

/** A source reference in the current unlocked identity. Never log its metadata. */
data class ContentIndexCandidate(
    val objectId: ByteArray,
    val primaryStreamId: ByteArray,
    val mediaClass: Int,
    val plaintextSize: Long,
    val contentType: String,
) {
    override fun toString(): String = "ContentIndexCandidate(<redacted>)"
}

internal fun decodeContentIndexCandidates(bytes: ByteArray): List<ContentIndexCandidate> {
    val reader = PrivateNoteReader(bytes)
    require(reader.byte() == 1)
    val count = reader.raw(4).fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255) }
    require(count in 0..100)
    val candidates = List(count.toInt()) {
        val objectId = reader.raw(ID_LENGTH)
        val streamId = reader.raw(ID_LENGTH)
        require(objectId.any { it != 0.toByte() } && streamId.any { it != 0.toByte() })
        val mediaClass = reader.byte()
        val size = reader.number()
        require(mediaClass == 1 || mediaClass == 4)
        require(size >= 0)
        ContentIndexCandidate(objectId, streamId, mediaClass, size, reader.text(63))
    }
    reader.end()
    return candidates
}
