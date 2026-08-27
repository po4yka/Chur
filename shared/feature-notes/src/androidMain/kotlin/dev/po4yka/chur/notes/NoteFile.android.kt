package dev.po4yka.chur.notes

import java.io.File
import java.io.IOException

internal actual fun readNoteFile(path: String): String? {
    val file = File(path)
    return if (file.isFile) file.readText() else null
}

/**
 * Writes beside the file and renames over it.
 *
 * `rename` within one filesystem replaces the destination in one step, so a
 * reader sees either the previous file or the new one.
 */
internal actual fun writeNoteFile(path: String, text: String) {
    val file = File(path)
    file.parentFile?.mkdirs()
    val temporary = File(file.parentFile, "${file.name}.tmp")
    temporary.writeText(text)
    if (!temporary.renameTo(file)) {
        temporary.delete()
        throw IOException("cannot replace the note file at $path")
    }
}
