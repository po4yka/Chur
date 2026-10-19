package dev.po4yka.chur.notes

import java.io.File

internal actual fun readNoteFile(path: String): String? {
    return readNoteBlob(path, NoteState.MAX_STORE_BYTES)?.decodeToString(throwOnInvalidSequence = true)
}

/**
 * Writes beside the file and renames over it.
 *
 * `rename` within one filesystem replaces the destination in one step, so a
 * reader sees either the previous file or the new one.
 */
internal actual fun writeNoteFile(path: String, text: String) {
    writeNoteBlob(path, text.encodeToByteArray())
}

internal actual fun readNoteBlob(path: String, maxBytes: Int): ByteArray? {
    val file = File(path)
    if (!file.exists()) return null
    require(file.isFile && file.length() <= maxBytes) { "attachment content is too large" }
    return file.inputStream().use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= maxBytes) { "attachment content is too large" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
}

internal actual fun writeNoteBlob(path: String, bytes: ByteArray) {
    val file = File(path)
    check(file.parentFile?.let { it.isDirectory || it.mkdirs() } != false) { "cannot create attachment directory" }
    val temporary = File(file.parentFile, "${file.name}.tmp")
    try {
        java.io.FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        check(temporary.renameTo(file)) { "cannot store attachment content" }
    } finally {
        temporary.delete()
    }
}

internal actual fun deleteNoteBlob(path: String) {
    val file = File(path)
    check(!file.exists() || file.delete()) { "cannot delete attachment content" }
}
