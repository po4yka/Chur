package dev.po4yka.chur.app

import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard

/** Paste is explicit; text selection never writes private note content to UIPasteboard. */
internal actual fun privateNoteClipboard(delegate: Clipboard): Clipboard = object : Clipboard by delegate {
    override suspend fun setClipEntry(clipEntry: ClipEntry?) = Unit
}
