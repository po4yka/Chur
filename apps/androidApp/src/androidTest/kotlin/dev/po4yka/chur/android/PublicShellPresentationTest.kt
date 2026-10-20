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
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.PublicShell
import dev.po4yka.chur.app.notes.NotesScreen
import dev.po4yka.chur.app.notes.Disclosure
import dev.po4yka.chur.app.notes.PublicSettingsScreen
import dev.po4yka.chur.app.publicshell.CalculatorScreen
import dev.po4yka.chur.app.publicshell.CameraScreen
import dev.po4yka.chur.app.publicshell.PUBLIC_CAMERA_DISCLOSURE
import dev.po4yka.chur.app.publicshell.PublicCameraPhoto
import dev.po4yka.chur.app.theme.ChurTheme
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Public fictional state through the shared renderer. Native capture/storage has separate tests. */
@RunWith(AndroidJUnit4::class)
class PublicShellPresentationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun spirit_is_opt_in_and_suppressed_during_search_disclosure_and_dialogs() {
        val query = mutableStateOf("")
        val disclosure = mutableStateOf(false)
        val companion = "Chur companion. Drag to move, activate to greet."
        present(content = { NotesScreen(emptyList(), query.value, { query.value = it }, {}, {}, {},
            showFirstWriteDisclosure = disclosure.value) }) { f ->
            assertFalse(f.has(companion))
            f.click("Show Chur")
            assertTrue(f.await { f.has(companion) })
            f.click("Folders and tags")
            assertTrue(f.await { !f.has(companion) })
            f.click("Close")
            assertTrue(f.await { f.has(companion) })
            instrumentation.runOnMainSync { query.value = "search" }
            assertTrue(f.await { !f.has(companion) })
            instrumentation.runOnMainSync { query.value = ""; disclosure.value = true }
            assertTrue(f.await { !f.has(companion) })
            instrumentation.runOnMainSync { disclosure.value = false }
            assertTrue(f.await { f.has(companion) })
            f.click("Hide Chur")
            assertTrue(f.await { !f.has(companion) })
        }
    }

    @Test
    fun narrow_double_font_calculator_keeps_keys_result_and_settings_reachable() {
        var settings = 0
        present(content = { CalculatorScreen(onOpenSettings = { settings++ }) }) { f ->
            for (key in listOf("2", "Add", "3", "Equals")) f.click(key)
            assertTrue(f.await { f.has("Calculator result: 5") })
            f.reveal("Calculator result: 5")
            f.capture("public-calculator-result-320-font2.png")
            f.click("Settings")
            assertEquals(1, settings)
            f.assertLabelFits("Calculator")
            f.reveal("Equals")
            f.assertLabelFits("=")
            f.capture("public-calculator-keys-320-font2.png")
        }
    }

    @Test
    fun public_selector_has_all_shells_and_a_visible_vault_entry_at_double_font() {
        val selected = mutableStateOf(PublicShell.NOTES)
        var opens = 0
        var backs = 0
        present(content = { PublicSettingsScreen(onBack = { backs++ }, onOpenVault = { opens++ },
            selectedShell = selected.value, onSelectShell = { selected.value = it }) }) { f ->
            for (shell in PublicShell.entries) {
                f.click(shell.label)
                assertEquals(shell, selected.value)
                assertTrue(f.await { f.isSelected(shell.label) })
            }
            f.capture("public-shell-selector-320-font2.png")
            f.click(Disclosure.VAULT_ENTRY)
            assertEquals(1, opens)
            f.assertLabelFits(Disclosure.VAULT_ENTRY)
            f.capture("public-shell-vault-entry-320-font2.png")
            assertTrue(f.has(PUBLIC_CAMERA_DISCLOSURE))
            f.click("Back")
            assertEquals(1, backs)
        }
    }

    @Test
    fun camera_requires_public_confirmation_and_explicit_save_and_delete() {
        val capture = mutableStateOf(false)
        val selected = mutableStateOf<String?>(null)
        val acknowledged = mutableStateOf(false)
        val photos = mutableStateOf(listOf(PublicCameraPhoto("sample", "Public sample photo")))
        // UI-owned pixels stay alive for pending Compose frames. This is not a native capture.
        val preview = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFF2764A5.toInt())
        }.asImageBitmap()
        var captures = 0
        var saves = 0
        var discards = 0
        var settings = 0
        var deletes = 0
        present(content = {
            CameraScreen(preview = preview, hasCapture = capture.value, busy = false, message = null,
                showFirstWriteDisclosure = !acknowledged.value,
                onAcknowledgeDisclosure = { acknowledged.value = true },
                onCapture = { captures++; capture.value = true },
                onSave = { saves++; capture.value = false },
                onDiscard = { discards++; capture.value = false }, onOpenSettings = { settings++ },
                photos = photos.value, selectedPhotoId = selected.value,
                onOpenPhoto = { selected.value = it }, onClosePhoto = { selected.value = null },
                onDeletePhoto = { deletes++; photos.value = photos.value.filterNot { p -> p.id == it }; selected.value = null })
        }) { f ->
            assertTrue(f.await { f.has(PUBLIC_CAMERA_DISCLOSURE) })
            f.click("Settings")
            assertEquals(1, settings)
            f.click("Take photo")
            assertTrue(f.await { f.has("Public camera") })
            assertEquals(0, captures)
            f.capture("public-camera-disclosure-320-font2.png", dialogTitle = "Public camera")
            f.click("Cancel")
            assertEquals(0, captures)
            assertFalse(acknowledged.value)
            f.click("Take photo")
            f.click("Continue")
            assertEquals(1, captures)
            assertTrue(acknowledged.value)
            assertEquals(0, saves)
            f.reveal("Save photo")
            f.assertLabelFits("Save photo")
            f.capture("public-camera-review-320-font2.png")
            f.click("Settings")
            assertEquals(2, settings)
            f.click("Save photo")
            assertEquals(1, saves)
            assertEquals(0, discards)
            f.click("Public sample photo")
            assertEquals("sample", selected.value)
            f.click("Delete photo")
            assertTrue(f.await { f.has("Delete public photo?") })
            f.click("Cancel")
            assertEquals(0, deletes)
            assertEquals(1, photos.value.size)
            f.click("Delete photo")
            f.capture("public-camera-delete-320-font2.png", dialogTitle = "Delete public photo?")
            f.click("Delete")
            assertEquals(1, deletes)
            assertTrue(photos.value.isEmpty())
        }
    }

    private fun present(content: @Composable () -> Unit, test: (Fixture) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            val f = Fixture(activity)
            f.main {
                val density = activity.resources.displayMetrics.density
                val config = Configuration(activity.resources.configuration).apply {
                    screenWidthDp = 320; screenHeightDp = 640; fontScale = 2f
                }
                val context = ContextThemeWrapper(activity, activity.theme).apply { applyOverrideConfiguration(config) }
                f.view = ComposeView(context).apply {
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(density, 2f), LocalConfiguration provides config) {
                            ChurTheme(dark = false, content = content)
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

    private inner class Fixture(val activity: MainActivity) {
        lateinit var view: ComposeView
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
        private fun nodes(merged: Boolean = true) = WindowInspector.getGlobalWindowViews().flatMap { it.roots() }
            .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = merged) }
        private fun find(label: String) = nodes().firstOrNull { label in it.labels }
        fun has(label: String): Boolean { var found = false; main { found = find(label) != null }; return found }
        fun isSelected(label: String): Boolean {
            var selected = false
            main { selected = find(label)?.config?.getOrNull(SemanticsProperties.Selected) == true }
            return selected
        }
        fun reveal(label: String) {
            assertTrue("missing public control: $label", await { has(label) })
            repeat(8) {
                var visible = false
                main {
                    val node = requireNotNull(find(label))
                    val top = node.positionInWindow.y
                    val bottom = top + node.size.height
                    val position = IntArray(2); view.getLocationOnScreen(position)
                    val low = position[1] + view.height - 1f
                    val high = position[1] + if (label == "Settings" || label == "Back" || label == "Calculator") 0f
                        else 64 * view.resources.displayMetrics.density
                    visible = top >= high && bottom <= low && bottom > top
                    if (!visible) {
                        val scroll = nodes().firstOrNull { SemanticsActions.ScrollBy in it.config &&
                            it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
                        val delta = (top + bottom) / 2f - (high + low) / 2f
                        scroll?.config?.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, delta)
                    }
                }
                if (visible) return
                frames()
            }
            throw AssertionError("public control did not scroll into view: $label")
        }
        fun click(label: String) {
            reveal(label)
            main {
                val node = nodes().first { label in it.labels && SemanticsActions.OnClick in it.config }
                assertTrue(node.config[SemanticsActions.OnClick].action?.invoke() == true)
            }
        }
        fun assertLabelFits(label: String) = main {
            val node = nodes(false).first { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true &&
                SemanticsActions.GetTextLayoutResult in n.config }
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) == true)
            assertTrue(layouts.isNotEmpty())
            for (layout in layouts) {
                val metrics = "label=$label, node=${node.boundsInWindow}, position=${node.positionInWindow}, " +
                    "nodeSize=${node.size}, layout=${layout.size}, constraints=${layout.layoutInput.constraints}, " +
                    "paragraphWidth=${layout.multiParagraph.width}, lines=${layout.lineCount}, " +
                    "widthOverflow=${layout.didOverflowWidth}, heightOverflow=${layout.didOverflowHeight}, " +
                    "font=${layout.layoutInput.style.fontSize}, scale=${layout.layoutInput.density.fontScale}"
                assertEquals("actual font scale; $metrics", 2f, layout.layoutInput.density.fontScale, 0.01f)
                // ui-text can retain a paragraph constraint wider than its wrapped Text node.
                // Compare visible lines and glyphs with the node, as DocumentPresentationTest does.
                assertEquals("every character must remain visible; $metrics", label.length,
                    layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
                for (line in 0 until layout.lineCount) {
                    assertFalse("the label must not be ellipsized; $metrics", layout.isLineEllipsized(line))
                    assertTrue("line $line must start inside the node; $metrics", layout.getLineLeft(line) >= -1f)
                    assertTrue("line $line must end inside the node; $metrics",
                        layout.getLineRight(line) <= node.boundsInWindow.width + 1f)
                }
                for (offset in label.indices) {
                    val box = layout.getBoundingBox(offset)
                    assertTrue("glyph $offset must fit horizontally: $box; $metrics",
                        box.left >= -1f && box.right <= node.boundsInWindow.width + 1f)
                    assertTrue("glyph $offset must fit vertically: $box; $metrics",
                        box.top >= -1f && box.bottom <= node.boundsInWindow.height + 1f)
                }
                assertFalse("the full height must fit; $metrics", layout.didOverflowHeight)
                assertTrue("the text node must not clip horizontally; $metrics",
                    layout.size.width <= node.boundsInWindow.width + 1f)
                assertTrue("the text node must not clip vertically; $metrics",
                    layout.size.height <= node.boundsInWindow.height + 1f)
            }
        }
        private fun frames() {
            val ready = CountDownLatch(1)
            main { view.postOnAnimation { view.postOnAnimation { ready.countDown() } } }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
        }
        fun capture(name: String, dialogTitle: String? = null) {
            frames()
            val bounds = Rect()
            main {
                val target = if (dialogTitle == null) view else WindowInspector.getGlobalWindowViews().first { root ->
                    root.roots().flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false) }
                        .any { dialogTitle in it.labels }
                }
                val point = IntArray(2); target.getLocationOnScreen(point)
                bounds.set(point[0], point[1], point[0] + target.width, point[1] + target.height)
            }
            // A dismissed Android dialog can keep its compositor surface during the window
            // animation after Compose is idle. Require stable public pixels, not a fixed delay.
            val deadline = SystemClock.uptimeMillis() + 5_000
            var previous: Bitmap? = null
            var unchangedSince = 0L
            var settled = false
            try {
                while (SystemClock.uptimeMillis() < deadline) {
                    frames()
                    val screen = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    val current = try {
                        assertTrue(bounds.intersect(0, 0, screen.width, screen.height))
                        Bitmap.createBitmap(screen, bounds.left, bounds.top, bounds.width(), bounds.height())
                    } catch (error: Throwable) {
                        screen.recycle()
                        throw error
                    }
                    // createBitmap can return the same bitmap for a full-screen crop.
                    if (current !== screen) screen.recycle()
                    val now = SystemClock.uptimeMillis()
                    if (previous?.sameAs(current) == true) {
                        if (unchangedSince == 0L) unchangedSince = now
                    } else unchangedSince = 0L
                    previous?.recycle()
                    previous = current
                    if (unchangedSince != 0L && now - unchangedSince >= 200L) {
                        settled = true
                        break
                    }
                }
                assertTrue("public fixture pixels must finish animating before capture: $name", settled)
                val crop = requireNotNull(previous)
                val colors = mutableSetOf<Int>()
                for (y in 0 until crop.height step 4) for (x in 0 until crop.width step 4) colors += crop.getPixel(x, y)
                assertTrue("public fixture must render", colors.size > 16)
                File(requireNotNull(activity.getExternalFilesDir(null)), name).outputStream().use {
                    assertTrue(crop.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { previous?.recycle() }
        }
    }
    private val SemanticsNode.labels get() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
        config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    private fun View.roots(): List<ViewRootForTest> = buildList {
        if (this@roots is ViewRootForTest) add(this@roots)
        if (this@roots is ViewGroup) for (index in 0 until childCount) addAll(getChildAt(index).roots())
    }
}
