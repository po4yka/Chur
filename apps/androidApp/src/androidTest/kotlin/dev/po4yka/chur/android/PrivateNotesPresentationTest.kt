package dev.po4yka.chur.android

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.PrivateNoteConfirmation
import dev.po4yka.chur.app.PrivateNoteEditorState
import dev.po4yka.chur.app.PrivateNotesActions
import dev.po4yka.chur.app.PrivateNotesUiState
import dev.po4yka.chur.app.theme.ChurTheme
import dev.po4yka.chur.app.vault.LocalPrivatePanic
import dev.po4yka.chur.app.vault.PrivateNotesScreen
import dev.po4yka.chur.ffi.PrivateNote
import dev.po4yka.chur.ffi.PrivateNoteSummary
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Public fictional state through the production renderer; no vault or screen-protection change. */
@RunWith(AndroidJUnit4::class)
class PrivateNotesPresentationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun markdown_preview_tasks_source_and_trash_keep_real_document_content() = present(editor = true) { f ->
        val source = "# Public Markdown sample\n\n- [ ] Public checklist task\n\n| First | Second |\n| --- | --- |\n| one | two |\n\n```kotlin\nval answer = 42\n```\n\n**Bold** and ~~strike~~ with [a link](https://example.invalid)."
        f.main { f.state.value = f.state.value.copy(editor = PrivateNoteEditorState(f.note.copy(body = source,
            format = dev.po4yka.chur.ffi.PrivateNoteFormat.MARKDOWN))) }
        f.click("Preview")
        assertTrue(f.await { f.text("Public Markdown sample") != null })
        f.assertHeadingFits("Public Markdown sample")
        f.assertButtonVisible("Checklist item")
        f.capture("private-notes-markdown-preview-public.png")
        f.click("Checklist item")
        assertTrue(f.await { f.state.value.editor!!.body.contains("- [x] Public checklist task") })
        f.click("Edit source")
        assertTrue(f.await { f.tag("private-note-body") != null })
        assertEquals(source.replace("- [ ]", "- [x]"), f.state.value.editor!!.body)
        f.main {
            val editor = f.state.value.editor!!
            f.state.value = f.state.value.copy(editor = PrivateNoteEditorState(f.note.copy(body = editor.body,
                format = dev.po4yka.chur.ffi.PrivateNoteFormat.MARKDOWN, trashedAtMs = 3)))
        }
        assertTrue(f.await { f.text("Restore") != null && f.text("Public Markdown sample") != null })
        f.capture("private-notes-markdown-trash-public.png")
    }

    @Test
    fun list_editor_failed_save_and_dirty_back_have_explicit_reachable_states() = present { f ->
        assertTrue(f.await { f.text("Public sample title") != null })
        f.capture("private-notes-list-public.png")
        f.click("New private note")
        assertTrue(f.await { f.tag("private-note-body") != null })
        f.setText("private-note-title", "Public draft title")
        f.setText("private-note-body", "<script>literal public draft</script>")
        f.click("Save")
        assertTrue(f.await { f.text("Public fixture: storage unavailable") != null })
        assertEquals("<script>literal public draft</script>", f.state.value.editor!!.body)
        f.capture("private-notes-save-failure-public.png")
        f.click("Back")
        assertTrue(f.await { f.text("Save changes before leaving?") != null })
        assertTrue(f.button("Save and leave") != null)
        assertTrue(f.button("Discard") != null)
        assertTrue(f.button("Keep editing") != null)
        f.capture("private-notes-dirty-back-public.png", dialog = true)
        f.click("Keep editing")
        assertEquals("<script>literal public draft</script>", f.state.value.editor!!.body)
    }

    @Test
    fun undo_then_replace_all_in_one_ui_turn_uses_the_latest_source() = present { f ->
        f.main { f.state.value = f.state.value.copy(editor = PrivateNoteEditorState(f.note, body = "cat")) }
        assertTrue(f.await { f.tag("private-note-body")?.config?.getOrNull(SemanticsProperties.EditableText)?.text == "cat" })
        f.setText("private-note-body", "cat dog")
        f.click("Find")
        assertTrue(f.await { f.tag("private-note-find") != null && f.tag("private-note-replace") != null })
        f.setText("private-note-find", "cat")
        f.setText("private-note-replace", "fox")
        assertTrue(f.await {
            f.tag("private-note-find")?.config?.getOrNull(SemanticsProperties.EditableText)?.text == "cat" &&
                f.tag("private-note-replace")?.config?.getOrNull(SemanticsProperties.EditableText)?.text == "fox"
        })
        // Both real callbacks come from the same composition: no frame or
        // recomposition is allowed between Undo and Replace all.
        f.clickInSameTurn("Undo", "Replace all")
        assertEquals("fox", f.state.value.editor!!.body)
        assertTrue(f.await { f.tag("private-note-body")?.config?.getOrNull(SemanticsProperties.EditableText)?.text == "fox" })
    }

    @Test
    fun private_markdown_format_menu_hides_the_source_and_popup_with_one_action() = present(editor = true) { f ->
        val source = "# Fictional menu source"
        f.main { f.state.value = f.state.value.copy(editor = PrivateNoteEditorState(f.note.copy(body = source,
            format = dev.po4yka.chur.ffi.PrivateNoteFormat.MARKDOWN))) }
        assertTrue(f.await { f.tag("private-note-body")?.config?.getOrNull(SemanticsProperties.EditableText)?.text == source })
        f.click("Format")
        assertTrue(f.await { f.button("Bold") != null && f.button("Lock and hide") != null })
        f.click("Lock and hide")
        assertTrue(f.await { f.text("Public screen") != null && f.button("Bold") == null &&
            f.button("Lock and hide") == null && f.tag("private-note-body") == null })
        assertEquals(1, f.hideCalls)
        assertEquals(null, f.state.value.editor)
    }

    @Test
    fun narrow_double_font_editor_keeps_save_controls_and_long_body_scrollable() = present(fontScale = 2f, editor = true) { f ->
        assertTrue(f.await { f.tag("private-note-body") != null })
        f.assertLabelFits("Save")
        f.assertButtonVisible("Save")
        f.assertButtonVisible("Lock now")
        f.assertBodyVisible(minHeightDp = 160f)
        f.capture("private-notes-editor-public-320-font2.png")
        var scrolled = false
        f.main {
            val body = requireNotNull(f.tagOnMain("private-note-body"))
            val scroll = sequenceOf(body).plus(body.descendants()).firstOrNull { SemanticsActions.ScrollBy in it.config }
            scrolled = scroll?.config?.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, 800f) == true
        }
        assertTrue("the literal note body must have its own vertical scroll", scrolled)
        assertEquals(f.longBody, f.state.value.editor!!.body)
        f.assertBodyVisible(minHeightDp = 160f)
        f.capture("private-notes-editor-scrolled-public-320-font2.png")
        f.scrollEditorToBottom()
        f.capture("private-notes-editor-controls-public-320-font2.png")
        f.assertButtonVisible("Move to Trash")
        f.assertButtonVisible("Export")
        f.assertButtonVisible("Encrypted archive")
        f.assertLabelFits("Move to Trash")
        f.assertLabelFits("Export")
        f.assertLabelFits("Encrypted archive")
        f.assertButtonVisible("Save")
        f.assertButtonVisible("Lock now")
    }

    @Test
    fun private_title_body_and_search_make_accessibility_copy_and_cut_inert() = present(editor = true) { f ->
        assertTrue(f.await { f.tag("private-note-body") != null })
        val before = f.state.value.editor
        for (tag in listOf("private-note-title", "private-note-body")) f.main {
            val field = requireNotNull(f.tagOnMain(tag))
            assertFalse("Copy must be inert", field.config.getOrNull(SemanticsActions.CopyText)?.action?.invoke() == true)
            assertFalse("Cut must be inert", field.config.getOrNull(SemanticsActions.CutText)?.action?.invoke() == true)
        }
        assertEquals(before, f.state.value.editor)
        f.main { f.state.value = f.state.value.copy(editor = null) }
        assertTrue(f.await { f.tag("private-note-search") != null })
        f.main {
            val field = requireNotNull(f.tagOnMain("private-note-search"))
            assertFalse(field.config.getOrNull(SemanticsActions.CopyText)?.action?.invoke() == true)
            assertFalse(field.config.getOrNull(SemanticsActions.CutText)?.action?.invoke() == true)
        }
    }

    private fun present(fontScale: Float = 1f, editor: Boolean = false, test: (Fixture) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            val f = Fixture(activity, editor)
            f.main {
                val density = activity.resources.displayMetrics.density
                val configuration = Configuration(activity.resources.configuration).apply {
                    screenWidthDp = 320; screenHeightDp = 640; this.fontScale = fontScale
                }
                val context = ContextThemeWrapper(activity, activity.theme).apply { applyOverrideConfiguration(configuration) }
                f.view = ComposeView(context).apply {
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(density, fontScale), LocalConfiguration provides configuration) {
                            ChurTheme(dark = false) {
                                if (f.hidden.value) Text("Public screen") else {
                                    CompositionLocalProvider(LocalPrivatePanic provides f::panic) {
                                        PrivateNotesScreen(f.state.value, f.actions, onLock = f::panic, onPanic = f::panic)
                                    }
                                }
                            }
                        }
                    }
                }
                activity.setContentView(FrameLayout(activity).apply {
                    addView(f.view, FrameLayout.LayoutParams((320 * density).toInt(), (640 * density).toInt()))
                })
            }
            test(f)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private inner class Fixture(val activity: MainActivity, editor: Boolean) {
        val hidden = mutableStateOf(false)
        var hideCalls = 0
        fun panic() {
            hideCalls++
            state.value = PrivateNotesUiState()
            hidden.value = true
        }
        val longBody = (1..120).joinToString("\n") { "Public literal line $it — UTF-8 🌍" }
        val note = PrivateNote(ByteArray(16) { 7 }, 1, "Public sample title", "Public sample body", 1, 2, false, null, 40)
        val summary = PrivateNoteSummary(note.noteId, 1, note.title, note.body, 1, 2, false, null, 40)
        val state = mutableStateOf(PrivateNotesUiState(notes = listOf(summary), editor =
            if (editor) PrivateNoteEditorState(note, title = note.title, body = longBody) else null))
        lateinit var view: ComposeView
        val actions = object : PrivateNotesActions {
            override fun refresh() = Unit
            override fun search(terms: String) { state.value = state.value.copy(terms = terms) }
            override fun showTrash(trash: Boolean) { state.value = state.value.copy(trash = trash) }
            override fun nextPage() = Unit
            override fun previousPage() = Unit
            override fun create() { state.value = state.value.copy(editor = PrivateNoteEditorState(), error = null) }
            override fun open(note: PrivateNoteSummary) { state.value = state.value.copy(editor = PrivateNoteEditorState(this@Fixture.note)) }
            override fun title(value: String) { state.value = state.value.copy(editor = state.value.editor!!.copy(title = value)) }
            override fun body(value: String) { state.value = state.value.copy(editor = state.value.editor!!.copy(body = value)) }
            override fun pinned(value: Boolean) { state.value = state.value.copy(editor = state.value.editor!!.copy(pinned = value)) }
            override fun format(value: dev.po4yka.chur.ffi.PrivateNoteFormat) { state.value = state.value.copy(editor = state.value.editor!!.copy(format = value)) }
            override fun importMarkdown(title: String, body: String) { state.value = state.value.copy(editor = PrivateNoteEditorState(title = title, body = body)) }
            override fun back() { state.value = state.value.copy(confirmation = PrivateNoteConfirmation.LEAVE) }
            override fun dismissConfirmation() { state.value = state.value.copy(confirmation = null) }
            override fun discardAndBack() { state.value = state.value.copy(editor = null, confirmation = null) }
            override fun save() { state.value = state.value.copy(error = "Public fixture: storage unavailable") }
            override fun saveAndBack() = save()
            override fun saveAndExport() = save()
            override fun export() = Unit
            override fun requestTrash() = Unit
            override fun requestPurge() = Unit
            override fun trash() = Unit
            override fun restore() = Unit
            override fun purge() = Unit
            override fun pin(note: PrivateNoteSummary) = Unit
        }
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun await(condition: () -> Boolean): Boolean {
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
                if (condition()) return true
                SystemClock.sleep(30)
            }
            return condition()
        }
        private fun nodesOnMain() = WindowInspector.getGlobalWindowViews().flatMap { it.roots() }
            .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) }
        fun tagOnMain(value: String) = nodesOnMain().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == value }
        fun tag(value: String): SemanticsNode? { var n: SemanticsNode? = null; main { n = tagOnMain(value) }; return n }
        fun text(value: String): SemanticsNode? {
            var n: SemanticsNode? = null
            main { n = nodesOnMain().firstOrNull { it.labels.any { label -> value in label } } }
            return n
        }
        fun button(value: String): SemanticsNode? {
            var n: SemanticsNode? = null
            main { n = nodesOnMain().firstOrNull { value in it.labels && SemanticsActions.OnClick in it.config } }
            return n
        }
        fun click(value: String) {
            assertTrue(await { button(value) != null })
            main {
                val node = nodesOnMain().first { value in it.labels && SemanticsActions.OnClick in it.config }
                assertTrue(node.config[SemanticsActions.OnClick].action?.invoke() == true)
            }
        }
        fun clickInSameTurn(vararg values: String) {
            assertTrue(await { values.all { button(it) != null } })
            main {
                val callbacks = values.map { value ->
                    requireNotNull(nodesOnMain().first { value in it.labels && SemanticsActions.OnClick in it.config }
                        .config[SemanticsActions.OnClick].action)
                }
                callbacks.forEach { callback -> assertTrue(callback.invoke()) }
            }
        }
        fun setText(tag: String, value: String) = main {
            val node = requireNotNull(tagOnMain(tag))
            assertTrue(node.config.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString(value)) == true)
        }
        fun assertBodyVisible(minHeightDp: Float) = main {
            val field = requireNotNull(tagOnMain("private-note-body"))
            val bounds = field.boundsInWindow
            val location = IntArray(2).also(view::getLocationInWindow)
            val saveBottom = requireNotNull(nodesOnMain().firstOrNull {
                "Save" in it.labels && SemanticsActions.OnClick in it.config
            }).boundsInWindow.bottom
            val top = maxOf(bounds.top, location[1].toFloat(), saveBottom)
            val bottom = minOf(bounds.bottom, (location[1] + view.height).toFloat())
            val visibleHeight = (bottom - top).coerceAtLeast(0f)
            val density = activity.resources.displayMetrics.density
            assertTrue("body must expose at least ${minHeightDp}dp, observed ${visibleHeight / density}dp",
                visibleHeight >= minHeightDp * density)
            val layouts = mutableListOf<TextLayoutResult>()
            (sequenceOf(field) + field.descendants()).forEach { node ->
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            }
            val layout = requireNotNull(layouts.firstOrNull { it.layoutInput.text.text == longBody })
            assertTrue("the real body must lay out multiple visible lines", layout.lineCount >= 3)
            assertTrue("at least three complete body lines must fit the visible viewport",
                layout.getLineBottom(2) <= visibleHeight - 12f * density)
        }
        fun assertButtonVisible(value: String) = main {
            val node = requireNotNull(nodesOnMain().firstOrNull { value in it.labels && SemanticsActions.OnClick in it.config })
            val location = IntArray(2).also(view::getLocationInWindow)
            val bounds = node.boundsInWindow
            val density = activity.resources.displayMetrics.density
            val axis = tagOnMain("private-note-editor-scroll")?.config?.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
            val candidates = nodesOnMain().filter { value in it.labels && SemanticsActions.OnClick in it.config }
                .joinToString { "id=${it.id}, bounds=${it.boundsInWindow}" }
            val metrics = "bounds=$bounds, minimumHeight=${40f * density}px, density=$density, " +
                "view=(${location[0]},${location[1]},${view.width},${view.height}), " +
                "outerScroll=${axis?.value?.invoke()}/${axis?.maxValue?.invoke()}, candidates=[$candidates]"
            assertTrue("$value must have a real visible touch target; $metrics",
                bounds.width > 0f && bounds.height >= 40f * density)
            assertTrue("$value must fit vertically in the visible screen; $metrics",
                bounds.top >= location[1] - 1f && bounds.bottom <= location[1] + view.height + 1f)
        }
        fun assertHeadingFits(value: String) = main {
            val nodes = view.roots().flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false) }
            val node = nodes.first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true }
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) == true)
            val layout = requireNotNull(layouts.firstOrNull())
            assertTrue("Markdown heading must use reading-scale typography", layout.layoutInput.style.fontSize.value <= 32f)
            assertFalse("Markdown heading must wrap within its viewport", layout.didOverflowWidth)
            assertTrue("the entire Markdown heading must fit its visible viewport", node.boundsInWindow.height + 1f >= layout.size.height)
            assertEquals(value.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
        }
        fun scrollEditorToBottom() {
            main {
                val node = requireNotNull(tagOnMain("private-note-editor-scroll"))
                assertTrue(node.config.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, 20_000f) == true)
            }
            // ScrollBy schedules a coroutine/animation. Looper idle alone can
            // occur between its frames while the footer remains clipped.
            val settled = await {
                var atEnd = false
                main {
                    val axis = tagOnMain("private-note-editor-scroll")?.config
                        ?.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                    if (axis != null) atEnd = axis.maxValue().isFinite() &&
                        kotlin.math.abs(axis.maxValue() - axis.value()) <= 1f
                }
                atEnd
            }
            var position = "missing editor scroll semantics"
            main {
                val axis = tagOnMain("private-note-editor-scroll")?.config
                    ?.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                if (axis != null) position = "${axis.value()}/${axis.maxValue()}px"
            }
            assertTrue("the outer editor scroll must reach its actual end, observed $position", settled)
        }
        fun assertLabelFits(value: String) = main {
            val node = view.roots().flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false) }
                .first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true }
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) == true)
            layouts.forEach { layout ->
                assertEquals(2f, layout.layoutInput.density.fontScale, 0.01f)
                assertEquals(value.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
                for (line in 0 until layout.lineCount) {
                    assertFalse(layout.isLineEllipsized(line))
                    assertTrue(layout.getLineLeft(line) >= -1f)
                    assertTrue(layout.getLineRight(line) <= node.boundsInWindow.width + 1f)
                }
                for (offset in value.indices) {
                    val box = layout.getBoundingBox(offset)
                    assertTrue(box.left >= -1f && box.right <= node.boundsInWindow.width + 1f)
                    assertTrue(box.top >= -1f && box.bottom <= node.boundsInWindow.height + 1f)
                }
            }
        }
        fun capture(name: String, dialog: Boolean = false) {
            val bounds = Rect()
            val ready = CountDownLatch(1)
            main {
                (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(view.windowToken, 0)
                val target = if (dialog) WindowInspector.getGlobalWindowViews().first { root -> root.roots()
                    .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false) }
                    .any { n -> n.labels.any { "Save changes before leaving?" in it } } } else view
                target.invalidateTree(); target.requestLayout()
                target.postOnAnimation { target.invalidateTree(); target.postOnAnimation { ready.countDown() } }
                val position = IntArray(2); target.getLocationOnScreen(position)
                bounds.set(position[0], position[1], position[0] + target.width, position[1] + target.height)
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            val screen = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                assertTrue(bounds.intersect(0, 0, screen.width, screen.height))
                val crop = Bitmap.createBitmap(screen, bounds.left, bounds.top, bounds.width(), bounds.height())
                try {
                    val colors = mutableSetOf<Int>()
                    for (y in 0 until crop.height step 4) for (x in 0 until crop.width step 4) colors.add(crop.getPixel(x, y))
                    assertTrue("public fixture capture must not be blank", colors.size > 16)
                    File(requireNotNull(activity.getExternalFilesDir(null)), name).outputStream().use { assertTrue(crop.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { if (crop !== screen) crop.recycle() }
            } finally { screen.recycle() }
        }
    }
    private val SemanticsNode.labels get() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
        config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    private fun SemanticsNode.descendants(): Sequence<SemanticsNode> = children.asSequence().flatMap { sequenceOf(it) + it.descendants() }
    private fun View.roots(): List<ViewRootForTest> = buildList {
        if (this@roots is ViewRootForTest) add(this@roots)
        if (this@roots is ViewGroup) for (index in 0 until childCount) addAll(getChildAt(index).roots())
    }
    private fun View.invalidateTree() { invalidate(); if (this is ViewGroup) for (index in 0 until childCount) getChildAt(index).invalidateTree() }
}
