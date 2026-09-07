package dev.po4yka.chur.sync

import java.io.File
import java.io.IOException
import java.security.SecureRandom

internal actual fun readSyncStateFile(path: String): String? {
    val file = File(path)
    return if (file.isFile) file.readText() else null
}

/**
 * Writes beside the file and renames over it, `FileNoteStore`'s Android write.
 *
 * `rename` within one filesystem replaces the destination in one step, so a
 * reader sees either the previous file or the new one.
 */
internal actual fun writeSyncStateFile(
    path: String,
    text: String,
) {
    val file = File(path)
    file.parentFile?.mkdirs()
    val temporary = File(file.parentFile, "${file.name}.tmp")
    temporary.writeText(text)
    if (!temporary.renameTo(file)) {
        temporary.delete()
        throw IOException("cannot replace the sync state file at $path")
    }
}

internal actual fun randomToken(): ByteArray = ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes)

/** The transport token's length, `SYNC_PROTOCOL_V1.md` §6. */
private const val TOKEN_BYTES = 32
