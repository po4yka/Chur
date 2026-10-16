package dev.po4yka.chur.ffi

/** A caller-owned external source. Do not log or persist this descriptor record. */
data class ExternalArchiveFile(
    val descriptor: Int,
    val displayName: String,
    val contentType: String,
    val knownLength: Long?,
) {
    override fun toString(): String = "ExternalArchiveFile(<redacted>)"
}

/** The native parser enforces these bounds again, including actual bytes read. */
internal fun encodeExternalArchiveRequest(
    files: List<ExternalArchiveFile>,
    password: ByteArray,
    maxOutputBytes: Long,
): ByteArray {
    require(password.size in 1..1024 && files.size in 1..32 && maxOutputBytes > 0) { "invalid external archive request" }
    val metadata = mutableListOf<Pair<ByteArray, ByteArray>>()
    try {
        var knownBytes = 0L
        for (file in files) {
            require(file.descriptor >= 0) { "invalid external source" }
            require(file.displayName.isNotEmpty() && file.displayName != "." && file.displayName != ".." &&
                file.displayName.none { it == '/' || it == '\\' || it.isISOControl() }) { "invalid external filename" }
            val name = file.displayName.encodeToByteArray(throwOnInvalidSequence = true)
            val type = file.contentType.encodeToByteArray(throwOnInvalidSequence = true)
            metadata.add(name to type)
            require(name.size in 1..1024 && type.size <= 255) { "external metadata exceeds limit" }
            file.knownLength?.let { length ->
                require(length in 0..EXTERNAL_ARCHIVE_MAX_SOURCE_BYTES) { "external source exceeds limit" }
                knownBytes += length
                require(knownBytes <= EXTERNAL_ARCHIVE_MAX_SOURCE_BYTES) { "external selection exceeds limit" }
            }
        }
        val size = 1 + 4 + password.size + 8 + 4 + metadata.indices.sumOf { index ->
            4 + metadata[index].first.size + 4 + metadata[index].second.size + 1 + if (files[index].knownLength == null) 0 else 8
        }
        val writer = PrivateNoteWriter(size)
        return try {
            writer.byte(1); writer.sized(password); writer.number(maxOutputBytes); writer.count(files.size)
            metadata.forEachIndexed { index, (name, type) ->
                writer.sized(name); writer.sized(type)
                val length = files[index].knownLength
                writer.byte(if (length == null) 0 else 1)
                if (length != null) writer.number(length)
            }
            writer.bytes
        } catch (error: Throwable) {
            writer.bytes.fill(0)
            throw error
        }
    } finally {
        metadata.forEach { (name, type) -> name.fill(0); type.fill(0) }
    }
}

private const val EXTERNAL_ARCHIVE_MAX_SOURCE_BYTES = 4L * 1024 * 1024 * 1024
