@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.sync

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.posix.memcpy

internal actual fun readSyncStateFile(path: String): String? {
    val data = NSData.dataWithContentsOfFile(path) ?: return null
    val length = data.length.toInt()
    if (length == 0) return ""
    val bytes = ByteArray(length)
    bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
    return bytes.decodeToString()
}

/**
 * Writes through Foundation's atomic replacement, `FileNoteStore`'s iOS write.
 *
 * `atomically` is the same temporary-then-rename the Android side does by
 * hand; Foundation already owns it here.
 */
internal actual fun writeSyncStateFile(
    path: String,
    text: String,
) {
    val bytes = text.encodeToByteArray()
    val data =
        if (bytes.isEmpty()) {
            NSData()
        } else {
            bytes.usePinned { pinned ->
                NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
            }
        }
    if (!data.writeToFile(path, atomically = true)) {
        error("cannot replace the sync state file at $path")
    }
}

internal actual fun randomToken(): ByteArray =
    ByteArray(TOKEN_BYTES).apply {
        usePinned { pinned ->
            val status = SecRandomCopyBytes(kSecRandomDefault, TOKEN_BYTES.toULong(), pinned.addressOf(0))
            require(status == 0) { "SecRandomCopyBytes failed with $status" }
        }
    }

/** The transport token's length, `SYNC_PROTOCOL_V1.md` §6. */
private const val TOKEN_BYTES = 32
