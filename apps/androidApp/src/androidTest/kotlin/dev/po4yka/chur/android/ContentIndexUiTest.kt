package dev.po4yka.chur.android

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.Choreographer
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.ContentIndexState
import dev.po4yka.chur.app.ActiveOperation
import dev.po4yka.chur.app.theme.ChurTheme
import dev.po4yka.chur.app.vault.VaultActions
import dev.po4yka.chur.app.vault.VaultDestination
import dev.po4yka.chur.app.vault.VaultShell
import dev.po4yka.chur.app.vault.VaultUiState
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Production Search UI at 320 dp and double font. Counts and search terms are public fixtures. */
@RunWith(AndroidJUnit4::class)
class ContentIndexUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun search_starts_local_index_shows_partial_retry_progress_and_stops_once() = present { f ->
        assertTrue(f.await { f.has("Local content search") })
        assertTrue(f.has("Search"))
        assertFalse(f.has(INDEX_DESCRIPTION))
        assertFalse(f.has("Index new items and retry failures"))
        f.capture("content-index-collapsed-public-320-font2.png")
        f.click("Local content search")
        assertTrue(f.await { f.has(INDEX_DESCRIPTION) })
        f.reveal("Index new items and retry failures")
        f.assertTextFits("Index new items and retry failures")
        f.capture("content-index-search-public-320-font2.png")
        f.click("Index new items and retry failures")
        assertEquals(listOf("start"), f.actionsTaken)
        assertTrue(f.await { f.has("7 indexed · 2 partial · 1 need retry · Page 3 of 8") })
        f.reveal("Stop indexing")
        f.assertTextFits("7 indexed · 2 partial · 1 need retry · Page 3 of 8")
        f.click("Stop indexing")
        assertTrue(f.await { f.has("Stopping…") })
        assertFalse(f.enabled("Stopping…"))
        assertEquals(listOf("start", "stop"), f.actionsTaken)
        f.main { f.state.value = f.state.value.copy(contentIndex = ContentIndexState(indexed = 7, partial = 2, failed = 1, message = "Indexing stopped.")) }
        assertTrue(f.await { f.has("Indexing stopped.") && f.has("7 indexed · 2 partial · 1 need retry") })
        f.reveal("Indexing stopped.")
        f.capture("content-index-stopped-public-320-font2.png")
        f.assertSearchNotSaved()
    }

    @Test
    fun options_rebuild_all_and_clear_requires_explicit_confirmation_and_cancel_keeps_index() = present { f ->
        f.click("Local content search")
        f.click("Index options")
        assertTrue(f.await { f.has("Content index") })
        f.click("Rebuild all supported items")
        assertEquals(listOf("rebuild"), f.actionsTaken)
        assertTrue(f.await { !f.has("Content index") })
        f.click("Index options")
        f.click("Clear content index")
        assertTrue(f.await { f.has("Clear content index?") })
        val warning = "This removes recognized text from content search. Your files, filenames, captions, and tags remain available. You can rebuild the index later."
        f.reveal(warning)
        f.assertTextFits(warning)
        f.reveal("Cancel")
        f.assertTextFits("Clear content index?")
        f.capture("content-index-clear-public-320-font2.png")
        f.click("Cancel")
        assertTrue(f.await { !f.has("Clear content index?") })
        assertEquals(listOf("rebuild"), f.actionsTaken)
        f.click("Index options")
        f.click("Clear content index")
        f.click("Clear index")
        assertEquals(listOf("rebuild", "clear"), f.actionsTaken)
        assertTrue(f.await { f.has("Content index cleared.") })
        f.assertSearchNotSaved()
    }

    @Test
    fun unavailable_engine_hides_index_commands_and_search_empty_result_is_useful() = present { f ->
        f.main { f.state.value = f.state.value.copy(searchTerms = PUBLIC_SEARCH, contentIndex = ContentIndexState(available = false)) }
        assertTrue(f.await { !f.has("Local content search") })
        assertFalse(f.has("Index new items and retry failures"))
        assertTrue(f.has("No matching items. Try another word or remove a filter."))
        f.assertTextFits("No matching items. Try another word or remove a filter.")
        f.assertSearchNotSaved()
        f.assertTextFits("Clear search")
        f.capture("search-empty-clear-public-320-font2.png")
        f.click("Hide thumbnails")
        assertFalse(f.state.value.thumbnailsConcealed)
        f.click("Hide thumbnails")
        assertTrue(f.state.value.thumbnailsConcealed)
        f.capture("search-privacy-control-public-320-font2.png")
        f.click("Clear search")
        assertTrue(f.await { f.state.value.searchTerms.isEmpty() })
        assertTrue(f.has("Search names, captions, tags, and indexed text."))
        assertFalse(f.has("Clear search"))
    }

    @Test
    fun running_index_and_idle_retry_counts_remain_visible_without_expanding_details() = present { f ->
        f.main { f.state.value = f.state.value.copy(contentIndex = ContentIndexState(running = true, indexed = 7, partial = 2, failed = 1)) }
        assertTrue(f.await { f.has("Stop indexing") })
        assertFalse(f.has(INDEX_DESCRIPTION))
        assertTrue(f.has("7 indexed · 2 partial · 1 need retry"))
        f.main { f.state.value = f.state.value.copy(contentIndex = ContentIndexState(indexed = 7, partial = 2, failed = 1)) }
        assertTrue(f.await { !f.has("Stop indexing") })
        assertTrue(f.has("7 indexed · 2 partial · 1 need retry"))
        assertFalse(f.has(INDEX_DESCRIPTION))
        assertFalse(f.has("Index new items and retry failures"))
    }

    @Test
    fun operation_progress_has_reserved_space_and_keeps_cancel_reachable_at_large_font() = present { f ->
        val operation = ActiveOperation(id = 42, name = "import", processed = 40, total = 100,
            stage = 2, item = 3, items = 12)
        f.main { f.state.value = f.state.value.copy(operation = operation) }
        // The production label intentionally exposes coarse spoken progress, not every byte update.
        assertTrue("spoken progress and Cancel must be available", f.await { f.has(operation.spoken) && f.has("Cancel") })
        f.capture("operation-progress-reserved-public-320-font2.png")
        f.assertTextFits("Search names, captions, tags, and indexed text.")
        f.assertAbove("Search names, captions, tags, and indexed text.", operation.spoken)
        f.click("Cancel")
        assertEquals(listOf("cancel operation"), f.actionsTaken)
        assertTrue(f.await { f.has("Cancelling…") })
        assertFalse(f.enabled("Cancelling…"))
    }

    private fun present(test: (Fixture) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val f = Fixture(activity)
        try {
            f.main {
                val density = activity.resources.displayMetrics.density
                val config = Configuration(activity.resources.configuration).apply { screenWidthDp = 320; screenHeightDp = 640; fontScale = 2f }
                val context = ContextThemeWrapper(activity, activity.theme).apply { applyOverrideConfiguration(config) }
                f.view = ComposeView(context).apply {
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(density, 2f), LocalConfiguration provides config,
                            LocalSaveableStateRegistry provides f.registry) {
                            ChurTheme(dark = false) { VaultShell(f.state.value, f.actions, systemBack = { enabled, onBack ->
                                androidx.activity.compose.BackHandler(enabled = enabled, onBack = onBack)
                            }) }
                        }
                    }
                }
                activity.setContentView(FrameLayout(activity).apply {
                    addView(f.view, FrameLayout.LayoutParams((320 * density).toInt(), (640 * density).toInt()))
                })
            }
            f.frames()
            test(f)
        } finally { f.main { f.view.disposeComposition(); activity.finish() } }
    }

    private inner class Fixture(val activity: MainActivity) {
        lateinit var view: ComposeView
        val registry = SaveableStateRegistry(emptyMap()) { true }
        val state = mutableStateOf(VaultUiState(destination = VaultDestination.SEARCH, contentIndex = ContentIndexState()))
        val actionsTaken = mutableListOf<String>()
        val actions = VaultActions(onDestination = { state.value = state.value.copy(destination = it) },
            onOpen = {}, onToggleSelection = {}, onImport = {}, onSearch = { state.value = state.value.copy(searchTerms = it) },
            onOpenAlbum = {}, onCloseAlbum = {}, onCreateAlbum = {}, onLock = {}, onVerifyAll = {}, onAddRecoverySlot = {}, onChangePassword = {},
            onThumbnailsConcealedChange = { state.value = state.value.copy(thumbnailsConcealed = it) },
            onIndexContent = { actionsTaken += "start"; state.value = state.value.copy(contentIndex = ContentIndexState(running = true, indexed = 7, partial = 2, failed = 1, page = 3, pages = 8)) },
            onCancelContentIndex = { actionsTaken += "stop"; state.value = state.value.copy(contentIndex = state.value.contentIndex.copy(stopping = true)) },
            onRebuildContentIndex = { actionsTaken += "rebuild" },
            onCancelOperation = { actionsTaken += "cancel operation"; state.value = state.value.copy(operation = state.value.operation?.copy(cancelling = true)) },
            onClearContentIndex = { actionsTaken += "clear"; state.value = state.value.copy(contentIndex = ContentIndexState(message = "Content index cleared.")) })
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
        fun enabled(label: String): Boolean { var enabled = false; main { enabled = find(label)?.config?.contains(SemanticsProperties.Disabled) == false }; return enabled }
        fun reveal(label: String) {
            assertTrue("missing control: $label", await { has(label) })
            var target: Pair<Int, Float>? = null
            main {
                val node = requireNotNull(find(label))
                val scroll = generateSequence(node.parent) { it.parent }.firstOrNull { SemanticsActions.ScrollBy in it.config }
                if (scroll != null) {
                    val viewport = scroll.boundsInWindow
                    val top = node.positionInWindow.y
                    val bottom = top + node.size.height
                    val delta = when { top < viewport.top -> top - viewport.top; bottom > viewport.bottom -> bottom - viewport.bottom; else -> 0f }
                    val range = scroll.config[SemanticsProperties.VerticalScrollAxisRange]
                    target = scroll.id to (range.value() + delta).coerceIn(0f, range.maxValue())
                    if (abs(delta) > 1f) assertTrue(scroll.config[SemanticsActions.ScrollBy].action?.invoke(0f, delta) == true)
                }
            }
            target?.let { (id, wanted) -> assertTrue(await {
                var done = false
                main { done = nodes(false).firstOrNull { it.id == id }?.config?.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                    ?.let { abs(it.value() - wanted.coerceAtMost(it.maxValue())) <= 1f } == true }
                done
            }) }
            frames()
        }
        fun click(label: String) {
            reveal(label)
            main {
                val node = requireNotNull(find(label))
                assertFalse("control is disabled: $label", SemanticsProperties.Disabled in node.config)
                assertTrue("control must be fully visible: $label", node.boundsInWindow.width + 2f >= node.size.width && node.boundsInWindow.height + 2f >= node.size.height)
                assertTrue(node.config[SemanticsActions.OnClick].action?.invoke() == true)
            }
            frames()
        }
        fun assertTextFits(label: String) = main {
            val node = nodes(false).first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true }
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) == true)
            assertTrue(layouts.isNotEmpty())
            layouts.forEach { layout ->
                assertEquals(2f, layout.layoutInput.density.fontScale, 0.01f)
                assertEquals(label.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
                for (line in 0 until layout.lineCount) assertFalse(layout.isLineEllipsized(line))
                for (offset in label.indices) {
                    val glyph = layout.getBoundingBox(offset)
                    assertTrue("text must fit: $label", glyph.left >= -1f && glyph.right <= node.size.width + 1f && glyph.top >= -1f && glyph.bottom <= node.size.height + 1f)
                }
            }
        }
        fun assertSearchNotSaved() = main {
            assertFalse("search text must not enter saved state", registry.performSave().toString().contains(PUBLIC_SEARCH))
        }
        fun assertAbove(content: String, progress: String) {
            assertTrue("progress must not cover or clip content", await {
                var separate = false
                main {
                    val contentNode = requireNotNull(find(content))
                    val progressNode = requireNotNull(find(progress))
                    separate = contentNode.boundsInWindow.height + 2f >= contentNode.size.height &&
                        progressNode.boundsInWindow.height + 2f >= progressNode.size.height &&
                        contentNode.boundsInWindow.bottom <= progressNode.boundsInWindow.top
                }
                separate
            })
        }
        fun frames() {
            val ready = CountDownLatch(1)
            main { val choreographer = Choreographer.getInstance(); choreographer.postFrameCallback { choreographer.postFrameCallback { ready.countDown() } } }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); instrumentation.waitForIdleSync()
        }
        fun capture(name: String) {
            frames()
            instrumentation.uiAutomation.waitForIdle(500, 5_000)
            val bounds = Rect()
            main {
                val target = WindowInspector.getGlobalWindowViews().lastOrNull { it.roots().isNotEmpty() } ?: view
                val position = IntArray(2); target.getLocationOnScreen(position)
                bounds.set(position[0], position[1], position[0] + target.width, position[1] + target.height)
            }
            val screen = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                assertTrue(bounds.intersect(0, 0, screen.width, screen.height))
                val crop = Bitmap.createBitmap(screen, bounds.left, bounds.top, bounds.width(), bounds.height())
                try {
                    val colors = mutableSetOf<Int>()
                    for (y in 0 until crop.height step 4) for (x in 0 until crop.width step 4) colors += crop.getPixel(x, y)
                    assertTrue("public fixture must render", colors.size > 16)
                    File(requireNotNull(activity.getExternalFilesDir(null)), name).outputStream().use { assertTrue(crop.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { if (crop !== screen) crop.recycle() }
            } finally { screen.recycle() }
        }
    }
    private val SemanticsNode.labels get() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    private fun View.roots(): List<ViewRootForTest> = buildList {
        if (this@roots is ViewRootForTest) add(this@roots)
        if (this@roots is ViewGroup) for (index in 0 until childCount) addAll(getChildAt(index).roots())
    }
    private companion object {
        const val PUBLIC_SEARCH = "public-search-saved-state-canary"
        const val INDEX_DESCRIPTION = "Local OCR for photos, PDFs, and UTF-8 text. The index stays encrypted. OCR can miss words."
    }
}
