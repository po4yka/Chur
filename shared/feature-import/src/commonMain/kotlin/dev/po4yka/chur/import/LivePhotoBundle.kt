package dev.po4yka.chur.imports

/** Exact resource metadata from LIVE_PHOTO_BUNDLE_V1.md. Names are metadata, never paths. */
data class LivePhotoResource(
    val type: Int,
    val byteLength: Long,
    val sha256: ByteArray,
    val originalFilename: String,
    val uniformTypeIdentifier: String,
    val contentType: String,
    val offset: Long = 0,
)

class LivePhotoBundleException(message: String) : Exception(message)

/** A validated bounded table. Payload checksums are checked separately before import. */
data class LivePhotoBundle(
    val captureTimeMs: Long?,
    val resources: List<LivePhotoResource>,
    val headerLength: Int,
    val totalLength: Long,
) {
    private val fullPair: Boolean get() = resources.count { it.type == 5 } == 1 && resources.count { it.type == 10 } == 1
    val displayPhoto: LivePhotoResource get() = resources.single { it.type == if (fullPair) 5 else 1 }
    val displayVideo: LivePhotoResource get() = resources.single { it.type == if (fullPair) 10 else 9 }

    companion object {
        const val CONTENT_TYPE = "application/vnd.chur.live-photo"
        const val EXTENSION = "churlive"
        const val FIXED_HEADER_BYTES = 24
        const val MAX_HEADER_BYTES = 65_536
        const val MAX_BYTES = 2_147_483_648L
        private val magic = "CHURLIVE".encodeToByteArray()
        fun hasMagic(bytes: ByteArray): Boolean = bytes.size >= 8 && (0..7).all { bytes[it] == magic[it] }
        fun headerLength(fixed: ByteArray): Int {
            if (fixed.size < FIXED_HEADER_BYTES || !hasMagic(fixed)) fail("Invalid Live Photo header")
            val length = unsigned(fixed, 12, 4)
            if (length !in FIXED_HEADER_BYTES.toLong()..MAX_HEADER_BYTES.toLong()) fail("Invalid Live Photo header length")
            return length.toInt()
        }
        fun parse(header: ByteArray, totalLength: Long): LivePhotoBundle {
            if (headerLength(header) != header.size || unsigned(header, 8, 2) != 1L) fail("Unsupported Live Photo version or header")
            if (totalLength !in header.size.toLong()..MAX_BYTES) fail("Invalid Live Photo size")
            val count = unsigned(header, 10, 2).toInt()
            if (count !in 2..32) fail("Invalid Live Photo resource count")
            val capture = unsigned(header, 16, 8)
            if (capture < -1) fail("Invalid Live Photo capture time")
            var at = FIXED_HEADER_BYTES
            var payload = header.size.toLong()
            val resources = ArrayList<LivePhotoResource>(count)
            repeat(count) {
                if (header.size - at < 50) fail("Truncated Live Photo table")
                val role = unsigned(header, at, 4)
                val length = unsigned(header, at + 4, 8)
                if (role !in 1..Int.MAX_VALUE.toLong() || length < 0 || length > MAX_BYTES - payload) fail("Invalid Live Photo resource bounds")
                val digest = header.copyOfRange(at + 12, at + 44)
                val nameLength = unsigned(header, at + 44, 2).toInt()
                val utiLength = unsigned(header, at + 46, 2).toInt()
                val mimeLength = unsigned(header, at + 48, 2).toInt()
                at += 50
                if (nameLength !in 1..1024 || utiLength !in 1..255 || mimeLength !in 3..63 ||
                    nameLength + utiLength + mimeLength > header.size - at) fail("Invalid Live Photo strings")
                fun text(length: Int): String {
                    val text = try { header.decodeToString(at, at + length, throwOnInvalidSequence = true) }
                    catch (_: Exception) { fail("Invalid Live Photo UTF-8") }
                    at += length
                    if ('\u0000' in text) fail("Invalid Live Photo string")
                    return text
                }
                val name = text(nameLength)
                val uti = text(utiLength)
                val mime = text(mimeLength)
                if (mime.any { it.code > 127 } || normalizedFileContentType(mime) != mime) fail("Invalid Live Photo MIME")
                resources += LivePhotoResource(role.toInt(), length, digest, name, uti, mime, payload)
                payload += length
            }
            if (at != header.size || payload != totalLength || resources.count { it.type == 1 } != 1 ||
                resources.count { it.type == 9 } != 1 || resources.any { (it.type == 1 || it.type == 9) && it.byteLength == 0L }) {
                fail("Incomplete Live Photo resources")
            }
            return LivePhotoBundle(capture.takeIf { it >= 0 }, resources, header.size, totalLength)
        }
        fun encodeHeader(captureTimeMs: Long?, resources: List<LivePhotoResource>): ByteArray {
            val strings = resources.map { listOf(it.originalFilename.encodeToByteArray(), it.uniformTypeIdentifier.encodeToByteArray(), it.contentType.encodeToByteArray()) }
            val size = FIXED_HEADER_BYTES + strings.sumOf { row -> 50 + row.sumOf { it.size } }
            if (size > MAX_HEADER_BYTES || resources.any { it.sha256.size != 32 }) fail("Invalid Live Photo table")
            val out = ByteArray(size)
            magic.copyInto(out)
            fun put(offset: Int, value: Long, bytes: Int) { for (i in 0 until bytes) out[offset + i] = (value ushr ((bytes - i - 1) * 8)).toByte() }
            put(8, 1, 2); put(10, resources.size.toLong(), 2); put(12, size.toLong(), 4); put(16, captureTimeMs ?: -1, 8)
            var at = FIXED_HEADER_BYTES
            var total = size.toLong()
            resources.forEachIndexed { index, resource ->
                if (resource.byteLength < 0 || resource.byteLength > MAX_BYTES - total) fail("Invalid Live Photo size")
                put(at, resource.type.toLong(), 4); put(at + 4, resource.byteLength, 8)
                resource.sha256.copyInto(out, at + 12)
                strings[index].forEachIndexed { field, bytes -> put(at + 44 + field * 2, bytes.size.toLong(), 2) }
                at += 50
                strings[index].forEach { bytes -> bytes.copyInto(out, at); at += bytes.size }
                total += resource.byteLength
            }
            parse(out, total)
            return out
        }
        private fun unsigned(bytes: ByteArray, offset: Int, length: Int): Long {
            var value = 0L
            repeat(length) { value = (value shl 8) or (bytes[offset + it].toLong() and 255) }
            return value
        }
        private fun fail(message: String): Nothing = throw LivePhotoBundleException(message)
    }
}

/** Reads a small table, then streams all hashes. A malformed package must never become an opaque file. */
expect fun readLivePhotoBundle(
    descriptor: Int,
    knownLength: Long?,
    validatePayloads: Boolean = true,
    cancelRequested: () -> Boolean = { false },
): LivePhotoBundle?

/** A bounded incremental digest. finish releases native state and must be called once. */
interface LivePhotoDigest {
    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset)
    fun finish(): ByteArray
}
expect fun livePhotoDigest(): LivePhotoDigest
