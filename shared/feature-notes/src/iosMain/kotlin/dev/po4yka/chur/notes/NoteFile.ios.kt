@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.po4yka.chur.notes

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

internal actual fun readNoteFile(path: String): String? {
    val data = NSData.dataWithContentsOfFile(path) ?: return null
    val length = data.length.toInt()
    if (length == 0) return ""
    val bytes = ByteArray(length)
    bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
    return bytes.decodeToString()
}

/**
 * Writes through Foundation's atomic replacement.
 *
 * `atomically` is the same temporary-then-rename that the Android side does by
 * hand; Foundation already owns it here, so this does not repeat it.
 *
 * The text becomes UTF-8 in Kotlin rather than through `NSString`. The
 * conversion is the same one, and doing it here keeps the encoding a decision
 * this file makes rather than one Foundation makes for it.
 */
internal actual fun writeNoteFile(path: String, text: String) {
    val bytes = text.encodeToByteArray()
    val data = if (bytes.isEmpty()) {
        NSData()
    } else {
        bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }
    }
    if (!data.writeToFile(path, atomically = true)) {
        error("cannot replace the note file at $path")
    }
}
