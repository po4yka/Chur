package dev.po4yka.chur.android

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.theme.ChurTheme
import dev.po4yka.chur.app.vault.VaultActions
import dev.po4yka.chur.app.vault.VaultDestination
import dev.po4yka.chur.app.vault.VaultShell
import dev.po4yka.chur.app.vault.VaultUiState
import dev.po4yka.chur.ffi.QuerySort
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Production vault controls with an empty fictional catalog, at 320 dp and double font. */
@RunWith(AndroidJUnit4::class)
class RecentlyAddedPresentationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun browse_opens_recent_with_fixed_order_kind_filters_and_import_then_returns_to_prior_order() = present { f ->
        f.click("Browse")
        f.click("Recently added")
        assertTrue(f.await { f.has("Order: Recently added") })
        assertTrue(f.state.value.recentlyAdded)
        assertTrue(f.has("No recently added media. Import files to see them here."))
        assertEquals("the empty state must offer one Import action", 1, f.clickableCount("Import"))
        assertFalse(f.has("Sort: Oldest capture ▾"))
        f.assertTextFits("Order: Recently added")
        f.capture("recently-added-empty-public-320-font2.png")
        f.click("Photos")
        assertEquals(1, f.state.value.kinds)
        assertTrue(f.await { f.has("No media matches these filters") })
        f.assertTextFits("Clear filters")
        f.click("Clear filters")
        assertEquals(0, f.state.value.kinds)
        assertTrue(f.state.value.recentlyAdded)
        assertEquals("Recently added", f.state.value.libraryScopeTitle)
        assertEquals(QuerySort.CAPTURE_ASC, f.state.value.sort)
        assertFalse(f.has("Clear filters"))
        assertEquals(1, f.clickableCount("Import"))
        f.click("Import")
        assertEquals(1, f.imports)
        f.click("Back")
        assertTrue(f.await { !f.state.value.recentlyAdded && f.has("Sort: Oldest capture ▾") })
        assertEquals(QuerySort.CAPTURE_ASC, f.state.value.sort)
    }

    @Test
    fun system_back_clears_selection_before_leaving_recent_and_browse_all_media_also_leaves_it() = present { f ->
        f.click("Browse"); f.click("Recently added")
        f.main { f.state.value = f.state.value.copy(selectedCount = 2) }
        f.frames()
        f.back()
        assertEquals(0, f.state.value.selectedCount)
        assertTrue(f.state.value.recentlyAdded)
        f.back()
        assertTrue(f.await { !f.state.value.recentlyAdded })
        assertEquals(listOf("selection", "all media"), f.steps)
        f.click("Browse"); f.click("Recently added")
        f.click("Browse"); f.click("All media")
        assertTrue(f.await { !f.state.value.recentlyAdded })
        assertEquals(VaultDestination.LIBRARY, f.state.value.destination)
    }

    private fun present(test: (Fixture) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val f = Fixture(activity)
        try {
            f.main {
                val density = activity.resources.displayMetrics.density
                val configuration = Configuration(activity.resources.configuration).apply {
                    screenWidthDp = 320; screenHeightDp = 640; fontScale = 2f
                }
                val context = ContextThemeWrapper(activity, activity.theme).apply { applyOverrideConfiguration(configuration) }
                f.view = ComposeView(context).apply {
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    setContent {
                        CompositionLocalProvider(LocalConfiguration provides configuration,
                            LocalDensity provides Density(density, 2f)) {
                            ChurTheme(dark = false) {
                                VaultShell(f.state.value, f.actions,
                                    systemBack = { enabled, back -> BackHandler(enabled, back) })
                            }
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
        val state = mutableStateOf(VaultUiState(sort = QuerySort.CAPTURE_ASC))
        val steps = mutableListOf<String>()
        var imports = 0
        val actions = VaultActions(onDestination = { state.value = state.value.copy(destination = it) },
            onOpen = {}, onToggleSelection = {}, onImport = { imports++ }, onSearch = {}, onOpenAlbum = {},
            onCloseAlbum = {}, onCreateAlbum = {}, onLock = {}, onVerifyAll = {}, onAddRecoverySlot = {}, onChangePassword = {},
            onShowRecentlyAdded = { state.value = state.value.copy(recentlyAdded = true, libraryScopeTitle = "Recently added") },
            onShowAllMedia = { steps += "all media"; state.value = state.value.copy(recentlyAdded = false, libraryScopeTitle = null) },
            onKindsChange = { state.value = state.value.copy(kinds = it) },
            onClearSelection = { steps += "selection"; state.value = state.value.copy(selectedCount = 0) })
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
        fun has(label: String): Boolean { var found = false; main { found = nodes().any { label in it.labels } }; return found }
        fun clickableCount(label: String): Int {
            var count = 0
            main { count = nodes().count { label in it.labels && SemanticsActions.OnClick in it.config } }
            return count
        }
        fun click(label: String) {
            assertTrue("control must exist: $label", await { has(label) })
            main {
                val node = nodes().first { label in it.labels && SemanticsActions.OnClick in it.config }
                assertFalse(SemanticsProperties.Disabled in node.config)
                assertTrue(node.config[SemanticsActions.OnClick].action?.invoke() == true)
            }
            frames()
        }
        fun back() {
            main {
                assertTrue(activity.onBackPressedDispatcher.hasEnabledCallbacks())
                activity.onBackPressedDispatcher.onBackPressed()
            }
            frames()
        }
        fun assertTextFits(label: String) = main {
            val node = nodes(false).first { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true &&
                SemanticsActions.GetTextLayoutResult in n.config }
            val results = mutableListOf<TextLayoutResult>()
            assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results) == true)
            results.forEach { layout ->
                assertEquals(2f, layout.layoutInput.density.fontScale, 0.01f)
                assertEquals(label.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
                for (line in 0 until layout.lineCount) assertFalse(layout.isLineEllipsized(line))
                for (offset in label.indices) {
                    val glyph = layout.getBoundingBox(offset)
                    assertTrue("label must fit at large font", glyph.left >= -1f && glyph.right <= node.size.width + 1f &&
                        glyph.top >= -1f && glyph.bottom <= node.size.height + 1f)
                }
            }
        }
        fun frames() {
            val ready = CountDownLatch(1)
            main { view.postOnAnimation { view.postOnAnimation { ready.countDown() } } }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
        }
        fun capture(name: String) {
            frames()
            main {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                try {
                    view.draw(Canvas(bitmap))
                    File(requireNotNull(activity.getExternalFilesDir(null)), name).outputStream().use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { bitmap.recycle() }
            }
        }
    }
    private val SemanticsNode.labels get() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
        config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    private fun View.roots(): List<ViewRootForTest> = buildList {
        if (this@roots is ViewRootForTest) add(this@roots)
        if (this@roots is ViewGroup) for (index in 0 until childCount) addAll(getChildAt(index).roots())
    }
}
