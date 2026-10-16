package dev.po4yka.chur.app

import androidx.compose.ui.platform.AndroidClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.nativeClipboardManager

/** Preserve native read metadata while denying writes from every Compose selection path. */
internal actual fun privateNoteClipboard(delegate: Clipboard): Clipboard = object : AndroidClipboard {
    override val clipboardManager get() = delegate.nativeClipboardManager
    override suspend fun getClipEntry(): ClipEntry? = delegate.getClipEntry()
    override suspend fun setClipEntry(clipEntry: ClipEntry?) = Unit
}
