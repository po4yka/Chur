package dev.po4yka.chur.android

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.theme.ChurTheme
import dev.po4yka.chur.app.vault.DocumentFallback
import dev.po4yka.chur.app.vault.DocumentTextContent
import dev.po4yka.chur.app.vault.ViewerScreen
import dev.po4yka.chur.ffi.ObjectDetail
import dev.po4yka.chur.ffi.ObjectProjection
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Public synthetic UI only. No vault, private source, or FLAG_SECURE change. */
@RunWith(AndroidJUnit4::class)
class DocumentPresentationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun literal_text_scrolls_without_hiding_chrome_or_switching_library_items() = present { fixture ->
        assertTrue(fixture.await { fixture.hasText("<script>public fixture</script>") })
        assertTrue(fixture.hasDescription("Export"))
        fixture.capture("document-text-public.png")
        fixture.swipe(vertical = true)
        assertTrue("document scrolling must own a vertical drag", fixture.await { fixture.scrollOffset() > 0f })
        assertTrue(fixture.hasDescription("Export"))
        val settled = fixture.selected.value.id
        fixture.swipe(vertical = false)
        assertEquals(settled, fixture.selected.value.id)
        assertTrue("plain text must expose no automatic clipboard action", fixture.nodes().none {
            SemanticsActions.CopyText in it.config
        })
    }

    @Test
    fun empty_and_binary_documents_have_explicit_states_and_an_export_action() = present { fixture ->
        fixture.main { fixture.mode.value = 1 }
        assertTrue(fixture.await { fixture.hasText("Empty text file") })
        assertTrue(fixture.hasDescription("Export"))
        fixture.capture("document-empty-public.png")
        fixture.main { fixture.mode.value = 2 }
        assertTrue(fixture.await { fixture.hasText("This file is not plain UTF-8 text.") })
        assertTrue(fixture.hasText("Use Export below"))
        assertTrue(fixture.hasDescription("Export"))
        fixture.capture("document-binary-fallback-public.png")
    }

    @Test
    fun returning_to_a_photo_restores_the_media_chrome_gesture() = present { fixture ->
        fixture.main { fixture.selected.value = fixture.photo }
        assertTrue(fixture.await { fixture.hasDescription("Hide controls") })
        fixture.tap()
        assertTrue(fixture.await { !fixture.chrome.value })
        fixture.main { fixture.selected.value = fixture.document }
        assertTrue(fixture.await { fixture.hasDescription("Export") })
        assertFalse(fixture.hasDescription("Show controls"))
    }

    @Test
    fun narrow_phone_at_double_font_scale_keeps_document_metadata_and_export_reachable() = present(fontScale = 2f) { fixture ->
        assertEquals(2f, fixture.view.context.resources.configuration.fontScale, 0.01f)
        assertTrue(fixture.await { fixture.hasText("Public fixture.txt") })
        assertTrue(fixture.hasDescription("Export"))
        fixture.assertTextFits("Info")
        fixture.assertTextFits("Tags")
        fixture.capture("document-text-public-320-font2.png")
        fixture.main { fixture.mode.value = 2 }
        assertTrue(fixture.await { fixture.hasText("This file is not plain UTF-8 text.") })
        assertTrue(fixture.hasDescription("Export"))
        fixture.assertTextFits("Info")
        fixture.assertTextFits("Tags")
        fixture.capture("document-fallback-public-320-font2.png")
    }

    private fun present(fontScale: Float = 1f, test: (Fixture) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            val fixture = Fixture(activity)
            fixture.main {
                val density = activity.resources.displayMetrics.density
                val configuration = Configuration(activity.resources.configuration).apply {
                    screenWidthDp = 320
                    screenHeightDp = 640
                    this.fontScale = fontScale
                }
                val context = ContextThemeWrapper(activity, activity.theme).apply { applyOverrideConfiguration(configuration) }
                fixture.view = ComposeView(context).apply {
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(density, fontScale),
                            LocalConfiguration provides configuration) {
                        ChurTheme(dark = true) {
                            val current = fixture.selected.value
                            // Capture each public fixture with a fresh layout tree.
                            // This is presentation, not a loaded-document transition test.
                            key(fixture.mode.value) {
                            ViewerScreen(current, listOf(fixture.document, fixture.photo), emptyMap(), false, {},
                                { fixture.selected.value = it }, fixture.detail, null, false, fixture.chrome.value,
                                { fixture.chrome.value = !fixture.chrome.value }, onBack = {}, onLock = {}, onPanic = {},
                                onToggleFavorite = {}, onEditTags = {}, onExport = {}, onDelete = {}, onToggleDetail = {},
                                document = if (current.mediaKind == 4) { modifier, padding ->
                                    when (fixture.mode.value) {
                                        1 -> DocumentTextContent(current, fixture.detail, "", false, modifier, padding)
                                        2 -> DocumentFallback(current, fixture.detail, "This file is not plain UTF-8 text.", modifier, padding)
                                        else -> DocumentTextContent(current, fixture.detail, fixture.literal, true, modifier, padding)
                                    }
                                } else null,
                            )
                            }
                        }
                        }
                    }
                }
                activity.setContentView(FrameLayout(activity).apply {
                    addView(fixture.view, FrameLayout.LayoutParams((320 * density).toInt(), (640 * density).toInt()))
                })
            }
            test(fixture)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private inner class Fixture(val activity: MainActivity) {
        val document = ObjectProjection(ByteArray(16) { 1 }, ByteArray(16) { 2 }, 4, 0, 0,
            false, 65_536, 0, 0, 0, false, 1, 4, false)
        val photo = document.copy(objectId = ByteArray(16) { 3 }, mediaKind = 1, width = 32, height = 32)
        val detail = ObjectDetail(0, 0, false, 0, 0, 0, 65_536, "text/plain", "Public fixture.txt", "", emptyList())
        val literal = "<script>public fixture</script>\n" + (1..160).joinToString("\n") { "Public line $it: literal read-only UTF-8 text." }
        val selected = mutableStateOf(document)
        val chrome = mutableStateOf(true)
        val mode = mutableStateOf(0)
        lateinit var view: ComposeView
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun await(condition: () -> Boolean): Boolean {
            val until = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < until) {
                if (condition()) return true
                SystemClock.sleep(50)
            }
            return condition()
        }
        fun nodes(): List<SemanticsNode> {
            var result = emptyList<SemanticsNode>()
            main { result = view.roots().flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false) } }
            return result
        }
        fun hasText(value: String) = nodes().any { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { value in it.text } == true }
        fun hasDescription(value: String) = nodes().any { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { value in it } == true ||
                node.config.getOrNull(SemanticsActions.OnClick)?.label == value
        }
        fun scrollOffset(): Float = nodes().mapNotNull { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke() }
            .maxOrNull() ?: 0f
        fun swipe(vertical: Boolean) {
            val location = IntArray(2)
            main { view.getLocationOnScreen(location) }
            val x = location[0] + view.width * .5f
            val y = location[1] + view.height * .65f
            gesture(x, y, if (vertical) x else x - view.width * .35f,
                if (vertical) y - view.height * .3f else y)
        }
        fun tap() {
            val location = IntArray(2)
            main { view.getLocationOnScreen(location) }
            gesture(location[0] + view.width * .5f, location[1] + view.height * .5f,
                location[0] + view.width * .5f, location[1] + view.height * .5f)
        }
        private fun gesture(x: Float, y: Float, endX: Float, endY: Float) {
            val started = SystemClock.uptimeMillis()
            fun event(action: Int, at: Long, px: Float, py: Float) {
                val event = MotionEvent.obtain(started, at, action, px, py, 0)
                try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            }
            event(MotionEvent.ACTION_DOWN, started, x, y)
            repeat(10) { step ->
                val fraction = (step + 1) / 10f
                event(MotionEvent.ACTION_MOVE, started + (step + 1) * 20L,
                    x + (endX - x) * fraction, y + (endY - y) * fraction)
            }
            event(MotionEvent.ACTION_UP, started + 220, endX, endY)
        }
        fun assertTextFits(text: String) {
            assertTrue(await { hasText(text) })
            main {
                val node = view.roots().flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false) }
                    .first { it.config.getOrNull(SemanticsProperties.Text)?.any { label -> label.text == text } == true }
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) == true)
                assertTrue(layouts.isNotEmpty())
                layouts.forEach { layout ->
                    val metrics = "$text: density=${layout.layoutInput.density}, contextScale=${view.context.resources.configuration.fontScale}, " +
                        "font=${layout.layoutInput.style.fontSize}, constraints=${layout.layoutInput.constraints}, size=${layout.size}, " +
                        "paragraphWidth=${layout.multiParagraph.width}, intrinsicWidth=${layout.multiParagraph.intrinsics.maxIntrinsicWidth}, " +
                        "lines=${layout.lineCount}, node=${node.boundsInWindow}, parent=${node.parent?.boundsInWindow}, " +
                        "grandparent=${node.parent?.parent?.boundsInWindow}, view=${view.width}x${view.height}"
                    assertEquals("the actual text layout must use the fixture font scale; $metrics",
                        view.context.resources.configuration.fontScale, layout.layoutInput.density.fontScale, 0.01f)
                    // ui-text TextLayoutResult.didOverflowWidth compares the
                    // paragraph's constraint width with the wrapped node width.
                    // Measure the actual lines and every label character instead.
                    assertEquals("the full label must remain on one line; $metrics", 1, layout.lineCount)
                    assertEquals("every label character must remain visible; $metrics", text.length,
                        layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
                    for (line in 0 until layout.lineCount) {
                        assertFalse("the label must not be ellipsized; $metrics", layout.isLineEllipsized(line))
                        assertTrue("the line must start inside the node; $metrics", layout.getLineLeft(line) >= -1f)
                        assertTrue("the line must end inside the node; $metrics", layout.getLineRight(line) <= layout.size.width + 1f)
                    }
                    for (offset in text.indices) {
                        val box = layout.getBoundingBox(offset)
                        assertTrue("character $offset must fit horizontally; box=$box; $metrics",
                            box.left >= -1f && box.right <= node.boundsInWindow.width + 1f)
                        assertTrue("character $offset must fit vertically; box=$box; $metrics",
                            box.top >= -1f && box.bottom <= node.boundsInWindow.height + 1f)
                    }
                    assertFalse("$text must fit its full height; $metrics", layout.didOverflowHeight)
                    assertTrue("$text must not be clipped vertically; $metrics", layout.size.height <= node.boundsInWindow.height + 1f)
                    assertTrue("$text must not be clipped horizontally; $metrics", layout.size.width <= node.boundsInWindow.width + 1f)
                }
            }
        }
        fun capture(name: String) {
            instrumentation.waitForIdleSync()
            val bounds = Rect()
            val frames = CountDownLatch(1)
            main {
                assertTrue("the public fixture must have size", view.width > 0 && view.height > 0)
                view.invalidateTree()
                view.requestLayout()
                view.postOnAnimation { view.invalidateTree(); view.postOnAnimation { frames.countDown() } }
                val position = IntArray(2)
                view.getLocationOnScreen(position)
                bounds.set(position[0], position[1], position[0] + view.width, position[1] + view.height)
            }
            assertTrue("two compositor frames must complete", frames.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            // Real compositor capture of the public fixture. Production screen
            // protection remains unchanged; an obscured result fails this check.
            val screen = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                assertTrue(bounds.intersect(0, 0, screen.width, screen.height))
                val bitmap = Bitmap.createBitmap(screen, bounds.left, bounds.top, bounds.width(), bounds.height())
                try {
                    val colors = mutableSetOf<Int>()
                    for (y in 0 until bitmap.height step 4) for (x in 0 until bitmap.width step 4) colors.add(bitmap.getPixel(x, y))
                    assertTrue("the public capture must contain rendered content", colors.size > 16)
                    fun bandColors(start: Int, end: Int): Int {
                        val band = mutableSetOf<Int>()
                        for (y in start until end step 2) for (x in 0 until bitmap.width step 2) band.add(bitmap.getPixel(x, y))
                        return band.size
                    }
                    assertTrue("the public capture must include the top controls", bandColors(0, bitmap.height / 6) > 16)
                    assertTrue("the public capture must include the export controls", bandColors(bitmap.height * 4 / 5, bitmap.height) > 16)
                    File(requireNotNull(activity.getExternalFilesDir(null)), name).outputStream().use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { if (bitmap !== screen) bitmap.recycle() }
            } finally { screen.recycle() }
        }
    }
    private fun View.invalidateTree() {
        invalidate()
        if (this is ViewGroup) for (index in 0 until childCount) getChildAt(index).invalidateTree()
    }
    private fun View.roots(): List<ViewRootForTest> = buildList {
        if (this@roots is ViewRootForTest) add(this@roots)
        if (this@roots is ViewGroup) for (index in 0 until childCount) addAll(getChildAt(index).roots())
    }
}
