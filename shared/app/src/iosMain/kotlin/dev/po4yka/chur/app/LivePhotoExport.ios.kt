@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.app

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.EINTR
import platform.posix.errno
import platform.posix.write

internal actual fun writeLivePhotoExportBytes(descriptor: Int, bytes: ByteArray) {
    if (bytes.isEmpty()) return
    bytes.usePinned { pinned ->
        var offset = 0
        while (offset < bytes.size) {
            val written = write(descriptor, pinned.addressOf(offset), (bytes.size - offset).toULong())
            if (written < 0 && errno == EINTR) continue
            if (written <= 0) throw ChurFailure(ChurStatus.IO_FAILURE, "the Live Photo destination")
            offset += written.toInt()
        }
    }
}
