package dev.po4yka.chur.imports

import java.io.File
import java.io.FileInputStream
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.security.MessageDigest

actual fun readLivePhotoBundle(
    descriptor: Int,
    knownLength: Long?,
    validatePayloads: Boolean,
    cancelRequested: () -> Boolean,
): LivePhotoBundle? {
    // Open an independent descriptor. Positional channel reads never move the
    // import descriptor. isFile rejects pipes before an open can wait or consume.
    val path = listOf(File("/proc/self/fd/$descriptor"), File("/dev/fd/$descriptor")).firstOrNull { it.isFile }
        ?: return null
    return FileInputStream(path).use { input ->
        val channel = input.channel
        fun read(offset: Long, length: Int): ByteArray {
            val bytes = ByteArray(length)
            val target = ByteBuffer.wrap(bytes)
            while (target.hasRemaining()) {
                if (cancelRequested()) throw InterruptedIOException("Live Photo import cancelled")
                val count = channel.read(target, offset + target.position())
                if (count <= 0) throw LivePhotoBundleException("Truncated Live Photo")
            }
            return bytes
        }
        val size = channel.size()
        if (size < 8) return@use null
        val magic = read(0, 8)
        try { if (!LivePhotoBundle.hasMagic(magic)) return@use null } finally { magic.fill(0) }
        val fixed = read(0, LivePhotoBundle.FIXED_HEADER_BYTES)
        val header = read(0, LivePhotoBundle.headerLength(fixed))
        val bundle = try { LivePhotoBundle.parse(header, size) } finally { fixed.fill(0); header.fill(0) }
        if (validatePayloads) {
            val buffer = ByteArray(64 * 1024)
            try {
                for (resource in bundle.resources) {
                    val hash = livePhotoDigest()
                    var at = 0L
                    while (at < resource.byteLength) {
                        if (cancelRequested()) throw InterruptedIOException("Live Photo import cancelled")
                        val take = minOf(buffer.size.toLong(), resource.byteLength - at).toInt()
                        val count = channel.read(ByteBuffer.wrap(buffer, 0, take), resource.offset + at)
                        if (count <= 0) throw LivePhotoBundleException("Truncated Live Photo resource")
                        hash.update(buffer, 0, count)
                        at += count
                    }
                    val digest = hash.finish()
                    try { if (!MessageDigest.isEqual(digest, resource.sha256)) throw LivePhotoBundleException("Live Photo checksum mismatch") }
                    finally { digest.fill(0) }
                }
            } finally { buffer.fill(0) }
        }
        bundle
    }
}

actual fun livePhotoDigest(): LivePhotoDigest = object : LivePhotoDigest {
    private val digest = MessageDigest.getInstance("SHA-256")
    override fun update(bytes: ByteArray, offset: Int, length: Int) { digest.update(bytes, offset, length) }
    override fun finish(): ByteArray = digest.digest()
}
