@file:Suppress("DEPRECATION")

package dev.po4yka.chur.app

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.AnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrivateNoteInputPolicyTest {
    @Test
    fun legacy_clipboard_can_paste_without_writing_or_clearing_the_system_clipboard() {
        var writes = 0
        val original = object : ClipboardManager {
            override fun getText() = AnnotatedString("public paste fixture")
            override fun setText(annotatedString: AnnotatedString) { writes += 1 }
            override fun setClip(clipEntry: ClipEntry?) { writes += 1 }
        }
        val guarded = PrivateNoteLegacyClipboard(original)
        assertEquals("public paste fixture", guarded.getText()?.text)
        guarded.setText(AnnotatedString("private synthetic fixture"))
        guarded.setClip(null)
        assertEquals(0, writes, "the guard must not replace or clear a clipboard it does not own")
    }

    @Test
    fun legacy_toolbar_keeps_paste_and_select_all_without_copy_cut_or_autofill() {
        var pasted = 0
        var selected = 0
        var copies = 0
        var cuts = 0
        var hidden = false
        val original = object : TextToolbar {
            override val status = TextToolbarStatus.Shown
            override fun hide() { hidden = true }
            override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?,
                onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
                assertNull(onCopyRequested)
                assertNull(onCutRequested)
                onPasteRequested?.invoke()
                onSelectAllRequested?.invoke()
            }
        }
        val toolbar = PrivateNoteTextToolbar(original)
        toolbar.showMenu(Rect.Zero, { copies += 1 }, { pasted += 1 }, { cuts += 1 }, { selected += 1 })
        assertEquals(1, pasted)
        assertEquals(1, selected)
        assertEquals(0, copies)
        assertEquals(0, cuts)
        assertEquals(TextToolbarStatus.Shown, toolbar.status)
        toolbar.hide()
        assertTrue(hidden)
    }
}
