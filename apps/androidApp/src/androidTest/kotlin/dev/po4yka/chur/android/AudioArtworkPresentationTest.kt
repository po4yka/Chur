package dev.po4yka.chur.android

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.theme.ChurTheme
import dev.po4yka.chur.app.vault.ContentView
import dev.po4yka.chur.app.vault.LibraryTile
import dev.po4yka.chur.app.vault.MediaBrowser
import dev.po4yka.chur.app.vault.ViewerScreen
import dev.po4yka.chur.ffi.ObjectProjection
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Public color artwork through shared UI. Native extraction and playback have separate tests. */
@RunWith(AndroidJUnit4::class)
class AudioArtworkPresentationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun an_audio_viewer_shows_its_cover_and_keeps_viewer_actions_reachable_at_double_font_scale() = present(2f) { f ->
        assertTrue(f.await { f.tag("audio-artwork-image") != null })
        assertTrue(f.button("Lock now") != null)
        f.click("Export")
        assertEquals(1, f.exports)
        f.capture("audio-artwork-viewer-public-320-font2.png")
        assertTrue(f.tag("audio-artwork-image") != null)
    }

    @Test
    fun removing_a_preview_replaces_the_cover_with_a_generic_audio_fallback() = present { f ->
        assertTrue(f.await { f.tag("audio-artwork-image") != null })
        f.main { f.art.value = false }
        assertTrue(f.await { f.tag("audio-artwork-placeholder") != null && f.tag("audio-artwork-image") == null })
        assertFalse(f.labels().any { it == "Decrypting" })
        f.capture("audio-no-artwork-viewer-public.png")
    }

    @Test
    fun audio_cover_and_no_cover_tiles_render_in_grid_and_list_and_concealment_stays_explicit() = present(gallery = true) { f ->
        assertTrue(f.await { f.labels().any { it.startsWith("Audio") } })
        assertTrue("cover art keeps a visible audio marker", f.await { f.unmergedTagCount("audio-tile-kind") == 1 })
        assertTrue("the tile still names the kind once", f.audioTiles().all { it.labels.single().startsWith("Audio,") })
        f.capture("audio-artwork-gallery-grid-public.png")
        f.main { f.concealed.value = true }
        assertTrue(f.await { f.states().any { "Thumbnail hidden" in it } })
        assertEquals("concealment keeps the audio marker", 1, f.unmergedTagCount("audio-tile-kind"))
        f.capture("audio-artwork-gallery-concealed-public.png")
        f.main { f.contentView.value = ContentView.LIST }
        assertTrue("both audio items must occupy full-width list rows", f.await { f.hasAudioListRows() })
        f.capture("audio-artwork-gallery-list-public.png")
    }

    @Test
    fun a_long_press_acknowledges_recognition_before_release_without_a_second_haptic() = present(gallery = true) { f ->
        assertTrue(f.await { f.audioTiles().size == 2 })
        f.pressAudio { _, _, _ ->
            assertTrue("the press is acknowledged before release", f.await { f.hapticEvents.size == 1 })
            assertEquals(listOf(HapticFeedbackType.LongPress), f.hapticEvents.toList())
        }
        assertTrue(f.await { f.selections == 1 })
        assertEquals("release must not acknowledge the press again", 1, f.hapticEvents.size)
        assertEquals(0, f.opens)
    }

    @Test
    fun a_drag_acknowledges_recognition_and_cancellation_has_no_success_haptic() = present(gallery = true) { f ->
        assertTrue(f.await { f.audioTiles().size == 2 })
        f.pressAudio(endAction = MotionEvent.ACTION_CANCEL) { at, x, y ->
            assertTrue(f.await { f.hapticEvents.size == 1 })
            f.pointer(at, MotionEvent.ACTION_MOVE, x + 60f, y + 60f)
        }
        assertEquals(listOf(HapticFeedbackType.LongPress), f.hapticEvents.toList())
        assertEquals(0, f.selections)
        assertEquals(0, f.opens)
    }

    @Test
    fun an_accessibility_selection_acknowledges_entry_and_an_ordinary_click_stays_silent() = present(gallery = true) { f ->
        assertTrue(f.await { f.audioTiles().size == 2 })
        f.main { assertTrue(f.audioTiles().first().config[SemanticsActions.OnClick].action?.invoke() == true) }
        assertEquals(1, f.opens)
        assertTrue(f.hapticEvents.isEmpty())
        f.main { assertTrue(f.audioTiles().first().config[SemanticsActions.OnLongClick].action?.invoke() == true) }
        assertTrue(f.await { f.selections == 1 })
        assertEquals(listOf(HapticFeedbackType.LongPress), f.hapticEvents.toList())
        f.main { assertTrue(f.audioTiles().last().config[SemanticsActions.OnLongClick].action?.invoke() == true) }
        assertTrue(f.await { f.selections == 2 })
        assertEquals("selection changes do not vibrate again", 1, f.hapticEvents.size)
    }

    private fun present(fontScale: Float = 1f, gallery: Boolean = false, test: (Fixture) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            val f = Fixture(activity)
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
                            LocalHapticFeedback provides f.haptics) {
                            ChurTheme(dark = false) {
                                if (gallery) MediaBrowser(tiles = listOf(
                                    LibraryTile(f.audio, if (f.concealed.value) f.mosaic else f.image, thumbnailConcealed = f.concealed.value, selected = f.selected.value),
                                    LibraryTile(f.audio.copy(objectId = ByteArray(16) { 2 }, thumbnailReady = false), null)),
                                    view = f.contentView.value, onOpen = { f.opens++ },
                                    onToggleSelection = { f.selections++; f.selected.value = true })
                                else ViewerScreen(projection = f.audio, pages = listOf(f.audio), thumbnails = emptyMap(),
                                    canLoadMore = false, onLoadMore = {}, onSettled = {}, detail = null,
                                    preview = if (f.art.value) f.image else null, showDetail = false, chromeVisible = true,
                                    onToggleChrome = {}, onBack = {}, onLock = {}, onPanic = {}, onToggleFavorite = {},
                                    onEditTags = {}, onExport = { f.exports++ }, onDelete = {}, onToggleDetail = {})
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

    private inner class Fixture(val activity: MainActivity) {
        val audio = ObjectProjection(objectId = ByteArray(16) { 1 }, primaryStreamId = ByteArray(16) { 3 }, mediaKind = 3,
            captureTimeMs = 0, importTimeMs = 0, captureTimeSubstituted = false, plaintextSize = 1_024,
            width = 0, height = 0, durationMs = 65_000, favorite = false, state = 1, integritySummary = 4, thumbnailReady = true)
        val image = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply {
            setPixels(IntArray(96 * 96) { i -> if (i % 96 < 48) 0xFF2453A6.toInt() else 0xFFE19E36.toInt() }, 0, 96, 0, 0, 96, 96)
        }.asImageBitmap()
        // Fictional already-concealed pixels. Cache/mosaic generation is tested separately.
        val mosaic = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF808080.toInt()) }.asImageBitmap()
        val art = mutableStateOf(true)
        val concealed = mutableStateOf(false)
        val contentView = mutableStateOf(ContentView.GRID)
        val selected = mutableStateOf(false)
        val hapticEvents = java.util.concurrent.CopyOnWriteArrayList<HapticFeedbackType>()
        val haptics = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { hapticEvents += hapticFeedbackType }
        }
        var selections = 0
        var opens = 0
        var exports = 0
        lateinit var view: ComposeView
        fun main(action: () -> Unit) {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) action()
            else instrumentation.runOnMainSync(action)
        }
        fun await(condition: () -> Boolean): Boolean {
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
                if (condition()) return true
                SystemClock.sleep(30)
            }
            return condition()
        }
        private fun nodes() = view.roots().flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) }
        fun tag(value: String): SemanticsNode? {
            var result: SemanticsNode? = null
            main { result = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == value } }
            return result
        }
        fun unmergedTagCount(value: String): Int {
            var count = 0
            main {
                count = view.roots().flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false) }
                    .count { it.config.getOrNull(SemanticsProperties.TestTag) == value }
            }
            return count
        }
        fun button(value: String): SemanticsNode? {
            var result: SemanticsNode? = null
            main { result = nodes().firstOrNull { value in it.labels && SemanticsActions.OnClick in it.config } }
            return result
        }
        fun labels(): List<String> { var values = emptyList<String>(); main { values = nodes().flatMap { it.labels } }; return values }
        fun states(): List<String> { var values = emptyList<String>(); main { values = nodes().mapNotNull { it.config.getOrNull(SemanticsProperties.StateDescription) } }; return values }
        fun audioTiles(): List<SemanticsNode> {
            var result = emptyList<SemanticsNode>()
            main { result = nodes().filter { SemanticsActions.OnClick in it.config && it.labels.any { label -> label.startsWith("Audio") } } }
            return result
        }
        fun pressAudio(endAction: Int = MotionEvent.ACTION_UP, whileHeld: (Long, Float, Float) -> Unit) {
            val tile = audioTiles().first().boundsInRoot
            val location = IntArray(2)
            main { view.getLocationOnScreen(location) }
            val x = location[0] + tile.center.x
            val y = location[1] + tile.center.y
            val at = SystemClock.uptimeMillis()
            pointer(at, MotionEvent.ACTION_DOWN, x, y)
            try { whileHeld(at, x, y) } finally { pointer(at, endAction, x, y) }
        }
        fun pointer(at: Long, action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(at, SystemClock.uptimeMillis(), action, x, y, 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            instrumentation.waitForIdleSync()
        }
        fun hasAudioListRows(): Boolean {
            var result = false
            main {
                val rows = nodes().filter { node ->
                    SemanticsActions.OnClick in node.config && node.labels.any { it.startsWith("Audio") }
                }.map { it.boundsInRoot }.sortedBy { it.top }
                result = rows.size == 2 && rows.all { it.width >= view.width * 0.8f && it.height > 0f } &&
                    kotlin.math.abs(rows[0].left - rows[1].left) <= 1f && rows[1].top >= rows[0].bottom - 1f
            }
            return result
        }
        fun click(value: String) = main {
            val button = nodes().first { value in it.labels && SemanticsActions.OnClick in it.config }
            assertTrue(button.config[SemanticsActions.OnClick].action?.invoke() == true)
        }
        fun capture(name: String) {
            val ready = CountDownLatch(1)
            val bounds = Rect()
            main {
                view.invalidateTree(); view.requestLayout()
                view.postOnAnimation { view.invalidateTree(); view.postOnAnimation {
                    val point = IntArray(2); view.getLocationOnScreen(point)
                    bounds.set(point[0], point[1], point[0] + view.width, point[1] + view.height)
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
                    assertTrue("the public fixture must render", colors.size > 16)
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
}
