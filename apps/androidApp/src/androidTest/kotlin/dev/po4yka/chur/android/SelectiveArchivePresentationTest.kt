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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
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
import dev.po4yka.chur.app.ExportTarget
import dev.po4yka.chur.app.PrivateNotesActions
import dev.po4yka.chur.app.PrivateNotesUiState
import dev.po4yka.chur.app.theme.ChurTheme
import dev.po4yka.chur.app.vault.PrivateNotesScreen
import dev.po4yka.chur.app.vault.SelectiveExportDialog
import dev.po4yka.chur.app.vault.SelectiveImportDialog
import dev.po4yka.chur.ffi.PrivateNoteSelection
import dev.po4yka.chur.ffi.PrivateNoteSummary
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Fictional public inputs through production UI. Native archive behavior has separate JNI tests. */
@RunWith(AndroidJUnit4::class)
class SelectiveArchivePresentationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun empty_mismatch_and_oversized_passwords_refuse_export_and_a_choice_consumes_the_password_once() = present { f ->
        assertTrue(f.await { f.tag("archive-password") != null })
        assertFalse(f.enabled("Save to Files"))
        f.setText("archive-password", PUBLIC_PASSWORD)
        f.setText("archive-password-repeat", "a different public value")
        assertTrue(f.await { f.text("The passwords do not match.") != null })
        assertFalse(f.enabled("Save to Files"))
        f.setText("archive-password-repeat", PUBLIC_PASSWORD)
        assertTrue(f.await { f.enabled("Save to Files") && f.enabled("Share") })
        f.setText("archive-password", "🌍".repeat(257))
        assertTrue(f.await { f.text("Use at most 1024 UTF-8 bytes for the password.") != null })
        assertFalse(f.enabled("Save to Files"))
        f.setText("archive-password", PUBLIC_PASSWORD)
        assertTrue(f.await { f.enabled("Save to Files") })
        // Call the same already-read semantic action twice, before recomposition.
        // Clearing must happen before the host callback, rather than after a frame.
        f.main {
            val action = requireNotNull(f.buttonOnMain("Save to Files")).config[SemanticsActions.OnClick].action!!
            action(); action()
        }
        assertEquals(listOf(PUBLIC_PASSWORD to ExportTarget.FILES), f.choices)
        assertTrue(f.await { f.emptyField("archive-password") && f.emptyField("archive-password-repeat") })
        assertFalse(f.enabled("Share"))
        f.assertNoSavedCredential()
    }

    @Test
    fun narrow_double_font_export_shows_full_actions_and_scrolls_to_password_errors() = present(fontScale = 2f) { f ->
        assertTrue(f.await { f.tag("archive-password") != null })
        f.setText("archive-password", PUBLIC_PASSWORD)
        f.setText("archive-password-repeat", "public mismatch")
        assertTrue(f.await { f.text("The passwords do not match.") != null })
        f.scrollDialog()
        f.assertTextFits("Save to Files")
        f.assertTextFits("Share")
        f.assertTextFits("The passwords do not match.")
        f.assertFieldFitsWidth("archive-password")
        f.assertFieldFitsWidth("archive-password-repeat")
        f.capture("selective-export-mismatch-public-320-font2.png")
        f.setText("archive-password-repeat", PUBLIC_PASSWORD)
        assertTrue(f.await { f.enabled("Share") })
        f.click("Share")
        assertEquals(listOf(PUBLIC_PASSWORD to ExportTarget.SHARE), f.choices)
        f.assertNoSavedCredential()
    }

    @Test
    fun import_discloses_retained_items_and_passwords_are_not_saved_or_restored_after_disposal() =
        present(fontScale = 2f, mode = Mode.IMPORT) { f ->
            assertTrue(f.await { f.tag("archive-import-password") != null })
            assertTrue(f.textContaining("checks the whole archive before adding items") != null)
            assertTrue(f.textContaining("saved originals and notes remain") != null)
            assertFalse(f.enabled("Choose archive"))
            f.setText("archive-import-password", "🌍".repeat(257))
            assertTrue(f.await { f.text("Use at most 1024 UTF-8 bytes for the password.") != null })
            assertFalse(f.enabled("Choose archive"))
            f.setText("archive-import-password", PUBLIC_PASSWORD)
            assertTrue(f.await { f.enabled("Choose archive") })
            f.scrollDialog()
            f.assertTextFits("Choose archive")
            f.assertFieldFitsWidth("archive-import-password")
            f.capture("selective-import-public-320-font2.png")
            f.assertNoSavedCredential()
            f.main { f.show.value = false }
            assertTrue(f.await { f.tag("archive-import-password") == null })
            f.main { f.show.value = true }
            assertTrue(f.await { f.emptyField("archive-import-password") })
            assertFalse(f.enabled("Choose archive"))
            f.setText("archive-import-password", PUBLIC_PASSWORD)
            f.click("Choose archive")
            assertEquals(listOf(PUBLIC_PASSWORD), f.imports)
            assertTrue(f.await { f.emptyField("archive-import-password") })
            f.assertNoSavedCredential()
        }

    @Test
    fun selected_private_notes_offer_an_archive_at_double_font_scale() = present(fontScale = 2f, mode = Mode.NOTES) { f ->
        assertTrue(f.await { f.button("Archive") != null })
        assertTrue(f.button("Lock now") != null)
        f.assertTextFits("Archive")
        f.capture("selective-private-notes-public-320-font2.png", dialog = false)
        f.click("Archive")
        assertTrue(f.await { f.tag("archive-password") != null })
        assertTrue(f.text("2 private notes") != null)
        f.click("Cancel")
        assertTrue(f.await { f.button("Archive") != null })
        assertTrue(f.choices.isEmpty())
    }

    private enum class Mode { EXPORT, IMPORT, NOTES }

    private fun present(fontScale: Float = 1f, mode: Mode = Mode.EXPORT, test: (Fixture) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            val f = Fixture(activity, fontScale, mode)
            f.main {
                val density = activity.resources.displayMetrics.density
                val configuration = Configuration(activity.resources.configuration).apply {
                    screenWidthDp = 320; screenHeightDp = 640; this.fontScale = fontScale
                }
                val context = ContextThemeWrapper(activity, activity.theme).apply { applyOverrideConfiguration(configuration) }
                f.view = ComposeView(context).apply {
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(density, fontScale), LocalConfiguration provides configuration,
                            LocalSaveableStateRegistry provides f.registry) {
                            ChurTheme(dark = false) {
                                when {
                                    !f.show.value -> Unit
                                    f.mode.value == Mode.IMPORT -> SelectiveImportDialog(onChoose = { f.imports += it },
                                        onDismiss = { f.show.value = false })
                                    f.mode.value == Mode.EXPORT -> SelectiveExportDialog(summary = f.summary,
                                        onChoose = { password, target -> f.choices += password to target },
                                        onDismiss = { if (mode == Mode.NOTES) f.mode.value = Mode.NOTES else f.show.value = false })
                                    else -> PrivateNotesScreen(f.notes, f.actions, onLock = {}, onPanic = {})
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

    private inner class Fixture(val activity: MainActivity, val fontScale: Float, initialMode: Mode) {
        val mode = mutableStateOf(initialMode)
        val show = mutableStateOf(true)
        val registry = SaveableStateRegistry(emptyMap()) { true }
        val summary = if (initialMode == Mode.NOTES) "2 private notes" else "3 files; 1 album, including subalbums; 2 private notes"
        val choices = mutableListOf<Pair<String, ExportTarget>>()
        val imports = mutableListOf<String>()
        val note = PrivateNoteSummary(ByteArray(16) { 1 }, 3, "Public fictional note", "A public literal preview", 1, 2, false, null, 45)
        val other = note.copy(noteId = ByteArray(16) { 2 }, revision = 9, title = "Another public note")
        val notes = PrivateNotesUiState(notes = listOf(note, other), selection = listOf(note, other)
            .associate { it.id to PrivateNoteSelection(it.noteId, it.revision) })
        lateinit var view: ComposeView
        val actions = object : PrivateNotesActions {
            override fun refresh() = Unit
            override fun search(terms: String) = Unit
            override fun showTrash(trash: Boolean) = Unit
            override fun nextPage() = Unit
            override fun previousPage() = Unit
            override fun create() = Unit
            override fun open(note: PrivateNoteSummary) = Unit
            override fun title(value: String) = Unit
            override fun body(value: String) = Unit
            override fun pinned(value: Boolean) = Unit
            override fun format(value: dev.po4yka.chur.ffi.PrivateNoteFormat) = Unit
            override fun importMarkdown(title: String, body: String) = Unit
            override fun back() = Unit
            override fun dismissConfirmation() = Unit
            override fun discardAndBack() = Unit
            override fun save() = Unit
            override fun saveAndBack() = Unit
            override fun saveAndExport() = Unit
            override fun export() = Unit
            override fun requestTrash() = Unit
            override fun requestPurge() = Unit
            override fun trash() = Unit
            override fun restore() = Unit
            override fun purge() = Unit
            override fun pin(note: PrivateNoteSummary) = Unit
            override fun exportEncryptedSelection() { mode.value = Mode.EXPORT }
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
        fun nodesOnMain(merged: Boolean = true) = WindowInspector.getGlobalWindowViews().flatMap { it.roots() }
            .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = merged) }
        fun buttonOnMain(value: String) = nodesOnMain().firstOrNull { value in it.labels && SemanticsActions.OnClick in it.config }
        fun button(value: String): SemanticsNode? { var n: SemanticsNode? = null; main { n = buttonOnMain(value) }; return n }
        fun tag(value: String): SemanticsNode? {
            var n: SemanticsNode? = null
            main { n = nodesOnMain().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == value } }
            return n
        }
        fun text(value: String): SemanticsNode? {
            var n: SemanticsNode? = null; main { n = nodesOnMain().firstOrNull { value in it.labels } }; return n
        }
        fun textContaining(value: String): SemanticsNode? {
            var n: SemanticsNode? = null; main { n = nodesOnMain().firstOrNull { it.labels.any { text -> value in text } } }; return n
        }
        fun enabled(value: String): Boolean = button(value)?.config?.contains(SemanticsProperties.Disabled) == false
        fun click(value: String) {
            assertTrue(await { button(value) != null })
            main { assertTrue(requireNotNull(buttonOnMain(value)).config[SemanticsActions.OnClick].action?.invoke() == true) }
        }
        fun setText(tag: String, value: String) = main {
            val field = nodesOnMain().first { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
            assertTrue(field.config.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString(value)) == true)
        }
        fun emptyField(tag: String): Boolean = this.tag(tag)?.config?.getOrNull(SemanticsProperties.EditableText)?.text == ""
        fun assertNoSavedCredential() = main { assertTrue("archive credentials must not enter saved state", registry.performSave().isEmpty()) }
        fun scrollDialog() {
            var target = 0f
            main {
                val scroll = nodesOnMain(merged = false).firstOrNull {
                    SemanticsActions.ScrollBy in it.config && SemanticsProperties.VerticalScrollAxisRange in it.config
                }
                target = requireNotNull(scroll).config[SemanticsProperties.VerticalScrollAxisRange].maxValue()
                assertTrue("dialog text and password fields must scroll", scroll?.config?.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, 10_000f) == true)
            }
            awaitScroll(target)
        }
        // ScrollBy starts a Compose coroutine and returns before its animation.
        // Wait for the actual offset, then fresh layout frames before measuring.
        private fun awaitScroll(target: Float) {
            assertTrue("the dialog must finish scrolling to $target", await {
                var finished = false
                main {
                    val range = nodesOnMain(merged = false).first { SemanticsProperties.VerticalScrollAxisRange in it.config }
                        .config[SemanticsProperties.VerticalScrollAxisRange]
                    finished = abs(range.value() - target.coerceAtMost(range.maxValue())) <= 1f
                }
                finished
            })
            settleFrames()
        }
        private fun settleFrames() {
            val ready = CountDownLatch(1)
            main {
                val target = if (mode.value == Mode.NOTES) view else dialogWindow()
                target.postOnAnimation { target.postOnAnimation { ready.countDown() } }
            }
            assertTrue("two layout frames must complete", ready.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
        }
        private fun reveal(predicate: (SemanticsNode) -> Boolean) {
            settleFrames()
            var target: Float? = null
            main {
                val node = nodesOnMain(merged = false).first(predicate)
                val scroller = generateSequence(node.parent) { it.parent }.firstOrNull {
                    SemanticsActions.ScrollBy in it.config && SemanticsProperties.VerticalScrollAxisRange in it.config
                }
                if (scroller != null) {
                    val viewport = scroller.boundsInWindow
                    // positionInWindow and size are un-clipped; boundsInWindow
                    // is clipped and can be Rect.Zero while the node is offscreen.
                    val top = node.positionInWindow.y
                    val bottom = top + node.size.height
                    val delta = when {
                        top < viewport.top -> top - viewport.top
                        bottom > viewport.bottom -> bottom - viewport.bottom
                        else -> 0f
                    }
                    val range = scroller.config[SemanticsProperties.VerticalScrollAxisRange]
                    target = (range.value() + delta).coerceIn(0f, range.maxValue())
                    if (abs(delta) > 1f) assertTrue(scroller.config[SemanticsActions.ScrollBy].action?.invoke(0f, delta) == true)
                }
            }
            target?.let(::awaitScroll)
        }
        fun assertTextFits(value: String) {
            reveal { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true }
            main {
            val node = nodesOnMain(merged = false).first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true }
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) == true)
            assertTrue(layouts.isNotEmpty())
            layouts.forEach { layout ->
                val metrics = "text=$value, clipped=${node.boundsInWindow}, position=${node.positionInWindow}, " +
                    "nodeSize=${node.size}, layout=${layout.size}, constraints=${layout.layoutInput.constraints}, " +
                    "font=${layout.layoutInput.style.fontSize}, scale=${layout.layoutInput.density.fontScale}, " +
                    "parent=${node.parent?.boundsInWindow}"
                assertEquals(fontScale, layout.layoutInput.density.fontScale, 0.01f)
                assertEquals(value.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
                for (line in 0 until layout.lineCount) {
                    assertFalse(layout.isLineEllipsized(line))
                    assertTrue("full line left must fit; $metrics", layout.getLineLeft(line) >= -1f)
                    assertTrue("full line right=${layout.getLineRight(line)} must fit; $metrics", layout.getLineRight(line) <= node.boundsInWindow.width + 1f)
                }
                for (offset in value.indices) {
                    val box = layout.getBoundingBox(offset)
                    assertTrue("glyph $offset must fit horizontally; $metrics", box.left >= -1f && box.right <= node.boundsInWindow.width + 1f)
                    assertTrue("glyph $offset must fit vertically; $metrics", box.top >= -1f && box.bottom <= node.boundsInWindow.height + 1f)
                }
            }
            }
        }
        fun assertFieldFitsWidth(tag: String) {
            reveal { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
            main {
            val field = nodesOnMain().first { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
            val target = dialogWindow()
            val metrics = "field=$tag, clipped=${field.boundsInWindow}, position=${field.positionInWindow}, " +
                "size=${field.size}, parent=${field.parent?.boundsInWindow}, dialog=${target.width}x${target.height}"
            assertTrue("the field must be visible; $metrics", field.boundsInWindow.width > 0f)
            assertTrue("the whole field width must be visible; $metrics", field.boundsInWindow.width >= field.size.width - 1f)
            assertTrue("the whole field height must be reachable; $metrics", field.boundsInWindow.height >= field.size.height - 1f)
            assertTrue("the field must fit the dialog width; $metrics", field.boundsInWindow.left >= -1f && field.boundsInWindow.right <= target.width + 1f)
            }
        }
        private fun dialogWindow(): View = WindowInspector.getGlobalWindowViews().first { root -> root.roots()
            .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false) }
            .any { node -> node.labels.any { it == "Create encrypted archive" || it == "Import encrypted archive" } } }
        fun capture(name: String, dialog: Boolean = true) {
            val ready = CountDownLatch(1)
            val bounds = Rect()
            main {
                (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(view.windowToken, 0)
                val target = if (dialog) dialogWindow() else view
                target.invalidateTree(); target.requestLayout()
                target.postOnAnimation { target.invalidateTree(); target.postOnAnimation {
                    val point = IntArray(2); target.getLocationOnScreen(point)
                    bounds.set(point[0], point[1], point[0] + target.width, point[1] + target.height)
                    ready.countDown()
                } }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            val screen = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                assertTrue(bounds.intersect(0, 0, screen.width, screen.height))
                val crop = Bitmap.createBitmap(screen, bounds.left, bounds.top, bounds.width(), bounds.height())
                try {
                    val colors = mutableSetOf<Int>()
                    for (y in 0 until crop.height step 4) for (x in 0 until crop.width step 4) colors += crop.getPixel(x, y)
                    assertTrue("the public fixture capture must not be blank", colors.size > 16)
                    File(requireNotNull(activity.getExternalFilesDir(null)), name).outputStream().use {
                        assertTrue(crop.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { if (crop !== screen) crop.recycle() }
            } finally { screen.recycle() }
        }
    }
    private val SemanticsNode.labels get() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
        config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    private fun View.roots(): List<ViewRootForTest> = buildList {
        if (this@roots is ViewRootForTest) add(this@roots)
        if (this@roots is ViewGroup) for (i in 0 until childCount) addAll(getChildAt(i).roots())
    }
    private fun View.invalidateTree() { invalidate(); if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).invalidateTree() }
    private companion object { const val PUBLIC_PASSWORD = "public fictional archive password" }
}
