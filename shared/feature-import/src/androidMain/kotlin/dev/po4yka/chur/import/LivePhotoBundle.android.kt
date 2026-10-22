package dev.po4yka.chur.imports

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
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
    // Host/JNI tests compile this source on a desktop JVM without Android APIs.
    // This branch is selected by the VM, never as a fallback for a denied grant.
    if (System.getProperty("java.vm.name") != "Dalvik") {
        val path = listOf(File("/proc/self/fd/$descriptor"), File("/dev/fd/$descriptor")).firstOrNull { it.isFile }
            ?: return null
        return FileInputStream(path).use { input ->
            readBundle(input.channel.size(), validatePayloads, cancelRequested) { bytes, at, length, offset ->
                input.channel.read(ByteBuffer.wrap(bytes, at, length), offset)
            }
        }
    }
    // Duplicate the granted descriptor, not its /proc path: SAF grants allow
    // descriptor reads but do not grant permission to reopen the provider file.
    // Positioned reads preserve the import cursor; reject pipes before reading.
    return ParcelFileDescriptor.fromFd(descriptor).use { input ->
        val info = Os.fstat(input.fileDescriptor)
        if (!OsConstants.S_ISREG(info.st_mode)) return@use null
        readBundle(info.st_size, validatePayloads, cancelRequested) { bytes, at, length, offset ->
            Os.pread(input.fileDescriptor, bytes, at, length, offset)
        }
    }
}

private fun readBundle(size: Long, validatePayloads: Boolean, cancelRequested: () -> Boolean,
    readAt: (ByteArray, Int, Int, Long) -> Int): LivePhotoBundle? {
    fun read(offset: Long, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var at = 0
        while (at < length) {
            if (cancelRequested()) throw InterruptedIOException("Live Photo import cancelled")
            val count = readAt(bytes, at, length - at, offset + at)
            if (count <= 0) throw LivePhotoBundleException("Truncated Live Photo")
            at += count
        }
        return bytes
    }
    if (size < 8) return null
    val magic = read(0, 8)
    try { if (!LivePhotoBundle.hasMagic(magic)) return null } finally { magic.fill(0) }
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
                    val count = readAt(buffer, 0, take, resource.offset + at)
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
    return bundle
}

actual fun livePhotoDigest(): LivePhotoDigest = object : LivePhotoDigest {
    private val digest = MessageDigest.getInstance("SHA-256")
    override fun update(bytes: ByteArray, offset: Int, length: Int) { digest.update(bytes, offset, length) }
    override fun finish(): ByteArray = digest.digest()
}
