@file:Suppress("DEPRECATION")

package dev.po4yka.chur.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.semantics.copyText
import androidx.compose.ui.semantics.cutText
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString

/** Clipboard export is an explicit operation, never an editor side effect. */
@Composable
internal fun PrivateNoteInputPolicy(content: @Composable () -> Unit) {
    val clipboard = LocalClipboard.current
    val legacy = LocalClipboardManager.current
    val toolbar = LocalTextToolbar.current
    val readOnly = remember(clipboard) { privateNoteClipboard(clipboard) }
    val readOnlyLegacy = remember(legacy) { PrivateNoteLegacyClipboard(legacy) }
    val pasteToolbar = remember(toolbar) { PrivateNoteTextToolbar(toolbar) }
    CompositionLocalProvider(
        LocalClipboard provides readOnly,
        LocalClipboardManager provides readOnlyLegacy,
        LocalTextToolbar provides pasteToolbar,
    ) {
        Box(Modifier.onPreviewKeyEvent(::blocksPrivateNoteClipboardKey)
            .filterTextContextMenuComponents { item ->
                item.key == TextContextMenuKeys.PasteKey || item.key == TextContextMenuKeys.SelectAllKey
            }) { content() }
    }
}

/** Field-level overrides also make accessibility Copy/Cut inert without removing editing. */
internal fun Modifier.privateNoteTextFieldPolicy(): Modifier =
    onPreviewKeyEvent(::blocksPrivateNoteClipboardKey).semantics {
        copyText { false }
        cutText { false }
    }

internal fun blocksPrivateNoteClipboardKey(event: KeyEvent): Boolean =
    event.key == Key.Copy || event.key == Key.Cut ||
        ((event.isCtrlPressed || event.isMetaPressed) && (event.key == Key.C || event.key == Key.X))

/** Android must retain its platform clipboard subtype for paste availability checks. */
internal expect fun privateNoteClipboard(delegate: Clipboard): Clipboard

internal class PrivateNoteLegacyClipboard(delegate: ClipboardManager) : ClipboardManager by delegate {
    override fun setText(annotatedString: AnnotatedString) = Unit
    override fun setClip(clipEntry: ClipEntry?) = Unit
}

internal class PrivateNoteTextToolbar(private val delegate: TextToolbar) : TextToolbar {
    override val status: TextToolbarStatus get() = delegate.status
    override fun hide() = delegate.hide()
    override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
        delegate.showMenu(rect, onCopyRequested = null, onPasteRequested = onPasteRequested,
            onCutRequested = null, onSelectAllRequested = onSelectAllRequested)
    }
}
